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
    void managedCredentialAndFullFreeCatalogDefinitionsAreFailClosed() {
        assertIncompatibleCompleteCatalog(new CatalogChange("key_ref", "intent", "managed_api_key_id       VARCHAR(100) DEFAULT NULL",
                "managed_api_key_id       VARCHAR(100) COLLATE utf8mb4_0900_ai_ci DEFAULT NULL", "columns"));
        assertIncompatibleCompleteCatalog(new CatalogChange("free_time", "reprovision", "paid_through BIGINT NOT NULL",
                "paid_through BIGINT DEFAULT NULL", "columns"));
        assertIncompatibleCompleteCatalog(new CatalogChange("free_live", "reprovision", "UNIQUE KEY uk_hosting_reprovision_live",
                "KEY uk_hosting_reprovision_live", "indexes"));
    }

    @Test
    void runtimeLinkageWidthsDefaultsUniquenessAndPositiveTargetAreFailClosed() {
        assertIncompatibleCompleteCatalog(new CatalogChange("runtime_width", "intent", "runtime_installation_id  VARCHAR(100) NULL",
                "runtime_installation_id  VARCHAR(99) NULL", "columns"));
        assertIncompatibleCompleteCatalog(new CatalogChange("runtime_default", "intent", "runtime_provision_generation BIGINT NOT NULL DEFAULT 0",
                "runtime_provision_generation BIGINT NOT NULL DEFAULT 1", "columns"));
        assertIncompatibleCompleteCatalog(new CatalogChange("runtime_unique", "intent", "UNIQUE KEY uk_hosting_intent_installation",
                "KEY uk_hosting_intent_installation", "indexes"));
    }

    @Test
    void fullCatalogWithSameColumnNamesButIncompatibleDefinitionsFailsWithoutRepair() {
        for (CatalogChange change : List.of(
                new CatalogChange("type", "plan", "amount_wen       BIGINT NOT NULL",
                        "amount_wen       DECIMAL(20,0) NOT NULL", "columns"),
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
    void applicationPaidPeriodSnapshotColumnsAndConstraintMustMatchExactly() {
        assertIncompatibleCompleteCatalog(new CatalogChange("paid_period_type", "intent",
                "paid_from                BIGINT DEFAULT NULL", "paid_from                DECIMAL(20,0) DEFAULT NULL", "columns"));
        assertIncompatibleCompleteCatalog(new CatalogChange("paid_default", "intent",
                "paid_through             BIGINT DEFAULT NULL", "paid_through             BIGINT DEFAULT 1", "columns"));
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

        assertThrows(RuntimeException.class, () -> insertLease(jdbc, "held-refund", "REFUNDED", 1));

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_lease", Integer.class));
    }

    @Test
    void retainedMigrationPreservesTermsIdentityAndReadyReprovisionWithoutNewMoney() throws Exception {
        JdbcTemplate jdbc = legacyHostingFixture("retained");
        var leases = jdbc.queryForList("SELECT lease_id,agent_id,binding_id,status,paid_from,paid_through,latest_intent_id,version FROM economy_hosting_lease ORDER BY id");
        var identities = jdbc.queryForList("SELECT intent_id,lease_id,status,managed_api_key_id,runtime_installation_id,runtime_manifest_sha256,runtime_provision_generation,version FROM economy_hosting_provisioning_intent ORDER BY id");
        var reprovision = jdbc.queryForList("SELECT * FROM economy_hosting_reprovision");
        jdbc.execute("CALL cyf_beta_retain_hosting_terms()");
        new EconomyHostingRentSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(leases, jdbc.queryForList("SELECT lease_id,agent_id,binding_id,status,paid_from,paid_through,latest_intent_id,version FROM economy_hosting_lease ORDER BY id"));
        assertEquals(identities, jdbc.queryForList("SELECT intent_id,lease_id,status,managed_api_key_id,runtime_installation_id,runtime_manifest_sha256,runtime_provision_generation,version FROM economy_hosting_provisioning_intent ORDER BY id"));
        assertEquals(reprovision, jdbc.queryForList("SELECT * FROM economy_hosting_reprovision"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_provisioning_intent WHERE settlement_kind='RETAINED' AND amount_wen=0 AND reserve_transaction_id IS NULL AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL AND escrow_version=0", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_rent_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_rent_quote", Integer.class));
        // A replay is not a second conversion. Restore snapshots for partial failures, never guess.
        assertThrows(RuntimeException.class, () -> jdbc.execute("CALL cyf_beta_retain_hosting_terms()"));
        assertEquals(leases, jdbc.queryForList("SELECT lease_id,agent_id,binding_id,status,paid_from,paid_through,latest_intent_id,version FROM economy_hosting_lease ORDER BY id"));
        jdbc.execute("DROP PROCEDURE cyf_beta_retain_hosting_terms");
    }

    @Test
    void retainedMigrationRejectsUnsettledIntentBeforeChangingSchemaOrTerms() throws Exception {
        JdbcTemplate jdbc = legacyHostingFixture("retain_pending");
        jdbc.update("UPDATE economy_hosting_provisioning_intent SET status='PROVISIONING_UNKNOWN' WHERE intent_id='intent-1'");
        assertThrows(RuntimeException.class, () -> jdbc.execute("CALL cyf_beta_retain_hosting_terms()"));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name='amount_micro'", Integer.class));
        assertEquals(7L, jdbc.queryForObject("SELECT amount_micro FROM economy_hosting_lease WHERE lease_id='lease-1'", Long.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT IS_FREE_LOCK('cyf:economy-v0:hosting-rent-schema')", Integer.class));
    }

    private JdbcTemplate legacyHostingFixture(String name) throws Exception {
        JdbcTemplate jdbc = fixture.newDatabase(name).jdbc();
        for (String ddl : EconomyHostingRentSchemaInitializer.tableDdlStatements()) {
            jdbc.execute(ddl.replace("amount_wen", "amount_micro")
                    .replace("settlement_kind          VARCHAR(16) NOT NULL DEFAULT 'PAID',", "")
                    .replace("reserve_transaction_id   VARCHAR(100) DEFAULT NULL", "reserve_transaction_id   VARCHAR(100) NOT NULL"));
        }
        jdbc.update("INSERT INTO economy_hosting_rent_plan(plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,currency,status,tenant_id,client_id,create_time) VALUES('plan',1,7,100,300,'SILVER','ACTIVE','t','c',1)");
        jdbc.update("INSERT INTO economy_hosting_rent_quote(quote_id,quote_purpose,plan_id,plan_version,amount_micro,period_seconds,principal_type,principal_id,persona_code,agent_id,idempotency_key,request_hash,expires_at,tenant_id,client_id,create_time) VALUES('quote-1','INITIAL','plan',1,7,100,'USER','u','p','agent-1','quote-key',UNHEX(REPEAT('11',32)),200,'t','c',1)");
        for (int n = 1; n <= 2; n++) {
            String state = n == 1 ? "ACTIVE" : "REFUNDED";
            jdbc.update("INSERT INTO economy_hosting_lease(lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,live_slot,plan_id,plan_version,amount_micro,period_seconds,status,paid_from,paid_through,latest_intent_id,version,tenant_id,client_id,create_time,update_time) VALUES(?,'USER','u','p',?,? ,?,'plan',1,7,100,?,100,200,?,3,'t','c',1,2)", "lease-" + n, "agent-" + n, "binding-" + n, n == 1 ? 1 : null, state, "intent-" + n);
            jdbc.update("INSERT INTO economy_hosting_provisioning_intent(intent_id,lease_id,quote_id,quote_purpose,principal_type,principal_id,persona_code,agent_id,amount_micro,period_seconds,status,reserve_idempotency_key,reserve_request_hash,reserve_transaction_id,reserved_at,escrow_version,capture_transaction_id,refund_transaction_id,managed_api_key_id,runtime_installation_id,runtime_manifest_sha256,runtime_provision_generation,paid_from,paid_through,version,tenant_id,client_id,create_time,update_time) VALUES(?,?,?,'INITIAL','USER','u','p',?,7,100,?,?,UNHEX(REPEAT('22',32)),?,1,2,?,?,?, ?,REPEAT('a',64),2,100,200,3,'t','c',1,2)", "intent-" + n, "lease-" + n, "quote-" + n, "agent-" + n, state, "reserve-key-" + n, "reserve-" + n, n == 1 ? "capture-1" : null, n == 2 ? "refund-2" : null, "managed-key-" + n, "installation-" + n);
        }
        jdbc.update("INSERT INTO economy_hosting_reprovision(request_id,lease_id,intent_id,agent_id,persona_code,principal_id,idempotency_key,request_hash,lease_version,paid_through,requested_at,status,version,service_ready_at,runtime_target_generation,evidence_ref,tenant_id,client_id) VALUES('request-1','lease-1','intent-1','agent-1','p','u','reprovision-key',UNHEX(REPEAT('33',32)),3,200,120,'SERVICE_READY',2,130,3,'ready-evidence','t','c')");
        for (String table : List.of("plan", "quote")) for (String event : List.of("UPDATE", "DELETE")) {
            jdbc.execute("CREATE TRIGGER trg_hosting_" + table + "_no_" + event.toLowerCase()
                    + " BEFORE " + event + " ON economy_hosting_rent_" + table
                    + " FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='immutable hosting-rent row'; END");
        }
        String sql = new org.springframework.core.io.ClassPathResource("db/migration-retained-hosting-terms-20261106.sql")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        jdbc.execute(sql.substring(sql.indexOf("CREATE PROCEDURE"), sql.indexOf("END$$") + 3));
        return jdbc;
    }

    private void insertLease(JdbcTemplate jdbc, String leaseId, String status, Integer liveSlot) {
        jdbc.update("""
                INSERT INTO economy_hosting_lease(
                    lease_id,principal_type,principal_id,persona_code,agent_id,live_slot,plan_id,plan_version,
                    amount_wen,period_seconds,status,latest_intent_id,version,tenant_id,client_id,create_time,update_time)
                VALUES(?,'USER','fixture-user','fixture-persona','fixture-agent',?,'fixture-only',1,
                       1000000000,2592000,?,?,1,'Tenant-A','Client-A',1,1)
                """, leaseId, liveSlot, status, "intent-" + leaseId);
    }

    private void assertIncompatibleCompleteCatalog(CatalogChange change) {
        JdbcTemplate jdbc = fixture.newDatabase(change.label()).jdbc();
        boolean replaced = false;
        for (String ddl : EconomyHostingRentSchemaInitializer.tableDdlStatements()) {
            String table = "economy_hosting_" + switch (change.tableSuffix()) {
                case "lease" -> "lease";
                case "intent" -> "provisioning_intent";
                case "reprovision" -> "reprovision";
                default -> "rent_" + change.tableSuffix();
            };
            if (ddl.startsWith("CREATE TABLE IF NOT EXISTS " + table + " (")) {
                assertTrue(ddl.contains(change.before()), change.toString());
                ddl = ddl.replace(change.before(), change.after());
                replaced = true;
            }
            jdbc.execute(ddl); // Every malformed fixture must still be valid MySQL DDL.
        }
        assertTrue(replaced, change.toString());
        assertEquals(5, jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name IN ('economy_hosting_rent_plan',
                    'economy_hosting_rent_quote','economy_hosting_lease','economy_hosting_provisioning_intent','economy_hosting_reprovision')
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
                    plan_id,plan_version,amount_wen,period_seconds,quote_ttl_seconds,
                    currency,status,tenant_id,client_id,create_time)
                VALUES('fixture-only',1,1000000000,2592000,30,'WEN','ACTIVE','Tenant-A','Client-A',1)
                """);
        assertThrows(RuntimeException.class, () -> immutable.update(
                "UPDATE economy_hosting_rent_plan SET amount_wen=1000000001 WHERE plan_id='fixture-only'"));
        assertThrows(RuntimeException.class, () -> immutable.update(
                "DELETE FROM economy_hosting_rent_plan WHERE plan_id='fixture-only'"));
    }
}
