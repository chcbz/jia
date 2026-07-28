#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
MYSQL_BIN=${MYSQL_BIN:-/home/isp/apps/mysql/bin/mysql}
MYSQL_SOCKET=${MYSQL_SOCKET:?Set MYSQL_SOCKET to an isolated MySQL 8.0.21 socket}
DB_NAME=${DB_NAME:-b09_probe_$$}
CLEAN_DB_NAME=${CLEAN_DB_NAME:-${DB_NAME}_clean}
KEEP_DB=${KEEP_DB:-0}
MYSQL=("$MYSQL_BIN" --no-defaults --local-infile=1 -uroot -S "$MYSQL_SOCKET")
SCHEMA="$ROOT/src/main/resources/db/schema.sql"
AUDIT_SCHEMA="$ROOT/src/main/resources/db/task-collaboration-backfill-audit-schema.sql"
DRY_RUN="$ROOT/src/main/resources/db/task-collaboration-backfill-dry-run.sql"
MANIFEST="$ROOT/src/main/resources/db/task-collaboration-backfill-manifest.sql"
STAGING="$ROOT/src/main/resources/db/task-collaboration-backfill-staging.sql"
APPROVE="$ROOT/src/main/resources/db/task-collaboration-backfill-approve.sql"
APPLY="$ROOT/src/main/resources/db/task-collaboration-backfill.sql"
TMP=$(mktemp -d /tmp/b09-mysql-probe.XXXXXX)
APPROVED_OPERATOR=probe-approved

cleanup() {
  if [[ "$KEEP_DB" != "1" ]]; then
    "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; DROP DATABASE IF EXISTS \`$CLEAN_DB_NAME\`" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

assert_scalar() {
  local db=$1 sql=$2 expected=$3 actual
  actual=$("${MYSQL[@]}" -Nse "$sql" "$db")
  [[ "$actual" == "$expected" ]] || {
    echo "expected [$expected], got [$actual] for $db: $sql" >&2
    exit 1
  }
}

state_of() {
  local db=$1
  "${MYSQL[@]}" -Nse "SELECT CONCAT(
    (SELECT COUNT(*) FROM agent_task_member), '/',
    (SELECT COUNT(*) FROM agent_task_work_item), '/',
    (SELECT COUNT(*) FROM agent_task_backfill_issue), '/',
    (SELECT COUNT(*) FROM agent_task_backfill_run))" "$db"
}

run_apply_sql() {
  local db=$1 sha=$2 operator_sql=$3 output=$4
  {
    printf "SET @b09_approved_report_sha256='%s';\n" "$sha"
    printf '%s\n' "$operator_sql"
    printf 'source %s;\n' "$APPLY"
  } | "${MYSQL[@]}" --batch --raw "$db" >"$output" 2>&1
}

expect_apply_failure() {
  local label=$1 db=$2 sha=$3 operator_sql=$4 expected_message=$5
  local before after rc output="$TMP/${label}.out"
  before=$(state_of "$db")
  set +e
  run_apply_sql "$db" "$sha" "$operator_sql" "$output"
  rc=$?
  set -e
  [[ $rc -ne 0 ]] || { echo "$label unexpectedly succeeded" >&2; cat "$output" >&2; exit 1; }
  grep -q "$expected_message" "$output" || { echo "$label missing error [$expected_message]" >&2; cat "$output" >&2; exit 1; }
  after=$(state_of "$db")
  [[ "$after" == "$before" ]] || { echo "$label changed transactional state: $before -> $after" >&2; exit 1; }
}

approve_manifest() {
  local db=$1 manifest_file=$2 sha=$3 operator=$4 output=$5
  {
    printf 'source %s;\n' "$STAGING"
    printf "LOAD DATA LOCAL INFILE '%s' INTO TABLE tmp_b09_approved_manifest_staging FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' IGNORE 1 LINES;\n" "$manifest_file"
    printf "SET @b09_approved_report_sha256='%s'; SET @b09_operator='%s';\n" "$sha" "$operator"
    printf 'source %s;\n' "$APPROVE"
  } | "${MYSQL[@]}" --batch --raw "$db" >"$output"
}

version=$("${MYSQL[@]}" -Nse 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || { echo "expected MySQL 8.0.21, got $version" >&2; exit 1; }
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
"${MYSQL[@]}" "$DB_NAME" < "$SCHEMA"
"${MYSQL[@]}" "$DB_NAME" < "$AUDIT_SCHEMA"

cat > "$TMP/fixture.sql" <<'SQL'
SET @now = 1700000000000;
INSERT INTO agent_identity_registry
(canonical_agent_id, canonical_type, lifecycle_status, client_id, owner_jiacn, tenant_id, audit_reason, create_time)
VALUES
('agt_11111111111111111111111111111111','OPAQUE','ACTIVE','client-a','tenant-a','tenant-a','probe',@now),
('agt_22222222222222222222222222222222','OPAQUE','ACTIVE','client-a','tenant-a','tenant-a','probe',@now),
('agt_33333333333333333333333333333333','OPAQUE','ACTIVE','client-b','tenant-b','tenant-b','probe',@now);
SET @r1=(SELECT id FROM agent_identity_registry WHERE BINARY canonical_agent_id=BINARY 'agt_11111111111111111111111111111111');
SET @r2=(SELECT id FROM agent_identity_registry WHERE BINARY canonical_agent_id=BINARY 'agt_22222222222222222222222222222222');
INSERT INTO agent_identity_alias
(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,valid_to,
 client_id,owner_jiacn,tenant_id,audit_reason,create_time)
VALUES
(@r1,'agt_11111111111111111111111111111111','LEGACY_AGENT_ID','legacy-one','ACTIVE',1600000000000,NULL,
 'client-a','tenant-a','tenant-a','probe',@now),
(@r2,'agt_22222222222222222222222222222222','LEGACY_AGENT_ID','legacy-two','ACTIVE',1600000000000,NULL,
 'client-a','tenant-a','tenant-a','probe',@now);
INSERT INTO agent_task_meta
(task_id,reward_status,assigned_agent_id,assigned_at,tenant_id,client_id,create_time,update_time)
VALUES
('eligible','assigned','legacy-one',@now,'tenant-a','client-a',@now,@now),
('multi','running','["legacy-one","legacy-two"]',@now,'tenant-a','client-a',@now,@now),
('plain-space','assigned',' legacy-one ',@now,'tenant-a','client-a',@now,@now),
('json-string-space','assigned','" legacy-one "',@now,'tenant-a','client-a',@now,@now),
('array-space','assigned','["legacy-one ",{"agentId":" legacy-two"}]',@now,'tenant-a','client-a',@now,@now),
('direct-wrong-type','assigned','{"agentId":123}',@now,'tenant-a','client-a',@now,@now),
('wrapper-wrong-type','assigned','{"assignees":"legacy-one"}',@now,'tenant-a','client-a',@now,@now),
('ambiguous-wrapper','assigned','{"agentId":"legacy-one","assignees":[]}',@now,'tenant-a','client-a',@now,@now),
('ambiguous-direct','assigned','{"agentId":"legacy-one","id":123}',@now,'tenant-a','client-a',@now,@now),
('array-ambiguous','assigned','[{"agentId":"legacy-one","id":123}]',@now,'tenant-a','client-a',@now,@now),
('status-case','Running','legacy-one',@now,'tenant-a','client-a',@now,@now),
('agent-case-source','assigned','AGT_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('scope-case-source','assigned','agt_11111111111111111111111111111111',@now,'Tenant-A','Client-A',@now,@now),
('member-case-target','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('work-case-target','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now);
INSERT INTO agent_task_member
(task_id,agent_id,member_role,member_status,assignment_source,version,tenant_id,client_id,create_time,update_time)
VALUES
('MEMBER-CASE-TARGET','AGT_11111111111111111111111111111111','worker','accepted','manual',0,
 'TENANT-A','CLIENT-A',@now,@now);
INSERT INTO agent_task_work_item
(work_item_id,task_id,title,work_type,assignee_agent_id,status,priority,required_item,
 attempt_count,max_attempts,version,tenant_id,client_id,create_time,update_time)
VALUES
('case-existing-item','WORK-CASE-TARGET','Case-equivalent item','manual','agt_11111111111111111111111111111111',
 'ready',0,1,0,3,0,'TENANT-A','CLIENT-A',@now,@now);
SQL
"${MYSQL[@]}" "$DB_NAME" < "$TMP/fixture.sql"
"${MYSQL[@]}" --batch --raw "$DB_NAME" < "$DRY_RUN" > "$TMP/dry-run.tsv"

for status in BLOCKED_AGENT_ID_BOUNDARY_WHITESPACE BLOCKED_INVALID_JSON_TARGET_TYPE \
  BLOCKED_AMBIGUOUS_JSON_OBJECT BLOCKED_UNSUPPORTED_TASK_STATUS \
  BLOCKED_IDENTITY_NOT_FOUND BLOCKED_IDENTITY_SCOPE_MISMATCH \
  BLOCKED_EXISTING_MEMBER_COLLATION_CONFLICT BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT; do
  grep -q "$status" "$TMP/dry-run.tsv" || { echo "missing dry-run status $status" >&2; exit 1; }
done
grep -q $'plain-space\t.*BLOCKED_AGENT_ID_BOUNDARY_WHITESPACE' "$TMP/dry-run.tsv"
grep -q $'json-string-space\t.*BLOCKED_AGENT_ID_BOUNDARY_WHITESPACE' "$TMP/dry-run.tsv"
grep -q $'direct-wrong-type\t.*BLOCKED_INVALID_JSON_TARGET_TYPE' "$TMP/dry-run.tsv"
grep -q $'wrapper-wrong-type\t.*BLOCKED_INVALID_JSON_TARGET_TYPE' "$TMP/dry-run.tsv"
grep -q $'ambiguous-wrapper\t.*BLOCKED_AMBIGUOUS_JSON_OBJECT' "$TMP/dry-run.tsv"
grep -q $'array-ambiguous\t.*BLOCKED_AMBIGUOUS_JSON_OBJECT' "$TMP/dry-run.tsv"

"${MYSQL[@]}" --batch --raw "$DB_NAME" < "$MANIFEST" > "$TMP/manifest.tsv"
report_sha=$(sha256sum "$TMP/manifest.tsv" | awk '{print $1}')
approve_manifest "$DB_NAME" "$TMP/manifest.tsv" "$report_sha" "$APPROVED_OPERATOR" "$TMP/approve.out"
assert_scalar "$DB_NAME" "SELECT COUNT(DISTINCT report_sha256) FROM agent_task_backfill_manifest" 1
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run" 0

fake_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
expect_apply_failure fake-sha "$DB_NAME" "$fake_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "approved manifest SHA was not found"
expect_apply_failure nul-operator "$DB_NAME" "$report_sha" \
  "SET @b09_operator=CONCAT('bad',CHAR(0),'operator');" "operator must be byte-clean"

"${MYSQL[@]}" "$DB_NAME" -e "INSERT INTO agent_task_meta(task_id,reward_status,assigned_agent_id,assigned_at,tenant_id,client_id,create_time,update_time) VALUES('drift-added','assigned','legacy-one',1700000000000,'tenant-a','client-a',1700000000000,1700000000000)"
expect_apply_failure drift-add "$DB_NAME" "$report_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "current task row count differs"
"${MYSQL[@]}" "$DB_NAME" -e "DELETE FROM agent_task_meta WHERE BINARY task_id=BINARY 'drift-added'"

"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET assigned_agent_id='legacy-two' WHERE BINARY task_id=BINARY 'eligible'"
expect_apply_failure drift-source "$DB_NAME" "$report_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "current task source/scope/resolution differs"
"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET assigned_agent_id='legacy-one' WHERE BINARY task_id=BINARY 'eligible'"

"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET client_id='client-b' WHERE BINARY task_id=BINARY 'eligible'"
expect_apply_failure drift-scope "$DB_NAME" "$report_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "current task source/scope/resolution differs"
"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET client_id='client-a' WHERE BINARY task_id=BINARY 'eligible'"

"${MYSQL[@]}" "$DB_NAME" -e "CREATE TABLE b09_saved_meta LIKE agent_task_meta; INSERT INTO b09_saved_meta SELECT * FROM agent_task_meta WHERE BINARY task_id=BINARY 'eligible'; DELETE FROM agent_task_meta WHERE BINARY task_id=BINARY 'eligible'"
expect_apply_failure drift-delete "$DB_NAME" "$report_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "current task row count differs"
"${MYSQL[@]}" "$DB_NAME" -e "INSERT INTO agent_task_meta SELECT * FROM b09_saved_meta; DROP TABLE b09_saved_meta"

before_rollback=$(state_of "$DB_NAME")
"${MYSQL[@]}" "$DB_NAME" <<'SQL'
DELIMITER $$
CREATE TRIGGER trg_b09_probe_force_rollback BEFORE INSERT ON agent_task_work_item
FOR EACH ROW
BEGIN
  IF BINARY NEW.task_id = BINARY 'eligible' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 probe forced work-item failure';
  END IF;
END$$
DELIMITER ;
SQL
expect_apply_failure transactional-rollback "$DB_NAME" "$report_sha" \
  "SET @b09_operator='$APPROVED_OPERATOR';" "B09 probe forced work-item failure"
"${MYSQL[@]}" "$DB_NAME" -e "DROP TRIGGER trg_b09_probe_force_rollback"
[[ "$(state_of "$DB_NAME")" == "$before_rollback" ]] || { echo 'rollback left partial rows' >&2; exit 1; }

run_apply_sql "$DB_NAME" "$report_sha" "SET @b09_operator='$APPROVED_OPERATOR';" "$TMP/apply-1.out"
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run" 1
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run WHERE run_status='SUCCEEDED' AND BINARY operator=BINARY '$APPROVED_OPERATOR' AND BINARY report_sha256=BINARY '$report_sha'" 1
first_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item))" "$DB_NAME")
run_apply_sql "$DB_NAME" "$report_sha" "SET @b09_operator='$APPROVED_OPERATOR';" "$TMP/apply-2.out"
second_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item))" "$DB_NAME")
[[ "$second_business" == "$first_business" ]] || { echo "idempotency failed: $first_business -> $second_business" >&2; exit 1; }
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run" 2
assert_scalar "$DB_NAME" "SELECT MIN(occurrence_count) FROM agent_task_backfill_issue" 2
assert_scalar "$DB_NAME" "SELECT MAX(occurrence_count) FROM agent_task_backfill_issue" 2

set +e
"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_backfill_manifest SET approved_operator='tampered' LIMIT 1" >"$TMP/immutable.out" 2>&1
immutable_rc=$?
set -e
[[ $immutable_rc -ne 0 ]] || { echo 'manifest update unexpectedly succeeded' >&2; exit 1; }
grep -q 'approved manifest is immutable' "$TMP/immutable.out"

# A clean eligible-only database proves successful no-issue runs still persist a run audit.
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$CLEAN_DB_NAME\`; CREATE DATABASE \`$CLEAN_DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
"${MYSQL[@]}" "$CLEAN_DB_NAME" < "$SCHEMA"
"${MYSQL[@]}" "$CLEAN_DB_NAME" < "$AUDIT_SCHEMA"
"${MYSQL[@]}" "$CLEAN_DB_NAME" <<'SQL'
SET @now=1700000000000;
INSERT INTO agent_identity_registry
(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,audit_reason,create_time)
VALUES('agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','OPAQUE','ACTIVE','client-clean','tenant-clean','tenant-clean','probe',@now);
INSERT INTO agent_task_meta
(task_id,reward_status,assigned_agent_id,assigned_at,tenant_id,client_id,create_time,update_time)
VALUES('clean','assigned','agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',@now,'tenant-clean','client-clean',@now,@now);
SQL
"${MYSQL[@]}" --batch --raw "$CLEAN_DB_NAME" < "$MANIFEST" > "$TMP/clean-manifest.tsv"
clean_sha=$(sha256sum "$TMP/clean-manifest.tsv" | awk '{print $1}')
approve_manifest "$CLEAN_DB_NAME" "$TMP/clean-manifest.tsv" "$clean_sha" clean-approved "$TMP/clean-approve.out"
run_apply_sql "$CLEAN_DB_NAME" "$clean_sha" "SET @b09_operator='clean-approved';" "$TMP/clean-apply.out"
assert_scalar "$CLEAN_DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_issue" 0
assert_scalar "$CLEAN_DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run WHERE issue_row_count=0 AND run_status='SUCCEEDED'" 1

printf 'mysql_version=%s\n' "$version"
printf 'manifest_sha256=%s rows=%s\n' "$report_sha" \
  "$("${MYSQL[@]}" -Nse "SELECT COUNT(*) FROM agent_task_backfill_manifest WHERE report_sha256='$report_sha'" "$DB_NAME")"
printf 'business=%s runs=%s issues=%s\n' "$second_business" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_backfill_run' "$DB_NAME")" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_backfill_issue' "$DB_NAME")"
printf 'B09 MYSQL 8.0.21 PROBE PASSED\n'
