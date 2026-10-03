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
import static org.mockito.Mockito.mock;

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

    private Operation textOperation(String operationId, String key, String requestSha,
            long messageRevision, int start, int end, String sourceSha, String snapshot,
            String text, long now) {
        return new Operation(operationId, "0", "owner", "client", "42", 3L,
                key, requestSha, "textSelection", null, null, "1675335", messageRevision,
                start, end, sourceSha, snapshot, text, "PENDING", null, null, null,
                null, null, 1, now, now);
    }

    private void createSourceTables() {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT NOT NULL PRIMARY KEY, tenant_id VARCHAR(50) NOT NULL,
                  jiacn VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  conversation_type VARCHAR(20), conversation_scope_type VARCHAR(20),
                  conversation_scope_key VARCHAR(120), task_id VARCHAR(64), deleted_at BIGINT,
                  lifecycle_generation BIGINT NOT NULL, status INT DEFAULT 0
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
