#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
MYSQL_BIN=${MYSQL_BIN:-/home/isp/apps/mysql/bin/mysql}
MYSQL_SOCKET=${MYSQL_SOCKET:?Set MYSQL_SOCKET to the isolated MySQL 8.0.21 socket on port 33307}
BASE=${DB_NAME:-c01h_probe_$$}
KEEP_DB=${KEEP_DB:-0}
MYSQL=("$MYSQL_BIN" --no-defaults --local-infile=1 -uroot -S "$MYSQL_SOCKET")
OPERATOR=c01h_probe_operator
OPERATOR_PASSWORD=c01h-probe-only
OPMYSQL=("$MYSQL_BIN" --no-defaults --local-infile=1 -u"$OPERATOR" -p"$OPERATOR_PASSWORD" -S "$MYSQL_SOCKET")
SCHEMA="$ROOT/src/main/resources/db/schema.sql"
AUDIT="$ROOT/src/main/resources/db/historical-task-event-baseline-audit-schema.sql"
ROUTINES="$ROOT/src/main/resources/db/historical-task-event-baseline-routines.sql"
MANIFEST="$ROOT/src/main/resources/db/historical-task-event-baseline-manifest.sql"
STAGING="$ROOT/src/main/resources/db/historical-task-event-baseline-staging.sql"
APPROVE="$ROOT/src/main/resources/db/historical-task-event-baseline-approve.sql"
APPLY="$ROOT/src/main/resources/db/historical-task-event-baseline-apply.sql"
TMP=$(mktemp -d /tmp/c01h-mysql-probe.XXXXXX)
DBS=("${BASE}_atomic" "${BASE}_version" "${BASE}_partial" "${BASE}_concurrency")
B09_REPORT=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
B09_RUN=11111111-1111-1111-1111-111111111111
B09_OPERATOR=b09-approved-probe
C01H_OPERATOR=c01h-approved-probe
B09_COMPLETED=1700000000123

cleanup() {
  if [[ "$KEEP_DB" != 1 ]]; then
    for db in "${DBS[@]}"; do "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$db\`" >/dev/null 2>&1 || true; done
    "${MYSQL[@]}" -e "DROP USER IF EXISTS '$OPERATOR'@'localhost'; DROP USER IF EXISTS 'cyf_c01h_definer'@'localhost'" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

version=$("${MYSQL[@]}" -Nse 'SELECT VERSION()')
port=$("${MYSQL[@]}" -Nse 'SELECT @@port')
datadir=$("${MYSQL[@]}" -Nse 'SELECT @@datadir')
[[ "$version" == 8.0.21* ]] || { echo "requires isolated MySQL 8.0.21, got $version" >&2; exit 1; }
[[ "$port" == 33307 ]] || { echo "refusing non-isolated port $port (must be 33307)" >&2; exit 1; }
[[ "$datadir" == /tmp/* ]] || { echo "refusing non-/tmp datadir $datadir" >&2; exit 1; }

"${MYSQL[@]}" -e "DROP USER IF EXISTS '$OPERATOR'@'localhost'; DROP USER IF EXISTS 'cyf_c01h_definer'@'localhost'; CREATE USER '$OPERATOR'@'localhost' IDENTIFIED BY '$OPERATOR_PASSWORD'; CREATE USER 'cyf_c01h_definer'@'localhost' IDENTIFIED BY RANDOM PASSWORD ACCOUNT LOCK;"

assert_scalar() {
  local db=$1 sql=$2 expected=$3 actual
  actual=$("${MYSQL[@]}" -Nse "$sql" "$db")
  [[ "$actual" == "$expected" ]] || { echo "$db expected [$expected], got [$actual]: $sql" >&2; exit 1; }
}

setup_db() {
  local db=$1
  "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$db\`; CREATE DATABASE \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
  "${MYSQL[@]}" "$db" < "$SCHEMA"
  "${MYSQL[@]}" "$db" < "$AUDIT"
  "${MYSQL[@]}" "$db" <<SQL
INSERT INTO agent_task_meta(id,task_id,reward_status,assigned_agent_id,collaboration_mode,risk_level,max_agents,review_required,task_version,current_event_version,create_time,update_time,tenant_id,client_id)
VALUES(9001,'task-C01H','completed','agent-C01H','single','low',1,0,7,0,1699999999000,1699999999999,'tenant-C01H','client-C01H');
INSERT INTO agent_task_member(id,task_id,agent_id,member_role,member_status,assignment_source,joined_at,accepted_at,started_at,completed_at,version,tenant_id,client_id,create_time,update_time)
VALUES(9101,'task-C01H','agent-C01H','worker','done','migration',1699999999000,1699999999000,1699999999100,1699999999999,0,'tenant-C01H','client-C01H',1699999999000,1699999999999);
INSERT INTO agent_task_work_item(id,work_item_id,task_id,title,description,work_type,assignee_agent_id,status,priority,required_item,attempt_count,max_attempts,completed_at,version,tenant_id,client_id,create_time,update_time)
VALUES(9201,'b09-work-C01H','task-C01H','Historical task assignment','probe','legacy_task','agent-C01H','completed',0,1,0,3,1699999999999,0,'tenant-C01H','client-C01H',1699999999000,1699999999999);
INSERT INTO agent_task_backfill_manifest_batch(report_sha256,manifest_row_count,seal_status,approved_operator,approved_at,sealed_at,create_time)
VALUES('$B09_REPORT',1,'SEALED','$B09_OPERATOR',1699999998000,1699999998500,1699999998000);
INSERT INTO agent_task_backfill_manifest(report_sha256,manifest_row_key,manifest_row_sha256,meta_id,task_id,tenant_id,client_id,source_hash,source_format,source_shape,source_ordinal,source_agent_id,canonical_agent_id,resolution_status,task_resolution_status,approved_operator,approved_at,create_time)
VALUES('$B09_REPORT',REPEAT('b',64),REPEAT('c',64),9001,'task-C01H','tenant-C01H','client-C01H',REPEAT('d',64),'PLAIN','scalar',1,'agent-C01H','agent-C01H','ELIGIBLE','ELIGIBLE','$B09_OPERATOR',1699999998000,1699999998000);
INSERT INTO agent_task_backfill_run(run_id,report_sha256,operator,manifest_row_count,issue_row_count,member_insert_count,work_item_insert_count,started_at,completed_at,run_status,create_time)
VALUES('$B09_RUN','$B09_REPORT','$B09_OPERATOR',1,0,1,1,1700000000000,$B09_COMPLETED,'SUCCEEDED',$B09_COMPLETED);
SQL
  "${MYSQL[@]}" -e "GRANT SELECT ON \`$db\`.agent_task_meta TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_member TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_work_item TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_event TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_backfill_manifest_batch TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_backfill_manifest TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_backfill_run TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_historical_event_manifest_batch TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_historical_event_manifest TO 'cyf_c01h_definer'@'localhost'; GRANT SELECT ON \`$db\`.agent_task_historical_event_run TO 'cyf_c01h_definer'@'localhost'; GRANT CREATE TEMPORARY TABLES ON \`$db\`.* TO 'cyf_c01h_definer'@'localhost'; GRANT UPDATE (current_event_version) ON \`$db\`.agent_task_meta TO 'cyf_c01h_definer'@'localhost'; GRANT INSERT ON \`$db\`.agent_task_event TO 'cyf_c01h_definer'@'localhost'; GRANT INSERT,UPDATE ON \`$db\`.agent_task_historical_event_manifest_batch TO 'cyf_c01h_definer'@'localhost'; GRANT INSERT ON \`$db\`.agent_task_historical_event_manifest TO 'cyf_c01h_definer'@'localhost'; GRANT INSERT ON \`$db\`.agent_task_historical_event_run TO 'cyf_c01h_definer'@'localhost'; GRANT CREATE TEMPORARY TABLES ON \`$db\`.* TO '$OPERATOR'@'localhost';"
  "${MYSQL[@]}" "$db" < "$ROUTINES"
  "${MYSQL[@]}" -e "GRANT EXECUTE ON PROCEDURE \`$db\`.c01h_approve_manifest_atomic_v1 TO '$OPERATOR'@'localhost'; GRANT EXECUTE ON PROCEDURE \`$db\`.c01h_apply_manifest_atomic_v1 TO '$OPERATOR'@'localhost';"
}

export_manifest() {
  local db=$1 out=$2
  { printf "SET @c01h_b09_report_sha256='%s'; SET @c01h_b09_run_id='%s';\n" "$B09_REPORT" "$B09_RUN"; printf 'SOURCE %s;\n' "$MANIFEST"; } |
    "${MYSQL[@]}" --batch --raw "$db" > "$out"
  [[ $(awk -F '\t' 'NR>1 {n++} END {print n+0}' "$out") == 1 ]] || { echo "unexpected manifest output" >&2; cat "$out" >&2; exit 1; }
}

manifest_digest() { awk -F '\t' 'NR==2 {print $1}' "$1"; }

approve_manifest() {
  local db=$1 file=$2 digest=$3 out=$4
  { printf 'SOURCE %s;\n' "$STAGING"; printf "LOAD DATA LOCAL INFILE '%s' INTO TABLE tmp_c01h_approved_manifest_staging FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' IGNORE 1 LINES;\n" "$file"; printf "SET @c01h_approved_manifest_digest='%s'; SET @c01h_operator='%s';\n" "$digest" "$C01H_OPERATOR"; printf 'SOURCE %s;\n' "$APPROVE"; } |
    "${OPMYSQL[@]}" --batch --raw "$db" > "$out" 2>&1
}

apply_manifest() {
  local db=$1 digest=$2 out=$3 force=${4:-0}
  local args=(--batch --raw)
  [[ "$force" == 1 ]] && args+=(--force)
  { printf "SET @c01h_approved_manifest_digest='%s'; SET @c01h_operator='%s';\n" "$digest" "$C01H_OPERATOR"; printf 'SOURCE %s;\n' "$APPLY"; [[ "$force" == 1 ]] && printf "SELECT 'force-continued-after-c01h-error';\n"; } |
    "${OPMYSQL[@]}" "${args[@]}" "$db" > "$out" 2>&1
}

expect_apply_failure() {
  local label=$1 db=$2 digest=$3 expected=$4 before after out="$TMP/$label.out"
  before=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_event),'/',(SELECT current_event_version FROM agent_task_meta WHERE id=9001),'/',(SELECT COUNT(*) FROM agent_task_historical_event_run))" "$db")
  set +e; apply_manifest "$db" "$digest" "$out" 1; set -e
  grep -q "$expected" "$out" || { echo "$label missing [$expected]" >&2; cat "$out" >&2; exit 1; }
  grep -q 'force-continued-after-c01h-error' "$out" || { echo "$label did not cover mysql --force continuation" >&2; cat "$out" >&2; exit 1; }
  after=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_event),'/',(SELECT current_event_version FROM agent_task_meta WHERE id=9001),'/',(SELECT COUNT(*) FROM agent_task_historical_event_run))" "$db")
  [[ "$before" == "$after" ]] || { echo "$label left partial state $before -> $after" >&2; exit 1; }
}

# Atomic success, ACL denial, rollback injection, exact repeat no-op.
db=${DBS[0]}; setup_db "$db"; export_manifest "$db" "$TMP/atomic.tsv"; digest=$(manifest_digest "$TMP/atomic.tsv"); approve_manifest "$db" "$TMP/atomic.tsv" "$digest" "$TMP/approve.out"
assert_scalar "$db" "SELECT CONCAT(manifest_row_count,'/',insert_required_count,'/',exact_noop_count,'/',blocked_count,'/',seal_status) FROM agent_task_historical_event_manifest_batch" '1/1/0/0/SEALED'
for sql in "UPDATE agent_task_meta SET current_event_version=99 WHERE id=9001" "INSERT INTO agent_task_event(task_id,event_version,event_id,event_type,actor_type,actor_id,aggregate_type,aggregate_id,event_json,occurred_at,tenant_id,client_id) VALUES('x',1,'x','HISTORICAL_BASELINE_IMPORTED','system','x','task','x','{}',1,'x','x')" "INSERT INTO agent_task_historical_event_run(run_id,report_sha256,b09_report_sha256,b09_run_id,operator,manifest_row_count,event_insert_count,version_update_count,exact_noop_count,started_at,completed_at,run_status) VALUES(UUID(),REPEAT('a',64),REPEAT('a',64),'$B09_RUN','x',1,1,1,0,1,1,'SUCCEEDED')"; do
  if "${OPMYSQL[@]}" -e "$sql" "$db" >/dev/null 2>&1; then echo "restricted operator unexpectedly performed direct DML" >&2; exit 1; fi
done
"${MYSQL[@]}" "$db" <<'SQL'
DELIMITER $$
CREATE TRIGGER c01h_probe_event_failure BEFORE INSERT ON agent_task_event FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='c01h probe injected event failure'; END$$
DELIMITER ;
SQL
expect_apply_failure rollback "$db" "$digest" 'c01h probe injected event failure'
"${MYSQL[@]}" -e 'DROP TRIGGER c01h_probe_event_failure' "$db"
before_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT(task_version,'/',(SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item)) FROM agent_task_meta WHERE id=9001" "$db")
apply_manifest "$db" "$digest" "$TMP/apply-success.out"
assert_scalar "$db" "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_event),'/',current_event_version,'/',task_version) FROM agent_task_meta WHERE id=9001" '1/1/7'
assert_scalar "$db" "SELECT CONCAT(event_version,'/',event_type,'/',actor_type,'/',actor_id,'/',aggregate_type,'/',aggregate_id,'/',occurred_at) FROM agent_task_event" "1/HISTORICAL_BASELINE_IMPORTED/system/c01h-b09/task/task-C01H/$B09_COMPLETED"
apply_manifest "$db" "$digest" "$TMP/apply-repeat.out"
assert_scalar "$db" "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_event),'/',current_event_version,'/',(SELECT COUNT(*) FROM agent_task_historical_event_run),'/',(SELECT SUM(event_insert_count) FROM agent_task_historical_event_run),'/',(SELECT SUM(exact_noop_count) FROM agent_task_historical_event_run)) FROM agent_task_meta WHERE id=9001" '1/1/2/1/1'
after_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT(task_version,'/',(SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item)) FROM agent_task_meta WHERE id=9001" "$db")
[[ "$before_business" == "$after_business" ]] || { echo "task_version/member/work-item changed: $before_business -> $after_business" >&2; exit 1; }

# Version drift after approval is fail-closed and atomic.
db=${DBS[1]}; setup_db "$db"; export_manifest "$db" "$TMP/version.tsv"; digest=$(manifest_digest "$TMP/version.tsv"); approve_manifest "$db" "$TMP/version.tsv" "$digest" "$TMP/version-approve.out"
"${MYSQL[@]}" -e 'UPDATE agent_task_meta SET current_event_version=1 WHERE id=9001' "$db"
expect_apply_failure version-drift "$db" "$digest" 'snapshot/event/version drift or partial baseline'

# Existing deterministic baseline without sealed C01H+SUCCEEDED run evidence is partial and blocked.
db=${DBS[2]}; setup_db "$db"; export_manifest "$db" "$TMP/partial.tsv"; digest=$(manifest_digest "$TMP/partial.tsv"); approve_manifest "$db" "$TMP/partial.tsv" "$digest" "$TMP/partial-approve.out"
event_id=$("${MYSQL[@]}" -Nse 'SELECT event_id FROM agent_task_historical_event_manifest' "$db")
content=$("${MYSQL[@]}" -Nse 'SELECT content_sha256 FROM agent_task_historical_event_manifest' "$db")
member_count=$("${MYSQL[@]}" -Nse 'SELECT member_count FROM agent_task_historical_event_manifest' "$db")
work_count=$("${MYSQL[@]}" -Nse 'SELECT work_item_count FROM agent_task_historical_event_manifest' "$db")
"${MYSQL[@]}" -e "INSERT INTO agent_task_event(task_id,event_version,event_id,event_type,actor_type,actor_id,aggregate_type,aggregate_id,event_json,occurred_at,tenant_id,client_id) VALUES('task-C01H',1,'$event_id','HISTORICAL_BASELINE_IMPORTED','system','c01h-b09','task','task-C01H','{\"contentSha256\":\"$content\",\"decisionCode\":\"c01h_b09_v1\",\"memberCount\":$member_count,\"source\":\"b09\",\"workItemCount\":$work_count}',$B09_COMPLETED,'tenant-C01H','client-C01H'); UPDATE agent_task_meta SET current_event_version=1 WHERE id=9001" "$db"
expect_apply_failure partial-baseline "$db" "$digest" 'snapshot/event/version drift or partial baseline'

# Concurrent root-first writer commits task_version drift before apply obtains all roots.
db=${DBS[3]}; setup_db "$db"; export_manifest "$db" "$TMP/concurrency.tsv"; digest=$(manifest_digest "$TMP/concurrency.tsv"); approve_manifest "$db" "$TMP/concurrency.tsv" "$digest" "$TMP/concurrency-approve.out"
("${MYSQL[@]}" "$db" -e "START TRANSACTION; SELECT id FROM agent_task_meta WHERE id=9001 FOR UPDATE; DO SLEEP(2); UPDATE agent_task_meta SET task_version=task_version+1 WHERE id=9001; COMMIT" >"$TMP/writer.out" 2>&1) & writer_pid=$!
sleep 0.5
expect_apply_failure concurrent-writer "$db" "$digest" 'snapshot/event/version drift or partial baseline'
wait "$writer_pid"
# Explicit named-lock contention is immediate and leaves no run.
("${MYSQL[@]}" "$db" -e "SELECT GET_LOCK(LEFT(CONCAT('c01h-historical-baseline:',DATABASE()),64),0); DO SLEEP(2); DO RELEASE_LOCK(LEFT(CONCAT('c01h-historical-baseline:',DATABASE()),64))" >"$TMP/lock-holder.out" 2>&1) & lock_pid=$!
sleep 0.5
expect_apply_failure named-lock "$db" "$digest" 'C01H apply: C01H lock busy'
wait "$lock_pid"

echo 'C01H isolated MySQL probe PASS: ACL, rollback, --force, exact repeat, partial baseline, version drift, root concurrency and lock contention'
