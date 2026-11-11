package cn.jia.agent.service;

import cn.jia.agent.entity.*;

public interface AgentRuntimeV1Service {
    AgentRuntimeV1InstallationView create(String tenantId, String clientId, String ownerJiacn,
            AgentRuntimeV1InstallationRequest request, long now);
    /** Internal trusted hosting boundary; no controller exposes this operation. Exact immutable replay only. */
    AgentRuntimeV1InstallationView ensureInstallation(String tenantId, String clientId, String ownerJiacn,
            AgentRuntimeV1InstallationRequest request, long now);
    AgentRuntimeV1InstallationView status(String tenantId, String clientId, String ownerJiacn, String installationId);
    /** Internal same-transaction installation fence; never creates or re-enrolls an installation. */
    AgentRuntimeV1InstallationView lockExistingInstallation(String tenantId, String clientId, String ownerJiacn, String installationId);
    void revoke(String tenantId, String clientId, String ownerJiacn, String installationId, long now);
    AgentRuntimeV1EnrollmentResult enroll(AgentRuntimeV1EnrollmentRequest request, long now);
    /** Internal installation credential boundary. Callback must enforce its own maintenance relationship;
     * does not issue a user session or grant execution authority. Holds installation lock through callback. */
    <T> T withInstallationAuthorization(String authorization,
            java.util.function.Function<AgentRuntimeV1InstallationView, T> operation);
    AgentRuntimeV1SessionResponse session(String authorization, AgentRuntimeV1SessionRequest request, long now);
    AgentRuntimeV1InstallationView heartbeat(String authorization, AgentRuntimeV1RuntimeRequest request, long now);
    AgentCommandAckResult acknowledge(String authorization, String pathMessageId,
            AgentRuntimeV1AckRequest request, long now);
}
