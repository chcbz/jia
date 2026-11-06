package cn.jia.agent.service;

/** Owner cancellation joins task execution shutdown and fixed-quote release in the root transaction. */
public interface AgentTaskCancellationService {
    Receipt cancel(String tenantId, String clientId, String ownerJiacn, String actorId,
            String taskId, long expectedTaskVersion);

    record Receipt(String taskId, String status, long taskVersion) { }
}
