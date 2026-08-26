#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
RUNNER="$SCRIPT_DIR/run-d09-isolated-mysql.sh"
SELECTOR=cn.jia.agent.service.impl.AgentCommandOperationsMySqlTest
RESULT_XML="$ROOT/agent/jia-agent-service/build/test-results/test/TEST-$SELECTOR.xml"
REPORT_PATH="$ROOT/agent/jia-agent-service/build/reports/tests/test/index.html"

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
require 'D09_MYSQL_EXPECTED_DATADIR="$expected_datadir"'
require 'D09_MYSQL_EXPECTED_VERSION="$EXPECTED_VERSION"'
require 'actual_datadir" == "$DATA/"'
require 'owned_mysql_process "$MYSQL_PID"'
require 'refused_signal_unowned_pid'
require 'refused_remove_unowned_base'
require 'host_3306_listener_unchanged'
require 'host_33060_listener_unchanged'
require 'python3 "$ORCHESTRATOR" evidence-get \'
require 'python3 "$ORCHESTRATOR" gradle \'
require '--fixture-digest "$PROVISIONAL_FIXTURE_DIGEST" --artifact "$EVIDENCE_DIR" \'
require 'python3 "$ORCHESTRATOR" evidence-put D09 \'
require '--fixture-digest "$FIXTURE_DIGEST" --result accepted \'
require 'EXPECTED_TESTS=5'
require 'CACHE_VALIDATION=invalid'
require 'FINAL_EVIDENCE_COMMITTED=yes'
require 'clean_known_evidence_files'
require 'validate_cached_artifact'
require 'teardown_contract_valid'
for field in current_tree current_fixture_digest final_selector provisional_selector_or_fixture \
  evidence_reused cache_validation gradle_started gradle_exit fresh_result_xml fresh_report \
  tests failures errors skipped final_evidence_committed; do
  require "echo \"$field="
done

if grep -Fq -- 'gradle D09 --heavy' "$RUNNER"; then
  fail 'orchestrator Gradle options must precede task_id D09'
fi
if grep -Fq -- '--rerun-tasks' "$RUNNER"; then
  fail 'runner must not use --rerun-tasks'
fi
if grep -Eq '(^|[[:space:]])flock([[:space:]]|$)' "$RUNNER"; then
  fail 'runner must serialize Gradle only through the CYF orchestrator'
fi
if grep -Eq '(^|[[:space:]])(pkill|killall)([[:space:]]|$)' "$RUNNER"; then
  fail 'runner must not signal non-owned processes'
fi

get_line=$(grep -nFm1 -- 'python3 "$ORCHESTRATOR" evidence-get \' "$RUNNER" | cut -d: -f1)
clean_line=$(grep -nFx -- 'clean_known_evidence_files' "$RUNNER" | cut -d: -f1)
gradle_line=$(grep -nFm1 -- 'python3 "$ORCHESTRATOR" gradle \' "$RUNNER" | cut -d: -f1)
put_line=$(grep -nFm1 -- 'python3 "$ORCHESTRATOR" evidence-put D09 \' "$RUNNER" | cut -d: -f1)
if [[ -z "$get_line" || -z "$clean_line" || -z "$gradle_line" || -z "$put_line" ]] \
    || ! ((get_line < clean_line && clean_line < gradle_line && gradle_line < put_line)); then
  fail 'source order must be final evidence-get, known-file cleanup, provisional Gradle, final evidence-put'
fi

bash -n "$RUNNER"
printf '%s\n' 'D09 isolated MySQL runner static contract: PASS'

TEST_ROOT=$(mktemp -d /tmp/cyf-d09-two-phase-contract-XXXXXXXX)
cleanup_contract_test() {
  rm -rf -- "$TEST_ROOT"
  rm -f -- "$RESULT_XML" "$REPORT_PATH"
}
trap cleanup_contract_test EXIT

FAKE_ORCHESTRATOR="$TEST_ROOT/fake-orchestrator.py"
cat > "$FAKE_ORCHESTRATOR" <<'PY'
#!/usr/bin/env python3
import json
import os
import pathlib
import sys
import xml.etree.ElementTree as ET

args = sys.argv[1:]
command = args[0] if args else ''
events = pathlib.Path(os.environ['FAKE_EVENTS'])
state_path = pathlib.Path(os.environ['FAKE_STATE'])

def option(name):
    try:
        return args[args.index(name) + 1]
    except (ValueError, IndexError):
        raise SystemExit(f'missing option {name}')

def event(value):
    with events.open('a', encoding='utf-8') as handle:
        handle.write(value + '\n')

def state():
    return json.loads(state_path.read_text(encoding='utf-8'))

if command == 'evidence-get':
    current = {
        'tree_sha': option('--tree-sha'),
        'selector': option('--selector'),
        'fixture_digest': option('--fixture-digest'),
    }
    state_path.write_text(json.dumps(current), encoding='utf-8')
    event('get')
    if os.environ.get('FAKE_CACHE_MODE', 'miss') == 'miss':
        print('EVIDENCE_MISS')
        raise SystemExit(1)
    record = dict(current)
    record.update({
        'task_id': 'D09',
        'result': 'accepted',
        'command': 'cached contract command',
        'artifact': os.environ['FAKE_CACHE_ARTIFACT'],
        'recorded_at': '2026-08-26T00:00:00+08:00',
    })
    print('EVIDENCE_HIT')
    print(json.dumps(record, indent=2))
    raise SystemExit(0)

if command == 'gradle':
    current = state()
    fixture = option('--fixture-digest')
    selector = option('--selector')
    tree = option('--tree-sha')
    if fixture == current['fixture_digest']:
        raise SystemExit('provisional Gradle reused final fixture key')
    if selector != current['selector'] or tree != current['tree_sha']:
        raise SystemExit('provisional Gradle changed final tree/selector binding')
    event('gradle:' + fixture)
    mode = os.environ.get('FAKE_GRADLE_MODE', 'denied')
    if mode == 'denied':
        print('Gradle denied by contract gate')
        raise SystemExit(77)
    print('GRADLE_LOCK_ACQUIRED task=D09 pid=contract')
    xml_path = pathlib.Path(os.environ['D09_CONTRACT_RESULT_XML'])
    report_path = pathlib.Path(os.environ['D09_CONTRACT_REPORT_PATH'])
    if mode != 'no-xml':
        xml_path.parent.mkdir(parents=True, exist_ok=True)
        counts = {
            'fail': (5, 1, 0, 0),
            'no-test': (0, 0, 0, 0),
            'all-skipped': (5, 0, 0, 5),
            'pass': (5, 0, 0, 0),
        }[mode]
        xml_path.write_text(
            '<testsuite tests="{}" failures="{}" errors="{}" skipped="{}"/>\n'.format(*counts),
            encoding='utf-8',
        )
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text('D09 CONTRACT REPORT\n', encoding='utf-8')
    raise SystemExit(17 if mode == 'fail' else 0)

if command == 'evidence-put':
    current = state()
    if option('--tree-sha') != current['tree_sha'] \
            or option('--selector') != current['selector'] \
            or option('--fixture-digest') != current['fixture_digest']:
        raise SystemExit('final evidence-put key mismatch')
    if option('--result') != 'accepted':
        raise SystemExit('final evidence-put was not accepted')
    artifact = pathlib.Path(option('--artifact'))
    summary = artifact / 'result-summary.txt'
    counts = artifact / 'test-counts.txt'
    xml_path = artifact / ('TEST-' + current['selector'] + '.xml')
    if not summary.is_file() or not counts.is_file() or not xml_path.is_file():
        raise SystemExit('final evidence-put occurred before artifact creation')
    values = dict(line.split('=', 1) for line in summary.read_text().splitlines() if '=' in line)
    expected = {'tests': '5', 'failures': '0', 'errors': '0', 'skipped': '0'}
    if any(values.get(k) != v for k, v in expected.items()):
        raise SystemExit('final evidence-put occurred before exact count validation')
    if values.get('gradle_exit') != '0' or values.get('final_evidence_committed') != 'yes':
        raise SystemExit('final evidence-put occurred before accepted summary preparation')
    root = ET.parse(xml_path).getroot()
    actual = {name: root.attrib.get(name) for name in expected}
    if actual != expected:
        raise SystemExit('final evidence-put XML counts are invalid')
    event('put')
    print('EVIDENCE_STORED key=contract reusable=true')
    raise SystemExit(0)

raise SystemExit(f'unexpected fake orchestrator command: {command}')
PY
chmod 0755 "$FAKE_ORCHESTRATOR"

FAKE_MYSQL_BASE="$TEST_ROOT/mysql-must-not-run"
mkdir -p "$FAKE_MYSQL_BASE/bin"
for binary in mysqld mysql mysqladmin; do
  cat > "$FAKE_MYSQL_BASE/bin/$binary" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$0 $*" >> "${FAKE_MYSQL_INVOCATIONS:?}"
exit 99
SH
  chmod 0755 "$FAKE_MYSQL_BASE/bin/$binary"
done
MYSQL_INVOCATIONS="$TEST_ROOT/mysql-invocations.log"
: > "$MYSQL_INVOCATIONS"

compute_fixture_digest() {
  sha256sum \
    "$ROOT/agent/jia-agent-service/src/test/java/cn/jia/agent/service/impl/AgentCommandOperationsMySqlTest.java" \
    "$RUNNER" \
    "$SCRIPT_DIR/test-run-d09-isolated-mysql-contract.sh" \
    "$ROOT/agent/jia-agent-mapper/src/main/java/cn/jia/agent/mapper/AgentCommandOperationsMapper.java" \
    "$ROOT/agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandOperationsServiceImpl.java" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandBrokerRedrivePolicy.java" \
    | sha256sum | awk '{print $1}'
}
TREE_SHA=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')
FIXTURE_DIGEST=$(compute_fixture_digest)

run_miss_case() {
  local name=$1 mode=$2 expected_status=$3 owner_token=$4 evidence_dir=$5
  local case_dir="$TEST_ROOT/$name"
  mkdir -p "$case_dir"
  : > "$case_dir/events"
  set +e
  FAKE_EVENTS="$case_dir/events" FAKE_STATE="$case_dir/state.json" \
    FAKE_CACHE_MODE=miss FAKE_GRADLE_MODE="$mode" \
    FAKE_MYSQL_INVOCATIONS="$MYSQL_INVOCATIONS" \
    CYF_ORCHESTRATOR="$FAKE_ORCHESTRATOR" D09_CONTRACT_NO_MYSQL=1 \
    D09_OWNER_TOKEN="$owner_token" D09_MYSQL_BASE="$FAKE_MYSQL_BASE" \
    D09_EVIDENCE_DIR="$evidence_dir" D09_LATEST_EVIDENCE_FILE="$case_dir/latest" \
    "$RUNNER" > "$case_dir/output.log" 2>&1
  local status=$?
  set -e
  [[ $status == "$expected_status" ]] || {
    cat "$case_dir/output.log" >&2
    fail "$name exit was $status, expected $expected_status"
  }
  [[ -f "$evidence_dir/result-summary.txt" ]] || fail "$name summary missing"
}

assert_no_put() {
  local events=$1
  ! grep -Fqx 'put' "$events" || fail "unexpected final evidence-put in $events"
}

# A. Final MISS + provisional Gradle denial/no XML: summary, no final commit.
A_DIR="$TEST_ROOT/A-evidence"
run_miss_case A denied 77 owner-A "$A_DIR"
grep -Fqx 'gradle_started=no' "$A_DIR/result-summary.txt" || fail 'A did not record denied Gradle as not started'
grep -Fqx 'fresh_result_xml=no' "$A_DIR/result-summary.txt" || fail 'A reported XML'
grep -Fqx 'final_evidence_committed=no' "$A_DIR/result-summary.txt" || fail 'A committed final evidence'
assert_no_put "$TEST_ROOT/A/events"

# B. Fresh failing XML: retain failure counts/status and never commit final evidence.
B_DIR="$TEST_ROOT/B-evidence"
run_miss_case B fail 17 owner-B "$B_DIR"
for expected in gradle_started=yes fresh_result_xml=yes tests=5 failures=1 errors=0 skipped=0 gradle_exit=17 final_evidence_committed=no; do
  grep -Fqx "$expected" "$B_DIR/result-summary.txt" || fail "B missing $expected"
done
assert_no_put "$TEST_ROOT/B/events"

# C. Gradle zero with no tests or all skipped must both normalize to exit 85.
C0_DIR="$TEST_ROOT/C0-evidence"
run_miss_case C0 no-test 85 owner-C0 "$C0_DIR"
grep -Fqx 'tests=0' "$C0_DIR/result-summary.txt" || fail 'C no-test count missing'
grep -Fqx 'gradle_exit=85' "$C0_DIR/result-summary.txt" || fail 'C no-test did not exit 85'
assert_no_put "$TEST_ROOT/C0/events"
CS_DIR="$TEST_ROOT/CS-evidence"
run_miss_case CS all-skipped 85 owner-CS "$CS_DIR"
grep -Fqx 'tests=5' "$CS_DIR/result-summary.txt" || fail 'C skipped test count missing'
grep -Fqx 'skipped=5' "$CS_DIR/result-summary.txt" || fail 'C skipped count missing'
grep -Fqx 'gradle_exit=85' "$CS_DIR/result-summary.txt" || fail 'C all-skipped did not exit 85'
assert_no_put "$TEST_ROOT/CS/events"
C0_PROVISIONAL=$(sed -n 's/^gradle://p' "$TEST_ROOT/C0/events")
CS_PROVISIONAL=$(sed -n 's/^gradle://p' "$TEST_ROOT/CS/events")
[[ -n "$C0_PROVISIONAL" && -n "$CS_PROVISIONAL" && "$C0_PROVISIONAL" != "$CS_PROVISIONAL" \
  && "$C0_PROVISIONAL" != "$FIXTURE_DIGEST" && "$CS_PROVISIONAL" != "$FIXTURE_DIGEST" ]] \
  || fail 'provisional execution keys were not unique and non-final'

# D. Exact 5/0/0/0 commits final evidence once, after fake put validates artifacts/counts.
D_DIR="$TEST_ROOT/D-evidence"
run_miss_case D pass 0 owner-D "$D_DIR"
for expected in cache_validation=miss gradle_started=yes gradle_exit=0 fresh_result_xml=yes tests=5 failures=0 errors=0 skipped=0 final_evidence_committed=yes; do
  grep -Fqx "$expected" "$D_DIR/result-summary.txt" || fail "D missing $expected"
done
[[ $(grep -Fxc 'put' "$TEST_ROOT/D/events") == 1 ]] || fail 'D final evidence-put count was not exactly one'
mapfile -t D_EVENTS < "$TEST_ROOT/D/events"
[[ ${D_EVENTS[0]} == get && ${D_EVENTS[1]} == gradle:* && ${D_EVENTS[2]} == put && ${#D_EVENTS[@]} == 3 ]] \
  || fail 'D commit order was not get -> provisional Gradle -> final put'

# Build a validator-complete immutable cached artifact for E/F.
CACHE_ARTIFACT="$TEST_ROOT/cached-artifact"
mkdir -p "$CACHE_ARTIFACT"
cat > "$CACHE_ARTIFACT/TEST-$SELECTOR.xml" <<'XML'
<testsuite tests="5" failures="0" errors="0" skipped="0"/>
XML
CACHE_XML_SHA=$(sha256sum "$CACHE_ARTIFACT/TEST-$SELECTOR.xml" | awk '{print $1}')
cat > "$CACHE_ARTIFACT/result-summary.txt" <<EOF_CACHE
current_tree=$TREE_SHA
current_fixture_digest=$FIXTURE_DIGEST
final_selector=$SELECTOR
provisional_selector_or_fixture=fixture:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
evidence_reused=no
cache_validation=miss
gradle_started=yes
gradle_exit=0
fresh_result_xml=yes
fresh_report=yes
tests=5
failures=0
errors=0
skipped=0
final_evidence_committed=yes
result_xml_artifact=TEST-$SELECTOR.xml
result_xml_sha256=$CACHE_XML_SHA
EOF_CACHE
find "$CACHE_ARTIFACT" -type f -print0 | sort -z | xargs -0 sha256sum > "$TEST_ROOT/cache-before.sha256"

# E. Valid final HIT validates artifact and returns reuse summary without Gradle/MySQL.
E_DIR="$TEST_ROOT/E-local-summary"
mkdir -p "$E_DIR"
printf '%s\n' 'E_SENTINEL' > "$E_DIR/unknown-sentinel"
: > "$TEST_ROOT/E-events"
set +e
FAKE_EVENTS="$TEST_ROOT/E-events" FAKE_STATE="$TEST_ROOT/E-state.json" \
  FAKE_CACHE_MODE=hit FAKE_CACHE_ARTIFACT="$CACHE_ARTIFACT" \
  FAKE_MYSQL_INVOCATIONS="$MYSQL_INVOCATIONS" \
  CYF_ORCHESTRATOR="$FAKE_ORCHESTRATOR" D09_MYSQL_BASE="$FAKE_MYSQL_BASE" \
  D09_EVIDENCE_DIR="$E_DIR" D09_LATEST_EVIDENCE_FILE="$TEST_ROOT/E-latest" \
  "$RUNNER" > "$TEST_ROOT/E-output.log" 2>&1
E_STATUS=$?
set -e
[[ $E_STATUS == 0 ]] || { cat "$TEST_ROOT/E-output.log" >&2; fail "E exit was $E_STATUS"; }
for expected in evidence_reused=yes cache_validation=valid gradle_started=no gradle_exit=0 fresh_result_xml=no tests=5 failures=0 errors=0 skipped=0 final_evidence_committed=yes cached_artifact="$CACHE_ARTIFACT"; do
  grep -Fqx "$expected" "$E_DIR/result-summary.txt" || fail "E missing $expected"
done
[[ $(cat "$TEST_ROOT/E-events") == get ]] || fail 'E invoked Gradle or evidence-put'
grep -Fqx 'E_SENTINEL' "$E_DIR/unknown-sentinel" || fail 'E removed unknown local sentinel'
find "$CACHE_ARTIFACT" -type f -print0 | sort -z | xargs -0 sha256sum > "$TEST_ROOT/cache-after-E.sha256"
cmp -s "$TEST_ROOT/cache-before.sha256" "$TEST_ROOT/cache-after-E.sha256" || fail 'E mutated cached artifact'
[[ ! -s "$MYSQL_INVOCATIONS" ]] || fail 'E or an earlier dependency-free case invoked MySQL'

# F. Stale/tampered HIT fails closed and cannot reuse or overwrite the final key.
INVALID_ARTIFACT="$TEST_ROOT/invalid-artifact"
cp -a "$CACHE_ARTIFACT" "$INVALID_ARTIFACT"
printf '%s\n' '<testsuite tests="5" failures="0" errors="0" skipped="5"/>' > "$INVALID_ARTIFACT/TEST-$SELECTOR.xml"
find "$INVALID_ARTIFACT" -type f -print0 | sort -z | xargs -0 sha256sum > "$TEST_ROOT/invalid-before.sha256"
F_DIR="$TEST_ROOT/F-local-summary"
: > "$TEST_ROOT/F-events"
set +e
FAKE_EVENTS="$TEST_ROOT/F-events" FAKE_STATE="$TEST_ROOT/F-state.json" \
  FAKE_CACHE_MODE=hit FAKE_CACHE_ARTIFACT="$INVALID_ARTIFACT" \
  FAKE_MYSQL_INVOCATIONS="$MYSQL_INVOCATIONS" \
  CYF_ORCHESTRATOR="$FAKE_ORCHESTRATOR" D09_MYSQL_BASE="$FAKE_MYSQL_BASE" \
  D09_EVIDENCE_DIR="$F_DIR" D09_LATEST_EVIDENCE_FILE="$TEST_ROOT/F-latest" \
  "$RUNNER" > "$TEST_ROOT/F-output.log" 2>&1
F_STATUS=$?
set -e
[[ $F_STATUS == 86 ]] || { cat "$TEST_ROOT/F-output.log" >&2; fail "F exit was $F_STATUS, expected 86"; }
for expected in evidence_reused=no cache_validation=invalid gradle_started=no final_evidence_committed=no; do
  grep -Fqx "$expected" "$F_DIR/result-summary.txt" || fail "F missing $expected"
done
[[ $(cat "$TEST_ROOT/F-events") == get ]] || fail 'F invoked Gradle or final evidence-put'
find "$INVALID_ARTIFACT" -type f -print0 | sort -z | xargs -0 sha256sum > "$TEST_ROOT/invalid-after.sha256"
cmp -s "$TEST_ROOT/invalid-before.sha256" "$TEST_ROOT/invalid-after.sha256" || fail 'F mutated invalid cached artifact'

# G. Explicit reused evidence dir removes only known D09 files; sentinel survives.
G_DIR="$TEST_ROOT/G-reused-evidence"
mkdir -p "$G_DIR"
printf '%s\n' 'G_UNKNOWN_SENTINEL' > "$G_DIR/unknown-sentinel"
for known in "TEST-$SELECTOR.xml" index.html d09-test-report-index.html test-counts.txt result-summary.txt \
  gradle-d09-mysql.log mysql-initialize.log mysql-server.log mysql-instance-before.txt mysql-instance.txt mysql-teardown.txt; do
  printf '%s\n' "STALE_$known" > "$G_DIR/$known"
done
mkdir -p "$(dirname "$RESULT_XML")" "$(dirname "$REPORT_PATH")"
printf '%s\n' '<testsuite tests="5" failures="0" errors="0" skipped="0"/>' > "$RESULT_XML"
printf '%s\n' 'STALE ROOT REPORT' > "$REPORT_PATH"
run_miss_case G denied 77 owner-G "$G_DIR"
grep -Fqx 'G_UNKNOWN_SENTINEL' "$G_DIR/unknown-sentinel" || fail 'G removed unknown sentinel'
[[ ! -e "$G_DIR/TEST-$SELECTOR.xml" && ! -e "$G_DIR/index.html" \
  && ! -e "$G_DIR/d09-test-report-index.html" && ! -e "$G_DIR/test-counts.txt" \
  && ! -e "$G_DIR/mysql-initialize.log" && ! -e "$G_DIR/mysql-server.log" \
  && ! -e "$G_DIR/mysql-instance-before.txt" && ! -e "$G_DIR/mysql-instance.txt" ]] \
  || fail 'G retained a known stale task file'
[[ ! -e "$RESULT_XML" && ! -e "$REPORT_PATH" ]] || fail 'G retained stale generated outputs'
! grep -Fq 'STALE_' "$G_DIR/result-summary.txt" "$G_DIR/gradle-d09-mysql.log" "$G_DIR/mysql-teardown.txt" \
  || fail 'G leaked stale known-file content into fresh evidence'
assert_no_put "$TEST_ROOT/G/events"
[[ ! -s "$MYSQL_INVOCATIONS" ]] || fail 'dependency-free A-G contract invoked MySQL'

printf '%s\n' 'D09 isolated MySQL two-phase evidence contract A-G: PASS'
