#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C

MODULE_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
MIGRATION="$MODULE_ROOT/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql"
MYSQL_BIN=${MYSQL_BIN:-/home/isp/apps/mysql/bin/mysql}
MYSQL_SOCKET=${MYSQL_SOCKET:?Set MYSQL_SOCKET to a verifier-owned isolated MySQL 8.0.21 socket}
MYSQL_EXPECTED_DATADIR=${MYSQL_EXPECTED_DATADIR:?Set MYSQL_EXPECTED_DATADIR to that isolated instance datadir}
DB_NAME=${DB_NAME:-wx_daily_vote_probe_$$}
KEEP_DB=${KEEP_DB:-0}
[[ "$DB_NAME" =~ ^wx_daily_vote_probe_[A-Za-z0-9_]+$ ]] || { echo "unsafe DB_NAME: $DB_NAME" >&2; exit 2; }
MYSQL=("$MYSQL_BIN" --no-defaults -uroot -S "$MYSQL_SOCKET" --batch --raw --skip-column-names)
TMP=$(mktemp -d /tmp/wx-daily-vote-mysql-probe.XXXXXX)

cleanup() {
  if [[ "$KEEP_DB" != 1 ]]; then
    "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$TMP"
}
trap cleanup EXIT

assert_scalar() {
  local sql=$1 expected=$2 actual
  actual=$("${MYSQL[@]}" "$DB_NAME" -e "$sql")
  [[ "$actual" == "$expected" ]] || {
    echo "expected [$expected], got [$actual] for: $sql" >&2
    exit 1
  }
}

version=$("${MYSQL[@]}" -e 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || { echo "expected MySQL 8.0.21, got $version" >&2; exit 1; }
port=$("${MYSQL[@]}" -e 'SELECT @@port')
[[ "$port" != 3306 && "$port" != 33060 ]] || { echo "refusing standard MySQL port $port" >&2; exit 1; }
actual_datadir=$("${MYSQL[@]}" -e 'SELECT @@datadir')
[[ "$(realpath -m -- "$actual_datadir")" == "$(realpath -m -- "$MYSQL_EXPECTED_DATADIR")" ]] || {
  echo "isolated MySQL datadir mismatch: $actual_datadir" >&2; exit 1;
}
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"

"${MYSQL[@]}" "$DB_NAME" <<'SQL'
CREATE TABLE wx_mp_user (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  appid VARCHAR(50), open_id VARCHAR(32), jiacn VARCHAR(32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE mat_vote_tick (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  jiacn VARCHAR(32), vote_id BIGINT, question_id BIGINT, opt VARCHAR(6), tick INT,
  create_time BIGINT, update_time BIGINT,
  KEY vote_tick_question_id (question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE mat_vote (id BIGINT PRIMARY KEY, num INT) ENGINE=InnoDB;
CREATE TABLE mat_vote_question (
  id BIGINT PRIMARY KEY, vote_id BIGINT, point INT, opt VARCHAR(6)
) ENGINE=InnoDB;
CREATE TABLE mat_vote_item (
  id BIGINT PRIMARY KEY, question_id BIGINT, opt VARCHAR(6), num INT
) ENGINE=InnoDB;
CREATE TABLE user_info (
  id BIGINT PRIMARY KEY, jiacn VARCHAR(32), point INT, update_time BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO wx_mp_user(appid,open_id,jiacn) VALUES
 ('wx-app','OpenId','legacy-user'),('WX-APP','openid','wrong-collation-user');
INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES
 ('legacy-user',1,337,'A',1),('legacy-user',1,337,'A',1),('legacy-user',1,337,'A',1);
INSERT INTO mat_vote VALUES (1,0),(2,0);
INSERT INTO mat_vote_question VALUES (400,1,2,'A');
INSERT INTO mat_vote_item VALUES (10,400,'A',0);
INSERT INTO user_info VALUES
 (1,'user-atomic',10,0),(2,'user-concurrent',0,0),(3,'USER-ATOMIC',99,0);
SQL

# Fresh migration and repeat migration must both succeed without rewriting the
# three historical duplicate answer rows.
"${MYSQL[@]}" "$DB_NAME" < "$MIGRATION" > "$TMP/migration-first.out"
"${MYSQL[@]}" "$DB_NAME" < "$MIGRATION" > "$TMP/migration-second.out"
assert_scalar "SELECT COUNT(*) FROM mat_vote_tick WHERE jiacn='legacy-user' AND question_id=337" 3
assert_scalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wx_mp_user' AND index_name='idx_wx_mp_user_appid_open_id' AND column_name IN ('appid','open_id')" 2
assert_scalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mat_vote_tick' AND index_name='idx_mat_vote_tick_jiacn_question' AND column_name IN ('jiacn','question_id')" 2
assert_scalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wx_daily_vote_receipt' AND non_unique=0 AND index_name IN ('uk_wx_daily_vote_message','uk_wx_daily_vote_user_question')" 5
assert_scalar "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='wx_daily_vote_message_receipt' AND non_unique=0 AND index_name='uk_wx_daily_vote_message_alias'" 2
assert_scalar "SELECT COUNT(*) FROM wx_mp_user WHERE appid='wx-app' AND open_id='OpenId'" 2
assert_scalar "SELECT COUNT(*) FROM wx_mp_user WHERE appid='wx-app' AND open_id='OpenId' AND OCTET_LENGTH(appid)=OCTET_LENGTH('wx-app') AND CAST(appid AS BINARY)=CAST('wx-app' AS BINARY) AND OCTET_LENGTH(open_id)=OCTET_LENGTH('OpenId') AND CAST(open_id AS BINARY)=CAST('OpenId' AS BINARY)" 1

# Two connections race on the same user/question with different message keys.
# The loser must wait for the winner and replay the one committed receipt.
ready_lock="wxdv_${DB_NAME}_ready"
"${MYSQL[@]}" "$DB_NAME" > "$TMP/claim-a.out" <<SQL &
START TRANSACTION;
INSERT INTO wx_daily_vote_receipt
(appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,create_time,update_time)
VALUES ('wx-app',REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),500,'11111111-1111-1111-1111-111111111111','PROCESSING',1,1);
INSERT INTO wx_daily_vote_message_receipt
(receipt_id,appid,message_key,user_key,request_fingerprint,question_id,create_time,update_time)
VALUES (LAST_INSERT_ID(),'wx-app',REPEAT('a',64),REPEAT('b',64),REPEAT('c',64),500,1,1);
SELECT GET_LOCK('$ready_lock',10);
DO SLEEP(2);
UPDATE wx_daily_vote_receipt SET status='COMPLETED',correct=1,point_awarded=2,
 reply_content='persisted-reply',update_time=2
WHERE appid='wx-app' AND message_key=REPEAT('a',64);
COMMIT;
SELECT RELEASE_LOCK('$ready_lock');
SQL
claim_a_pid=$!
for _ in $(seq 1 100); do
  [[ $("${MYSQL[@]}" -e "SELECT IS_USED_LOCK('$ready_lock') IS NOT NULL") == 1 ]] && break
  sleep 0.02
done
[[ $("${MYSQL[@]}" -e "SELECT IS_USED_LOCK('$ready_lock') IS NOT NULL") == 1 ]] || {
  echo "claim A did not reach the post-insert barrier" >&2; wait "$claim_a_pid" || true; exit 1;
}
"${MYSQL[@]}" "$DB_NAME" > "$TMP/claim-b.out" <<'SQL'
START TRANSACTION;
INSERT INTO wx_daily_vote_receipt
(appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,create_time,update_time)
VALUES ('wx-app',REPEAT('d',64),REPEAT('b',64),REPEAT('e',64),500,'22222222-2222-2222-2222-222222222222','PROCESSING',1,1)
ON DUPLICATE KEY UPDATE id=id;
INSERT INTO wx_daily_vote_message_receipt
(receipt_id,appid,message_key,user_key,request_fingerprint,question_id,create_time,update_time)
SELECT id,'wx-app',REPEAT('d',64),REPEAT('b',64),REPEAT('e',64),500,1,1
FROM wx_daily_vote_receipt
WHERE appid='wx-app' AND user_key=REPEAT('b',64) AND question_id=500
ON DUPLICATE KEY UPDATE id=id;
SELECT status FROM wx_daily_vote_receipt
WHERE appid='wx-app' AND user_key=REPEAT('b',64) AND question_id=500 FOR UPDATE;
COMMIT;
SQL
wait "$claim_a_pid"
grep -Fxq 'COMPLETED' "$TMP/claim-b.out" || { cat "$TMP/claim-b.out" >&2; exit 1; }
assert_scalar "SELECT COUNT(*) FROM wx_daily_vote_receipt WHERE appid='wx-app' AND user_key=REPEAT('b',64) AND question_id=500" 1
assert_scalar "SELECT COUNT(*) FROM wx_daily_vote_message_receipt WHERE appid='wx-app' AND message_key IN (REPEAT('a',64),REPEAT('d',64))" 2
assert_scalar "SELECT r.status FROM wx_daily_vote_message_receipt m JOIN wx_daily_vote_receipt r ON r.id=m.receipt_id WHERE m.appid='wx-app' AND m.message_key=REPEAT('d',64) AND m.request_fingerprint=REPEAT('e',64)" COMPLETED

# A same-message retry cannot create a second row even if the supplied user and
# question differ; both persistent unique identities remain enforced.
"${MYSQL[@]}" "$DB_NAME" <<'SQL'
INSERT INTO wx_daily_vote_receipt
(appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,create_time,update_time)
VALUES ('wx-app',REPEAT('a',64),REPEAT('f',64),REPEAT('1',64),999,'33333333-3333-3333-3333-333333333333','PROCESSING',1,1)
ON DUPLICATE KEY UPDATE id=id;
SQL
assert_scalar "SELECT COUNT(*) FROM wx_daily_vote_receipt WHERE appid='wx-app' AND message_key=REPEAT('a',64)" 1

# Simulate the exact receipt/tick/counter/point unit of work: rollback leaves all
# business state retryable, then the same work commits once.
"${MYSQL[@]}" "$DB_NAME" <<'SQL'
START TRANSACTION;
INSERT INTO wx_daily_vote_receipt
(appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,create_time,update_time)
VALUES ('wx-app',REPEAT('2',64),REPEAT('3',64),REPEAT('4',64),400,'44444444-4444-4444-4444-444444444444','PROCESSING',1,1);
INSERT INTO wx_daily_vote_message_receipt
(receipt_id,appid,message_key,user_key,request_fingerprint,question_id,create_time,update_time)
VALUES (LAST_INSERT_ID(),'wx-app',REPEAT('2',64),REPEAT('3',64),REPEAT('4',64),400,1,1);
INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES ('user-atomic',1,400,'A',1);
UPDATE mat_vote SET num=COALESCE(num,0)+1 WHERE id=1;
UPDATE mat_vote_item SET num=COALESCE(num,0)+1 WHERE question_id=400 AND UPPER(opt)=UPPER('A');
UPDATE user_info SET point=COALESCE(point,0)+2,update_time=2
WHERE jiacn='user-atomic' AND CAST(jiacn AS BINARY)=CAST('user-atomic' AS BINARY);
ROLLBACK;
SQL
assert_scalar "SELECT CONCAT((SELECT COUNT(*) FROM wx_daily_vote_receipt WHERE message_key=REPEAT('2',64)),'/',(SELECT COUNT(*) FROM wx_daily_vote_message_receipt WHERE message_key=REPEAT('2',64)),'/',(SELECT COUNT(*) FROM mat_vote_tick WHERE jiacn='user-atomic' AND question_id=400),'/',(SELECT num FROM mat_vote WHERE id=1),'/',(SELECT num FROM mat_vote_item WHERE id=10),'/',(SELECT point FROM user_info WHERE id=1),'/',(SELECT point FROM user_info WHERE id=3))" 0/0/0/0/0/10/99

"${MYSQL[@]}" "$DB_NAME" <<'SQL'
START TRANSACTION;
INSERT INTO wx_daily_vote_receipt
(appid,message_key,user_key,request_fingerprint,question_id,processor_token,status,create_time,update_time)
VALUES ('wx-app',REPEAT('2',64),REPEAT('3',64),REPEAT('4',64),400,'44444444-4444-4444-4444-444444444444','PROCESSING',1,1);
INSERT INTO wx_daily_vote_message_receipt
(receipt_id,appid,message_key,user_key,request_fingerprint,question_id,create_time,update_time)
VALUES (LAST_INSERT_ID(),'wx-app',REPEAT('2',64),REPEAT('3',64),REPEAT('4',64),400,1,1);
INSERT INTO mat_vote_tick(jiacn,vote_id,question_id,opt,tick) VALUES ('user-atomic',1,400,'A',1);
UPDATE mat_vote SET num=COALESCE(num,0)+1 WHERE id=1;
UPDATE mat_vote_item SET num=COALESCE(num,0)+1 WHERE question_id=400 AND UPPER(opt)=UPPER('A');
UPDATE user_info SET point=COALESCE(point,0)+2,update_time=2
WHERE jiacn='user-atomic' AND CAST(jiacn AS BINARY)=CAST('user-atomic' AS BINARY);
UPDATE wx_daily_vote_receipt SET status='COMPLETED',correct=1,point_awarded=2,
 reply_content='correct-reply',update_time=2
WHERE message_key=REPEAT('2',64) AND processor_token='44444444-4444-4444-4444-444444444444';
COMMIT;
SQL
assert_scalar "SELECT CONCAT((SELECT COUNT(*) FROM wx_daily_vote_receipt WHERE message_key=REPEAT('2',64) AND status='COMPLETED'),'/',(SELECT COUNT(*) FROM wx_daily_vote_message_receipt WHERE message_key=REPEAT('2',64)),'/',(SELECT COUNT(*) FROM mat_vote_tick WHERE jiacn='user-atomic' AND question_id=400),'/',(SELECT num FROM mat_vote WHERE id=1),'/',(SELECT num FROM mat_vote_item WHERE id=10),'/',(SELECT point FROM user_info WHERE id=1),'/',(SELECT point FROM user_info WHERE id=3))" 1/1/1/1/1/12/99

# Concurrent atomic increments must not lose updates.
increment_pids=()
for worker in $(seq 1 8); do
  {
    for _ in $(seq 1 25); do
      echo "UPDATE mat_vote SET num=COALESCE(num,0)+1 WHERE id=2;"
      echo "UPDATE user_info SET point=COALESCE(point,0)+1 WHERE id=2;"
    done
  } | "${MYSQL[@]}" "$DB_NAME" > "$TMP/increment-$worker.out" &
  increment_pids+=("$!")
done
for pid in "${increment_pids[@]}"; do
  wait "$pid"
done
assert_scalar "SELECT CONCAT((SELECT num FROM mat_vote WHERE id=2),'/',(SELECT point FROM user_info WHERE id=2))" 200/200

echo "WX DAILY VOTE MYSQL 8.0.21 PROBE PASSED"
