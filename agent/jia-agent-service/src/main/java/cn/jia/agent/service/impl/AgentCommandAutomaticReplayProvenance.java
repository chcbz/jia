package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxEventEntity;

import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;

/** Shared fail-closed binding for the two D06 automatic replay lanes. */
final class AgentCommandAutomaticReplayProvenance {
    private AgentCommandAutomaticReplayProvenance() {
    }

    static boolean validRequesterBinding(
            String targetAgentId, String requestedBy, String approverId, String reason) {
        if (!exact(targetAgentId, 100) || !exact(requestedBy, 100)
                || reason == null || approverId != null) {
            return false;
        }
        return switch (reason) {
            case AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT ->
                    targetAgentId.equals(requestedBy);
            case AgentCommandReissueServiceImpl.REASON_SCHEDULER ->
                    AgentCommandReissueServiceImpl.REQUESTER_SCHEDULER.equals(requestedBy);
            default -> false;
        };
    }

    static boolean validOptionalAudit(
            String activeMessageId,
            Integer deliveryActiveAttempt,
            Integer outboxActiveAttempt,
            String targetAgentId,
            String deliveryParentMessageId,
            String deliveryRequesterId,
            String deliveryApproverId,
            String deliveryReason,
            String outboxParentMessageId,
            String outboxRequesterId,
            String outboxApproverId,
            String outboxReason) {
        if (deliveryActiveAttempt == null || outboxActiveAttempt == null
                || deliveryActiveAttempt < 1
                || !deliveryActiveAttempt.equals(outboxActiveAttempt)) {
            return false;
        }
        boolean deliveryAbsent = deliveryParentMessageId == null
                && deliveryRequesterId == null
                && deliveryApproverId == null
                && deliveryReason == null;
        boolean outboxAbsent = outboxParentMessageId == null
                && outboxRequesterId == null
                && outboxApproverId == null
                && outboxReason == null;
        if (deliveryActiveAttempt == 1) {
            return deliveryAbsent && outboxAbsent;
        }
        if (deliveryAbsent || outboxAbsent
                || !exact(activeMessageId, 100)
                || !exact(deliveryParentMessageId, 100)
                || activeMessageId.equals(deliveryParentMessageId)
                || !validRequesterBinding(targetAgentId, deliveryRequesterId,
                        deliveryApproverId, deliveryReason)) {
            return false;
        }
        return Objects.equals(deliveryParentMessageId, outboxParentMessageId)
                && Objects.equals(deliveryRequesterId, outboxRequesterId)
                && Objects.equals(deliveryApproverId, outboxApproverId)
                && Objects.equals(deliveryReason, outboxReason)
                && validRequesterBinding(targetAgentId, outboxRequesterId,
                        outboxApproverId, outboxReason);
    }

    /**
     * Proves that replay_parent_message_id names the one durable transport attempt immediately
     * preceding the active outbox. Rabbit publish retries remain independent in attempt_count.
     */
    static boolean validImmediateParent(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity activeOutbox,
            List<AgentOutboxEventEntity> lockedPreviousAttempts) {
        if (delivery == null || activeOutbox == null
                || !validOptionalAudit(
                        delivery.getActiveMessageId(), delivery.getActiveAttempt(),
                        activeOutbox.getActiveAttempt(), delivery.getTargetAgentId(),
                        delivery.getReplayParentMessageId(), delivery.getReplayRequesterId(),
                        delivery.getReplayApproverId(), delivery.getReplayReason(),
                        activeOutbox.getReplayParentMessageId(), activeOutbox.getReplayRequesterId(),
                        activeOutbox.getReplayApproverId(), activeOutbox.getReplayReason())) {
            return false;
        }
        int activeAttempt = delivery.getActiveAttempt();
        List<AgentOutboxEventEntity> previous = lockedPreviousAttempts == null
                ? List.of() : lockedPreviousAttempts;
        if (activeAttempt == 1) return previous.isEmpty();
        if (previous.size() != 1) return false;

        AgentOutboxEventEntity parent = previous.getFirst();
        if (parent == null || parent.getId() == null || parent.getId() <= 0
                || parent.getActiveAttempt() == null
                || parent.getActiveAttempt() != activeAttempt - 1
                || !Objects.equals(parent.getTenantId(), delivery.getTenantId())
                || !Objects.equals(parent.getClientId(), delivery.getClientId())
                || !Objects.equals(parent.getDeliveryId(), delivery.getId())
                || !Objects.equals(parent.getCommandId(), delivery.getCommandId())
                || !Objects.equals(parent.getAggregateType(), activeOutbox.getAggregateType())
                || !Objects.equals(parent.getAggregateId(), delivery.getTaskId())
                || !Objects.equals(parent.getDestination(), activeOutbox.getDestination())
                || !Objects.equals(parent.getRoutingKey(), activeOutbox.getRoutingKey())
                || !Objects.equals(parent.getExpiresAt(), delivery.getExpiresAt())
                || !Objects.equals(parent.getMessageId(), delivery.getReplayParentMessageId())
                || !Objects.equals(parent.getMessageId(), activeOutbox.getReplayParentMessageId())
                || !exact(parent.getEventId(), 100) || !exact(parent.getMessageId(), 100)
                || parent.getAttemptCount() == null || parent.getAttemptCount() < 1
                || parent.getVersion() == null || parent.getVersion() < 0
                || !"PUBLISHED".equals(parent.getStatus())
                || parent.getNextRetryAt() != null || parent.getLeaseOwner() != null
                || parent.getLeaseUntil() != null || parent.getLastError() != null
                || !"ACK".equals(parent.getPublisherConfirmStatus())
                || parent.getConfirmedAt() == null || parent.getConfirmedAt() <= 0
                || parent.getConfirmError() != null
                || !"NOT_RETURNED".equals(parent.getMandatoryReturnStatus())
                || parent.getReturnedAt() != null || parent.getReturnReplyCode() != null
                || parent.getReturnReplyText() != null
                || parent.getPublishedAt() == null || parent.getPublishedAt() <= 0
                || !storedHash(parent.getWirePayload(), parent.getWirePayloadHash())
                || !validOptionalAudit(
                        parent.getMessageId(), parent.getActiveAttempt(), parent.getActiveAttempt(),
                        delivery.getTargetAgentId(),
                        parent.getReplayParentMessageId(), parent.getReplayRequesterId(),
                        parent.getReplayApproverId(), parent.getReplayReason(),
                        parent.getReplayParentMessageId(), parent.getReplayRequesterId(),
                        parent.getReplayApproverId(), parent.getReplayReason())) {
            return false;
        }
        try {
            AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
            AgentCommandAmqpContract.validate(new AgentConfirmedPublishRequest(
                    parent.getDestination(), parent.getRoutingKey(), parent.getWirePayload(),
                    parent.getWirePayloadHash(), parent.getMessageId(), parent.getEventId(),
                    parent.getDeliveryId(), parent.getCommandId(), parent.getTenantId(),
                    parent.getClientId(), delivery.getTaskId(), delivery.getTargetAgentId(),
                    delivery.getCommandType(), parent.getActiveAttempt(), parent.getExpiresAt(),
                    manifest.sha256(), AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY));
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean storedHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && MessageDigest.isEqual(AgentCommandCanonicalCodec.sha256(bytes), hash);
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
