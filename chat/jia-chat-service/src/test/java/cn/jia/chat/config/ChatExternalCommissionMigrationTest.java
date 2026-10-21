package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Resource-level contract only; MySQL migration execution belongs to the release validation. */
class ChatExternalCommissionMigrationTest {
    private String resource(String path) throws Exception {
        try (var input=getClass().getResourceAsStream(path)) {
            assertNotNull(input,path);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @Test void explicitMigrationAddsNullableHistoricalReferencesWithoutInventingAuthority() throws Exception {
        String sql=resource("/db/chat-external-commission-v1-migration.sql");
        assertTrue(sql.contains("ADD COLUMN task_grant_id VARCHAR(100)"));
        assertTrue(sql.contains("ADD COLUMN task_grant_version BIGINT DEFAULT NULL"));
        assertTrue(sql.contains("CREATE INDEX idx_chat_outbox_dispatch"));
        assertTrue(sql.contains("CHAT_HISTORICAL_GRANT_REFERENCE_UNPROVEN"));
        String executable=sql.replaceAll("(?m)--[^\\r\\n]*", "").toUpperCase(java.util.Locale.ROOT);
        assertFalse(executable.contains("UPDATE "));
        assertFalse(executable.contains("DELETE "));
        assertFalse(executable.contains("AGENT_TASK_EXECUTION_GRANT"));
        String fresh=resource("/db/chat-deliberation-schema.sql");
        assertTrue(fresh.contains("task_grant_id"));
        assertTrue(fresh.contains("task_grant_version"));
        assertTrue(fresh.contains("idx_chat_outbox_dispatch"));
    }
}
