package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentCommandReissueSettings;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.dao.AgentHallCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandProducerV2Test {
    private static final String ARTIFACT = "a".repeat(64);
    private static final String CREDENTIAL = "c".repeat(64);
    private static final String CREDENTIAL_DIGEST =
            "3e5161d30ae1008e3588272520fd5e5b6c8b68aae2bc6a42e2dc7c407582afe4";
    private static final long NOW = 200_000L;
    private static final long EXPIRES = 3_601_000L;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private TenantModuleAdmissionGuard guard;
    private JdbcTransportDao transport;
    private UserAdmissionScope userScope;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:agent_producer_v2_" + UUID.randomUUID()
                        + ";MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        createControlSchema();
        createTransportSchema();
        seedAdmission();
        guard = new TenantModuleAdmissionGuard(
                jdbc, "local-1", ARTIFACT, "test-pool", CREDENTIAL, 4, 11);
        transport = new JdbcTransportDao(jdbc);
        userScope = new UserAdmissionScope(new BusinessScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "owner", "web"),
                9, 4, 2, 5, 8);
    }

    @Test
    void tenantOneWriterPersistsProtocolAndGoldenWire() {
        AgentCommandDraft draft = draft("1", "web", "owner");
        AgentCommandTransportWriterImpl writer = writer(guard);

        var result = writer.writeV2(userScope, draft);

        assertFalse(result.duplicate());
        AgentCommandDeliveryEntity delivery = transport.lockDeliveryV2(
                "1", "web", "owner", draft.commandId());
        AgentOutboxEventEntity outbox = transport.lockActiveOutboxes(
                "1", "web", delivery.getId(), delivery.getActiveMessageId()).getFirst();
        assertEquals(AgentCommandAmqpContract.WIRE_VERSION, delivery.getProtocolVersion());
        assertEquals("cyf", delivery.getDataDomainId());
        assertEquals("test-pool", delivery.getExecutionPoolId());
        assertEquals("local-1", delivery.getProducerDeploymentId());
        assertEquals(3L, delivery.getAdmissionEpoch());
        assertEquals("agent-command", delivery.getModuleId());
        assertEquals(4L, delivery.getMigrationEpoch());
        assertEquals(AgentCommandProtocolSupport.producerScope(delivery),
                AgentCommandProtocolSupport.producerScope(delivery, outbox));
        assertEquals("PENDING", delivery.getStatus());
        assertEquals("PENDING", outbox.getStatus());
        String wire = new String(outbox.getWirePayload(), StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"schemaVersion\":2"));
        assertTrue(wire.contains("\"tenantId\":\"1\""));
        assertTrue(wire.contains("\"dataDomainId\":\"cyf\""));
        assertEquals("636585fea962711f5041bd59fd69283e610d03954ee80169f7a14ac82819f8ab", HexFormat.of().formatHex(
                AgentCommandCanonicalCodec.sha256(outbox.getWirePayload())));
        assertTrue(MessageDigest.isEqual(outbox.getWirePayloadHash(),
                AgentCommandCanonicalCodec.sha256(outbox.getWirePayload())));
    }

    @Test
    void signedScopeMismatchAndAdmissionDriftFailBeforeTransportWrite() {
        UserAdmissionScope wrongClient = new UserAdmissionScope(new BusinessScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "owner", "other"),
                9, 4, 2, 5, 8);
        assertThrows(IllegalArgumentException.class,
                () -> writer(guard).writeV2(wrongClient, draft("1", "web", "owner")));
        assertEquals(0, count("agent_command_delivery"));

        jdbc.update("UPDATE core_tenant_control SET admission_epoch=4 WHERE tenant_id='1'");
        assertThrows(IllegalStateException.class,
                () -> writer(guard).writeV2(userScope, draft("1", "web", "owner")));
        assertEquals(0, count("agent_command_delivery"));

        jdbc.update("UPDATE core_tenant_control SET admission_epoch=3 WHERE tenant_id='1'");
        jdbc.update("UPDATE core_tenant_module_control SET migration_epoch=5 "
                + "WHERE tenant_id='1' AND module_id='agent-command'");
        assertThrows(IllegalStateException.class,
                () -> writer(guard).writeV2(userScope, draft("1", "web", "owner")));
        assertEquals(0, count("agent_command_delivery"));
    }

    @Test
    void duplicateIsIdempotentButChangedProducerPoolConflicts() {
        AgentCommandDraft draft = draft("1", "web", "owner");
        AgentCommandTransportWriterImpl first = writer(guard);
        first.writeV2(userScope, draft);
        assertTrue(first.writeV2(userScope, draft).duplicate());
        assertEquals(1, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));

        jdbc.update("UPDATE core_deployment_tenant_grant SET execution_pool_id='other-pool' "
                + "WHERE deployment_id='local-1' AND tenant_id='1' AND data_domain_id='cyf'");
        TenantModuleAdmissionGuard changed = new TenantModuleAdmissionGuard(
                jdbc, "local-1", ARTIFACT, "other-pool", CREDENTIAL, 4, 11);
        assertThrows(IllegalStateException.class,
                () -> writer(changed).writeV2(userScope, draft));
        assertEquals(1, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));
    }

    @Test
    void legacyRemainsTenantZeroAndOutboxFailureRollsBackV2() {
        AgentCommandTransportWriterImpl legacy = new AgentCommandTransportWriterImpl(
                transport, gate(), null, null, transactions);
        var legacyResult = legacy.write(draft("0", "web", "owner"));
        AgentCommandDeliveryEntity legacyRow = transport.lockDelivery(
                "0", "web", "owner", legacyResult.commandId());
        assertNull(legacyRow.getProtocolVersion());
        assertNull(legacyRow.getDataDomainId());
        assertThrows(IllegalArgumentException.class,
                () -> legacy.write(draft("1", "web", "owner")));

        jdbc.update("DELETE FROM agent_outbox_event");
        jdbc.update("DELETE FROM agent_command_delivery");
        transport.failOutbox = true;
        assertThrows(IllegalStateException.class,
                () -> writer(guard).writeV2(userScope, draft("1", "web", "owner")));
        assertEquals(0, count("agent_command_delivery"));
        assertEquals(0, count("agent_outbox_event"));
    }

    @Test
    void protocolSupportRejectsMissingAndMixedProvenance() {
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setProtocolVersion(2).setCommandId("command");
        delivery.setTenantId("1");
        delivery.setClientId("web");
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandProtocolSupport.producerScope(delivery));

        ProducerScope scope = producerScope();
        AgentCommandProtocolSupport.apply(delivery, scope);
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity().setProtocolVersion(1);
        outbox.setTenantId("1");
        outbox.setClientId("web");
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandProtocolSupport.producerScope(delivery, outbox));

        AgentCommandProtocolSupport.apply(outbox, scope);
        outbox.setExecutionPoolId("other-pool");
        assertThrows(IllegalArgumentException.class,
                () -> AgentCommandProtocolSupport.producerScope(delivery, outbox));
    }

    @Test
    void automaticReissuePreservesV2ProvenanceAndRejectsEpochDrift() {
        RecoveryFixture fixture = recoveryFixture();
        AgentCommandReissueServiceImpl service = reissueService(fixture.dao, guard);

        AgentCommandReissueScanResult result = service.reissueDue(10, 0, NOW);

        assertEquals(1, result.reissued());
        AgentOutboxEventEntity inserted = fixture.dao.inserted;
        assertEquals(producerScope(), AgentCommandProtocolSupport.producerScope(
                fixture.delivery, inserted));
        assertEquals(2, inserted.getActiveAttempt());
        assertEquals(fixture.delivery.getActiveMessageId(), inserted.getReplayParentMessageId());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                fixture.dao.requestedConsumer);
        assertTrue(new String(inserted.getWirePayload(), StandardCharsets.UTF_8)
                .contains("\"attempt\":2"));

        RecoveryFixture revoked = recoveryFixture();
        jdbc.update("UPDATE core_tenant_control SET admission_epoch=4 WHERE tenant_id='1'");
        AgentCommandReissueScanResult denied = reissueService(revoked.dao, guard)
                .reissueDue(10, 0, NOW);
        assertEquals(0, denied.reissued());
        assertNull(revoked.dao.inserted);
    }

    @Test
    void consumerRetryIsRecoveredOnlyByTheScopedV2Lane() {
        RecoveryFixture scoped = recoveryFixture();
        markExecutionRetry(scoped, AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE);

        AgentCommandReissueScanResult recovered = reissueService(scoped.dao, guard)
                .reissueDue(10, 0, NOW);

        assertEquals(1, recovered.reissued());
        assertEquals(2, scoped.dao.inserted.getActiveAttempt());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                scoped.dao.requestedConsumer);

        RecoveryFixture forged = recoveryFixture();
        markExecutionRetry(forged, "UNTRUSTED_RETRY");
        AgentCommandReissueScanResult rejected = reissueService(forged.dao, guard)
                .reissueDue(10, 0, NOW);
        assertEquals(0, rejected.reissued());
        assertNull(forged.dao.inserted);
        assertEquals(0, forged.dao.reissueCalls);

        RecoveryFixture legacy = legacyRecoveryFixture();
        markExecutionRetry(legacy, AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE);
        AgentCommandReissueServiceImpl legacyService = new AgentCommandReissueServiceImpl(
                legacy.dao, gate(), new ConnectedDispatcher(),
                AgentRabbitTopologyManifest.canonical(), 30_000L, transactions,
                () -> new UUID(0, 30), new AgentCommandRecoveryPolicy());
        AgentCommandReissueScanResult legacyRejected = legacyService.reissueDue(10, 0, NOW);
        assertEquals(0, legacyRejected.reissued());
        assertNull(legacy.dao.inserted);
        assertEquals(0, legacy.dao.reissueCalls);
    }

    @Test
    void expiredConsumerRetryExpiresDeliveryAndRetryInboxAtomically() {
        RecoveryFixture expired = recoveryFixture();
        markExecutionRetry(expired, AgentCommandRabbitV2Consumer.EXECUTION_UNCERTAIN);

        AgentCommandReissueScanResult result = reissueService(expired.dao, guard)
                .reissueDue(10, 0, EXPIRES);

        assertEquals(0, result.reissued());
        assertEquals(1, expired.dao.expiredDeliveryCalls);
        assertEquals(1, expired.dao.expiredRetryCalls);
        assertEquals(0, expired.dao.expiredWaitingCalls);
        assertNull(expired.dao.inserted);
    }

    @Test
    void reconnectAndManualRecoveryUseThePersistedV2InboxIdentity() {
        RecoveryFixture reconnect = recoveryFixture();
        AgentCommandReissueScanResult reconnectResult = reissueService(reconnect.dao, guard)
                .reissueForReconnect(new cn.jia.agent.entity.AgentCommandReconnectScope(
                        "1", "web", "agent-1", "agent-1",
                        AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT), 10, NOW);
        assertEquals(1, reconnectResult.reissued());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                reconnect.dao.requestedConsumer);
        assertEquals(Integer.valueOf(2), reconnect.dao.inserted.getProtocolVersion());

        RecoveryFixture manual = recoveryFixture();
        manual.delivery.setStatus("DEAD").setNextRetryAt(null)
                .setLastError("BROKER_DEAD").setUpdateTime(NOW - 40_000L);
        manual.dao.inbox.setStatus("DEAD").setResultStatus("DEAD")
                .setNextRetryAt(null).setLastError("BROKER_DEAD");
        AgentCommandManualReissueResult manualResult = reissueService(manual.dao, guard)
                .reissueManually(new AgentCommandOperationRequest(
                        "1", "web", manual.delivery.getId(), manual.delivery.getTaskId(),
                        manual.delivery.getTargetAgentId(), manual.delivery.getActiveMessageId(),
                        "operator-a", "approver-b", "incident recovery", "INC-42"), NOW);
        assertEquals(2, manualResult.newAttempt());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                manual.dao.requestedConsumer);
        assertEquals(producerScope(), AgentCommandProtocolSupport.producerScope(
                manual.delivery, manual.dao.inserted));
    }

    @Test
    void scopedRecoveryRejectsLegacyInboxIdentityBeforeAnyWrite() {
        RecoveryFixture mixed = recoveryFixture();
        mixed.dao.inbox.setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1);

        AgentCommandReissueScanResult rejected =
                reissueService(mixed.dao, guard).reissueDue(10, 0, NOW);

        assertEquals(1, rejected.examined());
        assertEquals(0, rejected.reissued());
        assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                mixed.dao.requestedConsumer);
        assertNull(mixed.dao.inserted);
        assertEquals(0, mixed.dao.reissueCalls);
    }


    @Test
    void laneAwareCoordinatorReissuesLegacyAndScopedWithoutRouteCrossing() {
        RecoveryFixture legacy = legacyRecoveryFixture();
        RecoveryFixture scoped = recoveryFixture();
        AtomicInteger legacyIds = new AtomicInteger(30);
        AgentCommandReissueServiceImpl legacyService = new AgentCommandReissueServiceImpl(
                legacy.dao, gate(), new ConnectedDispatcher(),
                AgentRabbitTopologyManifest.canonical(), 30_000L, transactions,
                () -> new UUID(0, legacyIds.incrementAndGet()),
                new AgentCommandRecoveryPolicy());
        AgentCommandReissueServiceImpl scopedService = reissueService(scoped.dao, guard);
        AgentCommandReissueSettings settings = new AgentCommandReissueSettings(
                4, 1, 10, 100);

        long legacyCursor;
        long scopedCursor;
        try (AgentCommandReissueCoordinator coordinator = new AgentCommandReissueCoordinator(
                legacyService, scopedService, settings, () -> NOW, false)) {
            coordinator.runOnce();
            legacyCursor = coordinator.schedulerCursor();
            scopedCursor = coordinator.scopedSchedulerCursor();
        }

        assertEquals(AgentRabbitTopologyManifest.MAIN_EXCHANGE,
                legacy.dao.inserted.getDestination());
        assertEquals(AgentRabbitTopologyManifest.GENERAL_ROUTING_KEY,
                legacy.dao.inserted.getRoutingKey());
        assertNull(legacy.dao.inserted.getProtocolVersion());
        AgentRabbitV2TopologyManifest scopedManifest =
                AgentRabbitV2TopologyManifest.forScope(producerScope());
        assertEquals(scopedManifest.mainExchange(), scoped.dao.inserted.getDestination());
        assertEquals(scopedManifest.generalRoutingKey(), scoped.dao.inserted.getRoutingKey());
        assertEquals(Integer.valueOf(2), scoped.dao.inserted.getProtocolVersion());
        assertEquals(81L, legacyCursor);
        assertEquals(91L, scopedCursor);
    }

    private AgentCommandTransportWriterImpl writer(TenantModuleAdmissionGuard selectedGuard) {
        AtomicInteger ids = new AtomicInteger();
        return new AgentCommandTransportWriterImpl(
                transport, gate(), null, null, selectedGuard, transactions,
                () -> new UUID(0, ids.incrementAndGet()));
    }

    private AgentCommandReissueServiceImpl reissueService(
            RecordingRecoveryDao dao, TenantModuleAdmissionGuard selectedGuard) {
        AtomicInteger ids = new AtomicInteger(10);
        return new AgentCommandReissueServiceImpl(
                dao, gate(), new ConnectedDispatcher(), AgentRabbitTopologyManifest.canonical(),
                30_000L, transactions, () -> new UUID(0, ids.incrementAndGet()),
                new AgentCommandRecoveryPolicy(), selectedGuard, workerScope());
    }

    private RecoveryFixture recoveryFixture() {
        AgentCommandDraft draft = draft("1", "web", "owner");
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        String messageId = "00000000-0000-0000-0000-000000000001";
        ProducerScope scope = producerScope();
        byte[] wire = AgentCommandCanonicalCodec.wireBytesV2(draft, messageId, 91L, 1, scope);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(91L).setCommandId(draft.commandId()).setOwnerJiacn("owner")
                .setTaskId(draft.taskId()).setTargetAgentId(draft.targetAgentId())
                .setCommandType(draft.commandType()).setCommandPayload(business)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setStatus("WAITING_AGENT").setAttemptCount(1).setNextRetryAt(NOW - 1)
                .setActiveMessageId(messageId).setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setLastError(AgentCommandReissueServiceImpl.AGENT_OFFLINE).setVersion(7L);
        delivery.setTenantId("1"); delivery.setClientId("web"); delivery.setUpdateTime(NOW - 10);
        AgentCommandProtocolSupport.apply(delivery, scope);
        var route = AgentRabbitV2TopologyManifest.forScope(scope).defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(92L).setEventId("event-1").setMessageId(messageId)
                .setCommandId(draft.commandId()).setDeliveryId(91L).setAggregateType("task")
                .setAggregateId(draft.taskId()).setDestination(route.destination())
                .setRoutingKey(route.routingKey()).setWirePayload(wire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK")
                .setConfirmedAt(NOW - 20).setMandatoryReturnStatus("NOT_RETURNED")
                .setPublishedAt(NOW - 19).setVersion(2L);
        outbox.setTenantId("1"); outbox.setClientId("web");
        AgentCommandProtocolSupport.apply(outbox, scope);
        AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                .setId(93L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2)
                .setMessageId(messageId).setEventId("event-1").setCommandId(draft.commandId())
                .setDeliveryId(91L).setWirePayload(wire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("WAITING_AGENT").setResultStatus("WAITING_AGENT")
                .setAttemptCount(1).setNextRetryAt(NOW - 1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setProcessedAt(NOW - 100)
                .setLastError(AgentCommandReissueServiceImpl.AGENT_OFFLINE).setVersion(1L);
        inbox.setTenantId("1"); inbox.setClientId("web");
        return new RecoveryFixture(delivery, new RecordingRecoveryDao(delivery, outbox, inbox));
    }

    private void markExecutionRetry(RecoveryFixture fixture, String error) {
        fixture.delivery.setStatus("RETRY").setNextRetryAt(NOW - 1)
                .setLastError(error).setUpdateTime(NOW - 10);
        fixture.dao.inbox.setStatus("RETRY").setResultStatus("RETRY")
                .setNextRetryAt(NOW - 1).setLastError(error);
    }

    private RecoveryFixture legacyRecoveryFixture() {
        AgentCommandDraft draft = draft("0", "web", "owner");
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        String messageId = "00000000-0000-0000-0000-000000000002";
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, messageId, 1);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(81L).setCommandId(draft.commandId()).setOwnerJiacn("owner")
                .setTaskId(draft.taskId()).setTargetAgentId(draft.targetAgentId())
                .setCommandType(draft.commandType()).setCommandPayload(business)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setStatus("WAITING_AGENT").setAttemptCount(1).setNextRetryAt(NOW - 1)
                .setActiveMessageId(messageId).setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setLastError(AgentCommandReissueServiceImpl.AGENT_OFFLINE).setVersion(7L);
        delivery.setTenantId("0"); delivery.setClientId("web"); delivery.setUpdateTime(NOW - 10);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(82L).setEventId("legacy-event-1").setMessageId(messageId)
                .setCommandId(draft.commandId()).setDeliveryId(81L).setAggregateType("task")
                .setAggregateId(draft.taskId()).setDestination(route.destination())
                .setRoutingKey(route.routingKey()).setWirePayload(wire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK")
                .setConfirmedAt(NOW - 20).setMandatoryReturnStatus("NOT_RETURNED")
                .setPublishedAt(NOW - 19).setVersion(2L);
        outbox.setTenantId("0"); outbox.setClientId("web");
        AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                .setId(83L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1)
                .setMessageId(messageId).setEventId("legacy-event-1")
                .setCommandId(draft.commandId()).setDeliveryId(81L).setWirePayload(wire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("WAITING_AGENT").setResultStatus("WAITING_AGENT")
                .setAttemptCount(1).setNextRetryAt(NOW - 1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setProcessedAt(NOW - 100)
                .setLastError(AgentCommandReissueServiceImpl.AGENT_OFFLINE).setVersion(1L);
        inbox.setTenantId("0"); inbox.setClientId("web");
        return new RecoveryFixture(delivery, new RecordingRecoveryDao(delivery, outbox, inbox));
    }

    private ProducerScope producerScope() {
        return new ProducerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "test-pool", "local-1", 3, "agent-command", 4);
    }

    private WorkerScope workerScope() {
        return new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "test-pool", "local-1", 4, 11, 3, "agent-command", 4);
    }

    private AgentCommandDraft draft(String tenant, String client, String owner) {
        String commandId = AgentCommandCanonicalCodec.taskInviteCommandId(
                tenant, client, owner, "task-1", "agent-1");
        return new AgentCommandDraft(1, commandId, "task-1", "cause-1", tenant, client,
                owner, "task-1", null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE,
                1_000L, EXPIRES, new AgentTaskInvitePayload(
                "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                "测试任务", List.of("analysis"), "agent-1", List.of("agent-1"), "coordinator",
                "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                "juyiting"));
    }

    private AgentRabbitSafetyGate gate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "pass", "/producer-v2"));
        return new AgentRabbitSafetyGate(properties, new AgentRabbitDispatchScopeProperties(
                List.of(new AgentRabbitDispatchScopeProperties.AllowedScope("1", "web"),
                        new AgentRabbitDispatchScopeProperties.AllowedScope("0", "web"))));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void createControlSchema() {
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, "
                + "state VARCHAR(32), tenant_session_epoch BIGINT, admission_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "drain_sealed BOOLEAN, schema_version BIGINT, mapping_digest CHAR(64), "
                + "PRIMARY KEY(tenant_id,module_id))");
        jdbc.execute("CREATE TABLE user_info (id BIGINT PRIMARY KEY, jiacn VARCHAR(128), "
                + "account_state VARCHAR(16), auth_epoch BIGINT)");
        jdbc.execute("CREATE TABLE oauth_client (id VARCHAR(100) PRIMARY KEY, client_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_grant (client_id VARCHAR(100), tenant_id VARCHAR(50), "
                + "status VARCHAR(8), grant_revision BIGINT, PRIMARY KEY(client_id,tenant_id))");
        jdbc.execute("CREATE TABLE oauth_client_tenant_domain_grant (client_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), status VARCHAR(8), "
                + "domain_revision BIGINT, PRIMARY KEY(client_id,tenant_id,data_domain_id))");
        jdbc.execute("CREATE TABLE core_tenant_membership (tenant_id VARCHAR(50), uid BIGINT, "
                + "status VARCHAR(8), membership_revision BIGINT, PRIMARY KEY(tenant_id,uid))");
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
    }

    private void seedAdmission() {
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',2,3)");
        jdbc.update("INSERT INTO core_tenant_module_control VALUES "
                + "('1','agent-command','DOMAIN',7,4,TRUE,2,?)", ARTIFACT);
        jdbc.update("INSERT INTO user_info VALUES (9,'owner','ACTIVE',4)");
        jdbc.update("INSERT INTO oauth_client VALUES ('registered-web','web')");
        jdbc.update("INSERT INTO oauth_client_tenant_grant VALUES ('web','1','ACTIVE',8)");
        jdbc.update("INSERT INTO oauth_client_tenant_domain_grant VALUES ('web','1','cyf','ACTIVE',3)");
        jdbc.update("INSERT INTO core_tenant_membership VALUES ('1',9,'ACTIVE',5)");
        jdbc.update("INSERT INTO core_deployment_registration VALUES "
                + "('local-1','LOCAL_TEST',?,?,'ACTIVE',11,4)", ARTIFACT, CREDENTIAL_DIGEST);
        jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES "
                + "('local-1','1','cyf','test-pool','ACTIVE',8,TRUE,TRUE)");
        jdbc.update("INSERT INTO core_deployment_module_admission VALUES "
                + "('local-1','1','cyf','agent-command','DOMAIN',7,4,3,2,11,8,'ACTIVE',TRUE,TRUE)");
    }

    private void createTransportSchema() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, protocol_version INT, data_domain_id VARCHAR(64),
                  execution_pool_id VARCHAR(100), admission_epoch BIGINT, producer_deployment_id VARCHAR(100),
                  module_id VARCHAR(64), migration_epoch BIGINT, command_id VARCHAR(100) NOT NULL,
                  owner_jiacn VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100),
                  target_agent_id VARCHAR(100) NOT NULL, command_type VARCHAR(64) NOT NULL,
                  command_payload BLOB NOT NULL, command_payload_hash BINARY(32) NOT NULL,
                  status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL, next_retry_at BIGINT,
                  lease_owner VARCHAR(100), lease_until BIGINT, active_message_id VARCHAR(100),
                  active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, last_error VARCHAR(2000),
                  version BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT, UNIQUE(tenant_id,client_id,owner_jiacn,command_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, protocol_version INT, data_domain_id VARCHAR(64),
                  execution_pool_id VARCHAR(100), admission_epoch BIGINT, producer_deployment_id VARCHAR(100),
                  module_id VARCHAR(64), migration_epoch BIGINT, event_id VARCHAR(100) NOT NULL,
                  message_id VARCHAR(100) NOT NULL, command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL,
                  aggregate_type VARCHAR(30), aggregate_id VARCHAR(100), destination VARCHAR(100), routing_key VARCHAR(100),
                  wire_payload BLOB, wire_payload_hash BINARY(32), status VARCHAR(32), attempt_count INT,
                  next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT, active_attempt INT,
                  expires_at BIGINT, publisher_confirm_status VARCHAR(20), mandatory_return_status VARCHAR(20),
                  last_error VARCHAR(2000), version BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50),
                  create_time BIGINT, update_time BIGINT, UNIQUE(tenant_id,client_id,event_id))
                """);
    }

    private record RecoveryFixture(
            AgentCommandDeliveryEntity delivery, RecordingRecoveryDao dao) {
    }

    private static final class ConnectedDispatcher implements AgentRawCommandDispatcher {
        @Override
        public boolean isExactAgentConnected(String tenantId, String clientId, String targetAgentId) {
            return true;
        }
        @Override
        public AgentRawCommandDispatchResult dispatchExactRawCommand(
                String tenantId, String clientId, String taskId,
                String targetAgentId, byte[] rawWireBytes) {
            throw new AssertionError("reissue must not dispatch in transaction");
        }
    }

    private static final class RecordingRecoveryDao implements AgentCommandRecoveryDao {
        private final AgentCommandDeliveryEntity delivery;
        private final AgentOutboxEventEntity outbox;
        private final AgentConsumerInboxEntity inbox;
        private AgentOutboxEventEntity inserted;
        private String requestedConsumer;
        private int reissueCalls;
        private int expiredDeliveryCalls;
        private int expiredWaitingCalls;
        private int expiredRetryCalls;

        private RecordingRecoveryDao(AgentCommandDeliveryEntity delivery,
                AgentOutboxEventEntity outbox, AgentConsumerInboxEntity inbox) {
            this.delivery = delivery; this.outbox = outbox; this.inbox = inbox;
        }
        @Override public List<AgentWaitingCommandCandidate> findReconnectCandidates(
                String tenantId, String clientId, String targetAgentId,
                long now, long afterDeliveryId, int limit) { return List.of(); }
        @Override public List<AgentWaitingCommandCandidate> findDueCandidates(
                long now, long sentBefore, long afterDeliveryId, int limit) {
            return List.of(new AgentWaitingCommandCandidate(
                    delivery.getId(), delivery.getTenantId(), delivery.getClientId(),
                    delivery.getTargetAgentId(), false));
        }
        @Override public List<AgentWaitingCommandCandidate> findV2DueCandidates(
                WorkerScope scope, long now, long sentBefore, long afterDeliveryId, int limit) {
            return findDueCandidates(now, sentBefore, afterDeliveryId, limit);
        }
        @Override public List<AgentWaitingCommandCandidate> findV2ReconnectCandidates(
                WorkerScope scope, String clientId, String targetAgentId,
                long now, long afterDeliveryId, int limit) {
            return findDueCandidates(now, 0, afterDeliveryId, limit);
        }
        @Override public AgentCommandDeliveryEntity findDeliveryProtocolHint(
                String tenantId, String clientId, long deliveryId) { return delivery; }
        @Override public AgentCommandDeliveryEntity lockDelivery(
                String tenantId, String clientId, long deliveryId) { return delivery; }
        @Override public AgentCommandDeliveryEntity lockDeliveryByCommand(
                String tenantId, String clientId, String commandId) { return delivery; }
        @Override public List<AgentOutboxEventEntity> lockActiveOutboxes(
                String tenantId, String clientId, long deliveryId, String messageId) {
            return List.of(outbox);
        }
        @Override public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
                String tenantId, String clientId, long deliveryId, int previousAttempt) {
            return List.of();
        }
        @Override public AgentConsumerInboxEntity lockInbox(
                String tenantId, String clientId, String consumerName, String messageId) {
            requestedConsumer = consumerName;
            return consumerName.equals(inbox.getConsumerName()) ? inbox : null;
        }
        @Override public int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
                String requestedBy, String reason, String lastError, long now) {
            reissueCalls++;
            return 1;
        }
        @Override public int manualReissueDelivery(
                AgentCommandDeliveryEntity delivery, String newMessageId,
                String requestedBy, String approverId, String reason, String lastError, long now) {
            reissueCalls++;
            return 1;
        }
        @Override public int expireDelivery(AgentCommandDeliveryEntity delivery, String lastError, long now) {
            expiredDeliveryCalls++; return 1;
        }
        @Override public int expireWaitingInbox(AgentConsumerInboxEntity inbox, String lastError, long now) {
            expiredWaitingCalls++; return 1;
        }
        @Override public int expireRetryInbox(AgentConsumerInboxEntity inbox, String lastError, long now) {
            expiredRetryCalls++; return 1;
        }
        @Override public int insertOutbox(AgentOutboxEventEntity outbox) {
            inserted = outbox; outbox.setId(99L); return 1;
        }
        @Override public int advanceAck(
                AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now) { return 1; }
    }

    private static final class JdbcTransportDao implements AgentHallCommandTransportDao {
        private final JdbcTemplate jdbc;
        private boolean failOutbox;
        private JdbcTransportDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override public AgentCommandDeliveryEntity lockDelivery(
                String tenantId, String clientId, String ownerJiacn, String commandId) {
            List<AgentCommandDeliveryEntity> rows = jdbc.query("""
                    SELECT * FROM agent_command_delivery WHERE tenant_id=? AND client_id=?
                      AND owner_jiacn=? AND command_id=? FOR UPDATE
                    """, (rs, ignored) -> {
                AgentCommandDeliveryEntity d = new AgentCommandDeliveryEntity()
                        .setId(rs.getLong("id")).setProtocolVersion((Integer) rs.getObject("protocol_version"))
                        .setDataDomainId(rs.getString("data_domain_id"))
                        .setExecutionPoolId(rs.getString("execution_pool_id"))
                        .setAdmissionEpoch((Long) rs.getObject("admission_epoch"))
                        .setProducerDeploymentId(rs.getString("producer_deployment_id"))
                        .setModuleId(rs.getString("module_id"))
                        .setMigrationEpoch((Long) rs.getObject("migration_epoch"))
                        .setCommandId(rs.getString("command_id")).setOwnerJiacn(rs.getString("owner_jiacn"))
                        .setTaskId(rs.getString("task_id")).setWorkItemId(rs.getString("work_item_id"))
                        .setTargetAgentId(rs.getString("target_agent_id"))
                        .setCommandType(rs.getString("command_type"))
                        .setCommandPayload(rs.getBytes("command_payload"))
                        .setCommandPayloadHash(rs.getBytes("command_payload_hash"))
                        .setStatus(rs.getString("status")).setAttemptCount(rs.getInt("attempt_count"))
                        .setActiveMessageId(rs.getString("active_message_id"))
                        .setActiveAttempt(rs.getInt("active_attempt")).setExpiresAt(rs.getLong("expires_at"))
                        .setLastError(rs.getString("last_error")).setVersion(rs.getLong("version"));
                d.setTenantId(rs.getString("tenant_id")); d.setClientId(rs.getString("client_id"));
                d.setCreateTime((Long) rs.getObject("create_time")); d.setUpdateTime((Long) rs.getObject("update_time"));
                return d;
            }, tenantId, clientId, ownerJiacn, commandId);
            return rows.isEmpty() ? null : rows.getFirst();
        }
        @Override public AgentCommandDeliveryEntity lockDeliveryV2(
                String tenantId, String clientId, String ownerJiacn, String commandId) {
            return lockDelivery(tenantId, clientId, ownerJiacn, commandId);
        }
        @Override public int insertDelivery(AgentCommandDeliveryEntity d) { return insertDeliveryV2(d); }
        @Override public int insertDeliveryV2(AgentCommandDeliveryEntity d) {
            var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            int rows = jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        INSERT INTO agent_command_delivery(protocol_version,data_domain_id,execution_pool_id,
                        admission_epoch,producer_deployment_id,module_id,migration_epoch,command_id,owner_jiacn,
                        task_id,work_item_id,target_agent_id,command_type,command_payload,command_payload_hash,
                        status,attempt_count,active_message_id,active_attempt,expires_at,last_error,version,
                        tenant_id,client_id,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS);
                Object[] values = {d.getProtocolVersion(),d.getDataDomainId(),d.getExecutionPoolId(),d.getAdmissionEpoch(),
                        d.getProducerDeploymentId(),d.getModuleId(),d.getMigrationEpoch(),d.getCommandId(),d.getOwnerJiacn(),
                        d.getTaskId(),d.getWorkItemId(),d.getTargetAgentId(),d.getCommandType(),d.getCommandPayload(),
                        d.getCommandPayloadHash(),d.getStatus(),d.getAttemptCount(),d.getActiveMessageId(),
                        d.getActiveAttempt(),d.getExpiresAt(),d.getLastError(),d.getVersion(),d.getTenantId(),
                        d.getClientId(),d.getCreateTime(),d.getUpdateTime()};
                for (int i=0;i<values.length;i++) ps.setObject(i+1, values[i]);
                return ps;
            }, key);
            d.setId(key.getKey().longValue()); return rows;
        }
        @Override public int insertOutbox(AgentOutboxEventEntity o) { return insertOutboxV2(o); }
        @Override public int insertOutboxV2(AgentOutboxEventEntity o) {
            if (failOutbox) throw new IllegalStateException("forced outbox failure");
            var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
            int rows = jdbc.update(connection -> {
                var ps = connection.prepareStatement("""
                        INSERT INTO agent_outbox_event(protocol_version,data_domain_id,execution_pool_id,
                        admission_epoch,producer_deployment_id,module_id,migration_epoch,event_id,message_id,command_id,
                        delivery_id,aggregate_type,aggregate_id,destination,routing_key,wire_payload,wire_payload_hash,
                        status,attempt_count,active_attempt,expires_at,publisher_confirm_status,mandatory_return_status,
                        last_error,version,tenant_id,client_id,create_time,update_time)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, java.sql.Statement.RETURN_GENERATED_KEYS);
                Object[] values = {o.getProtocolVersion(),o.getDataDomainId(),o.getExecutionPoolId(),o.getAdmissionEpoch(),
                        o.getProducerDeploymentId(),o.getModuleId(),o.getMigrationEpoch(),o.getEventId(),o.getMessageId(),
                        o.getCommandId(),o.getDeliveryId(),o.getAggregateType(),o.getAggregateId(),o.getDestination(),
                        o.getRoutingKey(),o.getWirePayload(),o.getWirePayloadHash(),o.getStatus(),o.getAttemptCount(),
                        o.getActiveAttempt(),o.getExpiresAt(),o.getPublisherConfirmStatus(),o.getMandatoryReturnStatus(),
                        o.getLastError(),o.getVersion(),o.getTenantId(),o.getClientId(),o.getCreateTime(),o.getUpdateTime()};
                for (int i=0;i<values.length;i++) ps.setObject(i+1, values[i]);
                return ps;
            }, key);
            o.setId(key.getKey().longValue()); return rows;
        }
        @Override public List<AgentOutboxEventEntity> lockActiveOutboxes(
                String tenantId, String clientId, long deliveryId, String messageId) {
            return jdbc.query("SELECT * FROM agent_outbox_event WHERE tenant_id=? AND client_id=? "
                    + "AND delivery_id=? AND message_id=? FOR UPDATE", (rs, ignored) -> {
                AgentOutboxEventEntity o = new AgentOutboxEventEntity()
                        .setId(rs.getLong("id")).setProtocolVersion((Integer) rs.getObject("protocol_version"))
                        .setDataDomainId(rs.getString("data_domain_id"))
                        .setExecutionPoolId(rs.getString("execution_pool_id"))
                        .setAdmissionEpoch((Long) rs.getObject("admission_epoch"))
                        .setProducerDeploymentId(rs.getString("producer_deployment_id"))
                        .setModuleId(rs.getString("module_id"))
                        .setMigrationEpoch((Long) rs.getObject("migration_epoch"))
                        .setEventId(rs.getString("event_id")).setMessageId(rs.getString("message_id"))
                        .setCommandId(rs.getString("command_id")).setDeliveryId(rs.getLong("delivery_id"))
                        .setAggregateType(rs.getString("aggregate_type")).setAggregateId(rs.getString("aggregate_id"))
                        .setDestination(rs.getString("destination")).setRoutingKey(rs.getString("routing_key"))
                        .setWirePayload(rs.getBytes("wire_payload")).setWirePayloadHash(rs.getBytes("wire_payload_hash"))
                        .setStatus(rs.getString("status")).setAttemptCount(rs.getInt("attempt_count"))
                        .setActiveAttempt(rs.getInt("active_attempt")).setExpiresAt(rs.getLong("expires_at"))
                        .setPublisherConfirmStatus(rs.getString("publisher_confirm_status"))
                        .setMandatoryReturnStatus(rs.getString("mandatory_return_status"))
                        .setLastError(rs.getString("last_error")).setVersion(rs.getLong("version"));
                o.setTenantId(rs.getString("tenant_id")); o.setClientId(rs.getString("client_id"));
                return o;
            }, tenantId, clientId, deliveryId, messageId);
        }
        @Override public int promoteShadowDelivery(AgentCommandDeliveryEntity delivery, String marker, long now) {
            throw new AssertionError("not used");
        }
        @Override public int promoteShadowOutbox(AgentOutboxEventEntity outbox, String marker, long now) {
            throw new AssertionError("not used");
        }
    }
}
