-- A02 durable Agent identity schema migration (MySQL 8+).
-- Run agent-identity-dry-run.sql first. This file performs DDL only: it never
-- inserts, updates, deletes, or rewrites historical identity/task rows.

CREATE TABLE IF NOT EXISTS agent_identity_registry (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId | immutable after insert | never reused even after delete',
    canonical_type          VARCHAR(32) NOT NULL COMMENT 'OPAQUE/LEGACY_CANONICAL/SYSTEM | immutable after insert',
    lifecycle_status        VARCHAR(20) NOT NULL DEFAULT 'PROVISIONED' COMMENT 'PROVISIONED/ACTIVE/SUSPENDED/RETIRED | RETIRED is terminal and cannot be reverted',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope client after insert | NULL only for system identity',
    owner_jiacn             VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope jiacn after insert | NULL only for system identity',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Must equal TRIM(owner_jiacn) | NULL only for system | immutable after insert',
    binding_id              BIGINT DEFAULT NULL COMMENT 'Audited source binding ID | immutable after insert | not an ownership substitute',
    provisioned_at          BIGINT DEFAULT NULL COMMENT 'Provisioned time',
    activated_at            BIGINT DEFAULT NULL COMMENT 'First activation time',
    suspended_at            BIGINT DEFAULT NULL COMMENT 'Latest suspension time',
    retired_at              BIGINT DEFAULT NULL COMMENT 'Retirement time | RETIRED is terminal and cannot be reverted',
    audit_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable creation/migration reason | immutable after insert',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_registry_agent (canonical_agent_id),
    UNIQUE KEY uk_identity_registry_binding (binding_id),
    UNIQUE KEY uk_identity_registry_alias_target
        (id, canonical_agent_id, client_id, owner_jiacn, tenant_id),
    KEY idx_identity_registry_scope_status (tenant_id, client_id, owner_jiacn, lifecycle_status),
    CONSTRAINT chk_identity_registry_type CHECK (
        canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL', 'SYSTEM')
    ),
    CONSTRAINT chk_identity_registry_lifecycle CHECK (
        lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
    ),
    CONSTRAINT chk_identity_registry_canonical CHECK (
        (canonical_type = 'OPAQUE'
            AND canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'LEGACY_CANONICAL'
            AND canonical_agent_id <> 'builtin-songjiang'
            AND canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'SYSTEM'
            AND canonical_agent_id = 'builtin-songjiang')
    ),
    CONSTRAINT chk_identity_registry_scope CHECK (
        (canonical_type = 'SYSTEM'
            AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id IS NULL)
        OR (canonical_type <> 'SYSTEM'
            AND client_id IS NOT NULL AND TRIM(client_id) <> ''
            AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
            AND tenant_id = TRIM(owner_jiacn))
    ),
    CONSTRAINT chk_identity_registry_retired CHECK (
        (lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable canonical Agent identity registry | collation binary enforces exact case matching';

CREATE TABLE IF NOT EXISTS agent_identity_alias (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    registry_id             BIGINT NOT NULL COMMENT 'Target identity registry ID | immutable after insert',
    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'Resolved canonical agentId | immutable after insert',
    alias_type              VARCHAR(32) NOT NULL DEFAULT 'LEGACY_AGENT_ID' COMMENT 'v1 online alias type | immutable after insert',
    alias_value             VARCHAR(100) NOT NULL COMMENT 'Legacy agent ID resolved only with full owner scope | immutable after insert',
    alias_status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED | once REVOKED cannot become ACTIVE again',
    valid_from              BIGINT NOT NULL COMMENT 'Alias activation time',
    valid_to                BIGINT DEFAULT NULL COMMENT 'Alias revocation time, not used directly for uniqueness',
    active_key              TINYINT GENERATED ALWAYS AS (
                                CASE
                                    WHEN alias_status = 'ACTIVE' AND valid_to IS NULL THEN 1
                                    ELSE NULL
                                END
                            ) STORED,
    client_id               VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope client after insert',
    owner_jiacn             VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope jiacn after insert',
    tenant_id               VARCHAR(50) NOT NULL COMMENT 'Must equal TRIM(owner_jiacn) | immutable after insert',
    audit_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable alias evidence/reason',
    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_identity_alias_active
        (client_id, owner_jiacn, alias_type, alias_value, active_key),
    KEY idx_identity_alias_registry (registry_id, alias_status),
    KEY idx_identity_alias_canonical (canonical_agent_id, alias_status),
    CONSTRAINT chk_identity_alias_type CHECK (alias_type = 'LEGACY_AGENT_ID'),
    CONSTRAINT chk_identity_alias_status CHECK (alias_status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_identity_alias_scope CHECK (tenant_id = TRIM(owner_jiacn)),
    CONSTRAINT chk_identity_alias_no_blank_scope CHECK (
        TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''
    ),
    CONSTRAINT chk_identity_alias_window CHECK (
        (alias_status = 'ACTIVE' AND valid_to IS NULL)
        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)
    ),
    CONSTRAINT chk_identity_alias_not_system CHECK (
        alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'
    ),
    CONSTRAINT fk_identity_alias_registry_scope FOREIGN KEY
        (registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
        REFERENCES agent_identity_registry
        (id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped legacy Agent ID compatibility aliases | collation binary enforces exact case matching';

-- ---------------------------------------------------------------------------
-- Immutability and lifecycle enforcement triggers (A02 hardening)
-- These triggers prevent mutation of immutable identity columns, block
-- resurrection from RETIRED, and forbid physical deletion of registry rows.

DELIMITER $$

CREATE TRIGGER IF NOT EXISTS trg_identity_registry_immutable_update
BEFORE UPDATE ON agent_identity_registry
FOR EACH ROW
BEGIN
    IF NEW.canonical_agent_id <> OLD.canonical_agent_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: canonical_agent_id is immutable after insert';
    END IF;
    IF NEW.canonical_type <> OLD.canonical_type THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: canonical_type is immutable after insert';
    END IF;
    IF (NEW.client_id <=> OLD.client_id) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: client_id is immutable after insert';
    END IF;
    IF (NEW.owner_jiacn <=> OLD.owner_jiacn) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: owner_jiacn is immutable after insert';
    END IF;
    IF (NEW.tenant_id <=> OLD.tenant_id) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: tenant_id is immutable after insert';
    END IF;
    IF (NEW.binding_id <=> OLD.binding_id) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: binding_id is immutable after insert';
    END IF;
    IF OLD.lifecycle_status = 'RETIRED' AND NEW.lifecycle_status <> 'RETIRED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: RETIRED identity cannot be resurrected to another lifecycle_status';
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS trg_identity_registry_no_delete
BEFORE DELETE ON agent_identity_registry
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'A02: physical deletion of agent_identity_registry is forbidden | use RETIRED lifecycle_status or REVOKED alias instead';
END$$

CREATE TRIGGER IF NOT EXISTS trg_identity_alias_immutable_update
BEFORE UPDATE ON agent_identity_alias
FOR EACH ROW
BEGIN
    IF NEW.registry_id <> OLD.registry_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias registry_id is immutable after insert';
    END IF;
    IF NEW.canonical_agent_id <> OLD.canonical_agent_id THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias canonical_agent_id is immutable after insert';
    END IF;
    IF NEW.alias_type <> OLD.alias_type THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias_type is immutable after insert';
    END IF;
    IF NEW.alias_value <> OLD.alias_value THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias_value is immutable after insert';
    END IF;
    IF (NEW.client_id <=> OLD.client_id) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias client_id is immutable after insert';
    END IF;
    IF (NEW.owner_jiacn <=> OLD.owner_jiacn) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias owner_jiacn is immutable after insert';
    END IF;
    IF (NEW.tenant_id <=> OLD.tenant_id) = 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: alias tenant_id is immutable after insert';
    END IF;
    IF OLD.alias_status = 'REVOKED' AND NEW.alias_status = 'ACTIVE' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'A02: REVOKED alias cannot be reactivated to ACTIVE';
    END IF;
END$$

CREATE TRIGGER IF NOT EXISTS trg_identity_alias_no_delete
BEFORE DELETE ON agent_identity_alias
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'A02: physical deletion of agent_identity_alias is forbidden | use alias_status = REVOKED instead';
END$$

DELIMITER ;


-- Existing binding compatibility: jiacn remains the writable legacy field until
-- B02 changes the Entity/DAO. owner_jiacn and lifecycle_status are deterministic,
-- indexed projections, so current 0/1 writes map to SUSPENDED/ACTIVE without
-- deleting history. Values 2/3 are reserved for PROVISIONED/RETIRED.
SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND column_name = 'owner_jiacn') = 0,
    'ALTER TABLE agent_persona_binding ADD COLUMN owner_jiacn VARCHAR(50) GENERATED ALWAYS AS (jiacn) STORED AFTER jiacn',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND column_name = 'lifecycle_status') = 0,
    'ALTER TABLE agent_persona_binding ADD COLUMN lifecycle_status VARCHAR(20) GENERATED ALWAYS AS (CASE status WHEN 2 THEN ''PROVISIONED'' WHEN 1 THEN ''ACTIVE'' WHEN 0 THEN ''SUSPENDED'' WHEN 3 THEN ''RETIRED'' ELSE NULL END) STORED AFTER status',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

-- Replace the legacy client-wide persona unique key with the ADR-001 owner scope.
-- Build the new index under a temporary name first. If historical duplicates
-- exist, creation fails while the old protective index is still intact.
SET @a02_index_columns = (
    SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
      AND index_name = 'uk_agent_binding_active_persona'
);
SET @a02_sql = IF(
    COALESCE(@a02_index_columns, '') <> 'client_id,owner_jiacn,active_persona_code'
      AND (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
             AND index_name = 'uk_agent_binding_active_persona_a02') = 0,
    'CREATE UNIQUE INDEX uk_agent_binding_active_persona_a02 ON agent_persona_binding (client_id, owner_jiacn, active_persona_code)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    @a02_index_columns IS NOT NULL
        AND @a02_index_columns <> 'client_id,owner_jiacn,active_persona_code',
    'ALTER TABLE agent_persona_binding DROP INDEX uk_agent_binding_active_persona',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND index_name = 'uk_agent_binding_active_persona') = 0
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND index_name = 'uk_agent_binding_active_persona_a02') > 0,
    'ALTER TABLE agent_persona_binding RENAME INDEX uk_agent_binding_active_persona_a02 TO uk_agent_binding_active_persona',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

-- A canonical Agent may be active in only one owner scope. Historical inactive
-- binding rows remain retained; durable non-reuse is enforced by registry uniqueness.
SET @a02_index_columns = (
    SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
      AND index_name = 'uk_agent_binding_active_agent'
);
SET @a02_sql = IF(
    COALESCE(@a02_index_columns, '') <> 'active_agent_id'
      AND (SELECT COUNT(*) FROM information_schema.statistics
           WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
             AND index_name = 'uk_agent_binding_active_agent_a02') = 0,
    'CREATE UNIQUE INDEX uk_agent_binding_active_agent_a02 ON agent_persona_binding (active_agent_id)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    @a02_index_columns IS NOT NULL AND @a02_index_columns <> 'active_agent_id',
    'ALTER TABLE agent_persona_binding DROP INDEX uk_agent_binding_active_agent',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND index_name = 'uk_agent_binding_active_agent') = 0
    AND (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND index_name = 'uk_agent_binding_active_agent_a02') > 0,
    'ALTER TABLE agent_persona_binding RENAME INDEX uk_agent_binding_active_agent_a02 TO uk_agent_binding_active_agent',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND constraint_name = 'chk_agent_binding_status') = 0,
    'ALTER TABLE agent_persona_binding ADD CONSTRAINT chk_agent_binding_status CHECK (status IN (0, 1, 2, 3))',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE constraint_schema = DATABASE() AND table_name = 'agent_persona_binding'
        AND constraint_name = 'chk_agent_binding_tenant_owner') = 0,
    'ALTER TABLE agent_persona_binding ADD CONSTRAINT chk_agent_binding_tenant_owner CHECK (tenant_id IS NULL OR tenant_id = owner_jiacn)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;
