package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckRejectedException;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandAckService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D06 exact-scope monotonic ACK state machine. No Agent business side effects are performed here. */
public final class AgentCommandAckServiceImpl implements AgentCommandAckService {
    public static final String AGENT_REPORTED_FAILED = "AGENT_REPORTED_FAILED";
    public static final String AGENT_REPORTED_REJECTED = "AGENT_REPORTED_REJECTED";
    private static final Set<String> ACK_STATUSES = Set.of(
            "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED");

    private final AgentCommandRecoveryDao dao;
    private final AgentRabbitSafetyGate gate;
    private final TransactionTemplate transaction;

    public AgentCommandAckServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentCommandAckResult acknowledge(AgentCommandAck ack, long now) {
        validateAck(ack, now);
        if (!isDispatchState() || !gate.allowsDispatch(ack.tenantId(), ack.clientId())) {
            throw rejected("ACK_SCOPE_NOT_ALLOWED");
        }
        return transaction.execute(status -> acknowledgeLocked(ack, now));
    }

    private AgentCommandAckResult acknowledgeLocked(AgentCommandAck ack, long now) {
        AgentCommandDeliveryEntity delivery = dao.lockDeliveryByCommand(
                ack.tenantId(), ack.clientId(), ack.commandId());
        if (delivery == null) throw rejected("ACK_DELIVERY_NOT_FOUND");
        validateDeliveryIdentity(ack, delivery);

        List<AgentOutboxEventEntity> rows = dao.lockActiveOutboxes(
                ack.tenantId(), ack.clientId(), delivery.getId(), delivery.getActiveMessageId());
        if (rows == null || rows.size() != 1) throw rejected("ACK_ACTIVE_OUTBOX_CARDINALITY");
        AgentOutboxEventEntity outbox = rows.getFirst();
        AgentConsumerInboxEntity inbox = dao.lockInbox(
                ack.tenantId(), ack.clientId(), AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                delivery.getActiveMessageId());
        validateSentSource(delivery, outbox, inbox);

        String current = delivery.getStatus();
        if (ack.ackStatus().equals(current)) {
            return new AgentCommandAckResult(
                    AgentCommandAckResult.Kind.PRIOR, current, delivery.getVersion());
        }
        String expected = expectedNext(current, ack.ackStatus());
        if (expected == null) throw rejected("ACK_TRANSITION_INVALID");
        validateVersionCapacity(current, delivery.getVersion());
        String lastError = switch (expected) {
            case "FAILED" -> AGENT_REPORTED_FAILED;
            case "REJECTED" -> AGENT_REPORTED_REJECTED;
            default -> null;
        };
        int rowsUpdated = dao.advanceAck(delivery, expected, lastError, now);
        if (rowsUpdated != 1) throw rejected("ACK_CAS_LOST");
        return new AgentCommandAckResult(
                AgentCommandAckResult.Kind.ADVANCED, expected, delivery.getVersion() + 1);
    }

    private void validateAck(AgentCommandAck ack, long now) {
        if (ack == null || !exact(ack.tenantId(), 50) || !exact(ack.clientId(), 50)
                || !exact(ack.registeredAgentId(), 100)
                || !exact(ack.messageId(), 100) || !exact(ack.correlationId(), 100)
                || !exact(ack.commandId(), 100) || !exact(ack.taskId(), 100)
                || (ack.workItemId() != null && !exact(ack.workItemId(), 100))
                || !ACK_STATUSES.contains(ack.ackStatus())
                || ack.messageId().equals(ack.correlationId())
                || ack.messageId().equals(ack.commandId())
                || ack.ackAt() <= 0 || now <= 0) {
            throw rejected("ACK_ENVELOPE_INVALID");
        }
        long maxFuture;
        try {
            maxFuture = Math.addExact(now, 300_000L);
        } catch (ArithmeticException overflow) {
            maxFuture = Long.MAX_VALUE;
        }
        if (ack.ackAt() > maxFuture) throw rejected("ACK_TIME_INVALID");
    }

    private void validateDeliveryIdentity(AgentCommandAck ack, AgentCommandDeliveryEntity delivery) {
        if (delivery.getId() == null || delivery.getId() <= 0
                || !ack.tenantId().equals(delivery.getTenantId())
                || !ack.clientId().equals(delivery.getClientId())
                || !ack.commandId().equals(delivery.getCommandId())
                || !ack.taskId().equals(delivery.getTaskId())
                || !Objects.equals(ack.workItemId(), delivery.getWorkItemId())
                || !ack.registeredAgentId().equals(delivery.getTargetAgentId())
                || !AgentProtocolConstants.COMMAND_TASK_INVITE.equals(delivery.getCommandType())
                || !ack.correlationId().equals(delivery.getActiveMessageId())
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() <= 0
                || !delivery.getAttemptCount().equals(delivery.getActiveAttempt())
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || delivery.getExpiresAt() == null
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || !Set.of("SENT", "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED")
                        .contains(delivery.getStatus())) {
            throw rejected("ACK_DELIVERY_IDENTITY_INVALID");
        }
    }

    private void validateSentSource(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox) {
        if (outbox == null || inbox == null
                || outbox.getId() == null || outbox.getId() <= 0
                || inbox.getId() == null || inbox.getId() <= 0
                || !exact(outbox.getEventId(), 100)
                || !delivery.getTenantId().equals(outbox.getTenantId())
                || !delivery.getClientId().equals(outbox.getClientId())
                || !Objects.equals(delivery.getId(), outbox.getDeliveryId())
                || !delivery.getActiveMessageId().equals(outbox.getMessageId())
                || !delivery.getCommandId().equals(outbox.getCommandId())
                || !"task".equals(outbox.getAggregateType())
                || !delivery.getTaskId().equals(outbox.getAggregateId())
                || !AgentRabbitTopologyManifest.canonical().allowsCommandPublish(
                        outbox.getDestination(), outbox.getRoutingKey())
                || !"PUBLISHED".equals(outbox.getStatus())
                || outbox.getAttemptCount() == null || outbox.getAttemptCount() <= 0
                || outbox.getActiveAttempt() == null
                || (long) outbox.getActiveAttempt() != (long) outbox.getAttemptCount() + 1L
                || outbox.getNextRetryAt() != null || outbox.getLeaseOwner() != null
                || outbox.getLeaseUntil() != null || outbox.getLastError() != null
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || !"ACK".equals(outbox.getPublisherConfirmStatus())
                || !"NOT_RETURNED".equals(outbox.getMandatoryReturnStatus())
                || outbox.getConfirmedAt() == null || outbox.getConfirmedAt() <= 0
                || outbox.getPublishedAt() == null || outbox.getPublishedAt() <= 0
                || outbox.getConfirmError() != null || outbox.getReturnedAt() != null
                || outbox.getReturnReplyCode() != null || outbox.getReturnReplyText() != null
                || !Objects.equals(outbox.getExpiresAt(), delivery.getExpiresAt())
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(inbox.getConsumerName())
                || !delivery.getTenantId().equals(inbox.getTenantId())
                || !delivery.getClientId().equals(inbox.getClientId())
                || !delivery.getActiveMessageId().equals(inbox.getMessageId())
                || !outbox.getEventId().equals(inbox.getEventId())
                || !delivery.getCommandId().equals(inbox.getCommandId())
                || !Objects.equals(delivery.getId(), inbox.getDeliveryId())
                || !"PROCESSED".equals(inbox.getStatus())
                || inbox.getAttemptCount() == null || inbox.getAttemptCount() <= 0
                || inbox.getActiveAttempt() == null
                || !inbox.getActiveAttempt().equals(inbox.getAttemptCount())
                || inbox.getVersion() == null || inbox.getVersion() < 0
                || !"SENT".equals(inbox.getResultStatus())
                || inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getNextRetryAt() != null || inbox.getLeaseOwner() != null
                || inbox.getLeaseUntil() != null || inbox.getLastError() != null
                || !Objects.equals(inbox.getExpiresAt(), delivery.getExpiresAt())
                || !Arrays.equals(outbox.getWirePayload(), inbox.getWirePayload())
                || !hashEquals(outbox.getWirePayloadHash(), inbox.getWirePayloadHash())
                || !storedHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || !storedHash(inbox.getWirePayload(), inbox.getWirePayloadHash())
                || !validReplayAudit(delivery, outbox, inbox)) {
            throw rejected("ACK_SOURCE_PROVENANCE_INVALID");
        }
        AgentCommandDraft draft;
        try {
            draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload());
        } catch (IllegalArgumentException invalid) {
            throw rejected("ACK_COMMAND_PAYLOAD_INVALID");
        }
        if (!delivery.getCommandId().equals(draft.commandId())
                || !delivery.getTenantId().equals(draft.tenantId())
                || !delivery.getClientId().equals(draft.clientId())
                || !delivery.getTaskId().equals(draft.taskId())
                || !Objects.equals(delivery.getWorkItemId(), draft.workItemId())
                || !delivery.getTargetAgentId().equals(draft.targetAgentId())
                || !delivery.getCommandType().equals(draft.commandType())
                || !Objects.equals(delivery.getExpiresAt(), draft.expiresAt())
                || !storedHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())) {
            throw rejected("ACK_COMMAND_IDENTITY_INVALID");
        }
        byte[] canonicalWire = AgentCommandCanonicalCodec.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt());
        if (!Arrays.equals(canonicalWire, outbox.getWirePayload())) {
            throw rejected("ACK_WIRE_CANONICAL_DRIFT");
        }
    }

    private boolean validReplayAudit(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox) {
        if (inbox.getReplayParentMessageId() != null
                || inbox.getReplayRequesterId() != null
                || inbox.getReplayApproverId() != null
                || inbox.getReplayReason() != null) {
            return false;
        }
        return AgentCommandAutomaticReplayProvenance.validOptionalAudit(
                delivery.getActiveMessageId(), delivery.getTargetAgentId(),
                delivery.getReplayParentMessageId(), delivery.getReplayRequesterId(),
                delivery.getReplayApproverId(), delivery.getReplayReason(),
                outbox.getReplayParentMessageId(), outbox.getReplayRequesterId(),
                outbox.getReplayApproverId(), outbox.getReplayReason());
    }

    private String expectedNext(String current, String requested) {
        return switch (current) {
            case "SENT" -> Set.of("RECEIVED", "REJECTED").contains(requested) ? requested : null;
            case "RECEIVED" -> Set.of("STARTED", "REJECTED").contains(requested) ? requested : null;
            case "STARTED" -> Set.of("SUCCEEDED", "FAILED", "REJECTED").contains(requested)
                    ? requested : null;
            default -> null;
        };
    }

    private void validateVersionCapacity(String current, long version) {
        long maximum = switch (current) {
            case "SENT" -> Long.MAX_VALUE - 3;
            case "RECEIVED" -> Long.MAX_VALUE - 2;
            case "STARTED" -> Long.MAX_VALUE - 1;
            default -> -1;
        };
        if (version > maximum) throw rejected("ACK_VERSION_EXHAUSTED");
    }

    private boolean isDispatchState() {
        return gate.state() == AgentRabbitActivationState.DISPATCH_CANARY
                || gate.state() == AgentRabbitActivationState.DISPATCH_SCOPED;
    }

    private boolean storedHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && hashEquals(AgentCommandCanonicalCodec.sha256(bytes), hash);
    }

    private boolean hashEquals(byte[] left, byte[] right) {
        return left != null && right != null && left.length == right.length
                && MessageDigest.isEqual(left, right);
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private AgentCommandAckRejectedException rejected(String reason) {
        return new AgentCommandAckRejectedException(reason);
    }
}
