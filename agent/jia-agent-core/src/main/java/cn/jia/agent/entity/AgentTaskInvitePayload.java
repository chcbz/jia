package cn.jia.agent.entity;

import java.util.List;

/** Immutable, allowlisted TASK_INVITE briefing payload. */
public record AgentTaskInvitePayload(
        String actionType,
        String reason,
        String instruction,
        String taskTitle,
        List<String> requiredAbilities,
        String coordinatorAgentId,
        List<String> collaboratorAgentIds,
        String assignmentRole,
        String acceptance,
        String conversationType) {
    public AgentTaskInvitePayload {
        requiredAbilities = requiredAbilities == null ? List.of() : List.copyOf(requiredAbilities);
        collaboratorAgentIds = collaboratorAgentIds == null
                ? List.of() : List.copyOf(collaboratorAgentIds);
    }
}
