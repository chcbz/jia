package cn.jia.agent.config;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Creates one relay authority from one control-plane snapshot. */
@Configuration(value = "agentOutboxWorkerAuthorityConfiguration", proxyBeanMethods = false)
@Conditional(AgentRabbitPublishEnabledCondition.class)
@Profile("!local-test-area")
public class AgentOutboxWorkerAuthorityConfiguration {
    static final String MODULE_ID = "agent-command";

    @Bean(name = "agentOutboxProductionRuntimeArtifact", defaultCandidate = false)
    RuntimeArtifact agentOutboxProductionRuntimeArtifact() {
        return locateRuntimeArtifact(System.getProperty("java.class.path"),
                AgentOutboxWorkerAuthorityConfiguration.class.getProtectionDomain()
                        .getCodeSource().getLocation());
    }

    static RuntimeArtifact locateRuntimeArtifact(String classPath, URL codeSource) {
        if (classPath == null || classPath.isBlank() || codeSource == null) throw denied();
        String[] entries = classPath.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        if (entries.length != 1) throw denied();
        try {
            Path classPathJar = Path.of(entries[0]).toRealPath();
            if (!Files.isRegularFile(classPathJar)
                    || !classPathJar.getFileName().toString().endsWith(".jar")) throw denied();
            Path codeSourceJar = outerJarPath(codeSource).toRealPath();
            if (!classPathJar.equals(codeSourceJar)) throw denied();
            return new RuntimeArtifact(classPathJar, artifactDigest(classPathJar));
        } catch (IOException | RuntimeException invalid) {
            throw denied();
        }
    }

    private static Path outerJarPath(URL codeSource) {
        try {
            URI source = codeSource.toURI();
            if ("file".equalsIgnoreCase(source.getScheme())) return filePath(source);
            if (!"jar".equalsIgnoreCase(source.getScheme()) || !source.isOpaque()
                    || source.getRawFragment() != null) throw denied();

            URI nested = new URI(source.getRawSchemeSpecificPart());
            if (!"nested".equalsIgnoreCase(nested.getScheme()) || nested.isOpaque()
                    || nested.getRawAuthority() != null || nested.getRawQuery() != null
                    || nested.getRawFragment() != null) throw denied();
            String rawPath = nested.getRawPath();
            int boundary = rawPath == null ? -1 : rawPath.lastIndexOf("/!");
            if (boundary <= 0) throw denied();
            String nestedEntry = rawPath.substring(boundary + 2);
            if (!nestedEntry.startsWith("BOOT-INF/lib/")
                    || !nestedEntry.endsWith(".jar!/")
                    || nestedEntry.indexOf((char) 33) != nestedEntry.length() - 2
                    || nestedEntry.contains("//") || nestedEntry.contains("/../")
                    || nestedEntry.contains("/./")) throw denied();
            return filePath(new URI("file:" + rawPath.substring(0, boundary)));
        } catch (URISyntaxException | RuntimeException invalid) {
            throw denied();
        }
    }

    private static Path filePath(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque()
                || uri.getRawAuthority() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getRawPath() == null
                || uri.getRawPath().isBlank()) throw denied();
        return Path.of(uri);
    }

    @Bean(name = "agentOutboxWorkerAuthority", defaultCandidate = false)
    WorkerAuthority agentOutboxWorkerAuthority(JdbcTemplate jdbc, Environment environment,
            @Qualifier("agentOutboxProductionRuntimeArtifact") RuntimeArtifact runtimeArtifact) {
        String deploymentId = required(environment, "cyf.deployment.id");
        String credential = required(environment, "cyf.deployment.registration-credential");
        if (!deploymentId.matches("[a-z0-9-]{1,100}")
                || !credential.matches("[0-9a-f]{64}")) throw denied();
        String artifactDigest = runtimeArtifact.sha256();

        Registration row = one(jdbc.query("""
                SELECT control.tenant_id, control.state AS tenant_state,
                       control.admission_epoch AS control_admission_epoch,
                       module.module_id, module.mode_state AS module_mode_state,
                       module.mode_revision AS module_mode_revision,
                       module.migration_epoch AS module_migration_epoch,
                       module.drain_sealed, module.schema_version AS module_schema_version,
                       module.mapping_digest, deployment.deployment_id, deployment.stage,
                       deployment.artifact_digest, deployment.credential_digest,
                       deployment.status AS deployment_status, deployment.worker_generation,
                       deployment.registration_revision, grant_row.data_domain_id,
                       grant_row.execution_pool_id, grant_row.status AS grant_status,
                       grant_row.grant_revision, grant_row.allow_read AS grant_allow_read,
                       grant_row.allow_write AS grant_allow_write,
                       admitted.mode_state AS admitted_mode_state,
                       admitted.mode_revision AS admitted_mode_revision,
                       admitted.migration_epoch AS admitted_migration_epoch,
                       admitted.admission_epoch AS admitted_admission_epoch,
                       admitted.schema_version AS admitted_schema_version,
                       admitted.registration_revision AS admitted_registration_revision,
                       admitted.grant_revision AS admitted_grant_revision,
                       admitted.status AS admitted_status,
                       admitted.allow_read AS admitted_allow_read,
                       admitted.allow_write AS admitted_allow_write
                FROM core_deployment_registration deployment
                JOIN core_deployment_tenant_grant grant_row
                  ON grant_row.deployment_id = deployment.deployment_id
                JOIN core_tenant_control control ON control.tenant_id = grant_row.tenant_id
                JOIN core_tenant_module_control module
                  ON module.tenant_id = control.tenant_id AND module.module_id = ?
                JOIN core_deployment_module_admission admitted
                  ON admitted.deployment_id = deployment.deployment_id
                 AND admitted.tenant_id = grant_row.tenant_id
                 AND admitted.data_domain_id = grant_row.data_domain_id
                 AND admitted.module_id = module.module_id
                WHERE deployment.deployment_id = ?
                """, (rs, ignored) -> registration(rs), MODULE_ID, deploymentId));

        validate(row, deploymentId, artifactDigest, credential);
        WorkerScope scope = new WorkerScope(
                TenantId.of(row.tenantId()), DataDomainId.of(row.dataDomainId()),
                row.executionPoolId(), row.deploymentId(), row.workerGeneration(),
                row.registrationRevision(), row.controlAdmissionEpoch(), row.moduleId(),
                row.moduleMigrationEpoch());
        TenantModuleAdmissionGuard guard = new TenantModuleAdmissionGuard(
                jdbc, deploymentId, artifactDigest, row.executionPoolId(), credential,
                row.workerGeneration(), row.registrationRevision());
        return new WorkerAuthority(scope, guard);
    }

    @Bean(name = "agentOutboxWorkerScope", defaultCandidate = false)
    WorkerScope agentOutboxWorkerScope(
            @Qualifier("agentOutboxWorkerAuthority") WorkerAuthority authority) {
        return authority.scope();
    }

    @Bean(name = "agentOutboxTenantModuleAdmissionGuard", defaultCandidate = false)
    TenantModuleAdmissionGuard agentOutboxTenantModuleAdmissionGuard(
            @Qualifier("agentOutboxWorkerAuthority") WorkerAuthority authority) {
        return authority.guard();
    }

    private static Registration registration(ResultSet rs) throws SQLException {
        return new Registration(
                rs.getString("tenant_id"), rs.getString("tenant_state"),
                revision(rs, "control_admission_epoch"), rs.getString("module_id"),
                rs.getString("module_mode_state"), revision(rs, "module_mode_revision"),
                revision(rs, "module_migration_epoch"), rs.getBoolean("drain_sealed"),
                revision(rs, "module_schema_version"), rs.getString("mapping_digest"),
                rs.getString("deployment_id"), rs.getString("stage"),
                rs.getString("artifact_digest"), rs.getString("credential_digest"),
                rs.getString("deployment_status"), revision(rs, "worker_generation"),
                revision(rs, "registration_revision"), rs.getString("data_domain_id"),
                rs.getString("execution_pool_id"), rs.getString("grant_status"),
                revision(rs, "grant_revision"), rs.getBoolean("grant_allow_read"),
                rs.getBoolean("grant_allow_write"), rs.getString("admitted_mode_state"),
                revision(rs, "admitted_mode_revision"), revision(rs, "admitted_migration_epoch"),
                revision(rs, "admitted_admission_epoch"), revision(rs, "admitted_schema_version"),
                revision(rs, "admitted_registration_revision"),
                revision(rs, "admitted_grant_revision"), rs.getString("admitted_status"),
                rs.getBoolean("admitted_allow_read"), rs.getBoolean("admitted_allow_write"));
    }

    private static void validate(
            Registration row, String deploymentId, String artifactDigest, String credential) {
        try {
            TenantId.of(row.tenantId());
            DataDomainId.of(row.dataDomainId());
        } catch (RuntimeException invalidScope) {
            throw denied();
        }
        if (!deploymentId.equals(row.deploymentId()) || !"SHARED".equals(row.stage())
                || !"ACTIVE".equals(row.deploymentStatus())
                || !artifactDigest.equals(row.artifactDigest())
                || !credentialMatches(row, credential)
                || !("ENABLED".equals(row.tenantState()) || "AUTH_CLOSED".equals(row.tenantState()))
                || !MODULE_ID.equals(row.moduleId()) || !"DOMAIN".equals(row.moduleModeState())
                || !row.drainSealed() || row.moduleSchemaVersion() < 1
                || row.mappingDigest() == null || !row.mappingDigest().matches("[0-9a-f]{64}")
                || row.executionPoolId() == null
                || !row.executionPoolId().matches("[a-z0-9-]{1,100}")
                || !"ACTIVE".equals(row.grantStatus())
                || !row.grantAllowRead() || !row.grantAllowWrite()
                || !"ACTIVE".equals(row.admittedStatus())
                || !row.admittedAllowRead() || !row.admittedAllowWrite()
                || !row.moduleModeState().equals(row.admittedModeState())
                || row.moduleModeRevision() != row.admittedModeRevision()
                || row.moduleMigrationEpoch() != row.admittedMigrationEpoch()
                || row.controlAdmissionEpoch() != row.admittedAdmissionEpoch()
                || row.moduleSchemaVersion() != row.admittedSchemaVersion()
                || row.registrationRevision() != row.admittedRegistrationRevision()
                || row.grantRevision() != row.admittedGrantRevision()) throw denied();
    }

    private static boolean credentialMatches(Registration row, String credential) {
        String stored = row.credentialDigest();
        return stored != null && stored.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(HexFormat.of().parseHex(stored),
                credentialDigest(row.deploymentId(), row.workerGeneration(), credential));
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.contains("${")) throw denied();
        return value;
    }

    private static String artifactDigest(Path artifactPath) {
        if (!artifactPath.isAbsolute() || !Files.isRegularFile(artifactPath)
                || !artifactPath.getFileName().toString().endsWith(".jar")) throw denied();
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(artifactPath)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        } catch (IOException failure) {
            throw denied();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] credentialDigest(String deploymentId, long generation, String credential) {
        MessageDigest digest = sha256();
        digest.update("cyf-deployment-v1\0".getBytes(StandardCharsets.US_ASCII));
        digest.update(deploymentId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(generation).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(credential.getBytes(StandardCharsets.US_ASCII));
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long revision(ResultSet rs, String name) throws SQLException {
        Long value = rs.getObject(name, Long.class);
        if (value == null || value < 0) throw denied();
        return value;
    }

    private static <T> T one(List<T> rows) {
        if (rows.size() != 1 || rows.getFirst() == null) throw denied();
        return rows.getFirst();
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Agent outbox worker authority is unavailable");
    }

    record RuntimeArtifact(Path path, String sha256) {
        RuntimeArtifact {
            Objects.requireNonNull(path, "path");
            if (!path.isAbsolute() || !Files.isRegularFile(path)
                    || !path.getFileName().toString().endsWith(".jar")
                    || sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw denied();
        }
    }

    record WorkerAuthority(WorkerScope scope, TenantModuleAdmissionGuard guard) {
        WorkerAuthority {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(guard, "guard");
        }
    }

    private record Registration(
            String tenantId, String tenantState, long controlAdmissionEpoch,
            String moduleId, String moduleModeState, long moduleModeRevision,
            long moduleMigrationEpoch, boolean drainSealed, long moduleSchemaVersion,
            String mappingDigest, String deploymentId, String stage, String artifactDigest,
            String credentialDigest, String deploymentStatus, long workerGeneration,
            long registrationRevision, String dataDomainId, String executionPoolId,
            String grantStatus, long grantRevision, boolean grantAllowRead,
            boolean grantAllowWrite, String admittedModeState, long admittedModeRevision,
            long admittedMigrationEpoch, long admittedAdmissionEpoch,
            long admittedSchemaVersion, long admittedRegistrationRevision,
            long admittedGrantRevision, String admittedStatus, boolean admittedAllowRead,
            boolean admittedAllowWrite) {
    }
}
