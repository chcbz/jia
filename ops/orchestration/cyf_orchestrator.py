#!/usr/bin/env python3
"""CYF task-scoped parallel-writer orchestration, evidence cache, and notification hook."""

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
import pathlib
import shlex
import shutil
import string
import subprocess
import sys
import tempfile
from contextlib import contextmanager

ROOT = pathlib.Path(__file__).resolve().parents[2]
LEDGER_PATH = ROOT / "docs/implementation/TASKS.yaml"
LEDGER_BEGIN = "# BEGIN CYF_RUNTIME_LEDGER_JSON\n"
LEDGER_END = "# END CYF_RUNTIME_LEDGER_JSON\n"
EVIDENCE_PATH = ROOT / "docs/implementation/EVIDENCE_CACHE.json"
CONTROL_LOCK = pathlib.Path("/tmp/cyf-orchestrator.lock")
EVIDENCE_LOCK = pathlib.Path("/tmp/cyf-evidence-cache.lock")
GRADLE_LOCK = pathlib.Path("/tmp/cyf-gradle.lock")
PROC_MEMINFO = pathlib.Path("/proc/meminfo")
NOTIFICATION_LOCK = pathlib.Path("/tmp/cyf-orchestrator-notify.lock")
NOTIFICATION_OUTBOX = pathlib.Path(
    os.environ.get("CYF_ORCHESTRATOR_NOTIFICATION_OUTBOX", "/var/tmp/cyf-orchestrator-notifications.jsonl")
)
TASK_FIELDS = {"owner", "exact_sha_tree", "current_gate", "blocker", "next_action"}
OWNER_MODES = {"writer", "verifier", "reviewer", "support"}
TERMINAL_GATES = {"accepted", "done", "cancelled"}
WRITER_GATES = {"claimed", "implementing", "targeted_verification", "remediating"}
GRADLE_GATES = {"targeted_verification", "verifying"}
READ_ONLY_MODES = {"verifier", "reviewer", "support"}
MAX_READ_ONLY_AGENTS = 3
HEX = set(string.hexdigits)
FORBIDDEN_BLOCKER_CATEGORIES = {
    "critical_path_preemption",
    "process_preemption",
    "automatic_termination",
    "source_writer_slot_conflict",
    "global_single_writer",
}


def now():
    return dt.datetime.now(dt.timezone.utc).astimezone().isoformat(timespec="seconds")


@contextmanager
def exclusive_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield


def load_json(path, default=None):
    if not path.exists() and default is not None:
        return default
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def load_ledger():
    text = LEDGER_PATH.read_text(encoding="utf-8")
    try:
        body = text.split(LEDGER_BEGIN, 1)[1].split(LEDGER_END, 1)[0]
    except IndexError as exc:
        raise SystemExit("embedded runtime ledger markers are missing") from exc
    lines = body.splitlines()
    if not lines or lines[0] != "runtime_ledger_json: |":
        raise SystemExit("runtime_ledger_json header is invalid")
    payload = "\n".join(line[2:] for line in lines[1:] if line.startswith("  "))
    return json.loads(payload)


def atomic_write(path, value, default_mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    mode = (path.stat().st_mode & 0o777) if path.exists() else default_mode
    fd, temp_name = tempfile.mkstemp(prefix=".{}.".format(path.name), dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temp_name, mode)
        os.replace(temp_name, str(path))
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def append_jsonl(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, ensure_ascii=False, sort_keys=True) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def task(ledger, task_id):
    try:
        return ledger["tasks"][task_id]
    except KeyError as exc:
        raise SystemExit("unknown task: {}".format(task_id)) from exc


def valid_sha(value):
    return isinstance(value, str) and len(value) == 40 and all(char in HEX for char in value)


def validate_ledger(ledger):
    errors = []
    tasks = ledger.get("tasks")
    path = ledger.get("critical_path")
    if not isinstance(tasks, dict) or not tasks:
        return ["tasks must be a non-empty object"]
    if not isinstance(path, list) or len(path) != len(set(path)):
        errors.append("critical_path must be a duplicate-free list")
    for task_id in path or []:
        if task_id not in tasks:
            errors.append("critical_path references unknown task {}".format(task_id))

    writers = []
    read_only = []
    active_agents = {}
    for task_id, item in tasks.items():
        fields = set(item)
        if fields != TASK_FIELDS:
            errors.append(
                "{} fields must be exactly {}; got {}".format(
                    task_id, sorted(TASK_FIELDS), sorted(fields)
                )
            )
        exact = item.get("exact_sha_tree")
        if not isinstance(exact, dict) or set(exact) != {"commit_sha", "tree_sha"}:
            errors.append("{}.exact_sha_tree must contain only commit_sha/tree_sha".format(task_id))
        else:
            for name, value in exact.items():
                if value is not None and not valid_sha(value):
                    errors.append("{}.exact_sha_tree.{} must be null or a full hexadecimal SHA".format(task_id, name))

        gate = item.get("current_gate")
        if not isinstance(gate, str) or not gate.strip():
            errors.append("{}.current_gate must be non-empty".format(task_id))
        owner_value = item.get("owner")
        if owner_value is not None:
            if not isinstance(owner_value, dict) or set(owner_value) != {"agent", "profile", "mode"}:
                errors.append("{}.owner must contain only agent/profile/mode".format(task_id))
            elif owner_value.get("mode") not in OWNER_MODES:
                errors.append("{}.owner.mode is invalid".format(task_id))
            elif not owner_value.get("agent") or not owner_value.get("profile"):
                errors.append("{}.owner agent/profile must be non-empty".format(task_id))
            else:
                mode = owner_value["mode"]
                if mode == "writer":
                    if gate in WRITER_GATES:
                        writers.append(task_id)
                    else:
                        errors.append("{} writer owner requires a writer gate".format(task_id))
                else:
                    read_only.append(task_id)
                if mode == "verifier" and gate not in GRADLE_GATES:
                    errors.append("{} verifier owner requires a verification gate".format(task_id))
                if mode == "reviewer" and gate != "review":
                    errors.append("{} reviewer owner requires current_gate=review".format(task_id))
                if mode == "support" and gate in GRADLE_GATES:
                    errors.append("{} support owner cannot hold a Gradle gate".format(task_id))
                if gate not in TERMINAL_GATES and gate != "deferred":
                    agent = owner_value["agent"]
                    if agent in active_agents:
                        errors.append(
                            "duplicate active Agent {}: {} and {}".format(agent, active_agents[agent], task_id)
                        )
                    else:
                        active_agents[agent] = task_id
        if gate in TERMINAL_GATES or gate in {"deferred", "blocked_root_cause", "waiting_user"}:
            if owner_value is not None:
                errors.append("{} current_gate={} requires owner=null".format(task_id, gate))

        blocker = item.get("blocker")
        if blocker is not None:
            required = {"category", "summary", "consecutive_failures", "attempts"}
            if not isinstance(blocker, dict) or set(blocker) != required:
                errors.append("{}.blocker must be null or contain exactly {}".format(task_id, sorted(required)))
            else:
                category = blocker.get("category")
                count = blocker.get("consecutive_failures")
                attempts = blocker.get("attempts")
                if category in FORBIDDEN_BLOCKER_CATEGORIES:
                    errors.append(
                        "{}.blocker.category={} is forbidden; critical-path conflicts are alert-only"
                        .format(task_id, category)
                    )
                if not isinstance(count, int) or count < 0:
                    errors.append("{}.blocker.consecutive_failures must be a non-negative integer".format(task_id))
                if not isinstance(attempts, list):
                    errors.append("{}.blocker.attempts must be a list".format(task_id))
                else:
                    for index, attempt in enumerate(attempts):
                        if isinstance(attempt, dict) and attempt.get("category") in FORBIDDEN_BLOCKER_CATEGORIES:
                            errors.append(
                                "{}.blocker.attempts[{}].category={} is forbidden; record the actual failure cause"
                                .format(task_id, index, attempt.get("category"))
                            )
                if gate == "blocked_root_cause" and (not isinstance(count, int) or count < 2):
                    errors.append("{} blocked_root_cause requires consecutive_failures >= 2".format(task_id))
        elif gate == "blocked_root_cause":
            errors.append("{} blocked_root_cause requires a blocker matrix".format(task_id))
        if not isinstance(item.get("next_action"), str) or not item["next_action"].strip():
            errors.append("{}.next_action must be non-empty".format(task_id))

    if len(read_only) > MAX_READ_ONLY_AGENTS:
        errors.append("read-only Agent limit exceeded: {} > {}".format(len(read_only), MAX_READ_ONLY_AGENTS))
    return errors


def save_ledger(ledger):
    ledger["updated_at"] = now()
    errors = validate_ledger(ledger)
    if errors:
        raise SystemExit("ledger validation failed:\n- " + "\n- ".join(errors))
    text = LEDGER_PATH.read_text(encoding="utf-8")
    before, rest = text.split(LEDGER_BEGIN, 1)
    _, after = rest.split(LEDGER_END, 1)
    payload = json.dumps(ledger, ensure_ascii=False, indent=2)
    block = (
        LEDGER_BEGIN
        + "runtime_ledger_json: |\n"
        + "".join("  {}\n".format(line) for line in payload.splitlines())
        + LEDGER_END
    )
    mode = LEDGER_PATH.stat().st_mode & 0o777
    fd, temp_name = tempfile.mkstemp(prefix=".{}.".format(LEDGER_PATH.name), dir=str(LEDGER_PATH.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(before + block + after)
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temp_name, mode)
        os.replace(temp_name, str(LEDGER_PATH))
    finally:
        if os.path.exists(temp_name):
            os.unlink(temp_name)


def owner(agent, profile, mode):
    if mode not in OWNER_MODES:
        raise SystemExit("invalid owner mode: {}".format(mode))
    return {"agent": agent, "profile": profile, "mode": mode}


def emit_notification(task_id, event, next_action, details=None):
    identity = {
        "task_id": task_id,
        "event": event,
        "next_action": next_action,
        "details": details or {},
    }
    canonical = json.dumps(identity, ensure_ascii=False, sort_keys=True)
    record = dict(identity)
    record["at"] = now()
    record["notification_id"] = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    is_new = True
    with exclusive_lock(NOTIFICATION_LOCK):
        if NOTIFICATION_OUTBOX.exists():
            with NOTIFICATION_OUTBOX.open("r", encoding="utf-8") as handle:
                for line in handle:
                    try:
                        if json.loads(line).get("notification_id") == record["notification_id"]:
                            is_new = False
                            break
                    except (TypeError, ValueError):
                        continue
        if is_new:
            append_jsonl(NOTIFICATION_OUTBOX, record)
    prefix = "NOTIFY" if is_new else "NOTIFY_REUSED"
    print(prefix + " " + json.dumps(record, ensure_ascii=False, sort_keys=True))

    hook = os.environ.get("CYF_ORCHESTRATOR_NOTIFY_CMD", "").strip()
    if hook and is_new:
        try:
            result = subprocess.run(
                shlex.split(hook),
                input=json.dumps(record, ensure_ascii=False) + "\n",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                universal_newlines=True,
                timeout=30,
                check=False,
            )
            if result.returncode != 0:
                print("NOTIFY_HOOK_FAILED exit={} stderr={}".format(result.returncode, result.stderr[-500:]))
        except Exception as exc:
            print("NOTIFY_HOOK_FAILED exception={}".format(exc))
    return record


def first_active_task(ledger):
    """Return the first incomplete dependency-chain task for status display only."""
    for task_id in ledger["critical_path"]:
        gate = ledger["tasks"][task_id]["current_gate"]
        if gate not in TERMINAL_GATES and gate != "deferred":
            return task_id
    return None


def claim_denial_reason(item):
    """Return why a task cannot acquire its own task-scoped Writer, or None."""
    if item.get("owner") is not None:
        return "task already has an owner"
    gate = item.get("current_gate")
    if gate in TERMINAL_GATES or gate in {
        "deferred",
        "blocked_dependency",
        "blocked_root_cause",
        "review",
        "waiting_user",
        "waiting_user_sha_approval",
    }:
        return "current_gate={} is not claimable".format(gate)
    return None


def cmd_validate(_):
    errors = validate_ledger(load_ledger())
    if errors:
        print("INVALID")
        for error in errors:
            print("- {}".format(error))
        return 1
    print("VALID")
    return 0


def cmd_status(_):
    ledger = load_ledger()
    errors = validate_ledger(ledger)
    if errors:
        raise SystemExit("invalid ledger:\n- " + "\n- ".join(errors))
    for rank, task_id in enumerate(ledger["critical_path"], start=1):
        item = ledger["tasks"][task_id]
        current_owner = item["owner"] or {}
        exact = item["exact_sha_tree"]
        print(
            "{:02d} {} current_gate={} owner={}:{} commit={} tree={}".format(
                rank,
                task_id,
                item["current_gate"],
                current_owner.get("profile", "-"),
                current_owner.get("mode", "-"),
                (exact["commit_sha"] or "-")[:12],
                (exact["tree_sha"] or "-")[:12],
            )
        )
        if item["blocker"]:
            print("   blocker={}: {}".format(item["blocker"].get("category"), item["blocker"].get("summary")))
        print("   next={}".format(item["next_action"]))
    return 0


def cmd_next(_):
    ledger = load_ledger()
    task_id = first_active_task(ledger)
    if task_id is None:
        print("NO_RUNNABLE_TASK")
        return 0
    print(json.dumps({"task_id": task_id, **ledger["tasks"][task_id]}, ensure_ascii=False, indent=2))
    return 0


def cmd_claim(args):
    with exclusive_lock(CONTROL_LOCK):
        ledger = load_ledger()
        item = task(ledger, args.task_id)
        denial = claim_denial_reason(item)
        if denial:
            raise SystemExit("claim denied for {}: {}".format(args.task_id, denial))
        if item["current_gate"] == "blocked_root_cause" or (
            item.get("blocker") and item["blocker"].get("consecutive_failures", 0) >= 2
        ):
            raise SystemExit("claim denied: root-cause stop is active; use authorize-remediation with an approved matrix reference")
        item["owner"] = owner(args.agent, args.profile, "writer")
        item["exact_sha_tree"] = {"commit_sha": args.commit_sha, "tree_sha": args.tree_sha}
        item["current_gate"] = "claimed"
        item["blocker"] = None
        item["next_action"] = args.next_action
        save_ledger(ledger)
    emit_notification(args.task_id, "claimed", args.next_action, {"owner": args.profile})
    return 0


def cmd_authorize_remediation(args):
    with exclusive_lock(CONTROL_LOCK):
        ledger = load_ledger()
        item = task(ledger, args.task_id)
        blocker = item.get("blocker") or {}
        if item["current_gate"] != "blocked_root_cause" or blocker.get("consecutive_failures", 0) < 2:
            raise SystemExit("authorize-remediation requires blocked_root_cause with a completed matrix")
        blocker["summary"] = "{} | authorized remediation: {}".format(blocker.get("summary", ""), args.matrix_ref)
        blocker["consecutive_failures"] = 0
        item["owner"] = owner(args.agent, args.profile, "writer")
        item["exact_sha_tree"] = {"commit_sha": args.commit_sha, "tree_sha": args.tree_sha}
        item["current_gate"] = "remediating"
        item["next_action"] = args.next_action
        save_ledger(ledger)
    emit_notification(
        args.task_id,
        "remediation_authorized",
        args.next_action,
        {"owner": args.profile, "matrix_ref": args.matrix_ref},
    )
    return 0


def cmd_transition(args):
    with exclusive_lock(CONTROL_LOCK):
        ledger = load_ledger()
        item = task(ledger, args.task_id)
        if item["current_gate"] == "blocked_root_cause" and args.gate != "blocked_root_cause":
            raise SystemExit("transition denied: use authorize-remediation; blind continuation from blocked_root_cause is forbidden")
        if args.commit_sha is not None:
            item["exact_sha_tree"]["commit_sha"] = args.commit_sha or None
        if args.tree_sha is not None:
            item["exact_sha_tree"]["tree_sha"] = args.tree_sha or None
        item["current_gate"] = args.gate
        force_clear = args.gate in TERMINAL_GATES or args.gate in {"deferred", "waiting_user", "blocked_root_cause"}
        item["owner"] = None if args.clear_owner or force_clear else owner(args.agent, args.profile, args.mode)
        if args.clear_blocker or args.gate in TERMINAL_GATES:
            item["blocker"] = None
        item["next_action"] = args.next_action
        save_ledger(ledger)
    if args.gate in TERMINAL_GATES:
        event = "completed"
    elif args.gate.startswith("blocked") or args.gate.startswith("failed"):
        event = "abnormality"
    elif args.gate == "waiting_user":
        event = "user_action_required"
    else:
        event = "transition"
    emit_notification(args.task_id, event, args.next_action, {"current_gate": args.gate})
    return 0


def cmd_fail(args):
    with exclusive_lock(CONTROL_LOCK):
        ledger = load_ledger()
        item = task(ledger, args.task_id)
        previous = item.get("blocker") if isinstance(item.get("blocker"), dict) else {}
        count = int(previous.get("consecutive_failures", 0)) + 1
        attempts = list(previous.get("attempts", []))
        attempts.append(
            {
                "at": now(),
                "category": args.category,
                "evidence": args.evidence,
                "root_cause": args.root_cause,
                "remediation": args.remediation,
            }
        )
        item["blocker"] = {
            "category": args.category,
            "summary": args.summary,
            "consecutive_failures": count,
            "attempts": attempts,
        }
        if count >= 2:
            item["current_gate"] = "blocked_root_cause"
            item["owner"] = None
        elif (item.get("owner") or {}).get("mode") == "writer":
            item["current_gate"] = "remediating"
        else:
            item["current_gate"] = "failed_attribution_required"
            item["owner"] = None
        item["next_action"] = args.remediation
        save_ledger(ledger)
    event = "root_cause_matrix_required" if count >= 2 else "failure_attributed"
    emit_notification(args.task_id, event, args.remediation, {"consecutive_failures": count})
    if count >= 2:
        print("ROOT_CAUSE_MATRIX")
        for attempt in item["blocker"]["attempts"]:
            print(
                "- category={} evidence={} root_cause={} remediation={}".format(
                    attempt["category"], attempt["evidence"], attempt["root_cause"], attempt["remediation"]
                )
            )
    return 2 if count >= 2 else 1


def evidence_key(tree_sha, selector, fixture_digest):
    raw = "\0".join((tree_sha, selector, fixture_digest)).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def empty_cache():
    return {"schema_version": 1, "updated_at": now(), "records": {}}


def cmd_evidence_get(args):
    with exclusive_lock(EVIDENCE_LOCK):
        cache = load_json(EVIDENCE_PATH, empty_cache())
        key = evidence_key(args.tree_sha, args.selector, args.fixture_digest)
        record = cache.get("records", {}).get(key)
    if record and record.get("result") == "accepted":
        print("EVIDENCE_HIT")
        print(json.dumps(record, ensure_ascii=False, indent=2))
        return 0
    print("EVIDENCE_MISS")
    return 1


def cmd_evidence_put(args):
    ledger = load_ledger()
    item = task(ledger, args.task_id)
    exact = item["exact_sha_tree"]
    if exact["tree_sha"] != args.tree_sha:
        raise SystemExit("evidence denied: supplied tree does not match task exact_sha_tree")
    current_owner = item.get("owner") or {}
    if args.result == "accepted" and (
        current_owner.get("mode") not in {"writer", "verifier"}
        or item["current_gate"] not in GRADLE_GATES
    ):
        raise SystemExit("accepted evidence requires this task's writer/verifier verification gate")
    key = evidence_key(args.tree_sha, args.selector, args.fixture_digest)
    with exclusive_lock(EVIDENCE_LOCK):
        cache = load_json(EVIDENCE_PATH, empty_cache())
        cache.setdefault("records", {})[key] = {
            "task_id": args.task_id,
            "tree_sha": args.tree_sha,
            "selector": args.selector,
            "fixture_digest": args.fixture_digest,
            "result": args.result,
            "command": args.command,
            "artifact": args.artifact,
            "recorded_at": now(),
        }
        cache["updated_at"] = now()
        atomic_write(EVIDENCE_PATH, cache)
    print("EVIDENCE_STORED key={} reusable={}".format(key, str(args.result == "accepted").lower()))
    return 0


def verify_exact_worktree(cwd, item, supplied_tree):
    requested = pathlib.Path(cwd).resolve()
    try:
        top = pathlib.Path(
            subprocess.check_output(
                ["git", "-C", str(requested), "rev-parse", "--show-toplevel"],
                universal_newlines=True,
                stderr=subprocess.STDOUT,
            ).strip()
        )
        head = subprocess.check_output(
            ["git", "-C", str(top), "rev-parse", "HEAD"], universal_newlines=True
        ).strip()
        tree_sha = subprocess.check_output(
            ["git", "-C", str(top), "rev-parse", "HEAD^{tree}"], universal_newlines=True
        ).strip()
        dirty = subprocess.check_output(
            ["git", "-C", str(top), "status", "--porcelain"], universal_newlines=True
        ).strip()
    except subprocess.CalledProcessError as exc:
        raise SystemExit("Gradle denied: cwd is not a readable Git worktree: {}".format(exc.output))
    exact = item["exact_sha_tree"]
    if dirty:
        raise SystemExit("Gradle denied: exact-tree evidence requires a clean worktree")
    if head != exact["commit_sha"]:
        raise SystemExit("Gradle denied: cwd HEAD does not match task exact commit SHA")
    if tree_sha != supplied_tree or tree_sha != exact["tree_sha"]:
        raise SystemExit("Gradle denied: cwd tree does not match task/supplied exact tree SHA")
    return top


def read_meminfo_resource_bytes():
    values = {}
    for line in PROC_MEMINFO.read_text(encoding="utf-8").splitlines():
        name, separator, raw = line.partition(":")
        if not separator or name not in {"MemAvailable", "SwapFree"}:
            continue
        fields = raw.split()
        if len(fields) != 2 or fields[1] != "kB" or not fields[0].isdigit():
            raise ValueError("invalid /proc/meminfo resource field: {}".format(name))
        values[name] = int(fields[0]) * 1024
    missing = {"MemAvailable", "SwapFree"} - values.keys()
    if missing:
        raise ValueError("missing /proc/meminfo resource fields: {}".format(",".join(sorted(missing))))
    return values


def record_gradle_resource_telemetry(heavy):
    observation = {
        "admission_gate": "disabled_by_user_2026-08-28",
        "disk_available_bytes": None,
        "gradle_label": "heavy" if heavy else "standard",
        "inode_available": None,
        "mem_available_bytes": None,
        "swap_free_bytes": None,
        "telemetry_errors": [],
    }
    try:
        memory = read_meminfo_resource_bytes()
        observation["mem_available_bytes"] = memory["MemAvailable"]
        observation["swap_free_bytes"] = memory["SwapFree"]
    except (OSError, ValueError) as exc:
        observation["telemetry_errors"].append("meminfo:{}".format(type(exc).__name__))
    try:
        observation["disk_available_bytes"] = shutil.disk_usage(ROOT).free
    except OSError as exc:
        observation["telemetry_errors"].append("disk:{}".format(type(exc).__name__))
    try:
        observation["inode_available"] = os.statvfs(ROOT).f_favail
    except OSError as exc:
        observation["telemetry_errors"].append("inode:{}".format(type(exc).__name__))
    print(
        "RESOURCE_OBSERVATION {}".format(json.dumps(observation, sort_keys=True)),
        file=sys.stderr,
    )
    return observation


def gradle_guard(ledger, args):
    item = task(ledger, args.task_id)
    current_owner = item.get("owner") or {}
    if current_owner.get("mode") not in {"writer", "verifier"} or item["current_gate"] not in GRADLE_GATES:
        raise SystemExit("Gradle denied: only this task's writer/verifier at a verification gate may run")
    if item["exact_sha_tree"]["tree_sha"] != args.tree_sha:
        raise SystemExit("Gradle denied: supplied tree does not match task exact_sha_tree")
    return item


def gradle_child_identity(args):
    run_uid = getattr(args, "run_uid", None)
    run_gid = getattr(args, "run_gid", None)
    if run_uid is None and run_gid is None:
        return None
    if run_uid is None or run_gid is None:
        raise SystemExit("Gradle denied: --run-uid and --run-gid must be supplied together")
    if run_uid <= 0 or run_gid <= 0:
        raise SystemExit("Gradle denied: delegated Gradle identity must be non-root")
    if os.geteuid() != 0:
        raise SystemExit("Gradle denied: only root may delegate the Gradle child identity")

    def drop_identity():
        os.setgroups([])
        os.setgid(run_gid)
        os.setuid(run_uid)

    return drop_identity


def cmd_gradle(args):
    command = args.command
    if command and command[0] == "--":
        command = command[1:]
    if not command or "gradle" not in pathlib.Path(command[0]).name:
        raise SystemExit("command must begin with gradle or gradlew")

    child_identity = gradle_child_identity(args)
    record_gradle_resource_telemetry(args.heavy)
    ledger = load_ledger()
    item = gradle_guard(ledger, args)
    cwd = verify_exact_worktree(args.cwd or ROOT / "api", item, args.tree_sha)
    key = evidence_key(args.tree_sha, args.selector, args.fixture_digest)

    with exclusive_lock(GRADLE_LOCK):
        ledger = load_ledger()
        item = gradle_guard(ledger, args)
        cwd = verify_exact_worktree(cwd, item, args.tree_sha)
        with exclusive_lock(EVIDENCE_LOCK):
            cache = load_json(EVIDENCE_PATH, empty_cache())
            cached = cache.get("records", {}).get(key)
        if cached and cached.get("result") == "accepted":
            print("EVIDENCE_HIT key={}; Gradle skipped for unchanged tree/selector/fixture".format(key))
            return 0
        print("GRADLE_LOCK_ACQUIRED task={} pid={}".format(args.task_id, os.getpid()), flush=True)
        result = subprocess.run(
            command,
            cwd=str(cwd),
            check=False,
            preexec_fn=child_identity,
        )
        if result.returncode == 0:
            with exclusive_lock(EVIDENCE_LOCK):
                cache = load_json(EVIDENCE_PATH, empty_cache())
                cache.setdefault("records", {})[key] = {
                    "task_id": args.task_id,
                    "tree_sha": args.tree_sha,
                    "selector": args.selector,
                    "fixture_digest": args.fixture_digest,
                    "result": "accepted",
                    "command": " ".join(command),
                    "artifact": args.artifact,
                    "recorded_at": now(),
                }
                cache["updated_at"] = now()
                atomic_write(EVIDENCE_PATH, cache)
            print("EVIDENCE_STORED key={} reusable=true".format(key))
        else:
            emit_notification(
                args.task_id,
                "abnormality",
                "Run the fail command with evidence/root cause/remediation before any retry.",
                {"current_gate": item["current_gate"], "exit": result.returncode},
            )
    return result.returncode


def cmd_notify(args):
    emit_notification(args.task_id, args.event, args.next_action, {"summary": args.summary})
    return 0


def cmd_flow_remote(args):
    """Dispatch the narrow Flow ticket bridge without changing existing Gradle CLI behavior."""
    import flow_remote

    remote_args = list(args.remote_args)
    if remote_args and remote_args[0] == "--":
        remote_args = remote_args[1:]
    controller = {
        "root": ROOT,
        "control_lock": CONTROL_LOCK,
        "exclusive_lock": exclusive_lock,
        "load_ledger": load_ledger,
        "task": task,
        "gradle_gates": GRADLE_GATES,
    }
    try:
        return flow_remote.dispatch(remote_args, controller=controller, actual_root=ROOT)
    except flow_remote.RemoteError as exc:
        print("FLOW_REMOTE_DENIED: {}".format(exc), file=sys.stderr)
        return 2


def cmd_conflict_alert(args):
    ledger = load_ledger()
    errors = validate_ledger(ledger)
    if errors:
        raise SystemExit("invalid ledger:\n- " + "\n- ".join(errors))
    requesting = task(ledger, args.requesting_task)
    conflicting = task(ledger, args.conflicting_task)
    if args.requesting_task == args.conflicting_task:
        raise SystemExit("conflict-alert requires two distinct tasks")

    details = {
        "summary": args.summary,
        "requesting_task": args.requesting_task,
        "requesting_owner": requesting["owner"],
        "requesting_gate": requesting["current_gate"],
        "requesting_exact_sha_tree": requesting["exact_sha_tree"],
        "conflicting_task": args.conflicting_task,
        "conflicting_owner": conflicting["owner"],
        "conflicting_gate": conflicting["current_gate"],
        "conflicting_exact_sha_tree": conflicting["exact_sha_tree"],
        "policy": "alert_only_no_process_or_evidence_operation",
    }
    emit_notification(args.requesting_task, "coordination_required", args.next_action, details)
    return 0


def build_parser():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="subcommand")
    commands.add_parser("validate").set_defaults(func=cmd_validate)
    commands.add_parser("status").set_defaults(func=cmd_status)
    commands.add_parser("next").set_defaults(func=cmd_next)

    claim = commands.add_parser("claim")
    claim.add_argument("task_id")
    claim.add_argument("--agent", required=True)
    claim.add_argument("--profile", required=True)
    claim.add_argument("--commit-sha", required=True)
    claim.add_argument("--tree-sha", required=True)
    claim.add_argument("--next-action", required=True)
    claim.set_defaults(func=cmd_claim)

    authorize = commands.add_parser("authorize-remediation")
    authorize.add_argument("task_id")
    authorize.add_argument("--agent", required=True)
    authorize.add_argument("--profile", required=True)
    authorize.add_argument("--commit-sha", required=True)
    authorize.add_argument("--tree-sha", required=True)
    authorize.add_argument("--matrix-ref", required=True)
    authorize.add_argument("--next-action", required=True)
    authorize.set_defaults(func=cmd_authorize_remediation)

    transition = commands.add_parser("transition")
    transition.add_argument("task_id")
    transition.add_argument("--gate", required=True)
    transition.add_argument("--agent", default="main_orchestrator")
    transition.add_argument("--profile", default="main_orchestrator")
    transition.add_argument("--mode", choices=sorted(OWNER_MODES), default="writer")
    transition.add_argument("--commit-sha")
    transition.add_argument("--tree-sha")
    transition.add_argument("--next-action", required=True)
    transition.add_argument("--clear-owner", action="store_true")
    transition.add_argument("--clear-blocker", action="store_true")
    transition.set_defaults(func=cmd_transition)

    fail = commands.add_parser("fail")
    fail.add_argument("task_id")
    fail.add_argument("--category", required=True)
    fail.add_argument("--summary", required=True)
    fail.add_argument("--evidence", required=True)
    fail.add_argument("--root-cause", required=True)
    fail.add_argument("--remediation", required=True)
    fail.set_defaults(func=cmd_fail)

    get = commands.add_parser("evidence-get")
    get.add_argument("--tree-sha", required=True)
    get.add_argument("--selector", required=True)
    get.add_argument("--fixture-digest", required=True)
    get.set_defaults(func=cmd_evidence_get)

    put = commands.add_parser("evidence-put")
    put.add_argument("task_id")
    put.add_argument("--tree-sha", required=True)
    put.add_argument("--selector", required=True)
    put.add_argument("--fixture-digest", required=True)
    put.add_argument("--result", choices=["accepted", "failed", "blocked"], required=True)
    put.add_argument("--command", required=True)
    put.add_argument("--artifact", default=None)
    put.set_defaults(func=cmd_evidence_put)

    gradle = commands.add_parser("gradle")
    gradle.add_argument("task_id")
    gradle.add_argument("--heavy", action="store_true")
    gradle.add_argument("--cwd", type=pathlib.Path)
    gradle.add_argument("--tree-sha", required=True)
    gradle.add_argument("--selector", required=True)
    gradle.add_argument("--fixture-digest", required=True)
    gradle.add_argument("--artifact", default=None)
    gradle.add_argument("--run-uid", type=int)
    gradle.add_argument("--run-gid", type=int)
    gradle.add_argument("command", nargs=argparse.REMAINDER)
    gradle.set_defaults(func=cmd_gradle)

    notify = commands.add_parser("notify")
    notify.add_argument("task_id")
    notify.add_argument("--event", choices=["completed", "abnormality", "user_action_required"], required=True)
    notify.add_argument("--summary", required=True)
    notify.add_argument("--next-action", required=True)
    notify.set_defaults(func=cmd_notify)

    flow_remote = commands.add_parser("flow-remote")
    flow_remote.add_argument("remote_args", nargs=argparse.REMAINDER)
    flow_remote.set_defaults(func=cmd_flow_remote)

    conflict = commands.add_parser("conflict-alert")
    conflict.add_argument("requesting_task")
    conflict.add_argument("conflicting_task")
    conflict.add_argument("--summary", required=True)
    conflict.add_argument("--next-action", required=True)
    conflict.set_defaults(func=cmd_conflict_alert)
    return parser


if __name__ == "__main__":
    parser = build_parser()
    args = parser.parse_args()
    if not hasattr(args, "func"):
        parser.print_help()
        raise SystemExit(2)
    raise SystemExit(args.func(args))
