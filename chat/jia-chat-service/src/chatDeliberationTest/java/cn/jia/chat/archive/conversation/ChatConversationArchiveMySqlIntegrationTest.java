package cn.jia.chat.archive.conversation;

import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import cn.jia.chat.config.ChatConversationArchiveSchemaInitializer;
import cn.jia.chat.config.ChatSchemaReadiness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.jia.chat.service.ChatTypedDeliberationService;
import cn.jia.chat.service.ChatActionFinalService;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.support.AopUtils;
import java.sql.SQLException;
import java.util.Map;

/** Isolated MySQL 8 DDL, transaction, scope and archive-recovery evidence; no Provider is called. */
@EnabledIfEnvironmentVariable(named = "MMD_U1_REFERENCE_MYSQL_URL", matches = ".+")
class ChatConversationArchiveMySqlIntegrationTest {
    private static final Scope OWNER = new Scope("0", "owner", "client");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private JdbcChatConversationArchiveStore store;
    private SpringChatConversationArchiveTransactions transactions;
    private String database;
    private String namespace;

    @BeforeEach
    void setUp() {
        var target = ChatConversationArchiveMySqlTestGuard.requireDisposable(System.getenv());
        namespace = target.databasePrefix() + "_archive_";
        database = namespace + UUID.randomUUID().toString().substring(0, 8);
        admin = new JdbcTemplate(dataSource(target.adminUrl(), target.user(), target.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source = dataSource(databaseUrl(target.adminUrl(), database), target.user(), target.password());
        jdbc = new JdbcTemplate(source);
        createSourceTables();
        execute("db/chat-conversation-archive-schema.sql");
        execute("db/chat-conversation-archive-text-selection-migration.sql");
        store = new JdbcChatConversationArchiveStore(jdbc);
        transactions = new SpringChatConversationArchiveTransactions(
                new DataSourceTransactionManager(source));
        insertOwnerMessage();
    }

    @AfterEach
    void tearDown() {
        if (admin == null || database == null) return;
        if (!database.startsWith(namespace)) throw new IllegalStateException("unowned AC12 database");
        admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test
    void persistsFrozenUtf8SnapshotAndEnforcesScopedUniquenessRecoveryCasAndRollback() {
        var sourceText = transactions.required(() ->
                store.findAuthorizedTextSourceForUpdate(OWNER, "42", 1675335L));
        assertNotNull(sourceText);
        assertEquals("画一只鸟", sourceText.content());
        String hash = sha(sourceText.content());
        long now = System.currentTimeMillis();
        Operation row = textOperation("arc_mysql_1", "archive-text-mysql-1", "1".repeat(64),
                sourceText.messageRevision(), 0, 4, hash, "2".repeat(64), sourceText.content(), now);
        assertTrue(transactions.required(() -> store.tryInsert(row)));

        Operation persisted = transactions.required(() ->
                store.lockByIdempotencyKey(OWNER, "archive-text-mysql-1"));
        assertEquals("画一只鸟", persisted.sourceText());
        assertEquals(hash, sha(persisted.sourceText()));
        assertEquals(3L, persisted.conversationGeneration());

        Operation sameKeyDifferentPayload = textOperation("arc_mysql_2", "archive-text-mysql-1",
                "3".repeat(64), sourceText.messageRevision(), 0, 2, sha("画一"),
                "4".repeat(64), "画一", now);
        assertFalse(transactions.required(() -> store.tryInsert(sameKeyDifferentPayload)));

        Operation sameSnapshotDifferentKey = textOperation("arc_mysql_3", "archive-text-mysql-2",
                "1".repeat(64), sourceText.messageRevision(), 0, 4, hash,
                "2".repeat(64), sourceText.content(), now);
        assertFalse(transactions.required(() -> store.tryInsert(sameSnapshotDifferentKey)));

        assertEquals(1, transactions.required(() ->
                store.markSaving(OWNER, row.operationId(), 1, 3, now + 1)));
        Operation saving = transactions.required(() ->
                store.lockByOperationId(OWNER, "42", row.operationId()));
        assertEquals("SAVING", saving.state());
        assertEquals("画一只鸟", saving.sourceText(),
                "unknown-response retry must retain the exact selected bytes");
        assertEquals(1, transactions.required(() -> store.markSaved(OWNER, row.operationId(),
                saving.rowRevision(), "workspace-op", "pws_file_1", 1, now + 2)));
        assertEquals("SAVED", store.findByOperationId(OWNER, "42", row.operationId()).state());

        Operation rolledBack = textOperation("arc_mysql_rollback", "archive-text-rollback",
                "5".repeat(64), sourceText.messageRevision(), 0, 2, sha("画一"),
                "6".repeat(64), "画一", now);
        assertThrows(IllegalStateException.class, () -> transactions.required(() -> {
            assertTrue(store.tryInsert(rolledBack));
            throw new IllegalStateException("force rollback");
        }));
        assertNull(store.findByOperationId(OWNER, "42", rolledBack.operationId()));
    }

    @Test
    void exactOwnerClientConversationAndLiveBountyScopeAreFailClosed() {
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                new Scope("0", "foreign", "client"), "42", 1675335L)));
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                new Scope("0", "owner", "foreign"), "42", 1675335L)));
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                OWNER, "43", 1675335L)));
        jdbc.update("UPDATE chat_conversation SET deleted_at=1 WHERE id=42");
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                OWNER, "42", 1675335L)));
    }

    @Test
    void pristineAndRepeatedInitializationPassButSameNamedWeakCheckFailsClosed() throws Exception {
        ChatSchemaReadiness readiness = mock(ChatSchemaReadiness.class);
        var initializer = new ChatConversationArchiveSchemaInitializer(jdbc, readiness);
        assertDoesNotThrow(initializer::afterPropertiesSet);
        String before = showCreate();
        assertDoesNotThrow(initializer::afterPropertiesSet);
        assertEquals(before, showCreate());

        jdbc.execute("ALTER TABLE chat_conversation_archive_operation "
                + "DROP CHECK chk_chat_archive_source_union, "
                + "ADD CONSTRAINT chk_chat_archive_source_union CHECK (1=1)");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                initializer::afterPropertiesSet);
        assertTrue(failure.getMessage().contains("check definition drift"), failure.getMessage());
    }

    @Test
    void migrationDoesNotMaskUnknownSameNamedLegacyCheckDrift() {
        jdbc.execute("DROP TABLE chat_conversation_archive_operation");
        createLegacyOperationTable();
        jdbc.execute("ALTER TABLE chat_conversation_archive_operation "
                + "DROP CHECK chk_chat_archive_request_sha, "
                + "ADD CONSTRAINT chk_chat_archive_request_sha CHECK (1=1)");

        execute("db/chat-conversation-archive-text-selection-migration.sql");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ChatConversationArchiveSchemaInitializer(
                        jdbc, mock(ChatSchemaReadiness.class)).afterPropertiesSet());
        assertTrue(failure.getMessage().contains(
                "check definition drift: chk_chat_archive_request_sha"), failure.getMessage());
    }

    @Test
    void legacyAssetTableUpgradesOncePreservesRowsAndRejectsInvalidUnionRows() throws Exception {
        jdbc.execute("DROP TABLE chat_conversation_archive_operation");
        createLegacyOperationTable();
        jdbc.update("""
                INSERT INTO chat_conversation_archive_operation
                  (operation_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   conversation_generation,idempotency_key,request_sha256,asset_id,asset_revision,
                   state,workspace_operation_id,file_id,file_version,error_code,message,row_revision,
                   created_at,updated_at)
                VALUES ('arc_legacy','0','owner','client','42',3,'archive-legacy-1',?,
                        'asset_legacy',1,'PENDING',NULL,NULL,NULL,NULL,NULL,1,1,1)
                """, "a".repeat(64));

        execute("db/chat-conversation-archive-text-selection-migration.sql");
        String once = showCreate();
        execute("db/chat-conversation-archive-text-selection-migration.sql");
        assertEquals(once, showCreate());
        assertEquals("assetRef", jdbc.queryForObject("""
                SELECT source_kind FROM chat_conversation_archive_operation
                WHERE operation_id='arc_legacy'
                """, String.class));
        assertEquals("YES", jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='chat_conversation_archive_operation'
                  AND column_name='asset_id'
                """, String.class));
        assertDoesNotThrow(() -> new ChatConversationArchiveSchemaInitializer(
                jdbc, mock(ChatSchemaReadiness.class)).afterPropertiesSet());
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO chat_conversation_archive_operation
                  (operation_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   conversation_generation,idempotency_key,request_sha256,source_kind,
                   asset_id,asset_revision,state,row_revision,created_at,updated_at)
                VALUES ('arc_invalid','0','owner','client','42',3,'archive-invalid-1',?,
                        'textSelection','asset',1,'PENDING',1,1,1)
                """, "b".repeat(64)));
    }

    @Test
    void migrationResumesAfterPartialAdditiveDdlWithoutRewritingLegacyOperation() {
        jdbc.execute("DROP TABLE chat_conversation_archive_operation");
        createLegacyOperationTable();
        jdbc.update("""
                INSERT INTO chat_conversation_archive_operation
                  (operation_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   conversation_generation,idempotency_key,request_sha256,asset_id,asset_revision,
                   state,row_revision,created_at,updated_at)
                VALUES ('arc_interrupted','0','owner','client','42',3,
                        'archive-interrupted-1',?,'asset_interrupted',1,'PENDING',1,1,1)
                """, "a".repeat(64));
        // Model a process interruption after committed MySQL DDL, before the remaining steps.
        jdbc.execute("""
                ALTER TABLE chat_conversation_archive_operation
                ADD COLUMN source_kind VARCHAR(20) NOT NULL DEFAULT 'assetRef' AFTER request_sha256
                """);
        jdbc.execute("""
                ALTER TABLE chat_conversation_archive_operation
                ADD COLUMN message_id VARCHAR(20) DEFAULT NULL AFTER asset_revision
                """);
        jdbc.execute("""
                ALTER TABLE chat_conversation_archive_operation
                MODIFY COLUMN asset_id VARCHAR(64) DEFAULT NULL
                """);
        var before = jdbc.queryForMap("""
                SELECT operation_id,asset_id,asset_revision,state,row_revision,request_sha256,
                       created_at,updated_at FROM chat_conversation_archive_operation
                WHERE operation_id='arc_interrupted'
                """);
        var initializer = new ChatConversationArchiveSchemaInitializer(
                jdbc, mock(ChatSchemaReadiness.class));
        assertDoesNotThrow(initializer::afterPropertiesSet);
        assertEquals(before, jdbc.queryForMap("""
                SELECT operation_id,asset_id,asset_revision,state,row_revision,request_sha256,
                       created_at,updated_at FROM chat_conversation_archive_operation
                WHERE operation_id='arc_interrupted'
                """));
        String resumed = showCreate();
        assertDoesNotThrow(initializer::afterPropertiesSet);
        assertEquals(resumed, showCreate());
    }

    @Test
    void sourceUnionRejectsSqlUnknownForEveryRequiredTextFieldAndAssetRevision() {
        var text = transactions.required(() ->
                store.findAuthorizedTextSourceForUpdate(OWNER, "42", 1675335L));
        assertNotNull(text);
        Operation row = textOperation("arc_null_guard", "archive-text-null-guard", "1".repeat(64),
                text.messageRevision(), 0, 4, sha(text.content()), "2".repeat(64),
                text.content(), System.currentTimeMillis());
        assertTrue(transactions.required(() -> store.tryInsert(row)));
        for (String column : java.util.List.of("conversation_generation", "message_id",
                "message_revision", "selection_start_code_point", "selection_end_code_point",
                "source_sha256", "source_snapshot_key", "source_text")) {
            assertThrows(DataAccessException.class, () -> jdbc.update(
                    "UPDATE chat_conversation_archive_operation SET " + column
                            + "=NULL WHERE operation_id='arc_null_guard'"), column);
        }
        jdbc.update("""
                INSERT INTO chat_conversation_archive_operation
                  (operation_id,tenant_id,owner_jiacn,client_id,conversation_id,
                   idempotency_key,request_sha256,source_kind,asset_id,asset_revision,
                   state,row_revision,created_at,updated_at)
                VALUES ('arc_asset_null_guard','0','owner','client','42',
                        'archive-asset-null-guard',?,'assetRef','asset_null_guard',1,'PENDING',1,1,1)
                """, "a".repeat(64));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
                UPDATE chat_conversation_archive_operation SET asset_revision=NULL
                WHERE operation_id='arc_asset_null_guard'
                """));
        assertEquals("画一只鸟", jdbc.queryForObject("""
                SELECT source_text FROM chat_conversation_archive_operation
                WHERE operation_id='arc_null_guard'
                """, String.class));
    }

    private Operation textOperation(String operationId, String key, String requestSha,
            long messageRevision, int start, int end, String sourceSha, String snapshot,
            String text, long now) {
        return new Operation(operationId, "0", "owner", "client", "42", 3L,
                key, requestSha, "textSelection", null, null, "1675335", messageRevision,
                start, end, sourceSha, snapshot, text, "PENDING", null, null, null,
                null, null, 1, now, now);
    }

    @Test
    void committedAssetAuthorizationUsesExactTerminalLinkForBothReaders() {
        insertCommittedAsset();
        var unlocked = store.findAuthorizedSource(OWNER, "42", "asset", 1, "agent");
        var locked = transactions.required(() ->
                store.findAuthorizedSourceForUpdate(OWNER, "42", "asset", 1, "agent"));
        assertNotNull(unlocked);
        assertEquals(unlocked, locked);
        assertEquals("execution", locked.executionId());
        assertEquals("run", locked.runId());
        assertEquals("output", locked.outputId());
        assertEquals("image/png", locked.contentMimeType());
        assertEquals("a".repeat(64), locked.sha256());
        assertEquals(10, locked.byteLength());
    }

    @Test
    void committedAssetAuthorizationRejectsEveryNonterminalLinkAndExactScopeDrift() {
        insertCommittedAsset();
        for (String state : new String[]{"RUNNING", "WAITING_ADMISSION", "FAILED", "INPUTS_REVOKED"}) {
            jdbc.update("UPDATE chat_step_execution_link SET state=?", state);
            assertAssetRejected();
        }
        jdbc.update("UPDATE chat_step_execution_link SET state='OUTPUT_COMMITTED'");
        for (Scope scope : new Scope[]{new Scope("1", "owner", "client"),
                new Scope("0", "foreign", "client"), new Scope("0", "owner", "foreign"),
                new Scope("0", "owner ", "client")}) {
            assertNull(store.findAuthorizedSource(scope, "42", "asset", 1, "agent"));
            assertNull(transactions.required(() ->
                    store.findAuthorizedSourceForUpdate(scope, "42", "asset", 1, "agent")));
        }
        assertNull(store.findAuthorizedSource(OWNER, "42", "asset", 2, "agent"));
        assertNull(store.findAuthorizedSource(OWNER, "42", "asset ", 1, "agent"));
        assertNull(store.findAuthorizedSource(OWNER, "042", "asset", 1, "agent"));
        assertNull(store.findAuthorizedSource(OWNER, "42", "asset", 1, "foreign"));
        for (String[] damage : new String[][]{
                {"chat_conversation", "lifecycle_generation", "4", "3"},
                {"chat_conversation", "deleted_at", "1", null},
                {"chat_conversation", "conversation_scope_key", "task:foreign", "task:task-1"},
                {"chat_conversation", "target_agent_ids", "[\"foreign\"]", "[\"agent\"]"},
                {"chat_conversation", "target_agent_ids", "[\"agent\",\"foreign\"]", "[\"agent\"]"},
                {"chat_interaction_step", "request_revision", "2", "1"},
                {"chat_interaction_step", "kind", "CHAT", "EXECUTE"},
                {"chat_interaction_step", "state", "RUNNING", "OUTPUT_COMMITTED"},
                {"chat_interaction_step", "request_id", "foreign", "producer"},
                {"chat_interaction_step", "step_id", "foreign", "step"},
                {"chat_interaction_step", "task_id", "foreign", "task-1"},
                {"chat_step_execution_link", "execution_id", "foreign", "execution"},
                {"chat_step_execution_link", "owner_jiacn", "foreign", "owner"},
                {"chat_request", "aggregate_state", "RUNNING", "OUTPUT_COMMITTED"}}) {
            // Identifiers are compile-time fixture constants, never request payload.
            String sql = "UPDATE " + damage[0] + " SET " + damage[1] + "=?";
            jdbc.update(sql, damage[2]);
            assertAssetRejected();
            jdbc.update(sql, damage[3]);
            assertNotNull(store.findAuthorizedSource(OWNER, "42", "asset", 1, "agent"));
        }
    }

    @Test
    void committedAssetAuthorizationRejectsAmbiguousRowsAndNullExecution() {
        insertCommittedAsset();
        jdbc.update("INSERT INTO chat_conversation_asset SELECT * FROM chat_conversation_asset");
        assertAssetRejected();
        jdbc.update("DELETE FROM chat_conversation_asset LIMIT 1");
        jdbc.update("UPDATE chat_step_execution_link SET execution_id=NULL");
        assertAssetRejected();
    }

    @Test
    void typedOutcomeReadAllowsRequiredJdbcLockWithoutMutation() {
        var before = jdbc.queryForList("SELECT * FROM chat_conversation");
        try (var context = lockingReadContext()) {
            var typed = context.getBean(ChatTypedDeliberationService.class);
            assertTrue(AopUtils.isCglibProxy(typed));
            assertEquals(Map.of("state", "READY"), typed.read(
                    new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 3),
                    "request", "turn", 1));
        }
        assertEquals(before, jdbc.queryForList("SELECT * FROM chat_conversation"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void explicitReadOnlyCallerStillRejectsRequiredJdbcLockWithoutTransactionSplitting() {
        try (var context = lockingReadContext()) {
            var typed = context.getBean(ChatTypedDeliberationService.class);
            var readOnly = new TransactionTemplate(new DataSourceTransactionManager(source));
            readOnly.setReadOnly(true);
            DataAccessException failure = assertThrows(DataAccessException.class, () -> readOnly.execute(status ->
                    typed.read(new ChatTypedDeliberationStore.Scope("0", "owner", "client", "42", 3),
                            "request", "turn", 1)));
            Throwable cause = failure;
            while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
            assertInstanceOf(SQLException.class, cause);
            var sql = (SQLException) cause;
            assertEquals(1792, sql.getErrorCode());
            assertEquals("25006", sql.getSQLState());
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    private AnnotationConfigApplicationContext lockingReadContext() {
        ChatActionFinalService finals = mock(ChatActionFinalService.class);
        when(finals.readIfV3(any(), eq("request"), eq("turn"), eq(1L), eq("CHAT")))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    return transactions.required(() -> {
                        jdbc.queryForObject("SELECT id FROM chat_conversation WHERE id=42 FOR UPDATE", Long.class);
                        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                        return Map.of("state", "READY");
                    });
                });
        var context = new AnnotationConfigApplicationContext();
        context.register(LockingReadConfiguration.class);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(source));
        context.registerBean(ChatTypedDeliberationService.class, () -> {
            var typed = new ChatTypedDeliberationService(mock(ChatTypedDeliberationStore.class));
            typed.setActionFinals(finals);
            return typed;
        });
        context.refresh();
        return context;
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class LockingReadConfiguration { }

    private void assertAssetRejected() {
        assertNull(store.findAuthorizedSource(OWNER, "42", "asset", 1, "agent"));
        assertNull(transactions.required(() ->
                store.findAuthorizedSourceForUpdate(OWNER, "42", "asset", 1, "agent")));
    }

    private void insertCommittedAsset() {
        jdbc.update("UPDATE chat_conversation SET target_agent_ids='[\"agent\"]' WHERE id=42");
        jdbc.update("INSERT INTO chat_conversation_asset VALUES "
                + "('asset','0','owner','client','42',3,'producer','step','execution','run','output','image/png',?,10,1)",
                "a".repeat(64));
        jdbc.update("INSERT INTO chat_interaction_step VALUES "
                + "('step','0','owner','client','producer',1,'42',3,'task-1','EXECUTE','OUTPUT_COMMITTED')");
        jdbc.update("INSERT INTO chat_step_execution_link VALUES "
                + "('0','owner','client','step','execution','OUTPUT_COMMITTED')");
        jdbc.update("INSERT INTO chat_request VALUES "
                + "('0','owner','client','producer',1,'42',3,'OUTPUT_COMMITTED')");
    }

    private void createSourceTables() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT NOT NULL PRIMARY KEY, tenant_id VARCHAR(50) NOT NULL,
                  jiacn VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  conversation_type VARCHAR(20), conversation_scope_type VARCHAR(20),
                  conversation_scope_key VARCHAR(120), task_id VARCHAR(64), deleted_at BIGINT,
                  lifecycle_generation BIGINT NOT NULL, status INT DEFAULT 0, target_agent_ids JSON
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                  id BIGINT NOT NULL PRIMARY KEY, conversation_id VARCHAR(100) NOT NULL,
                  message_type VARCHAR(20), content TEXT, create_time BIGINT, update_time BIGINT,
                  tenant_id VARCHAR(50), client_id VARCHAR(50), jiacn VARCHAR(50),
                  conversation_type VARCHAR(20)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation_asset (
                  asset_id VARCHAR(64),tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),
                  conversation_id VARCHAR(100),conversation_generation BIGINT,request_id VARCHAR(100),
                  step_id VARCHAR(64),execution_id VARCHAR(100),run_id VARCHAR(100),output_id VARCHAR(100),
                  content_mime_type VARCHAR(127),sha256 CHAR(64),byte_length BIGINT,revision BIGINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_interaction_step (
                  step_id VARCHAR(64),tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),
                  request_id VARCHAR(100),request_revision BIGINT,conversation_id VARCHAR(100),
                  conversation_generation BIGINT,task_id VARCHAR(100),kind VARCHAR(20),state VARCHAR(30)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_step_execution_link (
                  tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),
                  step_id VARCHAR(64),execution_id VARCHAR(100),state VARCHAR(30)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_request (
                  tenant_id VARCHAR(50),owner_jiacn VARCHAR(50),client_id VARCHAR(50),
                  request_id VARCHAR(100),request_revision BIGINT,conversation_id VARCHAR(100),
                  conversation_generation BIGINT,aggregate_state VARCHAR(30)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void createLegacyOperationTable() {
        jdbc.execute("""
                CREATE TABLE chat_conversation_archive_operation (
                  operation_id VARCHAR(64) NOT NULL,tenant_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  conversation_id VARCHAR(100) NOT NULL,conversation_generation BIGINT DEFAULT NULL,
                  idempotency_key VARCHAR(160) NOT NULL,request_sha256 CHAR(64) NOT NULL,
                  asset_id VARCHAR(64) NOT NULL,asset_revision BIGINT NOT NULL,state VARCHAR(20) NOT NULL,
                  workspace_operation_id VARCHAR(100),file_id VARCHAR(100),file_version INT,
                  error_code VARCHAR(64),message VARCHAR(255),row_revision BIGINT NOT NULL DEFAULT 1,
                  created_at BIGINT NOT NULL,updated_at BIGINT NOT NULL,
                  PRIMARY KEY(operation_id),
                  UNIQUE KEY uk_chat_archive_key(tenant_id,owner_jiacn,client_id,idempotency_key),
                  UNIQUE KEY uk_chat_archive_source(tenant_id,owner_jiacn,client_id,asset_id,asset_revision),
                  UNIQUE KEY uk_chat_archive_workspace(tenant_id,owner_jiacn,client_id,workspace_operation_id),
                  KEY idx_chat_archive_conversation(tenant_id,owner_jiacn,client_id,conversation_id,updated_at,operation_id),
                  CONSTRAINT chk_chat_archive_asset_revision CHECK(asset_revision>=1),
                  CONSTRAINT chk_chat_archive_generation CHECK(conversation_generation IS NULL OR conversation_generation>=1),
                  CONSTRAINT chk_chat_archive_key_length CHECK(CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160),
                  CONSTRAINT chk_chat_archive_request_sha CHECK(CHAR_LENGTH(request_sha256)=64),
                  CONSTRAINT chk_chat_archive_state CHECK(state IN ('PENDING','SAVING','SAVED','PARTIAL_FAILED')),
                  CONSTRAINT chk_chat_archive_row_revision CHECK(row_revision>=1),
                  CONSTRAINT chk_chat_archive_saved_receipt CHECK(state<>'SAVED' OR (conversation_generation>=1 AND workspace_operation_id IS NOT NULL AND file_id IS NOT NULL AND file_version>=1))
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void insertOwnerMessage() {
        jdbc.update("""
                INSERT INTO chat_conversation
                  (id,tenant_id,jiacn,client_id,conversation_type,conversation_scope_type,
                   conversation_scope_key,task_id,deleted_at,lifecycle_generation,status)
                VALUES (42,'0','owner','client','juyiting','bounty','task:task-1','task-1',NULL,3,0)
                """);
        jdbc.update("""
                INSERT INTO chat_message
                  (id,conversation_id,message_type,content,create_time,update_time,
                   tenant_id,client_id,jiacn,conversation_type)
                VALUES (1675335,'42','USER','画一只鸟',1700000000000,1700000000000,
                        '0','client','owner','juyiting')
                """);
    }

    private void execute(String resource) {
        new ResourceDatabasePopulator(new ClassPathResource(resource)).execute(source);
    }

    private String showCreate() {
        return jdbc.queryForObject("SHOW CREATE TABLE chat_conversation_archive_operation",
                (result, row) -> result.getString(2));
    }

    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static String databaseUrl(String base, String database) {
        int query = base.indexOf('?');
        String suffix = query < 0 ? "" : base.substring(query);
        String plain = query < 0 ? base : base.substring(0, query);
        return plain.substring(0, plain.lastIndexOf('/') + 1) + database + suffix;
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
