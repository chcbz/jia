package cn.jia.chat.service;

import com.fasterxml.jackson.annotation.JsonAnySetter;
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
    private String workItemId;
    private String conversationId;
    private String reason;
    private String instruction;
    private Map<String, ?> context;
    private String autonomyLevel;
    private Boolean requiresApproval;
    private String status;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException("Unknown Hall action field: " + field);
    }
}
