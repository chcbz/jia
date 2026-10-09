package cn.jia.starter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                .withProperty("cyf.tenant.require-bound-identity", "true")
                .withProperty("cyf.tenant.allowed-tenants[0]", "1")
                .withProperty("cyf.worker.allowed-tenants[0]", "1")
                .withProperty("cyf.worker.data-domain-id", "cyf")
                .withProperty("cyf.worker.execution-pool", "local-experiment-a")
                .withProperty("cyf.oauth.authorization-server.enabled", "false")
                .withProperty("spring.sql.init.mode", "never")
                .withProperty("cyf.schema.mode", "validate")
                .withProperty("server.address", "127.0.0.1")
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
                "--spring.datasource.url=jdbc:h2:mem:must_not_connect",
                "--logging.level.root=OFF"));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        org.junit.jupiter.api.Assertions.assertEquals("Local TEST deployment registration is unavailable", cause.getMessage());
        org.junit.jupiter.api.Assertions.assertFalse(INITIALIZER_REACHED.get());
    }

    @Test
    void localProfileDoesNotInstallMutatingDefaultSchemaInitializers() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local-test-area");
            context.register(
                    cn.jia.agent.config.AgentSchemaInitializer.class,
                    cn.jia.agent.config.AgentRuntimeV1SchemaConfiguration.class,
                    cn.jia.agent.config.AgentExecutionReportSchemaConfiguration.class,
                    cn.jia.chat.config.ChatSchemaInitializer.class,
                    RegisteredDeploymentSchedulingConfiguration.class);
            context.refresh();
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentRuntimeV1SchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.agent.config.AgentExecutionReportSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.getBeansOfType(cn.jia.chat.config.ChatSchemaInitializer.class).isEmpty());
            org.junit.jupiter.api.Assertions.assertFalse(
                    context.containsBean("registeredDeploymentSchedulingConfiguration"));
            org.junit.jupiter.api.Assertions.assertFalse(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME));
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
}
