#!/usr/bin/env bash
set -euo pipefail

# Disposable MySQL 8 probe. It never starts MySQL and accepts credentials only through a
# caller-owned defaults file. The generated schema name is allowlisted and always dropped.
: "${MYSQL_DEFAULTS_FILE:?set MYSQL_DEFAULTS_FILE to a test-only mysql client defaults file}"
[[ -r "$MYSQL_DEFAULTS_FILE" ]] || { echo "MYSQL_DEFAULTS_FILE is not readable" >&2; exit 64; }
MYSQL_BIN="${MYSQL_BIN:-mysql}"
MYSQL_PROBE_HOST="${MYSQL_PROBE_HOST:-127.0.0.1}"
: "${MYSQL_PROBE_PORT:?set MYSQL_PROBE_PORT to the isolated local MySQL port}"
[[ "$MYSQL_PROBE_HOST" == "127.0.0.1" || "$MYSQL_PROBE_HOST" == "::1" ]] \
  || { echo "probe host must be loopback" >&2; exit 64; }
[[ "$MYSQL_PROBE_PORT" =~ ^[0-9]+$ ]] \
  && (( MYSQL_PROBE_PORT >= 1024 && MYSQL_PROBE_PORT <= 65535 && MYSQL_PROBE_PORT != 3306 )) \
  || { echo "probe port must be a non-default local test port" >&2; exit 64; }
SCHEMA="cyf_chat_delete_probe_$(date +%s)_$$"
[[ "$SCHEMA" =~ ^cyf_chat_delete_probe_[0-9]+_[0-9]+$ ]] || exit 64
ROOT_DIR="$(cd "$(dirname "$0")/../../../../../.." && pwd)"
MIGRATION="$ROOT_DIR/chat/jia-chat-mapper/src/main/resources/db/conversation-delete-fence-migration.sql"
[[ -r "$MIGRATION" ]] || { echo "migration resource is not readable" >&2; exit 64; }
MYSQL=("$MYSQL_BIN" --defaults-file="$MYSQL_DEFAULTS_FILE" --protocol=tcp
  --host="$MYSQL_PROBE_HOST" --port="$MYSQL_PROBE_PORT"
  --batch --raw --skip-column-names)
cleanup() { "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$SCHEMA\`" >/dev/null 2>&1 || true; }
trap cleanup EXIT

major="$("${MYSQL[@]}" -e 'SELECT SUBSTRING_INDEX(VERSION(),".",1)')"
[[ "$major" == "8" ]] || { echo "MySQL 8.x required, got major=$major" >&2; exit 65; }
"${MYSQL[@]}" -e "CREATE DATABASE \`$SCHEMA\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin"
"${MYSQL[@]}" "$SCHEMA" <<'SQL'
CREATE TABLE chat_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 jiacn VARCHAR(50), client_id VARCHAR(50), tenant_id VARCHAR(50) DEFAULT '0',
 target_agent_id VARCHAR(100), update_time BIGINT, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE chat_message (
 id BIGINT NOT NULL AUTO_INCREMENT, conversation_id VARCHAR(100) NOT NULL,
 content TEXT, PRIMARY KEY(id), KEY idx_conversation_id(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
SQL
"${MYSQL[@]}" "$SCHEMA" < "$MIGRATION"
"${MYSQL[@]}" "$SCHEMA" < "$MIGRATION"

assert_eq() { [[ "$1" == "$2" ]] || { echo "assertion failed: expected '$2', got '$1'" >&2; exit 66; }; }
column_shape() {
  "${MYSQL[@]}" "$SCHEMA" -e "SELECT CONCAT(data_type,':',COALESCE(character_maximum_length,''),':',is_nullable,':',COALESCE(column_default,'NULL'),':',COALESCE(collation_name,'')) FROM information_schema.columns WHERE table_schema='$SCHEMA' AND table_name='chat_conversation' AND column_name='$1'"
}
assert_eq "$(column_shape target_agent_ids)" "varchar:2000:YES:NULL:utf8mb4_0900_bin"
assert_eq "$(column_shape deleted_at)" "bigint::YES:NULL:"
assert_eq "$(column_shape lifecycle_generation)" "bigint::NO:1:"
index_shape="$("${MYSQL[@]}" "$SCHEMA" -e "SELECT CONCAT(MIN(non_unique),':',GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','),':',SUM(sub_part IS NOT NULL)) FROM information_schema.statistics WHERE table_schema='$SCHEMA' AND table_name='chat_conversation' AND index_name='idx_chat_conversation_live_owner'")"
assert_eq "$index_shape" "1:jiacn,client_id,deleted_at,lifecycle_generation,update_time:0"

reset_case() {
  "${MYSQL[@]}" "$SCHEMA" -e "DELETE FROM chat_message; DELETE FROM chat_conversation; ALTER TABLE chat_conversation AUTO_INCREMENT=1; INSERT INTO chat_conversation(jiacn,client_id,tenant_id,update_time) VALUES('owner','client','0',1);"
}

# append-first: connection A commits an append while holding the conversation row; connection B
# then tombstones and removes it. Final state must contain no message.
reset_case
("${MYSQL[@]}" "$SCHEMA" <<'SQL'
START TRANSACTION;
SELECT id FROM chat_conversation WHERE id=1 AND deleted_at IS NULL AND lifecycle_generation=1 FOR UPDATE;
INSERT INTO chat_message(conversation_id,content) VALUES('1','append-first');
SELECT SLEEP(2);
COMMIT;
SQL
) >/dev/null & a=$!
sleep 0.25
("${MYSQL[@]}" "$SCHEMA" <<'SQL'
START TRANSACTION;
SELECT id FROM chat_conversation WHERE id=1 FOR UPDATE;
UPDATE chat_conversation SET deleted_at=1000,lifecycle_generation=lifecycle_generation+1 WHERE id=1 AND deleted_at IS NULL;
DELETE FROM chat_message WHERE conversation_id='1';
COMMIT;
SQL
) >/dev/null & b=$!
wait "$a"; wait "$b"
assert_eq "$("${MYSQL[@]}" "$SCHEMA" -e "SELECT CONCAT(deleted_at,':',lifecycle_generation,':',(SELECT COUNT(*) FROM chat_message WHERE conversation_id='1')) FROM chat_conversation WHERE id=1")" "1000:2:0"

# delete-first: connection A tombstones while holding the row. Connection B's locking read resumes
# after commit, finds no live generation, and therefore inserts nothing.
reset_case
("${MYSQL[@]}" "$SCHEMA" <<'SQL'
START TRANSACTION;
SELECT id FROM chat_conversation WHERE id=1 FOR UPDATE;
UPDATE chat_conversation SET deleted_at=2000,lifecycle_generation=lifecycle_generation+1 WHERE id=1 AND deleted_at IS NULL;
DELETE FROM chat_message WHERE conversation_id='1';
SELECT SLEEP(2);
COMMIT;
SQL
) >/dev/null & a=$!
sleep 0.25
("${MYSQL[@]}" "$SCHEMA" <<'SQL'
START TRANSACTION;
SET @live_id := NULL;
SELECT id INTO @live_id FROM chat_conversation WHERE id=1 AND deleted_at IS NULL AND lifecycle_generation=1 FOR UPDATE;
INSERT INTO chat_message(conversation_id,content) SELECT '1','delete-first' FROM DUAL WHERE @live_id IS NOT NULL;
COMMIT;
SQL
) >/dev/null & b=$!
wait "$a"; wait "$b"
assert_eq "$("${MYSQL[@]}" "$SCHEMA" -e "SELECT CONCAT(deleted_at,':',lifecycle_generation,':',(SELECT COUNT(*) FROM chat_message WHERE conversation_id='1')) FROM chat_conversation WHERE id=1")" "2000:2:0"

# Repeated delete is idempotent: the second guarded update affects zero rows and does not advance.
second_rows="$("${MYSQL[@]}" "$SCHEMA" -e "UPDATE chat_conversation SET deleted_at=3000,lifecycle_generation=lifecycle_generation+1 WHERE id=1 AND deleted_at IS NULL; SELECT ROW_COUNT();")"
assert_eq "$second_rows" "0"
assert_eq "$("${MYSQL[@]}" "$SCHEMA" -e 'SELECT lifecycle_generation FROM chat_conversation WHERE id=1')" "2"
echo "PASS schema=$SCHEMA migration=twice append-first delete-first repeated-delete"
