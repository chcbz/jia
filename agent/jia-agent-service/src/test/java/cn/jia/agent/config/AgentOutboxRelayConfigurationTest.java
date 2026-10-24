package cn.jia.agent.config;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import cn.jia.agent.service.impl.AgentOutboxRelayScheduler;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.io.File;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AgentOutboxRelayConfigurationTest {
    private static final String CREDENTIAL = "a".repeat(64);
    private static final String[] BROKER = {
            "agent.rabbit-broker.host=isolated.invalid",
            "agent.rabbit-broker.port=5673",
            "agent.rabbit-broker.username=user",
            "agent.rabbit-broker.password=secret",
            "agent.rabbit-broker.virtual-host=/isolated"
    };
    private static final String[] ENABLED = {
            "agent.command-outbox.enabled=true",
            "agent.rabbit-topology.enabled=true",
            "agent.rabbit-publish.enabled=true"
    };

    @Test
    void publishDisabledRegistersNoRelayOrAuthorityBeans() {
        new ApplicationContextRunner()
                .withBean(AgentOutboxRelayDao.class, () -> mock(AgentOutboxRelayDao.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(AgentRabbitSafetyConfiguration.class)
                .withPropertyValues("agent.command-outbox.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    for (String bean : new String[] {"agentOutboxWorkerAuthority",
                            "agentOutboxWorkerScope", "agentOutboxTenantModuleAdmissionGuard",
                            "agentRabbitTemplate", "agentConfirmedRabbitPublisher",
                            "agentOutboxPublisher", "agentOutboxRelayService",
                            "agentOutboxRelayScheduler"}) {
                        assertFalse(context.containsBean(bean), bean);
                    }
                });
    }

    @Test
    void validControlPlaneRegistrationCreatesOneImmutableAuthorityForScheduler() throws Exception {
        Fixture fixture = fixture(jdbc -> { });
        fixture.runner().withUserConfiguration(DefaultRabbitTemplateConfiguration.class)
                // These values must never select or override the database authority.
                .withPropertyValues("cyf.worker.tenant-id=999",
                        "cyf.worker.data-domain-id=evil", "cyf.worker.execution-pool=evil",
                        "cyf.deployment.artifact-path=/tmp/must-not-select-runtime.jar")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WorkerScope scope = context.getBean("agentOutboxWorkerScope", WorkerScope.class);
                    assertEquals("1", scope.tenantId().value());
                    assertEquals("cyf", scope.dataDomainId().value());
                    assertEquals("pool-a", scope.executionPoolId());
                    assertEquals("deployment-a", scope.deploymentId());
                    assertEquals(7, scope.workerGeneration());
                    assertEquals(11, scope.registrationRevision());
                    assertEquals(13, scope.admissionEpoch());
                    assertEquals("agent-command", scope.moduleId());
                    assertEquals(17, scope.migrationEpoch());

                    AgentOutboxWorkerAuthorityConfiguration.WorkerAuthority authority =
                            context.getBean("agentOutboxWorkerAuthority",
                                    AgentOutboxWorkerAuthorityConfiguration.WorkerAuthority.class);
                    assertSame(scope, authority.scope());
                    assertSame(authority.guard(), context.getBean(
                            "agentOutboxTenantModuleAdmissionGuard",
                            TenantModuleAdmissionGuard.class));
                    AgentOutboxRelayScheduler scheduler = context.getBean(
                            "agentOutboxRelayScheduler", AgentOutboxRelayScheduler.class);
                    assertSame(scope, schedulerScope(scheduler));

                    RabbitTemplate agent = context.getBean("agentRabbitTemplate", RabbitTemplate.class);
                    RabbitTemplate defaultTemplate = context.getBean(RabbitTemplate.class);
                    assertNotSame(defaultTemplate, agent);
                    assertSame(context.getBean("agentRabbitConnectionFactory", ConnectionFactory.class),
                            agent.getConnectionFactory());
                    assertTrue(agent.isMandatoryFor(new Message(new byte[] {1})));
                    assertFalse(agent.isChannelTransacted());
                    assertEquals(1, context.getBeansOfType(AgentConfirmedRabbitPublisher.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxPublisher.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxRelayService.class).size());
                    assertEquals(1, context.getBeansOfType(AgentOutboxRelayScheduler.class).size());
                });
    }

    @Test
    void missingRegistrationFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "DELETE FROM core_deployment_registration")));
    }

    @Test
    void staleAdmissionEpochFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "UPDATE core_deployment_module_admission SET admission_epoch=14")));
    }

    @Test
    void generationNotBoundToCredentialFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "UPDATE core_deployment_registration SET worker_generation=8")));
    }

    @Test
    void registrationRevisionMismatchFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "UPDATE core_deployment_module_admission SET registration_revision=12")));
    }

    @Test
    void nonCanonicalExecutionPoolFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "UPDATE core_deployment_tenant_grant SET execution_pool_id='Pool-A'")));
    }

    @Test
    void multipleEligibleDatabaseScopesFailClosedInsteadOfChoosingOne() throws Exception {
        assertStartupDenied(fixture(jdbc -> {
            jdbc.update("INSERT INTO core_tenant_control VALUES ('2','ENABLED',13)");
            jdbc.update("INSERT INTO core_tenant_module_control VALUES "
                    + "('2','agent-command','DOMAIN',5,17,TRUE,2,?)", "c".repeat(64));
            jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES "
                    + "('deployment-a','2','cyf','pool-b','ACTIVE',19,TRUE,TRUE)");
            jdbc.update("INSERT INTO core_deployment_module_admission VALUES "
                    + "('deployment-a','2','cyf','agent-command','DOMAIN',5,17,13,2,11,19,"
                    + "'ACTIVE',TRUE,TRUE)");
        }));
    }

    @Test
    void wrongRuntimeArtifactDigestFailsClosedAtStartup() throws Exception {
        assertStartupDenied(fixture(jdbc -> jdbc.update(
                "UPDATE core_deployment_registration SET artifact_digest=?", "f".repeat(64))));
    }

    @Test
    void productionRuntimeLocatorRequiresOneExactCodeSourceJarPath() throws Exception {
        Path directory = Files.createTempDirectory("agent-runtime-artifact-");
        Path jar = directory.resolve("agent-runtime.jar");
        Files.writeString(jar, "runtime", StandardCharsets.UTF_8);
        AgentOutboxWorkerAuthorityConfiguration.RuntimeArtifact fileArtifact =
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), jar.toUri().toURL());
        assertEquals(jar.toRealPath(), fileArtifact.path());
        assertEquals(sha256(Files.readAllBytes(jar)), fileArtifact.sha256());

        Path encodedDirectory = Files.createDirectories(directory.resolve("space ! encoded"));
        Path encodedJar = encodedDirectory.resolve("agent runtime.jar");
        Files.writeString(encodedJar, "nested-runtime", StandardCharsets.UTF_8);
        AgentOutboxWorkerAuthorityConfiguration.RuntimeArtifact nestedArtifact =
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        encodedJar.toString(), nestedCodeSource(encodedJar,
                                "jia-agent-service-1.1.2-SNAPSHOT.jar"));
        assertEquals(encodedJar.toRealPath(), nestedArtifact.path());
        assertEquals(sha256(Files.readAllBytes(encodedJar)), nestedArtifact.sha256());

        Path otherDirectory = Files.createTempDirectory("agent-runtime-shadow-");
        Path sameName = otherDirectory.resolve(jar.getFileName());
        Files.writeString(sameName, "shadow", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), sameName.toUri().toURL()));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), nestedCodeSource(sameName,
                                "jia-agent-service-1.1.2-SNAPSHOT.jar")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar + File.pathSeparator + sameName, nestedCodeSource(jar,
                                "jia-agent-service-1.1.2-SNAPSHOT.jar")));

        Path substring = directory.resolve("agent-runtime.jar.backup");
        Files.writeString(substring, "shadow", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), substring.toUri().toURL()));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        substring.toString(), substring.toUri().toURL()));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        directory.toString(), directory.toUri().toURL()));

        String rawJarPath = jar.toUri().getRawPath();
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:nested:" + rawJarPath
                                + "/BOOT-INF/lib/jia-agent-service.jar!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:nested:" + rawJarPath
                                + "/!BOOT-INF/classes/!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:nested:" + rawJarPath
                                + "/!BOOT-INF/lib/../jia-agent-service.jar!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:nested:" + rawJarPath
                                + "/!BOOT-INF/lib/jia!agent-service.jar!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:nested:/tmp/%ZZ.jar/!BOOT-INF/lib/"
                                + "jia-agent-service.jar!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("jar:" + jar.toUri()
                                + "!/BOOT-INF/lib/jia-agent-service.jar")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), rawUrl("nested:" + rawJarPath
                                + "/!BOOT-INF/lib/jia-agent-service.jar!/")));
        assertThrows(IllegalStateException.class, () ->
                AgentOutboxWorkerAuthorityConfiguration.locateRuntimeArtifact(
                        jar.toString(), new URL("file://example.invalid" + rawJarPath)));
    }

    @Test
    void missingDeploymentCredentialConfigurationFailsBeforeAuthorityCreation() throws Exception {
        Fixture fixture = fixture(jdbc -> { });
        fixture.runner().withPropertyValues("cyf.deployment.registration-credential=")
                .run(context -> assertDenied(context.getStartupFailure()));
    }

    private static void assertStartupDenied(Fixture fixture) {
        fixture.runner().run(context -> assertDenied(context.getStartupFailure()));
    }

    private static void assertDenied(Throwable startupFailure) {
        assertNotNull(startupFailure);
        Throwable current = startupFailure;
        boolean found = false;
        while (current != null) {
            if ("Agent outbox worker authority is unavailable".equals(current.getMessage())) {
                found = true;
                break;
            }
            current = current.getCause();
        }
        assertTrue(found, () -> "unexpected startup failure: " + startupFailure);
    }

    private static Fixture fixture(Consumer<JdbcTemplate> mutation) throws Exception {
        Path artifact = Files.createTempFile("agent-relay-authority-", ".jar").toAbsolutePath();
        Files.writeString(artifact, "relay-v2-artifact", StandardCharsets.UTF_8);
        String artifactDigest = sha256(Files.readAllBytes(artifact));

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:relay_authority_" + UUID.randomUUID()
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createAuthoritySchema(jdbc);
        seedAuthority(jdbc, artifactDigest);
        mutation.accept(jdbc);

        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withBean("agentOutboxRelayV2SchemaGate",
                        AgentOutboxRelayV2SchemaGate.class,
                        () -> mock(AgentOutboxRelayV2SchemaGate.class))
                .withBean("agentOutboxProductionRuntimeArtifact",
                        AgentOutboxWorkerAuthorityConfiguration.RuntimeArtifact.class,
                        () -> new AgentOutboxWorkerAuthorityConfiguration.RuntimeArtifact(
                                artifact, artifactDigest))
                .withBean(javax.sql.DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(AgentOutboxRelayDao.class, () -> mock(AgentOutboxRelayDao.class))
                .withUserConfiguration(AgentRabbitSafetyConfiguration.class)
                .withPropertyValues(concat(BROKER, ENABLED,
                        "cyf.deployment.id=deployment-a",
                        "cyf.deployment.registration-credential=" + CREDENTIAL));
        return new Fixture(runner);
    }

    private static void createAuthoritySchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50), state VARCHAR(40), "
                + "admission_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50), "
                + "module_id VARCHAR(64), mode_state VARCHAR(20), mode_revision BIGINT, "
                + "migration_epoch BIGINT, drain_sealed BOOLEAN, schema_version BIGINT, "
                + "mapping_digest VARCHAR(64))");
        jdbc.execute("CREATE TABLE core_deployment_registration (deployment_id VARCHAR(100), "
                + "stage VARCHAR(20), artifact_digest VARCHAR(64), credential_digest VARCHAR(64), "
                + "status VARCHAR(20), worker_generation BIGINT, registration_revision BIGINT)");
        jdbc.execute("CREATE TABLE core_deployment_tenant_grant (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), execution_pool_id VARCHAR(100), "
                + "status VARCHAR(20), grant_revision BIGINT, allow_read BOOLEAN, allow_write BOOLEAN)");
        jdbc.execute("CREATE TABLE core_deployment_module_admission (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "admission_epoch BIGINT, schema_version BIGINT, registration_revision BIGINT, "
                + "grant_revision BIGINT, status VARCHAR(20), allow_read BOOLEAN, allow_write BOOLEAN)");
    }

    private static void seedAuthority(JdbcTemplate jdbc, String artifactDigest) throws Exception {
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',13)");
        jdbc.update("INSERT INTO core_tenant_module_control VALUES "
                + "('1','agent-command','DOMAIN',5,17,TRUE,2,?)", "c".repeat(64));
        jdbc.update("INSERT INTO core_deployment_registration VALUES "
                + "('deployment-a','SHARED',?,?,'ACTIVE',7,11)", artifactDigest,
                credentialDigest("deployment-a", 7, CREDENTIAL));
        jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES "
                + "('deployment-a','1','cyf','pool-a','ACTIVE',19,TRUE,TRUE)");
        jdbc.update("INSERT INTO core_deployment_module_admission VALUES "
                + "('deployment-a','1','cyf','agent-command','DOMAIN',5,17,13,2,11,19,"
                + "'ACTIVE',TRUE,TRUE)");
    }

    private static WorkerScope schedulerScope(AgentOutboxRelayScheduler scheduler) {
        try {
            Field field = AgentOutboxRelayScheduler.class.getDeclaredField("workerScope");
            field.setAccessible(true);
            return (WorkerScope) field.get(scheduler);
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String credentialDigest(
            String deploymentId, long generation, String credential) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("cyf-deployment-v1\0".getBytes(StandardCharsets.US_ASCII));
        digest.update(deploymentId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(generation).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(credential.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static URL nestedCodeSource(Path outerJar, String nestedJar) throws Exception {
        String rawOuterPath = outerJar.toUri().getRawPath().replace("!", "%21");
        return rawUrl("jar:nested:" + rawOuterPath
                + "/!BOOT-INF/lib/" + nestedJar + "!/");
    }

    private static URL rawUrl(String value) throws Exception {
        return new URL(null, value, new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) {
                throw new UnsupportedOperationException("not used by locator tests");
            }
        });
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String[] concat(String[] first, String[] second, String... rest) {
        String[] result = java.util.Arrays.copyOf(first, first.length + second.length + rest.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(rest, 0, result, first.length + second.length, rest.length);
        return result;
    }

    private record Fixture(ApplicationContextRunner runner) {
    }

    @Configuration(proxyBeanMethods = false)
    static class DefaultRabbitTemplateConfiguration {
        @Bean
        RabbitTemplate defaultRabbitTemplate() {
            return new RabbitTemplate(mock(ConnectionFactory.class));
        }
    }
}
