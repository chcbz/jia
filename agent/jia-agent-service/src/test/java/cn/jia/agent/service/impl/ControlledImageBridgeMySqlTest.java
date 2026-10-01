package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
import cn.jia.agent.config.ControlledImageBridgeSchemaInitializer;
import cn.jia.agent.config.ControlledImageExecutionSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MySQL 8 DDL, uniqueness and concurrent reserve/consume evidence; no Provider is called. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class ControlledImageBridgeMySqlTest {
    private JdbcTemplate admin,jdbc;
    private DriverManagerDataSource source;
    private String database,namespace;

    @BeforeEach void setUp() {
        String url=required("MMD_U1_REFERENCE_MYSQL_URL");
        String user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD");
        String prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE")))
            throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_ci_bridge_";
        database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source=ds(databaseUrl(url,database),user,password);jdbc=new JdbcTemplate(source);
        createExecutionBase();
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet();
        new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
    }

    @AfterEach void tearDown() {
        if(admin==null||database==null)return;
        if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");
        admin.execute("DROP DATABASE IF EXISTS `"+database+"`");
    }

    @Test void actualMySqlNormalizesExactChecksAndRejectsSameNamedWeakDefinitions() {
        assertDoesNotThrow(()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation "
                +"DROP CHECK chk_acibo_locator, ADD CONSTRAINT chk_acibo_locator CHECK (1=1)");
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageBridgeSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE agent_controlled_image_bridge_operation "
                +"DROP CHECK chk_acibo_locator, ADD CONSTRAINT chk_acibo_locator "
                +"CHECK (authority_locator=CONCAT('mmd-ci-v1:',consent_id))");
        jdbc.execute("ALTER TABLE agent_personal_workspace_execution "
                +"DROP CHECK chk_pwex_controlled_consent, "
                +"ADD CONSTRAINT chk_pwex_controlled_consent CHECK (1=1)");
        assertThrows(IllegalStateException.class,
                ()->new ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
    }

    @Test void twoExecutionRunsCanReserveOnlyOneConsent() throws Exception {
        insertConsent("consent_1234567890abcdef1234567890abcdef","BOUND",2,null,null);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->reserveAttempt(start,"execution-a","run-a"));
            var second=executor.submit(()->reserveAttempt(start,"execution-b","run-b"));
            start.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_execution WHERE controlled_consent_id=?",
                Integer.class,"consent_1234567890abcdef1234567890abcdef"));
        assertEquals("RESERVED:3",jdbc.queryForObject(
                "SELECT CONCAT(state,':',version) FROM agent_task_provider_cost_consent WHERE consent_id=?",
                String.class,"consent_1234567890abcdef1234567890abcdef"));
        assertThrows(DataAccessException.class,()->insertExecution("execution-c","run-c",
                "consent_1234567890abcdef1234567890abcdef"));
    }

    @Test void concurrentProviderStartCommitsExactlyOneConsumeAndMarker() throws Exception {
        String consent="consent_abcdefabcdefabcdefabcdefabcdefab";
        insertConsent(consent,"RESERVED",3,"execution-a","run-a");
        insertExecution("execution-a","run-a",consent);
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->startAttempt(start,consent));
            var second=executor.submit(()->startAttempt(start,consent));
            start.countDown();
            assertEquals(1,first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS));
        }
        assertEquals("CONSUMED:4",jdbc.queryForObject(
                "SELECT CONCAT(state,':',version) FROM agent_task_provider_cost_consent WHERE consent_id=?",
                String.class,consent));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_execution "
                        +"WHERE controlled_consent_id=? AND conversation_provider_started_at IS NOT NULL "
                        +"AND conversation_provider_lease_version=1",Integer.class,consent));
        assertEquals(0,startAttempt(new CountDownLatch(0),consent));
    }

    private int reserveAttempt(CountDownLatch start,String execution,String run) {
        await(start);
        try {
            return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
                insertExecution(execution,run,"consent_1234567890abcdef1234567890abcdef");
                int changed=jdbc.update("""
                        UPDATE agent_task_provider_cost_consent
                           SET state='RESERVED',version=version+1,reserved_execution_id=?,reserved_run_id=?,update_time=2
                         WHERE consent_id=? AND state='BOUND' AND version=2
                        """,execution,run,"consent_1234567890abcdef1234567890abcdef");
                if(changed!=1)throw new IllegalStateException("reserve lost after execution insert");
                return 1;
            });
        } catch (DataAccessException|IllegalStateException lost) { return 0; }
    }

    private int startAttempt(CountDownLatch start,String consent) {
        await(start);
        return new TransactionTemplate(new DataSourceTransactionManager(source)).execute(status->{
            int consumed=jdbc.update("""
                    UPDATE agent_task_provider_cost_consent
                       SET state='CONSUMED',version=version+1,consumed_lease_id='pwe_lease_fixture',
                           consumed_at=2,update_time=2
                     WHERE consent_id=? AND state='RESERVED' AND version=3
                    """,consent);
            if(consumed==0)return 0;
            int marker=jdbc.update("""
                    UPDATE agent_personal_workspace_execution
                       SET conversation_provider_started_at=2,conversation_provider_lease_version=1,
                           update_time=2
                     WHERE tenant_id='0' AND client_id='client' AND owner_jiacn='owner'
                       AND task_id='task' AND run_id='run-a' AND execution_id='execution-a'
                       AND controlled_consent_id=? AND execution_mode='CONVERSATION'
                       AND execution_state='QUEUED' AND permitted_operation='GENERATE_IMAGE'
                       AND output_content_mime_type='image/png' AND conversation_lease_version=1
                       AND conversation_provider_started_at IS NULL
                       AND conversation_provider_lease_version IS NULL
                       AND CAST(controlled_consent_id AS BINARY)=CAST(? AS BINARY)
                       AND OCTET_LENGTH(controlled_consent_id)=OCTET_LENGTH(?)
                    """,consent,consent,consent);
            if(marker!=1)throw new IllegalStateException("provider marker CAS failed");
            return 1;
        });
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

    private void insertExecution(String execution,String run,String consent) {
        jdbc.update("""
                INSERT INTO agent_personal_workspace_execution
                  (execution_id,owner_jiacn,task_id,run_id,execution_mode,execution_state,
                   permitted_operation,output_content_mime_type,conversation_lease_version,
                   conversation_provider_started_at,conversation_provider_lease_version,
                   controlled_consent_id,tenant_id,client_id,update_time)
                VALUES (?,'owner','task',?,'CONVERSATION','QUEUED','GENERATE_IMAGE','image/png',1,
                        NULL,NULL,?,'0','client',1)
                """,execution,run,consent);
    }

    private void insertConsent(String consent,String state,long version,String execution,String run) {
        boolean reserved=execution!=null;
        jdbc.update("""
                INSERT INTO agent_task_provider_cost_consent
                  (consent_id,owner_jiacn,task_id,target_agent_id,idempotency_key,request_digest,
                   assignment_idempotency_key,assignment_base_hash,task_version,requirement_revision,
                   requirement_sha256,input_snapshot_digest,input_snapshot_json,provider_lane,binding_id,
                   binding_epoch,model_id,custody,operator_issuer,operator_policy_revision,pricing_mode,
                   max_outbound_request_attempts,expires_at,state,version,bound_grant_id,bound_grant_version,
                   bound_assignment_revision,reserved_execution_id,reserved_run_id,consumed_lease_id,consumed_at,
                   revoke_idempotency_key,revoke_request_digest,revoked_at,created_at,tenant_id,client_id,
                   create_time,update_time)
                VALUES (?,'owner','task','agent',?,?,'assignment-key',?,6,3,?,?,JSON_ARRAY(),
                        'CONTROLLED_IMAGE_HTTP_V1','binding',1,'model','OPERATOR_TEMPLATE','operator',
                        'policy-r1','UNPRICED_EXTERNAL_ACCOUNT',1,9000000000000,?,?,
                        'grant',1,7,?,?,NULL,NULL,NULL,NULL,NULL,1,'0','client',1,1)
                """,consent,"issue-"+consent.substring(8,16),"a".repeat(64),"b".repeat(64),
                "c".repeat(64),"d".repeat(64),state,version,
                reserved?execution:null,reserved?run:null);
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10,TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) {
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
