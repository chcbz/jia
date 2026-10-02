package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
import cn.jia.agent.config.ControlledImageExecutionSchemaInitializer;
import cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer;
import cn.jia.agent.mapper.AgentTaskProviderCostConsentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL evidence for pristine/default-off compatibility, restart and authority uniqueness. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class ControlledImageIntentOperationGrantMySqlTest {
    private JdbcTemplate admin,jdbc;
    private String database,namespace;

    @BeforeEach void setUp() {
        String url=required("MMD_U1_REFERENCE_MYSQL_URL");
        String user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")))
            throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_ci_followup_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,database));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        jdbc=new JdbcTemplate(ds(databaseUrl(url,database),user,password));
        createExecutionBase();
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
        assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,database));
    }

    @Test void pristineDefaultOffProjectionAndCompleteV3RestartAreBothAccepted() {
        assertFalse(columnExists("agent_task_provider_cost_consent","consent_purpose"));
        assertDoesNotThrow(()->jdbc.queryForList("SELECT "+AgentTaskProviderCostConsentMapper.COLUMNS
                +" FROM agent_task_provider_cost_consent LIMIT 0"));

        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        assertTrue(columnExists("agent_task_provider_cost_consent","consent_purpose"));
        assertTrue(columnExists("agent_personal_workspace_execution","execution_protocol_version"));
        assertDoesNotThrow(()->jdbc.queryForList("SELECT "+AgentTaskProviderCostConsentMapper.COLUMNS
                +" FROM agent_task_provider_cost_consent LIMIT 0"));
        assertDoesNotThrow(()->jdbc.queryForList("SELECT "+AgentTaskProviderCostConsentMapper.FOLLOWUP_COLUMNS
                +" FROM agent_task_provider_cost_consent LIMIT 0"));

        // Real restart order can visit either legacy initializer before the v3 bean.
        assertDoesNotThrow(()->new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test void completeV3CatalogRejectsSameNamedWeakUnionAndPartialMigration() {
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("ALTER TABLE agent_task_provider_cost_consent DROP CHECK chk_atpcc_purpose_union, "
                +"ADD CONSTRAINT chk_atpcc_purpose_union CHECK (1=1)");
        assertThrows(IllegalStateException.class,
                ()->new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet());
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test void concurrentIssueForOneExecutionIntentHasExactlyOneDurableWinner() throws Exception {
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->insertAuthority(start,"a"));
            var second=executor.submit(()->insertAuthority(start,"b"));
            start.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM "
                +"agent_controlled_image_intent_operation_grant WHERE execution_intent_id='intent-shared'",
                Integer.class));
        assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE agent_controlled_image_intent_operation_grant SET operation_grant_id=? "
                        +"WHERE execution_intent_id='intent-shared'",
                "opgrant_A"+"a".repeat(31)));
        assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE agent_controlled_image_intent_operation_grant SET requirement_sha256=? "
                        +"WHERE execution_intent_id='intent-shared'",
                "A"+"a".repeat(63)));
    }

    private int insertAuthority(CountDownLatch start,String suffix) {
        await(start);
        try {
            return jdbc.update("""
                    INSERT INTO agent_controlled_image_intent_operation_grant
                      (operation_grant_id,owner_jiacn,task_id,target_agent_id,conversation_id,
                       conversation_generation,interaction_idempotency_key,request_id,step_id,
                       execution_intent_id,baseline_grant_id,baseline_grant_version,task_version,
                       assignment_revision,requirement_revision,requirement_sha256,operation,
                       instruction_sha256,source_snapshot_sha256,source_snapshot_json,
                       owner_payload_sha256,consent_id,issue_idempotency_key,issue_request_digest,
                       state,version,reserved_execution_id,reserved_run_id,consumed_lease_id,
                       revoke_idempotency_key,revoke_request_digest,revoked_at,created_at,
                       tenant_id,client_id,create_time,update_time)
                    VALUES (?,?, 'task','agent','conversation',1,?,?,'step','intent-shared','baseline',
                            1,0,0,1,?,'GENERATE_IMAGE',?,?,JSON_ARRAY(),?,?,?,?,'AUTHORIZED',1,
                            NULL,NULL,NULL,NULL,NULL,NULL,1,'0','client',1,1)
                    ""","opgrant_"+hex(suffix),"owner","interaction-"+suffix,"request-"+suffix,
                    "1".repeat(64),"2".repeat(64),"3".repeat(64),"4".repeat(64),
                    "consent_"+hex(suffix),"issue-"+suffix,"5".repeat(64));
        } catch(DataAccessException duplicate) { return 0; }
    }

    private static String hex(String seed) {
        return (seed.charAt(0)=='a'?"a":"b").repeat(32);
    }
    private boolean columnExists(String table,String column) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns "
                +"WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",
                Integer.class,table,column)==1;
    }
    private void createExecutionBase() {
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  execution_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  owner_jiacn VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  task_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  run_id VARCHAR(100) COLLATE utf8mb4_0900_bin NOT NULL,
                  execution_mode VARCHAR(20) COLLATE utf8mb4_0900_bin NOT NULL,
                  execution_state VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,
                  permitted_operation VARCHAR(40) COLLATE utf8mb4_0900_bin DEFAULT NULL,
                  output_content_mime_type VARCHAR(127) COLLATE utf8mb4_0900_bin NOT NULL,
                  conversation_lease_version BIGINT DEFAULT NULL,
                  conversation_provider_started_at BIGINT DEFAULT NULL,
                  conversation_provider_lease_version BIGINT DEFAULT NULL,
                  tenant_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  client_id VARCHAR(50) COLLATE utf8mb4_0900_bin NOT NULL,
                  update_time BIGINT DEFAULT NULL,
                  PRIMARY KEY(id),
                  UNIQUE KEY uk_pwex_fixture_execution(tenant_id,client_id,owner_jiacn,execution_id),
                  UNIQUE KEY uk_pwex_fixture_run(tenant_id,client_id,owner_jiacn,task_id,run_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
        catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);
        }
    }
    private static DriverManagerDataSource ds(String url,String user,String password) {
        DriverManagerDataSource value=new DriverManagerDataSource();
        value.setDriverClassName("com.mysql.cj.jdbc.Driver");value.setUrl(url);
        value.setUsername(user);value.setPassword(password);return value;
    }
    private static String databaseUrl(String base,String database) {
        int query=base.indexOf('?');String suffix=query<0?"":base.substring(query);
        String plain=query<0?base:base.substring(0,query);
        return plain.substring(0,plain.lastIndexOf('/')+1)+database+suffix;
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null||value.isBlank())throw new IllegalStateException(name+" required");
        return value;
    }
}
