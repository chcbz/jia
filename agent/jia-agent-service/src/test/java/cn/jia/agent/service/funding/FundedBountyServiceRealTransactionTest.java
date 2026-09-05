package cn.jia.agent.service.funding;

import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskEventWriteResult;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.economy.bounty.FundedBountyRefundCommand;
import cn.jia.economy.bounty.FundedBountyRefundReceipt;
import cn.jia.economy.bounty.FundedBountyReserveCommand;
import cn.jia.economy.bounty.FundedBountyReserveReceipt;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.task.entity.TaskDetailEntity;
import cn.jia.task.entity.TaskDetailVO;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.pagehelper.PageInfo;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real H2/MyBatis/TM proof for atomic funded create, cancel/refund, replay, and events. */
class FundedBountyServiceRealTransactionTest {
    private static final String TENANT = "Tenant-W04";
    private static final String CLIENT = "Client-W04";
    private static final String USER = "jwt-sub-w04";
    private static final FundedBountyActor ACTOR = new FundedBountyActor(TENANT, CLIENT, USER);

    private JdbcTemplate jdbc;
    private FundedBountyServiceImpl service;
    private JdbcLedger ledger;
    private JdbcEventWriter events;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:w04_funded_service;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskFundingMapper.class);
        configuration.addMapper(AgentTaskMetaMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        AgentTaskFundingMapper fundingMapper = template.getMapper(AgentTaskFundingMapper.class);
        AgentTaskMetaDaoImpl metaDao = new AgentTaskMetaDaoImpl();
        Field baseMapper = cn.jia.common.dao.BaseDaoImpl.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(metaDao, template.getMapper(AgentTaskMetaMapper.class));

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        AgentTaskMutationTransaction mutation = new AgentTaskMutationTransactionImpl(metaDao, transactionManager);
        ledger = new JdbcLedger(jdbc);
        events = new JdbcEventWriter(jdbc);
        JdbcTaskService tasks = new JdbcTaskService(jdbc);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("taskService", tasks);
        ObjectProvider<TaskService> taskProvider = beans.getBeanProvider(TaskService.class);
        service = new FundedBountyServiceImpl(fundingMapper, metaDao, mutation, events,
                taskProvider, ledger, transactionManager);
        jdbc.update("INSERT INTO test_wallet(tenant_id,client_id,principal_id,balance_micro) "
                + "VALUES(?,?,?,1000)", TENANT, CLIENT, USER);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void createThenCancelCommitsPlanRootFundingBalancedJournalEventsAndAfterCommitWakeups() {
        AgentTaskCreateDTO request = request("400", "funded-conservation");
        byte[] createHash = FundedBountyRequestDigest.create(request);
        AgentTaskDTO created = service.create(ACTOR, key(1), createHash, request);

        assertEquals("FUNDS_HELD", created.getFunding().getStatus());
        assertEquals(600L, wallet());
        assertEquals(400L, escrow(created.getId()));
        assertEquals(0L, journalSum());
        assertEquals(1, count("task_plan"));
        assertEquals(1, count("task_detail"));
        assertEquals(1, count("agent_task_meta"));
        assertEquals(1, count("agent_task_funding"));
        assertEquals(1, count("agent_task_funding_operation"));
        assertEquals(1, count("test_event"));
        assertEquals(1, count("test_outbox"));
        assertEquals(1, events.afterCommitWakeups.get());

        byte[] cancelHash = FundedBountyRequestDigest.cancel(created.getId(), "0");
        AgentTaskFundingCancelReceiptDTO cancelled = service.cancel(
                ACTOR, key(2), cancelHash, created.getId(), 0L);

        assertEquals("REFUNDED", cancelled.status());
        assertEquals("400", cancelled.refundedMicro());
        assertEquals(1000L, wallet());
        assertEquals(0L, escrow(created.getId()));
        assertEquals(0L, journalSum());
        assertEquals(4, count("test_journal"));
        assertEquals(2, count("test_event"));
        assertEquals(2, count("test_outbox"));
        assertEquals(2, events.afterCommitWakeups.get());
        assertEquals("cancelled", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta", String.class));

        assertEquals(cancelled, service.cancel(ACTOR, key(2), cancelHash, created.getId(), 0L));
        assertEquals(4, count("test_journal"));
        assertEquals(2, count("test_event"));
        assertEquals(2, events.afterCommitWakeups.get());
    }

    @Test
    void insufficientBalanceLeavesNoTaskPlanRootFundingJournalEventOrOutbox() {
        AgentTaskCreateDTO request = request("1001", "too-expensive");
        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                service.create(ACTOR, key(3), FundedBountyRequestDigest.create(request), request));

        assertEquals("INSUFFICIENT_SILVER", failure.code());
        assertEquals(1000L, wallet());
        assertNoCreateArtifacts();
    }

    @Test
    void lateEventFailureRollsBackPlanDetailRootFundingReserveJournalAndWakeup() {
        events.failAfterWrite.set(true);
        AgentTaskCreateDTO request = request("300", "late-event-failure");

        assertThrows(IllegalStateException.class, () ->
                service.create(ACTOR, key(4), FundedBountyRequestDigest.create(request), request));

        assertEquals(1000L, wallet());
        assertNoCreateArtifacts();
        assertEquals(0, events.afterCommitWakeups.get());
    }

    @Test
    void lateCancelEventFailureRollsBackRefundTaskAndFundingStateWithoutWakeup() {
        AgentTaskCreateDTO request = request("350", "cancel-rollback");
        AgentTaskDTO created = service.create(
                ACTOR, key(6), FundedBountyRequestDigest.create(request), request);
        events.failAfterWrite.set(true);
        byte[] cancelHash = FundedBountyRequestDigest.cancel(created.getId(), "0");

        assertThrows(IllegalStateException.class, () -> service.cancel(
                ACTOR, key(7), cancelHash, created.getId(), 0L));

        assertEquals(650L, wallet());
        assertEquals(350L, escrow(created.getId()));
        assertEquals("open", jdbc.queryForObject(
                "SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals("FUNDS_HELD", jdbc.queryForObject(
                "SELECT funding_status FROM agent_task_funding", String.class));
        assertEquals(2, count("test_journal"));
        assertEquals(1, count("test_event"));
        assertEquals(1, count("test_outbox"));
        assertEquals(1, events.afterCommitWakeups.get());
    }

    @Test
    void parallelSameKeyReturnsStableReceiptAndChangedBodyConflictsWithoutDuplicateRows() throws Exception {
        AgentTaskCreateDTO request = request("250", "parallel-create");
        byte[] hash = FundedBountyRequestDigest.create(request);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AgentTaskDTO first;
        AgentTaskDTO second;
        try {
            Future<AgentTaskDTO> a = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.create(ACTOR, key(5), hash, request);
            });
            Future<AgentTaskDTO> b = executor.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return service.create(ACTOR, key(5), hash, request);
            });
            start.countDown();
            first = a.get(10, TimeUnit.SECONDS);
            second = b.get(10, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(first.getId(), second.getId());
        assertEquals(first.getFunding().getEscrowId(), second.getFunding().getEscrowId());
        assertEquals(1, count("agent_task_funding_operation"));
        assertEquals(1, count("agent_task_funding"));
        assertEquals(2, count("test_journal"));
        assertEquals(1, count("test_event"));
        assertEquals(1, events.afterCommitWakeups.get());

        AgentTaskCreateDTO changed = request("251", "parallel-create");
        FundedBountyException conflict = assertThrows(FundedBountyException.class, () ->
                service.create(ACTOR, key(5), FundedBountyRequestDigest.create(changed), changed));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        assertTrue(!Arrays.equals(FundedBountyRequestDigest.create(request),
                FundedBountyRequestDigest.create(changed)));
        assertEquals(2, count("test_journal"));
    }

    private void assertNoCreateArtifacts() {
        for (String table : List.of("task_plan", "task_detail", "agent_task_meta",
                "agent_task_funding", "agent_task_funding_operation", "test_escrow",
                "test_journal", "test_event", "test_outbox")) {
            assertEquals(0, count(table), table);
        }
    }

    private AgentTaskCreateDTO request(String amount, String title) {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(title);
        request.setDescription("real transaction fixture");
        request.setRequiredAbilities(List.of("test"));
        request.setReward(1);
        request.setGrossBountyAmountMicro(amount);
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        request.setRequiredSkillRequirements(List.of());
        return request;
    }

    private long wallet() {
        return jdbc.queryForObject("SELECT balance_micro FROM test_wallet WHERE tenant_id=? "
                + "AND client_id=? AND principal_id=?", Long.class, TENANT, CLIENT, USER);
    }

    private long escrow(String taskId) {
        return jdbc.queryForObject("SELECT balance_micro FROM test_escrow WHERE task_id=?",
                Long.class, taskId);
    }

    private long journalSum() {
        Long value = jdbc.queryForObject("SELECT COALESCE(SUM(signed_amount_micro),0) FROM test_journal",
                Long.class);
        return value == null ? 0L : value;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String key(int value) {
        return "00000000-0000-0000-0000-" + String.format("%012d", value);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(32) NOT NULL,assigned_agent_id VARCHAR(100),
                    required_abilities VARCHAR(2000),reward INT,assigned_at BIGINT,started_at BIGINT,
                    completed_at BIGINT,failure_reason VARCHAR(500),collaboration_mode VARCHAR(16) NOT NULL,
                    risk_level VARCHAR(16) NOT NULL,max_agents INT NOT NULL,coordinator_agent_id VARCHAR(100),
                    review_required BOOLEAN NOT NULL,task_version BIGINT NOT NULL,
                    current_event_version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,create_time BIGINT NOT NULL,update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,task_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_funding_operation(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,principal_type VARCHAR(20) NOT NULL,
                    principal_id VARCHAR(100) NOT NULL,idempotency_key VARBINARY(36) NOT NULL,
                    request_hash BINARY(32) NOT NULL,task_id VARCHAR(100) NOT NULL,status VARCHAR(16) NOT NULL,
                    reserve_transaction_id VARCHAR(100),receipt_task_version BIGINT,receipt_created_at BIGINT,
                    receipt_updated_at BIGINT,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key),
                    UNIQUE(tenant_id,client_id,task_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_funding(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
                    funding_mode VARCHAR(32) NOT NULL,funding_status VARCHAR(24) NOT NULL,
                    payer_principal_type VARCHAR(20) NOT NULL,payer_principal_id VARCHAR(100) NOT NULL,
                    settlement_policy VARCHAR(32) NOT NULL,gross_bounty_amount_micro BIGINT NOT NULL,
                    remaining_micro BIGINT NOT NULL,escrow_id VARCHAR(100),escrow_version BIGINT,
                    reserve_transaction_id VARCHAR(100),required_skill_requirements CLOB NOT NULL,
                    cancel_idempotency_key VARBINARY(36),cancel_request_hash BINARY(32),
                    refund_transaction_id VARCHAR(100),cancel_refunded_micro BIGINT,cancel_task_version BIGINT,
                    refunded_at BIGINT,version BIGINT NOT NULL,tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,create_time BIGINT NOT NULL,update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,task_id),UNIQUE(tenant_id,client_id,escrow_id))
                """);
        jdbc.execute("CREATE TABLE agent_task_member(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100),tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_work_item(id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100),tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE task_plan(id BIGINT PRIMARY KEY,name VARCHAR(100),jiacn VARCHAR(50))");
        jdbc.execute("CREATE TABLE task_detail(id BIGINT AUTO_INCREMENT PRIMARY KEY,plan_id BIGINT,title VARCHAR(100))");
        jdbc.execute("CREATE TABLE test_wallet(tenant_id VARCHAR(50),client_id VARCHAR(50),principal_id VARCHAR(100),balance_micro BIGINT,PRIMARY KEY(tenant_id,client_id,principal_id))");
        jdbc.execute("CREATE TABLE test_escrow(task_id VARCHAR(100) PRIMARY KEY,balance_micro BIGINT,version BIGINT,reserve_transaction_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE test_journal(id BIGINT AUTO_INCREMENT PRIMARY KEY,transaction_id VARCHAR(100),task_id VARCHAR(100),signed_amount_micro BIGINT,idempotency_key VARCHAR(36),request_hash BINARY(32),UNIQUE(idempotency_key,signed_amount_micro))");
        jdbc.execute("CREATE TABLE test_event(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(100) UNIQUE,task_id VARCHAR(100),event_type VARCHAR(100))");
        jdbc.execute("CREATE TABLE test_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_id VARCHAR(100) UNIQUE,status VARCHAR(20))");
    }

    static final class JdbcLedger implements FundedBountyLedgerService {
        private final JdbcTemplate jdbc;
        private final AtomicInteger transactions = new AtomicInteger();

        JdbcLedger(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public FundedBountyReserveReceipt reserve(FundedBountyReserveCommand command) {
            Long balance = jdbc.queryForObject("SELECT balance_micro FROM test_wallet WHERE tenant_id=? "
                    + "AND client_id=? AND principal_id=? FOR UPDATE", Long.class,
                    command.scope().tenantId(), command.scope().clientId(), command.principal().id());
            if (balance == null || balance < command.amountMicro()) {
                throw new EconomyPostingException(EconomyPostingException.Reason.INSUFFICIENT_FUNDS,
                        "insufficient fixture wallet");
            }
            int id = transactions.incrementAndGet();
            String transactionId = "etx-test-" + id;
            String escrowId = "esc-test-" + id;
            jdbc.update("UPDATE test_wallet SET balance_micro=balance_micro-? WHERE tenant_id=? "
                            + "AND client_id=? AND principal_id=?", command.amountMicro(),
                    command.scope().tenantId(), command.scope().clientId(), command.principal().id());
            jdbc.update("INSERT INTO test_escrow(task_id,balance_micro,version,reserve_transaction_id) "
                    + "VALUES(?,?,1,?)", command.taskId(), command.amountMicro(), transactionId);
            journal(transactionId, command.taskId(), -command.amountMicro(), command.idempotencyKey(), command.requestHash());
            journal(transactionId, command.taskId(), command.amountMicro(), command.idempotencyKey(), command.requestHash());
            return new FundedBountyReserveReceipt(transactionId, escrowId, 1L,
                    command.amountMicro(), 1_800_000_000_000L + id);
        }

        @Override
        public FundedBountyRefundReceipt refund(FundedBountyRefundCommand command) {
            Long held = jdbc.queryForObject("SELECT balance_micro FROM test_escrow WHERE task_id=? FOR UPDATE",
                    Long.class, command.taskId());
            if (held == null || held != command.amountMicro()) {
                throw new EconomyPostingException(EconomyPostingException.Reason.ESCROW_CONFLICT,
                        "fixture escrow mismatch");
            }
            int id = transactions.incrementAndGet();
            String transactionId = "etx-test-" + id;
            jdbc.update("UPDATE test_escrow SET balance_micro=0,version=version+1 WHERE task_id=?",
                    command.taskId());
            jdbc.update("UPDATE test_wallet SET balance_micro=balance_micro+? WHERE tenant_id=? "
                            + "AND client_id=? AND principal_id=?", command.amountMicro(),
                    command.scope().tenantId(), command.scope().clientId(), command.principal().id());
            journal(transactionId, command.taskId(), -command.amountMicro(), command.idempotencyKey(), command.requestHash());
            journal(transactionId, command.taskId(), command.amountMicro(), command.idempotencyKey(), command.requestHash());
            return new FundedBountyRefundReceipt(transactionId, command.expectedEscrowVersion() + 1,
                    command.amountMicro(), 1_800_000_000_000L + id);
        }

        private void journal(String transactionId, String taskId, long amount,
                String key, byte[] hash) {
            jdbc.update("INSERT INTO test_journal(transaction_id,task_id,signed_amount_micro,"
                    + "idempotency_key,request_hash) VALUES(?,?,?,?,?)",
                    transactionId, taskId, amount, key, hash);
        }
    }

    static final class JdbcEventWriter implements AgentTaskEventWriter {
        private final JdbcTemplate jdbc;
        private final AtomicBoolean failAfterWrite = new AtomicBoolean();
        private final AtomicInteger afterCommitWakeups = new AtomicInteger();

        JdbcEventWriter(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public AgentTaskEventWriteResult append(AgentTaskEventWriteCommand command) {
            jdbc.update("INSERT INTO test_event(event_id,task_id,event_type) VALUES(?,?,?)",
                    command.getEventId(), command.getTaskId(), command.getEventType());
            jdbc.update("INSERT INTO test_outbox(event_id,status) VALUES(?,'PENDING')", command.getEventId());
            jdbc.update("UPDATE agent_task_meta SET current_event_version=current_event_version+1 "
                    + "WHERE tenant_id=? AND client_id=? AND task_id=?", command.getTenantId(),
                    command.getClientId(), command.getTaskId());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    afterCommitWakeups.incrementAndGet();
                }
            });
            if (failAfterWrite.get()) throw new IllegalStateException("forced late event failure");
            AgentTaskEventEntity event = new AgentTaskEventEntity();
            event.setEventId(command.getEventId());
            event.setTaskId(command.getTaskId());
            event.setEventType(command.getEventType());
            return new AgentTaskEventWriteResult().setEventVersion(1L)
                    .setPreviousVersion(0L).setCurrentVersion(1L).setEvent(event);
        }
    }

    static final class JdbcTaskService implements TaskService {
        private final JdbcTemplate jdbc;
        private final AtomicLong ids = new AtomicLong(100L);

        JdbcTaskService(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public TaskPlanEntity create(TaskPlanEntity entity) {
            long id = ids.incrementAndGet();
            entity.setId(id);
            jdbc.update("INSERT INTO task_plan(id,name,jiacn) VALUES(?,?,?)",
                    id, entity.getName(), entity.getJiacn());
            jdbc.update("INSERT INTO task_detail(plan_id,title) VALUES(?,?)", id, entity.getName());
            return entity;
        }

        @Override public void cancel(Long id) { throw unsupported(); }
        @Override public PageInfo<TaskDetailEntity> findItems(TaskDetailVO example, int pageNum, int pageSize, String orderBy) { throw unsupported(); }
        @Override public TaskPlanEntity get(Serializable id) { throw unsupported(); }
        @Override public TaskPlanEntity update(TaskPlanEntity entity) { throw unsupported(); }
        @Override public TaskPlanEntity upsert(TaskPlanEntity entity) { throw unsupported(); }
        @Override public boolean delete(Serializable id) { throw unsupported(); }
        @Override public TaskPlanEntity findOne(TaskPlanEntity query) { throw unsupported(); }
        @Override public List<TaskPlanEntity> findList(TaskPlanEntity query) { throw unsupported(); }
        @Override public PageInfo<TaskPlanEntity> findPage(TaskPlanEntity query, int pageNum, int pageSize) { throw unsupported(); }
        @Override public PageInfo<TaskPlanEntity> findPage(TaskPlanEntity query, int pageNum, int pageSize, String orderBy) { throw unsupported(); }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by funded transaction fixture");
        }
    }
}
