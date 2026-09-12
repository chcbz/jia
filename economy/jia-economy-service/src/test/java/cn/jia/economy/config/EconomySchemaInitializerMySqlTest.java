package cn.jia.economy.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ECO-V0-W02 catalog and immutability proof on an isolated MySQL 8.0.21 fixture. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class EconomySchemaInitializerMySqlTest {
    private final EconomyMySqlTestFixture fixture = new EconomyMySqlTestFixture();

    @BeforeEach
    void setUp() {
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void initializerAndNamedMigrationHaveCatalogParityAndRemainEmpty() throws Exception {
        JdbcTemplate initialized = fixture.newDatabase("initialized").jdbc();
        EconomySchemaInitializer initializer = new EconomySchemaInitializer(initialized);
        initializer.afterPropertiesSet();
        List<String> first = catalogSnapshot(initialized);
        initializer.afterPropertiesSet();
        assertEquals(first, catalogSnapshot(initialized));

        JdbcTemplate migrated = fixture.newDatabase("migration").jdbc();
        executeSql(migrated, readResource("db/economy-v0-foundation.sql"));
        new EconomySchemaInitializer(migrated).afterPropertiesSet();

        assertEquals(first, catalogSnapshot(migrated));
        for (String table : EconomySchemaInitializer.TABLES) {
            assertEquals(0, initialized.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
            assertEquals(0, migrated.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    @Test
    void sameNamedCheckClauseDriftAndDisabledEnforcementAreRejected() {
        JdbcTemplate changed = fixture.newDatabase("checkdrift").jdbc();
        new EconomySchemaInitializer(changed).afterPropertiesSet();
        changed.execute("ALTER TABLE economy_account DROP CHECK chk_economy_account_currency");
        changed.execute("""
                ALTER TABLE economy_account
                ADD CONSTRAINT chk_economy_account_currency
                CHECK (currency IN ('SILVER','GOLD')) ENFORCED
                """);
        IllegalStateException changedFailure = assertThrows(IllegalStateException.class,
                () -> new EconomySchemaInitializer(changed).afterPropertiesSet());
        assertTrue(changedFailure.getMessage().contains("CHECK constraints"), changedFailure.getMessage());

        JdbcTemplate disabled = fixture.newDatabase("checkoff").jdbc();
        new EconomySchemaInitializer(disabled).afterPropertiesSet();
        disabled.execute("""
                ALTER TABLE economy_account
                ALTER CHECK chk_economy_account_currency NOT ENFORCED
                """);
        IllegalStateException disabledFailure = assertThrows(IllegalStateException.class,
                () -> new EconomySchemaInitializer(disabled).afterPropertiesSet());
        assertTrue(disabledFailure.getMessage().contains("CHECK constraints"), disabledFailure.getMessage());
    }

    @Test
    void sameNamedTriggerDefinitionDriftIsRejected() {
        JdbcTemplate jdbc = fixture.newDatabase("triggerdrift").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("DROP TRIGGER trg_economy_entry_no_update");
        jdbc.execute("""
                CREATE TRIGGER trg_economy_entry_no_update
                BEFORE UPDATE ON economy_entry
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='wrong immutable guard'
                """);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EconomySchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("trigger"), failure.getMessage());
    }

    @Test
    void negativePermissionIsLimitedToApprovedSystemContraAndVarianceAccounts() {
        JdbcTemplate jdbc = fixture.newDatabase("nonnegative").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();

        assertThrows(RuntimeException.class, () -> insertAccount(jdbc, "acct-user-allow", 0, 1));
        assertThrows(RuntimeException.class, () -> insertAccount(jdbc, "acct-user-negative", -1, 0));
        assertThrows(RuntimeException.class, () -> insertTypedAccount(jdbc, "acct-task", "TASK", "task-1", "ESCROW", 0, 1));
        assertThrows(RuntimeException.class, () -> insertTypedAccount(jdbc, "acct-order", "ORDER", "order-1", "ESCROW", 0, 1));
        assertThrows(RuntimeException.class, () -> insertTypedAccount(jdbc, "acct-agent", "AGENT", "agent-1", "EARNINGS", 0, 1));
        assertThrows(RuntimeException.class, () -> insertTypedAccount(jdbc, "acct-system-other", "SYSTEM", "system", "MODEL_COST", 0, 1));
        assertThrows(RuntimeException.class, () -> insertTypedAccount(jdbc, "acct-system-balance", "SYSTEM", "system",
                "SILVER_ISSUANCE", -1, 0));
        insertTypedAccount(jdbc, "acct-issuance", "SYSTEM", "silver", "SILVER_ISSUANCE", -1, 1);
        insertTypedAccount(jdbc, "acct-variance", "SYSTEM", "variance", "PROVIDER_VARIANCE", -1, 1);
        insertAccount(jdbc, "acct-user-zero", 0, 0);

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account", Integer.class));
    }

    @Test
    void concurrentInitializersSerializeMissingTriggerCreationAndValidateExactCatalog() throws Exception {
        EconomyMySqlTestFixture.Database database = fixture.newDatabase("trigger_race");
        JdbcTemplate first = database.jdbc();
        executeSql(first, readResource("db/economy-v0-foundation.sql"));
        JdbcTemplate second = new JdbcTemplate(database.dataSource());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> one = executor.submit(() -> initializeAtBarrier(first, ready, start));
            Future<Void> two = executor.submit(() -> initializeAtBarrier(second, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "initializers did not reach barrier");
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "initializer workers did not terminate");
        }
        new EconomySchemaInitializer(first).afterPropertiesSet();
        assertEquals(6, first.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_economy_%'
                """, Integer.class));
    }

    @Test
    void postedJournalAndFundingLotsRejectUpdateAndDeleteWithExplicitSignals() {
        JdbcTemplate jdbc = fixture.newDatabase("immutable").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        insertPostingTransaction(jdbc, "etx-posting");
        assertEquals(1, jdbc.update("""
                UPDATE economy_transaction
                SET status='POSTED',entry_count=2,debit_total_micro=10,credit_total_micro=10,
                    posted_at=2,update_time=2
                WHERE transaction_id='etx-posting' AND status='POSTING'
                """));
        jdbc.update("""
                INSERT INTO economy_entry(
                    entry_id,transaction_id,account_id,entry_sequence,signed_amount_micro,
                    balance_after_micro,currency,status,posted_at,tenant_id,client_id,create_time)
                VALUES('entry-1','etx-posting','account-1',1,-10,90,'SILVER','POSTED',2,
                       'Tenant-A','Client-A',2)
                """);
        jdbc.update("""
                INSERT INTO economy_escrow_funding_lot(
                    escrow_id,funding_sequence,reserve_transaction_id,payer_account_id,
                    amount_micro,escrow_gross_after_micro,escrow_version_after,currency,
                    tenant_id,client_id,created_at)
                VALUES('escrow-1',1,'etx-posting','account-1',10,10,1,'SILVER',
                       'Tenant-A','Client-A',2)
                """);

        RuntimeException postedUpdate = assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE economy_transaction SET business_id='changed' WHERE transaction_id='etx-posting'"));
        assertTrue(failureChain(postedUpdate).contains("POSTED economy transactions are immutable"),
                failureChain(postedUpdate));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM economy_transaction WHERE transaction_id='etx-posting'"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE economy_entry SET balance_after_micro=91 WHERE entry_id='entry-1'"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM economy_entry WHERE entry_id='entry-1'"));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                UPDATE economy_escrow_funding_lot SET amount_micro=11
                WHERE reserve_transaction_id='etx-posting'
                """));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                DELETE FROM economy_escrow_funding_lot
                WHERE reserve_transaction_id='etx-posting'
                """));

        assertEquals("POSTED", jdbc.queryForObject(
                "SELECT status FROM economy_transaction WHERE transaction_id='etx-posting'", String.class));
        assertEquals(90L, jdbc.queryForObject(
                "SELECT balance_after_micro FROM economy_entry WHERE entry_id='entry-1'", Long.class));
        assertEquals(10L, jdbc.queryForObject("""
                SELECT amount_micro FROM economy_escrow_funding_lot
                WHERE reserve_transaction_id='etx-posting'
                """, Long.class));
    }

    private Void initializeAtBarrier(
            JdbcTemplate jdbc, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("initializer start was not released");
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        return null;
    }

    private void insertAccount(JdbcTemplate jdbc, String accountId, long balance, int allowNegative) {
        insertTypedAccount(jdbc, accountId, "USER", "user-1", "AVAILABLE", balance, allowNegative);
    }

    private void insertTypedAccount(
            JdbcTemplate jdbc, String accountId, String ownerType, String ownerId, String purpose,
            long balance, int allowNegative) {
        jdbc.update("""
                INSERT INTO economy_account(
                    account_id,owner_type,owner_id,purpose,currency,balance_micro,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?, 'SILVER',?,?,'ACTIVE',0,'Tenant-A','Client-A',1,1)
                """, accountId, ownerType, ownerId, purpose, balance, allowNegative);
    }

    private void insertPostingTransaction(JdbcTemplate jdbc, String transactionId) {
        jdbc.update("""
                INSERT INTO economy_transaction(
                    transaction_id,principal_type,principal_id,idempotency_key,request_hash,
                    business_type,business_id,currency,status,entry_count,
                    debit_total_micro,credit_total_micro,posted_at,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?, 'USER','user-1', ?, ?, 'ISSUE_SILVER','issue-1','SILVER','POSTING',
                       0,0,0,NULL,'Tenant-A','Client-A',1,1)
                """, transactionId,
                "00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.US_ASCII),
                new byte[32]);
    }

    private List<String> catalogSnapshot(JdbcTemplate jdbc) {
        List<String> snapshot = new ArrayList<>();
        for (String table : EconomySchemaInitializer.TABLES) {
            snapshot.add(jdbc.queryForObject("SHOW CREATE TABLE " + table,
                    (rs, rowNum) -> rs.getString(2)));
        }
        snapshot.addAll(jdbc.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_economy_%'
                ORDER BY trigger_name
                """, (rs, rowNum) -> rs.getString("trigger_name") + "|"
                + rs.getString("event_object_table") + "|"
                + rs.getString("action_timing") + "|"
                + rs.getString("event_manipulation") + "|"
                + EconomySchemaInitializer.normalizeTriggerSql(rs.getString("action_statement"))));
        return List.copyOf(snapshot);
    }

    private void executeSql(JdbcTemplate jdbc, String sql) {
        for (String statement : EconomySchemaInitializer.splitSql(sql)) jdbc.execute(statement);
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException(resource + " missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':').append(current.getMessage()).append('\n');
        }
        return result.toString();
    }
}
