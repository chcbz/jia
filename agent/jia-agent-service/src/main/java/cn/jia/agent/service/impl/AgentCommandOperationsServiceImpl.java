package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentCommandOperationsProperties;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandMetricCount;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationResult;
import cn.jia.agent.entity.AgentCommandOperationType;
import cn.jia.agent.entity.AgentCommandOperationsException;
import cn.jia.agent.entity.AgentCommandOpsMetrics;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.agent.service.AgentCommandOperationsService;
import cn.jia.agent.service.AgentCommandReissueService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** D09 privileged operations plane. Rabbit publication never occurs in a database transaction. */
public final class AgentCommandOperationsServiceImpl implements AgentCommandOperationsService {
    private static final long MAX_CLOCK = Long.MAX_VALUE - 86_400_000L;
    private static final List<String> DELIVERY_STATUSES = List.of(
            "PENDING", "PUBLISHED", "CONSUMED", "SENT", "RECEIVED", "STARTED",
            "SUCCEEDED", "WAITING_AGENT", "RETRY", "FAILED", "REJECTED", "EXPIRED", "DEAD");
    private static final List<String> INBOX_STATUSES = List.of(
            "RECEIVED", "PROCESSING", "PROCESSED", "WAITING_AGENT", "RETRY",
            "FAILED", "EXPIRED", "DEAD");
    private static final List<String> INBOX_RESULTS = List.of(
            "NONE", "SENT", "WAITING_AGENT", "RETRY", "FAILED", "EXPIRED", "DEAD");
    private static final List<String> OUTBOX_STATUSES = List.of(
            "PENDING", "CLAIMED", "RETRY", "PUBLISHED", "FAILED", "EXPIRED", "DEAD");

    private final AgentCommandOperationsDao dao;
    private final AgentRabbitSafetyGate gate;
    private final AgentRabbitTopologyManifest manifest;
    private final AgentCommandDlqRedriver redriver;
    private final AgentCommandReissueService reissueService;
    private final AgentCommandOperationsProperties settings;
    private final TransactionTemplate requiresNew;
    private final Supplier<UUID> operationIds;
    private final LongSupplier clock;

    public AgentCommandOperationsServiceImpl(
            AgentCommandOperationsDao dao,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentCommandDlqRedriver redriver,
            AgentCommandReissueService reissueService,
            AgentCommandOperationsProperties settings,
            PlatformTransactionManager transactionManager) {
        this(dao, gate, manifest, redriver, reissueService, settings,
                transactionManager, UUID::randomUUID, System::currentTimeMillis);
    }

    AgentCommandOperationsServiceImpl(
            AgentCommandOperationsDao dao,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentCommandDlqRedriver redriver,
            AgentCommandReissueService reissueService,
            AgentCommandOperationsProperties settings,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> operationIds) {
        this(dao, gate, manifest, redriver, reissueService, settings, transactionManager,
                operationIds, System::currentTimeMillis);
    }

    AgentCommandOperationsServiceImpl(
            AgentCommandOperationsDao dao,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentCommandDlqRedriver redriver,
            AgentCommandReissueService reissueService,
            AgentCommandOperationsProperties settings,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> operationIds,
            LongSupplier clock) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.redriver = redriver;
        this.reissueService = reissueService;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.operationIds = Objects.requireNonNull(operationIds, "operationIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requiresNew = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AgentCommandOpsMetrics metrics(String tenantId, String clientId, long now) {
        requireReadScope(tenantId, clientId);
        requireNow(now);
        long before = Math.addExact(now, settings.expiryProximityMillis());
        Long oldest = dao.oldestOutboxEpoch(tenantId, clientId);
        if (oldest != null && (oldest <= 0 || oldest > now)) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        long oldestAge = oldest == null ? 0 : (now - oldest) / 1_000L;
        return new AgentCommandOpsMetrics(
                boundedCounts(DELIVERY_STATUSES, dao.countDeliveryStatuses(tenantId, clientId)),
                boundedCounts(INBOX_STATUSES, dao.countInboxStatuses(tenantId, clientId)),
                boundedCounts(INBOX_RESULTS, dao.countInboxResults(tenantId, clientId)),
                boundedCounts(OUTBOX_STATUSES, dao.countOutboxStatuses(tenantId, clientId)),
                nonNegativeFinite(dao.averageAckLatencySeconds(tenantId, clientId)),
                nonNegative(dao.countOutboxBacklog(tenantId, clientId)),
                oldestAge,
                nonNegative(dao.countPublishFailures(tenantId, clientId)),
                nonNegative(dao.countDlq(tenantId, clientId, now)),
                nonNegative(dao.countWaitingDue(tenantId, clientId, now)),
                nonNegative(dao.countSentUnacknowledged(tenantId, clientId)),
                nonNegative(dao.countReconnectQueueDepth(tenantId, clientId, now)),
                nonNegative(dao.countExpiryProximity(tenantId, clientId, now, before)),
                boundedOperationCounts(dao.countOperationOutcomes(tenantId, clientId)),
                now);
    }

    @Override
    public List<AgentCommandDlqEntry> listDlq(
            String tenantId, String clientId, long afterDeliveryId, int limit) {
        requireReadScope(tenantId, clientId);
        requirePage(afterDeliveryId, limit);
        long now = clock.getAsLong();
        requireNow(now);
        List<AgentCommandDlqEntry> rows = Objects.requireNonNullElse(
                dao.listDlq(tenantId, clientId, afterDeliveryId, now, limit), List.of());
        if (rows.size() > limit || rows.stream().anyMatch(Objects::isNull)) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        validateDlqPage(rows, afterDeliveryId, now);
        return List.copyOf(rows);
    }

    @Override
    public List<AgentCommandOperationAuditEntry> listAudit(
            String tenantId, String clientId, long afterId, int limit) {
        requireReadScope(tenantId, clientId);
        requirePage(afterId, limit);
        List<AgentCommandOperationAuditEntry> rows = Objects.requireNonNullElse(
                dao.listAudit(tenantId, clientId, afterId, limit), List.of());
        if (rows.size() > limit || rows.stream().anyMatch(Objects::isNull)) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        validateAuditPage(rows, afterId);
        return List.copyOf(rows);
    }

    @Override
    public AgentCommandOperationResult brokerRedrive(
            AgentCommandOperationRequest request, long now) {
        validateRequest(request, false, now);
        requireWriteEnabled(settings.redriveEnabled(), request.tenantId(), request.clientId());
        String operationId = nextOperationId();
        Source source;
        try {
            source = requiresNew.execute(status -> prepareSource(
                    operationId, AgentCommandOperationType.BROKER_REDRIVE, request, now, true));
            if (source == null) throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        } catch (AgentCommandOperationsException rejected) {
            auditRejected(operationId, AgentCommandOperationType.BROKER_REDRIVE, request, now,
                    safeError(rejected));
            throw rejected;
        } catch (RuntimeException rejected) {
            auditRejected(operationId, AgentCommandOperationType.BROKER_REDRIVE, request, now,
                    "SOURCE_VALIDATION_FAILED");
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }

        assertNoDatabaseTransaction();
        AgentRabbitPublishResult publishResult;
        try {
            AgentRabbitTopologyManifest.PublishRoute route = manifest.defaultCommandPublishRoute();
            publishResult = redriver == null ? null : redriver.redrive(
                    new AgentConfirmedPublishRequest(
                            route.destination(), route.routingKey(), source.wirePayload(),
                            source.wireHash(), source.messageId(), source.eventId(),
                            source.deliveryId(), source.commandId(), source.tenantId(),
                            source.clientId(), source.taskId(), source.targetAgentId(),
                            source.commandType(), source.activeAttempt(), source.expiresAt(),
                            manifest.sha256(), AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY),
                    settings.confirmTimeoutMillis(), settings.dlqScanLimit());
        } catch (RuntimeException ignored) {
            publishResult = null;
        }
        boolean succeeded = publishResult != null
                && publishResult.type() == AgentRabbitPublishResult.Type.ACK;
        String errorCode = succeeded ? null : publishError(publishResult);
        long completedAt = completionTime(now);
        appendResult(operationId, AgentCommandOperationType.BROKER_REDRIVE,
                request, source, null, succeeded ? "SUCCEEDED" : "FAILED", errorCode,
                now, completedAt);
        if (!succeeded) throw failure(AgentCommandOperationsException.Reason.PUBLISH_FAILED);
        return result(operationId, AgentCommandOperationType.BROKER_REDRIVE,
                source, null, "SUCCEEDED", null, completedAt);
    }

    @Override
    public AgentCommandOperationResult manualReissue(
            AgentCommandOperationRequest request, long now) {
        validateRequest(request, true, now);
        requireWriteEnabled(settings.reissueEnabled(), request.tenantId(), request.clientId());
        String operationId = nextOperationId();
        Source source;
        try {
            source = requiresNew.execute(status -> prepareSource(
                    operationId, AgentCommandOperationType.MANUAL_REISSUE, request, now, false));
            if (source == null) throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        } catch (AgentCommandOperationsException rejected) {
            auditRejected(operationId, AgentCommandOperationType.MANUAL_REISSUE, request, now,
                    safeError(rejected));
            throw rejected;
        } catch (RuntimeException rejected) {
            auditRejected(operationId, AgentCommandOperationType.MANUAL_REISSUE, request, now,
                    "SOURCE_VALIDATION_FAILED");
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }

        ManualCompletion completion;
        try {
            completion = requiresNew.execute(status -> {
                if (reissueService == null) {
                    throw failure(AgentCommandOperationsException.Reason.OPERATION_DISABLED);
                }
                AgentCommandManualReissueResult reissued =
                        reissueService.reissueManually(request, now);
                validateReissueResult(source, reissued);
                long completedAt = completionTime(now);
                insertAudit(resultAudit(operationId, AgentCommandOperationType.MANUAL_REISSUE,
                        request, source, reissued, "SUCCEEDED", null, now, completedAt));
                return new ManualCompletion(reissued, completedAt);
            });
            if (completion == null) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
        } catch (AgentCommandOperationsException rejected) {
            if (rejected.reason() == AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE) {
                throw rejected;
            }
            long completedAt = completionTime(now);
            appendResult(operationId, AgentCommandOperationType.MANUAL_REISSUE,
                    request, source, null, "REJECTED", "REISSUE_REJECTED", now, completedAt);
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        } catch (RuntimeException rejected) {
            long completedAt = completionTime(now);
            appendResult(operationId, AgentCommandOperationType.MANUAL_REISSUE,
                    request, source, null, "REJECTED", "REISSUE_REJECTED", now, completedAt);
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        return result(operationId, AgentCommandOperationType.MANUAL_REISSUE,
                source, completion.reissued(), "SUCCEEDED", null, completion.completedAt());
    }

    private Source prepareSource(
            String operationId,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            long now,
            boolean redrive) {
        requireWriteEnabled(redrive ? settings.redriveEnabled() : settings.reissueEnabled(),
                request.tenantId(), request.clientId());
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                request.tenantId(), request.clientId(), request.deliveryId());
        if (delivery == null
                || !request.taskId().equals(delivery.getTaskId())
                || !request.targetAgentId().equals(delivery.getTargetAgentId())
                || !request.sourceMessageId().equals(delivery.getActiveMessageId())) {
            throw failure(AgentCommandOperationsException.Reason.NOT_FOUND_OR_FORBIDDEN);
        }
        List<AgentOutboxEventEntity> outboxes = dao.lockActiveOutboxes(
                request.tenantId(), request.clientId(), request.deliveryId(),
                request.sourceMessageId());
        if (outboxes == null || outboxes.size() != 1) {
            throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
        }
        AgentOutboxEventEntity outbox = outboxes.getFirst();
        AgentConsumerInboxEntity inbox = dao.lockInbox(
                request.tenantId(), request.clientId(),
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, request.sourceMessageId());
        Source source = validateSource(delivery, outbox, inbox, request, now, redrive);
        insertAudit(requestAudit(operationId, type, request, source, now));
        return source;
    }

    private Source validateSource(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox,
            AgentCommandOperationRequest request,
            long now,
            boolean redrive) {
        AgentRabbitTopologyManifest.PublishRoute route = manifest.defaultCommandPublishRoute();
        if (delivery.getId() == null || delivery.getId() != request.deliveryId()
                || !sameScope(delivery, outbox)
                || !Objects.equals(delivery.getId(), outbox.getDeliveryId())
                || !delivery.getCommandId().equals(outbox.getCommandId())
                || !delivery.getActiveMessageId().equals(outbox.getMessageId())
                || !delivery.getTaskId().equals(outbox.getAggregateId())
                || !"task".equals(outbox.getAggregateType())
                || !route.destination().equals(outbox.getDestination())
                || !route.routingKey().equals(outbox.getRoutingKey())
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || delivery.getExpiresAt() == null
                || !Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                || now >= delivery.getExpiresAt()
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || outbox.getLeaseOwner() != null || outbox.getLeaseUntil() != null
                || !AgentCommandCanonicalCodec.isSupportedCommandType(delivery.getCommandType())
                || !storedHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())
                || !storedHash(outbox.getWirePayload(), outbox.getWirePayloadHash())) {
            throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
        }
        AgentCommandDraft draft;
        try {
            draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload());
        } catch (IllegalArgumentException invalid) {
            throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
        }
        if (!delivery.getCommandId().equals(draft.commandId())
                || !delivery.getTenantId().equals(draft.tenantId())
                || !delivery.getClientId().equals(draft.clientId())
                || !delivery.getTaskId().equals(draft.taskId())
                || !Objects.equals(delivery.getWorkItemId(), draft.workItemId())
                || !delivery.getTargetAgentId().equals(draft.targetAgentId())
                || !delivery.getCommandType().equals(draft.commandType())
                || !Objects.equals(delivery.getExpiresAt(), draft.expiresAt())
                || !Arrays.equals(outbox.getWirePayload(), AgentCommandCanonicalCodec.wireBytes(
                        draft, delivery.getActiveMessageId(), delivery.getActiveAttempt()))) {
            throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
        }
        if (redrive) {
            boolean exactPublished = "PUBLISHED".equals(delivery.getStatus())
                    && delivery.getNextRetryAt() == null
                    && "PUBLISHED".equals(outbox.getStatus())
                    && outbox.getAttemptCount() != null && outbox.getAttemptCount() > 0
                    && outbox.getNextRetryAt() == null
                    && "ACK".equals(outbox.getPublisherConfirmStatus())
                    && outbox.getConfirmedAt() != null && outbox.getConfirmedAt() > 0
                    && outbox.getConfirmError() == null
                    && "NOT_RETURNED".equals(outbox.getMandatoryReturnStatus())
                    && outbox.getReturnedAt() == null && outbox.getReturnReplyCode() == null
                    && outbox.getReturnReplyText() == null
                    && outbox.getPublishedAt() != null && outbox.getPublishedAt() > 0
                    && outbox.getLastError() == null;
            if (!exactPublished || inbox != null) {
                throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
            }
        } else if (!("DEAD".equals(delivery.getStatus())
                || "FAILED".equals(delivery.getStatus()))) {
            throw failure(AgentCommandOperationsException.Reason.SOURCE_FORBIDDEN);
        }
        return new Source(delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getCommandId(), delivery.getTaskId(), delivery.getTargetAgentId(),
                delivery.getCommandType(), outbox.getEventId(), outbox.getMessageId(),
                outbox.getWirePayload(), outbox.getWirePayloadHash(),
                delivery.getActiveAttempt(), delivery.getExpiresAt());
    }

    private void auditRejected(
            String operationId,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            long now,
            String errorCode) {
        try {
            requiresNew.executeWithoutResult(status -> {
                insertAudit(requestAudit(operationId, type, request, null, now));
                insertAudit(resultAudit(operationId, type, request, null, null,
                        "REJECTED", errorCode, now, now));
            });
        } catch (RuntimeException auditFailure) {
            throw failure(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE);
        }
    }

    private void appendResult(
            String operationId,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            Source source,
            AgentCommandManualReissueResult reissued,
            String outcome,
            String errorCode,
            long requestedAt,
            long completedAt) {
        try {
            requiresNew.executeWithoutResult(status -> insertAudit(resultAudit(
                    operationId, type, request, source, reissued, outcome, errorCode,
                    requestedAt, completedAt)));
        } catch (RuntimeException auditFailure) {
            throw failure(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE);
        }
    }

    private AgentCommandOperationAuditEntity requestAudit(
            String operationId,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            Source source,
            long now) {
        return baseAudit(operationId, "REQUEST", type, request, source, now)
                .setOutcome("REQUESTED")
                .setRequestedAt(now);
    }

    private AgentCommandOperationAuditEntity resultAudit(
            String operationId,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            Source source,
            AgentCommandManualReissueResult reissued,
            String outcome,
            String errorCode,
            long requestedAt,
            long completedAt) {
        AgentCommandOperationAuditEntity audit = baseAudit(
                operationId, "RESULT", type, request, source, completedAt)
                .setOutcome(outcome)
                .setErrorCode(errorCode)
                .setRequestedAt(requestedAt)
                .setCompletedAt(completedAt);
        if (reissued != null) {
            audit.setNewMessageId(reissued.newMessageId());
            audit.setNewAttempt(reissued.newAttempt());
        }
        return audit;
    }

    private AgentCommandOperationAuditEntity baseAudit(
            String operationId,
            String phase,
            AgentCommandOperationType type,
            AgentCommandOperationRequest request,
            Source source,
            long now) {
        AgentCommandOperationAuditEntity audit = new AgentCommandOperationAuditEntity()
                .setOperationId(operationId)
                .setPhase(phase)
                .setOperationType(type.name())
                .setTenantId(request.tenantId())
                .setClientId(request.clientId())
                .setTaskId(request.taskId())
                .setTargetAgentId(request.targetAgentId())
                .setSourceMessageId(request.sourceMessageId())
                .setDeliveryId(request.deliveryId())
                .setRequesterId(request.requesterId())
                .setApproverId(request.approverId())
                .setReason(request.reason())
                .setTicketReference(request.ticketReference())
                .setCreatedBy(request.requesterId())
                .setCreatedAt(now);
        if (source != null) {
            audit.setCommandId(source.commandId());
            audit.setSourceAttempt(source.activeAttempt());
            audit.setWireHash(source.wireHash());
        }
        return audit;
    }

    private void insertAudit(AgentCommandOperationAuditEntity audit) {
        try {
            if (dao.insertAudit(audit) != 1 || audit.getId() == null || audit.getId() <= 0) {
                throw failure(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE);
            }
        } catch (AgentCommandOperationsException unavailable) {
            throw unavailable;
        } catch (RuntimeException unavailable) {
            throw failure(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE);
        }
    }

    private AgentCommandOperationResult result(
            String operationId,
            AgentCommandOperationType type,
            Source source,
            AgentCommandManualReissueResult reissued,
            String outcome,
            String errorCode,
            long now) {
        return new AgentCommandOperationResult(
                operationId, type, outcome, source.deliveryId(), source.commandId(),
                source.messageId(), reissued == null ? null : reissued.newMessageId(),
                source.activeAttempt(), reissued == null ? null : reissued.newAttempt(),
                errorCode, now);
    }

    private void requireReadScope(String tenantId, String clientId) {
        if (!settings.readEnabled()) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_DISABLED);
        }
        requireScope(tenantId, clientId);
        if (!gate.commandOutboxEnabled()) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_DISABLED);
        }
    }

    private void requireWriteEnabled(boolean enabled, String tenantId, String clientId) {
        requireReadScope(tenantId, clientId);
        AgentRabbitActivationState state = gate.state();
        if (!enabled || (state != AgentRabbitActivationState.DISPATCH_CANARY
                && state != AgentRabbitActivationState.DISPATCH_SCOPED)
                || !gate.allowsDispatch(tenantId, clientId)) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_DISABLED);
        }
    }

    private void validateDlqPage(
            List<AgentCommandDlqEntry> rows, long afterDeliveryId, long now) {
        long cursor = afterDeliveryId;
        for (AgentCommandDlqEntry row : rows) {
            boolean redriveCandidate = "PUBLISHED".equals(row.deliveryStatus())
                    && "PUBLISHED".equals(row.outboxStatus())
                    && row.inboxStatus() == null && row.inboxResultStatus() == null
                    && row.publishAttemptCount() > 0 && row.publishedAt() != null
                    && row.publishedAt() > 0 && row.processedAt() == null
                    && row.expiresAt() > now;
            if (!redriveCandidate || row.deliveryId() <= cursor || !exact(row.commandId(), 100)
                    || !exact(row.eventId(), 100) || !exact(row.messageId(), 100)
                    || !exact(row.taskId(), 100) || !exact(row.targetAgentId(), 100)
                    || row.activeAttempt() <= 0 || !hexSha256(row.wireSha256())
                    || row.updatedAt() <= 0) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
            cursor = row.deliveryId();
        }
    }

    private void validateAuditPage(List<AgentCommandOperationAuditEntry> rows, long afterId) {
        long cursor = afterId;
        for (AgentCommandOperationAuditEntry row : rows) {
            boolean requestPhase = "REQUEST".equals(row.phase());
            boolean resultPhase = "RESULT".equals(row.phase());
            boolean manual = AgentCommandOperationType.MANUAL_REISSUE.name()
                    .equals(row.operationType());
            boolean broker = AgentCommandOperationType.BROKER_REDRIVE.name()
                    .equals(row.operationType());
            boolean succeeded = "SUCCEEDED".equals(row.outcome());
            boolean failed = "FAILED".equals(row.outcome());
            boolean sourcePresent = row.commandId() != null || row.sourceAttempt() != null
                    || row.wireSha256() != null;
            boolean sourceComplete = exact(row.commandId(), 100)
                    && row.sourceAttempt() != null && row.sourceAttempt() > 0
                    && hexSha256(row.wireSha256());
            if (row.id() <= cursor || !uuid(row.operationId())
                    || (!requestPhase && !resultPhase) || (!manual && !broker)
                    || !exact(row.taskId(), 100) || !exact(row.targetAgentId(), 100)
                    || (row.commandId() != null && !exact(row.commandId(), 100))
                    || (sourcePresent && !sourceComplete)
                    || !exact(row.sourceMessageId(), 100)
                    || (row.newMessageId() != null && !uuid(row.newMessageId()))
                    || row.deliveryId() <= 0
                    || (row.sourceAttempt() != null && row.sourceAttempt() <= 0)
                    || (row.newAttempt() != null && row.newAttempt() <= 0)
                    || (row.wireSha256() != null && !hexSha256(row.wireSha256()))
                    || !exact(row.requesterId(), 100)
                    || (manual && (!exact(row.approverId(), 100)
                        || row.requesterId().equals(row.approverId())))
                    || (broker && row.approverId() != null)
                    || !exact(row.reason(), 1000) || !exact(row.ticketReference(), 200)
                    || containsCredentialMaterial(row.reason())
                    || containsCredentialMaterial(row.ticketReference())
                    || row.requestedAt() <= 0 || row.createdAt() <= 0
                    || !row.requesterId().equals(row.createdBy())
                    || (requestPhase && (!"REQUESTED".equals(row.outcome())
                        || row.completedAt() != null || row.errorCode() != null
                        || row.newMessageId() != null || row.newAttempt() != null
                        || row.createdAt() != row.requestedAt()))
                    || (resultPhase && ("REQUESTED".equals(row.outcome())
                        || !("SUCCEEDED".equals(row.outcome())
                            || "REJECTED".equals(row.outcome())
                            || "FAILED".equals(row.outcome()))
                        || row.completedAt() == null || row.completedAt() < row.requestedAt()
                        || row.createdAt() != row.completedAt()))
                    || (resultPhase && (succeeded || failed
                        || "REISSUE_REJECTED".equals(row.errorCode())) && !sourceComplete)
                    || (resultPhase && succeeded && row.errorCode() != null)
                    || (resultPhase && !succeeded
                        && (row.errorCode() == null
                            || !row.errorCode().matches("[A-Z0-9_]{1,200}")))
                    || (resultPhase && manual && succeeded
                        && (row.newMessageId() == null || row.sourceAttempt() == null
                            || row.newAttempt() == null
                            || row.newAttempt() != row.sourceAttempt() + 1))
                    || (resultPhase && broker
                        && (row.newMessageId() != null || row.newAttempt() != null))) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
            cursor = row.id();
        }
    }

    private boolean hexSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private void validateReissueResult(
            Source source, AgentCommandManualReissueResult reissued) {
        int expectedAttempt;
        try {
            expectedAttempt = Math.addExact(source.activeAttempt(), 1);
        } catch (ArithmeticException exhausted) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        if (reissued == null || reissued.deliveryId() != source.deliveryId()
                || !source.commandId().equals(reissued.commandId())
                || !source.messageId().equals(reissued.sourceMessageId())
                || reissued.sourceAttempt() != source.activeAttempt()
                || reissued.newAttempt() != expectedAttempt
                || !uuid(reissued.newMessageId()) || !uuid(reissued.newEventId())
                || reissued.newMessageId().equals(source.messageId())
                || reissued.newMessageId().equals(source.eventId())
                || reissued.newMessageId().equals(source.commandId())
                || reissued.newEventId().equals(source.eventId())
                || reissued.newEventId().equals(source.messageId())
                || reissued.newEventId().equals(source.commandId())
                || reissued.newEventId().equals(reissued.newMessageId())) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
    }

    private boolean uuid(String value) {
        if (!exact(value, 36)) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void validateRequest(AgentCommandOperationRequest request, boolean approverRequired, long now) {
        if (request == null || request.deliveryId() <= 0 || !exact(request.tenantId(), 50)
                || !exact(request.clientId(), 50) || !exact(request.taskId(), 100)
                || !exact(request.targetAgentId(), 100) || !exact(request.sourceMessageId(), 100)
                || !exact(request.requesterId(), 100) || !exact(request.reason(), 1000)
                || !exact(request.ticketReference(), 200) || now <= 0 || now > MAX_CLOCK
                || containsCredentialMaterial(request.reason())
                || containsCredentialMaterial(request.ticketReference())
                || (approverRequired && (!exact(request.approverId(), 100)
                    || request.requesterId().equals(request.approverId())))
                || (!approverRequired && request.approverId() != null)) {
            throw failure(AgentCommandOperationsException.Reason.INVALID_REQUEST);
        }
    }

    private void requirePage(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > settings.maxPageSize()) {
            throw failure(AgentCommandOperationsException.Reason.INVALID_REQUEST);
        }
    }

    private void requireNow(long now) {
        if (now <= 0 || now > MAX_CLOCK) {
            throw failure(AgentCommandOperationsException.Reason.INVALID_REQUEST);
        }
    }

    private void requireScope(String tenantId, String clientId) {
        if (!exact(tenantId, 50) || !exact(clientId, 50)) {
            throw failure(AgentCommandOperationsException.Reason.INVALID_REQUEST);
        }
    }

    private Map<String, Long> boundedCounts(
            List<String> allowlist, List<AgentCommandMetricCount> rows) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (String label : allowlist) result.put(label, 0L);
        Set<String> seen = new HashSet<>();
        List<AgentCommandMetricCount> safeRows = rows == null ? List.of() : rows;
        for (AgentCommandMetricCount row : safeRows) {
            if (row == null || !result.containsKey(row.label()) || row.count() < 0
                    || !seen.add(row.label())) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
            result.put(row.label(), row.count());
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Long> boundedOperationCounts(List<AgentCommandMetricCount> rows) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (AgentCommandOperationType type : AgentCommandOperationType.values()) {
            for (String outcome : List.of("REQUESTED", "SUCCEEDED", "REJECTED", "FAILED")) {
                result.put(type.name() + ':' + outcome, 0L);
            }
        }
        Set<String> seen = new HashSet<>();
        List<AgentCommandMetricCount> safeRows = rows == null ? List.of() : rows;
        for (AgentCommandMetricCount row : safeRows) {
            if (row == null || !result.containsKey(row.label()) || row.count() < 0
                    || !seen.add(row.label())) {
                throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
            }
            result.put(row.label(), row.count());
        }
        return Collections.unmodifiableMap(result);
    }

    private double nonNegativeFinite(Double value) {
        if (value == null) return 0.0D;
        if (!Double.isFinite(value) || value < 0.0D) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        return value;
    }

    private long nonNegative(long value) {
        if (value < 0) throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        return value;
    }

    private boolean sameScope(AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        return outbox != null && Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                && Objects.equals(delivery.getClientId(), outbox.getClientId());
    }

    private boolean storedHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && MessageDigest.isEqual(AgentCommandCanonicalCodec.sha256(bytes), hash);
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= maxLength
                && !hasUnpairedSurrogate(value)
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsCredentialMaterial(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("amqp://") || lower.contains("amqps://")
                || lower.contains("authorization:") || lower.contains("bearer ")
                || lower.contains("api_key=") || lower.contains("api-key=")
                || lower.contains("access_token=") || lower.contains("access-token=")
                || lower.contains("password=") || lower.contains("credential=")
                || lower.contains("secret=") || lower.contains("token=")
                || lower.contains("username=") || lower.contains("private key");
    }

    private long completionTime(long requestedAt) {
        long completedAt = clock.getAsLong();
        if (completedAt <= 0 || completedAt > MAX_CLOCK) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
        return Math.max(requestedAt, completedAt);
    }

    private String nextOperationId() {
        UUID id = operationIds.get();
        if (id == null) throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        return id.toString();
    }

    private String safeError(AgentCommandOperationsException failure) {
        return failure.reason().name();
    }

    private String publishError(AgentRabbitPublishResult result) {
        if (result == null || result.errorCode() == null
                || !result.errorCode().matches("[A-Z0-9_]{1,200}")) {
            return "RABBIT_PUBLISH_FAILED";
        }
        return result.errorCode();
    }

    private void assertNoDatabaseTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw failure(AgentCommandOperationsException.Reason.OPERATION_CONFLICT);
        }
    }

    private AgentCommandOperationsException failure(AgentCommandOperationsException.Reason reason) {
        return new AgentCommandOperationsException(reason);
    }

    private record ManualCompletion(
            AgentCommandManualReissueResult reissued, long completedAt) {
    }

    private record Source(
            String tenantId,
            String clientId,
            long deliveryId,
            String commandId,
            String taskId,
            String targetAgentId,
            String commandType,
            String eventId,
            String messageId,
            byte[] wirePayload,
            byte[] wireHash,
            int activeAttempt,
            long expiresAt) {
        private Source {
            wirePayload = Arrays.copyOf(wirePayload, wirePayload.length);
            wireHash = Arrays.copyOf(wireHash, wireHash.length);
        }
        @Override public byte[] wirePayload() { return Arrays.copyOf(wirePayload, wirePayload.length); }
        @Override public byte[] wireHash() { return Arrays.copyOf(wireHash, wireHash.length); }
    }
}
