-- ECO-V0-W04 bounded H2 integration fixture. MySQL catalog proof stays in the isolated selector.

CREATE TABLE agent_task_meta(
    id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
    reward_status VARCHAR(32) NOT NULL,assigned_agent_id VARCHAR(100),
    required_abilities VARCHAR(2000),reward INT,assigned_at BIGINT,started_at BIGINT,
    completed_at BIGINT,failure_reason VARCHAR(500),collaboration_mode VARCHAR(16) NOT NULL,
    risk_level VARCHAR(16) NOT NULL,max_agents INT NOT NULL,coordinator_agent_id VARCHAR(100),
    review_required BOOLEAN NOT NULL,task_version BIGINT NOT NULL,
    current_event_version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,create_time BIGINT NOT NULL,update_time BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,task_id));

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
);

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
);


CREATE TABLE economy_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(100) NOT NULL,
    owner_type VARCHAR(20) NOT NULL, owner_id VARCHAR(100) NOT NULL,
    purpose VARCHAR(32) NOT NULL, currency VARCHAR(16) NOT NULL,
    balance_micro BIGINT NOT NULL DEFAULT 0, allow_negative TINYINT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', version BIGINT NOT NULL DEFAULT 0,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,account_id),
    UNIQUE(tenant_id,client_id,currency,owner_type,owner_id,purpose));

CREATE TABLE economy_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, transaction_id VARCHAR(100) NOT NULL,
    principal_type VARCHAR(20) NOT NULL, principal_id VARCHAR(100) NOT NULL,
    idempotency_key VARBINARY(36) NOT NULL, request_hash BINARY(32) NOT NULL,
    business_type VARCHAR(32) NOT NULL, business_id VARCHAR(100) NOT NULL,
    currency VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
    entry_count INT NOT NULL, debit_total_micro BIGINT NOT NULL,
    credit_total_micro BIGINT NOT NULL, posted_at BIGINT,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,transaction_id),
    UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key));

CREATE TABLE economy_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, entry_id VARCHAR(140) NOT NULL,
    transaction_id VARCHAR(100) NOT NULL, account_id VARCHAR(100) NOT NULL,
    entry_sequence INT NOT NULL, signed_amount_micro BIGINT NOT NULL,
    balance_after_micro BIGINT NOT NULL, currency VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL, posted_at BIGINT NOT NULL,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,entry_id),
    UNIQUE(tenant_id,client_id,transaction_id,entry_sequence));

CREATE TABLE economy_escrow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, escrow_id VARCHAR(100) NOT NULL,
    business_type VARCHAR(32) NOT NULL, business_id VARCHAR(100) NOT NULL,
    payer_account_id VARCHAR(100) NOT NULL, escrow_account_id VARCHAR(100) NOT NULL,
    currency VARCHAR(16) NOT NULL, gross_micro BIGINT NOT NULL,
    captured_micro BIGINT NOT NULL, refunded_micro BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL, version BIGINT NOT NULL,
    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,escrow_id),
    UNIQUE(tenant_id,client_id,business_type,business_id),
    UNIQUE(tenant_id,client_id,escrow_account_id));

CREATE TABLE economy_escrow_funding_lot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, escrow_id VARCHAR(100) NOT NULL,
    funding_sequence INT NOT NULL, reserve_transaction_id VARCHAR(100) NOT NULL,
    payer_account_id VARCHAR(100) NOT NULL, amount_micro BIGINT NOT NULL,
    escrow_gross_after_micro BIGINT NOT NULL, escrow_version_after BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL, tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL, created_at BIGINT NOT NULL,
    UNIQUE(tenant_id,client_id,escrow_id,funding_sequence),
    UNIQUE(tenant_id,client_id,reserve_transaction_id));

CREATE TABLE agent_task_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    task_id VARCHAR(100) NOT NULL,
    event_version BIGINT NOT NULL,
    event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id VARCHAR(100),
    aggregate_type VARCHAR(30) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_json CLOB NOT NULL,
    occurred_at BIGINT NOT NULL,
    tenant_id VARCHAR(50) NOT NULL,
    client_id VARCHAR(50) NOT NULL,
    create_time BIGINT,
    update_time BIGINT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_event_version
        (tenant_id, client_id, task_id, event_version),
    UNIQUE KEY uk_task_event_id (tenant_id, client_id, event_id)
);

CREATE TABLE agent_task_member(id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100),
    agent_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50));
CREATE TABLE agent_task_work_item(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_item_id VARCHAR(100),
    task_id VARCHAR(100), tenant_id VARCHAR(50), client_id VARCHAR(50));
CREATE TABLE task_plan(id BIGINT AUTO_INCREMENT PRIMARY KEY, jiacn VARCHAR(50), type INT,
    period INT, crond VARCHAR(100), name VARCHAR(100), description VARCHAR(200), lunar INT,
    start_time BIGINT, end_time BIGINT, amount DECIMAL(20,2), remind INT, remind_phone VARCHAR(100),
    remind_msg VARCHAR(200), status INT, tenant_id VARCHAR(50), client_id VARCHAR(50),
    create_time BIGINT, update_time BIGINT);
CREATE TABLE task_item(id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT, status INT,
    execute_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT);
