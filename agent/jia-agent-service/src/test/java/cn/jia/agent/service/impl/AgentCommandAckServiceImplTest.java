package cn.jia.agent.service.impl;

import cn.jia.agent.config.*;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Expiry narrows admission, never authorizes replay or skips D06 provenance/lease/CAS. */
class AgentCommandAckServiceImplTest {
    static final long NOW = 1_001_000, EXPIRES = 3_601_000;
    static final String MESSAGE = "original-message", INTENT = "original-intent";
    static final String COMMAND = AgentCommandCanonicalCodec.hallCommandId(
            "0", "client", "owner", "task", "agent", INTENT, "TASK_INVITE");
    static class Fixture {
        final AgentCommandRecoveryDao dao = mock(AgentCommandRecoveryDao.class);
        final AgentCommandDeliveryEntity delivery;
        final AgentOutboxEventEntity outbox;
        final AgentConsumerInboxEntity inbox;
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final AgentCommandAckServiceImpl service;
        Fixture(String status) { this(status,defaultDraft(),MESSAGE); }
        private static AgentCommandDraft defaultDraft() {
            return new AgentCommandDraft(1, COMMAND, "task", INTENT, "0", "client", "owner", "task", null,
                    "agent", "TASK_INVITE", 1000, EXPIRES, INTENT,
                    new AgentHallCommandPayload("task_briefing", "fixture instruction", "juyiting", null, null, null, "assist", false, null));
        }
        Fixture(String status,AgentCommandDraft draft,String message) {
            byte[] business = AgentCommandCanonicalCodec.businessBytes(draft), wire = AgentCommandCanonicalCodec.wireBytes(draft, message, 1);
            delivery = new AgentCommandDeliveryEntity().setId(1L).setOwnerJiacn(draft.ownerJiacn()).setCommandId(draft.commandId()).setTaskId(draft.taskId())
                    .setTargetAgentId(draft.targetAgentId()).setWorkItemId(draft.workItemId()).setCommandType(draft.commandType()).setCommandPayload(business)
                    .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(business)).setAttemptCount(1)
                    .setActiveMessageId(message).setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setVersion(7L).setStatus(status);
            delivery.setTenantId(draft.tenantId()); delivery.setClientId(draft.clientId());
            var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
            outbox = new AgentOutboxEventEntity().setId(10L).setEventId("event").setMessageId(message).setCommandId(draft.commandId())
                    .setDeliveryId(1L).setAggregateType("task").setAggregateId(draft.taskId()).setDestination(route.destination()).setRoutingKey(route.routingKey())
                    .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire)).setStatus("PUBLISHED")
                    .setAttemptCount(1).setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setPublisherConfirmStatus("ACK")
                    .setConfirmedAt(NOW - 10).setMandatoryReturnStatus("NOT_RETURNED").setPublishedAt(NOW - 9).setVersion(2L);
            outbox.setTenantId(draft.tenantId()); outbox.setClientId(draft.clientId());
            inbox = new AgentConsumerInboxEntity().setId(20L).setConsumerName(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1)
                    .setMessageId(message).setEventId("event").setCommandId(draft.commandId()).setDeliveryId(1L)
                    .setWirePayload(wire).setWirePayloadHash(AgentCommandCanonicalCodec.sha256(wire))
                    .setAttemptCount(1).setActiveAttempt(1).setExpiresAt(draft.expiresAt()).setProcessedAt(NOW - 2).setVersion(1L)
                    .setStatus("PROCESSED").setResultStatus("SENT");
            inbox.setTenantId(draft.tenantId()); inbox.setClientId(draft.clientId());
            when(dao.lockDeliveryByCommand(draft.tenantId(), draft.clientId(), draft.commandId())).thenReturn(delivery);
            when(dao.lockActiveOutboxes(draft.tenantId(), draft.clientId(), 1L, message)).thenReturn(List.of(outbox));
            when(dao.lockInbox(draft.tenantId(), draft.clientId(), AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, message)).thenReturn(inbox);
            when(dao.advanceAck(any(), anyString(), any(), anyLong())).thenReturn(1);
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            var gate = mock(AgentRabbitSafetyGate.class);
            when(gate.state()).thenReturn(AgentRabbitActivationState.DISPATCH_SCOPED);
            when(gate.allowsDispatch(draft.tenantId(), draft.clientId())).thenReturn(true);
            service = new AgentCommandAckServiceImpl(dao, gate, manager);
        }
        AgentCommandAck ack(String status) { return new AgentCommandAck(delivery.getTenantId(), delivery.getClientId(),
                delivery.getTargetAgentId(), "independent-ack", delivery.getActiveMessageId(), delivery.getCommandId(),
                delivery.getTaskId(), delivery.getWorkItemId(), status, delivery.getExpiresAt()); }
    }
    @Test void expiredStartedCanReportEachTerminalAndReturnCommittedVersion() {
        for (String terminal : List.of("SUCCEEDED", "FAILED", "REJECTED")) {
            var f = new Fixture("STARTED"); var result = f.service.acknowledge(f.ack(terminal), EXPIRES);
            assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind()); assertEquals(terminal, result.status());
            assertEquals(8, result.deliveryVersion());
            verify(f.dao).advanceAck(eq(f.delivery), eq(terminal), any(), eq(EXPIRES)); verify(f.manager).commit(any());
            assertEquals(MESSAGE, f.delivery.getActiveMessageId()); assertEquals(COMMAND, f.delivery.getCommandId());
        }
    }
    @Test void expiredNewReceivedOrStartedAdmissionNeverMutates() {
        for (String stored : List.of("SENT", "RECEIVED", "STARTED")) {
            for (String requested : List.of("RECEIVED", "STARTED")) {
                var f = new Fixture(stored);
                assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack(requested), EXPIRES));
                verify(f.dao, never()).advanceAck(any(), anyString(), any(), anyLong()); verify(f.manager).rollback(any());
            }
        }
    }
    @Test void expiredWithoutStartedEvidenceCannotFirstReportTerminal() {
        for (String stored : List.of("SENT", "RECEIVED")) {
            var f = new Fixture(stored);
            assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES));
            verify(f.dao, never()).advanceAck(any(), anyString(), any(), anyLong());
        }
    }
    @Test void expiredTerminalExactDuplicateRemainsPriorButConflictIsRejected() {
        var f = new Fixture("SUCCEEDED");
        var prior = f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES);
        assertEquals(AgentCommandAckResult.Kind.PRIOR, prior.kind()); assertEquals(7, prior.deliveryVersion());
        assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack("FAILED"), EXPIRES));
        verify(f.dao, never()).advanceAck(any(), anyString(), any(), anyLong());
    }
    @Test void expiredStartedProcessingLaneStillRequiresUnexpiredInboxLease() {
        var f = new Fixture("STARTED");
        f.inbox.setStatus("PROCESSING").setResultStatus(null).setProcessedAt(null).setLeaseOwner("worker").setLeaseUntil(EXPIRES + 1);
        assertEquals(AgentCommandAckResult.Kind.ADVANCED, f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES).kind());
        clearInvocations(f.dao); f.inbox.setLeaseUntil(EXPIRES);
        assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES));
        verify(f.dao, never()).advanceAck(any(), anyString(), any(), anyLong());
    }
    @Test void expiredStartedNeverBypassesCanonicalHashFullSubjectOrSourceEvidence() {
        for (int variant = 0; variant < 4; variant++) {
            var f = new Fixture("STARTED");
            if (variant == 0) f.outbox.setWirePayloadHash(new byte[32]);
            if (variant == 1) f.inbox.setClientId("other-client");
            if (variant == 2) f.delivery.setTargetAgentId("other-agent");
            if (variant == 3) f.outbox.setPublisherConfirmStatus("NACK");
            assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES));
            verify(f.dao, never()).advanceAck(any(), anyString(), any(), anyLong());
        }
    }
    @Test void terminalCasLostStillRollsBackRatherThanClaimingCommit() {
        var f = new Fixture("STARTED"); when(f.dao.advanceAck(any(), anyString(), any(), anyLong())).thenReturn(0);
        assertThrows(AgentCommandAckRejectedException.class, () -> f.service.acknowledge(f.ack("SUCCEEDED"), EXPIRES));
        verify(f.manager).rollback(any()); verify(f.manager, never()).commit(any());
    }
}
