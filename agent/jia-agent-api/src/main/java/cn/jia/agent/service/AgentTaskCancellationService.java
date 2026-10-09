package cn.jia.agent.service;

/** Initial cancellation: only ordinary, unfunded tasks with no current execution or task-start evidence. */
public interface AgentTaskCancellationService {
    Receipt cancel(String tenantId, String clientId, String ownerJiacn, String actorId,
            String taskId, long expectedTaskVersion);

    record Receipt(String taskId, String status, long taskVersion) { }
}
