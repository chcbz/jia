package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceExecutionSchemaInitializerTest {
    @Test
    void conversationMigrationContainsByteOnlyAndGrantFenceChecks() {
        var execution=PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                PersonalWorkspaceExecutionSchemaInitializer.CONVERSATION_EXECUTION_RESOURCE,
                "agent_personal_workspace_execution", "drop check chk_pwex_mode", "drop check chk_pwex_task_bridge");
        var output=PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                PersonalWorkspaceExecutionSchemaInitializer.CONVERSATION_OUTPUT_RESOURCE,
                "agent_personal_workspace_execution_output", "drop check chk_pwexo_commit", "add constraint chk_pwexo_conversation");
        assertTrue(execution.contains("task_grant_version >= 1"));
        assertTrue(execution.contains("execution_mode='CONVERSATION'"));
        assertTrue(output.contains("output_purpose='CONVERSATION'"));
        assertTrue(output.contains("formal_delivery_id IS NULL"));
        assertThrows(IllegalArgumentException.class,()->
                PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                        "db/untrusted.sql", "agent_personal_workspace_execution", "", ""));
    }

    @Test
    void absentEnforcedMysqlCheckFailsClosedEvenWithZeroBadRows() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () ->
                new PersonalWorkspaceExecutionSchemaInitializer(jdbc).verifyConversationMode());
    }

    @Test
    void missingOrDisabledLeaseCheckFailsStartup() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->
                new PersonalWorkspaceExecutionSchemaInitializer(jdbc).verifyConversationLease());
        when(jdbc.queryForList(anyString(),eq("agent_personal_workspace_execution"),eq("chk_pwex_conversation_lease"))).thenReturn(java.util.List.of(
                java.util.Map.of("enforced","NO","check_clause","conversation_lease_version>=0")));
        assertThrows(IllegalStateException.class,()->
                new PersonalWorkspaceExecutionSchemaInitializer(jdbc).verifyConversationLease());
    }

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
        var lease=PersonalWorkspaceExecutionSchemaInitializer.conversationLeaseMigrationStatement();
        assertTrue(lease.contains("conversation_lease_version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(lease.contains("chk_pwex_conversation_lease"));
        assertTrue(lease.contains("conversation_lease_expires_at IS NOT NULL"));
    }
}
