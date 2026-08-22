package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcArchiveQuestionStoreTest {
    @Test
    void everyQuestionLookupMutationAndCasUsesExactThreeComponentByteScope() throws Exception {
        Field field = JdbcArchiveQuestionStore.class.getDeclaredField("EXACT_SCOPE");
        field.setAccessible(true);
        String sql = (String) field.get(null);
        for (String column : new String[]{"tenant_id", "client_id", "owner_jiacn"}) {
            assertTrue(sql.contains(column + "=?"), column);
            assertTrue(sql.contains("CAST(" + column + " AS BINARY)=CAST(? AS BINARY)"), column);
            assertTrue(sql.contains("OCTET_LENGTH(" + column + ")=OCTET_LENGTH(?)"), column);
        }
        assertEquals(9, sql.length() - sql.replace("?", "").length());
    }

    @Test
    void replayQueryFreezesExclusiveInclusiveBoundsExactQuestionAndAscendingSequence() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcArchiveQuestionStore store = new JdbcArchiveQuestionStore(jdbc);
        ArchiveOwnerScope owner = new ArchiveOwnerScope("tenant", "client", "owner");
        store.listEvents(owner, "123e4567-e89b-42d3-a456-426614174000", 7, 19, 100);
        assertTrue(jdbc.sql.contains("sequence>? AND sequence<=? ORDER BY sequence LIMIT ?"));
        assertEquals(15, jdbc.args.length);
        assertEquals(7L, jdbc.args[12]);
        assertEquals(19L, jdbc.args[13]);
        assertEquals(100, jdbc.args[14]);
        assertTrue(jdbc.sql.contains("CAST(question_id AS BINARY)=CAST(? AS BINARY)"));
    }

    @Test
    void ownerArraysAreFlattenedForMutationAndCasSqlArguments() throws Exception {
        Method scopeArgs = JdbcArchiveQuestionStore.class.getDeclaredMethod("scopeArgs", ArchiveOwnerScope.class);
        scopeArgs.setAccessible(true);
        Object[] scope = (Object[]) scopeArgs.invoke(null, new ArchiveOwnerScope("tenant", "client", "owner"));
        Method concat = JdbcArchiveQuestionStore.class.getDeclaredMethod(
                "concat", Object[].class, Object[].class, Object[].class);
        concat.setAccessible(true);
        Object[] actual = (Object[]) concat.invoke(null, new Object[]{new Object[]{"state", 3L}, scope,
                new Object[]{"id", 7L, 9L}});
        assertArrayEquals(new Object[]{"state", 3L, "tenant", "client", "owner", "tenant", "client", "owner",
                "tenant", "client", "owner", "id", 7L, 9L}, actual);
        assertTrue(Arrays.stream(actual).noneMatch(Object[].class::isInstance));
    }

    @Test
    void leaseRenewalIsExactScopedCurrentFenceOnlyAndCandidateScansAreBounded() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcArchiveQuestionStore store = new JdbcArchiveQuestionStore(jdbc);
        ArchiveOwnerScope owner = new ArchiveOwnerScope("tenant", "client", "tenant");
        Instant now = Instant.parse("2026-08-22T12:00:00Z");

        assertEquals(1, store.renewOutboxLease(owner,
                "123e4567-e89b-42d3-a456-426614174000", 7, now.plusSeconds(30), now));
        assertTrue(jdbc.sql.contains("SET lease_until=?,updated_at=?"));
        assertTrue(jdbc.sql.contains("fencing_token=? AND state='LEASED' AND lease_until>?"));
        assertTrue(jdbc.sql.contains("CAST(question_id AS BINARY)=CAST(? AS BINARY)"));
        assertEquals(16, jdbc.args.length);
        assertEquals(7L, jdbc.args[14]);

        store.listClaimCandidates(now, 100);
        assertTrue(jdbc.sql.contains("ORDER BY available_at,row_id LIMIT ?"));
        assertEquals(100, jdbc.args[2]);
        store.listExhaustedCandidates(now, 50);
        assertTrue(jdbc.sql.contains("ORDER BY lease_until,row_id LIMIT ?"));
        assertEquals(50, jdbc.args[1]);
    }

    @Test
    void claimAndRecoveryQueriesCarryDurableLeaseFencingAndPublicationWatermark() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/cn/jia/chat/archive/store/JdbcArchiveQuestionStore.java"));
        assertTrue(source.contains("attempt_count<3"));
        assertTrue(source.contains("state='LEASED' AND lease_until<=?"));
        assertTrue(source.contains("ORDER BY available_at,row_id LIMIT ?"));
        assertTrue(source.contains("ORDER BY lease_until,row_id LIMIT ?"));
        assertTrue(source.contains("fencing_token=? AND state=?"));
        assertTrue(source.contains("SET lease_until=?,updated_at=?"));
        assertTrue(source.contains("fencing_token=? AND state='LEASED' AND lease_until>?"));
        assertTrue(source.contains("published_sequence<q.current_sequence"));
        assertTrue(source.contains("AND published_sequence=?"));
        assertTrue(source.contains("FOR UPDATE"));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        String sql;
        Object[] args;
        @Override public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args.clone();
            return List.of();
        }
        @Override public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args.clone();
            return 1;
        }
    }
}
