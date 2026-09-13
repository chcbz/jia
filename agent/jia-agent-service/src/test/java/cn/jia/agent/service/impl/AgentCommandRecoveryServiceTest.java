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
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentHallCommandPayload;
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
    private static final String M3 = "44444444-4444-4444-4444-444444444444";

    @Test
    void d06AckAndReissueAcceptEveryCanonicalHallCommandType() {
        List<ActionType> allowlist = List.of(
                new ActionType("execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE),
                new ActionType("resume", AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME),
                new ActionType("cancel", AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL),
                new ActionType("ask_help", AgentProtocolConstants.COMMAND_REQUEST_RESPOND),
                new ActionType("review", AgentProtocolConstants.COMMAND_REVIEW_EXECUTE),
                new ActionType("request_report", AgentProtocolConstants.COMMAND_CONTEXT_REFRESH));

        for (ActionType item : allowlist) {
            AgentCommandDraft draft = hallDraft(item.actionType(), item.commandType());
            RecordingDao waiting = withDraft(waitingDao(), draft);
            AgentCommandReissueScanResult reissued = reissue(
                    waiting, new PresenceDispatcher(true))
                    .reissueForReconnect(reconnectScope(), 10, NOW);
            assertEquals(1, reissued.reissued(), item.commandType());
            assertEquals(draft.commandId(), waiting.inserted.getCommandId());
            assertTrue(new String(waiting.inserted.getWirePayload(), StandardCharsets.UTF_8)
                    .contains("\"commandType\":\"" + item.commandType() + "\""));

            RecordingDao sent = withDraft(sentDao(), draft);
            AgentCommandAck ack = new AgentCommandAck(
                    "tenant-a", "client-a", "agent-a", "ack-" + item.actionType(), M1,
                    draft.commandId(), "task-1", draft.workItemId(), "RECEIVED", NOW);
            AgentCommandAckResult result = ackService(sent, enabledGate()).acknowledge(ack, NOW);
            assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind(), item.commandType());
        }
    }

    @Test
    void approvedManualReissueUsesD06LockOrderCasAndFreshTransportIdentity() {
        AgentCommandDraft manualDraft = hallDraft(
                "execute", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        RecordingDao dao = withDraft(sourceDao(), manualDraft);
        dao.delivery.setStatus("DEAD").setNextRetryAt(null).setLastError("BROKER_DEAD");
        dao.inbox.setStatus("DEAD").setResultStatus("DEAD").setNextRetryAt(null)
                .setLeaseOwner(null).setLeaseUntil(null).setProcessedAt(NOW - 1)
                .setLastError("BROKER_DEAD");
        List<UUID> ids = new ArrayList<>(List.of(UUID.fromString(M2), UUID.fromString(E2)));
        AgentCommandReissueServiceImpl service = new AgentCommandReissueServiceImpl(
                dao, enabledGate(), new PresenceDispatcher(false),
                AgentRabbitTopologyManifest.canonical(), transactionManager(),
                () -> ids.removeFirst());
        AgentCommandOperationRequest request = new AgentCommandOperationRequest(
                "tenant-a", "client-a", 1L, "task-1", "agent-a", M1,
                "operator-a", "approver-b", "incident recovery", "INC-42");

        AgentCommandManualReissueResult result = service.reissueManually(request, NOW);

        assertEquals(new AgentCommandManualReissueResult(
                1L, manualDraft.commandId(), M1, M2, E2, 1, 2), result);
        assertEquals(List.of("delivery", "outbox", "inbox", "manualReissue", "insert"),
                dao.operations);
        assertEquals("operator-a", dao.requestedBy);
        assertEquals("approver-b", dao.approverId);
        assertEquals("incident recovery", dao.reason);
        assertEquals(M1, dao.inserted.getReplayParentMessageId());
        assertEquals("approver-b", dao.inserted.getReplayApproverId());
        assertEquals(2, dao.inserted.getActiveAttempt());
        assertArrayEquals(AgentCommandCanonicalCodec.businessBytes(manualDraft),
                dao.delivery.getCommandPayload());
        assertArrayEquals(AgentCommandCanonicalCodec.sha256(dao.inserted.getWirePayload()),
                dao.inserted.getWirePayloadHash());
        assertTrue(new String(dao.inserted.getWirePayload(), StandardCharsets.UTF_8)
                .contains("\"commandType\":\""
                        + AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE + "\""));
    }

    @Test
    void manualReplayLineageIsDurableButNeverQualifiesForAutomaticParentAckLane() {
        RecordingDao dao = sourceDao();
        setTransportAttempt(dao, 2);
        replayAudit(dao, "operator-a", "approver-b", "incident recovery");

        assertTrue(AgentCommandAutomaticReplayProvenance.validImmediateParent(
                dao.delivery, dao.outbox, dao.previousAttempts));
        assertFalse(AgentCommandAutomaticReplayProvenance.validAutomaticImmediateParent(
                dao.delivery, dao.outbox, dao.previousAttempts));
    }

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
    void boundedRecoveryPolicyDefersRepeatedAutomaticReplayAndStopsAtTakeoverBoundary() {
        RecordingDao deferred = waitingDao();
        setTransportAttempt(deferred, 2);
        replayAudit(deferred, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        deferred.delivery.setUpdateTime(NOW - 4_999L);

        AgentCommandReissueScanResult early = reissue(
                deferred, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW);

        assertEquals(0, early.reissued());
        assertFalse(deferred.operations.contains("reissue"));
        assertEquals(null, deferred.inserted);

        RecordingDao boundary = waitingDao();
        setTransportAttempt(boundary, 2);
        replayAudit(boundary, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        boundary.delivery.setUpdateTime(NOW - 5_000L);
        assertEquals(1, reissue(boundary, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());

        RecordingDao exhausted = waitingDao();
        setTransportAttempt(exhausted,
                AgentCommandRecoveryPolicy.DEFAULT_MAX_AUTOMATIC_ATTEMPTS);
        replayAudit(exhausted, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        exhausted.delivery.setUpdateTime(NOW - 60_000L);

        AgentCommandReissueScanResult stopped = reissue(
                exhausted, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW);

        assertEquals(0, stopped.reissued());
        assertEquals("WAITING_AGENT", exhausted.delivery.getStatus());
        assertEquals("WAITING_AGENT", exhausted.inbox.getStatus());
        assertEquals("WAITING_AGENT", exhausted.inbox.getResultStatus());
        assertEquals(AgentCommandReissueServiceImpl.AGENT_OFFLINE,
                exhausted.delivery.getLastError());
        assertEquals("MANUAL_TAKEOVER_REQUIRED", stopped.deferrals().getFirst().reason());
        assertEquals(List.of("delivery", "outbox", "previous", "inbox"), exhausted.operations);
        assertFalse(exhausted.operations.contains("reissue"));
        assertEquals(null, exhausted.inserted);

        RecordingDao deadline = waitingDao();
        setTransportAttempt(deadline, 2);
        replayAudit(deadline, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        deadline.delivery.setUpdateTime(EXPIRES - 4_000L);
        assertEquals(0, reissue(deadline, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, EXPIRES - 3_000L).reissued());
        assertEquals("WAITING_AGENT", deadline.delivery.getStatus());
        assertEquals(AgentCommandReissueServiceImpl.AGENT_OFFLINE,
                deadline.delivery.getLastError());
        assertEquals(null, deadline.inserted);
    }

    @Test
    void exhaustedSentAndHistoricalAttemptsKeepExactStateThenAcceptDirectOrParentTerminal() {
        for (int attempt : new int[] {4, 6, 9, Integer.MAX_VALUE}) {
            for (String correlation : List.of(M3, M2)) {
                for (String terminal : List.of("SUCCEEDED", "FAILED", "REJECTED")) {
                    RecordingDao dao = sentDao();
                    setAutomaticReplay(dao, attempt, M3, M2, M1);
                    byte[] before = sourceSnapshot(dao);
                    AgentCommandReissueServiceImpl service = reissue(dao, new PresenceDispatcher(true));
                    for (AgentCommandReissueScanResult result : List.of(
                            service.reissueForReconnect(reconnectScope(), 10, NOW),
                            service.reissueDue(10, 0, NOW))) {
                        assertEquals(0, result.reissued());
                        var observation = result.deferrals().getFirst();
                        assertEquals("MANUAL_TAKEOVER_REQUIRED", observation.reason());
                        assertEquals("tenant-a", observation.tenantId());
                        assertEquals("client-a", observation.clientId());
                        assertEquals("task-1", observation.taskId());
                        assertEquals(null, observation.workItemId());
                        assertEquals("agent-a", observation.targetAgentId());
                        assertEquals(1L, observation.deliveryId());
                        assertEquals(M3, observation.activeMessageId());
                        assertEquals(attempt, observation.activeAttempt());
                        assertEquals(dao.delivery.getVersion().longValue(), observation.deliveryVersion());
                        assertEquals(NOW, observation.observedAt());
                        assertEquals(EXPIRES, observation.expiresAt());
                    }
                    assertEquals(List.of("delivery", "outbox", "previous", "inbox",
                            "delivery", "outbox", "previous", "inbox"), dao.operations);
                    assertArrayEquals(before, sourceSnapshot(dao));
                    assertEquals(null, dao.inserted);
                    assertEquals(0, dao.ackMutations);

                    AgentCommandAckServiceImpl ack = ackService(dao, enabledGate());
                    for (String nonterminal : List.of("RECEIVED", "STARTED")) {
                        assertThrows(AgentCommandAckRejectedException.class, () -> ack.acknowledge(
                                ack("ambiguous-" + nonterminal, nonterminal, correlation), NOW + 1));
                        assertArrayEquals(before, sourceSnapshot(dao));
                    }
                    assertEquals(AgentCommandAckResult.Kind.ADVANCED, ack.acknowledge(
                            ack("actual-terminal", terminal, correlation), NOW + 2).kind());
                    assertEquals(terminal, dao.delivery.getStatus());
                    assertEquals(AgentCommandAckResult.Kind.PRIOR, ack.acknowledge(
                            ack("terminal-duplicate", terminal, correlation), NOW + 3).kind());
                    assertEquals(1, dao.ackMutations);
                }
            }
        }
    }

    @Test
    void deadlineDeferralKeepsSentAckEligibleAndObservationDoesNotAuthorizeManualReissue() {
        RecordingDao dao = sentDao();
        setAutomaticReplay(dao);
        dao.delivery.setUpdateTime(EXPIRES - 4_000L);
        byte[] before = sourceSnapshot(dao);
        var service = reissue(dao, new PresenceDispatcher(true));
        var scan = service.reissueForReconnect(reconnectScope(), 10, EXPIRES - 3_000L);
        assertEquals("DEADLINE_EXHAUSTED", scan.deferrals().getFirst().reason());
        assertEquals(EXPIRES, scan.deferrals().getFirst().policyBoundaryAt());
        assertEquals(0, scan.reissued());
        assertArrayEquals(before, sourceSnapshot(dao));
        AgentCommandOperationRequest request = new AgentCommandOperationRequest(
                "tenant-a", "client-a", 1L, "task-1", "agent-a", M2,
                "operator-a", "approver-b", "incident recovery", "INC-E05");
        assertEquals("MANUAL_REISSUE_DELIVERY_FORBIDDEN", assertThrows(RuntimeException.class,
                () -> service.reissueManually(request, EXPIRES - 2_000L)).getMessage());
        assertArrayEquals(before, sourceSnapshot(dao));
        assertEquals(null, dao.inserted);
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                ackService(dao, enabledGate()).acknowledge(
                        ack("late-real-terminal", "SUCCEEDED", M1), EXPIRES - 1_000L).kind());
    }

    @Test
    void deferralReadbackRequiresExactScopeAndUnpoisonedLockedSource() {
        List<java.util.function.Consumer<RecordingDao>> poisons = List.of(
                dao -> dao.delivery.setTenantId("tenant-other"),
                dao -> dao.delivery.setClientId("client-other"),
                dao -> dao.delivery.setTargetAgentId("agent-other"),
                dao -> dao.delivery.setId(99L),
                dao -> dao.delivery.setTaskId("task-other"),
                dao -> dao.delivery.setWorkItemId("work-other"),
                dao -> dao.outbox.setWirePayloadHash(new byte[32]));
        for (var poison : poisons) {
            RecordingDao dao = sentDao();
            setAutomaticReplay(dao, 6, M3, M2, M1);
            poison.accept(dao);
            byte[] before = sourceSnapshot(dao);
            var scan = reissue(dao, new PresenceDispatcher(true))
                    .reissueForReconnect(reconnectScope(), 10, NOW);
            assertEquals(0, scan.reissued());
            assertEquals(List.of(), scan.deferrals());
            assertArrayEquals(before, sourceSnapshot(dao));
            assertEquals(null, dao.inserted);
        }
    }

    private static byte[] sourceSnapshot(RecordingDao dao) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var stream = new java.io.ObjectOutputStream(bytes)) {
                stream.writeObject(dao.delivery);
                stream.writeObject(dao.outbox);
                stream.writeObject(dao.inbox);
                stream.writeObject(dao.parentInbox);
                stream.writeObject(dao.previousAttempts);
            }
            return bytes.toByteArray();
        } catch (java.io.IOException invalidFixture) {
            throw new AssertionError("Cannot snapshot recovery fixture", invalidFixture);
        }
    }

    @Test
    void manualRecoveryEntryPointHonorsBackoffAndTotalAttemptBudgetWithoutMutation() {
        RecordingDao deferred = manualSourceDao(1);
        deferred.delivery.setUpdateTime(NOW - 29_999L);
        AgentCommandOperationRequest request = new AgentCommandOperationRequest(
                "tenant-a", "client-a", 1L, "task-1", "agent-a", M1,
                "operator-a", "approver-b", "incident recovery", "INC-42");

        assertEquals("MANUAL_REISSUE_POLICY_DEFER", assertThrows(RuntimeException.class,
                () -> reissue(deferred, new PresenceDispatcher(false))
                        .reissueManually(request, NOW)).getMessage());
        assertFalse(deferred.operations.contains("manualReissue"));
        assertEquals(null, deferred.inserted);

        RecordingDao exhausted = manualSourceDao(
                AgentCommandRecoveryPolicy.DEFAULT_MAX_TOTAL_ATTEMPTS);
        exhausted.delivery.setUpdateTime(NOW - 60_000L);
        assertEquals("MANUAL_REISSUE_POLICY_ATTEMPTS_EXHAUSTED", assertThrows(RuntimeException.class,
                () -> reissue(exhausted, new PresenceDispatcher(false))
                        .reissueManually(request, NOW)).getMessage());
        assertFalse(exhausted.operations.contains("manualReissue"));
        assertEquals(null, exhausted.inserted);
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
    void sentRecoveryUsesReconnectImmediatelyAndSchedulerOnlyAfterAckTimeout() {
        RecordingDao reconnect = sentDao();
        reconnect.delivery.setUpdateTime(NOW - 1);
        assertEquals(1, reissue(reconnect, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertEquals(M1, reconnect.parentMessageId);
        assertEquals(COMMAND_ID, reconnect.inserted.getCommandId());
        assertNotEquals(M1, reconnect.inserted.getMessageId());

        RecordingDao fresh = sentDao();
        fresh.delivery.setUpdateTime(NOW - 1);
        assertEquals(0, reissue(fresh, new PresenceDispatcher(true))
                .reissueDue(10, 0, NOW).reissued());
        assertFalse(fresh.operations.contains("reissue"));

        RecordingDao stale = sentDao();
        stale.delivery.setUpdateTime(NOW - 30_001L);
        assertEquals(1, reissue(stale, new PresenceDispatcher(true))
                .reissueDue(10, 0, NOW).reissued());
        assertEquals(AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER,
                stale.inserted.getReplayRequesterId());
    }

    @Test
    void expiredWaitingIsAtomicallyTerminalizedWithoutRegisteredPresence() {
        RecordingDao expired = waitingDao();
        AgentCommandReissueScanResult result = reissue(
                expired, new PresenceDispatcher(false)).reissueDue(10, 0, EXPIRES);

        assertEquals(1, result.examined());
        assertEquals(0, result.reissued());
        assertEquals("EXPIRED", expired.delivery.getStatus());
        assertEquals("EXPIRED", expired.inbox.getStatus());
        assertEquals("EXPIRED", expired.inbox.getResultStatus());
        assertEquals(List.of("delivery", "outbox", "inbox",
                "expireDelivery", "expireInbox"), expired.operations);
        assertEquals(null, expired.inserted);
    }

    @Test
    void expiredSentIsTerminalizedWithoutReplayAndPreservesSentInboxEvidence() {
        RecordingDao expired = sentDao();
        AgentCommandReissueScanResult result = reissue(
                expired, new PresenceDispatcher(false)).reissueDue(10, 0, EXPIRES);

        assertEquals(1, result.examined());
        assertEquals(0, result.reissued());
        assertEquals("EXPIRED", expired.delivery.getStatus());
        assertEquals("PROCESSED", expired.inbox.getStatus());
        assertEquals("SENT", expired.inbox.getResultStatus());
        assertTrue(expired.operations.contains("expireDelivery"));
        assertFalse(expired.operations.contains("expireInbox"));
        assertEquals(null, expired.inserted);
    }

    @Test
    void replayParentMustExistAndBeTheImmediatePriorTransportAttempt() {
        RecordingDao nonexistent = waitingDao();
        setTransportAttempt(nonexistent, 2);
        replayAudit(nonexistent, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        nonexistent.previousAttempts = List.of();
        assertEquals(0, reissue(nonexistent, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertFalse(nonexistent.operations.contains("reissue"));

        RecordingDao skipped = waitingDao();
        setTransportAttempt(skipped, 3);
        replayAudit(skipped, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        skipped.previousAttempts.getFirst().setActiveAttempt(1);
        assertEquals(0, reissue(skipped, new PresenceDispatcher(true))
                .reissueForReconnect(reconnectScope(), 10, NOW).reissued());
        assertFalse(skipped.operations.contains("reissue"));
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
    void ackRejectsSettledSentAndTerminalPriorDispositionDriftWithoutMutation() {
        for (java.util.function.Consumer<RecordingDao> poison
                : List.<java.util.function.Consumer<RecordingDao>>of(
                        dao -> dao.delivery.setNextRetryAt(NOW + 1),
                        dao -> dao.delivery.setLastError("poison"))) {
            RecordingDao dao = sentDao();
            poison.accept(dao);
            Long poisonedNextRetryAt = dao.delivery.getNextRetryAt();
            String poisonedLastError = dao.delivery.getLastError();

            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(dao, enabledGate()).acknowledge(
                            ack("ack-poisoned-sent", "RECEIVED", M1), NOW));
            assertEquals("SENT", dao.delivery.getStatus());
            assertEquals(poisonedNextRetryAt, dao.delivery.getNextRetryAt());
            assertEquals(poisonedLastError, dao.delivery.getLastError());
            assertEquals(0, dao.ackMutations);
        }

        for (String terminal : List.of("SUCCEEDED", "FAILED", "REJECTED")) {
            String validLastError = switch (terminal) {
                case "FAILED" -> AgentCommandAckServiceImpl.AGENT_REPORTED_FAILED;
                case "REJECTED" -> AgentCommandAckServiceImpl.AGENT_REPORTED_REJECTED;
                default -> null;
            };
            String invalidLastError = switch (terminal) {
                case "FAILED" -> null;
                case "REJECTED" -> AgentCommandAckServiceImpl.AGENT_REPORTED_FAILED;
                default -> "poison";
            };

            RecordingDao retryDrift = sentDao();
            retryDrift.delivery.setStatus(terminal).setLastError(validLastError)
                    .setNextRetryAt(NOW + 1);
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(retryDrift, enabledGate()).acknowledge(
                            ack("ack-terminal-retry-drift-" + terminal, terminal, M1), NOW));
            assertEquals(terminal, retryDrift.delivery.getStatus());
            assertEquals(NOW + 1, retryDrift.delivery.getNextRetryAt());
            assertEquals(validLastError, retryDrift.delivery.getLastError());
            assertEquals(0, retryDrift.ackMutations);

            RecordingDao errorDrift = sentDao();
            errorDrift.delivery.setStatus(terminal).setLastError(invalidLastError);
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(errorDrift, enabledGate()).acknowledge(
                            ack("ack-terminal-error-drift-" + terminal, terminal, M1), NOW));
            assertEquals(terminal, errorDrift.delivery.getStatus());
            assertEquals(invalidLastError, errorDrift.delivery.getLastError());
            assertEquals(0, errorDrift.ackMutations);
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
                            ack("ack-valid-" + valid.get(1), "SUCCEEDED", M1), NOW).kind());
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
                            ack("ack-invalid", "SUCCEEDED", M1), NOW));
            assertEquals(0, dao.ackMutations);
        }
    }

    @Test
    void ackRequiresAttemptAuditAndKeepsPublishRetryCountIndependent() {
        RecordingDao missingAudit = sentDao();
        setTransportAttempt(missingAudit, 2);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(missingAudit, enabledGate()).acknowledge(
                        ack("ack-missing-audit", "SUCCEEDED", M1), NOW));
        assertEquals(0, missingAudit.ackMutations);

        RecordingDao mismatch = sentDao();
        mismatch.outbox.setActiveAttempt(2);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(mismatch, enabledGate()).acknowledge(
                        ack("ack-attempt-mismatch", "SUCCEEDED", M1), NOW));
        assertEquals(0, mismatch.ackMutations);

        RecordingDao automatic = sentDao();
        setTransportAttempt(automatic, 2);
        replayAudit(automatic, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        automatic.outbox.setAttemptCount(7);
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                ackService(automatic, enabledGate()).acknowledge(
                        ack("ack-automatic", "SUCCEEDED", M1), NOW).kind());
        assertEquals(1, automatic.ackMutations);
    }

    @Test
    void ackRequiresExistingImmediateReplayParentWithoutMutation() {
        RecordingDao missing = sentDao();
        setTransportAttempt(missing, 2);
        replayAudit(missing, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        missing.previousAttempts = List.of();
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(missing, enabledGate()).acknowledge(
                        ack("ack-parent-missing", "SUCCEEDED", M1), NOW));
        assertEquals(0, missing.ackMutations);

        RecordingDao skipped = sentDao();
        setTransportAttempt(skipped, 3);
        replayAudit(skipped, "agent-a", null,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        skipped.previousAttempts.getFirst().setActiveAttempt(1);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(skipped, enabledGate()).acknowledge(
                        ack("ack-parent-skipped", "SUCCEEDED", M1), NOW));
        assertEquals(0, skipped.ackMutations);
    }

    @Test
    void automaticReplayRejectsNonterminalLedgerReplayWithoutMutation() {
        for (String starting : List.of("CONSUMED", "SENT")) {
            for (String nonterminal : List.of("RECEIVED", "STARTED")) {
                RecordingDao replay = sentDao();
                setAutomaticReplay(replay);
                if ("CONSUMED".equals(starting)) {
                    replay.delivery.setStatus("CONSUMED");
                    replay.inbox.setStatus("PROCESSING").setResultStatus(null)
                            .setProcessedAt(null).setLeaseOwner("worker-a")
                            .setLeaseUntil(NOW + 10_000L);
                }
                long version = replay.delivery.getVersion();

                assertThrows(AgentCommandAckRejectedException.class,
                        () -> ackService(replay, enabledGate()).acknowledge(
                                ack("ack-replayed-" + starting + "-" + nonterminal,
                                        nonterminal, M2), NOW));

                assertEquals(starting, replay.delivery.getStatus());
                assertEquals(version, replay.delivery.getVersion());
                assertEquals(null, replay.delivery.getNextRetryAt());
                assertEquals(null, replay.delivery.getLastError());
                assertEquals(0, replay.ackMutations);
            }
        }
    }

    @Test
    void automaticReplayAcceptsOnlyDirectParentTerminalAndFreezesDuplicatesAndConflicts() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "REJECTED")) {
            RecordingDao replay = sentDao();
            setAutomaticReplay(replay);
            AgentCommandAckServiceImpl service = ackService(replay, enabledGate());

            AgentCommandAckResult advanced = service.acknowledge(
                    ack("ack-parent-" + terminal, terminal, M1), NOW);
            assertEquals(AgentCommandAckResult.Kind.ADVANCED, advanced.kind());
            assertEquals(terminal, replay.delivery.getStatus());
            assertEquals(M2, replay.delivery.getActiveMessageId());
            assertEquals(M1, replay.delivery.getReplayParentMessageId());
            assertEquals(1, replay.ackMutations);
            assertEquals(AgentCommandAckResult.Kind.PRIOR,
                    service.acknowledge(
                            ack("ack-parent-duplicate-" + terminal, terminal, M1), NOW).kind());
            String conflict = "SUCCEEDED".equals(terminal) ? "FAILED" : "SUCCEEDED";
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> service.acknowledge(
                            ack("ack-parent-conflict-" + terminal, conflict, M1), NOW));
            assertEquals(1, replay.ackMutations);
        }

        RecordingDao original = sentDao();
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(original, enabledGate()).acknowledge(
                        ack("ack-original-skip", "SUCCEEDED", M1), NOW));
        assertEquals(0, original.ackMutations);
    }

    @Test
    void directParentTerminalRejectsNonAdjacentPoisonExpiryVersionAndWrongIdentity() {
        List<java.util.function.Consumer<RecordingDao>> parentPoison = List.of(
                dao -> dao.parentInbox = null,
                dao -> dao.parentInbox.setLastError("poison"),
                dao -> dao.parentInbox.setLeaseOwner("poison").setLeaseUntil(NOW + 1),
                dao -> dao.parentInbox.setWirePayloadHash(new byte[32]),
                dao -> dao.outbox.setReplayRequesterId("operator"),
                dao -> dao.delivery.setReplayReason("MANUAL_REPLAY"));
        for (var poison : parentPoison) {
            RecordingDao replay = sentDao();
            setAutomaticReplay(replay);
            poison.accept(replay);
            long version = replay.delivery.getVersion();
            assertThrows(AgentCommandAckRejectedException.class,
                    () -> ackService(replay, enabledGate()).acknowledge(
                            ack("ack-parent-poison", "SUCCEEDED", M1), NOW));
            assertEquals("SENT", replay.delivery.getStatus());
            assertEquals(version, replay.delivery.getVersion());
            assertEquals(0, replay.ackMutations);
        }

        RecordingDao nonAdjacent = sentDao();
        setThirdAutomaticReplay(nonAdjacent);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(nonAdjacent, enabledGate()).acknowledge(
                        ack("ack-non-adjacent", "SUCCEEDED", M1), NOW));
        assertEquals(0, nonAdjacent.ackMutations);

        RecordingDao expired = sentDao();
        setAutomaticReplay(expired);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(expired, enabledGate()).acknowledge(
                        ack("ack-parent-expired", "SUCCEEDED", M1), EXPIRES));
        assertEquals(0, expired.ackMutations);

        RecordingDao exhausted = sentDao();
        setAutomaticReplay(exhausted);
        exhausted.delivery.setVersion(Long.MAX_VALUE - 2);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(exhausted, enabledGate()).acknowledge(
                        ack("ack-parent-version", "SUCCEEDED", M1), NOW));
        assertEquals(0, exhausted.ackMutations);

        RecordingDao wrongAgent = sentDao();
        setAutomaticReplay(wrongAgent);
        AgentCommandAck wrongIdentity = new AgentCommandAck(
                "tenant-a", "client-a", "agent-other", "ack-wrong-agent", M1,
                COMMAND_ID, "task-1", null, "SUCCEEDED", NOW);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(wrongAgent, enabledGate()).acknowledge(wrongIdentity, NOW));
        assertEquals(0, wrongAgent.ackMutations);

        RecordingDao shadow = sentDao();
        setAutomaticReplay(shadow);
        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(shadow, dbShadowGate()).acknowledge(
                        ack("ack-db-shadow", "SUCCEEDED", M1), NOW));
        assertEquals(List.of(), shadow.operations);
    }

    @Test
    void receivedStartedAndTerminalBeforeSentCompletionAdvanceOnlyDelivery() {
        RecordingDao dao = sourceDao();
        dao.delivery.setStatus("CONSUMED").setNextRetryAt(null).setLastError(null);
        dao.inbox.setStatus("PROCESSING").setResultStatus(null)
                .setProcessedAt(null).setNextRetryAt(null).setLastError(null)
                .setLeaseOwner("worker-a").setLeaseUntil(NOW + 10_000L);
        AgentCommandAckServiceImpl service = ackService(dao, enabledGate());

        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-fast-received", "RECEIVED", M1), NOW).kind());
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-fast-started", "STARTED", M1), NOW).kind());
        assertEquals(AgentCommandAckResult.Kind.ADVANCED,
                service.acknowledge(ack("ack-fast-terminal", "SUCCEEDED", M1), NOW).kind());

        assertEquals("SUCCEEDED", dao.delivery.getStatus());
        assertEquals("PROCESSING", dao.inbox.getStatus());
        assertEquals(3, dao.ackMutations);
    }

    @Test
    void expiredSentRejectsLateReceivedBeforeSourceLocksAndStillExpires() {
        RecordingDao dao = sentDao();
        long version = dao.delivery.getVersion();

        assertThrows(AgentCommandAckRejectedException.class,
                () -> ackService(dao, enabledGate()).acknowledge(
                        ack("ack-expired-received", "RECEIVED", M1), EXPIRES));

        assertEquals("SENT", dao.delivery.getStatus());
        assertEquals(version, dao.delivery.getVersion());
        assertEquals(0, dao.ackMutations);
        assertEquals(List.of("delivery"), dao.operations);

        AgentCommandReissueScanResult recovery = reissue(
                dao, new PresenceDispatcher(false)).reissueDue(10, 0, EXPIRES);
        assertEquals(1, recovery.examined());
        assertEquals(0, recovery.reissued());
        assertEquals("EXPIRED", dao.delivery.getStatus());
        assertFalse(dao.operations.contains("ack"));
    }

    @Test
    void expiredExactTerminalDuplicateRemainsPriorWithoutMutation() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "REJECTED")) {
            RecordingDao dao = sentDao();
            AgentCommandAckServiceImpl service = ackService(dao, enabledGate());
            service.acknowledge(ack("ack-received-" + terminal, "RECEIVED", M1), NOW);
            service.acknowledge(ack("ack-started-" + terminal, "STARTED", M1), NOW);
            service.acknowledge(ack("ack-terminal-" + terminal, terminal, M1), NOW);
            long version = dao.delivery.getVersion();

            AgentCommandAckResult duplicate = service.acknowledge(
                    ack("ack-expired-duplicate-" + terminal, terminal, M1), EXPIRES);

            assertEquals(AgentCommandAckResult.Kind.PRIOR, duplicate.kind());
            assertEquals(terminal, dao.delivery.getStatus());
            assertEquals(version, dao.delivery.getVersion());
            assertEquals(3, dao.ackMutations);
        }
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

    private static RecordingDao manualSourceDao(int attempt) {
        RecordingDao dao = sourceDao();
        dao.delivery.setStatus("DEAD").setNextRetryAt(null).setLastError("BROKER_DEAD");
        dao.inbox.setStatus("DEAD").setResultStatus("DEAD").setNextRetryAt(null)
                .setLeaseOwner(null).setLeaseUntil(null).setProcessedAt(NOW - 1)
                .setLastError("BROKER_DEAD");
        if (attempt > 1) {
            setTransportAttempt(dao, attempt);
            replayAudit(dao, "operator-a", "approver-b", "incident recovery");
        }
        return dao;
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
        delivery.setUpdateTime(NOW - 100_000L);

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

    private static RecordingDao withDraft(
            RecordingDao dao, AgentCommandDraft draft) {
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, M1, 1);
        byte[] businessHash = AgentCommandCanonicalCodec.sha256(business);
        byte[] wireHash = AgentCommandCanonicalCodec.sha256(wire);
        dao.delivery.setCommandId(draft.commandId()).setTaskId(draft.taskId())
                .setWorkItemId(draft.workItemId()).setTargetAgentId(draft.targetAgentId())
                .setCommandType(draft.commandType()).setCommandPayload(business)
                .setCommandPayloadHash(businessHash).setExpiresAt(draft.expiresAt());
        dao.outbox.setCommandId(draft.commandId()).setAggregateId(draft.taskId())
                .setWirePayload(wire).setWirePayloadHash(wireHash)
                .setExpiresAt(draft.expiresAt());
        dao.inbox.setCommandId(draft.commandId()).setWirePayload(wire)
                .setWirePayloadHash(wireHash).setExpiresAt(draft.expiresAt());
        return dao;
    }

    private static AgentCommandDraft hallDraft(String actionType, String commandType) {
        String intentId = "intent-d06-" + actionType;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                "tenant-a", "client-a", "task-1", "agent-a", intentId, commandType);
        return new AgentCommandDraft(
                1, commandId, "task-1", intentId,
                "tenant-a", "client-a", "task-1", "work-1", "agent-a",
                commandType, ISSUED,
                ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS,
                intentId, new AgentHallCommandPayload(
                        actionType, "执行聚义厅命令并回报结果", "juyiting",
                        null, null, null, null, false, null));
    }

    private static void setTransportAttempt(RecordingDao dao, int attempt) {
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft(), M1, attempt);
        byte[] wireHash = AgentCommandCanonicalCodec.sha256(wire);
        dao.delivery.setAttemptCount(attempt).setActiveAttempt(attempt);
        dao.outbox.setActiveAttempt(attempt).setWirePayload(wire).setWirePayloadHash(wireHash);
        dao.inbox.setAttemptCount(attempt).setActiveAttempt(attempt)
                .setWirePayload(wire).setWirePayloadHash(wireHash);
        if (attempt > 1) {
            String parentMessageId = "parent-message";
            byte[] parentWire = AgentCommandCanonicalCodec.wireBytes(
                    draft(), parentMessageId, attempt - 1);
            AgentOutboxEventEntity parent = new AgentOutboxEventEntity()
                    .setId(9L).setEventId("parent-event").setMessageId(parentMessageId)
                    .setCommandId(COMMAND_ID).setDeliveryId(1L)
                    .setAggregateType("task").setAggregateId("task-1")
                    .setDestination(dao.outbox.getDestination())
                    .setRoutingKey(dao.outbox.getRoutingKey())
                    .setWirePayload(parentWire)
                    .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(parentWire))
                    .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(attempt - 1)
                    .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK")
                    .setConfirmedAt(NOW - 20).setMandatoryReturnStatus("NOT_RETURNED")
                    .setPublishedAt(NOW - 19).setVersion(2L);
            parent.setTenantId("tenant-a");
            parent.setClientId("client-a");
            if (attempt > 2) {
                parent.setReplayParentMessageId("grandparent-message")
                        .setReplayRequesterId("agent-a")
                        .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
            }
            dao.previousAttempts = List.of(parent);
        }
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

    private static void setAutomaticReplay(RecordingDao dao) {
        setAutomaticReplay(dao, 2, M2, M1, null);
    }

    private static void setThirdAutomaticReplay(RecordingDao dao) {
        setAutomaticReplay(dao, 3, M3, M2, M1);
    }

    private static void setAutomaticReplay(
            RecordingDao dao, int activeAttempt, String activeMessageId,
            String parentMessageId, String parentParentMessageId) {
        byte[] activeWire = AgentCommandCanonicalCodec.wireBytes(
                draft(), activeMessageId, activeAttempt);
        byte[] parentWire = AgentCommandCanonicalCodec.wireBytes(
                draft(), parentMessageId, activeAttempt - 1);
        String activeEvent = "event-active-" + activeAttempt;
        String parentEvent = "event-parent-" + (activeAttempt - 1);

        dao.delivery.setAttemptCount(activeAttempt).setActiveAttempt(activeAttempt)
                .setActiveMessageId(activeMessageId)
                .setReplayParentMessageId(parentMessageId)
                .setReplayRequesterId("agent-a").setReplayApproverId(null)
                .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        dao.outbox.setEventId(activeEvent).setMessageId(activeMessageId)
                .setActiveAttempt(activeAttempt)
                .setWirePayload(activeWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(activeWire))
                .setReplayParentMessageId(parentMessageId)
                .setReplayRequesterId("agent-a").setReplayApproverId(null)
                .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        dao.inbox.setMessageId(activeMessageId).setEventId(activeEvent)
                .setWirePayload(activeWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(activeWire));

        AgentOutboxEventEntity parent = new AgentOutboxEventEntity()
                .setId(9L).setEventId(parentEvent).setMessageId(parentMessageId)
                .setCommandId(COMMAND_ID).setDeliveryId(1L)
                .setAggregateType("task").setAggregateId("task-1")
                .setDestination(dao.outbox.getDestination()).setRoutingKey(dao.outbox.getRoutingKey())
                .setWirePayload(parentWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(parentWire))
                .setStatus("PUBLISHED").setAttemptCount(1).setActiveAttempt(activeAttempt - 1)
                .setExpiresAt(EXPIRES).setPublisherConfirmStatus("ACK")
                .setConfirmedAt(NOW - 20).setMandatoryReturnStatus("NOT_RETURNED")
                .setPublishedAt(NOW - 19).setVersion(2L);
        parent.setTenantId("tenant-a");
        parent.setClientId("client-a");
        if (parentParentMessageId != null) {
            parent.setReplayParentMessageId(parentParentMessageId)
                    .setReplayRequesterId("agent-a").setReplayApproverId(null)
                    .setReplayReason(AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT);
        }
        dao.previousAttempts = List.of(parent);

        dao.parentInbox = new AgentConsumerInboxEntity()
                .setId(19L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1)
                .setMessageId(parentMessageId).setEventId(parentEvent)
                .setCommandId(COMMAND_ID).setDeliveryId(1L)
                .setWirePayload(parentWire)
                .setWirePayloadHash(AgentCommandCanonicalCodec.sha256(parentWire))
                .setStatus("PROCESSED").setResultStatus("SENT")
                .setAttemptCount(1).setActiveAttempt(1).setExpiresAt(EXPIRES)
                .setProcessedAt(NOW - 18).setVersion(1L);
        dao.parentInbox.setTenantId("tenant-a");
        dao.parentInbox.setClientId("client-a");
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

    private static AgentRabbitSafetyGate dbShadowGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(false),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false),
                null));
    }

    private static AgentRabbitSafetyGate offGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                null, null, null, null, null, null));
    }

    private record ActionType(String actionType, String commandType) {
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
        private AgentConsumerInboxEntity parentInbox;
        private final List<String> operations = new ArrayList<>();
        private int discoveryCalls;
        private int reissueRows = 1;
        private int ackRows = 1;
        private int ackMutations;
        private String newMessageId;
        private String parentMessageId;
        private String requestedBy;
        private String approverId;
        private String reason;
        private AgentOutboxEventEntity inserted;
        private List<AgentOutboxEventEntity> previousAttempts = List.of();
        private int expireDeliveryRows = 1;
        private int expireInboxRows = 1;

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
        public List<AgentWaitingCommandCandidate> findDueCandidates(
                long now, long sentBefore, long afterDeliveryId, int limit) {
            discoveryCalls++;
            return List.of(new AgentWaitingCommandCandidate(
                    1, delivery.getTenantId(), delivery.getClientId(), delivery.getTargetAgentId(),
                    delivery.getExpiresAt() <= now));
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
        public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
                String tenantId, String clientId, long deliveryId, int previousAttempt) {
            operations.add("previous");
            return previousAttempts;
        }

        @Override
        public AgentConsumerInboxEntity lockInbox(
                String tenantId, String clientId, String consumerName, String messageId) {
            operations.add("inbox");
            if (messageId.equals(inbox.getMessageId())) return inbox;
            if (parentInbox != null && messageId.equals(parentInbox.getMessageId())) {
                return parentInbox;
            }
            return null;
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
        public int manualReissueDelivery(
                AgentCommandDeliveryEntity delivery, String newMessageId,
                String requestedBy, String approverId, String reason,
                String lastError, long now) {
            operations.add("manualReissue");
            this.newMessageId = newMessageId;
            this.parentMessageId = delivery.getActiveMessageId();
            this.requestedBy = requestedBy;
            this.approverId = approverId;
            this.reason = reason;
            return reissueRows;
        }

        @Override
        public int expireDelivery(
                AgentCommandDeliveryEntity delivery, String lastError, long now) {
            operations.add("expireDelivery");
            if (expireDeliveryRows == 1) {
                delivery.setStatus("EXPIRED").setNextRetryAt(null).setLastError(lastError)
                        .setVersion(delivery.getVersion() + 1);
            }
            return expireDeliveryRows;
        }

        @Override
        public int expireWaitingInbox(
                AgentConsumerInboxEntity inbox, String lastError, long now) {
            operations.add("expireInbox");
            if (expireInboxRows == 1) {
                inbox.setStatus("EXPIRED").setResultStatus("EXPIRED").setNextRetryAt(null)
                        .setProcessedAt(now).setLastError(lastError)
                        .setVersion(inbox.getVersion() + 1);
            }
            return expireInboxRows;
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
