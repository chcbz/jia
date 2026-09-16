package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;

import java.util.List;

/** Task-member persistence always requires the authenticated task owner. */
public interface AgentTaskMemberDao {
    /**
     * Legacy owner-less task APIs are intentionally non-operational. They are retained only
     * while callers are migrated to the strict overloads; they do not read or write data.
     */
    @Deprecated(forRemoval = true)
    default int insert(String tenantId, String clientId, AgentTaskMemberDTO member) {
        throw ownerRequired();
    }

    int insert(String tenantId, String clientId, String ownerJiacn, AgentTaskMemberDTO member);

    @Deprecated(forRemoval = true)
    default AgentTaskMemberEntity findByTaskAndAgent(
            String tenantId, String clientId, String taskId, String agentId) {
        throw ownerRequired();
    }

    AgentTaskMemberEntity findByTaskAndAgent(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId);

    @Deprecated(forRemoval = true)
    default AgentTaskMemberEntity findByTaskAndAgentForUpdate(
            String tenantId, String clientId, String taskId, String agentId) {
        throw ownerRequired();
    }

    AgentTaskMemberEntity findByTaskAndAgentForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String agentId);

    @Deprecated(forRemoval = true)
    default List<AgentTaskMemberEntity> listByTask(
            String tenantId, String clientId, String taskId) {
        throw ownerRequired();
    }

    List<AgentTaskMemberEntity> listByTask(
            String tenantId, String clientId, String ownerJiacn, String taskId);

    @Deprecated(forRemoval = true)
    default List<AgentTaskMemberEntity> listByAgent(
            String tenantId, String clientId, String agentId, String memberStatus, int limit) {
        throw ownerRequired();
    }

    List<AgentTaskMemberEntity> listByAgent(
            String tenantId, String clientId, String ownerJiacn,
            String agentId, String memberStatus, int limit);

    @Deprecated(forRemoval = true)
    default int updateByVersion(String tenantId, String clientId, String taskId,
            String agentId, long expectedVersion, AgentTaskMemberDTO member) {
        throw ownerRequired();
    }

    int updateByVersion(String tenantId, String clientId, String ownerJiacn,
            String taskId, String agentId, long expectedVersion, AgentTaskMemberDTO member);

    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
