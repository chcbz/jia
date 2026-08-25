package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckRejectedException;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandRecoveryServiceTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS;
    private static final String COMMAND_ID = "cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624";
    private static final String M1 = "11111111-1111-1111-1111-111111111111";
    private static final String M2 = "22222222-2222-2222-2222-222222222222";
    private static final String E2 = "33333333-3333-3333-3333-333333333333";

    @Test
    void reconnectWinnerCreatesOneFreshCanonicalOutboxAndParentAudit() {
        RecordingDao dao = waitingDao();
        PresenceDispatcher presence = new PresenceDispatcher(true);
        List<UUID> ids = new ArrayList<>(List.of(UUID.fromString(M2), UUID.fromString(E2)));
        AgentCommandReissueServiceImpl service = new AgentCommandReissueServiceImpl(
                dao, enabledGate(), presence, AgentRabbitTopologyManifest.canonical(),
                transactionManager(), () -> ids.removeFirst());

        AgentCommandReissueScanResult result = service.reissueForReconnect(
                reconnectScope(), 10, NOW);

        assertEquals(new AgentCommandReissueScanResult(1, 1, 1), result);
        assertEquals(List.of("delivery", "outbox", "inbox", "reissue", "insert"), dao.operations);
        assertEquals(M2, dao.newMessageId);
        assertEquals(M1, dao.parentMessageId);
        assertEquals("agent-a", dao.requestedBy);
        assertEquals(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT, dao.reason);
        AgentOutboxEventEntity created = dao.inserted;
        assertNotNull(created);
        assertEquals(E2, created.getEventId());
        assertEquals(M2, created.getMessageId());
        assertEquals(COMMAND_ID, created.getCommandId());
        assertEquals(1L, created.getDeliveryId());
        assertEquals("PENDING", created.getStatus());
        assertEquals(0, created.getAttemptCount());
        assertEquals(2, created.getActiveAttempt());
        assertEquals(M1, created.getReplayParentMessageId());
        assertEquals("agent-a", created.getReplayRequesterId());
        assertEquals(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT,
                created.getReplayReason());
        assertArrayEquals(AgentCommandCanonicalCodec.sha256(created.getWirePayload()),
                created.getWirePayloadHash());
        String wire = new String(created.getWirePayload(), StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"messageId\":\"" + M2 + "\""));
        assertTrue(wire.contains("\"commandId\":\"" + COMMAND_ID + "\""));
        assertTrue(wire.contains("\"attempt\":2"));
        assertFalse(wire.contains(M1));
        assertNotEquals(new String(dao.outbox.getWirePayload(), StandardCharsets.UTF_8), wire);
        assertEquals(3, presence.checks);
    }

    @Test
    void casLoserAndLateDisconnectCreateNoOutbox() {
        RecordingDao loser = waitingDao();
        loser.reissueRows = 0;
        AgentCommandReissueScanResult lost = reissue(loser, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW);
        assertEquals(0, lost.reissued());
        assertEquals(null, loser.inserted);

        RecordingDao disconnected = waitingDao();
        PresenceDispatcher twoPhase = new PresenceDispatcher(true, true, false);
        AgentCommandReissueScanResult declined = reissue(disconnected, twoPhase)
                .reissueForReconnect(reconnectScope(), 10, NOW);
        assertEquals(0, declined.reissued());
        assertFalse(disconnected.operations.contains("reissue"));
    }

    @Test
    void waitingSourcePoisonExpiryAndAttemptOrVersionExhaustionFailClosed() {
        List<java.util.function.Consumer<RecordingDao>> poison = List.of(
                dao -> dao.delivery.setExpiresAt(NOW),
                dao -> dao.delivery.setActiveAttempt(Integer.MAX_VALUE)
                        .setAttemptCount(Integer.MAX_VALUE),
                dao -> dao.delivery.setVersion(Long.MAX_VALUE - 7),
                dao -> dao.delivery.setCommandPayloadHash(new byte[32]),
                dao -> dao.outbox.setActiveAttempt(2),
                dao -> dao.inbox.setActiveAttempt(2),
                dao -> dao.inbox.setMessageId("stale-message"),
                dao -> dao.inbox.setReplayReason("poison"),
                dao -> dao.outbox.setEventId(""),
                dao -> dao.outbox.setReplayParentMessageId("poison"));
        for (var corrupt : poison) {
            RecordingDao dao = waitingDao();
            corrupt.accept(dao);
            AgentCommandReissueScanResult result = reissue(dao, new PresenceDispatcher(true))
                    .reissueForReconnect(reconnectScope(), 10, NOW);
            assertEquals(0, result.reissued());
            assertFalse(dao.operations.contains("reissue"));
            assertEquals(null, dao.inserted);
        }
    }


    @Test
    void reissueVersionBudgetAcceptsMaxMinusEightAndRejectsAnythingHigher() {
        RecordingDao allowed = waitingDao();
        allowed.delivery.setVersion(Long.MAX_VALUE - 8);
        assertEquals(1, reissue(allowed, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());

        RecordingDao exhausted = waitingDao();
        exhausted.delivery.setVersion(Long.MAX_VALUE - 7);
        assertEquals(0, reissue(exhausted, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertEquals(null, exhausted.inserted);
    }

    @Test
    void reconnectRequesterReasonBindingRejectsImpersonationBeforeDiscoveryOrMutation() {
        for (AgentCommandReconnectScope invalid : List.of(
                new AgentCommandReconnectScope(
                        "tenant-a", "client-a", "agent-a", "operator", "AGENT_RECONNECT"),
                new AgentCommandReconnectScope(
                        "tenant-a", "client-a", "agent-a", "agent-b", "AGENT_RECONNECT"),
                new AgentCommandReconnectScope(
                        "tenant-a", "client-a", "agent-a", "agent-a",
                        "WAITING_AGENT_SCHEDULER"))) {
            RecordingDao dao = waitingDao();
            assertThrows(IllegalArgumentException.class, () -> reissue(
                    dao, new PresenceDispatcher(true)).reissueForReconnect(invalid, 10, NOW));
            assertEquals(0, dao.discoveryCalls);
            assertEquals(List.of(), dao.operations);
            assertEquals(null, dao.inserted);
        }
    }

    @Test
    void storedAutomaticReplayAuditRejectsRequesterReasonAndApproverDisguises() {
        List<java.util.function.Consumer<RecordingDao>> poison = List.of(
                dao -> replayAudit(dao, "operator", null, "AGENT_RECONNECT"),
                dao -> replayAudit(dao, "agent-b", null, "AGENT_RECONNECT"),
                dao -> replayAudit(dao, "agent-a", null, "WAITING_AGENT_SCHEDULER"),
                dao -> replayAudit(dao, "agent-a", "operator", "AGENT_RECONNECT"));
        for (var corrupt : poison) {
            RecordingDao dao = waitingDao();
            setTransportAttempt(dao, 2);
            replayAudit(dao, "agent-a", null, "AGENT_RECONNECT");
            corrupt.accept(dao);

            AgentCommandReissueScanResult result = reissue(dao, new PresenceDispatcher(true))
                    .reissueForReconnect(reconnectScope(), 10, NOW);

            assertEquals(0, result.reissued());
            assertFalse(dao.operations.contains("reissue"));
            assertEquals(null, dao.inserted);
        }
    }

    @Test
    void reissueSourceRequiresAttemptAuditAndKeepsPublishRetryCountIndependent() {
        RecordingDao missingAudit = waitingDao();
        setTransportAttempt(missingAudit, 2);
        assertEquals(0, reissue(missingAudit, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertFalse(missingAudit.operations.contains("reissue"));
        assertEquals(null, missingAudit.inserted);

        RecordingDao mismatch = waitingDao();
        mismatch.outbox.setActiveAttempt(2);
        assertEquals(0, reissue(mismatch, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertFalse(mismatch.operations.contains("reissue"));
        assertEquals(null, mismatch.inserted);

        RecordingDao automatic = waitingDao();
        setTransportAttempt(automatic, 2);
        replayAudit(automatic, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        automatic.outbox.setAttemptCount(7);
        assertEquals(1, reissue(automatic, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertEquals(3, automatic.inserted.getActiveAttempt());
        assertEquals(0, automatic.inserted.getAttemptCount());
    }

    @Test
    void schedulerRequiresDueAndReturnsFairnessCursor() {
        RecordingDao dao = waitingDao();
        dao.delivery.setNextRetryAt(NOW + 1);
        dao.inbox.setNextRetryAt(NOW + 1);
        AgentCommandReissueScanResult result = reissue(dao, new PresenceDispatcher(true))
                .reissueDue(10, 0, NOW);
        assertEquals(1, result.examined());
        assertEquals(0, result.reissued());
        assertEquals(1, result.lastVisitedDeliveryId());
    }


    @Test
    void schedulerWinnerBindsSystemRequesterToSchedulerReason() {
        RecordingDao dao = waitingDao();

        AgentCommandReissueScanResult result = reissue(dao, new PresenceDispatcher(true))
                .reissueDue(10, 0, NOW);

        assertEquals(1, result.reissued());
        assertEquals(AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER, dao.requestedBy);
        assertEquals(AgentCommandReissueServiceImpl.REASON_SCHEDULER, dao.reason);
        assertEquals(AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER,
                dao.inserted.getReplayRequesterId());
        assertEquals(AgentCommandReissueServiceImpl.REASON_SCHEDULER,
                dao.inserted.getReplayReason());
    }

    @Test
    void schedulerWithPresenceThatWasNotSuccessfullyRegisteredPerformsZeroMutation() {
        RecordingDao dao = waitingDao();
        PresenceDispatcher unregisteredPresence = new PresenceDispatcher(false);

        AgentCommandReissueScanResult result = reissue(dao, unregisteredPresence)
                .reissueDue(10, 0, NOW);

        assertEquals(1, result.examined());
        assertEquals(0, result.reissued());
        assertFalse(dao.operations.contains("delivery"));
        assertFalse(dao.operations.contains("reissue"));
        assertEquals(null, dao.inserted);
    }

    @Test
    void offOrWrongScopeStopsBeforeDiscovery() {
        RecordingDao dao = waitingDao();
        AgentCommandReissueServiceImpl service = new AgentCommandReissueServiceImpl(
                dao, offGate(), new PresenceDispatcher(true), AgentRabbitTopologyManifest.canonical(),
                transactionManager());
        assertEquals(0, service.reissueDue(10, 0, NOW).examined());
        assertEquals(0, dao.discoveryCalls);

        RecordingDao wrong = waitingDao();
        AgentCommandReconnectScope scope = new AgentCommandReconnectScope(
                "tenant-x", "client-a", "agent-a", "agent-a", "AGENT_RECONNECT");
        assertEquals(0, reissue(wrong, new PresenceDispatcher(true))
                .reissueForReconnect(scope, 10, NOW).examined());
        assertEquals(0, wrong.discoveryCalls);
    }

    @Test
    void ackAcceptsA06FifoAndExactDuplicateOnly() {
        RecordingDao dao = sentDao();
        AgentCommandAckServiceImpl service = ackService(dao, enabledGate());

        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-1", "RECEIVED", M1), NOW).kind());
        assertEquals(AgentCommandAckResult.Kind.PRIOR,
                service.acknowledge(ack("ack-duplicate", "RECEIVED", M1), NOW).kind());
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-2", "STARTED", M1), NOW).kind());
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-3", "SUCCEEDED", M1), NOW).kind());
        assertEquals("SUCCEEDED", dao.delivery.getStatus());
        assertEquals(3, dao.ackMutations);
    }


    @Test
    void ackAcceptsA06RejectedFromEachNonTerminalAndFreezesTerminalConflicts() {
        for (String starting : List.of("SENT", "RECEIVED", "STARTED")) {
            RecordingDao dao = sentDao();
            dao.delivery.setStatus(starting);
            AgentCommandAckServiceImpl service = ackService(dao, enabledGate());
            assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                    service.acknowledge(ack("ack-rejected-" + starting, "REJECTED", M1), NOW).kind());
            assertEquals("REJECTED", dao.delivery.getStatus());
            assertEquals(AgentCommandAckServiceImpl.AGENT_REPORTED_REJECTED,
                    dao.delivery.getLastError());
            assertEquals(AgentCommandAckResult.Kind.PRIOR,
                    service.acknowledge(ack("ack-rejected-duplicate-" + starting,
                            "REJECTED", M1), NOW).kind());
            for (String conflict : List.of("SUCCEEDED", "FAILED")) {
                assertThrows(AgentCommandAckRejectedException.class,
                        () -> service.acknowledge(
                                ack("ack-conflict-" + conflict, conflict, M1), NOW));
            }
            assertEquals(1, dao.ackMutations);
        }
    }

    @Test
    void ackSkipBackwardTerminalConflictAndOldMessageFailClosed() {
        for (AgentCommandAck invalid : List.of(
                ack("ack-skip", "STARTED", M1),
                ack("ack-old", "RECEIVED", "old-message"),
                new AgentCommandAck("tenant-other", "client-a", "agent-a", "ack-tenant", M1,
                        COMMAND_ID, "task-1", null, "RECEIVED", NOW),
                new AgentCommandAck("tenant-a", "client-other", "agent-a", "ack-client", M1,
                        COMMAND_ID, "task-1", null, "RECEIVED", NOW),
                new AgentCommandAck("tenant-a", "client-a", "agent-a", "ack-task", M1,
                        COMMAND_ID, "task-other", null, "RECEIVED", NOW),
                new AgentCommandAck("tenant-a", "client-a", "agent-a", "ack-work", M1,
                        COMMAND_ID, "task-1", "work-other", "RECEIVED", NOW),
                new AgentCommandAck("tenant-a", "client-a", "agent-other", "ack-agent", M1,
                        COMMAND_ID, "task-1", null, "RECEIVED", NOW))) {
            RecordingDao dao = sentDao();
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(dao, enabledGate()).acknowledge(invalid, NOW));
            assertEquals(0, dao.ackMutations);
        }

        RecordingDao terminal = sentDao();
        AgentCommandAckServiceImpl service = ackService(terminal, enabledGate());
        service.acknowledge(ack("ack-r", "RECEIVED", M1), NOW);
        service.acknowledge(ack("ack-s", "STARTED", M1), NOW);
        service.acknowledge(ack("ack-f", "FAILED", M1), NOW);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> service.acknowledge(ack("ack-conflict", "SUCCEEDED", M1), NOW));
        assertEquals("FAILED", terminal.delivery.getStatus());


        for (String terminalStatus : List.of("SUCCEEDED", "FAILED")) {
            RecordingDao completed = sentDao();
            AgentCommandAckServiceImpl completedService = ackService(completed, enabledGate());
            completedService.acknowledge(ack("ack-r-" + terminalStatus, "RECEIVED", M1), NOW);
            completedService.acknowledge(ack("ack-s-" + terminalStatus, "STARTED", M1), NOW);
            completedService.acknowledge(
                    ack("ack-terminal-" + terminalStatus, terminalStatus, M1), NOW);
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> completedService.acknowledge(
                            ack("ack-rejected-conflict-" + terminalStatus, "REJECTED", M1), NOW));
            assertEquals(terminalStatus, completed.delivery.getStatus());
            assertEquals(3, completed.ackMutations);
        }
    }

    @Test
    void ackReplayAuditUsesTheSameRequesterReasonBindingWithoutAdvancingPoison() {
        for (List<String> valid : List.of(
                List.of("agent-a", AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT),
                List.of(AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER,
                        AgentCommandReissueServiceImpl.REASON_SCHEDULER))) {
            RecordingDao dao = sentDao();
            setTransportAttempt(dao, 2);
            replayAudit(dao, valid.get(0), null, valid.get(1));
            assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                    ackService(dao, enabledGate()).acknowledge(
                            ack("ack-valid-" + valid.get(1), "RECEIVED", M1), NOW).kind());
            assertEquals(1, dao.ackMutations);
        }

        List<java.util.function.Consumer<RecordingDao>> poison = List.of(
                dao -> replayAudit(dao, "operator", null, "AGENT_RECONNECT"),
                dao -> replayAudit(dao, "agent-b", null, "AGENT_RECONNECT"),
                dao -> replayAudit(dao, "agent-a", null, "WAITING_AGENT_SCHEDULER"),
                dao -> replayAudit(dao, "agent-a", "manual-approver", "AGENT_RECONNECT"),
                dao -> dao.delivery.setReplayParentMessageId("parent-message")
                        .setReplayRequesterId("agent-a").setReplayReason("AGENT_RECONNECT"));
        for (var corrupt : poison) {
            RecordingDao dao = sentDao();
            setTransportAttempt(dao, 2);
            corrupt.accept(dao);
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(dao, enabledGate()).acknowledge(
                            ack("ack-invalid", "RECEIVED", M1), NOW));
            assertEquals(0, dao.ackMutations);
        }
    }

    @Test
    void ackRequiresAttemptAuditAndKeepsPublishRetryCountIndependent() {
        RecordingDao missingAudit = sentDao();
        setTransportAttempt(missingAudit, 2);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(missingAudit, enabledGate()).acknowledge(
                        ack("ack-missing-audit", "RECEIVED", M1), NOW));
        assertEquals(0, missingAudit.ackMutations);

        RecordingDao mismatch = sentDao();
        mismatch.outbox.setActiveAttempt(2);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(mismatch, enabledGate()).acknowledge(
                        ack("ack-attempt-mismatch", "RECEIVED", M1), NOW));
        assertEquals(0, mismatch.ackMutations);

        RecordingDao automatic = sentDao();
        setTransportAttempt(automatic, 2);
        replayAudit(automatic, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        automatic.outbox.setAttemptCount(7);
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                ackService(automatic, enabledGate()).acknowledge(
                        ack("ack-automatic", "RECEIVED", M1), NOW).kind());
        assertEquals(1, automatic.ackMutations);
    }

    @Test
    void ackRejectsNonIndependentIdSourcePoisonAndDisabledGate() {
        RecordingDao sameMessage = sentDao();
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(sameMessage, enabledGate()).acknowledge(
                        ack(M1, "RECEIVED", M1), NOW));

        RecordingDao poison = sentDao();
        poison.outbox.setWirePayloadHash(new byte[32]);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(poison, enabledGate()).acknowledge(
                        ack("ack-poison", "RECEIVED", M1), NOW));
        assertEquals(0, poison.ackMutations);

        RecordingDao disabled = sentDao();
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(disabled, offGate()).acknowledge(
                        ack("ack-off", "RECEIVED", M1), NOW));
        assertEquals(List.of(), disabled.operations);
    }

    private AgentCommandReissueServiceImpl reissue(RecordingDao dao, PresenceDispatcher dispatcher) {
        List<UUID> ids = new ArrayList<>(List.of(UUID.fromString(M2), UUID.fromString(E2)));
        return new AgentCommandReissueServiceImpl(
                dao, enabledGate(), dispatcher, AgentRabbitTopologyManifest.canonical(),
                transactionManager(), () -> ids.removeFirst());
    }

    private AgentCommandAckServiceImpl ackService(RecordingDao dao, AgentRabbitSafetyGate gate) {
        return new AgentCommandAckServiceImpl(dao, gate, transactionManager());
    }

    private AgentCommandReconnectScope reconnectScope() {
        return new AgentCommandReconnectScope(
                "tenant-a", "client-a", "agent-a", "agent-a", "AGENT_RECONNECT");
    }

    private AgentCommandAck ack(String ackMessageId, String status, String correlationId) {
        return new AgentCommandAck(
                "tenant-a", "client-a", "agent-a", ackMessageId, correlationId,
                COMMAND_ID, "task-1", null, status, NOW);
    }

    private static RecordingDao waitingDao() {
        RecordingDao dao = sourceDao();
        dao.delivery.setStatus("WAITING_AGENT").setNextRetryAt(NOW - 1)
                .setLastError(AgentCommandRabbitConsumer.AGENT_OFFLINE);
        dao.inbox.setStatus("WAITING_AGENT").setResultStatus("WAITING_AGENT")
                .setNextRetryAt(NOW - 1).setLastError(AgentCommandRabbitConsumer.AGENT_OFFLINE);
        return dao;
    }

    private static RecordingDao sentDao() {
        RecordingDao dao = sourceDao();
        dao.delivery.setStatus("SENT").setNextRetryAt(null).setLastError(null);
        dao.inbox.setStatus("PROCESSED").setResultStatus("SENT")
                .setNextRetryAt(null).setLastError(null);
        return dao;
    }

    private static RecordingDao sourceDao() {
        AgentCommandDraft draft = draft();
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, M1, 1);
        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setId(1L).setCommandId(COMMAND_ID).setTaskId("task-1").setWorkItemId(null)
                .setTargetAgentId("agent-a").setCommandType(AgentProtocolConstants.COMMAND_TASK_INVITE)
                .setCommandPayload(business).setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business))
                .setAttemptCount(1).setActiveMessageId(M1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setVersion(7L);
        delivery.setTenantId("tenant-a");
        delivery.setClientId("client-a");

        AgentRabbitTopologyManifest.PublishRoute route =
                AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setId(10L).setEventId("event-1").setMessageId(M1).setCommandId(COMMAND_ID)
                .setDeliveryId(1L).setAggregateType("task").setAggregateId("task-1")
                .setDestination(route.destination()).setRoutingKey(route.routingKey())
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(1)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK").setConfirmedAt(NOW - 10)
                .setMandatoryReturnStatus("NOT_RETURNED").setPublishedAt(NOW - 9)
                .setVersion(2L);
        outbox.setTenantId("tenant-a");
        outbox.setClientId("client-a");

        AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                .setId(20L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1)
                .setMessageId(M1).setEventId("event-1").setCommandId(COMMAND_ID).setDeliveryId(1L)
                .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                .setAttemptCount(1).setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setProcessedAt(NOW - 2).setVersion(1L);
        inbox.setTenantId("tenant-a");
        inbox.setClientId("client-a");
        return new RecordingDao(delivery, outbox, inbox);
    }

    private static void setTransportAttempt(RecordingDao dao, int attempt) {
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft(), M1, attempt);
        byte[] wireHash = AgentCommandCanonicalCodec.sha256(wire);
        dao.delivery.setAttemptCount(attempt).setActiveAttempt(attempt);
        dao.outbox.setActiveAttempt(attempt).setWirePayload(wire).setWirePayloadHash(wireHash);
        dao.inbox.setWirePayload(wire).setWirePayloadHash(wireHash);
    }

    private static void replayAudit(
            RecordingDao dao, String requester, String approver, String reason) {
        dao.delivery.setReplayParentMessageId("parent-message")
                .setReplayRequesterId(requester).setReplayApproverId(approver)
                .setReplayReason(reason);
        dao.outbox.setReplayParentMessageId("parent-message")
                .setReplayRequesterId(requester).setReplayApproverId(approver)
                .setReplayReason(reason);
    }

    private static AgentCommandDraft draft() {
        return new AgentCommandDraft(1, COMMAND_ID, "task-1", "cause-1",
                "tenant-a", "client-a", "task-1", null, "agent-a",
                AgentProtocolConstants.COMMAND_TASK_INVITE, ISSUED, EXPIRES,
                new AgentTaskInvitePayload(
                        "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        "title", List.of("review"), "agent-a", List.of("agent-a", "agent-b"),
                        "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
    }

    private static DataSourceTransactionManager transactionManager() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:d06_unit_" + System.nanoTime() + ";MODE=MYSQL");
        return new DataSourceTransactionManager(dataSource);
    }

    private static AgentRabbitSafetyGate enabledGate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "pass", "/d06"));
        return new AgentRabbitSafetyGate(properties, new AgentRabbitDispatchScopeProperties(
                List.of(new AgentRabbitDispatchScopeProperties.AllowedScope("tenant-a", "client-a"))));
    }

    private static AgentRabbitSafetyGate offGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                null, null, null, null, null, null));
    }

    private static final class PresenceDispatcher implements AgentRawCommandDispatcher {
        private final List<Boolean> answers;
        private int checks;

        private PresenceDispatcher(Boolean... answers) {
            this.answers = new ArrayList<>(List.of(answers));
        }

        @Override
        public boolean isExactAgentConnected(String tenantId, String clientId, String targetAgentId) {
            checks++;
            return answers.isEmpty() || answers.removeFirst();
        }

        @Override
        public AgentRawCommandDispatchResult dispatchExactRawCommand(
                String tenantId, String clientId, String taskId,
                String targetAgentId, byte[] rawWireBytes) {
            throw new AssertionError("D06 recovery must not dispatch WebSocket bytes");
        }
    }

    private static final class RecordingDao implements AgentCommandRecoveryDao {
        private final AgentCommandDeliveryEntity delivery;
        private final AgentOutboxEventEntity outbox;
        private final AgentConsumerInboxEntity inbox;
        private final List<String> operations = new ArrayList<>();
        private int discoveryCalls;
        private int reissueRows = 1;
        private int ackRows = 1;
        private int ackMutations;
        private String newMessageId;
        private String parentMessageId;
        private String requestedBy;
        private String reason;
        private AgentOutboxEventEntity inserted;

        private RecordingDao(
                AgentCommandDeliveryEntity delivery,
                AgentOutboxEventEntity outbox,
                AgentConsumerInboxEntity inbox) {
            this.delivery = delivery;
            this.outbox = outbox;
            this.inbox = inbox;
        }

        @Override
        public List<AgentWaitingCommandCandidate> findReconnectCandidates(
                String tenantId, String clientId, String targetAgentId,
                long now, long afterDeliveryId, int limit) {
            discoveryCalls++;
            return List.of(new AgentWaitingCommandCandidate(1, tenantId, clientId, targetAgentId));
        }

        @Override
        public List<AgentWaitingCommandCandidate> findDueCandidates(long now, long afterDeliveryId, int limit) {
            discoveryCalls++;
            return List.of(new AgentWaitingCommandCandidate(
                    1, delivery.getTenantId(), delivery.getClientId(), delivery.getTargetAgentId()));
        }

        @Override
        public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) {
            operations.add("delivery");
            return delivery;
        }

        @Override
        public AgentCommandDeliveryEntity lockDeliveryByCommand(
                String tenantId, String clientId, String commandId) {
            operations.add("delivery");
            return delivery;
        }

        @Override
        public List<AgentOutboxEventEntity> lockActiveOutboxes(
                String tenantId, String clientId, long deliveryId, String messageId) {
            operations.add("outbox");
            return List.of(outbox);
        }

        @Override
        public AgentConsumerInboxEntity lockInbox(
                String tenantId, String clientId, String consumerName, String messageId) {
            operations.add("inbox");
            return inbox;
        }

        @Override
        public int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
                String requestedBy, String reason, String lastError, long now) {
            operations.add("reissue");
            this.newMessageId = newMessageId;
            this.parentMessageId = delivery.getActiveMessageId();
            this.requestedBy = requestedBy;
            this.reason = reason;
            return reissueRows;
        }

        @Override
        public int insertOutbox(AgentOutboxEventEntity outbox) {
            operations.add("insert");
            inserted = outbox;
            outbox.setId(30L);
            return 1;
        }

        @Override
        public int advanceAck(
                AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now) {
            operations.add("ack");
            if (ackRows == 1) {
                ackMutations++;
                delivery.setStatus(newStatus).setLastError(lastError)
                        .setVersion(delivery.getVersion() + 1);
            }
            return ackRows;
        }
    }
}
