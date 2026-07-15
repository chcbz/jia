package cn.jia.agent.config;

import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

        for (String table : Set.of("agent_scene_state", "agent_scene_event", "agent_scene_phase_report")) {
            String definition = tableDefinition(schema, table);
            String compact = definition.replaceAll("\\s+", " ");
            assertTrue(compact.contains("tenant_id varchar(50) not null"), table);
            assertTrue(compact.contains("client_id varchar(50) not null"), table);
            assertTrue(compact.contains("scene_id varchar(100) not null"), table);
            assertFalse(definition.matches("(?s).*\\b(x|y|path|coordinates?|frame|frame_index|token|credential|model_response)\\b.*"), table);
        }
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
        verify(jdbcTemplate, atLeastOnce()).update(any(String.class), any(Object[].class));
    }

    @Test
    void requiredSceneIndexFailurePropagatesInsteadOfBeingSwallowed() {
        JdbcTemplate failingTemplate = new JdbcTemplate() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (args.length == 2
                        && "agent_scene_state".equals(args[0])
                        && "uk_agent_scene_state_scope_agent".equals(args[1])) {
                    return (T) Integer.valueOf(0);
                }
                return (T) Integer.valueOf(1);
            }

            @Override
            public void execute(String sql) {
                if (sql.startsWith("CREATE UNIQUE INDEX uk_agent_scene_state_scope_agent")) {
                    throw new IllegalStateException("required index denied");
                }
            }
        };

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new AgentSchemaInitializer(failingTemplate).afterPropertiesSet());
        assertEquals("required index denied", error.getMessage());
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

        for (String table : Set.of("agent_scene_state", "agent_scene_event", "agent_scene_phase_report")) {
            String resourceDefinition = tableDefinition(schema, table);
            String initializerDefinition = initializerSql.stream()
                    .filter(sql -> sql.contains("create table if not exists " + table))
                    .findFirst().orElseThrow();
            assertEquals(structuralNames(resourceDefinition), structuralNames(initializerDefinition), table);
        }
    }

    private String tableDefinition(String schema, String table) {
        int start = schema.indexOf("create table if not exists " + table);
        assertTrue(start >= 0, table);
        int end = schema.indexOf(";", start);
        assertTrue(end > start, table);
        return schema.substring(start, end);
    }

    private String readSchema() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("db/schema.sql")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    private Set<String> structuralNames(String definition) {
        Set<String> names = new HashSet<>();
        Matcher columns = Pattern.compile(
                "(?m)^\\s*([a-z][a-z0-9_]*)\\s+(?:bigint|varchar|text)\\b")
                .matcher(definition);
        while (columns.find()) {
            names.add("column:" + columns.group(1));
        }
        Matcher indexes = Pattern.compile("(?i)(?:unique\\s+key|key)\\s+([a-z][a-z0-9_]*)")
                .matcher(definition);
        while (indexes.find()) {
            names.add("index:" + indexes.group(1).toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
