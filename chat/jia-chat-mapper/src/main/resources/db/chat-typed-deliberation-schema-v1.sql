-- MMD typed deliberation v3. Additive only; no rewrite of chat deliberation v2 tables.
CREATE TABLE IF NOT EXISTS chat_typed_outcome (
  outcome_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  request_id VARCHAR(100) NOT NULL,
  request_revision BIGINT NOT NULL,
  turn_id VARCHAR(64) NOT NULL,
  task_id VARCHAR(100) NOT NULL,
  assignment_revision BIGINT NOT NULL,
  assistant_message_id BIGINT NOT NULL,
  final_digest VARCHAR(100) NOT NULL,
  kind VARCHAR(30) NOT NULL,
  text MEDIUMTEXT NOT NULL,
  binding_json MEDIUMTEXT NOT NULL,
  facts_json MEDIUMTEXT NOT NULL,
  outcome_json MEDIUMTEXT NOT NULL,
  source_catalog_json MEDIUMTEXT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (outcome_id),
  UNIQUE KEY uk_chat_typed_outcome_scope_id
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id),
  UNIQUE KEY uk_chat_typed_outcome_scope_turn
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,turn_id),
  UNIQUE KEY uk_chat_typed_outcome_scope_request
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,request_revision),
  KEY idx_chat_typed_outcome_message
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,assistant_message_id),
  KEY idx_chat_typed_outcome_turn_fk (turn_id),
  CONSTRAINT fk_chat_typed_outcome_turn FOREIGN KEY (turn_id) REFERENCES chat_turn(turn_id) ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT chk_chat_typed_outcome_generation CHECK (conversation_generation>=1),
  CONSTRAINT chk_chat_typed_outcome_revision CHECK (request_revision=1 AND assignment_revision>=0),
  CONSTRAINT chk_chat_typed_outcome_digest CHECK
    (final_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'),
  CONSTRAINT chk_chat_typed_outcome_kind CHECK
    (kind IN ('ANSWER','CLARIFY','EXECUTION_PROPOSAL')),
  CONSTRAINT chk_chat_typed_outcome_json CHECK
    (JSON_VALID(binding_json) AND JSON_VALID(facts_json) AND JSON_VALID(outcome_json)
      AND JSON_VALID(source_catalog_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_typed_pending_question (
  pending_question_id VARCHAR(64) NOT NULL,
  outcome_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  question MEDIUMTEXT NOT NULL,
  required_facts_json TEXT NOT NULL,
  reply_request_id VARCHAR(100) DEFAULT NULL,
  reply_idempotency_key VARCHAR(100) DEFAULT NULL,
  reply_body_digest VARCHAR(100) DEFAULT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (pending_question_id),
  UNIQUE KEY uk_chat_typed_pending_outcome
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id),
  KEY idx_chat_typed_pending_scope_state
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,state,updated_at),
  CONSTRAINT fk_chat_typed_pending_outcome FOREIGN KEY
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id)
    REFERENCES chat_typed_outcome
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT chk_chat_typed_pending_state CHECK (state IN ('OPEN','ANSWERED')),
  CONSTRAINT chk_chat_typed_pending_version CHECK
    ((state='OPEN' AND state_version=0 AND reply_request_id IS NULL
      AND reply_idempotency_key IS NULL AND reply_body_digest IS NULL)
     OR (state='ANSWERED' AND state_version=1 AND reply_request_id IS NOT NULL
      AND reply_idempotency_key IS NOT NULL
      AND reply_body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$')),
  CONSTRAINT chk_chat_typed_pending_required CHECK (JSON_VALID(required_facts_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_typed_proposal (
  proposal_id VARCHAR(64) NOT NULL,
  outcome_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  operation VARCHAR(30) NOT NULL,
  instruction TEXT NOT NULL,
  source_ref_ids_json TEXT NOT NULL,
  source_selectors_json MEDIUMTEXT NOT NULL,
  parent_request_id VARCHAR(100) DEFAULT NULL,
  parent_step_id VARCHAR(64) DEFAULT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (proposal_id),
  UNIQUE KEY uk_chat_typed_proposal_outcome
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id),
  KEY idx_chat_typed_proposal_scope
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,created_at),
  CONSTRAINT fk_chat_typed_proposal_outcome FOREIGN KEY
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id)
    REFERENCES chat_typed_outcome
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,outcome_id)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT chk_chat_typed_proposal_state CHECK (state='PROPOSED' AND state_version=0),
  CONSTRAINT chk_chat_typed_proposal_operation CHECK
    (operation IN ('GENERATE_IMAGE','EDIT_IMAGE')),
  CONSTRAINT chk_chat_typed_proposal_sources CHECK
    (JSON_VALID(source_ref_ids_json) AND JSON_VALID(source_selectors_json)),
  CONSTRAINT chk_chat_typed_proposal_parent CHECK
    ((parent_request_id IS NULL AND parent_step_id IS NULL)
      OR (parent_request_id IS NOT NULL AND parent_step_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_typed_admission (
  admission_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  request_digest VARCHAR(100) NOT NULL,
  body_digest VARCHAR(100) NOT NULL,
  intent VARCHAR(30) NOT NULL,
  task_id VARCHAR(100) NOT NULL,
  assignment_revision BIGINT NOT NULL,
  parent_outcome_id VARCHAR(64) DEFAULT NULL,
  pending_question_id VARCHAR(64) DEFAULT NULL,
  request_id VARCHAR(100) NOT NULL,
  request_revision BIGINT NOT NULL,
  user_message_id BIGINT NOT NULL,
  turn_ids_json TEXT NOT NULL,
  source_catalog_json MEDIUMTEXT NOT NULL,
  state VARCHAR(30) NOT NULL,
  state_version BIGINT NOT NULL,
  event_cursor BIGINT NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (admission_id),
  UNIQUE KEY uk_chat_typed_admission_scope_key
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,idempotency_key),
  UNIQUE KEY uk_chat_typed_admission_scope_request
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,request_id,request_revision),
  KEY idx_chat_typed_admission_parent
    (tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,parent_outcome_id),
  KEY idx_chat_typed_admission_request_fk
    (tenant_id,owner_jiacn,client_id,request_id,request_revision),
  CONSTRAINT fk_chat_typed_admission_request FOREIGN KEY
    (tenant_id,owner_jiacn,client_id,request_id,request_revision)
    REFERENCES chat_request(tenant_id,owner_jiacn,client_id,request_id,request_revision)
    ON UPDATE RESTRICT ON DELETE RESTRICT,
  CONSTRAINT chk_chat_typed_admission_generation CHECK (conversation_generation>=1),
  CONSTRAINT chk_chat_typed_admission_digest CHECK
    (request_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'
      AND body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'),
  CONSTRAINT chk_chat_typed_admission_intent CHECK
    (intent IN ('DISCUSSION','CLARIFICATION_REPLY')),
  CONSTRAINT chk_chat_typed_admission_revision CHECK
    (request_revision=1 AND assignment_revision>=0 AND state_version>=0 AND event_cursor>=0),
  CONSTRAINT chk_chat_typed_admission_turns CHECK
    (JSON_VALID(turn_ids_json) AND JSON_VALID(source_catalog_json)),
  CONSTRAINT chk_chat_typed_admission_reply CHECK
    ((intent='DISCUSSION' AND pending_question_id IS NULL)
      OR (intent='CLARIFICATION_REPLY' AND parent_outcome_id IS NOT NULL
        AND pending_question_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
