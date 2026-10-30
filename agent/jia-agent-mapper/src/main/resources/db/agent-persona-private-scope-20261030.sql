-- Explicit maintenance only; not an application-startup script.
-- Back up schema/data and quiesce persona writes under the authorized maintenance owner first.
-- Platform definitions remain in versioned source; this script never relabels or deletes persona rows.
-- Existing rows must already have an explicit valid tenant and persona code. No missing-scope fallback.
DROP PROCEDURE IF EXISTS cyf_persona_private_scope_20261030;
DELIMITER $$
CREATE PROCEDURE cyf_persona_private_scope_20261030()
BEGIN
    IF EXISTS (SELECT 1 FROM agent_persona
        WHERE tenant_id IS NULL OR CHAR_LENGTH(tenant_id) = 0
           OR OCTET_LENGTH(tenant_id) <> OCTET_LENGTH(TRIM(tenant_id))
           OR persona_code IS NULL OR CHAR_LENGTH(persona_code) = 0
           OR (client_id IS NOT NULL AND (CHAR_LENGTH(client_id) = 0
               OR OCTET_LENGTH(client_id) <> OCTET_LENGTH(TRIM(client_id))))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Persona scope/code is incomplete; do not infer historical ownership';
    END IF;

    ALTER TABLE agent_persona
        MODIFY tenant_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
        MODIFY persona_code VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
        MODIFY name VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND column_name = 'catalog_client_key') THEN
        ALTER TABLE agent_persona ADD COLUMN catalog_client_key VARBINARY(200)
            GENERATED ALWAYS AS (CAST(COALESCE(client_id, '') AS BINARY)) STORED;
    END IF;
    -- Refuse an unrelated pre-existing/generated column rather than trusting its name.
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND column_name = 'catalog_client_key'
        AND column_type = 'varbinary(200)' AND extra LIKE '%STORED GENERATED%'
        AND REPLACE(REPLACE(REPLACE(LOWER(generation_expression), '`', ''), ' ', ''), '_utf8mb4', '')
            = 'cast(coalesce(client_id,'''')asbinary)') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Persona client scope key definition requires verification';
    END IF;
    -- Establish replacement uniqueness before removing old global constraints.
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_code') THEN
        CREATE UNIQUE INDEX uk_agent_persona_scope_code
            ON agent_persona (tenant_id, persona_code, catalog_client_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_name') THEN
        CREATE UNIQUE INDEX uk_agent_persona_scope_name
            ON agent_persona (tenant_id, name, catalog_client_key);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
        AND table_name = 'agent_persona' AND constraint_name = 'chk_agent_persona_private_scope') THEN
        ALTER TABLE agent_persona ADD CONSTRAINT chk_agent_persona_private_scope CHECK (
            CHAR_LENGTH(tenant_id) > 0 AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(TRIM(tenant_id))
            AND (client_id IS NULL OR (CHAR_LENGTH(client_id) > 0
                AND OCTET_LENGTH(client_id) = OCTET_LENGTH(TRIM(client_id)))));
    END IF;
    IF (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_code'
        AND non_unique = 0 AND sub_part IS NULL AND (
            (seq_in_index = 1 AND column_name = 'tenant_id') OR
            (seq_in_index = 3 AND column_name = 'catalog_client_key') OR
            (seq_in_index = 2 AND column_name = 'persona_code'))) <> 3
        OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
            AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_code') <> 3
        OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
            AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_name'
            AND non_unique = 0 AND sub_part IS NULL AND (
                (seq_in_index = 1 AND column_name = 'tenant_id') OR
                (seq_in_index = 3 AND column_name = 'catalog_client_key') OR
                (seq_in_index = 2 AND column_name = 'name'))) <> 3
        OR (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
            AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_scope_name') <> 3 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Persona scoped unique index shape mismatch';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_code') THEN
        DROP INDEX uk_agent_persona_code ON agent_persona;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()
        AND table_name = 'agent_persona' AND index_name = 'uk_agent_persona_name') THEN
        DROP INDEX uk_agent_persona_name ON agent_persona;
    END IF;
END$$
DELIMITER ;
CALL cyf_persona_private_scope_20261030();
DROP PROCEDURE cyf_persona_private_scope_20261030;
