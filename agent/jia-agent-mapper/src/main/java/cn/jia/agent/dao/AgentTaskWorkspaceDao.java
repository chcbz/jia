package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;

import java.util.List;

public interface AgentTaskWorkspaceDao {
    TaskRow findTask(String tenantId, String clientId, String taskId);
    MemberRow findActorMember(String tenantId, String clientId, String taskId, String actorAgentId);
    List<MemberRow> findMembers(String tenantId, String clientId, String taskId);
    List<WorkItemRow> findWorkItems(String tenantId, String clientId, String taskId);
    List<RequestRow> findOpenRequests(String tenantId, String clientId, String taskId);
    List<ArtifactRow> findVisibleArtifacts(String tenantId, String clientId, String taskId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess);
    ArtifactRow findArtifactVersion(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion);
    List<EventRow> findLatestEvents(String tenantId, String clientId, String taskId);
}
