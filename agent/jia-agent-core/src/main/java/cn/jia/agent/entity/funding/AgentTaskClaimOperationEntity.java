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
@TableName("agent_task_bounty_claim_operation")
public class AgentTaskClaimOperationEntity extends BaseEntity {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String principalType;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private String taskId;
    private String agentId;
    private String quoteId;
    private String status;
    private Long receiptTaskVersion;
    private Long claimedAt;
}
