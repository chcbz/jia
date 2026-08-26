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
EVIDENCE_DIR=${D09_EVIDENCE_DIR:-$(mktemp -d /tmp/cyf-d09-evidence-XXXXXXXX)}
LATEST_EVIDENCE_FILE=${D09_LATEST_EVIDENCE_FILE:-/tmp/cyf-d09-latest-evidence}
BASE=$(mktemp -d /tmp/cyf-d09-mysql-XXXXXXXX)
BASE_REAL=$(realpath "$BASE")
OWNER_TOKEN=$(python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
)
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
GRADLE_LOG="$EVIDENCE_DIR/gradle-d09-mysql.log"
JDBC_USER=d09_isolated_test
JDBC_PASSWORD=d09-isolated-only
EXPECTED_VERSION=8.0.21
SELECTOR=cn.jia.agent.service.impl.AgentCommandOperationsMySqlTest
EXPECTED_TESTS=5
HOST_3306_BEFORE=$(ss -ltnp '( sport = :3306 )' 2>/dev/null || true)
HOST_33060_BEFORE=$(ss -ltnp '( sport = :33060 )' 2>/dev/null || true)
MYSQL_PID=''
CLEANED=0
TREE_SHA=$(git -C "$ROOT" rev-parse 'HEAD^{tree}')
FIXTURE_DIGEST=$(
  sha256sum \
    "$ROOT/agent/jia-agent-service/src/test/java/cn/jia/agent/service/impl/AgentCommandOperationsMySqlTest.java" \
    "$ROOT/agent/jia-agent-service/src/test/scripts/run-d09-isolated-mysql.sh" \
    "$ROOT/agent/jia-agent-service/src/test/scripts/test-run-d09-isolated-mysql-contract.sh" \
    "$ROOT/agent/jia-agent-mapper/src/main/java/cn/jia/agent/mapper/AgentCommandOperationsMapper.java" \
    "$ROOT/agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandOperationsServiceImpl.java" \
    "$ROOT/agent/jia-agent-service/src/main/java/cn/jia/agent/service/impl/AgentCommandBrokerRedrivePolicy.java" \
    | sha256sum | awk '{print $1}'
)
result_xml="$ROOT/agent/jia-agent-service/build/test-results/test/TEST-$SELECTOR.xml"
report_path="$ROOT/agent/jia-agent-service/build/reports/tests/test/index.html"

mkdir -p "$EVIDENCE_DIR" "$DATA" "$RUN" "$TMP" "$LOG_DIR"
# The result paths are defined before the gate/Gradle boundary. Removing only these
# task outputs makes a denied gate or skipped test unable to publish stale evidence.
rm -f -- "$result_xml" "$report_path"

collect_result() {
  local gradle_status=$1
  local gradle_started=$2
  local counts_status=1
  local fresh_result_xml=no
  local fresh_report=no
  if [[ -f "$result_xml" ]]; then
    fresh_result_xml=yes
    cp -- "$result_xml" "$EVIDENCE_DIR/"
    if python3 - "$result_xml" "$EXPECTED_TESTS" > "$EVIDENCE_DIR/test-counts.txt" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
counts = {name: int(root.attrib.get(name, '-1')) for name in ('tests', 'failures', 'errors', 'skipped')}
for name, value in counts.items():
    print(f'{name}={value}')
expected = int(sys.argv[2])
raise SystemExit(0 if counts == {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0} else 1)
PY
    then
      counts_status=0
    else
      counts_status=$?
    fi
  fi
  if [[ -f "$report_path" ]]; then
    fresh_report=yes
    cp -- "$report_path" "$EVIDENCE_DIR/"
  fi
  if [[ $gradle_status == 0 && $counts_status != 0 ]]; then gradle_status=85; fi
  {
    echo "current_tree=$TREE_SHA"
    echo "tree_sha=$TREE_SHA"
    echo "current_fixture_digest=$FIXTURE_DIGEST"
    echo "fixture_digest=$FIXTURE_DIGEST"
    echo "gradle_started=$gradle_started"
    echo "gradle_exit=$gradle_status"
    echo "gradle_selector=$SELECTOR"
    echo "orchestrator=$ORCHESTRATOR"
    echo "fresh_result_xml=$fresh_result_xml"
    echo "fresh_report=$fresh_report"
    echo "report_path=$report_path"
    echo "result_xml=$result_xml"
    [[ -f "$EVIDENCE_DIR/test-counts.txt" ]] && cat "$EVIDENCE_DIR/test-counts.txt"
  } > "$EVIDENCE_DIR/result-summary.txt"
  [[ -f "$EVIDENCE_DIR/mysql-instance.txt" ]] && cat "$EVIDENCE_DIR/mysql-instance.txt"
  cat "$EVIDENCE_DIR/result-summary.txt"
  echo "evidence_dir=$EVIDENCE_DIR"
  return "$gradle_status"
}

if [[ ${D09_CONTRACT_FRESH_FAILING_XML:-0} == 1 ]]; then
  trap 'rm -rf -- "$BASE"' EXIT
  mkdir -p -- "$(dirname -- "$result_xml")"
  cat > "$result_xml" <<'XML'
<testsuite tests="5" failures="1" errors="0" skipped="0"/>
XML
  if collect_result "${D09_CONTRACT_GRADLE_EXIT:-17}" yes; then
    result_status=0
  else
    result_status=$?
  fi
  exit "$result_status"
fi

if [[ ${D09_CONTRACT_GATE_DENIED:-0} == 1 ]]; then
  trap 'rm -rf -- "$BASE"' EXIT
  set +e
  "$ORCHESTRATOR" gate-denied > "$GRADLE_LOG" 2>&1
  gradle_status=$?
  set -e
  {
    echo "current_tree=$TREE_SHA"
    echo "tree_sha=$TREE_SHA"
    echo "current_fixture_digest=$FIXTURE_DIGEST"
    echo "fixture_digest=$FIXTURE_DIGEST"
    echo "gradle_started=no"
    echo "gradle_exit=$gradle_status"
    echo "fresh_result_xml=no"
    echo "fresh_report=no"
    echo "result_xml=$result_xml"
    echo "report_path=$report_path"
  } > "$EVIDENCE_DIR/result-summary.txt"
  cat "$EVIDENCE_DIR/result-summary.txt"
  echo "evidence_dir=$EVIDENCE_DIR"
  exit "$gradle_status"
fi

printf '%s\n' "$OWNER_TOKEN" > "$BASE/.cyf-d09-owner"
printf '%s\n' "$EVIDENCE_DIR" > "$LATEST_EVIDENCE_FILE"

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

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if [[ $CLEANED == 1 ]]; then exit "$original_status"; fi
  CLEANED=1
  {
    echo "original_exit=$original_status"
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
  if [[ $original_status != 0 ]]; then exit "$original_status"; fi
  exit "$cleanup_status"
}
trap cleanup EXIT INT TERM

[[ -x "$MYSQLD" && -x "$MYSQL" && -x "$MYSQLADMIN" ]] || {
  echo "MySQL 8.0.21 binaries are unavailable below $MYSQL_BASE" >&2; exit 80;
}
[[ -x "$ORCHESTRATOR" || -f "$ORCHESTRATOR" ]] || {
  echo "CYF orchestrator is unavailable: $ORCHESTRATOR" >&2; exit 80;
}
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1024 && "$PORT" -le 65535 \
  && "$PORT" -ne 3306 && "$PORT" -ne 33060 ]] || {
  echo "Unsafe isolated MySQL port: $PORT" >&2; exit 81;
}
if ss -ltnH "( sport = :$PORT )" | grep -q .; then
  echo "Refusing occupied isolated port $PORT" >&2; exit 82;
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
set +e
env \
  D09_MYSQL_URL="jdbc:mysql://127.0.0.1:$PORT/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=15000" \
  D09_MYSQL_USER="$JDBC_USER" \
  D09_MYSQL_PASSWORD="$JDBC_PASSWORD" \
  D09_MYSQL_EXPECTED_PORT="$PORT" \
  D09_MYSQL_EXPECTED_DATADIR="$DATA" \
  D09_MYSQL_EXPECTED_VERSION="$EXPECTED_VERSION" \
  python3 "$ORCHESTRATOR" gradle \
    --heavy --cwd "$ROOT" \
    --tree-sha "$TREE_SHA" --selector "$SELECTOR" \
    --fixture-digest "$FIXTURE_DIGEST" --artifact "$EVIDENCE_DIR" \
    D09 -- \
    ./gradlew --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx384m -XX:MaxMetaspaceSize=192m -Dfile.encoding=UTF-8' \
    -PrepoUsername=unused -PrepoPassword=unused \
    :agent:jia-agent-service:cleanTest :agent:jia-agent-service:test \
    --tests "$SELECTOR" > "$GRADLE_LOG" 2>&1
gradle_status=$?
set -e

if collect_result "$gradle_status" yes; then
  result_status=0
else
  result_status=$?
fi
exit "$result_status"
