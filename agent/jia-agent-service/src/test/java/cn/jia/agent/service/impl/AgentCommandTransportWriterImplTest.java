package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandTransportWriterImplTest {
    private AgentCommandTransportDao dao;
    private PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        dao = mock(AgentCommandTransportDao.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void dbShadowCreatesTerminalDeliveryAndOutboxWithIndependentTransportIds() {
        AtomicInteger sequence = new AtomicInteger();
        AgentCommandTransportWriterImpl writer = new AgentCommandTransportWriterImpl(
                dao, gate(AgentRabbitActivationState.DB_SHADOW, false), transactions,
                () -> new UUID(0, sequence.incrementAndGet()));
        when(dao.insertDelivery(any())).thenAnswer(invocation -> {
            invocation.<AgentCommandDeliveryEntity>getArgument(0).setId(41L);
            return 1;
        });
        when(dao.insertOutbox(any())).thenReturn(1);

        var result = writer.write(draft("Task One"));

        assertFalse(result.duplicate());
        assertEquals("00000000-0000-0000-0000-000000000001", result.messageId());
        assertEquals("00000000-0000-0000-0000-000000000002", result.outboxEventId());
        ArgumentCaptor<AgentCommandDeliveryEntity> delivery =
                ArgumentCaptor.forClass(AgentCommandDeliveryEntity.class);
        ArgumentCaptor<AgentOutboxEventEntity> outbox =
                ArgumentCaptor.forClass(AgentOutboxEventEntity.class);
        verify(dao).insertDelivery(delivery.capture());
        verify(dao).insertOutbox(outbox.capture());
        assertEquals("DEAD", delivery.getValue().getStatus());
        assertEquals(AgentCommandTransportWriterImpl.DB_SHADOW_MARKER,
                delivery.getValue().getLastError());
        assertEquals("DEAD", outbox.getValue().getStatus());
        assertEquals(AgentCommandTransportWriterImpl.DB_SHADOW_MARKER,
                outbox.getValue().getLastError());
        assertEquals(41L, outbox.getValue().getDeliveryId());
        assertEquals(1, delivery.getValue().getActiveAttempt());
        assertEquals(0, outbox.getValue().getAttemptCount());
        String wire = new String(outbox.getValue().getWirePayload(), StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"messageId\":\"" + result.messageId() + "\""));
        assertFalse(wire.contains("\"eventId\""));
        assertFalse(wire.contains("\"deliveryId\""));
    }

    @Test
    void exactDuplicateReturnsExistingDeliveryAndAddsNoOutbox() {
        AgentCommandDraft draft = draft("Task One");
        byte[] bytes = AgentCommandCanonicalCodec.businessBytes(draft);
        AgentCommandDeliveryEntity existing = existing(draft, bytes);
        when(dao.lockDelivery(draft.tenantId(), draft.clientId(), draft.commandId()))
                .thenReturn(existing);
        AgentCommandTransportWriterImpl writer = writer();

        var result = writer.write(draft);

        assertTrue(result.duplicate());
        assertEquals(77L, result.deliveryId());
        assertNull(result.outboxEventId());
        verify(dao, never()).insertDelivery(any());
        verify(dao, never()).insertOutbox(any());
    }

    @Test
    void sameCommandIdWithDifferentCanonicalBytesFailsClosed() {
        AgentCommandDraft requested = draft("Task One");
        AgentCommandDraft conflicting = draft("Other title");
        when(dao.lockDelivery(requested.tenantId(), requested.clientId(), requested.commandId()))
                .thenReturn(existing(conflicting,
                        AgentCommandCanonicalCodec.businessBytes(conflicting)));

        assertThrows(IllegalStateException.class, () -> writer().write(requested));
        verify(dao, never()).insertDelivery(any());
        verify(dao, never()).insertOutbox(any());
    }

    @Test
    void hallRetryAtLaterServerTimeReturnsPriorWithoutNewRows() {
        AgentCommandDraft stored = hallDraft(1_000L, "执行工作项并回报结果");
        AgentCommandDraft retry = hallDraft(9_000L, "执行工作项并回报结果");
        byte[] storedBytes = AgentCommandCanonicalCodec.businessBytes(stored);
        when(dao.lockDelivery(retry.tenantId(), retry.clientId(), retry.commandId()))
                .thenReturn(existing(stored, storedBytes));

        var result = writer().write(retry);

        assertTrue(result.duplicate());
        assertEquals(77L, result.deliveryId());
        verify(dao, never()).insertDelivery(any());
        verify(dao, never()).insertOutbox(any());
    }

    @Test
    void hallRetryWithChangedPayloadConflictsWithoutOverwrite() {
        AgentCommandDraft stored = hallDraft(1_000L, "执行工作项并回报结果");
        AgentCommandDraft changed = hallDraft(9_000L, "执行工作项并回报不同结果");
        byte[] storedBytes = AgentCommandCanonicalCodec.businessBytes(stored);
        when(dao.lockDelivery(changed.tenantId(), changed.clientId(), changed.commandId()))
                .thenReturn(existing(stored, storedBytes));

        assertThrows(IllegalStateException.class, () -> writer().write(changed));
        verify(dao, never()).insertDelivery(any());
        verify(dao, never()).insertOutbox(any());
    }

    @Test
    void mqShadowCreatesTerminalCaptureOnlyRowsAndNoDispatchBacklog() {
        assertAdmission(gate(AgentRabbitActivationState.MQ_SHADOW, false),
                "DEAD", AgentCommandTransportWriterImpl.MQ_SHADOW_MARKER);
    }

    @Test
    void dispatchExactAllowedScopeCreatesMarkedPendingRows() {
        assertAdmission(gate(AgentRabbitActivationState.DISPATCH_CANARY, true),
                "PENDING", AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER);
    }

    @Test
    void dispatchOutsideExactScopeCreatesTerminalCaptureOnlyRows() {
        assertAdmission(gate(AgentRabbitActivationState.DISPATCH_CANARY, false),
                "DEAD", AgentCommandTransportWriterImpl.DISPATCH_SCOPE_MARKER);
    }

    @Test
    void offFailsBeforeAnyDatabaseAccess() {
        AgentCommandTransportWriterImpl writer = new AgentCommandTransportWriterImpl(
                dao, gate(AgentRabbitActivationState.OFF, false), transactions,
                () -> new UUID(0, 1));

        assertThrows(IllegalStateException.class, () -> writer.write(draft("Task One")));
        verify(dao, never()).lockDelivery(any(), any(), any());
        verify(dao, never()).insertDelivery(any());
        verify(dao, never()).insertOutbox(any());
    }

    private AgentCommandTransportWriterImpl writer() {
        return new AgentCommandTransportWriterImpl(
                dao, gate(AgentRabbitActivationState.DB_SHADOW, false),
                transactions, () -> new UUID(0, 1));
    }

    private void assertAdmission(
            AgentRabbitSafetyGate gate, String status, String marker) {
        when(dao.insertDelivery(any())).thenAnswer(invocation -> {
            invocation.<AgentCommandDeliveryEntity>getArgument(0).setId(9L);
            return 1;
        });
        when(dao.insertOutbox(any())).thenReturn(1);
        AgentCommandTransportWriterImpl writer = new AgentCommandTransportWriterImpl(
                dao, gate, transactions, () -> new UUID(0, 1));

        writer.write(draft("Task One"));

        ArgumentCaptor<AgentCommandDeliveryEntity> delivery =
                ArgumentCaptor.forClass(AgentCommandDeliveryEntity.class);
        ArgumentCaptor<AgentOutboxEventEntity> outbox =
                ArgumentCaptor.forClass(AgentOutboxEventEntity.class);
        verify(dao).insertDelivery(delivery.capture());
        verify(dao).insertOutbox(outbox.capture());
        assertEquals(status, delivery.getValue().getStatus());
        assertEquals(marker, delivery.getValue().getLastError());
        assertEquals(status, outbox.getValue().getStatus());
        assertEquals(marker, outbox.getValue().getLastError());
        var route = cn.jia.agent.config.AgentRabbitTopologyManifest.canonical()
                .defaultCommandPublishRoute();
        assertEquals(route.destination(), outbox.getValue().getDestination());
        assertEquals(route.routingKey(), outbox.getValue().getRoutingKey());
    }

    private AgentCommandDeliveryEntity existing(AgentCommandDraft draft, byte[] bytes) {
        AgentCommandDeliveryEntity entity = new AgentCommandDeliveryEntity()
                .setId(77L).setCommandId(draft.commandId()).setTaskId(draft.taskId())
                .setWorkItemId(draft.workItemId()).setTargetAgentId(draft.targetAgentId())
                .setCommandType(draft.commandType()).setCommandPayload(bytes)
                .setCommandPayloadHash(AgentCommandCanonicalCodec.sha256(bytes))
                .setExpiresAt(draft.expiresAt()).setActiveMessageId("existing-message");
        entity.setTenantId(draft.tenantId());
        entity.setClientId(draft.clientId());
        return entity;
    }

    private AgentCommandDraft hallDraft(long issuedAt, String instruction) {
        String intentId = "intent-hall-1";
        String commandType = AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                "tenant-a", "client-a", "task-1", "agent-1", intentId, commandType);
        return new AgentCommandDraft(
                1, commandId, "task-1", intentId, "tenant-a", "client-a", "task-1",
                "work-1", "agent-1", commandType, issuedAt,
                issuedAt + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, intentId,
                new AgentHallCommandPayload(
                        "execute", instruction, "juyiting", null, null,
                        null, null, false, null));
    }

    private AgentCommandDraft draft(String title) {
        String commandId = AgentCommandCanonicalCodec.taskInviteCommandId(
                "tenant-a", "client-a", "task-1", "agent-1");
        return new AgentCommandDraft(1, commandId, "task-1", "evt-real",
                "tenant-a", "client-a", "task-1", null, "agent-1",
                AgentProtocolConstants.COMMAND_TASK_INVITE, 1000L, 3601000L,
                new AgentTaskInvitePayload(
                        "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        title, List.of("analysis", "planning"), "agent-1",
                        List.of("agent-1", "agent-2"), "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
    }

    private AgentRabbitSafetyGate gate(
            AgentRabbitActivationState state, boolean draftScopeAllowed) {
        boolean outbox = state != AgentRabbitActivationState.OFF;
        boolean topology = state == AgentRabbitActivationState.MQ_SHADOW
                || state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        boolean dispatch = state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        AgentRabbitSafetyProperties.RabbitBroker broker = topology
                ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated")
                : null;
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(dispatch),
                new AgentRabbitSafetyProperties.RabbitConsume(dispatch),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch), broker);
        AgentRabbitDispatchScopeProperties scopes = dispatch
                ? new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                draftScopeAllowed ? "tenant-a" : "tenant-other", "client-a")))
                : null;
        return new AgentRabbitSafetyGate(properties, scopes);
    }
}
