-- ECO-V0-W04 additive funded-task schema. No W02 economy table is rewritten.
CREATE TABLE IF NOT EXISTS agent_task_funding_operation (
    id                      BIGINT NOT NULL AUTO_INCREMENT,
    principal_type          VARCHAR(20) NOT NULL,
    principal_id            VARCHAR(100) NOT NULL,
    idempotency_key         VARBINARY(36) NOT NULL,
    request_hash            BINARY(32) NOT NULL,
    task_id                 VARCHAR(100) NOT NULL,
    status                  VARCHAR(16) NOT NULL DEFAULT 'POSTING',
    reserve_transaction_id  VARCHAR(100) DEFAULT NULL,
    receipt_task_version    BIGINT DEFAULT NULL,
    receipt_created_at      BIGINT DEFAULT NULL,
    receipt_updated_at      BIGINT DEFAULT NULL,
    tenant_id               VARCHAR(50) NOT NULL,
    client_id               VARCHAR(50) NOT NULL,
    create_time             BIGINT NOT NULL,
    update_time             BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_funding_operation_actor_key
        (tenant_id, client_id, principal_type, principal_id, idempotency_key),
    UNIQUE KEY uk_task_funding_operation_task (tenant_id, client_id, task_id),
    CONSTRAINT chk_task_funding_operation_key CHECK (OCTET_LENGTH(idempotency_key) = 36),
    CONSTRAINT chk_task_funding_operation_hash CHECK (OCTET_LENGTH(request_hash) = 32),
    CONSTRAINT chk_task_funding_operation_status CHECK (
        (status = 'POSTING' AND reserve_transaction_id IS NULL
            AND receipt_task_version IS NULL AND receipt_created_at IS NULL AND receipt_updated_at IS NULL)
        OR
        (status = 'COMPLETED' AND reserve_transaction_id IS NOT NULL
            AND receipt_task_version IS NOT NULL AND receipt_task_version = 0
            AND receipt_created_at IS NOT NULL AND receipt_created_at > 0
            AND receipt_updated_at IS NOT NULL AND receipt_updated_at > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Actor-scoped immutable funded-task create receipt root';

CREATE TABLE IF NOT EXISTS agent_task_funding (
    id                              BIGINT NOT NULL AUTO_INCREMENT,
    task_id                         VARCHAR(100) NOT NULL,
    funding_mode                    VARCHAR(32) NOT NULL,
    funding_status                  VARCHAR(24) NOT NULL,
    payer_principal_type            VARCHAR(20) NOT NULL,
    payer_principal_id              VARCHAR(100) NOT NULL,
    settlement_policy               VARCHAR(32) NOT NULL,
    gross_bounty_amount_micro       BIGINT NOT NULL,
    remaining_micro                 BIGINT NOT NULL,
    escrow_id                       VARCHAR(100) DEFAULT NULL,
    escrow_version                  BIGINT DEFAULT NULL,
    reserve_transaction_id          VARCHAR(100) DEFAULT NULL,
    required_skill_requirements     TEXT NOT NULL,
    cancel_idempotency_key          VARBINARY(36) DEFAULT NULL,
    cancel_request_hash             BINARY(32) DEFAULT NULL,
    refund_transaction_id           VARCHAR(100) DEFAULT NULL,
    cancel_refunded_micro           BIGINT DEFAULT NULL,
    cancel_task_version             BIGINT DEFAULT NULL,
    refunded_at                     BIGINT DEFAULT NULL,
    version                         BIGINT NOT NULL DEFAULT 0,
    tenant_id                       VARCHAR(50) NOT NULL,
    client_id                       VARCHAR(50) NOT NULL,
    create_time                     BIGINT NOT NULL,
    update_time                     BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_funding_task (tenant_id, client_id, task_id),
    UNIQUE KEY uk_agent_task_funding_escrow (tenant_id, client_id, escrow_id),
    KEY idx_agent_task_funding_payer
        (tenant_id, client_id, payer_principal_type, payer_principal_id, funding_status, id),
    CONSTRAINT chk_agent_task_funding_amount CHECK (
        gross_bounty_amount_micro > 0 AND remaining_micro >= 0
        AND remaining_micro <= gross_bounty_amount_micro),
    CONSTRAINT chk_agent_task_funding_mode CHECK (funding_mode = 'FUNDED_SINGLE_AGENT'),
    CONSTRAINT chk_agent_task_funding_policy CHECK (settlement_policy = 'GROSS_INCLUSIVE'),
    CONSTRAINT chk_agent_task_funding_state CHECK (
        (funding_status = 'RESERVING' AND version = 0 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'FUNDS_HELD' AND version >= 1 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 0 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'REFUNDED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NOT NULL AND OCTET_LENGTH(cancel_idempotency_key) = 36
            AND cancel_request_hash IS NOT NULL AND OCTET_LENGTH(cancel_request_hash) = 32
            AND refund_transaction_id IS NOT NULL
            AND cancel_refunded_micro IS NOT NULL AND cancel_refunded_micro > 0
            AND cancel_refunded_micro <= gross_bounty_amount_micro
            AND cancel_task_version IS NOT NULL AND cancel_task_version > 0
            AND refunded_at IS NOT NULL AND refunded_at > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='One additive V0 funded-bounty projection per task';
