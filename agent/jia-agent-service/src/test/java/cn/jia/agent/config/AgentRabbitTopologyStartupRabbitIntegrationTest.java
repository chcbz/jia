package cn.jia.agent.config;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real dedicated-broker proof for the production startup topology activation path. */
@EnabledIfEnvironmentVariable(named = "OD07_RABBIT_HOST", matches = ".+")
class AgentRabbitTopologyStartupRabbitIntegrationTest {
    private CachingConnectionFactory connectionFactory;

    @AfterEach
    void closeConnectionFactory() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void startupProvisionAndPassiveVerificationProduceCanonicalReadiness()
            throws Exception {
        AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
        connectionFactory = new CachingConnectionFactory(
                required("OD07_RABBIT_HOST"), integer("OD07_RABBIT_PORT"));
        connectionFactory.setUsername(required("OD07_RABBIT_USER"));
        connectionFactory.setPassword(required("OD07_RABBIT_PASSWORD"));
        connectionFactory.setVirtualHost(required("OD07_RABBIT_VHOST"));
        connectionFactory.setPublisherConfirmType(
                CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        admin.setExplicitDeclarationsOnly(true);
        AgentRabbitTopologyReadiness readiness =
                new AgentRabbitTopologyReadiness(manifest);
        AgentRabbitTopologyProvisioner provisioner =
                new AgentRabbitTopologyProvisioner(
                        manifest, admin, connectionFactory, readiness);
        AgentRabbitTopologyStartup startup = new AgentRabbitTopologyStartup(provisioner);

        assertFalse(readiness.snapshot().canonicalTopologyReady());
        startup.run(null);

        AgentRabbitTopologyReadiness.Snapshot snapshot = readiness.snapshot();
        assertTrue(snapshot.canonicalTopologyReady());
        assertEquals(AgentRabbitTopologyReadiness.Status.READY, snapshot.status());
        assertEquals(AgentRabbitTopologyReadiness.Source.PROVISION, snapshot.source());
        assertEquals(AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                snapshot.coverage());
        assertEquals(manifest.sha256(), snapshot.manifestSha256());
        assertEquals(1L, snapshot.revision());

        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            for (AgentRabbitTopologyManifest.ExchangeSpec exchange : manifest.exchanges()) {
                channel.exchangeDeclarePassive(exchange.name());
            }
            for (AgentRabbitTopologyManifest.QueueSpec queue : manifest.queues()) {
                channel.queueDeclarePassive(queue.name());
            }
        }
        System.out.printf("OD07_RABBIT_STARTUP_PASS topologySha256=%s host=%s port=%d vhost=%s%n",
                manifest.sha256(), required("OD07_RABBIT_HOST"),
                integer("OD07_RABBIT_PORT"), required("OD07_RABBIT_VHOST"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static int integer(String name) {
        try {
            int value = Integer.parseInt(required(name));
            if (value < 1 || value > 65_535) {
                throw new IllegalStateException(name + " is out of range");
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(name + " is invalid", invalid);
        }
    }
}
