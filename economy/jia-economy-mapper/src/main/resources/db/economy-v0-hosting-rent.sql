-- ECO-V0-R01 additive hosting-rent domain foundation.
-- Target: MySQL 8.0.21. No plan rows, prices, charging DML, or changes to W02 tables.

CREATE TABLE IF NOT EXISTS economy_hosting_rent_plan (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    plan_id            VARCHAR(100) NOT NULL,
    plan_version       BIGINT NOT NULL,
    amount_micro       BIGINT NOT NULL,
    period_seconds     BIGINT NOT NULL,
    quote_ttl_seconds  BIGINT NOT NULL,
    currency           VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    tenant_id          VARCHAR(50) NOT NULL,
    client_id          VARCHAR(50) NOT NULL,
    create_time        BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosting_plan_version (tenant_id,client_id,plan_id,plan_version),
    KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version),
    CONSTRAINT chk_hosting_plan_values CHECK (
        plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND quote_ttl_seconds > 0
    ),
    CONSTRAINT chk_hosting_plan_currency CHECK (currency = 'SILVER'),
    CONSTRAINT chk_hosting_plan_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable scoped hosting-rent plan versions; no production rows are seeded';

CREATE TABLE IF NOT EXISTS economy_hosting_rent_quote (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    quote_id                VARCHAR(100) NOT NULL,
    quote_purpose           VARCHAR(16) NOT NULL,
    plan_id                 VARCHAR(100) NOT NULL,
    plan_version            BIGINT NOT NULL,
    amount_micro            BIGINT NOT NULL,
    period_seconds          BIGINT NOT NULL,
    principal_type          VARCHAR(20) NOT NULL,
    principal_id            VARCHAR(100) NOT NULL,
    persona_code            VARCHAR(100) NOT NULL,
    agent_id                VARCHAR(100) NOT NULL,
    lease_id                VARCHAR(100) DEFAULT NULL,
    expected_lease_version  BIGINT DEFAULT NULL,
    idempotency_key         VARBINARY(36) NOT NULL,
    request_hash            BINARY(32) NOT NULL,
    expires_at              BIGINT NOT NULL,
    tenant_id               VARCHAR(50) NOT NULL,
    client_id               VARCHAR(50) NOT NULL,
    create_time             BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosting_quote_id (tenant_id,client_id,quote_id),
    UNIQUE KEY uk_hosting_quote_actor_key (tenant_id,client_id,principal_type,principal_id,idempotency_key),
    KEY idx_hosting_quote_agent (tenant_id,client_id,agent_id,expires_at,quote_id),
    CONSTRAINT chk_hosting_quote_values CHECK (
        plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND expires_at > create_time
    ),
    CONSTRAINT chk_hosting_quote_key CHECK (OCTET_LENGTH(idempotency_key) = 36),
    CONSTRAINT chk_hosting_quote_hash CHECK (OCTET_LENGTH(request_hash) = 32),
    CONSTRAINT chk_hosting_quote_actor CHECK (principal_type = 'USER'),
    CONSTRAINT chk_hosting_quote_purpose CHECK (
        (quote_purpose = 'INITIAL' AND lease_id IS NULL AND expected_lease_version IS NULL)
        OR
        (quote_purpose = 'RENEWAL' AND lease_id IS NOT NULL AND expected_lease_version IS NOT NULL AND expected_lease_version > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable actor/idempotency-scoped rent quotes bound to a canonical Agent';

CREATE TABLE IF NOT EXISTS economy_hosting_lease (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    lease_id           VARCHAR(100) NOT NULL,
    principal_type     VARCHAR(20) NOT NULL,
    principal_id       VARCHAR(100) NOT NULL,
    persona_code       VARCHAR(100) NOT NULL,
    agent_id           VARCHAR(100) NOT NULL,
    binding_id         VARCHAR(100) DEFAULT NULL,
    live_slot          TINYINT DEFAULT 1,
    plan_id            VARCHAR(100) NOT NULL,
    plan_version       BIGINT NOT NULL,
    amount_micro       BIGINT NOT NULL,
    period_seconds     BIGINT NOT NULL,
    status             VARCHAR(24) NOT NULL,
    paid_from          BIGINT DEFAULT NULL,
    paid_through       BIGINT DEFAULT NULL,
    latest_intent_id   VARCHAR(100) NOT NULL,
    version            BIGINT NOT NULL,
    tenant_id          VARCHAR(50) NOT NULL,
    client_id          VARCHAR(50) NOT NULL,
    create_time        BIGINT NOT NULL,
    update_time        BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosting_lease_id (tenant_id,client_id,lease_id),
    UNIQUE KEY uk_hosting_lease_agent (tenant_id,client_id,agent_id,live_slot),
    UNIQUE KEY uk_hosting_lease_intent (tenant_id,client_id,latest_intent_id),
    KEY idx_hosting_lease_actor (tenant_id,client_id,principal_type,principal_id,status,lease_id),
    CONSTRAINT chk_hosting_lease_values CHECK (
        plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND version > 0
    ),
    CONSTRAINT chk_hosting_lease_actor CHECK (principal_type = 'USER'),
    CONSTRAINT chk_hosting_lease_live_slot CHECK (
        (status = 'REFUNDED' AND live_slot IS NULL)
        OR (status IN ('PROVISIONING','ACTIVE') AND live_slot IS NOT NULL AND live_slot = 1)
    ),
    CONSTRAINT chk_hosting_lease_state CHECK (
        (status = 'PROVISIONING' AND paid_from IS NULL AND paid_through IS NULL)
        OR
        (status = 'ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from)
        OR
        (status = 'REFUNDED' AND paid_from IS NULL AND paid_through IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Canonical Agent hosting lease projection; existing Agents are not backfilled';

CREATE TABLE IF NOT EXISTS economy_hosting_provisioning_intent (
    id                       BIGINT NOT NULL AUTO_INCREMENT,
    intent_id                VARCHAR(100) NOT NULL,
    lease_id                 VARCHAR(100) NOT NULL,
    quote_id                 VARCHAR(100) NOT NULL,
    quote_purpose            VARCHAR(16) NOT NULL,
    principal_type           VARCHAR(20) NOT NULL,
    principal_id             VARCHAR(100) NOT NULL,
    persona_code             VARCHAR(100) NOT NULL,
    agent_id                 VARCHAR(100) NOT NULL,
    amount_micro             BIGINT NOT NULL,
    period_seconds           BIGINT NOT NULL,
    status                   VARCHAR(32) NOT NULL,
    reserve_idempotency_key  VARBINARY(36) NOT NULL,
    reserve_request_hash     BINARY(32) NOT NULL,
    reserve_transaction_id   VARCHAR(100) NOT NULL,
    reserved_at              BIGINT NOT NULL,
    escrow_version           BIGINT NOT NULL,
    capture_idempotency_key  VARBINARY(36) DEFAULT NULL,
    capture_request_hash     BINARY(32) DEFAULT NULL,
    capture_transaction_id   VARCHAR(100) DEFAULT NULL,
    captured_at              BIGINT DEFAULT NULL,
    refund_idempotency_key   VARBINARY(36) DEFAULT NULL,
    refund_request_hash      BINARY(32) DEFAULT NULL,
    refund_transaction_id    VARCHAR(100) DEFAULT NULL,
    refunded_at              BIGINT DEFAULT NULL,
    managed_api_key_id       VARCHAR(100) DEFAULT NULL,
    outcome_evidence_ref     VARCHAR(100) DEFAULT NULL,
    service_ready_at         BIGINT DEFAULT NULL,
    paid_from                BIGINT DEFAULT NULL,
    paid_through             BIGINT DEFAULT NULL,
    version                  BIGINT NOT NULL,
    tenant_id                VARCHAR(50) NOT NULL,
    client_id                VARCHAR(50) NOT NULL,
    create_time              BIGINT NOT NULL,
    update_time              BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosting_intent_id (tenant_id,client_id,intent_id),
    UNIQUE KEY uk_hosting_intent_quote (tenant_id,client_id,quote_id),
    UNIQUE KEY uk_hosting_intent_reserve_key (tenant_id,client_id,principal_type,principal_id,reserve_idempotency_key),
    KEY idx_hosting_intent_lease (tenant_id,client_id,lease_id,version),
    KEY idx_hosting_intent_status (tenant_id,client_id,status,update_time,intent_id),
    CONSTRAINT chk_hosting_intent_values CHECK (amount_micro > 0 AND period_seconds > 0 AND escrow_version > 0 AND version > 0),
    CONSTRAINT chk_hosting_intent_actor CHECK (principal_type = 'USER'),
    CONSTRAINT chk_hosting_intent_reserve_key CHECK (OCTET_LENGTH(reserve_idempotency_key) = 36),
    CONSTRAINT chk_hosting_intent_reserve_hash CHECK (OCTET_LENGTH(reserve_request_hash) = 32),
    CONSTRAINT chk_hosting_intent_capture_pair CHECK (
        (capture_idempotency_key IS NULL AND capture_request_hash IS NULL AND capture_transaction_id IS NULL AND captured_at IS NULL)
        OR
        (capture_idempotency_key IS NOT NULL AND capture_request_hash IS NOT NULL
         AND OCTET_LENGTH(capture_idempotency_key) = 36 AND OCTET_LENGTH(capture_request_hash) = 32
         AND capture_transaction_id IS NOT NULL AND captured_at IS NOT NULL)
    ),
    CONSTRAINT chk_hosting_intent_refund_pair CHECK (
        (refund_idempotency_key IS NULL AND refund_request_hash IS NULL AND refund_transaction_id IS NULL AND refunded_at IS NULL)
        OR
        (refund_idempotency_key IS NOT NULL AND refund_request_hash IS NOT NULL
         AND OCTET_LENGTH(refund_idempotency_key) = 36 AND OCTET_LENGTH(refund_request_hash) = 32
         AND refund_transaction_id IS NOT NULL AND refunded_at IS NOT NULL)
    ),
    CONSTRAINT chk_hosting_intent_paid_period CHECK (
        (status = 'ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from)
        OR (status <> 'ACTIVE' AND paid_from IS NULL AND paid_through IS NULL)
    ),
    CONSTRAINT chk_hosting_intent_state CHECK (
        (status = 'FUNDS_RESERVED' AND service_ready_at IS NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status IN ('PROVISIONING_UNKNOWN','FAILED_NO_EFFECT') AND service_ready_at IS NULL
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'SERVICE_READY' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'ACTIVE' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NOT NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'REFUNDED' AND service_ready_at IS NULL AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable source of truth for paid provisioning outcome and compensation';

-- Free reprovision is not a paid order. Original INITIAL intent remains the managed generation.
CREATE TABLE IF NOT EXISTS economy_hosting_reprovision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(100) NOT NULL,
    lease_id VARCHAR(100) NOT NULL,
    intent_id VARCHAR(100) NOT NULL,
    agent_id VARCHAR(100) NOT NULL,
    persona_code VARCHAR(100) NOT NULL,
    principal_id VARCHAR(100) NOT NULL,
    idempotency_key VARBINARY(36) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    lease_version BIGINT NOT NULL,
    paid_through BIGINT NOT NULL,
    requested_at BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    live_slot TINYINT DEFAULT 1,
    version BIGINT NOT NULL,
    service_ready_at BIGINT DEFAULT NULL,
    evidence_ref VARCHAR(100) DEFAULT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_hosting_reprovision_id (tenant_id,client_id,request_id),
    UNIQUE KEY uk_hosting_reprovision_key (tenant_id,client_id,principal_id,idempotency_key),
    UNIQUE KEY uk_hosting_reprovision_live (tenant_id,client_id,lease_id,live_slot),
    KEY idx_hosting_reprovision_pending (status,id),
    CONSTRAINT chk_hosting_reprovision_values CHECK (lease_version > 0 AND version > 0 AND requested_at > 0 AND paid_through > requested_at),
    CONSTRAINT chk_hosting_reprovision_key CHECK (OCTET_LENGTH(idempotency_key) = 36 AND OCTET_LENGTH(request_hash) = 32),
    CONSTRAINT chk_hosting_reprovision_state CHECK (
        (status IN ('ACCEPTED','PROVISIONING_UNKNOWN') AND live_slot IS NOT NULL AND live_slot = 1 AND service_ready_at IS NULL)
        OR (status = 'SERVICE_READY' AND live_slot IS NULL AND service_ready_at IS NOT NULL AND service_ready_at >= requested_at AND evidence_ref IS NOT NULL)
        OR (status = 'FAILED_NO_EFFECT' AND live_slot IS NULL AND service_ready_at IS NULL AND evidence_ref IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Free managed reprovision receipts; no posting and no paid period mutation';
