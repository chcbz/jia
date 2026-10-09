package cn.jia.agent.config;

import com.rabbitmq.client.ConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Single construction point for the raw Rabbit client used beneath both the normal Spring
 * topology connection factory and privileged one-shot operations.
 */
public final class AgentRabbitRawConnectionFactoryBuilder {
    public ConnectionFactory canonical(AgentRabbitBrokerSettings broker) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(broker.host());
        factory.setPort(broker.port());
        factory.setUsername(broker.username());
        factory.setPassword(broker.password());
        factory.setVirtualHost(broker.virtualHost());
        return factory;
    }

    public ConnectionFactory oneShot(
            ConnectionFactory canonical,
            AgentCommandRedriveRabbitSettings settings) {
        ConnectionFactory factory = canonical.clone();
        factory.setConnectionTimeout(settings.connectionTimeoutMillis());
        factory.setHandshakeTimeout(settings.handshakeTimeoutMillis());
        factory.setChannelRpcTimeout(settings.channelRpcTimeoutMillis());
        factory.setShutdownTimeout(settings.shutdownTimeoutMillis());
        factory.setAutomaticRecoveryEnabled(false);
        factory.setTopologyRecoveryEnabled(false);
        return factory;
    }

    /** Credential-safe equality fingerprint used by configuration tests and diagnostics. */
    public String configurationFingerprint(ConnectionFactory factory) {
        String value = String.join("\u0000",
                factory.getHost(), Integer.toString(factory.getPort()),
                factory.getUsername(), factory.getPassword(), factory.getVirtualHost(),
                Integer.toString(factory.getRequestedHeartbeat()),
                Integer.toString(factory.getRequestedChannelMax()),
                Integer.toString(factory.getRequestedFrameMax()),
                className(factory.getSocketFactory()),
                className(factory.getSaslConfig()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String className(Object value) {
        return value == null ? "<default>" : value.getClass().getName();
    }
}
