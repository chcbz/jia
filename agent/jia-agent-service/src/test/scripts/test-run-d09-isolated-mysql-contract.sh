#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RUNNER="$SCRIPT_DIR/run-d09-isolated-mysql.sh"

fail() {
  echo "D09 isolated MySQL runner contract failed: $*" >&2
  exit 1
}

require() {
  grep -Fq -- "$1" "$RUNNER" || fail "missing contract fragment: $1"
}

require 'port not in (3306, 33060)'
require '"$PORT" -ne 3306 && "$PORT" -ne 33060'
require 'D09_MYSQL_EXPECTED_PORT="$PORT"'
require 'D09_MYSQL_EXPECTED_DATADIR="$DATA"'
require 'D09_MYSQL_EXPECTED_VERSION="$EXPECTED_VERSION"'
require 'actual_datadir" == "$DATA/"'
require 'owned_mysql_process "$MYSQL_PID"'
require 'refused_signal_unowned_pid'
require 'refused_remove_unowned_base'
require 'host_3306_listener_unchanged'
require 'host_33060_listener_unchanged'
require 'python3 "$ORCHESTRATOR" gradle \'
require '    --heavy --cwd "$ROOT" \'
require '    --fixture-digest "$FIXTURE_DIGEST" --artifact "$EVIDENCE_DIR" \'
require '    D09 -- \'
require ':agent:jia-agent-service:cleanTest :agent:jia-agent-service:test'
require '--tests "$SELECTOR"'
require '--general-log=OFF'
require 'EXPECTED_TESTS=5'
require 'LATEST_EVIDENCE_FILE=${D09_LATEST_EVIDENCE_FILE:-/tmp/cyf-d09-latest-evidence}'
require 'result_xml="$ROOT/agent/jia-agent-service/build/test-results/test/TEST-$SELECTOR.xml"'
require 'report_path="$ROOT/agent/jia-agent-service/build/reports/tests/test/index.html"'
require 'rm -f -- "$result_xml" "$report_path"'
require 'fresh_result_xml='
require 'current_tree='
require 'current_fixture_digest='

if grep -Fq -- 'gradle D09 --heavy' "$RUNNER"; then
  fail "orchestrator gradle options must precede task_id D09"
fi
orchestrator_line=$(grep -nFm1 -- 'python3 "$ORCHESTRATOR" gradle \' "$RUNNER" | cut -d: -f1)
heavy_line=$(grep -nFm1 -- '    --heavy --cwd "$ROOT" \' "$RUNNER" | cut -d: -f1)
artifact_line=$(grep -nFm1 -- '    --fixture-digest "$FIXTURE_DIGEST" --artifact "$EVIDENCE_DIR" \' "$RUNNER" | cut -d: -f1)
task_id_line=$(grep -nFm1 -- '    D09 -- \' "$RUNNER" | cut -d: -f1)
gradlew_line=$(grep -nFm1 -- '    ./gradlew --no-daemon --max-workers=1 \' "$RUNNER" | cut -d: -f1)
if [[ -z "$orchestrator_line" || -z "$heavy_line" || -z "$artifact_line" || -z "$task_id_line" || -z "$gradlew_line" ]] ||
  ! ((orchestrator_line < heavy_line && heavy_line < artifact_line && artifact_line < task_id_line && task_id_line < gradlew_line)); then
  fail "orchestrator CLI order must be: gradle, options, D09, --, Gradle command"
fi

if grep -Fq -- '--rerun-tasks' "$RUNNER"; then
  fail "runner must not use --rerun-tasks"
fi
if grep -Eq '(^|[[:space:]])flock([[:space:]]|$)' "$RUNNER"; then
  fail "runner must serialize Gradle only through the CYF orchestrator"
fi
if grep -Eq '(^|[[:space:]])(kill|pkill|killall)[[:space:]].*\$MYSQL_PID' "$RUNNER"; then
  fail "runner may signal only the locally revalidated PID variable"
fi

bash -n "$RUNNER"
printf '%s\n' 'D09 isolated MySQL runner static contract: PASS'

TEST_ROOT=$(mktemp -d /tmp/cyf-d09-runner-contract-XXXXXXXX)
FAKE_MYSQL_BASE=$(mktemp -d /tmp/cyf-d09-fake-mysql-XXXXXXXX)
cleanup_contract_test() {
  rm -rf -- "$TEST_ROOT" "$FAKE_MYSQL_BASE"
}
trap cleanup_contract_test EXIT
mkdir -p "$FAKE_MYSQL_BASE/bin"
cat > "$FAKE_MYSQL_BASE/bin/mysqld" <<'FAKE_MYSQLD'
#!/usr/bin/env bash
set -euo pipefail
for arg in "$@"; do
  if [[ "$arg" == --version ]]; then
    echo "fake mysqld Ver 8.0.21 for Linux on x86_64"
    exit 0
  fi
done
for arg in "$@"; do
  if [[ "$arg" == --log-error=* ]]; then
    printf '%s\n' 'D09_FAKE_INITIALIZE_FAILURE_LOG' > "${arg#--log-error=}"
    exit 91
  fi
done
exit 92
FAKE_MYSQLD
cat > "$FAKE_MYSQL_BASE/bin/mysql" <<'FAKE_CLIENT'
#!/usr/bin/env bash
exit 93
FAKE_CLIENT
cp "$FAKE_MYSQL_BASE/bin/mysql" "$FAKE_MYSQL_BASE/bin/mysqladmin"
chmod 0755 "$FAKE_MYSQL_BASE" "$FAKE_MYSQL_BASE/bin" "$FAKE_MYSQL_BASE/bin/"*

NESTED_EVIDENCE="$TEST_ROOT/evidence"
set +e
D09_MYSQL_BASE="$FAKE_MYSQL_BASE" D09_EVIDENCE_DIR="$NESTED_EVIDENCE" \
  D09_LATEST_EVIDENCE_FILE="$TEST_ROOT/latest-evidence" "$RUNNER" > "$TEST_ROOT/runner-output.log" 2>&1
runner_status=$?
set -e
[[ "$runner_status" == 91 ]] || fail "fake initialize status was $runner_status, expected 91"
grep -Fqx 'D09_FAKE_INITIALIZE_FAILURE_LOG' "$NESTED_EVIDENCE/mysql-initialize.log" || \
  fail "initialize failure log was not preserved"
grep -Fqx 'base_exists_after_remove=no' "$NESTED_EVIDENCE/mysql-teardown.txt" || \
  fail "failed initialization did not remove its exact owned BASE"
grep -Fqx 'host_3306_listener_unchanged=yes' "$NESTED_EVIDENCE/mysql-teardown.txt" || \
  fail "host 3306 invariant was not recorded"
grep -Fqx 'host_33060_listener_unchanged=yes' "$NESTED_EVIDENCE/mysql-teardown.txt" || \
  fail "host 33060 invariant was not recorded"
failed_base=$(sed -n 's/^isolated_base=//p' "$NESTED_EVIDENCE/mysql-teardown.txt")
[[ -n "$failed_base" && ! -e "$failed_base" ]] || fail "failed initialization left BASE behind"

set +e
D09_MYSQL_BASE="$FAKE_MYSQL_BASE" D09_EVIDENCE_DIR="$TEST_ROOT/unsafe" \
  D09_LATEST_EVIDENCE_FILE="$TEST_ROOT/latest-unsafe" D09_MYSQL_PORT=33060 "$RUNNER" > "$TEST_ROOT/unsafe-output.log" 2>&1
unsafe_status=$?
set -e
[[ "$unsafe_status" == 81 ]] || fail "host mysqlx port was not rejected: $unsafe_status"
grep -Fq 'Unsafe isolated MySQL port: 33060' "$TEST_ROOT/unsafe-output.log" || \
  fail "unsafe-port rejection was not explicit"

printf '%s\n' 'D09 isolated MySQL initialize-failure/host-port contract: PASS'

# A denied gate must not allow pre-existing task outputs to become evidence.
STALE_RESULT_XML="$PWD/agent/jia-agent-service/build/test-results/test/TEST-cn.jia.agent.service.impl.AgentCommandOperationsMySqlTest.xml"
STALE_REPORT="$PWD/agent/jia-agent-service/build/reports/tests/test/index.html"
mkdir -p -- "$(dirname -- "$STALE_RESULT_XML")" "$(dirname -- "$STALE_REPORT")"
printf '%s\n' '<testsuite tests="5" failures="0" errors="0" skipped="0"/>' > "$STALE_RESULT_XML"
printf '%s\n' 'STALE_D09_REPORT' > "$STALE_REPORT"
GATE_DENIED_ORCHESTRATOR="$TEST_ROOT/gate-denied-orchestrator"
cat > "$GATE_DENIED_ORCHESTRATOR" <<'FAKE_ORCHESTRATOR'
#!/usr/bin/env bash
printf '%s\n' 'D09_GATE_DENIED'
exit 77
FAKE_ORCHESTRATOR
chmod 0755 "$GATE_DENIED_ORCHESTRATOR"

set +e
D09_CONTRACT_GATE_DENIED=1 CYF_ORCHESTRATOR="$GATE_DENIED_ORCHESTRATOR" \
  D09_EVIDENCE_DIR="$TEST_ROOT/gate-denied-evidence" \
  D09_LATEST_EVIDENCE_FILE="$TEST_ROOT/gate-denied-latest" \
  "$RUNNER" > "$TEST_ROOT/gate-denied-output.log" 2>&1
gate_denied_status=$?
set -e
[[ "$gate_denied_status" == 77 ]] || fail "gate denial status was $gate_denied_status, expected 77"
[[ ! -e "$STALE_RESULT_XML" ]] || fail "stale result XML was left in the task output path"
[[ ! -e "$STALE_REPORT" ]] || fail "stale report was left in the task output path"
GATE_SUMMARY="$TEST_ROOT/gate-denied-evidence/result-summary.txt"
grep -Fqx 'gradle_started=no' "$GATE_SUMMARY" || fail "gate denial did not record gradle_started=no"
grep -Fqx 'gradle_exit=77' "$GATE_SUMMARY" || fail "gate denial exit was not recorded"
grep -Fqx 'fresh_result_xml=no' "$GATE_SUMMARY" || fail "stale result XML was marked fresh"
[[ ! -e "$TEST_ROOT/gate-denied-evidence/test-counts.txt" ]] || fail "stale counts were published after gate denial"

printf '%s\n' 'D09 isolated MySQL stale-output/gate-denial freshness contract: PASS'
