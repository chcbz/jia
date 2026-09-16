package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentTaskAcceptedArtifactRow;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeDecisionEntity;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeEntity;

import java.util.List;

/** Artifact-outcome persistence is always restricted by the authenticated task owner. */
public interface AgentTaskArtifactOutcomeDao {
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactOutcomeEntity findForUpdate(String tenantId, String clientId,
            String taskId, String artifactId, int artifactVersion) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactOutcomeDecisionEntity findDecisionForUpdate(
            String tenantId, String clientId, String taskId, String decisionId) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default int insertDecision(String tenantId, String clientId,
            AgentTaskArtifactOutcomeDecisionEntity decision) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default int insert(String tenantId, String clientId,
            AgentTaskArtifactOutcomeEntity outcome) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default int updateByVersion(String tenantId, String clientId, String taskId,
            String artifactId, int artifactVersion, String expectedState,
            long expectedVersion, AgentTaskArtifactOutcomeEntity outcome) { throw ownerRequired(); }
    @Deprecated(forRemoval = true)
    default List<AgentTaskAcceptedArtifactRow> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit) {
        throw ownerRequired();
    }

    AgentTaskArtifactOutcomeEntity findForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String artifactId, int artifactVersion);

    AgentTaskArtifactOutcomeDecisionEntity findDecisionForUpdate(
            String tenantId, String clientId, String ownerJiacn, String taskId, String decisionId);

    int insertDecision(String tenantId, String clientId, String ownerJiacn,
            AgentTaskArtifactOutcomeDecisionEntity decision);

    int insert(String tenantId, String clientId, String ownerJiacn,
            AgentTaskArtifactOutcomeEntity outcome);

    int updateByVersion(String tenantId, String clientId, String ownerJiacn, String taskId,
            String artifactId, int artifactVersion, String expectedState,
            long expectedVersion, AgentTaskArtifactOutcomeEntity outcome);

    List<AgentTaskAcceptedArtifactRow> listAuthoritativeAccepted(
            String tenantId, String clientId, String ownerJiacn, String taskId, String workItemId,
            String actorAgentId, boolean reviewerAccess, boolean coordinatorAccess, int limit);

    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
