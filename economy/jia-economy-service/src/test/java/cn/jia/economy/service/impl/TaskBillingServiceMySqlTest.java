package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.config.EconomySchemaInitializer;
import cn.jia.economy.config.EconomyPricingProperties;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskEstimateRule;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.mapper.EconomyTaskBillingMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBillingException;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.task.TaskBillingService.Binding;
import cn.jia.economy.task.TaskBillingService.Charge;
import cn.jia.economy.task.TaskBillingService.Quote;
import cn.jia.economy.task.TaskBillingService.State;
import cn.jia.economy.task.TaskBillingService.Task;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static cn.jia.economy.task.TaskBillingException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL, Spring mandatory transactions, production posting and task-root lock ownership. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class TaskBillingServiceMySqlTest {
    private static final EconomyScope SCOPE = new EconomyScope("Tenant-A", "Client-A");
    private static final Task TASK = new Task(SCOPE, "owner-1", "task-1");
    private static final Binding BINDING = new Binding(1, "requirement-sha", "input-sha", "agent-1", "recommendation-1");
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();
    private TaskBillingService billing;
    private TransactionTemplate tx;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        fixture.start();
        var db = fixture.newDatabase("taskbilling");
        jdbc = db.jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        // Models the caller's existing task row, not a product billing lock table.
        jdbc.execute("CREATE TABLE test_task_root (id VARCHAR(100) PRIMARY KEY, state VARCHAR(24) NOT NULL) ENGINE=InnoDB");
        jdbc.update("INSERT INTO test_task_root VALUES ('task-1','OPEN'),('task-2','OPEN')");
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_wen,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('wallet','USER','payer-1','AVAILABLE','WEN',100,0,'ACTIVE',0,'Tenant-A','Client-A',1,1)
                """);
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EconomyLedgerMapper.class);
        config.addMapper(EconomyTaskBillingMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(db.dataSource());
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        var manager = new DataSourceTransactionManager(db.dataSource());
        tx = new TransactionTemplate(manager);
        var ledger = session.getMapper(EconomyLedgerMapper.class);
        var gate = new EconomyPreviewGate(new EconomyPreviewProperties(true,
                List.of(new EconomyPreviewProperties.AllowedScope(SCOPE.tenantId(), SCOPE.clientId()))));
        var target = new TaskBillingServiceImpl(session.getMapper(EconomyTaskBillingMapper.class), ledger,
                new EconomyPostingServiceImpl(ledger, manager, gate), new ObjectMapper(),
                new ConfiguredTaskPricingService(new EconomyPricingProperties(
                        List.of("0", "0.004", "0.1", "0.3", "0.4", "0.5", "0.500001", "0.8", "999999999999999999999").stream().map(TaskBillingServiceMySqlTest::price).toList(),
                        List.of(new EconomyPricingProperties.ToolEntry("test-channel", "render", "image", "tool-v2", "synthetic-test-price", new BigDecimal("0.004"))),
                        List.of(new TaskEstimateRule("test-rule", "1", "synthetic-test-rule", "test", TaskWorkload.Complexity.STANDARD,
                                Map.of(ModelTokenPrice.Category.INPUT, 1_000_000L), Map.of(), Map.of())))));
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        billing = (TaskBillingService) proxy.getProxy();
    }

    @AfterEach
    void close() { fixture.close(); }

    @Test
    void requiresCallerTransactionEvenForZeroPrice() {
        assertThrows(IllegalTransactionStateException.class, () -> billing.capture(TASK));
        assertThrows(IllegalTransactionStateException.class, () -> billing.quote(TASK, BINDING, "payer-1", workload(), route("0")));
        assertEquals(0, count("economy_task_quote"));
    }

    @Test
    void freezesQuotePricePayerAndStructuredSnapshot() throws Exception {
        Quote first = quote(TASK, BINDING, "0.500001");
        Quote replay = inTask(TASK, () -> billing.quote(TASK, BINDING, "payer-1", null, null));
        assertEquals(first, replay);
        assertEquals(51, first.amountWen());
        var snapshot = new ObjectMapper().readTree(first.snapshotJson());
        assertEquals("0.500001", snapshot.get("estimatedYuan").asText());
        assertEquals("synthetic-test-rule", snapshot.get("rule").get("basis").asText());
        assertEquals(1_000_000L, snapshot.get("rule").get("baseTokens").get("INPUT").asLong());
        assertEquals("synthetic-test-price", snapshot.get("modelPrice").get("basis").asText());
        assertEquals(1L, snapshot.get("workload").get("deliverables").asLong());
        assertEquals(1, count("economy_task_quote"));
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.quote(TASK, BINDING, "other-payer", workload(), route("0"))));
        Binding next = new Binding(1, "requirement-sha", "input-sha", "agent-1", "recommendation-2");
        assertEquals(10, quote(TASK, next, "0.1").amountWen());
    }

    @Test
    void unknownPriceDoesNotPersistFreeQuoteOrTouchWallet() {
        var missing = assertThrows(TaskPricingService.Unavailable.class, () -> quote(TASK, BINDING, "unknown"));
        assertEquals(TaskPricingService.Unavailable.Reason.MODEL_PRICE_MISSING, missing.reason());
        assertEquals(0, count("economy_task_quote"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(100, wallet());
    }

    @Test
    void amountOverflowIsUnavailableWithoutPersistingQuote() {
        var overflow = assertThrows(TaskPricingService.Unavailable.class, () -> quote(TASK, BINDING, "999999999999999999999"));
        assertEquals(TaskPricingService.Unavailable.Reason.QUOTE_AMOUNT_OVERFLOW, overflow.reason());
        assertEquals(0, count("economy_task_quote"));
        assertEquals(100, wallet());
    }

    @Test
    void snapshotPreservesToolIdentityAndRoundsOnlyWholeTaskTotal() throws Exception {
        var tool = new TaskQuoteCalculator.ToolKey("test-channel", "render", "image");
        var workload = new TaskWorkload("test", TaskWorkload.Complexity.STANDARD, 0, 1, Map.of(tool, BigDecimal.ONE));
        Quote quote = inTask(TASK, () -> billing.quote(TASK, BINDING, "payer-1", workload, route("0.004")));
        assertEquals(1, quote.amountWen());
        var tree = new ObjectMapper().readTree(quote.snapshotJson());
        assertTrue(tree.get("tools").isArray());
        assertEquals("render", tree.get("tools").get(0).get("key").get("tool").asText());
        assertEquals("tool-v2", tree.get("tools").get(0).get("price").get("version").asText());
        assertEquals("0.008", tree.get("estimatedYuan").asText());
    }

    @Test
    void concurrentAcceptanceAcrossTasksSharesIncomeAccountWithoutLockUpgrade() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        reserve(TASK, quote(TASK, BINDING, "0.3"));
        reserve(other, quote(other, BINDING, "0.4"));
        var results = race(() -> inTask(TASK, () -> billing.capture(TASK)),
                () -> inTask(other, () -> billing.capture(other)));
        for (Object result : results) assertEquals(State.CAPTURED, assertInstanceOf(Charge.class, result).state());
        assertEquals(70L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(30, wallet());
        assertEquals(4, count("economy_transaction"));
    }

    @Test
    void rejectsChangedRequirementMaterialsCandidateRecommendationAndOtherOwner() {
        Quote quote = quote(TASK, BINDING, "0.5");
        for (Binding changed : List.of(new Binding(2, "requirement-sha", "input-sha", "agent-1", "recommendation-1"),
                new Binding(1, "other", "input-sha", "agent-1", "recommendation-1"),
                new Binding(1, "requirement-sha", "other", "agent-1", "recommendation-1"),
                new Binding(1, "requirement-sha", "input-sha", "other", "recommendation-1"),
                new Binding(1, "requirement-sha", "input-sha", "agent-1", "other"))) {
            assertReason(QUOTE_BINDING_CHANGED, () -> inTask(TASK, () -> billing.reserve(TASK, quote.quoteId(), changed)));
        }
        Task other = new Task(SCOPE, "other-owner", TASK.taskId());
        assertReason(QUOTE_NOT_FOUND, () -> inTask(other, () -> billing.reserve(other, quote.quoteId(), BINDING)));
        assertEquals(100, wallet());
        assertEquals(0, count("economy_task_charge"));
    }

    @Test
    void reserveAndAcceptAreFixedIdempotentAndSeparateFromModelCost() {
        Quote quote = quote(TASK, BINDING, "0.5");
        Charge held = reserve(TASK, quote);
        assertEquals(held, reserve(TASK, quote));
        assertEquals(50, wallet());
        Charge captured = inTask(TASK, () -> billing.capture(TASK));
        assertEquals(State.CAPTURED, captured.state());
        assertEquals(captured, inTask(TASK, () -> billing.capture(TASK)));
        assertEquals(50, wallet());
        assertEquals(50L, jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE purpose='TASK_FEE'", Long.class));
        assertEquals(List.of("RESERVE_BOUNTY", "CAPTURE_TASK"), jdbc.queryForList(
                "SELECT business_type FROM economy_transaction ORDER BY id", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE purpose='MODEL_COST'", Integer.class));
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(4, count("economy_entry"));
    }

    @Test
    void cancellationRefundsAllOnceAndCannotBeReReserved() {
        Quote quote = quote(TASK, BINDING, "0.8");
        reserve(TASK, quote);
        Charge released = inTask(TASK, () -> billing.release(TASK, true));
        assertEquals(State.RELEASED, released.state());
        assertEquals(released, inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(100, wallet());
        assertReason(CHARGE_CONFLICT, () -> inTask(TASK, () -> billing.capture(TASK)));
        assertReason(CHARGE_CONFLICT, () -> reserve(TASK, quote));
        assertEquals(2, count("economy_transaction"));
    }

    @Test
    void zeroQuoteStillHasLifecycleWithoutZeroJournals() {
        Quote quote = quote(TASK, BINDING, "0");
        assertEquals(State.HELD, reserve(TASK, quote).state());
        assertEquals(State.CAPTURED, inTask(TASK, () -> billing.capture(TASK)).state());
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        reserve(other, quote(other, BINDING, "0"));
        assertEquals(State.RELEASED, inTask(other, () -> billing.release(other, true)).state());
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_escrow"));
    }

    @Test
    void absentChargeIsNotLegacyFree() {
        assertReason(RESERVATION_REQUIRED, () -> inTask(TASK, () -> billing.capture(TASK)));
        assertReason(RESERVATION_REQUIRED, () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertNull(inTask(TASK, () -> billing.release(TASK, false)));
        jdbc.update("""
                INSERT INTO economy_task_charge(tenant_id,client_id,owner_jiacn,task_id,amount_wen,state,created_at,updated_at)
                VALUES('Tenant-A','Client-A','owner-1','task-1',0,'LEGACY_FREE',1,1)
                """);
        assertEquals(State.LEGACY_FREE, inTask(TASK, () -> billing.capture(TASK)).state());
        assertEquals(State.LEGACY_FREE, inTask(TASK, () -> billing.release(TASK, true)).state());
        assertEquals(0, count("economy_transaction"));
    }

    @Test
    void outerAssignmentFailureRollsBackChargeJournalAccountsAndBusinessTogether() {
        Quote quote = quote(TASK, BINDING, "0.5");
        assertThrows(IllegalStateException.class, () -> inTask(TASK, () -> {
            billing.reserve(TASK, quote.quoteId(), BINDING);
            jdbc.update("UPDATE test_task_root SET state='ASSIGNED' WHERE id='task-1'");
            throw new IllegalStateException("assignment persistence failed");
        }));
        assertEquals("OPEN", taskState());
        assertEquals(100, wallet());
        assertEquals(0, count("economy_task_charge"));
        assertEquals(0, count("economy_transaction"));
        assertEquals(0, count("economy_escrow_funding_lot"));
        assertEquals(1, count("economy_account"));
        assertEquals(State.HELD, reserve(TASK, quote).state());
    }

    @Test
    void outerAcceptanceOrCancellationFailureKeepsReservation() {
        reserve(TASK, quote(TASK, BINDING, "0.5"));
        for (boolean capture : List.of(true, false)) {
            assertThrows(IllegalStateException.class, () -> inTask(TASK, () -> {
                if (capture) billing.capture(TASK); else billing.release(TASK, true);
                jdbc.update("UPDATE test_task_root SET state='DONE' WHERE id='task-1'");
                throw new IllegalStateException("business transition failed");
            }));
            assertEquals("OPEN", taskState());
            assertEquals("HELD", jdbc.queryForObject("SELECT state FROM economy_task_charge", String.class));
            assertEquals(50, wallet());
            assertEquals(1, count("economy_transaction"));
        }
    }

    @Test
    void differentTasksCanCreateQuotesConcurrentlyWithoutMissingRowGapLocks() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        var results = race(() -> quote(TASK, BINDING, "0.3"), () -> quote(other, BINDING, "0.4"));
        for (Object result : results) assertInstanceOf(Quote.class, result, result::toString);
        assertEquals(2, count("economy_task_quote"));
        assertEquals(0, count("economy_task_charge"));
        assertEquals(100, wallet());
    }

    @Test
    void sameTaskConcurrentReservationReplaysOneCharge() throws Exception {
        Quote quote = quote(TASK, BINDING, "0.8");
        List<Object> results = race(() -> reserve(TASK, quote), () -> reserve(TASK, quote));
        assertInstanceOf(Charge.class, results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(20, wallet());
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void differentTasksCannotOverspendSharedWallet() throws Exception {
        Task other = new Task(SCOPE, TASK.ownerJiacn(), "task-2");
        Quote a = quote(TASK, BINDING, "0.8"), b = quote(other, BINDING, "0.8");
        List<Object> results = race(() -> reserve(TASK, a), () -> reserve(other, b));
        assertEquals(1, results.stream().filter(Charge.class::isInstance).count());
        Object error = results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        EconomyPostingException failure = assertInstanceOf(EconomyPostingException.class, error, error::toString);
        assertEquals(EconomyPostingException.Reason.INSUFFICIENT_FUNDS, failure.reason());
        assertEquals(20, wallet());
        assertEquals(1, count("economy_task_charge"));
        assertEquals(1, count("economy_transaction"));
    }

    @Test
    void acceptanceCancellationRaceHasOneTerminalJournal() throws Exception {
        reserve(TASK, quote(TASK, BINDING, "0.8"));
        List<Object> results = race(() -> inTask(TASK, () -> billing.capture(TASK)),
                () -> inTask(TASK, () -> billing.release(TASK, true)));
        assertEquals(1, results.stream().filter(Charge.class::isInstance).count());
        TaskBillingException conflict = (TaskBillingException) results.stream().filter(Throwable.class::isInstance).findFirst().orElseThrow();
        assertEquals(CHARGE_CONFLICT, conflict.reason());
        Charge terminal = (Charge) results.stream().filter(Charge.class::isInstance).findFirst().orElseThrow();
        assertEquals(terminal.state() == State.CAPTURED ? 20 : 100, wallet());
        assertEquals(2, count("economy_transaction"));
        assertEquals(4, count("economy_entry"));
    }

    private Quote quote(Task task, Binding binding, String yuan) {
        return inTask(task, () -> billing.quote(task, binding, "payer-1", workload(), route(yuan)));
    }
    private Charge reserve(Task task, Quote quote) {
        return inTask(task, () -> billing.reserve(task, quote.quoteId(), BINDING));
    }
    private <T> T inTask(Task task, Supplier<T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("SELECT state FROM test_task_root WHERE id=? FOR UPDATE", String.class, task.taskId());
            return work.get();
        });
    }
    private static TaskWorkload workload() {
        return new TaskWorkload("test", TaskWorkload.Complexity.STANDARD, 0, 1, Map.of());
    }
    private static TaskPricingService.ModelKey route(String yuan) {
        return new TaskPricingService.ModelKey("test-channel", "test-model-" + yuan, "standard");
    }
    private static ModelTokenPrice price(String yuan) {
        return new ModelTokenPrice("test-channel", "test-model-" + yuan, "standard", "1", "synthetic-test-price",
                Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal(yuan)));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private long wallet() { return jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE account_id='wallet'", Long.class); }
    private String taskState() { return jdbc.queryForObject("SELECT state FROM test_task_root WHERE id='task-1'", String.class); }
    private static void assertReason(TaskBillingException.Reason expected, Runnable work) {
        assertEquals(expected, assertThrows(TaskBillingException.class, work::run).reason());
    }
    private static List<Object> race(Callable<?> a, Callable<?> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try {
            var futures = List.of(a, b).stream().map(call -> pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start timeout");
                try { return call.call(); } catch (RuntimeException failure) { return (Object) failure; }
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
}
