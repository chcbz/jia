-- A02 durable Agent identity schema migration and b0 upgrade (MySQL 8.0.21+).
-- This script changes schema only and is intentionally fail-closed: incompatible
-- historical rows or structural drift abort the migration instead of being rewritten
-- or guessed. A b0 catalog lacks dry-run runtime projection columns, so apply this DDL
-- in an isolated/approved maintenance step before running the read-only dry-run; do not
-- write registry/alias mappings until the report has been reviewed.

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

-- A02 uses a temporary assertion procedure so structural drift fails with a clear
-- SQLSTATE instead of being silently accepted by CREATE TABLE IF NOT EXISTS.
DROP PROCEDURE IF EXISTS a02_assert;
DELIMITER $$
CREATE PROCEDURE a02_assert(IN condition_ok BOOLEAN, IN failure_message VARCHAR(255))
BEGIN
    IF condition_ok IS NULL OR condition_ok = FALSE THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = failure_message;
    END IF;
END$$
DELIMITER ;

-- Dry-run requires these runtime projection columns. Keep schema.sql, migration,
-- Initializer and the report in lock-step.
SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND column_name = 'owner_jiacn') = 0,
    'ALTER TABLE agent_runtime ADD COLUMN owner_jiacn VARCHAR(50) DEFAULT NULL COMMENT ''Bound user Jia account''',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND column_name = 'persona_code') = 0,
    'ALTER TABLE agent_runtime ADD COLUMN persona_code VARCHAR(50) DEFAULT NULL COMMENT ''Bound persona code''',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND column_name = 'binding_id') = 0,
    'ALTER TABLE agent_runtime ADD COLUMN binding_id BIGINT DEFAULT NULL COMMENT ''Persona binding ID''',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND index_name = 'idx_agent_runtime_owner') = 0,
    'CREATE INDEX idx_agent_runtime_owner ON agent_runtime (client_id, owner_jiacn)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

SET @a02_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND index_name = 'idx_agent_runtime_persona_code') = 0,
    'CREATE INDEX idx_agent_runtime_persona_code ON agent_runtime (persona_code)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

CALL a02_assert(
    (SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND index_name = 'idx_agent_runtime_owner') = 'client_id,owner_jiacn',
    'A02: incompatible agent_runtime.idx_agent_runtime_owner');
CALL a02_assert(
    (SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
       FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 'agent_runtime'
        AND index_name = 'idx_agent_runtime_persona_code') = 'persona_code',
    'A02: incompatible agent_runtime.idx_agent_runtime_persona_code');

-- Existing binding compatibility. Add missing projections, then MODIFY them so a
-- partially-created table cannot retain a wrong generated expression.
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

ALTER TABLE agent_persona_binding
    MODIFY COLUMN owner_jiacn VARCHAR(50) GENERATED ALWAYS AS (jiacn) STORED,
    MODIFY COLUMN lifecycle_status VARCHAR(20) GENERATED ALWAYS AS (
        CASE status WHEN 2 THEN 'PROVISIONED' WHEN 1 THEN 'ACTIVE'
                    WHEN 0 THEN 'SUSPENDED' WHEN 3 THEN 'RETIRED' ELSE NULL END
    ) STORED;

-- Replace binding unique indexes using a temporary target index first. Historical
-- duplicates fail before the old protection is removed.
SET @a02_index_columns = (
    SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
       AND index_name = 'uk_agent_binding_active_persona');
SET @a02_sql = IF(
    COALESCE(@a02_index_columns, '') <> 'client_id,owner_jiacn,active_persona_code'
      AND (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
              AND index_name = 'uk_agent_binding_active_persona_a02') = 0,
    'CREATE UNIQUE INDEX uk_agent_binding_active_persona_a02 ON agent_persona_binding (client_id, owner_jiacn, active_persona_code)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;
SET @a02_sql = IF(@a02_index_columns IS NOT NULL
                    AND @a02_index_columns <> 'client_id,owner_jiacn,active_persona_code',
    'ALTER TABLE agent_persona_binding DROP INDEX uk_agent_binding_active_persona', 'DO 1');
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

SET @a02_index_columns = (
    SELECT GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ',')
      FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
       AND index_name = 'uk_agent_binding_active_agent');
SET @a02_sql = IF(
    COALESCE(@a02_index_columns, '') <> 'active_agent_id'
      AND (SELECT COUNT(*) FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name = 'agent_persona_binding'
              AND index_name = 'uk_agent_binding_active_agent_a02') = 0,
    'CREATE UNIQUE INDEX uk_agent_binding_active_agent_a02 ON agent_persona_binding (active_agent_id)',
    'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;
SET @a02_sql = IF(@a02_index_columns IS NOT NULL AND @a02_index_columns <> 'active_agent_id',
    'ALTER TABLE agent_persona_binding DROP INDEX uk_agent_binding_active_agent', 'DO 1');
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

-- Replace binding CHECK constraints to avoid accepting same-name but weaker rules.
SET @a02_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'agent_persona_binding'
      AND constraint_name = 'chk_agent_binding_status' AND constraint_type = 'CHECK') > 0,
    'ALTER TABLE agent_persona_binding DROP CHECK chk_agent_binding_status', 'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;
SET @a02_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'agent_persona_binding'
      AND constraint_name = 'chk_agent_binding_tenant_owner' AND constraint_type = 'CHECK') > 0,
    'ALTER TABLE agent_persona_binding DROP CHECK chk_agent_binding_tenant_owner', 'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;
ALTER TABLE agent_persona_binding
    ADD CONSTRAINT chk_agent_binding_status CHECK (status IN (0, 1, 2, 3)),
    ADD CONSTRAINT chk_agent_binding_tenant_owner CHECK (tenant_id IS NULL OR tenant_id = owner_jiacn);

-- Real b0 -> current upgrade. Drop dependent FK/checks, normalize the complete
-- column definitions and binary collation, then recreate exact constraints.
SET @a02_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE constraint_schema = DATABASE() AND table_name = 'agent_identity_alias'
      AND constraint_name = 'fk_identity_alias_registry_scope'
      AND constraint_type = 'FOREIGN KEY') > 0,
    'ALTER TABLE agent_identity_alias DROP FOREIGN KEY fk_identity_alias_registry_scope', 'DO 1');
PREPARE a02_stmt FROM @a02_sql; EXECUTE a02_stmt; DEALLOCATE PREPARE a02_stmt;

-- Drop all named A02 checks before replacing them with exact definitions.
SET @a02_checks = 'agent_identity_registry:chk_identity_registry_type,agent_identity_registry:chk_identity_registry_lifecycle,agent_identity_registry:chk_identity_registry_canonical,agent_identity_registry:chk_identity_registry_scope,agent_identity_registry:chk_identity_registry_retired,agent_identity_alias:chk_identity_alias_type,agent_identity_alias:chk_identity_alias_status,agent_identity_alias:chk_identity_alias_scope,agent_identity_alias:chk_identity_alias_no_blank_scope,agent_identity_alias:chk_identity_alias_window,agent_identity_alias:chk_identity_alias_not_system';
DROP PROCEDURE IF EXISTS a02_drop_checks;
DELIMITER $$
CREATE PROCEDURE a02_drop_checks()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE table_name_value VARCHAR(64);
    DECLARE constraint_name_value VARCHAR(64);
    DECLARE checks_cursor CURSOR FOR
        SELECT tc.table_name, tc.constraint_name
          FROM information_schema.table_constraints tc
         WHERE tc.constraint_schema = DATABASE()
           AND tc.constraint_type = 'CHECK'
           AND ((tc.table_name = 'agent_identity_registry' AND tc.constraint_name IN (
                'chk_identity_registry_type','chk_identity_registry_lifecycle',
                'chk_identity_registry_canonical','chk_identity_registry_scope',
                'chk_identity_registry_retired'))
             OR (tc.table_name = 'agent_identity_alias' AND tc.constraint_name IN (
                'chk_identity_alias_type','chk_identity_alias_status','chk_identity_alias_scope',
                'chk_identity_alias_no_blank_scope','chk_identity_alias_window',
                'chk_identity_alias_not_system')));
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;
    OPEN checks_cursor;
    checks_loop: LOOP
        FETCH checks_cursor INTO table_name_value, constraint_name_value;
        IF done = 1 THEN LEAVE checks_loop; END IF;
        SET @drop_check_sql = CONCAT('ALTER TABLE `', table_name_value,
                                     '` DROP CHECK `', constraint_name_value, '`');
        PREPARE a02_drop_stmt FROM @drop_check_sql;
        EXECUTE a02_drop_stmt;
        DEALLOCATE PREPARE a02_drop_stmt;
    END LOOP;
    CLOSE checks_cursor;
END$$
DELIMITER ;
CALL a02_drop_checks();
DROP PROCEDURE a02_drop_checks;

ALTER TABLE agent_identity_registry
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
ALTER TABLE agent_identity_registry
    MODIFY COLUMN canonical_agent_id VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId | immutable after insert | never reused even after delete',
    MODIFY COLUMN canonical_type VARCHAR(32) NOT NULL COMMENT 'OPAQUE/LEGACY_CANONICAL/SYSTEM | immutable after insert',
    MODIFY COLUMN lifecycle_status VARCHAR(20) NOT NULL DEFAULT 'PROVISIONED' COMMENT 'PROVISIONED/ACTIVE/SUSPENDED/RETIRED | RETIRED is terminal and cannot be reverted',
    MODIFY COLUMN client_id VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope client after insert | NULL only for system identity',
    MODIFY COLUMN owner_jiacn VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope jiacn after insert | NULL only for system identity',
    MODIFY COLUMN tenant_id VARCHAR(50) DEFAULT NULL COMMENT 'Must equal TRIM(owner_jiacn) | NULL only for system | immutable after insert',
    MODIFY COLUMN binding_id BIGINT DEFAULT NULL COMMENT 'Audited source binding ID | immutable after insert | not an ownership substitute',
    MODIFY COLUMN audit_reason VARCHAR(1000) NOT NULL COMMENT 'Auditable creation/migration reason | immutable after insert';
ALTER TABLE agent_identity_registry COMMENT = 'Durable canonical Agent identity registry | collation binary enforces exact case matching';

ALTER TABLE agent_identity_alias
    CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
ALTER TABLE agent_identity_alias
    MODIFY COLUMN registry_id BIGINT NOT NULL COMMENT 'Target identity registry ID | immutable after insert',
    MODIFY COLUMN canonical_agent_id VARCHAR(100) NOT NULL COMMENT 'Resolved canonical agentId | immutable after insert',
    MODIFY COLUMN alias_type VARCHAR(32) NOT NULL DEFAULT 'LEGACY_AGENT_ID' COMMENT 'v1 online alias type | immutable after insert',
    MODIFY COLUMN alias_value VARCHAR(100) NOT NULL COMMENT 'Legacy agent ID resolved only with full owner scope | immutable after insert',
    MODIFY COLUMN alias_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED | once REVOKED cannot become ACTIVE again',
    MODIFY COLUMN active_key TINYINT GENERATED ALWAYS AS (
        CASE WHEN alias_status = 'ACTIVE' AND valid_to IS NULL THEN 1 ELSE NULL END
    ) STORED,
    MODIFY COLUMN client_id VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope client after insert',
    MODIFY COLUMN owner_jiacn VARCHAR(50) NOT NULL COMMENT 'Immutable owner-scope jiacn after insert',
    MODIFY COLUMN tenant_id VARCHAR(50) NOT NULL COMMENT 'Must equal TRIM(owner_jiacn) | immutable after insert';
ALTER TABLE agent_identity_alias COMMENT = 'Scoped legacy Agent ID compatibility aliases | collation binary enforces exact case matching';

-- Fail closed if b0/partial indexes do not have the exact required shape.
CALL a02_assert((SELECT CONCAT(MIN(non_unique), ':',
    GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.statistics WHERE table_schema = DATABASE()
      AND table_name = 'agent_identity_registry' AND index_name = 'uk_identity_registry_agent')
    = '0:canonical_agent_id', 'A02: incompatible registry canonical unique index');
CALL a02_assert((SELECT CONCAT(MIN(non_unique), ':',
    GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.statistics WHERE table_schema = DATABASE()
      AND table_name = 'agent_identity_registry' AND index_name = 'uk_identity_registry_binding')
    = '0:binding_id', 'A02: incompatible registry binding unique index');
CALL a02_assert((SELECT CONCAT(MIN(non_unique), ':',
    GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.statistics WHERE table_schema = DATABASE()
      AND table_name = 'agent_identity_registry' AND index_name = 'uk_identity_registry_alias_target')
    = '0:id,canonical_agent_id,client_id,owner_jiacn,tenant_id',
    'A02: incompatible registry alias target unique index');
CALL a02_assert((SELECT CONCAT(MIN(non_unique), ':',
    GROUP_CONCAT(LOWER(column_name) ORDER BY seq_in_index SEPARATOR ','))
    FROM information_schema.statistics WHERE table_schema = DATABASE()
      AND table_name = 'agent_identity_alias' AND index_name = 'uk_identity_alias_active')
    = '0:client_id,owner_jiacn,alias_type,alias_value,active_key',
    'A02: incompatible alias active unique index');

ALTER TABLE agent_identity_registry
    ADD CONSTRAINT chk_identity_registry_type CHECK (
        canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL', 'SYSTEM')),
    ADD CONSTRAINT chk_identity_registry_lifecycle CHECK (
        lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')),
    ADD CONSTRAINT chk_identity_registry_canonical CHECK (
        (canonical_type = 'OPAQUE' AND canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'LEGACY_CANONICAL'
            AND canonical_agent_id <> 'builtin-songjiang'
            AND canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'SYSTEM' AND canonical_agent_id = 'builtin-songjiang')),
    ADD CONSTRAINT chk_identity_registry_scope CHECK (
        (canonical_type = 'SYSTEM'
            AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id IS NULL)
        OR (canonical_type <> 'SYSTEM'
            AND client_id IS NOT NULL AND TRIM(client_id) <> ''
            AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
            AND tenant_id = TRIM(owner_jiacn))),
    ADD CONSTRAINT chk_identity_registry_retired CHECK (
        (lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL));

ALTER TABLE agent_identity_alias
    ADD CONSTRAINT chk_identity_alias_type CHECK (alias_type = 'LEGACY_AGENT_ID'),
    ADD CONSTRAINT chk_identity_alias_status CHECK (alias_status IN ('ACTIVE', 'REVOKED')),
    ADD CONSTRAINT chk_identity_alias_scope CHECK (tenant_id = TRIM(owner_jiacn)),
    ADD CONSTRAINT chk_identity_alias_no_blank_scope CHECK (
        TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''),
    ADD CONSTRAINT chk_identity_alias_window CHECK (
        (alias_status = 'ACTIVE' AND valid_to IS NULL)
        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)),
    ADD CONSTRAINT chk_identity_alias_not_system CHECK (
        alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'),
    ADD CONSTRAINT fk_identity_alias_registry_scope FOREIGN KEY
        (registry_id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
        REFERENCES agent_identity_registry
        (id, canonical_agent_id, client_id, owner_jiacn, tenant_id)
        ON UPDATE RESTRICT ON DELETE RESTRICT;

-- MySQL 8.0.21 does not support CREATE TRIGGER IF NOT EXISTS. Drop and recreate
-- exact definitions so the migration is both compatible and repeatable.
DROP TRIGGER IF EXISTS trg_identity_registry_immutable_update;
DROP TRIGGER IF EXISTS trg_identity_registry_no_delete;
DROP TRIGGER IF EXISTS trg_identity_alias_immutable_update;
DROP TRIGGER IF EXISTS trg_identity_alias_no_delete;
DELIMITER $$
CREATE TRIGGER trg_identity_registry_immutable_update
BEFORE UPDATE ON agent_identity_registry
FOR EACH ROW
BEGIN
    IF NOT (NEW.canonical_agent_id <=> OLD.canonical_agent_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: canonical_agent_id is immutable after insert';
    END IF;
    IF NOT (NEW.canonical_type <=> OLD.canonical_type) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: canonical_type is immutable after insert';
    END IF;
    IF NOT (NEW.client_id <=> OLD.client_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: client_id is immutable after insert';
    END IF;
    IF NOT (NEW.owner_jiacn <=> OLD.owner_jiacn) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: owner_jiacn is immutable after insert';
    END IF;
    IF NOT (NEW.tenant_id <=> OLD.tenant_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: tenant_id is immutable after insert';
    END IF;
    IF NOT (NEW.binding_id <=> OLD.binding_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: binding_id is immutable after insert';
    END IF;
    IF OLD.lifecycle_status = 'RETIRED' AND NEW.lifecycle_status <> 'RETIRED' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: RETIRED identity cannot be resurrected';
    END IF;
END$$
CREATE TRIGGER trg_identity_registry_no_delete
BEFORE DELETE ON agent_identity_registry
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity registry is forbidden';
END$$
CREATE TRIGGER trg_identity_alias_immutable_update
BEFORE UPDATE ON agent_identity_alias
FOR EACH ROW
BEGIN
    IF NOT (NEW.registry_id <=> OLD.registry_id)
       OR NOT (NEW.canonical_agent_id <=> OLD.canonical_agent_id)
       OR NOT (NEW.alias_type <=> OLD.alias_type)
       OR NOT (NEW.alias_value <=> OLD.alias_value)
       OR NOT (NEW.client_id <=> OLD.client_id)
       OR NOT (NEW.owner_jiacn <=> OLD.owner_jiacn)
       OR NOT (NEW.tenant_id <=> OLD.tenant_id) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: alias identity and owner scope are immutable';
    END IF;
    IF OLD.alias_status = 'REVOKED' AND NEW.alias_status = 'ACTIVE' THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: REVOKED alias cannot be reactivated';
    END IF;
END$$
CREATE TRIGGER trg_identity_alias_no_delete
BEFORE DELETE ON agent_identity_alias
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity alias is forbidden';
END$$
DELIMITER ;

DROP PROCEDURE a02_assert;
