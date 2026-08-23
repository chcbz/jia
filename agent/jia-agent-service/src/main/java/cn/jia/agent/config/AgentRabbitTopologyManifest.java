package cn.jia.agent.config;

import org.springframework.amqp.core.AbstractExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Canonical immutable D04 Rabbit topology and its byte-stable SHA-256 identity. */
public final class AgentRabbitTopologyManifest {
    public static final String MAIN_EXCHANGE = "jia.agent.command";
    public static final String DEAD_LETTER_EXCHANGE = "jia.agent.command.dlx";

    public static final String DISPATCH_QUEUE = "jia.agent.command.dispatch.q";
    public static final String RETRY_5S_QUEUE = "jia.agent.command.retry.5s.q";
    public static final String RETRY_30S_QUEUE = "jia.agent.command.retry.30s.q";
    public static final String RETRY_5M_QUEUE = "jia.agent.command.retry.5m.q";
    public static final String DEAD_LETTER_QUEUE = "jia.agent.command.dlq";

    public static final String GENERAL_ROUTING_KEY = "agent.command.general";
    public static final String CODING_ROUTING_KEY = "agent.command.coding";
    public static final String RESEARCH_ROUTING_KEY = "agent.command.research";
    public static final String REVIEW_ROUTING_KEY = "agent.command.review";
    public static final String RETRY_5S_ROUTING_KEY = "agent.command.retry.5s";
    public static final String RETRY_30S_ROUTING_KEY = "agent.command.retry.30s";
    public static final String RETRY_5M_ROUTING_KEY = "agent.command.retry.5m";
    public static final String DEAD_ROUTING_KEY = "agent.command.dead";

    public static final String CANONICAL_SHA256 =
            "96fd7d32aba468eacbcf96dfa5d441fd938f0d0cb5c6dafcdcae9e797fb4110e";

    private static final AgentRabbitTopologyManifest CANONICAL = createCanonical();

    private final List<ExchangeSpec> exchanges;
    private final List<QueueSpec> queues;
    private final List<BindingSpec> bindings;
    private final String canonicalText;
    private final String sha256;

    private AgentRabbitTopologyManifest(
            List<ExchangeSpec> exchanges,
            List<QueueSpec> queues,
            List<BindingSpec> bindings) {
        this.exchanges = List.copyOf(exchanges);
        this.queues = List.copyOf(queues);
        this.bindings = List.copyOf(bindings);
        canonicalText = canonicalize(this.exchanges, this.queues, this.bindings);
        sha256 = sha256(canonicalText.getBytes(StandardCharsets.UTF_8));
        if (!CANONICAL_SHA256.equals(sha256)) {
            throw new IllegalStateException("D04 canonical Rabbit topology digest drift");
        }
    }

    public static AgentRabbitTopologyManifest canonical() {
        return CANONICAL;
    }

    public List<ExchangeSpec> exchanges() {
        return exchanges;
    }

    public List<QueueSpec> queues() {
        return queues;
    }

    public List<BindingSpec> bindings() {
        return bindings;
    }

    public String canonicalText() {
        return canonicalText;
    }

    public byte[] canonicalBytes() {
        return canonicalText.getBytes(StandardCharsets.UTF_8);
    }

    public String sha256() {
        return sha256;
    }

    /** Exact business-publish routes derived from the canonical dispatch bindings. */
    public List<PublishRoute> commandPublishRoutes() {
        return bindings.stream()
                .filter(binding -> DISPATCH_QUEUE.equals(binding.queue()))
                .map(binding -> new PublishRoute(binding.exchange(), binding.routingKey()))
                .toList();
    }

    /** D02's initial command route; ordering is part of the canonical manifest digest. */
    public PublishRoute defaultCommandPublishRoute() {
        List<PublishRoute> routes = commandPublishRoutes();
        if (routes.isEmpty()) {
            throw new IllegalStateException("D04 canonical manifest has no command publish route");
        }
        return routes.getFirst();
    }

    public boolean allowsCommandPublish(String destination, String routingKey) {
        return destination != null && routingKey != null
                && commandPublishRoutes().contains(new PublishRoute(destination, routingKey));
    }

    /** Exact exchange/routing pairs declared by D04, including future D05 retry/DLQ routes. */
    public boolean allowsPublish(String destination, String routingKey) {
        if (destination == null || routingKey == null) return false;
        PublishRoute requested = new PublishRoute(destination, routingKey);
        return bindings.stream()
                .map(binding -> new PublishRoute(binding.exchange(), binding.routingKey()))
                .anyMatch(requested::equals);
    }

    List<Exchange> exchangeDeclarations() {
        List<Exchange> declarations = new ArrayList<>(exchanges.size());
        for (ExchangeSpec spec : exchanges) {
            AbstractExchange exchange = switch (spec.type()) {
                case ExchangeTypes.TOPIC -> new TopicExchange(
                        spec.name(), spec.durable(), spec.autoDelete(), spec.arguments());
                case ExchangeTypes.DIRECT -> new DirectExchange(
                        spec.name(), spec.durable(), spec.autoDelete(), spec.arguments());
                default -> throw new IllegalStateException(
                        "Unsupported D04 exchange type " + spec.type());
            };
            exchange.setInternal(spec.internal());
            declarations.add(exchange);
        }
        return List.copyOf(declarations);
    }

    List<Queue> queueDeclarations() {
        return queues.stream()
                .map(spec -> new Queue(spec.name(), spec.durable(), spec.exclusive(),
                        spec.autoDelete(), spec.arguments()))
                .toList();
    }

    List<Binding> bindingDeclarations() {
        return bindings.stream()
                .map(spec -> new Binding(spec.queue(), Binding.DestinationType.QUEUE,
                        spec.exchange(), spec.routingKey(), spec.arguments()))
                .toList();
    }

    private static AgentRabbitTopologyManifest createCanonical() {
        Map<String, Object> dispatchArguments = arguments(
                "x-dead-letter-exchange", DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key", DEAD_ROUTING_KEY);
        Map<String, Object> retry5sArguments = retryArguments(5_000L);
        Map<String, Object> retry30sArguments = retryArguments(30_000L);
        Map<String, Object> retry5mArguments = retryArguments(300_000L);

        List<ExchangeSpec> exchanges = List.of(
                new ExchangeSpec(MAIN_EXCHANGE, ExchangeTypes.TOPIC,
                        true, false, false, Map.of()),
                new ExchangeSpec(DEAD_LETTER_EXCHANGE, ExchangeTypes.DIRECT,
                        true, false, false, Map.of()));
        List<QueueSpec> queues = List.of(
                new QueueSpec(DISPATCH_QUEUE, true, false, false, dispatchArguments),
                new QueueSpec(RETRY_5S_QUEUE, true, false, false, retry5sArguments),
                new QueueSpec(RETRY_30S_QUEUE, true, false, false, retry30sArguments),
                new QueueSpec(RETRY_5M_QUEUE, true, false, false, retry5mArguments),
                new QueueSpec(DEAD_LETTER_QUEUE, true, false, false, Map.of()));
        List<BindingSpec> bindings = List.of(
                binding(MAIN_EXCHANGE, DISPATCH_QUEUE, GENERAL_ROUTING_KEY),
                binding(MAIN_EXCHANGE, DISPATCH_QUEUE, CODING_ROUTING_KEY),
                binding(MAIN_EXCHANGE, DISPATCH_QUEUE, RESEARCH_ROUTING_KEY),
                binding(MAIN_EXCHANGE, DISPATCH_QUEUE, REVIEW_ROUTING_KEY),
                binding(DEAD_LETTER_EXCHANGE, RETRY_5S_QUEUE, RETRY_5S_ROUTING_KEY),
                binding(DEAD_LETTER_EXCHANGE, RETRY_30S_QUEUE, RETRY_30S_ROUTING_KEY),
                binding(DEAD_LETTER_EXCHANGE, RETRY_5M_QUEUE, RETRY_5M_ROUTING_KEY),
                binding(DEAD_LETTER_EXCHANGE, DEAD_LETTER_QUEUE, DEAD_ROUTING_KEY));
        return new AgentRabbitTopologyManifest(exchanges, queues, bindings);
    }

    private static BindingSpec binding(String exchange, String queue, String routingKey) {
        return new BindingSpec(exchange, queue, routingKey, Map.of());
    }

    private static Map<String, Object> retryArguments(long ttlMillis) {
        return arguments(
                "x-dead-letter-exchange", MAIN_EXCHANGE,
                "x-dead-letter-routing-key", GENERAL_ROUTING_KEY,
                "x-message-ttl", ttlMillis);
    }

    private static Map<String, Object> arguments(Object... pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("D04 argument pairs must be even");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String canonicalize(
            List<ExchangeSpec> exchanges,
            List<QueueSpec> queues,
            List<BindingSpec> bindings) {
        StringBuilder canonical = new StringBuilder("agent-rabbit-topology/v1\n");
        for (ExchangeSpec exchange : exchanges) {
            canonical.append("exchange|").append(exchange.name())
                    .append('|').append(exchange.type())
                    .append("|durable=").append(exchange.durable())
                    .append("|autoDelete=").append(exchange.autoDelete())
                    .append("|internal=").append(exchange.internal())
                    .append("|arguments=").append(canonicalArguments(exchange.arguments()))
                    .append('\n');
        }
        for (QueueSpec queue : queues) {
            canonical.append("queue|").append(queue.name())
                    .append("|durable=").append(queue.durable())
                    .append("|exclusive=").append(queue.exclusive())
                    .append("|autoDelete=").append(queue.autoDelete())
                    .append("|arguments=").append(canonicalArguments(queue.arguments()))
                    .append('\n');
        }
        for (BindingSpec binding : bindings) {
            canonical.append("binding|").append(binding.exchange())
                    .append('|').append(binding.queue())
                    .append('|').append(binding.routingKey())
                    .append("|arguments=").append(canonicalArguments(binding.arguments()))
                    .append('\n');
        }
        return canonical.toString();
    }

    private static String canonicalArguments(Map<String, Object> arguments) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(arguments).entrySet()) {
            if (!canonical.isEmpty()) {
                canonical.append(';');
            }
            Object value = entry.getValue();
            canonical.append(entry.getKey()).append('=')
                    .append(value instanceof Number ? "number:" : "string:")
                    .append(value);
        }
        return canonical.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Map<String, Object> immutableArguments(Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "argument key");
            Object value = Objects.requireNonNull(entry.getValue(), "argument value");
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new IllegalArgumentException("Unsupported D04 argument value for " + key);
            }
            sorted.put(key, value);
        }
        return Collections.unmodifiableMap(sorted);
    }

    public record ExchangeSpec(
            String name,
            String type,
            boolean durable,
            boolean autoDelete,
            boolean internal,
            Map<String, Object> arguments) {
        public ExchangeSpec {
            name = requireToken(name, "exchange name");
            type = requireToken(type, "exchange type").toLowerCase(Locale.ROOT);
            arguments = immutableArguments(arguments);
        }
    }

    public record QueueSpec(
            String name,
            boolean durable,
            boolean exclusive,
            boolean autoDelete,
            Map<String, Object> arguments) {
        public QueueSpec {
            name = requireToken(name, "queue name");
            arguments = immutableArguments(arguments);
        }
    }

    public record BindingSpec(
            String exchange,
            String queue,
            String routingKey,
            Map<String, Object> arguments) {
        public BindingSpec {
            exchange = requireToken(exchange, "binding exchange");
            queue = requireToken(queue, "binding queue");
            routingKey = requireToken(routingKey, "binding routing key");
            arguments = immutableArguments(arguments);
        }
    }

    public record PublishRoute(String destination, String routingKey) {
        public PublishRoute {
            destination = requireToken(destination, "publish destination");
            routingKey = requireToken(routingKey, "publish routing key");
        }
    }

    private static String requireToken(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("D04 " + label + " is required");
        }
        return value;
    }
}
