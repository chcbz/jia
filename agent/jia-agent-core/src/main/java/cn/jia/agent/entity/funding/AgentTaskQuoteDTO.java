package cn.jia.agent.entity.funding;

import java.io.Serializable;
import java.util.List;

public record AgentTaskQuoteDTO(
        String quoteId,
        String taskId,
        String agentId,
        String taskVersion,
        String priceBookVersion,
        String taskInputHash,
        String skillSetHash,
        String modelRouteVersion,
        AgentTokenEstimateDTO estimatedTokens,
        String estimatedComputeWen,
        String worstComputeWen,
        String platformFeeWen,
        String grossAllocationWen,
        String estimatedAgentPayoutWen,
        String worstAgentPayoutWen,
        String minimumAcceptedPayoutWen,
        String budgetHeadroomWen,
        boolean verifiedSkillMatch,
        boolean advisoryAbilityMatch,
        String recommendation,
        List<String> reasonCodes,
        String expiresAt) implements Serializable {
    public AgentTaskQuoteDTO {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
