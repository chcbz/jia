package cn.jia.chat.service;

/** Chat-owned discussion shutdown inside an authorized task-root cancellation transaction.
 * Native execution/lease revocation remains the Agent execution service's responsibility. */
public interface TaskDiscussionCancellation {
    void cancelTaskDiscussions(String tenantId, String clientId, String ownerJiacn, String taskId);
}
