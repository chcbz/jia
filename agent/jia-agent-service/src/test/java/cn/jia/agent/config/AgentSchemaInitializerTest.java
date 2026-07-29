package cn.jia.agent.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSchemaInitializerTest extends BaseMockTest {
    @Mock
    JdbcTemplate jdbcTemplate;

    @Test
    void schemaDeclaresScopedSemanticSceneTablesWithoutRuntimeGeometryOrSecrets() throws IOException {
        String schema;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/schema.sql")) {
            assertNotNull(input);
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }

        assertTrue(schema.contains("create table if not exists agent_scene_state"));
        assertTrue(schema.contains("unique key uk_agent_scene_state_scope_agent (tenant_id, client_id, scene_id, agent_id)"));
        assertTrue(schema.contains("key idx_agent_scene_state_scope_version (tenant_id, client_id, scene_id, state_version)"));
        assertTrue(schema.contains("create table if not exists agent_scene_event"));
        assertTrue(schema.contains("unique key uk_agent_scene_event_scope_version (tenant_id, client_id, scene_id, scene_version)"));
        assertTrue(schema.contains("create table if not exists agent_scene_phase_report"));
        assertTrue(schema.contains("unique key uk_agent_scene_phase_report_scope_report (tenant_id, client_id, scene_id, report_id)"));
        assertTrue(schema.contains("create table if not exists agent_scene_version"));
        assertTrue(schema.contains("primary key (tenant_id, client_id, scene_id)"));

        for (String table : Set.of(
                "agent_scene_state", "agent_scene_event", "agent_scene_phase_report", "agent_scene_version")) {
            String definition = tableDefinition(schema, table);
            String compact = definition.replaceAll("\\s+", " ");
            assertTrue(compact.contains("tenant_id varchar(50) not null"), table);
            assertTrue(compact.contains("client_id varchar(50) not null"), table);
            assertTrue(compact.contains("scene_id varchar(100) not null"), table);
            assertFalse(definition.matches("(?s).*\\b(x|y|path|coordinates?|frame|frame_index|token|credential|model_response)\\b.*"), table);
        }
        assertEquals(Set.of(
                        "tenant_id", "client_id", "scene_id", "current_version", "create_time", "update_time"),
                tableStructure(tableDefinition(schema, "agent_scene_version")).columns().keySet());
    }

    @Test
    void runCreatesTaskNoteTableIfMissing() {
        AgentSchemaInitializer initializer = new AgentSchemaInitializer(jdbcTemplate);

        initializer.afterPropertiesSet();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());
        String sql = String.join("\n", sqlCaptor.getAllValues());
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_note"));
        assertTrue(sql.contains("idx_agent_task_note_task_id"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_persona_binding"));
        assertTrue(sql.contains("uk_agent_binding_active_persona"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_identity_registry"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_identity_alias"));
        assertTrue(sql.contains("uk_identity_alias_active"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_state"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_event"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_phase_report"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_version"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_member"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_work_item"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_request"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_artifact"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_backfill_issue"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest_batch"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_backfill_manifest"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_backfill_run"));
        assertTrue(sql.contains("uk_task_backfill_issue_key"));
        assertTrue(sql.contains("uk_task_backfill_manifest_row"));
        assertTrue(sql.contains("uk_task_backfill_run_id"));
        verify(jdbcTemplate, atLeastOnce()).update(any(String.class), any(Object[].class));
    }

    @Test
    void initializerAddsAllRequiredTaskMetaCollaborationColumns() {
        AgentSchemaInitializer initializer = new AgentSchemaInitializer(jdbcTemplate);

        initializer.afterPropertiesSet();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).execute(sqlCaptor.capture());
        Set<String> taskMetaAlterColumns = sqlCaptor.getAllValues().stream()
                .filter(sql -> sql.startsWith("ALTER TABLE agent_task_meta ADD COLUMN "))
                .map(sql -> sql.substring("ALTER TABLE agent_task_meta ADD COLUMN ".length()))
                .map(sql -> sql.substring(0, sql.indexOf(' ')))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "collaboration_mode", "risk_level", "max_agents", "coordinator_agent_id",
                "review_required", "task_version", "current_event_version"), taskMetaAlterColumns);
    }

    @Test
    void initializerAndSqlResourcesKeepCollaborationTableStructureInParity() throws IOException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        new AgentSchemaInitializer(template).afterPropertiesSet();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(captor.capture());
        List<String> initializerSql = captor.getAllValues().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .toList();
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/task-collaboration-schema.sql");

        for (String table : Set.of(
                "agent_task_member", "agent_task_work_item", "agent_task_request", "agent_task_artifact")) {
            String initializerDefinition = initializerSql.stream()
                    .filter(sql -> sql.contains("create table if not exists " + table))
                    .findFirst().orElseThrow();
            TableStructure expected = tableStructure(tableDefinition(schema, table));
            assertEquals(expected, tableStructure(tableDefinition(migration, table)), table + " migration");
            assertEquals(expected, tableStructure(initializerDefinition), table + " initializer");
        }
    }

    @Test
    void initializerSchemaAndBackfillMigrationKeepAuditTablesInParity() throws IOException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        new AgentSchemaInitializer(template).afterPropertiesSet();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(captor.capture());
        List<String> initializerSql = captor.getAllValues().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .toList();
        String schema = readResource("db/schema.sql");
        String auditMigration = readResource("db/task-collaboration-backfill-audit-schema.sql");

        for (String table : Set.of(
                "agent_task_backfill_issue", "agent_task_backfill_manifest_batch",
                "agent_task_backfill_manifest", "agent_task_backfill_run")) {
            String initializerDefinition = initializerSql.stream()
                    .filter(sql -> sql.contains("create table if not exists " + table))
                    .findFirst().orElseThrow();
            TableStructure expected = tableStructure(tableDefinition(schema, table));
            assertEquals(expected, tableStructure(tableDefinition(auditMigration, table)), table + " migration");
            assertEquals(expected, tableStructure(initializerDefinition), table + " initializer");
        }
    }

    @Test
    void initializerAndSchemaResourceKeepIdentityTablesInParity() throws IOException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        new AgentSchemaInitializer(template).afterPropertiesSet();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(captor.capture());
        List<String> initializerSql = captor.getAllValues().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .toList();
        String schema = readResource("db/schema.sql");

        for (String table : Set.of("agent_identity_registry", "agent_identity_alias")) {
            String initializerDefinition = initializerSql.stream()
                    .filter(sql -> sql.contains("create table if not exists " + table))
                    .findFirst().orElseThrow();
            assertEquals(normalizeSqlStructure(tableDefinition(schema, table)),
                    normalizeSqlStructure(initializerDefinition), table);
        }
    }

    @Test
    void incompatibleBackfillAuditColumnTypeLengthNullDefaultOrCollationFailsStartup() {
        List<AgentSchemaInitializer.BackfillColumnDefinition> incompatible = List.of(
                new AgentSchemaInitializer.BackfillColumnDefinition(
                        "varchar", "char(64)", false, null, "utf8mb4_0900_bin"),
                new AgentSchemaInitializer.BackfillColumnDefinition(
                        "char", "char(63)", false, null, "utf8mb4_0900_bin"),
                new AgentSchemaInitializer.BackfillColumnDefinition(
                        "char", "char(64)", true, null, "utf8mb4_0900_bin"),
                new AgentSchemaInitializer.BackfillColumnDefinition(
                        "char", "char(64)", false, "unexpected", "utf8mb4_0900_bin"),
                new AgentSchemaInitializer.BackfillColumnDefinition(
                        "char", "char(64)", false, null, "utf8mb4_0900_ai_ci"));

        for (AgentSchemaInitializer.BackfillColumnDefinition definition : incompatible) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new AgentSchemaInitializer(backfillColumnMismatchTemplate(definition))
                            .afterPropertiesSet());
            assertTrue(error.getMessage().contains("agent_task_backfill_issue.issue_key"),
                    error.getMessage());
            assertTrue(error.getMessage().contains("type/null/default/collation/auto_increment"),
                    error.getMessage());
        }
    }

    @Test
    void missingOrWrongBackfillPrimaryKeyFailsClosed() {
        List<List<AgentSchemaInitializer.IndexColumn>> incompatible = List.of(
                List.of(),
                List.of(new AgentSchemaInitializer.IndexColumn(0, "other_id", 1, null)),
                List.of(
                        new AgentSchemaInitializer.IndexColumn(0, "id", 1, null),
                        new AgentSchemaInitializer.IndexColumn(0, "other_id", 2, null)));

        for (List<AgentSchemaInitializer.IndexColumn> primary : incompatible) {
            JdbcTemplate template = new JdbcTemplate() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                    return sql.contains("information_schema.statistics")
                            ? (List<T>) primary : List.of();
                }
            };
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> new AgentSchemaInitializer(template)
                            .validateBackfillPrimaryKey("agent_task_backfill_issue"));
            assertTrue(error.getMessage().contains("PRIMARY KEY (id)"), error.getMessage());
        }
    }

    @Test
    void missingBackfillIdAutoIncrementFailsClosed() {
        JdbcTemplate template = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("information_schema.columns")) {
                    return (List<T>) List.of(new AgentSchemaInitializer.BackfillColumnDefinition(
                            "bigint", "bigint", false, null, null, ""));
                }
                return List.of();
            }
        };
        AgentSchemaInitializer.BackfillColumnExpectation id =
                new AgentSchemaInitializer.BackfillColumnExpectation(
                        "agent_task_backfill_issue", "id", "bigint", "bigint",
                        false, null, null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(template).validateBackfillColumn(id));
        assertTrue(error.getMessage().contains("agent_task_backfill_issue.id"), error.getMessage());
        assertTrue(error.getMessage().contains("auto_increment"), error.getMessage());
    }

    @Test
    void sqlNormalizationPreservesParenthesisLogicAndNormalizesOnlyItsWhitespace() {
        AgentSchemaInitializer initializer = new AgentSchemaInitializer(mock(JdbcTemplate.class));
        String left = initializer.normalizeSql("IF (a AND b) OR c THEN SIGNAL SQLSTATE '45000'; END IF");
        String right = initializer.normalizeSql("IF a AND (b OR c) THEN SIGNAL SQLSTATE '45000'; END IF");
        assertNotEquals(left, right);
        assertEquals(left, initializer.normalizeSql(
                " IF  (  a AND b  )  OR c THEN SIGNAL SQLSTATE '45000'; END IF "));
    }

    @Test
    void parenthesisLogicCollisionInTriggerFailsClosed() throws IOException {
        List<AgentSchemaInitializer.TriggerDefinition> definitions =
                new java.util.ArrayList<>(backfillAuditTriggers(
                        readResource("db/task-collaboration-backfill-audit-schema.sql")));
        int index = java.util.stream.IntStream.range(0, definitions.size())
                .filter(i -> "trg_task_backfill_manifest_batch_update_guard"
                        .equals(definitions.get(i).name()))
                .findFirst().orElseThrow();
        AgentSchemaInitializer.TriggerDefinition original = definitions.get(index);
        String altered = original.statement().replace(
                "if not (\n        binary old.seal_status",
                "if (not\n        binary old.seal_status");
        assertNotEquals(original.statement(), altered);
        definitions.set(index, new AgentSchemaInitializer.TriggerDefinition(
                original.name(), original.table(), original.timing(), original.event(), altered));
        JdbcTemplate template = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                return sql.contains("information_schema.triggers")
                        ? (List<T>) definitions : List.of();
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return query(sql, rowMapper);
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(template).validateBackfillAuditTriggers());
        assertTrue(error.getMessage().contains(original.name()), error.getMessage());
    }

    @Test
    void initializerAndMigrationKeepBackfillAuditTriggerDefinitionsInParity() throws Exception {
        JdbcTemplate template = dialectTemplate("MySQL");
        new AgentSchemaInitializer(template).afterPropertiesSet();
        List<String> statements = executedStatements(template);
        String migration = readResource("db/task-collaboration-backfill-audit-schema.sql");

        for (AgentSchemaInitializer.TriggerDefinition expected : backfillAuditTriggers(migration)) {
            String initializerTrigger = statements.stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT)
                            .startsWith("create trigger " + expected.name()))
                    .findFirst().orElseThrow();
            assertEquals(migrationTriggerDefinition(migration, expected.name()),
                    normalizeDefinition(initializerTrigger), expected.name());
            assertTrue(normalizeDefinition(initializerTrigger).contains(
                    " on " + expected.table() + " "), expected.name());
        }
    }

    @Test
    void backfillAuditTriggerOnWrongTableFailsClosed() throws IOException {
        List<AgentSchemaInitializer.TriggerDefinition> definitions =
                new java.util.ArrayList<>(backfillAuditTriggers(
                        readResource("db/task-collaboration-backfill-audit-schema.sql")));
        AgentSchemaInitializer.TriggerDefinition original = definitions.get(0);
        definitions.set(0, new AgentSchemaInitializer.TriggerDefinition(
                original.name(), "agent_task_backfill_issue", original.timing(),
                original.event(), original.statement()));
        JdbcTemplate template = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                return sql.contains("information_schema.triggers")
                        ? (List<T>) definitions : List.of();
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return query(sql, rowMapper);
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(template).validateBackfillAuditTriggers());
        assertTrue(error.getMessage().contains(original.name()), error.getMessage());
        assertTrue(error.getMessage().contains("incompatible table"), error.getMessage());
    }

    @Test
    void incompatibleBackfillManifestIndexFailsClosed() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("information_schema.statistics")) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "report_sha256", 1, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> invokeEnsureRequiredIndex(new AgentSchemaInitializer(failingTemplate),
                        "agent_task_backfill_manifest", "uk_task_backfill_manifest_row", true,
                        List.of("report_sha256", "manifest_row_key"), ""));
        assertTrue(error.getMessage().contains("uk_task_backfill_manifest_row"), error.getMessage());
    }

    @Test
    void incompatibleArtifactVersionUniqueIndexFailsStartup() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises collaboration-index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if ("agent_task_artifact".equals(args[0]) && "uk_artifact_version".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "tenant_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(0, "client_id", 2, null),
                            new AgentSchemaInitializer.IndexColumn(0, "artifact_id", 3, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_artifact_version"), error.getMessage());
    }

    @Test
    void incompatibleLegacyActivePersonaIndexFailsStartupUntilMigrationRuns() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises A02 index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if ("agent_persona_binding".equals(args[0])
                        && "uk_agent_binding_active_persona".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "client_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(0, "active_persona_code", 2, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_agent_binding_active_persona"), error.getMessage());
    }

    @Test
    void incompatibleActiveAliasIndexFailsStartup() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises A02 index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (args.length > 1 && "agent_identity_alias".equals(args[0])
                        && "uk_identity_alias_active".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "client_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(0, "owner_jiacn", 2, null),
                            new AgentSchemaInitializer.IndexColumn(0, "alias_type", 3, null),
                            new AgentSchemaInitializer.IndexColumn(0, "alias_value", 4, null),
                            new AgentSchemaInitializer.IndexColumn(0, "valid_to", 5, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_identity_alias_active"), error.getMessage());
    }

    @Test
    void h2DialectOmitsUnsupportedStoredKeywordFromGeneratedBindingColumns() throws Exception {
        JdbcTemplate template = dialectTemplate("H2");

        new AgentSchemaInitializer(template).afterPropertiesSet();

        List<String> statements = executedStatements(template);
        String createBinding = statements.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS agent_persona_binding"))
                .findFirst().orElseThrow();
        assertTrue(createBinding.contains(
                "active_persona_code VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN persona_code ELSE NULL END)"));
        assertTrue(createBinding.contains(
                "active_agent_id     VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN agent_id ELSE NULL END)"));
        assertTrue(createBinding.contains("owner_jiacn VARCHAR(50) GENERATED ALWAYS AS (jiacn)"));
        assertTrue(createBinding.contains("lifecycle_status VARCHAR(20) GENERATED ALWAYS AS"));
        String createAlias = statements.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS agent_identity_alias"))
                .findFirst().orElseThrow();
        assertTrue(createAlias.contains("active_key TINYINT GENERATED ALWAYS AS"));
        assertFalse(createBinding.contains(" STORED"));
        assertFalse(createAlias.contains(" STORED"));
        assertTrue(statements.stream()
                .filter(sql -> sql.startsWith("ALTER TABLE agent_persona_binding ADD COLUMN active_"))
                .noneMatch(sql -> sql.contains(" STORED")));
    }

    @Test
    void mysqlDialectRetainsStoredGeneratedBindingColumns() throws Exception {
        JdbcTemplate template = dialectTemplate("MySQL");

        new AgentSchemaInitializer(template).afterPropertiesSet();

        List<String> statements = executedStatements(template);
        String createBinding = statements.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS agent_persona_binding"))
                .findFirst().orElseThrow();
        assertTrue(createBinding.contains(
                "active_persona_code VARCHAR(50) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN persona_code ELSE NULL END) STORED"));
        assertTrue(createBinding.contains(
                "active_agent_id     VARCHAR(100) GENERATED ALWAYS AS (CASE WHEN status = 1 THEN agent_id ELSE NULL END) STORED"));
        assertTrue(createBinding.contains("owner_jiacn VARCHAR(50) GENERATED ALWAYS AS (jiacn) STORED"));
        assertTrue(createBinding.contains("lifecycle_status VARCHAR(20) GENERATED ALWAYS AS"));
        String createAlias = statements.stream()
                .filter(sql -> sql.contains("CREATE TABLE IF NOT EXISTS agent_identity_alias"))
                .findFirst().orElseThrow();
        assertTrue(createAlias.contains("active_key TINYINT GENERATED ALWAYS AS"));
        assertTrue(createAlias.contains(" STORED"));
        assertTrue(statements.stream()
                .filter(sql -> sql.startsWith("ALTER TABLE agent_persona_binding ADD COLUMN active_"))
                .allMatch(sql -> sql.endsWith(" STORED")));
    }

    @Test
    void initializerAndMigrationKeepIdentityTriggerDefinitionsInParity() throws Exception {
        JdbcTemplate template = dialectTemplate("MySQL");
        new AgentSchemaInitializer(template).afterPropertiesSet();
        List<String> statements = executedStatements(template);
        String migration = readResource("db/agent-identity-schema.sql");

        for (String trigger : List.of(
                "trg_identity_registry_immutable_update", "trg_identity_registry_no_delete",
                "trg_identity_alias_immutable_update", "trg_identity_alias_no_delete")) {
            String initializerTrigger = statements.stream()
                    .filter(sql -> sql.toLowerCase(Locale.ROOT).startsWith("create trigger " + trigger))
                    .findFirst().orElseThrow();
            assertEquals(migrationTriggerDefinition(migration, trigger),
                    normalizeDefinition(initializerTrigger), trigger);
            assertTrue(statements.contains("DROP TRIGGER IF EXISTS " + trigger));
        }
    }

    @Test
    void h2DialectUsesNativeIndexCatalogAndStillRejectsIncompatibleRequiredIndex() throws Exception {
        DataSource dataSource = dialectDataSource("H2");
        JdbcTemplate failingTemplate = new JdbcTemplate(dataSource) {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises H2 required-index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("from information_schema.tables")) {
                    return List.of();
                }
                assertTrue(normalized.contains("from information_schema.index_columns"), normalized);
                if ("agent_scene_state".equals(args[0])
                        && "uk_agent_scene_state_scope_agent".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(1, "tenant_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(1, "client_id", 2, null),
                            new AgentSchemaInitializer.IndexColumn(1, "scene_id", 3, null),
                            new AgentSchemaInitializer.IndexColumn(1, "agent_id", 4, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_agent_scene_state_scope_agent"), error.getMessage());
    }

    @Test
    void h2WithoutOptionalBaseAgentTablesStillInitializesAndValidatesSceneSchema() throws Exception {
        DataSource dataSource = dialectDataSource("H2");
        AtomicBoolean sceneTableCreated = new AtomicBoolean();
        JdbcTemplate template = new JdbcTemplate(dataSource) {
            @Override
            public void execute(String sql) {
                if (sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_state")) {
                    sceneTableCreated.set(true);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                return (T) Integer.valueOf(normalized.contains("from information_schema.tables") ? 0 : 1);
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                return List.of();
            }

            @Override
            public int update(String sql, Object... args) {
                throw new AssertionError("persona seeds must not run without the optional agent_persona table");
            }
        };

        new AgentSchemaInitializer(template).afterPropertiesSet();

        assertTrue(sceneTableCreated.get());
    }

    @Test
    void requiredSceneIndexWithWrongUniquenessFailsStartup() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises required-index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("from information_schema.tables")) {
                    return List.of();
                }
                assertTrue(normalized.contains("select non_unique, column_name, seq_in_index, sub_part"), normalized);
                assertTrue(normalized.contains("order by seq_in_index"), normalized);
                if ("agent_scene_state".equals(args[0])
                        && "uk_agent_scene_state_scope_agent".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(1, "tenant_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(1, "client_id", 2, null),
                            new AgentSchemaInitializer.IndexColumn(1, "scene_id", 3, null),
                            new AgentSchemaInitializer.IndexColumn(1, "agent_id", 4, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_agent_scene_state_scope_agent"), error.getMessage());
    }

    @Test
    void requiredSceneIndexWithWrongOrderedColumnsFailsStartup() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises required-index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if ("agent_scene_state".equals(args[0])
                        && "uk_agent_scene_state_scope_agent".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "client_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(0, "tenant_id", 2, null),
                            new AgentSchemaInitializer.IndexColumn(0, "scene_id", 3, null),
                            new AgentSchemaInitializer.IndexColumn(0, "agent_id", 4, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_agent_scene_state_scope_agent"), error.getMessage());
    }

    @Test
    void requiredSceneIndexWithPrefixComponentFailsStartup() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is intentionally inert; this test exercises required-index introspection.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if ("agent_scene_state".equals(args[0])
                        && "uk_agent_scene_state_scope_agent".equals(args[1])) {
                    return (List<T>) List.of(
                            new AgentSchemaInitializer.IndexColumn(0, "tenant_id", 1, null),
                            new AgentSchemaInitializer.IndexColumn(0, "client_id", 2, 12),
                            new AgentSchemaInitializer.IndexColumn(0, "scene_id", 3, null),
                            new AgentSchemaInitializer.IndexColumn(0, "agent_id", 4, null));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("uk_agent_scene_state_scope_agent"), error.getMessage());
    }

    @Test
    void initializerAndSchemaResourceKeepSceneTableStructureInParity() throws IOException {
        JdbcTemplate template = mock(JdbcTemplate.class);
        new AgentSchemaInitializer(template).afterPropertiesSet();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(captor.capture());
        List<String> initializerSql = captor.getAllValues().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .toList();
        String schema = readSchema();

        for (String table : Set.of(
                "agent_scene_state", "agent_scene_event", "agent_scene_phase_report", "agent_scene_version")) {
            String resourceDefinition = tableDefinition(schema, table);
            String initializerDefinition = initializerSql.stream()
                    .filter(sql -> sql.contains("create table if not exists " + table))
                    .findFirst().orElseThrow();
            assertEquals(tableStructure(resourceDefinition), tableStructure(initializerDefinition), table);
        }
    }


    @Test
    void b0IdentityTablesWithAiCiCollationFailClosedBeforeStartup() {
        JdbcTemplate b0Template = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is inert; this fixture models an existing b0 catalog.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (requiredType == String.class && sql.contains("TABLE_COLLATION")) {
                    return (T) "utf8mb4_0900_ai_ci";
                }
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("from information_schema.tables")) {
                    String table = String.valueOf(args[0]);
                    return (List<T>) List.of(table.startsWith("agent_identity_") ? 1 : 0);
                }
                if (normalized.contains("from information_schema.columns")) {
                    return (List<T>) List.of(identityColumn(String.valueOf(args[0]), String.valueOf(args[1])));
                }
                if (normalized.contains("from information_schema.statistics")) {
                    return (List<T>) identityIndex(String.valueOf(args[0]), String.valueOf(args[1]));
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(b0Template).afterPropertiesSet());
        assertTrue(error.getMessage().contains("binary collation"), error.getMessage());
        assertTrue(error.getMessage().contains("utf8mb4_0900_ai_ci"), error.getMessage());
    }

    @Test
    void partialIdentityTableFailsClosedBeforeCreateCanMaskIt() {
        JdbcTemplate partialTemplate = new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is inert so the pre-create catalog state remains observable.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("from information_schema.tables")) {
                    return (List<T>) List.of("agent_identity_registry".equals(args[0]) ? 1 : 0);
                }
                return List.of();
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(partialTemplate).afterPropertiesSet());
        assertTrue(error.getMessage().contains("identity schema is partial"), error.getMessage());
    }

    @Test
    void incompatibleGeneratedColumnFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(identityCatalogTemplate("generated")).afterPropertiesSet());
        assertTrue(error.getMessage().contains("agent_identity_alias.active_key"), error.getMessage());
        assertTrue(error.getMessage().contains("generated expression"), error.getMessage());
    }

    @Test
    void weakenedIdentityCheckDefinitionFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(identityCatalogTemplate("check")).afterPropertiesSet());
        assertTrue(error.getMessage().contains("chk_identity_registry_scope"), error.getMessage());
        assertTrue(error.getMessage().contains("incompatible definition"), error.getMessage());
    }

    @Test
    void incompatibleAliasForeignKeyRuleFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(identityCatalogTemplate("fk")).afterPropertiesSet());
        assertTrue(error.getMessage().contains("composite FK"), error.getMessage());
    }

    @Test
    void weakenedIdentityTriggerDefinitionFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(identityCatalogTemplate("trigger")).afterPropertiesSet());
        assertTrue(error.getMessage().contains("trg_identity_registry_immutable_update"), error.getMessage());
        assertTrue(error.getMessage().contains("incompatible definition"), error.getMessage());
    }

    @Test
    void identityTriggerOnWrongTableFailsClosed() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(identityCatalogTemplate("trigger-table"))
                        .afterPropertiesSet());
        assertTrue(error.getMessage().contains("trg_identity_registry_immutable_update"), error.getMessage());
        assertTrue(error.getMessage().contains("incompatible definition"), error.getMessage());
    }

    private JdbcTemplate identityCatalogTemplate(String fault) {
        return new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL is inert; this fixture exposes a complete existing MySQL identity catalog.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (requiredType == String.class && sql.contains("TABLE_COLLATION")) {
                    return (T) "utf8mb4_0900_bin";
                }
                return (T) Integer.valueOf(1);
            }

            @Override
            public <T> List<T> query(String sql, RowMapper<T> rowMapper) {
                return query(sql, rowMapper, new Object[0]);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                String normalized = sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                if (normalized.contains("from information_schema.tables")) {
                    String table = String.valueOf(args[0]);
                    return (List<T>) List.of(table.startsWith("agent_identity_") ? 1 : 0);
                }
                if (normalized.contains("from information_schema.columns")) {
                    AgentSchemaInitializer.ColumnDefinition definition =
                            identityColumn(String.valueOf(args[0]), String.valueOf(args[1]));
                    if ("generated".equals(fault) && "active_key".equals(args[1])) {
                        definition = new AgentSchemaInitializer.ColumnDefinition(
                                "tinyint", "tinyint", true, null,
                                "case when alias_status = 'ACTIVE' then 1 else null end");
                    }
                    return (List<T>) List.of(definition);
                }
                if (normalized.contains("from information_schema.statistics")) {
                    return (List<T>) identityIndex(String.valueOf(args[0]), String.valueOf(args[1]));
                }
                if (normalized.contains("join information_schema.check_constraints")) {
                    return (List<T>) identityChecks("check".equals(fault));
                }
                if (normalized.contains("join information_schema.referential_constraints")) {
                    return (List<T>) identityForeignKey("fk".equals(fault));
                }
                if (normalized.contains("from information_schema.triggers")) {
                    List<AgentSchemaInitializer.TriggerDefinition> triggers =
                            new java.util.ArrayList<>(identityTriggers("trigger".equals(fault)));
                    if ("trigger-table".equals(fault)) {
                        AgentSchemaInitializer.TriggerDefinition original = triggers.get(0);
                        triggers.set(0, new AgentSchemaInitializer.TriggerDefinition(
                                original.name(), "agent_identity_alias", original.timing(),
                                original.event(), original.statement()));
                    }
                    return (List<T>) triggers;
                }
                return List.of();
            }
        };
    }

    private List<AgentSchemaInitializer.CheckDefinition> identityChecks(boolean weakenedScope) {
        return List.of(
                new AgentSchemaInitializer.CheckDefinition("agent_identity_registry",
                        "chk_identity_registry_type",
                        "canonical_type IN ('OPAQUE','LEGACY_CANONICAL','SYSTEM')"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_registry",
                        "chk_identity_registry_lifecycle",
                        "lifecycle_status IN ('PROVISIONED','ACTIVE','SUSPENDED','RETIRED')"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_registry",
                        "chk_identity_registry_canonical", """
                        (canonical_type = 'OPAQUE' AND REGEXP_LIKE(canonical_agent_id,'^agt_[0-9a-f]{32}$'))
                        OR (canonical_type = 'LEGACY_CANONICAL'
                            AND canonical_agent_id <> 'builtin-songjiang'
                            AND NOT(REGEXP_LIKE(canonical_agent_id,'^agt_[0-9a-f]{32}$')))
                        OR (canonical_type = 'SYSTEM' AND canonical_agent_id = 'builtin-songjiang')
                        """),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_registry",
                        "chk_identity_registry_scope", weakenedScope
                        ? "canonical_type = 'SYSTEM' OR tenant_id = TRIM(owner_jiacn)"
                        : """
                          (canonical_type = 'SYSTEM' AND client_id IS NULL
                              AND owner_jiacn IS NULL AND tenant_id IS NULL)
                          OR (canonical_type <> 'SYSTEM' AND client_id IS NOT NULL
                              AND TRIM(client_id) <> '' AND owner_jiacn IS NOT NULL
                              AND TRIM(owner_jiacn) <> '' AND tenant_id = TRIM(owner_jiacn))
                          """),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_registry",
                        "chk_identity_registry_retired", """
                        (lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
                        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL)
                        """),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_type", "alias_type = 'LEGACY_AGENT_ID'"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_status", "alias_status IN ('ACTIVE','REVOKED')"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_scope", "tenant_id = TRIM(owner_jiacn)"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_no_blank_scope",
                        "TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''"),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_window", """
                        (alias_status = 'ACTIVE' AND valid_to IS NULL)
                        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)
                        """),
                new AgentSchemaInitializer.CheckDefinition("agent_identity_alias",
                        "chk_identity_alias_not_system",
                        "alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'")
        );
    }

    private List<AgentSchemaInitializer.ForeignKeyColumn> identityForeignKey(boolean cascadeDelete) {
        List<String> columns = List.of(
                "registry_id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id");
        java.util.ArrayList<AgentSchemaInitializer.ForeignKeyColumn> result = new java.util.ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            result.add(new AgentSchemaInitializer.ForeignKeyColumn(
                    columns.get(i), "agent_identity_registry", columns.get(i), i + 1,
                    "RESTRICT", cascadeDelete ? "CASCADE" : "RESTRICT"));
        }
        return result;
    }

    private List<AgentSchemaInitializer.TriggerDefinition> identityTriggers(boolean weakenedRegistry) {
        String registryUpdate = weakenedRegistry ? """
                BEGIN
                    IF NOT (NEW.canonical_agent_id <=> OLD.canonical_agent_id) THEN
                        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'immutable';
                    END IF;
                END
                """ : """
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
                """;
        return List.of(
                new AgentSchemaInitializer.TriggerDefinition(
                        "trg_identity_registry_immutable_update", "agent_identity_registry",
                        "BEFORE", "UPDATE", registryUpdate),
                new AgentSchemaInitializer.TriggerDefinition(
                        "trg_identity_registry_no_delete", "agent_identity_registry",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity registry is forbidden';
                        END
                        """),
                new AgentSchemaInitializer.TriggerDefinition(
                        "trg_identity_alias_immutable_update", "agent_identity_alias",
                        "BEFORE", "UPDATE", """
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
                new AgentSchemaInitializer.TriggerDefinition(
                        "trg_identity_alias_no_delete", "agent_identity_alias",
                        "BEFORE", "DELETE", """
                        BEGIN
                            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'A02: physical delete of identity alias is forbidden';
                        END
                        """));
    }

    private AgentSchemaInitializer.ColumnDefinition identityColumn(String table, String column) {
        boolean alias = "agent_identity_alias".equals(table);
        return switch (column) {
            case "registry_id" -> new AgentSchemaInitializer.ColumnDefinition("bigint", "bigint", false, null, "");
            case "binding_id" -> new AgentSchemaInitializer.ColumnDefinition("bigint", "bigint", true, null, "");
            case "active_key" -> new AgentSchemaInitializer.ColumnDefinition(
                    "tinyint", "tinyint", true, null,
                    "case when alias_status = 'ACTIVE' and valid_to is null then 1 else null end");
            case "canonical_agent_id", "alias_value" -> new AgentSchemaInitializer.ColumnDefinition(
                    "varchar", "varchar(100)", false, "utf8mb4_0900_bin", "");
            case "canonical_type", "alias_type" -> new AgentSchemaInitializer.ColumnDefinition(
                    "varchar", "varchar(32)", false, "utf8mb4_0900_bin", "");
            case "lifecycle_status", "alias_status" -> new AgentSchemaInitializer.ColumnDefinition(
                    "varchar", "varchar(20)", false, "utf8mb4_0900_bin", "");
            case "client_id", "owner_jiacn", "tenant_id" -> new AgentSchemaInitializer.ColumnDefinition(
                    "varchar", "varchar(50)", alias ? false : true, "utf8mb4_0900_bin", "");
            case "audit_reason" -> new AgentSchemaInitializer.ColumnDefinition(
                    "varchar", "varchar(1000)", false, "utf8mb4_0900_bin", "");
            default -> throw new AssertionError(table + "." + column);
        };
    }

    private List<AgentSchemaInitializer.IndexColumn> identityIndex(String table, String index) {
        List<String> columns = switch (index) {
            case "uk_identity_registry_agent" -> List.of("canonical_agent_id");
            case "uk_identity_registry_binding" -> List.of("binding_id");
            case "uk_identity_registry_alias_target" ->
                    List.of("id", "canonical_agent_id", "client_id", "owner_jiacn", "tenant_id");
            case "idx_identity_registry_scope_status" ->
                    List.of("tenant_id", "client_id", "owner_jiacn", "lifecycle_status");
            case "uk_identity_alias_active" ->
                    List.of("client_id", "owner_jiacn", "alias_type", "alias_value", "active_key");
            case "idx_identity_alias_registry" -> List.of("registry_id", "alias_status");
            case "idx_identity_alias_canonical" -> List.of("canonical_agent_id", "alias_status");
            default -> List.of();
        };
        boolean unique = index.startsWith("uk_");
        java.util.ArrayList<AgentSchemaInitializer.IndexColumn> result = new java.util.ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            result.add(new AgentSchemaInitializer.IndexColumn(unique ? 0 : 1, columns.get(i), i + 1, null));
        }
        return result;
    }

    private List<AgentSchemaInitializer.TriggerDefinition> backfillAuditTriggers(String migration) {
        return List.of(
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_batch_insert_guard",
                        "agent_task_backfill_manifest_batch", "INSERT"),
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_batch_update_guard",
                        "agent_task_backfill_manifest_batch", "UPDATE"),
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_batch_no_delete",
                        "agent_task_backfill_manifest_batch", "DELETE"),
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_insert_guard",
                        "agent_task_backfill_manifest", "INSERT"),
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_no_update",
                        "agent_task_backfill_manifest", "UPDATE"),
                backfillAuditTrigger(migration, "trg_task_backfill_manifest_no_delete",
                        "agent_task_backfill_manifest", "DELETE"),
                backfillAuditTrigger(migration, "trg_task_backfill_issue_insert_guard",
                        "agent_task_backfill_issue", "INSERT"),
                backfillAuditTrigger(migration, "trg_task_backfill_issue_update_guard",
                        "agent_task_backfill_issue", "UPDATE"),
                backfillAuditTrigger(migration, "trg_task_backfill_issue_no_delete",
                        "agent_task_backfill_issue", "DELETE"),
                backfillAuditTrigger(migration, "trg_task_backfill_run_insert_guard",
                        "agent_task_backfill_run", "INSERT"),
                backfillAuditTrigger(migration, "trg_task_backfill_run_no_update",
                        "agent_task_backfill_run", "UPDATE"),
                backfillAuditTrigger(migration, "trg_task_backfill_run_no_delete",
                        "agent_task_backfill_run", "DELETE"));
    }

    private AgentSchemaInitializer.TriggerDefinition backfillAuditTrigger(
            String migration, String name, String table, String event) {
        int start = migration.indexOf("create trigger " + name);
        assertTrue(start >= 0, name);
        int bodyStart = migration.indexOf("for each row", start);
        assertTrue(bodyStart > start, name);
        bodyStart += "for each row".length();
        int end = migration.indexOf("end$$", bodyStart);
        assertTrue(end > bodyStart, name);
        String statement = migration.substring(bodyStart, end + "end".length()).trim();
        return new AgentSchemaInitializer.TriggerDefinition(
                name, table, "BEFORE", event, statement);
    }

    private String migrationTriggerDefinition(String migration, String trigger) {
        int start = migration.indexOf("create trigger " + trigger);
        assertTrue(start >= 0, trigger);
        int end = migration.indexOf("end$$", start);
        assertTrue(end > start, trigger);
        return normalizeDefinition(migration.substring(start, end + "end".length()));
    }

    private String tableDefinition(String schema, String table) {
        int start = schema.indexOf("create table if not exists " + table);
        assertTrue(start >= 0, table);
        int end = schema.indexOf(";", start);
        assertTrue(end > start, table);
        return schema.substring(start, end);
    }

    private JdbcTemplate dialectTemplate(String productName) throws Exception {
        JdbcTemplate template = mock(JdbcTemplate.class);
        DataSource dataSource = dialectDataSource(productName);
        when(template.getDataSource()).thenReturn(dataSource);
        return template;
    }

    private void invokeEnsureRequiredIndex(
            AgentSchemaInitializer initializer, String table, String indexName,
            boolean unique, List<String> columns, String createSql) {
        try {
            var method = AgentSchemaInitializer.class.getDeclaredMethod(
                    "ensureRequiredIndex", String.class, String.class,
                    boolean.class, List.class, String.class);
            method.setAccessible(true);
            method.invoke(initializer, table, indexName, unique, columns, createSql);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private DataSource dialectDataSource(String productName) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(productName);
        return dataSource;
    }

    private List<String> executedStatements(JdbcTemplate template) {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(template, atLeastOnce()).execute(captor.capture());
        return captor.getAllValues();
    }

    private String readSchema() throws IOException {
        return readResource("db/schema.sql");
    }

    private JdbcTemplate backfillColumnMismatchTemplate(
            AgentSchemaInitializer.BackfillColumnDefinition issueKeyDefinition) {
        return new JdbcTemplate() {
            @Override
            public void execute(String sql) {
                // DDL intentionally inert so catalog evidence controls the result.
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (sql.contains("TABLE_COLLATION")) {
                    return (T) "utf8mb4_0900_bin";
                }
                return (T) Integer.valueOf(1);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
                if (sql.contains("information_schema.tables")) {
                    return (List<T>) List.of(Integer.valueOf(
                            args.length > 0 && String.valueOf(args[0]).startsWith("agent_task_backfill_") ? 1 : 0));
                }
                if (sql.contains("information_schema.statistics")
                        && args.length > 1 && "PRIMARY".equals(args[1])) {
                    return (List<T>) List.of(new AgentSchemaInitializer.IndexColumn(
                            0, "id", 1, null));
                }
                if (sql.contains("SELECT DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT")) {
                    if ("agent_task_backfill_issue".equals(args[0]) && "id".equals(args[1])) {
                        return (List<T>) List.of(new AgentSchemaInitializer.BackfillColumnDefinition(
                                "bigint", "bigint", false, null, null, "auto_increment"));
                    }
                    if ("agent_task_backfill_issue".equals(args[0]) && "issue_key".equals(args[1])) {
                        return (List<T>) List.of(issueKeyDefinition);
                    }
                }
                return List.of();
            }
        };
    }

    private String readResource(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    private TableStructure tableStructure(String definition) {
        Map<String, String> columns = new LinkedHashMap<>();
        Matcher columnMatcher = Pattern.compile(
                "(?m)^\\s*([a-z][a-z0-9_]*)\\s+"
                        + "((?:(?:bigint|int|mediumtext|text)\\b|tinyint\\(1\\)|varchar\\(\\d+\\))[^,\\n]*)")
                .matcher(definition);
        while (columnMatcher.find()) {
            columns.put(columnMatcher.group(1), normalizeDefinition(columnMatcher.group(2)));
        }

        Map<String, String> indexes = new LinkedHashMap<>();
        String compact = definition.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        Matcher primary = Pattern.compile("primary key \\(([^)]*)\\)").matcher(compact);
        if (primary.find()) {
            indexes.put("primary", "unique|" + normalizeColumns(primary.group(1)));
        }
        Matcher namedIndexes = Pattern.compile("(unique key|key) ([a-z][a-z0-9_]*) \\(([^)]*)\\)")
                .matcher(compact);
        while (namedIndexes.find()) {
            String uniqueness = namedIndexes.group(1).startsWith("unique") ? "unique" : "nonunique";
            indexes.put(namedIndexes.group(2), uniqueness + "|" + normalizeColumns(namedIndexes.group(3)));
        }
        return new TableStructure(columns, indexes);
    }

    private String normalizeSqlStructure(String definition) {
        return normalizeDefinition(definition)
                .replace("( ", "(")
                .replace(" )", ")");
    }

    private String normalizeDefinition(String definition) {
        return definition.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeColumns(String columns) {
        return columns.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record TableStructure(Map<String, String> columns, Map<String, String> indexes) {}
}
