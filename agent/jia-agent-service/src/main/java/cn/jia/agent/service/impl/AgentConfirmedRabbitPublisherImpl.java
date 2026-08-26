package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.rabbitmq.client.LongString;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Bounded reusable persistent+mandatory publisher restricted to exact D04 manifest routes. */
public final class AgentConfirmedRabbitPublisherImpl implements AgentConfirmedRabbitPublisher {
    private static final long MAX_CONFIRM_TIMEOUT_MILLIS = 60_000L;

    private final RabbitTemplate template;
    private final AgentRabbitSafetyGate gate;
    private final AgentRabbitTopologyManifest manifest;
    private final AgentRabbitTopologyReadiness readiness;
    private final Supplier<UUID> correlationIds;

    public AgentConfirmedRabbitPublisherImpl(
            RabbitTemplate template,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentRabbitTopologyReadiness readiness) {
        this(template, gate, manifest, readiness, UUID::randomUUID);
    }

    AgentConfirmedRabbitPublisherImpl(
            RabbitTemplate template,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentRabbitTopologyReadiness readiness,
            Supplier<UUID> correlationIds) {
        this.template = Objects.requireNonNull(template, "template");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
    }

    @Override
    public AgentRabbitPublishResult publish(
            AgentConfirmedPublishRequest request, long confirmTimeoutMillis) {
        return publishInternal(request, null, confirmTimeoutMillis);
    }

    @Override
    public AgentRabbitPublishResult publishPreservingHeaders(
            AgentConfirmedPublishRequest request,
            Map<String, Object> preservedHeaders,
            long confirmTimeoutMillis) {
        return publishInternal(request, preservedHeaders, confirmTimeoutMillis);
    }

    private AgentRabbitPublishResult publishInternal(
            AgentConfirmedPublishRequest request,
            Map<String, Object> preservedHeaders,
            long confirmTimeoutMillis) {
        if (confirmTimeoutMillis <= 0
                || confirmTimeoutMillis > MAX_CONFIRM_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "confirmTimeoutMillis must be within (0,60000]");
        }
        try {
            AgentCommandAmqpContract.validate(request);
        } catch (IllegalArgumentException invalid) {
            return exception("PUBLISH_PROVENANCE_INVALID");
        }
        AgentRabbitActivationState state = gate.state();
        if ((state != AgentRabbitActivationState.DISPATCH_CANARY
                && state != AgentRabbitActivationState.DISPATCH_SCOPED)
                || !gate.allowsDispatch(request.tenantId(), request.clientId())) {
            return exception("DISPATCH_SCOPE_REJECTED");
        }
        AgentRabbitTopologyReadiness.Snapshot topology = readiness.snapshot();
        if (!topology.canonicalTopologyReady()
                || !manifest.sha256().equals(topology.manifestSha256())
                || !manifest.sha256().equals(request.topologySha256())) {
            return exception("TOPOLOGY_NOT_CANONICAL_READY");
        }
        if (!manifest.allowsPublish(request.destination(), request.routingKey())) {
            return exception("DESTINATION_POLICY_REJECTED");
        }
        UUID correlationId = correlationIds.get();
        if (correlationId == null) {
            return exception("RABBIT_CORRELATION_ID_UNAVAILABLE");
        }

        Map<String, Object> headers;
        try {
            headers = preservedHeaders == null
                    ? AgentCommandAmqpContract.headers(request)
                    : validatePreservedHeaders(request, preservedHeaders);
        } catch (IllegalArgumentException invalid) {
            return exception("PRESERVED_HEADERS_INVALID");
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(request.messageId());
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setHeaders(headers);
        Message message = new Message(request.wirePayload(), properties);
        CorrelationData correlation = new CorrelationData(correlationId.toString());

        try {
            template.send(request.destination(), request.routingKey(), message, correlation);
        } catch (RuntimeException ignored) {
            ReturnedMessage returned = correlation.getReturned();
            return returned != null
                    ? returned(returned, "NONE")
                    : exception("RABBIT_PUBLISH_EXCEPTION");
        }

        try {
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlation.getReturned();
            if (returned != null) {
                return returned(returned, confirm != null && confirm.ack() ? "ACK" : "NACK");
            }
            if (confirm == null) {
                return exception("RABBIT_CONFIRM_MISSING");
            }
            if (!confirm.ack()) {
                return new AgentRabbitPublishResult(
                        AgentRabbitPublishResult.Type.NACK,
                        "NACK", "NOT_RETURNED", null, null, "RABBIT_NACK");
            }
            return AgentRabbitPublishResult.ack();
        } catch (TimeoutException ignored) {
            ReturnedMessage returned = correlation.getReturned();
            return returned != null
                    ? returned(returned, "TIMEOUT")
                    : new AgentRabbitPublishResult(
                            AgentRabbitPublishResult.Type.TIMEOUT,
                            "TIMEOUT", "NOT_RETURNED", null, null,
                            "RABBIT_CONFIRM_TIMEOUT");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            ReturnedMessage returned = correlation.getReturned();
            return returned != null
                    ? returned(returned, "NONE")
                    : exception("RABBIT_CONFIRM_INTERRUPTED");
        } catch (ExecutionException | RuntimeException ignored) {
            ReturnedMessage returned = correlation.getReturned();
            return returned != null
                    ? returned(returned, "NONE")
                    : exception("RABBIT_CONFIRM_EXCEPTION");
        }
    }

    private static final Set<String> DEAD_LETTER_HEADERS = Set.of(
            "x-death", "x-first-death-exchange", "x-first-death-queue",
            "x-first-death-reason", "x-last-death-exchange", "x-last-death-queue",
            "x-last-death-reason");
    private static final Set<String> SPRING_TRANSPORT_HEADERS = Set.of(
            "spring_listener_return_correlation",
            "spring_returned_message_correlation");
    private static final Set<String> DEATH_ENTRY_KEYS = Set.of(
            "count", "reason", "queue", "time", "exchange", "routing-keys",
            "original-expiration");
    private static final int MAX_DEATH_ENTRIES = 16;
    private static final int MAX_DEATH_ROUTING_KEYS = 8;

    static Map<String, Object> validatePreservedHeaders(
            AgentConfirmedPublishRequest request, Map<String, Object> candidate) {
        Objects.requireNonNull(candidate, "preservedHeaders");
        Map<String, Object> canonical = AgentCommandAmqpContract.headers(request);
        if (candidate.size() < canonical.size()
                || candidate.size() > canonical.size() + DEAD_LETTER_HEADERS.size()
                + SPRING_TRANSPORT_HEADERS.size()) {
            throw new IllegalArgumentException("DLQ header count is invalid");
        }
        validateSpringTransportHeaders(candidate);
        LinkedHashMap<String, Object> preserved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : candidate.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (name == null || value == null
                    || (!canonical.containsKey(name) && !DEAD_LETTER_HEADERS.contains(name)
                    && !SPRING_TRANSPORT_HEADERS.contains(name))) {
                throw new IllegalArgumentException("Unexpected DLQ header");
            }
            // RabbitTemplate regenerates its mandatory/confirm correlations for this send;
            // stale source correlations are validated above but are not broker provenance.
            if (!SPRING_TRANSPORT_HEADERS.contains(name)) preserved.put(name, value);
        }
        for (Map.Entry<String, Object> expected : canonical.entrySet()) {
            if (!headerEquals(expected.getValue(), candidate.get(expected.getKey()))) {
                throw new IllegalArgumentException("Canonical DLQ header drift");
            }
        }
        validateDeathHeaders(candidate);
        return Collections.unmodifiableMap(preserved);
    }

    private static void validateSpringTransportHeaders(Map<String, Object> candidate) {
        long present = SPRING_TRANSPORT_HEADERS.stream().filter(candidate::containsKey).count();
        if (present != SPRING_TRANSPORT_HEADERS.size()) {
            throw new IllegalArgumentException(
                    "Complete Spring transport correlation pair is required");
        }
        Set<String> values = new java.util.HashSet<>();
        for (String name : SPRING_TRANSPORT_HEADERS) {
            String value = headerText(candidate.get(name));
            try {
                if (value == null || !UUID.fromString(value).toString().equals(value)) {
                    throw new IllegalArgumentException("Spring transport correlation is invalid");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException(
                        "Spring transport correlation is invalid", invalid);
            }
            values.add(value);
        }
        if (values.size() != SPRING_TRANSPORT_HEADERS.size()) {
            throw new IllegalArgumentException("Spring transport correlations are not independent");
        }
    }

    private static void validateDeathHeaders(Map<String, Object> candidate) {
        Object deaths = candidate.get("x-death");
        boolean anyDeathHeader = DEAD_LETTER_HEADERS.stream().anyMatch(candidate::containsKey);
        if (deaths == null) {
            if (anyDeathHeader) {
                throw new IllegalArgumentException("Partial DLQ death provenance is invalid");
            }
            return; // D05 confirmed terminal parking publishes directly to the DLX.
        }
        if (!(deaths instanceof List<?> history)
                || history.isEmpty() || history.size() > MAX_DEATH_ENTRIES) {
            throw new IllegalArgumentException("DLQ death history is invalid");
        }
        List<DeathEntry> entries = history.stream()
                .map(AgentConfirmedRabbitPublisherImpl::deathEntry)
                .toList();
        for (int index = 1; index < entries.size(); index++) {
            if (entries.get(index - 1).time() < entries.get(index).time()) {
                throw new IllegalArgumentException("DLQ death history order is invalid");
            }
        }
        validateDeathSummary(candidate, "x-first-death", entries, true);
        validateDeathSummary(candidate, "x-last-death", entries, false);
    }

    private static DeathEntry deathEntry(Object value) {
        if (!(value instanceof Map<?, ?> raw)
                || raw.size() < 6 || raw.size() > DEATH_ENTRY_KEYS.size()) {
            throw new IllegalArgumentException("DLQ death entry shape is invalid");
        }
        LinkedHashMap<String, Object> entry = new LinkedHashMap<>();
        for (Map.Entry<?, ?> field : raw.entrySet()) {
            if (!(field.getKey() instanceof String name) || field.getValue() == null
                    || !DEATH_ENTRY_KEYS.contains(name) || entry.put(name, field.getValue()) != null) {
                throw new IllegalArgumentException("DLQ death entry field is invalid");
            }
        }
        if (!entry.keySet().containsAll(Set.of(
                "count", "reason", "queue", "time", "exchange", "routing-keys"))) {
            throw new IllegalArgumentException("DLQ death entry is incomplete");
        }
        long count = positiveIntegral(entry.get("count"));
        String reason = exactHeaderText(entry.get("reason"), 32);
        String queue = exactHeaderText(entry.get("queue"), 100);
        String exchange = exactHeaderText(entry.get("exchange"), 100);
        if (!(entry.get("time") instanceof Date time) || time.getTime() <= 0) {
            throw new IllegalArgumentException("DLQ death timestamp is invalid");
        }
        if (!(entry.get("routing-keys") instanceof List<?> routing)
                || routing.isEmpty() || routing.size() > MAX_DEATH_ROUTING_KEYS) {
            throw new IllegalArgumentException("DLQ death routing keys are invalid");
        }
        List<String> routingKeys = routing.stream()
                .map(valuePart -> exactHeaderText(valuePart, 100)).toList();
        if (routingKeys.stream().distinct().count() != routingKeys.size()) {
            throw new IllegalArgumentException("DLQ death routing keys are duplicated");
        }
        Object originalExpiration = entry.get("original-expiration");
        if (originalExpiration != null
                && !exactHeaderText(originalExpiration, 20).matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("DLQ original expiration is invalid");
        }
        validateDeathRoute(reason, queue, exchange, routingKeys);
        return new DeathEntry(count, reason, queue, exchange, time.getTime());
    }

    private static void validateDeathRoute(
            String reason, String queue, String exchange, List<String> routingKeys) {
        if (AgentRabbitTopologyManifest.DISPATCH_QUEUE.equals(queue)) {
            if (!"rejected".equals(reason)
                    || !AgentRabbitTopologyManifest.MAIN_EXCHANGE.equals(exchange)
                    || routingKeys.size() != 1
                    || !AgentRabbitTopologyManifest.canonical().allowsCommandPublish(
                            exchange, routingKeys.getFirst())) {
                throw new IllegalArgumentException("DLQ dispatch death route is invalid");
            }
            return;
        }
        Map<String, String> retryRoutes = Map.of(
                AgentRabbitTopologyManifest.RETRY_5S_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY,
                AgentRabbitTopologyManifest.RETRY_30S_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_30S_ROUTING_KEY,
                AgentRabbitTopologyManifest.RETRY_5M_QUEUE,
                        AgentRabbitTopologyManifest.RETRY_5M_ROUTING_KEY);
        String expectedRouting = retryRoutes.get(queue);
        if (!"expired".equals(reason)
                || !AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE.equals(exchange)
                || expectedRouting == null || routingKeys.size() != 1
                || !expectedRouting.equals(routingKeys.getFirst())) {
            throw new IllegalArgumentException("DLQ retry death route is invalid");
        }
    }

    private static void validateDeathSummary(
            Map<String, Object> candidate,
            String prefix,
            List<DeathEntry> entries,
            boolean required) {
        String queue = optionalHeaderText(candidate, prefix + "-queue", 100);
        String exchange = optionalHeaderText(candidate, prefix + "-exchange", 100);
        String reason = optionalHeaderText(candidate, prefix + "-reason", 32);
        int present = (queue == null ? 0 : 1) + (exchange == null ? 0 : 1)
                + (reason == null ? 0 : 1);
        if ((required && present != 3) || (!required && present != 0 && present != 3)) {
            throw new IllegalArgumentException("DLQ death summary is incomplete");
        }
        DeathEntry expected = required ? entries.getLast() : entries.getFirst();
        if (present == 3 && (!expected.queue().equals(queue)
                || !expected.exchange().equals(exchange) || !expected.reason().equals(reason))) {
            throw new IllegalArgumentException("DLQ death summary does not match history");
        }
    }

    private static String optionalHeaderText(
            Map<String, Object> candidate, String name, int maxLength) {
        return candidate.containsKey(name) ? exactHeaderText(candidate.get(name), maxLength) : null;
    }

    private static long positiveIntegral(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("DLQ death count is invalid");
        }
        Long integral = integralValue(number);
        if (integral == null || integral <= 0) {
            throw new IllegalArgumentException("DLQ death count is invalid");
        }
        return integral;
    }

    private static String exactHeaderText(Object value, int maxLength) {
        String text = headerText(value);
        if (text == null || text.isEmpty() || text.length() > maxLength
                || !text.equals(text.strip())
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("DLQ death text is invalid");
        }
        return text;
    }

    private static boolean headerEquals(Object expected, Object actual) {
        if (expected instanceof Number number) {
            return actual instanceof Number other
                    && integralValue(number) != null
                    && Objects.equals(integralValue(number), integralValue(other));
        }
        return Objects.equals(expected.toString(), headerText(actual));
    }

    private static Long integralValue(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return number.longValue();
        }
        if (number instanceof BigInteger integer) {
            return integer.bitLength() <= 63 ? integer.longValue() : null;
        }
        if (number instanceof BigDecimal decimal) {
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException invalid) {
                return null;
            }
        }
        return null;
    }

    private static String headerText(Object value) {
        if (value instanceof String text) return text;
        if (value instanceof LongString text) {
            return new String(text.getBytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private record DeathEntry(
            long count, String reason, String queue, String exchange, long time) {
    }

    private static AgentRabbitPublishResult returned(
            ReturnedMessage returned, String confirmStatus) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.RETURNED,
                confirmStatus, "RETURNED", returned.getReplyCode(),
                sanitizeReplyText(returned.getReplyText()), "RABBIT_RETURNED");
    }

    private static AgentRabbitPublishResult exception(String code) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null, code);
    }

    static String sanitizeReplyText(String text) {
        if (text == null) return "";
        StringBuilder clean = new StringBuilder(Math.min(text.length(), 1000));
        for (int offset = 0; offset < text.length() && clean.length() < 1000;) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int sanitized = Character.isISOControl(codePoint) ? ' ' : codePoint;
            int width = Character.charCount(sanitized);
            if (clean.length() + width > 1000) break;
            clean.appendCodePoint(sanitized);
        }
        String value = clean.toString();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("amqp://") || lower.contains("amqps://")
                || lower.contains("authorization") || lower.contains("bearer ")
                || lower.contains("password") || lower.contains("credential")
                || lower.contains("secret") || lower.contains("token=")
                || lower.contains("username=")) {
            return "REDACTED_REPLY_TEXT";
        }
        return value;
    }
}
