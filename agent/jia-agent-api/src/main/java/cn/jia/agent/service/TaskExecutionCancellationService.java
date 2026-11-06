package cn.jia.agent.service;

/** Internal execution shutdown beneath the authorized task-root transaction.
 * Cancelling admission is not a claim that an already issued external call stopped. */
public interface TaskExecutionCancellationService {
    void cancelTaskExecutions(String tenantId, String clientId, String ownerJiacn, String taskId);
}
