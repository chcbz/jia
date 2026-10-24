-- MANUAL ONLY: no activation, backfill, DML or shared-database execution.
-- Old NULL-extension rows remain audit history, never new raw execution authority.
ALTER TABLE agent_command_v2_execution_request
  ADD COLUMN request_wire_bytes VARBINARY(512) NULL,
  ADD COLUMN previous_execution_grant_id VARCHAR(36) COLLATE utf8mb4_0900_bin NULL,
  ADD COLUMN previous_execution_grant_sha256 BINARY(32) NULL,
  ADD CONSTRAINT chk_execution_request_raw CHECK (
    (request_wire_bytes IS NULL AND previous_execution_grant_id IS NULL
      AND previous_execution_grant_sha256 IS NULL)
    OR (request_wire_bytes IS NOT NULL AND contract_version IS NOT NULL AND contract_version=2
      AND request_kind IS NOT NULL
      AND ((request_kind='CONTINUATION' AND OCTET_LENGTH(request_wire_bytes)=332
        AND previous_execution_grant_id IS NULL AND previous_execution_grant_sha256 IS NULL)
      OR (request_kind='RENEWAL' AND OCTET_LENGTH(request_wire_bytes)=488
        AND previous_execution_grant_id IS NOT NULL
        AND previous_execution_grant_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        AND previous_execution_grant_sha256 IS NOT NULL AND OCTET_LENGTH(previous_execution_grant_sha256)=32)))
  );
