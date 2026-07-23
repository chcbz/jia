package cn.jia.chat.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskThreadSchemaTest {
    @Test
    void testSchemaAndControlledMigrationUseSameScopedBindingDefinition() throws Exception {
        String schema = read("db/schema.sql");
        String migration = read("db/task-thread-schema.sql");
        String testDefinition = definition(schema);
        String migrationDefinition = definition(migration);
        assertEquals(compact(testDefinition), compact(migrationDefinition));
        assertTrue(compact(testDefinition).contains(
                "unique key uk_task_thread_scope (tenant_id, client_id, task_id, thread_type, thread_key)"));
        assertTrue(compact(testDefinition).contains(
                "unique key uk_task_thread_conversation (tenant_id, client_id, conversation_id)"));
        assertTrue(compact(testDefinition).contains("tenant_id varchar(50) not null"));
        assertTrue(compact(testDefinition).contains("client_id varchar(50) not null"));
    }

    private String read(String name) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT);
        }
    }

    private String definition(String sql) {
        int start = sql.indexOf("create table");
        while (start >= 0 && !sql.substring(start).startsWith("create table agent_task_thread")
                && !sql.substring(start).startsWith("create table if not exists agent_task_thread")) {
            start = sql.indexOf("create table", start + 1);
        }
        assertTrue(start >= 0);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end)
                .replace("create table if not exists", "create table");
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }
}
