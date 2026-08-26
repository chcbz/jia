package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentHallCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentCommandShadowIntentException;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Fail-closed REQUIRED writer. No Rabbit client or publish path is present here. */
public final class AgentCommandTransportWriterImpl implements AgentCommandTransportWriter {
    public static final String DB_SHADOW_MARKER = "DB_SHADOW_CAPTURE_ONLY";
    public static final String MQ_SHADOW_MARKER = "MQ_SHADOW_CAPTURE_ONLY";
    public static final String DISPATCH_ELIGIBLE_MARKER = "DISPATCH_ELIGIBLE_V1";
    public static final String DISPATCH_SCOPE_MARKER = "DISPATCH_SCOPE_CAPTURE_ONLY";
    private static final Set<String> RUNTIME_STATUSES = Set.of(
            AgentConstants.STATUS_ONLINE, AgentConstants.STATUS_BUSY,
            AgentConstants.STATUS_OFFLINE, AgentConstants.STATUS_ERROR);

    private final AgentCommandTransportDao dao;
    private final AgentHallCommandTransportDao hallDao;
    private final AgentRabbitSafetyGate gate;
    private final AgentService agentService;
    private final AgentTaskCollaborationAccessService accessService;
    private final TransactionTemplate transaction;
    private final Supplier<UUID> uuidSupplier;

    public AgentCommandTransportWriterImpl(
            AgentHallCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager) {
        this(dao, dao, gate, agentService, accessService,
                transactionManager, UUID::randomUUID);
    }

    /** Compatibility constructor for the pre-D08 TASK_INVITE producer and its focused tests. */
    AgentCommandTransportWriterImpl(
            AgentCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this(dao, null, gate, null, null, transactionManager, uuidSupplier);
    }

    AgentCommandTransportWriterImpl(
            AgentHallCommandTransportDao dao,
            AgentRabbitSafetyGate gate,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this(dao, dao, gate, agentService, accessService,
                transactionManager, uuidSupplier);
    }

    private AgentCommandTransportWriterImpl(
            AgentCommandTransportDao dao,
            AgentHallCommandTransportDao hallDao,
            AgentRabbitSafetyGate gate,
            AgentService agentService,
            AgentTaskCollaborationAccessService accessService,
            PlatformTransactionManager transactionManager,
            Supplier<UUID> uuidSupplier) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.hallDao = hallDao;
        this.gate = Objects.requireNonNull(gate, "gate");
        this.agentService = agentService;
        this.accessService = accessService;
        this.uuidSupplier = Objects.requireNonNull(uuidSupplier, "uuidSupplier");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentCommandTransportWriteResult write(AgentCommandDraft draft) {
        requireEnabled();
        byte[] commandBytes = AgentCommandCanonicalCodec.businessBytes(draft);
        if (AgentCommandCanonicalCodec.isHallIntentCommand(draft)) {
            throw new IllegalStateException(
                    "Hall commands require transactional caller and target authorization");
        }
        byte[] commandHash = AgentCommandCanonicalCodec.sha256(commandBytes);
        return transaction.execute(status -> writeInTransaction(
                draft, commandBytes, commandHash));
    }

    @Override
    public AgentCommandTransportWriteResult writeAuthorizedHall(
            AgentCommandDraft draft, String callerAgentId) {
        requireEnabled();
        byte[] commandBytes = AgentCommandCanonicalCodec.businessBytes(draft);
        if (!AgentCommandCanonicalCodec.isHallIntentCommand(draft)) {
            throw new IllegalArgumentException("authorized Hall writer requires a Hall command");
        }
        byte[] commandHash = AgentCommandCanonicalCodec.sha256(commandBytes);
        return transaction.execute(status -> {
            requireAuthorizedHall(draft, callerAgentId);
            return writeInTransaction(draft, commandBytes, commandHash);
        });
    }

    private void requireEnabled() {
        if (!gate.commandOutboxEnabled()) {
            throw new IllegalStateException(
                    "Agent command transport writer called while command outbox is disabled");
        }
    }

    /**
     * Frozen order: task root -> member rows -> identity/runtime rows -> delivery -> outbox.
     * The existing ForUpdate access contracts own the task/member and identity/runtime locks.
     */
    private void requireAuthorizedHall(AgentCommandDraft draft, String callerAgentId) {
        if (hallDao == null || agentService == null || accessService == null) {
            throw new IllegalStateException("Hall command authorization services are unavailable");
        }
        requireExact(callerAgentId, "callerAgentId", 100);
        List<String> lockedAgents = Stream.of(callerAgentId, draft.targetAgentId())
                .distinct().sorted(AgentCommandTransportWriterImpl::compareUtf8Unsigned)
                .toList();
        for (String agentId : lockedAgents) {
            AgentTaskAccessLevel access = accessService.resolveMemberAccessForUpdate(
                    draft.tenantId(), draft.clientId(), draft.taskId(), agentId);
            if (access == null || !access.canWrite()) {
                throw new IllegalArgumentException(
                        "Caller or target is not a writable task member");
            }
        }
        for (String agentId : lockedAgents) {
            AgentRuntimeDTO runtime;
            try {
                runtime = agentService.requireApiKeyOwnedAgentForUpdate(
                        draft.clientId(), draft.tenantId(), agentId);
            } catch (AgentServiceImpl.AgentBizException denied) {
                throw new IllegalArgumentException(
                        "Caller or target is not active in the trusted owner scope", denied);
            }
            if (runtime == null || !agentId.equals(runtime.getAgentId())
                    || !RUNTIME_STATUSES.contains(runtime.getStatus())) {
                throw new IllegalArgumentException(
                        "Caller or target is not active in the trusted owner scope");
            }
        }
    }

    private AgentCommandTransportWriteResult writeInTransaction(
            AgentCommandDraft draft, byte[] commandBytes, byte[] commandHash) {
        Admission admission = admission(draft.tenantId(), draft.clientId());
        AgentCommandDeliveryEntity existing = dao.lockDelivery(
                draft.tenantId(), draft.clientId(), draft.commandId());
        if (existing != null) {
            return duplicateOrConflict(existing, draft, commandBytes, commandHash, admission);
        }

        String messageId = nextUuid("messageId");
        String eventId = nextUuid("eventId");
        byte[] wireBytes = AgentCommandCanonicalCodec.wireBytes(draft, messageId);
        byte[] wireHash = AgentCommandCanonicalCodec.sha256(wireBytes);
        String status = admission.dispatchEligible() ? "PENDING" : "DEAD";
        String marker = admission.marker();
        AgentRabbitTopologyManifest.PublishRoute route =
                AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();

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
            if (winner == null) {
                throw new IllegalStateException(
                        "Concurrent command identity conflict did not expose the winning row",
                        concurrent);
            }
            return duplicateOrConflict(
                    winner, draft, commandBytes, commandHash, admission);
        }
        if (delivery.getId() == null || delivery.getId() <= 0) {
            throw new IllegalStateException(
                    "delivery insert did not return a generated id");
        }

        AgentOutboxEventEntity outbox = newOutbox(
                delivery.getId(), draft, eventId, messageId, wireBytes, wireHash,
                status, marker);
        requireOne(dao.insertOutbox(outbox), "outbox insert");
        return new AgentCommandTransportWriteResult(
                delivery.getId(), draft.commandId(), messageId, eventId, false);
    }

    private AgentOutboxEventEntity newOutbox(
            long deliveryId, AgentCommandDraft draft, String eventId, String messageId,
            byte[] wireBytes, byte[] wireHash, String status, String marker) {
        AgentRabbitTopologyManifest.PublishRoute route =
                AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        AgentOutboxEventEntity outbox = new AgentOutboxEventEntity()
                .setEventId(eventId)
                .setMessageId(messageId)
                .setCommandId(draft.commandId())
                .setDeliveryId(deliveryId)
                .setAggregateType("task")
                .setAggregateId(draft.taskId())
                .setDestination(route.destination())
                .setRoutingKey(route.routingKey())
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
        return outbox;
    }

    private Admission admission(String tenantId, String clientId) {
        return switch (gate.state()) {
            case OFF -> throw new IllegalStateException(
                    "Agent command transport writer called while command outbox is disabled");
            case DB_SHADOW -> new Admission(false, DB_SHADOW_MARKER);
            case MQ_SHADOW -> new Admission(false, MQ_SHADOW_MARKER);
            case DISPATCH_CANARY, DISPATCH_SCOPED -> gate.allowsDispatch(tenantId, clientId)
                    ? new Admission(true, DISPATCH_ELIGIBLE_MARKER)
                    : new Admission(false, DISPATCH_SCOPE_MARKER);
        };
    }

    private AgentCommandTransportWriteResult duplicateOrConflict(
            AgentCommandDeliveryEntity existing,
            AgentCommandDraft draft,
            byte[] commandBytes,
            byte[] commandHash,
            Admission admission) {
        ComparableCommand comparable = comparableCommand(
                existing, draft, commandBytes, commandHash);
        boolean identityMatches = Objects.equals(existing.getTenantId(), draft.tenantId())
                && Objects.equals(existing.getClientId(), draft.clientId())
                && Objects.equals(existing.getCommandId(), draft.commandId())
                && Objects.equals(existing.getTaskId(), draft.taskId())
                && Objects.equals(existing.getWorkItemId(), draft.workItemId())
                && Objects.equals(existing.getTargetAgentId(), draft.targetAgentId())
                && Objects.equals(existing.getCommandType(), draft.commandType())
                && Objects.equals(existing.getExpiresAt(), comparable.expiresAt());
        boolean bytesMatch = existing.getCommandPayloadHash() != null
                && existing.getCommandPayload() != null
                && MessageDigest.isEqual(
                        existing.getCommandPayloadHash(), comparable.commandHash())
                && Arrays.equals(
                        existing.getCommandPayload(), comparable.commandBytes());
        if (!identityMatches || !bytesMatch) {
            throw new IllegalStateException(
                    "Agent commandId conflict: frozen identity or canonical payload differs");
        }
        if (admission.dispatchEligible()
                && AgentCommandCanonicalCodec.isHallIntentCommand(draft)) {
            if (DB_SHADOW_MARKER.equals(existing.getLastError())) {
                throw new AgentCommandShadowIntentException(
                        "DB_SHADOW intent is capture-only; submit a new intent for canary dispatch");
            }
            if (MQ_SHADOW_MARKER.equals(existing.getLastError())) {
                return promoteShadowCapture(
                        existing, comparable.storedDraft(), draft.issuedAt());
            }
        }
        return new AgentCommandTransportWriteResult(
                existing.getId(), draft.commandId(), existing.getActiveMessageId(), null, true);
    }

    private ComparableCommand comparableCommand(
            AgentCommandDeliveryEntity existing,
            AgentCommandDraft draft,
            byte[] commandBytes,
            byte[] commandHash) {
        if (!AgentCommandCanonicalCodec.isHallIntentCommand(draft)
                || existing.getCommandPayload() == null) {
            return new ComparableCommand(
                    draft, commandBytes, commandHash, draft.expiresAt());
        }
        AgentCommandDraft stored;
        try {
            stored = AgentCommandCanonicalCodec.decodeBusinessBytes(
                    existing.getCommandPayload());
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Existing Agent command payload is not canonical", corrupt);
        }
        AgentCommandDraft stableRetry = new AgentCommandDraft(
                draft.schemaVersion(), draft.commandId(), draft.correlationId(), draft.causationId(),
                draft.tenantId(), draft.clientId(), draft.taskId(), draft.workItemId(),
                draft.targetAgentId(), draft.commandType(), stored.issuedAt(), stored.expiresAt(),
                draft.intentId(), draft.payload());
        byte[] comparableBytes = AgentCommandCanonicalCodec.businessBytes(stableRetry);
        return new ComparableCommand(
                stored, comparableBytes, AgentCommandCanonicalCodec.sha256(comparableBytes),
                stored.expiresAt());
    }

    private AgentCommandTransportWriteResult promoteShadowCapture(
            AgentCommandDeliveryEntity delivery, AgentCommandDraft storedDraft, long now) {
        validatePromotableShadowDelivery(delivery, now);
        List<AgentOutboxEventEntity> active = hallDao.lockActiveOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveMessageId());
        if (active == null || active.size() != 1) {
            throw new IllegalStateException(
                    "Shadow capture active outbox cardinality is invalid");
        }
        AgentOutboxEventEntity outbox = active.getFirst();
        validatePromotableShadowOutbox(delivery, outbox, storedDraft);
        requireOne(hallDao.promoteShadowDelivery(
                delivery, DISPATCH_ELIGIBLE_MARKER, now),
                "shadow delivery promotion");
        requireOne(hallDao.promoteShadowOutbox(
                outbox, DISPATCH_ELIGIBLE_MARKER, now),
                "shadow outbox promotion");
        return new AgentCommandTransportWriteResult(
                delivery.getId(), delivery.getCommandId(), delivery.getActiveMessageId(),
                outbox.getEventId(), false);
    }

    private void validatePromotableShadowDelivery(
            AgentCommandDeliveryEntity delivery, long now) {
        if (delivery.getId() == null || delivery.getId() <= 0
                || !"DEAD".equals(delivery.getStatus())
                || !shadowCaptureMarker(delivery.getLastError())
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() != 1
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() != 1
                || !exact(delivery.getActiveMessageId(), 100)
                || delivery.getExpiresAt() == null || now >= delivery.getExpiresAt()
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || delivery.getNextRetryAt() != null || delivery.getLeaseOwner() != null
                || delivery.getLeaseUntil() != null
                || delivery.getReplayParentMessageId() != null
                || delivery.getReplayRequesterId() != null
                || delivery.getReplayApproverId() != null
                || delivery.getReplayReason() != null
                || delivery.getUpdateTime() == null || now < delivery.getUpdateTime()) {
            throw new IllegalStateException(
                    "Shadow delivery is not eligible for canary dispatch promotion");
        }
    }

    private void validatePromotableShadowOutbox(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentCommandDraft storedDraft) {
        AgentRabbitTopologyManifest.PublishRoute route =
                AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        byte[] expectedWire = AgentCommandCanonicalCodec.wireBytes(
                storedDraft, delivery.getActiveMessageId(), delivery.getActiveAttempt());
        if (outbox == null || outbox.getId() == null || outbox.getId() <= 0
                || !Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                || !Objects.equals(delivery.getClientId(), outbox.getClientId())
                || !Objects.equals(delivery.getId(), outbox.getDeliveryId())
                || !Objects.equals(delivery.getCommandId(), outbox.getCommandId())
                || !Objects.equals(delivery.getTaskId(), outbox.getAggregateId())
                || !Objects.equals(delivery.getActiveMessageId(), outbox.getMessageId())
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                || !"task".equals(outbox.getAggregateType())
                || !route.destination().equals(outbox.getDestination())
                || !route.routingKey().equals(outbox.getRoutingKey())
                || !"DEAD".equals(outbox.getStatus())
                || !Objects.equals(delivery.getLastError(), outbox.getLastError())
                || outbox.getAttemptCount() == null || outbox.getAttemptCount() != 0
                || !Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || outbox.getNextRetryAt() != null || outbox.getLeaseOwner() != null
                || outbox.getLeaseUntil() != null
                || !"NONE".equals(outbox.getPublisherConfirmStatus())
                || outbox.getConfirmedAt() != null || outbox.getConfirmError() != null
                || !"NONE".equals(outbox.getMandatoryReturnStatus())
                || outbox.getReturnedAt() != null || outbox.getReturnReplyCode() != null
                || outbox.getReturnReplyText() != null || outbox.getPublishedAt() != null
                || outbox.getReplayParentMessageId() != null
                || outbox.getReplayRequesterId() != null
                || outbox.getReplayApproverId() != null
                || outbox.getReplayReason() != null
                || outbox.getWirePayload() == null
                || outbox.getWirePayloadHash() == null
                || !Arrays.equals(expectedWire, outbox.getWirePayload())
                || !MessageDigest.isEqual(
                        AgentCommandCanonicalCodec.sha256(expectedWire),
                        outbox.getWirePayloadHash())) {
            throw new IllegalStateException(
                    "Shadow outbox provenance is not eligible for canary dispatch promotion");
        }
    }

    private boolean shadowCaptureMarker(String marker) {
        return MQ_SHADOW_MARKER.equals(marker);
    }

    private String nextUuid(String field) {
        UUID value = uuidSupplier.get();
        if (value == null) {
            throw new IllegalStateException(field + " UUID supplier returned null");
        }
        return value.toString();
    }

    private void requireExact(String value, String field, int maxLength) {
        if (!exact(value, maxLength)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static int compareUtf8Unsigned(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int limit = Math.min(a.length, b.length);
        for (int index = 0; index < limit; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(a[index]), Byte.toUnsignedInt(b[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private record Admission(boolean dispatchEligible, String marker) {
    }

    private record ComparableCommand(
            AgentCommandDraft storedDraft, byte[] commandBytes,
            byte[] commandHash, long expiresAt) {
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(
                    operation + " returned " + rows + " rows; expected 1");
        }
    }
}
