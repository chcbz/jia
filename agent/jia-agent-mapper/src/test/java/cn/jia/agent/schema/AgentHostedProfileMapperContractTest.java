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
    void personaLookupUsesExactFullScopeBinaryLengthWithAndWithoutLock() throws Exception {
        Method locked = AgentPersonaBindingMapper.class.getMethod(
                "findExactActiveByScopeAndPersonaForUpdate",
                String.class, String.class, String.class, String.class, int.class);
        Method catalog = AgentPersonaBindingMapper.class.getMethod(
                "findExactActiveByScopeAndPersona",
                String.class, String.class, String.class, String.class, int.class);
        String sql = String.join(" ", locked.getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT);
        for (String column : new String[]{"tenant_id", "client_id", "owner_jiacn", "persona_code"}) {
            assertTrue(sql.contains(column + " = #"), column + " ordinary predicate");
            assertTrue(sql.contains("cast(" + column + " as binary)"), column + " binary predicate");
            assertTrue(sql.contains("octet_length(" + column + ")"), column + " length predicate");
        }
        assertTrue(sql.contains("for update"));
        String catalogSql = String.join(" ", catalog.getAnnotation(Select.class).value())
                .toLowerCase(Locale.ROOT);
        for (String column : new String[]{"tenant_id", "client_id", "owner_jiacn", "persona_code"}) {
            assertTrue(catalogSql.contains(column + " = #"), column + " catalog ordinary predicate");
            assertTrue(catalogSql.contains("cast(" + column + " as binary)"), column + " catalog binary predicate");
            assertTrue(catalogSql.contains("octet_length(" + column + ")"), column + " catalog length predicate");
        }
        assertFalse(catalogSql.contains("for update"));
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

        String repair = String.join(" ", AgentHostedProfileMapper.class.getMethod("markRepair",
                long.class, String.class, String.class, long.class, String.class, String.class, long.class)
                .getAnnotation(Update.class).value()).toLowerCase(Locale.ROOT);
        assertTrue(repair.contains("lifecycle_state=#{expectedstate}"));
        assertTrue(repair.contains("resume_state is null and #{expectedresumestate} is null"));
        assertTrue(repair.contains("generation=#{expectedgeneration}"));

        String resume = String.join(" ", AgentHostedProfileMapper.class.getMethod("resumeRepair",
                long.class, String.class, long.class, long.class)
                .getAnnotation(Update.class).value()).toLowerCase(Locale.ROOT);
        assertTrue(resume.contains("lifecycle_state='repair_required'"));
        assertTrue(resume.contains("resume_state=#{resumestate}"));
        assertTrue(resume.contains("generation=#{expectedgeneration}"));
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
