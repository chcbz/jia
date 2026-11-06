package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.api.AgentTaskDeliberationOperationController;
import cn.jia.chat.api.AgentTaskDeliveryReviewController;
import cn.jia.chat.service.*;
import cn.jia.agent.api.AgentTaskCancellationController;
import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.DispatcherType;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.economy.config.*;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.impl.ConfiguredTaskPricingService;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.economy.service.impl.TaskBillingServiceImpl;
import cn.jia.economy.task.TaskBillingService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Production Agent/ledger SQL and Spring transactions on the same isolated MySQL database.
 * Only identity/material observations, the external wakeup receiver and explicit fault injection
 * are test doubles. No task, event, grant, outbox or money persistence is implemented by this test.
 */
@EnabledIfEnvironmentVariable(named = "ECO_V0_W02_MYSQL_URL", matches = ".+")
class AgentTaskBillingMySqlTransactionTest {
    private static final String TENANT = "0", CLIENT = "billing-client", OWNER = "billing-owner";
    private static final String PAYER = "billing-user", TASK = "billing-task", AGENT = "agent-1";
    private static final AgentTaskExecutionGrantService.Scope SCOPE =
            new AgentTaskExecutionGrantService.Scope(TENANT, CLIENT, OWNER);
    private static final TaskBillingService.Task BILLING_TASK =
            new TaskBillingService.Task(new EconomyScope(TENANT, CLIENT), OWNER, TASK);
    private static final Path DDL = Path.of("../jia-agent-mapper/src/main/resources/db");

    private JdbcTemplate admin, jdbc, independent;
    private String database;
    private DataSourceTransactionManager manager;
    private AgentTaskMutationTransaction roots;
    private TaskBillingService billing;
    private AgentTaskExecutionGrantServiceImpl assignment;
    private AgentTaskFormalDeliveryDecisionService decisions;
    private AgentTaskCancellationService cancellation;
    private AgentTaskBountyBootstrapOutboxDao outbox;
    private AgentTaskBountyBootstrapOutboxService bootstrap;
    private AgentTaskEventAfterCommitPublisher publisher;
    private final AgentTaskEventBroker broker = mock(AgentTaskEventBroker.class);
    private String failEvent;
    private boolean failBootstrap;
    private boolean failOccupation;
    private String quoteId;
    private AgentTaskDeliberationOperationReadService operationReads;
    private AgentTaskFormalDeliveryReadService deliveryReads;
    private ChatTaskDeliveryReviewService reviews;
    private ChatTaskDeliveryReviewReadService reviewSources;
    @org.junit.jupiter.api.io.TempDir Path httpDirectory;
    private Tomcat httpServer;
    private AnnotationConfigWebApplicationContext httpContext;
    private String origin;
    private byte[] signingKey;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ApiEnvelopeJsonConfig.class, ApiEnvelopeAdvice.class, ApiRequestEnvelopeAdvice.class, ExceptionHandlerAdvice.class})
    public static class HttpApp {
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Same explicitly acknowledged worker-owned MySQL used by the economy regressions.
        assertEquals("true", System.getenv("ECO_V0_W02_MYSQL_ISOLATED_FIXTURE"));
        String baseUrl = System.getenv("ECO_V0_W02_MYSQL_URL");
        String prefix = System.getenv("ECO_V0_W02_MYSQL_DATABASE_PREFIX");
        assertTrue(baseUrl.startsWith("jdbc:mysql://"));
        assertNotNull(prefix); assertTrue(prefix.matches("[a-z][a-z0-9_]{0,23}"));
        String user = System.getenv("ECO_V0_W02_MYSQL_USER");
        String password = System.getenv("ECO_V0_W02_MYSQL_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, user, password));
        assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("8.0.21"));
        database = prefix + "_agentbill_" + Long.toUnsignedString(System.nanoTime(), 36);
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        int query = baseUrl.indexOf('?');
        String address = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String url = address.substring(0, address.indexOf('/', "jdbc:mysql://".length()) + 1)
                + database + (query < 0 ? "" : baseUrl.substring(query));
        var source = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(source);
        independent = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        // Read unchanged production definitions; no parallel hand-written table schema.
        String schema = Files.readString(DDL.resolve("schema.sql"));
        for (String table : List.of("agent_task_meta", "agent_task_member", "agent_task_work_item", "agent_task_event", "agent_task_artifact")) {
            int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            assertTrue(start >= 0, table);
            jdbc.execute(schema.substring(start, schema.indexOf(";\n", start)));
        }
        for (String file : List.of("agent-task-execution-grant-v1.sql",
                "agent-task-bounty-bootstrap-outbox-v1.sql", "agent-task-formal-delivery-r2.sql")) {
            new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(source);
        }
        manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskMemberMapper.class,
                AgentTaskWorkItemMapper.class, AgentTaskEventMapper.class, AgentTaskExecutionGrantMapper.class,
                AgentTaskBountyBootstrapOutboxMapper.class, AgentTaskFormalDeliveryMapper.class, AgentTaskCancellationMapper.class,
                PersonalWorkspaceExecutionMapper.class, PersonalWorkspaceExecutionInputMapper.class, PersonalWorkspaceExecutionOutputMapper.class, SkillExecutionMapper.class, AgentTaskProviderCostConsentMapper.class,
                EconomyLedgerMapper.class, EconomyTaskBillingMapper.class, ControlledImageBridgeOperationMapper.class)) config.addMapper(mapper);
        var global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source);
        factory.setConfiguration(config); factory.setGlobalConfig(global);
        var sql = new SqlSessionTemplate(factory.getObject());
        var meta = new AgentTaskMetaDaoImpl();
        ReflectionTestUtils.setField(meta, "baseMapper", sql.getMapper(AgentTaskMetaMapper.class));
        var members = new AgentTaskMemberDaoImpl(sql.getMapper(AgentTaskMemberMapper.class));
        var work = new AgentTaskWorkItemDaoImpl(sql.getMapper(AgentTaskWorkItemMapper.class));
        var eventDao = new AgentTaskEventDaoImpl();
        ReflectionTestUtils.setField(eventDao, "baseMapper", sql.getMapper(AgentTaskEventMapper.class));
        roots = new AgentTaskMutationTransactionImpl(meta, manager);
        publisher = new AgentTaskEventAfterCommitPublisher(broker, manager);
        var actualEvents = new AgentTaskEventWriterImpl(eventDao, manager, publisher);
        AgentTaskEventWriter events = command -> {
            var written = actualEvents.append(command);
            if (command.getEventType().equals(failEvent)) throw new InjectedFailure();
            return written;
        };
        var ledger = sql.getMapper(EconomyLedgerMapper.class);
        var pricing = new ConfiguredTaskPricingService(new EconomyPricingProperties(
                List.of(new ModelTokenPrice("fixture", "fixture-model", "standard", "1", "synthetic fixture",
                        Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal("0.50")), null, Map.of())), List.of(),
                List.of(new TaskEstimateRule("fixture", "1", "synthetic fixture", "GENERAL",
                        TaskWorkload.Complexity.STANDARD, Map.of(ModelTokenPrice.Category.INPUT, 1_000_000L),
                        Map.of(), Map.of()))));
        billing = proxy(new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class), ledger,
                new EconomyPostingServiceImpl(ledger, manager, new EconomyPreviewGate(new EconomyPreviewProperties(
                        true, List.of(new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))))),
                new ObjectMapper(), pricing), TaskBillingService.class);
        var identities = mock(AgentIdentityService.class);
        when(identities.resolveAgentIdInScope(TENANT, CLIENT, OWNER, AGENT)).thenReturn(AGENT);
        when(identities.lockActiveCanonicalAgentIdsInScope(TENANT, CLIENT, OWNER, List.of(AGENT))).thenReturn(List.of(AGENT));
        var legacy = new AgentLegacyTaskCompatibilityService(meta, members, work, identities, roots, events);
        var links = mock(PersonalWorkspaceTaskLinkDao.class);
        when(links.list(TENANT, CLIENT, OWNER, TASK, null, null, 33)).thenReturn(List.of());
        var requirements = mock(AgentTaskRequirementSnapshotService.class);
        var requirement = new AgentTaskRequirementSnapshotService.Snapshot(TENANT, CLIENT, OWNER, TASK,
                1, "Fixture title", "Fixture description", "a".repeat(64), "CREATE");
        when(requirements.requireCurrent(any(), eq(TASK), eq(1L))).thenReturn(requirement);
        when(requirements.freezeCurrent(any(), eq(TASK), eq(1L))).thenReturn(requirement);
        when(requirements.read(SCOPE, TASK, 1L)).thenReturn(requirement);
        outbox = spy(new AgentTaskBountyBootstrapOutboxDaoImpl(sql.getMapper(AgentTaskBountyBootstrapOutboxMapper.class)));
        doAnswer(call -> {
            Object written = call.callRealMethod();
            if (failBootstrap) throw new InjectedFailure();
            return written;
        }).when(outbox).insert(any());
        bootstrap = new AgentTaskBountyBootstrapOutboxServiceImpl(outbox, new ObjectMapper(), manager,
                requirements, new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, null));
        assignment = new AgentTaskExecutionGrantServiceImpl(
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)),
                outbox, links, mock(PersonalWorkspaceDao.class), legacy, identities, roots,
                new ObjectMapper(), requirements, billing);
        operationReads = new AgentTaskDeliberationOperationReadServiceImpl(
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), outbox,
                new ControlledImageBridgeOperationDaoImpl(sql.getMapper(ControlledImageBridgeOperationMapper.class)),
                requirements, roots, new ObjectMapper());
        var authority = mock(TaskAssigneeAuthorityService.class);
        var target = new TaskAssigneeAuthorityService.Target(TENANT, CLIENT, OWNER, AGENT, 1L, null);
        when(authority.lockForAssignment(TENANT, CLIENT, OWNER, AGENT)).thenReturn(target);
        when(authority.requireGrantTarget(eq(TENANT), eq(CLIENT), eq(OWNER), anyString(), anyString(), anyLong()))
                .thenReturn(target);
        assignment.setAssigneeAuthority(authority);
        deliveryReads = proxy(new AgentTaskFormalDeliveryReadServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)), meta),
                AgentTaskFormalDeliveryReadService.class);
        decisions = proxy(new AgentTaskFormalDeliveryDecisionServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)),
                meta, members, work, roots, events, billing), AgentTaskFormalDeliveryDecisionService.class);
        var cancelDao = spy(new AgentTaskCancellationDaoImpl(sql.getMapper(AgentTaskCancellationMapper.class)));
        doAnswer(call -> {
            Object changed = call.callRealMethod();
            if (failOccupation) throw new InjectedFailure();
            return changed;
        }).when(cancelDao).releaseTaskOccupation(anyString(), anyString(), anyString(), anyString(), anyLong());
        var leases = new AgentWorkItemLeaseServiceImpl(members, work, roots, events, 900000);
        leases.setAssigneeAuthority(authority);
        when(authority.requireTaskAgent(TENANT,CLIENT,OWNER,TASK,AGENT,false)).thenReturn(AGENT);
        var executionCancellation = proxy(new TaskExecutionCancellationServiceImpl(
                new PersonalWorkspaceExecutionDaoImpl(sql.getMapper(PersonalWorkspaceExecutionMapper.class),
                        sql.getMapper(PersonalWorkspaceExecutionInputMapper.class), sql.getMapper(PersonalWorkspaceExecutionOutputMapper.class)),
                new SkillExecutionDaoImpl(sql.getMapper(SkillExecutionMapper.class)),
                mock(org.springframework.context.ApplicationEventPublisher.class)), TaskExecutionCancellationService.class);
        var consentCancellation = proxy(new AgentTaskProviderCostConsentServiceImpl(
                new AgentTaskProviderCostConsentDaoImpl(sql.getMapper(AgentTaskProviderCostConsentMapper.class)),
                assignment,roots,mock(ControlledImageProviderOperatorPolicy.class),new ObjectMapper()), AgentTaskProviderCostConsentService.class);
        cancellation = new AgentTaskCancellationServiceImpl(roots,
                new AgentTaskStateServiceImpl(meta, members, work, roots, events), work,
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), cancelDao, billing,
                executionCancellation,
                mock(cn.jia.chat.service.TaskDiscussionCancellation.class),
                proxy(leases,AgentWorkItemLeaseService.class),consentCancellation);
        jdbc.update("INSERT INTO agent_task_meta(task_id,owner_jiacn,tenant_id,client_id) VALUES(?,?,?,?)",
                TASK, OWNER, TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                  allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet','USER',?,'AVAILABLE','WEN',100,0,'ACTIVE',0,?,?,1,1)
                """, PAYER, TENANT, CLIENT);
        quoteId = locked(() -> billing.quote(BILLING_TASK, new TaskBillingService.Binding(1, "a".repeat(64),
                assignment.snapshotTaskInputsWithinLockedTask(SCOPE, TASK).digest(), AGENT, "recommendation-1"),
                PAYER, new TaskWorkload("GENERAL", TaskWorkload.Complexity.STANDARD, 0, 1, Map.of()),
                new TaskPricingService.ModelKey("fixture", "fixture-model", "standard"))).quoteId();
    }

    @AfterEach
    void close() throws Exception {
        try {
            if (httpServer != null) { httpServer.stop(); httpServer.destroy(); }
        } finally { if (httpContext != null) httpContext.close(); }
        if (publisher != null) publisher.close();
        if (admin != null && database != null) admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test
    void assignmentAndAcceptanceCommitOnceAndReplayOriginalReceipt() {
        var first = assign();
        assertEquals(first.getGrantId(), assign().getGrantId());
        assertEquals(50, wallet(jdbc)); assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals(1, count("agent_task_execution_grant")); assertEquals(1, count("agent_task_bounty_bootstrap_outbox"));
        prepareSubmitted();
        var receipt = decide("accepted");
        assertFalse(receipt.getReplayed()); assertTrue(decide("accepted").getReplayed());
        assertEquals("CAPTURED", charge()); assertEquals(50, wallet(jdbc));
        assertEquals(2, count("economy_transaction"));
        assertEquals(50L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(balance_wen) FROM economy_account WHERE owner_type='TASK'", Long.class));
        assertEquals("completed", taskState());
        assertEquals("done", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
    }

    @Test
    void assignmentEventFailureRollsBackRealMoneyMembersWorkAndEvents() {
        failEvent = TaskEventType.TASK_ASSIGNED;
        assertThrows(InjectedFailure.class, this::assign);
        assertAssignmentAbsent();
        verifyNoInteractions(broker);
        failEvent = null; assign();
        assertEquals(50, wallet(jdbc)); assertEquals("HELD", charge());
    }

    @Test
    void persistedBootstrapFailureRollsBackGrantAssignmentAndHoldTogether() {
        failBootstrap = true;
        assertThrows(InjectedFailure.class, this::assign);
        assertAssignmentAbsent(); verifyNoInteractions(broker);
        failBootstrap = false; assign();
        assertEquals(1, count("agent_task_bounty_bootstrap_outbox"));
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void acceptanceEventFailureRestoresHoldAndAllCompletionFacts() {
        assign(); prepareSubmitted(); clearInvocations(broker);
        int eventsBefore = count("agent_task_event");
        failEvent = TaskEventType.FORMAL_DELIVERY_ACCEPTED;
        assertThrows(InjectedFailure.class, () -> decide("accepted"));
        assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals("reviewing", taskState()); assertEquals(eventsBefore, count("agent_task_event"));
        assertEquals("submitted", jdbc.queryForObject("SELECT state FROM agent_task_formal_delivery", String.class));
        assertEquals("submitted", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertEquals("accepted", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        verifyNoInteractions(broker);
        failEvent = null; decide("accepted"); assertEquals("CAPTURED", charge());
    }

    @Test
    void physicalOuterTransactionHidesAssignmentAndCaptureUntilCommit() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assign();
            assertEquals(100, wallet(independent)); assertEquals(50, wallet(jdbc));
            assertEquals(0, independent.queryForObject("SELECT COUNT(*) FROM agent_task_execution_grant", Integer.class));
            verifyNoInteractions(broker);
        });
        assertEquals(50, wallet(independent));
        prepareSubmitted(); clearInvocations(broker);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            decide("accepted");
            assertEquals("HELD", independent.queryForObject("SELECT state FROM economy_task_charge", String.class));
            assertEquals("reviewing", independent.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
            verifyNoInteractions(broker);
        });
        assertEquals("CAPTURED", charge()); assertEquals("completed", taskState());
    }

    @Test
    void concurrentAcceptanceReplaysUnderRootLockWithoutDoubleCapture() throws Exception {
        assign(); prepareSubmitted();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return decide("accepted").getReplayed(); });
            var b = pool.submit(() -> { start.await(); return decide("accepted").getReplayed(); });
            start.countDown();
            assertNotEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertEquals(2, count("economy_transaction")); assertEquals("CAPTURED", charge());
    }

    @Test
    void reworkKeepsOriginalHoldAndNeverCaptures() {
        assign(); prepareSubmitted();
        decide("changes_requested");
        assertEquals("HELD", charge()); assertEquals(1, count("economy_transaction"));
        assertEquals(50, wallet(jdbc)); assertEquals("running", taskState());
        assertTrue(decide("changes_requested").getReplayed());
    }

    @Test void runningCancellationReleasesHoldAndClosesExecutionLeaseAndGrantTogether() throws Exception {
        prepareCancellation();assign();prepareRunning();
        var receipt=cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3);
        assertEquals("cancelled",receipt.status()); assertEquals(100,wallet(jdbc));assertEquals("RELEASED",charge());
        assertEquals("CANCELLED",executionState());assertEquals("CANCELLED",skillPhase());
        assertEquals("left",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertNull(jdbc.queryForObject("SELECT lease_token FROM agent_task_work_item",String.class));
        assertEquals(2,jdbc.queryForObject("SELECT attempt_count FROM agent_task_work_item",Integer.class));
        assertEquals("REVOKED",jdbc.queryForObject("SELECT state FROM agent_task_execution_grant",String.class));
        assertEquals(100L,jdbc.queryForObject("SELECT started_at FROM agent_task_meta",Long.class));
        assertEquals("paid-lease",jdbc.queryForObject("SELECT conversation_lease_token FROM agent_personal_workspace_execution",String.class));
        assertEquals(receipt,cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3));
        assertEquals(2,count("economy_transaction"));
    }
    @Test void finalCancellationWriteFailureRollsBackMoneyExecutionSkillAndLease() throws Exception {
        prepareCancellation();assign();prepareRunning();failOccupation=true;
        assertThrows(InjectedFailure.class,()->cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3));
        assertEquals("running",taskState());assertEquals(50,wallet(jdbc));assertEquals("HELD",charge());
        assertEquals("QUEUED",executionState());assertEquals("RUNNING",skillPhase());
        assertEquals("work-lease",jdbc.queryForObject("SELECT lease_token FROM agent_task_work_item",String.class));
        assertEquals("working",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT state FROM agent_task_execution_grant",String.class));
    }
    @Test void failedTaskCanReleaseHoldWithoutRewritingFailedWorkHistory() throws Exception {
        prepareCancellation();assign();
        jdbc.update("UPDATE agent_task_meta SET reward_status='failed',task_version=3,started_at=100,failure_reason='fixture failure'");
        jdbc.update("UPDATE agent_task_member SET member_status='failed',started_at=100,completed_at=200");
        jdbc.update("UPDATE agent_task_work_item SET status='failed',attempt_count=2,completed_at=200");
        cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,3);
        assertEquals("RELEASED",charge()); assertEquals(100,wallet(jdbc));
        assertEquals("failed",jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));
        assertEquals("failed",jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));
    }
    @Test void cancellationAndAcceptanceRaceHaveOnlyOneBusinessAndFinancialTerminal() throws Exception {
        prepareCancellation();assign();prepareSubmitted();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            var cancel=pool.submit(()->{start.await();try {cancellation.cancel(TENANT,CLIENT,OWNER,PAYER,TASK,4);return true;}catch(RuntimeException lost){return false;}});
            var accept=pool.submit(()->{start.await();try {decide("accepted");return true;}catch(RuntimeException lost){return false;}});
            start.countDown();assertNotEquals(cancel.get(20,TimeUnit.SECONDS),accept.get(20,TimeUnit.SECONDS));
        }
        assertEquals(2,count("economy_transaction"));
        if("cancelled".equals(taskState())) {assertEquals("RELEASED",charge());assertEquals(100,wallet(jdbc));}
        else {assertEquals("completed",taskState());assertEquals("CAPTURED",charge());assertEquals(50,wallet(jdbc));}
    }
    private void prepareRunning() {
        jdbc.update("UPDATE agent_task_meta SET reward_status='running',task_version=3,started_at=100");
        jdbc.update("UPDATE agent_task_member SET member_status='working',started_at=100");
        jdbc.update("UPDATE agent_task_work_item SET status='running',version=5,attempt_count=2,lease_token='work-lease',lease_until=200");
        jdbc.update("""
            INSERT INTO agent_personal_workspace_execution
            (execution_id,tenant_id,client_id,owner_jiacn,task_id,run_id,target_agent_id,instruction,output_content_mime_type,
             execution_state,grant_revision,idempotency_key,request_hash,created_at,execution_mode,execution_protocol_version,
             conversation_lease_token,conversation_lease_version,conversation_lease_expires_at,conversation_provider_started_at,conversation_provider_lease_version)
            VALUES ('exec',?,?,?,?, 'run',?,'fixture instruction','text/plain','QUEUED',1,'exec-key',REPEAT('a',64),100,'CONVERSATION',5,'paid-lease',3,200,150,3)
            """,TENANT,CLIENT,OWNER,TASK,AGENT);
        jdbc.update("""
            INSERT INTO agent_skill_execution (tenant_id,client_id,owner_jiacn,execution_id,snapshot_json,snapshot_sha256,
             phase,version,effects_json,created_at,updated_at) VALUES (?,?,?,'exec','{}',REPEAT('a',64),'RUNNING',2,'{}',100,100)
            """,TENANT,CLIENT,OWNER);
    }
    private String executionState(){return jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution WHERE execution_id='exec'",String.class);}
    private String skillPhase(){return jdbc.queryForObject("SELECT phase FROM agent_skill_execution WHERE execution_id='exec'",String.class);}

    @Test
    void initialCancellationRefundsOriginalPayerAndReplaysWithoutMoreWrites() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        var receipt = cancel();
        assertEquals("cancelled", receipt.status());
        assertCancelled();
        int events = count("agent_task_event");
        assertEquals(receipt, cancel());
        assertEquals(events, count("agent_task_event"));
        assertEquals(2, count("economy_transaction"));
        assertEquals(1, count("economy_task_quote"));
        assertEquals(1, count("agent_task_execution_grant"));
    }

    @Test
    void pendingBootstrapCancellationRefundsWithoutInventingAttemptAndCannotBeClaimed() throws Exception {
        prepareCancellation(); assign();
        var receipt = cancel(); assertCancelled();
        assertEquals(0, jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox", Integer.class));
        assertEquals(receipt, cancel());
        new TransactionTemplate(manager).executeWithoutResult(status ->
                assertNull(outbox.findClaimableForUpdate(TENANT, CLIENT, OWNER, System.currentTimeMillis())));
    }

    @Test
    void claimVersusCancellationAlwaysClosesUnadmittedIntentAndRefundsOnce() throws Exception {
        prepareCancellation(); assign();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var cancelled = pool.submit(() -> { start.await(); return cancel(); });
            var claimed = pool.submit(() -> {
                start.await(); return bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
            });
            start.countDown();
            assertEquals("cancelled", cancelled.get(20, TimeUnit.SECONDS).status());
            var oldClaim = claimed.get(20, TimeUnit.SECONDS);
            assertCancelled();
            if (oldClaim != null) assertStaleClaim(oldClaim);
            assertNull(bootstrap.claimNext(SCOPE, "next-worker", System.currentTimeMillis()));
        }
    }

    @Test
    void claimedCancellationRejectsLateAdmissionAndAllOldReconcileOutcomes() throws Exception {
        prepareCancellation(); assign();
        var claim = bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
        assertNotNull(claim); assertEquals(1, claim.claimAttempt());
        var receipt = cancel(); assertCancelled(); assertEquals(receipt, cancel());
        assertNull(jdbc.queryForObject("SELECT lease_owner FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertNull(jdbc.queryForObject("SELECT lease_until FROM agent_task_bounty_bootstrap_outbox", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM agent_task_bounty_bootstrap_outbox", Integer.class));
        var denied = assertThrows(AgentTaskExecutionGrantException.class, () ->
                assignment.admit(SCOPE, TASK, claim.grantId(), claim.grantVersion(), claim.assignmentRevision(),
                        AGENT, claim.permittedOperation(), false));
        assertEquals(AgentTaskExecutionGrantException.Reason.CONFLICT, denied.reason());
        assertStaleClaim(claim); assertCancelled();
    }

    @Test
    void claimedCancellationFailureRestoresOriginalWorkerLeaseAndHold() throws Exception {
        prepareCancellation(); assign();
        var claim = bootstrap.claimNext(SCOPE, "fixture-worker", System.currentTimeMillis());
        assertNotNull(claim);
        failOccupation = true;
        assertThrows(InjectedFailure.class, this::cancel);
        assertEquals("HELD", charge()); assertEquals(50, wallet(jdbc));
        assertEquals("assigned", taskState());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals(claim.leaseOwner(), jdbc.queryForObject("SELECT lease_owner FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals(claim.outboxVersion(), jdbc.queryForObject("SELECT version FROM agent_task_bounty_bootstrap_outbox", Long.class));
        var retry = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(), claim.outboxVersion(),
                claim.leaseOwner(), claim.claimAttempt(), AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                null, null, "FIXTURE_RETRY");
        assertEquals("RETRY", bootstrap.reconcile(SCOPE, retry, System.currentTimeMillis()).status());
        failOccupation = false; cancel(); assertCancelled();
    }

    private void assertStaleClaim(AgentTaskBountyBootstrapClaimDTO claim) {
        for (var outcome : AgentTaskBountyBootstrapReconcileDTO.Outcome.values()) {
            boolean admitted = outcome == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED;
            var late = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(), claim.outboxVersion(),
                    claim.leaseOwner(), claim.claimAttempt(), outcome, admitted ? "late-conversation" : null,
                    admitted ? "late-request" : null, admitted ? null : "LATE_FAILURE");
            assertThrows(IllegalStateException.class, () -> bootstrap.reconcile(SCOPE, late, System.currentTimeMillis()));
        }
    }

    @Test
    void cancellationEventFailureRestoresMoneyGrantBootstrapAndChildren() throws Exception {
        prepareCancellation(); assign(); prepareRetry(); clearInvocations(broker);
        int events = count("agent_task_event");
        failEvent = TaskEventType.TASK_CANCELLED;
        assertThrows(InjectedFailure.class, this::cancel);
        assertCancellationRolledBack();
        assertEquals(events, count("agent_task_event")); verifyNoInteractions(broker);
        failEvent = null; cancel(); assertCancelled();
    }

    @Test
    void finalOccupationFailureAndOuterCommitKeepCancellationAtomic() throws Exception {
        prepareCancellation(); assign(); prepareRetry(); clearInvocations(broker);
        failOccupation = true;
        assertThrows(InjectedFailure.class, this::cancel);
        assertCancellationRolledBack(); verifyNoInteractions(broker);
        failOccupation = false;
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            cancel();
            assertEquals(100, wallet(jdbc)); assertEquals(50, wallet(independent));
            assertEquals("assigned", independent.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
            assertEquals("ACTIVE", independent.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
            verifyNoInteractions(broker);
        });
        assertCancelled();
    }

    @Test
    void concurrentInitialCancellationRefundsOnceUnderTaskRootLock() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return cancel(); });
            var b = pool.submit(() -> { start.await(); return cancel(); });
            start.countDown();
            assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
        assertCancelled();
    }

    @Test
    void neverAssignedCancellationNeedsNoBillAndCreatesNoMoneyTransactions() throws Exception {
        prepareCancellation();
        assertEquals("cancelled", cancellation.cancel(TENANT, CLIENT, OWNER, PAYER, TASK, 0).status());
        assertEquals(100, wallet(jdbc)); assertEquals(0, count("economy_task_charge"));
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void assignedMissingBillCannotCancelOrReleaseUnattributedFunds() throws Exception {
        prepareCancellation(); assign(); prepareRetry();
        // Explicit corruption fixture: never infer a free bill or refund arbitrary escrow.
        jdbc.update("DELETE FROM economy_task_charge");
        var failure = assertThrows(cn.jia.economy.task.TaskBillingException.class, this::cancel);
        assertEquals(cn.jia.economy.task.TaskBillingException.Reason.RESERVATION_REQUIRED, failure.reason());
        assertEquals(50, wallet(jdbc)); assertEquals("assigned", taskState());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals(1, count("economy_transaction"));
    }

    private void prepareCancellation() throws Exception {
        // All cancellation inventories and writes use the production mapper, not fake existence results.
        createTable(DDL.resolve("schema.sql"), "agent_runtime", true);
        createTable(DDL.resolve("schema.sql"), "agent_command_delivery", true);
        createTable(DDL.resolve("schema.sql"), "agent_outbox_event", true);
        for (String file : List.of("agent-task-funding-v0.sql", "agent-selected-output-finalization-v1.sql",
                "agent-task-provider-cost-consent-v1.sql")) {
            new ResourceDatabasePopulator(new FileSystemResource(DDL.resolve(file))).execute(jdbc.getDataSource());
        }
        // Inventory reads need the production execution/lease columns, not a full Runtime schema upgrade.
        // Its standalone initializer also validates columns owned by a later native-protocol initializer.
        for (String file : List.of("agent-personal-workspace-v1_11-executions.sql",
                "agent-personal-workspace-v1_13-task-execution.sql",
                "agent-personal-workspace-v1_13-task-publication.sql",
                "agent-personal-workspace-v1_14-conversation-execution.sql",
                "agent-personal-workspace-v1_14-conversation-output.sql",
                "agent-personal-workspace-v1_15-conversation-lease.sql",
                "agent-personal-workspace-v1_16-conversation-provider-start.sql",
                "agent-skill-execution-v1.sql", "agent-personal-workspace-native-v5.sql")) {
            new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/" + file))
                    .execute(jdbc.getDataSource());
        }
        new cn.jia.agent.config.ControlledImageFollowupV3SchemaInitializer(jdbc).afterPropertiesSet();
        // Legacy Chat base-table DDL lives in the existing mapper fixture; it is read-only in this suite.
        createTable(Path.of("../../chat/jia-chat-mapper/src/test/resources/db/schema.sql"), "chat_conversation", false);
        new ResourceDatabasePopulator(new FileSystemResource(Path.of(
                "../../chat/jia-chat-mapper/src/main/resources/db/chat-deliberation-schema.sql")))
                .execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO agent_runtime(agent_id,name,status,current_task_id,tenant_id,client_id,owner_jiacn) "
                + "VALUES(?,?,'busy',?,?,?,?)", AGENT, "fixture", TASK, TENANT, CLIENT, OWNER);
    }

    private void createTable(Path source, String table, boolean ifNotExists) throws Exception {
        String schema = Files.readString(source);
        int start = schema.indexOf("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + table + " (");
        assertTrue(start >= 0, table);
        jdbc.execute(schema.substring(start, schema.indexOf(";\n", start)));
    }

    private void prepareRetry() {
        // Seed a failed, unleased bootstrap attempt; this suite does not claim relay/network coverage.
        jdbc.update("UPDATE agent_task_bounty_bootstrap_outbox SET status='RETRY',attempt_count=1,"
                + "next_retry_at=1,version=2,last_error_code='FIXTURE_ADMISSION_FAILURE'");
    }

    private AgentTaskCancellationService.Receipt cancel() {
        return cancellation.cancel(TENANT, CLIENT, OWNER, PAYER, TASK, 1);
    }

    private void assertCancelled() {
        assertEquals("cancelled", taskState()); assertEquals("RELEASED", charge());
        assertEquals(100, wallet(jdbc)); assertEquals(2, count("economy_transaction"));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("DEAD", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("TASK_CANCELLED", jdbc.queryForObject("SELECT last_error_code FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("left", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        assertEquals("cancelled", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertNull(jdbc.queryForObject("SELECT current_task_id FROM agent_runtime", String.class));
        assertEquals("online", jdbc.queryForObject("SELECT status FROM agent_runtime", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(balance_wen) FROM economy_account WHERE owner_type='TASK'", Long.class));
    }

    private void assertCancellationRolledBack() {
        assertEquals("assigned", taskState()); assertEquals("HELD", charge());
        assertEquals(50, wallet(jdbc)); assertEquals(1, count("economy_transaction"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT state FROM agent_task_execution_grant", String.class));
        assertEquals("RETRY", jdbc.queryForObject("SELECT status FROM agent_task_bounty_bootstrap_outbox", String.class));
        assertEquals("accepted", jdbc.queryForObject("SELECT member_status FROM agent_task_member", String.class));
        assertEquals("ready", jdbc.queryForObject("SELECT status FROM agent_task_work_item", String.class));
        assertEquals(TASK, jdbc.queryForObject("SELECT current_task_id FROM agent_runtime", String.class));
        assertEquals("busy", jdbc.queryForObject("SELECT status FROM agent_runtime", String.class));
    }

    /** Actual production controllers, signed JWT, Q/R codecs, task SQL and money SQL.
     * Identity/material observations remain the declared fixture doubles; no model/relay is run. */
    @Test void realHttpAssignmentAcceptanceAndReplayHaveOneFinancialEffect() throws Exception {
        startHttp();
        pointHttp(200); pointHttp(200);
        httpPost("/point-and-deliberate", pointBody().replace("recommendation-1", "other-recommendation"), "assign-http", true, 409);
        httpPost("/point-and-deliberate", pointBody().replace(quoteId, "other-quote"), "assign-http", true, 409);
        assertEquals(50, wallet(independent)); assertEquals("HELD", charge());
        assertEquals(1, count("economy_transaction")); assertEquals(1, count("agent_task_execution_grant"));
        prepareSubmitted();
        var batch = deliveryReads.listForTaskOwner(TENANT, CLIENT, OWNER, TASK).getFirst();
        String decision = ChatDeliveryReviewContract.json(ChatDeliveryReviewContract.body(
                new ChatDeliveryReviewContract.Accept("delivery-1", 4, 0, 1,
                        ChatDeliveryReviewContract.batchDigest(TASK, batch))));
        httpPost("/delivery-review-operations", decision, "accept-http", true, 200);
        httpPost("/delivery-review-operations", decision, "accept-http", true, 200);
        assertEquals(1, count("chat_task_delivery_review_operation"));
        assertEquals("COMMITTED", jdbc.queryForObject("SELECT state FROM chat_task_delivery_review_operation", String.class));
        assertEquals("CAPTURED", charge()); assertEquals("completed", taskState());
        assertEquals(50, wallet(independent)); assertEquals(2, count("economy_transaction"));
    }

    @Test void realHttpCancellationReleasesOnceAndRejectsUnauthenticatedMutation() throws Exception {
        prepareCancellation(); startHttp();
        httpPost("/point-and-deliberate", pointBody(), "assign-http", false, 401);
        assertAssignmentAbsent(); pointHttp(200); prepareRetry();
        String body = "{\"expectedTaskVersion\":\"1\"}";
        httpPost("/cancel", body, "cancel-http", true, 200);
        httpPost("/cancel", body, "cancel-http", true, 200);
        assertCancelled();
    }

    @Test void realHttpInsufficientBalanceAndFailedAssignmentNeverPartiallyCommit() throws Exception {
        startHttp(); jdbc.update("UPDATE economy_account SET balance_wen=49 WHERE account_id='wallet'");
        pointHttp(409);
        assertEquals(49, wallet(independent)); assertEquals("open", taskState());
        assertEquals(0, count("economy_transaction")); assertEquals(0, count("agent_task_execution_grant"));
        jdbc.update("UPDATE economy_account SET balance_wen=100 WHERE account_id='wallet'");
        failBootstrap = true; pointHttp(503); assertAssignmentAbsent();
        failBootstrap = false; pointHttp(200);
        assertEquals(50, wallet(independent)); assertEquals("HELD", charge());
    }

    private String pointBody() {
        return "{\"targetAgentId\":\"" + AGENT + "\",\"expectedTaskVersion\":\"0\",\"requirementRevision\":\"1\","
                + "\"quoteId\":\"" + quoteId + "\",\"recommendationId\":\"recommendation-1\"}";
    }
    private void pointHttp(int expected) throws Exception { httpPost("/point-and-deliberate", pointBody(), "assign-http", true, expected); }
    private void startHttp() throws Exception {
        // The current Web uses delivery-review-operations, not the legacy raw-body formal decision route.
        new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/chat-task-delivery-review-v1.sql"))
                .execute(jdbc.getDataSource());
        var store = new ChatTaskDeliveryReviewStore(jdbc);
        reviewSources = mock(ChatTaskDeliveryReviewReadService.class);
        // Material carrier availability is the same declared fixture observation as assignment inputs;
        // the persisted batch, operation, decision and wallet are all real SQL/services.
        when(reviewSources.sourceItems(any(), eq(TASK), any())).thenReturn(List.of(
                new ChatTaskDeliveryReviewReadService.Item("artifact-1", "1", "a".repeat(64), "result", "RESULT", null, null)));
        var reviewTransactions = proxy(new ChatTaskDeliveryReviewTransaction(roots, store, deliveryReads, decisions, reviewSources),
                ChatTaskDeliveryReviewTransaction.class);
        reviews = new ChatTaskDeliveryReviewService(store, reviewTransactions, new ChatSelectedOutputFinalizationStore(jdbc));
        signingKey = new byte[32]; new SecureRandom().nextBytes(signingKey);
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-agent-billing-fixture"));
        httpServer = new Tomcat(); httpServer.setBaseDir(httpDirectory.toString()); httpServer.setPort(0);
        httpServer.getConnector().setProperty("address", "127.0.0.1");
        var context = httpServer.addContext("", Files.createDirectory(httpDirectory.resolve("webroot")).toString());
        context.setParentClassLoader(getClass().getClassLoader());
        httpContext = new AnnotationConfigWebApplicationContext(); httpContext.setServletContext(context.getServletContext());
        httpContext.register(HttpApp.class);
        httpContext.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("jwtDecoder", decoder);
            factory.registerSingleton("assignmentController", new AgentTaskDeliberationOperationController(operationReads, assignment));
            factory.registerSingleton("cancellationController", new AgentTaskCancellationController(cancellation));
            factory.registerSingleton("decisionController", new AgentTaskDeliveryReviewController(reviews,
                    new HumanSenderIdentityResolver(mock(cn.jia.user.service.UserService.class)),
                    new TenantScopeResolver(), reviewSources));
        });
        httpContext.refresh();
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(httpContext)).setLoadOnStartup(1);
        context.addServletMappingDecoded("/", "dispatcher");
        addHttpFilter(context, "requestId", new RequestIdFilter());
        addHttpFilter(context, "contextLifecycle", new cn.jia.core.filter.EsContextFilter());
        addHttpFilter(context, "security", new DelegatingFilterProxy("springSecurityFilterChain", httpContext));
        addHttpFilter(context, "authenticatedContext", new cn.jia.oauth.filter.EsSecurityContextFilter(
                new cn.jia.core.security.FixedTenantAdmissionPolicy(TENANT, CLIENT)));
        httpServer.start(); origin = "http://127.0.0.1:" + httpServer.getConnector().getLocalPort();
    }
    private static void addHttpFilter(org.apache.catalina.Context context, String name, jakarta.servlet.Filter filter) {
        var def = new FilterDef(); def.setFilterName(name); def.setFilter(filter); context.addFilterDef(def);
        var map = new FilterMap(); map.setFilterName(name); map.addURLPattern("/*");
        map.setDispatcher(DispatcherType.REQUEST.name()); context.addFilterMap(map);
    }
    private void httpPost(String route, String data, String key, boolean authenticated, int expected) throws Exception {
        String body = "{\"protocolVersion\":1,\"requestId\":\"billing-http\",\"data\":" + data + "}";
        var request = HttpRequest.newBuilder(URI.create(origin + "/agent/tasks/" + TASK + route))
                .header("Content-Type", "application/json").header("X-Request-Id", "billing-http")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(body));
        if (authenticated) request.header("Authorization", "Bearer " + httpToken());
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected, response.statusCode(), response.body());
            if (expected == 200) {
                var json = new ObjectMapper().readTree(response.body());
                assertEquals(1, json.path("protocolVersion").asInt());
                assertEquals("E0", json.path("code").asText());
                assertEquals("billing-http", json.path("requestId").asText());
            }
        }
    }
    private String httpToken() throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding(); long now = Instant.now().getEpochSecond();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(new ObjectMapper().writeValueAsBytes(Map.of(
                "iss", "cyf-agent-billing-fixture", "sub", PAYER, "jiacn", OWNER, "tenant_id", TENANT,
                "client_id", CLIENT, "iat", now, "exp", now + 3600)));
        String content = header + "." + payload; var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }

    private AgentTaskExecutionGrantDTO assign() {
        return assignment.assignForDeliberation(SCOPE, TASK, "assign-1", AGENT, 0, 1,
                new AgentTaskExecutionGrantService.Payment(PAYER, quoteId, "recommendation-1"));
    }
    private void prepareSubmitted() {
        // Seed the pre-existing execution result only; submission/runtime behavior is not this fixture's scope.
        jdbc.update("UPDATE agent_task_meta SET reward_status='reviewing',task_version=4,started_at=100");
        jdbc.update("UPDATE agent_task_work_item SET status='submitted',result_artifact_id='artifact-1',submitted_at=200,version=8");
        String work = jdbc.queryForObject("SELECT work_item_id FROM agent_task_work_item", String.class);
        jdbc.update("""
                INSERT INTO agent_task_formal_delivery(task_id,work_item_id,delivery_id,revision,producer_agent_id,
                  run_id,summary,state,submission_digest,manifest_artifact_id,manifest_artifact_version,
                  submitted_at,version,tenant_id,client_id)
                VALUES(?,?,'delivery-1',1,?,'run-1','fixture result','submitted',?,'artifact-1',1,200,0,?,?)
                """, TASK, work, AGENT, "a".repeat(64), TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO agent_task_formal_delivery_item(delivery_id,artifact_id,artifact_version,content_hash,
                  purpose,item_order,tenant_id,client_id) VALUES('delivery-1','artifact-1',1,?,'result',0,?,?)
                """, "a".repeat(64), TENANT, CLIENT);
    }
    private AgentTaskFormalDeliveryViewDTO decide(String decision) {
        var command = new AgentTaskFormalDeliveryDecisionDTO(); command.setDeliveryId("delivery-1");
        command.setDecision(decision); command.setExpectedTaskVersion(4L); command.setExpectedDeliveryVersion(0L);
        if ("changes_requested".equals(decision)) command.setReviewReason("add tests");
        return decisions.decide(TENANT, CLIENT, TASK, OWNER, command);
    }
    private void assertAssignmentAbsent() {
        assertEquals(100, wallet(jdbc)); assertEquals("open", taskState());
        assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        for (String table : List.of("economy_task_charge", "economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot", "agent_task_member", "agent_task_work_item",
                "agent_task_event", "agent_task_execution_grant", "agent_task_bounty_bootstrap_outbox")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(1, count("economy_task_quote")); // immutable quote predates the failed assignment
    }
    private <T> T proxy(Object target, Class<T> type) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(factory.getProxy());
    }
    private <T> T locked(java.util.function.Supplier<T> work) {
        return roots.executeWithLockedTaskRootInOwnerScope(TENANT, CLIENT, OWNER, TASK, root -> work.get());
    }
    private long wallet(JdbcTemplate db) {
        return db.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='wallet'", Long.class);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String charge() { return jdbc.queryForObject("SELECT state FROM economy_task_charge", String.class); }
    private String taskState() { return jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class); }
    private static class InjectedFailure extends RuntimeException { }
}
