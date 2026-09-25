package cn.jia.chat.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatDeliberationSchemaContractTest {
    @Test
    void schemaDeclaresDurableScopeAndIdempotencyConstraints() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/chat-deliberation-schema.sql")) {
            if (stream == null) throw new AssertionError("schema resource missing");
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            String compact = sql.replaceAll("\\s+", " ");
            assertTrue(sql.contains("create table if not exists chat_context_snapshot"));
            assertTrue(sql.contains("create table if not exists chat_request"));
            assertTrue(sql.contains("create table if not exists chat_turn"));
            assertTrue(sql.contains("create table if not exists chat_dispatch_outbox"));
            assertTrue(sql.contains("create table if not exists chat_conversation_event"));
            assertTrue(sql.contains("create table if not exists chat_deliberation_schema_version"));
            assertTrue(compact.contains("idx_chat_outbox_ready (status, available_at, event_id)"));
            assertTrue(sql.contains("lease_owner"));
            assertTrue(sql.contains("lease_until"));
            assertTrue(sql.contains("attempt_count"));
            assertTrue(sql.contains("fencing_token"));
            assertTrue(sql.contains("uk_chat_request_scope_revision"));
            assertTrue(sql.contains("tenant_id, owner_jiacn, client_id, request_id, request_revision"));
            assertTrue(sql.contains("uk_chat_turn_request_target"));
            assertTrue(sql.contains("tenant_id, owner_jiacn, client_id, request_id, target_agent_id"));
            assertTrue(sql.contains("final_digest"));
            assertTrue(sql.contains("state_version"));
            assertTrue(compact.contains("uk_chat_outbox_turn_event (turn_id, event_type)"));
            assertTrue(sql.contains("engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_bin"));
            assertTrue(compact.contains("uk_chat_turn_request_target (tenant_id, owner_jiacn, client_id, request_id, target_agent_id)"));
            assertTrue(compact.contains("idx_chat_turn_conversation (tenant_id, owner_jiacn, client_id, conversation_id, conversation_generation, state, updated_at)"));
            assertFalse(sql.contains("drop table"));
            assertFalse(sql.contains("delete from"));
        }
    }

    @Test
    void initializerValidatesTypesCollationNullabilityUniquenessAndIndexOrder() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/cn/jia/chat/config/ChatDeliberationSchemaInitializer.java"));
        assertTrue(source.contains("character_maximum_length"));
        assertTrue(source.contains("is_nullable"));
        assertTrue(source.contains("collation_name"));
        assertTrue(source.contains("non_unique"));
        assertTrue(source.contains("seq_in_index"));
        assertTrue(source.contains("allow-additive-migration:false"));
        assertTrue(source.contains("ALGORITHM=INPLACE, LOCK=NONE"));
        assertFalse(source.contains("index_name.contains"));
        assertTrue(source.contains("PREFLIGHT"));
        assertTrue(source.contains("BACKFILLED"));
        assertTrue(source.contains("TIGHTENED"));
        assertTrue(source.contains("for (var entry : expected.entrySet())"));
        assertTrue(source.contains("validateExistingColumnsBeforeExpansion"));
        assertTrue(source.contains("isLegacyReadyIndex"));
        assertTrue(source.contains("DROP INDEX idx_chat_outbox_ready"));
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../jia-chat-mapper/src/main/resources/db/chat-deliberation-v2-migration.sql"));
        assertTrue(migration.contains("PREFLIGHT -> EXPANDED -> BACKFILLED -> TIGHTENED -> APPLIED"));
    }
}
