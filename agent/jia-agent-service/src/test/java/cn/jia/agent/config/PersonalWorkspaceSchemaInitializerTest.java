package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceSchemaInitializerTest {
    @Test
    void acceptsOnlyTheThreeAdditiveWorkspaceCreateStatements() {
        var statements = PersonalWorkspaceSchemaInitializer.ddlStatements();
        assertEquals(3, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_file ("));
        assertTrue(statements.get(1).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_file_version ("));
        assertTrue(statements.get(2).startsWith("CREATE TABLE IF NOT EXISTS agent_personal_workspace_operation ("));
    }
}
