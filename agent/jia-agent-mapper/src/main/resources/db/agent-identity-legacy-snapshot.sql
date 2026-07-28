-- A08 read-only source snapshot helper (MySQL 8.0.21+).
-- This computes evidence only. It does not classify, approve, or insert identities.
-- Export both result sets and bind the reviewed manifest to their exact hashes.

SET @a08_old_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 16777216;

DROP TEMPORARY TABLE IF EXISTS a08_identity_snapshot_rows;
CREATE TEMPORARY TABLE a08_identity_snapshot_rows AS
SELECT b.id AS binding_id,
       b.client_id AS source_client_id,
       b.jiacn AS source_owner_jiacn,
       b.tenant_id AS source_tenant_id,
       b.persona_code AS source_persona_code,
       b.agent_id AS source_agent_id,
       b.status AS source_binding_status,
       b.bound_at AS source_bound_at,
       SHA2(CONCAT_WS('|',
           CAST(b.id AS CHAR),
           IFNULL(HEX(b.client_id), '<NULL>'),
           IFNULL(HEX(b.jiacn), '<NULL>'),
           IFNULL(HEX(b.tenant_id), '<NULL>'),
           IFNULL(HEX(b.persona_code), '<NULL>'),
           IFNULL(HEX(b.agent_id), '<NULL>'),
           IFNULL(CAST(b.status AS CHAR), '<NULL>'),
           IFNULL(CAST(b.bound_at AS CHAR), '<NULL>')), 256) AS source_row_sha256
  FROM agent_persona_binding b;

SELECT binding_id, source_client_id, source_owner_jiacn, source_tenant_id,
       source_persona_code, source_agent_id, source_binding_status,
       source_bound_at, source_row_sha256
  FROM a08_identity_snapshot_rows
 ORDER BY binding_id;

SELECT COUNT(*) AS source_snapshot_row_count,
       COALESCE(SUM(OCTET_LENGTH(CONCAT(
           LPAD(binding_id, 20, '0'), ':', source_row_sha256, '|'))), 0)
           AS source_snapshot_payload_bytes,
       @@SESSION.group_concat_max_len AS source_snapshot_hash_buffer_bytes,
       SHA2(COALESCE(GROUP_CONCAT(
           CONCAT(LPAD(binding_id, 20, '0'), ':', source_row_sha256)
           ORDER BY binding_id SEPARATOR '|'), ''), 256) AS source_snapshot_sha256
  FROM a08_identity_snapshot_rows;

DROP TEMPORARY TABLE a08_identity_snapshot_rows;
SET SESSION group_concat_max_len = @a08_old_group_concat_max_len;
