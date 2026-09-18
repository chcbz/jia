package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceTaskLinkSchemaInitializerTest {
    @Test
    void schemaIsAdditiveAndContainsOnlyTheTwoOwnedTables() {
        var statements = PersonalWorkspaceTaskLinkSchemaInitializer.ddlStatements();
        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_personal_workspace_task_file_link ("));
        assertTrue(statements.get(1).startsWith(
                "CREATE TABLE IF NOT EXISTS agent_personal_workspace_task_link_operation ("));
        for (String statement : statements) {
            String lower = statement.toLowerCase();
            assertFalse(lower.contains("alter table"));
            assertFalse(lower.contains("drop table"));
            assertFalse(lower.contains("insert into agent_task"));
        }
    }
}
