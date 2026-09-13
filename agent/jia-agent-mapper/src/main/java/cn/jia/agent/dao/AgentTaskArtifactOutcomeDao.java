package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskAcceptedArtifactRow;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeEntity;

import java.util.List;

public interface AgentTaskArtifactOutcomeDao {
    AgentTaskArtifactOutcomeEntity findForUpdate(
            String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion);

    AgentTaskArtifactOutcomeDecisionEntity findDecisionForUpdate(
            String tenantId, String clientId, String taskId, String decisionId);

    int insertDecision(String tenantId, String clientId,
            AgentTaskArtifactOutcomeDecisionEntity decision);

    int insert(String tenantId, String clientId, AgentTaskArtifactOutcomeEntity outcome);

    int updateByVersion(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion, String expectedState,
            long expectedVersion, AgentTaskArtifactOutcomeEntity outcome);

    List<AgentTaskAcceptedArtifactRow> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit);
}
