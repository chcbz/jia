package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandRedriveOperationEntity;
import cn.jia.agent.entity.AgentCommandRedriveOutcomeState;
import cn.jia.agent.entity.AgentCommandRedriveSettlementState;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentOutboxEventEntity;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The sole fail-closed legal-source policy for privileged broker redrive. */
final class AgentCommandBrokerRedrivePolicy {
    Evaluation evaluate(
            SourceBundle bundle,
            long now,
            AgentRabbitTopologyManifest.PublishRoute route,
            String topologySha256,
            String ownOperationId) {
        if (bundle == null || now <= 0 || route == null
                || bundle.delivery() == null
                || bundle.activeMessageOutboxes() == null
                || bundle.currentAttemptOutboxes() == null
                || bundle.previousAttemptOutboxes() == null
                || bundle.blockingRedriveOperations() == null
                || bundle.activeMessageOutboxes().size() != 1
                || bundle.currentAttemptOutboxes().size() != 1) {
            return Evaluation.illegal();
        }
        AgentCommandDeliveryEntity delivery = bundle.delivery();
        AgentOutboxEventEntity active = bundle.activeMessageOutboxes().getFirst();
        AgentOutboxEventEntity current = bundle.currentAttemptOutboxes().getFirst();
        if (active == null || current == null || active.getId() == null || active.getId() <= 0
                || !Objects.equals(active.getId(), current.getId())) {
            return Evaluation.illegal();
        }
        if (!exactIdentity(bundle, delivery, active, route, now)
                || !published(delivery, active)
                || bundle.inbox() != null
                || !storedHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())
                || !storedHash(active.getWirePayload(), active.getWirePayloadHash())) {
            return Evaluation.illegal();
        }

        AgentCommandDraft draft;
        try {
            draft = AgentCommandCanonicalCodec.decodeBusinessBytes(delivery.getCommandPayload());
        } catch (IllegalArgumentException invalid) {
            return Evaluation.illegal();
        }
        if (!Objects.equals(delivery.getCommandId(), draft.commandId())
                || !Objects.equals(delivery.getTenantId(), draft.tenantId())
                || !Objects.equals(delivery.getClientId(), draft.clientId())
                || !Objects.equals(delivery.getTaskId(), draft.taskId())
                || !Objects.equals(delivery.getWorkItemId(), draft.workItemId())
                || !Objects.equals(delivery.getTargetAgentId(), draft.targetAgentId())
                || !Objects.equals(delivery.getCommandType(), draft.commandType())
                || !Objects.equals(delivery.getExpiresAt(), draft.expiresAt())
                || !Arrays.equals(active.getWirePayload(), AgentCommandCanonicalCodec.wireBytes(
                        draft, delivery.getActiveMessageId(), delivery.getActiveAttempt()))) {
            return Evaluation.illegal();
        }
        try {
            AgentCommandAmqpContract.validate(new AgentConfirmedPublishRequest(
                    route.destination(), route.routingKey(), active.getWirePayload(),
                    active.getWirePayloadHash(), active.getMessageId(), active.getEventId(),
                    delivery.getId(), delivery.getCommandId(), delivery.getTenantId(),
                    delivery.getClientId(), delivery.getTaskId(), delivery.getTargetAgentId(),
                    delivery.getCommandType(), delivery.getActiveAttempt(), delivery.getExpiresAt(),
                    topologySha256, AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY));
        } catch (IllegalArgumentException invalid) {
            return Evaluation.illegal();
        }
        if (!AgentCommandAutomaticReplayProvenance.validImmediateParent(
                delivery, active, bundle.previousAttemptOutboxes())) {
            return Evaluation.illegal();
        }

        long updatedAt = Math.max(positiveOrZero(delivery.getUpdateTime()),
                positiveOrZero(active.getUpdateTime()));
        if (updatedAt <= 0) return Evaluation.illegal();
        LegalSource legal = new LegalSource(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getCommandId(), delivery.getTaskId(), delivery.getTargetAgentId(),
                delivery.getCommandType(), active.getEventId(), active.getMessageId(),
                active.getWirePayload(), active.getWirePayloadHash(),
                delivery.getActiveAttempt(), active.getAttemptCount(), delivery.getExpiresAt(),
                active.getPublishedAt(), updatedAt);
        if (!legalBlockingOperations(bundle.blockingRedriveOperations(), legal, ownOperationId)) {
            return Evaluation.illegal();
        }
        return Evaluation.legal(legal);
    }

    private boolean exactIdentity(
            SourceBundle bundle,
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentRabbitTopologyManifest.PublishRoute route,
            long now) {
        return delivery.getId() != null && delivery.getId() > 0
                && delivery.getId() == bundle.expectedDeliveryId()
                && exact(bundle.expectedTenantId(), 50)
                && exact(bundle.expectedClientId(), 50)
                && exact(bundle.expectedTaskId(), 100)
                && exact(bundle.expectedTargetAgentId(), 100)
                && exact(bundle.expectedMessageId(), 100)
                && Objects.equals(bundle.expectedTenantId(), delivery.getTenantId())
                && Objects.equals(bundle.expectedClientId(), delivery.getClientId())
                && Objects.equals(bundle.expectedTaskId(), delivery.getTaskId())
                && Objects.equals(bundle.expectedTargetAgentId(), delivery.getTargetAgentId())
                && Objects.equals(bundle.expectedMessageId(), delivery.getActiveMessageId())
                && exact(delivery.getCommandId(), 100)
                && exact(delivery.getCommandType(), 64)
                && AgentCommandCanonicalCodec.isSupportedCommandType(delivery.getCommandType())
                && sameScope(delivery, outbox)
                && Objects.equals(delivery.getId(), outbox.getDeliveryId())
                && Objects.equals(delivery.getCommandId(), outbox.getCommandId())
                && Objects.equals(delivery.getActiveMessageId(), outbox.getMessageId())
                && Objects.equals(delivery.getTaskId(), outbox.getAggregateId())
                && "task".equals(outbox.getAggregateType())
                && Objects.equals(route.destination(), outbox.getDestination())
                && Objects.equals(route.routingKey(), outbox.getRoutingKey())
                && delivery.getActiveAttempt() != null && delivery.getActiveAttempt() > 0
                && delivery.getExpiresAt() != null && delivery.getExpiresAt() > now
                && Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                && Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                && delivery.getLeaseOwner() == null && delivery.getLeaseUntil() == null
                && outbox.getLeaseOwner() == null && outbox.getLeaseUntil() == null;
    }

    private boolean published(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        return "PUBLISHED".equals(delivery.getStatus())
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
    }

    private boolean legalBlockingOperations(
            List<AgentCommandRedriveOperationEntity> blockers,
            LegalSource source,
            String ownOperationId) {
        if (blockers.isEmpty()) return true;
        if (ownOperationId == null || blockers.size() != 1) return false;
        AgentCommandRedriveOperationEntity operation = blockers.getFirst();
        return operation != null && operation.getId() != null && operation.getId() > 0
                && ownOperationId.equals(operation.getOperationId())
                && Objects.equals(source.deliveryId(), operation.getDeliveryId())
                && Objects.equals(source.tenantId(), operation.getTenantId())
                && Objects.equals(source.clientId(), operation.getClientId())
                && Objects.equals(source.taskId(), operation.getTaskId())
                && Objects.equals(source.targetAgentId(), operation.getTargetAgentId())
                && Objects.equals(source.commandId(), operation.getCommandId())
                && Objects.equals(source.eventId(), operation.getSourceEventId())
                && Objects.equals(source.messageId(), operation.getSourceMessageId())
                && Objects.equals(source.activeAttempt(), operation.getSourceAttempt())
                && storedHashMatch(source.wireHash(), operation.getWireHash())
                && operation.getOutcomeState() == AgentCommandRedriveOutcomeState.PENDING
                && operation.getSettlementState() == AgentCommandRedriveSettlementState.PENDING
                && operation.getErrorCode() == null && operation.getCompletedAt() == null
                && operation.getRequestedAt() != null && operation.getRequestedAt() > 0
                && operation.getVersion() != null && operation.getVersion() >= 0
                && Objects.equals(operation.getDispositionGuard(), 1)
                && Objects.equals(operation.getRedriveGuard(), 1);
    }

    private boolean sameScope(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        return outbox != null
                && Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                && Objects.equals(delivery.getClientId(), outbox.getClientId());
    }

    private boolean storedHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && MessageDigest.isEqual(AgentCommandCanonicalCodec.sha256(bytes), hash);
    }

    private boolean storedHashMatch(byte[] expected, byte[] actual) {
        return expected != null && actual != null && expected.length == 32 && actual.length == 32
                && MessageDigest.isEqual(expected, actual);
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty()
                && value.codePointCount(0, value.length()) <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private long positiveOrZero(Long value) {
        return value == null ? 0 : Math.max(value, 0);
    }

    record SourceBundle(
            String expectedTenantId,
            String expectedClientId,
            long expectedDeliveryId,
            String expectedTaskId,
            String expectedTargetAgentId,
            String expectedMessageId,
            AgentCommandDeliveryEntity delivery,
            List<AgentOutboxEventEntity> activeMessageOutboxes,
            List<AgentOutboxEventEntity> currentAttemptOutboxes,
            List<AgentOutboxEventEntity> previousAttemptOutboxes,
            AgentConsumerInboxEntity inbox,
            List<AgentCommandRedriveOperationEntity> blockingRedriveOperations) {
    }

    record Evaluation(Optional<LegalSource> legalSource) {
        Evaluation {
            legalSource = Objects.requireNonNull(legalSource, "legalSource");
        }
        static Evaluation legal(LegalSource source) {
            return new Evaluation(Optional.of(source));
        }
        static Evaluation illegal() {
            return new Evaluation(Optional.empty());
        }
        boolean legal() {
            return legalSource.isPresent();
        }
    }

    record LegalSource(
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
            int publishAttemptCount,
            long expiresAt,
            long publishedAt,
            long updatedAt) {
        LegalSource {
            wirePayload = Arrays.copyOf(wirePayload, wirePayload.length);
            wireHash = Arrays.copyOf(wireHash, wireHash.length);
        }
        @Override public byte[] wirePayload() {
            return Arrays.copyOf(wirePayload, wirePayload.length);
        }
        @Override public byte[] wireHash() {
            return Arrays.copyOf(wireHash, wireHash.length);
        }
    }
}
