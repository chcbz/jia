package cn.jia.agent.output;

public record OutputRunRequest(
        String tenantId,
        String clientId,
        String sourceType,
        String sourceId,
        String producerAgentId,
        String originType,
        String originId,
        String workItemId,
        int policyVersion) {
}
