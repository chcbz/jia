package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ControlledImageFollowupV3SchemaInitializerTest {
    @Test void featureIsDefaultOffAndDdlHasOnlyTwoExactCreateStatements() {
        ConditionalOnProperty condition=ControlledImageFollowupV3SchemaInitializer.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition);
        assertEquals("agent.controlled-image-provider",condition.prefix());
        assertArrayEquals(new String[]{"followup-v3-enabled"},condition.name());
        assertEquals("true",condition.havingValue());
        assertFalse(condition.matchIfMissing());

        var statements=ControlledImageFollowupV3SchemaInitializer.createStatements();
        assertEquals(2,statements.size());
        String ddl=String.join("\n",statements).toLowerCase(Locale.ROOT);
        assertTrue(ddl.contains("create table if not exists agent_controlled_image_intent_operation_grant"));
        assertTrue(ddl.contains("create table if not exists agent_controlled_image_execution_source_v3"));
        for(String forbidden:List.of(" alter table "," drop "," update "," delete "," insert "," create trigger "))
            assertFalse((" "+ddl+" ").contains(forbidden),forbidden);
    }

    @Test void allSixBridgeChecksAreExactEnforcedAndRejectSameNamedWeakDefinitions() {
        assertEquals(8,ControlledImageFollowupV3SchemaInitializer.operationGrantCheckExpressions().size());
        assertEquals(3,ControlledImageFollowupV3SchemaInitializer.sourceCheckExpressions().size());
        assertEquals(3,ControlledImageFollowupV3SchemaInitializer.additiveCheckExpressions().size());
        assertCatalog("operation",ControlledImageFollowupV3SchemaInitializer.operationGrantCheckExpressions());
        assertCatalog("source",ControlledImageFollowupV3SchemaInitializer.sourceCheckExpressions());

        var weak=rows(ControlledImageFollowupV3SchemaInitializer.operationGrantCheckExpressions());
        weak.stream().filter(row->"chk_aciiog_sources".equals(row.get("constraint_name")))
                .findFirst().orElseThrow().put("check_clause","1=1");
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks("operation",weak,
                        ControlledImageFollowupV3SchemaInitializer.operationGrantCheckExpressions()));

        var unenforced=rows(ControlledImageFollowupV3SchemaInitializer.sourceCheckExpressions());
        unenforced.getFirst().put("enforced","NO");
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks("source",unenforced,
                        ControlledImageFollowupV3SchemaInitializer.sourceCheckExpressions()));
    }

    @Test void strictPurposeAndExecutionProtocolUnionsRetainLegacyAndV3MutualExclusion() {
        String purpose=ControlledImageFollowupV3SchemaInitializer.consentPurposeCheckExpression();
        for(String required:List.of("INITIAL_ASSIGN_AND_START","FOLLOWUP_EXECUTE",
                "operation_grant_id IS NULL","operation_grant_id REGEXP BINARY",
                "runtime_input_snapshot_sha256 IS NULL","reserved_execution_id IS NOT NULL"))
            assertTrue(purpose.contains(required),required);
        String controlled=ControlledImageFollowupV3SchemaInitializer.controlledConsentCheckExpression();
        for(String required:List.of("execution_protocol_version=2","execution_protocol_version=3",
                "permitted_operation='GENERATE_IMAGE'","permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE')",
                "operation_grant_id IS NULL","runtime_input_snapshot_digest REGEXP BINARY"))
            assertTrue(controlled.contains(required),required);
        String legacy=ControlledImageFollowupV3SchemaInitializer.legacyControlledConsentCheckExpression();
        assertFalse(legacy.contains("execution_protocol_version"));
        assertFalse(legacy.contains("EDIT_IMAGE"));
    }

    @Test void executionRestartAcceptsOnlyExactLegacyOrCompleteV3Catalog() {
        assertDoesNotThrow(() -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(false), executionIndexes(false),
                        executionChecks(false)));

        var legacyWeak = executionChecks(false);
        legacyWeak.getFirst().put("check_clause", "1=1");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(false), executionIndexes(false),
                        legacyWeak));

        var legacyPredicateDrift = executionChecks(false);
        legacyPredicateDrift.getFirst().put("check_clause", legacyExecutionCatalogCheck()
                .replace("_utf8mb4'GENERATE_IMAGE'", "_utf8mb4'EDIT_IMAGE'"));
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(false), executionIndexes(false),
                        legacyPredicateDrift));

        var legacyUnenforced = executionChecks(false);
        legacyUnenforced.getFirst().put("enforced", "NO");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(false), executionIndexes(false),
                        legacyUnenforced));
        assertDoesNotThrow(() -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), executionIndexes(true),
                        executionChecks(true)));

        var partialColumns = executionColumns(true);
        partialColumns.removeLast();
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(partialColumns, executionIndexes(true),
                        executionChecks(true)));

        var weakChecks = executionChecks(true);
        weakChecks.stream().filter(row -> "chk_pwex_controlled_consent".equals(
                        row.get("constraint_name"))).findFirst().orElseThrow()
                .put("check_clause", "1=1");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), executionIndexes(true),
                        weakChecks));

        var unenforcedChecks = executionChecks(true);
        unenforcedChecks.stream().filter(row -> "chk_pwex_execution_protocol".equals(
                        row.get("constraint_name"))).findFirst().orElseThrow()
                .put("enforced", "NO");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), executionIndexes(true),
                        unenforcedChecks));

        var extraColumns = executionColumns(true);
        extraColumns.add(column("execution_protocol_shadow", "int", "YES", null, null));
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(extraColumns, executionIndexes(true),
                        executionChecks(true)));

        var extraIndexes = executionIndexes(true);
        index(extraIndexes, "uk_pwex_operation_grant_shadow", 0, 1, "operation_grant_id");
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), extraIndexes,
                        executionChecks(true)));

        var extraChecks = executionChecks(true);
        extraChecks.add(check("chk_pwex_execution_protocol_shadow", "YES", "1=1"));
        assertThrows(IllegalStateException.class, () -> ControlledImageFollowupV3SchemaInitializer
                .validateExecutionRestartCatalog(executionColumns(true), executionIndexes(true),
                        extraChecks));
    }

    @Test void tableCatalogRejectsMissingExtraAndReorderedColumnsBeforeAcceptingRuntime() {
        var expected=ControlledImageFollowupV3SchemaInitializer.operationGrantColumnDefinitions();
        var pristine=columns(expected);
        assertDoesNotThrow(()->ControlledImageFollowupV3SchemaInitializer.validateExactColumns(
                "operation",pristine,expected));
        var missing=new ArrayList<>(pristine);missing.removeLast();
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",missing,expected));
        var reordered=new ArrayList<>(pristine);java.util.Collections.swap(reordered,0,1);
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",reordered,expected));
        var extra=new ArrayList<>(pristine);extra.add(column("unexpected","bigint","YES",null,null));
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactColumns("operation",extra,expected));
    }

    private static List<Map<String,Object>> executionColumns(boolean v3) {
        List<Map<String,Object>> rows=new ArrayList<>();
        rows.add(column("controlled_consent_id","varchar(100)","YES","utf8mb4_0900_bin",null));
        if(v3) {
            rows.add(column("execution_protocol_version","int","NO",null,"1"));
            rows.add(column("operation_grant_id","varchar(100)","YES","utf8mb4_0900_bin",null));
            rows.add(column("runtime_input_snapshot_digest","char(64)","YES","ascii_bin",null));
        }
        return rows;
    }

    private static List<Map<String,Object>> executionIndexes(boolean v3) {
        List<Map<String,Object>> rows=new ArrayList<>();
        int sequence=1;
        for(String column:List.of("tenant_id","client_id","owner_jiacn","controlled_consent_id"))
            index(rows,"uk_pwex_controlled_consent",0,sequence++,column);
        if(v3) {
            sequence=1;
            for(String column:List.of("tenant_id","client_id","owner_jiacn","operation_grant_id"))
                index(rows,"uk_pwex_operation_grant",0,sequence++,column);
        }
        return rows;
    }

    private static void index(List<Map<String,Object>> rows,String name,int nonUnique,
            int sequence,String column) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("index_name",name);
        row.put("non_unique",nonUnique);row.put("seq_in_index",sequence);
        row.put("column_name",column);row.put("sub_part",null);rows.add(row);
    }

    private static List<Map<String,Object>> executionChecks(boolean v3) {
        if(!v3)return new ArrayList<>(List.of(check("chk_pwex_controlled_consent","YES",
                legacyExecutionCatalogCheck())));
        Map<String,String> expected=ControlledImageFollowupV3SchemaInitializer.additiveCheckExpressions();
        return new ArrayList<>(List.of(
                check("chk_pwex_controlled_consent","YES",
                        expected.get("chk_pwex_controlled_consent")),
                check("chk_pwex_execution_protocol","YES",
                        expected.get("chk_pwex_execution_protocol"))));
    }


    /** Literal CHECK_CLAUSE captured from MySQL 8.0.21; independent of production helpers. */
    private static String legacyExecutionCatalogCheck() {
        return "((`controlled_consent_id` is null) or ((`execution_mode` = "
                + "_utf8mb4'CONVERSATION') and regexp_like(`controlled_consent_id`,"
                + "cast(_utf8mb4'^consent_[0-9a-f]{32}$' as char charset binary)) and "
                + "(`permitted_operation` = _utf8mb4'GENERATE_IMAGE') and "
                + "(`output_content_mime_type` = _utf8mb4'image/png')))";
    }

    private static Map<String,Object> check(String name,String enforced,String clause) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("constraint_name",name);
        row.put("enforced",enforced);row.put("check_clause",clause);return row;
    }

    private static void assertCatalog(String table,Map<String,String> expected) {
        assertDoesNotThrow(()->ControlledImageFollowupV3SchemaInitializer.validateExactChecks(
                table,rows(expected),expected));
    }
    private static List<Map<String,Object>> rows(Map<String,String> expected) {
        List<Map<String,Object>> rows=new ArrayList<>();
        expected.forEach((name,clause)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("constraint_name",name);
            row.put("enforced","YES");row.put("check_clause",clause);rows.add(row);
        });
        return rows;
    }
    private static List<Map<String,Object>> columns(
            Map<String,ControlledImageFollowupV3SchemaInitializer.Column> expected) {
        List<Map<String,Object>> rows=new ArrayList<>();
        expected.forEach((name,value)->rows.add(column(name,value.type(),value.nullable(),
                value.collation(),value.defaultValue())));return rows;
    }
    private static Map<String,Object> column(String name,String type,String nullable,
            String collation,String defaultValue) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("column_name",name);
        row.put("column_type",type);row.put("is_nullable",nullable);
        row.put("collation_name",collation);row.put("column_default",defaultValue);return row;
    }
}
