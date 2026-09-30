-- R2b1 activation-only CHECK phase (2026-09-30).
-- REVIEW/RUN MANUALLY ONLY after the nullable-column phase, application write/read deployment,
-- and an operator proof that every reachable contract-v2 row has complete snapshot references.
-- This file contains no DML/backfill. If any proof query reports an incomplete reachable row, STOP.

ALTER TABLE agent_runtime_v2_binding
    ADD CONSTRAINT chk_runtime_v2_binding_registration CHECK (
      contract_version <> 2 OR
      (registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
       AND registration_authority_id IS NOT NULL
       AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND registration_authority_revision IS NOT NULL AND registration_authority_revision >= 0
       AND process_public_key_spki IS NOT NULL AND OCTET_LENGTH(process_public_key_spki)=44
       AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
       AND verifier_version IS NOT NULL AND verifier_version = binding_generation + 1
       AND verifier_key_id IS NOT NULL AND OCTET_LENGTH(verifier_key_id) BETWEEN 1 AND 100
       AND reconnect_verifier IS NOT NULL AND OCTET_LENGTH(reconnect_verifier)=32
       AND current_registration_receipt_id IS NOT NULL
       AND current_registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND current_registration_receipt_sha256 IS NOT NULL
       AND OCTET_LENGTH(current_registration_receipt_sha256)=32));

ALTER TABLE agent_runtime_v2_binding_generation_fact
    ADD CONSTRAINT chk_binding_generation_registration CHECK (
      event_type <> 'CONNECTED' OR
      (registration_subject_sha256 IS NOT NULL AND OCTET_LENGTH(registration_subject_sha256)=32
       AND registration_authority_id IS NOT NULL
       AND registration_authority_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND registration_authority_revision IS NOT NULL AND registration_authority_revision >= 0
       AND runtime_credential_client_id IS NOT NULL
       AND OCTET_LENGTH(runtime_credential_client_id) BETWEEN 1 AND 50
       AND process_public_key_sha256 IS NOT NULL AND OCTET_LENGTH(process_public_key_sha256)=32
       AND verifier_version IS NOT NULL AND verifier_version = binding_generation + 1
       AND registration_receipt_id IS NOT NULL
       AND registration_receipt_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND registration_receipt_sha256 IS NOT NULL AND OCTET_LENGTH(registration_receipt_sha256)=32
       AND connection_admission_kind IS NOT NULL
       AND connection_admission_kind IN ('CURRENT_ADMISSION','FROZEN_DRAIN')
       AND ((connection_admission_kind='CURRENT_ADMISSION' AND drain_control_operation_id IS NULL)
            OR (connection_admission_kind='FROZEN_DRAIN'
                AND drain_control_operation_id IS NOT NULL
                AND OCTET_LENGTH(drain_control_operation_id) BETWEEN 1 AND 100))
       AND generation_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(generation_snapshot_sha256)=32));

ALTER TABLE agent_consumer_inbox
    ADD CONSTRAINT chk_inbox_runtime_v2_origin_evidence CHECK (
      protocol_version IS NULL OR protocol_version<>2 OR runtime_dispatch_id IS NULL OR
      (dispatch_generation_fact_id IS NOT NULL
       AND dispatch_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND dispatch_generation_snapshot_sha256 IS NOT NULL
       AND OCTET_LENGTH(dispatch_generation_snapshot_sha256)=32
       AND execution_origin_snapshot_sha256 IS NOT NULL
       AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32));

ALTER TABLE agent_command_v2_ack_receipt
    ADD CONSTRAINT chk_ack_receipt_snapshot_links CHECK (
      contract_version<>2 OR
      (execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32
       AND ack_holder_generation_fact_id IS NOT NULL
       AND ack_holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
       AND ack_holder_generation_snapshot_sha256 IS NOT NULL
       AND OCTET_LENGTH(ack_holder_generation_snapshot_sha256)=32));

ALTER TABLE agent_command_v2_execution_lease_fact
    ADD CONSTRAINT chk_execution_lease_snapshot_links CHECK (
      (execution_origin_snapshot_sha256 IS NULL AND holder_generation_fact_id IS NULL
       AND holder_generation_snapshot_sha256 IS NULL)
      OR (execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32
          AND holder_generation_fact_id IS NOT NULL
          AND holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND holder_generation_snapshot_sha256 IS NOT NULL
          AND OCTET_LENGTH(holder_generation_snapshot_sha256)=32));

ALTER TABLE agent_command_v2_execution_grant
    ADD CONSTRAINT chk_execution_grant_snapshot_links CHECK (
      (execution_origin_snapshot_sha256 IS NULL AND holder_generation_fact_id IS NULL
       AND holder_generation_snapshot_sha256 IS NULL)
      OR (execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32
          AND holder_generation_fact_id IS NOT NULL
          AND holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND holder_generation_snapshot_sha256 IS NOT NULL
          AND OCTET_LENGTH(holder_generation_snapshot_sha256)=32));

ALTER TABLE agent_command_v2_execution_continuation_anchor
    ADD CONSTRAINT chk_execution_anchor_snapshot_links CHECK (
      (execution_origin_snapshot_sha256 IS NULL AND holder_generation_fact_id IS NULL
       AND holder_generation_snapshot_sha256 IS NULL)
      OR (execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32
          AND holder_generation_fact_id IS NOT NULL
          AND holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND holder_generation_snapshot_sha256 IS NOT NULL
          AND OCTET_LENGTH(holder_generation_snapshot_sha256)=32));

ALTER TABLE agent_command_v2_execution_request
    ADD CONSTRAINT chk_execution_request_snapshot_links CHECK (
      (execution_origin_snapshot_sha256 IS NULL AND requested_holder_generation_fact_id IS NULL
       AND requested_holder_generation_snapshot_sha256 IS NULL)
      OR (execution_origin_snapshot_sha256 IS NOT NULL AND OCTET_LENGTH(execution_origin_snapshot_sha256)=32
          AND requested_holder_generation_fact_id IS NOT NULL
          AND requested_holder_generation_fact_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND requested_holder_generation_snapshot_sha256 IS NOT NULL
          AND OCTET_LENGTH(requested_holder_generation_snapshot_sha256)=32));
