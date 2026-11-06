package cn.jia.agent.service;

/** Read-only, single-shot model port. Does not execute or assign tasks. */
public interface AgentAssigneeRecommendationModel {
    /** Trusted task ownership supplied by the recommendation service, never model output. */
    record Context(String tenantId, String clientId, String ownerJiacn, String taskId) { }
    String judge(Context context, String inputJson, String outputSchema);
}
