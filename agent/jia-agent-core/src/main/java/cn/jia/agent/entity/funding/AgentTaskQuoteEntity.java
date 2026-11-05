package cn.jia.agent.entity.funding;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_task_bounty_quote")
public class AgentTaskQuoteEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String quoteId;
    private String taskId;
    /** Authenticated user that owns this task; separate from the executing Agent. */
    private String ownerJiacn;
    private String agentId;
    private String principalType;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private Long taskVersion;
    private String priceBookVersion;
    private String taskInputHash;
    private String skillSetHash;
    private String modelRouteVersion;
    private Long estimatedInputTokens;
    private Long estimatedCachedInputTokens;
    private Long estimatedOutputTokens;
    private Long estimatedReasoningTokens;
    private Long estimatedComputeWen;
    private Long worstComputeWen;
    private Long platformFeeWen;
    private Long grossAllocationWen;
    private Long estimatedAgentPayoutWen;
    private Long worstAgentPayoutWen;
    private Long minimumAcceptedPayoutWen;
    private Long budgetHeadroomWen;
    private Boolean verifiedSkillMatch;
    private Boolean advisoryAbilityMatch;
    private Boolean budgetCovered;
    private Boolean agentReady;
    private String recommendation;
    private String reasonCodes;
    private String status;
    private Long expiresAt;
    private Long claimedAt;
}
