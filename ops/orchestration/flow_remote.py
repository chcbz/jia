#!/usr/bin/env python3
"""Ticket-bound remote Gradle execution for CYF Aliyun Flow.

The mutating ``issue`` and ``run`` commands are intentionally available only
through ``cyf_orchestrator.py flow-remote``.  Remote execution never reads or
writes the controller ledger or evidence cache.
"""

import argparse
import base64
import html
import io
import datetime as dt
import fcntl
import hashlib
import hmac
import json
import os
import pathlib
import re
import secrets
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from xml.sax.saxutils import escape as xml_escape
from contextlib import contextmanager
from urllib.parse import quote, quote_plus, urlsplit

SCHEMA_VERSION = 1
TICKET_KIND = "cyf.flow.gradle.ticket"
RECEIPT_KIND = "cyf.flow.gradle.receipt"
GRADLE_LOCK = pathlib.Path("/tmp/cyf-gradle.lock")
NONCE_DIR = pathlib.Path("/tmp/cyf-flow-nonces")
TICKET_PREFIX = pathlib.Path("/tmp/cyf-flow-tickets")
RUN_PREFIX = pathlib.Path("/tmp/cyf-flow-runs")
TOOL_PREFIX = pathlib.Path("/tmp/cyf-flow-tools")
MAX_TTL_SECONDS = 6 * 60 * 60
EXPECTED_BRANCH = "develop"
EXPECTED_JAVA_MAJOR = 21
EXPECTED_TASKS = [
    ":common:jia-common-core:test",
    "validateLayering",
    ":starter:bootJar",
]
DEFAULT_TEST_FILTER = "cn.jia.core.security.SensitiveDataSanitizerTest"
TOOL_PATHS = [
    "ops/orchestration/cyf_orchestrator.py",
    "ops/orchestration/flow_remote.py",
    "ops/ci/aliyun-flow/run-cloud.sh",
    "ops/ci/aliyun-flow/cold-init.gradle",
]
FLOW_ENVIRONMENT = {
    "organization_id": "CYF_FLOW_ORGANIZATION_ID",
    "pipeline_id": "CYF_FLOW_PIPELINE_ID",
    "run_id": "CYF_FLOW_RUN_ID",
    "job_id": "CYF_FLOW_JOB_ID",
    "source_tip_sha": "CYF_FLOW_SOURCE_TIP_SHA",
}
SAFE_ID = re.compile(r"^[A-Za-z0-9._:-]{1,160}$")
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
NONCE = re.compile(r"^[0-9a-f]{64}$")
TEST_FILTER = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$.]*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?$")
NESTED_GRADLE_PATTERN = (
    r"providers\s*\.\s*exec|project\s*\.\s*exec|"
    r"tasks\s*\.\s*(?:register|create)\s*\([^\n]*\bExec\b|"
    r"\bcommandLine\b[^\n]*(?:gradle|gradlew)|"
    r"\bexecutable\b[^\n]*(?:gradle|gradlew)|\bgradlew\b"
)


MAVEN_AUTH_ENV = ("CYF_MAVEN_USERNAME", "CYF_MAVEN_PASSWORD")
OPENCV_COORDINATE = "org.opencv:opencv:4.5.5"
OPENCV_REPOSITORY = "https://packages.aliyun.com/5fb7d76ee6f9d07f148529c7/maven/2049636-release-r6d3t8"
OPENCV_JAR_SHA256 = "323d40119548134b0966d3735e97a78d1edbc79bd91e7fb1074e5152392095f4"
OPENCV_JAR_SIZE = 722802
OPENCV_CONTROLLER_POM_SHA256 = "786de45db6266b338faf603d0555bdb491357aeece0f9608c3a08d78700dc203"
OPENCV_PROVENANCE_REF = "FLOW-CI-20260909-opencv-provenance.json"


class RemoteError(Exception):
    """Fail-closed validation or packaging error."""


@contextmanager
def exclusive_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+") as handle:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        yield


def utc_now():
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def format_utc(value):
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_utc(value):
    if not isinstance(value, str):
        raise RemoteError("timestamp must be a string")
    try:
        return dt.datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)
    except ValueError:
        raise RemoteError("timestamp must use YYYY-MM-DDTHH:MM:SSZ")


def canonical_bytes(value):
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def strict_keys(value, expected, label):
    if not isinstance(value, dict):
        raise RemoteError("{} must be an object".format(label))
    actual = set(value)
    expected = set(expected)
    if actual != expected:
        raise RemoteError(
            "{} fields must be exactly {}; got {}".format(
                label, sorted(expected), sorted(actual)
            )
        )


def require_string(value, label, pattern=None):
    if not isinstance(value, str) or not value:
        raise RemoteError("{} must be a non-empty string".format(label))
    if "\x00" in value or "\n" in value or "\r" in value:
        raise RemoteError("{} contains forbidden control characters".format(label))
    if pattern is not None and not pattern.match(value):
        raise RemoteError("{} has an invalid format".format(label))
    return value


def ensure_sha(value, label):
    return require_string(value, label, FULL_SHA)


def ensure_sha256(value, label):
    return require_string(value, label, SHA256)


def ensure_safe_id(value, label):
    return require_string(value, label, SAFE_ID)


def ensure_absolute(path_value, label):
    value = pathlib.Path(require_string(path_value, label))
    if not value.is_absolute() or ".." in value.parts:
        raise RemoteError("{} must be an absolute normalized path".format(label))
    return value


def is_within(path, parent):
    path = pathlib.Path(path).resolve()
    parent = pathlib.Path(parent).resolve()
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


def ensure_under(path, parent, label):
    if not is_within(path, parent):
        raise RemoteError("{} must be under {}".format(label, parent))


def safe_repo_url(value):
    value = require_string(value, "source.repo_url")
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise RemoteError("source.repo_url must be credential-free HTTPS")
    if parsed.query or parsed.fragment:
        raise RemoteError("source.repo_url must not contain query or fragment data")
    return value.rstrip("/")


def run_git(cwd, arguments, binary=False, check=True):
    command = ["git", "-C", str(cwd)] + list(arguments)
    try:
        output = subprocess.check_output(command, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
        if check:
            message = exc.output.decode("utf-8", "replace")[-800:]
            raise RemoteError("git command failed: {}".format(message.strip()))
        return None
    if binary:
        return output
    return output.decode("utf-8", "strict").strip()


def git_object_bytes(repo, commit_sha, path):
    try:
        return subprocess.check_output(
            ["git", "-C", str(repo), "show", "{}:{}".format(commit_sha, path)],
            stderr=subprocess.STDOUT,
        )
    except subprocess.CalledProcessError as exc:
        message = exc.output.decode("utf-8", "replace")[-800:]
        raise RemoteError("cannot read {} from source commit: {}".format(path, message.strip()))


def git_commit_tree(repo, commit_sha):
    ensure_sha(commit_sha, "source.commit_sha")
    return run_git(repo, ["rev-parse", "{}^{{tree}}".format(commit_sha)])


def verify_commit_on_branch(repo, commit_sha, branch):
    ref = "refs/remotes/origin/{}".format(branch)
    if run_git(repo, ["show-ref", "--verify", ref], check=False) is None:
        raise RemoteError("source repository lacks {}".format(ref))
    result = subprocess.run(
        ["git", "-C", str(repo), "merge-base", "--is-ancestor", commit_sha, ref],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RemoteError("source commit is not contained in origin/{}".format(branch))


def parse_wrapper_properties(raw):
    values = {}
    for line in raw.decode("utf-8", "strict").splitlines():
        if not line or line.lstrip().startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        values[name.strip()] = value.strip().replace("\\:", ":")
    distribution_url = values.get("distributionUrl")
    if not distribution_url:
        raise RemoteError("Gradle wrapper distributionUrl is missing")
    return distribution_url


def tool_records(root):
    records = []
    for logical in TOOL_PATHS:
        path = pathlib.Path(root) / logical
        if not path.is_file() or path.is_symlink():
            raise RemoteError("required tool is missing or symlinked: {}".format(logical))
        records.append({"path": logical, "sha256": sha256_file(path)})
    return records


def tool_bundle_digest(records):
    return sha256_bytes(canonical_bytes(records))


def generated_argv(tool_root, run_root, test_filter):
    init_script = pathlib.Path(tool_root) / "ops/ci/aliyun-flow/cold-init.gradle"
    project_cache = pathlib.Path(run_root) / "project-cache"
    return [
        "./gradlew",
        "--no-daemon",
        "--stacktrace",
        "--console=plain",
        "--init-script",
        str(init_script),
        "--project-cache-dir",
        str(project_cache),
        EXPECTED_TASKS[0],
        "--tests",
        test_filter,
        EXPECTED_TASKS[1],
        EXPECTED_TASKS[2],
    ]


def validate_gradle_contract(gradle, execution):
    strict_keys(
        gradle,
        ["selector", "argv", "tasks", "test_filter", "artifact_directory"],
        "ticket.gradle",
    )
    selector = ensure_safe_id(gradle["selector"], "gradle.selector")
    test_filter = require_string(gradle["test_filter"], "gradle.test_filter", TEST_FILTER)
    if test_filter != DEFAULT_TEST_FILTER:
        raise RemoteError("only the frozen SensitiveDataSanitizerTest selector is allowed")
    if gradle["tasks"] != EXPECTED_TASKS:
        raise RemoteError("Gradle task graph differs from the frozen cold-build graph")
    expected_argv = generated_argv(
        execution["tool_root"], execution["run_root"], test_filter
    )
    if gradle["argv"] != expected_argv:
        raise RemoteError("Gradle argv differs from the generated frozen command")
    forbidden = {
        "-x", "--exclude-task", "clean", "build", "check", "publish", "upload",
        "install", "deploy", "release", "wrapper", "--daemon", "--continuous",
        "--write-locks", "--update-locks", "--refresh-dependencies", "--offline",
        "--settings-file", "--build-file", "--project-dir", "--include-build",
    }
    lowered = [item.lower() for item in gradle["argv"]]
    if any(item in forbidden for item in lowered):
        raise RemoteError("Gradle argv contains a forbidden operation")
    for item in gradle["argv"]:
        if item.startswith("-P") or item.startswith("-D") or item.startswith("--project-prop") or item.startswith("--system-prop"):
            raise RemoteError("Gradle property injection is forbidden")
        if any(word in item.lower() for word in ("password", "secret", "token", "credential")):
            raise RemoteError("Gradle argv contains a sensitive-looking token")
    expected_artifact = "jia/starter/libs"
    if gradle["artifact_directory"] != expected_artifact:
        raise RemoteError("unexpected artifact directory")
    return selector


def validate_ticket(ticket, now_value=None, check_time=True):
    strict_keys(
        ticket,
        [
            "schema_version", "kind", "task", "source", "gradle", "fixture",
            "flow", "tools", "execution", "nonce", "issued_at", "expires_at",
        ],
        "ticket",
    )
    if ticket["schema_version"] != SCHEMA_VERSION or ticket["kind"] != TICKET_KIND:
        raise RemoteError("unsupported ticket schema or kind")

    strict_keys(
        ticket["task"],
        [
            "id", "ledger_source_commit_sha", "ledger_source_tree_sha", "owner_agent",
            "owner_profile", "owner_mode", "issued_gate",
        ],
        "ticket.task",
    )
    ensure_safe_id(ticket["task"]["id"], "task.id")
    ensure_sha(ticket["task"]["ledger_source_commit_sha"], "task.ledger_source_commit_sha")
    ensure_sha(ticket["task"]["ledger_source_tree_sha"], "task.ledger_source_tree_sha")
    ensure_safe_id(ticket["task"]["owner_agent"], "task.owner_agent")
    ensure_safe_id(ticket["task"]["owner_profile"], "task.owner_profile")
    if ticket["task"]["owner_mode"] not in ("writer", "verifier"):
        raise RemoteError("ticket owner must be writer or verifier")
    if ticket["task"]["issued_gate"] not in ("targeted_verification", "verifying"):
        raise RemoteError("ticket must be issued from a Gradle verification gate")

    strict_keys(
        ticket["source"],
        [
            "repo_url", "branch", "commit_sha", "tree_sha", "gradlew_sha256",
            "wrapper_properties_sha256", "distribution_url",
        ],
        "ticket.source",
    )
    safe_repo_url(ticket["source"]["repo_url"])
    if ticket["source"]["branch"] != EXPECTED_BRANCH:
        raise RemoteError("only source branch develop is allowed")
    ensure_sha(ticket["source"]["commit_sha"], "source.commit_sha")
    ensure_sha(ticket["source"]["tree_sha"], "source.tree_sha")
    ensure_sha256(ticket["source"]["gradlew_sha256"], "source.gradlew_sha256")
    ensure_sha256(
        ticket["source"]["wrapper_properties_sha256"],
        "source.wrapper_properties_sha256",
    )
    require_string(ticket["source"]["distribution_url"], "source.distribution_url")
    if ticket["task"]["ledger_source_commit_sha"] != ticket["source"]["commit_sha"]:
        raise RemoteError("ledger source commit does not match ticket source commit")
    if ticket["task"]["ledger_source_tree_sha"] != ticket["source"]["tree_sha"]:
        raise RemoteError("ledger source tree does not match ticket source tree")

    strict_keys(
        ticket["execution"],
        ["cwd", "tool_root", "run_root", "receipt_path"],
        "ticket.execution",
    )
    cwd = ensure_absolute(ticket["execution"]["cwd"], "execution.cwd")
    tool_root = ensure_absolute(ticket["execution"]["tool_root"], "execution.tool_root")
    run_root = ensure_absolute(ticket["execution"]["run_root"], "execution.run_root")
    receipt_path = ensure_absolute(
        ticket["execution"]["receipt_path"], "execution.receipt_path"
    )
    if receipt_path.parent != run_root or receipt_path.name != "receipt.json":
        raise RemoteError("receipt_path must be run_root/receipt.json")
    if is_within(run_root, cwd) or is_within(cwd, run_root):
        raise RemoteError("run_root and source checkout must be disjoint")
    if is_within(tool_root, cwd) or is_within(cwd, tool_root):
        raise RemoteError("tool_root and source checkout must be disjoint")
    if is_within(run_root, tool_root) or is_within(tool_root, run_root):
        raise RemoteError("run_root and tool_root must be disjoint")

    validate_gradle_contract(ticket["gradle"], ticket["execution"])

    strict_keys(ticket["flow"], ["organization_id", "pipeline_id"], "ticket.flow")
    ensure_safe_id(ticket["flow"]["organization_id"], "flow.organization_id")
    ensure_safe_id(ticket["flow"]["pipeline_id"], "flow.pipeline_id")

    strict_keys(ticket["tools"], ["bundle_sha256", "files"], "ticket.tools")
    ensure_sha256(ticket["tools"]["bundle_sha256"], "tools.bundle_sha256")
    if not isinstance(ticket["tools"]["files"], list) or len(ticket["tools"]["files"]) != len(TOOL_PATHS):
        raise RemoteError("tools.files must contain the frozen tool catalog")
    for index, record in enumerate(ticket["tools"]["files"]):
        strict_keys(record, ["path", "sha256"], "tools.files[{}]".format(index))
        require_string(record["path"], "tools.files.path")
        ensure_sha256(record["sha256"], "tools.files.sha256")
    if [record["path"] for record in ticket["tools"]["files"]] != TOOL_PATHS:
        raise RemoteError("tools.files paths differ from the frozen tool catalog")
    if tool_bundle_digest(ticket["tools"]["files"]) != ticket["tools"]["bundle_sha256"]:
        raise RemoteError("tools.bundle_sha256 does not match tools.files")

    strict_keys(ticket["fixture"], ["digest", "inputs"], "ticket.fixture")
    ensure_sha256(ticket["fixture"]["digest"], "fixture.digest")
    strict_keys(
        ticket["fixture"]["inputs"],
        [
            "source_tree_sha", "selector", "argv", "tool_bundle_sha256",
            "java_major", "gradle_wrapper_properties_sha256", "distribution_url",
        ],
        "ticket.fixture.inputs",
    )
    fixture_inputs = ticket["fixture"]["inputs"]
    if fixture_inputs != {
        "source_tree_sha": ticket["source"]["tree_sha"],
        "selector": ticket["gradle"]["selector"],
        "argv": ticket["gradle"]["argv"],
        "tool_bundle_sha256": ticket["tools"]["bundle_sha256"],
        "java_major": EXPECTED_JAVA_MAJOR,
        "gradle_wrapper_properties_sha256": ticket["source"]["wrapper_properties_sha256"],
        "distribution_url": ticket["source"]["distribution_url"],
    }:
        raise RemoteError("fixture inputs do not match ticket execution inputs")
    if sha256_bytes(canonical_bytes(fixture_inputs)) != ticket["fixture"]["digest"]:
        raise RemoteError("fixture digest mismatch")

    require_string(ticket["nonce"], "nonce", NONCE)
    issued_at = parse_utc(ticket["issued_at"])
    expires_at = parse_utc(ticket["expires_at"])
    if expires_at <= issued_at or (expires_at - issued_at).total_seconds() > MAX_TTL_SECONDS:
        raise RemoteError("ticket validity window is invalid")
    if check_time:
        current = now_value or utc_now()
        if current < issued_at - dt.timedelta(minutes=2):
            raise RemoteError("ticket is not yet valid")
        if current >= expires_at:
            raise RemoteError("ticket is expired")
    return ticket


def load_verified_ticket(path, expected_sha256, now_value=None, check_time=True):
    ensure_sha256(expected_sha256, "expected-ticket-sha256")
    ticket_path = pathlib.Path(path)
    if not ticket_path.is_file() or ticket_path.is_symlink():
        raise RemoteError("ticket must be a regular non-symlink file")
    raw = ticket_path.read_bytes()
    actual = sha256_bytes(raw)
    if not hmac.compare_digest(actual, expected_sha256):
        raise RemoteError("ticket SHA-256 mismatch")
    try:
        ticket = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise RemoteError("ticket is not valid UTF-8 JSON")
    validate_ticket(ticket, now_value=now_value, check_time=check_time)
    return ticket, actual


def atomic_json(path, value, mode=0o444, exclusive=False):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temp_name = tempfile.mkstemp(prefix=".{}.".format(path.name), dir=str(path.parent))
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temp_name, mode)
        if exclusive:
            try:
                os.link(temp_name, str(path))
            except FileExistsError:
                raise RemoteError("refusing to overwrite immutable file: {}".format(path))
            os.unlink(temp_name)
            temp_name = None
        else:
            os.replace(temp_name, str(path))
            temp_name = None
    finally:
        if temp_name and os.path.exists(temp_name):
            os.unlink(temp_name)


def issue_ticket(args, controller):
    if controller is None:
        raise RemoteError("ticket issue requires orchestrator controller context")
    root = pathlib.Path(controller["root"]).resolve()
    api_repo = pathlib.Path(args.api_git_repo).resolve()
    output = pathlib.Path(args.ticket).resolve()
    ensure_under(output, TICKET_PREFIX, "ticket output")
    if is_within(output, root):
        raise RemoteError("ticket output must be outside the controller checkout")
    if args.branch != EXPECTED_BRANCH:
        raise RemoteError("branch must be develop")
    if args.ttl_seconds <= 0 or args.ttl_seconds > MAX_TTL_SECONDS:
        raise RemoteError("ttl must be between 1 and {} seconds".format(MAX_TTL_SECONDS))
    test_filter = require_string(args.test_filter, "test-filter", TEST_FILTER)
    if test_filter != DEFAULT_TEST_FILTER:
        raise RemoteError("only the frozen SensitiveDataSanitizerTest selector is allowed")
    selector = ensure_safe_id(args.selector, "selector")
    repo_url = safe_repo_url(args.repo_url)
    commit_sha = ensure_sha(args.commit_sha, "commit-sha")
    supplied_tree = ensure_sha(args.tree_sha, "tree-sha")
    tool_root = ensure_absolute(args.remote_tool_root, "remote-tool-root")
    cwd = ensure_absolute(args.remote_cwd, "remote-cwd")
    run_base = ensure_absolute(args.remote_run_base, "remote-run-base")
    if is_within(tool_root, cwd) or is_within(cwd, tool_root):
        raise RemoteError("remote tool root and API checkout must be disjoint")
    if is_within(run_base, cwd) or is_within(cwd, run_base):
        raise RemoteError("remote run base and API checkout must be disjoint")

    with controller["exclusive_lock"](controller["control_lock"]):
        ledger = controller["load_ledger"]()
        item = controller["task"](ledger, args.task_id)
        owner = item.get("owner") or {}
        if owner.get("mode") not in ("writer", "verifier"):
            raise RemoteError("ticket issue requires this task's writer/verifier owner")
        if item.get("current_gate") not in controller["gradle_gates"]:
            raise RemoteError("ticket issue requires a Gradle verification gate")

        exact = item.get("exact_sha_tree") or {}
        if exact.get("commit_sha") != commit_sha:
            raise RemoteError("API source commit does not match task ledger exact commit")
        if exact.get("tree_sha") != supplied_tree:
            raise RemoteError("API source tree does not match task ledger exact tree")

        actual_tree = git_commit_tree(api_repo, commit_sha)
        if actual_tree != supplied_tree:
            raise RemoteError("API source commit/tree mismatch")
        verify_commit_on_branch(api_repo, commit_sha, EXPECTED_BRANCH)
        configured_url = safe_repo_url(run_git(api_repo, ["remote", "get-url", "origin"]))
        if configured_url != repo_url:
            raise RemoteError("API source origin URL does not match requested repo URL")

        gradlew = git_object_bytes(api_repo, commit_sha, "gradlew")
        wrapper = git_object_bytes(
            api_repo, commit_sha, "gradle/wrapper/gradle-wrapper.properties"
        )
        distribution_url = parse_wrapper_properties(wrapper)
        records = tool_records(root)
        bundle_digest = tool_bundle_digest(records)
        nonce = secrets.token_hex(32)
        run_root = run_base / args.task_id / nonce
        receipt_path = run_root / "receipt.json"
        execution = {
            "cwd": str(cwd),
            "tool_root": str(tool_root),
            "run_root": str(run_root),
            "receipt_path": str(receipt_path),
        }
        argv = generated_argv(tool_root, run_root, test_filter)
        fixture_inputs = {
            "source_tree_sha": supplied_tree,
            "selector": selector,
            "argv": argv,
            "tool_bundle_sha256": bundle_digest,
            "java_major": EXPECTED_JAVA_MAJOR,
            "gradle_wrapper_properties_sha256": sha256_bytes(wrapper),
            "distribution_url": distribution_url,
        }
        issued = utc_now()
        ticket = {
            "schema_version": SCHEMA_VERSION,
            "kind": TICKET_KIND,
            "task": {
                "id": args.task_id,
                "ledger_source_commit_sha": commit_sha,
                "ledger_source_tree_sha": supplied_tree,
                "owner_agent": owner["agent"],
                "owner_profile": owner["profile"],
                "owner_mode": owner["mode"],
                "issued_gate": item["current_gate"],
            },
            "source": {
                "repo_url": repo_url,
                "branch": EXPECTED_BRANCH,
                "commit_sha": commit_sha,
                "tree_sha": supplied_tree,
                "gradlew_sha256": sha256_bytes(gradlew),
                "wrapper_properties_sha256": sha256_bytes(wrapper),
                "distribution_url": distribution_url,
            },
            "gradle": {
                "selector": selector,
                "argv": argv,
                "tasks": list(EXPECTED_TASKS),
                "test_filter": test_filter,
                "artifact_directory": "jia/starter/libs",
            },
            "fixture": {
                "digest": sha256_bytes(canonical_bytes(fixture_inputs)),
                "inputs": fixture_inputs,
            },
            "flow": {
                "organization_id": ensure_safe_id(args.flow_organization, "flow-organization"),
                "pipeline_id": ensure_safe_id(args.flow_pipeline, "flow-pipeline"),
            },
            "tools": {"bundle_sha256": bundle_digest, "files": records},
            "execution": execution,
            "nonce": nonce,
            "issued_at": format_utc(issued),
            "expires_at": format_utc(issued + dt.timedelta(seconds=args.ttl_seconds)),
        }
        validate_ticket(ticket, now_value=issued)
        atomic_json(output, ticket, mode=0o444, exclusive=True)
        ticket_hash = sha256_file(output)

    print(
        json.dumps(
            {
                "ticket": str(output),
                "ticket_sha256": ticket_hash,
                "receipt": str(receipt_path),
                "fixture_digest": ticket["fixture"]["digest"],
                "selector": selector,
                "flow_literal_required": True,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


def verify_tools(ticket, actual_root):
    actual_root = pathlib.Path(actual_root).resolve()
    expected_root = pathlib.Path(ticket["execution"]["tool_root"])
    if actual_root != expected_root:
        raise RemoteError("running tool root does not match ticket tool_root")
    actual = tool_records(actual_root)
    if actual != ticket["tools"]["files"]:
        raise RemoteError("remote tool bytes differ from ticket tool catalog")
    if tool_bundle_digest(actual) != ticket["tools"]["bundle_sha256"]:
        raise RemoteError("remote tool bundle digest mismatch")


def verify_nested_gradle_absent(cwd):
    """Scan immutable Gradle blobs with Python's regex engine; never mix grep dialects."""
    names = run_git(cwd, ["ls-tree", "-r", "--name-only", "HEAD"])
    gradle_paths = [
        name for name in names.splitlines()
        if name.endswith(".gradle") or name.endswith(".gradle.kts")
    ]
    if not gradle_paths:
        raise RemoteError("source tree contains no Gradle build files")
    pattern = re.compile(NESTED_GRADLE_PATTERN, re.IGNORECASE)
    for name in gradle_paths:
        raw = run_git(cwd, ["show", "HEAD:{}".format(name)], binary=True)
        content = raw.decode("utf-8", "replace")
        match = pattern.search(content)
        if match:
            line_number = content.count("\n", 0, match.start()) + 1
            raise RemoteError(
                "nested Gradle execution pattern found in immutable build source: {}:{}"
                .format(name, line_number)
            )


def verify_source(ticket, cwd_value):
    cwd = pathlib.Path(cwd_value).resolve()
    if str(cwd) != ticket["execution"]["cwd"]:
        raise RemoteError("cwd does not match ticket execution.cwd")
    top = pathlib.Path(run_git(cwd, ["rev-parse", "--show-toplevel"])).resolve()
    if top != cwd:
        raise RemoteError("cwd must be the API Git top-level")
    dirty = run_git(cwd, ["status", "--porcelain", "--untracked-files=all"])
    if dirty:
        raise RemoteError("source checkout is dirty")
    head = run_git(cwd, ["rev-parse", "HEAD"])
    tree = run_git(cwd, ["rev-parse", "HEAD^{tree}"])
    if head != ticket["source"]["commit_sha"]:
        raise RemoteError("source HEAD does not match ticket commit")
    if tree != ticket["source"]["tree_sha"]:
        raise RemoteError("source tree does not match ticket tree")
    remote_url = safe_repo_url(run_git(cwd, ["remote", "get-url", "origin"]))
    if remote_url != ticket["source"]["repo_url"]:
        raise RemoteError("source origin URL does not match ticket")
    verify_commit_on_branch(cwd, head, ticket["source"]["branch"])
    gradlew_path = cwd / "gradlew"
    wrapper_path = cwd / "gradle/wrapper/gradle-wrapper.properties"
    if sha256_file(gradlew_path) != ticket["source"]["gradlew_sha256"]:
        raise RemoteError("gradlew bytes do not match ticket")
    if sha256_file(wrapper_path) != ticket["source"]["wrapper_properties_sha256"]:
        raise RemoteError("wrapper properties bytes do not match ticket")
    if parse_wrapper_properties(wrapper_path.read_bytes()) != ticket["source"]["distribution_url"]:
        raise RemoteError("wrapper distribution URL does not match ticket")
    verify_nested_gradle_absent(cwd)
    return {"head": head, "tree": tree, "remote_url": remote_url, "clean": True}


def flow_environment(ticket):
    values = {}
    for receipt_key, env_name in FLOW_ENVIRONMENT.items():
        raw = os.environ.get(env_name)
        if receipt_key == "source_tip_sha":
            values[receipt_key] = ensure_sha(raw, env_name)
        else:
            values[receipt_key] = ensure_safe_id(raw, env_name)
    if values["organization_id"] != ticket["flow"]["organization_id"]:
        raise RemoteError("Flow organization environment does not match ticket")
    if values["pipeline_id"] != ticket["flow"]["pipeline_id"]:
        raise RemoteError("Flow pipeline environment does not match ticket")
    return values


def java_identity():
    java_home = os.environ.get("JAVA_HOME", "").strip()
    executable = pathlib.Path(java_home) / "bin/java" if java_home else pathlib.Path("java")
    result = subprocess.run(
        [str(executable), "-version"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    output = (result.stderr + result.stdout).decode("utf-8", "replace")
    first_line = output.splitlines()[0] if output.splitlines() else ""
    match = re.search(r'(?:version\s+")?(\d+)(?:\.|\")', first_line)
    if result.returncode != 0 or not match or int(match.group(1)) != EXPECTED_JAVA_MAJOR:
        raise RemoteError("Java 21 is required")
    return first_line[:300]


def reserve_nonce(nonce, nonce_dir=NONCE_DIR):
    nonce_dir = pathlib.Path(nonce_dir)
    nonce_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    if nonce_dir.is_symlink():
        raise RemoteError("nonce directory must not be a symlink")
    marker = nonce_dir / sha256_bytes(nonce.encode("ascii"))
    try:
        fd = os.open(str(marker), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise RemoteError("ticket nonce has already been consumed on this runner")
    with os.fdopen(fd, "w", encoding="ascii") as handle:
        handle.write(format_utc(utc_now()) + "\n")
        handle.flush()
        os.fsync(handle.fileno())
    return marker


def safe_mkdir_new(path):
    path = pathlib.Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        path.mkdir(mode=0o700)
    except FileExistsError:
        raise RemoteError("run_root already exists")
    if path.is_symlink():
        raise RemoteError("run_root must not be a symlink")


def required_maven_credentials():
    # Value-free diagnostics, including partial pairs; never obtain credentials
    # from argv, tickets, Gradle properties, local files or controller caches.
    values = tuple(os.environ.get(name, "") for name in MAVEN_AUTH_ENV)
    if any(not value or not value.strip() or len(value) > 4096
           or any(ord(char) < 32 or ord(char) == 127 for char in value)
           for value in values) or ":" in values[0]:
        raise RemoteError("both CYF_MAVEN_USERNAME and CYF_MAVEN_PASSWORD are required and must be valid")
    return dict(zip(MAVEN_AUTH_ENV, values))


def secret_patterns(credentials):
    values = list(credentials.values())
    pair = credentials[MAVEN_AUTH_ENV[0]] + ":" + credentials[MAVEN_AUTH_ENV[1]]
    values.append(pair)
    for encoding in ("utf-8", "latin-1"):
        try:
            values.append(base64.b64encode(pair.encode(encoding)).decode("ascii"))
        except UnicodeEncodeError:
            pass
    patterns = set()
    for value in values:
        for form in (value, html.escape(value, quote=True),
                     xml_escape(value, {'"': "&quot;", "'": "&apos;"}),
                     json.dumps(value, ensure_ascii=True)[1:-1],
                     quote(value, safe=""), quote_plus(value, safe="")):
            patterns.add(form.encode("utf-8"))
    return tuple(sorted(patterns, key=lambda value: (-len(value), value)))


class StreamRedactor(object):
    """Hold incomplete matches across reads; neither sink ever sees raw bytes."""
    def __init__(self, patterns):
        self.patterns = patterns
        self.pending = b""
        self.width = max([len(value) for value in patterns] or [1])
        self.found = False

    def feed(self, chunk, final=False):
        data = self.pending + chunk
        limit = len(data) if final else max(0, len(data) - self.width + 1)
        output = []
        offset = 0
        while offset < limit:
            matches = [(data.find(value, offset), value) for value in self.patterns]
            matches = [(index, value) for index, value in matches if 0 <= index < limit]
            if not matches:
                output.append(data[offset:limit])
                offset = limit
                break
            index, value = min(matches, key=lambda match: (match[0], -len(match[1])))
            output.extend((data[offset:index], b"[REDACTED]"))
            self.found = True
            offset = index + len(value)
        self.pending = data[offset:]
        return b"".join(output)


def redact_bytes(data, patterns):
    return StreamRedactor(patterns).feed(data, final=True)


def pump_stream(source, disk_handle, console, patterns, errors):
    redactor = StreamRedactor(patterns)
    try:
        while True:
            chunk = source.read(65536)
            safe = redactor.feed(chunk, final=not chunk)
            disk_handle.write(safe)
            disk_handle.flush()
            target = getattr(console, "buffer", None)
            if target is not None:
                target.write(safe)
                target.flush()
            else:
                console.write(safe.decode("utf-8", errors="replace"))
                console.flush()
            if not chunk:
                break
    except Exception:
        # Drain without persisting/outputting, so a failed sink cannot deadlock
        # the child or silently convert incomplete logs into accepted evidence.
        errors.append("redacted log transport failed")
        while source.read(65536):
            pass
    finally:
        source.close()


def run_tee(argv, cwd, env, stdout_path, stderr_path):
    patterns = secret_patterns({name: env[name] for name in MAVEN_AUTH_ENV})
    errors = []
    with pathlib.Path(stdout_path).open("wb") as stdout_file, pathlib.Path(stderr_path).open("wb") as stderr_file:
        process = subprocess.Popen(
            argv, cwd=str(cwd), env=env, stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=False,
        )
        threads = [
            threading.Thread(target=pump_stream, args=(process.stdout, stdout_file, sys.stdout, patterns, errors)),
            threading.Thread(target=pump_stream, args=(process.stderr, stderr_file, sys.stderr, patterns, errors)),
        ]
        for thread in threads:
            thread.start()
        return_code = process.wait()
        for thread in threads:
            thread.join()
    return return_code, not errors


def assert_no_secret_stream(handle, patterns):
    redactor = StreamRedactor(patterns)
    while True:
        chunk = handle.read(65536)
        redactor.feed(chunk, final=not chunk)
        if redactor.found:
            raise RemoteError("credential material detected in artifact; export denied")
        if not chunk:
            break


def verify_export_jar(path, patterns):
    # Inspect raw ZIP bytes AND inflated members, including nested boot JAR libs.
    # Bounds fail closed instead of accepting an unexamined archive/ZIP bomb.
    declared_size_limit = 1024 * 1024 * 1024
    entry_count_limit = 100000
    remaining = [declared_size_limit, entry_count_limit]

    def limit_error(dimension, configured_limit, prior_total, attempted_total,
                    current_declared_size, depth, ordinal):
        # This diagnostic must remain numeric-only: archive/member metadata can
        # contain credentials and is never safe to expose to the receipt bridge.
        raise RemoteError(
            "artifact archive inspection limit exceeded: {} "
            "configured_limit={} prior_total={} attempted_total={} "
            "current_declared_size={} depth={} ordinal={}".format(
                dimension, configured_limit, prior_total, attempted_total,
                current_declared_size, depth, ordinal,
            )
        )

    def inspect(handle, depth):
        assert_no_secret_stream(handle, patterns)
        handle.seek(0)
        if depth > 3 or not zipfile.is_zipfile(handle):
            raise RemoteError("artifact must be a bounded valid JAR/ZIP")
        handle.seek(0)
        with zipfile.ZipFile(handle) as archive:
            assert_no_secret_stream(io.BytesIO(archive.comment), patterns)
            for ordinal, entry in enumerate(archive.infolist(), 1):
                prior_declared_size = declared_size_limit - remaining[0]
                attempted_declared_size = prior_declared_size + entry.file_size
                prior_entry_count = entry_count_limit - remaining[1]
                attempted_entry_count = prior_entry_count + 1
                remaining[0] -= entry.file_size
                remaining[1] -= 1
                if remaining[0] < 0:
                    limit_error(
                        "declared_size", declared_size_limit,
                        prior_declared_size, attempted_declared_size,
                        entry.file_size, depth, ordinal,
                    )
                if remaining[1] < 0:
                    limit_error(
                        "entry_count", entry_count_limit,
                        prior_entry_count, attempted_entry_count,
                        entry.file_size, depth, ordinal,
                    )
                assert_no_secret_stream(io.BytesIO(entry.filename.encode("utf-8") + entry.comment + entry.extra), patterns)
                with archive.open(entry) as member:
                    if entry.filename.lower().endswith((".jar", ".zip")):
                        if entry.file_size > 128 * 1024 * 1024:
                            raise RemoteError("nested archive inspection limit exceeded")
                        inspect(io.BytesIO(member.read()), depth + 1)
                    else:
                        assert_no_secret_stream(member, patterns)

    try:
        with pathlib.Path(path).open("rb") as handle:
            inspect(handle, 0)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, OSError, EOFError):
        raise RemoteError("artifact archive inspection failed")


def export_test_xml(build_root, run_root, patterns):
    output = pathlib.Path(run_root) / "test-results"
    output.mkdir(mode=0o700)
    records = []
    for path in sorted(pathlib.Path(build_root).rglob("TEST-*.xml")):
        if "test-results" not in path.parts:
            continue
        if path.is_symlink() or not is_within(path.resolve(), pathlib.Path(build_root).resolve()):
            raise RemoteError("test XML must stay inside the private build root")
        # Names are hashes, not potentially credential-bearing suite names.
        target = output / ("TEST-" + sha256_bytes(str(path.relative_to(build_root)).encode("utf-8")) + ".xml")
        redactor = StreamRedactor(patterns)
        with path.open("rb") as source, target.open("xb") as destination:
            while True:
                chunk = source.read(65536)
                destination.write(redactor.feed(chunk, final=not chunk))
                if not chunk:
                    break
        records.append(file_record(target, run_root, "test_xml"))
    return output, records


def opencv_provenance():
    return {
        "coordinate": OPENCV_COORDINATE,
        "repository": OPENCV_REPOSITORY,
        "jar_sha256": OPENCV_JAR_SHA256,
        "jar_size": OPENCV_JAR_SIZE,
        "controller_pom_sha256": OPENCV_CONTROLLER_POM_SHA256,
        "controller_provenance_reference": OPENCV_PROVENANCE_REF,
        "verification": "resolved-jar-before-consumption",
    }


def collect_opencv_provenance(run_root, build_root):
    path = pathlib.Path(build_root) / "opencv-resolved.json"
    if path.is_symlink() or not path.is_file():
        raise RemoteError("pre-consumption OpenCV provenance is missing")
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
        strict_keys(record, ["coordinate", "jar_sha256", "jar_size", "resolved_file"], "OpenCV resolution")
        artifact = pathlib.Path(record["resolved_file"])
        if (record["coordinate"] != OPENCV_COORDINATE or record["jar_sha256"] != OPENCV_JAR_SHA256
                or record["jar_size"] != OPENCV_JAR_SIZE or artifact.is_symlink()
                or not artifact.is_file() or not is_within(artifact.resolve(), (pathlib.Path(run_root) / "gradle-user-home").resolve())
                or artifact.stat().st_size != OPENCV_JAR_SIZE or sha256_file(artifact) != OPENCV_JAR_SHA256):
            raise RemoteError("resolved OpenCV JAR does not match controller pin")
    except (ValueError, TypeError, KeyError, OSError):
        raise RemoteError("OpenCV provenance is invalid")
    target = pathlib.Path(run_root) / "dependency-provenance.json"
    atomic_json(target, opencv_provenance(), mode=0o444, exclusive=True)
    return file_record(target, run_root, "dependency_provenance")


def discard_private_build_state(run_root):
    # Only this newly reserved run's scratch directories. Never a shared cache,
    # checkout, another task's evidence, or a controller ledger.
    for name in ("home", "gradle-user-home", "build", "tmp", "project-cache"):
        path = pathlib.Path(run_root) / name
        if path.is_symlink():
            path.unlink()
        elif path.exists():
            shutil.rmtree(str(path))


def test_xml_statistics(build_root):
    totals = {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "parse_errors": 0}
    suites = []
    for path in sorted(pathlib.Path(build_root).rglob("TEST-*.xml")):
        if "test-results" not in path.parts:
            continue
        totals["files"] += 1
        try:
            root = ET.parse(str(path)).getroot()
            values = {}
            for field in ("tests", "failures", "errors", "skipped"):
                raw = root.attrib.get(field, "0")
                values[field] = int(float(raw))
                totals[field] += values[field]
            suites.append({
                "name": root.attrib.get("name", path.name)[:300],
                "tests": values["tests"],
                "failures": values["failures"],
                "errors": values["errors"],
                "skipped": values["skipped"],
            })
        except (ET.ParseError, OSError, ValueError):
            totals["parse_errors"] += 1
    totals["suites"] = suites
    return totals


def file_record(path, base, kind):
    path = pathlib.Path(path)
    relative = path.resolve().relative_to(pathlib.Path(base).resolve())
    return {
        "kind": kind,
        "path": str(relative),
        "sha256": sha256_file(path),
        "size": path.stat().st_size,
    }


def package_artifact(ticket, run_root, build_root, flow_values, ticket_hash, patterns):
    artifact_dir = pathlib.Path(build_root) / ticket["gradle"]["artifact_directory"]
    jars = [
        path for path in artifact_dir.glob("*.jar")
        if path.is_file() and not path.is_symlink()
    ]
    if len(jars) != 1:
        raise RemoteError("expected exactly one boot JAR; found {}".format(len(jars)))
    source_jar = jars[0]
    if not is_within(source_jar, build_root):
        raise RemoteError("artifact must stay inside the private build root")
    if redact_bytes(source_jar.name.encode("utf-8"), patterns) != source_jar.name.encode("utf-8"):
        raise RemoteError("credential material detected in artifact name")
    verify_export_jar(source_jar, patterns)
    jar_hash = sha256_file(source_jar)
    output_dir = pathlib.Path(run_root) / "artifacts"
    output_dir.mkdir(mode=0o700)
    output_jar = output_dir / "{}-{}.jar".format(source_jar.stem, jar_hash)
    shutil.copyfile(str(source_jar), str(output_jar))
    os.chmod(str(output_jar), 0o444)
    sidecar = output_dir / (output_jar.name + ".sha256")
    with sidecar.open("x", encoding="ascii") as handle:
        handle.write("{}  {}\n".format(jar_hash, output_jar.name))
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(str(sidecar), 0o444)
    metadata = {
        "schema_version": 1,
        "task_id": ticket["task"]["id"],
        "source_commit_sha": ticket["source"]["commit_sha"],
        "source_tree_sha": ticket["source"]["tree_sha"],
        "selector": ticket["gradle"]["selector"],
        "fixture_digest": ticket["fixture"]["digest"],
        "ticket_sha256": ticket_hash,
        "tool_bundle_sha256": ticket["tools"]["bundle_sha256"],
        "flow": flow_values,
        "original_jar_name": source_jar.name,
        "packaged_jar_name": output_jar.name,
        "jar_sha256": jar_hash,
        "jar_size": output_jar.stat().st_size,
        "generated_at": format_utc(utc_now()),
    }
    metadata_path = output_dir / "metadata.json"
    atomic_json(metadata_path, metadata, mode=0o444, exclusive=True)
    return [
        file_record(output_jar, run_root, "application_jar"),
        file_record(sidecar, run_root, "sha256_sidecar"),
        file_record(metadata_path, run_root, "artifact_metadata"),
    ]


def sanitized_environment(run_root, credentials):
    environment = {}
    for name in ("PATH", "JAVA_HOME", "LANG", "LC_ALL", "TZ"):
        value = os.environ.get(name)
        if value:
            environment[name] = value
    environment.update(credentials)
    home = pathlib.Path(run_root) / "home"
    gradle_home = pathlib.Path(run_root) / "gradle-user-home"
    build_root = pathlib.Path(run_root) / "build"
    temp_root = pathlib.Path(run_root) / "tmp"
    for path in (home, gradle_home, build_root, temp_root):
        path.mkdir(mode=0o700)
    environment.update({
        "HOME": str(home),
        "GRADLE_USER_HOME": str(gradle_home),
        "CYF_FLOW_BUILD_ROOT": str(build_root),
        "CYF_FLOW_GRADLE_ACTIVE": "1",
        "TMPDIR": str(temp_root),
        "CI": "true",
    })
    return environment, build_root


def base_receipt(ticket, ticket_hash, flow_values, source_before, java_version, started_at):
    return {
        "schema_version": SCHEMA_VERSION,
        "kind": RECEIPT_KIND,
        "task_id": ticket["task"]["id"],
        "ticket_sha256": ticket_hash,
        "source": {
            "repo_url": ticket["source"]["repo_url"],
            "branch": ticket["source"]["branch"],
            "commit_sha": source_before["head"],
            "tree_sha": source_before["tree"],
            "clean_before": True,
            "clean_after": None,
        },
        "selector": ticket["gradle"]["selector"],
        "fixture_digest": ticket["fixture"]["digest"],
        "argv": ticket["gradle"]["argv"],
        "tool_bundle_sha256": ticket["tools"]["bundle_sha256"],
        "flow": flow_values,
        "java_version": java_version,
        "gradle_exit_code": None,
        "bridge_exit_code": None,
        "status": "running",
        "started_at": format_utc(started_at),
        "finished_at": None,
        "duration_ms": None,
        "tests": {"files": 0, "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "parse_errors": 0, "suites": []},
        "files": [],
        "error": None,
    }


def validate_receipt(receipt):
    strict_keys(
        receipt,
        [
            "schema_version", "kind", "task_id", "ticket_sha256", "source",
            "selector", "fixture_digest", "argv", "tool_bundle_sha256", "flow",
            "java_version", "gradle_exit_code", "bridge_exit_code", "status",
            "started_at", "finished_at", "duration_ms", "tests", "files", "error",
        ],
        "receipt",
    )
    if receipt["schema_version"] != SCHEMA_VERSION or receipt["kind"] != RECEIPT_KIND:
        raise RemoteError("unsupported receipt schema or kind")
    strict_keys(
        receipt["source"],
        ["repo_url", "branch", "commit_sha", "tree_sha", "clean_before", "clean_after"],
        "receipt.source",
    )
    strict_keys(receipt["flow"], ["organization_id", "pipeline_id", "run_id", "job_id", "source_tip_sha"], "receipt.flow")
    strict_keys(
        receipt["tests"],
        ["files", "tests", "failures", "errors", "skipped", "parse_errors", "suites"],
        "receipt.tests",
    )
    if not isinstance(receipt["files"], list):
        raise RemoteError("receipt.files must be a list")
    for index, record in enumerate(receipt["files"]):
        strict_keys(record, ["kind", "path", "sha256", "size"], "receipt.files[{}]".format(index))
        ensure_sha256(record["sha256"], "receipt.files.sha256")
        if not isinstance(record["size"], int) or record["size"] < 0:
            raise RemoteError("receipt file size is invalid")
    return receipt


def execute_run(args, actual_root, nonce_dir=NONCE_DIR):
    if os.environ.get("CYF_FLOW_GRADLE_ACTIVE") == "1":
        raise RemoteError("nested flow-remote Gradle execution is forbidden")
    ticket, ticket_hash = load_verified_ticket(args.ticket, args.expected_ticket_sha256)
    verify_tools(ticket, actual_root)
    receipt_path = pathlib.Path(args.receipt).resolve()
    cwd = pathlib.Path(args.cwd).resolve()
    if str(receipt_path) != ticket["execution"]["receipt_path"]:
        raise RemoteError("receipt argument does not match ticket")
    if str(cwd) != ticket["execution"]["cwd"]:
        raise RemoteError("cwd argument does not match ticket")
    credentials = required_maven_credentials()
    patterns = secret_patterns(credentials)
    flow_values = flow_environment(ticket)
    public_inputs = canonical_bytes(ticket) + canonical_bytes(flow_values) + canonical_bytes(opencv_provenance())
    if redact_bytes(public_inputs, patterns) != public_inputs:
        raise RemoteError("credential material overlaps public ticket/Flow fields")
    source_before = verify_source(ticket, cwd)
    java_version = java_identity()
    reserve_nonce(ticket["nonce"], nonce_dir=nonce_dir)
    run_root = pathlib.Path(ticket["execution"]["run_root"])
    safe_mkdir_new(run_root)
    started = utc_now()
    monotonic_start = time.monotonic()
    receipt = base_receipt(
        ticket, ticket_hash, flow_values, source_before, java_version, started
    )
    stdout_path = run_root / "gradle.stdout.log"
    stderr_path = run_root / "gradle.stderr.log"
    environment, build_root = sanitized_environment(run_root, credentials)
    gradle_exit = None
    bridge_exit = 70
    try:
        with exclusive_lock(GRADLE_LOCK):
            verify_source(ticket, cwd)
            gradle_exit, logs_complete = run_tee(
                ticket["gradle"]["argv"], cwd, environment, stdout_path, stderr_path
            )
        receipt["gradle_exit_code"] = gradle_exit
        if gradle_exit != 0:
            bridge_exit = gradle_exit
        xml_root, xml_records = export_test_xml(build_root, run_root, patterns)
        receipt["files"].extend(xml_records)
        receipt["tests"] = test_xml_statistics(xml_root)
        post_dirty = run_git(cwd, ["status", "--porcelain", "--untracked-files=all"])
        post_head = run_git(cwd, ["rev-parse", "HEAD"])
        post_tree = run_git(cwd, ["rev-parse", "HEAD^{tree}"])
        source_clean = (
            not post_dirty
            and post_head == ticket["source"]["commit_sha"]
            and post_tree == ticket["source"]["tree_sha"]
        )
        receipt["source"]["clean_after"] = source_clean
        receipt["files"].append(file_record(stdout_path, run_root, "stdout_log"))
        receipt["files"].append(file_record(stderr_path, run_root, "stderr_log"))
        if gradle_exit != 0:
            bridge_exit = gradle_exit
            receipt["status"] = "gradle_failed"
            receipt["error"] = "Gradle returned a non-zero native exit code"
        elif not logs_complete:
            raise RemoteError("redacted log transport failed")
        elif not source_clean:
            receipt["status"] = "bridge_failed"
            receipt["error"] = "source checkout changed during Gradle execution"
        elif receipt["tests"]["parse_errors"] or receipt["tests"]["files"] == 0:
            receipt["status"] = "bridge_failed"
            receipt["error"] = "test XML evidence is missing or malformed"
        elif receipt["tests"]["failures"] or receipt["tests"]["errors"]:
            receipt["status"] = "bridge_failed"
            receipt["error"] = "test XML reports failures or errors"
        else:
            receipt["files"].append(collect_opencv_provenance(run_root, build_root))
            receipt["files"].extend(
                package_artifact(ticket, run_root, build_root, flow_values, ticket_hash, patterns)
            )
            receipt["status"] = "success"
            receipt["error"] = None
            bridge_exit = 0
    except (RemoteError, OSError, ValueError) as exc:
        receipt["status"] = "bridge_failed"
        receipt["error"] = str(exc)[:500] if isinstance(exc, RemoteError) else "private output processing failed"
        if stdout_path.exists() and not any(item["kind"] == "stdout_log" for item in receipt["files"]):
            receipt["files"].append(file_record(stdout_path, run_root, "stdout_log"))
        if stderr_path.exists() and not any(item["kind"] == "stderr_log" for item in receipt["files"]):
            receipt["files"].append(file_record(stderr_path, run_root, "stderr_log"))
    finally:
        try:
            discard_private_build_state(run_root)
        except OSError:
            receipt["status"] = "bridge_failed"
            receipt["error"] = "private scratch cleanup failed; DO NOT UPLOAD run directory"
            bridge_exit = gradle_exit if gradle_exit else 70
        receipt["bridge_exit_code"] = bridge_exit
        receipt["finished_at"] = format_utc(utc_now())
        receipt["duration_ms"] = int((time.monotonic() - monotonic_start) * 1000)
        receipt = json.loads(redact_bytes(canonical_bytes(receipt), patterns).decode("utf-8"))
        validate_receipt(receipt)
        atomic_json(receipt_path, receipt, mode=0o444, exclusive=True)
    return bridge_exit


def inspect_receipt(args):
    ticket, ticket_hash = load_verified_ticket(
        args.ticket, args.expected_ticket_sha256, check_time=False
    )
    receipt_path = pathlib.Path(args.receipt).resolve()
    if not receipt_path.is_file() or receipt_path.is_symlink():
        raise RemoteError("receipt must be a regular non-symlink file")
    try:
        receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise RemoteError("receipt is not valid UTF-8 JSON")
    validate_receipt(receipt)
    comparisons = {
        "task_id": ticket["task"]["id"],
        "ticket_sha256": ticket_hash,
        "source_commit_sha": ticket["source"]["commit_sha"],
        "source_tree_sha": ticket["source"]["tree_sha"],
        "selector": ticket["gradle"]["selector"],
        "fixture_digest": ticket["fixture"]["digest"],
        "tool_bundle_sha256": ticket["tools"]["bundle_sha256"],
        "argv": ticket["gradle"]["argv"],
        "flow_organization_id": ticket["flow"]["organization_id"],
        "flow_pipeline_id": ticket["flow"]["pipeline_id"],
    }
    actuals = {
        "task_id": receipt["task_id"],
        "ticket_sha256": receipt["ticket_sha256"],
        "source_commit_sha": receipt["source"]["commit_sha"],
        "source_tree_sha": receipt["source"]["tree_sha"],
        "selector": receipt["selector"],
        "fixture_digest": receipt["fixture_digest"],
        "tool_bundle_sha256": receipt["tool_bundle_sha256"],
        "argv": receipt["argv"],
        "flow_organization_id": receipt["flow"]["organization_id"],
        "flow_pipeline_id": receipt["flow"]["pipeline_id"],
    }
    if comparisons != actuals:
        raise RemoteError("receipt fields do not match ticket")
    seen_paths = set()
    for record in receipt["files"]:
        relative = pathlib.Path(record["path"])
        if relative.is_absolute() or ".." in relative.parts or str(relative) in seen_paths:
            raise RemoteError("receipt file path is unsafe or duplicated")
        seen_paths.add(str(relative))
        path = receipt_path.parent / relative
        if not path.is_file() or path.is_symlink():
            raise RemoteError("receipt file is missing or symlinked: {}".format(relative))
        if path.stat().st_size != record["size"] or sha256_file(path) != record["sha256"]:
            raise RemoteError("receipt file digest mismatch: {}".format(relative))
    provenance_files = [item for item in receipt["files"] if item["kind"] == "dependency_provenance"]
    provenance_valid = False
    if len(provenance_files) == 1:
        try:
            provenance_valid = json.loads((receipt_path.parent / provenance_files[0]["path"]).read_text(encoding="utf-8")) == opencv_provenance()
        except (OSError, ValueError):
            pass
    application_jars = [item for item in receipt["files"] if item["kind"] == "application_jar"]
    candidate = (
        receipt["status"] == "success"
        and receipt["gradle_exit_code"] == 0
        and receipt["bridge_exit_code"] == 0
        and receipt["source"]["clean_before"] is True
        and receipt["source"]["clean_after"] is True
        and receipt["tests"]["files"] > 0
        and receipt["tests"]["failures"] == 0
        and receipt["tests"]["errors"] == 0
        and receipt["tests"]["parse_errors"] == 0
        and len(application_jars) == 1
        and provenance_valid
    )
    packet = {
        "authenticated_flow_review_required": True,
        "automatically_accepted": False,
        "local_receipt_integrity": "verified",
        "candidate_for_controller_evidence_put": candidate,
        "receipt_sha256": sha256_file(receipt_path),
        "flow": receipt["flow"],
        "evidence_put_fields": {
            "task_id": receipt["task_id"],
            "tree_sha": receipt["source"]["tree_sha"],
            "selector": receipt["selector"],
            "fixture_digest": receipt["fixture_digest"],
            "result": "accepted" if candidate else "failed",
            "command_argv": receipt["argv"],
            "artifact": application_jars[0] if candidate else None,
        },
        "tests": receipt["tests"],
        "status": receipt["status"],
    }
    print(json.dumps(packet, ensure_ascii=False, sort_keys=True, indent=2))
    return 0 if candidate else 1


def build_parser():
    parser = argparse.ArgumentParser(prog="flow-remote")
    commands = parser.add_subparsers(dest="command")

    issue = commands.add_parser("issue")
    issue.add_argument("--task-id", required=True)
    issue.add_argument("--ticket", required=True)
    issue.add_argument("--api-git-repo", required=True)
    issue.add_argument("--repo-url", required=True)
    issue.add_argument("--branch", default=EXPECTED_BRANCH)
    issue.add_argument("--commit-sha", required=True)
    issue.add_argument("--tree-sha", required=True)
    issue.add_argument("--selector", required=True)
    issue.add_argument("--test-filter", default=DEFAULT_TEST_FILTER)
    issue.add_argument("--flow-organization", required=True)
    issue.add_argument("--flow-pipeline", required=True)
    issue.add_argument("--remote-cwd", required=True)
    issue.add_argument("--remote-tool-root", required=True)
    issue.add_argument("--remote-run-base", default=str(RUN_PREFIX))
    issue.add_argument("--ttl-seconds", type=int, default=3600)

    run = commands.add_parser("run")
    run.add_argument("--ticket", required=True)
    run.add_argument("--expected-ticket-sha256", required=True)
    run.add_argument("--cwd", required=True)
    run.add_argument("--receipt", required=True)

    inspect = commands.add_parser("inspect")
    inspect.add_argument("--ticket", required=True)
    inspect.add_argument("--expected-ticket-sha256", required=True)
    inspect.add_argument("--receipt", required=True)
    return parser


def dispatch(argv, controller=None, actual_root=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if not args.command:
        parser.print_help()
        return 2
    if args.command == "issue":
        return issue_ticket(args, controller)
    if args.command == "run":
        if controller is None:
            raise RemoteError("remote Gradle must be dispatched by cyf_orchestrator.py")
        return execute_run(args, actual_root or controller["root"])
    if args.command == "inspect":
        return inspect_receipt(args)
    raise RemoteError("unsupported flow-remote command")


def direct_main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command == "inspect":
        return inspect_receipt(args)
    parser.error("issue/run must be invoked through cyf_orchestrator.py flow-remote")
    return 2


if __name__ == "__main__":
    try:
        raise SystemExit(direct_main())
    except RemoteError as exc:
        print("FLOW_REMOTE_DENIED: {}".format(exc), file=sys.stderr)
        raise SystemExit(2)
