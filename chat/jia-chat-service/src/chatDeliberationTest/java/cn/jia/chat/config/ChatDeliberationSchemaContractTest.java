package cn.jia.chat.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(migration.contains("CREATE PROCEDURE cyf_migrate_chat_deliberation_v2()"));
        assertTrue(migration.contains("DECLARE EXIT HANDLER FOR SQLEXCEPTION"));
        assertTrue(migration.contains("IF v_lock_acquired THEN"));
        assertTrue(migration.contains("GET_LOCK('cyf:chat-deliberation:v2', 10)"));
        assertTrue(migration.contains("IF v_lock_result IS NULL OR v_lock_result <> 1 THEN"));
        assertTrue(migration.contains("SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='chat deliberation v2 migration lock unavailable'"));
        assertTrue(migration.contains("RELEASE_LOCK('cyf:chat-deliberation:v2')"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS chat_conversation_event"));
        assertTrue(migration.contains("LEGACY_DISPATCH_UNRECOVERABLE"));
        assertTrue(migration.contains("LEGACY_CANCEL_UNRECOVERABLE_RESYNC_REQUIRED"));
        assertTrue(migration.contains("legacy-cancel-recovery:"));
        assertTrue(migration.contains("legacy-final:"));
        assertTrue(migration.contains("legacy-resync:"));
        assertTrue(migration.contains("chat outbox relay column contract mismatch"));
        assertTrue(migration.contains("active cancel payload scope contract mismatch"));
        int invariantGuard = migration.indexOf("-- APPLIED is reachable only after all schema and data guards above succeed.");
        int applied = migration.indexOf("VALUES(2,'APPLIED'");
        assertTrue(invariantGuard > 0 && applied > invariantGuard);
        assertTrue(migration.indexOf("SIGNAL SQLSTATE '45000'", migration.indexOf("DATA_BACKFILLED")) < applied);
        assertTrue(migration.contains("PREFLIGHT -> EXPANDED -> BACKFILLED -> TIGHTENED -> DATA_BACKFILLED -> APPLIED"));
        assertTrue(source.contains("acquireMigrationLock"));
        assertTrue(source.contains("initializeWhileLocked"));
        assertTrue(source.contains("backfillLegacyData"));
        assertTrue(source.contains("releaseMigrationLock"));
    }
    @Test
    void real1b5fa4ceCancelFixtureIsCanonicalizedInsteadOfTrustedAsValidJson() throws Exception {
        String fixture;
        try (var stream = getClass().getResourceAsStream("/fixtures/1b5fa4ce-cancel-payload.json")) {
            if (stream == null) throw new AssertionError("1b5fa4ce fixture missing");
            fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        @SuppressWarnings("unchecked")
        var legacy = (java.util.Map<String, Object>) cn.jia.core.util.JsonUtil.getMapper()
                .readValue(fixture, java.util.Map.class);
        assertEquals(java.util.Set.of("requestId", "turnId", "dispatchId", "reason"), legacy.keySet());
        assertFalse(legacy.containsKey("targetAgentId"));
        assertFalse(legacy.containsKey("conversationId"));
        assertFalse(legacy.containsKey("tenantId"));

        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../jia-chat-mapper/src/main/resources/db/chat-deliberation-v2-migration.sql"));
        assertTrue(migration.contains("Canonicalize every turn-backed cancel"));
        assertTrue(migration.contains("t.tenant_id=o.tenant_id AND t.owner_jiacn=o.owner_jiacn AND t.client_id=o.client_id"));
        assertTrue(migration.contains("'targetAgentId',t.target_agent_id"));
        assertTrue(migration.contains("'conversationId',t.conversation_id"));
        assertTrue(migration.contains("'tenantId',t.tenant_id"));
        assertTrue(migration.contains("WHERE o.event_type='CANCEL_REQUESTED';"));
    }

}
