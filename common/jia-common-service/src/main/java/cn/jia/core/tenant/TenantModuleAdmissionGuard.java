package cn.jia.core.tenant;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Transaction-bound business admission. No instance-wide tenant allowlist is
 * sufficient: the control, module mode, deployment grant and versioned admission
 * must all still be current at the exact read/write transaction boundary.
 *
 * <p>The caller supplies a scope constructed at a trusted authentication boundary,
 * never a request body. This guard does not replace row owner/team ACL or scoped SQL.
 * Deployments construct it with their validated registration identity; it is not
 * auto-created from arbitrary client headers.</p>
 */
public final class TenantModuleAdmissionGuard {
    public enum Operation { READ, WRITE }
    public enum WorkerOperation { CLAIM, EXECUTE, PUBLISH, SETTLEMENT }
    public enum Mode { LEGACY_CLIENT, DOMAIN }
    public record AdmissionSnapshot(Mode mode, ProducerScope producerScope) {
        public AdmissionSnapshot {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(producerScope, "producerScope");
        }
    }

    private final JdbcTemplate jdbc;
    private final String deploymentId;
    private final String artifactDigest;
    private final String executionPoolId;
    private final byte[] credentialDigest;
    private final String lockClause;
    private final long expectedWorkerGeneration;
    private final long expectedRegistrationRevision;

    public TenantModuleAdmissionGuard(JdbcTemplate jdbc, String deploymentId, String artifactDigest,
            String executionPoolId, String registrationCredential,
            long expectedWorkerGeneration, long expectedRegistrationRevision) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null || !exact(deploymentId, 100)
                || artifactDigest == null || !artifactDigest.matches("[0-9a-f]{64}")
                || executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")
                || registrationCredential == null || !registrationCredential.matches("[0-9a-f]{64}")
                || expectedWorkerGeneration < 0 || expectedRegistrationRevision < 0) {
            throw new IllegalArgumentException("A registered deployment, pool, and credential are required");
        }
        this.deploymentId = deploymentId;
        this.artifactDigest = artifactDigest;
        this.executionPoolId = executionPoolId;
        // Retain only the derived verifier, not the bearer secret. The control
        // plane must rotate the credential on every new worker generation.
        this.credentialDigest = credentialDigest(deploymentId, expectedWorkerGeneration, registrationCredential);
        this.expectedWorkerGeneration = expectedWorkerGeneration;
        this.expectedRegistrationRevision = expectedRegistrationRevision;
        String database = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName());
        if (!"MySQL".equals(database) && !"H2".equals(database)) throw denied();
        // H2 tests use its stronger exclusive lock; production MySQL uses shared locks.
        lockClause = "MySQL".equals(database) ? "FOR SHARE" : "FOR UPDATE";
    }

    /** Lock order: tenant, module, user, OAuth client, client grant, domain grant, member, deployment, deployment grant, admission. */
    public <T> T withAdmission(UserAdmissionScope scope, String moduleId, Operation operation, Function<Mode, T> work) {
        Objects.requireNonNull(work, "work");
        return withAdmissionSnapshot(scope, moduleId, operation,
                snapshot -> work.apply(snapshot.mode()));
    }

    /**
     * Revalidates the signed user and registered deployment in one current-read lock chain and
     * returns the exact producer provenance observed under those locks.
     */
    public <T> T withAdmissionSnapshot(UserAdmissionScope scope, String moduleId,
            Operation operation, Function<AdmissionSnapshot, T> work) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(scope, "scope");
        BusinessScope business = scope.businessScope();
        Objects.requireNonNull(operation, "operation");
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw denied();
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
                || operation == Operation.WRITE && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || operation == Operation.READ && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw denied();
        }
        String tenant = business.tenantId().value();
        String domain = business.dataDomainId().value();
        // Every SELECT is a locking current read. Missing tables/rows and SQL errors
        // propagate as failures; never infer LEGACY from absence or retry in DOMAIN.
        Control control = one(jdbc.query("""
                SELECT tenant_id, state, tenant_session_epoch, admission_epoch FROM core_tenant_control
                WHERE tenant_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Control(rs.getString("tenant_id"), rs.getString("state"),
                revision(rs, "tenant_session_epoch"), revision(rs, "admission_epoch")), tenant));
        if (!tenant.equals(control.tenant()) || control.sessionEpoch() != scope.tenantSessionEpoch()
                || !("ENABLED".equals(control.state())
                || "AUTH_CLOSED".equals(control.state())
                || operation == Operation.READ && "WRITE_CLOSED_CLAIM_CLOSED".equals(control.state()))) throw denied();

        Module module = one(jdbc.query("""
                SELECT tenant_id, module_id, mode_state, mode_revision, migration_epoch,
                       drain_sealed, schema_version, mapping_digest
                FROM core_tenant_module_control
                WHERE tenant_id = ? AND module_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Module(rs.getString("tenant_id"), rs.getString("module_id"),
                rs.getString("mode_state"), revision(rs, "mode_revision"),
                revision(rs, "migration_epoch"), rs.getBoolean("drain_sealed"),
                revision(rs, "schema_version"), rs.getString("mapping_digest")), tenant, moduleId));
        if (!tenant.equals(module.tenant()) || !moduleId.equals(module.id())) throw denied();
        Mode mode;
        if ("DOMAIN".equals(module.state())) {
            if (!module.sealed() || module.schemaVersion() < 1
                    || module.digest() == null || !module.digest().matches("[0-9a-f]{64}")) throw denied();
            mode = Mode.DOMAIN;
        } else if ("LEGACY_CLIENT".equals(module.state()) && "0".equals(tenant)) {
            mode = Mode.LEGACY_CLIENT;
        } else {
            // QUIESCING is not an ordinary read/write lane. Drain uses a separate,
            // frozen-execution API with the previous epoch, never this method.
            throw denied();
        }

        // Authentication happens before this transaction. Re-read the account
        // under a current row lock to serialize with incrementAuthEpoch and
        // state revocation; a previously accepted token is not enough.
        Account account = one(jdbc.query("""
                SELECT id, jiacn, account_state, auth_epoch FROM user_info
                WHERE id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Account(
                rs.getLong("id"), rs.getString("jiacn"), rs.getString("account_state"),
                revision(rs, "auth_epoch")), scope.userId()));
        if (account.id() != scope.userId() || !business.ownerJiacn().equals(account.jiacn())
                || !"ACTIVE".equals(account.state()) || account.epoch() != scope.authEpoch()) throw denied();

        // No proven unique constraint: equivalent-collation duplicates fail closed.
        String client = one(jdbc.query("""
                SELECT client_id FROM oauth_client WHERE client_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> rs.getString("client_id"),
                business.callerClientId()));
        if (!business.callerClientId().equals(client)) throw denied();

        ClientGrant clientGrant = one(jdbc.query("""
                SELECT tenant_id, client_id, status, grant_revision
                FROM oauth_client_tenant_grant
                WHERE tenant_id = ? AND client_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new ClientGrant(
                rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("status"),
                revision(rs, "grant_revision")), tenant, business.callerClientId()));
        if (!tenant.equals(clientGrant.tenant()) || !business.callerClientId().equals(clientGrant.client())
                || !"ACTIVE".equals(clientGrant.status())
                || clientGrant.revision() != scope.clientGrantRevision()) throw denied();

        DomainGrant domainGrant = one(jdbc.query("""
                SELECT tenant_id, client_id, data_domain_id, status, domain_revision
                FROM oauth_client_tenant_domain_grant
                WHERE tenant_id = ? AND client_id = ? AND data_domain_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new DomainGrant(
                rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("data_domain_id"),
                rs.getString("status"), revision(rs, "domain_revision")),
                tenant, business.callerClientId(), domain));
        if (!tenant.equals(domainGrant.tenant())
                || !business.callerClientId().equals(domainGrant.client())
                || !domain.equals(domainGrant.domain())
                || !"ACTIVE".equals(domainGrant.status())) throw denied();

        Membership membership = one(jdbc.query("""
                SELECT tenant_id, uid, status, membership_revision
                FROM core_tenant_membership
                WHERE tenant_id = ? AND uid = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Membership(
                rs.getString("tenant_id"), rs.getLong("uid"), rs.getString("status"),
                revision(rs, "membership_revision")), tenant, scope.userId()));
        if (!tenant.equals(membership.tenant()) || membership.userId() != scope.userId()
                || !"ACTIVE".equals(membership.status())
                || membership.revision() != scope.membershipRevision()) throw denied();

        Registration registration = one(jdbc.query("""
                SELECT deployment_id, stage, artifact_digest, credential_digest, status, registration_revision, worker_generation
                FROM core_deployment_registration
                WHERE deployment_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Registration(rs.getString("deployment_id"), rs.getString("stage"),
                rs.getString("artifact_digest"), rs.getString("credential_digest"), rs.getString("status"),
                revision(rs, "registration_revision"), revision(rs, "worker_generation")), deploymentId));
        if (!deploymentId.equals(registration.id()) || !artifactDigest.equals(registration.artifact())
                || expectedWorkerGeneration != registration.workerGeneration()
                || !credentialMatches(registration.credentialDigest())
                || expectedRegistrationRevision != registration.revision()
                || !"ACTIVE".equals(registration.status())
                || !("SHARED".equals(registration.stage()) || "LOCAL_TEST".equals(registration.stage()))) {
            throw denied();
        }

        DeploymentGrant grant = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, execution_pool_id,
                       status, grant_revision, allow_read, allow_write
                FROM core_deployment_tenant_grant
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new DeploymentGrant(rs.getString("deployment_id"),
                rs.getString("tenant_id"), rs.getString("data_domain_id"),
                rs.getString("execution_pool_id"), rs.getString("status"),
                revision(rs, "grant_revision"), rs.getBoolean("allow_read"),
                rs.getBoolean("allow_write")), deploymentId, tenant, domain));
        if (!deploymentId.equals(grant.deployment()) || !tenant.equals(grant.tenant())
                || !domain.equals(grant.domain()) || !"ACTIVE".equals(grant.status())
                || !executionPoolId.equals(grant.pool())
                || !permits(operation, grant.allowRead(), grant.allowWrite())) throw denied();

        ModuleAdmission admitted = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, module_id, mode_state,
                       mode_revision, migration_epoch, admission_epoch, schema_version,
                       registration_revision, grant_revision, status, allow_read, allow_write
                FROM core_deployment_module_admission
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? AND module_id = ?
                FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new ModuleAdmission(rs.getString("deployment_id"),
                rs.getString("tenant_id"), rs.getString("data_domain_id"), rs.getString("module_id"),
                rs.getString("mode_state"), revision(rs, "mode_revision"),
                revision(rs, "migration_epoch"), revision(rs, "admission_epoch"),
                revision(rs, "schema_version"), revision(rs, "registration_revision"),
                revision(rs, "grant_revision"), rs.getString("status"),
                rs.getBoolean("allow_read"), rs.getBoolean("allow_write")),
                deploymentId, tenant, domain, moduleId));
        if (!deploymentId.equals(admitted.deployment()) || !tenant.equals(admitted.tenant())
                || !domain.equals(admitted.domain()) || !moduleId.equals(admitted.module())
                || !"ACTIVE".equals(admitted.status()) || !mode.name().equals(admitted.mode())
                || admitted.modeRevision() != module.modeRevision()
                || admitted.migrationEpoch() != module.migrationEpoch()
                || admitted.schemaVersion() != module.schemaVersion()
                // During the read-only closed lane, tokens from the immediately previous
                // admission epoch may finish reads; ordinary ENABLED/AUTH_CLOSED lanes
                // still require the exact current epoch. Worker admission has no carve-out.
                || (!"WRITE_CLOSED_CLAIM_CLOSED".equals(control.state())
                    && admitted.admissionEpoch() != control.admissionEpoch())
                || admitted.registrationRevision() != registration.revision()
                || admitted.grantRevision() != grant.revision()
                || !permits(operation, admitted.allowRead(), admitted.allowWrite())) throw denied();
        ProducerScope producer = new ProducerScope(
                business.tenantId(), business.dataDomainId(), executionPoolId, deploymentId,
                control.admissionEpoch(), moduleId, module.migrationEpoch());
        return work.apply(new AdmissionSnapshot(mode, producer));
    }

    private record Control(String tenant, String state, long sessionEpoch, long admissionEpoch) {}

    /**
     * Worker-only admission. Lock order is tenant control, module control,
     * deployment registration, deployment tenant grant, deployment module admission.
     * User, client and membership rows are deliberately not consulted.
     */
    public <T> T withWorkerAdmission(
            WorkerScope scope, WorkerOperation operation, Function<Mode, T> work) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(work, "work");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !deploymentId.equals(scope.deploymentId())
                || !executionPoolId.equals(scope.executionPoolId())
                || expectedWorkerGeneration != scope.workerGeneration()
                || expectedRegistrationRevision != scope.registrationRevision()) {
            throw denied();
        }

        String tenant = scope.tenantId().value();
        String domain = scope.dataDomainId().value();
        Control control = one(jdbc.query("""
                SELECT tenant_id, state, tenant_session_epoch, admission_epoch
                FROM core_tenant_control WHERE tenant_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Control(
                rs.getString("tenant_id"), rs.getString("state"),
                revision(rs, "tenant_session_epoch"), revision(rs, "admission_epoch")), tenant));
        if (!tenant.equals(control.tenant()) || control.admissionEpoch() != scope.admissionEpoch()
                || !workerStatePermits(control.state(), operation)) throw denied();

        Module module = one(jdbc.query("""
                SELECT tenant_id, module_id, mode_state, mode_revision, migration_epoch,
                       drain_sealed, schema_version, mapping_digest
                FROM core_tenant_module_control
                WHERE tenant_id = ? AND module_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Module(
                rs.getString("tenant_id"), rs.getString("module_id"), rs.getString("mode_state"),
                revision(rs, "mode_revision"), revision(rs, "migration_epoch"),
                rs.getBoolean("drain_sealed"), revision(rs, "schema_version"),
                rs.getString("mapping_digest")), tenant, scope.moduleId()));
        if (!tenant.equals(module.tenant()) || !scope.moduleId().equals(module.id())
                || module.migrationEpoch() != scope.migrationEpoch()) throw denied();
        Mode mode = workerMode(module, tenant);

        Registration registration = one(jdbc.query("""
                SELECT deployment_id, stage, artifact_digest, credential_digest, status,
                       registration_revision, worker_generation
                FROM core_deployment_registration WHERE deployment_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Registration(
                rs.getString("deployment_id"), rs.getString("stage"),
                rs.getString("artifact_digest"), rs.getString("credential_digest"),
                rs.getString("status"), revision(rs, "registration_revision"),
                revision(rs, "worker_generation")), scope.deploymentId()));
        if (!scope.deploymentId().equals(registration.id())
                || !artifactDigest.equals(registration.artifact())
                || scope.workerGeneration() != registration.workerGeneration()
                || scope.registrationRevision() != registration.revision()
                || !credentialMatches(registration.credentialDigest())
                || !"ACTIVE".equals(registration.status())
                || !("SHARED".equals(registration.stage())
                    || "LOCAL_TEST".equals(registration.stage()))) throw denied();

        DeploymentGrant grant = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, execution_pool_id,
                       status, grant_revision, allow_read, allow_write
                FROM core_deployment_tenant_grant
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new DeploymentGrant(
                rs.getString("deployment_id"), rs.getString("tenant_id"),
                rs.getString("data_domain_id"), rs.getString("execution_pool_id"),
                rs.getString("status"), revision(rs, "grant_revision"),
                rs.getBoolean("allow_read"), rs.getBoolean("allow_write")),
                scope.deploymentId(), tenant, domain));
        if (!scope.deploymentId().equals(grant.deployment()) || !tenant.equals(grant.tenant())
                || !domain.equals(grant.domain()) || !scope.executionPoolId().equals(grant.pool())
                || !"ACTIVE".equals(grant.status()) || !grant.allowWrite()) throw denied();

        ModuleAdmission admitted = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, module_id, mode_state,
                       mode_revision, migration_epoch, admission_epoch, schema_version,
                       registration_revision, grant_revision, status, allow_read, allow_write
                FROM core_deployment_module_admission
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? AND module_id = ?
                FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new ModuleAdmission(
                rs.getString("deployment_id"), rs.getString("tenant_id"),
                rs.getString("data_domain_id"), rs.getString("module_id"),
                rs.getString("mode_state"), revision(rs, "mode_revision"),
                revision(rs, "migration_epoch"), revision(rs, "admission_epoch"),
                revision(rs, "schema_version"), revision(rs, "registration_revision"),
                revision(rs, "grant_revision"), rs.getString("status"),
                rs.getBoolean("allow_read"), rs.getBoolean("allow_write")),
                scope.deploymentId(), tenant, domain, scope.moduleId()));
        if (!scope.deploymentId().equals(admitted.deployment())
                || !tenant.equals(admitted.tenant()) || !domain.equals(admitted.domain())
                || !scope.moduleId().equals(admitted.module()) || !"ACTIVE".equals(admitted.status())
                || !mode.name().equals(admitted.mode())
                || admitted.modeRevision() != module.modeRevision()
                || admitted.migrationEpoch() != scope.migrationEpoch()
                || admitted.admissionEpoch() != scope.admissionEpoch()
                || admitted.schemaVersion() != module.schemaVersion()
                || admitted.registrationRevision() != scope.registrationRevision()
                || admitted.grantRevision() != grant.revision()
                || !admitted.allowWrite()) throw denied();
        return work.apply(mode);
    }

    /**
     * Revalidates persisted protocol-v2 producer provenance without consulting user/request
     * fields. The current process may reissue only records originally produced by its exact
     * registered deployment and pool; epoch or module migration drift fails closed.
     */
    public <T> T withProducerAdmission(
            ProducerScope scope, Operation operation, Function<Mode, T> work) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(work, "work");
        if (operation != Operation.WRITE
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !deploymentId.equals(scope.deploymentId())
                || !executionPoolId.equals(scope.executionPoolId())) {
            throw denied();
        }

        String tenant = scope.tenantId().value();
        String domain = scope.dataDomainId().value();
        Control control = one(jdbc.query("""
                SELECT tenant_id, state, tenant_session_epoch, admission_epoch
                FROM core_tenant_control WHERE tenant_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Control(
                rs.getString("tenant_id"), rs.getString("state"),
                revision(rs, "tenant_session_epoch"), revision(rs, "admission_epoch")), tenant));
        if (!tenant.equals(control.tenant()) || control.admissionEpoch() != scope.admissionEpoch()
                || !("ENABLED".equals(control.state()) || "AUTH_CLOSED".equals(control.state()))) {
            throw denied();
        }

        Module module = one(jdbc.query("""
                SELECT tenant_id, module_id, mode_state, mode_revision, migration_epoch,
                       drain_sealed, schema_version, mapping_digest
                FROM core_tenant_module_control
                WHERE tenant_id = ? AND module_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Module(
                rs.getString("tenant_id"), rs.getString("module_id"), rs.getString("mode_state"),
                revision(rs, "mode_revision"), revision(rs, "migration_epoch"),
                rs.getBoolean("drain_sealed"), revision(rs, "schema_version"),
                rs.getString("mapping_digest")), tenant, scope.moduleId()));
        if (!tenant.equals(module.tenant()) || !scope.moduleId().equals(module.id())
                || module.migrationEpoch() != scope.migrationEpoch()) throw denied();
        Mode mode = workerMode(module, tenant);
        if (mode != Mode.DOMAIN) throw denied();

        Registration registration = one(jdbc.query("""
                SELECT deployment_id, stage, artifact_digest, credential_digest, status,
                       registration_revision, worker_generation
                FROM core_deployment_registration WHERE deployment_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new Registration(
                rs.getString("deployment_id"), rs.getString("stage"),
                rs.getString("artifact_digest"), rs.getString("credential_digest"),
                rs.getString("status"), revision(rs, "registration_revision"),
                revision(rs, "worker_generation")), scope.deploymentId()));
        if (!scope.deploymentId().equals(registration.id())
                || !artifactDigest.equals(registration.artifact())
                || expectedWorkerGeneration != registration.workerGeneration()
                || expectedRegistrationRevision != registration.revision()
                || !credentialMatches(registration.credentialDigest())
                || !"ACTIVE".equals(registration.status())
                || !("SHARED".equals(registration.stage())
                    || "LOCAL_TEST".equals(registration.stage()))) throw denied();

        DeploymentGrant grant = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, execution_pool_id,
                       status, grant_revision, allow_read, allow_write
                FROM core_deployment_tenant_grant
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new DeploymentGrant(
                rs.getString("deployment_id"), rs.getString("tenant_id"),
                rs.getString("data_domain_id"), rs.getString("execution_pool_id"),
                rs.getString("status"), revision(rs, "grant_revision"),
                rs.getBoolean("allow_read"), rs.getBoolean("allow_write")),
                scope.deploymentId(), tenant, domain));
        if (!scope.deploymentId().equals(grant.deployment()) || !tenant.equals(grant.tenant())
                || !domain.equals(grant.domain()) || !scope.executionPoolId().equals(grant.pool())
                || !"ACTIVE".equals(grant.status()) || !grant.allowWrite()) throw denied();

        ModuleAdmission admitted = one(jdbc.query("""
                SELECT deployment_id, tenant_id, data_domain_id, module_id, mode_state,
                       mode_revision, migration_epoch, admission_epoch, schema_version,
                       registration_revision, grant_revision, status, allow_read, allow_write
                FROM core_deployment_module_admission
                WHERE deployment_id = ? AND tenant_id = ? AND data_domain_id = ? AND module_id = ?
                FOR SHARE
                """.replace("FOR SHARE", lockClause), (rs, row) -> new ModuleAdmission(
                rs.getString("deployment_id"), rs.getString("tenant_id"),
                rs.getString("data_domain_id"), rs.getString("module_id"),
                rs.getString("mode_state"), revision(rs, "mode_revision"),
                revision(rs, "migration_epoch"), revision(rs, "admission_epoch"),
                revision(rs, "schema_version"), revision(rs, "registration_revision"),
                revision(rs, "grant_revision"), rs.getString("status"),
                rs.getBoolean("allow_read"), rs.getBoolean("allow_write")),
                scope.deploymentId(), tenant, domain, scope.moduleId()));
        if (!scope.deploymentId().equals(admitted.deployment())
                || !tenant.equals(admitted.tenant()) || !domain.equals(admitted.domain())
                || !scope.moduleId().equals(admitted.module()) || !"ACTIVE".equals(admitted.status())
                || !mode.name().equals(admitted.mode())
                || admitted.modeRevision() != module.modeRevision()
                || admitted.migrationEpoch() != scope.migrationEpoch()
                || admitted.admissionEpoch() != scope.admissionEpoch()
                || admitted.schemaVersion() != module.schemaVersion()
                || admitted.registrationRevision() != registration.revision()
                || admitted.grantRevision() != grant.revision()
                || !admitted.allowWrite()) throw denied();
        return work.apply(mode);
    }

    private static boolean workerStatePermits(String state, WorkerOperation operation) {
        // This relay does not persist an immutable pre-close claim cutoff/deadline proof.
        // Therefore claim, publish revalidation and settlement all fail closed once
        // writes/claims close; controlled drain needs a separately reviewed protocol.
        return "ENABLED".equals(state) || "AUTH_CLOSED".equals(state);
    }

    private static Mode workerMode(Module module, String tenant) {
        if ("DOMAIN".equals(module.state()) && module.sealed() && module.schemaVersion() >= 1
                && module.digest() != null && module.digest().matches("[0-9a-f]{64}")) {
            return Mode.DOMAIN;
        }
        if ("LEGACY_CLIENT".equals(module.state()) && "0".equals(tenant)) {
            return Mode.LEGACY_CLIENT;
        }
        throw denied();
    }
    private record Module(String tenant, String id, String state, long modeRevision,
                          long migrationEpoch, boolean sealed, long schemaVersion, String digest) {}
    private record Account(long id, String jiacn, String state, long epoch) {}
    private record ClientGrant(String tenant, String client, String status, long revision) {}
    private record DomainGrant(String tenant, String client, String domain, String status, long revision) {}
    private record Membership(String tenant, long userId, String status, long revision) {}
    private record Registration(String id, String stage, String artifact, String credentialDigest,
                                String status, long revision, long workerGeneration) {}
    private record DeploymentGrant(String deployment, String tenant, String domain, String pool,
                                   String status, long revision, boolean allowRead, boolean allowWrite) {}
    private record ModuleAdmission(String deployment, String tenant, String domain, String module,
                                   String mode, long modeRevision, long migrationEpoch, long admissionEpoch,
                                   long schemaVersion, long registrationRevision, long grantRevision,
                                   String status, boolean allowRead, boolean allowWrite) {}

    private static <T> T one(List<T> rows) {
        if (rows.size() != 1 || rows.get(0) == null) throw denied();
        return rows.get(0);
    }

    private boolean credentialMatches(String stored) {
        return stored != null && stored.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(credentialDigest, HexFormat.of().parseHex(stored));
    }

    /** Control-plane digest: SHA-256 of versioned, deployment- and generation-bound credential. */
    private static byte[] credentialDigest(String deployment, long generation, String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("cyf-deployment-v1\0".getBytes(StandardCharsets.US_ASCII));
            sha.update(deployment.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Long.toString(generation).getBytes(StandardCharsets.US_ASCII));
            sha.update((byte) 0);
            sha.update(secret.getBytes(StandardCharsets.US_ASCII));
            return sha.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean permits(Operation operation, boolean read, boolean write) {
        return operation == Operation.READ ? read : write;
    }

    private static long revision(ResultSet rs, String name) throws SQLException {
        Long value = rs.getObject(name, Long.class);
        if (value == null || value < 0) throw denied();
        return value;
    }

    private static boolean exact(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.equals(value.strip()) && value.chars().noneMatch(Character::isISOControl);
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Tenant module admission is unavailable");
    }
}
