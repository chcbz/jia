package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskProviderCostConsentSchemaInitializerTest {
    @Test void ddlIsSingleAdditiveBinaryScopedLifecycleTable() {
        String ddl=AgentTaskProviderCostConsentSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("(?m)^\\s*--.*$"," ").replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("create table if not exists agent_task_provider_cost_consent "));
        assertFalse(normalized.contains(";"));
        for(String forbidden:new String[]{" alter table "," drop "," insert "," update "," delete "," replace "})
            assertFalse((" "+normalized+" ").contains(forbidden),forbidden);
        for(String required:new String[]{"engine=innodb","utf8mb4_0900_bin","uk_atpcc_scope_consent",
                "uk_atpcc_scope_key","chk_atpcc_scope","chk_atpcc_provider","chk_atpcc_state",
                "chk_atpcc_consumed","chk_atpcc_revoked","json_length(input_snapshot_json) between 0 and 16",
                "max_outbound_request_attempts=1","pricing_mode='unpriced_external_account'"})
            assertTrue(normalized.contains(required),required);
    }
}
