-- Manual MySQL 8 candidate. Review live data/schema and deploy the authority
-- tables first. NEVER execute on application startup; never seed enabled rows.
-- Control-plane writes require a separate credential from business instances.
CREATE TABLE core_tenant_module_control (
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  module_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode_revision BIGINT NOT NULL DEFAULT 0,
  migration_epoch BIGINT NOT NULL DEFAULT 0,
  drain_sealed BOOLEAN NOT NULL DEFAULT FALSE,
  schema_version BIGINT NOT NULL DEFAULT 0,
  mapping_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  drain_cutoff TIMESTAMP(6) NULL,
  drain_deadline TIMESTAMP(6) NULL,
  PRIMARY KEY (tenant_id, module_id),
  CONSTRAINT fk_module_control_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_module_id CHECK (module_id REGEXP '^[a-z][a-z0-9_-]{0,63}$'),
  CONSTRAINT ck_module_mode CHECK (mode_state IN ('LEGACY_CLIENT', 'QUIESCING', 'DOMAIN')),
  CONSTRAINT ck_module_revision CHECK (mode_revision >= 0 AND migration_epoch >= 0),
  CONSTRAINT ck_module_schema CHECK (schema_version >= 0),
  CONSTRAINT ck_module_digest CHECK (mapping_digest IS NULL OR mapping_digest REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Credential is a control-plane generated 256-bit random lowercase hex secret;
-- persist only SHA-256 of ASCII('cyf-deployment-v1') || 0x00 || UTF8(deployment_id)
-- || 0x00 || ASCII(worker_generation) || 0x00 || ASCII(secret). Rotate the
-- secret on every worker generation and revoke the old deployment credential.
CREATE TABLE core_deployment_registration (
  deployment_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stage VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  artifact_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  credential_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DISABLED',
  worker_generation BIGINT NOT NULL DEFAULT 0,
  registration_revision BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (deployment_id),
  CONSTRAINT ck_deployment_stage CHECK (stage IN ('SHARED', 'LOCAL_TEST')),
  CONSTRAINT ck_deployment_status CHECK (status IN ('DISABLED', 'ACTIVE', 'DRAINING', 'REVOKED')),
  CONSTRAINT ck_deployment_digest CHECK (artifact_digest REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_deployment_credential CHECK (credential_digest REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT ck_deployment_revisions CHECK (worker_generation >= 0 AND registration_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Exactly one pool per deployment/tenant/domain grant. No wildcard tenant or pool.
CREATE TABLE core_deployment_tenant_grant (
  deployment_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  execution_pool_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVOKED',
  grant_revision BIGINT NOT NULL DEFAULT 0,
  allow_read BOOLEAN NOT NULL DEFAULT FALSE,
  allow_write BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (deployment_id, tenant_id, data_domain_id),
  KEY idx_deployment_grant_tenant (tenant_id, data_domain_id),
  CONSTRAINT fk_deployment_grant_registration FOREIGN KEY (deployment_id)
    REFERENCES core_deployment_registration (deployment_id),
  CONSTRAINT fk_deployment_grant_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_deployment_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_deployment_grant_rev CHECK (grant_revision >= 0),
  CONSTRAINT ck_deployment_grant_flags CHECK (allow_read IN (0, 1) AND allow_write IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Control-plane sets these rows only after verifying the registered artifact
-- supports the particular module mode, schema and protocol generation.
CREATE TABLE core_deployment_module_admission (
  deployment_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  module_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode_revision BIGINT NOT NULL,
  migration_epoch BIGINT NOT NULL,
  admission_epoch BIGINT NOT NULL,
  schema_version BIGINT NOT NULL,
  registration_revision BIGINT NOT NULL,
  grant_revision BIGINT NOT NULL,
  allow_read BOOLEAN NOT NULL DEFAULT FALSE,
  allow_write BOOLEAN NOT NULL DEFAULT FALSE,
  status VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVOKED',
  PRIMARY KEY (deployment_id, tenant_id, data_domain_id, module_id),
  CONSTRAINT fk_admission_grant FOREIGN KEY (deployment_id, tenant_id, data_domain_id)
    REFERENCES core_deployment_tenant_grant (deployment_id, tenant_id, data_domain_id),
  CONSTRAINT fk_admission_module FOREIGN KEY (tenant_id, module_id)
    REFERENCES core_tenant_module_control (tenant_id, module_id),
  CONSTRAINT ck_admission_mode CHECK (mode_state IN ('LEGACY_CLIENT', 'DOMAIN')),
  CONSTRAINT ck_admission_status CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_admission_revisions CHECK (mode_revision >= 0 AND migration_epoch >= 0
    AND admission_epoch >= 0 AND schema_version >= 0 AND registration_revision >= 0
    AND grant_revision >= 0),
  CONSTRAINT ck_admission_flags CHECK (allow_read IN (0, 1) AND allow_write IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
