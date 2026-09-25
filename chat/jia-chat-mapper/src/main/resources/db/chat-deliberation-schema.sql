-- T10/T11/T15 additive MySQL 8 contract. No destructive DDL or data rewrite.
CREATE TABLE IF NOT EXISTS chat_context_snapshot (
  snapshot_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  request_id VARCHAR(100) NOT NULL,
  request_revision BIGINT NOT NULL,
  target_agent_id VARCHAR(100) NOT NULL,
  route VARCHAR(20) NOT NULL,
  source_vector_json TEXT NOT NULL,
  facts_manifest_json MEDIUMTEXT NOT NULL,
  context_digest VARCHAR(100) NOT NULL,
  created_at BIGINT NOT NULL,
  PRIMARY KEY (snapshot_id),
  UNIQUE KEY uk_chat_snapshot_request_target
    (tenant_id, owner_jiacn, client_id, request_id, request_revision, target_agent_id),
  KEY idx_chat_snapshot_conversation
    (tenant_id, owner_jiacn, client_id, conversation_id, conversation_generation, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_request (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  request_id VARCHAR(100) NOT NULL,
  request_revision BIGINT NOT NULL,
  request_digest VARCHAR(100) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  user_message_id BIGINT NOT NULL,
  aggregate_state VARCHAR(30) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_request_scope_revision
    (tenant_id, owner_jiacn, client_id, request_id, request_revision),
  KEY idx_chat_request_conversation
    (tenant_id, owner_jiacn, client_id, conversation_id, conversation_generation, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_turn (
  turn_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  request_id VARCHAR(100) NOT NULL,
  request_revision BIGINT NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  target_agent_id VARCHAR(100) NOT NULL,
  snapshot_id VARCHAR(64) NOT NULL,
  context_digest VARCHAR(100) NOT NULL,
  dispatch_id VARCHAR(64) NOT NULL,
  route VARCHAR(20) NOT NULL,
  state VARCHAR(30) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0,
  last_delta_seq BIGINT NOT NULL DEFAULT 0,
  last_delta_digest VARCHAR(100) DEFAULT NULL,
  final_digest VARCHAR(100) DEFAULT NULL,
  final_message_id BIGINT DEFAULT NULL,
  terminal_reason VARCHAR(500) DEFAULT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (turn_id),
  UNIQUE KEY uk_chat_turn_request_target
    (tenant_id, owner_jiacn, client_id, request_id, target_agent_id),
  UNIQUE KEY uk_chat_turn_dispatch (dispatch_id),
  UNIQUE KEY uk_chat_turn_snapshot (snapshot_id),
  KEY idx_chat_turn_conversation
    (tenant_id, owner_jiacn, client_id, conversation_id, conversation_generation, state, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_dispatch_outbox (
  event_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  turn_id VARCHAR(64) NOT NULL,
  dispatch_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  available_at BIGINT NOT NULL,
  lease_owner VARCHAR(100) DEFAULT NULL,
  lease_until BIGINT DEFAULT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  last_error VARCHAR(500) DEFAULT NULL,
  sent_at BIGINT DEFAULT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_chat_outbox_turn_event (turn_id, event_type),
  KEY idx_chat_outbox_ready
    (status, available_at, event_id),
  KEY idx_chat_outbox_scope
    (tenant_id, owner_jiacn, client_id, turn_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_conversation_event (
  event_sequence BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(50) NOT NULL,
  owner_jiacn VARCHAR(50) NOT NULL,
  client_id VARCHAR(50) NOT NULL,
  conversation_id VARCHAR(100) NOT NULL,
  conversation_generation BIGINT NOT NULL,
  request_id VARCHAR(100) DEFAULT NULL,
  turn_id VARCHAR(64) DEFAULT NULL,
  dispatch_id VARCHAR(64) DEFAULT NULL,
  event_type VARCHAR(40) NOT NULL,
  event_version BIGINT NOT NULL,
  payload_json MEDIUMTEXT NOT NULL,
  occurred_at BIGINT NOT NULL,
  PRIMARY KEY (event_sequence),
  UNIQUE KEY uk_chat_conversation_event_id (event_id),
  KEY idx_chat_conversation_event_replay
    (tenant_id, owner_jiacn, client_id, conversation_id, conversation_generation, event_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_deliberation_schema_version (
  version BIGINT NOT NULL,
  stage VARCHAR(30) NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
