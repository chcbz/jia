package cn.jia.agent.mapper;

import cn.jia.agent.entity.HallPrivateMarkEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HallPrivateMarkMapperContractTest {
    @Test void markLookupKeysAreBoundAndEveryReadIncludesExactOwnerClientTenant() {
        Configuration configuration = new Configuration(); configuration.addMapper(HallPrivateMarkMapper.class);
        var parameters = Map.of("tenantId","0","clientId","c","ownerJiacn","o",
                "sourceType","PRIVATE_CASE","sourceId","quoted'","operationKey","secret-key");
        for (String method : new String[]{"current","byKey"}) {
            var bound = configuration.getMappedStatement(HallPrivateMarkMapper.class.getName()+"."+method).getBoundSql(parameters);
            String sql = bound.getSql();
            for (String field : new String[]{"tenant_id","client_id","owner_jiacn"}) {
                assertTrue(sql.contains("CAST("+field+" AS BINARY)"));
                assertTrue(sql.contains("OCTET_LENGTH("+field+")"));
            }
            assertFalse(sql.contains("quoted'")); assertFalse(sql.contains("secret-key"));
            assertTrue(sql.contains("LIMIT 1"));
        }
        var statement = configuration.getMappedStatement(HallPrivateMarkMapper.class.getName()+".insert")
                .getBoundSql(new HallPrivateMarkEntity());
        assertTrue(statement.getSql().contains("INSERT INTO hall_private_mark"));
        assertEquals(15,statement.getParameterMappings().size());
        for (var method : HallPrivateMarkMapper.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(Update.class)); assertNull(method.getAnnotation(Delete.class));
        }
    }
}
