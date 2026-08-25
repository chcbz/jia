package cn.jia.agent.entity;

import java.util.List;

/** Explicitly allowlisted, bounded Hall context. Identity and credentials are forbidden. */
public record AgentHallCommandContext(
        String taskTitle,
        String workItemTitle,
        String requestSummary,
        String reviewSummary,
        String contextVersion,
        List<String> referenceIds,
        List<String> tags) {
    public AgentHallCommandContext {
        referenceIds = referenceIds == null ? List.of() : List.copyOf(referenceIds);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
