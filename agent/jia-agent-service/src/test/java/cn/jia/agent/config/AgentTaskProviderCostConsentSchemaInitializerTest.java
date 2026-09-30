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

    @Test void mysql821CatalogRenderingPreservesBetweenAndBinaryRegexpSemantics() {
        List<Map<String,Object>> rendered=checkRows();
        clause(rendered,"chk_atpcc_identity","""
                ((char_length(`client_id`) between 1 and 50)
                  and (char_length(`owner_jiacn`) between 1 and 50)
                  and (char_length(`consent_id`) between 1 and 100)
                  and (char_length(`task_id`) between 1 and 100)
                  and (char_length(`target_agent_id`) between 1 and 100)
                  and (char_length(`idempotency_key`) between 1 and 100)
                  and (char_length(`assignment_idempotency_key`) between 1 and 100)
                  and (char_length(`binding_id`) between 1 and 100)
                  and (char_length(`model_id`) between 1 and 100)
                  and (char_length(`operator_issuer`) between 1 and 100)
                  and (char_length(`operator_policy_revision`) between 1 and 100))
                """);
        clause(rendered,"chk_atpcc_inputs","""
                ((json_type(`input_snapshot_json`) = _utf8mb4\\'ARRAY\\')
                  and (json_length(`input_snapshot_json`) between 0 and 16))
                """);
        clause(rendered,"chk_atpcc_hashes","""
                (regexp_like(`request_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))
                  and regexp_like(`assignment_base_hash`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))
                  and regexp_like(`requirement_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))
                  and regexp_like(`input_snapshot_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))
                  and ((`revoke_request_digest` is null) or
                    regexp_like(`revoke_request_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))))
                """);
        assertDoesNotThrow(()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(rendered));
    }

    @Test void exactCheckCatalogRejectsSameNamedWeakAndAlwaysTrueDefinitions() {
        List<Map<String,Object>> pristine=checkRows();
        assertDoesNotThrow(()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(pristine));

        for (String weakClause:List.of("1",
                AgentTaskProviderCostConsentSchemaInitializer.checkExpressions()
                        .get("chk_atpcc_provider")+" OR 1=1",
                AgentTaskProviderCostConsentSchemaInitializer.checkExpressions()
                        .get("chk_atpcc_provider").replace("(binding_epoch>0)","(binding_epoch>=0)"))) {
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

    private static void clause(List<Map<String,Object>> rows,String name,String clause) {
        rows.stream().filter(row->name.equals(row.get("constraint_name")))
                .findFirst().orElseThrow().put("check_clause",clause);
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
