-- Test-owned, in-memory schema for executing the v1.7 SELECT-only MyBatis mapper.
-- Scope columns deliberately use case-insensitive comparison so BINARY/OCTET_LENGTH ACL fences
-- are exercised instead of being hidden by H2's default case-sensitive VARCHAR behavior.
CREATE TABLE economy_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id VARCHAR_IGNORECASE(100) NOT NULL,
    owner_type VARCHAR(20) NOT NULL,
    owner_id VARCHAR_IGNORECASE(100) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    balance_micro BIGINT NOT NULL,
    allow_negative INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL
);

CREATE TABLE economy_transaction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    transaction_id VARCHAR_IGNORECASE(100) NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_id VARCHAR_IGNORECASE(100) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id VARCHAR(100) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    posted_at BIGINT,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL
);

CREATE TABLE economy_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entry_id VARCHAR(140) NOT NULL,
    transaction_id VARCHAR_IGNORECASE(100) NOT NULL,
    account_id VARCHAR_IGNORECASE(100) NOT NULL,
    entry_sequence INT NOT NULL,
    signed_amount_micro BIGINT NOT NULL,
    balance_after_micro BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    posted_at BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL
);

CREATE TABLE economy_escrow (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    escrow_id VARCHAR(100) NOT NULL,
    business_type VARCHAR(32) NOT NULL,
    business_id VARCHAR(100) NOT NULL,
    payer_account_id VARCHAR_IGNORECASE(100) NOT NULL,
    escrow_account_id VARCHAR(100) NOT NULL,
    currency VARCHAR(16) NOT NULL,
    gross_micro BIGINT NOT NULL,
    captured_micro BIGINT NOT NULL,
    refunded_micro BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL
);

CREATE TABLE economy_skill_product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id VARCHAR_IGNORECASE(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_product_version_id VARCHAR_IGNORECASE(100),
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL
);

CREATE TABLE economy_skill_product_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_version_id VARCHAR_IGNORECASE(100) NOT NULL,
    product_id VARCHAR_IGNORECASE(100) NOT NULL,
    skill_key VARCHAR(100) NOT NULL,
    skill_version VARCHAR(64) NOT NULL,
    price_micro BIGINT NOT NULL,
    approved_permissions_manifest CLOB NOT NULL,
    deployment_restriction VARCHAR(20) NOT NULL,
    review_status VARCHAR(16) NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL
);

CREATE TABLE economy_skill_installation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    installation_id VARCHAR_IGNORECASE(100) NOT NULL,
    order_id VARCHAR_IGNORECASE(100) NOT NULL,
    product_version_id VARCHAR_IGNORECASE(100) NOT NULL,
    target_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    schema_version INT NOT NULL,
    message_type VARCHAR(32) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    request_id VARCHAR(100) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    attempt INT NOT NULL,
    fencing_token BIGINT NOT NULL,
    delivery_epoch BIGINT NOT NULL,
    skill_key VARCHAR(100) NOT NULL,
    skill_version VARCHAR(64) NOT NULL,
    package_size BIGINT NOT NULL,
    package_sha256 BINARY(32) NOT NULL,
    download_path VARCHAR(300) NOT NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64),
    version BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    started_at BIGINT,
    installed_at BIGINT,
    failed_at BIGINT,
    update_time BIGINT NOT NULL
);

CREATE TABLE economy_skill_entitlement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entitlement_id VARCHAR(100) NOT NULL,
    order_id VARCHAR_IGNORECASE(100) NOT NULL,
    installation_id VARCHAR_IGNORECASE(100) NOT NULL,
    product_version_id VARCHAR_IGNORECASE(100) NOT NULL,
    target_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    skill_key VARCHAR(100) NOT NULL,
    skill_version VARCHAR(64) NOT NULL,
    permission_grant_version BIGINT NOT NULL,
    approved_permissions_manifest CLOB NOT NULL,
    approved_permissions_sha256 BINARY(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    version BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    activated_at BIGINT,
    failed_at BIGINT,
    update_time BIGINT NOT NULL
);

CREATE TABLE agent_persona_binding (
    id BIGINT PRIMARY KEY,
    owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
    persona_code VARCHAR(50) NOT NULL,
    agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    status INT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL
);

CREATE TABLE agent_identity_registry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    lifecycle_status VARCHAR(20) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    binding_id BIGINT NOT NULL
);

CREATE TABLE agent_hosted_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    binding_id BIGINT NOT NULL,
    owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,
    canonical_agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    persona_code VARCHAR(50) NOT NULL,
    profile_key VARCHAR(160) NOT NULL,
    api_key_id VARCHAR(100) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    resume_state VARCHAR(32),
    generation BIGINT NOT NULL,
    desired_enabled INT NOT NULL,
    last_error VARCHAR(1000),
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT,
    update_time BIGINT
);

CREATE TABLE economy_hosting_rent_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id VARCHAR(100) NOT NULL,
    plan_version BIGINT NOT NULL,
    amount_micro BIGINT NOT NULL,
    period_seconds BIGINT NOT NULL,
    quote_ttl_seconds BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL
);

CREATE TABLE economy_hosting_lease (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id VARCHAR(100) NOT NULL,
    principal_type VARCHAR(20) NOT NULL,
    principal_id VARCHAR_IGNORECASE(100) NOT NULL,
    persona_code VARCHAR(100) NOT NULL,
    agent_id VARCHAR_IGNORECASE(100) NOT NULL,
    binding_id VARCHAR(100),
    plan_id VARCHAR(100) NOT NULL,
    plan_version BIGINT NOT NULL,
    amount_micro BIGINT NOT NULL,
    period_seconds BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    paid_from BIGINT,
    paid_through BIGINT,
    latest_intent_id VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL,
    tenant_id VARCHAR_IGNORECASE(50) NOT NULL,
    client_id VARCHAR_IGNORECASE(50) NOT NULL,
    create_time BIGINT NOT NULL,
    update_time BIGINT NOT NULL
);

CREATE TABLE agent_outbox_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    marker VARCHAR(100) NOT NULL
);
