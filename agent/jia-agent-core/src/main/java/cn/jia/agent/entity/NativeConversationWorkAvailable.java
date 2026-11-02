package cn.jia.agent.entity;

/** Process-local scheduling hint. No command, task grant, receipt or execution authority. */
public record NativeConversationWorkAvailable(String tenantId, String clientId, String agentId) { }
