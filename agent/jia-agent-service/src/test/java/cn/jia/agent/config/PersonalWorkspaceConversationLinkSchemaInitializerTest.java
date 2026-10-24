package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceConversationLinkSchemaInitializerTest {
    @Test
    void schemaIsAdditiveAndContainsOnlyTheTwoOwnedTables() {
        var statements = PersonalWorkspaceConversationLinkSchemaInitializer.ddlStatements();
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_personal_workspace_conversation_file_link ("));
        assertTrue(statements.get(1).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_personal_workspace_conversation_link_operation ("));
        for (String statement : statements) {
            String lower = statement.toLowerCase();
            assertFalse(lower.contains("alter table"));
            assertFalse(lower.contains("drop table"));
            assertFalse(lower.contains("insert into agent_task"));
        }
    }

    @Test
    void canonicalTenantChecksAcceptExactMysqlCatalogAndRejectDrift() {
        var rows = tenantCheckRows();
        PersonalWorkspaceConversationLinkSchemaInitializer.validateTenantChecks(rows);

        var weakened = tenantCheckRows();
        weakened.getFirst().compute("CHECK_CLAUSE",
                (ignored, clause) -> clause + " OR TRUE");
        assertThrows(IllegalStateException.class,
                () -> PersonalWorkspaceConversationLinkSchemaInitializer
                        .validateTenantChecks(weakened));

        var missing = tenantCheckRows();
        missing.removeLast();
        assertThrows(IllegalStateException.class,
                () -> PersonalWorkspaceConversationLinkSchemaInitializer
                        .validateTenantChecks(missing));

        var disabled = tenantCheckRows();
        disabled.getFirst().put("ENFORCED", "NO");
        assertThrows(IllegalStateException.class,
                () -> PersonalWorkspaceConversationLinkSchemaInitializer
                        .validateTenantChecks(disabled));
    }

    private static List<Map<String, Object>> tenantCheckRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(check("chk_pwcl_single_tenant"));
        rows.add(check("chk_pwcl_operation_tenant"));
        return rows;
    }

    private static Map<String, Object> check(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("CONSTRAINT_NAME", name);
        row.put("ENFORCED", "YES");
        row.put("CHECK_CLAUSE", "((`tenant_id` is not null) and "
                + "regexp_like(`tenant_id`,cast(_utf8mb4\\'^(0|[1-9][0-9]{0,49})$\\' "
                + "as char charset binary)) and not(regexp_like(`tenant_id`,"
                + "cast(_utf8mb4\\'[^0-9]\\' as char charset binary))) and "
                + "(instr(`tenant_id`,char(10)) = 0) and "
                + "(instr(`tenant_id`,char(13)) = 0))");
        return row;
    }
}
