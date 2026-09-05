package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.api.EconomyWalletService;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.entity.EconomyEscrowFundingLotEntity;
import cn.jia.economy.exception.EconomyPostingException;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.service.EconomyAccountKey;
import cn.jia.economy.service.EconomyEscrowFunding;
import cn.jia.economy.service.EconomyPostingCommand;
import cn.jia.economy.service.EconomyPostingLine;
import cn.jia.economy.service.EconomyPostingResult;
import cn.jia.economy.service.EconomyPrincipal;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.EconomyTreasuryPostingService;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Real H2 transaction coverage using the production annotated mapper and posting service. */
class EconomyPostingServiceRealTransactionTest {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String USER = "user-1";
    private static final String LOWER_TENANT = "tenant-a";
    private static final String LOWER_CLIENT = "client-a";
    private static final byte[] HASH_ONE = hash(1);
    private static final byte[] HASH_TWO = hash(2);

    private JdbcTemplate jdbc;
    private EconomyLedgerMapper mapper;
    private EconomyPostingServiceImpl service;
    private EconomyTreasuryPostingService treasury;
    private AtomicInteger transactionSequence;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:eco_v0_w02;MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        createTables();

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EconomyLedgerMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("SqlSessionFactory was not created");
        mapper = spy(new SqlSessionTemplate(factory).getMapper(EconomyLedgerMapper.class));

        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT),
                new EconomyPreviewProperties.AllowedScope(LOWER_TENANT, LOWER_CLIENT))));
        transactionSequence = new AtomicInteger();
        service = new EconomyPostingServiceImpl(
                mapper, new DataSourceTransactionManager(dataSource), gate,
                () -> "etx-" + transactionSequence.incrementAndGet(),
                () -> "esc-1", () -> 1_800_000_000_000L + transactionSequence.get());
        treasury = new EconomyTreasuryPostingServiceImpl(service);
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void issuanceProvisioningUsesTreasuryReplayAndOriginalReceiptAfterLaterWalletChanges() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        EconomyWalletService wallet = new EconomyWalletService(mapper, treasury, environment,
                new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                        new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))));
        String firstKey = "00000000-0000-0000-0000-000000000101";
        EconomyPostingResult first = wallet.issue(scope(), USER, firstKey, HASH_ONE, 100, "preview-welcome");
        EconomyPostingResult later = wallet.issue(scope(), USER,
                "00000000-0000-0000-0000-000000000102", HASH_TWO, 50, "preview-welcome-2");
        EconomyPostingResult replay = wallet.issue(scope(), USER, firstKey, HASH_ONE, 100, "preview-welcome");

        assertEquals(first, replay);
        assertEquals(100L, first.creditTotalMicro());
        assertEquals(50L, later.creditTotalMicro());
        assertEquals(150L, balance("wallet_" + walletHash(USER)));
        assertEquals(-150L, balance("system_silver_issuance"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
    }

    @Test
    void concurrentIssuanceProvisioningCreatesOnlyTypedAccounts() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        EconomyWalletService wallet = new EconomyWalletService(mapper, treasury, environment,
                new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                        new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<EconomyPostingResult>> results = List.of(
                    executor.submit(issueAfter(start, wallet, USER, "00000000-0000-0000-0000-000000000111", HASH_ONE)),
                    executor.submit(issueAfter(start, wallet, "user-2", "00000000-0000-0000-0000-000000000112", HASH_TWO)));
            start.countDown();
            for (Future<EconomyPostingResult> result : results) assertEquals("POSTED", result.get().status());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE owner_type='USER' AND purpose='AVAILABLE' AND allow_negative=0 AND version=1", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account WHERE owner_type='SYSTEM' AND purpose='SILVER_ISSUANCE' AND allow_negative=1 AND version=2", Integer.class));
        assertEquals(100L, balance("wallet_" + walletHash(USER)));
        assertEquals(100L, balance("wallet_" + walletHash("user-2")));
    }

    @Test
    void walletHeldBalanceIsExactRemainingEscrowOnly() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        EconomyWalletService wallet = new EconomyWalletService(mapper, treasury, environment,
                new EconomyPreviewGate(new EconomyPreviewProperties(true, true, List.of(
                        new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT)))));
        wallet.issue(scope(), USER, "00000000-0000-0000-0000-000000000121", HASH_ONE, 100, "preview");
        String accountId = "wallet_" + walletHash(USER);
        jdbc.update("""
                INSERT INTO economy_escrow(escrow_id,business_type,business_id,payer_account_id,escrow_account_id,
                    currency,gross_micro,captured_micro,refunded_micro,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('esc-held','BOUNTY','task-held',?,'escrow-account','SILVER',100,30,20,'ACTIVE',1,?,?,1,1)
                """, accountId, TENANT, CLIENT);
        EconomyWalletService.WalletSnapshot snapshot = wallet.wallet(scope(), USER);
        assertEquals(100L, snapshot.availableMicro());
        assertEquals(50L, snapshot.heldMicro());
        assertEquals(1L, snapshot.version());
    }

    @Test
    void balancedPostingUsesDeterministicLocksAndDuplicateReturnsOriginalResult() {
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount("acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);

        EconomyPostingCommand firstCommand = issueCommand(
                "00000000-0000-0000-0000-000000000001", HASH_ONE, 100);
        EconomyPostingResult first = treasury.issue(firstCommand);
        EconomyPostingResult later = treasury.issue(issueCommand(
                "00000000-0000-0000-0000-000000000002", HASH_TWO, 50));
        EconomyPostingResult replay = treasury.issue(firstCommand);

        assertEquals(first, replay);
        assertEquals(100, first.debitTotalMicro());
        assertEquals(first.debitTotalMicro(), first.creditTotalMicro());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT SUM(signed_amount_micro) FROM economy_entry WHERE transaction_id='etx-1'",
                Long.class));
        assertEquals(2, first.lines().size());
        assertEquals(-100, first.lines().get(0).balanceAfterMicro());
        assertEquals(100, first.lines().get(1).balanceAfterMicro());
        assertEquals(-150, later.lines().get(0).balanceAfterMicro());
        assertEquals(150, later.lines().get(1).balanceAfterMicro());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry", Integer.class));

        var owners = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper, times(4)).selectAccountForUpdate(
                eq(TENANT), eq(CLIENT), eq("SILVER"), owners.capture(), anyString(), anyString());
        assertEquals(List.of("SYSTEM", "USER", "SYSTEM", "USER"), owners.getAllValues());
    }

    @Test
    void genericPostingRejectsUserIssueBeforeAnyDml() {
        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> service.post(issueCommand("00000000-0000-0000-0000-000000000015", HASH_ONE, 1)));

        assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, failure.reason());
        verify(mapper, never()).insertTransaction(any());
        verify(mapper, never()).selectAccountForUpdate(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void imbalanceIsRejectedBeforeAnyTransactionOrBalanceDml() {
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount("acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        EconomyPostingCommand imbalanced = new EconomyPostingCommand(
                scope(), principal(), "00000000-0000-0000-0000-000000000003", HASH_ONE,
                EconomyJournalType.ISSUE_SILVER, "issue-1", List.of(
                new EconomyPostingLine(userAvailable(), 100),
                new EconomyPostingLine(issuance(), -99)), null);

        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> treasury.issue(imbalanced));

        assertEquals(EconomyPostingException.Reason.IMBALANCED_TRANSACTION, failure.reason());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry", Integer.class));
        assertEquals(0L, balance("acct-user"));
        assertEquals(0L, balance("acct-issuance"));
    }

    @Test
    void insufficientFundsRollsBackReservationAndLeavesNoPartialPosting() {
        EconomyAccountKey payer = userAvailable();
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        insertAccount("acct-user", payer.ownerType(), payer.ownerId(), payer.purpose(), 75, false);
        insertAccount("acct-escrow", held.ownerType(), held.ownerId(), held.purpose(), 0, false);
        EconomyPostingCommand command = reserveCommand(
                "00000000-0000-0000-0000-000000000004", HASH_ONE, payer, held, 100, null);

        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> service.post(command));

        assertEquals(EconomyPostingException.Reason.INSUFFICIENT_FUNDS, failure.reason());
        assertEquals(75L, balance("acct-user"));
        assertEquals(0L, balance("acct-escrow"));
        for (String table : List.of("economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    @Test
    void overflowRollsBackReservationAndAccountWrites() {
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, Long.MAX_VALUE, false);
        insertAccount("acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);

        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> treasury.issue(issueCommand(
                        "00000000-0000-0000-0000-000000000005", HASH_ONE, 1)));

        assertEquals(EconomyPostingException.Reason.AMOUNT_RANGE_EXCEEDED, failure.reason());
        assertEquals(Long.MAX_VALUE, balance("acct-user"));
        assertEquals(0L, balance("acct-issuance"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry", Integer.class));
    }

    @Test
    void escrowTopUpReusesOneRootAppendsImmutableLotsAndReplayKeepsOriginalLot() {
        EconomyAccountKey payer = userAvailable();
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        insertAccount("acct-user", payer.ownerType(), payer.ownerId(), payer.purpose(), 1_000, false);
        insertAccount("acct-escrow", held.ownerType(), held.ownerId(), held.purpose(), 0, false);
        EconomyPostingCommand initial = reserveCommand(
                "00000000-0000-0000-0000-000000000006", HASH_ONE, payer, held, 300, null);
        EconomyPostingCommand topUp = reserveCommand(
                "00000000-0000-0000-0000-000000000007", HASH_TWO, payer, held, 200, 1L);

        EconomyPostingResult first = service.post(initial);
        EconomyPostingResult second = service.post(topUp);
        EconomyPostingResult replay = service.post(initial);

        assertEquals(first, replay);
        assertEquals("esc-1", first.escrow().escrowId());
        assertEquals(1, first.escrow().fundingSequence());
        assertEquals(300, first.escrow().grossAfterMicro());
        assertEquals(1, first.escrow().escrowVersion());
        assertEquals(2, second.escrow().fundingSequence());
        assertEquals(500, second.escrow().grossAfterMicro());
        assertEquals(2, second.escrow().escrowVersion());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM economy_escrow", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_escrow_funding_lot", Integer.class));
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT funding_sequence FROM economy_escrow_funding_lot ORDER BY funding_sequence",
                Integer.class));
        assertEquals(500L, jdbc.queryForObject("SELECT gross_micro FROM economy_escrow", Long.class));
        assertEquals(500L, balance("acct-escrow"));
        assertEquals(500L, balance("acct-user"));
        assertTrue(second.lines().stream().noneMatch(line -> line.signedAmountMicro() == 0));
    }


    @Test
    void reserveRequiresUserPrincipalAndByteExactPrincipalOwnedPayerBeforeAnyDml() {
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        EconomyAccountKey anotherUsersPayer = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.USER,
                "User-1", EconomyAccountPurpose.AVAILABLE);
        EconomyPostingCommand crossUser = new EconomyPostingCommand(
                scope(), principal(), "00000000-0000-0000-0000-000000000008", HASH_ONE,
                EconomyJournalType.RESERVE_BOUNTY, "task-1", List.of(
                new EconomyPostingLine(anotherUsersPayer, -100),
                new EconomyPostingLine(held, 100)),
                new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, anotherUsersPayer, held, 100, null));
        EconomyAccountKey ownPayer = userAvailable();
        EconomyPostingCommand systemPrincipal = new EconomyPostingCommand(
                scope(), new EconomyPrincipal(EconomyPrincipalType.SYSTEM, USER),
                "00000000-0000-0000-0000-000000000009", HASH_ONE,
                EconomyJournalType.RESERVE_BOUNTY, "task-1", List.of(
                new EconomyPostingLine(ownPayer, -100),
                new EconomyPostingLine(held, 100)),
                new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, ownPayer, held, 100, null));

        for (EconomyPostingCommand denied : List.of(crossUser, systemPrincipal)) {
            EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                    () -> service.post(denied));
            assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, failure.reason());
        }

        verify(mapper, never()).insertTransaction(any());
        verify(mapper, never()).selectAccountForUpdate(
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
    }

    @Test
    void userAvailableCannotUseAllowNegativeMisprovisionAndNeverPostsNegativeBalance() {
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 10, true);
        insertAccount("acct-escrow", held.ownerType(), held.ownerId(), held.purpose(), 0, false);

        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> service.post(reserveCommand(
                        "00000000-0000-0000-0000-000000000010", HASH_ONE,
                        userAvailable(), held, 11, null)));

        assertEquals(EconomyPostingException.Reason.JOURNAL_CORRUPT, failure.reason());
        assertEquals(10L, balance("acct-user"));
        assertEquals(0L, balance("acct-escrow"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry", Integer.class));
    }

    @Test
    void nonSystemEscrowCannotBeMisprovisionedNegative() {
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 10, false);
        insertAccount("acct-escrow", held.ownerType(), held.ownerId(), held.purpose(), 0, true);

        EconomyPostingException failure = assertThrows(EconomyPostingException.class,
                () -> service.post(reserveCommand("00000000-0000-0000-0000-000000000016", HASH_ONE,
                        userAvailable(), held, 1, null)));

        assertEquals(EconomyPostingException.Reason.JOURNAL_CORRUPT, failure.reason());
        assertEquals(10L, balance("acct-user"));
        assertEquals(0L, balance("acct-escrow"));
    }

    @Test
    void exactIdentitiesRejectControlNulAndUnpairedSurrogatesBeforeDml() {
        for (String invalid : List.of("user\u0000x", "user\u001fx", "user\ud800", "user\udc00")) {
            EconomyPostingCommand principalInvalid = new EconomyPostingCommand(
                    scope(), new EconomyPrincipal(EconomyPrincipalType.USER, invalid),
                    "00000000-0000-0000-0000-000000000011", HASH_ONE,
                    EconomyJournalType.ISSUE_SILVER, "issue-1", List.of(
                    new EconomyPostingLine(userAvailable(), 1),
                    new EconomyPostingLine(issuance(), -1)), null);
            EconomyPostingException principalFailure = assertThrows(EconomyPostingException.class,
                    () -> treasury.issue(principalInvalid), invalid);
            assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, principalFailure.reason());

            EconomyAccountKey invalidOwner = new EconomyAccountKey(
                    "SILVER", EconomyAccountOwnerType.USER, invalid, EconomyAccountPurpose.AVAILABLE);
            EconomyPostingCommand ownerInvalid = new EconomyPostingCommand(
                    scope(), principal(), "00000000-0000-0000-0000-000000000011", HASH_ONE,
                    EconomyJournalType.ISSUE_SILVER, "issue-1", List.of(
                    new EconomyPostingLine(invalidOwner, 1),
                    new EconomyPostingLine(issuance(), -1)), null);
            EconomyPostingException ownerFailure = assertThrows(EconomyPostingException.class,
                    () -> treasury.issue(ownerInvalid), invalid);
            assertEquals(EconomyPostingException.Reason.INVALID_COMMAND, ownerFailure.reason());
        }
        verify(mapper, never()).insertTransaction(any());
    }

    @Test
    void accountLockOrderUsesUnsignedUtf8BytesRatherThanUtf16CodeUnits() {
        EconomyAccountKey privateUseBmp = new EconomyAccountKey(
                "SILVER", EconomyAccountOwnerType.USER, "\ue000", EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey supplementary = new EconomyAccountKey(
                "SILVER", EconomyAccountOwnerType.USER, "\ud800\udc00", EconomyAccountPurpose.AVAILABLE);
        EconomyAccountKey malformed = new EconomyAccountKey(
                "SILVER", EconomyAccountOwnerType.USER, "\ud800", EconomyAccountPurpose.AVAILABLE);

        assertTrue(privateUseBmp.ownerId().compareTo(supplementary.ownerId()) > 0);
        assertTrue(EconomyAccountKey.LOCK_ORDER.compare(privateUseBmp, supplementary) < 0);
        assertThrows(IllegalArgumentException.class,
                () -> EconomyAccountKey.LOCK_ORDER.compare(malformed, privateUseBmp));
    }

    @Test
    void sameActorKeyDifferentBodyHashConflictsAndOriginalBodyStillReplays() {
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount("acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        String key = "00000000-0000-0000-0000-000000000012";
        EconomyPostingCommand original = issueCommand(key, HASH_ONE, 100);
        EconomyPostingResult first = treasury.issue(original);

        EconomyPostingException conflict = assertThrows(EconomyPostingException.class,
                () -> treasury.issue(issueCommand(key, HASH_TWO, 100)));

        assertEquals(EconomyPostingException.Reason.IDEMPOTENCY_CONFLICT, conflict.reason());
        assertEquals(first, treasury.issue(original));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_entry", Integer.class));
        assertEquals(100L, balance("acct-user"));
    }

    @Test
    void caseLookalikeTenantAndClientRemainDistinctIdempotencyAndBalanceScopes() {
        insertAccount(TENANT, CLIENT, "acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount(TENANT, CLIENT, "acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        insertAccount(LOWER_TENANT, LOWER_CLIENT, "acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount(LOWER_TENANT, LOWER_CLIENT, "acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        String key = "00000000-0000-0000-0000-000000000013";

        treasury.issue(issueCommand(new EconomyScope(TENANT, CLIENT), key, HASH_ONE, 100));
        treasury.issue(issueCommand(new EconomyScope(LOWER_TENANT, LOWER_CLIENT), key, HASH_ONE, 70));

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(100L, balance(TENANT, CLIENT, "acct-user"));
        assertEquals(70L, balance(LOWER_TENANT, LOWER_CLIENT, "acct-user"));
    }

    @Test
    void lateFundingLotFailureRollsBackAccountsEscrowEntriesAndReservation() {
        EconomyAccountKey held = new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                "task-1", EconomyAccountPurpose.ESCROW);
        insertAccount("acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 500, false);
        insertAccount("acct-escrow", held.ownerType(), held.ownerId(), held.purpose(), 0, false);
        doThrow(new DataIntegrityViolationException("late funding lot failure"))
                .when(mapper).insertFundingLot(any(EconomyEscrowFundingLotEntity.class));

        assertThrows(DataIntegrityViolationException.class, () -> service.post(reserveCommand(
                "00000000-0000-0000-0000-000000000014", HASH_ONE,
                userAvailable(), held, 100, null)));

        assertEquals(500L, balance("acct-user"));
        assertEquals(0L, balance("acct-escrow"));
        for (String table : List.of("economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    private EconomyPostingCommand issueCommand(String key, byte[] hash, long amount) {
        return issueCommand(scope(), key, hash, amount);
    }

    private EconomyPostingCommand issueCommand(EconomyScope scope, String key, byte[] hash, long amount) {
        return new EconomyPostingCommand(scope, principal(), key, hash,
                EconomyJournalType.ISSUE_SILVER, "issue-1", List.of(
                new EconomyPostingLine(userAvailable(), amount),
                new EconomyPostingLine(issuance(), -amount)), null);
    }

    private EconomyPostingCommand reserveCommand(
            String key, byte[] hash, EconomyAccountKey payer, EconomyAccountKey held,
            long amount, Long expectedVersion) {
        return new EconomyPostingCommand(scope(), principal(), key, hash,
                EconomyJournalType.RESERVE_BOUNTY, "task-1", List.of(
                new EconomyPostingLine(payer, -amount),
                new EconomyPostingLine(held, amount)),
                new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, payer, held, amount, expectedVersion));
    }

    private EconomyScope scope() {
        return new EconomyScope(TENANT, CLIENT);
    }

    private EconomyPrincipal principal() {
        return new EconomyPrincipal(EconomyPrincipalType.USER, USER);
    }

    private EconomyAccountKey userAvailable() {
        return new EconomyAccountKey("SILVER", EconomyAccountOwnerType.USER,
                USER, EconomyAccountPurpose.AVAILABLE);
    }

    private EconomyAccountKey issuance() {
        return new EconomyAccountKey("SILVER", EconomyAccountOwnerType.SYSTEM,
                "silver", EconomyAccountPurpose.SILVER_ISSUANCE);
    }

    private void insertAccount(String accountId, EconomyAccountOwnerType ownerType, String ownerId,
                               EconomyAccountPurpose purpose, long balance, boolean allowNegative) {
        insertAccount(TENANT, CLIENT, accountId, ownerType, ownerId, purpose, balance, allowNegative);
    }

    private void insertAccount(String tenantId, String clientId, String accountId,
                               EconomyAccountOwnerType ownerType, String ownerId,
                               EconomyAccountPurpose purpose, long balance, boolean allowNegative) {
        jdbc.update("""
                INSERT INTO economy_account(
                    account_id,owner_type,owner_id,purpose,currency,balance_micro,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?, 'SILVER', ?, ?, 'ACTIVE', 0, ?, ?, 1, 1)
                """, accountId, ownerType.name(), ownerId, purpose.name(), balance,
                allowNegative ? 1 : 0, tenantId, clientId);
    }

    private long balance(String accountId) {
        return balance(TENANT, CLIENT, accountId);
    }

    private long balance(String tenantId, String clientId, String accountId) {
        return jdbc.queryForObject(
                "SELECT balance_micro FROM economy_account "
                        + "WHERE tenant_id=? AND client_id=? AND account_id=?",
                Long.class, tenantId, clientId, accountId);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE economy_account (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    account_id VARCHAR(100) NOT NULL,
                    owner_type VARCHAR(20) NOT NULL,
                    owner_id VARCHAR(100) NOT NULL,
                    purpose VARCHAR(32) NOT NULL,
                    currency VARCHAR(16) NOT NULL,
                    balance_micro BIGINT NOT NULL DEFAULT 0,
                    allow_negative TINYINT NOT NULL DEFAULT 0,
                    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,
                    update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,account_id),
                    UNIQUE(tenant_id,client_id,currency,owner_type,owner_id,purpose))
                """);
        jdbc.execute("""
                CREATE TABLE economy_transaction (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    transaction_id VARCHAR(100) NOT NULL,
                    principal_type VARCHAR(20) NOT NULL,
                    principal_id VARCHAR(100) NOT NULL,
                    idempotency_key VARBINARY(36) NOT NULL,
                    request_hash BINARY(32) NOT NULL,
                    business_type VARCHAR(32) NOT NULL,
                    business_id VARCHAR(100) NOT NULL,
                    currency VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    entry_count INT NOT NULL,
                    debit_total_micro BIGINT NOT NULL,
                    credit_total_micro BIGINT NOT NULL,
                    posted_at BIGINT,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,
                    update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,transaction_id),
                    UNIQUE(tenant_id,client_id,principal_type,principal_id,idempotency_key))
                """);
        jdbc.execute("""
                CREATE TABLE economy_entry (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    entry_id VARCHAR(140) NOT NULL,
                    transaction_id VARCHAR(100) NOT NULL,
                    account_id VARCHAR(100) NOT NULL,
                    entry_sequence INT NOT NULL,
                    signed_amount_micro BIGINT NOT NULL,
                    balance_after_micro BIGINT NOT NULL,
                    currency VARCHAR(16) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    posted_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,entry_id),
                    UNIQUE(tenant_id,client_id,transaction_id,entry_sequence))
                """);
        jdbc.execute("""
                CREATE TABLE economy_escrow (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    escrow_id VARCHAR(100) NOT NULL,
                    business_type VARCHAR(32) NOT NULL,
                    business_id VARCHAR(100) NOT NULL,
                    payer_account_id VARCHAR(100) NOT NULL,
                    escrow_account_id VARCHAR(100) NOT NULL,
                    currency VARCHAR(16) NOT NULL,
                    gross_micro BIGINT NOT NULL,
                    captured_micro BIGINT NOT NULL,
                    refunded_micro BIGINT NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL,
                    update_time BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,escrow_id),
                    UNIQUE(tenant_id,client_id,business_type,business_id),
                    UNIQUE(tenant_id,client_id,escrow_account_id))
                """);
        jdbc.execute("""
                CREATE TABLE economy_escrow_funding_lot (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    escrow_id VARCHAR(100) NOT NULL,
                    funding_sequence INT NOT NULL,
                    reserve_transaction_id VARCHAR(100) NOT NULL,
                    payer_account_id VARCHAR(100) NOT NULL,
                    amount_micro BIGINT NOT NULL,
                    escrow_gross_after_micro BIGINT NOT NULL,
                    escrow_version_after BIGINT NOT NULL,
                    currency VARCHAR(16) NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    created_at BIGINT NOT NULL,
                    UNIQUE(tenant_id,client_id,escrow_id,funding_sequence),
                    UNIQUE(tenant_id,client_id,reserve_transaction_id))
                """);
    }

    private static Callable<EconomyPostingResult> issueAfter(
            CountDownLatch start, EconomyWalletService wallet, String actor, String key, byte[] hash) {
        return () -> {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test start timed out");
            return wallet.issue(scopeStatic(), actor, key, hash, 100, "preview-welcome");
        };
    }

    private static EconomyScope scopeStatic() {
        return new EconomyScope(TENANT, CLIENT);
    }

    private static String walletHash(String actor) {
        try {
            byte[] value = actor.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] framed = java.nio.ByteBuffer.allocate(Integer.BYTES + value.length)
                    .putInt(value.length).put(value).array();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(framed);
            StringBuilder result = new StringBuilder();
            for (byte unit : digest) result.append(String.format("%02x", unit));
            return result.toString();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static byte[] hash(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return value;
    }
}
