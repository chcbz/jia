package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyConstants;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import cn.jia.economy.entity.EconomyHostingRentPlanEntity;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.hosting.HostingRentException;
import cn.jia.economy.hosting.HostingRentMutationReceipt;
import cn.jia.economy.hosting.HostingRentOutcomeCommand;
import cn.jia.economy.hosting.HostingRentQuoteCommand;
import cn.jia.economy.hosting.HostingRentQuotePurpose;
import cn.jia.economy.hosting.HostingRentQuoteReceipt;
import cn.jia.economy.hosting.HostingRentReserveCommand;
import cn.jia.economy.hosting.HostingRentSettlementCommand;
import cn.jia.economy.mapper.EconomyHostingRentMapper;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class HostingRentLedgerServiceRealTransactionTest {
    private static final String TENANT = "Tenant-Rent";
    private static final String CLIENT = "Client-Rent";
    private static final String USER = "jwt-sub-user";
    private static final long V1_AMOUNT_MICRO = 1_000_000_000L;
    private static final long V1_PERIOD_SECONDS = 2_592_000L;
    private static final long WALLET_BALANCE_MICRO = 2_000_000_000L;
    private static final byte[] HASH_QUOTE = hash(1);
    private static final byte[] HASH_RESERVE = hash(2);
    private static final byte[] HASH_CAPTURE = hash(3);
    private static final byte[] HASH_REFUND = hash(4);

    private JdbcTemplate jdbc;
    private EconomyLedgerMapper ledgerMapper;
    private EconomyHostingRentMapper hostingMapper;
    private HostingRentLedgerServiceImpl service;
    private AtomicLong clock;
    private AtomicInteger quoteIds;
    private AtomicInteger leaseIds;
    private AtomicInteger intentIds;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:eco_v0_r01;MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        clock = new AtomicLong(1_800_000_000_000L);
        createTables();

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EconomyLedgerMapper.class);
        configuration.addMapper(EconomyHostingRentMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        ledgerMapper = template.getMapper(EconomyLedgerMapper.class);
        hostingMapper = spy(template.getMapper(EconomyHostingRentMapper.class));

        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT))));
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        AtomicInteger transactionIds = new AtomicInteger();
        EconomyPostingServiceImpl posting = new EconomyPostingServiceImpl(
                ledgerMapper, transactionManager, gate,
                () -> "etx-rent-" + transactionIds.incrementAndGet(),
                () -> "esc-rent-" + transactionIds.get(), this::tick);
        quoteIds = new AtomicInteger();
        leaseIds = new AtomicInteger();
        intentIds = new AtomicInteger();
        service = new HostingRentLedgerServiceImpl(hostingMapper, ledgerMapper, posting, transactionManager,
                () -> "hrq-test-" + quoteIds.incrementAndGet(),
                () -> "hrl-test-" + leaseIds.incrementAndGet(),
                () -> "hri-test-" + intentIds.incrementAndGet(), this::tick);
        seedPlan("plan-test", 1, V1_AMOUNT_MICRO, V1_PERIOD_SECONDS, 300);
        insertAccount("wallet-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, WALLET_BALANCE_MICRO);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void reserveCaptureAndAllReplaysRemainOriginalReceiptsAndBalanced() {
        HostingRentQuoteReceipt quote = service.quote(initialQuote("agent-1", "persona-1", "plan-test", 1));
        HostingRentQuoteReceipt quoteReplay = service.quote(initialQuote("agent-1", "persona-1", "plan-test", 1));
        assertEquals(quote, quoteReplay);
        assertEquals(1L, quote.planVersion());
        assertEquals(V1_AMOUNT_MICRO, quote.amountMicro());
        assertEquals(V1_PERIOD_SECONDS, quote.periodSeconds());

        HostingRentReserveCommand reserveCommand = reserve(quote.quoteId(), "00000000-0000-0000-0000-000000000002");
        HostingRentMutationReceipt reserved = service.reserve(reserveCommand);
        service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "ready-agent-1"));
        HostingRentSettlementCommand capture = settlement(reserved.intentId(), 2,
                "00000000-0000-0000-0000-000000000003", HASH_CAPTURE);
        HostingRentMutationReceipt captured = service.capture(capture);

        assertEquals(reserved, service.reserve(reserveCommand));
        assertEquals(captured, service.capture(capture));
        assertEquals("FUNDS_RESERVED", reserved.status());
        assertEquals("ACTIVE", captured.status());
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
        assertEquals(0L, balanceLike("hosting_esc_%"));
        assertEquals(V1_AMOUNT_MICRO, balance("system_hosting_rent"));
        assertEquals(V1_AMOUNT_MICRO, jdbc.queryForObject("SELECT captured_micro FROM economy_escrow", Long.class));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM economy_hosting_lease", String.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM economy_hosting_provisioning_intent", String.class));
        for (String table : List.of("economy_hosting_rent_plan", "economy_hosting_rent_quote",
                "economy_hosting_lease", "economy_hosting_provisioning_intent")) {
            assertEquals(V1_AMOUNT_MICRO, jdbc.queryForObject(
                    "SELECT amount_micro FROM " + table + " LIMIT 1", Long.class), table);
            assertEquals(V1_PERIOD_SECONDS, jdbc.queryForObject(
                    "SELECT period_seconds FROM " + table + " LIMIT 1", Long.class), table);
        }
        assertEquals(2, count("economy_transaction"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
        assertTrue(jdbc.queryForObject("SELECT paid_through>paid_from FROM economy_hosting_lease", Boolean.class));
    }

    @Test
    void unknownOutcomeCannotCaptureOrRefundUntilExplicitNoEffectThenRefundsExactlyOnce() {
        HostingRentMutationReceipt reserved = service.reserve(reserve(
                service.quote(initialQuote("agent-2", "persona-2", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000012"));
        service.markProvisioningUnknown(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "probe-unknown-1"));

        HostingRentException captureFailure = assertThrows(HostingRentException.class,
                () -> service.capture(settlement(reserved.intentId(), 2,
                        "00000000-0000-0000-0000-000000000013", HASH_CAPTURE)));
        assertEquals(HostingRentException.Reason.PROVISIONING_OUTCOME_UNKNOWN, captureFailure.reason());
        HostingRentException refundFailure = assertThrows(HostingRentException.class,
                () -> service.refund(settlement(reserved.intentId(), 2,
                        "00000000-0000-0000-0000-000000000014", HASH_REFUND)));
        assertEquals(HostingRentException.Reason.REFUND_NOT_ALLOWED, refundFailure.reason());
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
        assertEquals(1, count("economy_transaction"));

        service.confirmProvisioningFailedNoEffect(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 2, "probe-no-effect-1"));
        HostingRentSettlementCommand refund = settlement(reserved.intentId(), 3,
                "00000000-0000-0000-0000-000000000014", HASH_REFUND);
        HostingRentMutationReceipt refunded = service.refund(refund);
        assertEquals(refunded, service.refund(refund));
        assertEquals("REFUNDED", refunded.status());
        assertEquals(WALLET_BALANCE_MICRO, balance("wallet-user"));
        assertEquals(0L, balanceLike("hosting_esc_%"));
        assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM economy_hosting_lease", String.class));
        assertEquals(2, count("economy_transaction"));
    }

    @Test
    void crossOwnerAndExpiredQuoteFailClosedWithoutPaidArtifacts() {
        HostingRentQuoteReceipt quote = service.quote(initialQuote(
                "agent-guarded", "persona-guarded", "plan-test", 1));
        HostingRentReserveCommand crossOwner = new HostingRentReserveCommand(
                scope(), new EconomyPrincipal(EconomyPrincipalType.USER, "other-jwt-sub"),
                "00000000-0000-0000-0000-000000000018", HASH_RESERVE, quote.quoteId());

        HostingRentException forbidden = assertThrows(HostingRentException.class,
                () -> service.reserve(crossOwner));
        assertEquals(HostingRentException.Reason.NOT_FOUND_OR_FORBIDDEN, forbidden.reason());
        assertNoPaidArtifacts();

        clock.set(quote.expiresAt());
        HostingRentException expired = assertThrows(HostingRentException.class,
                () -> service.reserve(reserve(quote.quoteId(),
                        "00000000-0000-0000-0000-000000000019")));
        assertEquals(HostingRentException.Reason.QUOTE_EXPIRED, expired.reason());
        assertNoPaidArtifacts();
        assertEquals(WALLET_BALANCE_MICRO, balance("wallet-user"));
    }

    @Test
    void insufficientFundsAndLateIntentFailureRollbackLeaseIntentEscrowAccountsAndJournal() {
        seedPlan("plan-expensive", 1, WALLET_BALANCE_MICRO + 1L, V1_PERIOD_SECONDS, 300);
        HostingRentQuoteReceipt expensive = service.quote(initialQuote(
                "agent-expensive", "persona-expensive", "plan-expensive", 1));
        EconomyPostingException insufficient = assertThrows(EconomyPostingException.class,
                () -> service.reserve(reserve(expensive.quoteId(),
                        "00000000-0000-0000-0000-000000000022")));
        assertEquals(EconomyPostingException.Reason.INSUFFICIENT_FUNDS, insufficient.reason());
        assertNoPaidArtifacts();

        HostingRentQuoteReceipt affordable = service.quote(initialQuote(
                "agent-late", "persona-late", "plan-test", 1,
                "00000000-0000-0000-0000-000000000021", hash(21)));
        doThrow(new IllegalStateException("late intent failure")).when(hostingMapper).insertIntent(any());
        assertThrows(IllegalStateException.class, () -> service.reserve(reserve(affordable.quoteId(),
                "00000000-0000-0000-0000-000000000023")));
        assertNoPaidArtifacts();
        assertEquals(WALLET_BALANCE_MICRO, balance("wallet-user"));
    }

    @Test
    void concurrentSameReserveReturnsOneImmutableReceiptAndDifferentBodyCannotConsumeQuote() throws Exception {
        HostingRentQuoteReceipt quote = service.quote(initialQuote("agent-race", "persona-race", "plan-test", 1));
        HostingRentReserveCommand command = reserve(quote.quoteId(),
                "00000000-0000-0000-0000-000000000032");
        List<Outcome> same = race(() -> reserveOutcome(command), () -> reserveOutcome(command));
        assertTrue(same.stream().allMatch(Outcome::succeeded), same.toString());
        assertEquals(same.get(0).receipt(), same.get(1).receipt());
        assertEquals(1, count("economy_transaction"));
        assertEquals(1, count("economy_hosting_lease"));
        assertEquals(1, count("economy_hosting_provisioning_intent"));

        HostingRentException conflict = assertThrows(HostingRentException.class,
                () -> service.reserve(new HostingRentReserveCommand(scope(), principal(),
                        "00000000-0000-0000-0000-000000000033", hash(33), quote.quoteId())));
        assertEquals(HostingRentException.Reason.QUOTE_ALREADY_CONSUMED, conflict.reason());
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
    }

    @Test
    void quoteIdentityAndRenewalIntentAreExactAndCannotBeInferredFromEmptyInitialState() {
        HostingRentQuoteReceipt initial = service.quote(initialQuote("Agent-A", "Persona-A", "plan-test", 1));
        HostingRentException conflict = assertThrows(HostingRentException.class,
                () -> service.quote(initialQuote("agent-a", "Persona-A", "plan-test", 1)));
        assertEquals(HostingRentException.Reason.IDEMPOTENCY_CONFLICT, conflict.reason());
        assertNotEquals("agent-a", initial.agentId());

        HostingRentException invalidRenewal = assertThrows(HostingRentException.class,
                () -> service.quote(new HostingRentQuoteCommand(scope(), principal(),
                        "00000000-0000-0000-0000-000000000041", hash(41),
                        HostingRentQuotePurpose.RENEWAL, "plan-test", 1,
                        "Persona-A", "Agent-A", null, null)));
        assertEquals(HostingRentException.Reason.INVALID_COMMAND, invalidRenewal.reason());
    }

    @Test
    void durableRunnerReadyTimeSurvivesDelayedReconciliationAndCannotBeRewritten() {
        var reserved = service.reserve(reserve(service.quote(initialQuote("ready-time-agent", "ready-time-persona", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000091"));
        service.markProvisioningUnknown(new HostingRentOutcomeCommand(scope(), principal(), reserved.intentId(), 1, "attempt"));
        long readyAt = clock.getAndAdd(10000L);
        assertEquals(HostingRentException.Reason.INVALID_COMMAND, assertThrows(HostingRentException.class,
                () -> service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(), reserved.intentId(), 2,
                        "ready-time", clock.get() + 100000L))).reason());
        var proof = new HostingRentOutcomeCommand(scope(), principal(), reserved.intentId(), 2, "ready-time", readyAt);
        service.confirmProvisioningSucceeded(proof);
        service.capture(settlement(reserved.intentId(), 3, "00000000-0000-0000-0000-000000000092", HASH_CAPTURE));
        service.confirmProvisioningSucceeded(proof);
        assertEquals(readyAt, jdbc.queryForObject("SELECT service_ready_at FROM economy_hosting_provisioning_intent", Long.class));
        assertEquals(readyAt, jdbc.queryForObject("SELECT paid_from FROM economy_hosting_lease", Long.class));
        assertEquals(readyAt + V1_PERIOD_SECONDS * 1000, jdbc.queryForObject("SELECT paid_through FROM economy_hosting_lease", Long.class));
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(), reserved.intentId(), 2,
                        "ready-time", readyAt + 1))).reason());
        assertEquals(2, count("economy_transaction"));
    }

    @Test
    void unknownSuccessRequiresTrustedProofAndKeepsEscrowUntilExactlyOneCapture() throws Exception {
        HostingRentMutationReceipt reserved = service.reserve(reserve(
                service.quote(initialQuote("agent-ready", "persona-ready", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000052"));
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.capture(settlement(reserved.intentId(), 1,
                        "00000000-0000-0000-0000-000000000053", HASH_CAPTURE))).reason());
        service.markProvisioningUnknown(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "unknown-ready"));
        HostingRentOutcomeCommand proof = new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 2, "trusted-readiness-agent-ready");
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(1, count("economy_transaction"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM economy_hosting_provisioning_intent WHERE service_ready_at IS NOT NULL
                """, Integer.class));

        for (HostingRentOutcomeCommand foreign : List.of(
                new HostingRentOutcomeCommand(new EconomyScope("tenant-rent", CLIENT), principal(),
                        reserved.intentId(), 2, proof.evidenceRef()),
                new HostingRentOutcomeCommand(new EconomyScope(TENANT, "client-rent"), principal(),
                        reserved.intentId(), 2, proof.evidenceRef()),
                new HostingRentOutcomeCommand(scope(), new EconomyPrincipal(EconomyPrincipalType.USER,
                        "JWT-sub-user"), reserved.intentId(), 2, proof.evidenceRef()))) {
            assertEquals(HostingRentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                    assertThrows(HostingRentException.class,
                            () -> service.confirmProvisioningSucceeded(foreign)).reason());
        }
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(),
                        reserved.intentId(), 1, proof.evidenceRef()))).reason());

        List<Outcome> reports = race(() -> {
            service.confirmProvisioningSucceeded(proof);
            return new Outcome(reserved, null);
        }, () -> {
            service.confirmProvisioningSucceeded(proof);
            return new Outcome(reserved, null);
        });
        assertTrue(reports.stream().allMatch(Outcome::succeeded));
        long readyAt = jdbc.queryForObject(
                "SELECT service_ready_at FROM economy_hosting_provisioning_intent", Long.class);
        assertEquals(3L, jdbc.queryForObject("SELECT version FROM economy_hosting_provisioning_intent", Long.class));
        assertEquals("SERVICE_READY", jdbc.queryForObject(
                "SELECT status FROM economy_hosting_provisioning_intent", String.class));
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(1, count("economy_transaction"));
        assertEquals(HostingRentException.Reason.REFUND_NOT_ALLOWED, assertThrows(HostingRentException.class,
                () -> service.refund(settlement(reserved.intentId(), 3,
                        "00000000-0000-0000-0000-000000000054", HASH_REFUND))).reason());
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(),
                        reserved.intentId(), 2, "different-proof"))).reason());

        clock.addAndGet(10_000L); // Capture may lag proof; the paid period must not move.
        HostingRentSettlementCommand capture = settlement(reserved.intentId(), 3,
                "00000000-0000-0000-0000-000000000053", HASH_CAPTURE);
        List<Outcome> captures = race(() -> new Outcome(service.capture(capture), null),
                () -> new Outcome(service.capture(capture), null));
        assertEquals(captures.get(0).receipt(), captures.get(1).receipt());
        service.confirmProvisioningSucceeded(proof); // Callback replay also works after capture.
        assertEquals(captures.get(0).receipt(), service.capture(capture));
        assertEquals(readyAt, jdbc.queryForObject("SELECT paid_from FROM economy_hosting_lease", Long.class));
        assertEquals(readyAt + V1_PERIOD_SECONDS * 1_000L,
                jdbc.queryForObject("SELECT paid_through FROM economy_hosting_lease", Long.class));
        assertEquals(readyAt, jdbc.queryForObject(
                "SELECT service_ready_at FROM economy_hosting_provisioning_intent", Long.class));
        assertEquals(2, count("economy_transaction"));
        assertEquals(V1_AMOUNT_MICRO, balance("system_hosting_rent"));
        assertEquals(0L, balanceLike("hosting_esc_%"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
    }

    @Test
    void refundedAgentCanReserveFreshIntentWithoutChangingHistoryOrAcceptingOldCallbacks() {
        HostingRentQuoteReceipt firstQuote = service.quote(initialQuote("agent-retry", "persona-retry", "plan-test", 1));
        HostingRentReserveCommand originalReserve = reserve(firstQuote.quoteId(),
                "00000000-0000-0000-0000-000000000062");
        HostingRentMutationReceipt first = service.reserve(originalReserve);
        service.confirmProvisioningFailedNoEffect(new HostingRentOutcomeCommand(scope(), principal(),
                first.intentId(), 1, "confirmed-no-effect"));
        HostingRentSettlementCommand originalRefund = settlement(first.intentId(), 2,
                "00000000-0000-0000-0000-000000000064", HASH_REFUND);
        HostingRentMutationReceipt refunded = service.refund(originalRefund);
        Map<String, Object> oldLease = leaseRow(first.leaseId());
        Map<String, Object> oldIntent = intentRow(first.intentId());
        assertEquals(WALLET_BALANCE_MICRO, balance("wallet-user"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM economy_hosting_lease WHERE live_slot IS NOT NULL", Integer.class));

        HostingRentQuoteReceipt retryQuote = service.quote(initialQuote(
                "agent-retry", "persona-retry", "plan-test", 1,
                "00000000-0000-0000-0000-000000000065", hash(65)));
        HostingRentReserveCommand retryReserve = reserve(retryQuote.quoteId(),
                "00000000-0000-0000-0000-000000000066");
        HostingRentMutationReceipt retry = service.reserve(retryReserve);
        assertNotEquals(first.intentId(), retry.intentId());
        assertNotEquals(first.leaseId(), retry.leaseId());
        assertNotEquals(first.quoteId(), retry.quoteId());
        Map<String, Object> newLease = leaseRow(retry.leaseId());
        Map<String, Object> newIntent = intentRow(retry.intentId());

        assertEquals(first, service.reserve(originalReserve));
        assertEquals(refunded, service.refund(originalRefund));
        assertEquals(retry, service.reserve(retryReserve));
        HostingRentOutcomeCommand late = new HostingRentOutcomeCommand(scope(), principal(),
                first.intentId(), 3, "stale-old-intent");
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT,
                assertThrows(HostingRentException.class, () -> service.confirmProvisioningSucceeded(late)).reason());
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT,
                assertThrows(HostingRentException.class, () -> service.markProvisioningUnknown(late)).reason());
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT,
                assertThrows(HostingRentException.class, () -> service.confirmProvisioningFailedNoEffect(late)).reason());
        assertEquals(HostingRentException.Reason.INTENT_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.capture(settlement(first.intentId(), 3,
                        "00000000-0000-0000-0000-000000000067", HASH_CAPTURE))).reason());

        HostingRentQuoteReceipt thirdQuote = service.quote(initialQuote(
                "agent-retry", "persona-retry", "plan-test", 1,
                "00000000-0000-0000-0000-000000000068", hash(68)));
        assertEquals(HostingRentException.Reason.LEASE_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.reserve(reserve(thirdQuote.quoteId(),
                        "00000000-0000-0000-0000-000000000069"))).reason());
        assertEquals(oldLease, leaseRow(first.leaseId()));
        assertEquals(oldIntent, intentRow(first.intentId()));
        assertEquals(newLease, leaseRow(retry.leaseId()));
        assertEquals(newIntent, intentRow(retry.intentId()));
        assertEquals(2, count("economy_hosting_lease"));
        assertEquals(2, count("economy_hosting_provisioning_intent"));
        assertEquals(2, count("economy_escrow"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM economy_hosting_lease WHERE live_slot=1", Integer.class));
        assertEquals(3, count("economy_transaction"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
    }

    @Test
    void differentQuotesCompetingForSameAgentLeaveOnlyOneLiveLeaseAndPosting() throws Exception {
        HostingRentQuoteReceipt one = service.quote(initialQuote("agent-slot", "persona-slot", "plan-test", 1));
        HostingRentQuoteReceipt two = service.quote(initialQuote("agent-slot", "persona-slot", "plan-test", 1,
                "00000000-0000-0000-0000-000000000071", hash(71)));
        List<Outcome> results = race(
                () -> reserveOutcome(reserve(one.quoteId(), "00000000-0000-0000-0000-000000000072")),
                () -> reserveOutcome(reserve(two.quoteId(), "00000000-0000-0000-0000-000000000073")));
        assertEquals(1, results.stream().filter(Outcome::succeeded).count(), results.toString());
        assertEquals(HostingRentException.Reason.LEASE_CONFLICT,
                results.stream().filter(result -> !result.succeeded()).findFirst().orElseThrow().reason());
        assertEquals(1, count("economy_hosting_lease"));
        assertEquals(1, count("economy_hosting_provisioning_intent"));
        assertEquals(1, count("economy_escrow"));
        assertEquals(1, count("economy_transaction"));
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
    }

    @Test
    void lateCaptureFailureRollsBackJournalAndKeepsDurableReadyProofAndEscrow() {
        HostingRentMutationReceipt reserved = service.reserve(reserve(
                service.quote(initialQuote("agent-capture-rollback", "persona-rollback", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000082"));
        service.markProvisioningUnknown(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "unknown-before-ready"));
        service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 2, "durable-ready-before-capture"));
        Map<String, Object> before = intentRow(reserved.intentId());
        doThrow(new IllegalStateException("late capture projection failure")).when(hostingMapper)
                .markLeaseActive(any(), anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> service.capture(settlement(reserved.intentId(), 3,
                "00000000-0000-0000-0000-000000000083", HASH_CAPTURE)));
        assertEquals(before, intentRow(reserved.intentId()));
        assertEquals("PROVISIONING", leaseRow(reserved.leaseId()).get("STATUS"));
        assertEquals(1, count("economy_transaction"));
        assertEquals(0L, jdbc.queryForObject("SELECT captured_micro FROM economy_escrow", Long.class));
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
    }

    @Test
    void lateRefundFailureCannotReleaseLiveSlotOrCreateRetryArtifacts() {
        HostingRentMutationReceipt reserved = service.reserve(reserve(
                service.quote(initialQuote("agent-refund-rollback", "persona-rollback", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000092"));
        service.confirmProvisioningFailedNoEffect(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "durable-no-effect-before-refund"));
        Map<String, Object> before = intentRow(reserved.intentId());
        doThrow(new IllegalStateException("late refund projection failure")).when(hostingMapper)
                .markLeaseRefunded(any(), anyString(), anyString(), anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> service.refund(settlement(reserved.intentId(), 2,
                "00000000-0000-0000-0000-000000000094", HASH_REFUND)));
        assertEquals(before, intentRow(reserved.intentId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM economy_hosting_lease WHERE live_slot=1 AND status='PROVISIONING'", Integer.class));
        HostingRentQuoteReceipt retry = service.quote(initialQuote(
                "agent-refund-rollback", "persona-rollback", "plan-test", 1,
                "00000000-0000-0000-0000-000000000095", hash(95)));
        assertEquals(HostingRentException.Reason.LEASE_CONFLICT, assertThrows(HostingRentException.class,
                () -> service.reserve(reserve(retry.quoteId(),
                        "00000000-0000-0000-0000-000000000096"))).reason());
        assertEquals(1, count("economy_transaction"));
        assertEquals(1, count("economy_hosting_lease"));
        assertEquals(1, count("economy_hosting_provisioning_intent"));
        assertEquals(0L, jdbc.queryForObject("SELECT refunded_micro FROM economy_escrow", Long.class));
        assertEquals(V1_AMOUNT_MICRO, balanceLike("hosting_esc_%"));
        assertEquals(WALLET_BALANCE_MICRO - V1_AMOUNT_MICRO, balance("wallet-user"));
    }

    @Test
    void explicitManualRenewalKeepsHistoricalPaidPeriodsAndUsesMaxNowPaidThrough() {
        jdbc.update("UPDATE economy_account SET balance_micro=? WHERE account_id='wallet-user'", 4 * V1_AMOUNT_MICRO);
        HostingRentMutationReceipt first = activeLease("agent-renew");
        Map<String, Object> originalPeriod = intentRow(first.intentId());
        long originalThrough = jdbc.queryForObject("SELECT paid_through FROM economy_hosting_lease", Long.class);
        long heldVersion = ledgerMapper.selectUserWalletSnapshot(TENANT, CLIENT, USER).getVersion();
        HostingRentQuoteReceipt quote = service.quote(renewalQuote(first.leaseId(), "agent-renew", 2, 1, 101));
        HostingRentReserveCommand confirmation = reserve(quote.quoteId(), "00000000-0000-0000-0000-000000000102");
        HostingRentMutationReceipt renewed = service.renew(confirmation);
        assertEquals(renewed, service.renew(confirmation));
        ArgumentCaptor<EconomyHostingProvisioningIntentEntity> insertedIntents =
                ArgumentCaptor.forClass(EconomyHostingProvisioningIntentEntity.class);
        verify(hostingMapper, atLeast(2)).insertIntent(insertedIntents.capture());
        EconomyHostingProvisioningIntentEntity renewalIntent = insertedIntents.getAllValues().stream()
                .filter(intent -> "RENEWAL".equals(intent.getQuotePurpose()))
                .findFirst().orElseThrow();
        assertTrue(renewalIntent.getId() != null && renewalIntent.getId() > 0,
                "renewal CAS must use the database-generated identity hydrated onto the inserted entity");
        assertEquals(originalThrough, jdbc.queryForObject(
                "SELECT paid_from FROM economy_hosting_provisioning_intent WHERE intent_id=?", Long.class, renewed.intentId()));
        long renewedThrough = originalThrough + V1_PERIOD_SECONDS * 1000;
        assertEquals(renewedThrough, jdbc.queryForObject("SELECT paid_through FROM economy_hosting_lease", Long.class));
        assertEquals(originalPeriod, intentRow(first.intentId()));
        assertEquals(4, count("economy_transaction"));
        assertEquals(2 * V1_AMOUNT_MICRO, balance("system_hosting_rent"));
        assertEquals(0L, balanceLike("hosting_esc_%"));
        assertTrue(ledgerMapper.selectUserWalletSnapshot(TENANT, CLIENT, USER).getVersion() > heldVersion);

        // Fixture-only price version change: old paid periods and order replays never change.
        seedPlan("plan-test", 2, V1_AMOUNT_MICRO / 2, V1_PERIOD_SECONDS, 300);
        clock.set(renewedThrough + 1000);
        HostingRentQuoteReceipt laterQuote = service.quote(renewalQuote(first.leaseId(), "agent-renew", 3, 2, 103));
        HostingRentMutationReceipt later = service.renew(reserve(laterQuote.quoteId(),
                "00000000-0000-0000-0000-000000000104"));
        assertEquals(later.occurredAt(), jdbc.queryForObject("SELECT paid_from FROM economy_hosting_lease", Long.class));
        assertEquals(later.occurredAt() + V1_PERIOD_SECONDS * 1000,
                jdbc.queryForObject("SELECT paid_through FROM economy_hosting_lease", Long.class));
        assertEquals(originalPeriod, intentRow(first.intentId()));
        assertEquals(renewed, service.renew(confirmation));
        assertEquals(6, count("economy_transaction"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
    }

    @Test
    void competingRenewalQuotesExtendExactlyOnceAndLoserCannotDebit() throws Exception {
        HostingRentMutationReceipt first = activeLease("agent-renew-race");
        HostingRentQuoteReceipt one = service.quote(renewalQuote(first.leaseId(), "agent-renew-race", 2, 1, 111));
        HostingRentQuoteReceipt two = service.quote(renewalQuote(first.leaseId(), "agent-renew-race", 2, 1, 112));
        List<Outcome> outcomes = race(() -> renewalOutcome(reserve(one.quoteId(),
                        "00000000-0000-0000-0000-000000000113")),
                () -> renewalOutcome(reserve(two.quoteId(), "00000000-0000-0000-0000-000000000114")));
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count());
        assertEquals(HostingRentException.Reason.LEASE_CONFLICT,
                outcomes.stream().filter(o -> !o.succeeded()).findFirst().orElseThrow().reason());
        assertEquals(4, count("economy_transaction"));
        assertEquals(2, count("economy_hosting_provisioning_intent"));
        assertEquals(0L, balance("wallet-user"));
        assertEquals(3L, jdbc.queryForObject("SELECT version FROM economy_hosting_lease", Long.class));
    }

    @Test
    void lateRenewalCasFailureRollsBackBothPostingsAndPreservesLease() {
        HostingRentMutationReceipt first = activeLease("agent-renew-rollback");
        Map<String, Object> before = leaseRow(first.leaseId());
        HostingRentQuoteReceipt quote = service.quote(renewalQuote(first.leaseId(), "agent-renew-rollback", 2, 1, 121));
        doThrow(new IllegalStateException("late renewal CAS failure")).when(hostingMapper).renewLease(
                anyString(), anyString(), any(), any(), anyString(), anyLong(), anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> service.renew(reserve(quote.quoteId(),
                "00000000-0000-0000-0000-000000000122")));
        assertEquals(before, leaseRow(first.leaseId()));
        assertEquals(2, count("economy_transaction"));
        assertEquals(1, count("economy_hosting_provisioning_intent"));
        assertEquals(1, count("economy_escrow"));
        assertEquals(V1_AMOUNT_MICRO, balance("wallet-user"));
    }

    private HostingRentMutationReceipt activeLease(String agentId) {
        HostingRentMutationReceipt reserved = service.reserve(reserve(
                service.quote(initialQuote(agentId, "persona-renew", "plan-test", 1)).quoteId(),
                "00000000-0000-0000-0000-000000000002"));
        long beforeCaptureVersion = ledgerMapper.selectUserWalletSnapshot(TENANT, CLIENT, USER).getVersion();
        service.confirmProvisioningSucceeded(new HostingRentOutcomeCommand(scope(), principal(),
                reserved.intentId(), 1, "ready-for-renewal"));
        HostingRentMutationReceipt captured = service.capture(settlement(reserved.intentId(), 2,
                "00000000-0000-0000-0000-000000000003", HASH_CAPTURE));
        assertTrue(ledgerMapper.selectUserWalletSnapshot(TENANT, CLIENT, USER).getVersion() > beforeCaptureVersion,
                "capture changes held funds even when available balance does not change");
        return captured;
    }

    private HostingRentQuoteCommand renewalQuote(String leaseId, String agentId, long leaseVersion, long planVersion, int marker) {
        return new HostingRentQuoteCommand(scope(), principal(),
                String.format("00000000-0000-0000-0000-%012d", marker), hash(marker), HostingRentQuotePurpose.RENEWAL,
                "plan-test", planVersion, "persona-renew", agentId, leaseId, leaseVersion);
    }

    private Outcome renewalOutcome(HostingRentReserveCommand command) {
        try { return new Outcome(service.renew(command), null); }
        catch (HostingRentException failure) { return new Outcome(null, failure.reason()); }
    }

    private Map<String, Object> leaseRow(String leaseId) {
        return jdbc.queryForMap("SELECT * FROM economy_hosting_lease WHERE lease_id=?", leaseId);
    }

    private Map<String, Object> intentRow(String intentId) {
        // Binary JDBC values use array identity in Map.equals; project the immutable scalar history.
        return jdbc.queryForMap("""
                SELECT intent_id,lease_id,quote_id,status,version,reserve_transaction_id,reserved_at,
                       capture_transaction_id,captured_at,refund_transaction_id,refunded_at,
                       service_ready_at,paid_from,paid_through,outcome_evidence_ref,amount_micro,period_seconds,update_time
                FROM economy_hosting_provisioning_intent WHERE intent_id=?
                """, intentId);
    }

    private void assertNoPaidArtifacts() {
        for (String table : List.of("economy_transaction", "economy_entry", "economy_escrow",
                "economy_escrow_funding_lot", "economy_hosting_lease",
                "economy_hosting_provisioning_intent")) {
            assertEquals(0, count(table), table);
        }
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM economy_account WHERE owner_type='LEASE'", Integer.class));
    }

    private Outcome reserveOutcome(HostingRentReserveCommand command) {
        try {
            return new Outcome(service.reserve(command), null);
        } catch (HostingRentException failure) {
            return new Outcome(null, failure.reason());
        }
    }

    private List<Outcome> race(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> one = executor.submit(await(ready, start, first));
            Future<Outcome> two = executor.submit(await(ready, start, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Callable<Outcome> await(CountDownLatch ready, CountDownLatch start, Callable<Outcome> task) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return task.call();
        };
    }

    private HostingRentQuoteCommand initialQuote(String agent, String persona, String plan, long version) {
        return initialQuote(agent, persona, plan, version,
                "00000000-0000-0000-0000-000000000001", HASH_QUOTE);
    }

    private HostingRentQuoteCommand initialQuote(
            String agent, String persona, String plan, long version, String key, byte[] hash) {
        return new HostingRentQuoteCommand(scope(), principal(), key, hash,
                HostingRentQuotePurpose.INITIAL, plan, version, persona, agent, null, null);
    }

    private HostingRentReserveCommand reserve(String quoteId, String key) {
        return new HostingRentReserveCommand(scope(), principal(), key, HASH_RESERVE, quoteId);
    }

    private HostingRentSettlementCommand settlement(
            String intentId, long version, String key, byte[] hash) {
        return new HostingRentSettlementCommand(scope(), principal(), key, hash, intentId, version);
    }

    private EconomyScope scope() {
        return new EconomyScope(TENANT, CLIENT);
    }

    private EconomyPrincipal principal() {
        return new EconomyPrincipal(EconomyPrincipalType.USER, USER);
    }

    private void seedPlan(String planId, long version, long amount, long period, long ttl) {
        hostingMapper.insertPlanVersion(new EconomyHostingRentPlanEntity()
                .setPlanId(planId).setPlanVersion(version).setAmountMicro(amount)
                .setPeriodSeconds(period).setQuoteTtlSeconds(ttl)
                .setCurrency(EconomyConstants.CURRENCY_SILVER).setStatus("ACTIVE")
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(tick()));
    }

    private void insertAccount(
            String accountId, EconomyAccountOwnerType ownerType, String ownerId,
            EconomyAccountPurpose purpose, long balance) {
        ledgerMapper.insertAccountIfAbsent(new EconomyAccountEntity()
                .setAccountId(accountId).setOwnerType(ownerType.name()).setOwnerId(ownerId)
                .setPurpose(purpose.name()).setCurrency(EconomyConstants.CURRENCY_SILVER)
                .setBalanceMicro(balance).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(tick()).setUpdateTime(tick()));
    }

    private long balance(String accountId) {
        return jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id=?",
                Long.class, accountId);
    }

    private long balanceLike(String pattern) {
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_micro),0) FROM economy_account WHERE account_id LIKE ?",
                Long.class, pattern);
        return value == null ? 0 : value;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long tick() {
        return clock.getAndIncrement();
    }

    private static byte[] hash(int marker) {
        byte[] hash = new byte[32];
        hash[31] = (byte) marker;
        return hash;
    }

    private void createTables() {
        for (String ddl : List.of(
                "CREATE TABLE economy_account(id BIGINT AUTO_INCREMENT PRIMARY KEY,account_id VARCHAR(100),owner_type VARCHAR(20),owner_id VARCHAR(100),purpose VARCHAR(32),currency VARCHAR(16),balance_micro BIGINT,allow_negative TINYINT,status VARCHAR(16),version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,account_id),UNIQUE(tenant_id,client_id,currency,owner_type,owner_id,purpose))",
                "CREATE TABLE economy_transaction(id BIGINT AUTO_INCREMENT PRIMARY KEY,transaction_id VARCHAR(100),principal_type VARCHAR(20),principal_id VARCHAR(100),idempotency_key VARBINARY(36),request_hash BINARY(32),business_type VARCHAR(32),business_id VARCHAR(100),currency VARCHAR(16),status VARCHAR(16),entry_count INT,debit_total_micro BIGINT,credit_total_micro BIGINT,posted_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,transaction_id),UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key))",
                "CREATE TABLE economy_entry(id BIGINT AUTO_INCREMENT PRIMARY KEY,entry_id VARCHAR(140),transaction_id VARCHAR(100),account_id VARCHAR(100),entry_sequence INT,signed_amount_micro BIGINT,balance_after_micro BIGINT,currency VARCHAR(16),status VARCHAR(16),posted_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,UNIQUE(tenant_id,client_id,entry_id),UNIQUE(tenant_id,client_id,transaction_id,entry_sequence))",
                "CREATE TABLE economy_escrow(id BIGINT AUTO_INCREMENT PRIMARY KEY,escrow_id VARCHAR(100),business_type VARCHAR(32),business_id VARCHAR(100),payer_account_id VARCHAR(100),escrow_account_id VARCHAR(100),currency VARCHAR(16),gross_micro BIGINT,captured_micro BIGINT,refunded_micro BIGINT,status VARCHAR(24),version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,escrow_id),UNIQUE(tenant_id,client_id,business_type,business_id),UNIQUE(tenant_id,client_id,escrow_account_id))",
                "CREATE TABLE economy_escrow_funding_lot(id BIGINT AUTO_INCREMENT PRIMARY KEY,escrow_id VARCHAR(100),funding_sequence INT,reserve_transaction_id VARCHAR(100),payer_account_id VARCHAR(100),amount_micro BIGINT,escrow_gross_after_micro BIGINT,escrow_version_after BIGINT,currency VARCHAR(16),tenant_id VARCHAR(50),client_id VARCHAR(50),created_at BIGINT,UNIQUE(tenant_id,client_id,escrow_id,funding_sequence),UNIQUE(tenant_id,client_id,reserve_transaction_id))",
                "CREATE TABLE economy_hosting_rent_plan(id BIGINT AUTO_INCREMENT PRIMARY KEY,plan_id VARCHAR(100),plan_version BIGINT,amount_micro BIGINT,period_seconds BIGINT,quote_ttl_seconds BIGINT,currency VARCHAR(16),status VARCHAR(16),tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,UNIQUE(tenant_id,client_id,plan_id,plan_version))",
                "CREATE TABLE economy_hosting_rent_quote(id BIGINT AUTO_INCREMENT PRIMARY KEY,quote_id VARCHAR(100),quote_purpose VARCHAR(16),plan_id VARCHAR(100),plan_version BIGINT,amount_micro BIGINT,period_seconds BIGINT,principal_type VARCHAR(20),principal_id VARCHAR(100),persona_code VARCHAR(100),agent_id VARCHAR(100),lease_id VARCHAR(100),expected_lease_version BIGINT,idempotency_key VARBINARY(36),request_hash BINARY(32),expires_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,UNIQUE(tenant_id,client_id,quote_id),UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key))",
                "CREATE TABLE economy_hosting_lease(id BIGINT AUTO_INCREMENT PRIMARY KEY,lease_id VARCHAR(100),principal_type VARCHAR(20),principal_id VARCHAR(100),persona_code VARCHAR(100),agent_id VARCHAR(100),binding_id VARCHAR(100),live_slot TINYINT DEFAULT 1,plan_id VARCHAR(100),plan_version BIGINT,amount_micro BIGINT,period_seconds BIGINT,status VARCHAR(24),paid_from BIGINT,paid_through BIGINT,latest_intent_id VARCHAR(100),version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,lease_id),UNIQUE(tenant_id,client_id,agent_id,live_slot),CHECK((status='REFUNDED' AND live_slot IS NULL) OR (status IN ('PROVISIONING','ACTIVE') AND live_slot IS NOT NULL AND live_slot=1)),UNIQUE(tenant_id,client_id,latest_intent_id))",
                "CREATE TABLE economy_hosting_provisioning_intent(id BIGINT AUTO_INCREMENT PRIMARY KEY,intent_id VARCHAR(100),lease_id VARCHAR(100),quote_id VARCHAR(100),quote_purpose VARCHAR(16),principal_type VARCHAR(20),principal_id VARCHAR(100),persona_code VARCHAR(100),agent_id VARCHAR(100),amount_micro BIGINT,period_seconds BIGINT,status VARCHAR(32),reserve_idempotency_key VARBINARY(36),reserve_request_hash BINARY(32),reserve_transaction_id VARCHAR(100),reserved_at BIGINT,escrow_version BIGINT,capture_idempotency_key VARBINARY(36),capture_request_hash BINARY(32),capture_transaction_id VARCHAR(100),captured_at BIGINT,refund_idempotency_key VARBINARY(36),refund_request_hash BINARY(32),refund_transaction_id VARCHAR(100),refunded_at BIGINT,managed_api_key_id VARCHAR(100),outcome_evidence_ref VARCHAR(100),service_ready_at BIGINT,paid_from BIGINT,paid_through BIGINT,version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,intent_id),UNIQUE(tenant_id,client_id,quote_id),UNIQUE(tenant_id,client_id,principal_type,principal_id,reserve_idempotency_key),CHECK((status='ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through>paid_from) OR (status<>'ACTIVE' AND paid_from IS NULL AND paid_through IS NULL)))"
        )) jdbc.execute(ddl);
    }

    private record Outcome(HostingRentMutationReceipt receipt, HostingRentException.Reason reason) {
        private boolean succeeded() {
            return receipt != null;
        }
    }
}
