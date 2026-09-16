package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;

import java.util.List;

/** Task work-item persistence always requires the authenticated task owner. */
public interface AgentTaskWorkItemDao {
    /**
     * Legacy owner-less task APIs are intentionally non-operational. They are retained only
     * while callers are migrated to the strict overloads; they do not read or write data.
     */
    @Deprecated(forRemoval = true)
    default int insert(String tenantId, String clientId, AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int insert(String tenantId, String clientId, String ownerJiacn, AgentTaskWorkItemDTO item);

    @Deprecated(forRemoval = true)
    default AgentTaskWorkItemEntity findByWorkItemId(
            String tenantId, String clientId, String workItemId) {
        throw ownerRequired();
    }

    AgentTaskWorkItemEntity findByWorkItemId(
            String tenantId, String clientId, String ownerJiacn, String workItemId);

    @Deprecated(forRemoval = true)
    default AgentTaskWorkItemEntity findByTaskAndWorkItemId(
            String tenantId, String clientId, String taskId, String workItemId) {
        throw ownerRequired();
    }

    AgentTaskWorkItemEntity findByTaskAndWorkItemId(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId);

    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String taskId, String status, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listByTask(
            String tenantId, String clientId, String ownerJiacn,
            String taskId, String status, int limit);

    /** Locks the complete bounded task graph in deterministic binary work-item ID order. */
    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listByTaskForUpdate(
            String tenantId, String clientId, String taskId, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listByTaskForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, int limit);

    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String assigneeAgentId, String status, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listByAssignee(
            String tenantId, String clientId, String ownerJiacn,
            String assigneeAgentId, String status, int limit);

    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listByTaskAndAssignee(
            String tenantId, String clientId, String taskId, String assigneeAgentId, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listByTaskAndAssignee(
            String tenantId, String clientId, String ownerJiacn,
            String taskId, String assigneeAgentId, int limit);

    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listByTaskAssigneeAndType(
            String tenantId, String clientId, String taskId, String assigneeAgentId,
            String workType, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listByTaskAssigneeAndType(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String assigneeAgentId, String workType, int limit);

    @Deprecated(forRemoval = true)
    default List<AgentTaskWorkItemEntity> listExpiredLeases(
            String tenantId, String clientId, long expiredAtOrBefore, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskWorkItemEntity> listExpiredLeases(
            String tenantId, String clientId, String ownerJiacn, long expiredAtOrBefore, int limit);

    @Deprecated(forRemoval = true)
    default int updateByVersion(String tenantId, String clientId, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int updateByVersion(String tenantId, String clientId, String ownerJiacn, String workItemId,
            long expectedVersion, AgentTaskWorkItemDTO item);

    @Deprecated(forRemoval = true)
    default int claimReadyByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String expectedAssigneeAgentId, long expectedVersion, AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int claimReadyByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String expectedAssigneeAgentId, long expectedVersion, AgentTaskWorkItemDTO item);

    /** Exact-scope pending-to-ready CAS used only after full dependency-graph validation. */
    @Deprecated(forRemoval = true)
    default int readyPendingByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            long expectedVersion, long changedAt, AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int readyPendingByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            long expectedVersion, long changedAt, AgentTaskWorkItemDTO item);

    @Deprecated(forRemoval = true)
    default int updateActiveLeaseByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long operationTime,
            AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int updateActiveLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long operationTime,
            AgentTaskWorkItemDTO item);

    /** Exact E05 CAS: expired claimed/running lease directly becomes a fresh target claimed lease. */
    @Deprecated(forRemoval = true)
    default int reassignExpiredLeaseByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String previousAgentId, String previousLeaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int reassignExpiredLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String previousAgentId, String previousLeaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item);

    @Deprecated(forRemoval = true)
    default int expireLeaseByVersion(
            String tenantId, String clientId, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item) {
        throw ownerRequired();
    }

    int expireLeaseByVersion(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String assigneeAgentId, String leaseToken, String expectedStatus,
            long expectedLeaseUntil, long expectedVersion, long expiredAtOrBefore,
            AgentTaskWorkItemDTO item);

    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
