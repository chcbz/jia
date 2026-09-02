-- ECO-V0-W02 economy ledger and escrow foundation.
-- Target: MySQL 8.0.21. Additive DDL only; no migration or business DML.

CREATE TABLE IF NOT EXISTS economy_account (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    account_id         VARCHAR(100) NOT NULL,
    owner_type         VARCHAR(20) NOT NULL,
    owner_id           VARCHAR(100) NOT NULL,
    purpose            VARCHAR(32) NOT NULL,
    currency           VARCHAR(16) NOT NULL,
    balance_micro      BIGINT NOT NULL DEFAULT 0,
    allow_negative     TINYINT NOT NULL DEFAULT 0,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version            BIGINT NOT NULL DEFAULT 0,
    tenant_id          VARCHAR(50) NOT NULL,
    client_id          VARCHAR(50) NOT NULL,
    create_time        BIGINT NOT NULL,
    update_time        BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_economy_account_id (tenant_id, client_id, account_id),
    UNIQUE KEY uk_economy_account_key (tenant_id, client_id, currency, owner_type, owner_id, purpose),
    KEY idx_economy_account_owner (tenant_id, client_id, owner_type, owner_id, currency, purpose),
    CONSTRAINT chk_economy_account_currency CHECK (currency = 'SILVER'),
    CONSTRAINT chk_economy_account_negative CHECK (
        allow_negative IN (0,1)
        AND (owner_type <> 'USER' OR purpose <> 'AVAILABLE' OR allow_negative = 0)
        AND (allow_negative = 1 OR balance_micro >= 0)
    ),
    CONSTRAINT chk_economy_account_status CHECK (status IN ('ACTIVE','FROZEN','CLOSED')),
    CONSTRAINT chk_economy_account_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='SILVER account balance cache; ledger remains authoritative';

CREATE TABLE IF NOT EXISTS economy_transaction (
    id                  BIGINT NOT NULL AUTO_INCREMENT,
    transaction_id      VARCHAR(100) NOT NULL,
    principal_type      VARCHAR(20) NOT NULL,
    principal_id        VARCHAR(100) NOT NULL,
    idempotency_key     VARBINARY(36) NOT NULL,
    request_hash        BINARY(32) NOT NULL,
    business_type       VARCHAR(32) NOT NULL,
    business_id         VARCHAR(100) NOT NULL,
    currency            VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'POSTING',
    entry_count         INT NOT NULL DEFAULT 0,
    debit_total_micro   BIGINT NOT NULL DEFAULT 0,
    credit_total_micro  BIGINT NOT NULL DEFAULT 0,
    posted_at           BIGINT DEFAULT NULL,
    tenant_id           VARCHAR(50) NOT NULL,
    client_id           VARCHAR(50) NOT NULL,
    create_time         BIGINT NOT NULL,
    update_time         BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_economy_transaction_id (tenant_id, client_id, transaction_id),
    UNIQUE KEY uk_economy_transaction_idempotency (tenant_id, client_id, principal_type, principal_id, idempotency_key),
    KEY idx_economy_transaction_business (tenant_id, client_id, business_type, business_id, posted_at, id),
    CONSTRAINT chk_economy_transaction_key CHECK (OCTET_LENGTH(idempotency_key) = 36),
    CONSTRAINT chk_economy_transaction_hash CHECK (OCTET_LENGTH(request_hash) = 32),
    CONSTRAINT chk_economy_transaction_currency CHECK (currency = 'SILVER'),
    CONSTRAINT chk_economy_transaction_totals CHECK (entry_count >= 0 AND debit_total_micro >= 0 AND credit_total_micro >= 0),
    CONSTRAINT chk_economy_transaction_state CHECK (
        (status = 'POSTING' AND posted_at IS NULL AND entry_count = 0 AND debit_total_micro = 0 AND credit_total_micro = 0)
        OR
        (status = 'POSTED' AND posted_at IS NOT NULL AND entry_count >= 2 AND debit_total_micro > 0 AND debit_total_micro = credit_total_micro)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Actor-idempotent complete journal transaction';

CREATE TABLE IF NOT EXISTS economy_entry (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    entry_id              VARCHAR(140) NOT NULL,
    transaction_id        VARCHAR(100) NOT NULL,
    account_id            VARCHAR(100) NOT NULL,
    entry_sequence        INT NOT NULL,
    signed_amount_micro   BIGINT NOT NULL,
    balance_after_micro   BIGINT NOT NULL,
    currency              VARCHAR(16) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    posted_at             BIGINT NOT NULL,
    tenant_id             VARCHAR(50) NOT NULL,
    client_id             VARCHAR(50) NOT NULL,
    create_time           BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_economy_entry_id (tenant_id, client_id, entry_id),
    UNIQUE KEY uk_economy_entry_sequence (tenant_id, client_id, transaction_id, entry_sequence),
    KEY idx_economy_entry_account (tenant_id, client_id, account_id, posted_at, id),
    KEY idx_economy_entry_transaction (tenant_id, client_id, transaction_id, id),
    CONSTRAINT chk_economy_entry_sequence CHECK (entry_sequence > 0),
    CONSTRAINT chk_economy_entry_amount CHECK (signed_amount_micro <> 0),
    CONSTRAINT chk_economy_entry_currency CHECK (currency = 'SILVER'),
    CONSTRAINT chk_economy_entry_status CHECK (status = 'POSTED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable balanced journal entry';

CREATE TABLE IF NOT EXISTS economy_escrow (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    escrow_id             VARCHAR(100) NOT NULL,
    business_type         VARCHAR(32) NOT NULL,
    business_id           VARCHAR(100) NOT NULL,
    payer_account_id      VARCHAR(100) NOT NULL,
    escrow_account_id     VARCHAR(100) NOT NULL,
    currency              VARCHAR(16) NOT NULL,
    gross_micro           BIGINT NOT NULL,
    captured_micro        BIGINT NOT NULL DEFAULT 0,
    refunded_micro        BIGINT NOT NULL DEFAULT 0,
    status                VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version               BIGINT NOT NULL DEFAULT 1,
    tenant_id             VARCHAR(50) NOT NULL,
    client_id             VARCHAR(50) NOT NULL,
    create_time           BIGINT NOT NULL,
    update_time           BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_economy_escrow_id (tenant_id, client_id, escrow_id),
    UNIQUE KEY uk_economy_escrow_business (tenant_id, client_id, business_type, business_id),
    UNIQUE KEY uk_economy_escrow_account (tenant_id, client_id, escrow_account_id),
    KEY idx_economy_escrow_payer (tenant_id, client_id, payer_account_id, status, id),
    CONSTRAINT chk_economy_escrow_currency CHECK (currency = 'SILVER'),
    CONSTRAINT chk_economy_escrow_amounts CHECK (
        gross_micro > 0 AND captured_micro >= 0 AND refunded_micro >= 0
        AND captured_micro <= gross_micro - refunded_micro
    ),
    CONSTRAINT chk_economy_escrow_status CHECK (status IN ('ACTIVE','PARTIALLY_CAPTURED','CAPTURED','REFUNDED','EXPIRED')),
    CONSTRAINT chk_economy_escrow_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Single escrow root per funded business aggregate';

CREATE TABLE IF NOT EXISTS economy_escrow_funding_lot (
    id                         BIGINT NOT NULL AUTO_INCREMENT,
    escrow_id                  VARCHAR(100) NOT NULL,
    funding_sequence           INT NOT NULL,
    reserve_transaction_id     VARCHAR(100) NOT NULL,
    payer_account_id           VARCHAR(100) NOT NULL,
    amount_micro               BIGINT NOT NULL,
    escrow_gross_after_micro   BIGINT NOT NULL,
    escrow_version_after       BIGINT NOT NULL,
    currency                   VARCHAR(16) NOT NULL,
    tenant_id                  VARCHAR(50) NOT NULL,
    client_id                  VARCHAR(50) NOT NULL,
    created_at                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_economy_funding_sequence (tenant_id, client_id, escrow_id, funding_sequence),
    UNIQUE KEY uk_economy_funding_transaction (tenant_id, client_id, reserve_transaction_id),
    KEY idx_economy_funding_escrow (tenant_id, client_id, escrow_id, id),
    CONSTRAINT chk_economy_funding_sequence CHECK (funding_sequence > 0),
    CONSTRAINT chk_economy_funding_amount CHECK (amount_micro > 0 AND escrow_gross_after_micro >= amount_micro),
    CONSTRAINT chk_economy_funding_version CHECK (escrow_version_after > 0),
    CONSTRAINT chk_economy_funding_currency CHECK (currency = 'SILVER')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable initial and top-up escrow funding lots';

DROP TRIGGER IF EXISTS trg_economy_transaction_posted_no_update;
DROP TRIGGER IF EXISTS trg_economy_transaction_posted_no_delete;
DROP TRIGGER IF EXISTS trg_economy_entry_no_update;
DROP TRIGGER IF EXISTS trg_economy_entry_no_delete;
DROP TRIGGER IF EXISTS trg_economy_funding_lot_no_update;
DROP TRIGGER IF EXISTS trg_economy_funding_lot_no_delete;

CREATE TRIGGER trg_economy_transaction_posted_no_update
BEFORE UPDATE ON economy_transaction
FOR EACH ROW
BEGIN
    IF OLD.status = 'POSTED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'ECO-V0: POSTED economy transactions are immutable';
    END IF;
END;

CREATE TRIGGER trg_economy_transaction_posted_no_delete
BEFORE DELETE ON economy_transaction
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ECO-V0: economy transaction deletion is forbidden';
END;

CREATE TRIGGER trg_economy_entry_no_update
BEFORE UPDATE ON economy_entry
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ECO-V0: POSTED economy entries are immutable';
END;

CREATE TRIGGER trg_economy_entry_no_delete
BEFORE DELETE ON economy_entry
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ECO-V0: economy entry deletion is forbidden';
END;

CREATE TRIGGER trg_economy_funding_lot_no_update
BEFORE UPDATE ON economy_escrow_funding_lot
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ECO-V0: escrow funding lots are immutable';
END;

CREATE TRIGGER trg_economy_funding_lot_no_delete
BEFORE DELETE ON economy_escrow_funding_lot
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ECO-V0: escrow funding lot deletion is forbidden';
END;
