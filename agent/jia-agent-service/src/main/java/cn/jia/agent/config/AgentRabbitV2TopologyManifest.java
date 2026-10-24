package cn.jia.agent.config;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.ProducerScope;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
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
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable protocol-v2 Rabbit topology for one trusted tenant/domain/pool lane. */
public final class AgentRabbitV2TopologyManifest {
    private static final String RESOURCE_PREFIX = "jia.agent.command.v2.s.";
    private static final String ROUTING_PREFIX = "agent.command.v2.s.";

    private final Scope scope;
    private final String scopeSha256;
    private final String mainExchange;
    private final String deadLetterExchange;
    private final String dispatchQueue;
    private final String retry5sQueue;
    private final String retry30sQueue;
    private final String retry5mQueue;
    private final String deadLetterQueue;
    private final String generalRoutingKey;
    private final String codingRoutingKey;
    private final String researchRoutingKey;
    private final String reviewRoutingKey;
    private final String retry5sRoutingKey;
    private final String retry30sRoutingKey;
    private final String retry5mRoutingKey;
    private final String deadRoutingKey;
    private final List<ExchangeSpec> exchanges;
    private final List<QueueSpec> queues;
    private final List<BindingSpec> bindings;
    private final String canonicalText;
    private final String sha256;

    private AgentRabbitV2TopologyManifest(Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
        scopeSha256 = sha256(scope.canonicalText().getBytes(StandardCharsets.UTF_8));
        String resourceBase = RESOURCE_PREFIX + scopeSha256;
        String routingBase = ROUTING_PREFIX + scopeSha256;
        mainExchange = resourceBase + ".x";
        deadLetterExchange = resourceBase + ".dlx";
        dispatchQueue = resourceBase + ".dispatch.q";
        retry5sQueue = resourceBase + ".retry.5s.q";
        retry30sQueue = resourceBase + ".retry.30s.q";
        retry5mQueue = resourceBase + ".retry.5m.q";
        deadLetterQueue = resourceBase + ".dlq";
        generalRoutingKey = routingBase + ".general";
        codingRoutingKey = routingBase + ".coding";
        researchRoutingKey = routingBase + ".research";
        reviewRoutingKey = routingBase + ".review";
        retry5sRoutingKey = routingBase + ".retry.5s";
        retry30sRoutingKey = routingBase + ".retry.30s";
        retry5mRoutingKey = routingBase + ".retry.5m";
        deadRoutingKey = routingBase + ".dead";

        exchanges = List.of(
                new ExchangeSpec(mainExchange, ExchangeTypes.TOPIC,
                        true, false, false, Map.of()),
                new ExchangeSpec(deadLetterExchange, ExchangeTypes.DIRECT,
                        true, false, false, Map.of()));
        queues = List.of(
                new QueueSpec(dispatchQueue, true, false, false, arguments(
                        "x-dead-letter-exchange", deadLetterExchange,
                        "x-dead-letter-routing-key", deadRoutingKey)),
                new QueueSpec(retry5sQueue, true, false, false, retryArguments(5_000L)),
                new QueueSpec(retry30sQueue, true, false, false, retryArguments(30_000L)),
                new QueueSpec(retry5mQueue, true, false, false, retryArguments(300_000L)),
                new QueueSpec(deadLetterQueue, true, false, false, Map.of()));
        bindings = List.of(
                binding(mainExchange, dispatchQueue, generalRoutingKey),
                binding(mainExchange, dispatchQueue, codingRoutingKey),
                binding(mainExchange, dispatchQueue, researchRoutingKey),
                binding(mainExchange, dispatchQueue, reviewRoutingKey),
                binding(deadLetterExchange, retry5sQueue, retry5sRoutingKey),
                binding(deadLetterExchange, retry30sQueue, retry30sRoutingKey),
                binding(deadLetterExchange, retry5mQueue, retry5mRoutingKey),
                binding(deadLetterExchange, deadLetterQueue, deadRoutingKey));
        canonicalText = canonicalize();
        sha256 = sha256(canonicalText.getBytes(StandardCharsets.UTF_8));
    }

    public static AgentRabbitV2TopologyManifest forScope(
            TenantId tenantId, DataDomainId dataDomainId, String executionPoolId) {
        return new AgentRabbitV2TopologyManifest(
                new Scope(tenantId, dataDomainId, executionPoolId));
    }

    public static AgentRabbitV2TopologyManifest forScope(ProducerScope scope) {
        Objects.requireNonNull(scope, "scope");
        return forScope(scope.tenantId(), scope.dataDomainId(), scope.executionPoolId());
    }

    public static AgentRabbitV2TopologyManifest forScope(WorkerScope scope) {
        Objects.requireNonNull(scope, "scope");
        return forScope(scope.tenantId(), scope.dataDomainId(), scope.executionPoolId());
    }

    public static AgentRabbitV2TopologyManifest forPersistedScope(
            String tenantId, String dataDomainId, String executionPoolId) {
        return forScope(TenantId.of(tenantId), DataDomainId.of(dataDomainId), executionPoolId);
    }

    public Scope scope() { return scope; }
    public String scopeSha256() { return scopeSha256; }
    public String mainExchange() { return mainExchange; }
    public String deadLetterExchange() { return deadLetterExchange; }
    public String dispatchQueue() { return dispatchQueue; }
    public String retry5sQueue() { return retry5sQueue; }
    public String retry30sQueue() { return retry30sQueue; }
    public String retry5mQueue() { return retry5mQueue; }
    public String deadLetterQueue() { return deadLetterQueue; }
    public String generalRoutingKey() { return generalRoutingKey; }
    public String retry5sRoutingKey() { return retry5sRoutingKey; }
    public String retry30sRoutingKey() { return retry30sRoutingKey; }
    public String retry5mRoutingKey() { return retry5mRoutingKey; }
    public String deadRoutingKey() { return deadRoutingKey; }
    public List<ExchangeSpec> exchanges() { return exchanges; }
    public List<QueueSpec> queues() { return queues; }
    public List<BindingSpec> bindings() { return bindings; }
    public String canonicalText() { return canonicalText; }
    public byte[] canonicalBytes() { return canonicalText.getBytes(StandardCharsets.UTF_8); }
    public String sha256() { return sha256; }

    public PublishRoute defaultCommandPublishRoute() {
        return new PublishRoute(mainExchange, generalRoutingKey);
    }

    public PublishRoute retry5sPublishRoute() {
        return new PublishRoute(deadLetterExchange, retry5sRoutingKey);
    }

    public PublishRoute retry30sPublishRoute() {
        return new PublishRoute(deadLetterExchange, retry30sRoutingKey);
    }

    public PublishRoute retry5mPublishRoute() {
        return new PublishRoute(deadLetterExchange, retry5mRoutingKey);
    }

    public PublishRoute deadLetterPublishRoute() {
        return new PublishRoute(deadLetterExchange, deadRoutingKey);
    }

    public boolean matchesScope(String tenantId, String dataDomainId, String executionPoolId) {
        return scope.tenantId().value().equals(tenantId)
                && scope.dataDomainId().value().equals(dataDomainId)
                && scope.executionPoolId().equals(executionPoolId);
    }

    public boolean allowsCommandPublish(String destination, String routingKey) {
        if (destination == null || routingKey == null) return false;
        return bindings.subList(0, 4).stream().anyMatch(binding ->
                binding.exchange().equals(destination) && binding.routingKey().equals(routingKey));
    }

    public boolean allowsPublish(String destination, String routingKey) {
        if (destination == null || routingKey == null) return false;
        return bindings.stream().anyMatch(binding ->
                binding.exchange().equals(destination) && binding.routingKey().equals(routingKey));
    }

    List<Exchange> exchangeDeclarations() {
        List<Exchange> declarations = new ArrayList<>(exchanges.size());
        for (ExchangeSpec spec : exchanges) {
            AbstractExchange exchange = switch (spec.type()) {
                case ExchangeTypes.TOPIC -> new TopicExchange(
                        spec.name(), spec.durable(), spec.autoDelete(), spec.arguments());
                case ExchangeTypes.DIRECT -> new DirectExchange(
                        spec.name(), spec.durable(), spec.autoDelete(), spec.arguments());
                default -> throw new IllegalStateException("Unsupported v2 exchange type");
            };
            exchange.setInternal(spec.internal());
            declarations.add(exchange);
        }
        return List.copyOf(declarations);
    }

    List<Queue> queueDeclarations() {
        return queues.stream().map(spec -> new Queue(spec.name(), spec.durable(),
                spec.exclusive(), spec.autoDelete(), spec.arguments())).toList();
    }

    List<Binding> bindingDeclarations() {
        return bindings.stream().map(spec -> new Binding(spec.queue(),
                Binding.DestinationType.QUEUE, spec.exchange(), spec.routingKey(),
                spec.arguments())).toList();
    }

    private Map<String, Object> retryArguments(long ttlMillis) {
        return arguments(
                "x-dead-letter-exchange", mainExchange,
                "x-dead-letter-routing-key", generalRoutingKey,
                "x-message-ttl", ttlMillis);
    }

    private String canonicalize() {
        StringBuilder text = new StringBuilder("agent-rabbit-topology/v2\n")
                .append(scope.canonicalText());
        for (ExchangeSpec exchange : exchanges) {
            text.append("exchange|").append(exchange.name()).append('|').append(exchange.type())
                    .append("|durable=").append(exchange.durable())
                    .append("|autoDelete=").append(exchange.autoDelete())
                    .append("|internal=").append(exchange.internal())
                    .append("|arguments=").append(canonicalArguments(exchange.arguments()))
                    .append('\n');
        }
        for (QueueSpec queue : queues) {
            text.append("queue|").append(queue.name())
                    .append("|durable=").append(queue.durable())
                    .append("|exclusive=").append(queue.exclusive())
                    .append("|autoDelete=").append(queue.autoDelete())
                    .append("|arguments=").append(canonicalArguments(queue.arguments()))
                    .append('\n');
        }
        for (BindingSpec binding : bindings) {
            text.append("binding|").append(binding.exchange()).append('|')
                    .append(binding.queue()).append('|').append(binding.routingKey())
                    .append("|arguments=").append(canonicalArguments(binding.arguments()))
                    .append('\n');
        }
        return text.toString();
    }

    private static BindingSpec binding(String exchange, String queue, String routingKey) {
        return new BindingSpec(exchange, queue, routingKey, Map.of());
    }

    private static Map<String, Object> arguments(Object... pairs) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String) pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static String canonicalArguments(Map<String, Object> arguments) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : new TreeMap<>(arguments).entrySet()) {
            if (!text.isEmpty()) text.append(';');
            text.append(entry.getKey()).append('=')
                    .append(entry.getValue() instanceof Number ? "number:" : "string:")
                    .append(entry.getValue());
        }
        return text.toString();
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
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> entry : Objects.requireNonNull(arguments).entrySet()) {
            Object value = Objects.requireNonNull(entry.getValue());
            if (!(value instanceof String) && !(value instanceof Number)) {
                throw new IllegalArgumentException("Unsupported v2 topology argument");
            }
            sorted.put(Objects.requireNonNull(entry.getKey()), value);
        }
        return Collections.unmodifiableMap(sorted);
    }

    public record Scope(TenantId tenantId, DataDomainId dataDomainId, String executionPoolId) {
        public Scope {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(dataDomainId, "dataDomainId");
            if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
                throw new IllegalArgumentException("executionPoolId is not canonical");
            }
        }

        String canonicalText() {
            return "scope|tenant=" + tenantId.value() + "|domain=" + dataDomainId.value()
                    + "|pool=" + executionPoolId + "\n";
        }
    }

    public record ExchangeSpec(String name, String type, boolean durable, boolean autoDelete,
                               boolean internal, Map<String, Object> arguments) {
        public ExchangeSpec { arguments = immutableArguments(arguments); }
    }

    public record QueueSpec(String name, boolean durable, boolean exclusive, boolean autoDelete,
                            Map<String, Object> arguments) {
        public QueueSpec { arguments = immutableArguments(arguments); }
    }

    public record BindingSpec(String exchange, String queue, String routingKey,
                              Map<String, Object> arguments) {
        public BindingSpec { arguments = immutableArguments(arguments); }
    }

    public record PublishRoute(String destination, String routingKey) {
        public PublishRoute {
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(routingKey, "routingKey");
        }
    }
}
