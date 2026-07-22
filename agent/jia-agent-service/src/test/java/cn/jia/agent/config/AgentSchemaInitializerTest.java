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
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_state"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_event"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_phase_report"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_scene_version"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_member"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_work_item"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_request"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS agent_task_artifact"));
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
        assertFalse(createBinding.contains(" STORED"));
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
        assertTrue(statements.stream()
                .filter(sql -> sql.startsWith("ALTER TABLE agent_persona_binding ADD COLUMN active_"))
                .allMatch(sql -> sql.endsWith(" STORED")));
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

    private String normalizeDefinition(String definition) {
        return definition.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeColumns(String columns) {
        return columns.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record TableStructure(Map<String, String> columns, Map<String, String> indexes) {}
}
