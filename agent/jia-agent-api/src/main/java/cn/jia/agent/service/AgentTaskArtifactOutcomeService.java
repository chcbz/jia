package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;

import java.util.List;

/** Artifact outcome APIs require the exact authenticated task owner. */
public interface AgentTaskArtifactOutcomeService {
    @Deprecated(forRemoval = true)
    default AgentTaskArtifactOutcomeViewDTO accept(
            String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactAcceptDTO command) {
        throw ownerRequired();
    }

    @Deprecated(forRemoval = true)
    default List<AgentTaskArtifactOutcomeViewDTO> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String workItemId, Integer limit) {
        throw ownerRequired();
    }

    AgentTaskArtifactOutcomeViewDTO accept(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, AgentTaskArtifactAcceptDTO command);

    /** Returns accepted rows only; implicit draft and superseded rows are never returned. */
    List<AgentTaskArtifactOutcomeViewDTO> listAuthoritativeAccepted(
            String tenantId, String clientId, String ownerJiacn, String taskId,
            String actorAgentId, String workItemId, Integer limit);

    private static UnsupportedOperationException ownerRequired() {
        return new UnsupportedOperationException("strict task owner scope is required");
    }
}
