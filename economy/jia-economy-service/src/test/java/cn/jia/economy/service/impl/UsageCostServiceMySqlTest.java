package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.EconomyUsageCostSchemaInitializer;
import cn.jia.economy.entity.EconomyTaskCostEntity;
import cn.jia.economy.mapper.EconomyUsageCostMapper;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import cn.jia.economy.usage.UsageCostService.Binding;
import cn.jia.economy.usage.UsageCostService.Observation;
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
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static cn.jia.economy.usage.TokenUsageCost.Status.*;
import static cn.jia.economy.usage.UsageCostConflict.Reason.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Real MySQL ledger/summary writes with Spring independent transaction semantics; no model calls. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class UsageCostServiceMySqlTest {
    private static final EconomyScope SCOPE = new EconomyScope("Tenant-A", "Client-A");
    private static final Binding BINDING = new Binding(SCOPE, "owner", "task", "fixture-provider", "attempt-1");
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();
    private UsageCostService service;
    private EconomyUsageCostMapper sql;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;

    @BeforeEach void setUp() throws Exception {
        fixture.start(); var db = fixture.newDatabase("usagecost"); jdbc = db.jdbc();
        var schema = new EconomyUsageCostSchemaInitializer(db.dataSource());
        schema.afterPropertiesSet(); schema.afterPropertiesSet();
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true); config.addMapper(EconomyUsageCostMapper.class);
        var factory = new SqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
        sql = new SqlSessionTemplate(factory.getObject()).getMapper(EconomyUsageCostMapper.class);
        manager = new DataSourceTransactionManager(db.dataSource()); service = proxied(sql);
    }
    @AfterEach void tearDown() { fixture.close(); }
    private UsageCostService proxied(EconomyUsageCostMapper mapper) {
        var proxy = new ProxyFactory(new UsageCostServiceImpl(mapper, new ObjectMapper()));
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (UsageCostService) proxy.getProxy();
    }
    private static ModelTokenPrice price(String version, String inputRate) {
        return new ModelTokenPrice("channel", "actual-model", "standard", version, "synthetic-cny-price",
                Map.of(INPUT, new BigDecimal(inputRate), OUTPUT, new BigDecimal("2")));
    }
    private static TokenUsageCost.Usage usage(long input) {
        return new TokenUsageCost.Usage("channel", "actual-model", "standard", "fixture-disjoint-v1", Map.of(INPUT, input));
    }
    private static Observation observation(Binding binding, String receipt, long revision, TokenUsageCost.Usage usage, ModelTokenPrice price) {
        return new Observation(binding, receipt, revision, "{\"provider\":\"synthetic-usage\"}", usage, price, 1234);
    }
    private Observation priced(Binding binding, String receipt, long revision, long tokens) {
        return observation(binding, receipt, revision, usage(tokens), price("v1", "1"));
    }
    private UsageCostService.Summary summary() { return service.summary(SCOPE, "owner", "task"); }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private void conflict(UsageCostConflict.Reason reason, Runnable action) {
        assertEquals(reason, assertThrows(UsageCostConflict.class, action::run).reason());
    }

    @Test void pendingEvidenceCanBePricedLaterWithoutPretendingMissingUsageIsFree() {
        var first = service.record(observation(BINDING, "started", 0, null, price("v1", "1")));
        assertEquals(PENDING_USAGE, first.status()); assertNull(first.costYuan());
        assertEquals(1, summary().pendingUsage()); assertEquals(new BigDecimal("0.000000"), summary().costYuan());
        var second = service.record(observation(BINDING, "usage-without-price", 1, usage(123456), null));
        assertEquals(PENDING_PRICE, second.status()); assertNull(second.costYuan());
        assertEquals(0, summary().pendingUsage()); assertEquals(1, summary().pendingPrice());
        var third = service.record(priced(BINDING, "price-verified", 2, 123456));
        assertEquals(new BigDecimal("0.123456"), third.costYuan());
        assertEquals(1, summary().attempts()); assertEquals(0, summary().pendingPrice());
        assertEquals(third.costYuan(), summary().costYuan()); assertEquals(3, count("economy_usage_revision"));
        assertNull(jdbc.queryForObject("SELECT cost_yuan FROM economy_usage_revision WHERE revision=1", BigDecimal.class));
        assertEquals("{\"provider\":\"synthetic-usage\"}", jdbc.queryForObject("SELECT raw_usage_json FROM economy_usage_revision WHERE revision=3", String.class));
    }

    @Test void nativeStartedAndTerminalReceiptsKeepOneUnknownAttemptAcrossLostAcks() {
        var binding = new Binding(SCOPE, "owner", "task", "NATIVE_EXECUTION_EVIDENCE", "exec");
        var started = new Observation(binding, "started", 0, "{\"phase\":\"STARTED\",\"usageEvidence\":null}", null, null, 0);
        var terminal = new Observation(binding, "terminal", 1, "{\"phase\":\"TERMINAL\",\"usageEvidence\":null}", null, null, 0);
        conflict(REVISION, () -> service.record(terminal));
        assertEquals(0, summary().attempts());
        var first = service.record(started);
        assertEquals(1, first.revision()); assertNull(first.costYuan());
        assertTrue(service.record(started).replay());
        var last = service.record(terminal);
        assertEquals(first.attemptId(), last.attemptId()); assertEquals(2, last.revision());
        assertTrue(service.record(terminal).replay());
        assertEquals(1, service.record(started).revision());
        assertEquals(1, summary().attempts()); assertEquals(1, summary().pendingUsage());
        assertEquals(PENDING_USAGE, last.status()); assertNull(last.costYuan());
        assertEquals(2, count("economy_usage_revision"));
    }

    @Test void duplicateAndOldReceiptReplayNeverRepriceOrDoubleCount() {
        var initial = priced(BINDING, "receipt-1", 0, 10);
        var original = service.record(initial); assertTrue(service.record(initial).replay());
        var revised = observation(BINDING, "verified-correction", 1, usage(10), price("v2", "2"));
        assertEquals(new BigDecimal("0.000020"), service.record(revised).costYuan());
        var replay = service.record(initial);
        assertTrue(replay.replay()); assertEquals(original.costYuan(), replay.costYuan()); assertEquals(1, replay.revision());
        assertEquals(new BigDecimal("0.000020"), summary().costYuan()); assertEquals(2, count("economy_usage_revision"));
        var json = new ObjectMapper().readTree(jdbc.queryForObject("SELECT price_snapshot_json FROM economy_usage_revision WHERE revision=1", String.class));
        assertEquals("v1", json.get("version").asText());
        conflict(RECEIPT_CONTENT, () -> service.record(priced(BINDING, "receipt-1", 0, 20)));
        conflict(REVISION, () -> service.record(priced(BINDING, "stale-new-receipt", 0, 10)));
        assertEquals(2, count("economy_usage_revision"));
    }

    @Test void actualRetryIsAnotherAttemptButCannotRebindTheSameAttemptToAnotherTaskOrOwner() {
        service.record(priced(BINDING, "receipt", 0, 10));
        var retry = new Binding(SCOPE, "owner", "task", "fixture-provider", "attempt-2");
        service.record(priced(retry, "receipt", 0, 10));
        assertEquals(2, summary().attempts()); assertEquals(new BigDecimal("0.000020"), summary().costYuan());
        for (var binding : new Binding[]{new Binding(SCOPE, "owner", "other-task", "fixture-provider", "attempt-1"),
                new Binding(SCOPE, "other-owner", "task", "fixture-provider", "attempt-1")})
            conflict(ATTEMPT_BINDING, () -> service.record(priced(binding, "new-receipt", 1, 10)));
        assertEquals(1, count("economy_task_cost"));
    }

    @Test void routeMismatchAndMissingCategoryStayPendingAndRawSnapshotRemainsAuditable() {
        var mismatch = new TokenUsageCost.Usage("other-channel", "actual-model", "standard", "fixture", Map.of(INPUT, 10L));
        assertEquals(PENDING_PRICE, service.record(observation(BINDING, "mismatch", 0, mismatch, price("v1", "1"))).status());
        var cached = new TokenUsageCost.Usage("channel", "actual-model", "standard", "fixture", Map.of(CACHED_INPUT, 10L));
        assertEquals(PENDING_PRICE, service.record(observation(BINDING, "incomplete", 1, cached, price("v1", "1"))).status());
        assertEquals(1, summary().pendingPrice()); assertEquals(new BigDecimal("0.000000"), summary().costYuan());
        assertNotNull(jdbc.queryForObject("SELECT price_snapshot_json FROM economy_usage_revision WHERE revision=1", String.class));
    }

    @Test void correctionCanWithdrawUnverifiedCostWithoutErasingEarlierPricedHistory() {
        service.record(priced(BINDING, "first", 0, 25));
        service.record(observation(BINDING, "withdraw-uncertain-usage", 1, null, price("v1", "1")));
        assertEquals(1, summary().pendingUsage()); assertEquals(new BigDecimal("0.000000"), summary().costYuan());
        assertEquals(new BigDecimal("0.000025"), jdbc.queryForObject("SELECT cost_yuan FROM economy_usage_revision WHERE revision=1", BigDecimal.class));
    }

    @Test void scopeIsolationIsExactAndDoesNotDependOnHttpOrTaskLifecycleTables() {
        for (var scope : new EconomyScope[]{SCOPE, new EconomyScope("Tenant-B", "Client-A"), new EconomyScope("Tenant-A", "Client-B")}) {
            var binding = new Binding(scope, "owner", "task", "fixture-provider", "attempt-1");
            service.record(priced(binding, "receipt", 0, 10));
            assertEquals(1, service.summary(scope, "owner", "task").attempts());
        }
        assertEquals(3, count("economy_usage_attempt"));
        assertEquals(0, service.summary(SCOPE, "other-owner", "task").attempts());
        // There is deliberately no wallet or business task table in this fixture.
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()", Integer.class));
    }

    @Test void finalSummaryWriteFailureRollsBackAttemptAndRevisionTogether() {
        var failing = mock(EconomyUsageCostMapper.class, delegatesTo(sql));
        doThrow(new IllegalStateException("fixture-summary-write-failed")).when(failing).updateSummary(any(EconomyTaskCostEntity.class));
        assertThrows(IllegalStateException.class, () -> proxied(failing).record(priced(BINDING, "receipt", 0, 10)));
        assertEquals(0, count("economy_usage_attempt")); assertEquals(0, count("economy_usage_revision")); assertEquals(0, count("economy_task_cost"));
        service.record(priced(BINDING, "receipt", 0, 10));
        assertThrows(IllegalStateException.class, () -> proxied(failing).record(priced(BINDING, "revision", 1, 20)));
        assertEquals(1, count("economy_usage_revision")); assertEquals(new BigDecimal("0.000010"), summary().costYuan());
    }

    @Test void independentCostTransactionNeitherRollsBackWithBusinessNorPoisonsItOnCostConflict() {
        jdbc.execute("CREATE TABLE fixture_business(id INT PRIMARY KEY) ENGINE=InnoDB");
        var tx = new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            jdbc.update("INSERT INTO fixture_business VALUES(1)"); service.record(priced(BINDING, "receipt", 0, 10));
            throw new IllegalStateException("fixture-business-rollback");
        }));
        assertEquals(0, count("fixture_business")); assertEquals(1, summary().attempts());
        tx.execute(status -> {
            jdbc.update("INSERT INTO fixture_business VALUES(2)");
            conflict(RECEIPT_CONTENT, () -> service.record(priced(BINDING, "receipt", 0, 20)));
            return null;
        });
        assertEquals(1, count("fixture_business")); assertEquals(new BigDecimal("0.000010"), summary().costYuan());
    }

    @Test void concurrentDuplicateReceiptsProduceOneRevisionAndOneSummaryIncrement() throws Exception {
        var observation = priced(BINDING, "receipt", 0, 10);
        var results = race(() -> service.record(observation), () -> service.record(observation));
        assertNotEquals(results.get(0).replay(), results.get(1).replay());
        assertEquals(1, count("economy_usage_revision")); assertEquals(1, summary().attempts());
        assertEquals(new BigDecimal("0.000010"), summary().costYuan());
    }

    @Test void concurrentDifferentAttemptsDoNotLoseAggregateUpdates() throws Exception {
        var second = new Binding(SCOPE, "owner", "task", "fixture-provider", "attempt-2");
        race(() -> service.record(priced(BINDING, "first", 0, 10)), () -> service.record(priced(second, "second", 0, 20)));
        assertEquals(2, summary().attempts()); assertEquals(new BigDecimal("0.000030"), summary().costYuan());
    }

    @Test void replayHashIsIndependentOfNormalizedMapInsertionOrder() {
        var tokens = new LinkedHashMap<ModelTokenPrice.Category, Long>(); tokens.put(OUTPUT, 2L); tokens.put(INPUT, 1L);
        var first = new TokenUsageCost.Usage("channel", "actual-model", "standard", "fixture", tokens);
        var reordered = new LinkedHashMap<ModelTokenPrice.Category, Long>(); reordered.put(INPUT, 1L); reordered.put(OUTPUT, 2L);
        var second = new TokenUsageCost.Usage("channel", "actual-model", "standard", "fixture", reordered);
        service.record(observation(BINDING, "receipt", 0, first, price("v1", "1")));
        assertTrue(service.record(observation(BINDING, "receipt", 0, second, price("v1", "1"))).replay());
        assertEquals(new BigDecimal("0.000005"), summary().costYuan());
    }

    private static <T> java.util.List<T> race(Callable<T> left, Callable<T> right) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return left.call(); });
            var b = pool.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return right.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            return java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
