package cn.jia.agent.entity.funding;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class AgentTaskClaimRequestDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String agentId;
    private String quoteId;
    private String taskVersion;
    private Boolean allowQueue;
}
