package cn.jia.agent.output;

import java.util.List;

public record OutputTicketAuthorization(
        String tenantId,
        String clientId,
        String runId,
        String sourceType,
        String sourceId,
        String producerAgentId,
        String bindingId,
        String runtimeInstanceId,
        boolean currentRuntimeTicket,
        List<String> operations,
        long expiresAt,
        String runState,
        int policyVersion,
        String workItemId,
        long recoveryUntil) {
    public OutputTicketAuthorization(String tenantId, String clientId, String runId,
            String sourceType, String sourceId, String producerAgentId, String bindingId,
            String runtimeInstanceId, boolean currentRuntimeTicket, List<String> operations,
            long expiresAt, String runState, int policyVersion, String workItemId) {
        this(tenantId, clientId, runId, sourceType, sourceId, producerAgentId, bindingId,
                runtimeInstanceId, currentRuntimeTicket, operations, expiresAt, runState,
                policyVersion, workItemId, expiresAt);
    }

    public OutputTicketAuthorization(String tenantId, String clientId, String runId,
            String sourceType, String sourceId, String producerAgentId, String bindingId,
            String runtimeInstanceId, List<String> operations, long expiresAt, String runState) {
        this(tenantId, clientId, runId, sourceType, sourceId, producerAgentId, bindingId,
                runtimeInstanceId, true, operations, expiresAt, runState, 0, null, expiresAt);
    }

    public OutputTicketAuthorization(String tenantId, String clientId, String runId,
            String sourceType, String sourceId, String producerAgentId, String bindingId,
            String runtimeInstanceId, List<String> operations, long expiresAt) {
        this(tenantId, clientId, runId, sourceType, sourceId, producerAgentId, bindingId,
                runtimeInstanceId, true, operations, expiresAt,
                OutputConstants.RUN_ACTIVE, 0, null, expiresAt);
    }
}
