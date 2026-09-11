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
        List<String> operations,
        long expiresAt,
        String runState) {
    public OutputTicketAuthorization(String tenantId, String clientId, String runId,
            String sourceType, String sourceId, String producerAgentId, String bindingId,
            String runtimeInstanceId, List<String> operations, long expiresAt) {
        this(tenantId, clientId, runId, sourceType, sourceId, producerAgentId, bindingId,
                runtimeInstanceId, operations, expiresAt, OutputConstants.RUN_ACTIVE);
    }
}
