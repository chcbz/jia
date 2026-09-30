package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

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
                "json_length(input_refs_json) between 0 and 32"}) {
            assertTrue(normalized.contains(required), required);
        }
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
        assertEquals("((tenant_id='0')and(owner_jiacn<>'0'))", canonical);
        assertTrue(canonical.contains("<>"));
        assertTrue(canonical.startsWith("(("));
        assertTrue(canonical.endsWith("))"));
        assertTrue(canonical.contains("'0'"));
        assertNotEquals(canonical, AgentTaskCreationOperationSchemaInitializer
                .canonicalCheckExpression(actual.replace("<>", "=")));
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
                "((`tenant_id` = _utf8mb4'0') and (`owner_jiacn` <> _utf8mb4'0'))")));
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
        return expression.replace("'", "\\\\'");
    }
}
