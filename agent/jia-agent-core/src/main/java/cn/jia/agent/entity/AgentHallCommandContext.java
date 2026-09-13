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
        List<String> tags,
        String bindingVersion,
        String reassignmentId) {
    /** Preserves every pre-E05 Hall command constructor and canonical byte sequence. */
    public AgentHallCommandContext(
            String taskTitle,
            String workItemTitle,
            String requestSummary,
            String reviewSummary,
            String contextVersion,
            List<String> referenceIds,
            List<String> tags) {
        this(taskTitle, workItemTitle, requestSummary, reviewSummary, contextVersion,
                referenceIds, tags, null, null);
    }

    public AgentHallCommandContext {
        referenceIds = referenceIds == null ? List.of() : List.copyOf(referenceIds);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
