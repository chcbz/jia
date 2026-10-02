package cn.jia.chat.archive.conversation;

import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Operation;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore.Scope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in destructive evidence against a disposable loopback MySQL database only.
 * No default test run opens a connection or performs DDL/DML.
 */
class ChatConversationArchiveMySqlIntegrationTest {
    private static final Scope OWNER = new Scope("0", "owner", "client");
    private JdbcTemplate jdbc;
    private JdbcChatConversationArchiveStore store;
    private SpringChatConversationArchiveTransactions transactions;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_AC12_MYSQL_ISOLATED")),
                "requires explicit isolated-MySQL acknowledgement");
        var target = ChatConversationArchiveMySqlTestGuard.requireDisposable(System.getenv());
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(target.url());
        dataSource.setUsername(System.getenv().getOrDefault("CYF_AC12_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_AC12_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        clean();
        createSourceTables();
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(
                new ClassPathResource("db/chat-conversation-archive-schema.sql"));
        schema.execute(dataSource);
        store = new JdbcChatConversationArchiveStore(jdbc);
        transactions = new SpringChatConversationArchiveTransactions(
                new DataSourceTransactionManager(dataSource));
        insertOwnerMessage();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) clean();
    }

    @Test
    void persistsFrozenUtf8SnapshotAndEnforcesScopedUniquenessAndRecoveryCas() {
        var source = transactions.required(() ->
                store.findAuthorizedTextSourceForUpdate(OWNER, "42", 1675335L));
        assertNotNull(source);
        assertEquals("画一只鸟", source.content());
        String hash = sha(source.content());
        long now = System.currentTimeMillis();
        Operation row = new Operation("arc_mysql_1", "0", "owner", "client", "42", 3L,
                "archive-text-mysql-1", "1".repeat(64), "textSelection", null, null,
                "1675335", source.messageRevision(), 0, 4, hash, "2".repeat(64),
                source.content(), "PENDING", null, null, null, null, null, 1, now, now);
        assertTrue(transactions.required(() -> store.tryInsert(row)));

        Operation persisted = transactions.required(() ->
                store.lockByIdempotencyKey(OWNER, "archive-text-mysql-1"));
        assertEquals("画一只鸟", persisted.sourceText());
        assertEquals(hash, sha(persisted.sourceText()));
        assertEquals(3L, persisted.conversationGeneration());

        Operation sameKeyDifferentPayload = new Operation("arc_mysql_2", "0", "owner", "client", "42", 3L,
                "archive-text-mysql-1", "3".repeat(64), "textSelection", null, null,
                "1675335", source.messageRevision(), 0, 2, sha("画一"), "4".repeat(64),
                "画一", "PENDING", null, null, null, null, null, 1, now, now);
        assertFalse(transactions.required(() -> store.tryInsert(sameKeyDifferentPayload)));

        Operation sameSnapshotDifferentKey = new Operation("arc_mysql_3", "0", "owner", "client", "42", 3L,
                "archive-text-mysql-2", "1".repeat(64), "textSelection", null, null,
                "1675335", source.messageRevision(), 0, 4, hash, "2".repeat(64),
                source.content(), "PENDING", null, null, null, null, null, 1, now, now);
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
    }

    @Test
    void exactOwnerClientConversationAndLiveBountyScopeAreFailClosed() {
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                new Scope("0", "foreign", "client"), "42", 1675335L)));
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                OWNER, "43", 1675335L)));
        jdbc.update("UPDATE chat_conversation SET deleted_at=1 WHERE id=42");
        assertNull(transactions.required(() -> store.findAuthorizedTextSourceForUpdate(
                OWNER, "42", 1675335L)));
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

    private void clean() {
        jdbc.execute("DROP TABLE IF EXISTS chat_conversation_archive_operation");
        jdbc.execute("DROP TABLE IF EXISTS chat_message");
        jdbc.execute("DROP TABLE IF EXISTS chat_conversation");
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
