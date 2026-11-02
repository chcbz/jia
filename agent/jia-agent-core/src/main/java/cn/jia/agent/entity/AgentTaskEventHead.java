package cn.jia.agent.entity;

/** Transient durable high-water projection, never a shared replay cursor or authorization. */
public record AgentTaskEventHead(String tenantId, String clientId, String ownerJiacn,
        String taskId, Long currentVersion) { }
