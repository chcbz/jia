package cn.jia.agent.entity.funding;

import java.io.Serializable;

/** Bounded V0 claim receipt; the frozen V0 contract does not prescribe a claim response shape. */
public record AgentTaskClaimReceiptDTO(
        String taskId,
        String agentId,
        String quoteId,
        String status,
        String taskVersion,
        String claimedAt) implements Serializable {
}
