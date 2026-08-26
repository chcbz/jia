#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)
MYSQL_BASE=${D01_MYSQL_BASE:-/home/isp/apps/mysql}
MYSQLD="$MYSQL_BASE/bin/mysqld"
MYSQL="$MYSQL_BASE/bin/mysql"
MYSQLADMIN="$MYSQL_BASE/bin/mysqladmin"
EVIDENCE_DIR=${D01_EVIDENCE_DIR:-$(mktemp -d /tmp/cyf-d01-evidence-XXXXXXXX)}
BASE=$(mktemp -d /tmp/cyf-d01-mysql-XXXXXXXX)
DATA="$BASE/data"
RUN="$BASE/run"
TMP="$BASE/tmp"
SOCKET="$RUN/mysql.sock"
PIDFILE="$RUN/mysqld.pid"
PORT=${D01_MYSQL_PORT:-$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    print(sock.getsockname()[1])
PY
)}
INIT_LOG="$EVIDENCE_DIR/mysql-initialize.log"
SERVER_LOG="$EVIDENCE_DIR/mysql-server.log"
GENERAL_LOG="$EVIDENCE_DIR/mysql-general.log"
GRADLE_LOG="$EVIDENCE_DIR/gradle-d01-mysql.log"
JDBC_USER=d01_isolated_test
JDBC_PASSWORD=d01-isolated-only
PRODUCTION_LISTENER_BEFORE=$(ss -ltnp '( sport = :3306 )' 2>/dev/null || true)
MYSQL_PID=''
CLEANED=0

mkdir -p "$EVIDENCE_DIR" "$DATA" "$RUN" "$TMP"
printf '%s\n' "$EVIDENCE_DIR" > /tmp/cyf-d01-latest-evidence

cleanup() {
  local original_status=$?
  local cleanup_status=0
  trap - EXIT INT TERM
  set +e
  if [[ $CLEANED == 1 ]]; then
    exit "$original_status"
  fi
  CLEANED=1
  {
    echo "original_exit=$original_status"
    echo "isolated_base=$BASE"
    echo "isolated_pid=${MYSQL_PID:-unknown}"
    if [[ -S "$SOCKET" ]]; then
      schemas=$("$MYSQL" --no-defaults -uroot -S "$SOCKET" -Nse \
        "SELECT schema_name FROM information_schema.schemata WHERE schema_name LIKE 'd01\\_%' ESCAPE '\\\\'" 2>/dev/null || true)
      while IFS= read -r schema; do
        [[ -z "$schema" ]] || "$MYSQL" --no-defaults -uroot -S "$SOCKET" \
          -e "DROP DATABASE IF EXISTS \`$schema\`" || cleanup_status=1
      done <<< "$schemas"
      "$MYSQL" --no-defaults -uroot -S "$SOCKET" \
        -e "DROP USER IF EXISTS '$JDBC_USER'@'127.0.0.1'" || cleanup_status=1
      "$MYSQLADMIN" --no-defaults --protocol=SOCKET --socket="$SOCKET" \
        -uroot shutdown || cleanup_status=1
    fi
    if [[ -f "$PIDFILE" ]]; then
      pid=$(cat "$PIDFILE" 2>/dev/null)
      if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        kill "$pid" 2>/dev/null || true
        for _ in $(seq 1 100); do
          kill -0 "$pid" 2>/dev/null || break
          sleep 0.1
        done
      fi
    fi
    rm -rf "$BASE"
    echo "base_exists_after_remove=$(if [[ -e $BASE ]]; then echo yes; else echo no; fi)"
    echo "isolated_port_listening_after_shutdown=$(if ss -ltnH "( sport = :$PORT )" | grep -q .; then echo yes; else echo no; fi)"
    production_after=$(ss -ltnp '( sport = :3306 )' 2>/dev/null || true)
    echo "production_3306_listener_unchanged=$(if [[ $production_after == "$PRODUCTION_LISTENER_BEFORE" ]]; then echo yes; else echo no; fi)"
    printf 'production_3306_before=%q\n' "$PRODUCTION_LISTENER_BEFORE"
    printf 'production_3306_after=%q\n' "$production_after"
  } > "$EVIDENCE_DIR/mysql-teardown.txt" 2>&1

  if ss -ltnH "( sport = :$PORT )" | grep -q .; then cleanup_status=1; fi
  if [[ $(ss -ltnp '( sport = :3306 )' 2>/dev/null || true) != "$PRODUCTION_LISTENER_BEFORE" ]]; then
    cleanup_status=1
  fi
  if [[ $original_status != 0 ]]; then
    exit "$original_status"
  fi
  exit "$cleanup_status"
}
trap cleanup EXIT INT TERM

[[ -x "$MYSQLD" && -x "$MYSQL" && -x "$MYSQLADMIN" ]] || {
  echo "MySQL 8.0.21 binaries are unavailable below $MYSQL_BASE" >&2
  exit 80
}
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" -ge 1024 && "$PORT" -le 65535 && "$PORT" -ne 3306 ]] || {
  echo "Unsafe isolated MySQL port: $PORT" >&2
  exit 81
}
if ss -ltnH "( sport = :$PORT )" | grep -q .; then
  echo "Refusing occupied isolated port $PORT" >&2
  exit 82
fi
binary_version=$($MYSQLD --no-defaults --version)
[[ "$binary_version" == *"Ver 8.0.21"* ]] || {
  echo "Expected MySQL 8.0.21, got: $binary_version" >&2
  exit 83
}

if [[ $(id -u) == 0 ]] && id mysql >/dev/null 2>&1; then
  MYSQL_OS_USER=mysql
  chown -R mysql:mysql "$BASE" "$EVIDENCE_DIR"
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
  printf 'production_3306_listener_before=%q\n' "$PRODUCTION_LISTENER_BEFORE"
} > "$EVIDENCE_DIR/mysql-instance-before.txt"

"${RUN_AS[@]}" "$MYSQLD" --no-defaults \
  --basedir="$MYSQL_BASE" --datadir="$DATA" --initialize-insecure \
  --skip-log-bin --innodb-buffer-pool-size=64M --innodb-log-file-size=16M \
  --log-error="$INIT_LOG"

"${RUN_AS[@]}" "$MYSQLD" --no-defaults \
  --basedir="$MYSQL_BASE" --datadir="$DATA" \
  --bind-address=127.0.0.1 --port="$PORT" --socket="$SOCKET" \
  --pid-file="$PIDFILE" --log-error="$SERVER_LOG" --daemonize \
  --skip-log-bin --mysqlx=OFF --performance-schema=OFF \
  --innodb-buffer-pool-size=64M --innodb-log-file-size=16M \
  --max-connections=40 --thread-cache-size=0 --table-open-cache=128 \
  --key-buffer-size=8M --tmp-table-size=16M --max-heap-table-size=16M \
  --tmpdir="$TMP" --skip-name-resolve --local-infile=OFF \
  --general-log=ON --general-log-file="$GENERAL_LOG"

for _ in $(seq 1 200); do
  if [[ -S "$SOCKET" ]] && "$MYSQLADMIN" --no-defaults --protocol=SOCKET \
      --socket="$SOCKET" -uroot ping >/dev/null 2>&1; then
    break
  fi
  sleep 0.1
done
[[ -S "$SOCKET" ]] && "$MYSQLADMIN" --no-defaults --protocol=SOCKET \
  --socket="$SOCKET" -uroot ping >/dev/null
MYSQL_PID=$(cat "$PIDFILE")
version=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT VERSION()')
actual_port=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT @@port')
actual_datadir=$($MYSQL --no-defaults -uroot -S "$SOCKET" -Nse 'SELECT @@datadir')
[[ "$version" == 8.0.21* && "$actual_port" == "$PORT" && "$actual_datadir" == "$DATA"/* ]] || {
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
} > "$EVIDENCE_DIR/mysql-instance.txt"

cd "$ROOT"
set +e
flock /tmp/cyf-gradle.lock env \
  D01_MYSQL_URL="jdbc:mysql://127.0.0.1:$PORT/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8" \
  D01_MYSQL_USER="$JDBC_USER" \
  D01_MYSQL_PASSWORD="$JDBC_PASSWORD" \
  D01_MYSQL_EXPECTED_PORT="$PORT" \
  D01_MYSQL_EXPECTED_DATADIR="$DATA" \
  bash ./gradlew --no-daemon --max-workers=1 \
    -Dorg.gradle.jvmargs='-Xmx384m -Dfile.encoding=UTF-8' \
    -PrepoUsername=unused -PrepoPassword=unused \
    :agent:jia-agent-service:cleanTest :agent:jia-agent-service:test \
    --tests cn.jia.agent.config.AgentCommandTransportSchemaInitializerMySqlTest \
    > "$GRADLE_LOG" 2>&1
gradle_status=$?
set -e

result_xml="$ROOT/agent/jia-agent-service/build/test-results/test/TEST-cn.jia.agent.config.AgentCommandTransportSchemaInitializerMySqlTest.xml"
report_path="$ROOT/agent/jia-agent-service/build/reports/tests/test/index.html"
if [[ -f "$result_xml" ]]; then
  cp "$result_xml" "$EVIDENCE_DIR/"
  python3 - "$result_xml" > "$EVIDENCE_DIR/test-counts.txt" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for name in ('tests', 'failures', 'errors', 'skipped'):
    print(name + '=' + root.attrib.get(name, ''))
PY
fi
fixture_digest=$(sha256sum \
  agent/jia-agent-mapper/src/main/resources/db/schema.sql \
  agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql \
  agent/jia-agent-service/src/main/java/cn/jia/agent/config/AgentCommandTransportSchemaInitializer.java \
  agent/jia-agent-service/src/main/java/cn/jia/agent/config/AgentCommandTransportSchemaConfiguration.java \
  agent/jia-agent-service/src/test/java/cn/jia/agent/config/AgentCommandTransportSchemaInitializerMySqlTest.java \
  | sha256sum | awk '{print $1}')
{
  echo "gradle_exit=$gradle_status"
  echo "gradle_selector=cn.jia.agent.config.AgentCommandTransportSchemaInitializerMySqlTest"
  echo "report_path=$report_path"
  echo "result_xml=$result_xml"
  echo "fixture_digest=$fixture_digest"
  [[ -f "$EVIDENCE_DIR/test-counts.txt" ]] && cat "$EVIDENCE_DIR/test-counts.txt"
} > "$EVIDENCE_DIR/result-summary.txt"
cat "$EVIDENCE_DIR/mysql-instance.txt"
cat "$EVIDENCE_DIR/result-summary.txt"
echo "evidence_dir=$EVIDENCE_DIR"
exit "$gradle_status"
