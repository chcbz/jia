package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Immutable startup-only configuration for the M3 command transport safety boundary. */
@ConfigurationProperties(prefix = "agent")
public record AgentRabbitSafetyProperties(
        CommandOutbox commandOutbox,
        RabbitTopology rabbitTopology,
        RabbitPublish rabbitPublish,
        RabbitConsume rabbitConsume,
        RabbitDispatch rabbitDispatch,
        RabbitBroker rabbitBroker) {

    public AgentRabbitSafetyProperties {
        commandOutbox = commandOutbox == null ? new CommandOutbox(false) : commandOutbox;
        rabbitTopology = rabbitTopology == null ? new RabbitTopology(false) : rabbitTopology;
        rabbitPublish = rabbitPublish == null ? new RabbitPublish(false) : rabbitPublish;
        rabbitConsume = rabbitConsume == null ? new RabbitConsume(false) : rabbitConsume;
        rabbitDispatch = rabbitDispatch == null
                ? new RabbitDispatch(false) : rabbitDispatch;
    }

    public record CommandOutbox(boolean enabled) {
    }

    public record RabbitTopology(boolean enabled) {
    }

    public record RabbitPublish(
            boolean enabled,
            Integer batchSize,
            Integer overscan,
            Long pollDelayMillis,
            Long leaseMillis,
            Long confirmTimeoutMillis,
            Integer maxInflight,
            Integer maxAttempts,
            Long initialBackoffMillis,
            Double backoffMultiplier,
            Long maxBackoffMillis,
            Integer jitterPercent,
            Long shutdownDrainMillis,
            Integer maxWireBytes) {
        @ConstructorBinding
        public RabbitPublish {
            batchSize = batchSize == null ? 50 : batchSize;
            overscan = overscan == null ? 4 : overscan;
            pollDelayMillis = pollDelayMillis == null ? 250L : pollDelayMillis;
            leaseMillis = leaseMillis == null ? 30_000L : leaseMillis;
            confirmTimeoutMillis = confirmTimeoutMillis == null ? 5_000L : confirmTimeoutMillis;
            maxInflight = maxInflight == null ? 50 : maxInflight;
            maxAttempts = maxAttempts == null ? 20 : maxAttempts;
            initialBackoffMillis = initialBackoffMillis == null ? 1_000L : initialBackoffMillis;
            backoffMultiplier = backoffMultiplier == null ? 2.0D : backoffMultiplier;
            maxBackoffMillis = maxBackoffMillis == null ? 300_000L : maxBackoffMillis;
            jitterPercent = jitterPercent == null ? 20 : jitterPercent;
            shutdownDrainMillis = shutdownDrainMillis == null ? 7_000L : shutdownDrainMillis;
            maxWireBytes = maxWireBytes == null ? 131_072 : maxWireBytes;
        }

        public RabbitPublish(boolean enabled) {
            this(enabled, null, null, null, null, null, null, null, null, null, null,
                    null, null, null);
        }
    }

    public record RabbitConsume(boolean enabled) {
    }

    public record RabbitDispatch(boolean enabled) {
    }

    /** Dedicated M3 broker boundary. It is intentionally unrelated to spring.rabbitmq. */
    public record RabbitBroker(
            String host,
            Integer port,
            String username,
            String password,
            String virtualHost) {
        @Override
        public String toString() {
            return "RabbitBroker[host=" + host + ", port=" + port
                    + ", username=<redacted>, password=<redacted>, virtualHost="
                    + virtualHost + "]";
        }
    }
}
