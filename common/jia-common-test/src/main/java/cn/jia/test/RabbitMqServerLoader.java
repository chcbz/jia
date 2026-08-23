package cn.jia.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.lifecycle.Startable;
import org.testcontainers.utility.DockerImageName;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Explicit isolated RabbitMQ Testcontainers lifecycle helper.
 *
 * <p>The helper is deliberately not a Spring component and never starts in its constructor.
 * JUnit tests may use it as a static {@code @Container} field or call start/stop explicitly.</p>
 */
public final class RabbitMqServerLoader implements Startable {
    public static final String IMAGE_OVERRIDE_PROPERTY = "jia.test.rabbitmq.image";
    public static final String DEFAULT_IMAGE = "rabbitmq:4.1-management-alpine";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RabbitMQContainer container;
    private final String imageName;
    private final String username;
    private final String password;
    private final String virtualHost;

    public RabbitMqServerLoader() {
        this(resolveImage());
    }

    public RabbitMqServerLoader(String imageName) {
        this(createContainer(imageName));
    }

    private RabbitMqServerLoader(IsolatedContainer isolated) {
        this(isolated.container(), isolated.imageName(), isolated.username(),
                isolated.password(), isolated.virtualHost());
    }

    RabbitMqServerLoader(RabbitMQContainer container, String username,
            String password, String virtualHost) {
        this(container, "injected:test", username, password, virtualHost);
    }

    RabbitMqServerLoader(RabbitMQContainer container, String imageName, String username,
            String password, String virtualHost) {
        this.container = Objects.requireNonNull(container, "container");
        this.imageName = requireNonBlank(imageName, "imageName");
        this.username = requireNonBlank(username, "username");
        this.password = requireNonBlank(password, "password");
        this.virtualHost = requireNonDefaultVhost(virtualHost);
    }

    @Override
    public void start() {
        container.start();
    }

    @Override
    public void stop() {
        container.stop();
    }

    public boolean isRunning() {
        return container.isRunning();
    }

    public String imageName() {
        return imageName;
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

    RabbitMQContainer containerForInspection() {
        return container;
    }

    /** Registers legacy Spring Rabbit properties lazily after the explicit container starts. */
    public void registerSpringRabbitProperties(DynamicPropertyRegistry registry) {
        register(registry, springRabbitSuppliers());
    }

    /** Registers only the independent M3 broker boundary, never spring.rabbitmq. */
    public void registerAgentRabbitBrokerProperties(DynamicPropertyRegistry registry) {
        register(registry, agentRabbitBrokerSuppliers());
    }

    public Map<String, Object> springRabbitProperties() {
        requireRunning();
        return materialize(springRabbitSuppliers());
    }

    public Map<String, Object> agentRabbitBrokerProperties() {
        requireRunning();
        return materialize(agentRabbitBrokerSuppliers());
    }

    private Map<String, Supplier<Object>> springRabbitSuppliers() {
        LinkedHashMap<String, Supplier<Object>> values = new LinkedHashMap<>();
        values.put("spring.rabbitmq.host", container::getHost);
        values.put("spring.rabbitmq.port", container::getAmqpPort);
        values.put("spring.rabbitmq.username", () -> username);
        values.put("spring.rabbitmq.password", () -> password);
        values.put("spring.rabbitmq.virtual-host", () -> virtualHost);
        return Map.copyOf(values);
    }

    private Map<String, Supplier<Object>> agentRabbitBrokerSuppliers() {
        LinkedHashMap<String, Supplier<Object>> values = new LinkedHashMap<>();
        values.put("agent.rabbit-broker.host", container::getHost);
        values.put("agent.rabbit-broker.port", container::getAmqpPort);
        values.put("agent.rabbit-broker.username", () -> username);
        values.put("agent.rabbit-broker.password", () -> password);
        values.put("agent.rabbit-broker.virtual-host", () -> virtualHost);
        return Map.copyOf(values);
    }

    private static void register(DynamicPropertyRegistry registry,
            Map<String, Supplier<Object>> values) {
        Objects.requireNonNull(registry, "registry");
        values.forEach(registry::add);
    }

    private static Map<String, Object> materialize(Map<String, Supplier<Object>> suppliers) {
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        suppliers.forEach((name, supplier) -> values.put(name, supplier.get()));
        return Map.copyOf(values);
    }

    private void requireRunning() {
        if (!container.isRunning()) {
            throw new IllegalStateException("RabbitMQ Testcontainer has not been started explicitly");
        }
    }

    private static IsolatedContainer createContainer(String imageName) {
        String checkedImage = requireNonBlank(imageName, "imageName");
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "m3_" + suffix;
        String password = randomSecret();
        String virtualHost = "/m3-" + suffix;
        RabbitMQContainer container = new RabbitMQContainer(DockerImageName.parse(checkedImage))
                .withEnv("RABBITMQ_DEFAULT_USER", username)
                .withEnv("RABBITMQ_DEFAULT_PASS", password)
                .withEnv("RABBITMQ_DEFAULT_VHOST", virtualHost);
        return new IsolatedContainer(container, checkedImage, username, password, virtualHost);
    }

    private static String resolveImage() {
        return System.getProperty(IMAGE_OVERRIDE_PROPERTY, DEFAULT_IMAGE);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }

    private static String requireNonDefaultVhost(String value) {
        requireNonBlank(value, "virtualHost");
        if ("/".equals(value)) {
            throw new IllegalArgumentException("virtualHost must not be the RabbitMQ default vhost");
        }
        return value;
    }

    private record IsolatedContainer(
            RabbitMQContainer container,
            String imageName,
            String username,
            String password,
            String virtualHost) {
    }
}
