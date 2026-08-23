package cn.jia.agent.config;

/** Immutable dedicated M3 broker settings, registered only when a Rabbit flag is active. */
public final class AgentRabbitBrokerSettings {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;

    AgentRabbitBrokerSettings(AgentRabbitSafetyProperties.RabbitBroker broker) {
        this.host = broker.host();
        this.port = broker.port();
        this.username = broker.username();
        this.password = broker.password();
        this.virtualHost = broker.virtualHost();
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public String virtualHost() {
        return virtualHost;
    }

    @Override
    public String toString() {
        return "AgentRabbitBrokerSettings[host=" + host + ", port=" + port
                + ", username=<redacted>, password=<redacted>, virtualHost="
                + virtualHost + "]";
    }
}
