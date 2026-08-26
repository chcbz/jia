package cn.jia.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.RabbitMQContainer;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit opt-in smoke; never runs unless jia.test.rabbitmq.smoke=true. */
@EnabledIfSystemProperty(named = "jia.test.rabbitmq.smoke", matches = "true")
class RabbitMqServerLoaderSmokeTest {
    @Test
    void randomCredentialsVhostAndHostPortAreEffectiveInsideRealContainer()
            throws Exception {
        RabbitMqServerLoader loader = new RabbitMqServerLoader();
        RabbitMQContainer container = loader.containerForInspection();
        assertTrue(container.getPortBindings().isEmpty());
        assertNotEquals("host", container.getNetworkMode());

        try {
            loader.start();
            assertTrue(loader.isRunning());
            assertEquals(loader.username(), container.getAdminUsername());
            assertEquals(loader.password(), container.getAdminPassword());
            assertNotEquals("/", loader.virtualHost());
            assertTrue(loader.virtualHost().startsWith("/m3-"));

            int mappedAmqpPort = container.getMappedPort(5672);
            assertTrue(mappedAmqpPort > 0);
            assertNotEquals(5672, mappedAmqpPort);

            Container.ExecResult authentication = container.execInContainer(
                    "rabbitmqctl", "authenticate_user", loader.username(), loader.password());
            assertEquals(0, authentication.getExitCode(),
                    authentication.getStdout() + authentication.getStderr());

            Container.ExecResult vhosts = container.execInContainer(
                    "rabbitmqctl", "-q", "list_vhosts", "name");
            assertEquals(0, vhosts.getExitCode(), vhosts.getStdout() + vhosts.getStderr());
            long exactVhostMatches = Arrays.stream(vhosts.getStdout().split("\\R"))
                    .filter(loader.virtualHost()::equals)
                    .count();
            assertEquals(1, exactVhostMatches, vhosts.getStdout());
        } finally {
            loader.stop();
        }
        assertFalse(loader.isRunning());
    }
}
