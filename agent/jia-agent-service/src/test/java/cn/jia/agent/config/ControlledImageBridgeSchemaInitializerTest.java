package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ControlledImageBridgeSchemaInitializerTest {
    @Test void bridgeDdlIsSingleAdditiveBinaryOwnerScopedTable() {
        String ddl=ControlledImageBridgeSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("create table if not exists agent_controlled_image_bridge_operation "));
        assertFalse(normalized.contains(";"));
        for(String required:List.of("engine=innodb","utf8mb4_0900_bin","uk_acibo_scope_key",
                "uk_acibo_scope_consent","uk_acibo_scope_grant","uk_acibo_scope_operation_grant",
                "chk_acibo_locator","chk_acibo_protocol","execution_protocol_version int not null default 2",
                "operation_grant_id varchar(100)","authority_locator=concat('mmd-ci-v1:',consent_id)")) assertTrue(normalized.contains(required),required);
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

    @Test void bridgeExactChecksRejectSameNamedWeakOrUnenforcedDefinitions() {
        Set<String> names=Set.of("chk_acibo_scope","chk_acibo_hash","chk_acibo_consent",
                "chk_acibo_locator","chk_acibo_protocol","chk_acibo_versions","chk_acibo_time");
        assertEquals(names,ControlledImageBridgeSchemaInitializer.checkExpressions().keySet());
        List<Map<String,Object>> pristine=bridgeCatalogChecks();
        assertDoesNotThrow(()->ControlledImageBridgeSchemaInitializer.validateChecks(pristine));
        for(String name:names) {
            List<Map<String,Object>> weak=bridgeCatalogChecks();
            weak.stream().filter(row->name.equals(row.get("constraint_name")))
                    .findFirst().orElseThrow().put("check_clause","1=1");
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageBridgeSchemaInitializer.validateChecks(weak),name+" weak");
            List<Map<String,Object>> unenforced=bridgeCatalogChecks();
            unenforced.stream().filter(row->name.equals(row.get("constraint_name")))
                    .findFirst().orElseThrow().put("enforced","NO");
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageBridgeSchemaInitializer.validateChecks(unenforced),name+" unenforced");
        }
        List<Map<String,Object>> appended=bridgeCatalogChecks();
        appended.stream().filter(row->"chk_acibo_locator".equals(row.get("constraint_name")))
                .findFirst().orElseThrow().put("check_clause",
                        ControlledImageBridgeSchemaInitializer.checkExpressions()
                                .get("chk_acibo_locator")+" OR 1=1");
        assertThrows(IllegalStateException.class,
                ()->ControlledImageBridgeSchemaInitializer.validateChecks(appended));
    }

    @Test void executionAlterAndExactMySqlCatalogRejectPredicateDriftAndUnenforcedCheck() {
        String ddl=ControlledImageExecutionSchemaInitializer.ddl();
        String normalized=ddl.replaceAll("\\s+"," ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith("alter table agent_personal_workspace_execution "));
        for(String required:List.of("add unique key uk_pwex_controlled_consent",
                "add constraint chk_pwex_controlled_consent check",
                "controlled_consent_id is null","execution_mode='conversation'",
                "controlled_consent_id regexp binary '^consent_[0-9a-f]{32}$'",
                "permitted_operation='generate_image'","output_content_mime_type='image/png'"))
            assertTrue(normalized.contains(required),required);
        assertFalse(normalized.contains(";"));

        String catalog=executionCatalogCheck();
        assertDoesNotThrow(()->ControlledImageExecutionSchemaInitializer.validateCheck(
                List.of(executionCheck("YES",catalog))));
        for(String weak:List.of("1=1",catalog+" OR 1=1",
                catalog.replace("_utf8mb4\\'GENERATE_IMAGE\\'",
                        "_utf8mb4\\'EDIT_IMAGE\\'"))) {
            assertThrows(IllegalStateException.class,
                    ()->ControlledImageExecutionSchemaInitializer.validateCheck(
                            List.of(executionCheck("YES",weak))));
        }
        assertThrows(IllegalStateException.class,
                ()->ControlledImageExecutionSchemaInitializer.validateCheck(
                        List.of(executionCheck("NO",catalog))));
    }

    private static Map<String,Object> executionCheck(String enforced,String clause) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("enforced",enforced);
        row.put("check_clause",clause);return row;
    }

    /** Literal catalog fixture captured from MySQL 8.0.21, independent of initializer constants. */
    private static String executionCatalogCheck() {
        return "((`controlled_consent_id` is null) or ((`execution_mode` = _utf8mb4\\'CONVERSATION\\') and regexp_like(`controlled_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (`permitted_operation` = _utf8mb4\\'GENERATE_IMAGE\\') and (`output_content_mime_type` = _utf8mb4\\'image/png\\')))";
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
        column(rows,"execution_protocol_version","int","NO",null);
        column(rows,"operation_grant_id","varchar(100)","YES","utf8mb4_0900_bin");
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

    private static List<Map<String,Object>> bridgeCatalogChecks() {
        Map<String,String> catalog=Map.of(
                "chk_acibo_scope","((`tenant_id` = _utf8mb4'0') and (`owner_jiacn` <> _utf8mb4'0'))",
                "chk_acibo_hash","regexp_like(`wrapper_digest`,cast(_utf8mb4'^[0-9a-f]{64}$' as char charset binary))",
                "chk_acibo_consent","regexp_like(`consent_id`,cast(_utf8mb4'^consent_[0-9a-f]{32}$' as char charset binary))",
                "chk_acibo_locator","(`authority_locator` = concat(_utf8mb4'mmd-ci-v1:',`consent_id`))",
                "chk_acibo_protocol","(((`execution_protocol_version` = 2) and (`operation_grant_id` is null)) or ((`execution_protocol_version` = 3) and regexp_like(`operation_grant_id`,cast(_utf8mb4'^opgrant_[0-9a-f]{32}$' as char charset binary))))",
                "chk_acibo_versions","((`expected_consent_version` > 0) and (`grant_version` > 0) and (`assignment_revision` >= 0))",
                "chk_acibo_time","(`created_at` > 0)");
        List<Map<String,Object>> rows=new ArrayList<>();
        ControlledImageBridgeSchemaInitializer.checkExpressions().keySet().forEach(name->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("constraint_name",name);
            row.put("enforced","YES");row.put("check_clause",catalog.get(name));rows.add(row);
        });
        return rows;
    }
}
