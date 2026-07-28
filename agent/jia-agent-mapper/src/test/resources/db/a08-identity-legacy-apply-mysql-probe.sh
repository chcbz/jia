#!/usr/bin/env bash
set -euo pipefail

# Destructive only to an isolated temporary database on the supplied MySQL 8.0.21 test server.
: "${A08_MYSQL_HOST:=127.0.0.1}"
: "${A08_MYSQL_PORT:=3306}"
: "${A08_MYSQL_USER:=root}"
: "${A08_MYSQL_PASSWORD:=}"
: "${A08_API_ROOT:=$(cd "$(dirname "$0")/../../../../../.." && pwd)}"

mysql_base=(mysql --protocol=tcp -h "$A08_MYSQL_HOST" -P "$A08_MYSQL_PORT" -u "$A08_MYSQL_USER" --batch --raw)
if [[ -n "$A08_MYSQL_PASSWORD" ]]; then
  mysql_base+=("--password=$A08_MYSQL_PASSWORD")
fi

db="a08_legacy_apply_probe_$(date +%s)_$$"
cleanup() {
  "${mysql_base[@]}" -e "DROP DATABASE IF EXISTS \`$db\`" >/dev/null 2>&1 || true
}
trap cleanup EXIT

version=$("${mysql_base[@]}" -Nse 'SELECT VERSION()')
[[ "$version" == 8.0.21* ]] || {
  echo "A08 probe requires MySQL 8.0.21, got $version" >&2
  exit 1
}

"${mysql_base[@]}" -e "CREATE DATABASE \`$db\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"

schema_sql="$A08_API_ROOT/agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql"
apply_sql="$A08_API_ROOT/agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-apply.sql"
[[ -r "$schema_sql" && -r "$apply_sql" ]] || {
  echo "A08 SQL resources are not readable under A08_API_ROOT=$A08_API_ROOT" >&2
  exit 1
}

"${mysql_base[@]}" "$db" <<SQL
CREATE TABLE agent_persona_binding (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  jiacn VARCHAR(50) NOT NULL,
  persona_code VARCHAR(50) NOT NULL,
  agent_id VARCHAR(100) NOT NULL,
  bound_at BIGINT NOT NULL,
  status INT NOT NULL,
  create_time BIGINT,
  update_time BIGINT,
  tenant_id VARCHAR(50),
  client_id VARCHAR(50)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE agent_identity_registry (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  canonical_agent_id VARCHAR(100) NOT NULL,
  canonical_type VARCHAR(32) NOT NULL,
  lifecycle_status VARCHAR(20) NOT NULL,
  client_id VARCHAR(50), owner_jiacn VARCHAR(50), tenant_id VARCHAR(50),
  binding_id BIGINT, provisioned_at BIGINT, activated_at BIGINT,
  suspended_at BIGINT, retired_at BIGINT,
  audit_reason VARCHAR(1000) NOT NULL, create_time BIGINT, update_time BIGINT,
  UNIQUE KEY uk_identity_registry_agent (canonical_agent_id),
  UNIQUE KEY uk_identity_registry_binding (binding_id),
  UNIQUE KEY uk_identity_registry_alias_target
    (id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE agent_identity_alias (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  registry_id BIGINT NOT NULL,
  canonical_agent_id VARCHAR(100) NOT NULL,
  alias_type VARCHAR(32) NOT NULL,
  alias_value VARCHAR(100) NOT NULL,
  alias_status VARCHAR(20) NOT NULL,
  valid_from BIGINT NOT NULL,
  valid_to BIGINT,
  active_key TINYINT GENERATED ALWAYS AS
    (CASE WHEN alias_status='ACTIVE' AND valid_to IS NULL THEN 1 ELSE NULL END) STORED,
  client_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  audit_reason VARCHAR(1000) NOT NULL,
  create_time BIGINT, update_time BIGINT,
  UNIQUE KEY uk_identity_alias_active
    (client_id, owner_jiacn, alias_type, alias_value, active_key),
  CONSTRAINT fk_probe_alias_registry FOREIGN KEY
    (registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
    REFERENCES agent_identity_registry
    (id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

SOURCE $schema_sql;

INSERT INTO agent_persona_binding
  (jiacn, persona_code, agent_id, bound_at, status,
   tenant_id, client_id, create_time, update_time)
VALUES
  ('owner-a', 'wuyong', 'legacy-wuyong', 100, 1,
   NULL, 'client-a', 1, 1);

SET @source_row_sha = (
  SELECT SHA2(CONCAT_WS('|',
    CAST(id AS CHAR), IFNULL(HEX(client_id), '<NULL>'),
    IFNULL(HEX(jiacn), '<NULL>'), IFNULL(HEX(tenant_id), '<NULL>'),
    IFNULL(HEX(persona_code), '<NULL>'), IFNULL(HEX(agent_id), '<NULL>'),
    IFNULL(CAST(status AS CHAR), '<NULL>'), IFNULL(CAST(bound_at AS CHAR), '<NULL>')), 256)
  FROM agent_persona_binding WHERE id=1);
SET @snapshot_sha = SHA2(CONCAT(LPAD(1, 20, '0'), ':', @source_row_sha), 256);
SET @report_sha = SHA2('reviewed-a08-report', 256);

INSERT INTO agent_identity_legacy_manifest (
  batch_id, manifest_row_no, binding_id,
  source_client_id, source_owner_jiacn, source_tenant_id, target_tenant_id,
  source_persona_code, source_agent_id, source_binding_status, source_bound_at,
  source_row_sha256, source_snapshot_row_count, source_snapshot_sha256,
  canonical_agent_id, canonical_type, lifecycle_status, legacy_agent_id,
  audit_reason, approved_report_sha256, approval_status,
  approved_by, approved_at, create_time
) VALUES (
  'probe-approved', 1, 1,
  'client-a', 'owner-a', NULL, 'owner-a',
  'wuyong', 'legacy-wuyong', 1, 100,
  @source_row_sha, 1, @snapshot_sha,
  'agt_cccccccccccccccccccccccccccccccc', 'OPAQUE', 'ACTIVE', 'legacy-wuyong',
  'explicit test approval', @report_sha, 'APPROVED',
  'probe-reviewer', 1000, 1000
);

SET @a08_manifest_batch_id='probe-approved';
SET @a08_approved_report_sha256=@report_sha;
SET @a08_operator='probe-operator';
SOURCE $apply_sql;

DROP PROCEDURE IF EXISTS a08_probe_assert;
DELIMITER \$\$
CREATE PROCEDURE a08_probe_assert(IN ok BOOLEAN, IN message_text VARCHAR(255))
BEGIN
  IF ok IS NULL OR ok = FALSE THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = message_text;
  END IF;
END\$\$
DELIMITER ;
CALL a08_probe_assert((SELECT COUNT(*) FROM agent_identity_registry)=1,
  'A08 probe: registry insert missing');
CALL a08_probe_assert((SELECT COUNT(*) FROM agent_identity_alias)=1,
  'A08 probe: alias insert missing');
CALL a08_probe_assert((SELECT COUNT(*) FROM agent_identity_legacy_apply_run
  WHERE run_status='SUCCEEDED' AND registry_insert_count=1 AND alias_insert_count=1)=1,
  'A08 probe: successful audited apply missing');
DROP PROCEDURE a08_probe_assert;

SET @a08_manifest_batch_id='probe-approved';
SET @a08_approved_report_sha256=@report_sha;
SET @a08_operator='probe-operator-rerun';
SOURCE $apply_sql;
SQL

noop_runs=$("${mysql_base[@]}" "$db" -Nse \
  "SELECT COUNT(*) FROM agent_identity_legacy_apply_run WHERE run_status='SUCCEEDED' AND registry_insert_count=0 AND alias_insert_count=0")
[[ "$noop_runs" == "1" ]] || {
  echo "A08 probe: idempotent no-op audit missing" >&2
  exit 1
}

"${mysql_base[@]}" "$db" -e \
  "INSERT INTO agent_persona_binding (jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) VALUES ('owner-a','linchong','new-after-review',200,1,'owner-a','client-a',1,1)"

expect_drift_failure() {
  local operator=$1
  local output="/tmp/a08-identity-drift-probe.$$.out"
  set +e
  "${mysql_base[@]}" "$db" <<SQL >"$output" 2>&1
SET @a08_manifest_batch_id='probe-approved';
SET @a08_approved_report_sha256=SHA2('reviewed-a08-report', 256);
SET @a08_operator='$operator';
SOURCE $apply_sql;
SQL
  local rc=$?
  set -e
  rm -f "$output"
  [[ $rc -ne 0 ]] || {
    echo "A08 probe: apply accepted binding drift for $operator" >&2
    exit 1
  }
}

expect_drift_failure 'probe-added-row'
"${mysql_base[@]}" "$db" -e "DELETE FROM agent_persona_binding WHERE id=2; UPDATE agent_persona_binding SET agent_id='changed-after-review' WHERE id=1"
expect_drift_failure 'probe-modified-row'
"${mysql_base[@]}" "$db" -e "UPDATE agent_persona_binding SET agent_id='legacy-wuyong' WHERE id=1; DELETE FROM agent_persona_binding WHERE id=1"
expect_drift_failure 'probe-deleted-row'

failed_runs=$("${mysql_base[@]}" "$db" -Nse \
  "SELECT COUNT(*) FROM agent_identity_legacy_apply_run WHERE run_status='FAILED' AND error_message LIKE '%snapshot changed%'")
[[ "$failed_runs" == "3" ]] || {
  echo "A08 probe: expected three durable drift failure audits, got $failed_runs" >&2
  exit 1
}

registry_count=$("${mysql_base[@]}" "$db" -Nse 'SELECT COUNT(*) FROM agent_identity_registry')
alias_count=$("${mysql_base[@]}" "$db" -Nse 'SELECT COUNT(*) FROM agent_identity_alias')
[[ "$registry_count" == "1" && "$alias_count" == "1" ]] || {
  echo "A08 probe: failed apply changed identity rows" >&2
  exit 1
}

echo "A08 MYSQL 8.0.21 LEGACY APPLY PROBE PASSED"
