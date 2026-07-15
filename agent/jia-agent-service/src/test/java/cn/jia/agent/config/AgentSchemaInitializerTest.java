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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
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

    private String tableDefinition(String schema, String table) {
        int start = schema.indexOf("create table if not exists " + table);
        assertTrue(start >= 0, table);
        int end = schema.indexOf(";", start);
        assertTrue(end > start, table);
        return schema.substring(start, end);
    }
}
