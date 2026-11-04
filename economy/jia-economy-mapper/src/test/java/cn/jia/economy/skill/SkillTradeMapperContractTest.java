package cn.jia.economy.skill;

import cn.jia.economy.mapper.SkillTradeMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SkillTradeMapperContractTest {
    @Test void immutableTradeSnapshotsAndScopedOrderCasRemainProtected() {
        for (var method : SkillTradeMapper.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Delete.class),method.toString());
            var select=method.getAnnotation(Select.class);
            var update=method.getAnnotation(Update.class);
            String sql=select!=null?String.join(" ",select.value()):update!=null?String.join(" ",update.value()):null;
            if(sql==null) continue;
            assertTrue(sql.contains("tenant_id=#{tenantId}"),method.toString());
            assertTrue(sql.contains("client_id=#{clientId}"),method.toString());
            assertTrue(sql.contains("OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"),method.toString());
            assertTrue(sql.contains("OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})"),method.toString());
            if(update!=null) {
                assertTrue(Set.of("markOrderInstalling","markOrderActive","markOrderRefunded").contains(method.getName()));
                assertTrue(sql.stripLeading().startsWith("UPDATE economy_skill_order\n"));
                assertTrue(sql.contains("version=#{expectedVersion}"));
                assertTrue(sql.contains("status=" ) || sql.contains("status IN"));
            }
        }
    }
}
