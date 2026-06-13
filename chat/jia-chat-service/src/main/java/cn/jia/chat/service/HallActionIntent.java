package cn.jia.chat.service;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class HallActionIntent {
    private String intentId;
    private String triggerEventId;
    private String actionType;
    private String actorAgentId;
    private List<String> targetAgentIds;
    private String taskId;
    private String conversationId;
    private String reason;
    private String instruction;
    private Map<String, ?> context;
    private String autonomyLevel;
    private Boolean requiresApproval;
    private String status;
}
