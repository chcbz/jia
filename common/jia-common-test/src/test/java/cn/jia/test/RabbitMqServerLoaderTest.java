package cn.jia.test;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RabbitMqServerLoaderTest {
    @Test
    void constructionIsDockerFreeRandomAndHasNoFixedBindingOrHostNetwork() {
        RabbitMqServerLoader first = new RabbitMqServerLoader();
        RabbitMqServerLoader second = new RabbitMqServerLoader();

        assertFalse(first.isRunning());
        assertNotEquals(first.username(), second.username());
        assertNotEquals(first.password(), second.password());
        assertNotEquals(first.virtualHost(), second.virtualHost());
        assertNotEquals("/", first.virtualHost());
        assertFalse(first.imageName().contains("3.8"));
        assertEquals(RabbitMqServerLoader.DEFAULT_IMAGE, first.imageName());
        assertTrue(first.containerForInspection().getExposedPorts().contains(5672));
        assertTrue(first.containerForInspection().getPortBindings().isEmpty());
        assertNotEquals("host", first.containerForInspection().getNetworkMode());
    }

    @Test
    void explicitImageOverrideDoesNotPinHelperToDefaultImage() {
        RabbitMqServerLoader loader = new RabbitMqServerLoader("rabbitmq:4.2-management");
        assertEquals("rabbitmq:4.2-management", loader.imageName());
    }

    @Test
    void systemPropertyProvidesControlledImageOverride() {
        String previous = System.getProperty(RabbitMqServerLoader.IMAGE_OVERRIDE_PROPERTY);
        try {
            System.setProperty(RabbitMqServerLoader.IMAGE_OVERRIDE_PROPERTY,
                    "rabbitmq:4.2-management-alpine");
            assertEquals("rabbitmq:4.2-management-alpine",
                    new RabbitMqServerLoader().imageName());
        } finally {
            if (previous == null) {
                System.clearProperty(RabbitMqServerLoader.IMAGE_OVERRIDE_PROPERTY);
            } else {
                System.setProperty(RabbitMqServerLoader.IMAGE_OVERRIDE_PROPERTY, previous);
            }
        }
    }

    @Test
    void helperSourceHasNoSpringComponentJUnitLifecycleFixedBindingOrHostNetwork()
            throws Exception {
        Path source = apiRoot().resolve(
                "common/jia-common-test/src/main/java/cn/jia/test/RabbitMqServerLoader.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        for (String forbiddenAnnotation : new String[] {
                "Component", "Configuration", "Value", "Testcontainers", "Container"}) {
            assertFalse(text.matches("(?s).*\\n\\s*@" + forbiddenAnnotation + "(?:\\s|\\().*"),
                    forbiddenAnnotation);
        }
        for (String forbiddenCall : new String[] {
                "withFixedExposedPort", "withNetworkMode(\"host\")", "rabbitmq:3.8"}) {
            assertFalse(text.contains(forbiddenCall), forbiddenCall);
        }
    }

    @Test
    void lifecycleIsExplicitAndDelegatesOnlyWhenCalled() {
        RabbitMQContainer container = mock(RabbitMQContainer.class);
        RabbitMqServerLoader loader = new RabbitMqServerLoader(
                container, "random-user", "random-password", "/m3-random");

        verify(container, never()).start();
        loader.start();
        verify(container).start();
        loader.stop();
        verify(container).stop();
    }

    @Test
    void dynamicPropertiesAreLazyAndCoverLegacyAndIndependentM3Shapes() {
        RabbitMQContainer container = mock(RabbitMQContainer.class);
        when(container.getHost()).thenReturn("container-host");
        when(container.getAmqpPort()).thenReturn(35672);
        RabbitMqServerLoader loader = new RabbitMqServerLoader(
                container, "random-user", "random-password", "/m3-random");
        CapturingRegistry registry = new CapturingRegistry();

        loader.registerSpringRabbitProperties(registry);
        loader.registerAgentRabbitBrokerProperties(registry);

        assertEquals("container-host", registry.value("spring.rabbitmq.host"));
        assertEquals(35672, registry.value("spring.rabbitmq.port"));
        assertEquals("random-user", registry.value("spring.rabbitmq.username"));
        assertEquals("random-password", registry.value("spring.rabbitmq.password"));
        assertEquals("/m3-random", registry.value("spring.rabbitmq.virtual-host"));
        assertEquals("container-host", registry.value("agent.rabbit-broker.host"));
        assertEquals(35672, registry.value("agent.rabbit-broker.port"));
        assertEquals("/m3-random", registry.value("agent.rabbit-broker.virtual-host"));
    }

    @Test
    void materializedPropertiesRequireExplicitStart() {
        RabbitMQContainer container = mock(RabbitMQContainer.class);
        when(container.isRunning()).thenReturn(false);
        RabbitMqServerLoader loader = new RabbitMqServerLoader(
                container, "random-user", "random-password", "/m3-random");

        assertThrows(IllegalStateException.class, loader::springRabbitProperties);
        assertThrows(IllegalStateException.class, loader::agentRabbitBrokerProperties);
    }

    @Test
    void defaultVhostIsRejectedEvenForInjectedLifecycleTests() {
        RabbitMQContainer container = mock(RabbitMQContainer.class);
        assertThrows(IllegalArgumentException.class, () -> new RabbitMqServerLoader(
                container, "random-user", "random-password", "/"));
    }

    private static Path apiRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("common"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate API worktree root from " + current);
    }

    private static final class CapturingRegistry implements DynamicPropertyRegistry {
        private final Map<String, Supplier<Object>> suppliers = new LinkedHashMap<>();

        @Override
        public void add(String name, Supplier<Object> valueSupplier) {
            suppliers.put(name, valueSupplier);
        }

        Object value(String name) {
            return suppliers.get(name).get();
        }
    }
}
