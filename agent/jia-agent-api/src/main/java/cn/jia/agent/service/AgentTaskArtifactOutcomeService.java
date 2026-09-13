package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactOutcomeViewDTO;

import java.util.List;

/** Internal F06 contract; no HTTP path is activated by this task. */
public interface AgentTaskArtifactOutcomeService {
    AgentTaskArtifactOutcomeViewDTO accept(
            String tenantId, String clientId, String taskId,
            String actorAgentId, AgentTaskArtifactAcceptDTO command);

    /** Returns accepted rows only; implicit draft and superseded rows are never returned. */
    List<AgentTaskArtifactOutcomeViewDTO> listAuthoritativeAccepted(
            String tenantId, String clientId, String taskId,
            String actorAgentId, String workItemId, Integer limit);
}
