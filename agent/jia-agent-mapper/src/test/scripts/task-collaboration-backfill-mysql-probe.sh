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

approval_state_of() {
  local db=$1
  "${MYSQL[@]}" -Nse "SELECT CONCAT(
    (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch), '/',
    (SELECT COUNT(*) FROM agent_task_backfill_manifest))" "$db"
}

manifest_digest_of() {
  local file=$1 digest distinct
  digest=$(awk -F '\t' 'NR==2 {print $1}' "$file")
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || { echo "invalid exported digest [$digest]" >&2; exit 1; }
  distinct=$(awk -F '\t' 'NR>1 {print $1}' "$file" | sort -u | wc -l)
  [[ "$distinct" == 1 ]] || { echo "manifest rows do not share one digest" >&2; exit 1; }
  printf '%s' "$digest"
}

approve_manifest() {
  local db=$1 manifest_file=$2 digest=$3 operator=$4 output=$5
  {
    printf 'source %s;\n' "$STAGING"
    printf "LOAD DATA LOCAL INFILE '%s' INTO TABLE tmp_b09_approved_manifest_staging FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' IGNORE 1 LINES;\n" "$manifest_file"
    printf "SET @b09_approved_manifest_digest='%s'; SET @b09_operator='%s';\n" "$digest" "$operator"
    printf 'source %s;\n' "$APPROVE"
  } | "${MYSQL[@]}" --batch --raw "$db" >"$output" 2>&1
}

expect_approval_force_failure() {
  local label=$1 db=$2 file=$3 digest=$4 expected=$5
  local before after output="$TMP/${label}.out"
  before=$(approval_state_of "$db")
  set +e
  {
    printf 'source %s;\n' "$STAGING"
    printf "LOAD DATA LOCAL INFILE '%s' INTO TABLE tmp_b09_approved_manifest_staging FIELDS TERMINATED BY '\\t' LINES TERMINATED BY '\\n' IGNORE 1 LINES;\n" "$file"
    printf "SET @b09_approved_manifest_digest='%s'; SET @b09_operator='%s';\n" "$digest" "$APPROVED_OPERATOR"
    printf 'source %s;\n' "$APPROVE"
    printf "SELECT 'force-continued-after-approval-error';\n"
  } | "${MYSQL[@]}" --force --batch --raw "$db" >"$output" 2>&1
  set -e
  grep -q "$expected" "$output" || { echo "$label missing error [$expected]" >&2; cat "$output" >&2; exit 1; }
  grep -q 'force-continued-after-approval-error' "$output" || { echo "$label did not exercise --force continuation" >&2; cat "$output" >&2; exit 1; }
  after=$(approval_state_of "$db")
  [[ "$after" == "$before" ]] || { echo "$label left partial approval: $before -> $after" >&2; exit 1; }
}

run_apply_sql() {
  local db=$1 digest=$2 operator_sql=$3 output=$4
  {
    printf "SET @b09_approved_manifest_digest='%s';\n" "$digest"
    printf '%s\n' "$operator_sql"
    printf 'source %s;\n' "$APPLY"
  } | "${MYSQL[@]}" --batch --raw "$db" >"$output" 2>&1
}

expect_apply_force_failure() {
  local label=$1 db=$2 digest=$3 operator_sql=$4 expected=$5
  local before after output="$TMP/${label}.out"
  before=$(state_of "$db")
  set +e
  {
    printf "SET @b09_approved_manifest_digest='%s';\n" "$digest"
    printf '%s\n' "$operator_sql"
    printf 'source %s;\n' "$APPLY"
    printf "SELECT 'force-continued-after-apply-error';\n"
  } | "${MYSQL[@]}" --force --batch --raw "$db" >"$output" 2>&1
  set -e
  grep -q "$expected" "$output" || { echo "$label missing error [$expected]" >&2; cat "$output" >&2; exit 1; }
  grep -q 'force-continued-after-apply-error' "$output" || { echo "$label did not exercise --force continuation" >&2; cat "$output" >&2; exit 1; }
  after=$(state_of "$db")
  [[ "$after" == "$before" ]] || { echo "$label changed transactional state: $before -> $after" >&2; exit 1; }
}

mutate_tsv() {
  local src=$1 dst=$2 column=$3 value=$4
  python3 - "$src" "$dst" "$column" "$value" <<'PY'
import sys
src, dst, column, value = sys.argv[1], sys.argv[2], int(sys.argv[3]) - 1, sys.argv[4]
lines = open(src, encoding='utf-8').read().splitlines()
fields = lines[1].split('\t')
fields[column] = value
lines[1] = '\t'.join(fields)
open(dst, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')
PY
}

version=$("${MYSQL[@]}" -Nse 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || { echo "expected MySQL 8.0.21, got $version" >&2; exit 1; }
"${MYSQL[@]}" -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
"${MYSQL[@]}" "$DB_NAME" < "$SCHEMA"
"${MYSQL[@]}" "$DB_NAME" < "$AUDIT_SCHEMA"
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_task_backfill_%'" 7
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('agent_task_backfill_issue','agent_task_backfill_manifest_batch','agent_task_backfill_manifest','agent_task_backfill_run') AND column_name='id' AND extra='auto_increment'" 4
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name IN ('agent_task_backfill_issue','agent_task_backfill_manifest_batch','agent_task_backfill_manifest','agent_task_backfill_run') AND index_name='PRIMARY' AND column_name='id' AND seq_in_index=1" 4

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

"${MYSQL[@]}" --batch --raw "$DB_NAME" < "$MANIFEST" > "$TMP/manifest.tsv"
manifest_digest=$(manifest_digest_of "$TMP/manifest.tsv")

# Approval attacks run under mysql --force and must leave no batch/row fragment.
fake_digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
awk -F '\t' -v OFS='\t' -v digest="$fake_digest" 'NR==1 {print; next} {$1=digest; print}' \
  "$TMP/manifest.tsv" > "$TMP/fake-digest.tsv"
expect_approval_force_failure fake-digest "$DB_NAME" "$TMP/fake-digest.tsv" "$fake_digest" 'canonical digest mismatch'
mutate_tsv "$TMP/manifest.tsv" "$TMP/forged-row-digest.tsv" 3 "$fake_digest"
expect_approval_force_failure forged-row-digest "$DB_NAME" "$TMP/forged-row-digest.tsv" "$manifest_digest" 'row key or row digest is forged'
{ cat "$TMP/manifest.tsv"; sed -n '2p' "$TMP/manifest.tsv"; } > "$TMP/duplicate-key.tsv"
expect_approval_force_failure duplicate-key "$DB_NAME" "$TMP/duplicate-key.tsv" "$manifest_digest" 'duplicate manifest row key'
emoji_hex=$(python3 - <<'PY'
print('F09F9880' * 101)
PY
)
mutate_tsv "$TMP/manifest.tsv" "$TMP/overlong-utf8.tsv" 5 "$emoji_hex"
expect_approval_force_failure overlong-utf8 "$DB_NAME" "$TMP/overlong-utf8.tsv" "$manifest_digest" 'malformed or oversized required HEX'
mutate_tsv "$TMP/manifest.tsv" "$TMP/odd-hex.tsv" 5 'ABC'
expect_approval_force_failure odd-hex "$DB_NAME" "$TMP/odd-hex.tsv" "$manifest_digest" 'malformed or oversized required HEX'
mutate_tsv "$TMP/manifest.tsv" "$TMP/illegal-hex.tsv" 5 'GG'
expect_approval_force_failure illegal-hex "$DB_NAME" "$TMP/illegal-hex.tsv" "$manifest_digest" 'malformed or oversized required HEX'

approve_manifest "$DB_NAME" "$TMP/manifest.tsv" "$manifest_digest" "$APPROVED_OPERATOR" "$TMP/approve.out"
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_manifest_batch WHERE report_sha256='$manifest_digest' AND seal_status='SEALED'" 1
assert_scalar "$DB_NAME" "SELECT manifest_row_count FROM agent_task_backfill_manifest_batch WHERE report_sha256='$manifest_digest'" "$(($(wc -l < "$TMP/manifest.tsv") - 1))"
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run" 0
expect_approval_force_failure duplicate-approval "$DB_NAME" "$TMP/manifest.tsv" "$manifest_digest" 'digest is already approved'

# Sealed rows/batch reject direct append/update/delete.
for attack in append update delete batch-update batch-delete; do
  set +e
  case "$attack" in
    append) "${MYSQL[@]}" "$DB_NAME" -e "INSERT INTO agent_task_backfill_manifest(report_sha256,manifest_row_key,manifest_row_sha256,meta_id,task_id,source_hash,source_format,source_shape,source_ordinal,resolution_status,task_resolution_status,approved_operator,approved_at) SELECT report_sha256,SHA2(CONCAT(manifest_row_key,'append'),256),manifest_row_sha256,meta_id,task_id,source_hash,source_format,source_shape,source_ordinal,resolution_status,task_resolution_status,approved_operator,approved_at FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest' LIMIT 1" >"$TMP/$attack.out" 2>&1 ;;
    update) "${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_backfill_manifest SET approved_operator='tampered' WHERE report_sha256='$manifest_digest' LIMIT 1" >"$TMP/$attack.out" 2>&1 ;;
    delete) "${MYSQL[@]}" "$DB_NAME" -e "DELETE FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest' LIMIT 1" >"$TMP/$attack.out" 2>&1 ;;
    batch-update) "${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_backfill_manifest_batch SET manifest_row_count=manifest_row_count+1 WHERE report_sha256='$manifest_digest'" >"$TMP/$attack.out" 2>&1 ;;
    batch-delete) "${MYSQL[@]}" "$DB_NAME" -e "DELETE FROM agent_task_backfill_manifest_batch WHERE report_sha256='$manifest_digest'" >"$TMP/$attack.out" 2>&1 ;;
  esac
  rc=$?
  set -e
  [[ $rc -ne 0 ]] || { echo "$attack unexpectedly succeeded" >&2; exit 1; }
done
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest'" "$(($(wc -l < "$TMP/manifest.tsv") - 1))"

# Apply independently verifies sealed row count and canonical digest.
"${MYSQL[@]}" "$DB_NAME" -e "DROP TRIGGER trg_task_backfill_manifest_insert_guard; DROP TRIGGER trg_task_backfill_manifest_no_delete; INSERT INTO agent_task_backfill_manifest(report_sha256,manifest_row_key,manifest_row_sha256,meta_id,task_id,source_hash,source_format,source_shape,source_ordinal,resolution_status,task_resolution_status,approved_operator,approved_at) SELECT report_sha256,SHA2(CONCAT(manifest_row_key,'tamper'),256),manifest_row_sha256,meta_id,task_id,source_hash,source_format,source_shape,source_ordinal,resolution_status,task_resolution_status,approved_operator,approved_at FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest' LIMIT 1"
expect_apply_force_failure sealed-count-tamper "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'sealed manifest row count mismatch'
"${MYSQL[@]}" "$DB_NAME" -e "DELETE FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest' AND manifest_row_key NOT IN (SELECT manifest_row_key FROM (SELECT manifest_row_key FROM agent_task_backfill_manifest WHERE report_sha256='$manifest_digest' ORDER BY id LIMIT $(($(wc -l < "$TMP/manifest.tsv") - 1))) keep_rows);"
"${MYSQL[@]}" "$DB_NAME" < "$AUDIT_SCHEMA"
original_key=$(awk -F '\t' 'NR==2 {print $2}' "$TMP/manifest.tsv")
original_row_digest=$(awk -F '\t' 'NR==2 {print $3}' "$TMP/manifest.tsv")
"${MYSQL[@]}" "$DB_NAME" -e "DROP TRIGGER trg_task_backfill_manifest_no_update; UPDATE agent_task_backfill_manifest SET manifest_row_sha256='$fake_digest' WHERE manifest_row_key='$original_key'"
expect_apply_force_failure sealed-digest-tamper "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'sealed manifest canonical digest mismatch'
"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_backfill_manifest SET manifest_row_sha256='$original_row_digest' WHERE manifest_row_key='$original_key'"
"${MYSQL[@]}" "$DB_NAME" < "$AUDIT_SCHEMA"

expect_apply_force_failure fake-apply-digest "$DB_NAME" "$fake_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'sealed approved manifest batch was not found'
expect_apply_force_failure nul-operator "$DB_NAME" "$manifest_digest" "SET @b09_operator=CONCAT('bad',CHAR(0),'operator');" 'operator must be byte-clean'

"${MYSQL[@]}" "$DB_NAME" -e "INSERT INTO agent_task_meta(task_id,reward_status,assigned_agent_id,assigned_at,tenant_id,client_id,create_time,update_time) VALUES('drift-added','assigned','legacy-one',1700000000000,'tenant-a','client-a',1700000000000,1700000000000)"
expect_apply_force_failure drift-add "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'current task row count differs'
"${MYSQL[@]}" "$DB_NAME" -e "DELETE FROM agent_task_meta WHERE BINARY task_id=BINARY 'drift-added'"

"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET assigned_agent_id='legacy-two' WHERE BINARY task_id=BINARY 'eligible'"
expect_apply_force_failure drift-source "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'current source/scope/resolution differs'
"${MYSQL[@]}" "$DB_NAME" -e "UPDATE agent_task_meta SET assigned_agent_id='legacy-one' WHERE BINARY task_id=BINARY 'eligible'"

# Failure after member DML (work-item trigger) must rollback everything under --force.
"${MYSQL[@]}" "$DB_NAME" <<'SQL'
DELIMITER $$
CREATE TRIGGER trg_b09_probe_force_work_item BEFORE INSERT ON agent_task_work_item
FOR EACH ROW
BEGIN
  IF BINARY NEW.task_id = BINARY 'eligible' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 probe forced work-item failure';
  END IF;
END$$
DELIMITER ;
SQL
expect_apply_force_failure force-work-item "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'B09 probe forced work-item failure'
"${MYSQL[@]}" "$DB_NAME" -e "DROP TRIGGER trg_b09_probe_force_work_item"

# Failure at final run audit must rollback prior issue/member/work-item DML too.
"${MYSQL[@]}" "$DB_NAME" <<'SQL'
DELIMITER $$
CREATE TRIGGER trg_b09_probe_force_run BEFORE INSERT ON agent_task_backfill_run
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 probe forced run failure';
END$$
DELIMITER ;
SQL
expect_apply_force_failure force-run "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" 'B09 probe forced run failure'
"${MYSQL[@]}" "$DB_NAME" -e "DROP TRIGGER trg_b09_probe_force_run"

run_apply_sql "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" "$TMP/apply-1.out"
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run WHERE run_status='SUCCEEDED' AND operator='$APPROVED_OPERATOR' AND report_sha256='$manifest_digest'" 1
first_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item))" "$DB_NAME")
run_apply_sql "$DB_NAME" "$manifest_digest" "SET @b09_operator='$APPROVED_OPERATOR';" "$TMP/apply-2.out"
second_business=$("${MYSQL[@]}" -Nse "SELECT CONCAT((SELECT COUNT(*) FROM agent_task_member),'/',(SELECT COUNT(*) FROM agent_task_work_item))" "$DB_NAME")
[[ "$second_business" == "$first_business" ]] || { echo "idempotency failed: $first_business -> $second_business" >&2; exit 1; }
assert_scalar "$DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run" 2
assert_scalar "$DB_NAME" "SELECT MIN(occurrence_count) FROM agent_task_backfill_issue" 2
assert_scalar "$DB_NAME" "SELECT MAX(occurrence_count) FROM agent_task_backfill_issue" 2

# Clean eligible-only database proves issue=0 success is still audited.
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
clean_digest=$(manifest_digest_of "$TMP/clean-manifest.tsv")
approve_manifest "$CLEAN_DB_NAME" "$TMP/clean-manifest.tsv" "$clean_digest" clean-approved "$TMP/clean-approve.out"
run_apply_sql "$CLEAN_DB_NAME" "$clean_digest" "SET @b09_operator='clean-approved';" "$TMP/clean-apply.out"
assert_scalar "$CLEAN_DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_issue" 0
assert_scalar "$CLEAN_DB_NAME" "SELECT COUNT(*) FROM agent_task_backfill_run WHERE issue_row_count=0 AND run_status='SUCCEEDED'" 1

printf 'mysql_version=%s\n' "$version"
printf 'manifest_digest=%s rows=%s\n' "$manifest_digest" \
  "$("${MYSQL[@]}" -Nse "SELECT manifest_row_count FROM agent_task_backfill_manifest_batch WHERE report_sha256='$manifest_digest'" "$DB_NAME")"
printf 'business=%s runs=%s issues=%s\n' "$second_business" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_backfill_run' "$DB_NAME")" \
  "$("${MYSQL[@]}" -Nse 'SELECT COUNT(*) FROM agent_task_backfill_issue' "$DB_NAME")"
printf 'B09 MYSQL 8.0.21 PROBE PASSED\n'
