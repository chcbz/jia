package cn.jia.agent.schema;

import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AgentPersonaBindingMapperContractTest {
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

}
