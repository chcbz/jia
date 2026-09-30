package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class AgentSelectedOutputFinalizationSchemaInitializerTest {
    @Test
    void ddlIsAdditiveBinaryScopedAndKeepsLeaseAndSourceAuthorityPrivate(){
        String ddl=AgentSelectedOutputFinalizationSchemaInitializer.ddl();String n=ddl.toLowerCase();
        assertTrue(n.contains("create table if not exists agent_selected_output_finalization"));
        assertTrue(n.contains("utf8mb4_0900_bin"));assertTrue(n.contains("source_facts_digest"));
        assertTrue(n.contains("conversation_generation"));assertTrue(n.contains("lease_token"));
        assertTrue(n.contains("chk_asof_lease"));assertTrue(n.contains("9007199254740991"));
        assertFalse(n.contains("alter table"));assertFalse(n.contains("provider"));
    }

    @Test
    void initializingBeanRunsOnlyAfterExistingFormalDeliveryInitializingBean() throws Exception {
        var method=AgentSelectedOutputFinalizationConfiguration.class.getDeclaredMethod(
                "agentSelectedOutputFinalizationSchemaInitializer",JdbcTemplate.class);
        DependsOn order=method.getAnnotation(DependsOn.class);
        assertNotNull(order);assertArrayEquals(new String[]{"agentTaskFormalDeliverySchemaInitializer"},order.value());
    }
}
