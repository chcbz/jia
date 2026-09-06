-- ECO-V0-W07 platform skill marketplace foundation.
-- Target: MySQL 8.0.21. Additive DDL only; no seed, publication, order, or money DML.

CREATE TABLE IF NOT EXISTS economy_skill_product (
    id                         BIGINT NOT NULL AUTO_INCREMENT,
    product_id                 VARCHAR(100) NOT NULL,
    seller_type                VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    seller_id                  VARCHAR(100) NOT NULL DEFAULT 'SKILL_STORE',
    creator_agent_id           VARCHAR(100) DEFAULT NULL,
    name                       VARCHAR(200) NOT NULL,
    description                VARCHAR(1000) NOT NULL,
    status                     VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    current_product_version_id VARCHAR(100) DEFAULT NULL,
    version                    BIGINT NOT NULL DEFAULT 1,
    tenant_id                  VARCHAR(50) NOT NULL,
    client_id                  VARCHAR(50) NOT NULL,
    create_time                BIGINT NOT NULL,
    update_time                BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_product_id (tenant_id, client_id, product_id),
    KEY idx_skill_product_catalog (tenant_id, client_id, status, name, product_id),
    CONSTRAINT chk_skill_product_seller CHECK (seller_type = 'SYSTEM' AND seller_id = 'SKILL_STORE'),
    CONSTRAINT chk_skill_product_status CHECK (status IN ('DRAFT','PUBLISHED','RETIRED')),
    CONSTRAINT chk_skill_product_current CHECK (
        (status = 'DRAFT' AND current_product_version_id IS NULL)
        OR (status IN ('PUBLISHED','RETIRED') AND current_product_version_id IS NOT NULL)
    ),
    CONSTRAINT chk_skill_product_version CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Platform-owned V0 skill product catalog';

CREATE TABLE IF NOT EXISTS economy_skill_product_version (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    product_version_id          VARCHAR(100) NOT NULL,
    product_id                  VARCHAR(100) NOT NULL,
    version_sequence            BIGINT NOT NULL,
    skill_key                   VARCHAR(100) NOT NULL,
    skill_version               VARCHAR(64) NOT NULL,
    price_micro                 BIGINT NOT NULL,
    package_sha256              BINARY(32) NOT NULL,
    package_size                BIGINT NOT NULL,
    approved_permissions_manifest TEXT NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    deployment_restriction      VARCHAR(20) NOT NULL DEFAULT 'NONE',
    review_status               VARCHAR(16) NOT NULL DEFAULT 'APPROVED',
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_product_version_id (tenant_id, client_id, product_version_id),
    UNIQUE KEY uk_skill_product_version_sequence (tenant_id, client_id, product_id, version_sequence),
    UNIQUE KEY uk_skill_product_skill_version (tenant_id, client_id, skill_key, skill_version),
    UNIQUE KEY uk_skill_product_version_envelope (
        tenant_id, client_id, product_version_id, skill_key, skill_version, package_size, package_sha256
    ),
    KEY idx_skill_product_version_product (tenant_id, client_id, product_id, product_version_id),
    CONSTRAINT fk_skill_version_product FOREIGN KEY (tenant_id, client_id, product_id)
        REFERENCES economy_skill_product (tenant_id, client_id, product_id),
    CONSTRAINT chk_skill_product_version_sequence CHECK (version_sequence > 0),
    CONSTRAINT chk_skill_product_version_price CHECK (price_micro >= 0),
    CONSTRAINT chk_skill_product_version_package CHECK (
        package_size > 0 AND OCTET_LENGTH(package_sha256) = 32
        AND OCTET_LENGTH(approved_permissions_sha256) = 32
    ),
    CONSTRAINT chk_skill_product_version_restriction CHECK (
        deployment_restriction IN ('NONE','ADMIN_ONLY')
        AND (skill_key <> 'deploy-runner' OR deployment_restriction = 'ADMIN_ONLY')
    ),
    CONSTRAINT chk_skill_product_version_review CHECK (review_status = 'APPROVED')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable approved V0 skill package version';

CREATE TABLE IF NOT EXISTS economy_skill_purchase_quote (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    quote_id                    VARCHAR(100) NOT NULL,
    actor_type                  VARCHAR(16) NOT NULL,
    actor_id                    VARCHAR(100) NOT NULL,
    idempotency_key             VARBINARY(36) NOT NULL,
    request_hash                BINARY(32) NOT NULL,
    product_version_id          VARCHAR(100) NOT NULL,
    target_agent_id             VARCHAR(100) NOT NULL,
    expected_agent_version      BIGINT NOT NULL,
    expected_price_micro        BIGINT NOT NULL,
    approved_permissions_manifest TEXT NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    deployment_restriction      VARCHAR(20) NOT NULL,
    expires_at                  BIGINT NOT NULL,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_quote_id (tenant_id, client_id, quote_id),
    UNIQUE KEY uk_skill_quote_actor_key (tenant_id, client_id, actor_type, actor_id, idempotency_key),
    UNIQUE KEY uk_skill_quote_target_id (tenant_id, client_id, target_agent_id, quote_id),
    KEY idx_skill_quote_target (tenant_id, client_id, target_agent_id, expires_at, quote_id),
    KEY idx_skill_quote_version (tenant_id, client_id, product_version_id, quote_id),
    CONSTRAINT fk_skill_quote_version FOREIGN KEY (tenant_id, client_id, product_version_id)
        REFERENCES economy_skill_product_version (tenant_id, client_id, product_version_id),
    CONSTRAINT chk_skill_quote_actor CHECK (actor_type = 'USER'),
    CONSTRAINT chk_skill_quote_key CHECK (OCTET_LENGTH(idempotency_key) = 36),
    CONSTRAINT chk_skill_quote_hashes CHECK (
        OCTET_LENGTH(request_hash) = 32 AND OCTET_LENGTH(approved_permissions_sha256) = 32
    ),
    CONSTRAINT chk_skill_quote_amount_version CHECK (expected_price_micro >= 0 AND expected_agent_version >= 0),
    CONSTRAINT chk_skill_quote_restriction CHECK (deployment_restriction IN ('NONE','ADMIN_ONLY')),
    CONSTRAINT chk_skill_quote_expiry CHECK (expires_at > create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable actor-scoped V0 skill purchase quote';

CREATE TABLE IF NOT EXISTS economy_skill_order (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    order_id                    VARCHAR(100) NOT NULL,
    quote_id                    VARCHAR(100) NOT NULL,
    product_version_id          VARCHAR(100) NOT NULL,
    target_agent_id             VARCHAR(100) NOT NULL,
    buyer_type                  VARCHAR(16) NOT NULL,
    buyer_id                    VARCHAR(100) NOT NULL,
    seller_type                 VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    seller_id                   VARCHAR(100) NOT NULL DEFAULT 'SKILL_STORE',
    price_micro                 BIGINT NOT NULL,
    expected_agent_version      BIGINT NOT NULL,
    permission_grant_version    BIGINT NOT NULL DEFAULT 1,
    approved_permissions_manifest TEXT NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    escrow_id                   VARCHAR(100) DEFAULT NULL,
    reserve_transaction_id      VARCHAR(100) DEFAULT NULL,
    capture_transaction_id      VARCHAR(100) DEFAULT NULL,
    refund_transaction_id       VARCHAR(100) DEFAULT NULL,
    status                      VARCHAR(16) NOT NULL DEFAULT 'FUNDS_HELD',
    version                     BIGINT NOT NULL DEFAULT 1,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    held_at                     BIGINT NOT NULL,
    installing_at               BIGINT DEFAULT NULL,
    active_at                   BIGINT DEFAULT NULL,
    refunded_at                 BIGINT DEFAULT NULL,
    update_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_order_id (tenant_id, client_id, order_id),
    UNIQUE KEY uk_skill_order_quote (tenant_id, client_id, quote_id),
    UNIQUE KEY uk_skill_order_target_id (tenant_id, client_id, target_agent_id, order_id),
    UNIQUE KEY uk_skill_order_target_quote (tenant_id, client_id, target_agent_id, quote_id),
    UNIQUE KEY uk_skill_order_buyer_id (tenant_id, client_id, buyer_type, buyer_id, order_id),
    KEY idx_skill_order_buyer (tenant_id, client_id, buyer_type, buyer_id, held_at, order_id),
    KEY idx_skill_order_target (tenant_id, client_id, target_agent_id, status, order_id),
    KEY idx_skill_order_version (tenant_id, client_id, product_version_id, order_id),
    CONSTRAINT fk_skill_order_quote FOREIGN KEY (tenant_id, client_id, target_agent_id, quote_id)
        REFERENCES economy_skill_purchase_quote (tenant_id, client_id, target_agent_id, quote_id),
    CONSTRAINT fk_skill_order_version FOREIGN KEY (tenant_id, client_id, product_version_id)
        REFERENCES economy_skill_product_version (tenant_id, client_id, product_version_id),
    CONSTRAINT chk_skill_order_parties CHECK (
        buyer_type = 'USER' AND seller_type = 'SYSTEM' AND seller_id = 'SKILL_STORE'
    ),
    CONSTRAINT chk_skill_order_amount CHECK (price_micro >= 0 AND expected_agent_version >= 0),
    CONSTRAINT chk_skill_order_permission CHECK (
        permission_grant_version = 1 AND OCTET_LENGTH(approved_permissions_sha256) = 32
    ),
    CONSTRAINT chk_skill_order_funding CHECK (
        (price_micro = 0 AND escrow_id IS NULL AND reserve_transaction_id IS NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (price_micro > 0 AND escrow_id IS NOT NULL AND reserve_transaction_id IS NOT NULL)
    ),
    CONSTRAINT chk_skill_order_status CHECK (status IN ('FUNDS_HELD','INSTALLING','ACTIVE','REFUNDED')),
    CONSTRAINT chk_skill_order_state_time CHECK (
        (status = 'FUNDS_HELD' AND installing_at IS NULL AND active_at IS NULL AND refunded_at IS NULL)
        OR (status = 'INSTALLING' AND installing_at IS NOT NULL AND active_at IS NULL AND refunded_at IS NULL)
        OR (status = 'ACTIVE' AND installing_at IS NOT NULL AND active_at IS NOT NULL AND refunded_at IS NULL)
        OR (status = 'REFUNDED' AND active_at IS NULL AND refunded_at IS NOT NULL)
    ),
    CONSTRAINT chk_skill_order_paid_terminal CHECK (
        price_micro = 0
        OR (status IN ('FUNDS_HELD','INSTALLING') AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR (status = 'ACTIVE' AND capture_transaction_id IS NOT NULL AND refund_transaction_id IS NULL)
        OR (status = 'REFUNDED' AND capture_transaction_id IS NULL AND refund_transaction_id IS NOT NULL)
    ),
    CONSTRAINT chk_skill_order_version_number CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped V0 skill purchase and delivery state root';

CREATE TABLE IF NOT EXISTS economy_skill_order_receipt (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    order_id                    VARCHAR(100) NOT NULL,
    actor_type                  VARCHAR(16) NOT NULL,
    actor_id                    VARCHAR(100) NOT NULL,
    idempotency_key             VARBINARY(36) NOT NULL,
    request_hash                BINARY(32) NOT NULL,
    order_version               BIGINT NOT NULL,
    order_status                VARCHAR(16) NOT NULL,
    price_micro                 BIGINT NOT NULL,
    permission_grant_version    BIGINT NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    escrow_id                   VARCHAR(100) DEFAULT NULL,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_receipt_order (tenant_id, client_id, order_id),
    UNIQUE KEY uk_skill_receipt_actor_key (tenant_id, client_id, actor_type, actor_id, idempotency_key),
    KEY idx_skill_receipt_buyer_order (tenant_id, client_id, actor_type, actor_id, order_id),
    CONSTRAINT fk_skill_receipt_order FOREIGN KEY (tenant_id, client_id, actor_type, actor_id, order_id)
        REFERENCES economy_skill_order (tenant_id, client_id, buyer_type, buyer_id, order_id),
    CONSTRAINT chk_skill_receipt_actor CHECK (actor_type = 'USER'),
    CONSTRAINT chk_skill_receipt_key CHECK (OCTET_LENGTH(idempotency_key) = 36),
    CONSTRAINT chk_skill_receipt_hashes CHECK (
        OCTET_LENGTH(request_hash) = 32 AND OCTET_LENGTH(approved_permissions_sha256) = 32
    ),
    CONSTRAINT chk_skill_receipt_snapshot CHECK (
        order_version = 1 AND order_status = 'FUNDS_HELD'
        AND price_micro >= 0 AND permission_grant_version = 1
        AND ((price_micro = 0 AND escrow_id IS NULL) OR (price_micro > 0 AND escrow_id IS NOT NULL))
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable original actor-idempotent V0 purchase receipt';

CREATE TABLE IF NOT EXISTS economy_skill_installation (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    installation_id      VARCHAR(100) NOT NULL,
    order_id             VARCHAR(100) NOT NULL,
    product_version_id   VARCHAR(100) NOT NULL,
    target_agent_id      VARCHAR(100) NOT NULL,
    schema_version       INT NOT NULL DEFAULT 1,
    message_type         VARCHAR(32) NOT NULL DEFAULT 'command.dispatch',
    message_id           VARCHAR(100) NOT NULL,
    request_id           VARCHAR(100) NOT NULL,
    command_type         VARCHAR(32) NOT NULL DEFAULT 'SKILL_INSTALL',
    command_id           VARCHAR(100) NOT NULL,
    attempt              INT NOT NULL,
    fencing_token        BIGINT NOT NULL,
    delivery_epoch       BIGINT NOT NULL,
    skill_key            VARCHAR(100) NOT NULL,
    skill_version        VARCHAR(64) NOT NULL,
    package_size         BIGINT NOT NULL,
    package_sha256       BINARY(32) NOT NULL,
    download_path        VARCHAR(300) NOT NULL,
    status               VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    failure_code         VARCHAR(64) DEFAULT NULL,
    version              BIGINT NOT NULL DEFAULT 1,
    tenant_id            VARCHAR(50) NOT NULL,
    client_id            VARCHAR(50) NOT NULL,
    create_time          BIGINT NOT NULL,
    started_at           BIGINT DEFAULT NULL,
    installed_at         BIGINT DEFAULT NULL,
    failed_at            BIGINT DEFAULT NULL,
    update_time          BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_installation_id (tenant_id, client_id, installation_id),
    UNIQUE KEY uk_skill_installation_order (tenant_id, client_id, order_id),
    UNIQUE KEY uk_skill_installation_target_id (tenant_id, client_id, target_agent_id, installation_id),
    UNIQUE KEY uk_skill_installation_entitlement_ref (
        tenant_id, client_id, target_agent_id, installation_id, product_version_id, skill_key, skill_version
    ),
    UNIQUE KEY uk_skill_installation_command (
        tenant_id, client_id, target_agent_id, command_id, attempt, fencing_token, delivery_epoch
    ),
    KEY idx_skill_installation_target (tenant_id, client_id, target_agent_id, status, installation_id),
    KEY idx_skill_installation_target_order (tenant_id, client_id, target_agent_id, order_id),
    KEY idx_skill_installation_package_envelope (
        tenant_id, client_id, product_version_id, skill_key, skill_version, package_size, package_sha256
    ),
    KEY idx_skill_installation_version (tenant_id, client_id, product_version_id, installation_id),
    CONSTRAINT fk_skill_installation_order FOREIGN KEY (tenant_id, client_id, target_agent_id, order_id)
        REFERENCES economy_skill_order (tenant_id, client_id, target_agent_id, order_id),
    CONSTRAINT fk_skill_installation_version FOREIGN KEY (
        tenant_id, client_id, product_version_id, skill_key, skill_version, package_size, package_sha256
    ) REFERENCES economy_skill_product_version (
        tenant_id, client_id, product_version_id, skill_key, skill_version, package_size, package_sha256
    ),
    CONSTRAINT chk_skill_installation_envelope CHECK (
        schema_version = 1 AND message_type = 'command.dispatch' AND command_type = 'SKILL_INSTALL'
        AND request_id = message_id AND attempt > 0 AND fencing_token > 0 AND delivery_epoch > 0
    ),
    CONSTRAINT chk_skill_installation_package CHECK (package_size > 0 AND OCTET_LENGTH(package_sha256) = 32),
    CONSTRAINT chk_skill_installation_path CHECK (
        download_path = CONCAT('/internal/agent/skill-installations/', installation_id, '/package')
    ),
    CONSTRAINT chk_skill_installation_status CHECK (status IN ('REQUESTED','INSTALLING','SUCCEEDED','FAILED')),
    CONSTRAINT chk_skill_installation_state_time CHECK (
        (status = 'REQUESTED' AND started_at IS NULL AND installed_at IS NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'INSTALLING' AND started_at IS NOT NULL AND installed_at IS NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND started_at IS NOT NULL AND installed_at IS NOT NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND installed_at IS NULL AND failed_at IS NOT NULL AND failure_code IS NOT NULL)
    ),
    CONSTRAINT chk_skill_installation_version_number CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Persistent identity for frozen V0 SKILL_INSTALL command envelope';

CREATE TABLE IF NOT EXISTS economy_skill_entitlement (
    id                          BIGINT NOT NULL AUTO_INCREMENT,
    entitlement_id              VARCHAR(100) NOT NULL,
    order_id                    VARCHAR(100) NOT NULL,
    installation_id             VARCHAR(100) NOT NULL,
    product_version_id          VARCHAR(100) NOT NULL,
    target_agent_id             VARCHAR(100) NOT NULL,
    skill_key                   VARCHAR(100) NOT NULL,
    skill_version               VARCHAR(64) NOT NULL,
    permission_grant_version    BIGINT NOT NULL,
    approved_permissions_manifest TEXT NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    status                      VARCHAR(24) NOT NULL DEFAULT 'PENDING_INSTALLATION',
    version                     BIGINT NOT NULL DEFAULT 1,
    tenant_id                   VARCHAR(50) NOT NULL,
    client_id                   VARCHAR(50) NOT NULL,
    create_time                 BIGINT NOT NULL,
    activated_at                BIGINT DEFAULT NULL,
    failed_at                   BIGINT DEFAULT NULL,
    update_time                 BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_entitlement_id (tenant_id, client_id, entitlement_id),
    UNIQUE KEY uk_skill_entitlement_order (tenant_id, client_id, order_id),
    UNIQUE KEY uk_skill_entitlement_install (tenant_id, client_id, target_agent_id, installation_id),
    UNIQUE KEY uk_skill_entitlement_skill (tenant_id, client_id, target_agent_id, skill_key, skill_version),
    KEY idx_skill_entitlement_target (tenant_id, client_id, target_agent_id, status, entitlement_id),
    KEY idx_skill_entitlement_target_order (tenant_id, client_id, target_agent_id, order_id),
    KEY idx_skill_entitlement_install_ref (
        tenant_id, client_id, target_agent_id, installation_id, product_version_id, skill_key, skill_version
    ),
    KEY idx_skill_entitlement_installation (tenant_id, client_id, installation_id),
    KEY idx_skill_entitlement_version (tenant_id, client_id, product_version_id, entitlement_id),
    CONSTRAINT fk_skill_entitlement_order FOREIGN KEY (tenant_id, client_id, target_agent_id, order_id)
        REFERENCES economy_skill_order (tenant_id, client_id, target_agent_id, order_id),
    CONSTRAINT fk_skill_entitlement_install FOREIGN KEY (
        tenant_id, client_id, target_agent_id, installation_id, product_version_id, skill_key, skill_version
    ) REFERENCES economy_skill_installation (
        tenant_id, client_id, target_agent_id, installation_id, product_version_id, skill_key, skill_version
    ),
    CONSTRAINT fk_skill_entitlement_version FOREIGN KEY (tenant_id, client_id, product_version_id)
        REFERENCES economy_skill_product_version (tenant_id, client_id, product_version_id),
    CONSTRAINT chk_skill_entitlement_permission CHECK (
        permission_grant_version = 1 AND OCTET_LENGTH(approved_permissions_sha256) = 32
    ),
    CONSTRAINT chk_skill_entitlement_status CHECK (status IN ('PENDING_INSTALLATION','ACTIVE','FAILED')),
    CONSTRAINT chk_skill_entitlement_state_time CHECK (
        (status = 'PENDING_INSTALLATION' AND activated_at IS NULL AND failed_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'FAILED' AND activated_at IS NULL AND failed_at IS NOT NULL)
    ),
    CONSTRAINT chk_skill_entitlement_version_number CHECK (version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped V0 commercial entitlement distinct from runtime abilities';
