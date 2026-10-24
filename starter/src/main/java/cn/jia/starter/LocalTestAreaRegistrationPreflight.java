package cn.jia.starter;

import cn.jia.agent.config.AgentTaskEventsProperties;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only startup preflight for the separately registered local TEST deployment. */
@Configuration(proxyBeanMethods = false)
@Conditional(LocalTestAreaRegistrationPreflight.LocalTestDeploymentCondition.class)
public class LocalTestAreaRegistrationPreflight {
    /** Run before bean initialization: schema installers must not precede registration. */
    public static final class BeforeRefresh implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            Environment environment = context.getEnvironment();
            if (!requiresPreflight(environment)) return;
            if (isFixedTenantMode(environment)) {
                verify(null, environment, null);
                return;
            }
            String url = config(environment, "spring.datasource.url");
            if (!url.startsWith("jdbc:mysql://")) throw denied();
            String user = config(environment, "spring.datasource.username");
            String password = config(environment, "spring.datasource.password");
            JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            verify(jdbc, environment, Path.of(System.getProperty("java.class.path")));
        }
    }

    static boolean requiresPreflight(Environment environment) {
        return List.of(environment.getActiveProfiles()).contains("local-test-area")
                || "LOCAL_TEST".equals(environment.getProperty("cyf.deployment.stage"));
    }

    /** Either signal requires the full preflight; overriding stage must not skip it. */
    public static final class LocalTestDeploymentCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return requiresPreflight(context.getEnvironment());
        }
    }

    @Bean
    SmartInitializingSingleton localTestRegistration(JdbcOperations jdbc, Environment environment) {
        return () -> verify(jdbc, environment, Path.of(System.getProperty("java.class.path")));
    }
    /** Runtime transaction guard bound to the exact deployment registration verified at startup. */
    @Bean
    @Conditional(PlatformAdmissionCondition.class)
    TenantModuleAdmissionGuard localTestTenantModuleAdmissionGuard(
            JdbcTemplate jdbc, Environment environment) {
        return createAdmissionGuard(
                jdbc, environment, Path.of(System.getProperty("java.class.path")));
    }

    /** Fixed-tenant mode has no platform registration row and therefore no platform guard bean. */
    public static final class PlatformAdmissionCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !isFixedTenantMode(context.getEnvironment());
        }
    }

    static TenantModuleAdmissionGuard createAdmissionGuard(
            JdbcTemplate jdbc, Environment environment, Path runningJar) {
        if (isFixedTenantMode(environment)) throw denied();
        String deployment = config(environment, "cyf.deployment.id");
        Map<String, Object> registration = one(jdbc.queryForList("""
                SELECT worker_generation, registration_revision
                FROM core_deployment_registration
                WHERE deployment_id = ?
                """, deployment));
        return new TenantModuleAdmissionGuard(
                jdbc,
                deployment,
                artifactDigest(runningJar),
                config(environment, "cyf.worker.execution-pool"),
                config(environment, "cyf.deployment.registration-credential"),
                revision(registration.get("worker_generation")),
                revision(registration.get("registration_revision")));
    }

    static void verify(JdbcOperations jdbc, Environment environment, Path runningJar) {
        if (!List.of(environment.getActiveProfiles()).equals(List.of("local-test-area"))) throw denied();
        if (!"LOCAL_TEST".equals(config(environment, "cyf.deployment.stage"))) throw denied();
        if (isFixedTenantMode(environment)) {
            verifyFixedTenant(environment);
            return;
        }
        verifyPlatform(jdbc, environment, runningJar);
    }

    private static void verifyFixedTenant(Environment environment) {
        String tenant = TenantId.of(config(environment, "cyf.tenant.fixed-tenant-id")).value();
        if (!"1".equals(tenant)
                || !Map.of("cyf-local", "1").equals(map(
                        environment, "cyf.tenant.fixed-client-tenants"))
                || !list(environment, "cyf.tenant.allowed-tenants").isEmpty()
                || !List.of("1").equals(list(environment, "cyf.worker.allowed-tenants"))
                || !"false".equals(config(environment, "cyf.tenant.require-bound-identity"))
                || !"true".equals(config(environment, "cyf.oauth.authorization-server.enabled"))
                || !"true".equals(config(environment, "cyf.oauth.signing.enabled"))
                || !"http://127.0.0.1:10019".equals(config(
                        environment, "cyf.oauth.signing.issuer"))
                || !"/home/chc/.local/state/cyf-local-test-area/signing.jwk".equals(config(
                        environment, "cyf.oauth.signing.private-jwk-path"))
                || !"http://127.0.0.1:10019".equals(config(
                        environment, "spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                || !"http://127.0.0.1:10019/oauth2/jwks".equals(config(
                        environment, "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                || !"127.0.0.1".equals(config(environment, "spring.data.redis.host"))
                || !"16379".equals(config(environment, "spring.data.redis.port"))
                || !"http://127.0.0.1:10018".equals(config(
                        environment, "cors.allowed.origin.patterns"))
                || !"true".equals(config(environment, "agent.personal-workspace-storage.enabled"))
                || !"/home/chc/.local/state/cyf-local-test-area/workspace-files".equals(config(
                        environment, "agent.personal-workspace-storage.root-directory"))
                || !"true".equals(config(environment, "agent.task-events.enabled"))
                || !List.of(new AgentTaskEventsProperties.AllowedScope("1", "cyf-local"))
                    .equals(eventScopes(environment))
                || !"never".equals(config(environment, "spring.sql.init.mode"))
                || !"validate".equals(config(environment, "cyf.schema.mode"))
                || !"127.0.0.1".equals(config(environment, "server.address"))
                || !"10019".equals(config(environment, "server.port"))
                || unsafeSideEffectEnabled(environment, true)) {
            throw denied();
        }
    }

    private static void verifyPlatform(
            JdbcOperations jdbc, Environment environment, Path runningJar) {
        if (jdbc == null) throw denied();
        if (!"true".equals(config(environment, "cyf.tenant.require-bound-identity"))
                || !"false".equals(config(environment, "cyf.oauth.authorization-server.enabled"))
                || !"never".equals(config(environment, "spring.sql.init.mode"))
                || !"validate".equals(config(environment, "cyf.schema.mode"))
                || !"127.0.0.1".equals(config(environment, "server.address"))
                || unsafeSideEffectEnabled(environment, false)) throw denied();
        List<String> tenants = list(environment, "cyf.tenant.allowed-tenants");
        if (tenants.size() != 1 || !tenants.equals(list(environment, "cyf.worker.allowed-tenants"))) {
            throw denied();
        }
        String tenant = TenantId.of(tenants.getFirst()).value();
        String deployment = config(environment, "cyf.deployment.id");
        String pool = config(environment, "cyf.worker.execution-pool");
        String domain = config(environment, "cyf.worker.data-domain-id");
        String credential = config(environment, "cyf.deployment.registration-credential");
        if (!credential.matches("[0-9a-f]{64}") || !pool.matches("[a-z0-9-]{1,100}")
                || !deployment.matches("[a-z0-9-]{1,100}")
                || !domain.matches("[a-z][a-z0-9-]{0,63}")) throw denied();
        List<String> modules = list(environment, "cyf.deployment.allowed-modules");
        if (modules.stream().anyMatch(module -> !module.matches("[a-z][a-z0-9_-]{0,63}"))
                || new java.util.HashSet<>(modules).size() != modules.size()) throw denied();
        String allowNoBusinessModulesValue = config(
                environment, "cyf.deployment.allow-no-business-modules");
        if (!"true".equals(allowNoBusinessModulesValue)
                && !"false".equals(allowNoBusinessModulesValue)) throw denied();
        boolean allowNoBusinessModules = "true".equals(allowNoBusinessModulesValue);
        if (allowNoBusinessModules != modules.isEmpty()) throw denied();
        LocalTestAreaAuthoritySchemaValidator.verify(jdbc);
        LocalTestAreaBusinessSchemaValidator.verify(jdbc, modules);
        String artifact = artifactDigest(runningJar);
        StringBuilder registrationSql = new StringBuilder("""
                SELECT tenant.tenant_id,
                       tenant.tenant_kind,
                       control.state AS tenant_state,
                       control.control_revision,
                       control.tenant_session_epoch,
                       control.admission_epoch,
                       deployment.deployment_id AS registered_deployment_id,
                       deployment.stage,
                       deployment.artifact_digest,
                       deployment.credential_digest,
                       deployment.status AS deployment_status,
                       deployment.worker_generation,
                       deployment.registration_revision,
                       grant_row.deployment_id AS grant_deployment_id,
                       grant_row.tenant_id AS grant_tenant_id,
                       grant_row.data_domain_id,
                       grant_row.execution_pool_id,
                       grant_row.status AS grant_status,
                       grant_row.allow_read,
                       grant_row.allow_write,
                       grant_row.grant_revision
                FROM core_tenant tenant
                JOIN core_tenant_control control ON control.tenant_id = tenant.tenant_id
                JOIN core_deployment_registration deployment ON deployment.deployment_id = ?
                JOIN core_deployment_tenant_grant grant_row
                  ON grant_row.deployment_id = deployment.deployment_id
                 AND grant_row.tenant_id = tenant.tenant_id
                 AND grant_row.data_domain_id = ?
                WHERE tenant.tenant_id = ?
                  AND (SELECT COUNT(*)
                       FROM core_deployment_module_admission admitted
                       WHERE admitted.deployment_id = deployment.deployment_id
                         AND admitted.tenant_id = tenant.tenant_id
                         AND admitted.data_domain_id = grant_row.data_domain_id) = ?
                  AND (SELECT COUNT(DISTINCT admitted.module_id)
                       FROM core_deployment_module_admission admitted
                       WHERE admitted.deployment_id = deployment.deployment_id
                         AND admitted.tenant_id = tenant.tenant_id
                         AND admitted.data_domain_id = grant_row.data_domain_id) = ?
                """);
        List<Object> parameters = new ArrayList<>();
        parameters.add(deployment);
        parameters.add(domain);
        parameters.add(tenant);
        parameters.add(modules.size());
        parameters.add(modules.size());
        for (String moduleId : modules) {
            registrationSql.append("""
                  AND (SELECT COUNT(*)
                       FROM core_tenant_module_control module
                       WHERE module.tenant_id = tenant.tenant_id
                         AND module.module_id = ?) = 1
                  AND (SELECT COUNT(*)
                       FROM core_deployment_module_admission admitted
                       JOIN core_tenant_module_control module
                         ON module.tenant_id = admitted.tenant_id
                        AND module.module_id = admitted.module_id
                       WHERE admitted.deployment_id = deployment.deployment_id
                         AND admitted.tenant_id = tenant.tenant_id
                         AND admitted.data_domain_id = grant_row.data_domain_id
                         AND admitted.module_id = ?
                         AND admitted.status = 'ACTIVE'
                         AND admitted.mode_state = 'DOMAIN'
                         AND module.mode_state = admitted.mode_state
                         AND module.mode_revision = admitted.mode_revision
                         AND module.mode_revision >= 0
                         AND module.migration_epoch = admitted.migration_epoch
                         AND module.migration_epoch >= 0
                         AND module.drain_sealed = TRUE
                         AND module.schema_version = admitted.schema_version
                         AND module.schema_version >= 1
                         AND module.mapping_digest REGEXP '^[0-9a-f]{64}$'
                         AND admitted.admission_epoch = control.admission_epoch
                         AND admitted.admission_epoch >= 0
                         AND admitted.registration_revision = deployment.registration_revision
                         AND admitted.registration_revision >= 0
                         AND admitted.grant_revision = grant_row.grant_revision
                         AND admitted.grant_revision >= 0
                         AND admitted.allow_read = TRUE
                         AND admitted.allow_write = TRUE) = 1
                    """);
            parameters.add(moduleId);
            parameters.add(moduleId);
        }
        Map<String, Object> registration = one(jdbc.queryForList(
                registrationSql.toString(), parameters.toArray()));
        long generation = registration.get("worker_generation") instanceof Number value ? value.longValue() : -1;
        if (!tenant.equals(registration.get("tenant_id"))
                || !"TEST".equals(registration.get("tenant_kind"))
                || !"ENABLED".equals(registration.get("tenant_state"))
                || !validRevision(registration.get("control_revision"))
                || !validRevision(registration.get("tenant_session_epoch"))
                || !validRevision(registration.get("admission_epoch"))
                || !deployment.equals(registration.get("registered_deployment_id"))
                || !"LOCAL_TEST".equals(registration.get("stage"))
                || !"ACTIVE".equals(registration.get("deployment_status"))
                || !validRevision(registration.get("registration_revision"))
                || !artifact.equals(registration.get("artifact_digest"))
                || generation < 0 || !digestEquals(registration.get("credential_digest"),
                        credentialDigest(deployment, generation, credential))) throw denied();
        if (!deployment.equals(registration.get("grant_deployment_id"))
                || !tenant.equals(registration.get("grant_tenant_id"))
                || !domain.equals(registration.get("data_domain_id"))
                || !pool.equals(registration.get("execution_pool_id"))
                || !"ACTIVE".equals(registration.get("grant_status"))
                || !granted(registration.get("allow_read"))
                || !granted(registration.get("allow_write"))
                || !validRevision(registration.get("grant_revision"))) throw denied();
    }

    private static boolean validRevision(Object value) {
        return value instanceof Number number && number.longValue() >= 0;
    }

    private static long revision(Object value) {
        if (!validRevision(value)) throw denied();
        return ((Number) value).longValue();
    }

    private static boolean unsafeSideEffectEnabled(
            Environment environment, boolean allowFixedLocalWorkspace) {
        return !"false".equals(config(environment, "economy.preview.enabled"))
                || !"false".equals(config(environment, "chat.agent.tools.enabled"))
                || !"false".equals(config(environment, "economy.preview.test-issuance-enabled"))
                || !"false".equals(config(environment, "agent.rabbit-publish.enabled"))
                || !"false".equals(config(environment, "economy.hosting-rent.schema-enabled"))
                || !"false".equals(config(environment, "economy.skill.schema.enabled"))
                || !"false".equals(config(environment, "economy.skill.marketplace.enabled"))
                || !"false".equals(config(environment, "economy.skill.seed.enabled"))
                || (!allowFixedLocalWorkspace
                    && !"false".equals(config(environment, "agent.personal-workspace-storage.enabled")))
                || !"false".equals(config(environment, "agent.command-outbox.enabled"))
                || !"false".equals(config(environment, "jia.agent.formal-delivery.enabled"))
                || !"false".equals(config(environment, "wx.daily-vote.schema.install-on-startup"))
                || !"false".equals(config(environment, "archive.reader.enabled"))
                || !"false".equals(config(environment, "archive.question.enabled"))
                || !"false".equals(config(environment, "user.permit.refresh.enable"))
                || !"false".equals(config(environment, "jia.chat.service.stdio.enable"))
                || (!allowFixedLocalWorkspace
                    && !"false".equals(config(environment, "agent.task-events.enabled")))
                || !"false".equals(config(environment, "agent.rabbit-topology.enabled"))
                || !"false".equals(config(environment, "juyiting.scene-state.enabled"))
                || !"false".equals(config(environment, "juyiting.scene-events.enabled"))
                || !"false".equals(config(environment, "agent.rabbit-consume.enabled"))
                || !"false".equals(config(environment, "agent.rabbit-dispatch.enabled"))
                || !"false".equals(config(environment, "agent.rabbit-operations.read-enabled"))
                || !"false".equals(config(environment, "agent.rabbit-operations.redrive-enabled"))
                || !"false".equals(config(environment, "agent.rabbit-operations.reissue-enabled"))
                || !"simple".equals(config(environment, "spring.rabbitmq.listener.type"))
                || !"false".equals(config(environment, "spring.rabbitmq.listener.simple.auto-startup"))
                || !"false".equals(config(environment, "spring.rabbitmq.listener.direct.auto-startup"))
                || !"false".equals(config(environment, "management.health.rabbit.enabled"))
                || !"false".equals(config(environment, "management.health.elasticsearch.enabled"))
                || !"false".equals(config(environment, "management.health.ldap.enabled"))
                || !"false".equals(config(environment, "jia.chat.service.websocket.enable"))
                || !"false".equals(config(environment, "jia.chat.voice.enabled"))
                || !"false".equals(config(environment, "chat.memory.aggregate.enabled"))
                || !"false".equals(config(environment, "chat.memory.retention-cleanup.enabled"));
    }

    private static boolean granted(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() == 1;
    }

    private static String config(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.contains("${")) {
            throw denied();
        }
        return value;
    }

    private static List<String> list(Environment environment, String name) {
        return Binder.get(environment).bind(name, Bindable.listOf(String.class)).orElse(List.of());
    }

    private static Map<String, String> map(Environment environment, String name) {
        return Binder.get(environment).bind(name, Bindable.mapOf(String.class, String.class))
                .orElse(Map.of());
    }

    private static List<AgentTaskEventsProperties.AllowedScope> eventScopes(
            Environment environment) {
        return Binder.get(environment).bind(
                "agent.task-events.allowed-scopes",
                Bindable.listOf(AgentTaskEventsProperties.AllowedScope.class))
                .orElse(List.of());
    }

    private static boolean isFixedTenantMode(Environment environment) {
        String mode = environment.getProperty("cyf.tenant.admission-mode", "platform");
        if (mode == null || !mode.equals(mode.strip()) || mode.contains("${")
                || !("platform".equals(mode) || "fixed-tenant".equals(mode))) {
            throw denied();
        }
        return "fixed-tenant".equals(mode);
    }

    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        if (rows.size() != 1 || rows.getFirst() == null) throw denied();
        return rows.getFirst();
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Local TEST deployment registration is unavailable");
    }

    private static String artifactDigest(Path runningJar) {
        if (runningJar == null || !Files.isRegularFile(runningJar)
                || !runningJar.getFileName().toString().endsWith(".jar")) throw denied();
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(runningJar)) {
            byte[] bytes = new byte[65536];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        } catch (IOException failure) {
            throw denied();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] credentialDigest(String deployment, long generation, String credential) {
        MessageDigest sha = sha256();
        sha.update("cyf-deployment-v1\0".getBytes(StandardCharsets.US_ASCII));
        sha.update(deployment.getBytes(StandardCharsets.UTF_8));
        sha.update((byte) 0);
        sha.update(Long.toString(generation).getBytes(StandardCharsets.US_ASCII));
        sha.update((byte) 0);
        sha.update(credential.getBytes(StandardCharsets.US_ASCII));
        return sha.digest();
    }

    private static boolean digestEquals(Object stored, byte[] expected) {
        return stored instanceof String hex && hex.matches("[0-9a-f]{64}")
                && MessageDigest.isEqual(HexFormat.of().parseHex(hex), expected);
    }
}
