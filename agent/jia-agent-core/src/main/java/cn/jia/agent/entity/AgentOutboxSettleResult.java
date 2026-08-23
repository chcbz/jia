package cn.jia.agent.entity;

/** Durable result of one independent fenced settle transaction. */
public enum AgentOutboxSettleResult {
    PUBLISHED,
    RETRY,
    FAILED,
    EXPIRED,
    DEAD,
    STALE
}
