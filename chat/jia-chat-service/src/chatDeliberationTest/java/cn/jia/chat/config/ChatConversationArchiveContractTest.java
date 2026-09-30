package cn.jia.chat.config;

import cn.jia.chat.api.ChatConversationArchiveController;
import cn.jia.chat.archive.conversation.ChatConversationArchiveService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationArchiveContractTest {
    @Test
    void controllerServiceAndInitializerAreExplicitlyDefaultOff() {
        for (Class<?> type : List.of(ChatConversationArchiveController.class,
                ChatConversationArchiveService.class, ChatConversationArchiveSchemaInitializer.class)) {
            ConditionalOnProperty conditional = type.getAnnotation(ConditionalOnProperty.class);
            assertNotNull(conditional, type.getName());
            assertEquals("chat.conversation-archive", conditional.prefix());
            assertArrayEquals(new String[]{"enabled"}, conditional.name());
            assertEquals("true", conditional.havingValue());
            assertFalse(conditional.matchIfMissing());
        }
    }

    @Test
    void additiveMigrationHasSourceAndKeyUniquenessAndConfirmedSavedChecks() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/chat-conversation-archive-schema.sql")) {
            if (stream == null) throw new AssertionError("archive schema resource missing");
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        String compact = sql.replaceAll("\\s+", " ");
        assertTrue(sql.contains("create table if not exists chat_conversation_archive_operation"));
        assertTrue(compact.contains("uk_chat_archive_key (tenant_id,owner_jiacn,client_id,idempotency_key)"));
        assertTrue(compact.contains("uk_chat_archive_source (tenant_id,owner_jiacn,client_id,asset_id,asset_revision)"));
        assertTrue(compact.contains("uk_chat_archive_workspace (tenant_id,owner_jiacn,client_id,workspace_operation_id)"));
        assertTrue(sql.contains("chk_chat_archive_saved_receipt"));
        assertTrue(sql.contains("file_version>=1"));
        assertTrue(sql.contains("engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_bin"));
        assertFalse(sql.contains("drop table"));
        assertFalse(sql.contains("delete from"));
        assertFalse(sql.contains("alter table chat_conversation_asset"));
    }

    @Test
    void sourceAuthorizationWalksLiveConversationRequestStepAndExecutionLink() throws Exception {
        String source = Files.readString(Path.of("../jia-chat-mapper/src/main/java/cn/jia/chat/archive/conversation/"
                + "JdbcChatConversationArchiveStore.java"));
        assertTrue(source.contains("FROM chat_conversation_asset a"));
        assertTrue(source.contains("JOIN chat_interaction_step s"));
        assertTrue(source.contains("JOIN chat_step_execution_link l"));
        assertTrue(source.contains("JOIN chat_request r"));
        assertTrue(source.contains("JOIN chat_conversation c"));
        assertTrue(source.contains("c.deleted_at IS NULL"));
        assertTrue(source.contains("c.lifecycle_generation=a.conversation_generation"));
        assertTrue(source.contains("BINARY l.execution_id=BINARY a.execution_id"));
        assertTrue(source.contains("a.run_id"));
        assertTrue(source.contains("s.state='OUTPUT_COMMITTED'"));
        assertTrue(source.contains("r.aggregate_state='OUTPUT_COMMITTED'"));
        assertFalse(source.contains("http://"));
        assertFalse(source.contains("https://"));
    }

    @Test
    void getPathIsDocumentedAndImplementedAsDatabaseOnly() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/cn/jia/chat/archive/conversation/ChatConversationArchiveService.java"));
        int begin = service.indexOf("public Receipt get(");
        int end = service.indexOf("private Operation claim", begin);
        String get = service.substring(begin, end);
        assertTrue(get.contains("store.findByOperationId"));
        assertFalse(get.contains("executions."));
        assertFalse(get.contains("workspace."));
        assertFalse(get.contains("readConversationOutput"));
        assertFalse(get.contains("archiveConversationAsset"));
    }
}
