#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
RUNNER="$SCRIPT_DIR/run-d03-isolated-mysql.sh"

fail() {
  echo "D03 isolated MySQL runner contract failed: $*" >&2
  exit 1
}

require_literal() {
  local expected=$1
  grep -Fqx -- "$expected" "$RUNNER" || fail "missing exact line: $expected"
}

require_literal 'LOG_DIR="$BASE/log"'
require_literal 'INIT_LOG="$LOG_DIR/mysql-initialize.log"'
require_literal 'SERVER_LOG="$LOG_DIR/mysql-server.log"'
require_literal 'GENERAL_LOG="$LOG_DIR/mysql-general.log"'
require_literal 'mkdir -p "$EVIDENCE_DIR" "$DATA" "$RUN" "$TMP" "$LOG_DIR"'
require_literal '  chown -R mysql:mysql "$BASE"'
require_literal '    for runtime_log in "$INIT_LOG" "$SERVER_LOG" "$GENERAL_LOG"; do'
require_literal '        cp -f -- "$runtime_log" "$EVIDENCE_DIR/$(basename -- "$runtime_log")" || cleanup_status=1'

if grep -Eq 'chown[^\n]*EVIDENCE_DIR|chmod[[:space:]]+(777|a\+x|o\+x)|chmod[^\n]*(EVIDENCE_DIR|dirname)' "$RUNNER"; then
  fail "must not change caller evidence-path ownership or traversal permissions"
fi

copy_line=$(grep -nF 'cp -f -- "$runtime_log" "$EVIDENCE_DIR/$(basename -- "$runtime_log")"' "$RUNNER" | cut -d: -f1)
remove_line=$(grep -nF 'rm -rf "$BASE"' "$RUNNER" | cut -d: -f1)
[[ "$copy_line" =~ ^[0-9]+$ && "$remove_line" =~ ^[0-9]+$ && "$copy_line" -lt "$remove_line" ]] || \
  fail "runtime logs must be copied before BASE removal"

for log_var in INIT_LOG SERVER_LOG GENERAL_LOG; do
  assignment=$(grep -F "${log_var}=\"" "$RUNNER")
  [[ "$assignment" == *'"$LOG_DIR/'* ]] || fail "$log_var must remain below LOG_DIR"
done

printf '%s\n' 'D03 isolated MySQL runner contract: PASS'

# Exercise the initialization-failure trap with a fake 8.0.21 binary. The
# evidence ancestor remains mode 0700, so the mysql OS user cannot traverse it.
TEST_ROOT=$(mktemp -d /tmp/cyf-d03-runner-contract-XXXXXXXX)
FAKE_MYSQL_BASE=$(mktemp -d /tmp/cyf-d03-fake-mysql-XXXXXXXX)
cleanup_contract_test() {
  rm -rf -- "$TEST_ROOT" "$FAKE_MYSQL_BASE"
}
trap cleanup_contract_test EXIT
chmod 0700 "$TEST_ROOT"
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
    printf '%s\n' 'D03_FAKE_INITIALIZE_FAILURE_LOG' > "${arg#--log-error=}"
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

NESTED_EVIDENCE="$TEST_ROOT/root-owned-0700/nested/evidence"
set +e
D03_MYSQL_BASE="$FAKE_MYSQL_BASE" D03_EVIDENCE_DIR="$NESTED_EVIDENCE" \
  "$RUNNER" > "$TEST_ROOT/runner-output.log" 2>&1
runner_status=$?
set -e
[[ "$runner_status" == 91 ]] || fail "fake initialize failure status was $runner_status, expected 91"
[[ $(stat -c '%a' "$TEST_ROOT") == 700 ]] || fail "runner changed caller evidence ancestor permissions"
grep -Fqx 'D03_FAKE_INITIALIZE_FAILURE_LOG' "$NESTED_EVIDENCE/mysql-initialize.log" || \
  fail "initialize failure log was not copied into nested evidence directory"
grep -Fqx 'base_exists_after_remove=no' "$NESTED_EVIDENCE/mysql-teardown.txt" || \
  fail "failed initialization did not remove isolated BASE"
failed_base=$(sed -n 's/^isolated_base=//p' "$NESTED_EVIDENCE/mysql-teardown.txt")
[[ -n "$failed_base" && ! -e "$failed_base" ]] || fail "failed initialization left isolated BASE behind"

printf '%s\n' 'D03 isolated MySQL initialize-failure preservation: PASS'
