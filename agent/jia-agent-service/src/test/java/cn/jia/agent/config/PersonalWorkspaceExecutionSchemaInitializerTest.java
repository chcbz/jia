package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceExecutionSchemaInitializerTest {

    @Test void conversationMigrationAddsColumnsWithoutChecks() {
        var execution=PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                PersonalWorkspaceExecutionSchemaInitializer.CONVERSATION_EXECUTION_RESOURCE,
                "agent_personal_workspace_execution");
        var output=PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                PersonalWorkspaceExecutionSchemaInitializer.CONVERSATION_OUTPUT_RESOURCE,
                "agent_personal_workspace_execution_output");
        assertTrue(execution.contains("task_grant_version"));
        assertTrue(output.contains("output_purpose"));
        org.junit.jupiter.api.Assertions.assertFalse((execution+output).toLowerCase().contains("check ("));
        assertThrows(IllegalArgumentException.class,()->PersonalWorkspaceExecutionSchemaInitializer.conversationMigrationStatement(
                "db/untrusted.sql", "agent_personal_workspace_execution"));
    }

    @Test void conversationRowsAreValidatedWithoutCheckCatalog() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        assertDoesNotThrow(initializer::verifyConversationMode);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        assertThrows(IllegalStateException.class,initializer::verifyConversationMode);
    }

    @Test void leaseRowsAreValidatedWithoutCheckCatalog() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(0);
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        assertDoesNotThrow(initializer::verifyConversationLease);
        when(jdbc.queryForObject(anyString(),eq(Integer.class))).thenReturn(1);
        assertThrows(IllegalStateException.class,initializer::verifyConversationLease);
    }

    @Test
    void executionGrantSchemaIsStrictlyAdditiveAndComplete() {
        var statements = PersonalWorkspaceExecutionSchemaInitializer.ddlStatements();
        assertEquals(3, statements.size());
        assertTrue(statements.getFirst().contains("uk_pwex_task_run_scope"));
        assertTrue(statements.getFirst().contains("output_content_mime_type VARCHAR(127) NOT NULL"));
        assertTrue(statements.getFirst().contains("failure_code VARCHAR(64) DEFAULT NULL"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.outputMimeMigrationStatement()
                .contains("ADD COLUMN output_content_mime_type VARCHAR(127) NOT NULL DEFAULT"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskExecutionMigrationStatement()
                .contains("ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'PRIVATE'"));
        assertTrue(PersonalWorkspaceExecutionSchemaInitializer.taskPublicationMigrationStatement()
                .contains("ADD COLUMN publication_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(statements.get(1).contains("uk_pwexi_file_version_scope"));
        var provider=PersonalWorkspaceExecutionSchemaInitializer.conversationProviderStartMigrationStatement();
        assertTrue(provider.contains("conversation_provider_started_at BIGINT DEFAULT NULL"));

        var lease=PersonalWorkspaceExecutionSchemaInitializer.conversationLeaseMigrationStatement();
        assertTrue(lease.contains("conversation_lease_version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(lease.contains("conversation_lease_expires_at BIGINT"));
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
    void committedSkillResultRequiresProtocolFourOrFive() {
        var jdbc = taskExecutionFixture();
        var initializer = new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        for (int protocol : new int[]{4, 5}) {
            jdbc.update("UPDATE agent_personal_workspace_execution SET execution_protocol_version=?", protocol);
            assertDoesNotThrow(initializer::verifyTaskExecution);
        }
        for (Integer protocol : new Integer[]{null, 0, 3, 6}) {
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
