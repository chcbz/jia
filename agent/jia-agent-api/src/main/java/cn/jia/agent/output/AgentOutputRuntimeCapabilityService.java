package cn.jia.agent.output;

import java.util.List;

/** Authenticated runtime capability snapshots, kept separate from business abilities. */
public interface AgentOutputRuntimeCapabilityService {
    void replaceAfterRegistration(
            String tenantId, String clientId, String agentId,
            String runtimeInstanceId, String registrationToken,
            List<String> outputCapabilities);

    void refreshPresence(
            String tenantId, String clientId, String agentId,
            String runtimeInstanceId, List<String> outputCapabilities);
}
