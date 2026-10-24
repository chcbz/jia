package cn.jia.starter;

import cn.jia.core.config.CorsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.web.filter.CorsFilter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LocalTestAreaRegistrationPreflightTest {
    private static final String CREDENTIAL = "a".repeat(64);
    @TempDir Path temp;
    private JdbcTemplate jdbc;
    private static final AtomicBoolean INITIALIZER_REACHED = new AtomicBoolean();
    private Path artifact;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() throws Exception {
        artifact = temp.resolve("starter.jar");
        Files.writeString(artifact, "local-test-artifact", StandardCharsets.UTF_8);
        environment = new MockEnvironment();
        environment.setActiveProfiles("local-test-area");
        environment.withProperty("cyf.deployment.stage", "LOCAL_TEST")
                .withProperty("cyf.deployment.id", "local-api-a")
                .withProperty("cyf.deployment.registration-credential", CREDENTIAL)
                .withProperty("cyf.deployment.allow-no-business-modules", "false")
                .withProperty("cyf.deployment.allowed-modules[0]", "conversation")
                .withProperty("cyf.tenant.admission-mode", "platform")
                .withProperty("cyf.tenant.require-bound-identity", "true")
                .withProperty("cyf.tenant.allowed-tenants[0]", "1")
                .withProperty("cyf.worker.allowed-tenants[0]", "1")
                .withProperty("cyf.worker.data-domain-id", "cyf")
                .withProperty("cyf.worker.execution-pool", "local-experiment-a")
                .withProperty("cyf.oauth.authorization-server.enabled", "false")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("cyf.schema.mode", "validate")
                .withProperty("server.address", "127.0.0.1")
                .withProperty("server.port", "10019")
                .withProperty("economy.preview.enabled", "false")
                .withProperty("chat.agent.tools.enabled", "false")
                .withProperty("economy.preview.test-issuance-enabled", "false")
                .withProperty("agent.rabbit-publish.enabled", "false")
                .withProperty("economy.hosting-rent.schema-enabled", "false")
                .withProperty("economy.skill.schema.enabled", "false")
                .withProperty("economy.skill.marketplace.enabled", "false")
                .withProperty("economy.skill.seed.enabled", "false")
                .withProperty("agent.personal-workspace-storage.enabled", "false")
                .withProperty("agent.command-outbox.enabled", "false")
                .withProperty("jia.agent.formal-delivery.enabled", "false")
                .withProperty("wx.daily-vote.schema.install-on-startup", "false")
                .withProperty("archive.reader.enabled", "false")
                .withProperty("archive.question.enabled", "false")
                .withProperty("user.permit.refresh.enable", "false")
                .withProperty("jia.chat.service.stdio.enable", "false")
                .withProperty("agent.task-events.enabled", "false")
                .withProperty("agent.rabbit-topology.enabled", "false")
                .withProperty("juyiting.scene-state.enabled", "false")
                .withProperty("juyiting.scene-events.enabled", "false")
                .withProperty("agent.rabbit-consume.enabled", "false")
                .withProperty("agent.rabbit-dispatch.enabled", "false")
                .withProperty("agent.rabbit-operations.read-enabled", "false")
                .withProperty("agent.rabbit-operations.redrive-enabled", "false")
                .withProperty("agent.rabbit-operations.reissue-enabled", "false")
                .withProperty("spring.rabbitmq.listener.type", "simple")
                .withProperty("spring.rabbitmq.listener.simple.auto-startup", "false")
                .withProperty("spring.rabbitmq.listener.direct.auto-startup", "false")
                .withProperty("management.health.rabbit.enabled", "false")
                .withProperty("management.health.elasticsearch.enabled", "false")
                .withProperty("management.health.ldap.enabled", "false")
                .withProperty("jia.chat.service.websocket.enable", "false")
                .withProperty("jia.chat.voice.enabled", "false")
                .withProperty("chat.memory.aggregate.enabled", "false")
                .withProperty("chat.memory.retention-cleanup.enabled", "false");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:preflight_" + java.util.UUID.randomUUID()
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;INIT=CREATE SCHEMA IF NOT EXISTS LOCAL_TEST\\;SET SCHEMA LOCAL_TEST", "sa", ""));
        seed();
    }

    private void useFixedTenantMode() {
        var source = (org.springframework.mock.env.MockPropertySource)
                environment.getPropertySources().get("mockProperties");
        source.getSource().remove("cyf.tenant.allowed-tenants[0]");
        environment.withProperty("cyf.tenant.admission-mode", "fixed-tenant")
                .withProperty("cyf.tenant.fixed-tenant-id", "1")
                .withProperty("cyf.tenant.fixed-client-tenants.cyf-local", "1")
                .withProperty("cyf.tenant.require-bound-identity", "false")
                .withProperty("cyf.oauth.authorization-server.enabled", "true")
                .withProperty("cyf.oauth.signing.enabled", "true")
                .withProperty("cyf.oauth.signing.issuer", "http://127.0.0.1:10019")
                .withProperty("cyf.oauth.signing.private-jwk-path",
                        "/home/chc/.local/state/cyf-local-test-area/signing.jwk")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "http://127.0.0.1:10019")
                .withProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                        "http://127.0.0.1:10019/oauth2/jwks")
                .withProperty("spring.data.redis.host", "127.0.0.1")
                .withProperty("spring.data.redis.port", "16379")
                .withProperty("cors.allowed.origin.patterns", "http://127.0.0.1:10018")
                .withProperty("agent.personal-workspace-storage.enabled", "true")
                .withProperty("agent.personal-workspace-storage.root-directory",
                        "/home/chc/.local/state/cyf-local-test-area/workspace-files")
                .withProperty("agent.task-events.enabled", "true")
                .withProperty("agent.task-events.allowed-scopes[0].tenant-id", "1")
                .withProperty("agent.task-events.allowed-scopes[0].client-id", "cyf-local");
    }

    private void seed() throws Exception {
        jdbc.execute("CREATE TABLE core_tenant (tenant_id VARCHAR(50) NOT NULL, "
                + "tenant_code VARCHAR(64) NOT NULL, tenant_name VARCHAR(128) NOT NULL, "
                + "tenant_kind VARCHAR(6) NOT NULL, policy_revision BIGINT NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, PRIMARY KEY (tenant_id))");
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) NOT NULL, "
                + "state VARCHAR(32) NOT NULL, control_revision BIGINT NOT NULL, "
                + "tenant_session_epoch BIGINT NOT NULL, admission_epoch BIGINT NOT NULL, "
                + "drain_epoch BIGINT NOT NULL, drain_started_at TIMESTAMP NULL, "
                + "drain_deadline TIMESTAMP NULL, PRIMARY KEY (tenant_id))");
        jdbc.execute("CREATE TABLE user_info (id BIGINT NOT NULL, jiacn VARCHAR(32) NULL, "
                + "account_state VARCHAR(16) NOT NULL, auth_epoch BIGINT NOT NULL, PRIMARY KEY (id))");
        jdbc.execute("CREATE TABLE oauth_client (id VARCHAR(100) NOT NULL, "
                + "client_id VARCHAR(100) NOT NULL, PRIMARY KEY (id))");
        jdbc.execute("CREATE TABLE core_tenant_membership (tenant_id VARCHAR(50) NOT NULL, "
                + "uid BIGINT NOT NULL, status VARCHAR(8) NOT NULL, membership_revision BIGINT NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, PRIMARY KEY (tenant_id, uid))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_grant (client_id VARCHAR(100) NOT NULL, "
                + "tenant_id VARCHAR(50) NOT NULL, status VARCHAR(8) NOT NULL, grant_revision BIGINT NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, PRIMARY KEY (client_id, tenant_id))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_domain_grant (client_id VARCHAR(100) NOT NULL, "
                + "tenant_id VARCHAR(50) NOT NULL, data_domain_id VARCHAR(64) NOT NULL, "
                + "status VARCHAR(8) NOT NULL, domain_revision BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, "
                + "PRIMARY KEY (client_id, tenant_id, data_domain_id))");
        jdbc.execute("CREATE TABLE core_tenant_operation (tenant_id VARCHAR(50) NOT NULL, "
                + "operator_id VARCHAR(128) NOT NULL, idempotency_key VARCHAR(128) NOT NULL, "
                + "request_hash VARCHAR(64) NOT NULL, expected_revision BIGINT NOT NULL, "
                + "from_state VARCHAR(32) NOT NULL, to_state VARCHAR(32) NOT NULL, "
                + "result_revision BIGINT NOT NULL, result_snapshot VARCHAR(200) NOT NULL, "
                + "created_at TIMESTAMP NOT NULL, PRIMARY KEY (tenant_id, operator_id, idempotency_key))");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50) NOT NULL, "
                + "module_id VARCHAR(64) NOT NULL, mode_state VARCHAR(16) NOT NULL, "
                + "mode_revision BIGINT NOT NULL, migration_epoch BIGINT NOT NULL, "
                + "drain_sealed BOOLEAN NOT NULL, schema_version BIGINT NOT NULL, "
                + "mapping_digest VARCHAR(64) NULL, drain_cutoff TIMESTAMP NULL, drain_deadline TIMESTAMP NULL, "
                + "PRIMARY KEY (tenant_id, module_id))");
        jdbc.execute("CREATE TABLE core_deployment_registration (deployment_id VARCHAR(100) NOT NULL, "
                + "stage VARCHAR(12) NOT NULL, artifact_digest VARCHAR(64) NOT NULL, "
                + "credential_digest VARCHAR(64) NOT NULL, status VARCHAR(12) NOT NULL, "
                + "worker_generation BIGINT NOT NULL, registration_revision BIGINT NOT NULL, "
                + "PRIMARY KEY (deployment_id))");
        jdbc.execute("CREATE TABLE core_deployment_tenant_grant (deployment_id VARCHAR(100) NOT NULL, "
                + "tenant_id VARCHAR(50) NOT NULL, data_domain_id VARCHAR(64) NOT NULL, "
                + "execution_pool_id VARCHAR(100) NOT NULL, status VARCHAR(12) NOT NULL, "
                + "grant_revision BIGINT NOT NULL, allow_read BOOLEAN NOT NULL, allow_write BOOLEAN NOT NULL, "
                + "PRIMARY KEY (deployment_id, tenant_id, data_domain_id))");
        jdbc.execute("CREATE TABLE core_deployment_module_admission (deployment_id VARCHAR(100) NOT NULL, "
                + "tenant_id VARCHAR(50) NOT NULL, data_domain_id VARCHAR(64) NOT NULL, "
                + "module_id VARCHAR(64) NOT NULL, mode_state VARCHAR(16) NOT NULL, "
                + "mode_revision BIGINT NOT NULL, migration_epoch BIGINT NOT NULL, admission_epoch BIGINT NOT NULL, "
                + "schema_version BIGINT NOT NULL, registration_revision BIGINT NOT NULL, "
                + "grant_revision BIGINT NOT NULL, allow_read BOOLEAN NOT NULL, allow_write BOOLEAN NOT NULL, "
                + "status VARCHAR(12) NOT NULL, PRIMARY KEY (deployment_id, tenant_id, data_domain_id, module_id))");
        seedConversationSchema();
        jdbc.update("INSERT INTO core_tenant "
                + "(tenant_id, tenant_code, tenant_name, tenant_kind, policy_revision, created_at) "
                + "VALUES ('1', 'south-test', '华南测试区', 'TEST', 1, CURRENT_TIMESTAMP)");
        insertTenantControl();
        String artifactDigest = sha(Files.readAllBytes(artifact));
        String credentialDigest = sha(("cyf-deployment-v1\0local-api-a\0" + "4\0" + CREDENTIAL)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO core_deployment_registration "
                        + "(deployment_id, stage, artifact_digest, credential_digest, status, "
                        + "worker_generation, registration_revision) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "local-api-a", "LOCAL_TEST", artifactDigest, credentialDigest, "ACTIVE", 4, 11);
        insertDeploymentGrant();
        insertModuleControl();
        insertModuleAdmission();
    }

    private void seedConversationSchema() {
        jdbc.execute("CREATE TABLE chat_conversation ("
                + "id BIGINT NOT NULL, tenant_id VARCHAR(50) NULL, "
                + "data_domain_id VARCHAR(64) NOT NULL, jiacn VARCHAR(50) NULL, "
                + "deleted_at BIGINT NULL, lifecycle_generation BIGINT NOT NULL, "
                + "data_scope_mode VARCHAR(20) DEFAULT 'LEGACY_CLIENT' NOT NULL, "
                + "memory_published_token CHAR(64) NULL DEFAULT NULL, "
                + "memory_published_revision BIGINT NULL DEFAULT NULL, "
                + "update_time BIGINT NULL, PRIMARY KEY (id))");
        jdbc.execute("CREATE INDEX idx_chat_conversation_domain_owner ON chat_conversation "
                + "(tenant_id, data_domain_id, jiacn, deleted_at, lifecycle_generation, update_time)");
        jdbc.execute("CREATE TABLE chat_message ("
                + "id BIGINT NOT NULL, conversation_id VARCHAR(100) NOT NULL, "
                + "tenant_id VARCHAR(50) NULL, data_domain_id VARCHAR(64) NOT NULL, "
                + "jiacn VARCHAR(50) NULL, create_time BIGINT NULL, PRIMARY KEY (id))");
        jdbc.execute("CREATE INDEX idx_chat_message_domain_owner_conversation ON chat_message "
                + "(tenant_id, data_domain_id, jiacn, conversation_id, create_time, id)");
        jdbc.execute("CREATE TABLE chat_memory_publication ("
                + "id BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL, scope_mode VARCHAR(20) NOT NULL, "
                + "scope_key VARCHAR(64) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL, "
                + "conversation_id VARCHAR(100) NOT NULL, lifecycle_generation BIGINT NOT NULL, "
                + "snapshot_token CHAR(64) NOT NULL, es_revision BIGINT NOT NULL, "
                + "document_id VARCHAR(96) NOT NULL, message_count INT NOT NULL, state VARCHAR(20) NOT NULL, "
                + "summary_content LONGTEXT NULL DEFAULT NULL, embedding_bytes BLOB NULL DEFAULT NULL, "
                + "payload_digest CHAR(64) NULL DEFAULT NULL, "
                + "published_at BIGINT NULL DEFAULT NULL, tombstoned_at BIGINT NULL DEFAULT NULL, "
                + "create_time BIGINT NOT NULL, update_time BIGINT NOT NULL, PRIMARY KEY (id), "
                + "CONSTRAINT chk_memory_publication_mode CHECK (scope_mode IN ('LEGACY_CLIENT','DOMAIN')), "
                + "CONSTRAINT chk_memory_publication_state CHECK "
                + "(state IN ('ALLOCATED','STAGED','PUBLISHED','TOMBSTONED')), "
                + "CONSTRAINT chk_memory_publication_payload CHECK "
                + "((state IN ('ALLOCATED','TOMBSTONED') AND summary_content IS NULL "
                + "AND embedding_bytes IS NULL AND payload_digest IS NULL) OR "
                + "(state IN ('STAGED','PUBLISHED','TOMBSTONED') AND summary_content IS NOT NULL "
                + "AND embedding_bytes IS NOT NULL AND payload_digest IS NOT NULL)), "
                + "CONSTRAINT chk_memory_publication_revision CHECK (es_revision > 0), "
                + "CONSTRAINT chk_memory_publication_count CHECK (message_count > 0))");
        jdbc.execute("CREATE UNIQUE INDEX uk_memory_publication_token ON chat_memory_publication "
                + "(tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id, lifecycle_generation, snapshot_token)");
        jdbc.execute("CREATE UNIQUE INDEX uk_memory_publication_revision ON chat_memory_publication "
                + "(tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id, es_revision)");
        jdbc.execute("CREATE UNIQUE INDEX uk_memory_publication_document ON chat_memory_publication (document_id)");
        jdbc.execute("CREATE TABLE chat_memory_work ("
                + "id BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL, scope_mode VARCHAR(20) NOT NULL, "
                + "scope_key VARCHAR(64) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL, "
                + "conversation_id VARCHAR(100) NOT NULL, lifecycle_generation BIGINT NOT NULL, "
                + "state VARCHAR(20) NOT NULL, due_at BIGINT NOT NULL, lease_owner VARCHAR(100) NULL DEFAULT NULL, "
                + "lease_until BIGINT NULL DEFAULT NULL, attempts INT DEFAULT 0 NOT NULL, "
                + "wake_requested TINYINT DEFAULT 0 NOT NULL, last_error VARCHAR(1000) NULL DEFAULT NULL, "
                + "create_time BIGINT NOT NULL, update_time BIGINT NOT NULL, PRIMARY KEY (id), "
                + "CONSTRAINT chk_memory_work_mode CHECK (scope_mode IN ('LEGACY_CLIENT','DOMAIN')), "
                + "CONSTRAINT chk_memory_work_state CHECK (state IN ('READY','LEASED','RETRY','DONE','DEAD')), "
                + "CONSTRAINT chk_memory_work_attempts CHECK (attempts >= 0))");
        jdbc.execute("CREATE UNIQUE INDEX uk_memory_work_scope ON chat_memory_work "
                + "(tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id)");
        jdbc.execute("CREATE INDEX idx_memory_work_claim ON chat_memory_work "
                + "(state, due_at, lease_until, id)");
    }

    private void insertTenantControl() {
        jdbc.update("INSERT INTO core_tenant_control "
                        + "(tenant_id, state, control_revision, tenant_session_epoch, admission_epoch, drain_epoch) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "1", "ENABLED", 3, 2, 7, 0);
    }

    private void insertDeploymentGrant() {
        jdbc.update("INSERT INTO core_deployment_tenant_grant "
                        + "(deployment_id, tenant_id, data_domain_id, execution_pool_id, status, "
                        + "allow_read, allow_write, grant_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "local-api-a", "1", "cyf", "local-experiment-a", "ACTIVE", true, true, 13);
    }

    private void insertModuleControl() {
        jdbc.update("INSERT INTO core_tenant_module_control "
                        + "(tenant_id, module_id, mode_state, mode_revision, migration_epoch, "
                        + "drain_sealed, schema_version, mapping_digest) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "1", "conversation", "DOMAIN", 5, 6, true, 2, "b".repeat(64));
    }

    private void insertModuleAdmission() {
        jdbc.update("INSERT INTO core_deployment_module_admission "
                        + "(deployment_id, tenant_id, data_domain_id, module_id, mode_state, mode_revision, "
                        + "migration_epoch, admission_epoch, schema_version, registration_revision, "
                        + "grant_revision, status, allow_read, allow_write) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "local-api-a", "1", "cyf", "conversation", "DOMAIN", 5, 6, 7, 2, 11, 13,
                "ACTIVE", true, true);
    }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    @Test
    void canonicalizesMySqlCheckMetadataWithoutWeakeningBooleanStructure() {
        String expected = "(state IN ('ALLOCATED','TOMBSTONED') AND summary_content IS NULL "
                + "AND embedding_bytes IS NULL AND payload_digest IS NULL) OR "
                + "(state IN ('STAGED','PUBLISHED','TOMBSTONED') "
                + "AND summary_content IS NOT NULL AND embedding_bytes IS NOT NULL "
                + "AND payload_digest IS NOT NULL)";
        String mysqlMetadata = "(((`state` in (_utf8mb4\\'ALLOCATED\\',"
                + "_utf8mb4\\'TOMBSTONED\\')) and (`summary_content` is null) "
                + "and (`embedding_bytes` is null) and (`payload_digest` is null)) or "
                + "((`state` in (_utf8mb4\\'STAGED\\',_utf8mb4\\'PUBLISHED\\',"
                + "_utf8mb4\\'TOMBSTONED\\')) and (`summary_content` is not null) "
                + "and (`embedding_bytes` is not null) and (`payload_digest` is not null)))";

        org.junit.jupiter.api.Assertions.assertEquals(
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(expected),
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(mysqlMetadata));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(expected),
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "state IN ('ALLOCATED','TOMBSTONED') AND "
                                + "(summary_content IS NULL OR embedding_bytes IS NULL) AND "
                                + "payload_digest IS NULL"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "state IN ('ALLOCATED','TOMBSTONED')"),
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "state IN ('allocated','tombstoned')"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "state IN ('ALLOCATED','TOMBSTONED')"),
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "state IN ('ALLOCATED_utf8mb4','TOMBSTONED')"));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "scope_mode IN ('LEGACY_CLIENT','DOMAIN')"),
                LocalTestAreaBusinessSchemaValidator.canonicalExpression(
                        "scope_utf8mb4_mode IN ('LEGACY_CLIENT','DOMAIN')"));
    }

    @Test
    void acceptsOnlyMatchingRegisteredTestDeployment() {
        assertDoesNotThrow(() -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        assertDoesNotThrow(() -> LocalTestAreaRegistrationPreflight.createAdmissionGuard(
                jdbc, environment, artifact));
    }

    @Test
    void fixedTenantAcceptsExactLocalScopeWithoutPlatformSql() {
        useFixedTenantMode();
        JdbcOperations noPlatformSql = mock(JdbcOperations.class);

        assertDoesNotThrow(() -> LocalTestAreaRegistrationPreflight.verify(
                noPlatformSql, environment, null));
        verifyNoInteractions(noPlatformSql);

        JdbcTemplate noGuardSql = mock(JdbcTemplate.class);
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.createAdmissionGuard(
                        noGuardSql, environment, null));
        verifyNoInteractions(noGuardSql);
    }

    @Test
    void fixedTenantBeanAssemblyOmitsPlatformGuardAndSql() {
        useFixedTenantMode();
        JdbcOperations noPlatformSql = mock(JdbcOperations.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(JdbcOperations.class, () -> noPlatformSql);
            context.register(LocalTestAreaRegistrationPreflight.class);
            context.refresh();
            assertTrue(context.containsBean("localTestRegistration"));
            assertFalse(context.containsBean("localTestTenantModuleAdmissionGuard"));
        }
        verifyNoInteractions(noPlatformSql);
    }

    @Test
    void fixedTenantRejectsWrongTenantOrClientWithoutSql() {
        useFixedTenantMode();
        JdbcOperations noPlatformSql = mock(JdbcOperations.class);

        environment.withProperty("cyf.tenant.fixed-tenant-id", "2");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("cyf.tenant.fixed-tenant-id", "1");
        environment.withProperty("cyf.tenant.fixed-client-tenants.cyf-local", "2");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("cyf.tenant.fixed-client-tenants.cyf-local", "1");
        environment.withProperty("cyf.tenant.fixed-client-tenants.other-client", "1");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        ((org.springframework.mock.env.MockPropertySource)
                environment.getPropertySources().get("mockProperties"))
                .getSource().remove("cyf.tenant.fixed-client-tenants.other-client");
        environment.withProperty("cyf.worker.allowed-tenants[0]", "2");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));

        verifyNoInteractions(noPlatformSql);
    }

    @Test
    void fixedTenantRejectsExternalPathsCorsAndUnsafeFeaturesWithoutSql() {
        useFixedTenantMode();
        JdbcOperations noPlatformSql = mock(JdbcOperations.class);

        environment.withProperty("cyf.oauth.signing.private-jwk-path", "/tmp/signing.jwk");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("cyf.oauth.signing.private-jwk-path",
                "/home/chc/.local/state/cyf-local-test-area/signing.jwk");
        environment.withProperty("agent.personal-workspace-storage.root-directory", "/tmp/files");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("agent.personal-workspace-storage.root-directory",
                "/home/chc/.local/state/cyf-local-test-area/workspace-files");
        environment.withProperty("cors.allowed.origin.patterns", "http://localhost:10018");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("cors.allowed.origin.patterns", "http://127.0.0.1:10018");
        environment.withProperty("agent.task-events.allowed-scopes[0].client-id", "other-client");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));
        environment.withProperty("agent.task-events.allowed-scopes[0].client-id", "cyf-local");
        environment.withProperty("agent.rabbit-publish.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(noPlatformSql, environment, null));

        verifyNoInteractions(noPlatformSql);
    }

    @Test
    void corsFilterAllowsOnlyCredentialedLocalUiPreflight() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("localCors", Map.of(
                            "cors.allowed.origin.patterns", "http://127.0.0.1:10018")));
            context.register(CorsConfig.class);
            context.refresh();

            @SuppressWarnings("unchecked")
            FilterRegistrationBean<CorsFilter> registration =
                    context.getBean(FilterRegistrationBean.class);
            CorsFilter filter = registration.getFilter();

            AtomicBoolean localChainInvoked = new AtomicBoolean();
            MockHttpServletRequest localRequest = new MockHttpServletRequest(
                    "OPTIONS", "/oauth2/token");
            localRequest.addHeader("Origin", "http://127.0.0.1:10018");
            localRequest.addHeader("Access-Control-Request-Method", "POST");
            localRequest.addHeader("Access-Control-Request-Headers",
                    "Authorization,Content-Type");
            MockHttpServletResponse localResponse = new MockHttpServletResponse();

            filter.doFilter(localRequest, localResponse,
                    (request, response) -> localChainInvoked.set(true));

            assertEquals(200, localResponse.getStatus());
            assertEquals("http://127.0.0.1:10018",
                    localResponse.getHeader("Access-Control-Allow-Origin"));
            assertEquals("true", localResponse.getHeader("Access-Control-Allow-Credentials"));
            assertTrue(localResponse.getHeader("Access-Control-Allow-Methods").contains("POST"));
            assertFalse(localChainInvoked.get());

            AtomicBoolean externalChainInvoked = new AtomicBoolean();
            MockHttpServletRequest externalRequest = new MockHttpServletRequest(
                    "OPTIONS", "/oauth2/token");
            externalRequest.addHeader("Origin", "https://evil.example");
            externalRequest.addHeader("Access-Control-Request-Method", "POST");
            externalRequest.addHeader("Access-Control-Request-Headers",
                    "Authorization,Content-Type");
            MockHttpServletResponse externalResponse = new MockHttpServletResponse();

            filter.doFilter(externalRequest, externalResponse,
                    (request, response) -> externalChainInvoked.set(true));

            assertEquals(403, externalResponse.getStatus());
            assertNull(externalResponse.getHeader("Access-Control-Allow-Origin"));
            assertFalse(externalChainInvoked.get());
        }
    }

    @Test
    void fixedTenantBeforeRefreshDoesNotRequireOrQueryPlatformDatasource() {
        useFixedTenantMode();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            assertDoesNotThrow(() -> new LocalTestAreaRegistrationPreflight.BeforeRefresh()
                    .initialize(context));
        }
    }

    @Test
    void greyApplicationSettingsKeepLocalIsolationOverrides() {
        JdbcTemplate unusedJdbc = mock(JdbcTemplate.class);
        var application = new SpringApplication(GreyConfigFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of(
                "CYF_SHARED_DATABASE_URL", "jdbc:mysql://127.0.0.1:3306/jia",
                "CYF_LOCAL_DATABASE_USER", "fixture-user",
                "CYF_LOCAL_DATABASE_PASSWORD", "fixture-password",
                "CYF_LOCAL_REDIS_PASSWORD", "fixture-local-redis-password",
                "CYF_LOCAL_REGISTRATION_CREDENTIAL", "fixture-local-fixed"));
        application.addInitializers(new LocalTestAreaRegistrationPreflight.BeforeRefresh());
        try (var context = application.run(
                "--spring.profiles.active=local-test-area",
                "--spring.config.additional-location=classpath:/application-grey.properties,"
                        + "classpath:/application-local-test-area.properties",
                "--logging.file.name=",
                "--logging.level.root=OFF")) {
            var effective = context.getEnvironment();
            assertEquals("true", effective.getProperty("jia.chat.ai.enabled"));
            assertEquals("openai", effective.getProperty("jia.chat.ai.provider"));
            assertEquals("openai", effective.getProperty("spring.ai.model.embedding"));
            assertEquals("text-embedding-3-small",
                    effective.getProperty("spring.ai.openai.embedding.model"));
            assertEquals("false", effective.getProperty("server.ssl.enabled"));
            assertEquals("fixture-local-redis-password", effective.getProperty("spring.data.redis.password"));
            assertEquals("cyf-local", effective.getProperty("oauth.default.clientId"));
            assertEquals("chat_memory_local_tenant_1", effective.getProperty("chat.memory.vector-index"));
            assertEquals("/home/chc/.local/state/cyf-local-test-area/files",
                    effective.getProperty("jia.file.path"));
            assertDoesNotThrow(() -> LocalTestAreaRegistrationPreflight.verify(unusedJdbc, effective, null));
            assertTrue(context.getBeansOfType(DataSource.class).isEmpty());
            assertFalse(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }
        verifyNoInteractions(unusedJdbc);
    }

    @Test
    void localProfilePropertiesDeclareExactFixedTenantBoundary() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream(
                "/application-local-test-area.properties")) {
            assertTrue(input != null);
            properties.load(input);
        }
        assertEquals("fixed-tenant", properties.getProperty("cyf.tenant.admission-mode"));
        assertEquals("1", properties.getProperty("cyf.tenant.fixed-tenant-id"));
        assertEquals("1", properties.getProperty("cyf.tenant.fixed-client-tenants.cyf-local"));
        assertFalse(properties.containsKey("cyf.tenant.allowed-tenants[0]"));
        assertEquals("true", properties.getProperty("cyf.tenant.mybatis.enabled"));
        assertEquals(List.of(
                        "task_plan", "task_item", "v_task_item",
                        "chat_conversation", "chat_message", "chat_context_snapshot",
                        "chat_request", "chat_turn", "chat_dispatch_outbox",
                        "chat_conversation_event", "chat_interaction_step",
                        "chat_step_execution_link", "chat_bounty_binding",
                        "chat_conversation_asset", "chat_typed_outcome",
                        "chat_typed_pending_question", "chat_typed_proposal",
                        "chat_typed_admission", "agent_persona", "agent_persona_binding",
                        "agent_identity_registry", "agent_identity_alias", "agent_runtime",
                        "agent_task_meta", "agent_task_member", "agent_task_work_item",
                        "agent_task_event", "agent_task_execution_grant", "agent_task_funding",
                        "agent_task_funding_operation", "agent_task_provider_cost_consent",
                        "agent_task_bounty_bootstrap_outbox", "agent_task_formal_delivery",
                        "agent_task_request", "agent_selected_output_finalization",
                        "agent_command_delivery", "agent_outbox_event",
                        "agent_task_creation_operation", "agent_task_requirement_snapshot",
                        "hall_request_draft", "agent_personal_workspace_file",
                        "agent_personal_workspace_file_version",
                        "agent_personal_workspace_operation",
                        "agent_personal_workspace_execution",
                        "agent_personal_workspace_execution_input",
                        "agent_personal_workspace_execution_output",
                        "agent_personal_workspace_conversation_file_link",
                        "agent_personal_workspace_conversation_link_operation",
                        "agent_personal_workspace_task_file_link",
                        "agent_personal_workspace_task_link_operation",
                        "hall_private_case", "hall_case_execution", "hall_private_mark"),
                indexedProperties(properties, "cyf.tenant.mybatis.tenant-tables"));
        assertEquals(List.of("user_info", "oauth_client", "user_perms_rel",
                        "user_role_rel", "user_group_rel", "user_perms"),
                indexedProperties(properties, "cyf.tenant.mybatis.global-tables"));
        assertEquals("1", properties.getProperty("cyf.worker.allowed-tenants[0]"));
        assertEquals("true", properties.getProperty("cyf.oauth.authorization-server.enabled"));
        assertEquals("http://127.0.0.1:10019",
                properties.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"));
        assertEquals("http://127.0.0.1:10019/oauth2/jwks",
                properties.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"));
        assertEquals("/home/chc/.local/state/cyf-local-test-area/signing.jwk",
                properties.getProperty("cyf.oauth.signing.private-jwk-path"));
        assertEquals("http://127.0.0.1:10018",
                properties.getProperty("cors.allowed.origin.patterns"));
        assertEquals("127.0.0.1", properties.getProperty("spring.data.redis.host"));
        assertEquals("16379", properties.getProperty("spring.data.redis.port"));
        assertEquals("/home/chc/.local/state/cyf-local-test-area/workspace-files",
                properties.getProperty("agent.personal-workspace-storage.root-directory"));
        assertEquals("1", properties.getProperty("agent.task-events.allowed-scopes[0].tenant-id"));
        assertEquals("cyf-local",
                properties.getProperty("agent.task-events.allowed-scopes[0].client-id"));
    }

    private static List<String> indexedProperties(Properties properties, String prefix) {
        List<String> values = new ArrayList<>();
        for (int index = 0; ; index++) {
            String value = properties.getProperty(prefix + "[" + index + "]");
            if (value == null) break;
            assertFalse(value.contains("*"), () -> "Wildcard table entry for " + prefix);
            values.add(value);
        }
        String indexedPrefix = prefix + "[";
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(indexedPrefix)) continue;
            String suffix = name.substring(indexedPrefix.length());
            assertTrue(suffix.matches("[0-9]+\\]"), () -> "Malformed indexed property " + name);
            int index = Integer.parseInt(suffix.substring(0, suffix.length() - 1));
            assertTrue(index < values.size(), () -> "Non-contiguous or extra property " + name);
        }
        return List.copyOf(values);
    }

    @Test
    void acceptsOnlyExplicitZeroBusinessModuleMode() {
        var source = (org.springframework.mock.env.MockPropertySource)
                environment.getPropertySources().get("mockProperties");
        source.getSource().remove("cyf.deployment.allowed-modules[0]");
        environment.withProperty("cyf.deployment.allow-no-business-modules", "true");
        jdbc.update("DELETE FROM core_deployment_module_admission");
        jdbc.execute("DROP TABLE chat_message");
        jdbc.execute("DROP TABLE chat_conversation");
        assertDoesNotThrow(() -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMissingOrDriftedConversationSchema() throws Exception {
        jdbc.execute("DROP INDEX idx_chat_conversation_domain_owner");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_message ALTER COLUMN data_domain_id DROP NOT NULL");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("DROP TABLE chat_message");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMissingMemoryPublicationColumnsOrTables() throws Exception {
        jdbc.execute("ALTER TABLE chat_conversation DROP COLUMN data_scope_mode");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_conversation DROP COLUMN memory_published_token");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("DROP TABLE chat_memory_publication");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("DROP TABLE chat_memory_work");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_memory_publication "
                + "DROP CONSTRAINT chk_memory_publication_payload");
        jdbc.execute("ALTER TABLE chat_memory_publication DROP COLUMN payload_digest");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_memory_publication "
                + "ALTER COLUMN summary_content SET NOT NULL");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_memory_publication "
                + "ALTER COLUMN summary_content VARCHAR(100)");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMemoryPublicationIndexAndDefaultDrift() throws Exception {
        jdbc.execute("DROP INDEX uk_memory_publication_document");
        jdbc.execute("CREATE INDEX uk_memory_publication_document "
                + "ON chat_memory_publication (document_id)");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("DROP INDEX idx_memory_work_claim");
        jdbc.execute("CREATE INDEX idx_memory_work_claim ON chat_memory_work "
                + "(due_at, state, lease_until, id)");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_conversation ALTER COLUMN data_scope_mode "
                + "SET DEFAULT 'DOMAIN'");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE chat_memory_work ALTER COLUMN wake_requested SET DEFAULT 1");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesRevokedDeploymentAndWrongPool() {
        jdbc.update("UPDATE core_deployment_registration SET status = 'REVOKED'");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        jdbc.update("UPDATE core_deployment_registration SET status = 'ACTIVE'");
        jdbc.update("UPDATE core_deployment_tenant_grant SET execution_pool_id = 'shared-pool'");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesDuplicateCompatibleAuthoritySchemas() throws Exception {
        jdbc.execute("ALTER TABLE core_tenant_control DROP PRIMARY KEY");
        insertTenantControl();
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE core_deployment_tenant_grant DROP PRIMARY KEY");
        insertDeploymentGrant();
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE core_deployment_module_admission DROP PRIMARY KEY");
        insertModuleAdmission();
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMissingAuthorityTableOrColumn() throws Exception {
        jdbc.execute("DROP TABLE core_tenant_operation");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE core_tenant DROP COLUMN tenant_name");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));


        setUp();
        jdbc.execute("CREATE TABLE coreXtenant (tenant_name VARCHAR(128) NOT NULL)");
        jdbc.execute("ALTER TABLE core_tenant DROP COLUMN tenant_name");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        setUp();
        jdbc.execute("CREATE SCHEMA LOCALXTEST");
        jdbc.execute("CREATE TABLE LOCALXTEST.coreXtenant (tenant_name VARCHAR(128) NOT NULL)");
        jdbc.execute("ALTER TABLE core_tenant DROP COLUMN tenant_name");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesNullabilityOrPrimaryKeyOrderDrift() throws Exception {
        jdbc.execute("ALTER TABLE core_tenant ALTER COLUMN tenant_name SET NULL");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));

        setUp();
        jdbc.execute("ALTER TABLE core_tenant_membership DROP PRIMARY KEY");
        jdbc.execute("ALTER TABLE core_tenant_membership ADD PRIMARY KEY (uid, tenant_id)");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMalformedNoBusinessModuleFlag() {
        environment.withProperty("cyf.deployment.allow-no-business-modules", "yes");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesUnexpectedOrDuplicateModuleAuthority() {
        environment.withProperty("cyf.deployment.allowed-modules[1]", "workspace");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesDuplicateModuleControl() {
        jdbc.execute("ALTER TABLE core_tenant_module_control DROP PRIMARY KEY");
        insertModuleControl();
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesMissingOrDriftedModuleAdmission() {
        jdbc.update("DELETE FROM core_deployment_module_admission");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        insertModuleAdmission();
        jdbc.update("UPDATE core_tenant_module_control SET mode_revision = 6");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        jdbc.update("UPDATE core_tenant_module_control SET mode_revision = 5");
        jdbc.update("UPDATE core_deployment_module_admission SET registration_revision = 12");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesEveryModuleAdmissionMetadataDrift() throws Exception {
        for (String update : new String[] {
                "UPDATE core_deployment_module_admission SET admission_epoch = 8",
                "UPDATE core_deployment_tenant_grant SET grant_revision = 14",
                "UPDATE core_tenant_module_control SET drain_sealed = FALSE",
                "UPDATE core_tenant_module_control SET schema_version = 0",
                "UPDATE core_tenant_module_control SET mapping_digest = NULL",
                "UPDATE core_deployment_module_admission SET allow_write = FALSE",
                "UPDATE core_deployment_module_admission SET migration_epoch = -1"
        }) {
            jdbc.update(update);
            assertThrows(IllegalStateException.class,
                    () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
            setUp();
        }
    }
    @Test
    void deniesAlteredArtifactAndCredential() throws Exception {
        Files.writeString(artifact, "changed");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        Files.writeString(artifact, "local-test-artifact");
        environment.withProperty("cyf.deployment.registration-credential", "b".repeat(64));
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesInheritedProductionProfileOrNonTestRegion() {
        environment.setActiveProfiles("local-test-area", "prod");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.setActiveProfiles("local-test-area");
        jdbc.update("UPDATE core_tenant SET tenant_kind = 'NORMAL'");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        jdbc.update("UPDATE core_tenant SET tenant_kind = 'TEST'");
        jdbc.update("UPDATE core_deployment_tenant_grant SET allow_write = FALSE");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void deniesExternallyReachableOrSideEffectEnabledLocalProcess() {
        environment.withProperty("server.address", "0.0.0.0");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("server.address", "127.0.0.1");
        environment.withProperty("agent.rabbit-publish.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("agent.rabbit-publish.enabled", "false");
        environment.withProperty("agent.task-events.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("agent.task-events.enabled", "false");
        environment.withProperty("agent.rabbit-topology.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("agent.rabbit-topology.enabled", "false");
        environment.withProperty("spring.rabbitmq.listener.simple.auto-startup", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("spring.rabbitmq.listener.simple.auto-startup", "false");
        environment.withProperty("spring.rabbitmq.listener.type", "direct");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("spring.rabbitmq.listener.type", "simple");
        environment.withProperty("spring.rabbitmq.listener.direct.auto-startup", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("spring.rabbitmq.listener.direct.auto-startup", "false");
        environment.withProperty("management.health.rabbit.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("management.health.rabbit.enabled", "false");
        environment.withProperty("management.health.elasticsearch.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("management.health.elasticsearch.enabled", "false");
        environment.withProperty("management.health.ldap.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("management.health.ldap.enabled", "false");
        environment.withProperty("juyiting.scene-state.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("juyiting.scene-state.enabled", "false");
        environment.withProperty("juyiting.scene-events.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("juyiting.scene-events.enabled", "false");
        environment.withProperty("chat.memory.aggregate.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
        environment.withProperty("chat.memory.aggregate.enabled", "false");
        environment.withProperty("chat.memory.retention-cleanup.enabled", "true");
        assertThrows(IllegalStateException.class,
                () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
    }

    @Test
    void localProfileWiresFailClosedStartupCallback() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(org.springframework.jdbc.core.JdbcOperations.class, () -> jdbc);
            context.register(LocalTestAreaRegistrationPreflight.class);
            // A test JVM's class path is not a single bootJar; refresh must fail
            // rather than leave a LOCAL_TEST context running without registration.
            var failure = assertThrows(RuntimeException.class, context::refresh);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            org.junit.jupiter.api.Assertions.assertEquals("Local TEST deployment registration is unavailable", cause.getMessage());
            assertThrows(IllegalStateException.class,
                    () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment,
                            Path.of(System.getProperty("java.class.path"))));
        }
    }

    @Test
    void localProfileWithOverriddenStageStillRejectsStartup() {
        environment.withProperty("cyf.deployment.stage", "SHARED");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.registerBean(org.springframework.jdbc.core.JdbcOperations.class, () -> jdbc);
            context.register(LocalTestAreaRegistrationPreflight.class);
            assertThrows(IllegalStateException.class,
                    () -> LocalTestAreaRegistrationPreflight.verify(jdbc, environment, artifact));
            var failure = assertThrows(RuntimeException.class, context::refresh);
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            org.junit.jupiter.api.Assertions.assertEquals("Local TEST deployment registration is unavailable", cause.getMessage());
        }
    }

    @Test
    void nonLocalStageDoesNotInstallRegistrationCallback() {
        environment.setActiveProfiles("prod");
        environment.withProperty("cyf.deployment.stage", "SHARED");
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(LocalTestAreaRegistrationPreflight.class);
            context.refresh();
            org.junit.jupiter.api.Assertions.assertFalse(context.containsBean("localTestRegistration"));
        }
    }

    @Test
    void preRefreshDenialPreventsSchemaInitializerFromRunning() {
        INITIALIZER_REACHED.set(false);
        var application = new SpringApplication(EarlyFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addInitializers(new LocalTestAreaRegistrationPreflight.BeforeRefresh());
        var failure = assertThrows(RuntimeException.class, () -> application.run(
                "--spring.profiles.active=local-test-area",
                "--cyf.tenant.admission-mode=platform",
                "--spring.datasource.url=jdbc:h2:mem:must_not_connect",
                "--logging.level.root=OFF"));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        org.junit.jupiter.api.Assertions.assertEquals("Local TEST deployment registration is unavailable", cause.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(INITIALIZER_REACHED.get());
    }

    @Test
    void localProfileExcludesMaintenanceInitializersWithoutDatabaseAccess() {
        JdbcTemplate validationJdbc = mock(JdbcTemplate.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local-test-area");
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "validate-only", Map.of("cyf.schema.mode", "validate")));
            context.registerBean(JdbcTemplate.class, () -> validationJdbc);
            context.register(
                    cn.jia.agent.config.AgentSchemaInitializer.class,
                    cn.jia.agent.config.AgentRuntimeV1SchemaConfiguration.class,
                    cn.jia.agent.config.AgentRuntimeSessionFenceSchemaInitializer.class,
                    cn.jia.agent.config.AgentExecutionReportSchemaConfiguration.class,
                    cn.jia.chat.config.ChatSchemaInitializer.class,
                    RegisteredDeploymentSchedulingConfiguration.class);
            context.refresh();
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentRuntimeV1SchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentRuntimeSessionFenceSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentExecutionReportSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.chat.config.ChatSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertFalse(
                    context.containsBean("registeredDeploymentSchedulingConfiguration"));
            org.junit.jupiter.api.Assertions.assertFalse(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }
        org.mockito.Mockito.verify(validationJdbc).afterPropertiesSet();
        org.mockito.Mockito.verifyNoMoreInteractions(validationJdbc);
    }

    @Test
    void explicitMaintenanceProfileRetainsValidateOnlyRuntimeV1Checks() throws Exception {
        JdbcTemplate validationJdbc = runtimeV1ValidationJdbc();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("schema-maintenance");
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "validate-only", Map.of("cyf.schema.mode", "validate")));
            context.registerBean(JdbcTemplate.class, () -> validationJdbc);
            context.register(cn.jia.agent.config.AgentRuntimeV1SchemaConfiguration.class);
            context.refresh();
            assertEquals(1, context.getBeansOfType(
                    cn.jia.agent.config.AgentRuntimeV1SchemaInitializer.class).size());
        }
        verify(validationJdbc, org.mockito.Mockito.atLeastOnce()).getDataSource();
        verify(validationJdbc, never()).execute(anyString());
        verify(validationJdbc, never()).update(anyString(), any(Object[].class));
    }

    private static JdbcTemplate runtimeV1ValidationJdbc() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(jdbc.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MySQL");
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any())).thenReturn(Map.of(
                "engine", "InnoDB", "table_collation", "utf8mb4_0900_bin"));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("information_schema.columns")) return runtimeV1Columns();
            if (sql.contains("information_schema.statistics")) return runtimeV1Indexes();
            if (sql.contains("information_schema.table_constraints")) return List.of(Map.of(
                    "constraint_name", "chk_runtime_v1_status",
                    "enforced", "YES",
                    "check_clause", "status IN ('PENDING','ACTIVE','REVOKED','REBINDS_REQUIRED')"));
            throw new AssertionError("Unexpected validation query: " + sql);
        });
        return jdbc;
    }

    private static List<Map<String, Object>> runtimeV1Columns() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(column("id", "bigint", "NO", null, "auto_increment"));
        rows.add(column("installation_id", "varchar(100)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("canonical_agent_id", "varchar(100)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("manifest_version", "varchar(100)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("manifest_sha256", "varchar(72)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("enrollment_secret_hash", "binary(32)", "NO", null, ""));
        rows.add(column("enrollment_expires_at", "bigint", "NO", null, ""));
        rows.add(column("enrollment_consumed_at", "bigint", "YES", null, ""));
        rows.add(column("runtime_authorization_hash", "binary(32)", "YES", null, ""));
        rows.add(column("runtime_authorization_issued_at", "bigint", "YES", null, ""));
        rows.add(column("status", "varchar(32)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("last_heartbeat_at", "bigint", "YES", null, ""));
        rows.add(column("version", "bigint", "NO", null, ""));
        rows.add(column("tenant_id", "varchar(50)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("client_id", "varchar(50)", "NO", "utf8mb4_0900_bin", ""));
        rows.add(column("create_time", "bigint", "YES", null, ""));
        rows.add(column("update_time", "bigint", "YES", null, ""));
        return rows;
    }

    private static Map<String, Object> column(
            String name, String type, String nullable, String collation, String extra) {
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("column_name", name);
        row.put("column_type", type);
        row.put("is_nullable", nullable);
        row.put("collation_name", collation);
        row.put("extra", extra);
        return row;
    }

    private static List<Map<String, Object>> runtimeV1Indexes() {
        List<Map<String, Object>> rows = new ArrayList<>();
        addIndex(rows, "PRIMARY", 0, "id");
        addIndex(rows, "idx_runtime_v1_identity", 1,
                "tenant_id", "client_id", "canonical_agent_id", "status");
        addIndex(rows, "idx_runtime_v1_scope", 1, "tenant_id", "client_id", "installation_id");
        addIndex(rows, "uk_runtime_v1_authorization", 0, "runtime_authorization_hash");
        addIndex(rows, "uk_runtime_v1_installation", 0, "installation_id");
        return rows;
    }

    private static void addIndex(
            List<Map<String, Object>> rows, String name, int nonUnique, String... columns) {
        for (int index = 0; index < columns.length; index++) {
            Map<String, Object> row = new java.util.HashMap<>();
            row.put("index_name", name);
            row.put("non_unique", nonUnique);
            row.put("seq_in_index", index + 1);
            row.put("column_name", columns[index]);
            row.put("sub_part", null);
            rows.add(row);
        }
    }

    @Test
    void nonLocalProfileKeepsProductionSchedulingEnabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            context.register(RegisteredDeploymentSchedulingConfiguration.class);
            context.refresh();
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.containsBean("registeredDeploymentSchedulingConfiguration"));
            org.junit.jupiter.api.Assertions.assertTrue(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EarlyFixture {
        @Bean InitializingBean schemaInstallerProbe() { return () -> INITIALIZER_REACHED.set(true); }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(RegisteredDeploymentSchedulingConfiguration.class)
    static class GreyConfigFixture { }
}
