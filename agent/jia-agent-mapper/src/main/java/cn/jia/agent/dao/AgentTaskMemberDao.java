package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;

import java.util.List;

public interface AgentTaskMemberDao {
    int insert(String tenantId, String clientId, AgentTaskMemberDTO member);

    AgentTaskMemberEntity findByTaskAndAgent(
            String tenantId, String clientId, String taskId, String agentId);

    AgentTaskMemberEntity findByTaskAndAgentForUpdate(
            String tenantId, String clientId, String taskId, String agentId);

    List<AgentTaskMemberEntity> listByTask(String tenantId, String clientId, String taskId);

    List<AgentTaskMemberEntity> listByAgent(
            String tenantId, String clientId, String agentId, String memberStatus, int limit);

    int updateByVersion(String tenantId, String clientId, String taskId, String agentId,
            long expectedVersion, AgentTaskMemberDTO member);
}
