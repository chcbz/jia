package cn.jia.agent.config;

/** Bounded D06 reconnect signal and fallback scheduler resource limits. */
public record AgentCommandReissueSettings(
        int queueCapacity,
        int reconnectBatchSize,
        int schedulerBatchSize,
        long pollDelayMillis) {
    public AgentCommandReissueSettings() {
        this(256, 32, 50, 1_000L);
    }

    public AgentCommandReissueSettings {
        if (queueCapacity < 1 || queueCapacity > 4096
                || reconnectBatchSize < 1 || reconnectBatchSize > 100
                || schedulerBatchSize < 1 || schedulerBatchSize > 100
                || pollDelayMillis < 100 || pollDelayMillis > 300_000) {
            throw new IllegalArgumentException("invalid Agent command reissue settings");
        }
    }
}
