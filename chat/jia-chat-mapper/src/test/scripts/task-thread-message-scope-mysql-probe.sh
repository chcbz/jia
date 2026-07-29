#!/usr/bin/env bash
set -euo pipefail
MYSQL_BIN=${MYSQL_BIN:-/home/isp/apps/mysql/bin/mysql}
MYSQL_SOCKET=${MYSQL_SOCKET:?Set MYSQL_SOCKET to the isolated MySQL 8.0.21 socket}
DB_NAME=${DB_NAME:-b07_message_scope_$$}
MYSQL=("$MYSQL_BIN" --no-defaults -uroot -S "$MYSQL_SOCKET" --batch --raw --skip-column-names)
cleanup() { "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`" >/dev/null 2>&1 || true; }
trap cleanup EXIT
version=$("${MYSQL[@]}" -e 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || { echo "expected MySQL 8.0.21, got $version" >&2; exit 1; }
"${MYSQL[@]}" <<SQL
CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE \`$DB_NAME\`;
CREATE TABLE chat_message (
 id BIGINT PRIMARY KEY, tenant_id VARCHAR(50), client_id VARCHAR(50),
 conversation_id VARCHAR(100), content VARCHAR(100), create_time BIGINT,
 KEY idx_scope (tenant_id, client_id, conversation_id, create_time, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO chat_message VALUES
 (1,'Tenant-A','Client-A','Room-1','exact',1),
 (2,'tenant-a','Client-A','Room-1','case',2),
 (3,CONCAT('Tenant-A',CHAR(32)),'Client-A','Room-1','padding',3),
 (4,CONCAT('Tenant-A',CHAR(0)),'Client-A','Room-1','nul',4),
 (5,'Tenant-A',CONCAT('Client-A',CHAR(32)),'Room-1','client-padding',5),
 (6,'Tenant-A','Client-A',CONCAT('Room-1',CHAR(0)),'conversation-nul',6);
SQL
query="SELECT GROUP_CONCAT(id ORDER BY id) FROM \`$DB_NAME\`.chat_message WHERE tenant_id='Tenant-A' AND client_id='Client-A' AND conversation_id='Room-1' AND CAST(tenant_id AS BINARY)=CAST('Tenant-A' AS BINARY) AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH('Tenant-A') AND CAST(client_id AS BINARY)=CAST('Client-A' AS BINARY) AND OCTET_LENGTH(client_id)=OCTET_LENGTH('Client-A') AND CAST(conversation_id AS BINARY)=CAST('Room-1' AS BINARY) AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH('Room-1')"
actual=$("${MYSQL[@]}" -e "$query")
[[ "$actual" == "1" ]] || { echo "byte-exact scope leaked rows: $actual" >&2; exit 1; }
vulnerable=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM \`$DB_NAME\`.chat_message WHERE tenant_id='Tenant-A' AND client_id='Client-A' AND conversation_id='Room-1'")
[[ "$vulnerable" -gt 1 ]] || { echo "probe did not exercise permissive collation" >&2; exit 1; }
echo "B07 MYSQL 8.0.21 EXACT MESSAGE SCOPE PROBE PASSED"
