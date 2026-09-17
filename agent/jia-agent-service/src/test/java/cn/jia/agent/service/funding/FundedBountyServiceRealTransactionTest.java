package cn.jia.agent.service.funding;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import cn.jia.agent.service.impl.AgentTaskEventWriterImpl;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.impl.EconomyPostingServiceImpl;
import cn.jia.economy.service.impl.FundedBountyLedgerServiceImpl;
import cn.jia.task.mapper.TaskItemMapper;
import cn.jia.task.mapper.TaskPlanMapper;
import cn.jia.task.service.TaskService;
import cn.jia.task.service.impl.TaskItemDaoImpl;
import cn.jia.task.service.impl.TaskPlanDaoImpl;
import cn.jia.task.service.impl.TaskServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real Spring CGLIB ledger -> EconomyPostingServiceImpl/MyBatis, real TaskServiceImpl/DAOs,
 * and real M2 event writer/DAO/after-commit publisher, all sharing one H2 transaction manager.
 * Only fault/latch observers and fail-if-reached downstream legacy collaborators are test code;
 * none substitutes posting, task persistence, event persistence, or transaction behavior.
 * This is bounded integration source, not MySQL catalog or external-dispatch runtime evidence.
 */
class FundedBountyServiceRealTransactionTest {
    private static final String TENANT = "Tenant-W04";
    private static final String CLIENT = "Client-W04";
    private static final String USER = "jwt-sub-w04";
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final FundedBountyActor ACTOR = new FundedBountyActor(TENANT, CLIENT, USER);

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private IntegrationServices services;
    private FundedBountyServiceImpl service;
    private RecordingBroker broker;
    private CommitInterleaving interleaving;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("w04-preview", Map.of(
                        "economy.preview.enabled", "true")));
        context.register(TestConfiguration.class);
        context.refresh();
        DataSource source = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("w04/funded-integration-h2.sql"))
                .execute(source);
        services = context.getBean(IntegrationServices.class);
        service = services.funding();
        broker = context.getBean(RecordingBroker.class);
        interleaving = context.getBean(CommitInterleaving.class);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,
                    balance_micro,allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet-fixture','USER',?,'AVAILABLE','SILVER',1000,0,'ACTIVE',0,?,?,1,1)
                """, USER, TENANT, CLIENT);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            try {
                if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); // UUID-named, test-owned H2 only.
            } finally {
                context.close();
            }
        }
    }

    @Test
    void createAndCancelUseRealPostingTaskAndEventCollaboratorsWithStableReplay() {
        assertTrue(AopUtils.isCglibProxy(context.getBean(FundedBountyLedgerService.class)));
        AgentTaskCreateDTO request = request("400", "funded-conservation");
        byte[] createHash = FundedBountyRequestDigest.create(request);
        AgentTaskDTO created = service.create(ACTOR, key(1), createHash, request);

        assertEquals("FUNDS_HELD", created.getFunding().getStatus());
        assertEquals(600L, wallet());
        assertEquals(400L, escrow(created.getId()));
        assertEquals(1, count("task_plan"));
        assertEquals(1, count("task_item")); // The production ALLTIME TaskService creates an item, not a detail.
        assertEquals(created.getId(), jdbc.queryForObject("SELECT CAST(id AS VARCHAR) FROM task_plan", String.class));
        assertEquals(created.getId(), jdbc.queryForObject("SELECT task_id FROM agent_task_funding_operation", String.class));
        assertEquals(1, count("agent_task_meta"));
        assertEquals(1, count("agent_task_funding"));
        assertEquals(1, count("agent_task_funding_operation"));
        assertEquals(1, count("economy_escrow_funding_lot"));
        assertJournalAndEvents(1, 1);
        assertEquals(List.of(1L), broker.wakeups.stream().map(CommittedWakeup::version).toList());

        AgentTaskDTO replay = service.create(ACTOR, key(1), createHash, request);
        assertEquals(created.getId(), replay.getId());
        assertEquals(created.getFunding().getEscrowId(), replay.getFunding().getEscrowId());
        assertEquals(created.getCreatedAt(), replay.getCreatedAt());
        assertJournalAndEvents(1, 1);

        byte[] cancelHash = FundedBountyRequestDigest.cancel(created.getId(), "0");
        AgentTaskFundingCancelReceiptDTO cancelled = service.cancel(
                ACTOR, key(2), cancelHash, created.getId(), 0L);
        assertEquals("REFUNDED", cancelled.fundingStatus());
        assertEquals("400", cancelled.refundedMicro());
        assertEquals(1000L, wallet());
        assertEquals(0L, escrow(created.getId()));
        assertEquals("cancelled", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(2L, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(400L, jdbc.queryForObject("SELECT refunded_micro FROM economy_escrow", Long.class));
        assertJournalAndEvents(2, 2);
        assertEquals(List.of(1L, 2L), broker.wakeups.stream().map(CommittedWakeup::version).toList());
        assertEquals(cancelled, service.cancel(ACTOR, key(2), cancelHash, created.getId(), 0L));
        assertJournalAndEvents(2, 2);
    }

    @Test
    void insufficientBalanceRollsBackRealTaskItemsAccountsJournalFundingAndEvents() {
        AgentTaskCreateDTO request = request("1001", "too-expensive");
        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                service.create(ACTOR, key(3), FundedBountyRequestDigest.create(request), request));
        assertEquals("INSUFFICIENT_SILVER", failure.code());
        assertNoCreateArtifacts();
    }

    @Test
    void failureAfterRealEventAppendRollsBackTaskPostingEventVersionAndAfterCommit() {
        services.events().failAfterWrite.set(true);
        AgentTaskCreateDTO request = request("300", "late-event-failure");
        assertThrows(IllegalStateException.class, () ->
                service.create(ACTOR, key(4), FundedBountyRequestDigest.create(request), request));
        assertTrue(services.events().observedActiveTransaction.get());
        assertNoCreateArtifacts();
    }

    @Test
    void lateCancelFailureRollsBackActualRefundJournalAndEventThenRetryCommitsOnce() {
        AgentTaskCreateDTO request = request("350", "cancel-rollback");
        AgentTaskDTO created = service.create(ACTOR, key(6), FundedBountyRequestDigest.create(request), request);
        services.events().failAfterWrite.set(true);
        byte[] hash = FundedBountyRequestDigest.cancel(created.getId(), "0");
        assertThrows(IllegalStateException.class, () -> service.cancel(ACTOR, key(7), hash, created.getId(), 0L));
        assertEquals(650L, wallet());
        assertEquals(350L, escrow(created.getId()));
        assertEquals("open", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals("FUNDS_HELD", jdbc.queryForObject("SELECT funding_status FROM agent_task_funding", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT refunded_micro FROM economy_escrow", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_funding "
                + "WHERE cancel_idempotency_key IS NOT NULL OR refund_transaction_id IS NOT NULL", Integer.class));
        assertJournalAndEvents(1, 1);
        services.events().failAfterWrite.set(false);
        service.cancel(ACTOR, key(7), hash, created.getId(), 0L);
        assertEquals(1000L, wallet());
        assertJournalAndEvents(2, 2);
    }

    @Test
    void parallelSameKeyCreatesOneActualReserveAndEventAndDifferentBodyConflicts() throws Exception {
        AgentTaskCreateDTO request = request("250", "parallel-create");
        byte[] hash = FundedBountyRequestDigest.create(request);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskDTO> a = executor.submit(() -> {
                await(start);
                return service.create(ACTOR, key(5), hash, request);
            });
            Future<AgentTaskDTO> b = executor.submit(() -> {
                await(start);
                return service.create(ACTOR, key(5), hash, request);
            });
            start.countDown();
            AgentTaskDTO first = a.get(10, TimeUnit.SECONDS);
            AgentTaskDTO second = b.get(10, TimeUnit.SECONDS);
            assertEquals(first.getId(), second.getId());
            assertEquals(first.getFunding().getEscrowId(), second.getFunding().getEscrowId());
        } finally {
            start.countDown();
            executor.shutdownNow(); // Only this test's two owned workers.
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("task_plan"));
        assertEquals(1, count("task_item"));
        assertEquals(1, count("agent_task_funding_operation"));
        assertEquals(750L, wallet());
        assertJournalAndEvents(1, 1);
        AgentTaskCreateDTO changed = request("251", "parallel-create");
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(FundedBountyException.class, () ->
                service.create(ACTOR, key(5), FundedBountyRequestDigest.create(changed), changed)).code());
        assertJournalAndEvents(1, 1);
    }

    @Test
    void actualCreateHeldBeforeCommitRacesLegacyPrecheckAndRootReservationThenLockedGuardRejects() throws Exception {
        // No synthetic funding INSERT: pause the real creator after its final receipt write,
        // while its root, real reserve, and real event are still uncommitted.
        Pause pause = new Pause();
        interleaving.pause = pause;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            AgentTaskCreateDTO request = request("200", "create-root-interleaving");
            Future<AgentTaskDTO> creator = executor.submit(() -> service.create(
                    ACTOR, key(8), FundedBountyRequestDigest.create(request), request));
            await(pause.createWritten);
            Future<String> legacy = executor.submit(() -> {
                services.guard().requireAssignmentAllowed(TENANT, CLIENT, pause.taskId, false, 1, false);
                pause.precheckPassed.countDown(); // The other transaction's funding is not visible yet.
                FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                        services.compatibility().assignResolved(TENANT, CLIENT, pause.taskId, List.of(AGENT), false,
                                new AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator() {
                                    @Override
                                    public void beforeIdentityLock(AgentTaskMetaEntity root, List<String> agents) {
                                        services.guard().requireAssignmentAllowed(
                                                TENANT, CLIENT, root.getTaskId(), false, agents.size(), true);
                                    }
                                    @Override
                                    public void validate(AgentTaskMetaEntity root, List<String> agents) {
                                        throw new AssertionError("locked funded guard must reject before identity/mutation");
                                    }
                                }));
                return failure.code();
            });
            await(pause.precheckPassed);
            await(pause.legacyRootAttempt); // Interceptor is at the actual mapper root-reservation statement.
            assertFalse(creator.isDone());
            assertThrows(TimeoutException.class, () -> legacy.get(200, TimeUnit.MILLISECONDS));
            assertTrue(broker.wakeups.isEmpty(), "no wakeup while real creation transaction is uncommitted");
            assertEquals(0, count("agent_task_event"));
            assertEquals(0, count("economy_transaction"));
            pause.allowCommit.countDown();
            assertEquals(pause.taskId, creator.get(10, TimeUnit.SECONDS).getId());
            assertEquals("QUOTE_REQUIRED", legacy.get(10, TimeUnit.SECONDS));
        } finally {
            pause.allowCommit.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            interleaving.pause = null;
        }
        assertEquals(0, count("agent_task_member"));
        assertEquals(0, count("agent_task_work_item"));
        assertEquals("open", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals(0L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Long.class));
        assertEquals(800L, wallet());
        assertJournalAndEvents(1, 1);
    }

    private void assertNoCreateArtifacts() {
        assertEquals(1000L, wallet());
        assertEquals(1, count("economy_account"));
        for (String table : List.of("task_plan", "task_item", "agent_task_meta", "agent_task_funding",
                "agent_task_funding_operation", "economy_transaction", "economy_entry", "economy_escrow",
                "economy_escrow_funding_lot", "agent_task_event", "agent_task_member", "agent_task_work_item")) {
            assertEquals(0, count(table), table);
        }
        assertTrue(broker.wakeups.isEmpty());
    }

    private void assertJournalAndEvents(int journals, int events) {
        assertEquals(journals, count("economy_transaction"));
        assertEquals(journals * 2, count("economy_entry"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT transaction_id FROM economy_entry "
                + "GROUP BY transaction_id HAVING SUM(signed_amount_micro)<>0) unbalanced", Integer.class));
        assertEquals(1000L, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE balance_micro<0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_escrow e JOIN economy_account a "
                + "ON a.account_id=e.escrow_account_id AND a.tenant_id=e.tenant_id AND a.client_id=e.client_id "
                + "WHERE e.gross_micro<>e.captured_micro+e.refunded_micro+a.balance_micro", Integer.class));
        assertEquals(events, count("agent_task_event"));
        assertEquals(events, broker.wakeups.size());
        for (CommittedWakeup wakeup : broker.wakeups) {
            assertEquals(1, wakeup.visibleEventCount(), "event must be committed on independent connection before publication");
        }
        assertTrue(services.events().observedActiveTransaction.get());
    }

    private long wallet() {
        return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE owner_type='USER' "
                + "AND owner_id=? AND tenant_id=? AND client_id=?", Long.class, USER, TENANT, CLIENT);
    }

    private long escrow(String taskId) {
        return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE owner_type='TASK' "
                + "AND owner_id=? AND tenant_id=? AND client_id=?", Long.class, taskId, TENANT, CLIENT);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static AgentTaskCreateDTO request(String amount, String title) {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(title);
        request.setDescription("real collaborator integration fixture");
        request.setRequiredAbilities(List.of("test"));
        request.setReward(1);
        request.setGrossBountyAmountMicro(amount);
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        request.setRequiredSkillRequirements(List.of());
        return request;
    }

    private static String key(int value) {
        return "00000000-0000-0000-0000-" + String.format("%012d", value);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "bounded interleaving timed out");
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfiguration {
        @Bean
        DriverManagerDataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:w04_integrated_" + UUID.randomUUID()
                    + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        CommitInterleaving interleaving() { return new CommitInterleaving(); }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(DataSource source, CommitInterleaving interleaving) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskFundingMapper.class,
                    AgentTaskEventMapper.class, TaskPlanMapper.class, TaskItemMapper.class, EconomyLedgerMapper.class)) {
                configuration.addMapper(mapper);
            }
            configuration.addInterceptor(interleaving);
            GlobalConfig global = new GlobalConfig();
            global.setIdentifierGenerator(new DefaultIdentifierGenerator());
            MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
            factoryBean.setDataSource(source);
            factoryBean.setConfiguration(configuration);
            factoryBean.setGlobalConfig(global);
            SqlSessionFactory factory = factoryBean.getObject();
            if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
            return new SqlSessionTemplate(factory);
        }

        @Bean
        EconomyLedgerMapper economyMapper(SqlSessionTemplate template) {
            return template.getMapper(EconomyLedgerMapper.class);
        }

        @Bean
        EconomyPostingServiceImpl postingService(EconomyLedgerMapper mapper, PlatformTransactionManager tm) {
            return new EconomyPostingServiceImpl(mapper, tm, new EconomyPreviewGate(
                    new EconomyPreviewProperties(true, true, List.of(
                            new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))));
        }

        @Bean
        FundedBountyLedgerServiceImpl fundedLedger(EconomyLedgerMapper mapper, EconomyPostingServiceImpl posting) {
            return new FundedBountyLedgerServiceImpl(mapper, posting);
        }

        @Bean
        RecordingBroker broker(DriverManagerDataSource source) {
            // Different DataSource identity intentionally forces a separate READ_COMMITTED observer,
            // not a read of the creator's transaction-bound, uncommitted event.
            return new RecordingBroker(new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), "sa", "")));
        }

        @Bean
        AgentTaskEventAfterCommitPublisher afterCommitPublisher(RecordingBroker broker, PlatformTransactionManager tm) {
            return new AgentTaskEventAfterCommitPublisher(broker, tm);
        }

        @Bean
        IntegrationServices integrationServices(SqlSessionTemplate template, DataSource source,
                PlatformTransactionManager tm, FundedBountyLedgerService ledger,
                AgentTaskEventAfterCommitPublisher publisher) {
            AgentTaskMetaDaoImpl meta = new AgentTaskMetaDaoImpl();
            ReflectionTestUtils.setField(meta, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
            AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
            ReflectionTestUtils.setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
            ObservingEvents events = new ObservingEvents(new AgentTaskEventWriterImpl(eventDao, tm, publisher));
            TaskPlanDaoImpl plans = new TaskPlanDaoImpl();
            ReflectionTestUtils.setField(plans, "baseMapper", template.getMapper(TaskPlanMapper.class));
            TaskItemDaoImpl items = new TaskItemDaoImpl();
            ReflectionTestUtils.setField(items, "baseMapper", template.getMapper(TaskItemMapper.class));
            TaskServiceImpl tasks = new TaskServiceImpl();
            ReflectionTestUtils.setField(tasks, "baseDao", plans);
            ReflectionTestUtils.setField(tasks, "taskItemDao", items);
            StaticListableBeanFactory taskBeans = new StaticListableBeanFactory();
            taskBeans.addBean("taskService", tasks);
            AgentTaskMutationTransaction mutation = new AgentTaskMutationTransactionImpl(meta, tm);
            AgentTaskFundingMapper funding = template.getMapper(AgentTaskFundingMapper.class);
            PersistedFundedBountyLegacyGuard guard = new PersistedFundedBountyLegacyGuard(funding, source);
            FundedBountyServiceImpl service = new FundedBountyServiceImpl(funding, meta, mutation, events,
                    taskBeans.getBeanProvider(TaskService.class), ledger, tm);
            // These collaborators must never be reached: the actual task/root/funding admission
            // must reject first. They provide no persistence or transaction behavior to this test.
            AgentLegacyTaskCompatibilityService compatibility = new AgentLegacyTaskCompatibilityService(
                    meta, unreachable(AgentTaskMemberDao.class), unreachable(AgentTaskWorkItemDao.class),
                    unreachable(AgentTaskAggregationService.class), unreachable(AgentIdentityService.class), mutation, events);
            ReflectionTestUtils.setField(compatibility, "fundedBountyLegacyGuard", guard);
            return new IntegrationServices(service, events, guard, compatibility);
        }
    }

    private static <T> T unreachable(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            throw new AssertionError("funded guard bypass reached " + type.getSimpleName() + "." + method.getName());
        }));
    }

    record IntegrationServices(FundedBountyServiceImpl funding, ObservingEvents events,
            PersistedFundedBountyLegacyGuard guard, AgentLegacyTaskCompatibilityService compatibility) { }

    static final class ObservingEvents implements AgentTaskEventWriter {
        private final AgentTaskEventWriter delegate;
        final AtomicBoolean failAfterWrite = new AtomicBoolean();
        final AtomicBoolean observedActiveTransaction = new AtomicBoolean();

        ObservingEvents(AgentTaskEventWriter delegate) { this.delegate = delegate; }

        @Override
        public AgentTaskEventWriteResult append(AgentTaskEventWriteCommand command) {
            observedActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
            AgentTaskEventWriteResult result = delegate.append(command);
            if (failAfterWrite.get()) throw new IllegalStateException("injected failure after real event/enqueue");
            return result;
        }
    }

    record CommittedWakeup(long version, int visibleEventCount) { }

    static class RecordingBroker extends AgentTaskEventBroker {
        private final JdbcTemplate committedView;
        final List<CommittedWakeup> wakeups = new CopyOnWriteArrayList<>();

        RecordingBroker(JdbcTemplate committedView) { this.committedView = committedView; }

        @Override
        public void publish(TaskScope scope, long eventVersion) {
            int visible = committedView.queryForObject("SELECT COUNT(*) FROM agent_task_event "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=? AND event_version=?",
                    Integer.class, scope.tenantId(), scope.clientId(), scope.taskId(), eventVersion);
            wakeups.add(new CommittedWakeup(eventVersion, visible));
            super.publish(scope, eventVersion);
        }
    }

    static final class Pause {
        final CountDownLatch createWritten = new CountDownLatch(1);
        final CountDownLatch precheckPassed = new CountDownLatch(1);
        final CountDownLatch legacyRootAttempt = new CountDownLatch(1);
        final CountDownLatch allowCommit = new CountDownLatch(1);
        volatile String taskId;
    }

    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static final class CommitInterleaving implements Interceptor {
        volatile Pause pause;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String statement = ((MappedStatement) invocation.getArgs()[0]).getId();
            Pause current = pause;
            if (current != null && statement.equals(AgentTaskMetaMapper.class.getName() + ".reserveOpenTaskRoot")) {
                current.legacyRootAttempt.countDown();
            }
            Object result = invocation.proceed(); // Always execute the production SQL; never synthesize results.
            if (current != null && statement.equals(AgentTaskFundingMapper.class.getName() + ".completeOperation")) {
                current.taskId = (String) ((Map<?, ?>) invocation.getArgs()[1]).get("taskId");
                current.createWritten.countDown();
                await(current.allowCommit);
            }
            return result;
        }
    }
}
