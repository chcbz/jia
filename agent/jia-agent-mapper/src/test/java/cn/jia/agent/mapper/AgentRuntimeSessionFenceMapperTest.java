package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeSessionFenceMapperTest {
    @Test void readAndLockAreByteExactForEverySubjectCoordinate() throws Exception {
        for (String method : java.util.List.of("findInScope", "lockInScope")) {
            String sql = String.join("\n", AgentRuntimeMapper.class.getMethod(method, String.class, String.class, String.class)
                    .getAnnotation(Select.class).value());
            for (var coordinate : java.util.Map.of("tenant_id", "tenantId", "client_id", "clientId", "agent_id", "agentId").entrySet()) {
                assertTrue(sql.contains("CAST(" + coordinate.getKey() + " AS BINARY) = CAST(#{" + coordinate.getValue() + "} AS BINARY)"));
                assertTrue(sql.contains("OCTET_LENGTH(" + coordinate.getKey() + ") = OCTET_LENGTH(#{" + coordinate.getValue() + "})"));
            }
            assertEquals(method.equals("lockInScope"), sql.contains("FOR UPDATE"));
        }
    }
}
