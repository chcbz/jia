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
        assertFalse(ddl.contains("regexp binary"));
    }

    @Test void binaryCollationCanonicalizationAcceptsOnlyFrozenLegacyRenderings() {
        String plain="regexp_like(content_sha256,'^[0-9a-f]{64}$')";
        String legacy="regexp_like(content_sha256,cast('^[0-9a-f]{64}$' as char charset binary))";
        assertEquals(ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(plain),
                ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(legacy));
        for (String drift:List.of(
                "regexp_like(content_sha256,'^[0-9A-F]{64}$')",
                "regexp_like(content_sha256,'^[0-9a-f]{63}$')",
                "regexp_like(content_sha256,'^[0-9a-f]{64}$') OR TRUE",
                "regexp_like(content_sha256,cast('^[0-9a-f]{64}$' as binary))")) {
            assertNotEquals(ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(plain),
                    ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(drift));
        }
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


    @Test void observedMysql8021OwnedChecksAcceptExactLiteralsAndRejectEveryDriftShape() {
        Map<String,String> additive=ControlledImageFollowupV3SchemaInitializer.additiveCheckExpressions();
        assertStrictObservedCatalog("operation",observedOperationCatalogChecks(),
                ControlledImageFollowupV3SchemaInitializer.operationGrantCheckExpressions());
        assertStrictObservedCatalog("source",observedSourceCatalogChecks(),
                ControlledImageFollowupV3SchemaInitializer.sourceCheckExpressions());
        assertStrictObservedCatalog("consent",observedConsentCatalogChecks(),Map.of(
                "chk_atpcc_purpose_union",additive.get("chk_atpcc_purpose_union")));
        assertStrictObservedCatalog("execution",observedExecutionCatalogChecks(),Map.of(
                "chk_pwex_controlled_consent",additive.get("chk_pwex_controlled_consent"),
                "chk_pwex_execution_protocol",additive.get("chk_pwex_execution_protocol")));
    }

    @Test void strictPurposeAndExecutionProtocolUnionsRetainLegacyAndV3MutualExclusion() {
        String purpose=ControlledImageFollowupV3SchemaInitializer.consentPurposeCheckExpression();
        for(String required:List.of("INITIAL_ASSIGN_AND_START","FOLLOWUP_EXECUTE",
                "operation_grant_id IS NULL","operation_grant_id REGEXP",
                "runtime_input_snapshot_sha256 IS NULL","reserved_execution_id IS NOT NULL"))
            assertTrue(purpose.contains(required),required);
        String controlled=ControlledImageFollowupV3SchemaInitializer.controlledConsentCheckExpression();
        for(String required:List.of("execution_protocol_version=2","execution_protocol_version=3",
                "permitted_operation='GENERATE_IMAGE'","permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE')",
                "operation_grant_id IS NULL","runtime_input_snapshot_digest REGEXP"))
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


    private static void assertStrictObservedCatalog(String table,Map<String,String> observed,
            Map<String,String> expected) {
        assertEquals(expected.keySet(),observed.keySet());
        assertDoesNotThrow(()->ControlledImageFollowupV3SchemaInitializer.validateExactChecks(
                table,rows(observed),expected));

        var weak=rows(observed);
        weak.getFirst().put("check_clause","("+weak.getFirst().get("check_clause")+") OR 1=1");
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks(table,weak,expected));

        var alwaysTrue=rows(observed);
        alwaysTrue.getFirst().put("check_clause","1=1");
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks(table,alwaysTrue,expected));

        var extra=rows(observed);
        extra.add(check("chk_unexpected_extra","YES","1=1"));
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks(table,extra,expected));

        var missing=rows(observed);
        missing.removeFirst();
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks(table,missing,expected));

        var unenforced=rows(observed);
        unenforced.getFirst().put("enforced","NO");
        assertThrows(IllegalStateException.class,()->ControlledImageFollowupV3SchemaInitializer
                .validateExactChecks(table,unenforced,expected));
    }

    private static Map<String,String> observedOperationCatalogChecks() {
        Map<String,String> checks=new LinkedHashMap<>();
        checks.put("chk_aciiog_hashes",
                "(regexp_like(`requirement_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`" +
                "instruction_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`source_snapsho" +
                "t_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`owner_payload_sha256`,ca" +
                "st(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`issue_request_digest`,cast(_utf8mb4\\" +
                "'^[0-9a-f]{64}$\\' as char charset binary)) and ((`revoke_request_digest` is null) or regexp_like(`revoke_req" +
                "uest_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary))))");
        checks.put("chk_aciiog_ids",
                "(regexp_like(`operation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{32}$\\' as char charset binary)) and regex" +
                "p_like(`consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)))");
        checks.put("chk_aciiog_lifecycle",
                "(((`state` = _utf8mb4\\'AUTHORIZED\\') and (`reserved_execution_id` is null) and (`reserved_run_id` is null) a" +
                "nd (`consumed_lease_id` is null) and (`revoke_idempotency_key` is null) and (`revoke_request_digest` is null" +
                ") and (`revoked_at` is null)) or ((`state` = _utf8mb4\\'RESERVED\\') and (`reserved_execution_id` is not null)" +
                " and (`reserved_run_id` is not null) and (`consumed_lease_id` is null) and (`revoke_idempotency_key` is null" +
                ") and (`revoke_request_digest` is null) and (`revoked_at` is null)) or ((`state` = _utf8mb4\\'CONSUMED\\') and" +
                " (`reserved_execution_id` is not null) and (`reserved_run_id` is not null) and (`consumed_lease_id` is not n" +
                "ull) and (`revoke_idempotency_key` is null) and (`revoke_request_digest` is null) and (`revoked_at` is null)" +
                ") or ((`state` = _utf8mb4\\'REVOKED\\') and (((`reserved_execution_id` is null) and (`reserved_run_id` is null" +
                ")) or ((`reserved_execution_id` is not null) and (`reserved_run_id` is not null))) and (`consumed_lease_id` " +
                "is null) and (`revoke_idempotency_key` is not null) and (`revoke_request_digest` is not null) and (`revoked_" +
                "at` is not null)))");
        checks.put("chk_aciiog_operation",
                "(`operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\'))");
        checks.put("chk_aciiog_scope",
                "((`tenant_id` = _utf8mb4\\'0\\') and (`owner_jiacn` <> _utf8mb4\\'0\\'))");
        checks.put("chk_aciiog_sources",
                "((json_type(`source_snapshot_json`) = _utf8mb4\\'ARRAY\\') and (((`operation` = _utf8mb4\\'GENERATE_IMAGE\\') an" +
                "d (json_length(`source_snapshot_json`) between 0 and 16) and (json_search(`source_snapshot_json`,_utf8mb4\\'o" +
                "ne\\',_utf8mb4\\'CURRENT_CONVERSATION_ASSET\\',NULL,_utf8mb4\\'$[*].kind\\') is null)) or ((`operation` = _utf8mb" +
                "4\\'EDIT_IMAGE\\') and (json_length(`source_snapshot_json`) = 1) and (json_unquote(json_extract(`source_snapsh" +
                "ot_json`,_utf8mb4\\'$[0].kind\\')) = _utf8mb4\\'CURRENT_CONVERSATION_ASSET\\'))))");
        checks.put("chk_aciiog_state",
                "(`state` in (_utf8mb4\\'AUTHORIZED\\',_utf8mb4\\'RESERVED\\',_utf8mb4\\'CONSUMED\\',_utf8mb4\\'REVOKED\\'))");
        checks.put("chk_aciiog_versions",
                "((`conversation_generation` > 0) and (`baseline_grant_version` > 0) and (`task_version` >= 0) and (`assignme" +
                "nt_revision` >= 0) and (`requirement_revision` > 0) and (`version` > 0))");
        return checks;
    }

    private static Map<String,String> observedSourceCatalogChecks() {
        Map<String,String> checks=new LinkedHashMap<>();
        checks.put("chk_acies_common",
                "((`input_ordinal` between 1 and 16) and (`content_mime_type` in (_utf8mb4\\'image/jpeg\\',_utf8mb4\\'image/png\\" +
                "')) and (`byte_length` > 0) and regexp_like(`content_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset" +
                " binary)))");
        checks.put("chk_acies_scope",
                "((`tenant_id` = _utf8mb4\\'0\\') and (`owner_jiacn` <> _utf8mb4\\'0\\'))");
        checks.put("chk_acies_union",
                "(((`source_kind` = _utf8mb4\\'TASK_LINKED_WORKSPACE_VERSION\\') and (`file_id` is not null) and (`file_version" +
                "` > 0) and (`purpose` = _utf8mb4\\'REFERENCE\\') and (`conversation_id` is null) and (`conversation_generation" +
                "` is null) and (`asset_id` is null) and (`asset_revision` is null) and (`producer_request_id` is null) and (" +
                "`producer_request_revision` is null) and (`producer_step_id` is null) and (`producer_execution_id` is null) " +
                "and (`producer_run_id` is null) and (`producer_output_id` is null)) or ((`source_kind` = _utf8mb4\\'CURRENT_C" +
                "ONVERSATION_ASSET\\') and (`file_id` is null) and (`file_version` is null) and (`purpose` is null) and (`conv" +
                "ersation_id` is not null) and (`conversation_generation` > 0) and (`asset_id` is not null) and (`asset_revis" +
                "ion` > 0) and (`producer_request_id` is not null) and (`producer_request_revision` > 0) and (`producer_step_" +
                "id` is not null) and (`producer_execution_id` is not null) and (`producer_run_id` is not null) and (`produce" +
                "r_output_id` is not null)))");
        return checks;
    }

    private static Map<String,String> observedConsentCatalogChecks() {
        Map<String,String> checks=new LinkedHashMap<>();
        checks.put("chk_atpcc_purpose_union",
                "((`consent_purpose` in (_utf8mb4\\'INITIAL_ASSIGN_AND_START\\',_utf8mb4\\'FOLLOWUP_EXECUTE\\')) and (((`consent_" +
                "purpose` = _utf8mb4\\'INITIAL_ASSIGN_AND_START\\') and (`operation_grant_id` is null) and (`execution_intent_i" +
                "d` is null) and (`conversation_id` is null) and (`conversation_generation` is null) and (`operation` is null" +
                ") and (`instruction_sha256` is null) and (`source_snapshot_sha256` is null) and (`owner_payload_sha256` is n" +
                "ull) and (`runtime_input_snapshot_sha256` is null)) or ((`consent_purpose` = _utf8mb4\\'FOLLOWUP_EXECUTE\\') a" +
                "nd regexp_like(`operation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{32}$\\' as char charset binary)) and (ch" +
                "ar_length(`execution_intent_id`) between 1 and 100) and (char_length(`conversation_id`) between 1 and 100) a" +
                "nd (`conversation_generation` > 0) and (`operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) " +
                "and regexp_like(`instruction_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_lik" +
                "e(`source_snapshot_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and regexp_like(`owner_p" +
                "ayload_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)) and (((`reserved_execution_id` is nu" +
                "ll) and (`runtime_input_snapshot_sha256` is null)) or ((`reserved_execution_id` is not null) and regexp_like" +
                "(`runtime_input_snapshot_sha256`,cast(_utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)))))))");
        return checks;
    }

    private static Map<String,String> observedExecutionCatalogChecks() {
        Map<String,String> checks=new LinkedHashMap<>();
        checks.put("chk_pwex_controlled_consent",
                "((`controlled_consent_id` is null) or ((`execution_mode` = _utf8mb4\\'CONVERSATION\\') and regexp_like(`contro" +
                "lled_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (((`execution_protocol" +
                "_version` = 2) and (`permitted_operation` = _utf8mb4\\'GENERATE_IMAGE\\') and (`operation_grant_id` is null) a" +
                "nd (`runtime_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 3) and (`permitted_operati" +
                "on` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\')) and regexp_like(`operation_grant_id`,cast(_utf8m" +
                "b4\\'^opgrant_[0-9a-f]{32}$\\' as char charset binary)) and regexp_like(`runtime_input_snapshot_digest`,cast(_" +
                "utf8mb4\\'^[0-9a-f]{64}$\\' as char charset binary)))) and (`output_content_mime_type` = _utf8mb4\\'image/png\\'" +
                ")))");
        checks.put("chk_pwex_execution_protocol",
                "(((`execution_protocol_version` = 1) and (`controlled_consent_id` is null) and (`operation_grant_id` is null" +
                ") and (`runtime_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 2) and regexp_like(`con" +
                "trolled_consent_id`,cast(_utf8mb4\\'^consent_[0-9a-f]{32}$\\' as char charset binary)) and (`operation_grant_i" +
                "d` is null) and (`runtime_input_snapshot_digest` is null)) or ((`execution_protocol_version` = 3) and (`exec" +
                "ution_mode` = _utf8mb4\\'CONVERSATION\\') and regexp_like(`controlled_consent_id`,cast(_utf8mb4\\'^consent_[0-9" +
                "a-f]{32}$\\' as char charset binary)) and regexp_like(`operation_grant_id`,cast(_utf8mb4\\'^opgrant_[0-9a-f]{3" +
                "2}$\\' as char charset binary)) and regexp_like(`runtime_input_snapshot_digest`,cast(_utf8mb4\\'^[0-9a-f]{64}$" +
                "\\' as char charset binary)) and (`permitted_operation` in (_utf8mb4\\'GENERATE_IMAGE\\',_utf8mb4\\'EDIT_IMAGE\\'" +
                ")) and (`output_content_mime_type` = _utf8mb4\\'image/png\\')))");
        return checks;
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
