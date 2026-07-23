#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
MYSQL_BIN=${MYSQL_BIN:-/home/isp/apps/mysql/bin/mysql}
MYSQL_SOCKET=${MYSQL_SOCKET:?Set MYSQL_SOCKET to an isolated MySQL 8.0.21 socket}
DB_NAME=${DB_NAME:-b09_probe_$$}
KEEP_DB=${KEEP_DB:-0}
MYSQL=("$MYSQL_BIN" --no-defaults -uroot -S "$MYSQL_SOCKET")
SCHEMA="$ROOT/src/main/resources/db/schema.sql"
DRY_RUN="$ROOT/src/main/resources/db/task-collaboration-backfill-dry-run.sql"
APPLY="$ROOT/src/main/resources/db/task-collaboration-backfill.sql"
TMP=$(mktemp -d /tmp/b09-mysql-probe.XXXXXX)

cleanup() {
  if [[ "$KEEP_DB" != "1" ]]; then
    "${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

version=$("${MYSQL[@]}" -Nse 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || { echo "expected MySQL 8.0.21, got $version" >&2; exit 1; }
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
"${MYSQL[@]}" "$DB_NAME" < "$SCHEMA"

cat > "$TMP/fixture.sql" <<'SQL'
SET @now = 1700000000000;
INSERT INTO agent_identity_registry
(canonical_agent_id, canonical_type, lifecycle_status, client_id, owner_jiacn, tenant_id, audit_reason, create_time)
VALUES
('agt_11111111111111111111111111111111','OPAQUE','ACTIVE','client-a','tenant-a','tenant-a','probe',@now),
('agt_22222222222222222222222222222222','OPAQUE','ACTIVE','client-a','tenant-a','tenant-a','probe',@now),
('agt_33333333333333333333333333333333','OPAQUE','ACTIVE','client-b','tenant-b','tenant-b','probe',@now);
ALTER TABLE agent_identity_registry DROP CHECK chk_identity_registry_type;
ALTER TABLE agent_identity_registry DROP CHECK chk_identity_registry_lifecycle;
ALTER TABLE agent_identity_registry DROP CHECK chk_identity_registry_canonical;
INSERT INTO agent_identity_registry
(canonical_agent_id, canonical_type, lifecycle_status, client_id, owner_jiacn, tenant_id, audit_reason, create_time)
VALUES
('agt_44444444444444444444444444444444','opaque','ACTIVE','client-a','tenant-a','tenant-a','probe-invalid-type',@now),
('agt_55555555555555555555555555555555','OPAQUE','Active','client-a','tenant-a','tenant-a','probe-invalid-lifecycle',@now);
SET @r1=(SELECT id FROM agent_identity_registry WHERE canonical_agent_id='agt_11111111111111111111111111111111');
SET @r2=(SELECT id FROM agent_identity_registry WHERE canonical_agent_id='agt_22222222222222222222222222222222');
INSERT INTO agent_identity_alias
(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,valid_to,
 client_id,owner_jiacn,tenant_id,audit_reason,create_time)
VALUES
(@r1,'agt_11111111111111111111111111111111','LEGACY_AGENT_ID','legacy-one','ACTIVE',1600000000000,NULL,
 'client-a','tenant-a','tenant-a','probe',@now),
(@r2,'agt_22222222222222222222222222222222','LEGACY_AGENT_ID','legacy-two','REVOKED',1600000000000,1800000000000,
 'client-a','tenant-a','tenant-a','probe',@now);
ALTER TABLE agent_identity_alias DROP CHECK chk_identity_alias_type;
ALTER TABLE agent_identity_alias DROP CHECK chk_identity_alias_status;
ALTER TABLE agent_identity_alias DROP CHECK chk_identity_alias_window;
INSERT INTO agent_identity_alias
(registry_id,canonical_agent_id,alias_type,alias_value,alias_status,valid_from,valid_to,
 client_id,owner_jiacn,tenant_id,audit_reason,create_time)
VALUES
(@r1,'agt_11111111111111111111111111111111','legacy_agent_id','bad-alias-type','ACTIVE',1600000000000,NULL,
 'client-a','tenant-a','tenant-a','probe-invalid-alias-type',@now),
(@r1,'agt_11111111111111111111111111111111','LEGACY_AGENT_ID','bad-alias-status','active',1600000000000,NULL,
 'client-a','tenant-a','tenant-a','probe-invalid-alias-status',@now);
INSERT INTO agent_task_meta
(task_id,reward_status,assigned_agent_id,assigned_at,tenant_id,client_id,create_time,update_time)
VALUES
('plain','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('array','running','["legacy-one",{"agentId":"agt_22222222222222222222222222222222"}]',@now,'tenant-a','client-a',@now,@now),
('object','completed','{"agentId":"legacy-one"}',@now,'tenant-a','client-a',@now,@now),
('wrapper','assigned','{"assignees":[{"agent_id":"legacy-one"}]}',@now,'tenant-a','client-a',@now,@now),
('revoked-historical','assigned','legacy-two',1700000000000,'tenant-a','client-a',@now,@now),
('active-running','running','legacy-one',@now,'tenant-a','client-a',@now,@now),
('unknown','assigned','no-such-agent',@now,'tenant-a','client-a',@now,@now),
('cross','assigned','agt_33333333333333333333333333333333',@now,'tenant-a','client-a',@now,@now),
('mixed','assigned','["legacy-one","bad"]',@now,'tenant-a','client-a',@now,@now),
('empty','open',NULL,NULL,NULL,NULL,@now,@now),
('bad-json','assigned','["legacy-one"',@now,'tenant-a','client-a',@now,@now),
('ambiguous-object','assigned','{"agentId":"legacy-one","id":"legacy-two"}',@now,'tenant-a','client-a',@now,@now),
('manual-member','assigned','legacy-one',@now,'tenant-a','client-a',@now,@now),
('existing-item','assigned','legacy-one',@now,'tenant-a','client-a',@now,@now),
('status-case','Running','legacy-one',@now,'tenant-a','client-a',@now,@now),
('bad-registry-type','assigned','agt_44444444444444444444444444444444',@now,'tenant-a','client-a',@now,@now),
('bad-registry-lifecycle','assigned','agt_55555555555555555555555555555555',@now,'tenant-a','client-a',@now,@now),
('bad-alias-type','assigned','bad-alias-type',@now,'tenant-a','client-a',@now,@now),
('bad-alias-status','assigned','bad-alias-status',@now,'tenant-a','client-a',@now,@now),
('agent-case-source','assigned','AGT_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('scope-case-source','assigned','agt_11111111111111111111111111111111',@now,'Tenant-A','Client-A',@now,@now),
('member-case-target','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('cafe-task','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now),
('work-key-target','assigned','agt_11111111111111111111111111111111',@now,'tenant-a','client-a',@now,@now);
INSERT INTO agent_task_member
(task_id,agent_id,member_role,member_status,assignment_source,version,tenant_id,client_id,create_time,update_time)
VALUES
('manual-member','agt_11111111111111111111111111111111','reviewer','working','manual',7,
 'tenant-a','client-a',@now,@now),
('MEMBER-CASE-TARGET','AGT_11111111111111111111111111111111','worker','accepted','manual',0,
 'TENANT-A','CLIENT-A',@now,@now);
INSERT INTO agent_task_work_item
(work_item_id,task_id,title,work_type,assignee_agent_id,status,priority,required_item,
 attempt_count,max_attempts,version,tenant_id,client_id,create_time,update_time)
VALUES
('manual-existing-item','existing-item','Manual item','manual','agt_11111111111111111111111111111111',
 'ready',0,1,0,3,0,'tenant-a','client-a',@now,@now),
('accent-existing-item','café-task','Accent-equivalent task item','manual','agt_11111111111111111111111111111111',
 'ready',0,1,0,3,0,'tenant-a','client-a',@now,@now),
(UPPER(CONCAT('b09-', LOWER(SHA2(CONCAT_WS(CHAR(31),'tenant-a','client-a','work-key-target'),256)))),
 'unrelated-key-holder','Case-equivalent deterministic key','manual','agt_11111111111111111111111111111111',
 'ready',0,1,0,3,0,'tenant-a','client-a',@now,@now);
SQL
"${MYSQL[@]}" "$DB_NAME" < "$TMP/fixture.sql"
"${MYSQL[@]}" --batch --raw "$DB_NAME" < "$DRY_RUN" > "$TMP/dry-run.tsv"

for status in ELIGIBLE BLOCKED_IDENTITY_NOT_FOUND BLOCKED_IDENTITY_SCOPE_MISMATCH BLOCKED_INVALID_JSON BLOCKED_AMBIGUOUS_JSON_OBJECT BLOCKED_UNSUPPORTED_TASK_STATUS BLOCKED_NON_CANONICAL_IDENTITY_RECORD BLOCKED_EXISTING_MEMBER_COLLATION_CONFLICT BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT SKIPPED_EMPTY_ASSIGNEE; do
  grep -q "$status" "$TMP/dry-run.tsv" || { echo "missing dry-run status $status" >&2; exit 1; }
done
grep -q $'array\t.*MEMBERS_ONLY_MANUAL_DECOMPOSITION' "$TMP/dry-run.tsv"
grep -q $'active-running\t.*DEFAULT_SINGLE_WORK_ITEM\tworking\tblocked' "$TMP/dry-run.tsv"
grep -q $'status-case\t.*BLOCKED_UNSUPPORTED_TASK_STATUS' "$TMP/dry-run.tsv"
grep -q $'bad-registry-type\t.*BLOCKED_NON_CANONICAL_IDENTITY_RECORD' "$TMP/dry-run.tsv"
grep -q $'bad-registry-lifecycle\t.*BLOCKED_NON_CANONICAL_IDENTITY_RECORD' "$TMP/dry-run.tsv"
grep -q $'bad-alias-type\t.*BLOCKED_NON_CANONICAL_IDENTITY_RECORD' "$TMP/dry-run.tsv"
grep -q $'bad-alias-status\t.*BLOCKED_NON_CANONICAL_IDENTITY_RECORD' "$TMP/dry-run.tsv"
grep -q $'member-case-target\t.*BLOCKED_EXISTING_MEMBER_COLLATION_CONFLICT' "$TMP/dry-run.tsv"
grep -q $'cafe-task\t.*BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT' "$TMP/dry-run.tsv"
grep -q $'work-key-target\t.*BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT' "$TMP/dry-run.tsv"

set +e
"${MYSQL[@]}" "$DB_NAME" < "$APPLY" > "$TMP/missing-guard.out" 2>&1
missing_guard_rc=$?
set -e
[[ $missing_guard_rc -ne 0 ]] || { echo 'apply unexpectedly passed without approval guard' >&2; exit 1; }
grep -q 'approved dry-run report SHA-256' "$TMP/missing-guard.out"

report_sha=$(sha256sum "$TMP/dry-run.tsv" | awk '{print $1}')
run_apply() {
  local operator=$1
  { printf "SET @b09_approved_report_sha256='%s'; SET @b09_operator='%s';\n" "$report_sha" "$operator"; cat "$APPLY"; } \
    | "${MYSQL[@]}" --batch --raw "$DB_NAME" > "$TMP/apply-$operator.tsv"
}
run_apply probe-1

assert_scalar() {
  local sql=$1 expected=$2 actual
  actual=$("${MYSQL[@]}" -Nse "$sql" "$DB_NAME")
  [[ "$actual" == "$expected" ]] || { echo "expected [$expected], got [$actual] for $sql" >&2; exit 1; }
}

assert_scalar "SELECT COUNT(*) FROM agent_task_member" 10
assert_scalar "SELECT COUNT(*) FROM agent_task_work_item" 9
assert_scalar "SELECT COUNT(*) FROM agent_task_member WHERE task_id='mixed'" 0
assert_scalar "SELECT COUNT(*) FROM agent_task_member WHERE BINARY task_id IN (BINARY 'unknown',BINARY 'cross',BINARY 'bad-json',BINARY 'ambiguous-object',BINARY 'status-case',BINARY 'bad-registry-type',BINARY 'bad-registry-lifecycle',BINARY 'bad-alias-type',BINARY 'bad-alias-status',BINARY 'agent-case-source',BINARY 'scope-case-source',BINARY 'member-case-target',BINARY 'cafe-task',BINARY 'work-key-target')" 0
assert_scalar "SELECT CONCAT(member_role,'/',member_status,'/',assignment_source,'/',version) FROM agent_task_member WHERE task_id='manual-member'" 'reviewer/working/manual/7'
assert_scalar "SELECT COUNT(*) FROM agent_task_work_item WHERE task_id='existing-item'" 1
assert_scalar "SELECT status FROM agent_task_work_item WHERE task_id='active-running'" blocked
assert_scalar "SELECT COUNT(*) FROM agent_task_work_item WHERE task_id='array'" 0
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code='REVIEW_MULTI_AGENT_WORK_ITEM_REQUIRED'" 1
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code='BLOCKED_UNSUPPORTED_TASK_STATUS'" 1
assert_scalar "SELECT issue_code FROM agent_task_backfill_issue WHERE BINARY task_id=BINARY 'status-case'" 'BLOCKED_UNSUPPORTED_TASK_STATUS'
assert_scalar "SELECT issue_code FROM agent_task_backfill_issue WHERE BINARY task_id=BINARY 'agent-case-source'" 'BLOCKED_IDENTITY_NOT_FOUND'
assert_scalar "SELECT issue_code FROM agent_task_backfill_issue WHERE BINARY task_id=BINARY 'scope-case-source'" 'BLOCKED_IDENTITY_SCOPE_MISMATCH'
assert_scalar "SELECT COUNT(*) FROM agent_task_member WHERE BINARY task_id=BINARY 'MEMBER-CASE-TARGET' AND BINARY tenant_id=BINARY 'TENANT-A' AND BINARY client_id=BINARY 'CLIENT-A'" 1
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code='BLOCKED_NON_CANONICAL_IDENTITY_RECORD'" 4
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code='BLOCKED_EXISTING_MEMBER_COLLATION_CONFLICT'" 1
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code='BLOCKED_EXISTING_WORK_ITEM_COLLATION_CONFLICT'" 2
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue WHERE issue_code LIKE 'BLOCKED_%'" 15

run_apply probe-2
assert_scalar "SELECT COUNT(*) FROM agent_task_member" 10
assert_scalar "SELECT COUNT(*) FROM agent_task_work_item" 9
assert_scalar "SELECT COUNT(*) FROM agent_task_backfill_issue" 17
assert_scalar "SELECT MIN(occurrence_count) FROM agent_task_backfill_issue" 2
assert_scalar "SELECT MAX(occurrence_count) FROM agent_task_backfill_issue" 2

printf 'mysql_version=%s\n' "$version"
printf 'dry_run_sha256=%s\n' "$report_sha"
printf 'members=%s work_items=%s issues=%s\n' \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_member' "$DB_NAME")" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_work_item' "$DB_NAME")" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_backfill_issue' "$DB_NAME")"
printf 'B09 MYSQL 8.0.21 PROBE PASSED\n'
