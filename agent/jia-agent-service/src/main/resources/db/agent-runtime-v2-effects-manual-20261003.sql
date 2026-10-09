-- MANUAL REVIEW ONLY. Not a startup migration; do not execute on a shared or production database.
-- R4 remains readiness=false. Real MySQL deployment/schema/trigger validation belongs to scoped R5.
-- No foreign-key cascade, trigger, historical rewrite or table reset is introduced.
-- Accepted origin/STARTED/grant/fact and resource links must also be validated under composite locks.

CREATE TABLE agent_runtime_v2_effect_run (
  runtime_dispatch_id CHAR(36) NOT NULL,
  run_token CHAR(36) NOT NULL,
  execution_origin_snapshot_sha256 BINARY(32) NOT NULL,
  dispatch_wire_sha256 BINARY(32) NOT NULL,
  started_receipt_id CHAR(36) NOT NULL,
  started_receipt_sha256 BINARY(32) NOT NULL,
  original_start_lease_fact_id CHAR(36) NOT NULL,
  disposition VARCHAR(24) NOT NULL,
  retired TINYINT NOT NULL,
  version BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (runtime_dispatch_id),
  UNIQUE KEY uq_r4_run_token (run_token),
  CONSTRAINT ck_r4_run_disposition CHECK (disposition IN ('ACTIVE','CANCELLING','DRAINED_KNOWN','EFFECT_UNKNOWN','SETTLED_KNOWN')),
  CONSTRAINT ck_r4_run_retired CHECK (retired IN (0,1)),
  CONSTRAINT ck_r4_run_version CHECK (version>=0 AND created_at>0 AND updated_at>=created_at),
  CONSTRAINT ck_r4_run_closed CHECK (disposition='ACTIVE' OR retired=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_runtime_v2_effect (
  effect_id CHAR(68) NOT NULL,
  runtime_dispatch_id CHAR(36) NOT NULL,
  run_token CHAR(36) NOT NULL,
  effect_kind VARCHAR(24) NOT NULL,
  resource_wire_bytes VARBINARY(8192) NOT NULL,
  resource_sha256 BINARY(32) NOT NULL,
  execution_grant_id CHAR(36) NOT NULL,
  execution_grant_sha256 BINARY(32) NOT NULL,
  holder_generation_fact_id CHAR(36) NOT NULL,
  current_lease_fact_id CHAR(36) NOT NULL,
  admission_request_id CHAR(36) NULL,
  admitted_at BIGINT NULL,
  valid_until BIGINT NULL,
  disposition VARCHAR(24) NOT NULL,
  commit_receipt_id CHAR(36) NULL,
  result_wire_bytes MEDIUMBLOB NULL,
  result_sha256 BINARY(32) NULL,
  version BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (effect_id),
  KEY ix_r4_effect_dispatch (runtime_dispatch_id,effect_id),
  CONSTRAINT ck_r4_effect_kind CHECK (effect_kind IN ('SKILL_PACKAGE_READ','SKILL_ACTIVATE','SKILL_RESULT','WORKSPACE_PREPARE','WORKSPACE_MUTATE','FILE_DESCRIBE','FILE_INPUT_READ','FILE_STAGE','FILE_COMMIT','PROCESS_START','PROCESS_EXIT')),
  CONSTRAINT ck_r4_effect_disposition CHECK (disposition IN ('DESCRIBED','STAGED','ADMITTED_IN_FLIGHT','SERVER_COMMITTED','LOCAL_COMMIT_OBSERVED','EXTERNAL_UNKNOWN','SETTLED_KNOWN')),
  CONSTRAINT ck_r4_effect_resource CHECK (OCTET_LENGTH(resource_wire_bytes)>0 AND OCTET_LENGTH(resource_wire_bytes)<=8192),
  CONSTRAINT ck_r4_effect_ticket CHECK ((admission_request_id IS NULL AND admitted_at IS NULL AND valid_until IS NULL) OR (admission_request_id IS NOT NULL AND admitted_at IS NOT NULL AND valid_until IS NOT NULL AND admitted_at>0 AND valid_until>admitted_at AND valid_until-admitted_at<=1000)),
  CONSTRAINT ck_r4_effect_result CHECK ((result_wire_bytes IS NULL AND result_sha256 IS NULL) OR (result_wire_bytes IS NOT NULL AND result_sha256 IS NOT NULL AND OCTET_LENGTH(result_wire_bytes)>0 AND OCTET_LENGTH(result_wire_bytes)<=98304)),
  CONSTRAINT ck_r4_effect_version CHECK (version>=0 AND created_at>0 AND updated_at>=created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_runtime_v2_effect_request (
  request_id CHAR(36) NOT NULL,
  runtime_dispatch_id CHAR(36) NOT NULL,
  request_family VARCHAR(12) NOT NULL,
  effect_id CHAR(68) NULL,
  subject_kind VARCHAR(14) NULL,
  subject_id VARCHAR(100) NULL,
  request_wire_bytes MEDIUMBLOB NOT NULL,
  request_sha256 BINARY(32) NOT NULL,
  response_wire_bytes MEDIUMBLOB NOT NULL,
  response_sha256 BINARY(32) NOT NULL,
  actor_credential_reference VARCHAR(100) NOT NULL,
  actor_registration_fact_id CHAR(36) NULL,
  actor_runtime_binding_id CHAR(36) NULL,
  actor_runtime_instance_id VARCHAR(100) NULL,
  actor_binding_generation BIGINT NULL,
  actor_session_fence VARCHAR(100) NOT NULL,
  actor_socket_owner_instance_id VARCHAR(100) NOT NULL,
  actor_socket_owner_epoch BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (request_id),
  KEY ix_r4_request_dispatch (runtime_dispatch_id,request_id),
  KEY ix_r4_request_effect (effect_id,request_id),
  CONSTRAINT ck_r4_request_family CHECK (request_family IN ('CONTROL','EFFECT','RECONCILE')),
  CONSTRAINT ck_r4_request_subject CHECK ((request_family='CONTROL' AND effect_id IS NULL AND subject_kind IS NULL AND subject_id IS NULL) OR (request_family='EFFECT' AND effect_id IS NOT NULL AND subject_kind IS NULL AND subject_id IS NULL) OR (request_family='RECONCILE' AND subject_kind IS NOT NULL AND subject_kind IN ('RENEWAL','EXECUTION','EFFECT','EFFECT_SUCCESS') AND subject_id IS NOT NULL)),
  CONSTRAINT ck_r4_request_bounds CHECK (OCTET_LENGTH(request_wire_bytes)>0 AND OCTET_LENGTH(request_wire_bytes)<=131072 AND OCTET_LENGTH(response_wire_bytes)>0 AND OCTET_LENGTH(response_wire_bytes)<=131072),
  CONSTRAINT ck_r4_request_actor CHECK (actor_credential_reference IS NOT NULL AND actor_session_fence IS NOT NULL AND actor_socket_owner_instance_id IS NOT NULL AND actor_socket_owner_epoch IS NOT NULL AND actor_socket_owner_epoch>=0 AND created_at IS NOT NULL AND created_at>0 AND ((request_family='RECONCILE' AND actor_registration_fact_id IS NULL AND actor_runtime_binding_id IS NULL AND actor_runtime_instance_id IS NULL AND actor_binding_generation IS NULL) OR (actor_registration_fact_id IS NOT NULL AND actor_runtime_binding_id IS NOT NULL AND actor_runtime_instance_id IS NOT NULL AND actor_binding_generation IS NOT NULL AND actor_binding_generation>=0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_runtime_v2_effect_event (
  effect_id CHAR(68) NOT NULL,
  event_sequence BIGINT NOT NULL,
  runtime_dispatch_id CHAR(36) NOT NULL,
  run_token CHAR(36) NOT NULL,
  request_id CHAR(36) NULL,
  previous_sha256 BINARY(32) NULL,
  event_kind VARCHAR(24) NOT NULL,
  evidence_wire_bytes MEDIUMBLOB NOT NULL,
  evidence_sha256 BINARY(32) NOT NULL,
  event_sha256 BINARY(32) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (effect_id,event_sequence),
  KEY ix_r4_event_dispatch (runtime_dispatch_id,effect_id,event_sequence),
  CONSTRAINT ck_r4_event_sequence CHECK ((event_sequence=0 AND previous_sha256 IS NULL) OR (event_sequence>0 AND previous_sha256 IS NOT NULL)),
  CONSTRAINT ck_r4_event_kind CHECK (event_kind IN ('REQUEST_SENT','OUTCOME_OBSERVED','CANCELLED','ADMITTED_IN_FLIGHT','SERVER_COMMIT','LOCAL_COMMIT_OBSERVED','EXTERNAL_UNKNOWN','RECONCILIATION','SETTLED_KNOWN')),
  CONSTRAINT ck_r4_event_evidence CHECK (OCTET_LENGTH(evidence_wire_bytes)>0 AND OCTET_LENGTH(evidence_wire_bytes)<=131072 AND created_at>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE agent_runtime_v2_effect_blob (
  effect_id CHAR(68) NOT NULL,
  chunk_offset BIGINT NOT NULL,
  chunk_length BIGINT NOT NULL,
  total_length BIGINT NOT NULL,
  content_sha256 BINARY(32) NOT NULL,
  chunk_sha256 BINARY(32) NOT NULL,
  chunk_bytes MEDIUMBLOB NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (effect_id,chunk_offset),
  CONSTRAINT ck_r4_blob_bounds CHECK (chunk_offset>=0 AND chunk_length>=0 AND chunk_length<=65536 AND total_length>=0 AND chunk_offset<=total_length AND chunk_length<=total_length-chunk_offset),
  CONSTRAINT ck_r4_blob_bytes CHECK (OCTET_LENGTH(chunk_bytes)=chunk_length AND created_at>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
