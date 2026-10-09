package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCreationOperationMapperContractTest {
    @Test
    void reserveLockReadAndCommitRemainExactScopedAndParameterized() throws Exception {
        Method reserve = AgentTaskCreationOperationMapper.class.getMethod("reserve",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, long.class);
        String insert = String.join(" ", reserve.getAnnotation(Insert.class).value());
        assertTrue(insert.contains("ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)"));
        assertFalse(insert.contains("${"));

        for (String name : new String[] {"selectForUpdate", "select"}) {
            Method read = AgentTaskCreationOperationMapper.class.getMethod(name,
                    String.class, String.class, String.class, String.class);
            String sql = String.join(" ", read.getAnnotation(Select.class).value());
            for (String column : new String[] {"tenant_id", "client_id", "owner_jiacn",
                    "idempotency_key"}) {
                assertTrue(sql.contains("CAST(" + column + " AS BINARY)=CAST(#{"),
                        name + ":" + column);
                assertTrue(sql.contains("OCTET_LENGTH(" + column + ")=OCTET_LENGTH(#{"),
                        name + ":" + column);
            }
            assertTrue(name.equals("selectForUpdate") == sql.contains("FOR UPDATE"));
        }

        Method commit = AgentTaskCreationOperationMapper.class.getMethod("commit",
                String.class, String.class, String.class, String.class, String.class,
                String.class, String.class, long.class, long.class);
        String update = String.join(" ", commit.getAnnotation(Update.class).value());
        assertTrue(update.contains("operation_state='PROCESSING'"));
        assertTrue(update.contains("SET operation_state='COMMITTED'"));
        assertTrue(update.contains("request_hash=#{requestHash}"));
        assertTrue(update.contains("input_refs_json=CAST(#{inputRefsJson} AS JSON)"));
        assertFalse(update.contains("${"));
    }
}
