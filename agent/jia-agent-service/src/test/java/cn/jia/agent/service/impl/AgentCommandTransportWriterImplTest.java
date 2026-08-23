package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
                dao, gate(true, false), transactions,
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
    void nonDbShadowReservesPendingWithoutCaptureMarker() {
        when(dao.insertDelivery(any())).thenAnswer(invocation -> {
            invocation.<AgentCommandDeliveryEntity>getArgument(0).setId(9L);
            return 1;
        });
        when(dao.insertOutbox(any())).thenReturn(1);
        AgentCommandTransportWriterImpl writer = new AgentCommandTransportWriterImpl(
                dao, gate(true, true), transactions, () -> new UUID(0, 1));

        writer.write(draft("Task One"));

        ArgumentCaptor<AgentCommandDeliveryEntity> delivery =
                ArgumentCaptor.forClass(AgentCommandDeliveryEntity.class);
        verify(dao).insertDelivery(delivery.capture());
        assertEquals("PENDING", delivery.getValue().getStatus());
        assertNull(delivery.getValue().getLastError());
    }

    private AgentCommandTransportWriterImpl writer() {
        return new AgentCommandTransportWriterImpl(
                dao, gate(true, false), transactions, () -> new UUID(0, 1));
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

    private AgentRabbitSafetyGate gate(boolean outbox, boolean topology) {
        AgentRabbitSafetyProperties.RabbitBroker broker = topology
                ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated")
                : null;
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false), broker));
    }
}
