package cn.jia.economy.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class EconomyHostingRentSchemaInitializerMySqlTest {
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
    void additiveInitializerPreservesAcceptedFoundationAndSeedsNoPriceOrHistory() {
        JdbcTemplate jdbc = fixture.newDatabase("rent_schema").jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        List<String> foundationBefore = EconomySchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2)))
                .toList();

        EconomyHostingRentSchemaInitializer initializer = new EconomyHostingRentSchemaInitializer(jdbc);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();

        List<String> foundationAfter = EconomySchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2)))
                .toList();
        assertEquals(foundationBefore, foundationAfter);
        for (String table : EconomyHostingRentSchemaInitializer.TABLES) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
        }
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_hosting_%'
                """, Integer.class));
    }

    @Test
    void fullCatalogWithSameColumnNamesButIncompatibleDefinitionsFailsWithoutRepair() {
        for (CatalogChange change : List.of(
                new CatalogChange("type", "plan", "amount_micro       BIGINT NOT NULL",
                        "amount_micro       DECIMAL(20,0) NOT NULL", "columns"),
                new CatalogChange("signedness", "plan", "plan_version       BIGINT NOT NULL",
                        "plan_version       BIGINT UNSIGNED NOT NULL", "columns"),
                new CatalogChange("nullable", "plan", "create_time        BIGINT NOT NULL",
                        "create_time        BIGINT DEFAULT NULL", "columns"),
                new CatalogChange("default", "plan", "create_time        BIGINT NOT NULL",
                        "create_time        BIGINT NOT NULL DEFAULT 1", "columns"),
                new CatalogChange("width", "quote", "persona_code            VARCHAR(100) NOT NULL",
                        "persona_code            VARCHAR(99) NOT NULL", "columns"),
                new CatalogChange("collation", "quote", "persona_code            VARCHAR(100) NOT NULL",
                        "persona_code            VARCHAR(100) COLLATE utf8mb4_0900_ai_ci NOT NULL", "columns"),
                new CatalogChange("auto_increment", "plan", "BIGINT NOT NULL AUTO_INCREMENT",
                        "BIGINT NOT NULL", "columns"))) {
            assertIncompatibleCompleteCatalog(change);
        }
    }

    @Test
    void fullCatalogWithSameIndexNamesButWrongUniquenessOrderPrefixTypeOrVisibilityFails() {
        for (CatalogChange change : List.of(
                new CatalogChange("nonunique", "lease", "UNIQUE KEY uk_hosting_lease_agent",
                        "KEY uk_hosting_lease_agent", "indexes"),
                new CatalogChange("old_live_key", "lease", "(tenant_id,client_id,agent_id,live_slot)",
                        "(tenant_id,client_id,agent_id)", "indexes"),
                new CatalogChange("index_order", "plan", "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version)",
                        "KEY idx_hosting_plan_status (client_id,tenant_id,status,plan_id,plan_version)", "indexes"),
                new CatalogChange("prefix", "plan", "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version)",
                        "KEY idx_hosting_plan_status (tenant_id(10),client_id,status,plan_id,plan_version)", "index"),
                new CatalogChange("invisible", "plan", "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version)",
                        "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version) INVISIBLE", "index"),
                new CatalogChange("descending", "plan", "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version)",
                        "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version DESC)", "index"),
                new CatalogChange("fulltext", "plan", "KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id,plan_version)",
                        "FULLTEXT KEY idx_hosting_plan_status (tenant_id,client_id,status,plan_id)", "index"))) {
            assertIncompatibleCompleteCatalog(change);
        }
    }

    @Test
    void fullCatalogWithSameCheckNamesButWeakenedOrUnenforcedExpressionsFails() {
        for (CatalogChange change : List.of(
                new CatalogChange("check_true", "plan", "CHECK (currency = 'SILVER')",
                        "CHECK (1=1)", "checks"),
                new CatalogChange("check_literal", "plan", "CHECK (currency = 'SILVER')",
                        "CHECK (currency = 'silver')", "checks"),
                new CatalogChange("check_disabled", "plan", "CHECK (currency = 'SILVER')",
                        "CHECK (currency = 'SILVER') NOT ENFORCED", "checks"))) {
            assertIncompatibleCompleteCatalog(change);
        }
    }

    @Test
    void malformedOrUnexpectedTriggersOnCompleteCatalogFailBeforeMissingGuardsAreCreated() {
        for (String table : List.of("economy_hosting_rent_plan", "economy_hosting_lease")) {
            JdbcTemplate jdbc = fixture.newDatabase("rent_trigger").jdbc();
            EconomyHostingRentSchemaInitializer.tableDdlStatements().forEach(jdbc::execute);
            jdbc.execute("CREATE TRIGGER trg_hosting_plan_no_update BEFORE UPDATE ON " + table
                    + " FOR EACH ROW SET @rent_fixture_noop=1");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new EconomyHostingRentSchemaInitializer(jdbc).afterPropertiesSet());
            assertTrue(failure.getMessage().contains("trigger"), failure.getMessage());
            assertEquals(1, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
                    """, Integer.class));
        }
    }

    @Test
    void liveSlotAllowsRefundedHistoryButOnlyOneUnrefundedLeasePerCanonicalAgent() {
        JdbcTemplate jdbc = fixture.newDatabase("rent_live_slot").jdbc();
        new EconomyHostingRentSchemaInitializer(jdbc).afterPropertiesSet();
        insertLease(jdbc, "old-one", "REFUNDED", null);
        insertLease(jdbc, "old-two", "REFUNDED", null);
        insertLease(jdbc, "current", "PROVISIONING", 1);
        assertThrows(RuntimeException.class, () -> insertLease(jdbc, "duplicate", "PROVISIONING", 1));
        assertThrows(RuntimeException.class, () -> insertLease(jdbc, "null-bypass", "PROVISIONING", null));
        assertThrows(RuntimeException.class, () -> insertLease(jdbc, "slot-bypass", "PROVISIONING", 2));
        assertThrows(RuntimeException.class, () -> insertLease(jdbc, "held-refund", "REFUNDED", 1));
        assertThrows(RuntimeException.class, () -> jdbc.update(
                "UPDATE economy_hosting_lease SET live_slot=NULL WHERE lease_id='current'"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_lease", Integer.class));
    }

    private void insertLease(JdbcTemplate jdbc, String leaseId, String status, Integer liveSlot) {
        jdbc.update("""
                INSERT INTO economy_hosting_lease(
                    lease_id,principal_type,principal_id,persona_code,agent_id,live_slot,plan_id,plan_version,
                    amount_micro,period_seconds,status,latest_intent_id,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,'USER','fixture-user','fixture-persona','fixture-agent',?,'fixture-only',1,
                       1000000000,2592000,?,?,1,'Tenant-A','Client-A',1,1)
                """, leaseId, liveSlot, status, "intent-" + leaseId);
    }

    private void assertIncompatibleCompleteCatalog(CatalogChange change) {
        JdbcTemplate jdbc = fixture.newDatabase(change.label()).jdbc();
        boolean replaced = false;
        for (String ddl : EconomyHostingRentSchemaInitializer.tableDdlStatements()) {
            String table = "economy_hosting_" + (change.tableSuffix().equals("lease")
                    ? "lease" : "rent_" + change.tableSuffix());
            if (ddl.startsWith("CREATE TABLE IF NOT EXISTS " + table + " (")) {
                assertTrue(ddl.contains(change.before()), change.toString());
                ddl = ddl.replace(change.before(), change.after());
                replaced = true;
            }
            jdbc.execute(ddl); // Every malformed fixture must still be valid MySQL DDL.
        }
        assertTrue(replaced, change.toString());
        assertEquals(4, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN ('economy_hosting_rent_plan',
                    'economy_hosting_rent_quote','economy_hosting_lease','economy_hosting_provisioning_intent')
                """, Integer.class));
        List<String> before = tableDefinitions(jdbc);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new EconomyHostingRentSchemaInitializer(jdbc).afterPropertiesSet(), change.toString());
        assertTrue(failure.getMessage().contains(change.failureCategory()), change + ": " + failure.getMessage());
        assertEquals(before, tableDefinitions(jdbc), change.toString());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
                """, Integer.class));
        for (String table : EconomyHostingRentSchemaInitializer.TABLES) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), change + ": " + table);
        }
    }

    private List<String> tableDefinitions(JdbcTemplate jdbc) {
        return EconomyHostingRentSchemaInitializer.TABLES.stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table, (rs, row) -> rs.getString(2)))
                .toList();
    }

    private record CatalogChange(String label, String tableSuffix, String before, String after,
                                 String failureCategory) {
    }

    @Test
    void partialCatalogAndImmutablePlanOrQuoteMutationFailClosed() {
        JdbcTemplate partial = fixture.newDatabase("rent_partial").jdbc();
        partial.execute(EconomyHostingRentSchemaInitializer.tableDdlStatements().getFirst());
        IllegalStateException partialFailure = assertThrows(IllegalStateException.class,
                () -> new EconomyHostingRentSchemaInitializer(partial).afterPropertiesSet());
        assertTrue(partialFailure.getMessage().contains("Partial"), partialFailure.getMessage());

        JdbcTemplate immutable = fixture.newDatabase("rent_immutable").jdbc();
        new EconomyHostingRentSchemaInitializer(immutable).afterPropertiesSet();
        immutable.update("""
                INSERT INTO economy_hosting_rent_plan(
                    plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,
                    currency,status,tenant_id,client_id,create_time)
                VALUES('fixture-only',1,1000000000,2592000,30,'SILVER','ACTIVE','Tenant-A','Client-A',1)
                """);
        assertThrows(RuntimeException.class, () -> immutable.update(
                "UPDATE economy_hosting_rent_plan SET amount_micro=1000000001 WHERE plan_id='fixture-only'"));
        assertThrows(RuntimeException.class, () -> immutable.update(
                "DELETE FROM economy_hosting_rent_plan WHERE plan_id='fixture-only'"));
    }
}
