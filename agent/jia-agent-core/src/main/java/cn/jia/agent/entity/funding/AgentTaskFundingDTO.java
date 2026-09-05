package cn.jia.agent.entity.funding;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskFundingDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String mode;
    private String status;
    private String escrowId;
    private String grossBountyAmountMicro;
    private String remainingMicro;
}
