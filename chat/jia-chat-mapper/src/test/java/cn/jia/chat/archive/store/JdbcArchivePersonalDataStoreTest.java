package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcArchivePersonalDataStoreTest {
    @Test
    void exactScopePredicatesFreezeAllThreeOwnerComponentsByValueBytesAndLength() throws Exception {
        Field field = JdbcArchivePersonalDataStore.class.getDeclaredField("EXACT_SCOPE");
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
    void nullFirstPageCursorOmitsExclusivePredicateSoLongMaxRowIsEligible() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        JdbcArchivePersonalDataStore store = new JdbcArchivePersonalDataStore(jdbc);
        ArchiveOwnerScope owner = new ArchiveOwnerScope("tenant", "client", "owner");

        store.listBookmarks(owner, "edition", null, 101);
        assertFalse(jdbc.sql.contains("row_id<?"));
        assertEquals(13, jdbc.args.length);
        assertEquals(101, jdbc.args[12]);

        store.listBookmarks(owner, "edition", Long.MAX_VALUE, 101);
        assertTrue(jdbc.sql.contains("row_id<?"));
        assertEquals(14, jdbc.args.length);
        assertEquals(Long.MAX_VALUE, jdbc.args[12]);
        assertEquals(101, jdbc.args[13]);
    }

    @Test
    void multiArrayArgumentConcatenationFlattensOwnerScopeInsteadOfNestingIt() throws Exception {
        Method scopeArgs = JdbcArchivePersonalDataStore.class.getDeclaredMethod("scopeArgs", ArchiveOwnerScope.class);
        scopeArgs.setAccessible(true);
        Object[] scope = (Object[]) scopeArgs.invoke(null, new ArchiveOwnerScope("tenant", "client", "owner"));
        Method concat = JdbcArchivePersonalDataStore.class.getDeclaredMethod(
                "concat", Object[].class, Object[].class, Object[].class);
        concat.setAccessible(true);
        Object[] actual = (Object[]) concat.invoke(null, new Object[]{new Object[]{"state"}, scope,
                new Object[]{"id", 7L}});
        assertArrayEquals(new Object[]{"state", "tenant", "client", "owner", "tenant", "client", "owner",
                "tenant", "client", "owner", "id", 7L}, actual);
        assertTrue(Arrays.stream(actual).noneMatch(Object[].class::isInstance));
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        String sql;
        Object[] args;

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args.clone();
            return List.of();
        }
    }
}
