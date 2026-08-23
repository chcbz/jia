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

import java.util.Locale;
import java.util.Objects;
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

        MessageProperties properties = new MessageProperties();
        properties.setContentType(AgentCommandAmqpContract.CONTENT_TYPE);
        properties.setContentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(request.messageId());
        properties.setType(AgentCommandAmqpContract.MESSAGE_TYPE);
        properties.setHeaders(AgentCommandAmqpContract.headers(request));
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
