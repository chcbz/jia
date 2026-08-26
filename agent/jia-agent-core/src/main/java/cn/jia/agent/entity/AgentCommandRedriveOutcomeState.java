package cn.jia.agent.entity;

/** Durable terminalization state for one privileged broker-redrive operation. */
public enum AgentCommandRedriveOutcomeState {
    PENDING,
    SUCCEEDED,
    FAILED
}
