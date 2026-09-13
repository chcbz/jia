package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
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
import java.util.UUID;
import java.util.function.Supplier;

/** D06 sole WAITING_AGENT/SENT recovery path. It never performs Rabbit or WebSocket network I/O in a transaction. */
public final class AgentCommandReissueServiceImpl implements AgentCommandReissueService {
    public static final String REASON_AGENT_RECONNECT = "AGENT_RECONNECT";
    public static final String REASON_SCHEDULER = "WAITING_AGENT_SCHEDULER";
    public static final String REQUESTER_SCHEDULER = "SYSTEM_SCHEDULER";
    public static final String AGENT_OFFLINE = AgentCommandRabbitConsumer.AGENT_OFFLINE;
    public static final String SENT_ACK_TIMEOUT = "SENT_ACK_TIMEOUT";
    public static final String MESSAGE_EXPIRED = AgentCommandInboxServiceImpl.MESSAGE_EXPIRED;
    public static final String RECOVERY_ATTEMPTS_EXHAUSTED = "RECOVERY_ATTEMPTS_EXHAUSTED";
    public static final String RECOVERY_DEADLINE_EXHAUSTED = "RECOVERY_DEADLINE_EXHAUSTED";
    private static final long DEFAULT_SENT_ACK_TIMEOUT_MILLIS = 30_000L;
    // Reserve all remaining delivery mutations: D06 reissue, D03 claim+settle,
    // D07 claim+complete, and A06 ACK RECEIVED+STARTED+terminal.
    private static final long MAX_SAFE_REISSUE_VERSION = Long.MAX_VALUE - 8;
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandReissueServiceImpl.class);

    private final AgentCommandRecoveryDao dao;
    private final AgentRabbitSafetyGate gate;
    private final AgentRawCommandDispatcher dispatcher;
    private final AgentRabbitTopologyManifest.PublishRoute route;
    private final TransactionTemplate transaction;
    private final Supplier<UUID> uuidSupplier;
    private final long sentAckTimeoutMillis;
    private final AgentCommandRecoveryPolicy recoveryPolicy;

    public AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, dispatcher, manifest, DEFAULT_SENT_ACK_TIMEOUT_MILLIS,
                transactionManager, UUID::randomUUID, new AgentCommandRecoveryPolicy());
    }

    public AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis,
                transactionManager, UUID::randomUUID, new AgentCommandRecoveryPolicy());
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this(dao, gate, dispatcher, manifest, DEFAULT_SENT_ACK_TIMEOUT_MILLIS,
                transactionManager, uuidSupplier, new AgentCommandRecoveryPolicy());
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis, transactionManager,
                uuidSupplier, new AgentCommandRecoveryPolicy());
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier,
            AgentCommandRecoveryPolicy recoveryPolicy) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.route = Objects.requireNonNull(manifest, "manifest").defaultCommandPublishRoute();
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
        if (sentAckTimeoutMillis < 1_000L || sentAckTimeoutMillis > 3_600_000L) {
            throw new IllegalArgumentException("sentAckTimeoutMillis is out of range");
        }
        this.sentAckTimeoutMillis = sentAckTimeoutMillis;
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }


    @Override
    public AgentCommandManualReissueResult reissueManually(
            AgentCommandOperationRequest request, long now) {
        validateManualRequest(request, now);
        if (!allows(request.tenantId(), request.clientId())) {
            throw conflict("MANUAL_REISSUE_SCOPE_DISABLED");
        }
        AgentCommandManualReissueResult result = transaction.execute(status ->
                manualReissueLocked(request, now));
        if (result == null) throw conflict("MANUAL_REISSUE_TRANSACTION_EMPTY");
        return result;
    }

    private AgentCommandManualReissueResult manualReissueLocked(
            AgentCommandOperationRequest request, long now) {
        if (!allows(request.tenantId(), request.clientId())) {
            throw conflict("MANUAL_REISSUE_SCOPE_DISABLED");
        }
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                request.tenantId(), request.clientId(), request.deliveryId());
        if (delivery == null
                || !request.taskId().equals(delivery.getTaskId())
                || !request.targetAgentId().equals(delivery.getTargetAgentId())
                || !request.sourceMessageId().equals(delivery.getActiveMessageId())) {
            throw conflict("MANUAL_REISSUE_NOT_FOUND");
        }
        if ("SKILL_INSTALL".equals(delivery.getCommandType())) throw conflict("SKILL_INSTALL_RECONCILIATION_REQUIRED");
        validateManualDelivery(delivery, now);
        List<AgentOutboxEventEntity> activeRows = dao.lockActiveOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveMessageId());
        if (activeRows == null || activeRows.size() != 1) {
            throw conflict("MANUAL_REISSUE_OUTBOX_CARDINALITY");
        }
        AgentOutboxEventEntity sourceOutbox = activeRows.getFirst();
        List<AgentOutboxEventEntity> previousAttempts = lockPreviousAttempts(delivery);
        AgentConsumerInboxEntity sourceInbox = dao.lockInbox(
                delivery.getTenantId(), delivery.getClientId(),
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, delivery.getActiveMessageId());
        validateManualSource(delivery, sourceOutbox, sourceInbox, previousAttempts);

        AgentCommandDraft draft;
        try {
            draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload());
        } catch (IllegalArgumentException invalid) {
            throw conflict("COMMAND_PAYLOAD_NOT_CANONICAL");
        }
        validateDraftMatches(delivery, draft);
        if (!storedHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())) {
            throw conflict("COMMAND_PAYLOAD_HASH_DRIFT");
        }
        requirePolicyReissue(recoveryPolicy.manual(
                recoveryScope(delivery), delivery.getActiveAttempt(),
                requiredTransitionTime(delivery), delivery.getExpiresAt(), now),
                "MANUAL_REISSUE_POLICY_");
        byte[] sourceWire = AgentCommandCanonicalCodec.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt());
        if (!Arrays.equals(sourceWire, sourceOutbox.getWirePayload())
                || !storedHash(sourceOutbox.getWirePayload(), sourceOutbox.getWirePayloadHash())) {
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
        if (!allows(delivery.getTenantId(), delivery.getClientId())) {
            throw conflict("MANUAL_REISSUE_SCOPE_DISABLED");
        }
        int deliveryRows = dao.manualReissueDelivery(
                delivery, newMessageId, request.requesterId(), request.approverId(),
                request.reason(), AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER, now);
        requireOne(deliveryRows, "manual delivery reissue");

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
                .setActiveAttempt(nextAttempt)
                .setExpiresAt(delivery.getExpiresAt())
                .setPublisherConfirmStatus("NONE")
                .setMandatoryReturnStatus("NONE")
                .setLastError(AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER)
                .setVersion(0L)
                .setReplayParentMessageId(delivery.getActiveMessageId())
                .setReplayRequesterId(request.requesterId())
                .setReplayApproverId(request.approverId())
                .setReplayReason(request.reason());
        outbox.setTenantId(delivery.getTenantId());
        outbox.setClientId(delivery.getClientId());
        outbox.setCreateTime(now);
        outbox.setUpdateTime(now);
        requireOne(dao.insertOutbox(outbox), "manual reissue outbox insert");
        if (outbox.getId() == null || outbox.getId() <= 0) {
            throw new IllegalStateException("manual reissue outbox insert did not return a generated id");
        }
        return new AgentCommandManualReissueResult(
                delivery.getId(), delivery.getCommandId(), delivery.getActiveMessageId(),
                newMessageId, newEventId, delivery.getActiveAttempt(), nextAttempt);
    }

    private void validateManualRequest(AgentCommandOperationRequest request, long now) {
        if (request == null || now <= 0 || request.deliveryId() <= 0
                || !exact(request.tenantId(), 50) || !exact(request.clientId(), 50)
                || !exact(request.taskId(), 100) || !exact(request.targetAgentId(), 100)
                || !exact(request.sourceMessageId(), 100)
                || !exact(request.requesterId(), 100) || !exact(request.approverId(), 100)
                || request.requesterId().equals(request.approverId())
                || !exact(request.reason(), 1000) || !exact(request.ticketReference(), 200)
                || REASON_AGENT_RECONNECT.equals(request.reason())
                || REASON_SCHEDULER.equals(request.reason())) {
            throw new IllegalArgumentException("manual reissue request is invalid");
        }
    }

    private void validateManualDelivery(AgentCommandDeliveryEntity delivery, long now) {
        boolean allowedStatus = "DEAD".equals(delivery.getStatus())
                || "FAILED".equals(delivery.getStatus());
        if (!allowedStatus || delivery.getExpiresAt() == null || now >= delivery.getExpiresAt()
                || delivery.getNextRetryAt() != null || delivery.getLeaseOwner() != null
                || delivery.getLeaseUntil() != null || !exact(delivery.getLastError(), 2000)
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() <= 0
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || !delivery.getAttemptCount().equals(delivery.getActiveAttempt())
                || delivery.getActiveAttempt() == Integer.MAX_VALUE
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || delivery.getVersion() > MAX_SAFE_REISSUE_VERSION
                || !AgentCommandCanonicalCodec.isSupportedCommandType(delivery.getCommandType())) {
            throw conflict("MANUAL_REISSUE_DELIVERY_FORBIDDEN");
        }
    }

    private void validateManualSource(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox,
            List<AgentOutboxEventEntity> previousAttempts) {
        if (outbox == null) {
            throw conflict("MANUAL_REISSUE_SOURCE_PROVENANCE_INVALID");
        }
        boolean published = "PUBLISHED".equals(outbox.getStatus())
                && outbox.getAttemptCount() != null && outbox.getAttemptCount() > 0
                && outbox.getNextRetryAt() == null && outbox.getLeaseOwner() == null
                && outbox.getLeaseUntil() == null
                && "ACK".equals(outbox.getPublisherConfirmStatus())
                && outbox.getConfirmedAt() != null && outbox.getConfirmedAt() > 0
                && outbox.getConfirmError() == null
                && "NOT_RETURNED".equals(outbox.getMandatoryReturnStatus())
                && outbox.getReturnedAt() == null && outbox.getReturnReplyCode() == null
                && outbox.getReturnReplyText() == null
                && outbox.getPublishedAt() != null && outbox.getPublishedAt() > 0
                && outbox.getLastError() == null;
        boolean publishTerminal = ("DEAD".equals(outbox.getStatus())
                || "FAILED".equals(outbox.getStatus()))
                && outbox.getNextRetryAt() == null && outbox.getLeaseOwner() == null
                && outbox.getLeaseUntil() == null && outbox.getPublishedAt() == null
                && exact(outbox.getLastError(), 2000);
        boolean inboxTerminal = inbox != null
                && ("DEAD".equals(inbox.getStatus()) || "FAILED".equals(inbox.getStatus()))
                && ("DEAD".equals(inbox.getResultStatus()) || "FAILED".equals(inbox.getResultStatus()))
                && inbox.getNextRetryAt() == null && inbox.getLeaseOwner() == null
                && inbox.getLeaseUntil() == null && inbox.getProcessedAt() != null
                && inbox.getProcessedAt() > 0 && exact(inbox.getLastError(), 2000)
                && storedHash(inbox.getWirePayload(), inbox.getWirePayloadHash())
                && Arrays.equals(outbox.getWirePayload(), inbox.getWirePayload());
        if (outbox == null || outbox.getId() == null || outbox.getId() <= 0
                || !sameScope(delivery, outbox)
                || !Objects.equals(delivery.getId(), outbox.getDeliveryId())
                || !delivery.getCommandId().equals(outbox.getCommandId())
                || !delivery.getActiveMessageId().equals(outbox.getMessageId())
                || !delivery.getTaskId().equals(outbox.getAggregateId())
                || !"task".equals(outbox.getAggregateType())
                || !route.destination().equals(outbox.getDestination())
                || !route.routingKey().equals(outbox.getRoutingKey())
                || !Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || !storedHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || (!published && !publishTerminal)
                || (published && !inboxTerminal)
                || (publishTerminal && inbox != null)
                || !AgentCommandAutomaticReplayProvenance.validImmediateParent(
                        delivery, outbox, previousAttempts)) {
            throw conflict("MANUAL_REISSUE_SOURCE_PROVENANCE_INVALID");
        }
        if (inbox != null && (!sameScope(delivery, inbox)
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(inbox.getConsumerName())
                || !delivery.getActiveMessageId().equals(inbox.getMessageId())
                || !outbox.getEventId().equals(inbox.getEventId())
                || !delivery.getCommandId().equals(inbox.getCommandId())
                || !Objects.equals(delivery.getId(), inbox.getDeliveryId())
                || !Objects.equals(delivery.getActiveAttempt(), inbox.getActiveAttempt())
                || !Objects.equals(delivery.getExpiresAt(), inbox.getExpiresAt()))) {
            throw conflict("MANUAL_REISSUE_INBOX_PROVENANCE_INVALID");
        }
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
        long sentBefore = now > sentAckTimeoutMillis ? now - sentAckTimeoutMillis : 0L;
        List<AgentWaitingCommandCandidate> candidates = dao.findDueCandidates(
                now, sentBefore, afterDeliveryId, limit);
        if (candidates.isEmpty() && afterDeliveryId > 0) {
            candidates = dao.findDueCandidates(now, sentBefore, 0, limit);
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
                    || (!candidate.expiryCandidate()
                        && !dispatcher.isExactAgentConnected(
                                candidate.tenantId(), candidate.clientId(),
                                candidate.targetAgentId()))) {
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
        if (!allows(candidate.tenantId(), candidate.clientId())
                || !AgentCommandAutomaticReplayProvenance.validRequesterBinding(
                        candidate.targetAgentId(), requestedBy, null, reason)) {
            return false;
        }
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                candidate.tenantId(), candidate.clientId(), candidate.deliveryId());
        if (delivery == null || !candidate.targetAgentId().equals(delivery.getTargetAgentId())) return false;
        // W10 installation identity is immutable. Never mint a generic task redrive attempt for it.
        // Preserve unknown escrow and permit the original durable result to reconcile.
        if ("SKILL_INSTALL".equals(delivery.getCommandType())) return false;
        validateRecoverableDelivery(delivery, requireDue, now);

        List<AgentOutboxEventEntity> activeRows = dao.lockActiveOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveMessageId());
        if (activeRows == null || activeRows.size() != 1) throw conflict("ACTIVE_OUTBOX_CARDINALITY");
        AgentOutboxEventEntity sourceOutbox = activeRows.getFirst();
        List<AgentOutboxEventEntity> previousAttempts = lockPreviousAttempts(delivery);

        AgentConsumerInboxEntity sourceInbox = dao.lockInbox(
                delivery.getTenantId(), delivery.getClientId(),
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, delivery.getActiveMessageId());
        validateSource(delivery, sourceOutbox, sourceInbox, previousAttempts);

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

        if (now >= delivery.getExpiresAt()) {
            expireLocked(delivery, sourceInbox, now);
            return false;
        }
        if (requireDue && "SENT".equals(delivery.getStatus())) {
            Long updatedAt = delivery.getUpdateTime();
            long sentBefore = now > sentAckTimeoutMillis ? now - sentAckTimeoutMillis : 0L;
            if (updatedAt == null || updatedAt <= 0 || updatedAt > sentBefore) return false;
        }
        AgentCommandRecoveryPolicy.Decision policyDecision;
        try {
            policyDecision = recoveryPolicy.automatic(
                    recoveryScope(delivery), delivery.getActiveAttempt(),
                    requiredTransitionTime(delivery), delivery.getExpiresAt(), now);
        } catch (IllegalArgumentException invalidPolicyState) {
            throw conflict("AUTOMATIC_REISSUE_POLICY_STATE_INVALID");
        }
        if (!policyDecision.permitsReissue()) {
            if (policyDecision.action()
                    == AgentCommandRecoveryPolicy.Action.MANUAL_TAKEOVER_REQUIRED) {
                failForManualTakeoverLocked(
                        delivery, sourceInbox, RECOVERY_ATTEMPTS_EXHAUSTED, now);
            } else if (policyDecision.action()
                    == AgentCommandRecoveryPolicy.Action.DEADLINE_EXHAUSTED) {
                failForManualTakeoverLocked(
                        delivery, sourceInbox, RECOVERY_DEADLINE_EXHAUSTED, now);
            }
            return false;
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
                .setActiveAttempt(nextAttempt)
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

    private void validateRecoverableDelivery(
            AgentCommandDeliveryEntity delivery, boolean requireDue, long now) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        boolean sent = "SENT".equals(delivery.getStatus());
        boolean expiryValid = delivery.getExpiresAt() != null && delivery.getExpiresAt() > 0;
        boolean waitingShape = waiting && expiryValid
                && delivery.getNextRetryAt() != null && delivery.getNextRetryAt() > 0
                && delivery.getNextRetryAt() < delivery.getExpiresAt()
                && (!requireDue || delivery.getNextRetryAt() <= now)
                && AGENT_OFFLINE.equals(delivery.getLastError());
        boolean sentShape = sent && expiryValid
                && delivery.getNextRetryAt() == null
                && delivery.getLastError() == null
                && delivery.getUpdateTime() != null && delivery.getUpdateTime() > 0;
        if (!exact(delivery.getTenantId(), 50) || !exact(delivery.getClientId(), 50)
                || delivery.getId() == null || delivery.getId() <= 0
                || !exact(delivery.getCommandId(), 100) || !exact(delivery.getTaskId(), 100)
                || !exact(delivery.getTargetAgentId(), 100)
                || !AgentCommandCanonicalCodec.isSupportedCommandType(delivery.getCommandType())
                || (!waitingShape && !sentShape)
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() <= 0
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || !delivery.getAttemptCount().equals(delivery.getActiveAttempt())
                || (now < delivery.getExpiresAt()
                    && delivery.getActiveAttempt() == Integer.MAX_VALUE)
                || !exact(delivery.getActiveMessageId(), 100)
                || delivery.getExpiresAt() == null || delivery.getExpiresAt() <= 0
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || delivery.getVersion() > (now >= delivery.getExpiresAt()
                    ? Long.MAX_VALUE - 1 : MAX_SAFE_REISSUE_VERSION)) {
            throw conflict("RECOVERABLE_DELIVERY_SHAPE_INVALID");
        }
    }

    private List<AgentOutboxEventEntity> lockPreviousAttempts(
            AgentCommandDeliveryEntity delivery) {
        if (delivery.getActiveAttempt() == 1) return List.of();
        return dao.lockPreviousAttemptOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveAttempt() - 1);
    }

    private void validateSource(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox,
            List<AgentOutboxEventEntity> previousAttempts) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        boolean waitingInbox = waiting
                && "WAITING_AGENT".equals(inbox == null ? null : inbox.getStatus())
                && "WAITING_AGENT".equals(inbox.getResultStatus())
                && inbox.getNextRetryAt() != null
                && inbox.getNextRetryAt().equals(delivery.getNextRetryAt())
                && inbox.getProcessedAt() != null && inbox.getProcessedAt() > 0
                && inbox.getNextRetryAt() > inbox.getProcessedAt()
                && AGENT_OFFLINE.equals(inbox.getLastError());
        boolean sentInbox = "SENT".equals(delivery.getStatus())
                && "PROCESSED".equals(inbox == null ? null : inbox.getStatus())
                && "SENT".equals(inbox.getResultStatus())
                && inbox.getNextRetryAt() == null
                && inbox.getProcessedAt() != null && inbox.getProcessedAt() > 0
                && inbox.getLastError() == null;
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
                || outbox.getActiveAttempt() == null || outbox.getActiveAttempt() <= 0
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
                || (!waitingInbox && !sentInbox)
                || inbox.getAttemptCount() == null || inbox.getAttemptCount() <= 0
                || inbox.getActiveAttempt() == null
                || !inbox.getActiveAttempt().equals(inbox.getAttemptCount())
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null
                || !Objects.equals(inbox.getExpiresAt(), delivery.getExpiresAt())
                || inbox.getVersion() == null || inbox.getVersion() < 0
                || inbox.getReplayParentMessageId() != null
                || inbox.getReplayRequesterId() != null
                || inbox.getReplayApproverId() != null
                || inbox.getReplayReason() != null) {
            throw conflict("RECOVERABLE_SOURCE_PROVENANCE_INVALID");
        }
        if (!storedHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || !storedHash(inbox.getWirePayload(), inbox.getWirePayloadHash())) {
            throw conflict("RECOVERABLE_SOURCE_HASH_INVALID");
        }
        if (!AgentCommandAutomaticReplayProvenance.validImmediateParent(
                delivery, outbox, previousAttempts)) {
            throw conflict("RECOVERABLE_SOURCE_REPLAY_PARENT_INVALID");
        }
    }

    private void failForManualTakeoverLocked(
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox,
            String reason, long now) {
        if (delivery.getVersion() == Long.MAX_VALUE || inbox.getVersion() == Long.MAX_VALUE) {
            throw conflict("RECOVERY_FAILURE_VERSION_EXHAUSTED");
        }
        requireOne(dao.failRecoveryDelivery(delivery, reason, now),
                "recovery delivery failure");
        requireOne(dao.failRecoveryInbox(inbox, reason, now),
                "recovery Inbox failure");
    }

    private void expireLocked(
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox, long now) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        if (delivery.getVersion() == Long.MAX_VALUE
                || (waiting && inbox.getVersion() == Long.MAX_VALUE)) {
            throw conflict("EXPIRY_VERSION_EXHAUSTED");
        }
        requireOne(dao.expireDelivery(delivery, MESSAGE_EXPIRED, now),
                "delivery expiry");
        if (waiting) {
            requireOne(dao.expireWaitingInbox(inbox, MESSAGE_EXPIRED, now),
                    "waiting Inbox expiry");
        }
    }

    private AgentCommandRecoveryPolicy.Scope recoveryScope(
            AgentCommandDeliveryEntity delivery) {
        return new AgentCommandRecoveryPolicy.Scope(
                delivery.getTenantId(), delivery.getClientId(), delivery.getTaskId(),
                delivery.getWorkItemId(), delivery.getTargetAgentId());
    }

    private long requiredTransitionTime(AgentCommandDeliveryEntity delivery) {
        Long value = delivery.getUpdateTime();
        if (value == null) throw conflict("RECOVERY_TRANSITION_TIME_INVALID");
        return value;
    }

    private void requirePolicyReissue(
            AgentCommandRecoveryPolicy.Decision decision, String reasonPrefix) {
        if (!decision.permitsReissue()) {
            throw conflict(reasonPrefix + decision.action().name());
        }
    }

    private void requireOne(int rows, String operation) {
        if (rows != 1) throw new IllegalStateException(
                operation + " returned " + rows + " rows; expected 1");
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
                || !expectedReason.equals(scope.reason())
                || !AgentCommandAutomaticReplayProvenance.validRequesterBinding(
                        scope.targetAgentId(), scope.requestedBy(), null, scope.reason())) {
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
