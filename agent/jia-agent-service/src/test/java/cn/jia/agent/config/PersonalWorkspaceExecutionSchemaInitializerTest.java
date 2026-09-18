package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceExecutionSchemaInitializerTest {
    @Test
    void executionGrantSchemaIsStrictlyAdditiveAndComplete() {
        var statements = PersonalWorkspaceExecutionSchemaInitializer.ddlStatements();
        assertEquals(3, statements.size());
        assertTrue(statements.getFirst().contains("uk_pwex_task_run_scope"));
        assertTrue(statements.getFirst().contains("output_content_mime_type VARCHAR(127) NOT NULL"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.outputMimeMigrationStatement()
                .contains("ADD COLUMN output_content_mime_type VARCHAR(127) NOT NULL DEFAULT"));
        assertTrue(statements.get(1).contains("uk_pwexi_file_version_scope"));
        assertTrue(statements.get(2).contains("chk_pwexo_commit"));
    }
}
