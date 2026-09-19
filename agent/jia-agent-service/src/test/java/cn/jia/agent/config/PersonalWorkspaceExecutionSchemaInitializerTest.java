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
        assertTrue(statements.getFirst().contains("failure_code VARCHAR(64) DEFAULT NULL"));
        assertTrue(statements.getFirst().contains("chk_pwex_failure"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.outputMimeMigrationStatement()
                .contains("ADD COLUMN output_content_mime_type VARCHAR(127) NOT NULL DEFAULT"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.terminalStateMigrationStatement()
                .contains("DROP CHECK chk_pwex_state"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskExecutionMigrationStatement()
                .contains("ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskExecutionMigrationStatement()
                .contains("ADD CONSTRAINT chk_pwex_task_bridge"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskPublicationMigrationStatement()
                .contains("ADD COLUMN publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskPublicationMigrationStatement()
                .contains("ADD CONSTRAINT chk_pwexo_publication_mapping"));
        assertTrue(statements.get(1).contains("uk_pwexi_file_version_scope"));
        assertTrue(statements.get(2).contains("chk_pwexo_commit"));
    }
}
