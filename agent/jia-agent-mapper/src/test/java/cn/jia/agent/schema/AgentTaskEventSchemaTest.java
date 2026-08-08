package cn.jia.agent.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C01 schema-level tests for agent_task_event (§7.5).
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
                "task_id", "event_version", "event_id", "event_type",
                "actor_type", "actor_id", "aggregate_type", "aggregate_id",
                "event_json", "occurred_at", "tenant_id", "client_id");
        for (String col : requiredColumns) {
            assertTrue(eventDef.contains(col), "Missing column: " + col);
        }

        assertTrue(eventDef.contains("tenant_id varchar(50) not null"));
        assertTrue(eventDef.contains("client_id varchar(50) not null"));
        assertTrue(eventDef.contains("event_json mediumtext not null"),
                "event_json must be MEDIUMTEXT NOT NULL");
        assertTrue(eventDef.contains("actor_id varchar(100) default null"),
                "actor_id must be nullable");

        // No legacy actor field
        assertFalse(eventDef.contains("actor varchar"));
    }

    @Test
    void eventTableHasRequiredUniqueConstraints() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains(
                "unique key uk_task_event_version (tenant_id, client_id, task_id, event_version)"));
        assertTrue(eventDef.contains(
                "unique key uk_task_event_id (tenant_id, client_id, event_id)"));
    }

    @Test
    void eventTableHasRequiredSecondaryIndexes() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains(
                "key idx_task_event_occurred (tenant_id, client_id, task_id, occurred_at)"));
        assertTrue(eventDef.contains(
                "key idx_event_actor_time (tenant_id, client_id, actor_type, actor_id, occurred_at)"));
        assertTrue(eventDef.contains(
                "key idx_event_type_time (tenant_id, client_id, event_type, occurred_at)"));
    }

    @Test
    void eventTableHasBinaryCollation() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("charset=utf8mb4 collate=utf8mb4_0900_bin"),
                "Must use binary collation for byte-exact scope matching");
    }

    @Test
    void eventTableDoesNotIntrudeOnOtherTables() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertFalse(eventDef.contains("persona_code"));
        assertFalse(eventDef.contains("runtime_instance_id"));
        assertFalse(eventDef.contains("websocket_session_id"));
        assertFalse(eventDef.contains("outbox"));
        assertFalse(eventDef.contains("inbox"));
        assertFalse(eventDef.contains("delivery"));
    }

    @Test
    void eventVersionUsesBigintNotInt() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("event_version bigint not null"));
        assertFalse(eventDef.contains("event_version int"));
    }

    @Test
    void eventIdIsVarchar100() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("event_id varchar(100) not null"));
        assertFalse(eventDef.contains("event_id varchar(64)"));
    }

    @Test
    void eventTypeIsVarchar64() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("event_type varchar(64) not null"));
        assertFalse(eventDef.contains("event_type varchar(50)"));
    }

    @Test
    void migrationScriptIsSafeToReRun() throws IOException {
        String migration = readResource("db/task-event-schema.sql");
        assertTrue(migration.contains("create table if not exists agent_task_event"),
                "Migration must use IF NOT EXISTS for idempotent re-run");
    }

    @Test
    void actorTypeIsVarchar20NotNull() throws IOException {
        String schema = readResource("db/schema.sql");
        String eventDef = compact(tableDefinition(schema, "agent_task_event"));

        assertTrue(eventDef.contains("actor_type varchar(20) not null"));
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
