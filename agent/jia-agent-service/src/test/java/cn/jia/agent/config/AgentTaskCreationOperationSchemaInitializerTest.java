package cn.jia.agent.config;

import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCreationOperationSchemaInitializerTest {
    @Test
    void ddlIsOneAdditiveCreateWithBinaryScopeAndEnforcedReceiptConstraints() {
        String ddl = AgentTaskCreationOperationSchemaInitializer.ddl();
        String normalized = ddl.replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertTrue(normalized.startsWith(
                "create table if not exists agent_task_creation_operation "));
        assertFalse(normalized.contains(";"));
        for (String forbidden : new String[] {" alter table ", " drop ", " insert ",
                " update ", " delete ", " replace "}) {
            assertFalse((" " + normalized + " ").contains(forbidden), forbidden);
        }
        for (String required : new String[] {"engine=innodb", "utf8mb4_0900_bin",
                "uk_atco_scope_key", "uk_atco_scope_operation", "chk_atco_scope",
                "chk_atco_identity", "chk_atco_hash", "chk_atco_refs",
                "chk_atco_receipt", "chk_atco_time", "requirement_revision=1",
                "json_length(input_refs_json) between 0 and 32", "tenant_id is not null",
                "tenant_id regexp binary '^(0|[1-9][0-9]{0,49})$'",
                "tenant_id not regexp binary '[^0-9]'",
                "instr(tenant_id, char(10)) = 0", "instr(tenant_id, char(13)) = 0",
                "owner_jiacn<>'0'"}) {
            assertTrue(normalized.contains(required), required);
        }
    }

    @Test
    void tenantCheckMatchesCanonicalJavaTenantIdBoundary() {
        String fiftyDigits = "1" + "0".repeat(49);
        for (String valid : List.of("0", "1", fiftyDigits)) {
            assertEquals(valid, TenantId.of(valid).value());
        }
        assertThrows(NullPointerException.class, () -> TenantId.of(null));
        for (String invalid : List.of("", "00", "01", "-1", "+1", "１２", "١",
                " 1", "1 ", "1\t", "1\u000b", "1\u000c", "1\n2", "1\r2", "1\n",
                "1\u0085", "1\u2028", "1\u2029",
                "1" + "0".repeat(50))) {
            assertThrows(IllegalArgumentException.class, () -> TenantId.of(invalid), invalid);
        }
    }

    @Test
    void manualCandidateIsSevenAtomicCheckOnlyAltersWithoutBackfill() throws Exception {
        String sql = new ClassPathResource("db/agent-tenant-checks-manual-candidate-20261023.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String normalized = sql.replaceAll("(?m)^\\s*--.*$", " ")
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        assertEquals(7, normalized.split(";").length);
        assertEquals(7, count(normalized, "alter table "));
        assertEquals(7, count(normalized, " drop check "));
        assertEquals(7, count(normalized, " add constraint "));
        assertFalse(normalized.contains(" insert "));
        assertFalse(normalized.contains(" update "));
        assertFalse(normalized.contains(" delete "));
        assertFalse(normalized.contains(" modify column "));
        assertTrue(sql.contains("Execution requires shared-consumer compatibility verification"));
    }

    @Test
    void actualMysql8021EscapedCatalogMatchesAllExactCheckDefinitions() {
        AgentTaskCreationOperationSchemaInitializer.validateChecks(actualCatalogRows());
    }

    @Test
    void canonicalizationPreservesOperatorsQuotesParenthesesAndLiteralCase() {
        String actual = row(actualCatalogRows(), "chk_atco_scope").get("CHECK_CLAUSE").toString();
        String canonical = AgentTaskCreationOperationSchemaInitializer
                .canonicalCheckExpression(actual);
        assertEquals("((tenant_idisnotnull)andregexp_like(tenant_id,"
                + "cast('^(0|[1-9][0-9]{0,49})$'ascharcharsetbinary))and"
                + "not(regexp_like(tenant_id,cast('[^0-9]'ascharcharsetbinary)))and"
                + "(instr(tenant_id,char(10))=0)and(instr(tenant_id,char(13))=0)and"
                + "(owner_jiacn<>'0'))", canonical);
        assertTrue(canonical.contains("<>"));
        assertTrue(canonical.startsWith("(("));
        assertTrue(canonical.endsWith("))"));
        assertTrue(canonical.contains("'0'"));
        assertNotEquals(canonical, AgentTaskCreationOperationSchemaInitializer
                .canonicalCheckExpression(actual.replace("<>", "=")));
    }

    @Test
    void multiSlashBeforeCatalogQuoteIsRejectedAsAmbiguousLiteralData() {
        List<Map<String, Object>> weakened = actualCatalogRows();
        Map<String, Object> scope = row(weakened, "chk_atco_scope");
        String clause = scope.get("CHECK_CLAUSE").toString();
        int opening = clause.indexOf("\\'");
        int closing = clause.indexOf("\\'", opening + 2);
        assertTrue(opening >= 0 && closing > opening);
        scope.put("CHECK_CLAUSE", clause.substring(0, closing) + "\\"
                + clause.substring(closing));

        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(weakened));
        assertThrows(IllegalArgumentException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.canonicalCheckExpression(
                        scope.get("CHECK_CLAUSE").toString()));
    }

    @Test
    void weakOrTrueAndChangedConjunctionFailClosed() {
        List<Map<String, Object>> weak = actualCatalogRows();
        row(weak, "chk_atco_scope").compute("CHECK_CLAUSE",
                (ignored, clause) -> clause + " OR TRUE");
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(weak));

        List<Map<String, Object>> changed = actualCatalogRows();
        row(changed, "chk_atco_scope").compute("CHECK_CLAUSE",
                (ignored, clause) -> clause.toString().replace(" and ", " or "));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(changed));
    }

    @Test
    void nullReceiptRefsBoundAndHashDefinitionDriftFailClosed() {
        List<Map<String, Object>> nullReceipt = actualCatalogRows();
        row(nullReceipt, "chk_atco_receipt").put("CHECK_CLAUSE", null);
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(nullReceipt));

        List<Map<String, Object>> refs = actualCatalogRows();
        row(refs, "chk_atco_refs").compute("CHECK_CLAUSE",
                (ignored, clause) -> clause.toString().replace("between 0 and 32",
                        "between 0 and 33"));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(refs));

        List<Map<String, Object>> hash = actualCatalogRows();
        row(hash, "chk_atco_hash").compute("CHECK_CLAUSE",
                (ignored, clause) -> clause.toString().replace("{64}", "{63}"));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(hash));
    }

    @Test
    void notEnforcedMissingExtraAndDuplicateChecksFailClosed() {
        List<Map<String, Object>> notEnforced = actualCatalogRows();
        row(notEnforced, "chk_atco_hash").put("ENFORCED", "NO");
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(notEnforced));

        List<Map<String, Object>> missing = actualCatalogRows();
        missing.removeIf(candidate -> "chk_atco_time".equals(candidate.get("CONSTRAINT_NAME")));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(missing));

        List<Map<String, Object>> extra = actualCatalogRows();
        extra.add(check("chk_atco_extra", "YES", "(1 = 1)"));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(extra));

        List<Map<String, Object>> duplicate = actualCatalogRows();
        duplicate.add(new LinkedHashMap<>(duplicate.getFirst()));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskCreationOperationSchemaInitializer.validateChecks(duplicate));
    }

    private static List<Map<String, Object>> actualCatalogRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(check("chk_atco_hash", "YES", escaped(
                "((char_length(`request_hash`) = 64) and regexp_like(`request_hash`,"
                        + "cast(_utf8mb4'^[0-9a-f]{64}$' as char charset binary)))")));
        rows.add(check("chk_atco_identity", "YES", escaped(
                "((char_length(`client_id`) between 1 and 50) and "
                        + "(char_length(`owner_jiacn`) between 1 and 50) and "
                        + "(char_length(`operation_id`) between 1 and 100) and "
                        + "(char_length(`idempotency_key`) between 1 and 100) and "
                        + "(`operation_state` in (_utf8mb4'PROCESSING',"
                        + "_utf8mb4'COMMITTED')))")));
        rows.add(check("chk_atco_receipt", "YES", escaped(
                "(((`operation_state` = _utf8mb4'PROCESSING') and (`task_id` is null) "
                        + "and (`requirement_revision` is null) and (`completed_at` is null)) "
                        + "or ((`operation_state` = _utf8mb4'COMMITTED') and "
                        + "(`task_id` is not null) and (`requirement_revision` = 1) "
                        + "and (`completed_at` is not null)))")));
        rows.add(check("chk_atco_refs", "YES", escaped(
                "((json_type(`input_refs_json`) = _utf8mb4'ARRAY') and "
                        + "(json_length(`input_refs_json`) between 0 and 32))")));
        rows.add(check("chk_atco_scope", "YES", escaped(
                "((`tenant_id` is not null) and regexp_like(`tenant_id`,"
                        + "cast(_utf8mb4'^(0|[1-9][0-9]{0,49})$' as char charset binary)) "
                        + "and not(regexp_like(`tenant_id`,"
                        + "cast(_utf8mb4'[^0-9]' as char charset binary))) "
                        + "and (instr(`tenant_id`,char(10)) = 0) "
                        + "and (instr(`tenant_id`,char(13)) = 0) "
                        + "and (`owner_jiacn` <> _utf8mb4'0'))")));
        rows.add(check("chk_atco_time", "YES",
                "((`created_at` > 0) and ((`completed_at` is null) "
                        + "or (`completed_at` >= `created_at`)))"));
        return rows;
    }

    private static Map<String, Object> check(String name, String enforced, String clause) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("CONSTRAINT_NAME", name);
        row.put("ENFORCED", enforced);
        row.put("CHECK_CLAUSE", clause);
        return row;
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String name) {
        return rows.stream().filter(candidate -> name.equals(candidate.get("CONSTRAINT_NAME")))
                .findFirst().orElseThrow();
    }

    private static String escaped(String expression) {
        return expression.replace("'", "\\'");
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) {
            count++;
        }
        return count;
    }
}
