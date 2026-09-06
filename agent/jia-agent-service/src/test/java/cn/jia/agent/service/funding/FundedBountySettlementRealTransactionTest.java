package cn.jia.agent.service.funding;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.funding.*;
import cn.jia.agent.mapper.*;
import cn.jia.economy.bounty.FundedBountyCaptureService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyPostingService;
import cn.jia.economy.common.*;
import cn.jia.economy.service.*;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.service.impl.FundedBountyCaptureServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reuses the unchanged R3/W05 real Spring+MyBatis fixture (not its test methods).
 * Actual reserve, claim, three posting templates, escrow/task CAS, receipt SQL and event/outbox
 * writer all use one physical TM. Rabbit invitations remain OFF, not claimed as verified.
 */
class FundedBountySettlementRealTransactionTest {
    private static final FundedBountyActor ACTOR = new FundedBountyActor("Tenant-W04", "Client-W04", "jwt-sub-w04");
    private static final String AGENT = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final long GROSS = 1_000_000_000L;
    private FundedBountyQuoteClaimRealTransactionTest fixture;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager tm;
    private FundedBountyServiceRealTransactionTest.IntegrationServices foundation;
    private FundedBountyServiceRealTransactionTest.RecordingBroker broker;
    private FundedBountyQuoteClaimServiceImpl claims;
    private FundedBountySettlementServiceImpl settlement;
    private String taskId;
    private AgentTaskQuoteDTO quote;
    private AgentTaskClaimReceiptDTO claimReceipt;
    private AgentTaskClaimRequestDTO claimRequest;

    @BeforeEach
    void setUp() throws Exception {
        fixture = new FundedBountyQuoteClaimRealTransactionTest();
        fixture.setUp();
        context = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(fixture, "context");
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(fixture, "jdbc");
        tm = context.getBean(PlatformTransactionManager.class);
        foundation = context.getBean(FundedBountyServiceRealTransactionTest.IntegrationServices.class);
        broker = context.getBean(FundedBountyServiceRealTransactionTest.RecordingBroker.class);
        claims = (FundedBountyQuoteClaimServiceImpl) ReflectionTestUtils.getField(fixture, "service");
        taskId = (String) ReflectionTestUtils.getField(fixture, "taskId");
        DataSource source = context.getBean(DataSource.class);
        new ResourceDatabasePopulator(new ClassPathResource("w06/settlement-integration-h2.sql")).execute(source);
        String ddl = new ClassPathResource("db/agent-task-bounty-settlement-v0.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin", "");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(source);
        context.registerBean("w06Capture", FundedBountyCaptureServiceImpl.class, () -> new FundedBountyCaptureServiceImpl(
                context.getBean(EconomyLedgerMapper.class), context.getBean(EconomyPostingService.class),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                        new EconomyPreviewProperties.AllowedScope(ACTOR.tenantId(), ACTOR.clientId()))))));
        FundedBountyCaptureService capture = context.getBean(FundedBountyCaptureService.class);
        assertTrue(AopUtils.isCglibProxy(capture), "MANDATORY capture must be a real Spring transactional bean");
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskSettlementMapper.class);
        configuration.addMapper(AgentTaskBountyQuoteMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()));
        SqlSessionTemplate root = context.getBean(SqlSessionTemplate.class);
        context.registerBean(AgentTaskSettlementMapper.class, () -> template.getMapper(AgentTaskSettlementMapper.class));
        context.registerBean(AgentTaskMetaMapper.class, () -> root.getMapper(AgentTaskMetaMapper.class));
        context.registerBean(cn.jia.agent.service.AgentTaskEventWriter.class, () -> foundation.events());
        context.registerBean(FundedBountySettlementServiceImpl.class);
        settlement = context.getBean(FundedBountySettlementServiceImpl.class);
        assertEquals(settlement, context.getBean(FundedBountySettlementService.class));
    }

    @AfterEach
    void tearDown() { if (fixture != null) fixture.tearDown(); }

    private void claim() {
        quote = ReflectionTestUtils.invokeMethod(fixture, "quote", AGENT, 2);
        claimRequest = new AgentTaskClaimRequestDTO();
        claimRequest.setAgentId(AGENT); claimRequest.setQuoteId(quote.quoteId());
        claimRequest.setTaskVersion("0"); claimRequest.setAllowQueue(false);
        claimReceipt = claims.claim(ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, claimRequest), taskId, claimRequest);
    }

    @Test
    void realSplitCaptureConservesAndStaysInvisibleUntilOuterCommitWithImmutableReplayAndReadback() {
        claim();
        long actual = Long.parseLong(quote.worstComputeMicro());
        AgentTaskSettlementReceiptDTO receipt = new TransactionTemplate(tm).execute(status -> {
            AgentTaskSettlementReceiptDTO pending = complete(actual);
            assertEquals(4, broker.wakeups.size(), "completion wakeup must wait for outer physical commit");
            assertEquals(4, count("economy_transaction"), "reserve plus all three actual posting templates");
            DriverManagerDataSource source = context.getBean(DriverManagerDataSource.class);
            JdbcTemplate independent = new JdbcTemplate(new DriverManagerDataSource(source.getUrl(), "sa", ""));
            assertEquals(1, independent.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
            assertEquals(0, independent.queryForObject("SELECT COUNT(*) FROM agent_task_bounty_settlement", Integer.class));
            assertEquals("FUNDS_HELD", independent.queryForObject("SELECT funding_status FROM agent_task_funding", String.class));
            return pending;
        });
        assertEquals(List.of("CAPTURE_COMPUTE", "CAPTURE_FEE", "PAY_AGENT"), jdbc.queryForList(
                "SELECT business_type FROM economy_transaction WHERE business_type<>'RESERVE_BOUNTY' ORDER BY id", String.class));
        assertSettlement(receipt, actual, 3);
        assertEquals(receipt, complete(actual));
        assertEquals(receipt, settlement.read(ACTOR, taskId).settlement());
        assertEquals(claimReceipt, claims.claim(ACTOR, key(3), FundedBountyRequestDigest.claim(taskId, claimRequest), taskId, claimRequest));
        assertEquals(quote, ReflectionTestUtils.invokeMethod(fixture, "quote", AGENT, 2));
        assertEquals("SETTLED", foundation.funding().findFunding(ACTOR.tenantId(), ACTOR.clientId(), taskId).getStatus());
        assertEquals(5, count("agent_task_event"));
        assertEquals(5, broker.wakeups.size());
    }

    @Test
    void zeroComputePostsOnlyFeeAndExplicitAgentWithNoRefundOrZeroLines() {
        claim();
        AgentTaskSettlementReceiptDTO receipt = complete(0);
        assertSettlement(receipt, 0, 2);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry WHERE signed_amount_micro=0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction WHERE business_type='CAPTURE_COMPUTE'", Integer.class));
        assertEquals("0", receipt.refundedMicro());
    }

    @Test
    void failureAfterRealReceiptAndEventRollsBackEveryCaptureAndSameKeyCanRetry() {
        claim();
        foundation.events().failAfterWrite.set(true);
        assertThrows(IllegalStateException.class, () -> complete(1));
        assertTrue(foundation.events().observedActiveTransaction.get());
        assertHeldClaim();
        foundation.events().failAfterWrite.set(false);
        assertSettlement(complete(1), 1, 3);
    }

    @Test
    void outerRollbackDropsSuccessfulInnerCaptureReceiptAndDeferredCallbacks() {
        claim();
        new TransactionTemplate(tm).executeWithoutResult(status -> {
            assertEquals("SETTLED", complete(1).status());
            assertEquals(1, count("agent_task_bounty_settlement"));
            assertEquals(4, count("economy_transaction"));
            status.setRollbackOnly();
        });
        assertHeldClaim();
        assertSettlement(complete(1), 1, 3);
    }

    @Test
    void invalidAmountsVersionsScopeOwnerAndChangedBodyWriteNothing() {
        claim();
        for (String amount : List.of("-1", "+1", "01", "1.0", " 1", "9223372036854775808")) {
            assertEquals("BAD_REQUEST", assertThrows(FundedBountyException.class, () -> settlement.complete(
                    ACTOR, key(4), taskId, new AgentTaskFundingCompleteDTO("1", amount))).code());
        }
        for (String version : List.of("-1", "01", "9223372036854775807", "9223372036854775808")) {
            assertThrows(FundedBountyException.class, () -> settlement.complete(ACTOR, key(4), taskId,
                    new AgentTaskFundingCompleteDTO(version, "0")));
        }
        assertEquals("ACTUAL_COMPUTE_EXCEEDS_QUOTE", assertThrows(FundedBountyException.class,
                () -> complete(Long.parseLong(quote.worstComputeMicro()) + 1)).code());
        assertThrows(FundedBountyException.class, () -> complete(Long.MAX_VALUE));
        assertEquals("TASK_VERSION_CONFLICT", assertThrows(FundedBountyException.class, () -> settlement.complete(
                ACTOR, key(4), taskId, new AgentTaskFundingCompleteDTO("0", "0"))).code());
        for (FundedBountyActor actor : List.of(new FundedBountyActor(ACTOR.tenantId(), ACTOR.clientId(), "other"),
                new FundedBountyActor("tenant-w04", ACTOR.clientId(), ACTOR.userId()),
                new FundedBountyActor(ACTOR.tenantId(), "Client-other", ACTOR.userId()))) {
            assertThrows(FundedBountyException.class, () -> settlement.complete(actor, key(4), taskId, request(1)));
            assertThrows(FundedBountyException.class, () -> settlement.read(actor, taskId));
        }
        assertHeldClaim();
        AgentTaskSettlementReceiptDTO result = complete(1);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(FundedBountyException.class, () -> complete(2)).code());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(FundedBountyException.class,
                () -> settlement.complete(ACTOR, key(5), taskId, request(1))).code());
        assertEquals(result, complete(1));
    }

    @Test
    void actorScopedCompletionKeyCannotBeReusedForAnotherTaskAndItsCapturesRollback() {
        claim();
        AgentTaskSettlementReceiptDTO first = complete(1);
        String firstTask = taskId;
        // Explicit test funding fixture, not an unjournaled application issuance path.
        jdbc.update("UPDATE economy_account SET balance_micro=? WHERE owner_type='USER' AND purpose='AVAILABLE'", GROSS);
        cn.jia.agent.entity.AgentTaskCreateDTO create = new cn.jia.agent.entity.AgentTaskCreateDTO();
        create.setTitle("second bounded settlement"); create.setGrossBountyAmountMicro(Long.toString(GROSS));
        create.setSettlementPolicy("GROSS_INCLUSIVE"); create.setRequiredAbilities(List.of("test"));
        create.setRequiredSkillRequirements(List.of());
        taskId = foundation.funding().create(ACTOR, key(6), FundedBountyRequestDigest.create(create), create).getId();
        ReflectionTestUtils.setField(fixture, "taskId", taskId);
        quote = ReflectionTestUtils.invokeMethod(fixture, "quote", AGENT, 7);
        AgentTaskClaimRequestDTO second = new AgentTaskClaimRequestDTO();
        second.setAgentId(AGENT); second.setQuoteId(quote.quoteId()); second.setTaskVersion("0"); second.setAllowQueue(false);
        claims.claim(ACTOR, key(8), FundedBountyRequestDigest.claim(taskId, second), taskId, second);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(FundedBountyException.class, () -> complete(1)).code());
        assertEquals(first, settlement.read(ACTOR, firstTask).settlement());
        assertEquals("FUNDS_HELD", settlement.read(ACTOR, taskId).status());
        assertEquals(1, count("agent_task_bounty_settlement")); assertEquals(5, count("economy_transaction"));
        assertEquals(0L, jdbc.queryForObject("SELECT captured_micro FROM economy_escrow WHERE business_id=?", Long.class, taskId));
        assertEquals(2 * GROSS, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
    }

    @Test
    void insufficientEscrowOrMismatchedAcceptedAgentCannotCapture() {
        claim();
        jdbc.update("UPDATE economy_account SET balance_micro=balance_micro-1 WHERE purpose='ESCROW'");
        assertThrows(FundedBountyException.class, () -> complete(1));
        assertEquals(1, count("economy_transaction"));
        assertEquals(0, count("agent_task_bounty_settlement"));
        jdbc.update("UPDATE economy_account SET balance_micro=balance_micro+1 WHERE purpose='ESCROW'");
        jdbc.update("UPDATE agent_task_meta SET assigned_agent_id='agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'");
        assertThrows(FundedBountyException.class, () -> complete(1));
        jdbc.update("UPDATE agent_task_meta SET assigned_agent_id=?", AGENT);
        assertHeldClaim();
    }

    @Test
    void sharedSkillCaptureAndRefundConserveReplayAndRollBackUsingRealPosting() {
        EconomyPostingService posting = context.getBean(EconomyPostingService.class);
        EconomyScope scope = new EconomyScope(ACTOR.tenantId(), ACTOR.clientId());
        EconomyPrincipal actor = new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId());
        EconomyAccountKey payer = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.USER, ACTOR.userId(), EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey store = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.SYSTEM, "SKILL_STORE", EconomyAccountPurpose.SKILL_STORE);
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_micro,
                  allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('skill-store-fixture','SYSTEM','SKILL_STORE','SKILL_STORE','SILVER',0,0,'ACTIVE',0,?,?,1,1)
                """, ACTOR.tenantId(), ACTOR.clientId());
        for (int index = 0; index < 2; index++) {
            boolean capture = index == 0;
            String order = "skill-order-" + index;
            // Test account/funds fixture only; actual reserve and settlement are production posting.
            jdbc.update("UPDATE economy_account SET balance_micro=10 WHERE owner_type='USER' AND purpose='AVAILABLE'");
            jdbc.update("""
                    INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_micro,
                      allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                    VALUES(?,'ORDER',?,'ESCROW','SILVER',0,0,'ACTIVE',0,?,?,1,1)
                    """, order + "-escrow", order, ACTOR.tenantId(), ACTOR.clientId());
            EconomyAccountKey escrow = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.ORDER, order, EconomyAccountPurpose.ESCROW);
            EconomyPostingResult reserve = posting.post(new EconomyPostingCommand(scope, actor, key(10 + index), new byte[32],
                    EconomyJournalType.RESERVE_SKILL, order,
                    List.of(new EconomyPostingLine(payer, -10), new EconomyPostingLine(escrow, 10)),
                    new EconomyEscrowFunding(EconomyEscrowType.SKILL_ORDER, payer, escrow, 10, null)));
            EconomyAccountKey destination = capture ? store : payer;
            EconomyJournalType type = capture ? EconomyJournalType.CAPTURE_SKILL : EconomyJournalType.REFUND_SKILL;
            EconomyPostingCommand command = new EconomyPostingCommand(scope, actor, key(20 + index), new byte[32], type,
                    order, List.of(new EconomyPostingLine(escrow, -10), new EconomyPostingLine(destination, 10)), null,
                    new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER, escrow, destination, 10, 1, reserve.transactionId()));
            int journals = count("economy_transaction");
            EconomyPostingCommand stale = new EconomyPostingCommand(scope, actor, key(30 + index), new byte[32], type,
                    order, command.lines(), null, new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,
                    escrow, destination, 10, 2, reserve.transactionId()));
            assertEquals(EconomyPostingException.Reason.ESCROW_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(stale)).reason());
            EconomyPostingCommand wrongReserve = new EconomyPostingCommand(scope, actor, key(30 + index), new byte[32], type,
                    order, command.lines(), null, new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,
                    escrow, destination, 10, 1, "etx_wrong-reserve"));
            assertEquals(EconomyPostingException.Reason.ESCROW_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(wrongReserve)).reason());
            assertEquals(journals, count("economy_transaction"));
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                posting.post(command);
                assertEquals(capture ? "CAPTURED" : "REFUNDED", jdbc.queryForObject(
                        "SELECT status FROM economy_escrow WHERE business_id=?", String.class, order));
                status.setRollbackOnly();
            });
            assertEquals(journals, count("economy_transaction"));
            assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM economy_escrow WHERE business_id=?", String.class, order));
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM economy_escrow WHERE business_id=?", Long.class, order));
            EconomyPostingResult result = posting.post(command);
            assertEquals(result, posting.post(command));
            byte[] different = new byte[32]; different[0] = 1;
            EconomyPostingCommand conflicting = new EconomyPostingCommand(scope, actor, command.idempotencyKey(), different,
                    type, order, command.lines(), null, command.escrowSettlement());
            assertEquals(EconomyPostingException.Reason.IDEMPOTENCY_CONFLICT,
                    assertThrows(EconomyPostingException.class, () -> posting.post(conflicting)).reason());
            assertEquals(journals + 1, count("economy_transaction"));
            assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
            assertEquals(GROSS + 10L * (index + 1), jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
            assertEquals(0L, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id=?", Long.class, order + "-escrow"));
            assertEquals(2L, jdbc.queryForObject("SELECT version FROM economy_escrow WHERE business_id=?", Long.class, order));
            assertEquals(10L, jdbc.queryForObject("SELECT " + (capture ? "captured_micro" : "refunded_micro")
                    + " FROM economy_escrow WHERE business_id=?", Long.class, order));
        }
        assertEquals(10L, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id='skill-store-fixture'", Long.class));
        assertEquals(10L, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE owner_type='USER' AND purpose='AVAILABLE'", Long.class));
    }

    @Test
    void sharedCaptureTemplatesStillRejectWrongCounterpartyAndImbalancedLinesBeforeAnyJournal() {
        claim();
        EconomyPostingService posting = context.getBean(EconomyPostingService.class);
        EconomyAccountKey escrow = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK, taskId, EconomyAccountPurpose.ESCROW);
        EconomyAccountKey wrong = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.USER, ACTOR.userId(), EconomyAccountPurpose.AVAILABLE);
        String reserve = jdbc.queryForObject("SELECT reserve_transaction_id FROM agent_task_funding", String.class);
        for (EconomyJournalType type : List.of(EconomyJournalType.CAPTURE_COMPUTE, EconomyJournalType.CAPTURE_FEE, EconomyJournalType.PAY_AGENT)) {
            EconomyPostingException failure = assertThrows(EconomyPostingException.class, () -> posting.post(
                    new EconomyPostingCommand(new EconomyScope(ACTOR.tenantId(), ACTOR.clientId()),
                            new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId()), key(9), new byte[32], type, taskId,
                            List.of(new EconomyPostingLine(escrow, -1), new EconomyPostingLine(wrong, 1)), null,
                            new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, wrong, 1, 1, reserve))));
            assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, failure.reason());
        }
        EconomyAccountKey model = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.SYSTEM, "MODEL_COST", EconomyAccountPurpose.MODEL_COST);
        EconomyPostingException imbalance = assertThrows(EconomyPostingException.class, () -> posting.post(
                new EconomyPostingCommand(new EconomyScope(ACTOR.tenantId(), ACTOR.clientId()),
                        new EconomyPrincipal(EconomyPrincipalType.USER, ACTOR.userId()), key(9), new byte[32],
                        EconomyJournalType.CAPTURE_COMPUTE, taskId,
                        List.of(new EconomyPostingLine(escrow, -2), new EconomyPostingLine(model, 1)), null,
                        new EconomyEscrowSettlement(EconomyEscrowType.BOUNTY, escrow, model, 1, 1, reserve))));
        assertEquals(EconomyPostingException.Reason.IMBALANCED_TRANSACTION, imbalance.reason());
        assertHeldClaim();
    }

    @Test
    void feeAccountOverflowAfterComputePostingRollsBackTheFirstCaptureToo() {
        claim();
        jdbc.update("""
                INSERT INTO economy_account(account_id,owner_type,owner_id,purpose,currency,balance_micro,
                  allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('fee-overflow','SYSTEM','PLATFORM_FEE','PLATFORM_FEE','SILVER',?,0,'ACTIVE',0,?,?,1,1)
                """, Long.MAX_VALUE, ACTOR.tenantId(), ACTOR.clientId());
        assertThrows(FundedBountyException.class, () -> complete(1));
        assertHeldClaim();
        assertEquals(Long.MAX_VALUE, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE account_id='fee-overflow'", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE purpose='MODEL_COST'", Integer.class),
                "recipient provisioning and first successful compute posting must both roll back");
        jdbc.update("UPDATE economy_account SET balance_micro=0 WHERE account_id='fee-overflow'");
        assertSettlement(complete(1), 1, 3);
    }

    @Test
    void parallelSameKeyCompletionsReturnOneReceiptAndOneConservedSettlement() throws Exception {
        claim();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return complete(1); });
            var second = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return complete(1); });
            start.countDown();
            AgentTaskSettlementReceiptDTO receipt = first.get(15, TimeUnit.SECONDS);
            assertEquals(receipt, second.get(15, TimeUnit.SECONDS));
            assertSettlement(receipt, 1, 3);
        }
    }

    @Test
    void completionRacingCancelCannotRefundAnAcceptedClaimOrDoubleSpend() throws Exception {
        claim();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var completed = workers.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS)); return complete(1); });
            var cancelled = workers.submit(() -> {
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return assertThrows(FundedBountyException.class, () -> foundation.funding().cancel(ACTOR, key(5),
                        FundedBountyRequestDigest.cancel(taskId, "1"), taskId, 1));
            });
            start.countDown();
            assertNotNull(cancelled.get(15, TimeUnit.SECONDS));
            assertSettlement(completed.get(15, TimeUnit.SECONDS), 1, 3);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction WHERE business_type='REFUND_BOUNTY'", Integer.class));
        }
    }

    @Test
    void prestartHeldReadAndFullCancelRetainW04SemanticsAndLegacyGuards() {
        AgentTaskSettlementDTO held = settlement.read(ACTOR, taskId);
        assertEquals("FUNDS_HELD", held.status()); assertNull(held.settlement()); assertNull(held.cancellation());
        assertThrows(FundedBountyException.class, () -> settlement.complete(ACTOR, key(4), taskId,
                new AgentTaskFundingCompleteDTO("0", "0")));
        assertThrows(FundedBountyException.class, () -> foundation.guard().requireLifecycleAllowed(ACTOR.tenantId(), ACTOR.clientId(), taskId, false));
        AgentTaskFundingCancelReceiptDTO refund = foundation.funding().cancel(ACTOR, key(5),
                FundedBountyRequestDigest.cancel(taskId, "0"), taskId, 0);
        AgentTaskSettlementDTO cancelled = settlement.read(ACTOR, taskId);
        assertEquals("REFUNDED", cancelled.status()); assertEquals(refund, cancelled.cancellation()); assertNull(cancelled.settlement());
        assertEquals(Long.toString(GROSS), refund.refundedMicro());
        assertEquals(0, count("agent_task_bounty_settlement"));
        assertEquals(GROSS, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
    }

    @Test
    void successfulReceiptCannotUseNullableSqlUnknownToEvadeChecks() {
        claim(); complete(1);
        for (String field : List.of("actual_compute_micro", "platform_fee_micro", "agent_payout_micro",
                "task_version", "funding_version", "escrow_version", "settled_at")) {
            assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE agent_task_bounty_settlement SET " + field + "=NULL"));
        }
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE agent_task_funding SET escrow_version=NULL"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE agent_task_bounty_settlement SET agent_payout_micro=agent_payout_micro+1"));
    }

    private AgentTaskSettlementReceiptDTO complete(long compute) { return settlement.complete(ACTOR, key(4), taskId, request(compute)); }
    private static AgentTaskFundingCompleteDTO request(long amount) { return new AgentTaskFundingCompleteDTO("1", Long.toString(amount)); }
    private static String key(int number) { return "00000000-0000-0000-0000-" + String.format("%012d", number); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private void assertHeldClaim() {
        assertEquals("FUNDS_HELD", jdbc.queryForObject("SELECT funding_status FROM agent_task_funding", String.class));
        assertEquals("assigned", jdbc.queryForObject("SELECT reward_status FROM agent_task_meta", String.class));
        assertEquals("CLAIMED", jdbc.queryForObject("SELECT status FROM agent_task_bounty_quote", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT task_version FROM agent_task_meta", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT version FROM economy_escrow", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT captured_micro FROM economy_escrow", Long.class));
        assertEquals(GROSS, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE purpose='ESCROW'", Long.class));
        assertEquals(1, count("economy_transaction")); assertEquals(2, count("economy_entry"));
        assertEquals(0, count("agent_task_bounty_settlement")); assertEquals(4, count("agent_task_event"));
        assertEquals(4, broker.wakeups.size());
    }

    private void assertSettlement(AgentTaskSettlementReceiptDTO receipt, long compute, int components) {
        long fee = Long.parseLong(quote.platformFeeMicro());
        assertEquals("SETTLED", receipt.status()); assertEquals(AGENT, receipt.agentId());
        assertEquals(Long.toString(GROSS), receipt.grossBountyAmountMicro());
        assertEquals(Long.toString(compute), receipt.actualComputeMicro());
        assertEquals(Long.toString(fee), receipt.platformFeeMicro());
        assertEquals(Long.toString(GROSS - compute - fee), receipt.agentPayoutMicro());
        assertEquals("0", receipt.refundedMicro()); assertEquals("0", receipt.remainingMicro());
        assertEquals("2", receipt.taskVersion()); assertEquals("2", receipt.fundingVersion());
        assertEquals(Integer.toString(1 + components), receipt.escrowVersion());
        assertEquals(components, receipt.transactionIds().size());
        assertEquals(components + 1, count("economy_transaction")); assertEquals(2 * (components + 1), count("economy_entry"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
        assertEquals(GROSS, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE balance_micro<0", Integer.class));
        assertEquals(compute, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE purpose='MODEL_COST'", Long.class));
        assertEquals(fee, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE purpose='PLATFORM_FEE'", Long.class));
        assertEquals(GROSS - compute - fee, jdbc.queryForObject("SELECT balance_micro FROM economy_account WHERE owner_type='AGENT' AND owner_id=? AND purpose='EARNINGS'", Long.class, AGENT));
        assertEquals("CAPTURED", jdbc.queryForObject("SELECT status FROM economy_escrow", String.class));
        assertEquals(GROSS, jdbc.queryForObject("SELECT captured_micro FROM economy_escrow", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT refunded_micro FROM economy_escrow", Long.class));
        assertEquals(1, count("agent_task_bounty_settlement"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_event WHERE event_type=?", Integer.class, TaskEventType.TASK_COMPLETED));
        assertEquals(5, broker.wakeups.size());
        for (var wakeup : broker.wakeups) assertEquals(1, wakeup.visibleEventCount());
    }
}
