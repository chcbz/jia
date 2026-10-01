package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlledImageBridgeSchemaInitializerTest {
    @Test void bridgeDdlIsSingleAdditiveBinaryOwnerScopedTable() {
        String ddl=ControlledImageBridgeSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("create table if not exists agent_controlled_image_bridge_operation "));
        assertFalse(normalized.contains(";"));
        for(String required:List.of("engine=innodb","utf8mb4_0900_bin","uk_acibo_scope_key",
                "uk_acibo_scope_consent","uk_acibo_scope_grant","chk_acibo_locator",
                "authority_locator=concat('mmd-ci-v1:',consent_id)")) assertTrue(normalized.contains(required),required);
        for(String forbidden:List.of(" alter table "," drop "," insert "," update "," delete "))
            assertFalse((" "+normalized+" ").contains(forbidden),forbidden);
    }

    @Test void bridgeColumnCatalogRequiresExactOrderTypeNullabilityAndBinaryCollation() {
        List<Map<String,Object>> pristine=bridgeColumns();
        assertDoesNotThrow(()->ControlledImageBridgeSchemaInitializer.validateColumns(pristine));
        for(String field:List.of("column_type","is_nullable","collation_name")) {
            List<Map<String,Object>> drift=bridgeColumns();
            Map<String,Object> row=drift.stream().filter(value->"consent_id".equals(value.get("column_name")))
                    .findFirst().orElseThrow();
            row.put(field,switch(field){case "column_type"->"varchar(99)";
                case "is_nullable"->"YES";default->"utf8mb4_0900_ai_ci";});
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageBridgeSchemaInitializer.validateColumns(drift),field);
        }
        List<Map<String,Object>> reordered=bridgeColumns();
        java.util.Collections.swap(reordered,1,2);
        assertThrows(IllegalStateException.class,
                ()->ControlledImageBridgeSchemaInitializer.validateColumns(reordered));
    }

    @Test void bridgeExactChecksRejectSameNamedWeakOrAlwaysTrueDefinitions() {
        List<Map<String,Object>> pristine=bridgeChecks();
        assertDoesNotThrow(()->ControlledImageBridgeSchemaInitializer.validateChecks(pristine));
        for(String weak:List.of("1",ControlledImageBridgeSchemaInitializer.checkExpressions()
                .get("chk_acibo_locator")+" OR 1=1")) {
            List<Map<String,Object>> rows=bridgeChecks();
            rows.stream().filter(row->"chk_acibo_locator".equals(row.get("constraint_name")))
                    .findFirst().orElseThrow().put("check_clause",weak);
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageBridgeSchemaInitializer.validateChecks(rows));
        }
    }

    @Test void executionAlterAndExactCheckRejectWeakSameName() {
        String ddl=ControlledImageExecutionSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("alter table agent_personal_workspace_execution "));
        assertTrue(normalized.contains("add unique key uk_pwex_controlled_consent"));
        assertTrue(normalized.contains("add constraint chk_pwex_controlled_consent check"));
        assertFalse(normalized.contains(";"));
        Map<String,Object> exact=new LinkedHashMap<>();exact.put("enforced","YES");
        exact.put("check_clause",ControlledImageExecutionSchemaInitializer.checkExpression());
        assertDoesNotThrow(()->ControlledImageExecutionSchemaInitializer.validateCheck(List.of(exact)));
        for(String weak:List.of("1",ControlledImageExecutionSchemaInitializer.checkExpression()+" OR 1=1")) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("enforced","YES");row.put("check_clause",weak);
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageExecutionSchemaInitializer.validateCheck(List.of(row)));
        }
    }

    private static List<Map<String,Object>> bridgeColumns() {
        List<Map<String,Object>> rows=new ArrayList<>();
        column(rows,"id","bigint","NO",null);
        column(rows,"owner_jiacn","varchar(50)","NO","utf8mb4_0900_bin");
        column(rows,"task_id","varchar(100)","NO","utf8mb4_0900_bin");
        column(rows,"assignment_idempotency_key","varchar(100)","NO","utf8mb4_0900_bin");
        column(rows,"wrapper_digest","char(64)","NO","ascii_bin");
        column(rows,"consent_id","varchar(100)","NO","utf8mb4_0900_bin");
        column(rows,"expected_consent_version","bigint","NO",null);
        column(rows,"grant_id","varchar(100)","NO","utf8mb4_0900_bin");
        column(rows,"grant_version","bigint","NO",null);
        column(rows,"assignment_revision","bigint","NO",null);
        column(rows,"authority_locator","varchar(100)","NO","utf8mb4_0900_bin");
        column(rows,"created_at","bigint","NO",null);
        column(rows,"tenant_id","varchar(50)","NO","utf8mb4_0900_bin");
        column(rows,"client_id","varchar(50)","NO","utf8mb4_0900_bin");
        column(rows,"create_time","bigint","YES",null);
        column(rows,"update_time","bigint","YES",null);
        return rows;
    }
    private static void column(List<Map<String,Object>> rows,String name,String type,String nullable,String collation) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("column_name",name);row.put("column_type",type);
        row.put("is_nullable",nullable);row.put("collation_name",collation);rows.add(row);
    }

    private static List<Map<String,Object>> bridgeChecks() {
        List<Map<String,Object>> rows=new ArrayList<>();
        ControlledImageBridgeSchemaInitializer.checkExpressions().forEach((name,clause)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("constraint_name",name);
            row.put("enforced","YES");row.put("check_clause",clause);rows.add(row);
        });
        return rows;
    }
}
