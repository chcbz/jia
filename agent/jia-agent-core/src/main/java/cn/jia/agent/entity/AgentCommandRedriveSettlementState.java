package cn.jia.agent.entity;

/** Durable proof state for the source DLQ delivery settlement barrier. */
public enum AgentCommandRedriveSettlementState {
    PENDING,
    SOURCE_ACKED,
    SOURCE_REQUEUED,
    NOT_ACQUIRED,
    UNKNOWN
}
