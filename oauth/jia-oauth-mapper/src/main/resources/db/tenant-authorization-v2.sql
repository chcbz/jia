-- Manual MySQL 8 candidate, NEVER a startup initializer. Run only after live schema/data preflight.
-- No tenant rows, memberships, client grants or enabled control rows are seeded here.
CREATE TABLE core_tenant (
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  tenant_kind VARCHAR(6) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  policy_revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id), UNIQUE KEY uq_core_tenant_code (tenant_code),
  CONSTRAINT ck_core_tenant_id CHECK (tenant_id REGEXP '^(0|[1-9][0-9]{0,49})$'),
  CONSTRAINT ck_core_tenant_kind CHECK (tenant_kind IN ('NORMAL', 'TEST')),
  CONSTRAINT ck_core_tenant_revision CHECK (policy_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A missing control row is DENIED; the only initial state is DISABLED.
CREATE TABLE core_tenant_control (
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DISABLED',
  control_revision BIGINT NOT NULL DEFAULT 0,
  tenant_session_epoch BIGINT NOT NULL DEFAULT 0,
  admission_epoch BIGINT NOT NULL DEFAULT 0,
  drain_epoch BIGINT NOT NULL DEFAULT 0,
  drain_started_at TIMESTAMP(6) NULL,
  drain_deadline TIMESTAMP(6) NULL,
  PRIMARY KEY (tenant_id),
  CONSTRAINT fk_tenant_control_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_tenant_control_state CHECK (state IN ('DISABLED', 'ENABLED', 'AUTH_CLOSED', 'WRITE_CLOSED_CLAIM_CLOSED', 'DRAINING')),
  CONSTRAINT ck_tenant_control_rev CHECK (control_revision >= 0),
  CONSTRAINT ck_tenant_control_session CHECK (tenant_session_epoch >= 0),
  CONSTRAINT ck_tenant_control_admission CHECK (admission_epoch >= 0),
  CONSTRAINT ck_tenant_control_drain CHECK (drain_epoch >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep revoked rows as tombstones: membership_revision must never reset on re-grant.
CREATE TABLE core_tenant_membership (
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  uid BIGINT NOT NULL,
  status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVOKED',
  membership_revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, uid),
  KEY idx_membership_uid (uid),
  CONSTRAINT fk_membership_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_membership_uid CHECK (uid > 0),
  CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_membership_revision CHECK (membership_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- A client may have multiple rows; oauth_client.tenant_id is NOT an authority for these grants.
CREATE TABLE oauth_client_tenant_grant (
  client_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVOKED',
  grant_revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (client_id, tenant_id),
  KEY idx_client_grant_tenant (tenant_id),
  CONSTRAINT fk_client_grant_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_client_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_client_grant_revision CHECK (grant_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Any domain-grant mutation must also advance its parent grant_revision in the same transaction.
CREATE TABLE oauth_client_tenant_domain_grant (
  client_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  data_domain_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  status VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'REVOKED',
  domain_revision BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (client_id, tenant_id, data_domain_id),
  KEY idx_domain_grant_tenant (tenant_id, data_domain_id),
  CONSTRAINT fk_domain_grant_parent FOREIGN KEY (client_id, tenant_id)
    REFERENCES oauth_client_tenant_grant (client_id, tenant_id),
  CONSTRAINT ck_domain_id CHECK (data_domain_id REGEXP '^[a-z][a-z0-9-]{0,63}$'),
  CONSTRAINT ck_domain_grant_status CHECK (status IN ('ACTIVE', 'REVOKED')),
  CONSTRAINT ck_domain_grant_revision CHECK (domain_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotency/audit record for tenant control transitions. Every successful
-- transition leaves one row keyed by (tenant, operator, idempotency_key); a
-- replay with the same key and request_hash returns the original result
-- snapshot without re-applying the transition.
CREATE TABLE core_tenant_operation (
  tenant_id VARCHAR(50) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operator_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  expected_revision BIGINT NOT NULL,
  from_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  to_state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_revision BIGINT NOT NULL,
  result_snapshot VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (tenant_id, operator_id, idempotency_key),
  CONSTRAINT fk_operation_tenant FOREIGN KEY (tenant_id) REFERENCES core_tenant (tenant_id),
  CONSTRAINT ck_operation_revision CHECK (expected_revision >= 0 AND result_revision >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
