package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRabbitOutboxPublisherTest {
    private static final String ARTIFACT = "a".repeat(64);
    private static final String CREDENTIAL = "c".repeat(64);
    private static final AgentRabbitTopologyManifest MANIFEST =
            AgentRabbitTopologyManifest.canonical();
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);
    private static final AgentRabbitV2TopologyManifest SCOPED_MANIFEST =
            AgentRabbitV2TopologyManifest.forScope(SCOPE);

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentConfirmedRabbitPublisher primitive;
    private AgentRabbitOutboxPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:publisher_" + UUID.randomUUID()
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        createAuthoritySchema();
        seedAuthority();
        TenantModuleAdmissionGuard guard = new TenantModuleAdmissionGuard(
                jdbc, "deployment-a", ARTIFACT, "pool-a", CREDENTIAL, 7, 11);
        primitive = mock(AgentConfirmedRabbitPublisher.class);
        publisher = new AgentRabbitOutboxPublisher(
                primitive, MANIFEST, guard, transactionManager, settings());
    }

    @Test
    void validCurrentAuthorityInvokesPrimitiveOnceAndKeepsRawBytes() {
        AgentOutboxClaimToken token = token();
        when(primitive.publish(any(), eq(5000L))).thenReturn(AgentRabbitPublishResult.ack());

        assertEquals(AgentRabbitPublishResult.Type.ACK,
                publisher.publish(SCOPE, token, 5000).type());

        ArgumentCaptor<AgentConfirmedPublishRequest> request =
                ArgumentCaptor.forClass(AgentConfirmedPublishRequest.class);
        verify(primitive).publish(request.capture(), eq(5000L));
        assertEquals(2, request.getValue().activeAttempt());
        assertEquals(0, request.getValue().sourceSettlementRetry());
        assertEquals("msg-1", request.getValue().messageId());
        assertEquals("cyf", request.getValue().dataDomainId());
        assertEquals("pool-a", request.getValue().executionPoolId());
        assertEquals(13, request.getValue().admissionEpoch());
        assertEquals(17, request.getValue().migrationEpoch());
        assertArrayEquals(token.wirePayload(), request.getValue().wirePayload());
    }

    @Test
    void legacyConstructorAndUnscopedMethodFailClosed() {
        AgentRabbitOutboxPublisher legacy = new AgentRabbitOutboxPublisher(primitive, MANIFEST);

        assertEquals("WORKER_AUTHORITY_REQUIRED",
                legacy.publish(token(), 5000).errorCode());
        assertEquals("WORKER_AUTHORITY_REQUIRED",
                legacy.publish(SCOPE, token(), 5000).errorCode());
        verifyNoInteractions(primitive);
    }

    @Test
    void revokedAuthorityAfterClaimCausesZeroRabbitSends() throws Exception {
        for (String update : new String[] {
                "UPDATE core_tenant_control SET state='WRITE_CLOSED_CLAIM_CLOSED'",
                "UPDATE core_deployment_registration SET status='DRAINING'",
                "UPDATE core_deployment_tenant_grant SET status='REVOKED'",
                "UPDATE core_deployment_module_admission SET status='REVOKED'",
                "UPDATE core_tenant_module_control SET migration_epoch=18"
        }) {
            setUp();
            jdbc.update(update);
            assertEquals(AgentRabbitPublishResult.Type.EXCEPTION,
                    publisher.publish(SCOPE, token(), 5000).type(), update);
            verifyNoInteractions(primitive);
        }
    }

    @Test
    void wrongScopeOrClaimantTokenCausesZeroRabbitSends() {
        WorkerScope wrongScope = new WorkerScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-b", "deployment-a",
                7, 11, 13, "agent-command", 17);
        AgentOutboxClaimToken wrongToken = copyWithClaimant(token(), "deployment-b", 7);

        assertEquals(AgentRabbitPublishResult.Type.EXCEPTION,
                publisher.publish(wrongScope, token(), 5000).type());
        assertEquals(AgentRabbitPublishResult.Type.EXCEPTION,
                publisher.publish(SCOPE, wrongToken, 5000).type());
        verifyNoInteractions(primitive);
    }

    @Test
    void controlCloseWaitsForAdmittedPublishAndSubsequentPublishFailsClosed() throws Exception {
        CountDownLatch enteredPrimitive = new CountDownLatch(1);
        CountDownLatch releasePrimitive = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        when(primitive.publish(any(), eq(5000L))).thenAnswer(invocation -> {
            sends.incrementAndGet();
            enteredPrimitive.countDown();
            if (!releasePrimitive.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting to release mocked Rabbit send");
            }
            return AgentRabbitPublishResult.ack();
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var publish = executor.submit(() -> publisher.publish(SCOPE, token(), 5000));
            assertTrue(enteredPrimitive.await(5, TimeUnit.SECONDS));

            CountDownLatch closeStarted = new CountDownLatch(1);
            var close = executor.submit(() -> {
                closeStarted.countDown();
                return new TransactionTemplate(transactionManager).execute(status ->
                        jdbc.update("UPDATE core_tenant_control "
                                + "SET state='WRITE_CLOSED_CLAIM_CLOSED' WHERE tenant_id='1'"));
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> close.get(200, TimeUnit.MILLISECONDS));

            releasePrimitive.countDown();
            assertEquals(AgentRabbitPublishResult.Type.ACK,
                    publish.get(5, TimeUnit.SECONDS).type());
            assertEquals(1, close.get(5, TimeUnit.SECONDS));

            assertEquals(AgentRabbitPublishResult.Type.EXCEPTION,
                    publisher.publish(SCOPE, token(), 5000).type());
            assertEquals(1, sends.get());
        } finally {
            releasePrimitive.countDown();
            executor.shutdownNow();
        }
    }

    private void createAuthoritySchema() {
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, "
                + "state VARCHAR(40), tenant_session_epoch BIGINT, admission_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50), "
                + "module_id VARCHAR(64), mode_state VARCHAR(20), mode_revision BIGINT, "
                + "migration_epoch BIGINT, drain_sealed BOOLEAN, schema_version BIGINT, "
                + "mapping_digest VARCHAR(64), PRIMARY KEY(tenant_id,module_id))");
        jdbc.execute("CREATE TABLE core_deployment_registration (deployment_id VARCHAR(100) PRIMARY KEY, "
                + "stage VARCHAR(20), artifact_digest VARCHAR(64), credential_digest VARCHAR(64), "
                + "status VARCHAR(20), worker_generation BIGINT, registration_revision BIGINT)");
        jdbc.execute("CREATE TABLE core_deployment_tenant_grant (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), execution_pool_id VARCHAR(100), "
                + "status VARCHAR(20), grant_revision BIGINT, allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id))");
        jdbc.execute("CREATE TABLE core_deployment_module_admission (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "admission_epoch BIGINT, schema_version BIGINT, registration_revision BIGINT, "
                + "grant_revision BIGINT, status VARCHAR(20), allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id,module_id))");
    }

    private void seedAuthority() throws Exception {
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',2,13)");
        jdbc.update("INSERT INTO core_tenant_module_control VALUES "
                + "('1','agent-command','DOMAIN',5,17,TRUE,2,?)", "b".repeat(64));
        jdbc.update("INSERT INTO core_deployment_registration VALUES "
                + "('deployment-a','SHARED',?,?,'ACTIVE',7,11)", ARTIFACT,
                credentialDigest("deployment-a", 7, CREDENTIAL));
        jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES "
                + "('deployment-a','1','cyf','pool-a','ACTIVE',19,TRUE,TRUE)");
        jdbc.update("INSERT INTO core_deployment_module_admission VALUES "
                + "('deployment-a','1','cyf','agent-command','DOMAIN',5,17,13,2,11,19,"
                + "'ACTIVE',TRUE,TRUE)");
    }

    private static AgentOutboxRelaySettings settings() {
        return new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(
                true, 2, 4, 60_000L, 15_000L, 5_000L, 2, 20,
                1_000L, 2.0D, 300_000L, 20, 1_000L, 131_072));
    }

    private static AgentOutboxClaimToken token() {
        byte[] wire = ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"1\",\"dataDomainId\":\"cyf\"," +
                "\"executionPoolId\":\"pool-a\",\"producerDeploymentId\":\"producer-a\"," +
                "\"admissionEpoch\":13,\"moduleId\":\"agent-command\",\"migrationEpoch\":17," +
                "\"clientId\":\"client-a\",\"taskId\":\"task-1\"," +
                "\"targetAgentId\":\"agent-1\",\"commandType\":\"task.invite\"," +
                "\"attempt\":2,\"expiresAt\":9999999}").getBytes(StandardCharsets.UTF_8);
        return new AgentOutboxClaimToken(
                1, 41, "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17, 2,
                "deployment-a", 7, "client-a", "evt-1", "msg-1", "cmd-1",
                "task-1", "agent-1", "task.invite",
                SCOPED_MANIFEST.defaultCommandPublishRoute().destination(),
                SCOPED_MANIFEST.defaultCommandPublishRoute().routingKey(), wire,
                AgentCommandAmqpContract.sha256(wire), 9_999_999L, "lease", 1234,
                9, 5, "RETRY", "msg-1", 2, 7);
    }

    private static AgentOutboxClaimToken copyWithClaimant(
            AgentOutboxClaimToken token, String deployment, long generation) {
        return new AgentOutboxClaimToken(
                token.outboxId(), token.deliveryId(), token.tenantId(), token.dataDomainId(),
                token.executionPoolId(), token.admissionEpoch(), token.producerDeploymentId(),
                token.moduleId(), token.migrationEpoch(), token.protocolVersion(), deployment,
                generation, token.clientId(), token.eventId(), token.messageId(), token.commandId(),
                token.taskId(), token.targetAgentId(), token.commandType(), token.destination(),
                token.routingKey(), token.wirePayload(), token.wirePayloadHash(), token.expiresAt(),
                token.leaseOwner(), token.leaseUntil(), token.publishAttempt(), token.outboxVersion(),
                token.deliveryStatus(), token.deliveryActiveMessageId(), token.deliveryActiveAttempt(),
                token.deliveryVersion());
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
}
