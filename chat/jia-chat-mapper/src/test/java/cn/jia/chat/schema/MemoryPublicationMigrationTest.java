package cn.jia.chat.schema;

import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.mapper.MemoryPublicationMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPublicationMigrationTest {
    @Test
    void mainMigrationIsDigestBoundResumableAndRejectsSchemaDrift() throws Exception {
        String sql = normalized("db/memory-publication-v2-20260924.sql");

        assertTrue(sql.contains("migration_digest char(64)"), sql);
        assertTrue(sql.contains("set v_digest = sha2("), sql);
        assertTrue(sql.contains("v_state = 'applied'"), sql);
        assertTrue(sql.contains("v_state <> 'applying'"), sql);
        assertTrue(sql.contains("migration digest mismatch"), sql);
        assertTrue(sql.contains("contract-v3"), sql);
        assertTrue(sql.contains("stable-payload"), sql);
        assertTrue(sql.contains("summary_content longtext"), sql);
        assertTrue(sql.contains("embedding_bytes blob"), sql);
        assertTrue(sql.contains("payload_digest char(64) character set ascii collate ascii_bin"), sql);
        assertTrue(sql.contains("state in (''allocated'', ''staged'', ''published'', ''tombstoned'')"), sql);
        assertTrue(sql.contains("chk_memory_publication_payload"), sql);
        assertTrue(sql.contains("unledgered partial schema detected"), sql);
        assertTrue(sql.contains("applied publication table is missing"), sql);
        assertTrue(sql.contains("information_schema.columns"), sql);
        assertTrue(sql.contains("information_schema.statistics"), sql);
        assertTrue(sql.contains("information_schema.check_constraints"), sql);
        assertTrue(sql.contains("table_collation = 'utf8mb4_0900_bin'"), sql);
        assertTrue(sql.contains("column definition drift"), sql);
        assertTrue(sql.contains("table definition drift"), sql);
        assertTrue(sql.contains("get_lock(@cyf_memory_v2_lock_name, 0)"), sql);
        assertTrue(sql.contains("release_lock(@cyf_memory_v2_lock_name)"), sql);
        assertTrue(sql.contains("join chat_conversation_scope_audit"), sql);
        assertTrue(sql.contains("binary a.scope_key = binary c.data_domain_id"), sql);
        assertTrue(sql.contains("set @cyf_memory_v2_sql = v_sql"), sql);
        assertTrue(sql.contains("prepare cyf_memory_v2_stmt from @cyf_memory_v2_sql"), sql);
        assertFalse(sql.contains("prepare cyf_memory_v2_stmt from v_sql"), sql);
        assertTrue(sql.contains("if @@session.group_concat_max_len < 65535 then"), sql);
        assertTrue(sql.contains("set session group_concat_max_len = 65535"), sql);
        assertFalse(sql.contains("set session group_concat_max_len = greatest("), sql);
        assertTrue(sql.contains("upper(cc.check_clause), '_utf8mb4', ''"), sql);
        assertTrue(sql.contains("'_ascii', ''"), sql);
        assertTrue(sql.contains("'\\\\', ''"), sql);
        assertFalse(sql.contains("single-apply"), sql);
        assertFalse(sql.contains("add column if not exists"), sql);
        assertFalse(sql.contains("case when tenant_id = '0' then 'legacy_client' else 'domain' end"), sql);
    }

    @Test
    void pendingBackfillIsIdempotentPreservesLeasesAndDoesNotPoisonCanonicalWork() throws Exception {
        String sql = normalized("db/memory-publication-v2-pending-backfill-20260924.sql");

        assertTrue(sql.contains("insert into chat_memory_work"), sql);
        assertTrue(sql.contains("on duplicate key update"), sql);
        assertTrue(sql.contains("case when state = 'leased' then lifecycle_generation"), sql);
        assertTrue(sql.contains("wake_requested = case when state = 'leased' then 1 else 0 end"), sql);
        assertTrue(sql.contains("state = case when state = 'leased' then state else values(state) end"), sql);
        assertTrue(sql.contains("historical task-thread pending rows are excluded"), sql);
        assertTrue(sql.contains("mixed-scope pending rows ignored; canonical pending work remains processable"), sql);
        assertTrue(sql.contains("select 1 from chat_message canonical"), sql);
        assertTrue(sql.contains("mixed-scope pollution report"), sql);
        assertTrue(sql.contains("canonical, non-task-thread pending rows without their exact work item"), sql);
        assertFalse(sql.contains("historical pending rows contain mixed scope identity"), sql);
    }

    @Test
    void pendingBackfillFailsClosedBeforeWritingInvalidCanonicalScope() throws Exception {
        String sql = normalized("db/memory-publication-v2-pending-backfill-20260924.sql");
        int orphanGuard = sql.indexOf("memory backfill has orphan pending messages");
        int scopeGuard = sql.indexOf("memory backfill has invalid canonical scope");
        int insert = sql.indexOf("insert into chat_memory_work");

        assertTrue(orphanGuard >= 0 && orphanGuard < insert, sql);
        assertTrue(scopeGuard > orphanGuard && scopeGuard < insert, sql);
        assertTrue(sql.contains("binary cast(c.id as char) = binary m.conversation_id"), sql);
        assertTrue(sql.contains("octet_length(cast(c.id as char))"), sql);
        assertTrue(sql.contains("deploy every message signal writer"), sql);
        assertTrue(sql.contains("keep all memory publication workers disabled"), sql);
        assertTrue(sql.contains("enable workers only after"), sql);
    }

    @Test
    void canonicalAndPollutedPendingRowsStillCreateAndWakeCanonicalWorkInH2() throws Exception {
        JdbcTemplate jdbc = h2();
        createBackfillSchema(jdbc);
        jdbc.update("""
                INSERT INTO chat_conversation
                    (id, tenant_id, data_scope_mode, client_id, data_domain_id, jiacn,
                     lifecycle_generation, conversation_scope_type, deleted_at)
                VALUES (41, '1', 'DOMAIN', 'web-client', 'cyf', 'tester', 7, 'normal', NULL)
                """);
        jdbc.update("""
                INSERT INTO chat_message
                    (id, conversation_id, sync_status, tenant_id, jiacn, client_id, data_domain_id)
                VALUES (1, '41', 'PENDING', '1', 'tester', 'web-client', 'cyf'),
                       (2, '41', 'PENDING', '2', 'tester', 'web-client', 'foreign')
                """);

        String upsert = h2Compatible(extractCanonicalUpsert())
                .replace("@cyf_memory_backfill_now", "1000");
        jdbc.execute(upsert);

        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM chat_memory_work WHERE conversation_id = '41'", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT last_error FROM chat_memory_work WHERE conversation_id = '41'",
                String.class).contains("canonical PENDING work remains processable"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_work", Integer.class));

        jdbc.update("""
                UPDATE chat_memory_work
                SET state = 'LEASED', wake_requested = 0, last_error = NULL,
                    lease_owner = 'worker-a', lease_until = 2000
                WHERE conversation_id = '41'
                """);
        jdbc.execute(upsert);
        assertEquals("LEASED", jdbc.queryForObject(
                "SELECT state FROM chat_memory_work WHERE conversation_id = '41'", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT wake_requested FROM chat_memory_work WHERE conversation_id = '41'",
                Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT last_error FROM chat_memory_work WHERE conversation_id = '41'",
                String.class).contains("canonical PENDING work remains processable"));
    }

    @Test
    void runtimeSignalDerivesWorkScopeFromCanonicalConversation() throws Exception {
        Method method = MemoryPublicationMapper.class.getMethod(
                "signalForInsertedMessage", long.class, long.class);
        Insert insert = method.getAnnotation(Insert.class);
        assertNotNull(insert);
        String sql = String.join(" ", insert.value())
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertTrue(sql.contains("select c.tenant_id, c.data_scope_mode"), sql);
        assertTrue(sql.contains("case when c.data_scope_mode = 'legacy_client'"), sql);
        assertTrue(sql.contains("then c.client_id else c.data_domain_id end"), sql);
        assertTrue(sql.contains("m.tenant_id = c.tenant_id"), sql);
        assertTrue(sql.contains("c.data_scope_mode = 'domain' and m.data_domain_id = c.data_domain_id"), sql);
        assertTrue(sql.contains("c.data_scope_mode = 'legacy_client' and m.client_id = c.client_id"), sql);
    }

    @Test
    void payloadBindingIsSingleAllocatedCasFollowedByLockedWinnerRead() throws Exception {
        Method bind = MemoryPublicationMapper.class.getMethod(
                "bindPayload", long.class, String.class, byte[].class, String.class, long.class);
        Update update = bind.getAnnotation(Update.class);
        assertNotNull(update);
        String updateSql = String.join(" ", update.value())
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertTrue(updateSql.contains("set state = 'staged'"), updateSql);
        assertTrue(updateSql.contains("where id = #{id} and state = 'allocated'"), updateSql);
        assertTrue(updateSql.contains("summary_content is null"), updateSql);
        assertTrue(updateSql.contains("embedding_bytes is null"), updateSql);
        assertTrue(updateSql.contains("payload_digest is null"), updateSql);

        Method winnerRead = MemoryPublicationMapper.class.getMethod(
                "findByIdForUpdate", long.class);
        Select select = winnerRead.getAnnotation(Select.class);
        assertNotNull(select);
        assertTrue(String.join(" ", select.value()).toLowerCase(Locale.ROOT)
                .contains("for update"));
    }

    @Test
    void publicationPayloadCodecRejectsEmptyNonFiniteTruncatedAndDigestDrift() {
        MemoryPublicationPayload generated = MemoryPublicationPayload.generated(
                "summary", new float[]{0.25f, -0.5f});
        assertEquals("summary", generated.summaryContent());
        assertArrayEquals(new float[]{0.25f, -0.5f}, generated.embedding());
        assertEquals(64, generated.payloadDigest().length());

        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.generated(" ", new float[]{0.25f}));
        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.generated("summary", new float[0]));
        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.generated(
                        "summary", new float[]{Float.NaN}));
        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.stored(
                        generated.summaryContent(), generated.embeddingBytes(), "0".repeat(64)));
        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.stored(
                        generated.summaryContent(), new byte[]{0, 0, 0, 1},
                        generated.payloadDigest()));

        byte[] nonFinite = ByteBuffer.allocate(8)
                .putInt(1).putInt(Float.floatToIntBits(Float.POSITIVE_INFINITY)).array();
        assertThrows(IllegalStateException.class,
                () -> MemoryPublicationPayload.stored(
                        generated.summaryContent(), nonFinite, generated.payloadDigest()));
    }

    private JdbcTemplate h2() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:memory_backfill_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        return new JdbcTemplate(source);
    }

    private void createBackfillSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT PRIMARY KEY, tenant_id VARCHAR(50) NOT NULL,
                  data_scope_mode VARCHAR(20) NOT NULL, client_id VARCHAR(50),
                  data_domain_id VARCHAR(64), jiacn VARCHAR(50) NOT NULL,
                  lifecycle_generation BIGINT NOT NULL,
                  conversation_scope_type VARCHAR(20) NOT NULL, deleted_at BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE chat_message (
                  id BIGINT PRIMARY KEY, conversation_id VARCHAR(100) NOT NULL,
                  sync_status VARCHAR(20), tenant_id VARCHAR(50), jiacn VARCHAR(50),
                  client_id VARCHAR(50), data_domain_id VARCHAR(64))
                """);
        jdbc.execute("CREATE TABLE agent_task_thread (conversation_id VARCHAR(100) PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE chat_memory_work (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(50) NOT NULL,
                  scope_mode VARCHAR(20) NOT NULL, scope_key VARCHAR(64) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL, conversation_id VARCHAR(100) NOT NULL,
                  lifecycle_generation BIGINT NOT NULL, state VARCHAR(20) NOT NULL,
                  due_at BIGINT NOT NULL, lease_owner VARCHAR(100), lease_until BIGINT,
                  attempts INT NOT NULL DEFAULT 0, wake_requested TINYINT NOT NULL DEFAULT 0,
                  last_error VARCHAR(1000), create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  UNIQUE (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id))
                """);
    }

    private String h2Compatible(String mysql) {
        return mysql
                .replace("CAST(c.id AS CHAR)", "CAST(c.id AS VARCHAR)")
                .replace("BINARY CAST(c.id AS VARCHAR)",
                        "CAST(CAST(c.id AS VARCHAR) AS BINARY)")
                .replaceAll("BINARY ([A-Za-z_][A-Za-z0-9_.]*)",
                        "CAST($1 AS BINARY)");
    }

    private String extractCanonicalUpsert() throws Exception {
        String sql = raw("db/memory-publication-v2-pending-backfill-20260924.sql");
        String begin = "-- BEGIN CANONICAL_PENDING_WORK_UPSERT";
        String end = "-- END CANONICAL_PENDING_WORK_UPSERT";
        int start = sql.indexOf(begin);
        int finish = sql.indexOf(end);
        assertTrue(start >= 0 && finish > start, sql);
        return sql.substring(start + begin.length(), finish).trim();
    }

    private String raw(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String normalized(String resource) throws Exception {
        return raw(resource).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
