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
        List<Map<String,Object>> rendered=checkRows(false);
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
        List<Map<String,Object>> pristine=checkRows(false);
        assertDoesNotThrow(()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(pristine));

        for (String weakClause:List.of("1",
                AgentTaskProviderCostConsentSchemaInitializer.checkExpressions()
                        .get("chk_atpcc_provider")+" OR 1=1",
                AgentTaskProviderCostConsentSchemaInitializer.checkExpressions()
                        .get("chk_atpcc_provider").replace("(binding_epoch>0)","(binding_epoch>=0)"))) {
            List<Map<String,Object>> weakened=checkRows(false);
            weakened.stream()
                    .filter(row->"chk_atpcc_provider".equals(row.get("constraint_name")))
                    .findFirst().orElseThrow().put("check_clause",weakClause);
            IllegalStateException failure=assertThrows(IllegalStateException.class,
                    ()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(weakened));
            assertTrue(failure.getMessage().contains("definition drift"));
        }

        List<Map<String,Object>> unenforced=checkRows(false);
        unenforced.getFirst().put("enforced","NO");
        assertThrows(IllegalStateException.class,
                ()->AgentTaskProviderCostConsentSchemaInitializer.validateChecks(unenforced));
    }


    @Test void catalogAcceptsOnlyExactPristineV1OrCompleteFollowupV3RestartShape() {
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(false), indexRows(false), checkRows(false)));
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(true), indexRows(true), checkRows(true)));

        List<Map<String,Object>> partial=columnRows(true);
        partial.removeLast();
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        partial,indexRows(true),checkRows(true)));

        List<Map<String,Object>> extraColumn=columnRows(true);
        extraColumn.add(column("unexpected_column","bigint","YES",null,null));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        extraColumn,indexRows(true),checkRows(true)));

        List<Map<String,Object>> partialIndex=indexRows(true);
        partialIndex.removeLast();
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),partialIndex,checkRows(true)));

        List<Map<String,Object>> extraIndex=indexRows(true);
        extraIndex.add(index("unexpected_index",1,1,"task_id"));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),extraIndex,checkRows(true)));
    }

    @Test void extendedCatalogRetainsAllThirteenChecksAndRejectsWeakOrExtraUnion() {
        List<Map<String,Object>> pristine=checkRows(true);
        assertEquals(AgentTaskProviderCostConsentSchemaInitializer.checkExpressions().size()+1,
                pristine.size());
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(true),indexRows(true),pristine));

        List<Map<String,Object>> weak=checkRows(true);
        clause(weak,"chk_atpcc_purpose_union","1=1");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),weak));

        List<Map<String,Object>> weakenedLegacy=checkRows(true);
        clause(weakenedLegacy,"chk_atpcc_provider","1=1");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),weakenedLegacy));

        List<Map<String,Object>> extra=checkRows(true);
        extra.add(check("chk_atpcc_unowned","YES","1=1"));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),extra));

        List<Map<String,Object>> unenforced=checkRows(true);
        unenforced.stream().filter(row -> "chk_atpcc_purpose_union".equals(
                row.get("constraint_name"))).findFirst().orElseThrow().put("enforced","NO");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),unenforced));
    }

    @Test void legacyRestartAcceptsObservedMysql821PurposeUnionAndRejectsEveryDriftShape() {
        String observed=observedMysql821PurposeUnionCatalogCheck();
        assertEquals(observed,ControlledImageFollowupV3SchemaInitializer
                .legacyConsentPurposeCatalogCheckExpression());
        assertNotEquals(observed,ControlledImageFollowupV3SchemaInitializer
                .consentPurposeCatalogCheckExpression());
        assertNotEquals(observed,ControlledImageFollowupV3SchemaInitializer
                .consentPurposeCheckExpression());

        List<Map<String,Object>> exact=checkRows(true);
        clause(exact,"chk_atpcc_purpose_union",observed);
        assertDoesNotThrow(() -> AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                columnRows(true),indexRows(true),exact));

        List<Map<String,Object>> wrong=checkRows(true);
        clause(wrong,"chk_atpcc_purpose_union",observed.replace(
                "(`conversation_generation` > 0)","(`conversation_generation` >= 0)"));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),wrong));

        List<Map<String,Object>> alwaysTrue=checkRows(true);
        clause(alwaysTrue,"chk_atpcc_purpose_union","TRUE");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),alwaysTrue));

        List<Map<String,Object>> orWeakened=checkRows(true);
        clause(orWeakened,"chk_atpcc_purpose_union","("+observed+") OR 1=1");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),orWeakened));

        List<Map<String,Object>> unenforced=checkRows(true);
        unenforced.stream().filter(row -> "chk_atpcc_purpose_union".equals(
                row.get("constraint_name"))).findFirst().orElseThrow().put("enforced","NO");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),unenforced));

        List<Map<String,Object>> missing=checkRows(true);
        missing.removeIf(row -> "chk_atpcc_purpose_union".equals(row.get("constraint_name")));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),missing));

        List<Map<String,Object>> extra=checkRows(true);
        extra.add(check("chk_atpcc_unexpected","YES","1=1"));
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        columnRows(true),indexRows(true),extra));
    }

    @Test void completeV3CatalogRejectsPurposeDefaultDrift() {
        List<Map<String,Object>> rows=columnRows(true);
        rows.stream().filter(row -> "consent_purpose".equals(row.get("column_name")))
                .findFirst().orElseThrow().put("column_default","FOLLOWUP_EXECUTE");
        assertThrows(IllegalStateException.class, () ->
                AgentTaskProviderCostConsentSchemaInitializer.validateCatalog(
                        rows,indexRows(true),checkRows(true)));
    }

    private static String observedMysql821PurposeUnionCatalogCheck() {
        return "((`consent_purpose` in (_utf8mb4\\'INITIAL_ASSIGN_AND_START\\',_utf8mb4\\'FOLLOWUP_EXECUTE\\')) and (((`consent_purp" +
                "ose` = _utf8mb4\\'INITIAL_ASSIGN_AND_START\\') and (`operation_grant_id` is null) and (`execution_intent_id` is nu" +
                "ll) and (`conversation_id` is null) and (`conversation_generation` is null) and (`operation` is null) and (`inst" +
                "ruction_sha256` is null) and (`source_snapshot_sha256` is null) and (`owner_payload_sha256` is null) and (`runti" +
                "me_input_snapshot_sha256` is null)) or ((`consent_purpose` = _utf8mb4\\'FOLLOWUP_EXECUTE\\') and regexp_like(`oper" +
                "ation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{32}$\\' as char charset binary)) and (char_length(`execution_int" +
                "ent_id`) between 1 and 100) and (char_length(`conversation_id`) between 1 and 100) and (`conversation_generation" +
                "` > 0) and (`operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) and regexp_like(`instruction_sha" +
                "256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`source_snapshot_sha256`,cast(_utf" +
                "8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`owner_payload_sha256`,cast(_utf8mb4\\'^[0-9a-f]{" +
                "64}$\\' as char charset binary)) and (((`reserved_execution_id` is null) and (`runtime_input_snapshot_sha256` is " +
                "null)) or ((`reserved_execution_id` is not null) and regexp_like(`runtime_input_snapshot_sha256`,cast(_utf8mb4\\'" +
                "^[0-9a-f]{64}$\\' as char charset binary)))))))";
    }

    private static void clause(List<Map<String,Object>> rows,String name,String clause) {
        rows.stream().filter(row->name.equals(row.get("constraint_name")))
                .findFirst().orElseThrow().put("check_clause",clause);
    }

    private static List<Map<String,Object>> checkRows(boolean extended) {
        List<Map<String,Object>> rows=new ArrayList<>();
        Map<String,String> definitions=extended
                ? AgentTaskProviderCostConsentSchemaInitializer.v3CheckExpressions()
                : AgentTaskProviderCostConsentSchemaInitializer.checkExpressions();
        definitions.forEach((name,clause)->rows.add(check(name,"YES",clause)));
        return rows;
    }

    private static List<Map<String,Object>> columnRows(boolean extended) {
        var definitions=extended
                ? AgentTaskProviderCostConsentSchemaInitializer.v3ColumnDefinitions()
                : AgentTaskProviderCostConsentSchemaInitializer.v1ColumnDefinitions();
        List<Map<String,Object>> rows=new ArrayList<>();
        definitions.forEach((name,value)->rows.add(column(name,value.type(),value.nullable(),
                value.collation(),value.defaultValue())));
        return rows;
    }

    private static List<Map<String,Object>> indexRows(boolean extended) {
        var definitions=extended
                ? AgentTaskProviderCostConsentSchemaInitializer.v3IndexDefinitions()
                : AgentTaskProviderCostConsentSchemaInitializer.v1IndexDefinitions();
        List<Map<String,Object>> rows=new ArrayList<>();
        definitions.forEach((name,value)->{
            for(int ordinal=0;ordinal<value.columns().size();ordinal++) {
                rows.add(index(name,value.nonUnique(),ordinal+1,value.columns().get(ordinal)));
            }
        });
        return rows;
    }

    private static Map<String,Object> column(String name,String type,String nullable,
            String collation,String defaultValue) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("column_name",name);
        row.put("column_type",type);row.put("is_nullable",nullable);
        row.put("collation_name",collation);row.put("column_default",defaultValue);return row;
    }
    private static Map<String,Object> index(String name,int nonUnique,int sequence,String column) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("index_name",name);
        row.put("non_unique",nonUnique);row.put("seq_in_index",sequence);
        row.put("column_name",column);row.put("sub_part",null);return row;
    }
    private static Map<String,Object> check(String name,String enforced,String clause) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("constraint_name",name);
        row.put("enforced",enforced);row.put("check_clause",clause);return row;
    }
}
