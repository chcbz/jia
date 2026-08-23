package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
                ? new RabbitDispatch(false, List.of()) : rabbitDispatch;
    }

    public record CommandOutbox(boolean enabled) {
    }

    public record RabbitTopology(boolean enabled) {
    }

    public record RabbitPublish(boolean enabled) {
    }

    public record RabbitConsume(boolean enabled) {
    }

    public record RabbitDispatch(boolean enabled, List<AllowedScope> allowedScopes) {
        public RabbitDispatch {
            allowedScopes = allowedScopes == null ? List.of() : List.copyOf(allowedScopes);
        }
    }

    public record AllowedScope(String tenantId, String clientId) {
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
