package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    @Test void exactCheckCatalogRejectsSameNamedWeakAndAlwaysTrueDefinitions() {
        List<Map<String,Object>> pristine=checkRows();
        assertDoesNotThrow(()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(pristine));

        for (String weakClause:List.of("1",
                AgentTaskProviderCostConsentSchemaInitializer.checkExpressions()
                        .get("chk_atpcc_provider")+" OR 1=1")) {
            List<Map<String,Object>> weakened=checkRows();
            weakened.stream()
                    .filter(row->"chk_atpcc_provider".equals(row.get("constraint_name")))
                    .findFirst().orElseThrow().put("check_clause",weakClause);
            IllegalStateException failure=assertThrows(IllegalStateException.class,
                    ()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(weakened));
            assertTrue(failure.getMessage().contains("definition drift"));
        }

        List<Map<String,Object>> unenforced=checkRows();
        unenforced.getFirst().put("enforced","NO");
        assertThrows(IllegalStateException.class,
                ()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(unenforced));
    }

    private static List<Map<String,Object>> checkRows() {
        List<Map<String,Object>> rows=new ArrayList<>();
        AgentTaskProviderCostConsentSchemaInitializer.checkExpressions().forEach((name,clause)->{
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("constraint_name",name);row.put("enforced","YES");
            row.put("check_clause",clause);rows.add(row);
        });
        return rows;
    }
}
