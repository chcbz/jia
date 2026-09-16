package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;

import java.util.List;

/** Task-request persistence is always restricted by the authenticated task owner. */
public interface AgentTaskRequestDao {
    @Deprecated(forRemoval = true)
    default int insert(String tenantId, String clientId, AgentTaskRequestDTO request) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskRequestEntity findByRequestId(String tenantId, String clientId,
            String taskId, String requestId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskRequestEntity> listByTask(String tenantId, String clientId,
            String taskId, String status, String workItemId, int limit) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskRequestEntity> listByTarget(String tenantId, String clientId,
            String taskId, String targetType, String targetId, String status, int limit) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default int updateByVersion(String tenantId, String clientId, String taskId, String requestId,
            long expectedVersion, AgentTaskRequestDTO request) { throw ownerRequired(); }

    int insert(String tenantId, String clientId, String ownerJiacn, AgentTaskRequestDTO request);

    AgentTaskRequestEntity findByRequestId(
            String tenantId, String clientId, String ownerJiacn, String taskId, String requestId);

    List<AgentTaskRequestEntity> listByTask(
            String tenantId, String clientId, String ownerJiacn, String taskId, String status,
            String workItemId, int limit);

    List<AgentTaskRequestEntity> listByTarget(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String targetType, String targetId, String status, int limit);

    int updateByVersion(String tenantId, String clientId, String ownerJiacn, String taskId,
            String requestId, long expectedVersion, AgentTaskRequestDTO request);
    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
