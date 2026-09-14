package cn.jia.agent.service;

import cn.jia.agent.entity.*;

public interface AgentRuntimeV1Service {
    AgentRuntimeV1InstallationView create(String tenantId, String clientId, String ownerJiacn,
            AgentRuntimeV1InstallationRequest request, long now);
    AgentRuntimeV1InstallationView status(String tenantId, String clientId, String installationId);
    void revoke(String tenantId, String clientId, String installationId, long now);
    AgentRuntimeV1EnrollmentResult enroll(AgentRuntimeV1EnrollmentRequest request, long now);
    AgentRuntimeV1InstallationView session(String authorization, AgentRuntimeV1RuntimeRequest request, long now);
    AgentRuntimeV1InstallationView heartbeat(String authorization, AgentRuntimeV1RuntimeRequest request, long now);
    AgentCommandAckResult acknowledge(String authorization, String pathMessageId,
            AgentRuntimeV1AckRequest request, long now);
}
