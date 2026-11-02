package cn.jia.agent.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL 8 regression: pre-provider schema survives restart and fails closed when partial. */
@EnabledIfEnvironmentVariable(named = "MMD_PROVIDER_MYSQL_URL", matches = ".+")
class PersonalWorkspaceProviderStartMySqlTest {
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;

    @BeforeEach void open() {
        String base=System.getenv("MMD_PROVIDER_MYSQL_URL");
        assertTrue(base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/mysql\\?.+"),
                "Only isolated loopback MySQL test URL is accepted");
        String user=System.getenv().getOrDefault("MMD_PROVIDER_MYSQL_USER","root");
        String password=System.getenv().getOrDefault("MMD_PROVIDER_MYSQL_PASSWORD","");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8.0.21"));
        database="mmd_provider_"+Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(base.replace("/mysql?","/"+database+"?"),user,password));
        // Install the actual structural predecessors before startup's protocol-aware row validation.
        var schema=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator();
        for (String resource:java.util.List.of(
                "db/agent-personal-workspace-v1_11-executions.sql",
                "db/agent-personal-workspace-v1_13-task-execution.sql",
                "db/agent-personal-workspace-v1_13-task-publication.sql",
                "db/agent-personal-workspace-v1_14-conversation-execution.sql",
                "db/agent-personal-workspace-v1_14-conversation-output.sql",
                "db/agent-personal-workspace-v1_15-conversation-lease.sql",
                "db/agent-personal-workspace-v1_16-conversation-provider-start.sql"))
            schema.addScript(new org.springframework.core.io.ClassPathResource(resource));
        schema.execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
    }
    @AfterEach void close() {
        if(admin!=null && database!=null) admin.execute("DROP DATABASE IF EXISTS "+database);
    }
    @Test void imageAndTextRowsSurviveRestartAndTransactionRollback() throws Exception {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        insertConversation("image", "image/png");
        initializer.afterPropertiesSet();
        insertConversation("text", "text/plain");
        initializer.afterPropertiesSet();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution",Integer.class));
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("UPDATE agent_personal_workspace_execution SET instruction=? WHERE execution_id='text'")) {
                statement.setString(1,"rolled back");statement.executeUpdate();
            }
            connection.rollback();
        }
        assertEquals("test text",jdbc.queryForObject("SELECT instruction FROM agent_personal_workspace_execution WHERE execution_id='text'",String.class));
        assertEquals("image/png",jdbc.queryForObject("SELECT output_content_mime_type FROM agent_personal_workspace_execution WHERE execution_id='image'",String.class));
    }
    @Test void freshSchemaAcceptsTextWithoutMimeCheck() {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        insertConversation("fresh", "text/plain");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(initializer::afterPropertiesSet);
    }
    private void insertConversation(String id,String mime) {
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution
              (execution_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,
               output_content_mime_type,execution_state,grant_revision,idempotency_key,
               request_hash,created_at,tenant_id,client_id,execution_mode,conversation_id,
               task_grant_id,task_grant_version,assignment_revision,permitted_operation)
            VALUES (?,'owner',?,?,'agent','test text',?,'QUEUED',1,?,?,1,'0','client',
                    'CONVERSATION','conversation','grant',1,1,?)
            """,id,"task-"+id,"run-"+id,mime,"key-"+id,"a".repeat(64),
                    mime.equals("text/plain")?"GENERATE_TEXT_FILE":"GENERATE_IMAGE");
    }

    @Test void oldPopulatedPrivateSchemaMigratesAndRepeatedBootPreservesRows() {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution
              (execution_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,
               output_content_mime_type,execution_state,grant_revision,idempotency_key,
               request_hash,created_at,tenant_id,client_id)
            VALUES ('exec-1','owner','task-1','run-1','agent','old private task',
                    'image/png','QUEUED',1,'idempotency-1',?,1,'0','client')
            ""","a".repeat(64));
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution "
                + " DROP COLUMN conversation_provider_started_at, DROP COLUMN conversation_provider_lease_version");
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution "
                + "WHERE execution_id='exec-1' AND conversation_provider_started_at IS NULL "
                + "AND conversation_provider_lease_version IS NULL",Integer.class));
        assertEquals(1,jdbc.update("UPDATE agent_personal_workspace_execution "
                + "SET conversation_provider_started_at=1,conversation_provider_lease_version=1 "
                + "WHERE execution_id='exec-1'"));
        assertThrows(IllegalStateException.class,initializer::verifyConversationProviderStart);
    }
    @Test void partialProviderStartColumnsCannotPassStartup() {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP COLUMN conversation_provider_started_at");
        assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);
    }
}
