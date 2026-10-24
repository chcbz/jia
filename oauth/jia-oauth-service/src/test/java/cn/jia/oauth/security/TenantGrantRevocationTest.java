package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantGrantRevocationTest {
    private final TenantId tenant = TenantId.of("1");
    private final DataDomainId domain = DataDomainId.of("cyf");
    private JdbcTemplate jdbc;
    private JdbcTenantAuthorizationRepository verifier;
    private TenantGrantRevocation revocation;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:revoke_" + UUID.randomUUID()
                + ";MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE core_tenant (tenant_id VARCHAR(50) PRIMARY KEY, tenant_name VARCHAR(128), tenant_kind VARCHAR(6))");
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, state VARCHAR(32), tenant_session_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_membership (tenant_id VARCHAR(50), uid BIGINT, status VARCHAR(8), membership_revision BIGINT, PRIMARY KEY (tenant_id, uid))");
        jdbc.execute("CREATE TABLE oauth_client (id VARCHAR(100) PRIMARY KEY, client_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_grant (client_id VARCHAR(100), tenant_id VARCHAR(50), status VARCHAR(8), grant_revision BIGINT, PRIMARY KEY (client_id, tenant_id))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_domain_grant (client_id VARCHAR(100), tenant_id VARCHAR(50), data_domain_id VARCHAR(64), status VARCHAR(8), domain_revision BIGINT, PRIMARY KEY (client_id, tenant_id, data_domain_id))");
        jdbc.update("INSERT INTO core_tenant VALUES ('1','测试区','TEST')");
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',3)");
        jdbc.update("INSERT INTO core_tenant_membership VALUES ('1',17,'ACTIVE',8)");
        jdbc.update("INSERT INTO oauth_client VALUES ('registered-web','web')");
        jdbc.update("INSERT INTO oauth_client_tenant_grant VALUES ('web','1','ACTIVE',4)");
        jdbc.update("INSERT INTO oauth_client_tenant_domain_grant VALUES ('web','1','cyf','ACTIVE',2)");
        verifier = new JdbcTenantAuthorizationRepository(jdbc);
        revocation = new TenantGrantRevocation(jdbc);
    }

    @Test
    void membershipTombstoneNeverReusesAnEarlierBinding() {
        var bound = verifier.findEligibleSelection(tenant, domain, 17, "web").orElseThrow();
        assertEquals(9, revocation.revokeMember(tenant, 17, 8));
        assertFalse(verifier.isCurrent(bound, 17, "web"));
        assertThrows(IllegalStateException.class, () -> revocation.revokeMember(tenant, 17, 8));
        jdbc.update("UPDATE core_tenant_membership SET status='ACTIVE', membership_revision=10 WHERE tenant_id='1'");
        assertFalse(verifier.isCurrent(bound, 17, "web"));
        assertEquals(11, revocation.revokeMember(tenant, 17, 10));
        assertEquals(11L, value("SELECT membership_revision FROM core_tenant_membership"));
    }

    @Test
    void childRevocationChangesBothRevisionsAtomicallyAndParentRevocationIsDistinct() {
        var bound = verifier.findEligibleSelection(tenant, domain, 17, "web").orElseThrow();
        assertThrows(IllegalStateException.class, () -> revocation.revokeDomain(tenant, "web", domain, 3, 2));
        assertThrows(IllegalStateException.class, () -> revocation.revokeDomain(tenant, "web", domain, 4, 1));
        assertEquals(4L, value("SELECT grant_revision FROM oauth_client_tenant_grant"));
        assertEquals(2L, value("SELECT domain_revision FROM oauth_client_tenant_domain_grant"));
        assertEquals(5, revocation.revokeDomain(tenant, "web", domain, 4, 2));
        assertEquals(3L, value("SELECT domain_revision FROM oauth_client_tenant_domain_grant"));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT status FROM oauth_client_tenant_domain_grant", String.class));
        assertFalse(verifier.isCurrent(bound, 17, "web"));
        assertEquals(6, revocation.revokeClient(tenant, "web", 5));
        assertEquals("REVOKED", jdbc.queryForObject("SELECT status FROM oauth_client_tenant_grant", String.class));
        assertThrows(IllegalStateException.class, () -> revocation.revokeClient(tenant, "web", 5));
    }

    @Test
    void missingAndCollationFoldedKeysFailClosedWithoutChangingOtherGrants() {
        assertThrows(IllegalStateException.class, () -> revocation.revokeMember(tenant, 18, 8));
        assertThrows(IllegalStateException.class,
                () -> revocation.revokeClient(TenantId.of("2"), "web", 4));
        jdbc.update("UPDATE oauth_client SET client_id='WEB'");
        assertThrows(IllegalStateException.class, () -> revocation.revokeClient(tenant, "web", 4));
        jdbc.update("UPDATE oauth_client SET client_id='web'");
        jdbc.update("UPDATE oauth_client_tenant_grant SET client_id='WEB'");
        assertThrows(IllegalStateException.class, () -> revocation.revokeClient(tenant, "web", 4));
        assertEquals(4L, value("SELECT grant_revision FROM oauth_client_tenant_grant"));
    }

    @Test
    void competingRevocationsCannotCommitTwiceWithTheSameExpectedRevision() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return revocation.revokeMember(tenant, 17, 8);
            });
            var second = executor.submit(() -> {
                start.await();
                return revocation.revokeMember(tenant, 17, 8);
            });
            start.countDown();
            int successes = 0;
            int conflicts = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertEquals(9, future.get(10, TimeUnit.SECONDS));
                    successes++;
                } catch (ExecutionException failed) {
                    assertTrue(failed.getCause() instanceof IllegalStateException);
                    conflicts++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
            assertEquals(9L, value("SELECT membership_revision FROM core_tenant_membership"));
        }
    }

    @Test
    void clientAndDomainRevocationCompeteForTheSameParentVersion() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var client = executor.submit(() -> {
                start.await();
                return revocation.revokeClient(tenant, "web", 4);
            });
            var child = executor.submit(() -> {
                start.await();
                return revocation.revokeDomain(tenant, "web", domain, 4, 2);
            });
            start.countDown();
            int success = 0;
            for (var future : java.util.List.of(client, child)) {
                try {
                    assertEquals(5, future.get(10, TimeUnit.SECONDS));
                    success++;
                } catch (ExecutionException conflict) {
                    assertTrue(conflict.getCause() instanceof IllegalStateException);
                }
            }
            assertEquals(1, success);
            assertEquals(5L, value("SELECT grant_revision FROM oauth_client_tenant_grant"));
            assertFalse(verifier.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        }
    }

    @Test
    void clientRevocationSealsChildrenAndReactivationNeverRevivesThemImplicitly() {
        var old = verifier.findEligibleSelection(tenant, domain, 17, "web").orElseThrow();
        assertThrows(IllegalStateException.class, () -> revocation.activateMember(tenant, 17, 8));
        assertThrows(IllegalStateException.class, () -> revocation.activateClient(tenant, "web", 4));
        assertThrows(IllegalStateException.class, () -> revocation.activateDomain(tenant, "web", domain, 4, 2));
        assertEquals(5, revocation.revokeClient(tenant, "web", 4));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM oauth_client_tenant_domain_grant", String.class));
        assertEquals(3L, value("SELECT domain_revision FROM oauth_client_tenant_domain_grant"));
        assertFalse(verifier.isCurrent(old, 17, "web"));

        assertEquals(6, revocation.activateClient(tenant, "web", 5));
        assertFalse(verifier.findEligibleSelection(tenant, domain, 17, "web").isPresent());
        assertThrows(IllegalStateException.class, () -> revocation.activateClient(tenant, "web", 5));
        assertThrows(IllegalStateException.class, () -> revocation.activateDomain(tenant, "web", domain, 5, 3));
        assertEquals(7, revocation.activateDomain(tenant, "web", domain, 6, 3));
        var restored = verifier.findEligibleSelection(tenant, domain, 17, "web").orElseThrow();
        assertEquals("7", restored.clientTenantGrantRevision());
        assertFalse(verifier.isCurrent(old, 17, "web"));
        assertEquals(9, revocation.revokeMember(tenant, 17, 8));
        assertFalse(verifier.isCurrent(restored, 17, "web"));
        assertEquals(10, revocation.activateMember(tenant, 17, 9));
        assertFalse(verifier.isCurrent(restored, 17, "web"));
        assertEquals("10", verifier.findEligibleSelection(tenant, domain, 17, "web")
                .orElseThrow().membershipRevision());
    }

    @Test
    void legacyActiveChildBlocksParentReactivationAndMissingTombstonesCannotBeCreated() {
        jdbc.update("UPDATE oauth_client_tenant_grant SET status='REVOKED', grant_revision=5 "
                + "WHERE client_id='web' AND tenant_id='1'");
        assertThrows(IllegalStateException.class, () -> revocation.activateClient(tenant, "web", 5));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM oauth_client_tenant_grant", String.class));
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET status='REVOKED', domain_revision=3 "
                + "WHERE client_id='web' AND tenant_id='1'");
        assertEquals(6, revocation.activateClient(tenant, "web", 5));
        assertThrows(IllegalStateException.class, () -> revocation.activateMember(tenant, 18, 0));
        assertThrows(IllegalStateException.class,
                () -> revocation.activateDomain(tenant, "web", DataDomainId.of("other"), 6, 0));
        assertFalse(verifier.findEligibleSelection(tenant, domain, 17, "web").isPresent());
    }

    @Test
    void foldedChildClientAndDomainKeysAreRejectedBeforeRevokeOrActivation() {
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='WEB'");
        assertThrows(IllegalStateException.class,
                () -> revocation.revokeDomain(tenant, "web", domain, 4, 2));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM oauth_client_tenant_domain_grant", String.class));
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='web', data_domain_id='CYF'");
        assertThrows(IllegalStateException.class,
                () -> revocation.revokeDomain(tenant, "web", domain, 4, 2));
        assertEquals(4L, value("SELECT grant_revision FROM oauth_client_tenant_grant"));
        assertEquals(2L, value("SELECT domain_revision FROM oauth_client_tenant_domain_grant"));

        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='WEB', "
                + "data_domain_id='cyf', status='REVOKED', domain_revision=3");
        assertThrows(IllegalStateException.class,
                () -> revocation.activateDomain(tenant, "web", domain, 4, 3));
        jdbc.update("UPDATE oauth_client_tenant_domain_grant SET client_id='web', data_domain_id='CYF'");
        assertThrows(IllegalStateException.class,
                () -> revocation.activateDomain(tenant, "web", domain, 4, 3));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM oauth_client_tenant_domain_grant", String.class));
        assertEquals(4L, value("SELECT grant_revision FROM oauth_client_tenant_grant"));
        assertEquals(3L, value("SELECT domain_revision FROM oauth_client_tenant_domain_grant"));
    }

    @Test
    void competingReactivationsAdvanceTombstoneRevisionOnlyOnce() throws Exception {
        assertEquals(9, revocation.revokeMember(tenant, 17, 8));
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return revocation.activateMember(tenant, 17, 9); });
            var second = executor.submit(() -> { start.await(); return revocation.activateMember(tenant, 17, 9); });
            start.countDown();
            int successes = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertEquals(10, future.get(10, TimeUnit.SECONDS));
                    successes++;
                } catch (ExecutionException conflict) {
                    assertTrue(conflict.getCause() instanceof IllegalStateException);
                }
            }
            assertEquals(1, successes);
            assertEquals(10L, value("SELECT membership_revision FROM core_tenant_membership"));
        }
    }

    @Test
    void grantChangesJoinTheCallerTransactionSoFailedAuditCannotLeaveAnActiveGrant() {
        assertEquals(9, revocation.revokeMember(tenant, 17, 8));
        var outer = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        outer.execute(status -> {
            assertEquals(10, revocation.activateMember(tenant, 17, 9));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT status FROM core_tenant_membership WHERE tenant_id='1' AND uid=17", String.class));
            status.setRollbackOnly(); // Future management audit or idempotency insert failed.
            return null;
        });
        assertEquals(9L, value("SELECT membership_revision FROM core_tenant_membership"));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM core_tenant_membership WHERE tenant_id='1' AND uid=17", String.class));

        outer.execute(status -> {
            assertEquals(10, revocation.activateMember(tenant, 17, 9));
            return null;
        });
        assertEquals(10L, value("SELECT membership_revision FROM core_tenant_membership"));
    }

    private long value(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
