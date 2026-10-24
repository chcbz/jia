package cn.jia.core.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantModuleAdmissionGuardTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String CREDENTIAL = "c".repeat(64);
    // Independent fixture digest: SHA-256 of NUL-separated (protocol, deployment, generation, secret).
    private static final String CREDENTIAL_DIGEST = "3e5161d30ae1008e3588272520fd5e5b6c8b68aae2bc6a42e2dc7c407582afe4";
    private final UserAdmissionScope area1 = new UserAdmissionScope(new BusinessScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "owner", "web"), 9, 4, 2, 5, 8);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private TenantModuleAdmissionGuard guard;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:admission_" + UUID.randomUUID()
                + ";MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, "
                + "state VARCHAR(32), tenant_session_epoch BIGINT, admission_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "drain_sealed BOOLEAN, schema_version BIGINT, mapping_digest CHAR(64), "
                + "PRIMARY KEY(tenant_id,module_id))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_grant (client_id VARCHAR(100), tenant_id VARCHAR(50), "
                + "status VARCHAR(8), grant_revision BIGINT, PRIMARY KEY(client_id,tenant_id))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_domain_grant (client_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), status VARCHAR(8), domain_revision BIGINT, "
                + "PRIMARY KEY(client_id,tenant_id,data_domain_id))");
        jdbc.execute("CREATE TABLE core_tenant_membership (tenant_id VARCHAR(50), uid BIGINT, "
                + "status VARCHAR(8), membership_revision BIGINT, PRIMARY KEY(tenant_id,uid))");

        jdbc.execute("CREATE TABLE user_info (id BIGINT PRIMARY KEY, jiacn VARCHAR(128), "
                + "account_state VARCHAR(16), auth_epoch BIGINT)");
        jdbc.execute("CREATE TABLE oauth_client (id VARCHAR(100) PRIMARY KEY, client_id VARCHAR(100))");
        jdbc.update("INSERT INTO user_info VALUES (9,'owner','ACTIVE',4)");
        jdbc.update("INSERT INTO oauth_client VALUES ('registered-web','web')");
        jdbc.execute("CREATE TABLE core_deployment_registration (deployment_id VARCHAR(100) PRIMARY KEY, "
                + "stage VARCHAR(20), artifact_digest CHAR(64), credential_digest CHAR(64), status VARCHAR(16), "
                + "registration_revision BIGINT, worker_generation BIGINT)");
        jdbc.execute("CREATE TABLE core_deployment_tenant_grant (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), execution_pool_id VARCHAR(100), "
                + "status VARCHAR(16), grant_revision BIGINT, allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id))");
        jdbc.execute("CREATE TABLE core_deployment_module_admission (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "admission_epoch BIGINT, schema_version BIGINT, registration_revision BIGINT, "
                + "grant_revision BIGINT, status VARCHAR(16), allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id,module_id))");
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',2,3)");
        jdbc.update("INSERT INTO oauth_client_tenant_grant VALUES ('web','1','ACTIVE',8)");
        jdbc.update("INSERT INTO oauth_client_tenant_domain_grant VALUES ('web','1','cyf','ACTIVE',3)");
        jdbc.update("INSERT INTO core_tenant_membership VALUES ('1',9,'ACTIVE',5)");
        jdbc.execute("CREATE TABLE business_probe (id INT PRIMARY KEY)");
        jdbc.update("INSERT INTO core_tenant_module_control VALUES ('1','conversation','DOMAIN',7,4,TRUE,2,?)", DIGEST);
        jdbc.update("INSERT INTO core_deployment_registration VALUES ('local-1','LOCAL_TEST',?,?,'ACTIVE',11,4)",
                DIGEST, CREDENTIAL_DIGEST);
        jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES ('local-1','1','cyf','test-pool','ACTIVE',8,TRUE,TRUE)");
        jdbc.update("INSERT INTO core_deployment_module_admission "
                + "VALUES ('local-1','1','cyf','conversation','DOMAIN',7,4,3,2,11,8,'ACTIVE',TRUE,TRUE)");
        guard = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST, "test-pool", CREDENTIAL, 4, 11);
    }

    @Test
    void admitsOnlyMatchingDomainWithActiveTransaction() {
        var result = tx.execute(status -> guard.withAdmission(area1, "conversation",
                TenantModuleAdmissionGuard.Operation.WRITE, mode -> {
                    assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, mode);
                    assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    return jdbc.update("INSERT INTO business_probe VALUES (1)");
                }));
        assertEquals(1, result);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM business_probe", Integer.class));
        assertThrows(IllegalStateException.class,
                () -> guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode));
        tx.setReadOnly(false);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ,
                        mode -> jdbc.update("INSERT INTO business_probe VALUES (2)"))));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM business_probe", Integer.class));
        tx.setReadOnly(true);
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.READ);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.WRITE, mode -> mode)));
    }

    @Test
    void deniesCrossTenantDomainAndCollationEquivalentAuthorityKeys() {
        assertDenied(new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"), "owner", "web"));
        assertDenied(new BusinessScope(TenantId.of("1"), DataDomainId.of("other"), "owner", "web"));
        jdbc.update("UPDATE core_deployment_tenant_grant SET data_domain_id='CYF'");
        assertDenied(area1);
        jdbc.update("UPDATE core_deployment_tenant_grant SET data_domain_id='cyf', tenant_id='01'");
        assertDenied(area1);
    }

    @Test
    void deniesMissingStateQuiescenceAndTenantZeroLegacyMisuse() {
        jdbc.update("UPDATE core_tenant_control SET state='AUTH_CLOSED'");
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.READ);
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        jdbc.update("UPDATE core_tenant_control SET state='WRITE_CLOSED_CLAIM_CLOSED', admission_epoch=4");
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.READ);
        assertDenied(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        jdbc.update("UPDATE core_tenant_control SET state='DRAINING'");
        assertDenied(area1);
        jdbc.update("UPDATE core_tenant_control SET state='DISABLED'");
        assertDenied(area1);
        jdbc.update("UPDATE core_tenant_control SET state='ENABLED', admission_epoch=3");
        jdbc.update("UPDATE core_tenant_module_control SET mode_state='QUIESCING'");
        assertDenied(area1);
        jdbc.update("UPDATE core_tenant_module_control SET mode_state='LEGACY_CLIENT'");
        jdbc.update("UPDATE core_deployment_module_admission SET mode_state='LEGACY_CLIENT'");
        assertDenied(area1);
        jdbc.update("DELETE FROM core_tenant_module_control");
        assertDenied(area1);
    }

    @Test
    void rejectsEveryVersionDriftAndRevocation() {
        for (String update : new String[] {
                "UPDATE core_tenant_control SET admission_epoch=4",
                "UPDATE core_tenant_control SET tenant_session_epoch=3",
                "UPDATE core_tenant_module_control SET mode_revision=8",
                "UPDATE core_tenant_module_control SET migration_epoch=5",
                "UPDATE core_tenant_module_control SET schema_version=3",
                "UPDATE core_deployment_registration SET registration_revision=12",
                "UPDATE core_deployment_registration SET worker_generation=5",
                "UPDATE core_deployment_tenant_grant SET grant_revision=9",
                "UPDATE core_deployment_module_admission SET status='REVOKED'",
                "UPDATE oauth_client_tenant_grant SET status='REVOKED'",
                "UPDATE oauth_client_tenant_grant SET grant_revision=9",
                "UPDATE oauth_client_tenant_grant SET client_id='WEB'",
                "UPDATE oauth_client_tenant_domain_grant SET status='REVOKED'",
                "UPDATE oauth_client_tenant_domain_grant SET data_domain_id='CYF'",
                "UPDATE core_tenant_membership SET status='REVOKED'",
                "UPDATE core_tenant_membership SET membership_revision=6",
                "UPDATE core_tenant_membership SET uid=10",
                "UPDATE oauth_client_tenant_domain_grant SET domain_revision=NULL"
        }) {
            jdbc.update(update);
            assertDenied(area1);
            setUp();
        }
        jdbc.update("UPDATE core_tenant_module_control SET drain_sealed=FALSE");
        assertDenied(area1);
        jdbc.update("UPDATE core_tenant_module_control SET drain_sealed=TRUE, mapping_digest=NULL");
        assertDenied(area1);
    }

    @Test
    void rejectsIdentityRevokedAfterJwtValidationButBeforeAdmission() {
        jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=9");
        assertDenied(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        jdbc.update("UPDATE user_info SET auth_epoch=4, account_state='DISABLED' WHERE id=9");
        assertDenied(area1);
        jdbc.update("UPDATE user_info SET account_state='ACTIVE', jiacn='Owner' WHERE id=9");
        assertDenied(area1);
        jdbc.update("UPDATE user_info SET jiacn='owner'");
        jdbc.update("UPDATE oauth_client SET client_id='WEB'");
        assertDenied(area1);
        jdbc.update("UPDATE oauth_client SET client_id='web'");
        jdbc.update("INSERT INTO oauth_client VALUES ('duplicate','web')");
        assertDenied(area1);
    }

    @Test
    void accountRevocationWaitsForInFlightAdmissionThenDeniesStaleToken() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var revokeStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> business = executor.submit(() -> tx.execute(status ->
                    guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.WRITE, mode -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        return jdbc.update("INSERT INTO business_probe VALUES (10)");
                    })));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> revocation = executor.submit(() -> {
                revokeStarted.countDown();
                return jdbc.update("UPDATE user_info SET auth_epoch=5 WHERE id=9");
            });
            assertTrue(revokeStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> revocation.get(200, TimeUnit.MILLISECONDS));
            release.countDown();
            business.get(10, TimeUnit.SECONDS);
            assertEquals(1, revocation.get(10, TimeUnit.SECONDS));
            assertDenied(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void callbackWriteRollsBackInTheAdmissionTransaction() {
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.WRITE, mode -> {
                    jdbc.update("INSERT INTO business_probe VALUES (9)");
                    throw new IllegalStateException("rollback");
                })));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM business_probe", Integer.class));
    }

    @Test
    void generationAndCapabilitiesCannotBeInherited() {
        var stale = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST, "test-pool", CREDENTIAL, 3, 11);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                stale.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
        jdbc.update("UPDATE core_deployment_tenant_grant SET allow_write=FALSE");
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.READ);
        assertDenied(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        jdbc.update("UPDATE core_deployment_tenant_grant SET allow_write=TRUE");
        jdbc.update("UPDATE core_deployment_module_admission SET allow_write=FALSE");
        assertAdmitted(area1, TenantModuleAdmissionGuard.Operation.READ);
        assertDenied(area1, TenantModuleAdmissionGuard.Operation.WRITE);
        jdbc.update("UPDATE core_deployment_registration SET status='DRAINING'");
        assertDenied(area1);
    }

    @Test
    void credentialPoolAndGenerationMustMatchControlPlaneRegistration() {
        tx.setReadOnly(true);
        var stolenDigest = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST,
                "test-pool", CREDENTIAL_DIGEST, 4, 11);
        var wrongPool = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST,
                "other-pool", CREDENTIAL, 4, 11);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                stolenDigest.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                wrongPool.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
        jdbc.update("UPDATE core_deployment_registration SET credential_digest=?", "b".repeat(64));
        assertDenied(area1);
        jdbc.update("UPDATE core_deployment_registration SET credential_digest=?, worker_generation=5", CREDENTIAL_DIGEST);
        var forgedGeneration = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST,
                "test-pool", CREDENTIAL, 5, 11);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                forgedGeneration.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
        jdbc.update("UPDATE core_deployment_registration SET worker_generation=4");
        jdbc.update("UPDATE core_deployment_tenant_grant SET execution_pool_id='other-pool'");
        assertDenied(area1);
    }

    @Test
    void rotatedCredentialOnlyAdmitsNewWorkerGeneration() {
        String rotatedDigest = "508c0a803e8b1aa52bbd630cd28a4f056cdffdbf31963728a8f73aa1246dea66";
        jdbc.update("UPDATE core_deployment_registration "
                + "SET credential_digest=?, worker_generation=5, registration_revision=12", rotatedDigest);
        jdbc.update("UPDATE core_deployment_module_admission SET registration_revision=12");
        assertDenied(area1);
        var rotated = new TenantModuleAdmissionGuard(jdbc, "local-1", DIGEST,
                "test-pool", "d".repeat(64), 5, 12);
        tx.setReadOnly(true);
        assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, tx.execute(status ->
                rotated.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
    }

    @Test
    void workerAdmissionRejectsEveryScopeAndFenceDrift() {
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.PUBLISH);
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);

        for (WorkerScope drift : new WorkerScope[] {
                new WorkerScope(TenantId.of("0"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 4, 11, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("other"), "test-pool",
                        "local-1", 4, 11, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "other-pool",
                        "local-1", 4, 11, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "other-deployment", 4, 11, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 5, 11, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 4, 12, 3, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 4, 11, 4, "conversation", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 4, 11, 3, "agent-command", 4),
                new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                        "local-1", 4, 11, 3, "conversation", 5)
        }) {
            assertWorkerDenied(drift, TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
            assertWorkerDenied(drift, TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);
        }
    }

    @Test
    void everyWorkerOperationFailsClosedWithoutImmutableDrainProof() {
        jdbc.update("UPDATE core_tenant_control SET state='AUTH_CLOSED'");
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.PUBLISH);
        assertWorkerAdmitted(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);

        jdbc.update("UPDATE core_tenant_control SET state='WRITE_CLOSED_CLAIM_CLOSED', admission_epoch=4");
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.PUBLISH);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);

        jdbc.update("UPDATE core_tenant_control SET state='DRAINING'");
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.PUBLISH);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);

        jdbc.update("UPDATE core_tenant_control SET state='DISABLED'");
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.PUBLISH);
        assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT);
    }

    @Test
    void workerAdmissionFailsClosedOnEveryAuthorityRevocation() {
        for (String update : new String[] {
                "UPDATE core_deployment_registration SET status='DRAINING'",
                "UPDATE core_deployment_registration SET registration_revision=12",
                "UPDATE core_deployment_registration SET worker_generation=5",
                "UPDATE core_deployment_tenant_grant SET status='REVOKED'",
                "UPDATE core_deployment_tenant_grant SET execution_pool_id='other-pool'",
                "UPDATE core_deployment_tenant_grant SET allow_write=FALSE",
                "UPDATE core_deployment_module_admission SET status='REVOKED'",
                "UPDATE core_deployment_module_admission SET admission_epoch=4",
                "UPDATE core_deployment_module_admission SET migration_epoch=5",
                "UPDATE core_deployment_module_admission SET registration_revision=12",
                "UPDATE core_deployment_module_admission SET grant_revision=9",
                "UPDATE core_deployment_module_admission SET allow_write=FALSE"
        }) {
            jdbc.update(update);
            assertWorkerDenied(workerScope(), TenantModuleAdmissionGuard.WorkerOperation.CLAIM);
            setUp();
        }
    }

    @Test
    void admissionSnapshotReturnsExactProducerScopeAndProducerReadmissionSucceeds() {
        ProducerScope expected = producerScope();
        tx.setReadOnly(false);
        assertEquals(expected, tx.execute(status -> guard.withAdmissionSnapshot(
                area1, "conversation", TenantModuleAdmissionGuard.Operation.WRITE, snapshot -> {
                    assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, snapshot.mode());
                    return snapshot.producerScope();
                })));
        assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, tx.execute(status ->
                guard.withProducerAdmission(expected, TenantModuleAdmissionGuard.Operation.WRITE, mode -> mode)));
    }

    @Test
    void producerReadmissionFailsClosedOnEpochMigrationPoolAndDeploymentDrift() {
        ProducerScope expected = producerScope();
        for (String update : new String[] {
                "UPDATE core_tenant_control SET admission_epoch=4",
                "UPDATE core_tenant_module_control SET migration_epoch=5",
                "UPDATE core_deployment_tenant_grant SET execution_pool_id='other-pool'",
                "UPDATE core_deployment_registration SET registration_revision=12"
        }) {
            jdbc.update(update);
            tx.setReadOnly(false);
            assertThrows(RuntimeException.class, () -> tx.execute(status ->
                    guard.withProducerAdmission(expected, TenantModuleAdmissionGuard.Operation.WRITE, mode -> mode)),
                    update);
            setUp();
        }
        tx.setReadOnly(true);
        assertThrows(RuntimeException.class, () -> tx.execute(status ->
                guard.withProducerAdmission(expected, TenantModuleAdmissionGuard.Operation.WRITE, mode -> mode)));
    }

    @Test
    void refusesWrongDeploymentArtifactAndAbsentSchema() {
        var wrong = new TenantModuleAdmissionGuard(jdbc, "local-1", "b".repeat(64), "test-pool", CREDENTIAL, 4, 11);
        tx.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> tx.execute(status ->
                wrong.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
        jdbc.execute("DROP TABLE core_deployment_module_admission");
        assertThrows(RuntimeException.class, () -> tx.execute(status ->
                guard.withAdmission(area1, "conversation", TenantModuleAdmissionGuard.Operation.READ, mode -> mode)));
    }

    private void assertDenied(BusinessScope scope) {
        assertDenied(new UserAdmissionScope(scope, 9, 4, 2, 5, 8), TenantModuleAdmissionGuard.Operation.READ);
    }

    private void assertDenied(UserAdmissionScope scope) {
        assertDenied(scope, TenantModuleAdmissionGuard.Operation.READ);
    }

    private void assertDenied(UserAdmissionScope scope, TenantModuleAdmissionGuard.Operation operation) {
        tx.setReadOnly(operation == TenantModuleAdmissionGuard.Operation.READ);
        assertThrows(RuntimeException.class, () -> tx.execute(status ->
                guard.withAdmission(scope, "conversation", operation, mode -> mode)));
    }

    private ProducerScope producerScope() {
        return new ProducerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "test-pool", "local-1", 3, "conversation", 4);
    }

    private WorkerScope workerScope() {
        return new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"), "test-pool",
                "local-1", 4, 11, 3, "conversation", 4);
    }

    private void assertWorkerDenied(
            WorkerScope scope, TenantModuleAdmissionGuard.WorkerOperation operation) {
        tx.setReadOnly(false);
        assertThrows(RuntimeException.class, () -> tx.execute(status ->
                guard.withWorkerAdmission(scope, operation, mode -> mode)));
    }

    private void assertWorkerAdmitted(
            WorkerScope scope, TenantModuleAdmissionGuard.WorkerOperation operation) {
        tx.setReadOnly(false);
        assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, tx.execute(status ->
                guard.withWorkerAdmission(scope, operation, mode -> mode)));
    }

    private void assertAdmitted(UserAdmissionScope scope, TenantModuleAdmissionGuard.Operation operation) {
        tx.setReadOnly(operation == TenantModuleAdmissionGuard.Operation.READ);
        assertEquals(TenantModuleAdmissionGuard.Mode.DOMAIN, tx.execute(status ->
                guard.withAdmission(scope, "conversation", operation, mode -> mode)));
    }
}
