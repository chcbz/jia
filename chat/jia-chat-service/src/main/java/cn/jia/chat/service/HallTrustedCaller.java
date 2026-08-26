package cn.jia.chat.service;

/** JWT-derived tenant/client scope plus a separately nominated, ownership-verified caller Agent. */
public record HallTrustedCaller(String tenantId, String clientId, String callerAgentId) {
}
