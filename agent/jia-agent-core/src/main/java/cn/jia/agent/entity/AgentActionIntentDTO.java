package cn.jia.agent.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class AgentActionIntentDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String intentId;
    private String actionType;
    private String actorAgentId;
    private List<String> targetAgentIds;
    private String taskId;
    private String reason;
    private String instruction;
    private Map<String, Object> context;
    private String autonomyLevel;
    private Boolean requiresApproval;
    private String conversationType;
    private Long createdAt;
}
