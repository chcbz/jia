package cn.jia.agent.config;

/** Startup activation state for the M3 command transport. */
public enum AgentRabbitActivationState {
    OFF,
    DB_SHADOW,
    MQ_SHADOW,
    DISPATCH_CANARY,
    DISPATCH_SCOPED
}
