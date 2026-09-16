package cn.jia.chat.service;

/** JWT-derived strict scope and separately nominated, ownership-verified caller Agent. */
public record HallTrustedCaller(
        String tenantId, String clientId, String ownerJiacn, String callerAgentId) {
}
