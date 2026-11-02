package cn.jia.agent.entity;

/** Process-local scheduling hint only. Durable scoped claims remain the authority. */
public record AgentTaskBountyBootstrapWakeup(long availableAt) { }
