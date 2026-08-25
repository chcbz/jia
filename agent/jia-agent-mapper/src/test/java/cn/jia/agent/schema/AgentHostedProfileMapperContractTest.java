package cn.jia.agent.schema;

import cn.jia.agent.dao.impl.AgentHostedProfileDaoImpl;
import cn.jia.agent.mapper.AgentHostedProfileMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AgentHostedProfileMapperContractTest {
    @Test
    void personaLookupUsesExactFullScopeBinaryLengthAndLock() throws Exception {
        Method method = AgentPersonaBindingMapper.class.getMethod(
                "findExactActiveByScopeAndPersonaForUpdate",
                String.class, String.class, String.class, String.class, int.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT);
        for (String column : new String[]{"tenant_id", "client_id", "owner_jiacn", "persona_code"}) {
            assertTrue(sql.contains(column + " = #"), column + " ordinary predicate");
            assertTrue(sql.contains("cast(" + column + " as binary)"), column + " binary predicate");
            assertTrue(sql.contains("octet_length(" + column + ")"), column + " length predicate");
        }
        assertTrue(sql.contains("for update"));
    }

    @Test
    void hostedLookupAndStateCasAreByteExact() throws Exception {
        String select = String.join(" ", AgentHostedProfileMapper.class.getMethod("findExactForUpdate",
                String.class, String.class, String.class, long.class)
                .getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT);
        for (String column : new String[]{"tenant_id", "client_id", "owner_jiacn"}) {
            assertTrue(select.contains(column + " = #"));
            assertTrue(select.contains("cast(" + column + " as binary)"));
            assertTrue(select.contains("octet_length(" + column + ")"));
        }
        String update = String.join(" ", AgentHostedProfileMapper.class.getMethod("transition",
                long.class, String.class, long.class, String.class, long.class, boolean.class, long.class)
                .getAnnotation(Update.class).value()).toLowerCase(Locale.ROOT);
        assertTrue(update.contains("cast(lifecycle_state as binary)"));
        assertTrue(update.contains("octet_length(lifecycle_state)"));
    }

    @Test
    void hostedDaoRejectsInvalidScopeBeforeMapperAccess() {
        AgentHostedProfileDaoImpl dao = new AgentHostedProfileDaoImpl();
        for (String invalid : new String[]{"", " owner", "owner\u00a0", "owner\n", "owner\ud800"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> dao.findExact(invalid, "client", "owner", 1L));
        }
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExact("owner", "client", "owner", 0L));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExact("0", "client", "0", 1L));
        assertThrows(IllegalArgumentException.class,
                () -> dao.findExact("tenant-a", "client", "owner-a", 1L));
    }
}
