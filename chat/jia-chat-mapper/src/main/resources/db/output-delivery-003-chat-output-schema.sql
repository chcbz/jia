CREATE TABLE IF NOT EXISTS chat_output (
    tenant_id VARBINARY(200) NOT NULL, client_id VARBINARY(200) NOT NULL,
    conversation_id BIGINT NOT NULL, output_id VARBINARY(400) NOT NULL,
    output_version BIGINT NOT NULL, run_id VARBINARY(100) NOT NULL,
    producer_agent_id VARBINARY(400) NOT NULL, title VARCHAR(255) NOT NULL,
    file_name VARCHAR(255) NULL, artifact_type VARCHAR(30) NOT NULL,
    content MEDIUMTEXT NULL, object_id VARBINARY(100) NULL,
    content_hash BINARY(32) NOT NULL, content_byte_length BIGINT NOT NULL,
    mime_type VARCHAR(100) NOT NULL, state VARCHAR(20) NOT NULL,
    retain_until BIGINT NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id,client_id,output_id,output_version),
    KEY idx_chat_output_source
      (tenant_id,client_id,conversation_id,created_at,output_id,output_version),
    CONSTRAINT chk_chat_output_payload CHECK ((content IS NULL) <> (object_id IS NULL)),
    CONSTRAINT chk_chat_output_version CHECK (output_version > 0),
    CONSTRAINT chk_chat_output_length CHECK (content_byte_length >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
