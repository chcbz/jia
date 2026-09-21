package cn.jia.agent.dao;

import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PersonalWorkspaceExecutionMapperContractTest {
    @Test
    void historyUsesExactOwnerKeysetBinaryDescendingAndSummaryOnlyProjection() throws Exception {
        Method method = PersonalWorkspaceExecutionMapper.class.getDeclaredMethod(
                "listHistory", String.class, String.class, String.class,
                Long.class, String.class, int.class);
        String sql = sql(method);

        assertTrue(sql.startsWith("<script> select tenant_id, client_id, owner_jiacn, execution_id,"));
        assertFalse(sql.contains("select *"));
        for (String sensitive : new String[] {" instruction", "lease_token", "storage_uri",
                "idempotency_key", "request_hash", "revoke_idempotency_key"}) {
            assertFalse(sql.substring(0, sql.indexOf(" from ")).contains(sensitive), sensitive);
        }
        for (String exact : new String[] {"tenant_id", "client_id", "owner_jiacn"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)=cast(#{"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")=octet_length(#{"), exact);
        }
        assertTrue(sql.contains("created_at &lt; #{beforecreatedat}"));
        assertTrue(sql.contains("created_at=#{beforecreatedat}"));
        assertTrue(sql.contains("cast(execution_id as binary) &lt; cast(#{beforeexecutionid} as binary)"));
        assertTrue(sql.contains("order by created_at desc, cast(execution_id as binary) desc"));
        assertTrue(sql.contains("limit #{limit}"));
    }

    @Test
    void requestLookupIsExactAndExcludesInstructionAndRuntimeCredentials() throws Exception {
        Method method = PersonalWorkspaceExecutionMapper.class.getDeclaredMethod(
                "findRequestByIdempotency", String.class, String.class, String.class, String.class);
        String sql = sql(method);
        String projection = sql.substring(0, sql.indexOf(" from "));

        assertFalse(projection.contains("*"));
        assertFalse(projection.contains("instruction"));
        assertFalse(projection.contains("lease_token"));
        assertFalse(projection.contains("lease_work_item_version"));
        assertFalse(projection.contains("lease_expires_at"));
        assertFalse(projection.contains("request_hash"));
        for (String exact : new String[] {"tenant_id", "client_id", "owner_jiacn", "idempotency_key"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)=cast(#{"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")=octet_length(#{"), exact);
        }
        assertTrue(sql.contains("limit 1"));
    }

    @Test
    void daoKeepsPairedCursorAndFullLimitPlusOneBound() {
        PersonalWorkspaceExecutionMapper mapper = mock(PersonalWorkspaceExecutionMapper.class);
        PersonalWorkspaceExecutionDaoImpl dao = new PersonalWorkspaceExecutionDaoImpl(
                mapper, mock(PersonalWorkspaceExecutionInputMapper.class),
                mock(PersonalWorkspaceExecutionOutputMapper.class));

        dao.listHistory("0", "client-a", "owner-a", 1000L, "pwe_1", 101);
        verify(mapper).listHistory("0", "client-a", "owner-a", 1000L, "pwe_1", 101);
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", 1000L, null, 20));
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", null, "pwe_1", 20));
        assertThrows(IllegalArgumentException.class,
                () -> dao.listHistory("0", "client-a", "owner-a", null, null, 102));
    }

    private static String sql(Method method) {
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value()).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);
    }
}
