package cn.jia.economy.config.skill;

import cn.jia.economy.config.EconomySkillSchemaInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

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

/** W07 catalog, immutability, drift, and concurrent bootstrap proof on an isolated MySQL 8.0.21 fixture. */
@EnabledIfEnvironmentVariable(named = EconomySkillMySqlTestFixture.URL_ENV, matches = ".+")
class EconomySkillSchemaInitializerMySqlTest {
    private final EconomySkillMySqlTestFixture fixture = new EconomySkillMySqlTestFixture();

    @BeforeEach
    void setUp() {
        fixture.start();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
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
    void concurrentInitializersSerializeWholeSchemaAndExactTriggerCreation() throws Exception {
        EconomySkillMySqlTestFixture.Database database = fixture.newDatabase("concurrent");
        JdbcTemplate first = database.jdbc();
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
        new EconomySkillSchemaInitializer(first).afterPropertiesSet();
        assertEquals(7, first.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name LIKE 'economy_skill_%'
                """, Integer.class));
        assertEquals(6, first.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_skill_market_%'
                """, Integer.class));
    }

    private Void initializeAtBarrier(
            JdbcTemplate jdbc, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("initializer start was not released");
        new EconomySkillSchemaInitializer(jdbc).afterPropertiesSet();
        return null;
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
            sql = new String(getClass().getClassLoader()
                    .getResourceAsStream("db/economy-v0-skill-marketplace.sql").readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        int semicolon = sql.indexOf(';');
        if (semicolon < 0) throw new IllegalStateException("W07 DDL contains no statement");
        return sql.substring(0, semicolon);
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
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_skill_market_%'
                ORDER BY trigger_name
                """, (rs, rowNum) -> rs.getString("trigger_name") + "|"
                + rs.getString("event_object_table") + "|" + rs.getString("action_timing") + "|"
                + rs.getString("event_manipulation") + "|" + rs.getString("action_statement")));
        return List.copyOf(snapshot);
    }
}
