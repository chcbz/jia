package cn.jia.agent.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentSchemaInitializer implements InitializingBean {
    private final JdbcTemplate jdbcTemplate;
    private Boolean h2Database;

    @Override
    public void afterPropertiesSet() {
        ensureAgentPersonaColumns();
        ensureAgentRuntimeColumns();
        ensureBindingTable();
        ensureIdentitySchema();
        ensureTaskCollaborationSchema();
        ensureTaskNoteTable();
        ensureSceneTables();
        seedWaterMarginPersonas();
    }

    private void ensureAgentPersonaColumns() {
        addColumnIfMissing("agent_persona", "persona_code",
                "persona_code VARCHAR(50) DEFAULT NULL COMMENT 'Water Margin persona code'");
        addColumnIfMissing("agent_persona", "rank_no",
                "rank_no INT DEFAULT NULL COMMENT 'Liangshan ranking number'");
        addColumnIfMissing("agent_persona", "star_name",
                "star_name VARCHAR(50) DEFAULT NULL COMMENT 'Star name'");
        addColumnIfMissing("agent_persona", "visual_config",
                "visual_config TEXT COMMENT 'Frontend visual config JSON'");
        addColumnIfMissing("agent_persona", "system_agent",
                "system_agent TINYINT(1) DEFAULT 0 COMMENT 'System controlled persona'");
        addIndexIfMissing("agent_persona", "uk_agent_persona_code",
                "CREATE UNIQUE INDEX uk_agent_persona_code ON agent_persona (persona_code)");
        addIndexIfMissing("agent_persona", "idx_agent_persona_system",
                "CREATE INDEX idx_agent_persona_system ON agent_persona (system_agent)");
    }

    private void ensureAgentRuntimeColumns() {
        addColumnIfMissing("agent_runtime", "owner_jiacn",
                "owner_jiacn VARCHAR(50) DEFAULT NULL COMMENT 'Bound user Jia account'");
        addColumnIfMissing("agent_runtime", "persona_code",
                "persona_code VARCHAR(50) DEFAULT NULL COMMENT 'Bound persona code'");
        addColumnIfMissing("agent_runtime", "binding_id",
                "binding_id BIGINT DEFAULT NULL COMMENT 'Persona binding ID'");
        addIndexIfMissing("agent_runtime", "idx_agent_runtime_owner",
                "CREATE INDEX idx_agent_runtime_owner ON agent_runtime (client_id, owner_jiacn)");
        addIndexIfMissing("agent_runtime", "idx_agent_runtime_persona_code",
                "CREATE INDEX idx_agent_runtime_persona_code ON agent_runtime (persona_code)");
    }

    private void ensureBindingTable() {
        String generatedColumnStorage = isH2Database() ? "" : " STORED";
        String ownerJiacnColumn = "owner_jiacn VARCHAR(50) GENERATED ALWAYS AS (jiacn)"
                + generatedColumnStorage;
        String lifecycleColumn = "lifecycle_status VARCHAR(20) GENERATED ALWAYS AS "
                + "(CASE status WHEN 2 THEN 'PROVISIONED' WHEN 1 THEN 'ACTIVE' "
                + "WHEN 0 THEN 'SUSPENDED' WHEN 3 THEN 'RETIRED' ELSE NULL END)"
                + generatedColumnStorage;
        String activePersonaColumn = "active_persona_code VARCHAR(50) GENERATED ALWAYS AS "
                + "(CASE WHEN status = 1 THEN persona_code ELSE NULL END)" + generatedColumnStorage;
        String activeAgentColumn = "active_agent_id     VARCHAR(100) GENERATED ALWAYS AS "
                + "(CASE WHEN status = 1 THEN agent_id ELSE NULL END)" + generatedColumnStorage;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_persona_binding (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    jiacn               VARCHAR(50) NOT NULL COMMENT 'Legacy writable owner Jia account field',
                    %s,
                    persona_code        VARCHAR(50) NOT NULL COMMENT 'Water Margin persona code',
                    agent_id            VARCHAR(100) NOT NULL COMMENT 'Canonical Agent ID',
                    bound_at            BIGINT NOT NULL COMMENT 'Bind time',
                    status              INT NOT NULL DEFAULT 1 COMMENT '2 provisioned, 1 active, 0 suspended, 3 retired',
                    %s,
                    %s,
                    %s,
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    tenant_id           VARCHAR(50) DEFAULT '0' COMMENT 'Legacy tenant, when populated must equal owner_jiacn',
                    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'Owner-scope client ID',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_binding_active_persona (client_id, owner_jiacn, active_persona_code),
                    UNIQUE KEY uk_agent_binding_active_agent (active_agent_id),
                    KEY idx_agent_binding_user (client_id, jiacn, status),
                    KEY idx_agent_binding_agent (client_id, agent_id, status),
                    KEY idx_agent_binding_persona (client_id, persona_code, status),
                    CONSTRAINT chk_agent_binding_status CHECK (status IN (0, 1, 2, 3)),
                    CONSTRAINT chk_agent_binding_tenant_owner CHECK (tenant_id = '0' OR tenant_id = owner_jiacn)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Durable Agent persona binding history'
                """.formatted(ownerJiacnColumn, lifecycleColumn, activePersonaColumn, activeAgentColumn));
        addRequiredColumnIfMissing("agent_persona_binding", "owner_jiacn", ownerJiacnColumn);
        addRequiredColumnIfMissing("agent_persona_binding", "lifecycle_status", lifecycleColumn);
        addRequiredColumnIfMissing("agent_persona_binding", "active_persona_code", activePersonaColumn);
        addRequiredColumnIfMissing("agent_persona_binding", "active_agent_id", activeAgentColumn);
        ensureRequiredIndex("agent_persona_binding", "uk_agent_binding_active_persona", true,
                List.of("client_id", "owner_jiacn", "active_persona_code"),
                "CREATE UNIQUE INDEX uk_agent_binding_active_persona "
                        + "ON agent_persona_binding (client_id, owner_jiacn, active_persona_code)");
        ensureRequiredIndex("agent_persona_binding", "uk_agent_binding_active_agent", true,
                List.of("active_agent_id"),
                "CREATE UNIQUE INDEX uk_agent_binding_active_agent ON agent_persona_binding (active_agent_id)");
        ensureRequiredCheckConstraint("agent_persona_binding", "chk_agent_binding_status",
                "status IN (0, 1, 2, 3)");
        ensureRequiredCheckConstraint("agent_persona_binding", "chk_agent_binding_tenant_owner",
                "tenant_id = '0' OR tenant_id = owner_jiacn");
    }

    private void validateExistingIdentityTables(boolean validateTriggers) {
        boolean registryExists = tableExists("agent_identity_registry");
        boolean aliasExists = tableExists("agent_identity_alias");
        if (registryExists != aliasExists) {
            throw new IllegalStateException(
                    "A02 identity schema is partial: registry and alias tables must exist together");
        }
        if (!registryExists) {
            return;
        }

        validateIdentityColumn("agent_identity_registry", "canonical_agent_id",
                "varchar", "varchar(100)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "canonical_type",
                "varchar", "varchar(32)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "lifecycle_status",
                "varchar", "varchar(20)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "client_id",
                "varchar", "varchar(50)", true, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "owner_jiacn",
                "varchar", "varchar(50)", true, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "tenant_id",
                "varchar", "varchar(50)", true, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_registry", "binding_id",
                "bigint", "bigint", true, null, null);
        validateIdentityColumn("agent_identity_registry", "audit_reason",
                "varchar", "varchar(1000)", false, "utf8mb4_0900_bin", null);

        validateIdentityColumn("agent_identity_alias", "registry_id",
                "bigint", "bigint", false, null, null);
        validateIdentityColumn("agent_identity_alias", "canonical_agent_id",
                "varchar", "varchar(100)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "alias_type",
                "varchar", "varchar(32)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "alias_value",
                "varchar", "varchar(100)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "alias_status",
                "varchar", "varchar(20)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "client_id",
                "varchar", "varchar(50)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "owner_jiacn",
                "varchar", "varchar(50)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "tenant_id",
                "varchar", "varchar(50)", false, "utf8mb4_0900_bin", null);
        validateIdentityColumn("agent_identity_alias", "active_key",
                "tinyint", "tinyint", true, null,
                "(case when ((alias_status = 'ACTIVE') and (valid_to is null)) then 1 else null end)");

        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_agent", true,
                List.of("canonical_agent_id"), "");
        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_binding", true,
                List.of("binding_id"), "");
        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_alias_target", true,
                List.of("id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id"), "");
        ensureRequiredIndex("agent_identity_registry", "idx_identity_registry_scope_status", false,
                List.of("tenant_id", "client_id", "owner_jiacn", "lifecycle_status"), "");
        ensureRequiredIndex("agent_identity_alias", "uk_identity_alias_active", true,
                List.of("client_id", "owner_jiacn", "alias_type", "alias_value", "active_key"), "");
        ensureRequiredIndex("agent_identity_alias", "idx_identity_alias_registry", false,
                List.of("registry_id", "alias_status"), "");
        ensureRequiredIndex("agent_identity_alias", "idx_identity_alias_canonical", false,
                List.of("canonical_agent_id", "alias_status"), "");

        if (!isH2Database()) {
            validateTableCollation("agent_identity_registry", "utf8mb4_0900_bin");
            validateTableCollation("agent_identity_alias", "utf8mb4_0900_bin");
            validateIdentityChecks();
            validateIdentityAliasForeignKey();
            if (validateTriggers) {
                validateIdentityTriggers();
            }
        }
    }

    private void validateRuntimeIdentityProjection() {
        if (!tableExists("agent_runtime")) {
            return;
        }
        validateIdentityColumn("agent_runtime", "owner_jiacn",
                "varchar", "varchar(50)", true, null, null);
        validateIdentityColumn("agent_runtime", "persona_code",
                "varchar", "varchar(50)", true, null, null);
        validateIdentityColumn("agent_runtime", "binding_id",
                "bigint", "bigint", true, null, null);
        ensureRequiredIndex("agent_runtime", "idx_agent_runtime_owner", false,
                List.of("client_id", "owner_jiacn"), "");
        ensureRequiredIndex("agent_runtime", "idx_agent_runtime_persona_code", false,
                List.of("persona_code"), "");
    }

    private void validateIdentityColumn(
            String table, String column, String dataType, String columnType,
            boolean nullable, String collation, String generationExpression) {
        List<ColumnDefinition> definitions = inspectColumnDefinition(table, column);
        if (definitions.size() != 1) {
            throw new IllegalStateException("A02 required column " + table + "." + column
                    + " is missing or duplicated");
        }
        ColumnDefinition actual = definitions.get(0);
        boolean matches = dataType.equalsIgnoreCase(actual.dataType())
                && normalizeSql(columnType).equals(normalizeSql(actual.columnType()))
                && nullable == actual.nullable();
        if (collation != null && !isH2Database()) {
            matches &= collation.equalsIgnoreCase(actual.collation());
        }
        if (generationExpression != null) {
            matches &= normalizeIdentityExpression(generationExpression)
                    .equals(normalizeIdentityExpression(actual.generationExpression()));
        } else {
            matches &= actual.generationExpression() == null
                    || actual.generationExpression().isBlank();
        }
        if (!matches) {
            throw new IllegalStateException("A02 required column " + table + "." + column
                    + " has incompatible type/nullability/collation/generated expression: " + actual);
        }
    }

    private List<ColumnDefinition> inspectColumnDefinition(String table, String column) {
        if (isH2Database()) {
            return jdbcTemplate.query("""
                    SELECT DATA_TYPE, DECLARED_DATA_TYPE AS COLUMN_TYPE, IS_NULLABLE,
                           COLLATION_NAME, GENERATION_EXPRESSION
                    FROM information_schema.columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = LOWER(?)
                      AND LOWER(column_name) = LOWER(?)
                    """, (rs, rowNum) -> new ColumnDefinition(
                    rs.getString("DATA_TYPE"), rs.getString("COLUMN_TYPE"),
                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                    rs.getString("COLLATION_NAME"), rs.getString("GENERATION_EXPRESSION")),
                    table, column);
        }
        return jdbcTemplate.query("""
                SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLLATION_NAME, GENERATION_EXPRESSION
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, (rs, rowNum) -> new ColumnDefinition(
                rs.getString("DATA_TYPE"), rs.getString("COLUMN_TYPE"),
                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                rs.getString("COLLATION_NAME"), rs.getString("GENERATION_EXPRESSION")),
                table, column);
    }

    private void validateTableCollation(String table, String expected) {
        String actual = jdbcTemplate.queryForObject("""
                SELECT TABLE_COLLATION FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);
        if (!expected.equalsIgnoreCase(actual)) {
            throw new IllegalStateException("A02 identity table " + table
                    + " must use binary collation " + expected + " but was " + actual);
        }
    }

    private void validateIdentityChecks() {
        java.util.Map<String, String> expected = java.util.Map.ofEntries(
                java.util.Map.entry("agent_identity_registry.chk_identity_registry_type",
                        "canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL', 'SYSTEM')"),
                java.util.Map.entry("agent_identity_registry.chk_identity_registry_lifecycle",
                        "lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')"),
                java.util.Map.entry("agent_identity_registry.chk_identity_registry_canonical", """
                        (canonical_type = 'OPAQUE'
                            AND REGEXP_LIKE(canonical_agent_id, '^agt_[0-9a-f]{32}$'))
                        OR (canonical_type = 'LEGACY_CANONICAL'
                            AND canonical_agent_id <> 'builtin-songjiang'
                            AND NOT(REGEXP_LIKE(canonical_agent_id, '^agt_[0-9a-f]{32}$')))
                        OR (canonical_type = 'SYSTEM'
                            AND canonical_agent_id = 'builtin-songjiang')
                        """),
                java.util.Map.entry("agent_identity_registry.chk_identity_registry_scope", """
                        (canonical_type = 'SYSTEM'
                            AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id IS NULL)
                        OR (canonical_type <> 'SYSTEM'
                            AND client_id IS NOT NULL AND TRIM(client_id) <> ''
                            AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
                            AND tenant_id = TRIM(owner_jiacn))
                        """),
                java.util.Map.entry("agent_identity_registry.chk_identity_registry_retired", """
                        (lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
                        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL)
                        """),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_type",
                        "alias_type = 'LEGACY_AGENT_ID'"),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_status",
                        "alias_status IN ('ACTIVE', 'REVOKED')"),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_scope",
                        "tenant_id = TRIM(owner_jiacn)"),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_no_blank_scope",
                        "TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''"),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_window", """
                        (alias_status = 'ACTIVE' AND valid_to IS NULL)
                        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)
                        """),
                java.util.Map.entry("agent_identity_alias.chk_identity_alias_not_system",
                        "alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'"));
        List<CheckDefinition> actual = jdbcTemplate.query("""
                SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
                FROM information_schema.table_constraints tc
                JOIN information_schema.check_constraints cc
                  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
                 AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
                WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
                  AND tc.CONSTRAINT_TYPE = 'CHECK'
                  AND tc.TABLE_NAME IN ('agent_identity_registry', 'agent_identity_alias')
                """, (rs, rowNum) -> new CheckDefinition(
                rs.getString("TABLE_NAME"), rs.getString("CONSTRAINT_NAME"),
                rs.getString("CHECK_CLAUSE")));
        java.util.Map<String, String> actualByName = new java.util.HashMap<>();
        for (CheckDefinition definition : actual) {
            actualByName.put(definition.table() + "." + definition.name(),
                    normalizeIdentityExpression(definition.clause()));
        }
        if (!actualByName.keySet().equals(expected.keySet())) {
            throw new IllegalStateException("A02 identity CHECK constraint set is incompatible: "
                    + actualByName.keySet());
        }
        for (var entry : expected.entrySet()) {
            if (!normalizeIdentityExpression(entry.getValue()).equals(actualByName.get(entry.getKey()))) {
                throw new IllegalStateException("A02 CHECK " + entry.getKey()
                        + " has an incompatible definition");
            }
        }
    }

    private void validateIdentityAliasForeignKey() {
        List<ForeignKeyColumn> columns = jdbcTemplate.query("""
                SELECT kcu.COLUMN_NAME, kcu.REFERENCED_TABLE_NAME,
                       kcu.REFERENCED_COLUMN_NAME, kcu.ORDINAL_POSITION,
                       rc.UPDATE_RULE, rc.DELETE_RULE
                FROM information_schema.key_column_usage kcu
                JOIN information_schema.referential_constraints rc
                  ON rc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA
                 AND rc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                WHERE kcu.CONSTRAINT_SCHEMA = DATABASE()
                  AND kcu.TABLE_NAME = 'agent_identity_alias'
                  AND kcu.CONSTRAINT_NAME = 'fk_identity_alias_registry_scope'
                ORDER BY kcu.ORDINAL_POSITION
                """, (rs, rowNum) -> new ForeignKeyColumn(
                rs.getString("COLUMN_NAME"), rs.getString("REFERENCED_TABLE_NAME"),
                rs.getString("REFERENCED_COLUMN_NAME"), rs.getInt("ORDINAL_POSITION"),
                rs.getString("UPDATE_RULE"), rs.getString("DELETE_RULE")));
        List<String> expectedColumns = List.of(
                "registry_id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id");
        List<String> expectedReferencedColumns = List.of(
                "id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id");
        boolean matches = columns.size() == expectedColumns.size();
        for (int i = 0; matches && i < columns.size(); i++) {
            ForeignKeyColumn column = columns.get(i);
            matches = expectedColumns.get(i).equalsIgnoreCase(column.column())
                    && expectedReferencedColumns.get(i).equalsIgnoreCase(column.referencedColumn())
                    && "agent_identity_registry".equalsIgnoreCase(column.referencedTable())
                    && column.position() == i + 1
                    && "RESTRICT".equalsIgnoreCase(column.updateRule())
                    && "RESTRICT".equalsIgnoreCase(column.deleteRule());
        }
        if (!matches) {
            throw new IllegalStateException(
                    "A02 alias composite FK definition is missing or incompatible");
        }
    }

    private void validateIdentityTriggers() {
        java.util.Map<String, TriggerDefinition> expected = expectedIdentityTriggers();
        List<TriggerDefinition> actual = jdbcTemplate.query("""
                SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING,
                       EVENT_MANIPULATION, ACTION_STATEMENT
                FROM information_schema.triggers
                WHERE trigger_schema = DATABASE()
                  AND trigger_name IN (
                    'trg_identity_registry_immutable_update', 'trg_identity_registry_no_delete',
                    'trg_identity_alias_immutable_update', 'trg_identity_alias_no_delete')
                """, (rs, rowNum) -> new TriggerDefinition(
                rs.getString("TRIGGER_NAME"), rs.getString("EVENT_OBJECT_TABLE"),
                rs.getString("ACTION_TIMING"), rs.getString("EVENT_MANIPULATION"),
                rs.getString("ACTION_STATEMENT")));
        if (actual.size() != expected.size()) {
            throw new IllegalStateException("A02 requires four exact identity protection triggers");
        }
        for (TriggerDefinition trigger : actual) {
            TriggerDefinition required = expected.get(trigger.name());
            boolean matches = required != null
                    && required.table().equalsIgnoreCase(trigger.table())
                    && required.timing().equalsIgnoreCase(trigger.timing())
                    && required.event().equalsIgnoreCase(trigger.event())
                    && normalizeSql(required.statement()).equals(normalizeSql(trigger.statement()));
            if (!matches) {
                throw new IllegalStateException("A02 trigger " + trigger.name()
                        + " has an incompatible definition");
            }
        }
    }

    private java.util.Map<String, TriggerDefinition> expectedIdentityTriggers() {
        return java.util.Map.of(
                "trg_identity_registry_immutable_update", new TriggerDefinition(
                        "trg_identity_registry_immutable_update", "agent_identity_registry", "BEFORE", "UPDATE", """
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
                        END
                        """),
                "trg_identity_registry_no_delete", new TriggerDefinition(
                        "trg_identity_registry_no_delete", "agent_identity_registry", "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity registry is forbidden';
                        END
                        """),
                "trg_identity_alias_immutable_update", new TriggerDefinition(
                        "trg_identity_alias_immutable_update", "agent_identity_alias", "BEFORE", "UPDATE", """
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
                        END
                        """),
                "trg_identity_alias_no_delete", new TriggerDefinition(
                        "trg_identity_alias_no_delete", "agent_identity_alias", "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity alias is forbidden';
                        END
                        """));
    }

    private void createIdentityTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_identity_registry_immutable_update");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_identity_registry_no_delete");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_identity_alias_immutable_update");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_identity_alias_no_delete");
        for (TriggerDefinition trigger : expectedIdentityTriggers().values()) {
            jdbcTemplate.execute("CREATE TRIGGER " + trigger.name() + " " + trigger.timing()
                    + " " + trigger.event() + " ON " + trigger.table()
                    + " FOR EACH ROW " + trigger.statement());
        }
    }


    String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replace("_utf8mb4", "")
                .replace("\\", "")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String normalizeIdentityExpression(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.toLowerCase(Locale.ROOT)
                .replace("`", "")
                .replace("_utf8mb4", "")
                .replace("\\", "")
                .replaceAll("[()]", " ")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean tableExists(String table) {
        String catalogQuery = isH2Database()
                ? """
                  SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?)
                  """
                : """
                  SELECT COUNT(*) FROM information_schema.tables
                  WHERE table_schema = DATABASE() AND table_name = ?
                  """;
        List<Integer> counts = jdbcTemplate.query(catalogQuery,
                (rs, rowNum) -> rs.getInt(1), table);
        return !counts.isEmpty() && counts.get(0) > 0;
    }

    static record ColumnDefinition(
            String dataType, String columnType, boolean nullable,
            String collation, String generationExpression) {}
    static record CheckDefinition(String table, String name, String clause) {}
    static record ForeignKeyColumn(
            String column, String referencedTable, String referencedColumn,
            int position, String updateRule, String deleteRule) {}
    static record TriggerDefinition(
            String name, String table, String timing, String event, String statement) {}
    static record BackfillColumnExpectation(
            String table, String column, String dataType, String columnType,
            boolean nullable, String defaultValue, String collation) {}
    static record BackfillColumnDefinition(
            String dataType, String columnType, boolean nullable,
            String defaultValue, String collation, String extra) {
        BackfillColumnDefinition(
                String dataType, String columnType, boolean nullable,
                String defaultValue, String collation) {
            this(dataType, columnType, nullable, defaultValue, collation, "");
        }
    }

    private void ensureIdentitySchema() {
        validateRuntimeIdentityProjection();
        boolean identityTablesAlreadyExist = tableExists("agent_identity_registry")
                || tableExists("agent_identity_alias");
        validateExistingIdentityTables(false);
        String generatedColumnStorage = isH2Database() ? "" : " STORED";
        String activeAliasColumn = "active_key TINYINT GENERATED ALWAYS AS "
                + "( CASE WHEN alias_status = 'ACTIVE' AND valid_to IS NULL THEN 1 ELSE NULL END)"
                + generatedColumnStorage;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_identity_registry (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId | immutable after insert | never reused even after delete',
                    canonical_type          VARCHAR(32) NOT NULL COMMENT 'OPAQUE/LEGACY_CANONICAL/SYSTEM | immutable after insert',
                    lifecycle_status        VARCHAR(20) NOT NULL DEFAULT 'PROVISIONED' COMMENT 'PROVISIONED/ACTIVE/SUSPENDED/RETIRED | RETIRED is terminal and cannot be reverted',
                    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope client after insert | NULL only for system identity',
                    owner_jiacn             VARCHAR(50) DEFAULT NULL COMMENT 'Immutable owner-scope jiacn after insert | NULL only for system identity',
                    tenant_id               VARCHAR(50) DEFAULT '0' COMMENT 'Must equal TRIM(owner_jiacn) | NULL only for system | immutable after insert',
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
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Durable canonical Agent identity registry | collation binary enforces exact case matching'
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_identity_alias (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    registry_id             BIGINT NOT NULL COMMENT 'Target identity registry ID | immutable after insert',
                    canonical_agent_id      VARCHAR(100) NOT NULL COMMENT 'Resolved canonical agentId | immutable after insert',
                    alias_type              VARCHAR(32) NOT NULL DEFAULT 'LEGACY_AGENT_ID' COMMENT 'v1 online alias type | immutable after insert',
                    alias_value             VARCHAR(100) NOT NULL COMMENT 'Legacy agent ID resolved only with full owner scope | immutable after insert',
                    alias_status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED | once REVOKED cannot become ACTIVE again',
                    valid_from              BIGINT NOT NULL COMMENT 'Alias activation time',
                    valid_to                BIGINT DEFAULT NULL COMMENT 'Alias revocation time, not used directly for uniqueness',
                    %s,
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
                        ON UPDATE RESTRICT ON DELETE RESTRICT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped legacy Agent ID compatibility aliases | collation binary enforces exact case matching'
                """.formatted(activeAliasColumn));

        addRequiredColumnIfMissing("agent_identity_alias", "active_key", activeAliasColumn);
        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_agent", true,
                List.of("canonical_agent_id"),
                "CREATE UNIQUE INDEX uk_identity_registry_agent "
                        + "ON agent_identity_registry (canonical_agent_id)");
        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_binding", true,
                List.of("binding_id"),
                "CREATE UNIQUE INDEX uk_identity_registry_binding ON agent_identity_registry (binding_id)");
        ensureRequiredIndex("agent_identity_registry", "uk_identity_registry_alias_target", true,
                List.of("id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id"),
                "CREATE UNIQUE INDEX uk_identity_registry_alias_target "
                        + "ON agent_identity_registry "
                        + "(id, canonical_agent_id, client_id, owner_jiacn, tenant_id)");
        ensureRequiredIndex("agent_identity_registry", "idx_identity_registry_scope_status", false,
                List.of("tenant_id", "client_id", "owner_jiacn", "lifecycle_status"),
                "CREATE INDEX idx_identity_registry_scope_status "
                        + "ON agent_identity_registry (tenant_id, client_id, owner_jiacn, lifecycle_status)");
        ensureRequiredIndex("agent_identity_alias", "uk_identity_alias_active", true,
                List.of("client_id", "owner_jiacn", "alias_type", "alias_value", "active_key"),
                "CREATE UNIQUE INDEX uk_identity_alias_active "
                        + "ON agent_identity_alias (client_id, owner_jiacn, alias_type, alias_value, active_key)");
        ensureRequiredIndex("agent_identity_alias", "idx_identity_alias_registry", false,
                List.of("registry_id", "alias_status"),
                "CREATE INDEX idx_identity_alias_registry ON agent_identity_alias (registry_id, alias_status)");
        ensureRequiredIndex("agent_identity_alias", "idx_identity_alias_canonical", false,
                List.of("canonical_agent_id", "alias_status"),
                "CREATE INDEX idx_identity_alias_canonical "
                        + "ON agent_identity_alias (canonical_agent_id, alias_status)");
        if (!isH2Database() && (!identityTablesAlreadyExist
                || identityProtectionTriggerCount() == 0 && tablesAreEmpty(
                        "agent_identity_registry", "agent_identity_alias"))) {
            createIdentityTriggers();
        }
        validateExistingIdentityTables(true);
    }

    private void ensureTaskCollaborationSchema() {
        boolean backfillIssueAlreadyExists = tableExists("agent_task_backfill_issue");
        boolean backfillBatchAlreadyExists = tableExists("agent_task_backfill_manifest_batch");
        boolean backfillManifestAlreadyExists = tableExists("agent_task_backfill_manifest");
        boolean backfillRunAlreadyExists = tableExists("agent_task_backfill_run");
        boolean backfillAuditAlreadyExists = backfillIssueAlreadyExists
                || backfillBatchAlreadyExists || backfillManifestAlreadyExists
                || backfillRunAlreadyExists;
        boolean bootstrapBackfillTriggers = false;
        if (backfillAuditAlreadyExists && !isH2Database()) {
            if (!(backfillIssueAlreadyExists && backfillBatchAlreadyExists
                    && backfillManifestAlreadyExists && backfillRunAlreadyExists)) {
                throw new IllegalStateException("B09 audit schema is partial; run the exact B09 audit migration");
            }
            validateBackfillAuditSchema();
            bootstrapBackfillTriggers = backfillAuditTriggerCount() == 0 && tablesAreEmpty(
                    "agent_task_backfill_issue", "agent_task_backfill_manifest_batch",
                    "agent_task_backfill_manifest", "agent_task_backfill_run");
            if (!bootstrapBackfillTriggers) {
                validateBackfillAuditTriggers();
            }
        }

        addRequiredColumnIfMissing("agent_task_meta", "collaboration_mode",
                "collaboration_mode VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT 'single/team'");
        addRequiredColumnIfMissing("agent_task_meta", "risk_level",
                "risk_level VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT 'low/medium/high'");
        addRequiredColumnIfMissing("agent_task_meta", "max_agents",
                "max_agents INT NOT NULL DEFAULT 1 COMMENT 'Maximum collaboration agent count'");
        addRequiredColumnIfMissing("agent_task_meta", "coordinator_agent_id",
                "coordinator_agent_id VARCHAR(100) DEFAULT NULL COMMENT 'ADR-001 canonical coordinator agentId'");
        addRequiredColumnIfMissing("agent_task_meta", "review_required",
                "review_required TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Whether independent review is required'");
        addRequiredColumnIfMissing("agent_task_meta", "task_version",
                "task_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Task aggregate optimistic lock version'");
        addRequiredColumnIfMissing("agent_task_meta", "current_event_version",
                "current_event_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Latest persisted task event version'");
        migrateTaskMetaScopedUniqueness();

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_member (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    agent_id            VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical agentId',
                    member_role         VARCHAR(20) NOT NULL COMMENT 'coordinator/worker/reviewer/observer',
                    member_status       VARCHAR(20) NOT NULL DEFAULT 'invited' COMMENT 'invited/accepted/working/done/rejected/blocked/failed/left',
                    assignment_source   VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT 'manual/auto/migration',
                    joined_at           BIGINT DEFAULT NULL COMMENT 'Join or invitation time',
                    accepted_at         BIGINT DEFAULT NULL COMMENT 'Acceptance time',
                    started_at          BIGINT DEFAULT NULL COMMENT 'Work start time',
                    completed_at        BIGINT DEFAULT NULL COMMENT 'Completion time',
                    last_heartbeat_at   BIGINT DEFAULT NULL COMMENT 'Last member heartbeat time',
                    failure_reason      VARCHAR(1000) DEFAULT NULL COMMENT 'Failure or blocking reason',
                    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
                    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
                    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_member_scope (tenant_id, client_id, task_id, agent_id),
                    KEY idx_task_member_agent_status (tenant_id, client_id, agent_id, member_status),
                    KEY idx_task_member_task_status (tenant_id, client_id, task_id, member_status),
                    KEY idx_task_member_task_role (tenant_id, client_id, task_id, member_role, member_status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent task members'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_work_item (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    work_item_id        VARCHAR(100) NOT NULL COMMENT 'Stable work item ID',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    title               VARCHAR(255) NOT NULL COMMENT 'Work item title',
                    description         TEXT COMMENT 'Work item description',
                    work_type           VARCHAR(30) NOT NULL COMMENT 'Work item type',
                    required_abilities  TEXT COMMENT 'Required abilities JSON array',
                    assignee_agent_id   VARCHAR(100) DEFAULT NULL COMMENT 'ADR-001 canonical assignee agentId',
                    status              VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending/ready/claimed/running/blocked/submitted/completed/failed/cancelled',
                    priority            INT NOT NULL DEFAULT 0 COMMENT 'Higher value means higher priority',
                    required_item       TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Whether task completion requires this item',
                    dependency_json     TEXT COMMENT 'Dependency work item IDs JSON array',
                    lease_token         VARCHAR(100) DEFAULT NULL COMMENT 'Current claim lease token',
                    lease_until         BIGINT DEFAULT NULL COMMENT 'Lease expiry time',
                    attempt_count       INT NOT NULL DEFAULT 0 COMMENT 'Execution attempts',
                    max_attempts        INT NOT NULL DEFAULT 3 COMMENT 'Maximum execution attempts',
                    result_artifact_id  VARCHAR(100) DEFAULT NULL COMMENT 'Accepted result artifact ID',
                    submitted_at        BIGINT DEFAULT NULL COMMENT 'Submission time',
                    completed_at        BIGINT DEFAULT NULL COMMENT 'Review completion time',
                    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
                    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
                    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_work_item_scope (tenant_id, client_id, work_item_id),
                    KEY idx_work_item_task_status (tenant_id, client_id, task_id, status, priority),
                    KEY idx_work_item_assignee_status (tenant_id, client_id, assignee_agent_id, status, lease_until),
                    KEY idx_work_item_lease (status, lease_until, id),
                    KEY idx_work_item_task_required (tenant_id, client_id, task_id, required_item, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent task work items'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_backfill_issue (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    issue_key               CHAR(64) NOT NULL COMMENT 'Deterministic SHA-256 issue identity',
                    meta_id                 BIGINT NOT NULL COMMENT 'Source agent_task_meta primary key',
                    task_id                 VARCHAR(100) NOT NULL COMMENT 'Source task ID',
                    source_hash             CHAR(64) NOT NULL COMMENT 'SHA-256 of the original assignee field',
                    source_format           VARCHAR(32) NOT NULL COMMENT 'Detected legacy assignee shape',
                    source_shape            VARCHAR(32) NOT NULL COMMENT 'Parsed scalar/array/object location',
                    source_ordinal          INT NOT NULL COMMENT 'Stable source element ordinal',
                    raw_assignee            VARCHAR(100) DEFAULT NULL COMMENT 'Original agent_task_meta.assigned_agent_id',
                    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Parsed historical Agent ID before resolution',
                    issue_code              VARCHAR(64) NOT NULL COMMENT 'Fail-closed B09 exception/review code',
                    issue_reason            VARCHAR(1000) NOT NULL COMMENT 'Auditable resolution reason',
                    first_report_sha256     CHAR(64) NOT NULL COMMENT 'First approved canonical manifest digest',
                    last_report_sha256      CHAR(64) NOT NULL COMMENT 'Latest approved canonical manifest digest',
                    first_seen_at           BIGINT NOT NULL COMMENT 'First apply observation time',
                    last_seen_at            BIGINT NOT NULL COMMENT 'Latest apply observation time',
                    occurrence_count        BIGINT NOT NULL DEFAULT 1 COMMENT 'Number of approved apply observations',
                    last_operator           VARCHAR(100) NOT NULL COMMENT 'Latest approved migration operator',
                    tenant_id               VARCHAR(50) DEFAULT '0' COMMENT 'Source owner jiacn scope',
                    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Source OAuth/API client scope, nullable only for audited bad history',
                    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_backfill_issue_key (issue_key),
                    KEY idx_task_backfill_issue_scope_task (tenant_id, client_id, task_id, issue_code),
                    KEY idx_task_backfill_issue_code_seen (issue_code, last_seen_at, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Auditable B09 historical task backfill exceptions'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest_batch (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
                    manifest_row_count      BIGINT NOT NULL DEFAULT 0 COMMENT 'Exact sealed manifest row count',
                    seal_status             VARCHAR(16) NOT NULL COMMENT 'LOADING/SEALED/LEGACY_UNSEALED, only SEALED is consumable',
                    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
                    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
                    sealed_at               BIGINT DEFAULT NULL COMMENT 'Seal time, non-null only when SEALED',
                    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_backfill_manifest_batch_digest (report_sha256),
                    KEY idx_task_backfill_manifest_batch_status (seal_status, approved_at, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Sealed B09 canonical manifest approval batch'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    report_sha256           CHAR(64) NOT NULL COMMENT 'Database-recomputed canonical manifest digest',
                    manifest_row_key        CHAR(64) NOT NULL COMMENT 'Database-verified deterministic source row identity',
                    manifest_row_sha256     CHAR(64) NOT NULL COMMENT 'Database-recomputed source/scope/resolution digest',
                    meta_id                 BIGINT NOT NULL COMMENT 'Approved source agent_task_meta primary key',
                    task_id                 VARCHAR(100) NOT NULL COMMENT 'Approved source task ID',
                    tenant_id               VARCHAR(50) DEFAULT '0' COMMENT 'Approved tenant scope',
                    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'Approved client scope',
                    source_hash             CHAR(64) NOT NULL COMMENT 'Approved original assignee SHA-256',
                    source_format           VARCHAR(32) NOT NULL COMMENT 'Approved source format',
                    source_shape            VARCHAR(32) NOT NULL COMMENT 'Approved source element shape',
                    source_ordinal          INT NOT NULL COMMENT 'Approved source element ordinal',
                    source_agent_id         VARCHAR(100) DEFAULT NULL COMMENT 'Approved parsed source Agent ID',
                    canonical_agent_id      VARCHAR(100) DEFAULT NULL COMMENT 'Approved canonical Agent ID',
                    resolution_status       VARCHAR(64) NOT NULL COMMENT 'Approved row resolution',
                    task_resolution_status  VARCHAR(32) NOT NULL COMMENT 'Approved task resolution',
                    approved_operator       VARCHAR(100) NOT NULL COMMENT 'Operator/ticket that approved this manifest',
                    approved_at             BIGINT NOT NULL COMMENT 'Approval time',
                    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_backfill_manifest_row (report_sha256, manifest_row_key),
                    KEY idx_task_backfill_manifest_meta (report_sha256, meta_id, source_ordinal, manifest_row_key),
                    KEY idx_task_backfill_manifest_resolution
                        (report_sha256, task_resolution_status, resolution_status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable rows of a sealed B09 canonical manifest'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_backfill_run (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    run_id                  CHAR(36) NOT NULL COMMENT 'Apply run UUID',
                    report_sha256           CHAR(64) NOT NULL COMMENT 'Approved canonical manifest digest',
                    operator                VARCHAR(100) NOT NULL COMMENT 'Approved migration operator/ticket',
                    manifest_row_count      BIGINT NOT NULL COMMENT 'Rows matched against sealed manifest',
                    issue_row_count         BIGINT NOT NULL DEFAULT 0 COMMENT 'Issue observations in this run',
                    member_insert_count     BIGINT NOT NULL DEFAULT 0 COMMENT 'Members inserted in this run',
                    work_item_insert_count  BIGINT NOT NULL DEFAULT 0 COMMENT 'Work items inserted in this run',
                    started_at              BIGINT NOT NULL COMMENT 'Run start time',
                    completed_at            BIGINT NOT NULL COMMENT 'Run commit time',
                    run_status              VARCHAR(20) NOT NULL COMMENT 'SUCCEEDED only, failures roll back',
                    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_backfill_run_id (run_id),
                    KEY idx_task_backfill_run_report (report_sha256, completed_at, id),
                    KEY idx_task_backfill_run_operator (operator, completed_at, id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Immutable successful B09 apply run audit'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_request (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    request_id          VARCHAR(100) NOT NULL COMMENT 'Stable request ID',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    work_item_id        VARCHAR(100) DEFAULT NULL COMMENT 'Related work item ID',
                    requester_agent_id  VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical requester agentId',
                    target_type         VARCHAR(20) NOT NULL COMMENT 'agent/role/user/system',
                    target_id           VARCHAR(100) NOT NULL COMMENT 'Canonical agentId, role, user or system target',
                    request_type        VARCHAR(30) NOT NULL COMMENT 'help/clarification/dependency/review/resource/reassignment/approval',
                    status              VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/acknowledged/resolved/rejected/cancelled',
                    priority            INT NOT NULL DEFAULT 0 COMMENT 'Higher value means higher priority',
                    title               VARCHAR(255) NOT NULL COMMENT 'Request title',
                    description         TEXT NOT NULL COMMENT 'Request details',
                    response_json       MEDIUMTEXT COMMENT 'Structured response JSON',
                    due_at              BIGINT DEFAULT NULL COMMENT 'Requested response deadline',
                    acknowledged_at     BIGINT DEFAULT NULL COMMENT 'Acknowledgement time',
                    resolved_at         BIGINT DEFAULT NULL COMMENT 'Resolution time',
                    version             BIGINT NOT NULL DEFAULT 0 COMMENT 'Optimistic lock version',
                    tenant_id           VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
                    client_id           VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_task_request_scope (tenant_id, client_id, request_id),
                    KEY idx_task_request_task_status (tenant_id, client_id, task_id, status, priority, create_time),
                    KEY idx_task_request_target_status (tenant_id, client_id, target_type, target_id, status, due_at),
                    KEY idx_task_request_work_item (tenant_id, client_id, work_item_id, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped Agent collaboration requests'
                """);

        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_artifact (
                    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    artifact_id             VARCHAR(100) NOT NULL COMMENT 'Stable logical artifact ID',
                    task_id                 VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    work_item_id            VARCHAR(100) DEFAULT NULL COMMENT 'Related work item ID',
                    producer_agent_id       VARCHAR(100) NOT NULL COMMENT 'ADR-001 canonical producer agentId',
                    artifact_type           VARCHAR(30) NOT NULL COMMENT 'summary/document/patch/commit/test_report/analysis/dataset/link',
                    title                   VARCHAR(255) NOT NULL COMMENT 'Artifact title',
                    content                 MEDIUMTEXT COMMENT 'Inline artifact content',
                    storage_uri             VARCHAR(1000) DEFAULT NULL COMMENT 'External large object location',
                    content_hash            VARCHAR(128) DEFAULT NULL COMMENT 'Content integrity hash',
                    artifact_version        INT NOT NULL DEFAULT 1 COMMENT 'Logical artifact version',
                    visibility              VARCHAR(20) NOT NULL DEFAULT 'task_members' COMMENT 'task_members/reviewer/private',
                    metadata_json           TEXT COMMENT 'Artifact metadata JSON',
                    created_at              BIGINT NOT NULL COMMENT 'Artifact publication time',
                    tenant_id               VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
                    client_id               VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
                    create_time             BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time             BIGINT DEFAULT NULL COMMENT 'Update time',
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_artifact_version (tenant_id, client_id, artifact_id, artifact_version),
                    KEY idx_artifact_task_created (tenant_id, client_id, task_id, created_at),
                    KEY idx_artifact_work_item (tenant_id, client_id, work_item_id, artifact_type, created_at),
                    KEY idx_artifact_producer (tenant_id, client_id, producer_agent_id, created_at),
                    KEY idx_artifact_hash (tenant_id, client_id, content_hash)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Scoped versioned Agent task artifacts'
                """);

        ensureRequiredIndex("agent_task_meta", "idx_agent_task_meta_scope_status", false,
                List.of("tenant_id", "client_id", "reward_status", "update_time", "id"),
                "CREATE INDEX idx_agent_task_meta_scope_status "
                        + "ON agent_task_meta (tenant_id, client_id, reward_status, update_time, id)");
        ensureRequiredIndex("agent_task_meta", "idx_agent_task_meta_scope_coordinator", false,
                List.of("tenant_id", "client_id", "coordinator_agent_id", "reward_status"),
                "CREATE INDEX idx_agent_task_meta_scope_coordinator "
                        + "ON agent_task_meta (tenant_id, client_id, coordinator_agent_id, reward_status)");

        ensureRequiredIndex("agent_task_member", "uk_task_member_scope", true,
                List.of("tenant_id", "client_id", "task_id", "agent_id"),
                "CREATE UNIQUE INDEX uk_task_member_scope "
                        + "ON agent_task_member (tenant_id, client_id, task_id, agent_id)");
        ensureRequiredIndex("agent_task_member", "idx_task_member_agent_status", false,
                List.of("tenant_id", "client_id", "agent_id", "member_status"),
                "CREATE INDEX idx_task_member_agent_status "
                        + "ON agent_task_member (tenant_id, client_id, agent_id, member_status)");
        ensureRequiredIndex("agent_task_member", "idx_task_member_task_status", false,
                List.of("tenant_id", "client_id", "task_id", "member_status"),
                "CREATE INDEX idx_task_member_task_status "
                        + "ON agent_task_member (tenant_id, client_id, task_id, member_status)");
        ensureRequiredIndex("agent_task_member", "idx_task_member_task_role", false,
                List.of("tenant_id", "client_id", "task_id", "member_role", "member_status"),
                "CREATE INDEX idx_task_member_task_role "
                        + "ON agent_task_member (tenant_id, client_id, task_id, member_role, member_status)");

        ensureRequiredIndex("agent_task_work_item", "uk_work_item_scope", true,
                List.of("tenant_id", "client_id", "work_item_id"),
                "CREATE UNIQUE INDEX uk_work_item_scope "
                        + "ON agent_task_work_item (tenant_id, client_id, work_item_id)");
        ensureRequiredIndex("agent_task_work_item", "idx_work_item_task_status", false,
                List.of("tenant_id", "client_id", "task_id", "status", "priority"),
                "CREATE INDEX idx_work_item_task_status "
                        + "ON agent_task_work_item (tenant_id, client_id, task_id, status, priority)");
        ensureRequiredIndex("agent_task_work_item", "idx_work_item_assignee_status", false,
                List.of("tenant_id", "client_id", "assignee_agent_id", "status", "lease_until"),
                "CREATE INDEX idx_work_item_assignee_status "
                        + "ON agent_task_work_item (tenant_id, client_id, assignee_agent_id, status, lease_until)");
        ensureRequiredIndex("agent_task_work_item", "idx_work_item_lease", false,
                List.of("status", "lease_until", "id"),
                "CREATE INDEX idx_work_item_lease ON agent_task_work_item (status, lease_until, id)");
        ensureRequiredIndex("agent_task_work_item", "idx_work_item_task_required", false,
                List.of("tenant_id", "client_id", "task_id", "required_item", "status"),
                "CREATE INDEX idx_work_item_task_required "
                        + "ON agent_task_work_item (tenant_id, client_id, task_id, required_item, status)");

        ensureRequiredIndex("agent_task_backfill_issue", "uk_task_backfill_issue_key", true,
                List.of("issue_key"),
                "CREATE UNIQUE INDEX uk_task_backfill_issue_key ON agent_task_backfill_issue (issue_key)");
        ensureRequiredIndex("agent_task_backfill_issue", "idx_task_backfill_issue_scope_task", false,
                List.of("tenant_id", "client_id", "task_id", "issue_code"),
                "CREATE INDEX idx_task_backfill_issue_scope_task "
                        + "ON agent_task_backfill_issue (tenant_id, client_id, task_id, issue_code)");
        ensureRequiredIndex("agent_task_backfill_issue", "idx_task_backfill_issue_code_seen", false,
                List.of("issue_code", "last_seen_at", "id"),
                "CREATE INDEX idx_task_backfill_issue_code_seen "
                        + "ON agent_task_backfill_issue (issue_code, last_seen_at, id)");

        ensureRequiredIndex("agent_task_backfill_manifest_batch",
                "uk_task_backfill_manifest_batch_digest", true,
                List.of("report_sha256"),
                "CREATE UNIQUE INDEX uk_task_backfill_manifest_batch_digest "
                        + "ON agent_task_backfill_manifest_batch (report_sha256)");
        ensureRequiredIndex("agent_task_backfill_manifest_batch",
                "idx_task_backfill_manifest_batch_status", false,
                List.of("seal_status", "approved_at", "id"),
                "CREATE INDEX idx_task_backfill_manifest_batch_status "
                        + "ON agent_task_backfill_manifest_batch (seal_status, approved_at, id)");

        ensureRequiredIndex("agent_task_backfill_manifest", "uk_task_backfill_manifest_row", true,
                List.of("report_sha256", "manifest_row_key"),
                "CREATE UNIQUE INDEX uk_task_backfill_manifest_row "
                        + "ON agent_task_backfill_manifest (report_sha256, manifest_row_key)");
        ensureRequiredIndex("agent_task_backfill_manifest", "idx_task_backfill_manifest_meta", false,
                List.of("report_sha256", "meta_id", "source_ordinal", "manifest_row_key"),
                "CREATE INDEX idx_task_backfill_manifest_meta ON agent_task_backfill_manifest "
                        + "(report_sha256, meta_id, source_ordinal, manifest_row_key)");
        ensureRequiredIndex("agent_task_backfill_manifest", "idx_task_backfill_manifest_resolution", false,
                List.of("report_sha256", "task_resolution_status", "resolution_status"),
                "CREATE INDEX idx_task_backfill_manifest_resolution ON agent_task_backfill_manifest "
                        + "(report_sha256, task_resolution_status, resolution_status)");

        ensureRequiredIndex("agent_task_backfill_run", "uk_task_backfill_run_id", true,
                List.of("run_id"),
                "CREATE UNIQUE INDEX uk_task_backfill_run_id ON agent_task_backfill_run (run_id)");
        ensureRequiredIndex("agent_task_backfill_run", "idx_task_backfill_run_report", false,
                List.of("report_sha256", "completed_at", "id"),
                "CREATE INDEX idx_task_backfill_run_report "
                        + "ON agent_task_backfill_run (report_sha256, completed_at, id)");
        ensureRequiredIndex("agent_task_backfill_run", "idx_task_backfill_run_operator", false,
                List.of("operator", "completed_at", "id"),
                "CREATE INDEX idx_task_backfill_run_operator "
                        + "ON agent_task_backfill_run (operator, completed_at, id)");

        ensureRequiredIndex("agent_task_request", "uk_task_request_scope", true,
                List.of("tenant_id", "client_id", "request_id"),
                "CREATE UNIQUE INDEX uk_task_request_scope "
                        + "ON agent_task_request (tenant_id, client_id, request_id)");
        ensureRequiredIndex("agent_task_request", "idx_task_request_task_status", false,
                List.of("tenant_id", "client_id", "task_id", "status", "priority", "create_time"),
                "CREATE INDEX idx_task_request_task_status "
                        + "ON agent_task_request (tenant_id, client_id, task_id, status, priority, create_time)");
        ensureRequiredIndex("agent_task_request", "idx_task_request_target_status", false,
                List.of("tenant_id", "client_id", "target_type", "target_id", "status", "due_at"),
                "CREATE INDEX idx_task_request_target_status "
                        + "ON agent_task_request (tenant_id, client_id, target_type, target_id, status, due_at)");
        ensureRequiredIndex("agent_task_request", "idx_task_request_work_item", false,
                List.of("tenant_id", "client_id", "work_item_id", "status"),
                "CREATE INDEX idx_task_request_work_item "
                        + "ON agent_task_request (tenant_id, client_id, work_item_id, status)");

        ensureRequiredIndex("agent_task_artifact", "uk_artifact_version", true,
                List.of("tenant_id", "client_id", "artifact_id", "artifact_version"),
                "CREATE UNIQUE INDEX uk_artifact_version "
                        + "ON agent_task_artifact (tenant_id, client_id, artifact_id, artifact_version)");
        ensureRequiredIndex("agent_task_artifact", "idx_artifact_task_created", false,
                List.of("tenant_id", "client_id", "task_id", "created_at"),
                "CREATE INDEX idx_artifact_task_created "
                        + "ON agent_task_artifact (tenant_id, client_id, task_id, created_at)");
        ensureRequiredIndex("agent_task_artifact", "idx_artifact_work_item", false,
                List.of("tenant_id", "client_id", "work_item_id", "artifact_type", "created_at"),
                "CREATE INDEX idx_artifact_work_item "
                        + "ON agent_task_artifact (tenant_id, client_id, work_item_id, artifact_type, created_at)");
        ensureRequiredIndex("agent_task_artifact", "idx_artifact_producer", false,
                List.of("tenant_id", "client_id", "producer_agent_id", "created_at"),
                "CREATE INDEX idx_artifact_producer "
                        + "ON agent_task_artifact (tenant_id, client_id, producer_agent_id, created_at)");
        ensureRequiredIndex("agent_task_artifact", "idx_artifact_hash", false,
                List.of("tenant_id", "client_id", "content_hash"),
                "CREATE INDEX idx_artifact_hash "
                        + "ON agent_task_artifact (tenant_id, client_id, content_hash)");

        if (!isH2Database()) {
            if (!backfillAuditAlreadyExists || bootstrapBackfillTriggers) {
                createBackfillAuditTriggers();
            }
            if (backfillAuditAlreadyExists) {
                validateBackfillAuditSchema();
                validateBackfillAuditTriggers();
            }
        }
    }

    private int identityProtectionTriggerCount() {
        return namedTriggerCount(List.of(
                "trg_identity_registry_immutable_update", "trg_identity_registry_no_delete",
                "trg_identity_alias_immutable_update", "trg_identity_alias_no_delete"));
    }

    private int backfillAuditTriggerCount() {
        return namedTriggerCount(List.of(
                "trg_task_backfill_issue_insert_guard", "trg_task_backfill_issue_update_guard",
                "trg_task_backfill_issue_no_delete", "trg_task_backfill_manifest_batch_insert_guard",
                "trg_task_backfill_manifest_batch_update_guard", "trg_task_backfill_manifest_batch_no_delete",
                "trg_task_backfill_manifest_insert_guard", "trg_task_backfill_manifest_no_update",
                "trg_task_backfill_manifest_no_delete", "trg_task_backfill_run_insert_guard",
                "trg_task_backfill_run_no_update", "trg_task_backfill_run_no_delete"));
    }

    private int namedTriggerCount(List<String> names) {
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.triggers "
                        + "WHERE trigger_schema = DATABASE() AND trigger_name IN (" + placeholders + ")",
                Integer.class, names.toArray());
        return count == null ? 0 : count;
    }

    private boolean tablesAreEmpty(String... tables) {
        for (String table : tables) {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            if (count == null || count != 0L) {
                return false;
            }
        }
        return true;
    }

    private void migrateTaskMetaScopedUniqueness() {
        ensureRequiredIndex("agent_task_meta", "uk_agent_task_meta_scope", true,
                List.of("tenant_id", "client_id", "task_id"),
                "CREATE UNIQUE INDEX uk_agent_task_meta_scope "
                        + "ON agent_task_meta (tenant_id, client_id, task_id)");
        for (String indexName : inspectSingleColumnUniqueIndexes("agent_task_meta", "task_id")) {
            if (!indexName.matches("[A-Za-z0-9_$]+")) {
                throw new IllegalStateException("Unsafe legacy agent_task_meta index name: " + indexName);
            }
            String dropSql = isH2Database()
                    ? "DROP INDEX " + indexName
                    : "ALTER TABLE agent_task_meta DROP INDEX `" + indexName + "`";
            jdbcTemplate.execute(dropSql);
        }
        if (!inspectSingleColumnUniqueIndexes("agent_task_meta", "task_id").isEmpty()) {
            throw new IllegalStateException(
                    "Legacy global UNIQUE(task_id) remains on agent_task_meta");
        }
    }

    private List<String> inspectSingleColumnUniqueIndexes(String table, String column) {
        if (jdbcTemplate.getDataSource() == null) {
            return List.of();
        }
        String sql = isH2Database() ? """
                SELECT INDEX_NAME
                FROM information_schema.index_columns
                WHERE table_schema = SCHEMA()
                  AND LOWER(table_name) = LOWER(?)
                  AND IS_UNIQUE = TRUE
                  AND LOWER(index_name) <> 'primary_key'
                GROUP BY INDEX_NAME
                HAVING COUNT(*) = 1 AND LOWER(MAX(COLUMN_NAME)) = LOWER(?)
                ORDER BY INDEX_NAME
                """ : """
                SELECT INDEX_NAME
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = ?
                  AND NON_UNIQUE = 0
                  AND index_name <> 'PRIMARY'
                GROUP BY INDEX_NAME
                HAVING COUNT(*) = 1 AND LOWER(MAX(COLUMN_NAME)) = LOWER(?)
                ORDER BY INDEX_NAME
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("INDEX_NAME"), table, column);
    }

    private void validateBackfillAuditSchema() {
        validateBackfillTableCollation("agent_task_backfill_issue");
        validateBackfillTableCollation("agent_task_backfill_manifest_batch");
        validateBackfillTableCollation("agent_task_backfill_manifest");
        validateBackfillTableCollation("agent_task_backfill_run");
        for (String table : List.of(
                "agent_task_backfill_issue", "agent_task_backfill_manifest_batch",
                "agent_task_backfill_manifest", "agent_task_backfill_run")) {
            validateBackfillPrimaryKey(table);
        }

        List<BackfillColumnExpectation> expected = List.of(
                new BackfillColumnExpectation("agent_task_backfill_issue", "id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "issue_key", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "meta_id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "task_id", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "source_hash", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "source_format", "varchar", "varchar(32)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "source_shape", "varchar", "varchar(32)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "source_ordinal", "int", "int", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "raw_assignee", "varchar", "varchar(100)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "source_agent_id", "varchar", "varchar(100)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "issue_code", "varchar", "varchar(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "issue_reason", "varchar", "varchar(1000)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "first_report_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "last_report_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "first_seen_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "last_seen_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "occurrence_count", "bigint", "bigint", false, "1", null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "last_operator", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "tenant_id", "varchar", "varchar(50)", true, "0", "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "client_id", "varchar", "varchar(50)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_issue", "create_time", "bigint", "bigint", true, null, null),
                new BackfillColumnExpectation("agent_task_backfill_issue", "update_time", "bigint", "bigint", true, null, null),

                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "report_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "manifest_row_count", "bigint", "bigint", false, "0", null),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "seal_status", "varchar", "varchar(16)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "approved_operator", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "approved_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "sealed_at", "bigint", "bigint", true, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest_batch", "create_time", "bigint", "bigint", true, null, null),

                new BackfillColumnExpectation("agent_task_backfill_manifest", "id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "report_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "manifest_row_key", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "manifest_row_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "meta_id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "task_id", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "tenant_id", "varchar", "varchar(50)", true, "0", "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "client_id", "varchar", "varchar(50)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "source_hash", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "source_format", "varchar", "varchar(32)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "source_shape", "varchar", "varchar(32)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "source_ordinal", "int", "int", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "source_agent_id", "varchar", "varchar(100)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "canonical_agent_id", "varchar", "varchar(100)", true, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "resolution_status", "varchar", "varchar(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "task_resolution_status", "varchar", "varchar(32)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "approved_operator", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "approved_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_manifest", "create_time", "bigint", "bigint", true, null, null),

                new BackfillColumnExpectation("agent_task_backfill_run", "id", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_run", "run_id", "char", "char(36)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_run", "report_sha256", "char", "char(64)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_run", "operator", "varchar", "varchar(100)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_run", "manifest_row_count", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_run", "issue_row_count", "bigint", "bigint", false, "0", null),
                new BackfillColumnExpectation("agent_task_backfill_run", "member_insert_count", "bigint", "bigint", false, "0", null),
                new BackfillColumnExpectation("agent_task_backfill_run", "work_item_insert_count", "bigint", "bigint", false, "0", null),
                new BackfillColumnExpectation("agent_task_backfill_run", "started_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_run", "completed_at", "bigint", "bigint", false, null, null),
                new BackfillColumnExpectation("agent_task_backfill_run", "run_status", "varchar", "varchar(20)", false, null, "utf8mb4_0900_bin"),
                new BackfillColumnExpectation("agent_task_backfill_run", "create_time", "bigint", "bigint", true, null, null));
        for (BackfillColumnExpectation column : expected) {
            validateBackfillColumn(column);
        }
    }

    void validateBackfillPrimaryKey(String table) {
        List<IndexColumn> primary = inspectRequiredIndex(table, "PRIMARY");
        boolean matches = primary != null && primary.size() == 1
                && primary.get(0).nonUnique() == 0
                && primary.get(0).sequence() == 1
                && primary.get(0).subPart() == null
                && "id".equalsIgnoreCase(primary.get(0).columnName());
        if (!matches) {
            throw new IllegalStateException("B09 audit table " + table
                    + " must have PRIMARY KEY (id)");
        }
    }

    void validateBackfillColumn(BackfillColumnExpectation expected) {
        List<BackfillColumnDefinition> actual = jdbcTemplate.query("""
                SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT,
                       COLLATION_NAME, EXTRA
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                """, (rs, rowNum) -> new BackfillColumnDefinition(
                rs.getString("DATA_TYPE"), rs.getString("COLUMN_TYPE"),
                "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")),
                rs.getString("COLUMN_DEFAULT"), rs.getString("COLLATION_NAME"),
                rs.getString("EXTRA")),
                expected.table(), expected.column());
        if (actual.size() != 1) {
            throw new IllegalStateException("B09 audit column " + expected.table() + "."
                    + expected.column() + " is missing or duplicated");
        }
        BackfillColumnDefinition column = actual.get(0);
        boolean matches = expected.dataType().equalsIgnoreCase(column.dataType())
                && normalizeSql(expected.columnType()).equals(normalizeSql(column.columnType()))
                && expected.nullable() == column.nullable()
                && java.util.Objects.equals(expected.defaultValue(), column.defaultValue())
                && (expected.collation() == null
                    ? column.collation() == null
                    : expected.collation().equalsIgnoreCase(column.collation()))
                && (!"id".equals(expected.column())
                    || "auto_increment".equalsIgnoreCase(column.extra()));
        if (!matches) {
            throw new IllegalStateException("B09 audit column " + expected.table() + "."
                    + expected.column()
                    + " has incompatible type/null/default/collation/auto_increment: " + column);
        }
    }

    private void validateBackfillTableCollation(String table) {
        String actual = jdbcTemplate.queryForObject("""
                SELECT TABLE_COLLATION FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = ?
                """, String.class, table);
        if (!"utf8mb4_0900_bin".equalsIgnoreCase(actual)) {
            throw new IllegalStateException("B09 audit table " + table
                    + " must use utf8mb4_0900_bin but was " + actual);
        }
    }

    void validateBackfillAuditTriggers() {
        java.util.Map<String, TriggerDefinition> expected = expectedBackfillAuditTriggers();
        List<TriggerDefinition> actual = jdbcTemplate.query("""
                SELECT TRIGGER_NAME, EVENT_OBJECT_TABLE, ACTION_TIMING,
                       EVENT_MANIPULATION, ACTION_STATEMENT
                FROM information_schema.triggers
                WHERE trigger_schema = DATABASE()
                  AND trigger_name IN (
                    'trg_task_backfill_manifest_batch_insert_guard',
                    'trg_task_backfill_manifest_batch_update_guard',
                    'trg_task_backfill_manifest_batch_no_delete',
                    'trg_task_backfill_manifest_insert_guard',
                    'trg_task_backfill_manifest_no_update',
                    'trg_task_backfill_manifest_no_delete',
                    'trg_task_backfill_issue_insert_guard',
                    'trg_task_backfill_issue_update_guard',
                    'trg_task_backfill_issue_no_delete',
                    'trg_task_backfill_run_insert_guard',
                    'trg_task_backfill_run_no_update',
                    'trg_task_backfill_run_no_delete')
                """, (rs, rowNum) -> new TriggerDefinition(
                rs.getString("TRIGGER_NAME"), rs.getString("EVENT_OBJECT_TABLE"),
                rs.getString("ACTION_TIMING"), rs.getString("EVENT_MANIPULATION"),
                rs.getString("ACTION_STATEMENT")));
        if (actual.size() != expected.size()) {
            throw new IllegalStateException("B09 requires twelve exact procedure-gated audit triggers");
        }
        for (TriggerDefinition trigger : actual) {
            TriggerDefinition required = expected.get(trigger.name());
            if (required == null
                    || !required.table().equalsIgnoreCase(trigger.table())
                    || !required.timing().equalsIgnoreCase(trigger.timing())
                    || !required.event().equalsIgnoreCase(trigger.event())
                    || !normalizeSql(required.statement()).equals(normalizeSql(trigger.statement()))) {
                throw new IllegalStateException("B09 audit trigger " + trigger.name()
                        + " has an incompatible table or definition");
            }
        }
    }

    private void createBackfillAuditTriggers() {
        for (TriggerDefinition trigger : expectedBackfillAuditTriggers().values()) {
            jdbcTemplate.execute("CREATE TRIGGER " + trigger.name() + " " + trigger.timing()
                    + " " + trigger.event() + " ON " + trigger.table()
                    + " FOR EACH ROW " + trigger.statement());
        }
    }

    private java.util.Map<String, TriggerDefinition> expectedBackfillAuditTriggers() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("trg_task_backfill_manifest_batch_insert_guard", new TriggerDefinition(
                        "trg_task_backfill_manifest_batch_insert_guard", "agent_task_backfill_manifest_batch",
                        "BEFORE", "INSERT", """
                        BEGIN
                            IF NEW.report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR NEW.approved_operator IS NULL
                               OR CHAR_LENGTH(NEW.approved_operator) NOT BETWEEN 1 AND 100
                               OR BINARY NEW.approved_operator <> BINARY TRIM(NEW.approved_operator)
                               OR REGEXP_LIKE(NEW.approved_operator, '[[:cntrl:]]', 'c')
                               OR NOT (
                                   (BINARY NEW.seal_status = BINARY 'LOADING'
                                    AND NEW.manifest_row_count = 0 AND NEW.sealed_at IS NULL
                                    AND NEW.approved_at > 0 AND NEW.create_time = NEW.approved_at)
                                   OR
                                   (BINARY NEW.seal_status = BINARY 'LEGACY_UNSEALED'
                                    AND NEW.manifest_row_count > 0 AND NEW.sealed_at IS NULL
                                    AND NEW.approved_at > 0)
                               ) THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest batch insert requires exact loading or quarantined legacy state';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_manifest_batch_update_guard", new TriggerDefinition(
                        "trg_task_backfill_manifest_batch_update_guard", "agent_task_backfill_manifest_batch",
                        "BEFORE", "UPDATE", """
                        BEGIN
                            IF NOT (
                                BINARY OLD.seal_status = BINARY 'LOADING'
                                AND BINARY NEW.seal_status = BINARY 'SEALED'
                                AND OLD.manifest_row_count = 0
                                AND NEW.manifest_row_count > 0
                                AND NEW.sealed_at IS NOT NULL
                                AND BINARY NEW.report_sha256 = BINARY OLD.report_sha256
                                AND BINARY NEW.approved_operator = BINARY OLD.approved_operator
                                AND NEW.approved_at = OLD.approved_at
                                AND NEW.create_time <=> OLD.create_time
                                AND (SELECT COUNT(*) FROM agent_task_backfill_manifest m
                                     WHERE BINARY m.report_sha256 = BINARY OLD.report_sha256
                                       AND BINARY m.approved_operator = BINARY OLD.approved_operator
                                       AND m.approved_at = OLD.approved_at) = NEW.manifest_row_count
                            ) THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest batch permits only exact LOADING to SEALED transition';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_manifest_batch_no_delete", new TriggerDefinition(
                        "trg_task_backfill_manifest_batch_no_delete", "agent_task_backfill_manifest_batch",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest batch cannot be deleted';
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_manifest_insert_guard", new TriggerDefinition(
                        "trg_task_backfill_manifest_insert_guard", "agent_task_backfill_manifest",
                        "BEFORE", "INSERT", """
                        BEGIN
                            IF NEW.manifest_row_key NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR NEW.manifest_row_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR NEW.source_hash NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b
                                   WHERE BINARY b.report_sha256 = BINARY NEW.report_sha256
                                     AND BINARY b.seal_status = BINARY 'LOADING'
                                     AND b.manifest_row_count = 0 AND b.sealed_at IS NULL
                                     AND BINARY b.approved_operator = BINARY NEW.approved_operator
                                     AND b.approved_at = NEW.approved_at
                                     AND b.create_time <=> NEW.create_time) <> 1 THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 manifest rows require the matching procedure-owned loading batch';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_manifest_no_update", new TriggerDefinition(
                        "trg_task_backfill_manifest_no_update", "agent_task_backfill_manifest",
                        "BEFORE", "UPDATE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approved manifest is immutable';
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_manifest_no_delete", new TriggerDefinition(
                        "trg_task_backfill_manifest_no_delete", "agent_task_backfill_manifest",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 approved manifest cannot be deleted';
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_issue_insert_guard", new TriggerDefinition(
                        "trg_task_backfill_issue_insert_guard", "agent_task_backfill_issue",
                        "BEFORE", "INSERT", """
                        BEGIN
                            IF NEW.issue_key NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR NEW.first_report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR BINARY NEW.first_report_sha256 <> BINARY NEW.last_report_sha256
                               OR NEW.occurrence_count <> 1
                               OR NEW.first_seen_at <> NEW.last_seen_at
                               OR NEW.create_time <> NEW.first_seen_at
                               OR NEW.update_time <> NEW.last_seen_at
                               OR (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b
                                   WHERE BINARY b.report_sha256 = BINARY NEW.last_report_sha256
                                     AND BINARY b.seal_status = BINARY 'SEALED'
                                     AND BINARY b.approved_operator = BINARY NEW.last_operator) <> 1 THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 issue insert requires a matching sealed approval';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_issue_update_guard", new TriggerDefinition(
                        "trg_task_backfill_issue_update_guard", "agent_task_backfill_issue",
                        "BEFORE", "UPDATE", """
                        BEGIN
                            IF NOT (
                                NEW.id = OLD.id
                                AND BINARY NEW.issue_key = BINARY OLD.issue_key
                                AND NEW.meta_id = OLD.meta_id
                                AND BINARY NEW.task_id = BINARY OLD.task_id
                                AND BINARY NEW.source_hash = BINARY OLD.source_hash
                                AND BINARY NEW.source_format = BINARY OLD.source_format
                                AND BINARY NEW.source_shape = BINARY OLD.source_shape
                                AND NEW.source_ordinal = OLD.source_ordinal
                                AND BINARY NEW.raw_assignee <=> BINARY OLD.raw_assignee
                                AND BINARY NEW.source_agent_id <=> BINARY OLD.source_agent_id
                                AND BINARY NEW.issue_code = BINARY OLD.issue_code
                                AND BINARY NEW.issue_reason = BINARY OLD.issue_reason
                                AND BINARY NEW.first_report_sha256 = BINARY OLD.first_report_sha256
                                AND NEW.first_seen_at = OLD.first_seen_at
                                AND NEW.occurrence_count = OLD.occurrence_count + 1
                                AND NEW.last_seen_at >= OLD.last_seen_at
                                AND BINARY NEW.tenant_id <=> BINARY OLD.tenant_id
                                AND BINARY NEW.client_id <=> BINARY OLD.client_id
                                AND NEW.create_time <=> OLD.create_time
                                AND NEW.update_time = NEW.last_seen_at
                                AND (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b
                                     WHERE BINARY b.report_sha256 = BINARY NEW.last_report_sha256
                                       AND BINARY b.seal_status = BINARY 'SEALED'
                                       AND BINARY b.approved_operator = BINARY NEW.last_operator) = 1
                            ) THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 issue audit permits only one sealed apply observation increment';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_issue_no_delete", new TriggerDefinition(
                        "trg_task_backfill_issue_no_delete", "agent_task_backfill_issue",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 issue audit cannot be deleted';
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_run_insert_guard", new TriggerDefinition(
                        "trg_task_backfill_run_insert_guard", "agent_task_backfill_run",
                        "BEFORE", "INSERT", """
                        BEGIN
                            IF NEW.run_id IS NULL OR CHAR_LENGTH(NEW.run_id) <> 36
                               OR NEW.report_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
                               OR BINARY NEW.run_status <> BINARY 'SUCCEEDED'
                               OR NEW.manifest_row_count <= 0 OR NEW.issue_row_count < 0
                               OR NEW.member_insert_count < 0 OR NEW.work_item_insert_count < 0
                               OR NEW.completed_at < NEW.started_at OR NEW.create_time <> NEW.completed_at
                               OR (SELECT COUNT(*) FROM agent_task_backfill_manifest_batch b
                                   WHERE BINARY b.report_sha256 = BINARY NEW.report_sha256
                                     AND BINARY b.seal_status = BINARY 'SEALED'
                                     AND BINARY b.approved_operator = BINARY NEW.operator
                                     AND b.manifest_row_count = NEW.manifest_row_count) <> 1
                               OR (SELECT COUNT(*) FROM agent_task_backfill_manifest m
                                   WHERE BINARY m.report_sha256 = BINARY NEW.report_sha256)
                                  <> NEW.manifest_row_count THEN
                                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 run insert requires a complete matching sealed approval';
                            END IF;
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_run_no_update", new TriggerDefinition(
                        "trg_task_backfill_run_no_update", "agent_task_backfill_run",
                        "BEFORE", "UPDATE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 run audit is immutable';
                        END
                        """)),
                java.util.Map.entry("trg_task_backfill_run_no_delete", new TriggerDefinition(
                        "trg_task_backfill_run_no_delete", "agent_task_backfill_run",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'B09 run audit cannot be deleted';
                        END
                        """)));
    }

    private boolean isH2Database() {
        if (h2Database != null) {
            return h2Database;
        }
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            h2Database = false;
            return h2Database;
        }
        try (Connection connection = dataSource.getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            h2Database = productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
            return h2Database;
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to determine database dialect for agent schema initialization", e);
        }
    }

    private void ensureTaskNoteTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_task_note (
                    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
                    task_id             VARCHAR(100) NOT NULL COMMENT 'Task ID',
                    author_id           VARCHAR(100) DEFAULT NULL COMMENT 'Author ID',
                    author_type         VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user/agent/system',
                    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary' COMMENT 'summary/report/meeting/system',
                    content             TEXT NOT NULL COMMENT 'Note content',
                    created_at          BIGINT NOT NULL COMMENT 'Note created time',
                    create_time         BIGINT DEFAULT NULL COMMENT 'Create time',
                    update_time         BIGINT DEFAULT NULL COMMENT 'Update time',
                    tenant_id           VARCHAR(50) DEFAULT '0' COMMENT 'Tenant ID',
                    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'Client ID',
                    PRIMARY KEY (id),
                    KEY idx_agent_task_note_task_id (task_id),
                    KEY idx_agent_task_note_created_at (created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent task notes'
                """);
    }

    private void ensureSceneTables() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_scene_state (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    scene_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    persona_code VARCHAR(50) NOT NULL,
                    behavior VARCHAR(50) NOT NULL,
                    origin_region_id VARCHAR(100) DEFAULT NULL,
                    target_region_id VARCHAR(100) NOT NULL,
                    related_type VARCHAR(50) DEFAULT NULL,
                    related_id VARCHAR(100) DEFAULT NULL,
                    phase VARCHAR(20) NOT NULL,
                    state_version BIGINT NOT NULL,
                    started_at BIGINT NOT NULL,
                    expected_arrival_at BIGINT DEFAULT NULL,
                    expires_at BIGINT DEFAULT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_scene_state_scope_agent (tenant_id, client_id, scene_id, agent_id),
                    KEY idx_agent_scene_state_scope_version (tenant_id, client_id, scene_id, state_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_scene_event (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    scene_id VARCHAR(100) NOT NULL,
                    scene_version BIGINT NOT NULL,
                    event_type VARCHAR(50) NOT NULL,
                    event_json TEXT NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_scene_event_scope_version (tenant_id, client_id, scene_id, scene_version),
                    KEY idx_agent_scene_event_scope_occurred (tenant_id, client_id, scene_id, occurred_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_scene_version (
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    scene_id VARCHAR(100) NOT NULL,
                    current_version BIGINT NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (tenant_id, client_id, scene_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS agent_scene_phase_report (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    scene_id VARCHAR(100) NOT NULL,
                    report_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    state_version BIGINT NOT NULL,
                    phase VARCHAR(20) NOT NULL,
                    region_id VARCHAR(100) NOT NULL,
                    result VARCHAR(30) NOT NULL,
                    occurred_at BIGINT NOT NULL,
                    processed_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT DEFAULT NULL,
                    update_time BIGINT DEFAULT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_agent_scene_phase_report_scope_report
                        (tenant_id, client_id, scene_id, report_id),
                    KEY idx_agent_scene_phase_report_scope_agent_version
                        (tenant_id, client_id, scene_id, agent_id, state_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);

        ensureRequiredIndex("agent_scene_state", "uk_agent_scene_state_scope_agent", true,
                List.of("tenant_id", "client_id", "scene_id", "agent_id"),
                "CREATE UNIQUE INDEX uk_agent_scene_state_scope_agent "
                        + "ON agent_scene_state (tenant_id, client_id, scene_id, agent_id)");
        ensureRequiredIndex("agent_scene_state", "idx_agent_scene_state_scope_version", false,
                List.of("tenant_id", "client_id", "scene_id", "state_version"),
                "CREATE INDEX idx_agent_scene_state_scope_version "
                        + "ON agent_scene_state (tenant_id, client_id, scene_id, state_version)");
        ensureRequiredIndex("agent_scene_event", "uk_agent_scene_event_scope_version", true,
                List.of("tenant_id", "client_id", "scene_id", "scene_version"),
                "CREATE UNIQUE INDEX uk_agent_scene_event_scope_version "
                        + "ON agent_scene_event (tenant_id, client_id, scene_id, scene_version)");
        ensureRequiredIndex("agent_scene_event", "idx_agent_scene_event_scope_occurred", false,
                List.of("tenant_id", "client_id", "scene_id", "occurred_at"),
                "CREATE INDEX idx_agent_scene_event_scope_occurred "
                        + "ON agent_scene_event (tenant_id, client_id, scene_id, occurred_at)");
        ensureRequiredIndex("agent_scene_phase_report", "uk_agent_scene_phase_report_scope_report", true,
                List.of("tenant_id", "client_id", "scene_id", "report_id"),
                "CREATE UNIQUE INDEX uk_agent_scene_phase_report_scope_report "
                        + "ON agent_scene_phase_report (tenant_id, client_id, scene_id, report_id)");
        ensureRequiredIndex("agent_scene_phase_report", "idx_agent_scene_phase_report_scope_agent_version", false,
                List.of("tenant_id", "client_id", "scene_id", "agent_id", "state_version"),
                "CREATE INDEX idx_agent_scene_phase_report_scope_agent_version "
                        + "ON agent_scene_phase_report (tenant_id, client_id, scene_id, agent_id, state_version)");
        ensureRequiredIndex("agent_scene_version", "PRIMARY", true,
                List.of("tenant_id", "client_id", "scene_id"),
                "ALTER TABLE agent_scene_version ADD PRIMARY KEY (tenant_id, client_id, scene_id)");
    }

    private void seedWaterMarginPersonas() {
        if (isH2Database() && !h2TableExists("agent_persona")) {
            log.info("Skipping Water Margin persona seeds because H2 does not provide the optional agent_persona table");
            return;
        }
        for (PersonaSeed seed : PERSONAS) {
            jdbcTemplate.update("""
                    INSERT INTO agent_persona
                        (persona_code, rank_no, star_name, name, title, avatar, visual_config, abilities,
                         personality, speaking_style, background, power, intelligence, leadership, active,
                         system_agent, create_time, update_time)
                    SELECT ?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?
                    WHERE NOT EXISTS (
                        SELECT 1 FROM agent_persona WHERE persona_code = ?
                    )
                    """,
                    seed.code(), seed.rankNo(), seed.starName(), seed.name(), seed.title(),
                    visualConfig(seed.rankNo()), abilities(seed), personality(seed), speakingStyle(seed), background(seed),
                    score(seed.rankNo(), 72), score(109 - seed.rankNo(), 70), score(seed.rankNo(), 66),
                    "songjiang".equals(seed.code()), System.currentTimeMillis(), System.currentTimeMillis(), seed.code());
        }
    }

    private void addRequiredColumnIfMissing(String table, String column, String definition) {
        String catalogQuery = isH2Database()
                ? """
                  SELECT COUNT(*)
                  FROM information_schema.columns
                  WHERE table_schema = SCHEMA()
                    AND LOWER(table_name) = LOWER(?)
                    AND LOWER(column_name) = LOWER(?)
                  """
                : """
                  SELECT COUNT(*)
                  FROM information_schema.columns
                  WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                  """;
        Integer count = jdbcTemplate.queryForObject(catalogQuery, Integer.class, table, column);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
        }
    }

    private void addColumnIfMissing(String table, String column, String definition) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
                    """, Integer.class, table, column);
            if (count == null || count == 0) {
                jdbcTemplate.execute("ALTER TABLE " + table + " ADD COLUMN " + definition);
            }
        } catch (Exception e) {
            log.warn("Unable to ensure column {}.{}: {}", table, column, e.getMessage());
        }
    }

    private void addIndexIfMissing(String table, String indexName, String sql) {
        try {
            Integer count = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM information_schema.statistics
                    WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                    """, Integer.class, table, indexName);
            if (count == null || count == 0) {
                jdbcTemplate.execute(sql);
            }
        } catch (Exception e) {
            log.warn("Unable to ensure index {}.{}: {}", table, indexName, e.getMessage());
        }
    }

    private void ensureRequiredCheckConstraint(String table, String constraintName, String expression) {
        String catalogQuery = isH2Database()
                ? """
                  SELECT COUNT(*)
                  FROM information_schema.table_constraints
                  WHERE table_schema = SCHEMA()
                    AND LOWER(table_name) = LOWER(?)
                    AND LOWER(constraint_name) = LOWER(?)
                    AND constraint_type = 'CHECK'
                  """
                : """
                  SELECT COUNT(*)
                  FROM information_schema.table_constraints
                  WHERE constraint_schema = DATABASE() AND table_name = ?
                    AND constraint_name = ? AND constraint_type = 'CHECK'
                  """;
        Integer count = jdbcTemplate.queryForObject(catalogQuery, Integer.class, table, constraintName);
        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + constraintName
                    + " CHECK (" + expression + ")");
        }
    }

    private void ensureRequiredIndex(
            String table, String indexName, boolean unique, List<String> columns, String createSql) {
        List<IndexColumn> actual = inspectRequiredIndex(table, indexName);
        if (actual == null || actual.isEmpty()) {
            if (createSql == null || createSql.isBlank()) {
                throw new IllegalStateException("Required index " + table + "." + indexName
                        + " is missing");
            }
            jdbcTemplate.execute(createSql);
            return;
        }
        int expectedNonUnique = unique ? 0 : 1;
        boolean matches = actual.size() == columns.size();
        for (int i = 0; matches && i < actual.size(); i++) {
            IndexColumn part = actual.get(i);
            matches = part.nonUnique() == expectedNonUnique
                    && part.sequence() == i + 1
                    && part.subPart() == null
                    && columns.get(i).equals(part.columnName().toLowerCase(Locale.ROOT));
        }
        if (!matches) {
            throw new IllegalStateException("Required index " + table + "." + indexName
                    + " has an incompatible uniqueness or ordered column definition");
        }
    }

    private boolean h2TableExists(String table) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = SCHEMA() AND LOWER(table_name) = LOWER(?)
                """, Integer.class, table);
        return count != null && count > 0;
    }

    private List<IndexColumn> inspectRequiredIndex(String table, String indexName) {
        if (isH2Database()) {
            if ("PRIMARY".equals(indexName)) {
                return jdbcTemplate.query("""
                        SELECT 0 AS NON_UNIQUE, kcu.COLUMN_NAME,
                               kcu.ORDINAL_POSITION AS SEQ_IN_INDEX,
                               CAST(NULL AS INTEGER) AS SUB_PART
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                          ON tc.constraint_catalog = kcu.constraint_catalog
                         AND tc.constraint_schema = kcu.constraint_schema
                         AND tc.constraint_name = kcu.constraint_name
                        WHERE tc.table_schema = SCHEMA()
                          AND LOWER(tc.table_name) = LOWER(?)
                          AND tc.constraint_type = 'PRIMARY KEY'
                        ORDER BY kcu.ordinal_position
                        """, INDEX_COLUMN_MAPPER, table);
            }
            return jdbcTemplate.query("""
                    SELECT CASE WHEN IS_UNIQUE THEN 0 ELSE 1 END AS NON_UNIQUE,
                           COLUMN_NAME, ORDINAL_POSITION AS SEQ_IN_INDEX,
                           CAST(NULL AS INTEGER) AS SUB_PART
                    FROM information_schema.index_columns
                    WHERE table_schema = SCHEMA()
                      AND LOWER(table_name) = LOWER(?)
                      AND LOWER(index_name) = LOWER(?)
                    ORDER BY ordinal_position
                    """, INDEX_COLUMN_MAPPER, table, indexName);
        }
        return jdbcTemplate.query("""
                SELECT NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX, SUB_PART
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                ORDER BY SEQ_IN_INDEX
                """, INDEX_COLUMN_MAPPER, table, indexName);
    }

    private static final RowMapper<IndexColumn> INDEX_COLUMN_MAPPER = (rs, rowNum) ->
            new IndexColumn(
                    rs.getInt("NON_UNIQUE"),
                    rs.getString("COLUMN_NAME"),
                    rs.getInt("SEQ_IN_INDEX"),
                    rs.getObject("SUB_PART") == null ? null : rs.getInt("SUB_PART"));

    static record IndexColumn(int nonUnique, String columnName, int sequence, Integer subPart) {}

    private String visualConfig(int rankNo) {
        int x = (rankNo - 1) % 6;
        int y = (rankNo - 1) / 6;
        return "{\"gridX\":" + x + ",\"gridY\":" + y + "}";
    }

    private String abilities(PersonaSeed seed) {
        if ("songjiang".equals(seed.code())) {
            return "[\"coordination\",\"dispatch\",\"planning\",\"briefing\",\"task_management\"]";
        }
        if (seed.rankNo() <= 36) {
            return "[\"strategy\",\"execution\",\"battle\"]";
        }
        return "[\"support\",\"execution\",\"scouting\"]";
    }

    private String personality(PersonaSeed seed) {
        return seed.title() + "，梁山第" + seed.rankNo() + "位好汉。";
    }

    private String speakingStyle(PersonaSeed seed) {
        return seed.rankNo() <= 36 ? "沉稳果决，重义守信。" : "直率利落，听令而行。";
    }

    private String background(PersonaSeed seed) {
        return seed.starName() + "，" + seed.name() + "，绰号" + seed.title() + "。";
    }

    private int score(int value, int base) {
        return Math.max(35, Math.min(98, base + (value % 24)));
    }

    private record PersonaSeed(String code, int rankNo, String starName, String name, String title) {
    }

    private static final PersonaSeed[] PERSONAS = new PersonaSeed[] {
            new PersonaSeed("songjiang", 1, "天魁星", "宋江", "及时雨"),
            new PersonaSeed("lujunyi", 2, "天罡星", "卢俊义", "玉麒麟"),
            new PersonaSeed("wuyong", 3, "天机星", "吴用", "智多星"),
            new PersonaSeed("gongsunsheng", 4, "天闲星", "公孙胜", "入云龙"),
            new PersonaSeed("guansheng", 5, "天勇星", "关胜", "大刀"),
            new PersonaSeed("linchong", 6, "天雄星", "林冲", "豹子头"),
            new PersonaSeed("qinming", 7, "天猛星", "秦明", "霹雳火"),
            new PersonaSeed("huyanzhuo", 8, "天威星", "呼延灼", "双鞭"),
            new PersonaSeed("huarong", 9, "天英星", "花荣", "小李广"),
            new PersonaSeed("chaijin", 10, "天贵星", "柴进", "小旋风"),
            new PersonaSeed("liying", 11, "天富星", "李应", "扑天雕"),
            new PersonaSeed("zhutong", 12, "天满星", "朱仝", "美髯公"),
            new PersonaSeed("luzhishen", 13, "天孤星", "鲁智深", "花和尚"),
            new PersonaSeed("wusong", 14, "天伤星", "武松", "行者"),
            new PersonaSeed("dongping", 15, "天立星", "董平", "双枪将"),
            new PersonaSeed("zhangqing", 16, "天捷星", "张清", "没羽箭"),
            new PersonaSeed("yangzhi", 17, "天暗星", "杨志", "青面兽"),
            new PersonaSeed("xuning", 18, "天佑星", "徐宁", "金枪手"),
            new PersonaSeed("suochao", 19, "天空星", "索超", "急先锋"),
            new PersonaSeed("daizong", 20, "天速星", "戴宗", "神行太保"),
            new PersonaSeed("liutang", 21, "天异星", "刘唐", "赤发鬼"),
            new PersonaSeed("likui", 22, "天杀星", "李逵", "黑旋风"),
            new PersonaSeed("shijin", 23, "天微星", "史进", "九纹龙"),
            new PersonaSeed("muhong", 24, "天究星", "穆弘", "没遮拦"),
            new PersonaSeed("leiheng", 25, "天退星", "雷横", "插翅虎"),
            new PersonaSeed("lijun", 26, "天寿星", "李俊", "混江龙"),
            new PersonaSeed("ruanxiaoer", 27, "天剑星", "阮小二", "立地太岁"),
            new PersonaSeed("zhangheng", 28, "天平星", "张横", "船火儿"),
            new PersonaSeed("ruanxiaowu", 29, "天罪星", "阮小五", "短命二郎"),
            new PersonaSeed("zhangshun", 30, "天损星", "张顺", "浪里白条"),
            new PersonaSeed("ruanxiaoqi", 31, "天败星", "阮小七", "活阎罗"),
            new PersonaSeed("yangxiong", 32, "天牢星", "杨雄", "病关索"),
            new PersonaSeed("shixiu", 33, "天慧星", "石秀", "拼命三郎"),
            new PersonaSeed("xiezhen", 34, "天暴星", "解珍", "两头蛇"),
            new PersonaSeed("xiebao", 35, "天哭星", "解宝", "双尾蝎"),
            new PersonaSeed("yanqing", 36, "天巧星", "燕青", "浪子"),
            new PersonaSeed("zhuwu", 37, "地魁星", "朱武", "神机军师"),
            new PersonaSeed("huangxin", 38, "地煞星", "黄信", "镇三山"),
            new PersonaSeed("sunli", 39, "地勇星", "孙立", "病尉迟"),
            new PersonaSeed("xuanzan", 40, "地杰星", "宣赞", "丑郡马"),
            new PersonaSeed("haosiwen", 41, "地雄星", "郝思文", "井木犴"),
            new PersonaSeed("hantao", 42, "地威星", "韩滔", "百胜将"),
            new PersonaSeed("pengqi", 43, "地英星", "彭玘", "天目将"),
            new PersonaSeed("shantinggui", 44, "地奇星", "单廷珪", "圣水将"),
            new PersonaSeed("weidingguo", 45, "地猛星", "魏定国", "神火将"),
            new PersonaSeed("xiaorang", 46, "地文星", "萧让", "圣手书生"),
            new PersonaSeed("peixuan", 47, "地正星", "裴宣", "铁面孔目"),
            new PersonaSeed("oupeng", 48, "地阔星", "欧鹏", "摩云金翅"),
            new PersonaSeed("dengfei", 49, "地阖星", "邓飞", "火眼狻猊"),
            new PersonaSeed("yanshun", 50, "地强星", "燕顺", "锦毛虎"),
            new PersonaSeed("yanglin", 51, "地暗星", "杨林", "锦豹子"),
            new PersonaSeed("lingzhen", 52, "地轴星", "凌振", "轰天雷"),
            new PersonaSeed("jiangjing", 53, "地会星", "蒋敬", "神算子"),
            new PersonaSeed("lvfang", 54, "地佐星", "吕方", "小温侯"),
            new PersonaSeed("guosheng", 55, "地佑星", "郭盛", "赛仁贵"),
            new PersonaSeed("andaoquan", 56, "地灵星", "安道全", "神医"),
            new PersonaSeed("huangfuduan", 57, "地兽星", "皇甫端", "紫髯伯"),
            new PersonaSeed("wangying", 58, "地微星", "王英", "矮脚虎"),
            new PersonaSeed("husanniang", 59, "地慧星", "扈三娘", "一丈青"),
            new PersonaSeed("baoxu", 60, "地暴星", "鲍旭", "丧门神"),
            new PersonaSeed("fanrui", 61, "地然星", "樊瑞", "混世魔王"),
            new PersonaSeed("kongming", 62, "地猖星", "孔明", "毛头星"),
            new PersonaSeed("kongliang", 63, "地狂星", "孔亮", "独火星"),
            new PersonaSeed("xiangchong", 64, "地飞星", "项充", "八臂哪吒"),
            new PersonaSeed("ligun", 65, "地走星", "李衮", "飞天大圣"),
            new PersonaSeed("jindajian", 66, "地巧星", "金大坚", "玉臂匠"),
            new PersonaSeed("malin", 67, "地明星", "马麟", "铁笛仙"),
            new PersonaSeed("tongwei", 68, "地进星", "童威", "出洞蛟"),
            new PersonaSeed("tongmeng", 69, "地退星", "童猛", "翻江蜃"),
            new PersonaSeed("mengkang", 70, "地满星", "孟康", "玉幡竿"),
            new PersonaSeed("houjian", 71, "地遂星", "侯健", "通臂猿"),
            new PersonaSeed("chenda", 72, "地周星", "陈达", "跳涧虎"),
            new PersonaSeed("yangchun", 73, "地隐星", "杨春", "白花蛇"),
            new PersonaSeed("zhengtianshou", 74, "地异星", "郑天寿", "白面郎君"),
            new PersonaSeed("taozongwang", 75, "地理星", "陶宗旺", "九尾龟"),
            new PersonaSeed("songqing", 76, "地俊星", "宋清", "铁扇子"),
            new PersonaSeed("yuehe", 77, "地乐星", "乐和", "铁叫子"),
            new PersonaSeed("gongwang", 78, "地捷星", "龚旺", "花项虎"),
            new PersonaSeed("dingdesun", 79, "地速星", "丁得孙", "中箭虎"),
            new PersonaSeed("muchun", 80, "地镇星", "穆春", "小遮拦"),
            new PersonaSeed("caozheng", 81, "地稽星", "曹正", "操刀鬼"),
            new PersonaSeed("songwan", 82, "地魔星", "宋万", "云里金刚"),
            new PersonaSeed("duqian", 83, "地妖星", "杜迁", "摸着天"),
            new PersonaSeed("xueyong", 84, "地幽星", "薛永", "病大虫"),
            new PersonaSeed("shien", 85, "地伏星", "施恩", "金眼彪"),
            new PersonaSeed("lizhong", 86, "地僻星", "李忠", "打虎将"),
            new PersonaSeed("zhoutong", 87, "地空星", "周通", "小霸王"),
            new PersonaSeed("tanglong", 88, "地孤星", "汤隆", "金钱豹子"),
            new PersonaSeed("duxing", 89, "地全星", "杜兴", "鬼脸儿"),
            new PersonaSeed("zouyuan", 90, "地短星", "邹渊", "出林龙"),
            new PersonaSeed("zourun", 91, "地角星", "邹润", "独角龙"),
            new PersonaSeed("zhugui", 92, "地囚星", "朱贵", "旱地忽律"),
            new PersonaSeed("zhufu", 93, "地藏星", "朱富", "笑面虎"),
            new PersonaSeed("caifu", 94, "地平星", "蔡福", "铁臂膊"),
            new PersonaSeed("caiqing", 95, "地损星", "蔡庆", "一枝花"),
            new PersonaSeed("lili", 96, "地奴星", "李立", "催命判官"),
            new PersonaSeed("liyun", 97, "地察星", "李云", "青眼虎"),
            new PersonaSeed("jiaoting", 98, "地恶星", "焦挺", "没面目"),
            new PersonaSeed("shiyong", 99, "地丑星", "石勇", "石将军"),
            new PersonaSeed("sunxin", 100, "地数星", "孙新", "小尉迟"),
            new PersonaSeed("gudasao", 101, "地阴星", "顾大嫂", "母大虫"),
            new PersonaSeed("zhangqing_gardener", 102, "地刑星", "张青", "菜园子"),
            new PersonaSeed("sunerniang", 103, "地壮星", "孙二娘", "母夜叉"),
            new PersonaSeed("wangdingliu", 104, "地劣星", "王定六", "活闪婆"),
            new PersonaSeed("yubaosi", 105, "地健星", "郁保四", "险道神"),
            new PersonaSeed("baisheng", 106, "地耗星", "白胜", "白日鼠"),
            new PersonaSeed("shiqian", 107, "地贼星", "时迁", "鼓上蚤"),
            new PersonaSeed("duanjingzhu", 108, "地狗星", "段景住", "金毛犬")
    };
}
