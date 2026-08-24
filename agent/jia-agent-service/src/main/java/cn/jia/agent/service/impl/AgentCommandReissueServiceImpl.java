package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** D06 sole WAITING_AGENT reissue path. It never performs Rabbit or WebSocket network I/O in a transaction. */
public final class AgentCommandReissueServiceImpl implements AgentCommandReissueService {
    public static final String REASON_AGENT_RECONNECT = "AGENT_RECONNECT";
    public static final String REASON_SCHEDULER = "WAITING_AGENT_SCHEDULER";
    public static final String REQUESTER_SCHEDULER = "SYSTEM_SCHEDULER";
    public static final String AGENT_OFFLINE = AgentCommandRabbitConsumer.AGENT_OFFLINE;
    // Reserve: D06 reissue, D03 claim+settle, and D07 claim+complete.
    private static final long MAX_SAFE_REISSUE_VERSION = Long.MAX_VALUE - 5;
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandReissueServiceImpl.class);

    private final AgentCommandRecoveryDao dao;
    private final AgentRabbitSafetyGate gate;
    private final AgentRawCommandDispatcher dispatcher;
    private final AgentRabbitTopologyManifest.PublishRoute route;
    private final TransactionTemplate transaction;
    private final Supplier<UUID> uuidSupplier;

    public AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, dispatcher, manifest, transactionManager, UUID::randomUUID);
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.route = Objects.requireNonNull(manifest, "manifest").defaultCommandPublishRoute();
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentCommandReissueScanResult reissueForReconnect(
            AgentCommandReconnectScope scope, int limit, long now) {
        validateScope(scope, REASON_AGENT_RECONNECT);
        validateScan(limit, now);
        if (!allows(scope.tenantId(), scope.clientId())
                || !dispatcher.isExactAgentConnected(
                        scope.tenantId(), scope.clientId(), scope.targetAgentId())) {
            return new AgentCommandReissueScanResult(0, 0, 0);
        }
        List<AgentWaitingCommandCandidate> candidates = dao.findReconnectCandidates(
                scope.tenantId(), scope.clientId(), scope.targetAgentId(), now, 0, limit);
        return process(candidates, scope.requestedBy(), scope.reason(), false, now);
    }

    @Override
    public AgentCommandReissueScanResult reissueDue(
            int limit, long afterDeliveryId, long now) {
        validateScan(limit, now);
        if (afterDeliveryId < 0) throw new IllegalArgumentException("afterDeliveryId must be non-negative");
        if (!isDispatchState()) return new AgentCommandReissueScanResult(0, 0, afterDeliveryId);
        List<AgentWaitingCommandCandidate> candidates = dao.findDueCandidates(now, afterDeliveryId, limit);
        if (candidates.isEmpty() && afterDeliveryId > 0) {
            candidates = dao.findDueCandidates(now, 0, limit);
        }
        return process(candidates, REQUESTER_SCHEDULER, REASON_SCHEDULER, true, now);
    }

    private AgentCommandReissueScanResult process(
            List<AgentWaitingCommandCandidate> candidates,
            String requestedBy,
            String reason,
            boolean requireDue,
            long now) {
        int examined = 0;
        int reissued = 0;
        long cursor = 0;
        for (AgentWaitingCommandCandidate candidate : safeCandidates(candidates)) {
            examined++;
            cursor = Math.max(cursor, candidate.deliveryId());
            if (!validCandidate(candidate)
                    || !allows(candidate.tenantId(), candidate.clientId())
                    || !dispatcher.isExactAgentConnected(
                            candidate.tenantId(), candidate.clientId(), candidate.targetAgentId())) {
                continue;
            }
            try {
                Boolean won = transaction.execute(status -> reissueLocked(
                        candidate, requestedBy, reason, requireDue, now));
                if (Boolean.TRUE.equals(won)) reissued++;
            } catch (RecoveryConflictException conflict) {
                LOG.warn("WAITING_AGENT reissue rejected, reason={}", conflict.reason);
            }
        }
        return new AgentCommandReissueScanResult(examined, reissued, cursor);
    }

    private boolean reissueLocked(
            AgentWaitingCommandCandidate candidate,
            String requestedBy,
            String reason,
            boolean requireDue,
            long now) {
        if (!allows(candidate.tenantId(), candidate.clientId())) return false;
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                candidate.tenantId(), candidate.clientId(), candidate.deliveryId());
        if (delivery == null || !candidate.targetAgentId().equals(delivery.getTargetAgentId())) return false;
        validateWaitingDelivery(delivery, requireDue, now);

        List<AgentOutboxEventEntity> activeRows = dao.lockActiveOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveMessageId());
        if (activeRows == null || activeRows.size() != 1) throw conflict("ACTIVE_OUTBOX_CARDINALITY");
        AgentOutboxEventEntity sourceOutbox = activeRows.getFirst();

        AgentConsumerInboxEntity sourceInbox = dao.lockInbox(
                delivery.getTenantId(), delivery.getClientId(),
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, delivery.getActiveMessageId());
        validateSource(delivery, sourceOutbox, sourceInbox);

        AgentCommandDraft draft;
        try {
            draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload());
        } catch (IllegalArgumentException invalid) {
            throw conflict("COMMAND_PAYLOAD_NOT_CANONICAL");
        }
        validateDraftMatches(delivery, draft);
        byte[] commandHash = AgentCommandCanonicalCodec.sha256(delivery.getCommandPayload());
        if (!hashEquals(commandHash, delivery.getCommandPayloadHash())) {
            throw conflict("COMMAND_PAYLOAD_HASH_DRIFT");
        }
        byte[] sourceWire = AgentCommandCanonicalCodec.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt());
        if (!Arrays.equals(sourceWire, sourceOutbox.getWirePayload())
                || !hashEquals(AgentCommandCanonicalCodec.sha256(sourceWire),
                        sourceOutbox.getWirePayloadHash())) {
            throw conflict("SOURCE_WIRE_CANONICAL_DRIFT");
        }

        int nextAttempt;
        try {
            nextAttempt = Math.addExact(delivery.getActiveAttempt(), 1);
        } catch (ArithmeticException overflow) {
            throw conflict("DELIVERY_ATTEMPT_EXHAUSTED");
        }
        String newMessageId = nextUuid("messageId");
        String newEventId = nextUuid("eventId");
        if (newMessageId.equals(delivery.getActiveMessageId())
                || newMessageId.equals(delivery.getCommandId())
                || newEventId.equals(sourceOutbox.getEventId())
                || newEventId.equals(newMessageId)
                || newEventId.equals(delivery.getCommandId())) {
            throw conflict("REISSUE_ID_COLLISION");
        }
        byte[] newWire = AgentCommandCanonicalCodec.wireBytes(draft, newMessageId, nextAttempt);
        byte[] newWireHash = AgentCommandCanonicalCodec.sha256(newWire);

        if (!allows(delivery.getTenantId(), delivery.getClientId())
                || !dispatcher.isExactAgentConnected(
                        delivery.getTenantId(), delivery.getClientId(), delivery.getTargetAgentId())) {
            return false;
        }

        int deliveryRows = dao.reissueDelivery(
                delivery, newMessageId, requestedBy, reason,
                AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER, now);
        if (deliveryRows == 0) return false;
        if (deliveryRows != 1) throw new IllegalStateException(
                "delivery reissue CAS returned " + deliveryRows + " rows; expected 0 or 1");

        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setEventId(newEventId)
                .setMessageId(newMessageId)
                .setCommandId(delivery.getCommandId())
                .setDeliveryId(delivery.getId())
                .setAggregateType("task")
                .setAggregateId(delivery.getTaskId())
                .setDestination(route.destination())
                .setRoutingKey(route.routingKey())
                .setWirePayload(newWire)
                .setWirePayloadHash(newWireHash)
                .setStatus("PENDING")
                .setAttemptCount(0)
                .setActiveAttempt(AgentCommandCanonicalCodec.ATTEMPT)
                .setExpiresAt(delivery.getExpiresAt())
                .setPublisherConfirmStatus("NONE")
                .setMandatoryReturnStatus("NONE")
                .setLastError(AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER)
                .setVersion(0L)
                .setReplayParentMessageId(delivery.getActiveMessageId())
                .setReplayRequesterId(requestedBy)
                .setReplayReason(reason);
        outbox.setTenantId(delivery.getTenantId());
        outbox.setClientId(delivery.getClientId());
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        if (dao.insertOutbox(outbox) != 1) {
            throw new IllegalStateException("reissue outbox insert did not affect exactly one row");
        }
        if (outbox.getId() == null || outbox.getId() <= 0) {
            throw new IllegalStateException("reissue outbox insert did not return a generated id");
        }
        return true;
    }

    private void validateWaitingDelivery(
            AgentCommandDeliveryEntity delivery, boolean requireDue, long now) {
        if (!exact(delivery.getTenantId(), 50) || !exact(delivery.getClientId(), 50)
                || delivery.getId() == null || delivery.getId() <= 0
                || !exact(delivery.getCommandId(), 100) || !exact(delivery.getTaskId(), 100)
                || !exact(delivery.getTargetAgentId(), 100)
                || !AgentProtocolConstants.COMMAND_TASK_INVITE.equals(delivery.getCommandType())
                || !"WAITING_AGENT".equals(delivery.getStatus())
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() <= 0
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || !delivery.getAttemptCount().equals(delivery.getActiveAttempt())
                || delivery.getActiveAttempt() == Integer.MAX_VALUE
                || !exact(delivery.getActiveMessageId(), 100)
                || delivery.getNextRetryAt() == null || delivery.getNextRetryAt() <= 0
                || delivery.getExpiresAt() == null || now >= delivery.getExpiresAt()
                || delivery.getNextRetryAt() >= delivery.getExpiresAt()
                || (requireDue && delivery.getNextRetryAt() > now)
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || !AGENT_OFFLINE.equals(delivery.getLastError())
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || delivery.getVersion() > MAX_SAFE_REISSUE_VERSION) {
            throw conflict("WAITING_DELIVERY_SHAPE_INVALID");
        }
    }

    private void validateSource(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox) {
        if (outbox == null || inbox == null
                || outbox.getId() == null || outbox.getId() <= 0
                || inbox.getId() == null || inbox.getId() <= 0
                || !exact(outbox.getEventId(), 100)
                || !sameScope(delivery, outbox) || !sameScope(delivery, inbox)
                || !Objects.equals(outbox.getDeliveryId(), delivery.getId())
                || !delivery.getActiveMessageId().equals(outbox.getMessageId())
                || !delivery.getCommandId().equals(outbox.getCommandId())
                || !"task".equals(outbox.getAggregateType())
                || !delivery.getTaskId().equals(outbox.getAggregateId())
                || !route.destination().equals(outbox.getDestination())
                || !route.routingKey().equals(outbox.getRoutingKey())
                || !"PUBLISHED".equals(outbox.getStatus())
                || outbox.getAttemptCount() == null || outbox.getAttemptCount() <= 0
                || outbox.getActiveAttempt() == null
                || (long) outbox.getActiveAttempt() != (long) outbox.getAttemptCount() + 1L
                || outbox.getNextRetryAt() != null || outbox.getLeaseOwner() != null
                || outbox.getLeaseUntil() != null
                || !"ACK".equals(outbox.getPublisherConfirmStatus())
                || outbox.getConfirmedAt() == null || outbox.getConfirmedAt() <= 0
                || outbox.getConfirmError() != null
                || !"NOT_RETURNED".equals(outbox.getMandatoryReturnStatus())
                || outbox.getReturnedAt() != null || outbox.getReturnReplyCode() != null
                || outbox.getReturnReplyText() != null
                || outbox.getPublishedAt() == null || outbox.getPublishedAt() <= 0
                || outbox.getLastError() != null
                || !Objects.equals(outbox.getExpiresAt(), delivery.getExpiresAt())
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(inbox.getConsumerName())
                || !delivery.getActiveMessageId().equals(inbox.getMessageId())
                || !outbox.getEventId().equals(inbox.getEventId())
                || !delivery.getCommandId().equals(inbox.getCommandId())
                || !Objects.equals(delivery.getId(), inbox.getDeliveryId())
                || !Arrays.equals(outbox.getWirePayload(), inbox.getWirePayload())
                || !hashEquals(outbox.getWirePayloadHash(), inbox.getWirePayloadHash())
                || !"WAITING_AGENT".equals(inbox.getStatus())
                || !"WAITING_AGENT".equals(inbox.getResultStatus())
                || inbox.getAttemptCount() == null || inbox.getAttemptCount() <= 0
                || inbox.getActiveAttempt() == null
                || !inbox.getActiveAttempt().equals(inbox.getAttemptCount())
                || inbox.getNextRetryAt() == null
                || !inbox.getNextRetryAt().equals(delivery.getNextRetryAt())
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null
                || !Objects.equals(inbox.getExpiresAt(), delivery.getExpiresAt())
                || inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getNextRetryAt() <= inbox.getProcessedAt()
                || !AGENT_OFFLINE.equals(inbox.getLastError())
                || inbox.getVersion() == null || inbox.getVersion() < 0
                || inbox.getReplayParentMessageId() != null
                || inbox.getReplayRequesterId() != null
                || inbox.getReplayApproverId() != null
                || inbox.getReplayReason() != null) {
            throw conflict("WAITING_SOURCE_PROVENANCE_INVALID");
        }
        if (!storedHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || !storedHash(inbox.getWirePayload(), inbox.getWirePayloadHash())) {
            throw conflict("WAITING_SOURCE_HASH_INVALID");
        }
        if (!validReplayAudit(delivery, outbox)) {
            throw conflict("WAITING_SOURCE_REPLAY_AUDIT_INVALID");
        }
    }

    private boolean validReplayAudit(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        boolean absent = delivery.getReplayParentMessageId() == null
                && delivery.getReplayRequesterId() == null
                && delivery.getReplayApproverId() == null
                && delivery.getReplayReason() == null
                && outbox.getReplayParentMessageId() == null
                && outbox.getReplayRequesterId() == null
                && outbox.getReplayApproverId() == null
                && outbox.getReplayReason() == null;
        if (absent) return true;
        return exact(delivery.getReplayParentMessageId(), 100)
                && !delivery.getActiveMessageId().equals(delivery.getReplayParentMessageId())
                && exact(delivery.getReplayRequesterId(), 100)
                && delivery.getReplayApproverId() == null
                && Set.of(REASON_AGENT_RECONNECT, REASON_SCHEDULER)
                        .contains(delivery.getReplayReason())
                && Objects.equals(delivery.getReplayParentMessageId(),
                        outbox.getReplayParentMessageId())
                && Objects.equals(delivery.getReplayRequesterId(),
                        outbox.getReplayRequesterId())
                && outbox.getReplayApproverId() == null
                && Objects.equals(delivery.getReplayReason(), outbox.getReplayReason());
    }

    private void validateDraftMatches(AgentCommandDeliveryEntity delivery, AgentCommandDraft draft) {
        if (!delivery.getCommandId().equals(draft.commandId())
                || !delivery.getTenantId().equals(draft.tenantId())
                || !delivery.getClientId().equals(draft.clientId())
                || !delivery.getTaskId().equals(draft.taskId())
                || !Objects.equals(delivery.getWorkItemId(), draft.workItemId())
                || !delivery.getTargetAgentId().equals(draft.targetAgentId())
                || !delivery.getCommandType().equals(draft.commandType())
                || !Objects.equals(delivery.getExpiresAt(), draft.expiresAt())) {
            throw conflict("COMMAND_PAYLOAD_IDENTITY_DRIFT");
        }
    }

    private boolean allows(String tenantId, String clientId) {
        return isDispatchState() && gate.allowsDispatch(tenantId, clientId);
    }

    private boolean isDispatchState() {
        return gate.state() == AgentRabbitActivationState.DISPATCH_CANARY
                || gate.state() == AgentRabbitActivationState.DISPATCH_SCOPED;
    }

    private void validateScope(AgentCommandReconnectScope scope, String expectedReason) {
        if (scope == null || !exact(scope.tenantId(), 50) || !exact(scope.clientId(), 50)
                || !exact(scope.targetAgentId(), 100) || !exact(scope.requestedBy(), 100)
                || !expectedReason.equals(scope.reason())) {
            throw new IllegalArgumentException("reconnect scope is invalid");
        }
    }

    private void validateScan(int limit, long now) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("limit must be in 1..100");
        if (now <= 0) throw new IllegalArgumentException("now must be positive");
    }

    private boolean validCandidate(AgentWaitingCommandCandidate candidate) {
        return candidate != null && candidate.deliveryId() > 0
                && exact(candidate.tenantId(), 50) && exact(candidate.clientId(), 50)
                && exact(candidate.targetAgentId(), 100);
    }

    private List<AgentWaitingCommandCandidate> safeCandidates(
            List<AgentWaitingCommandCandidate> candidates) {
        return candidates == null ? List.of() : candidates;
    }

    private boolean sameScope(AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        return delivery.getTenantId().equals(outbox.getTenantId())
                && delivery.getClientId().equals(outbox.getClientId());
    }

    private boolean sameScope(AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox) {
        return delivery.getTenantId().equals(inbox.getTenantId())
                && delivery.getClientId().equals(inbox.getClientId());
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

    private String nextUuid(String field) {
        UUID value = uuidSupplier.get();
        if (value == null) throw new IllegalStateException(field + " UUID supplier returned null");
        return value.toString();
    }

    private RecoveryConflictException conflict(String reason) {
        return new RecoveryConflictException(reason);
    }

    private static final class RecoveryConflictException extends RuntimeException {
        private final String reason;
        private RecoveryConflictException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
