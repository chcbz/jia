package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.entity.AgentCommandRecoveryDeferral;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** D06 durable recovery path. It never performs Rabbit or WebSocket network I/O in a transaction. */
public final class AgentCommandReissueServiceImpl implements AgentCommandReissueService {
    public static final String REASON_AGENT_RECONNECT = "AGENT_RECONNECT";
    public static final String REASON_SCHEDULER = "WAITING_AGENT_SCHEDULER";
    public static final String REQUESTER_SCHEDULER = "SYSTEM_SCHEDULER";
    public static final String AGENT_OFFLINE = AgentCommandRabbitConsumer.AGENT_OFFLINE;
    public static final String SENT_ACK_TIMEOUT = "SENT_ACK_TIMEOUT";
    public static final String MESSAGE_EXPIRED = AgentCommandInboxServiceImpl.MESSAGE_EXPIRED;
    private static final long DEFAULT_SENT_ACK_TIMEOUT_MILLIS = 30_000L;
    // Reserve all remaining delivery mutations: D06 reissue, D03 claim+settle,
    // D07 claim+complete, and A06 ACK RECEIVED+STARTED+terminal.
    private static final long MAX_SAFE_REISSUE_VERSION = Long.MAX_VALUE - 8;
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandReissueServiceImpl.class);

    private final AgentCommandRecoveryDao dao;
    private final AgentRabbitSafetyGate gate;
    private final AgentRawCommandDispatcher dispatcher;
    private final Route route;
    private final TransactionTemplate transaction;
    private final Supplier<UUID> uuidSupplier;
    private final long sentAckTimeoutMillis;
    private final AgentCommandRecoveryPolicy recoveryPolicy;
    private final TenantModuleAdmissionGuard admissionGuard;
    private final WorkerScope workerScope;

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

    public AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            TenantModuleAdmissionGuard admissionGuard,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis,
                transactionManager, UUID::randomUUID, new AgentCommandRecoveryPolicy(),
                admissionGuard, null);
    }

    public AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            TenantModuleAdmissionGuard admissionGuard,
            WorkerScope workerScope,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis,
                transactionManager, UUID::randomUUID, new AgentCommandRecoveryPolicy(),
                admissionGuard, workerScope);
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
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis, transactionManager,
                uuidSupplier, recoveryPolicy, null, null);
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier,
            AgentCommandRecoveryPolicy recoveryPolicy,
            TenantModuleAdmissionGuard admissionGuard) {
        this(dao, gate, dispatcher, manifest, sentAckTimeoutMillis, transactionManager,
                uuidSupplier, recoveryPolicy, admissionGuard, null);
    }

    AgentCommandReissueServiceImpl(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            AgentRabbitTopologyManifest manifest,
            long sentAckTimeoutMillis,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier,
            AgentCommandRecoveryPolicy recoveryPolicy,
            TenantModuleAdmissionGuard admissionGuard,
            WorkerScope workerScope) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(manifest, "manifest");
        if (workerScope == null) {
            AgentRabbitTopologyManifest.PublishRoute legacy =
                    manifest.defaultCommandPublishRoute();
            this.route = new Route(legacy.destination(), legacy.routingKey());
        } else {
            AgentRabbitV2TopologyManifest.PublishRoute scoped =
                    AgentRabbitV2TopologyManifest.forScope(workerScope)
                            .defaultCommandPublishRoute();
            this.route = new Route(scoped.destination(), scoped.routingKey());
        }
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy, "recoveryPolicy");
        this.admissionGuard = admissionGuard;
        this.workerScope = workerScope;
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
        AgentCommandDeliveryEntity hint = dao.findDeliveryProtocolHint(
                request.tenantId(), request.clientId(), request.deliveryId());
        if (hint == null) throw conflict("MANUAL_REISSUE_NOT_FOUND");
        return withProducerAdmission(hint, "MANUAL_REISSUE_ADMISSION_DENIED",
                () -> manualReissueAdmitted(request, now, hint));
    }

    private AgentCommandManualReissueResult manualReissueAdmitted(
            AgentCommandOperationRequest request, long now, AgentCommandDeliveryEntity hint) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                request.tenantId(), request.clientId(), request.deliveryId());
        if (delivery == null || !sameProtocolHint(hint, delivery)
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
        ProducerScope producerScope = requireProtocolScope(delivery, sourceOutbox,
                "MANUAL_REISSUE_SOURCE_PROTOCOL_INVALID");
        validatePreviousProtocol(delivery, previousAttempts, producerScope,
                "MANUAL_REISSUE_PREVIOUS_PROTOCOL_INVALID");
        String inboxConsumer = inboxConsumer(producerScope);
        AgentConsumerInboxEntity sourceInbox = dao.lockInbox(
                delivery.getTenantId(), delivery.getClientId(),
                inboxConsumer, delivery.getActiveMessageId());
        validateManualSource(
                delivery, sourceOutbox, sourceInbox, previousAttempts, inboxConsumer);

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
        byte[] sourceWire = AgentCommandProtocolSupport.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt(),
                delivery, sourceOutbox);
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
        byte[] newWire = producerScope == null
                ? AgentCommandCanonicalCodec.wireBytes(draft, newMessageId, nextAttempt)
                : AgentCommandCanonicalCodec.wireBytesV2(
                    draft, newMessageId, delivery.getId(), nextAttempt, producerScope);
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
        applyReissueProtocol(outbox, delivery, producerScope);
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
            List<AgentOutboxEventEntity> previousAttempts,
            String expectedConsumer) {
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
                || !expectedConsumer.equals(inbox.getConsumerName())
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
        List<AgentWaitingCommandCandidate> candidates;
        boolean legacyDiscovery = workerScope == null;
        if (legacyDiscovery) {
            if (!"0".equals(scope.tenantId())) {
                return new AgentCommandReissueScanResult(0, 0, 0);
            }
            candidates = dao.findLegacyReconnectCandidates(
                    scope.tenantId(), scope.clientId(), scope.targetAgentId(), now, 0, limit);
        } else {
            if (!workerScope.tenantId().value().equals(scope.tenantId())) {
                return new AgentCommandReissueScanResult(0, 0, 0);
            }
            candidates = dao.findV2ReconnectCandidates(
                    workerScope, scope.clientId(), scope.targetAgentId(), now, 0, limit);
        }
        return process(candidates, scope.requestedBy(), scope.reason(), false, now, scope,
                workerScope, legacyDiscovery);
    }

    @Override
    public AgentCommandReissueScanResult reissueDue(
            int limit, long afterDeliveryId, long now) {
        validateScan(limit, now);
        if (afterDeliveryId < 0) throw new IllegalArgumentException("afterDeliveryId must be non-negative");
        if (!isDispatchState()) return new AgentCommandReissueScanResult(0, 0, afterDeliveryId);
        long sentBefore = now > sentAckTimeoutMillis ? now - sentAckTimeoutMillis : 0L;
        boolean legacyDiscovery = workerScope == null;
        List<AgentWaitingCommandCandidate> candidates = legacyDiscovery
                ? dao.findLegacyDueCandidates(now, sentBefore, afterDeliveryId, limit)
                : dao.findV2DueCandidates(workerScope, now, sentBefore, afterDeliveryId, limit);
        if (candidates.isEmpty() && afterDeliveryId > 0) {
            candidates = legacyDiscovery
                    ? dao.findLegacyDueCandidates(now, sentBefore, 0, limit)
                    : dao.findV2DueCandidates(workerScope, now, sentBefore, 0, limit);
        }
        return process(candidates, REQUESTER_SCHEDULER, REASON_SCHEDULER, true, now, null,
                workerScope, legacyDiscovery);
    }

    private AgentCommandReissueScanResult process(
            List<AgentWaitingCommandCandidate> candidates,
            String requestedBy,
            String reason,
            boolean requireDue,
            long now,
            AgentCommandReconnectScope reconnectScope,
            WorkerScope discoveryScope,
            boolean legacyDiscovery) {
        List<AgentCommandRecoveryDeferral> deferrals = new ArrayList<>();
        int examined = 0;
        int reissued = 0;
        long cursor = 0;
        for (AgentWaitingCommandCandidate candidate : safeCandidates(candidates)) {
            if (examined == 100) break; // Same bound as validated discovery limits.
            if (!validCandidate(candidate)
                    || !matchesDiscoveryLane(
                            candidate, reconnectScope, discoveryScope, legacyDiscovery)) {
                continue;
            }
            examined++;
            cursor = Math.max(cursor, candidate.deliveryId());
            if (!allows(candidate.tenantId(), candidate.clientId())
                    || (!candidate.expiryCandidate()
                        && !dispatcher.isExactAgentConnected(
                                candidate.tenantId(), candidate.clientId(),
                                candidate.targetAgentId()))) {
                continue;
            }
            try {
                RecoveryOutcome outcome = transaction.execute(status -> reissueLocked(
                        candidate, requestedBy, reason, requireDue, now));
                if (outcome != null) {
                    if (outcome.reissued()) reissued++;
                    if (outcome.deferral() != null) deferrals.add(outcome.deferral());
                }
            } catch (RecoveryConflictException conflict) {
                LOG.warn("WAITING_AGENT reissue rejected, reason={}", conflict.reason);
            }
        }
        return new AgentCommandReissueScanResult(examined, reissued, cursor, deferrals);
    }

    private boolean matchesDiscoveryLane(
            AgentWaitingCommandCandidate candidate,
            AgentCommandReconnectScope reconnectScope,
            WorkerScope discoveryScope,
            boolean legacyDiscovery) {
        if (legacyDiscovery) {
            if (!"0".equals(candidate.tenantId())) return false;
        } else if (discoveryScope == null
                || !discoveryScope.tenantId().value().equals(candidate.tenantId())) {
            return false;
        }
        return reconnectScope == null
                || (reconnectScope.tenantId().equals(candidate.tenantId())
                    && reconnectScope.clientId().equals(candidate.clientId())
                    && reconnectScope.targetAgentId().equals(candidate.targetAgentId()));
    }

    private RecoveryOutcome reissueLocked(
            AgentWaitingCommandCandidate candidate,
            String requestedBy,
            String reason,
            boolean requireDue,
            long now) {
        if (!allows(candidate.tenantId(), candidate.clientId())
                || !AgentCommandAutomaticReplayProvenance.validRequesterBinding(
                        candidate.targetAgentId(), requestedBy, null, reason)) {
            return new RecoveryOutcome(false, null);
        }
        AgentCommandDeliveryEntity hint = dao.findDeliveryProtocolHint(
                candidate.tenantId(), candidate.clientId(), candidate.deliveryId());
        if (hint == null) return new RecoveryOutcome(false, null);
        return withProducerAdmission(hint, "AUTOMATIC_REISSUE_ADMISSION_DENIED",
                () -> reissueAdmitted(candidate, requestedBy, reason, requireDue, now, hint));
    }

    private RecoveryOutcome reissueAdmitted(
            AgentWaitingCommandCandidate candidate, String requestedBy, String reason,
            boolean requireDue, long now, AgentCommandDeliveryEntity hint) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                candidate.tenantId(), candidate.clientId(), candidate.deliveryId());
        if (delivery == null || !sameProtocolHint(hint, delivery)
                || !Objects.equals(candidate.deliveryId(), delivery.getId())
                || !candidate.tenantId().equals(delivery.getTenantId())
                || !candidate.clientId().equals(delivery.getClientId())
                || !candidate.targetAgentId().equals(delivery.getTargetAgentId())) {
            return new RecoveryOutcome(false, null);
        }
        // W10 installation identity is immutable. Never mint a generic task redrive attempt for it.
        // Preserve unknown escrow and permit the original durable result to reconcile.
        if ("SKILL_INSTALL".equals(delivery.getCommandType())) return new RecoveryOutcome(false, null);
        validateRecoverableDelivery(delivery, requireDue, now);

        List<AgentOutboxEventEntity> activeRows = dao.lockActiveOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveMessageId());
        if (activeRows == null || activeRows.size() != 1) throw conflict("ACTIVE_OUTBOX_CARDINALITY");
        AgentOutboxEventEntity sourceOutbox = activeRows.getFirst();
        List<AgentOutboxEventEntity> previousAttempts = lockPreviousAttempts(delivery);
        ProducerScope producerScope = requireProtocolScope(delivery, sourceOutbox,
                "AUTOMATIC_REISSUE_SOURCE_PROTOCOL_INVALID");
        validatePreviousProtocol(delivery, previousAttempts, producerScope,
                "AUTOMATIC_REISSUE_PREVIOUS_PROTOCOL_INVALID");
        String inboxConsumer = inboxConsumer(producerScope);

        AgentConsumerInboxEntity sourceInbox = dao.lockInbox(
                delivery.getTenantId(), delivery.getClientId(),
                inboxConsumer, delivery.getActiveMessageId());
        validateSource(delivery, sourceOutbox, sourceInbox, previousAttempts, inboxConsumer);

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
        byte[] sourceWire = AgentCommandProtocolSupport.wireBytes(
                draft, delivery.getActiveMessageId(), delivery.getActiveAttempt(),
                delivery, sourceOutbox);
        if (!Arrays.equals(sourceWire, sourceOutbox.getWirePayload())
                || !hashEquals(AgentCommandCanonicalCodec.sha256(sourceWire),
                        sourceOutbox.getWirePayloadHash())) {
            throw conflict("SOURCE_WIRE_CANONICAL_DRIFT");
        }

        if (now >= delivery.getExpiresAt()) {
            expireLocked(delivery, sourceInbox, now);
            return new RecoveryOutcome(false, null);
        }
        if (requireDue && "SENT".equals(delivery.getStatus())) {
            Long updatedAt = delivery.getUpdateTime();
            long sentBefore = now > sentAckTimeoutMillis ? now - sentAckTimeoutMillis : 0L;
            if (updatedAt == null || updatedAt <= 0 || updatedAt > sentBefore) return new RecoveryOutcome(false, null);
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
            // A lost ACK is ambiguous, not failure. Keep all delivery/inbox/outbox bytes,
            // timestamps and versions intact so D06 direct/parent terminal ACKs still reconcile.
            // Readback is a scoped observation, never authority to use the manual endpoint.
            return new RecoveryOutcome(false, new AgentCommandRecoveryDeferral(
                    delivery.getTenantId(), delivery.getClientId(), delivery.getTaskId(),
                    delivery.getWorkItemId(), delivery.getTargetAgentId(), delivery.getId(),
                    delivery.getActiveMessageId(), delivery.getActiveAttempt(),
                    delivery.getVersion(), policyDecision.action().name(), now,
                    policyDecision.eligibleAt(), delivery.getExpiresAt()));
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
        byte[] newWire = producerScope == null
                ? AgentCommandCanonicalCodec.wireBytes(draft, newMessageId, nextAttempt)
                : AgentCommandCanonicalCodec.wireBytesV2(
                    draft, newMessageId, delivery.getId(), nextAttempt, producerScope);
        byte[] newWireHash = AgentCommandCanonicalCodec.sha256(newWire);

        if (!allows(delivery.getTenantId(), delivery.getClientId())
                || !dispatcher.isExactAgentConnected(
                        delivery.getTenantId(), delivery.getClientId(), delivery.getTargetAgentId())) {
            return new RecoveryOutcome(false, null);
        }

        int deliveryRows = dao.reissueDelivery(
                delivery, newMessageId, requestedBy, reason,
                AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER, now);
        if (deliveryRows == 0) return new RecoveryOutcome(false, null);
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
        applyReissueProtocol(outbox, delivery, producerScope);
        if (dao.insertOutbox(outbox) != 1) {
            throw new IllegalStateException("reissue outbox insert did not affect exactly one row");
        }
        if (outbox.getId() == null || outbox.getId() <= 0) {
            throw new IllegalStateException("reissue outbox insert did not return a generated id");
        }
        return new RecoveryOutcome(true, null);
    }

    private <T> T withProducerAdmission(
            AgentCommandDeliveryEntity hint, String deniedReason, Supplier<T> work) {
        ProducerScope producerScope;
        try {
            producerScope = AgentCommandProtocolSupport.producerScopeOrLegacy(hint);
        } catch (IllegalArgumentException invalid) {
            throw conflict(deniedReason);
        }
        if (producerScope == null) return work.get();
        if (admissionGuard == null) throw conflict(deniedReason);
        try {
            return admissionGuard.withProducerAdmission(
                    producerScope, TenantModuleAdmissionGuard.Operation.WRITE, mode -> {
                        if (mode != TenantModuleAdmissionGuard.Mode.DOMAIN) {
                            throw conflict(deniedReason);
                        }
                        return work.get();
                    });
        } catch (RecoveryConflictException denied) {
            throw denied;
        } catch (RuntimeException denied) {
            throw conflict(deniedReason);
        }
    }

    private ProducerScope requireProtocolScope(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox, String reason) {
        try {
            return AgentCommandProtocolSupport.producerScope(delivery, outbox);
        } catch (IllegalArgumentException invalid) {
            throw conflict(reason);
        }
    }

    private void validatePreviousProtocol(
            AgentCommandDeliveryEntity delivery, List<AgentOutboxEventEntity> previousAttempts,
            ProducerScope expected, String reason) {
        for (AgentOutboxEventEntity previous : previousAttempts) {
            if (!Objects.equals(expected, requireProtocolScope(delivery, previous, reason))) {
                throw conflict(reason);
            }
        }
    }

    private boolean sameProtocolHint(
            AgentCommandDeliveryEntity hint, AgentCommandDeliveryEntity locked) {
        if (!Objects.equals(hint.getId(), locked.getId())
                || !Objects.equals(hint.getTenantId(), locked.getTenantId())
                || !Objects.equals(hint.getClientId(), locked.getClientId())
                || !Objects.equals(hint.getProtocolVersion(), locked.getProtocolVersion())) {
            return false;
        }
        try {
            return Objects.equals(
                    AgentCommandProtocolSupport.producerScopeOrLegacy(hint),
                    AgentCommandProtocolSupport.producerScopeOrLegacy(locked));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void applyReissueProtocol(
            AgentOutboxEventEntity outbox, AgentCommandDeliveryEntity delivery,
            ProducerScope producerScope) {
        if (producerScope == null) {
            outbox.setProtocolVersion(delivery.getProtocolVersion());
        } else {
            AgentCommandProtocolSupport.apply(outbox, producerScope);
        }
    }

    private String inboxConsumer(ProducerScope producerScope) {
        return producerScope == null
                ? AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1
                : AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2;
    }

    private record RecoveryOutcome(boolean reissued, AgentCommandRecoveryDeferral deferral) {
    }

    private void validateRecoverableDelivery(
            AgentCommandDeliveryEntity delivery, boolean requireDue, long now) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        boolean retry = "RETRY".equals(delivery.getStatus());
        boolean sent = "SENT".equals(delivery.getStatus());
        boolean expiryValid = delivery.getExpiresAt() != null && delivery.getExpiresAt() > 0;
        boolean waitingShape = waiting && expiryValid
                && delivery.getNextRetryAt() != null && delivery.getNextRetryAt() > 0
                && delivery.getNextRetryAt() < delivery.getExpiresAt()
                && (!requireDue || delivery.getNextRetryAt() <= now)
                && AGENT_OFFLINE.equals(delivery.getLastError());
        boolean retryShape = retry && workerScope != null && expiryValid
                && delivery.getNextRetryAt() != null && delivery.getNextRetryAt() > 0
                && delivery.getNextRetryAt() < delivery.getExpiresAt()
                && (!requireDue || delivery.getNextRetryAt() <= now)
                && executionRetryError(delivery.getLastError());
        boolean sentShape = sent && expiryValid
                && delivery.getNextRetryAt() == null
                && delivery.getLastError() == null
                && delivery.getUpdateTime() != null && delivery.getUpdateTime() > 0;
        if (!exact(delivery.getTenantId(), 50) || !exact(delivery.getClientId(), 50)
                || delivery.getId() == null || delivery.getId() <= 0
                || !exact(delivery.getCommandId(), 100) || !exact(delivery.getTaskId(), 100)
                || !exact(delivery.getTargetAgentId(), 100)
                || !AgentCommandCanonicalCodec.isSupportedCommandType(delivery.getCommandType())
                || (!waitingShape && !retryShape && !sentShape)
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() <= 0
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || !delivery.getAttemptCount().equals(delivery.getActiveAttempt())
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
            List<AgentOutboxEventEntity> previousAttempts,
            String expectedConsumer) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        boolean retry = "RETRY".equals(delivery.getStatus());
        boolean waitingInbox = waiting
                && "WAITING_AGENT".equals(inbox == null ? null : inbox.getStatus())
                && "WAITING_AGENT".equals(inbox.getResultStatus())
                && inbox.getNextRetryAt() != null
                && inbox.getNextRetryAt().equals(delivery.getNextRetryAt())
                && inbox.getProcessedAt() != null && inbox.getProcessedAt() > 0
                && inbox.getNextRetryAt() > inbox.getProcessedAt()
                && AGENT_OFFLINE.equals(inbox.getLastError());
        boolean retryInbox = retry && workerScope != null
                && "RETRY".equals(inbox == null ? null : inbox.getStatus())
                && "RETRY".equals(inbox.getResultStatus())
                && inbox.getNextRetryAt() != null
                && inbox.getNextRetryAt().equals(delivery.getNextRetryAt())
                && inbox.getProcessedAt() != null && inbox.getProcessedAt() > 0
                && inbox.getNextRetryAt() > inbox.getProcessedAt()
                && executionRetryError(inbox.getLastError())
                && Objects.equals(inbox.getLastError(), delivery.getLastError());
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
                || !expectedConsumer.equals(inbox.getConsumerName())
                || !delivery.getActiveMessageId().equals(inbox.getMessageId())
                || !outbox.getEventId().equals(inbox.getEventId())
                || !delivery.getCommandId().equals(inbox.getCommandId())
                || !Objects.equals(delivery.getId(), inbox.getDeliveryId())
                || !Arrays.equals(outbox.getWirePayload(), inbox.getWirePayload())
                || !hashEquals(outbox.getWirePayloadHash(), inbox.getWirePayloadHash())
                || (!waitingInbox && !retryInbox && !sentInbox)
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

    private void expireLocked(
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox, long now) {
        boolean waiting = "WAITING_AGENT".equals(delivery.getStatus());
        boolean retry = "RETRY".equals(delivery.getStatus());
        if (delivery.getVersion() == Long.MAX_VALUE
                || ((waiting || retry) && inbox.getVersion() == Long.MAX_VALUE)) {
            throw conflict("EXPIRY_VERSION_EXHAUSTED");
        }
        requireOne(dao.expireDelivery(delivery, MESSAGE_EXPIRED, now),
                "delivery expiry");
        if (waiting) {
            requireOne(dao.expireWaitingInbox(inbox, MESSAGE_EXPIRED, now),
                    "waiting Inbox expiry");
        } else if (retry) {
            requireOne(dao.expireRetryInbox(inbox, MESSAGE_EXPIRED, now),
                    "retry Inbox expiry");
        }
    }

    private boolean executionRetryError(String error) {
        return AgentCommandRabbitV2Consumer.EXECUTION_RETRYABLE.equals(error)
                || AgentCommandRabbitV2Consumer.EXECUTION_UNCERTAIN.equals(error);
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

    private record Route(String destination, String routingKey) { }

    private static final class RecoveryConflictException extends RuntimeException {
        private final String reason;
        private RecoveryConflictException(String reason) {
            super(reason);
            this.reason = reason;
        }
    }
}
