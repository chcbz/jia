package cn.jia.agent.config;

/** Read-only readiness placeholder; infrastructure probes are added by later M3 tasks. */
public final class AgentRabbitReadiness {
    private final AgentRabbitActivationState state;
    private final boolean brokerRequired;

    AgentRabbitReadiness(AgentRabbitSafetyGate gate) {
        this.state = gate.state();
        this.brokerRequired = gate.brokerRequired();
    }

    public AgentRabbitActivationState state() {
        return state;
    }

    public boolean brokerRequired() {
        return brokerRequired;
    }
}
