package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C01 schema-level tests for agent_task_event.
 */
class AgentTaskEventSchemaTest {

    @Test
    void schemaAndEventMigrationDeclareSameTable() throws IOException {
        String schema = readResource("db/schema.sql");
        String migration = readResource("db/task-event-schema.sql");

        String schemaDef = tableDefinition(schema, "agent_task_event");
        String migrationDef = tableDefinition(migration, "agent_task_event");

        assertEquals(compact(schemaDef), compact(migrationDef),
                "schema.sql and task-event-schema.sql must declare identical agent_task_event");
    }

    @Test
    void eventTableHasRequiredColumns() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        List<String> requiredColumns = List.of(
                "event_id", "task_id", "event_version", "event_type",
                "actor", "aggregate_type", "aggregate_id",
                "created_at", "tenant_id", "client_id");
        for (String col : requiredColumns) {
            assertTrue(eventDef.contains(col), "Missing column: " + col);
        }

        // Tenant/client must be NOT NULL (event scope is mandatory)
        assertTrue(eventDef.contains("tenant_id varchar(50) not null"));
        assertTrue(eventDef.contains("client_id varchar(50) not null"));

        // payload is nullable (MEDIUMTEXT)
        assertTrue(eventDef.contains("payload mediumtext"));
    }

    @Test
    void eventTableHasRequiredUniqueConstraints() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        // Primary unique: scope + task + event_version
        assertTrue(eventDef.contains(
                "unique key uk_event_version (tenant_id, client_id, task_id, event_version)"),
                "uk_event_version required");

        // Event ID uniqueness: scope + event_id
        assertTrue(eventDef.contains(
                "unique key uk_event_id (tenant_id, client_id, event_id)"),
                "uk_event_id required");
    }

    @Test
    void eventTableHasRequiredSecondaryIndexes() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains(
                "key idx_event_task_time (tenant_id, client_id, task_id, created_at)"));
        assertTrue(eventDef.contains(
                "key idx_event_actor_time (tenant_id, client_id, actor, created_at)"));
        assertTrue(eventDef.contains(
                "key idx_event_type_time (tenant_id, client_id, event_type, created_at)"));
    }

    @Test
    void eventTableDoesNotIntrudeOnOtherTables() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        // No persona/runtime/websocket leakage
        assertFalse(eventDef.contains("persona_code"));
        assertFalse(eventDef.contains("runtime_instance_id"));
        assertFalse(eventDef.contains("websocket_session_id"));

        // No outbox/inbox/delivery (C02+ territory)
        assertFalse(eventDef.contains("outbox"));
        assertFalse(eventDef.contains("inbox"));
        assertFalse(eventDef.contains("delivery"));
    }

    @Test
    void eventVersionUsesBigintNotInt() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("event_version bigint not null"),
                "event_version must be BIGINT, not INT");
        assertFalse(eventDef.contains("event_version int"),
                "event_version must not use INT (overflow risk)");
    }

    @Test
    void migrationScriptIsSafeToReRun() throws IOException {
        String migration = readResource("db/task-event-schema.sql");
        assertTrue(migration.contains("create table if not exists agent_task_event"),
                "Migration must use IF NOT EXISTS for idempotent re-run");
    }

    // ── helpers ──

    private String readResource(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        }
    }

    private String tableDefinition(String sql, String table) {
        int start = sql.indexOf("create table if not exists " + table);
        assertTrue(start >= 0, "Table not found: " + table);
        int end = sql.indexOf(';', start);
        assertTrue(end > start, "No semicolon after table: " + table);
        return sql.substring(start, end);
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
