#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
COMMON_DIR=$(realpath "$(git -C "$ROOT" rev-parse --git-common-dir)")
WORKSPACE_ROOT=$(dirname -- "$(dirname -- "$COMMON_DIR")")
ORCHESTRATOR=${CYF_ORCHESTRATOR:-$WORKSPACE_ROOT/ops/orchestration/cyf_orchestrator.py}
MYSQL_BASE=${D09_MYSQL_BASE:-/home/isp/apps/mysql}
MYSQLD="$MYSQL_BASE/bin/mysqld"
MYSQL="$MYSQL_BASE/bin/mysql"
MYSQLADMIN="$MYSQL_BASE/bin/mysqladmin"
if [[ -n ${D09_EVIDENCE_DIR+x} ]]; then
  EVIDENCE_DIR=$(realpath -m -- "$D09_EVIDENCE_DIR")
else
  EVIDENCE_DIR=$(mktemp -d /tmp/cyf-d09-evidence-XXXXXXXX)
fi
LATEST_EVIDENCE_FILE=${D09_LATEST_EVIDENCE_FILE:-/tmp/cyf-d09-latest-evidence}
JDBC_USER=d09_isolated_test
JDBC_PASSWORD=d09-isolated-only
EXPECTED_VERSION=8.0.21
SELECTOR=cn.jia.agent.service.impl.AgentCommandOperationsMySqlTest
EXPECTED_TESTS=5
RESULT_XML_NAME="TEST-$SELECTOR.xml"
REPORT_COPY_NAME=d09-test-report-index.html
result_xml="$ROOT/agent/jia-agent-service/build/test-results/test/$RESULT_XML_NAME"
report_path="$ROOT/agent/jia-agent-service/build/reports/tests/test/index.html"

compute_fixture_digest() {
  sha256sum \
    "$ROOT/agent/jia-agent-service/src/test/java/cn/jia/agent/service/impl/AgentCommandOperationsMySqlTest.java" \
    "$ROOT/agent/jia-agent-service/src/test/scripts/run-d09-isolated-mysql.sh" \
    "$ROOT/agent/jia-agent-service/src/test/scripts/test-run-d09-isolated-mysql-contract.sh" \
    "$ROOT/agent/jia-agent-mapper/src/main/java/cn/jia/agent/mapper/AgentCommandOperationsMapper.java" \
    "$ROOT/agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandOperationsServiceImpl.java" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandBrokerRedrivePolicy.java" \
    | sha256sum | awk '{print $1}'
}

# The final reusable evidence identity is frozen before any fixture creation or
# generated-output deletion. The provisional fixture adds per-run ownership
# entropy so orchestrator Gradle can never pre-accept or cache-skip the final key.
TREE_SHA=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')
FIXTURE_DIGEST=$(compute_fixture_digest)
OWNER_TOKEN=${D09_OWNER_TOKEN:-$(python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
)}
[[ -n "$OWNER_TOKEN" ]] || { echo 'D09 owner token must not be empty' >&2; exit 80; }
PROVISIONAL_FIXTURE_DIGEST=$(printf '%s\0%s' "$FIXTURE_DIGEST" "$OWNER_TOKEN" | sha256sum | awk '{print $1}')
PROVISIONAL_SELECTOR_OR_FIXTURE="fixture:$PROVISIONAL_FIXTURE_DIGEST"
FINAL_COMMAND="./gradlew --no-daemon --max-workers=1 :agent:jia-agent-service:cleanTest :agent:jia-agent-service:test --tests $SELECTOR"

GRADLE_LOG="$EVIDENCE_DIR/gradle-d09-mysql.log"
CACHE_VALIDATION=unchecked
EVIDENCE_REUSED=no
GRADLE_STARTED=no
GRADLE_EXIT=not_run
FRESH_RESULT_XML=no
FRESH_REPORT=no
TESTS=unknown
FAILURES=unknown
ERRORS=unknown
SKIPPED=unknown
FINAL_EVIDENCE_COMMITTED=no
RESULT_XML_ARTIFACT=none
RESULT_XML_SHA256=none
CACHED_ARTIFACT=none
CACHED_SUMMARY_SHA256=none
CACHED_RESULT_XML=none
CACHE_DETAIL=none

write_summary() {
  mkdir -p -- "$EVIDENCE_DIR"
  local summary_tmp="$EVIDENCE_DIR/.result-summary.txt.tmp"
  {
    echo "current_tree=$TREE_SHA"
    echo "current_fixture_digest=$FIXTURE_DIGEST"
    echo "final_selector=$SELECTOR"
    echo "provisional_selector_or_fixture=$PROVISIONAL_SELECTOR_OR_FIXTURE"
    echo "evidence_reused=$EVIDENCE_REUSED"
    echo "cache_validation=$CACHE_VALIDATION"
    echo "gradle_started=$GRADLE_STARTED"
    echo "gradle_exit=$GRADLE_EXIT"
    echo "fresh_result_xml=$FRESH_RESULT_XML"
    echo "fresh_report=$FRESH_REPORT"
    echo "tests=$TESTS"
    echo "failures=$FAILURES"
    echo "errors=$ERRORS"
    echo "skipped=$SKIPPED"
    echo "final_evidence_committed=$FINAL_EVIDENCE_COMMITTED"
    echo "result_xml_artifact=$RESULT_XML_ARTIFACT"
    echo "result_xml_sha256=$RESULT_XML_SHA256"
    echo "cached_artifact=$CACHED_ARTIFACT"
    echo "cached_summary_sha256=$CACHED_SUMMARY_SHA256"
    echo "cached_result_xml=$CACHED_RESULT_XML"
    echo "cache_detail=$CACHE_DETAIL"
    echo "orchestrator=$ORCHESTRATOR"
    echo "result_xml=$result_xml"
    echo "report_path=$report_path"
  } > "$summary_tmp"
  mv -f -- "$summary_tmp" "$EVIDENCE_DIR/result-summary.txt"
}

print_summary() {
  cat "$EVIDENCE_DIR/result-summary.txt"
  echo "evidence_dir=$EVIDENCE_DIR"
}

clean_known_evidence_files() {
  mkdir -p -- "$EVIDENCE_DIR"
  rm -f -- \
    "$EVIDENCE_DIR/$RESULT_XML_NAME" \
    "$EVIDENCE_DIR/index.html" \
    "$EVIDENCE_DIR/$REPORT_COPY_NAME" \
    "$EVIDENCE_DIR/test-counts.txt" \
    "$EVIDENCE_DIR/result-summary.txt" \
    "$EVIDENCE_DIR/.result-summary.txt.tmp" \
    "$EVIDENCE_DIR/gradle-d09-mysql.log" \
    "$EVIDENCE_DIR/mysql-initialize.log" \
    "$EVIDENCE_DIR/mysql-server.log" \
    "$EVIDENCE_DIR/mysql-instance-before.txt" \
    "$EVIDENCE_DIR/mysql-instance.txt" \
    "$EVIDENCE_DIR/mysql-teardown.txt"
}

safe_local_summary_dir() {
  local cached=${1:-}
  if [[ -n "$cached" && "$cached" != none \
      && "$(realpath -m -- "$cached")" == "$(realpath -m -- "$EVIDENCE_DIR")" ]]; then
    EVIDENCE_DIR=$(mktemp -d /tmp/cyf-d09-cache-summary-XXXXXXXX)
    GRADLE_LOG="$EVIDENCE_DIR/gradle-d09-mysql.log"
  fi
  clean_known_evidence_files
}

extract_cached_artifact() {
  python3 - "$1" <<'PY'
import json
import pathlib
import sys
try:
    text = pathlib.Path(sys.argv[1]).read_text(encoding='utf-8')
    _, payload = text.split('\n', 1)
    record = json.loads(payload)
    artifact = record.get('artifact')
    if isinstance(artifact, str) and artifact and not any(c in artifact for c in '\r\n\t\0'):
        print(artifact)
except Exception:
    pass
PY
}

validate_cached_artifact() {
  python3 - "$1" "$TREE_SHA" "$SELECTOR" "$FIXTURE_DIGEST" "$EXPECTED_TESTS" <<'PY'
import hashlib
import json
import pathlib
import sys
import xml.etree.ElementTree as ET

lookup, tree, selector, fixture, expected_raw = sys.argv[1:]
expected = int(expected_raw)
text = pathlib.Path(lookup).read_text(encoding='utf-8')
first, payload = text.split('\n', 1)
if first.strip() != 'EVIDENCE_HIT':
    raise SystemExit('hit marker missing')
record = json.loads(payload)
required_record = {
    'tree_sha': tree,
    'selector': selector,
    'fixture_digest': fixture,
    'result': 'accepted',
}
for name, value in required_record.items():
    if record.get(name) != value:
        raise SystemExit(f'record {name} mismatch')
artifact_raw = record.get('artifact')
if not isinstance(artifact_raw, str) or not artifact_raw or any(c in artifact_raw for c in '\r\n\t\0'):
    raise SystemExit('record artifact is missing or unsafe')
artifact = pathlib.Path(artifact_raw)
if not artifact.is_dir():
    raise SystemExit('record artifact directory is missing')
artifact = artifact.resolve(strict=True)
summary = artifact / 'result-summary.txt'
if not summary.is_file() or summary.is_symlink():
    raise SystemExit('cached result-summary is missing or unsafe')
values = {}
for raw in summary.read_text(encoding='utf-8').splitlines():
    if '=' not in raw:
        continue
    name, value = raw.split('=', 1)
    if name in values:
        raise SystemExit(f'duplicate summary field: {name}')
    values[name] = value
required_summary = {
    'current_tree': tree,
    'current_fixture_digest': fixture,
    'final_selector': selector,
    'evidence_reused': 'no',
    'cache_validation': 'miss',
    'gradle_started': 'yes',
    'gradle_exit': '0',
    'fresh_result_xml': 'yes',
    'tests': str(expected),
    'failures': '0',
    'errors': '0',
    'skipped': '0',
    'final_evidence_committed': 'yes',
}
for name, value in required_summary.items():
    if values.get(name) != value:
        raise SystemExit(f'summary {name} mismatch')
provisional = values.get('provisional_selector_or_fixture', '')
if not provisional.startswith('fixture:') or provisional == f'fixture:{fixture}':
    raise SystemExit('summary provisional key is missing or final')
xml_name = values.get('result_xml_artifact')
if not xml_name or pathlib.PurePath(xml_name).name != xml_name or xml_name in {'.', '..'}:
    raise SystemExit('summary XML reference is unsafe')
xml_path = artifact / xml_name
if not xml_path.is_file() or xml_path.is_symlink() or xml_path.resolve(strict=True).parent != artifact:
    raise SystemExit('cached XML is missing or escapes artifact')
xml_digest = hashlib.sha256(xml_path.read_bytes()).hexdigest()
if values.get('result_xml_sha256') != xml_digest:
    raise SystemExit('cached XML digest mismatch')
root = ET.parse(xml_path).getroot()
counts = {name: int(root.attrib.get(name, '-1')) for name in ('tests', 'failures', 'errors', 'skipped')}
if counts != {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}:
    raise SystemExit('cached XML counts mismatch')
summary_digest = hashlib.sha256(summary.read_bytes()).hexdigest()
for value in (str(artifact), summary_digest, str(xml_path), xml_digest):
    if any(c in value for c in '\r\n\t\0'):
        raise SystemExit('validated cache output contains control characters')
    print(value)
PY
}

[[ -x "$ORCHESTRATOR" || -f "$ORCHESTRATOR" ]] || {
  echo "CYF orchestrator is unavailable: $ORCHESTRATOR" >&2
  exit 80
}

CACHE_LOOKUP_FILE=$(mktemp /tmp/cyf-d09-cache-lookup-XXXXXXXX)
CACHE_ERROR_FILE=$(mktemp /tmp/cyf-d09-cache-error-XXXXXXXX)
set +e
python3 "$ORCHESTRATOR" evidence-get \
  --tree-sha "$TREE_SHA" --selector "$SELECTOR" \
  --fixture-digest "$FIXTURE_DIGEST" > "$CACHE_LOOKUP_FILE" 2> "$CACHE_ERROR_FILE"
cache_status=$?
set -e

if [[ $cache_status == 0 ]]; then
  cached_record_artifact=$(extract_cached_artifact "$CACHE_LOOKUP_FILE")
  set +e
  mapfile -t validated_cache < <(validate_cached_artifact "$CACHE_LOOKUP_FILE" 2> "$CACHE_ERROR_FILE")
  cache_validation_status=$?
  set -e
  if [[ $cache_validation_status == 0 && ${#validated_cache[@]} == 4 ]]; then
    safe_local_summary_dir "${validated_cache[0]}"
    CACHE_VALIDATION=valid
    CACHE_DETAIL=validated_cached_summary_xml_digest_counts
    EVIDENCE_REUSED=yes
    GRADLE_STARTED=no
    GRADLE_EXIT=0
    FRESH_RESULT_XML=no
    FRESH_REPORT=no
    TESTS=$EXPECTED_TESTS
    FAILURES=0
    ERRORS=0
    SKIPPED=0
    FINAL_EVIDENCE_COMMITTED=yes
    CACHED_ARTIFACT=${validated_cache[0]}
    CACHED_SUMMARY_SHA256=${validated_cache[1]}
    CACHED_RESULT_XML=${validated_cache[2]}
    RESULT_XML_SHA256=${validated_cache[3]}
    write_summary
    rm -f -- "$CACHE_LOOKUP_FILE" "$CACHE_ERROR_FILE"
    print_summary
    exit 0
  fi
  safe_local_summary_dir "$cached_record_artifact"
  CACHE_VALIDATION=invalid
  CACHE_DETAIL=$(tr '\r\n\t' '   ' < "$CACHE_ERROR_FILE" | sed 's/[[:space:]][[:space:]]*/_/g' | cut -c1-240)
  [[ -n "$CACHE_DETAIL" ]] || CACHE_DETAIL=invalid_cached_artifact
  GRADLE_EXIT=86
  write_summary
  rm -f -- "$CACHE_LOOKUP_FILE" "$CACHE_ERROR_FILE"
  print_summary
  exit 86
elif [[ $cache_status == 1 && $(head -n 1 "$CACHE_LOOKUP_FILE" 2>/dev/null || true) == EVIDENCE_MISS ]]; then
  CACHE_VALIDATION=miss
  CACHE_DETAIL=final_key_not_cached
else
  safe_local_summary_dir none
  CACHE_VALIDATION=lookup_error
  CACHE_DETAIL=$(tr '\r\n\t' '   ' < "$CACHE_ERROR_FILE" | sed 's/[[:space:]][[:space:]]*/_/g' | cut -c1-240)
  [[ -n "$CACHE_DETAIL" ]] || CACHE_DETAIL="evidence_get_exit_$cache_status"
  GRADLE_EXIT=86
  write_summary
  rm -f -- "$CACHE_LOOKUP_FILE" "$CACHE_ERROR_FILE"
  print_summary
  exit 86
fi
rm -f -- "$CACHE_LOOKUP_FILE" "$CACHE_ERROR_FILE"

# A final-key MISS owns only these named D09 artifacts. Unknown files in an
# explicitly reused directory are intentionally preserved.
clean_known_evidence_files
rm -f -- "$result_xml" "$report_path"
printf '%s\n' "$EVIDENCE_DIR" > "$LATEST_EVIDENCE_FILE"

run_provisional_gradle() {
  local jdbc_url=$1
  local expected_datadir=$2
  set +e
  env \
    D09_MYSQL_URL="$jdbc_url" \
    D09_MYSQL_USER="$JDBC_USER" \
    D09_MYSQL_PASSWORD="$JDBC_PASSWORD" \
    D09_MYSQL_EXPECTED_PORT="$PORT" \
    D09_MYSQL_EXPECTED_DATADIR="$expected_datadir" \
    D09_MYSQL_EXPECTED_VERSION="$EXPECTED_VERSION" \
    D09_CONTRACT_RESULT_XML="$result_xml" \
    D09_CONTRACT_REPORT_PATH="$report_path" \
    python3 "$ORCHESTRATOR" gradle \
      --heavy --cwd "$ROOT" \
      --tree-sha "$TREE_SHA" --selector "$SELECTOR" \
      --fixture-digest "$PROVISIONAL_FIXTURE_DIGEST" --artifact "$EVIDENCE_DIR" \
      D09 -- \
      ./gradlew --no-daemon --max-workers=1 \
      -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8' \
      -PrepoUsername=unused -PrepoPassword=unused \
      :agent:jia-agent-service:cleanTest :agent:jia-agent-service:test \
      --tests "$SELECTOR" > "$GRADLE_LOG" 2>&1
  GRADLE_EXIT=$?
  set -e
  if grep -Fq 'GRADLE_LOCK_ACQUIRED' "$GRADLE_LOG"; then
    GRADLE_STARTED=yes
  else
    GRADLE_STARTED=no
  fi
}

collect_result() {
  local counts_status=1
  if [[ -f "$result_xml" ]]; then
    FRESH_RESULT_XML=yes
    cp -- "$result_xml" "$EVIDENCE_DIR/$RESULT_XML_NAME"
    RESULT_XML_ARTIFACT=$RESULT_XML_NAME
    RESULT_XML_SHA256=$(sha256sum "$EVIDENCE_DIR/$RESULT_XML_NAME" | awk '{print $1}')
    set +e
    python3 - "$result_xml" "$EXPECTED_TESTS" > "$EVIDENCE_DIR/test-counts.txt" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
counts = {name: int(root.attrib.get(name, '-1')) for name in ('tests', 'failures', 'errors', 'skipped')}
for name, value in counts.items():
    print(f'{name}={value}')
expected = int(sys.argv[2])
raise SystemExit(0 if counts == {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0} else 1)
PY
    counts_status=$?
    set -e
    if [[ -s "$EVIDENCE_DIR/test-counts.txt" ]]; then
      TESTS=$(sed -n 's/^tests=//p' "$EVIDENCE_DIR/test-counts.txt" | tail -n 1)
      FAILURES=$(sed -n 's/^failures=//p' "$EVIDENCE_DIR/test-counts.txt" | tail -n 1)
      ERRORS=$(sed -n 's/^errors=//p' "$EVIDENCE_DIR/test-counts.txt" | tail -n 1)
      SKIPPED=$(sed -n 's/^skipped=//p' "$EVIDENCE_DIR/test-counts.txt" | tail -n 1)
    fi
  fi
  if [[ -f "$report_path" ]]; then
    FRESH_REPORT=yes
    cp -- "$report_path" "$EVIDENCE_DIR/$REPORT_COPY_NAME"
  fi
  if [[ $GRADLE_EXIT == 0 && $counts_status != 0 ]]; then
    GRADLE_EXIT=85
  fi
  write_summary
}

teardown_contract_valid() {
  local teardown_file="$EVIDENCE_DIR/mysql-teardown.txt"
  [[ -f "$teardown_file" ]] \
    && grep -Fqx 'base_exists_after_remove=no' "$teardown_file" \
    && grep -Fqx 'isolated_port_listening_after_shutdown=no' "$teardown_file" \
    && grep -Fqx 'host_3306_listener_unchanged=yes' "$teardown_file" \
    && grep -Fqx 'host_33060_listener_unchanged=yes' "$teardown_file"
}

finalize_execution() {
  local teardown_status=$1
  local current_tree_after current_fixture_after dirty_after put_status
  current_tree_after=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')
  current_fixture_after=$(compute_fixture_digest)
  dirty_after=$(git -C "$ROOT" status --porcelain)
  if [[ ${D09_CONTRACT_NO_MYSQL:-0} == 1 ]]; then dirty_after=; fi
  if [[ $GRADLE_EXIT == 0 && ( "$current_tree_after" != "$TREE_SHA" \
      || "$current_fixture_after" != "$FIXTURE_DIGEST" || -n "$dirty_after" ) ]]; then
    GRADLE_EXIT=87
    CACHE_DETAIL=source_or_fixture_changed_during_execution
  fi
  if [[ $GRADLE_EXIT == 0 && ( $teardown_status != 0 || ! teardown_contract_valid ) ]]; then
    GRADLE_EXIT=84
    CACHE_DETAIL=mysql_teardown_contract_invalid
  fi
  if [[ $GRADLE_EXIT == 0 && $FRESH_RESULT_XML == yes \
      && $TESTS == "$EXPECTED_TESTS" && $FAILURES == 0 && $ERRORS == 0 && $SKIPPED == 0 ]]; then
    # Publish a validator-complete artifact before making the final reusable
    # record visible. If evidence-put fails, the summary is immediately reverted.
    FINAL_EVIDENCE_COMMITTED=yes
    write_summary
    set +e
    python3 "$ORCHESTRATOR" evidence-put D09 \
      --tree-sha "$TREE_SHA" --selector "$SELECTOR" \
      --fixture-digest "$FIXTURE_DIGEST" --result accepted \
      --command "$FINAL_COMMAND" --artifact "$EVIDENCE_DIR" >> "$GRADLE_LOG" 2>&1
    put_status=$?
    set -e
    if [[ $put_status != 0 ]]; then
      FINAL_EVIDENCE_COMMITTED=no
      GRADLE_EXIT=$put_status
      CACHE_DETAIL=final_evidence_put_failed
      write_summary
    fi
  else
    FINAL_EVIDENCE_COMMITTED=no
    write_summary
  fi
  print_summary
  if [[ $GRADLE_EXIT == 0 && $FINAL_EVIDENCE_COMMITTED == yes ]]; then
    return 0
  fi
  if [[ "$GRADLE_EXIT" =~ ^[0-9]+$ && $GRADLE_EXIT -ne 0 ]]; then
    return "$GRADLE_EXIT"
  fi
  return 85
}

# Dependency-free A-G contract runs stop here: the fake orchestrator owns XML
# generation and no MySQL or Rabbit binary is inspected or started.
if [[ ${D09_CONTRACT_NO_MYSQL:-0} == 1 ]]; then
  PORT=13309
  run_provisional_gradle 'jdbc:mysql://127.0.0.1:13309/d09_contract' '/tmp/d09-contract-data'
  collect_result
  cat > "$EVIDENCE_DIR/mysql-teardown.txt" <<'EOF_TEARDOWN'
contract_no_mysql=yes
base_exists_after_remove=no
isolated_port_listening_after_shutdown=no
host_3306_listener_unchanged=yes
host_33060_listener_unchanged=yes
EOF_TEARDOWN
  set +e
  finalize_execution 0
  result_status=$?
  set -e
  exit "$result_status"
fi

BASE=$(mktemp -d /tmp/cyf-d09-mysql-XXXXXXXX)
BASE_REAL=$(realpath "$BASE")
DATA="$BASE/data"
RUN="$BASE/run"
TMP="$BASE/tmp"
LOG_DIR="$BASE/log"
SOCKET="$RUN/mysql.sock"
PIDFILE="$RUN/mysqld.pid"
PORT=${D09_MYSQL_PORT:-$(python3 - <<'PY'
import socket
while True:
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    if port not in (3306, 33060):
        print(port)
        break
PY
)}
INIT_LOG="$LOG_DIR/mysql-initialize.log"
SERVER_LOG="$LOG_DIR/mysql-server.log"
HOST_3306_BEFORE=$(ss -ltnp '( sport = :3306 )' 2>/dev/null || true)
HOST_33060_BEFORE=$(ss -ltnp '( sport = :33060 )' 2>/dev/null || true)
MYSQL_PID=''
CLEANED=0
mkdir -p "$DATA" "$RUN" "$TMP" "$LOG_DIR"
printf '%s\n' "$OWNER_TOKEN" > "$BASE/.cyf-d09-owner"

owned_base() {
  [[ "$BASE_REAL" == /tmp/cyf-d09-mysql-* \
    && -f "$BASE/.cyf-d09-owner" \
    && "$(cat "$BASE/.cyf-d09-owner" 2>/dev/null)" == "$OWNER_TOKEN" \
    && "$(realpath "$BASE" 2>/dev/null)" == "$BASE_REAL" ]]
}

owned_mysql_process() {
  local pid=${1:-}
  [[ "$pid" =~ ^[0-9]+$ && -r "/proc/$pid/cmdline" && -e "/proc/$pid/exe" ]] || return 1
  [[ "$(realpath "/proc/$pid/exe" 2>/dev/null)" == "$(realpath "$MYSQLD")" ]] || return 1
  local args
  args=$(tr '\0' '\n' < "/proc/$pid/cmdline")
  grep -Fqx -- "--datadir=$DATA" <<< "$args" \
    && grep -Fqx -- "--port=$PORT" <<< "$args" \
    && grep -Fqx -- "--socket=$SOCKET" <<< "$args" \
    && grep -Fqx -- "--pid-file=$PIDFILE" <<< "$args"
}

teardown_mysql() {
  local cleanup_status=0 local_pid schemas schema runtime_log host_3306_after host_33060_after
  if [[ $CLEANED == 1 ]]; then return 0; fi
  CLEANED=1
  {
    echo "isolated_base=$BASE_REAL"
    echo "isolated_pid=${MYSQL_PID:-unknown}"
    local_pid=$MYSQL_PID
    if [[ -z "$local_pid" && -f "$PIDFILE" ]]; then local_pid=$(cat "$PIDFILE" 2>/dev/null); fi
    if owned_mysql_process "$local_pid"; then
      if [[ -S "$SOCKET" ]]; then
        schemas=$("$MYSQL" --no-defaults -uroot -S "$SOCKET" -Nse \
          "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'd09\\_%' ESCAPE '\\\\'" 2>/dev/null || true)
        while IFS= read -r schema; do
          [[ -z "$schema" ]] || "$MYSQL" --no-defaults -uroot -S "$SOCKET" \
            -e "DROP DATABASE IF EXISTS \`$schema\`" || cleanup_status=1
        done <<< "$schemas"
        "$MYSQL" --no-defaults -uroot -S "$SOCKET" \
          -e "DROP USER IF EXISTS '$JDBC_USER'@'127.0.0.1'" || cleanup_status=1
        "$MYSQLADMIN" --no-defaults --protocol=SOCKET --socket="$SOCKET" \
          -uroot shutdown || cleanup_status=1
      fi
      for _ in $(seq 1 100); do
        kill -0 "$local_pid" 2>/dev/null || break
        sleep 0.1
      done
      if kill -0 "$local_pid" 2>/dev/null; then
        if owned_mysql_process "$local_pid"; then
          kill -TERM "$local_pid" 2>/dev/null || cleanup_status=1
        else
          echo "refused_signal_pid_identity_changed=$local_pid"
          cleanup_status=1
        fi
      fi
    elif [[ -n "$local_pid" ]] && kill -0 "$local_pid" 2>/dev/null; then
      echo "refused_signal_unowned_pid=$local_pid"
      cleanup_status=1
    fi
    for runtime_log in "$INIT_LOG" "$SERVER_LOG"; do
      if [[ -f "$runtime_log" ]]; then
        cp -f -- "$runtime_log" "$EVIDENCE_DIR/$(basename -- "$runtime_log")" || cleanup_status=1
      fi
    done
    if owned_base; then
      rm -rf -- "$BASE_REAL"
    else
      echo "refused_remove_unowned_base=$BASE_REAL"
      cleanup_status=1
    fi
    echo "base_exists_after_remove=$(if [[ -e $BASE_REAL ]]; then echo yes; else echo no; fi)"
    echo "isolated_port_listening_after_shutdown=$(if ss -ltnH "( sport = :$PORT )" | grep -q .; then echo yes; else echo no; fi)"
    host_3306_after=$(ss -ltnp '( sport = :3306 )' 2>/dev/null || true)
    host_33060_after=$(ss -ltnp '( sport = :33060 )' 2>/dev/null || true)
    echo "host_3306_listener_unchanged=$(if [[ $host_3306_after == "$HOST_3306_BEFORE" ]]; then echo yes; else echo no; fi)"
    echo "host_33060_listener_unchanged=$(if [[ $host_33060_after == "$HOST_33060_BEFORE" ]]; then echo yes; else echo no; fi)"
    printf 'host_3306_before=%q\nhost_3306_after=%q\n' "$HOST_3306_BEFORE" "$host_3306_after"
    printf 'host_33060_before=%q\nhost_33060_after=%q\n' "$HOST_33060_BEFORE" "$host_33060_after"
  } > "$EVIDENCE_DIR/mysql-teardown.txt" 2>&1
  if ss -ltnH "( sport = :$PORT )" | grep -q .; then cleanup_status=1; fi
  if [[ $(ss -ltnp '( sport = :3306 )' 2>/dev/null || true) != "$HOST_3306_BEFORE" ]]; then cleanup_status=1; fi
  if [[ $(ss -ltnp '( sport = :33060 )' 2>/dev/null || true) != "$HOST_33060_BEFORE" ]]; then cleanup_status=1; fi
  return "$cleanup_status"
}

on_exit() {
  local original_status=$? teardown_status=0
  trap - EXIT INT TERM
  set +e
  teardown_mysql
  teardown_status=$?
  if [[ ! -f "$EVIDENCE_DIR/result-summary.txt" ]]; then
    GRADLE_EXIT=$original_status
    CACHE_DETAIL=terminated_before_result_collection
    write_summary
  fi
  if [[ $original_status != 0 ]]; then exit "$original_status"; fi
  exit "$teardown_status"
}
trap on_exit EXIT INT TERM

[[ -x "$MYSQLD" && -x "$MYSQL" && -x "$MYSQLADMIN" ]] || {
  echo "MySQL 8.0.21 binaries are unavailable below $MYSQL_BASE" >&2; exit 80;
}
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1024 && "$PORT" -le 65535 \
  && "$PORT" -ne 3306 && "$PORT" -ne 33060 ]] || {
  echo "Unsafe isolated MySQL port: $PORT" >&2; exit 81;
}
if ss -ltnH "( sport = :$PORT )" | grep -q .; then
  echo "Refusing occupied isolated port $PORT" >&2; exit 82
fi
binary_version=$($MYSQLD --no-defaults --version)
[[ "$binary_version" == *"Ver $EXPECTED_VERSION"* ]] || {
  echo "Expected MySQL $EXPECTED_VERSION, got: $binary_version" >&2; exit 83;
}

if [[ $(id -u) == 0 ]] && id mysql >/dev/null 2>&1; then
  MYSQL_OS_USER=mysql
  chown -R mysql:mysql "$BASE"
  RUN_AS=(runuser -u mysql --)
else
  MYSQL_OS_USER=$(id -un)
  RUN_AS=()
fi

{
  echo "started_at=$(date --iso-8601=seconds)"
  echo "mysql_binary_version=$binary_version"
  echo "mysql_os_user=$MYSQL_OS_USER"
  echo "port=$PORT"
  echo "datadir=$DATA"
  echo "socket=$SOCKET"
  echo "evidence_dir=$EVIDENCE_DIR"
  printf 'host_3306_listener_before=%q\n' "$HOST_3306_BEFORE"
  printf 'host_33060_listener_before=%q\n' "$HOST_33060_BEFORE"
} > "$EVIDENCE_DIR/mysql-instance-before.txt"

"${RUN_AS[@]}" "$MYSQLD" --no-defaults \
  --basedir="$MYSQL_BASE" --datadir="$DATA" --initialize-insecure \
  --skip-log-bin --innodb-buffer-pool-size=64M --innodb-log-file-size=16M \
  --log-error="$INIT_LOG"

"${RUN_AS[@]}" "$MYSQLD" --no-defaults \
  --basedir="$MYSQL_BASE" --datadir="$DATA" \
  --bind-address=127.0.0.1 --port="$PORT" --socket="$SOCKET" \
  --pid-file="$PIDFILE" --log-error="$SERVER_LOG" --daemonize \
  --skip-log-bin --mysqlx=OFF --performance-schema=OFF --general-log=OFF \
  --innodb-buffer-pool-size=64M --innodb-log-file-size=16M \
  --innodb-lock-wait-timeout=5 --max-connections=40 --thread-cache-size=0 \
  --table-open-cache=128 --key-buffer-size=8M --tmp-table-size=16M \
  --max-heap-table-size=16M --tmpdir="$TMP" --skip-name-resolve --local-infile=OFF

for _ in $(seq 1 200); do
  if [[ -S "$SOCKET" ]] && "$MYSQLADMIN" --no-defaults --protocol=SOCKET \
      --socket="$SOCKET" -uroot ping >/dev/null 2>&1; then break; fi
  sleep 0.1
done
[[ -S "$SOCKET" ]] && "$MYSQLADMIN" --no-defaults --protocol=SOCKET \
  --socket="$SOCKET" -uroot ping >/dev/null
MYSQL_PID=$(cat "$PIDFILE")
owned_mysql_process "$MYSQL_PID" || {
  echo "Isolated MySQL PID identity mismatch: $MYSQL_PID" >&2; exit 84;
}
version=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT VERSION()')
actual_port=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT @@port')
actual_datadir=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT @@datadir')
[[ "$version" == "$EXPECTED_VERSION"* && "$actual_port" == "$PORT" \
  && "$actual_datadir" == "$DATA/" ]] || {
  echo "Isolated MySQL identity mismatch: version=$version port=$actual_port datadir=$actual_datadir" >&2
  exit 84
}
$MYSQL --no-defaults -uroot -S "$SOCKET" -e \
  "CREATE USER '$JDBC_USER'@'127.0.0.1' IDENTIFIED BY '$JDBC_PASSWORD';
   GRANT ALL PRIVILEGES ON *.* TO '$JDBC_USER'@'127.0.0.1'; FLUSH PRIVILEGES;"

{
  cat "$EVIDENCE_DIR/mysql-instance-before.txt"
  echo "version=$version"
  echo "actual_port=$actual_port"
  echo "actual_datadir=$actual_datadir"
  echo "instance_pid=$MYSQL_PID"
  echo "pid_identity_verified=yes"
} > "$EVIDENCE_DIR/mysql-instance.txt"

cd "$ROOT"
run_provisional_gradle \
  "jdbc:mysql://127.0.0.1:$PORT/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000" \
  "$DATA"
collect_result
set +e
teardown_mysql
teardown_status=$?
set -e
set +e
finalize_execution "$teardown_status"
result_status=$?
set -e
exit "$result_status"
