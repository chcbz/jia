package cn.jia.agent.service.impl;

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

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }
}
