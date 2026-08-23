package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandTransportWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Fail-closed REQUIRED writer. No Rabbit client or publish path is present here. */
public final class AgentCommandTransportWriterImpl implements AgentCommandTransportWriter {
    public static final String DB_SHADOW_MARKER = "DB_SHADOW_CAPTURE_ONLY";
    public static final String DESTINATION = "jia.agent.command";
    public static final String ROUTING_KEY = "agent.command.general";

    private final AgentCommandTransportDao dao;
    private final AgentRabbitSafetyGate gate;
    private final TransactionTemplate transaction;
    private final Supplier<UUID> uuidSupplier;

    public AgentCommandTransportWriterImpl(
            AgentCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, transactionManager, UUID::randomUUID);
    }

    AgentCommandTransportWriterImpl(
            AgentCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentCommandTransportWriteResult write(AgentCommandDraft draft) {
        if (!gate.commandOutboxEnabled()) {
            throw new IllegalStateException("Agent command transport writer called while command outbox is disabled");
        }
        byte[] commandBytes = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] commandHash = AgentCommandCanonicalCodec.sha256(commandBytes);
        return transaction.execute(status -> writeInTransaction(draft, commandBytes, commandHash));
    }

    private AgentCommandTransportWriteResult writeInTransaction(
            AgentCommandDraft draft, byte[] commandBytes, byte[] commandHash) {
        AgentCommandDeliveryEntity existing = dao.lockDelivery(
                draft.tenantId(), draft.clientId(), draft.commandId());
        if (existing != null) return duplicateOrConflict(existing, draft, commandBytes, commandHash);

        String messageId = nextUuid("messageId");
        String eventId = nextUuid("eventId");
        byte[] wireBytes = AgentCommandCanonicalCodec.wireBytes(draft, messageId);
        byte[] wireHash = AgentCommandCanonicalCodec.sha256(wireBytes);
        boolean captureOnly = gate.state() == AgentRabbitActivationState.DB_SHADOW;
        String status = captureOnly ? "DEAD" : "PENDING";
        String marker = captureOnly ? DB_SHADOW_MARKER : null;

        AgentCommandDeliveryEntity delivery = new AgentCommandDeliveryEntity()
                .setCommandId(draft.commandId())
                .setTaskId(draft.taskId())
                .setWorkItemId(draft.workItemId())
                .setTargetAgentId(draft.targetAgentId())
                .setCommandType(draft.commandType())
                .setCommandPayload(commandBytes)
                .setCommandPayloadHash(commandHash)
                .setStatus(status)
                .setAttemptCount(1)
                .setActiveMessageId(messageId)
                .setActiveAttempt(AgentCommandCanonicalCodec.ATTEMPT)
                .setExpiresAt(draft.expiresAt())
                .setLastError(marker)
                .setVersion(0L);
        delivery.setTenantId(draft.tenantId());
        delivery.setClientId(draft.clientId());
        delivery.setCreateTime(draft.issuedAt());
        delivery.setUpdateTime(draft.issuedAt());
        try {
            requireOne(dao.insertDelivery(delivery), "delivery insert");
        } catch (DuplicateKeyException concurrent) {
            AgentCommandDeliveryEntity winner = dao.lockDelivery(
                    draft.tenantId(), draft.clientId(), draft.commandId());
            if (winner == null) throw new IllegalStateException(
                    "Concurrent command identity conflict did not expose the winning row", concurrent);
            return duplicateOrConflict(winner, draft, commandBytes, commandHash);
        }
        if (delivery.getId() == null || delivery.getId() <= 0) {
            throw new IllegalStateException("delivery insert did not return a generated id");
        }

        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setEventId(eventId)
                .setMessageId(messageId)
                .setCommandId(draft.commandId())
                .setDeliveryId(delivery.getId())
                .setAggregateType("task")
                .setAggregateId(draft.taskId())
                .setDestination(DESTINATION)
                .setRoutingKey(ROUTING_KEY)
                .setWirePayload(wireBytes)
                .setWirePayloadHash(wireHash)
                .setStatus(status)
                .setAttemptCount(0)
                .setActiveAttempt(AgentCommandCanonicalCodec.ATTEMPT)
                .setExpiresAt(draft.expiresAt())
                .setPublisherConfirmStatus("NONE")
                .setMandatoryReturnStatus("NONE")
                .setLastError(marker)
                .setVersion(0L);
        outbox.setTenantId(draft.tenantId());
        outbox.setClientId(draft.clientId());
        outbox.setCreateTime(draft.issuedAt());
        outbox.setUpdateTime(draft.issuedAt());
        requireOne(dao.insertOutbox(outbox), "outbox insert");
        return new AgentCommandTransportWriteResult(
                delivery.getId(), draft.commandId(), messageId, eventId, false);
    }

    private AgentCommandTransportWriteResult duplicateOrConflict(
            AgentCommandDeliveryEntity existing,
            AgentCommandDraft draft,
            byte[] commandBytes,
            byte[] commandHash) {
        boolean identityMatches = Objects.equals(existing.getTenantId(), draft.tenantId())
                && Objects.equals(existing.getClientId(), draft.clientId())
                && Objects.equals(existing.getCommandId(), draft.commandId())
                && Objects.equals(existing.getTaskId(), draft.taskId())
                && Objects.equals(existing.getWorkItemId(), draft.workItemId())
                && Objects.equals(existing.getTargetAgentId(), draft.targetAgentId())
                && Objects.equals(existing.getCommandType(), draft.commandType())
                && Objects.equals(existing.getExpiresAt(), draft.expiresAt());
        boolean bytesMatch = existing.getCommandPayloadHash() != null
                && existing.getCommandPayload() != null
                && MessageDigest.isEqual(existing.getCommandPayloadHash(), commandHash)
                && Arrays.equals(existing.getCommandPayload(), commandBytes);
        if (!identityMatches || !bytesMatch) {
            throw new IllegalStateException(
                    "Agent commandId conflict: frozen identity or canonical payload differs");
        }
        return new AgentCommandTransportWriteResult(
                existing.getId(), draft.commandId(), existing.getActiveMessageId(), null, true);
    }

    private String nextUuid(String field) {
        UUID value = uuidSupplier.get();
        if (value == null) throw new IllegalStateException(field + " UUID supplier returned null");
        return value.toString();
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) throw new IllegalStateException(operation + " returned " + rows + " rows; expected 1");
    }
}
