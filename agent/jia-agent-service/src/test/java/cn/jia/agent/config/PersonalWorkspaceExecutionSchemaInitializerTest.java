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
    void textMimeMigrationVerifiesEnforcedDefinitionAndIsIdempotent() {
        var jdbc=mock(JdbcTemplate.class);
        var old=java.util.Map.<String,Object>of("enforced","YES","check_clause",
                "(`output_content_mime_type` in ("+PersonalWorkspaceExecutionSchemaInitializer.DOCUMENT_MIMES.replace("'", "\\'")+"))");
        var current=java.util.Map.<String,Object>of("enforced","YES","check_clause",
                "(`output_content_mime_type` in ("+PersonalWorkspaceExecutionSchemaInitializer.OUTPUT_MIMES+"))");
        when(jdbc.queryForList(PersonalWorkspaceExecutionSchemaInitializer.MIME_CHECK_QUERY))
                .thenReturn(java.util.List.of(old),java.util.List.of(current));
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.migrateTextOutputMime();
        initializer.migrateTextOutputMime();
        verify(jdbc,times(1)).execute(PersonalWorkspaceExecutionSchemaInitializer.textOutputMimeMigrationStatement());
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.ddlStatements().getFirst().contains("'text/plain'"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.outputMimeMigrationStatement().contains("'text/plain'"));
    }

    @Test
    void missingDisabledDriftedOrUnappliedMimeCheckCannotPass() {
        for (var row:java.util.List.of(
                java.util.Map.<String,Object>of("enforced","NO","check_clause","1"),
                java.util.Map.<String,Object>of("enforced","YES","check_clause","1"),
                java.util.Map.<String,Object>of("enforced","YES","check_clause",
                    "output_content_mime_type in ("+PersonalWorkspaceExecutionSchemaInitializer.DOCUMENT_MIMES+")"))) {
            var jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForList(PersonalWorkspaceExecutionSchemaInitializer.MIME_CHECK_QUERY)).thenReturn(java.util.List.of(row));
            assertThrows(IllegalStateException.class,()->new PersonalWorkspaceExecutionSchemaInitializer(jdbc).migrateTextOutputMime());
        }
        assertThrows(IllegalStateException.class,()->new PersonalWorkspaceExecutionSchemaInitializer(mock(JdbcTemplate.class)).migrateTextOutputMime());
    }

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
        var provider=PersonalWorkspaceExecutionSchemaInitializer.conversationProviderStartMigrationStatement();
        assertTrue(provider.contains("conversation_provider_started_at BIGINT DEFAULT NULL"));
        assertTrue(provider.contains("chk_pwex_provider_start"));
        assertTrue(provider.contains("execution_mode='CONVERSATION'"));
        var lease=PersonalWorkspaceExecutionSchemaInitializer.conversationLeaseMigrationStatement();
        assertTrue(lease.contains("conversation_lease_version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(lease.contains("chk_pwex_conversation_lease"));
        assertTrue(lease.contains("conversation_lease_expires_at IS NOT NULL"));
    }
    private JdbcTemplate taskExecutionFixture() {
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution (
                    execution_mode VARCHAR(32), execution_state VARCHAR(32), execution_protocol_version INT,
                    work_item_id VARCHAR(64), lease_token VARCHAR(64), lease_work_item_version BIGINT,
                    lease_expires_at BIGINT, conversation_id VARCHAR(64), task_grant_id VARCHAR(64),
                    task_grant_version BIGINT, assignment_revision BIGINT, permitted_operation VARCHAR(64))
                """);
        jdbc.update("""
                INSERT INTO agent_personal_workspace_execution
                (execution_mode, execution_state, execution_protocol_version, conversation_id,
                 task_grant_id, task_grant_version, assignment_revision, permitted_operation)
                VALUES ('CONVERSATION', 'RESULT_COMMITTED', 4, 'conversation', 'grant', 1, 0, 'GENERATE_IMAGE')
                """);
        return jdbc;
    }

    @Test
    void committedSkillResultSurvivesRepeatedTaskStartupValidation() {
        var jdbc = taskExecutionFixture();
        var initializer = new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        assertDoesNotThrow(initializer::verifyTaskExecution);
        assertDoesNotThrow(initializer::verifyTaskExecution);
        assertEquals("RESULT_COMMITTED", jdbc.queryForObject(
                "SELECT execution_state FROM agent_personal_workspace_execution", String.class));
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void committedSkillResultRequiresProtocolFour() {
        var jdbc = taskExecutionFixture();
        var initializer = new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        for (Integer protocol : new Integer[]{null, 0, 3, 5}) {
            jdbc.update("UPDATE agent_personal_workspace_execution SET execution_protocol_version=?", protocol);
            assertThrows(IllegalStateException.class, initializer::verifyTaskExecution);
        }
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void committedSkillResultStillRequiresConversationAndTaskFences() {
        var jdbc = taskExecutionFixture();
        var initializer = new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        jdbc.update("UPDATE agent_personal_workspace_execution SET task_grant_version=0");
        assertThrows(IllegalStateException.class, initializer::verifyTaskExecution);
        jdbc.update("UPDATE agent_personal_workspace_execution SET task_grant_version=1, execution_mode='TASK'");
        assertThrows(IllegalStateException.class, initializer::verifyTaskExecution);
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_mode='PRIVATE', lease_token='foreign'");
        assertThrows(IllegalStateException.class, initializer::verifyTaskExecution);
        jdbc.update("UPDATE agent_personal_workspace_execution SET lease_token=NULL, execution_state='UNKNOWN'");
        assertThrows(IllegalStateException.class, initializer::verifyTaskExecution);
        jdbc.execute("SHUTDOWN");
    }
}
