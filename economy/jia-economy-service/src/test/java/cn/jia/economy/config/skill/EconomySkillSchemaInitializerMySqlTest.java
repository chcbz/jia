package cn.jia.economy.config.skill;

import cn.jia.economy.config.EconomySkillSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** W07 catalog, immutability, drift, and concurrent bootstrap proof on an isolated MySQL 8.0.21 fixture. */
@EnabledIfEnvironmentVariable(named = EconomySkillMySqlTestFixture.URL_ENV, matches = ".+")
class EconomySkillSchemaInitializerMySqlTest {
    private static final String LOCK_TIMEOUT_MESSAGE =
            "Timed out acquiring ECO-V0 skill schema initialization lock";
    private static final long CONCURRENT_INITIALIZATION_DEADLINE_SECONDS = TimeUnit.MINUTES.toSeconds(40);
    private final EconomySkillMySqlTestFixture fixture = new EconomySkillMySqlTestFixture();
    private Throwable concurrentFailure;

    @BeforeEach
    void setUp() {
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        fixture.closePreserving(concurrentFailure);
    }

    @Test
    void initializerIsIdempotentEmptyAndDoesNotCreateOrAlterW02Tables() {
        JdbcTemplate jdbc = fixture.newDatabase("catalog").jdbc();
        EconomySkillSchemaInitializer initializer = new EconomySkillSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        List<String> first = catalogSnapshot(jdbc);
        initializer.afterPropertiesSet();
        assertEquals(first, catalogSnapshot(jdbc));
        assertEquals(7, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN (
                  'economy_account','economy_transaction','economy_entry','economy_escrow','economy_escrow_funding_lot')
                """, Integer.class));
        for (String table : List.of(
                "economy_skill_product", "economy_skill_product_version", "economy_skill_purchase_quote",
                "economy_skill_order", "economy_skill_order_receipt", "economy_skill_installation",
                "economy_skill_entitlement")) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
    }

    @Test
    void partialSchemaAndCatalogDriftFailClosedWithoutRepair() {
        JdbcTemplate partial = fixture.newDatabase("partial").jdbc();
        partial.execute(firstDdlStatement());
        assertEquals(List.of("economy_skill_product"), partial.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE() ORDER BY table_name
                """, String.class));
        IllegalStateException partialFailure = assertThrows(IllegalStateException.class,
                () -> new EconomySkillSchemaInitializer(partial).afterPropertiesSet());
        assertTrue(partialFailure.getMessage().contains("Refusing automatic repair"), partialFailure.getMessage());
        assertEquals(1, partial.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'
                """, Integer.class));

        JdbcTemplate drift = fixture.newDatabase("drift").jdbc();
        new EconomySkillSchemaInitializer(drift).afterPropertiesSet();
        drift.execute("ALTER TABLE economy_skill_order ADD COLUMN unsafe_extra VARCHAR(20) NULL");
        IllegalStateException driftFailure = assertThrows(IllegalStateException.class,
                () -> new EconomySkillSchemaInitializer(drift).afterPropertiesSet());
        assertTrue(driftFailure.getMessage().contains("incompatible columns"), driftFailure.getMessage());
        assertEquals(1, drift.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='economy_skill_order' AND column_name='unsafe_extra'
                """, Integer.class));
    }

    @Test
    void arbitraryOrderTriggerFailsClosedWithoutRemovingOrReplacingIt() {
        JdbcTemplate jdbc = fixture.newDatabase("order_hook").jdbc();
        EconomySkillSchemaInitializer initializer = new EconomySkillSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        jdbc.execute("""
                CREATE TRIGGER unrelated_buyer_hook BEFORE INSERT ON economy_skill_order
                FOR EACH ROW SET NEW.buyer_id = 'other-user'
                """);
        List<String> before = catalogSnapshot(jdbc);
        IllegalStateException failure = assertThrows(IllegalStateException.class, initializer::afterPropertiesSet);
        assertTrue(failure.getMessage().contains("unrelated_buyer_hook"), failure.getMessage());
        assertEquals(before, catalogSnapshot(jdbc));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name='unrelated_buyer_hook'
                """, Integer.class));
    }

    @Test
    void sameNamedForeignTableInAnotherDatabaseFailsClosedWithoutRepair() {
        // Create the parent first so fixture cleanup removes the referencing database first.
        EconomySkillMySqlTestFixture.Database foreign = fixture.newDatabase("foreign_parent");
        foreign.jdbc().execute(firstDdlStatement());
        JdbcTemplate jdbc = fixture.newDatabase("foreign_child").jdbc();
        EconomySkillSchemaInitializer initializer = new EconomySkillSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        jdbc.execute("ALTER TABLE economy_skill_product_version DROP FOREIGN KEY fk_skill_version_product");
        jdbc.execute("ALTER TABLE economy_skill_product_version ADD CONSTRAINT fk_skill_version_product "
                + "FOREIGN KEY (tenant_id,client_id,product_id) "
                + "REFERENCES `" + foreign.name() + "`.economy_skill_product (tenant_id,client_id,product_id)");
        List<String> before = catalogSnapshot(jdbc);
        IllegalStateException failure = assertThrows(IllegalStateException.class, initializer::afterPropertiesSet);
        assertTrue(failure.getMessage().contains("outside the current database"), failure.getMessage());
        assertEquals(before, catalogSnapshot(jdbc));
        assertEquals(foreign.name(), jdbc.queryForObject("""
                SELECT referenced_table_schema FROM information_schema.key_column_usage
                WHERE constraint_schema=DATABASE() AND table_name='economy_skill_product_version'
                  AND constraint_name='fk_skill_version_product' AND ordinal_position=1
                """, String.class));
    }

    @Test
    void immutableVersionQuoteReceiptAndPlatformOnlyConstraintsRejectAdversarialWrites() {
        JdbcTemplate jdbc = fixture.newDatabase("immutable").jdbc();
        new EconomySkillSchemaInitializer(jdbc).afterPropertiesSet();
        insertProduct(jdbc, "Tenant-A", "Client-A", "sp-1", "repo-inspector");
        insertVersion(jdbc, "Tenant-A", "Client-A", "sp-1", "spv-1", "repo-inspector", "NONE", 0);
        insertQuote(jdbc, "Tenant-A", "Client-A", "sq-1", "spv-1");
        insertFreeOrderAndReceipt(jdbc);

        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE economy_skill_product_version SET price_micro=1 WHERE product_version_id='spv-1'"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM economy_skill_product_version WHERE product_version_id='spv-1'"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE economy_skill_purchase_quote SET expected_price_micro=1 WHERE quote_id='sq-1'"));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "DELETE FROM economy_skill_order_receipt WHERE order_id='so-1'"));

        assertThrows(RuntimeException.class, () -> insertProduct(
                jdbc, "Tenant-A", "Client-A", "sp-third-party", "repo-test", "AGENT", "seller-agent"));
        assertThrows(RuntimeException.class, () -> insertVersion(
                jdbc, "Tenant-A", "Client-A", "sp-1", "spv-deploy-bad", "deploy-runner", "NONE", 1));
        assertThrows(RuntimeException.class, () -> insertVersion(
                jdbc, "Tenant-B", "Client-A", "sp-1", "spv-cross-scope", "repo-test", "NONE", 1));
        assertThrows(RuntimeException.class, () -> jdbc.update("""
                INSERT INTO economy_skill_order(
                    order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,seller_type,seller_id,
                    price_micro,expected_agent_version,permission_grant_version,approved_permissions_manifest,
                    approved_permissions_sha256,escrow_id,reserve_transaction_id,status,version,tenant_id,client_id,
                    held_at,update_time)
                VALUES('so-free-paid-ref','sq-1','spv-1','agent-1','USER','user-1','SYSTEM','SKILL_STORE',
                    0,1,1,'[]',?, 'esc-not-allowed',NULL,'FUNDS_HELD',1,'Tenant-A','Client-A',1,1)
                """, new byte[32]));
    }

    @Test
    void concurrentInitializersSerializeWholeSchemaAndExactTriggerCreation() throws Throwable {
        EconomySkillMySqlTestFixture.Database database = fixture.newDatabase("concurrent");
        JdbcTemplate first = database.jdbc();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        // Resource close failures are suppressed onto the primary failure by try-with-resources.
        try (EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers()) {
            Future<InitializationOutcome> one = workers.submit(
                    () -> initializeAtBarrier(workers, database, ready, start));
            Future<InitializationOutcome> two = workers.submit(
                    () -> initializeAtBarrier(workers, database, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "initializers did not reach barrier");
            start.countDown();
            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(CONCURRENT_INITIALIZATION_DEADLINE_SECONDS);
            InitializationOutcome firstOutcome = getBeforeDeadline(one, deadline);
            InitializationOutcome secondOutcome = getBeforeDeadline(two, deadline);
            assertAll(
                    () -> assertTrue(firstOutcome.isAllowed(), firstOutcome.describe()),
                    () -> assertTrue(secondOutcome.isAllowed(), secondOutcome.describe()),
                    () -> assertTrue(firstOutcome.succeeded() || secondOutcome.succeeded(),
                            "at least one concurrent initializer must complete successfully"));
        } catch (Throwable failure) {
            concurrentFailure = failure;
            throw failure;
        } finally {
            start.countDown();
        }
        assertEquals(7, first.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'
                """, Integer.class));
        assertEquals(6, first.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_skill_market_%'
                """, Integer.class));
    }

    private InitializationOutcome initializeAtBarrier(
            EconomySkillMySqlTestFixture.InitializerWorkers workers,
            EconomySkillMySqlTestFixture.Database database,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("initializer start was not released");
        try {
            // Connection establishment is inside the shared fixture deadline, not the 10s start barrier.
            new EconomySkillSchemaInitializer(workers.jdbc(database.dataSource())).afterPropertiesSet();
            return InitializationOutcome.success();
        } catch (Throwable failure) {
            return InitializationOutcome.failure(failure);
        }
    }

    private InitializationOutcome getBeforeDeadline(Future<InitializationOutcome> future, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("concurrent initializer fixture deadline exceeded");
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }

    private record InitializationOutcome(boolean succeeded, Throwable failure) {
        private static InitializationOutcome success() {
            return new InitializationOutcome(true, null);
        }

        private static InitializationOutcome failure(Throwable failure) {
            return new InitializationOutcome(false, failure);
        }

        private boolean isAllowed() {
            return succeeded || failure instanceof IllegalStateException
                    && LOCK_TIMEOUT_MESSAGE.equals(failure.getMessage());
        }

        private String describe() {
            return succeeded ? "initializer succeeded" : "unexpected initializer outcome: " + failure;
        }
    }

    private void insertProduct(JdbcTemplate jdbc, String tenant, String client, String productId, String skillKey) {
        insertProduct(jdbc, tenant, client, productId, skillKey, "SYSTEM", "SKILL_STORE");
    }

    private void insertProduct(
            JdbcTemplate jdbc, String tenant, String client, String productId, String skillKey,
            String sellerType, String sellerId) {
        jdbc.update("""
                INSERT INTO economy_skill_product(
                    product_id,seller_type,seller_id,name,description,status,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,'DRAFT',1,?,?,1,1)
                """, productId, sellerType, sellerId, skillKey, skillKey, tenant, client);
    }

    private void insertVersion(
            JdbcTemplate jdbc, String tenant, String client, String productId, String productVersionId,
            String skillKey, String restriction, long price) {
        jdbc.update("""
                INSERT INTO economy_skill_product_version(
                    product_version_id,product_id,version_sequence,skill_key,skill_version,price_micro,
                    package_sha256,package_size,approved_permissions_manifest,approved_permissions_sha256,
                    deployment_restriction,review_status,tenant_id,client_id,create_time)
                VALUES(?,?,1,?,'1.0.0',?, ?,123,'[]',? ,?,'APPROVED',?,?,1)
                """, productVersionId, productId, skillKey, price, new byte[32], new byte[32],
                restriction, tenant, client);
    }

    private void insertQuote(JdbcTemplate jdbc, String tenant, String client, String quoteId, String versionId) {
        jdbc.update("""
                INSERT INTO economy_skill_purchase_quote(
                    quote_id,actor_type,actor_id,idempotency_key,request_hash,product_version_id,target_agent_id,
                    expected_agent_version,expected_price_micro,approved_permissions_manifest,
                    approved_permissions_sha256,deployment_restriction,expires_at,tenant_id,client_id,create_time)
                VALUES(?,'USER','user-1',?, ?,?,'agent-1',1,0,'[]',?,'NONE',100,?,?,1)
                """, quoteId,
                "00000000-0000-0000-0000-000000000701".getBytes(StandardCharsets.US_ASCII),
                new byte[32], versionId, new byte[32], tenant, client);
    }

    private void insertFreeOrderAndReceipt(JdbcTemplate jdbc) {
        jdbc.update("""
                INSERT INTO economy_skill_order(
                    order_id,quote_id,product_version_id,target_agent_id,buyer_type,buyer_id,seller_type,seller_id,
                    price_micro,expected_agent_version,permission_grant_version,approved_permissions_manifest,
                    approved_permissions_sha256,status,version,tenant_id,client_id,held_at,update_time)
                VALUES('so-1','sq-1','spv-1','agent-1','USER','user-1','SYSTEM','SKILL_STORE',
                    0,1,1,'[]',?,'FUNDS_HELD',1,'Tenant-A','Client-A',1,1)
                """, new byte[32]);
        jdbc.update("""
                INSERT INTO economy_skill_order_receipt(
                    order_id,actor_type,actor_id,idempotency_key,request_hash,order_version,order_status,
                    price_micro,permission_grant_version,approved_permissions_sha256,tenant_id,client_id,create_time)
                VALUES('so-1','USER','user-1',?, ?,1,'FUNDS_HELD',0,1,?,'Tenant-A','Client-A',1)
                """, "00000000-0000-0000-0000-000000000702".getBytes(StandardCharsets.US_ASCII),
                new byte[32], new byte[32]);
    }

    private String firstDdlStatement() {
        String sql;
        try {
            sql = new ClassPathResource("db/economy-v0-skill-marketplace.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        // This frozen first CREATE has no internal semicolon; skip the header comments entirely.
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS economy_skill_product (");
        if (start < 0) throw new IllegalStateException("W07 DDL contains no product table statement");
        int semicolon = sql.indexOf(';', start);
        if (semicolon < 0) throw new IllegalStateException("W07 product table statement is unterminated");
        return sql.substring(start, semicolon);
    }

    private List<String> catalogSnapshot(JdbcTemplate jdbc) {
        List<String> snapshot = new ArrayList<>();
        for (String table : List.of(
                "economy_skill_product", "economy_skill_product_version", "economy_skill_purchase_quote",
                "economy_skill_order", "economy_skill_order_receipt", "economy_skill_installation",
                "economy_skill_entitlement")) {
            snapshot.add(jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, rowNum) -> rs.getString(2)));
        }
        snapshot.addAll(jdbc.query("""
                SELECT trigger_name,event_object_table,action_timing,event_manipulation,action_statement
                FROM information_schema.triggers
                WHERE trigger_schema=DATABASE()
                  AND (event_object_table IN (
                    'economy_skill_product','economy_skill_product_version','economy_skill_purchase_quote',
                    'economy_skill_order','economy_skill_order_receipt','economy_skill_installation',
                    'economy_skill_entitlement') OR trigger_name LIKE 'trg_skill_market_%')
                ORDER BY trigger_name
                """, (rs, rowNum) -> rs.getString("trigger_name") + "|"
                + rs.getString("event_object_table") + "|" + rs.getString("action_timing") + "|"
                + rs.getString("event_manipulation") + "|" + rs.getString("action_statement")));
        return List.copyOf(snapshot);
    }
}
