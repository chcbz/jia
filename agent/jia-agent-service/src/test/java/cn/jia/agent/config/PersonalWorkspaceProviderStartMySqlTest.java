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
