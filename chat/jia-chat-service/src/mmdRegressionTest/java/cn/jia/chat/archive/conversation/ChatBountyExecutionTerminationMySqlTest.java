package cn.jia.chat.archive.conversation;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.config.PersonalWorkspaceExecutionSchemaInitializer;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.FileSystemPersonalWorkspaceStorage;
import cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.service.*;
import cn.jia.chat.service.ChatBountyExecutionTerminationService.*;
import cn.jia.chat.service.impl.WorkspaceConversationAccessServiceImpl;
import cn.jia.core.util.JsonUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production execution/chat DDL, event+execution MyBatis and real InnoDB locks/rollback.
 * Task-root and conversation DAO fixture adapters use scoped locking SQL, not mocked row states.
 * No Provider, production DB, or business task is used.
 */
@EnabledIfEnvironmentVariable(named="MMD_U1_REFERENCE_MYSQL_URL",matches=".+")
class ChatBountyExecutionTerminationMySqlTest {
    private static final Scope OWNER=new Scope("0","owner","client","42");
    private static final String KEY="abandon-test-001";
    private static final Command COMMAND=new Command("step","exec","0","2",ChatBountyExecutionTerminationService.REASON);
    @TempDir Path storage;
    private JdbcTemplate admin,jdbc;
    private String database;
    private TransactionTemplate tx;
    private ChatBountyExecutionTerminationService service;
    private PersonalWorkspaceExecutionServiceImpl runtime;
    private PersonalWorkspaceExecutionDao executions;
    private ChatDeliberationDao events;
    private ChatConversationEventBroker broker;
    private AgentTaskMutationTransaction roots;
    private ChatConversationDao conversations;
    private volatile CountDownLatch beforeRoot;
    private final AtomicBoolean failEvent=new AtomicBoolean();

    @BeforeEach void setup() throws Exception {
        var target=ChatConversationArchiveMySqlTestGuard.requireDisposable(System.getenv());
        admin=new JdbcTemplate(ds(target.adminUrl(),target.user(),target.password()));
        assertTrue(admin.queryForObject("SELECT VERSION()",String.class).startsWith("8."));
        database=target.databasePrefix()+"_termination_"+UUID.randomUUID().toString().substring(0,8);
        admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        int query=target.adminUrl().indexOf('?');
        String base=query<0?target.adminUrl():target.adminUrl().substring(0,query);
        String url=base.substring(0,base.lastIndexOf('/')+1)+database+(query<0?"":target.adminUrl().substring(query));
        var source=ds(url,target.user(),target.password());jdbc=new JdbcTemplate(source);
        tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/chat-deliberation-schema.sql")).execute(source);
        jdbc.execute("""
                CREATE TABLE fixture_task_root (tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),
                  task_id VARCHAR(100),assigned_agent_id VARCHAR(100),task_version BIGINT,
                  PRIMARY KEY(tenant_id,client_id,owner_jiacn,task_id)) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE fixture_conversation (id BIGINT PRIMARY KEY,tenant_id VARCHAR(50),client_id VARCHAR(50),
                  owner_jiacn VARCHAR(50),generation BIGINT,deleted_at BIGINT NULL,task_id VARCHAR(100),
                  scope_type VARCHAR(20),scope_key VARCHAR(120),targets VARCHAR(2000),conversation_type VARCHAR(50)) ENGINE=InnoDB
                """);
        jdbc.update("INSERT INTO fixture_task_root VALUES('0','client','owner','task','agent',1)");
        jdbc.update("INSERT INTO fixture_conversation VALUES(42,'0','client','owner',1,NULL,'task','bounty','task:task','[\"agent\"]','juyiting')");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
        config.addMapper(ChatDeliberationMapper.class);config.addMapper(PersonalWorkspaceExecutionMapper.class);
        config.addMapper(PersonalWorkspaceExecutionInputMapper.class);config.addMapper(PersonalWorkspaceExecutionOutputMapper.class);
        var global=new GlobalConfig();global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory=new MybatisSqlSessionFactoryBean();factory.setConfiguration(config);factory.setDataSource(source);factory.setGlobalConfig(global);
        var session=new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        events=spy(new ChatDeliberationDaoImpl(session.getMapper(ChatDeliberationMapper.class)));
        doAnswer(i->{if(failEvent.get())throw new IllegalStateException("injected journal failure");return i.callRealMethod();})
                .when(events).assignEventVersion(anyLong());
        executions=new PersonalWorkspaceExecutionDaoImpl(session.getMapper(PersonalWorkspaceExecutionMapper.class),
                session.getMapper(PersonalWorkspaceExecutionInputMapper.class),session.getMapper(PersonalWorkspaceExecutionOutputMapper.class));
        roots=mock(AgentTaskMutationTransaction.class);
        when(roots.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any()))
                .thenAnswer(i->{
                    CountDownLatch barrier=beforeRoot;
                    if(barrier!=null){barrier.countDown();assertTrue(barrier.await(20,TimeUnit.SECONDS),"test barrier");}
                    var found=jdbc.query("""
                            SELECT * FROM fixture_task_root WHERE BINARY tenant_id=BINARY ? AND BINARY client_id=BINARY ?
                            AND BINARY owner_jiacn=BINARY ? AND BINARY task_id=BINARY ? FOR UPDATE
                            """,(r,n)->{
                        var root=new AgentTaskMetaEntity().setTaskId(r.getString("task_id"))
                                .setAssignedAgentId(r.getString("assigned_agent_id")).setTaskVersion(r.getLong("task_version"));
                        root.setTenantId(r.getString("tenant_id"));root.setClientId(r.getString("client_id"));
                        root.setOwnerJiacn(r.getString("owner_jiacn"));return root;
                    },i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
                    AgentTaskMutationTransaction.LockedTaskMutation<?> callback=i.getArgument(4);
                    return callback.apply(found.isEmpty()?null:found.getFirst());
                });
        conversations=mock(ChatConversationDao.class);
        when(conversations.lockScopedById(anyString(),anyString(),anyString()))
                .thenAnswer(i->conversation(i.getArgument(0),i.getArgument(1),i.getArgument(2),true));
        when(conversations.findScopedById(anyString(),anyString(),anyString()))
                .thenAnswer(i->conversation(i.getArgument(0),i.getArgument(1),i.getArgument(2),false));
        var access=new WorkspaceConversationAccessServiceImpl(conversations,mock(AgentTaskThreadDao.class));
        broker=mock(ChatConversationEventBroker.class);
        service=new ChatBountyExecutionTerminationService(jdbc,roots,conversations,events,broker,access,JsonUtil.getMapper());
        configureRuntime(access);
        seed();
    }

    @AfterEach void cleanup() {
        beforeRoot=null;
        if(database!=null&&admin!=null){
            assertTrue(database.startsWith("mmd_ac12main_termination_"));
            admin.execute("DROP DATABASE `"+database+"`");
        }
    }

    @Test void atomicTerminalReceiptPreservesStartLeaseAndUnrelatedAuthorityFieldsAndRecoversLostAck() {
        var before=paidFields();
        Receipt receipt=abandon();
        assertEquals("CANCELLED",receipt.state());assertEquals("1",receipt.requestStateVersion());
        assertEquals("3",receipt.stepStateVersion());assertTrue(receipt.providerAlreadyStarted());
        assertFalse(receipt.providerStopped());assertTrue(receipt.paidFactsPreserved());
        assertEquals(before,paidFields());assertEquals("FAILED",executionState());
        assertEquals("AGENT_DELIVERY_FAILED",
                jdbc.queryForObject("SELECT failure_code FROM agent_personal_workspace_execution",String.class));
        assertEquals(receipt,inTx(()->service.get(OWNER,"req",KEY)));
        assertEquals(receipt,abandon());assertEquals(1,countEvents());
        assertEquals(1L,jdbc.queryForObject("SELECT event_version FROM chat_conversation_event",Long.class));
        verify(broker,times(1)).publishIfSubscribed(eq("42"),eq(1L),any(),any());
    }

    @Test void concurrentSameKeyWaitsAndReturnsSingleReceiptNotFalseConflict() throws Exception {
        beforeRoot=new CountDownLatch(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(this::abandon);var second=pool.submit(this::abandon);
            assertEquals(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        }finally{beforeRoot=null;}
        assertEquals(1,countEvents());assertEquals("CANCELLED",requestState());
    }

    @Test void sameKeyWithDifferentPayloadConflictsWithoutSecondMutation() {
        abandon();
        fail(Reason.CONFLICT,()->inTx(()->service.abandon(OWNER,"req",KEY,
                new Command("step","exec","1","3",ChatBountyExecutionTerminationService.REASON))));
        assertEquals(1,countEvents());
    }

    @Test void unknownGetDoesNotExecuteOrCreateReceipt() {
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.get(OWNER,"req",KEY)));
        assertPristine();verifyNoInteractions(broker);
    }

    @Test void ownerClientTenantConversationAndCaseAreExactForWritesAndReceiptReads() {
        for(Scope foreign:List.of(new Scope("0","Owner","client","42"),new Scope("0","owner","Client","42"),
                new Scope("1","owner","client","42"),new Scope("0","owner","client","43"))) {
            fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.abandon(foreign,"req",KEY,COMMAND)));
        }
        assertPristine();abandon();
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.get(new Scope("0","Owner","client","42"),"req",KEY)));
        assertEquals(1,countEvents());
    }

    @Test void staleVersionsAndOverflowFailBeforeAnyWrite() {
        fail(Reason.CONFLICT,()->inTx(()->service.abandon(OWNER,"req",KEY,
                new Command("step","exec","1","2",ChatBountyExecutionTerminationService.REASON))));
        fail(Reason.CONFLICT,()->inTx(()->service.abandon(OWNER,"req",KEY,
                new Command("step","exec","0","3",ChatBountyExecutionTerminationService.REASON))));
        jdbc.update("UPDATE chat_step_execution_link SET state_version=?",Long.MAX_VALUE);
        fail(Reason.CONFLICT,this::abandon);assertPristine();
    }

    @Test void currentGenerationDeletionTargetAclAndAssignmentRecheckedEvenForReplay() {
        for(String change:List.of("generation=2","deleted_at=1","targets='[\"foreign\"]'",
                "conversation_type='internal'","scope_type='private'")) {
            jdbc.update("UPDATE fixture_conversation SET "+change);
            fail(Reason.NOT_FOUND_OR_FORBIDDEN,this::abandon);
            jdbc.update("UPDATE fixture_conversation SET generation=1,deleted_at=NULL,targets='[\"agent\"]',conversation_type='juyiting',scope_type='bounty'");
        }
        jdbc.update("UPDATE fixture_task_root SET assigned_agent_id='other'");
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,this::abandon);
        jdbc.update("UPDATE fixture_task_root SET assigned_agent_id='agent'");
        abandon();jdbc.update("UPDATE fixture_conversation SET targets='[\"foreign\"]'");
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,this::abandon);
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.get(OWNER,"req",KEY)));
    }

    @Test void preStartAndIncorrectIntentOrExecutionBindingAreRejected() {
        jdbc.update("UPDATE agent_personal_workspace_execution SET conversation_provider_started_at=NULL,conversation_provider_lease_version=NULL");
        fail(Reason.CONFLICT,this::abandon);
        jdbc.update("UPDATE agent_personal_workspace_execution SET conversation_provider_started_at=10,conversation_provider_lease_version=1,idempotency_key='other-intent'");
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,this::abandon);
        assertPristine();
    }

    @Test void actualStagedBytesBlockAbandonmentEvenBeforeExecutionProjectionChanges() throws Exception {
        stage();assertEquals("QUEUED",executionState());
        // Conversation stage stores bytes without changing execution_state: guard the actual output.
        assertEquals("STAGED",jdbc.queryForObject("SELECT output_state FROM agent_personal_workspace_execution_output",String.class));
        fail(Reason.CONFLICT,this::abandon);assertEquals("RUNNING",requestState());assertEquals(0,countEvents());
    }

    @Test void committedResultAndItsLostAckCannotBeAbandoned() throws Exception {
        byte[] bytes=png();stage();String hash=sha(bytes);
        var result=inTx(()->runtime.commitConversationOutput(runtimeScope(),"task","run",fence(),
                "pwe_m_"+sha("task\nrun\noutput_1\n"+hash+"\n"+bytes.length+"\n"),
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,bytes.length))));
        assertEquals("COMMITTED",result.state());fail(Reason.CONFLICT,this::abandon);
        assertEquals("OUTPUT_COMMITTED",executionState());assertEquals(0,countEvents());
    }

    @Test void terminalAbandonmentRejectsRealRuntimeLateStageWithOriginalFence() throws Exception {
        abandon();byte[] bytes=png();
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->inTx(()->runtime.stageConversationOutput(
                runtimeScope(),"task","run",fence(),"output_1","bird.png","image/png",bytes)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution_output",Integer.class));
        assertEquals("FAILED",executionState());assertEquals(1,countEvents());
    }

    @Test void realStageAndAbandonRaceSerializeAtSameRootWithoutLostResult() throws Exception {
        byte[] bytes=png();beforeRoot=new CountDownLatch(2);
        Object stageResult,abandonResult;
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->outcome(()->inTx(()->runtime.stageConversationOutput(runtimeScope(),"task","run",
                    fence(),"output_1","bird.png","image/png",bytes))));
            var second=pool.submit(()->outcome(this::abandon));
            stageResult=first.get(30,TimeUnit.SECONDS);abandonResult=second.get(30,TimeUnit.SECONDS);
        }finally{beforeRoot=null;}
        if(abandonResult instanceof Receipt) {
            assertInstanceOf(PersonalWorkspaceExecutionService.Failure.class,stageResult);
            assertEquals("FAILED",executionState());assertEquals(1,countEvents());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution_output",Integer.class));
        }else{
            assertFalse(stageResult instanceof Exception,String.valueOf(stageResult));
            assertInstanceOf(Failure.class,abandonResult);assertEquals(Reason.CONFLICT,((Failure)abandonResult).reason());
            assertEquals("QUEUED",executionState());assertEquals("RUNNING",requestState());assertEquals(0,countEvents());
            assertEquals("STAGED",jdbc.queryForObject("SELECT output_state FROM agent_personal_workspace_execution_output",String.class));
        }
    }

    @Test void journalFailureRollsBackAllAggregatesAndEmitsNothing() {
        failEvent.set(true);
        assertThrows(IllegalStateException.class,this::abandon);
        assertPristine();
        assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM chat_interaction_step",String.class));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM chat_step_execution_link",String.class));
        verifyNoInteractions(broker);
        failEvent.set(false);assertEquals("CANCELLED",abandon().state());
    }

    @Test void existingFailureIsPreservedRatherThanRelabeledAsProviderCancellation() {
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='FAILED',failure_code='AGENT_DELIVERY_FAILED',failure_message='original',failed_at=12");
        assertEquals("CANCELLED",abandon().state());
        assertEquals("AGENT_DELIVERY_FAILED",jdbc.queryForObject("SELECT failure_code FROM agent_personal_workspace_execution",String.class));
        assertEquals(12L,jdbc.queryForObject("SELECT failed_at FROM agent_personal_workspace_execution",Long.class));
    }

    @Test void corruptedReceiptIsFailClosedWithoutAnotherMutation() {
        abandon();
        jdbc.update("UPDATE chat_conversation_event SET payload_json=REPLACE(payload_json,'\"providerStopped\":false','\"providerStopped\":true')");
        fail(Reason.UNAVAILABLE,()->inTx(()->service.get(OWNER,"req",KEY)));
        fail(Reason.UNAVAILABLE,this::abandon);assertEquals(1,countEvents());
    }

    @Test void v3StartedAuthorityBindingSurvivesAbandonAndSchemaRestartWithoutReadmittingProvider() {
        new cn.jia.agent.config.AgentTaskProviderCostConsentSchemaInitializer(jdbc).afterPropertiesSet();
        new cn.jia.agent.config.ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet();
        new cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.update("""
                UPDATE agent_personal_workspace_execution SET controlled_consent_id=?,execution_protocol_version=3,
                  operation_grant_id=?,runtime_input_snapshot_digest=?
                ""","consent_"+"1".repeat(32),"opgrant_"+"2".repeat(32),"7".repeat(64));
        String query="""
                SELECT controlled_consent_id,execution_protocol_version,operation_grant_id,runtime_input_snapshot_digest,
                  conversation_provider_started_at,conversation_provider_lease_version FROM agent_personal_workspace_execution
                """;
        var before=jdbc.queryForList(query);var authority=mock(ControlledImageFollowupAuthorityService.class);
        runtime.setControlledImageFollowupV3(authority,mock(ControlledImageExecutionSourceV3Dao.class));
        Receipt receipt=abandon();assertEquals(before,jdbc.queryForList(query));
        assertEquals(receipt,inTx(()->service.get(OWNER,"req",KEY)));assertEquals(receipt,abandon());
        assertDoesNotThrow(()->new PersonalWorkspaceExecutionSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new cn.jia.agent.config.ControlledImageExecutionSchemaInitializer(jdbc).afterPropertiesSet());
        assertDoesNotThrow(()->new cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet());
        byte[] bytes;try{bytes=png();}catch(Exception e){throw new AssertionError(e);}
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->inTx(()->runtime.stageConversationOutput(
                runtimeScope(),"task","run",fence(),"output_1","bird.png","image/png",bytes)));
        verifyNoInteractions(authority);
    }

    @Test void malformedVersionsKeysReasonsAndForgedCommandBindingsCannotWrite() {
        for(String bad:Arrays.asList(null,"-1","01","1.0","9223372036854775808")) {
            fail(Reason.INVALID_REQUEST,()->inTx(()->service.abandon(OWNER,"req",KEY,
                    new Command("step","exec",bad,"2",ChatBountyExecutionTerminationService.REASON))));
        }
        for(String bad:Arrays.asList(null,"tiny","space is forbidden","bad\nkey-0001"))
            fail(Reason.INVALID_REQUEST,()->inTx(()->service.abandon(OWNER,"req",bad,COMMAND)));
        fail(Reason.INVALID_REQUEST,()->inTx(()->service.abandon(OWNER,"req",KEY,new Command("step","exec","0","2","PROVIDER_NOT_STARTED"))));
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.abandon(OWNER,"req",KEY,
                new Command("foreign-step","exec","0","2",ChatBountyExecutionTerminationService.REASON))));
        fail(Reason.NOT_FOUND_OR_FORBIDDEN,()->inTx(()->service.abandon(OWNER,"req",KEY,
                new Command("step","foreign-exec","0","2",ChatBountyExecutionTerminationService.REASON))));
        assertPristine();verifyNoInteractions(broker);
    }

    private void seed() {
        jdbc.update("""
                INSERT INTO chat_request(tenant_id,owner_jiacn,client_id,request_id,request_revision,request_digest,
                  conversation_id,conversation_generation,user_message_id,aggregate_state,state_version,created_at,updated_at)
                VALUES('0','owner','client','req',1,?,'42',1,7,'RUNNING',0,1,1)
                ""","a".repeat(64));
        jdbc.update("""
                INSERT INTO chat_interaction_step(step_id,tenant_id,owner_jiacn,client_id,request_id,request_revision,
                  step_number,conversation_id,conversation_generation,task_id,assignment_revision,grant_id,grant_version,
                  target_agent_id,kind,state,state_version,input_snapshot_digest,created_at,updated_at)
                VALUES('step','0','owner','client','req',1,1,'42',1,'task',1,'grant',1,'agent','EXECUTE','RUNNING',2,?,1,1)
                ""","b".repeat(64));
        jdbc.update("INSERT INTO chat_step_execution_link VALUES('intent','0','owner','client','step','exec','RUNNING',0,1,1)");
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec").setOwnerJiacn("owner")
                .setTaskId("task").setRunId("run").setExecutionMode("CONVERSATION")
                .setTaskGrantId("grant").setTaskGrantVersion(1L).setAssignmentRevision(1L).setPermittedOperation("GENERATE_IMAGE")
                .setConversationLeaseToken("lease-token").setConversationLeaseRuntimeId("runtime")
                .setConversationLeaseVersion(1L).setConversationLeaseExpiresAt(Long.MAX_VALUE)
                .setConversationProviderStartedAt(10L).setConversationProviderLeaseVersion(1L)
                .setConversationId("42").setTargetAgentId("agent").setInstruction("draw")
                .setOutputContentMimeType("image/png").setExecutionState("QUEUED").setGrantRevision(1L)
                .setIdempotencyKey("conv_"+sha("intent")).setRequestHash("c".repeat(64)).setCreatedAt(1L);
        execution.setTenantId("0");execution.setClientId("client");inTx(()->{executions.insert(execution);return execution;});
    }
    private void configureRuntime(WorkspaceConversationAccessService access) {
        runtime=new PersonalWorkspaceExecutionServiceImpl(executions,mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class),mock(AgentRuntimeDao.class),
                new FileSystemPersonalWorkspaceStorage(storage.toAbsolutePath(),1_000_000,Set.of("image/png")),
                mock(PersonalWorkspaceWriteService.class),new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        var grants=mock(AgentTaskExecutionGrantService.class);
        when(grants.admit(any(),eq("task"),eq("grant"),eq(1L),eq(1L),eq("agent"),eq("GENERATE_IMAGE"),eq(true)))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,1,"agent","GENERATE_IMAGE",true));
        runtime.setConversationAdmission(grants,roots);
        runtime.setTaskExecutionDependencies(access,mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
        ReflectionTestUtils.setField(runtime,"conversationExecutionEnabled",true);
    }
    private ChatConversationEntity conversation(String owner,String client,String id,boolean lock) {
        var found=jdbc.query("SELECT * FROM fixture_conversation WHERE BINARY owner_jiacn=BINARY ? AND BINARY client_id=BINARY ? AND id=?"+(lock?" FOR UPDATE":""),
                (r,n)->{
            var row=new ChatConversationEntity().setId(r.getLong("id")).setJiacn(r.getString("owner_jiacn"))
                    .setLifecycleGeneration(r.getLong("generation")).setDeletedAt(r.getObject("deleted_at",Long.class))
                    .setTaskId(r.getString("task_id")).setConversationScopeType(r.getString("scope_type"))
                    .setConversationScopeKey(r.getString("scope_key")).setTargetAgentIds(r.getString("targets"))
                    .setConversationType(r.getString("conversation_type"));
            row.setTenantId(r.getString("tenant_id"));row.setClientId(r.getString("client_id"));row.setUpdateTime(1L);return row;
        },owner,client,id);
        return found.isEmpty()?null:found.getFirst();
    }
    private List<Map<String,Object>> paidFields() {
        return jdbc.queryForList("""
                SELECT conversation_provider_started_at,conversation_provider_lease_version,
                  conversation_lease_token,conversation_lease_runtime_id,conversation_lease_version,conversation_lease_expires_at,
                  task_grant_id,task_grant_version,assignment_revision,permitted_operation,idempotency_key,request_hash,grant_revision
                FROM agent_personal_workspace_execution
                """);
    }
    private void stage() throws Exception {byte[] bytes=png();inTx(()->runtime.stageConversationOutput(runtimeScope(),"task","run",fence(),"output_1","bird.png","image/png",bytes));}
    private static PersonalWorkspaceExecutionService.RuntimeScope runtimeScope(){return new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");}
    private static PersonalWorkspaceExecutionService.ConversationFence fence(){return new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token");}
    private Receipt abandon(){return inTx(()->service.abandon(OWNER,"req",KEY,COMMAND));}
    private <T>T inTx(Supplier<T> action){return tx.execute(status->action.get());}
    private static Object outcome(Supplier<?> action){try{return action.get();}catch(RuntimeException failure){return failure;}}
    private static void fail(Reason reason,Supplier<?> action){assertEquals(reason,assertThrows(Failure.class,action::get).reason());}
    private String executionState(){return jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution",String.class);}
    private String requestState(){return jdbc.queryForObject("SELECT aggregate_state FROM chat_request",String.class);}
    private int countEvents(){return jdbc.queryForObject("SELECT COUNT(*) FROM chat_conversation_event",Integer.class);}
    private void assertPristine(){assertEquals("QUEUED",executionState());assertEquals("RUNNING",requestState());assertEquals(0,countEvents());}
    private static DriverManagerDataSource ds(String url,String user,String password){var source=new DriverManagerDataSource();source.setDriverClassName("com.mysql.cj.jdbc.Driver");source.setUrl(url);source.setUsername(user);source.setPassword(password);return source;}
    private static byte[] png() throws Exception {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",bytes);return bytes.toByteArray();}
    private static String sha(String value){return sha(value.getBytes(StandardCharsets.UTF_8));}
    private static String sha(byte[] value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
