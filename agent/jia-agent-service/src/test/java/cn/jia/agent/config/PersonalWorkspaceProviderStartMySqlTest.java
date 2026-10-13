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
    }
    @AfterEach void close() {
        if(admin!=null && database!=null) admin.execute("DROP DATABASE IF EXISTS "+database);
    }
    @Test void textMimeUpgradePreservesImagesAndEnforcesRealConversationInsertsAndRollback() throws Exception {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        // Exact previously installed six-MIME constraint, not a disabled CHECK.
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP CHECK chk_pwex_output_mime, "
                + "ADD CONSTRAINT chk_pwex_output_mime CHECK (output_content_mime_type IN ("
                + PersonalWorkspaceExecutionSchemaInitializer.DOCUMENT_MIMES+"))");
        insertConversation("image", "image/png");
        assertThrows(Exception.class,()->insertConversation("text", "text/plain"));
        initializer.afterPropertiesSet();
        insertConversation("text", "text/plain");
        initializer.afterPropertiesSet();
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution",Integer.class));
        assertThrows(Exception.class,()->insertConversation("bad", "text/html"));
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
    @Test void freshSchemaAcceptsTextAndMissingMimeCheckFailsClosed() {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        insertConversation("fresh", "text/plain");
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP CHECK chk_pwex_output_mime");
        assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);
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
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP CHECK chk_pwex_provider_start,"
                + " DROP COLUMN conversation_provider_started_at, DROP COLUMN conversation_provider_lease_version");
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution "
                + "WHERE execution_id='exec-1' AND conversation_provider_started_at IS NULL "
                + "AND conversation_provider_lease_version IS NULL",Integer.class));
        assertThrows(Exception.class,()->jdbc.update("UPDATE agent_personal_workspace_execution "
                + "SET conversation_provider_started_at=1,conversation_provider_lease_version=1 "
                + "WHERE execution_id='exec-1'"));
    }
    @Test void oneColumnPresentOrDisabledCheckCannotPassStartup() {
        var initializer=new PersonalWorkspaceExecutionSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP CHECK chk_pwex_provider_start");
        assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution DROP COLUMN conversation_provider_started_at");
        assertThrows(IllegalStateException.class,initializer::afterPropertiesSet);
    }
}
