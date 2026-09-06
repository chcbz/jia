package cn.jia.economy.service.impl;

import cn.jia.economy.common.EconomyAccountOwnerType;
import cn.jia.economy.common.EconomyAccountPurpose;
import cn.jia.economy.common.EconomyEscrowType;
import cn.jia.economy.common.EconomyJournalType;
import cn.jia.economy.common.EconomyPrincipalType;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.config.EconomyPreviewProperties;
import cn.jia.economy.config.EconomySchemaInitializer;
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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.ArrayList;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MySQL-only transaction, actor-idempotency, exact-scope, and bounded escrow race coverage. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class EconomyPostingServiceMySqlConcurrencyTest {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String LOWER_TENANT = "tenant-a";
    private static final String LOWER_CLIENT = "client-a";
    private static final String USER = "user-1";
    private static final byte[] HASH_ONE = hash(1);
    private static final byte[] HASH_TWO = hash(2);

    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();
    private JdbcTemplate jdbc;
    private EconomyPostingServiceImpl service;
    private EconomyTreasuryPostingService treasury;

    @BeforeEach
    void setUp() throws Exception {
        fixture.start();
        EconomyMySqlTestFixture.Database database = fixture.newDatabase("posting");
        jdbc = database.jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(database.dataSource());
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EconomyLedgerMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("SqlSessionFactory was not created");
        EconomyLedgerMapper mapper = new SqlSessionTemplate(factory).getMapper(EconomyLedgerMapper.class);

        EconomyPreviewGate gate = new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(
                new EconomyPreviewProperties.AllowedScope(TENANT, CLIENT),
                new EconomyPreviewProperties.AllowedScope(LOWER_TENANT, LOWER_CLIENT))));
        AtomicInteger transactionIds = new AtomicInteger();
        AtomicInteger escrowIds = new AtomicInteger();
        service = new EconomyPostingServiceImpl(
                mapper, new DataSourceTransactionManager(database.dataSource()), gate,
                () -> "etx-mysql-" + transactionIds.incrementAndGet(),
                () -> "esc-mysql-" + escrowIds.incrementAndGet(),
                () -> 1_800_000_000_000L + transactionIds.get());
        treasury = new EconomyTreasuryPostingServiceImpl(service);
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void sameActorKeyRaceReturnsOnePostingStableReplayAndBodyHashConflict() throws Exception {
        insertAccount(TENANT, CLIENT, "acct-user", EconomyAccountOwnerType.USER, USER,
                EconomyAccountPurpose.AVAILABLE, 0, false);
        insertAccount(TENANT, CLIENT, "acct-issuance", EconomyAccountOwnerType.SYSTEM, "silver",
                EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        String key = "10000000-0000-0000-0000-000000000001";
        EconomyPostingCommand command = issueCommand(scope(TENANT, CLIENT), key, HASH_ONE, 100);

        List<Outcome> raced = race(() -> postTreasury(command), () -> postTreasury(command));

        assertTrue(raced.stream().allMatch(Outcome::succeeded), raced.toString());
        assertEquals(raced.get(0).result(), raced.get(1).result());
        EconomyPostingResult replay = treasury.issue(command);
        assertEquals(raced.get(0).result(), replay);
        EconomyPostingException conflict = assertThrows(EconomyPostingException.class,
                () -> treasury.issue(issueCommand(scope(TENANT, CLIENT), key, HASH_TWO, 100)));
        assertEquals(EconomyPostingException.Reason.IDEMPOTENCY_CONFLICT, conflict.reason());
        assertEquals(1, count("economy_transaction"));
        assertEquals(2, count("economy_entry"));
        assertEquals(100L, balance(TENANT, CLIENT, "acct-user"));
    }

    @Test
    void concurrentInitialReserveAndSameVersionTopUpEachHaveOneWinner() throws Exception {
        EconomyAccountKey payer = userAvailable();
        EconomyAccountKey held = taskEscrow();
        insertAccount(TENANT, CLIENT, "acct-user", payer.ownerType(), payer.ownerId(), payer.purpose(),
                500, false);
        insertAccount(TENANT, CLIENT, "acct-escrow", held.ownerType(), held.ownerId(), held.purpose(),
                0, false);

        List<Outcome> initial = race(
                () -> post(reserveCommand("10000000-0000-0000-0000-000000000002", HASH_ONE, 100, null)),
                () -> post(reserveCommand("10000000-0000-0000-0000-000000000003", HASH_TWO, 100, null)));
        assertOneSuccessOneConflict(initial, EconomyPostingException.Reason.ESCROW_CONFLICT);
        assertEquals(1, count("economy_escrow"));
        assertEquals(1, count("economy_escrow_funding_lot"));
        assertEquals(100L, escrowGross());
        assertEquals(400L, balance(TENANT, CLIENT, "acct-user"));

        List<Outcome> topUps = race(
                () -> post(reserveCommand("10000000-0000-0000-0000-000000000004", HASH_ONE, 50, 1L)),
                () -> post(reserveCommand("10000000-0000-0000-0000-000000000005", HASH_TWO, 50, 1L)));
        assertOneSuccessOneConflict(topUps, EconomyPostingException.Reason.ESCROW_CONFLICT);
        assertEquals(1, count("economy_escrow"));
        assertEquals(2, count("economy_escrow_funding_lot"));
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT funding_sequence FROM economy_escrow_funding_lot ORDER BY funding_sequence",
                Integer.class));
        assertEquals(150L, escrowGross());
        assertEquals(350L, balance(TENANT, CLIENT, "acct-user"));
        assertEquals(150L, balance(TENANT, CLIENT, "acct-escrow"));
        assertEquals(2, count("economy_transaction"));
        assertEquals(4, count("economy_entry"));
    }

    @Test
    void oneHundredConcurrentDebitsConserveLedgerBalancesAndEscrowWithoutOverdraft() throws Exception {
        EconomyAccountKey payer = userAvailable();
        insertAccount(TENANT, CLIENT, "acct-user", payer.ownerType(), payer.ownerId(), payer.purpose(),
                60, false);
        List<EconomyPostingCommand> commands = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            String taskId = "task-" + index;
            EconomyAccountKey held = taskEscrow(taskId);
            insertAccount(TENANT, CLIENT, "acct-escrow-" + index, held.ownerType(), held.ownerId(),
                    held.purpose(), 0, false);
            commands.add(reserveCommand(
                    String.format("20000000-0000-0000-0000-%012d", index + 1),
                    hash(index + 10), 1, null, taskId));
        }

        List<Outcome> outcomes = raceAll(commands.stream().<Callable<Outcome>>map(
                command -> () -> post(command)).toList());
        long successes = outcomes.stream().filter(Outcome::succeeded).count();
        long insufficient = outcomes.stream().filter(outcome -> !outcome.succeeded()
                && outcome.reason() == EconomyPostingException.Reason.INSUFFICIENT_FUNDS).count();
        assertEquals(60, successes, outcomes.toString());
        assertEquals(40, insufficient, outcomes.toString());
        assertEquals(100, outcomes.size());
        assertEquals(0L, balance(TENANT, CLIENT, "acct-user"));
        assertEquals(60L, jdbc.queryForObject("SELECT version FROM economy_account WHERE account_id='acct-user'",
                Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT MIN(balance_micro) FROM economy_account", Long.class));
        assertEquals(60, count("economy_transaction"));
        assertEquals(120, count("economy_entry"));
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry", Long.class));
        assertEquals(-60L, jdbc.queryForObject("""
                SELECT SUM(signed_amount_micro) FROM economy_entry e
                JOIN economy_account a ON a.tenant_id=e.tenant_id AND a.client_id=e.client_id
                    AND a.account_id=e.account_id
                WHERE a.owner_type='USER' AND a.purpose='AVAILABLE'
                """, Long.class));
        assertEquals(60L, jdbc.queryForObject("SELECT SUM(balance_micro) FROM economy_account "
                + "WHERE owner_type='TASK' AND purpose='ESCROW'", Long.class));
        assertEquals(60L, jdbc.queryForObject("SELECT SUM(version) FROM economy_account "
                + "WHERE owner_type='TASK' AND purpose='ESCROW'", Long.class));
        assertEquals(60, count("economy_escrow"));
        assertEquals(60, count("economy_escrow_funding_lot"));
        assertEquals(60L, jdbc.queryForObject("SELECT SUM(gross_micro) FROM economy_escrow", Long.class));
        assertEquals(60L, jdbc.queryForObject("SELECT SUM(amount_micro) FROM economy_escrow_funding_lot", Long.class));
    }

    @Test
    void binaryScopeKeepsTenantAndClientCaseLookalikesIndependent() {
        for (EconomyScope scope : List.of(scope(TENANT, CLIENT), scope(LOWER_TENANT, LOWER_CLIENT))) {
            insertAccount(scope.tenantId(), scope.clientId(), "acct-user",
                    EconomyAccountOwnerType.USER, USER, EconomyAccountPurpose.AVAILABLE, 0, false);
            insertAccount(scope.tenantId(), scope.clientId(), "acct-issuance",
                    EconomyAccountOwnerType.SYSTEM, "silver", EconomyAccountPurpose.SILVER_ISSUANCE, 0, true);
        }
        String key = "10000000-0000-0000-0000-000000000006";

        treasury.issue(issueCommand(scope(TENANT, CLIENT), key, HASH_ONE, 100));
        treasury.issue(issueCommand(scope(LOWER_TENANT, LOWER_CLIENT), key, HASH_ONE, 70));

        assertEquals(2, count("economy_transaction"));
        assertEquals(100L, balance(TENANT, CLIENT, "acct-user"));
        assertEquals(70L, balance(LOWER_TENANT, LOWER_CLIENT, "acct-user"));
    }

    @Test
    void lateFundingLotSignalRollsBackReservationAccountsEscrowAndEntries() {
        EconomyAccountKey payer = userAvailable();
        EconomyAccountKey held = taskEscrow();
        insertAccount(TENANT, CLIENT, "acct-user", payer.ownerType(), payer.ownerId(), payer.purpose(),
                500, false);
        insertAccount(TENANT, CLIENT, "acct-escrow", held.ownerType(), held.ownerId(), held.purpose(),
                0, false);
        jdbc.execute("""
                CREATE TRIGGER test_economy_late_funding_failure
                BEFORE INSERT ON economy_escrow_funding_lot
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test late funding failure'
                """);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> service.post(
                reserveCommand("10000000-0000-0000-0000-000000000007", HASH_ONE, 100, null)));
        assertTrue(failureChain(failure).contains("test late funding failure"), failureChain(failure));
        assertEquals(500L, balance(TENANT, CLIENT, "acct-user"));
        assertEquals(0L, balance(TENANT, CLIENT, "acct-escrow"));
        for (String table : List.of("economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot")) {
            assertEquals(0, count(table), table);
        }
    }

    private Outcome postTreasury(EconomyPostingCommand command) {
        try {
            return new Outcome(treasury.issue(command), null);
        } catch (EconomyPostingException failure) {
            return new Outcome(null, failure.reason());
        }
    }

    private Outcome post(EconomyPostingCommand command) {
        try {
            return new Outcome(service.post(command), null);
        } catch (EconomyPostingException failure) {
            return new Outcome(null, failure.reason());
        }
    }

    private List<Outcome> race(Callable<Outcome> first, Callable<Outcome> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> firstFuture = executor.submit(awaitStart(ready, start, first));
            Future<Outcome> secondFuture = executor.submit(awaitStart(ready, start, second));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not reach race barrier");
            start.countDown();
            return List.of(firstFuture.get(30, TimeUnit.SECONDS), secondFuture.get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "race workers did not terminate");
        }
    }

    private List<Outcome> raceAll(List<Callable<Outcome>> operations) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(operations.size());
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> operation : operations) futures.add(executor.submit(awaitStart(ready, start, operation)));
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not reach race barrier");
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) outcomes.add(future.get(60, TimeUnit.SECONDS));
            return List.copyOf(outcomes);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "race workers did not terminate");
        }
    }

    private Callable<Outcome> awaitStart(
            CountDownLatch ready, CountDownLatch start, Callable<Outcome> operation) {
        return () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("race start was not released");
            }
            return operation.call();
        };
    }

    private void assertOneSuccessOneConflict(
            List<Outcome> outcomes, EconomyPostingException.Reason expectedFailure) {
        assertEquals(1, outcomes.stream().filter(Outcome::succeeded).count(), outcomes.toString());
        List<Outcome> failures = outcomes.stream().filter(outcome -> !outcome.succeeded()).toList();
        assertEquals(1, failures.size(), outcomes.toString());
        assertEquals(expectedFailure, failures.getFirst().reason());
        assertNotNull(outcomes.stream().filter(Outcome::succeeded).findFirst().orElseThrow().result());
    }

    private EconomyPostingCommand issueCommand(
            EconomyScope scope, String key, byte[] hash, long amount) {
        return new EconomyPostingCommand(scope, principal(), key, hash,
                EconomyJournalType.ISSUE_SILVER, "issue-1", List.of(
                new EconomyPostingLine(userAvailable(), amount),
                new EconomyPostingLine(issuance(), -amount)), null);
    }

    private EconomyPostingCommand reserveCommand(
            String key, byte[] hash, long amount, Long expectedVersion) {
        return reserveCommand(key, hash, amount, expectedVersion, "task-1");
    }

    private EconomyPostingCommand reserveCommand(
            String key, byte[] hash, long amount, Long expectedVersion, String taskId) {
        EconomyAccountKey payer = userAvailable();
        EconomyAccountKey held = taskEscrow(taskId);
        return new EconomyPostingCommand(scope(TENANT, CLIENT), principal(), key, hash,
                EconomyJournalType.RESERVE_BOUNTY, taskId, List.of(
                new EconomyPostingLine(payer, -amount),
                new EconomyPostingLine(held, amount)),
                new EconomyEscrowFunding(EconomyEscrowType.BOUNTY, payer, held, amount, expectedVersion));
    }

    private EconomyPrincipal principal() {
        return new EconomyPrincipal(EconomyPrincipalType.USER, USER);
    }

    private EconomyScope scope(String tenantId, String clientId) {
        return new EconomyScope(tenantId, clientId);
    }

    private EconomyAccountKey userAvailable() {
        return new EconomyAccountKey("SILVER", EconomyAccountOwnerType.USER,
                USER, EconomyAccountPurpose.AVAILABLE);
    }

    private EconomyAccountKey issuance() {
        return new EconomyAccountKey("SILVER", EconomyAccountOwnerType.SYSTEM,
                "silver", EconomyAccountPurpose.SILVER_ISSUANCE);
    }

    private EconomyAccountKey taskEscrow() {
        return taskEscrow("task-1");
    }

    private EconomyAccountKey taskEscrow(String taskId) {
        return new EconomyAccountKey("SILVER", EconomyAccountOwnerType.TASK,
                taskId, EconomyAccountPurpose.ESCROW);
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long balance(String tenantId, String clientId, String accountId) {
        return jdbc.queryForObject("""
                SELECT balance_micro FROM economy_account
                WHERE tenant_id=? AND client_id=? AND account_id=?
                """, Long.class, tenantId, clientId, accountId);
    }

    private long escrowGross() {
        return jdbc.queryForObject("SELECT gross_micro FROM economy_escrow", Long.class);
    }

    private String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private static byte[] hash(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return value;
    }

    private record Outcome(EconomyPostingResult result, EconomyPostingException.Reason reason) {
        private boolean succeeded() {
            return result != null;
        }
    }
}
