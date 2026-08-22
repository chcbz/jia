package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
