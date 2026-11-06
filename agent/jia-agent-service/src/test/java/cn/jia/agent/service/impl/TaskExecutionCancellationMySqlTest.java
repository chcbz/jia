package cn.jia.agent.service.impl;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.TaskExecutionCancellationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real production DDL, MyBatis CAS and transactions. Caller root authorization is outside
 * this execution-layer slice; task+wallet concurrency is covered by coordinator tests. */
@EnabledIfEnvironmentVariable(named="ECO_V0_W02_MYSQL_URL", matches=".+")
class TaskExecutionCancellationMySqlTest {
    JdbcTemplate admin, jdbc;
    String database;
    TransactionTemplate tx;
    TaskExecutionCancellationService service;
    PersonalWorkspaceExecutionDao executions;
    SkillExecutionDao skills;
    ApplicationEventPublisher events;

    @BeforeEach void setup() throws Exception {
        assertEquals("true", System.getenv("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"));
        String base=System.getenv("ECO_V0_W02_MYSQL_URL"), prefix=System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        assertTrue(base.startsWith("jdbc:mysql://")); assertNotNull(prefix); assertTrue(prefix.matches("[a-z][a-z0-9_]{0,23}"));
        String user=System.getenv("ECO_V0_W02_MYSQL_USER"), password=System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(base,user,password));
        database=prefix+"_execcancel_"+Long.toUnsignedString(System.nanoTime(),36);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        int q=base.indexOf('?'); String address=q<0?base:base.substring(0,q);
        String url=address.substring(0,address.indexOf('/',"jdbc:mysql://".length())+1)+database+(q<0?"":base.substring(q));
        var source=new DriverManagerDataSource(url,user,password); jdbc=new JdbcTemplate(source);
        var schema=new ResourceDatabasePopulator();
        for(String name:List.of("agent-personal-workspace-v1_11-executions.sql", "agent-personal-workspace-v1_13-task-execution.sql",
                "agent-personal-workspace-v1_13-task-publication.sql", "agent-personal-workspace-v1_14-conversation-execution.sql",
                "agent-personal-workspace-v1_14-conversation-output.sql", "agent-personal-workspace-v1_15-conversation-lease.sql",
                "agent-personal-workspace-v1_16-conversation-provider-start.sql", "agent-skill-execution-v1.sql", "agent-personal-workspace-native-v5.sql"))
            schema.addScript(new ClassPathResource("db/"+name));
        schema.execute(source);
        new ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(PersonalWorkspaceExecutionMapper.class, PersonalWorkspaceExecutionInputMapper.class,
                PersonalWorkspaceExecutionOutputMapper.class, SkillExecutionMapper.class)) config.addMapper(mapper);
        var global=new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config); factory.setGlobalConfig(global);
        var sql=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        executions=spy(new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                sql.getMapper(PersonalWorkspaceExecutionInputMapper.class),sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class)));
        skills=new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class)); events=mock(ApplicationEventPublisher.class);
        var manager=new DataSourceTransactionManager(source); tx=new TransactionTemplate(manager);
        var proxy=new ProxyFactory(new TaskExecutionCancellationServiceImpl(executions,skills,events));
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        service=(TaskExecutionCancellationService)proxy.getProxy();
    }
    @AfterEach void cleanup() {
        if(database!=null&&admin!=null) {
            assertTrue(database.startsWith(System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX")+"_execcancel_"));
            admin.execute("DROP DATABASE `"+database+"`");
        }
    }
    void row(String id,String owner,String task,String mode,String state,Integer protocol) {
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution
            (execution_id,tenant_id,client_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,output_content_mime_type,
             execution_state,grant_revision,idempotency_key,request_hash,created_at,execution_mode,execution_protocol_version,
             conversation_lease_token,conversation_lease_version,conversation_lease_expires_at,conversation_provider_started_at,conversation_provider_lease_version)
            VALUES (?,'0','client',?,?,?,'agent','original instruction','text/plain',?,7,?,REPEAT('a',64),100,?,?, 'original-lease',3,9000,123,3)
            """,id,owner,task,"run-"+id,state,"key-"+id,mode,protocol==null?1:protocol);
    }
    void skill(String id,String phase) {
        skills.insertReady(new SkillExecutionEntity().setTenantId("0").setClientId("client").setOwnerJiacn("owner").setExecutionId(id)
                .setSnapshotJson("{}").setSnapshotSha256("a".repeat(64)).setEffectsJson("{\"external\":{\"status\":\"UNKNOWN\"}}")
                .setResultManifestJson("{\"preserved\":true}").setPhase(phase).setVersion(2L).setCreatedAt(100L).setUpdatedAt(100L));
        // Production insertion owns READY defaults; fixtures seed an already accepted phase explicitly.
        jdbc.update("UPDATE agent_skill_execution SET phase=?,version=2,effects_json='{\"external\":{\"status\":\"UNKNOWN\"}}',result_manifest_json='{\"preserved\":true}' WHERE execution_id=?",phase,id);
    }
    void cancel(){tx.executeWithoutResult(s->service.cancelTaskExecutions("0","client","owner","task"));}
    String state(String id){return jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution WHERE execution_id=?",String.class,id);}

    @Test void exactTaskCancellationPreservesTerminalsPrivateExecutionsAndHistoricalPaidFacts() {
        row("active","owner","task","CONVERSATION","QUEUED",5); skill("active","RESULT_UNKNOWN");
        row("staged","owner","task","TASK","OUTPUT_STAGED",1);
        for(String terminal:List.of("OUTPUT_COMMITTED","RESULT_COMMITTED","FAILED","INPUTS_REVOKED","CANCELLED"))
            row(terminal,"owner","task","CONVERSATION",terminal,1);
        row("private","owner","task","PRIVATE","QUEUED",1);row("foreign","other","task","CONVERSATION","QUEUED",1);
        row("other-task","owner","other","CONVERSATION","QUEUED",1);
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution_output
            (output_id,execution_id,owner_jiacn,original_filename,content_mime_type,byte_length,content_hash,storage_uri,output_state,staged_at,tenant_id,client_id)
            VALUES ('saved-output','active','owner','draft.txt','text/plain',12,REPEAT('b',64),'fixture://retained','STAGED',200,'0','client')
            """);
        var outputBefore=jdbc.queryForMap("SELECT * FROM agent_personal_workspace_execution_output WHERE output_id='saved-output'");
        var before=jdbc.queryForMap("SELECT conversation_lease_token,conversation_lease_version,conversation_lease_expires_at,conversation_provider_started_at,conversation_provider_lease_version,grant_revision,request_hash FROM agent_personal_workspace_execution WHERE execution_id='active'");
        cancel(); assertEquals(outputBefore,jdbc.queryForMap("SELECT * FROM agent_personal_workspace_execution_output WHERE output_id='saved-output'")); assertEquals("CANCELLED",state("active")); assertEquals("CANCELLED",state("staged"));
        assertEquals(before,jdbc.queryForMap("SELECT conversation_lease_token,conversation_lease_version,conversation_lease_expires_at,conversation_provider_started_at,conversation_provider_lease_version,grant_revision,request_hash FROM agent_personal_workspace_execution WHERE execution_id='active'"));
        var attachment=skills.find("0","client","owner","active");assertEquals("CANCELLED",attachment.getPhase());
        assertTrue(attachment.getEffectsJson().contains("UNKNOWN")); assertTrue(attachment.getResultManifestJson().contains("preserved"));assertEquals(3L,attachment.getVersion());
        for(String terminal:List.of("OUTPUT_COMMITTED","RESULT_COMMITTED","FAILED","INPUTS_REVOKED","CANCELLED"))assertEquals(terminal,state(terminal));
        for(String untouched:List.of("private","foreign","other-task"))assertEquals("QUEUED",state(untouched));
        cancel();assertEquals(3L,skills.find("0","client","owner","active").getVersion());
        verify(events,times(1)).publishEvent(any(WorkspaceExecutionProjectionHint.class));
    }
    @Test void allUncommittedSkillPhasesCancelWithoutClearingUnknownEffectsOrProposals() {
        for(String phase:List.of("READY","RUNNING","WAITING_PROVIDER","RESULT_UNKNOWN","RESULT_READY")) {
            row(phase,"owner","task","CONVERSATION","QUEUED",5);skill(phase,phase);
        }
        cancel();
        for(String phase:List.of("READY","RUNNING","WAITING_PROVIDER","RESULT_UNKNOWN","RESULT_READY")) {
            assertEquals("CANCELLED",state(phase));assertEquals("CANCELLED",skills.find("0","client","owner",phase).getPhase());
        }
    }
    @Test void outerTaskRollbackRestoresExecutionAndSkillAndEmitsNoHint() {
        row("active","owner","task","CONVERSATION","QUEUED",5);skill("active","RUNNING");
        assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(s->{
            service.cancelTaskExecutions("0","client","owner","task");throw new IllegalStateException("wallet release fixture failure");
        }));
        assertEquals("QUEUED",state("active"));assertEquals("RUNNING",skills.find("0","client","owner","active").getPhase());verifyNoInteractions(events);
    }
    @Test void executionCasFailureRollsBackPriorSkillMutation() {
        row("active","owner","task","CONVERSATION","QUEUED",5);skill("active","RUNNING");
        doReturn(false).when(executions).cancelLockedTaskExecution(any(),anyLong());
        assertThrows(IllegalStateException.class,this::cancel);
        assertEquals("RUNNING",skills.find("0","client","owner","active").getPhase());
        assertEquals("QUEUED",state("active"));verifyNoInteractions(events);
    }
    @Test void exactCaseOwnerClientAndTaskDoNotSelectOtherExecution() {
        row("active","owner","task","CONVERSATION","QUEUED",1);
        for(String[] scope:List.of(new String[]{"0","Client","owner","task"},new String[]{"0","client","Owner","task"},new String[]{"0","client","owner","Task"}))
            tx.executeWithoutResult(s->service.cancelTaskExecutions(scope[0],scope[1],scope[2],scope[3]));
        assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(s->service.cancelTaskExecutions("0","client","owner","task ")));
        assertEquals("QUEUED",state("active"));verifyNoInteractions(events);
    }
    @Test void concurrentCancellationCommitsOneStateChangeAndOneWakeup() throws Exception {
        row("active","owner","task","CONVERSATION","QUEUED",5);skill("active","RUNNING");
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(2);
            java.util.concurrent.Callable<Void> operation=()->{start.countDown();assertTrue(start.await(10,java.util.concurrent.TimeUnit.SECONDS));cancel();return null;};
            var a=pool.submit(operation);var b=pool.submit(operation);
            a.get(20,java.util.concurrent.TimeUnit.SECONDS);b.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals("CANCELLED",state("active"));assertEquals(3L,skills.find("0","client","owner","active").getVersion());
        verify(events,times(1)).publishEvent(any(WorkspaceExecutionProjectionHint.class));
    }
    @Test void requiresOuterTransactionAndWakeupFailureDoesNotUndoCommittedCancellation() {
        row("active","owner","task","CONVERSATION","QUEUED",1);
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                ()->service.cancelTaskExecutions("0","client","owner","task"));
        doThrow(new IllegalStateException("local receiver unavailable")).when(events).publishEvent(any(WorkspaceExecutionProjectionHint.class));
        cancel();assertEquals("CANCELLED",state("active"));
    }
}
