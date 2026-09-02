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
    void userAvailableSchemaInvariantRejectsNegativePermissionAndNegativeBalance() {
        JdbcTemplate jdbc = fixture.newDatabase("nonnegative").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();

        assertThrows(RuntimeException.class, () -> insertAccount(jdbc, "acct-user-allow", 0, 1));
        assertThrows(RuntimeException.class, () -> insertAccount(jdbc, "acct-user-negative", -1, 0));
        insertAccount(jdbc, "acct-user-zero", 0, 0);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM economy_account", Integer.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT balance_micro FROM economy_account WHERE account_id='acct-user-zero'", Long.class));
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

    private void insertAccount(JdbcTemplate jdbc, String accountId, long balance, int allowNegative) {
        jdbc.update("""
                INSERT INTO economy_account(
                    account_id,owner_type,owner_id,purpose,currency,balance_micro,
                    allow_negative,status,version,tenant_id,client_id,create_time,update_time)
                VALUES(?, 'USER','user-1','AVAILABLE','SILVER',?,?,'ACTIVE',0,
                       'Tenant-A','Client-A',1,1)
                """, accountId, balance, allowNegative);
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
        snapshot.addAll(jdbc.queryForList("""
                SELECT CONCAT(trigger_name,'|',event_object_table,'|',action_timing,'|',
                              event_manipulation,'|',action_statement)
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_economy_%'
                ORDER BY trigger_name
                """, String.class));
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
