package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
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
        for (String table : List.of("agent_task_meta", "agent_task_member", "agent_task_work_item", "agent_task_event")) {
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
                EconomyLedgerMapper.class, EconomyTaskBillingMapper.class)) config.addMapper(mapper);
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
                        Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal("0.50")))), List.of(),
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
        var legacy = new AgentLegacyTaskCompatibilityService(meta, members, work,
                mock(AgentTaskAggregationService.class), identities, roots, events);
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
        var authority = mock(TaskAssigneeAuthorityService.class);
        var target = new TaskAssigneeAuthorityService.Target(TENANT, CLIENT, OWNER, AGENT, 1L, null);
        when(authority.lockForAssignment(TENANT, CLIENT, OWNER, AGENT)).thenReturn(target);
        when(authority.requireGrantTarget(eq(TENANT), eq(CLIENT), eq(OWNER), anyString(), anyString(), anyLong()))
                .thenReturn(target);
        assignment.setAssigneeAuthority(authority);
        decisions = proxy(new AgentTaskFormalDeliveryDecisionServiceImpl(
                new AgentTaskFormalDeliveryDaoImpl(sql.getMapper(AgentTaskFormalDeliveryMapper.class)),
                meta, members, work, roots, events, billing), AgentTaskFormalDeliveryDecisionService.class);
        var cancelDao = spy(new AgentTaskCancellationDaoImpl(sql.getMapper(AgentTaskCancellationMapper.class)));
        doAnswer(call -> {
            Object changed = call.callRealMethod();
            if (failOccupation) throw new InjectedFailure();
            return changed;
        }).when(cancelDao).releaseTaskOccupation(anyString(), anyString(), anyString(), anyString(), anyLong());
        cancellation = new AgentTaskCancellationServiceImpl(roots,
                new AgentTaskStateServiceImpl(meta, members, work, roots, events), work,
                new AgentTaskExecutionGrantDaoImpl(sql.getMapper(AgentTaskExecutionGrantMapper.class)), cancelDao, billing);
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
    void close() {
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
        var denied = assertThrows(cn.jia.agent.exception.AgentTaskExecutionGrantException.class, () ->
                assignment.admit(SCOPE, TASK, claim.grantId(), claim.grantVersion(), claim.assignmentRevision(),
                        AGENT, claim.permittedOperation(), false));
        assertEquals(cn.jia.agent.exception.AgentTaskExecutionGrantException.Reason.CONFLICT, denied.getReason());
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
                "agent-personal-workspace-v1_14-conversation-execution.sql",
                "agent-personal-workspace-v1_15-conversation-lease.sql")) {
            new ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/" + file))
                    .execute(jdbc.getDataSource());
        }
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
