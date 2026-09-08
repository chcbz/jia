package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentRuntimeMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeMapperContractTest {
    @Test
    void rosterFiltersActiveBindingAndIdentityBeforeDatabasePagination() throws Exception {
        String sql = selectSql("findActiveRosterByOwner");

        assertTrue(sql.contains("select r.* from agent_runtime r"));
        assertTrue(sql.contains("and exists ( select 1 from agent_persona_binding b"));
        assertTrue(sql.contains("inner join agent_identity_registry i on i.binding_id = b.id"));
        assertTrue(sql.contains("b.id = r.binding_id"));
        assertTrue(sql.contains("b.status = 1"));
        assertTrue(sql.contains("i.lifecycle_status = 'active'"));
        assertTrue(sql.contains("i.canonical_agent_id = r.agent_id"));
        assertTrue(sql.contains("b.persona_code = r.persona_code"));
        assertTrue(sql.contains("or exists ( select 1 from agent_identity_alias a"));
        assertTrue(sql.contains("a.alias_status = 'active'"));
        assertTrue(sql.contains("a.valid_to is null"));
        assertTrue(sql.contains("order by r.persona_code asc"));
        assertFalse(sql.contains(" limit "), "PageHelper owns bounded pagination");

        assertExactParameter(sql, "r.client_id", "clientid");
        assertExactParameter(sql, "r.owner_jiacn", "ownerjiacn");
        assertExactParameter(sql, "b.client_id", "clientid");
        assertExactParameter(sql, "b.jiacn", "ownerjiacn");
        assertExactParameter(sql, "i.tenant_id", "ownerjiacn");
        assertExactParameter(sql, "i.client_id", "clientid");
        assertExactParameter(sql, "i.owner_jiacn", "ownerjiacn");
        assertExactColumns(sql, "i.canonical_agent_id", "r.agent_id");
        assertExactColumns(sql, "b.persona_code", "r.persona_code");
    }

    @Test
    void unbindRuntimeCasReliablyClearsOwnershipCredentialsAndTaskProjection() throws Exception {
        String sql = updateSql("clearBindingAfterUnbind");
        String set = sql.substring(sql.indexOf(" set "), sql.indexOf(" where "));

        for (String column : new String[] {
                "client_id", "owner_jiacn", "persona_code", "persona_name", "binding_id",
                "endpoint", "token_hash", "current_task_id", "current_task_title", "error_message"}) {
            assertTrue(set.contains(column + " = null"), column + ": " + set);
        }
        assertTrue(set.contains("status = 'offline'"));
        assertTrue(sql.contains("where id = #{runtimeid}"));
        assertTrue(sql.contains("and binding_id = #{bindingid}"));
        assertExactParameter(sql, "agent_id", "agentid");
        assertExactParameter(sql, "client_id", "clientid");
        assertExactParameter(sql, "owner_jiacn", "ownerjiacn");
        assertFalse(set.contains("name = null"));
        assertFalse(set.contains("abilities = null"));
        assertFalse(set.contains("tenant_id = null"));
    }

    private static String selectSql(String methodName) throws Exception {
        Method method = find(methodName);
        Select select = method.getAnnotation(Select.class);
        if (select == null) throw new IllegalStateException(methodName + " is not @Select");
        return normalize(select.value());
    }

    private static String updateSql(String methodName) throws Exception {
        Method method = find(methodName);
        Update update = method.getAnnotation(Update.class);
        if (update == null) throw new IllegalStateException(methodName + " is not @Update");
        return normalize(update.value());
    }

    private static Method find(String methodName) throws NoSuchMethodException {
        for (Method candidate : AgentRuntimeMapper.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) return candidate;
        }
        throw new NoSuchMethodException(methodName);
    }

    private static String normalize(String[] sql) {
        return String.join(" ", sql).replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void assertExactParameter(String sql, String column, String parameter) {
        assertTrue(sql.contains("cast(" + column + " as binary) = cast(#{" + parameter + "} as binary)"), column);
        assertTrue(sql.contains("octet_length(" + column + ") = octet_length(#{" + parameter + "})"), column);
    }

    private static void assertExactColumns(String sql, String left, String right) {
        assertTrue(sql.contains("cast(" + left + " as binary) = cast(" + right + " as binary)"), left);
        assertTrue(sql.contains("octet_length(" + left + ") = octet_length(" + right + ")"), left);
    }
}
