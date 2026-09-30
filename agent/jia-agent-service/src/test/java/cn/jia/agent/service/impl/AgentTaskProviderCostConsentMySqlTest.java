package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer;
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

/** Isolated MySQL 8 fixture; creates and drops only its acknowledged task-prefixed database. */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class AgentTaskProviderCostConsentMySqlTest {
    private JdbcTemplate admin,jdbc;private DriverManagerDataSource source;private String database,namespace;
    @BeforeEach void setUp(){String url=required("MMD_U1_REFERENCE_MYSQL_URL"),user=required("MMD_U1_REFERENCE_MYSQL_USER");
        String password=System.getenv("MMD_U1_REFERENCE_MYSQL_PASSWORD"),prefix=required("MMD_U1_REFERENCE_MYSQL_DATABASE_PREFIX");
        if(password==null||!url.startsWith("jdbc:mysql://")||!prefix.matches("[A-Za-z0-9_]{1,20}")
                ||!"true".equals(required("MMD_U1_REFERENCE_MYSQL_ISOLATED_FIXTURE"))) throw new IllegalStateException("isolated MySQL acknowledgement required");
        namespace=prefix+"_provider_";database=namespace+UUID.randomUUID().toString().substring(0,8);
        admin=new JdbcTemplate(ds(url,user,password));assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source=ds(databaseUrl(url,database),user,password);jdbc=new JdbcTemplate(source);
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();}
    @AfterEach void tearDown(){if(admin==null||database==null)return;if(!database.startsWith(namespace))throw new IllegalStateException("unowned database");admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    @Test void ddlReentryBinaryKeysAndProviderLifecycleChecksAreEnforced(){String before=jdbc.queryForObject("SHOW CREATE TABLE agent_task_provider_cost_consent",(rs,row)->rs.getString(2));
        new AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(before,jdbc.queryForObject("SHOW CREATE TABLE agent_task_provider_cost_consent",(rs,row)->rs.getString(2)));
        insertIssued("Key","consent-a","owner-a",7,1);
        assertThrows(DataAccessException.class,()->insertIssued("Key","consent-b","owner-a",7,1));
        insertIssued("key","consent-c","owner-a",7,1);insertIssued("Key","consent-d","Owner-a",7,1);
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_provider_cost_consent",Integer.class));
        assertThrows(DataAccessException.class,()->insertIssued("bad-attempts","consent-e","owner-a",7,2));
        assertThrows(DataAccessException.class,()->insertIssued("bad-epoch","consent-f","owner-a",0,1));
        assertThrows(DataAccessException.class,()->jdbc.update("UPDATE agent_task_provider_cost_consent SET state='CONSUMED' WHERE consent_id='consent-a'"));}

    @Test void concurrentCasAllowsExactlyOneIssuedToBoundTransition()throws Exception{insertIssued("race","consent-race","owner-a",7,1);
        TransactionTemplate first=new TransactionTemplate(new DataSourceTransactionManager(source));
        TransactionTemplate second=new TransactionTemplate(new DataSourceTransactionManager(source));CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)){var a=executor.submit(()->first.execute(status->{await(start);return bindCas();}));
            var b=executor.submit(()->second.execute(status->{await(start);return bindCas();}));start.countDown();
            int changed=a.get(10,TimeUnit.SECONDS)+b.get(10,TimeUnit.SECONDS);assertEquals(1,changed);
            assertEquals("BOUND:2",jdbc.queryForObject("SELECT CONCAT(state,':',version) FROM agent_task_provider_cost_consent WHERE consent_id='consent-race'",String.class));}
    }
    private int bindCas(){return jdbc.update("""
            UPDATE agent_task_provider_cost_consent
               SET state='BOUND',version=version+1,bound_grant_id='grant-a',bound_grant_version=1,
                   bound_assignment_revision=6,update_time=2
             WHERE consent_id='consent-race' AND state='ISSUED' AND version=1
            """);}
    private void insertIssued(String key,String consent,String owner,long epoch,int attempts){jdbc.update("""
            INSERT INTO agent_task_provider_cost_consent
              (consent_id,owner_jiacn,task_id,target_agent_id,idempotency_key,request_digest,
               assignment_idempotency_key,assignment_base_hash,task_version,requirement_revision,
               requirement_sha256,input_snapshot_digest,input_snapshot_json,provider_lane,binding_id,
               binding_epoch,model_id,custody,operator_issuer,operator_policy_revision,pricing_mode,
               max_outbound_request_attempts,expires_at,state,version,bound_grant_id,bound_grant_version,
               bound_assignment_revision,reserved_execution_id,reserved_run_id,consumed_lease_id,consumed_at,
               revoke_idempotency_key,revoke_request_digest,revoked_at,created_at,tenant_id,client_id,create_time,update_time)
            VALUES (?,?,'task-a','agent-a',?,?,'assign-key',?,5,2,?,?,JSON_ARRAY(),
                    'CONTROLLED_IMAGE_HTTP_V1','binding-a',?,'model-a','OWNER_EXTERNAL_ACCOUNT',
                    'operator-a','policy-r1','UNPRICED_EXTERNAL_ACCOUNT',?,2000000000000,
                    'ISSUED',1,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,1,'0','client-a',1,1)
            """,consent,owner,key,"a".repeat(64),"b".repeat(64),"c".repeat(64),"d".repeat(64),epoch,attempts);}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private static DriverManagerDataSource ds(String url,String user,String password){DriverManagerDataSource source=new DriverManagerDataSource();source.setDriverClassName("com.mysql.cj.jdbc.Driver");source.setUrl(url);source.setUsername(user);source.setPassword(password);return source;}
    private static String databaseUrl(String base,String db){int q=base.indexOf('?');String suffix=q<0?"":base.substring(q),plain=q<0?base:base.substring(0,q);return plain.substring(0,plain.lastIndexOf('/')+1)+db+suffix;}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" required");return value;}
}
