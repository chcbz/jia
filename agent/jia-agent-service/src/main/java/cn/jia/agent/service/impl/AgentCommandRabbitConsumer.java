package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentCommandInboxService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** D05 manual-ACK consumer. Rabbit and WebSocket network calls are never made in a DB transaction. */
@Component
@ConditionalOnProperty(prefix = "agent.rabbit-dispatch", name = "enabled", havingValue = "true")
public final class AgentCommandRabbitConsumer {
    static final long CLAIM_LEASE_MILLIS = 60_000L;
    static final long CONFIRM_TIMEOUT_MILLIS = 10_000L;
    static final long OFFLINE_RETRY_MILLIS = 30_000L;
    static final int MAX_SOURCE_SETTLEMENT_RETRIES = 8;

    static final String ACL_DENIED = "TASK_MEMBER_ACCESS_DENIED";
    static final String ACL_RESOLUTION_FAILED = "TASK_MEMBER_ACCESS_RESOLUTION_FAILED";
    static final String AGENT_OFFLINE = "AGENT_OFFLINE";
    static final String WS_SEND_FAILED = "WEBSOCKET_SEND_FAILED";
    static final String WS_DISPATCH_REJECTED = "WEBSOCKET_DISPATCH_REJECTED";
    static final String RETRY_WINDOW_CLOSED = "RETRY_WINDOW_CLOSED";

    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandRabbitConsumer.class);

    private final AgentCommandInboxService inboxService;
    private final AgentTaskCollaborationAccessService accessService;
    private final AgentRawCommandDispatcher dispatcher;
    private final AgentConfirmedRabbitPublisher publisher;
    private final AgentRabbitSafetyGate gate;
    private final AgentCommandRabbitMessageDecoder decoder;
    private final LongSupplier nowMillis;
    private final String leaseOwner;

    @Autowired
    public AgentCommandRabbitConsumer(
            AgentCommandInboxService inboxService,
            AgentTaskCollaborationAccessService accessService,
            AgentRawCommandDispatcher dispatcher,
            @Qualifier("agentConfirmedRabbitPublisher") AgentConfirmedRabbitPublisher publisher,
            AgentRabbitSafetyGate gate,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest) {
        this(inboxService, accessService, dispatcher, publisher, gate, manifest,
                Clock.systemUTC()::millis, "d05-" + UUID.randomUUID());
    }

    AgentCommandRabbitConsumer(
            AgentCommandInboxService inboxService,
            AgentTaskCollaborationAccessService accessService,
            AgentRawCommandDispatcher dispatcher,
            AgentConfirmedRabbitPublisher publisher,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            LongSupplier nowMillis,
            String leaseOwner) {
        this.inboxService = Objects.requireNonNull(inboxService, "inboxService");
        this.accessService = Objects.requireNonNull(accessService, "accessService");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.decoder = new AgentCommandRabbitMessageDecoder(manifest);
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        this.leaseOwner = requireExact(leaseOwner, "leaseOwner", 100);
    }

    @RabbitListener(
            id = AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
            queues = AgentRabbitTopologyManifest.DISPATCH_QUEUE,
            containerFactory = "agentCommandListenerContainerFactory")
    public void consume(Message message, Channel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        long deliveryTag = message == null || message.getMessageProperties() == null
                ? 0L : message.getMessageProperties().getDeliveryTag();
        if (deliveryTag <= 0) {
            throw new IllegalArgumentException("Rabbit deliveryTag must be positive");
        }

        Settlement settlement;
        try {
            settlement = process(message);
        } catch (AgentCommandRabbitDecodeException invalid) {
            LOG.warn("Dropping invalid Agent command Rabbit message, reason={}",
                    invalid.reasonCode());
            settlement = Settlement.NACK_DROP;
        } catch (AgentInboxIdentityConflictException conflict) {
            LOG.warn("Dropping conflicting Agent command Inbox identity, reason={}",
                    safeReason(conflict.reasonCode()));
            settlement = Settlement.NACK_DROP;
        } catch (RuntimeException failure) {
            LOG.warn("Fail-closed Agent command Rabbit consumption, reason={}",
                    safeFailureCode(failure));
            settlement = Settlement.NACK_DROP;
        }

        if (settlement == Settlement.ACK) {
            channel.basicAck(deliveryTag, false);
        } else {
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private Settlement process(Message rabbitMessage) {
        DecodedAgentCommandMessage message = decoder.decode(rabbitMessage);
        if (!gate.allowsDispatch(message.tenantId(), message.clientId())) {
            return Settlement.NACK_DROP;
        }

        long claimNow = positiveNow();
        AgentInboxClaim claim = inboxService.claim(new AgentInboxMessage(
                        AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                        message.tenantId(), message.clientId(), message.messageId(),
                        message.eventId(), message.commandId(), message.deliveryId(),
                        message.rawWireBytes()),
                leaseOwner, claimNow, CLAIM_LEASE_MILLIS);
        if (claim == null) return Settlement.NACK_DROP;
        return switch (claim.kind()) {
            case DISABLED -> Settlement.NACK_DROP;
            case PRIOR_RESULT -> priorResultMatches(message, claim.priorResult())
                    ? Settlement.ACK : Settlement.NACK_DROP;
            case IN_FLIGHT -> parkInFlight(message, claim.retryAfterMillis());
            case ACQUIRED -> dispatchAcquired(message, claim.token());
        };
    }

    private Settlement dispatchAcquired(
            DecodedAgentCommandMessage message, AgentInboxClaimToken token) {
        if (!tokenMatches(message, token)) return Settlement.NACK_DROP;
        long now = positiveNow();
        if (now >= token.expiresAt()) {
            return complete(token,
                    new AgentInboxDisposition(
                            AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED"),
                    now, "EXPIRED") ? Settlement.ACK : Settlement.NACK_DROP;
        }

        AgentTaskAccessLevel access;
        try {
            access = accessService.resolveMemberAccess(
                    message.tenantId(), message.clientId(), message.taskId(),
                    message.targetAgentId());
        } catch (RuntimeException unavailable) {
            return completeDead(token, ACL_RESOLUTION_FAILED);
        }
        if (access == null || !access.canWrite()) {
            return completeDead(token, ACL_DENIED);
        }

        assertNoDatabaseTransaction("WebSocket dispatch");
        AgentRawCommandDispatchResult dispatch;
        try {
            dispatch = dispatcher.dispatchExactRawCommand(
                    message.tenantId(), message.clientId(), message.taskId(),
                    message.targetAgentId(), message.rawWireBytes());
        } catch (RuntimeException sendFailure) {
            dispatch = AgentRawCommandDispatchResult.sendFailed(1);
        }
        if (dispatch == null) return completeDead(token, WS_DISPATCH_REJECTED);
        return switch (dispatch.status()) {
            case SENT -> complete(token, AgentInboxDisposition.sent(), positiveNow(), "SENT")
                    ? Settlement.ACK : Settlement.NACK_DROP;
            case OFFLINE -> completeWaitingAgent(token);
            case SEND_FAILED -> completeRetryAndPark(message, token);
            case REJECTED -> completeDead(token, WS_DISPATCH_REJECTED);
        };
    }

    private Settlement completeWaitingAgent(AgentInboxClaimToken token) {
        long now = positiveNow();
        Long retryAt = boundedRetryAt(now, token.expiresAt(), OFFLINE_RETRY_MILLIS);
        if (retryAt == null) return completeDead(token, RETRY_WINDOW_CLOSED);
        AgentInboxDisposition waiting = new AgentInboxDisposition(
                AgentInboxDisposition.Type.WAITING_AGENT, retryAt, AGENT_OFFLINE);
        return complete(token, waiting, now, "WAITING_AGENT")
                ? Settlement.ACK : Settlement.NACK_DROP;
    }

    private Settlement completeRetryAndPark(
            DecodedAgentCommandMessage message, AgentInboxClaimToken token) {
        if (message.sourceSettlementRetry() >= MAX_SOURCE_SETTLEMENT_RETRIES) {
            return completeDead(token, RETRY_WINDOW_CLOSED);
        }
        long now = positiveNow();
        RetryLane lane = retryLane(5_000L, now, token.expiresAt());
        if (lane == null) return completeDead(token, RETRY_WINDOW_CLOSED);
        AgentInboxDisposition retry = new AgentInboxDisposition(
                AgentInboxDisposition.Type.RETRY, now + lane.delayMillis(), WS_SEND_FAILED);
        if (!complete(token, retry, now, "RETRY")) return Settlement.NACK_DROP;
        return publishRetry(message, lane) ? Settlement.ACK : Settlement.NACK_DROP;
    }

    private Settlement completeDead(AgentInboxClaimToken token, String errorCode) {
        long now = positiveNow();
        AgentInboxDisposition dead = new AgentInboxDisposition(
                AgentInboxDisposition.Type.DEAD, null, errorCode);
        return complete(token, dead, now, "DEAD")
                ? Settlement.ACK : Settlement.NACK_DROP;
    }

    private Settlement parkInFlight(
            DecodedAgentCommandMessage message, Long retryAfterMillis) {
        if (retryAfterMillis == null || retryAfterMillis <= 0
                || message.sourceSettlementRetry() >= MAX_SOURCE_SETTLEMENT_RETRIES) {
            return Settlement.NACK_DROP;
        }
        long now = positiveNow();
        RetryLane lane = retryLane(retryAfterMillis, now, message.expiresAt());
        if (lane == null) return Settlement.NACK_DROP;
        return publishRetry(message, lane) ? Settlement.ACK : Settlement.NACK_DROP;
    }

    private boolean publishRetry(DecodedAgentCommandMessage message, RetryLane lane) {
        assertNoDatabaseTransaction("Rabbit retry publish");
        AgentConfirmedPublishRequest request = new AgentConfirmedPublishRequest(
                AgentRabbitTopologyManifest.DEAD_LETTER_EXCHANGE,
                lane.routingKey(), message.rawWireBytes(), message.wireSha256(),
                message.messageId(), message.eventId(), message.deliveryId(),
                message.commandId(), message.tenantId(), message.clientId(),
                message.taskId(), message.targetAgentId(), message.commandType(),
                message.activeAttempt(), message.expiresAt(), message.topologySha256(),
                message.sourceSettlementRetry() + 1);
        AgentRabbitPublishResult result = publisher.publish(request, CONFIRM_TIMEOUT_MILLIS);
        return result != null && result.type() == AgentRabbitPublishResult.Type.ACK;
    }

    private boolean complete(
            AgentInboxClaimToken token,
            AgentInboxDisposition disposition,
            long now,
            String expectedResultStatus) {
        AgentInboxResult result = inboxService.complete(token, disposition, now);
        return result != null
                && token.inboxId() == result.inboxId()
                && token.deliveryId() == result.deliveryId()
                && token.consumerName().equals(result.consumerName())
                && token.tenantId().equals(result.tenantId())
                && token.clientId().equals(result.clientId())
                && token.messageId().equals(result.messageId())
                && token.eventId().equals(result.eventId())
                && token.commandId().equals(result.commandId())
                && expectedResultStatus.equals(result.resultStatus())
                && expectedInboxStatus(disposition.type()).equals(result.status())
                && Objects.equals(disposition.errorCode(), result.lastError())
                && result.processedAt() != null
                && result.processedAt() > 0;
    }


    private String expectedInboxStatus(AgentInboxDisposition.Type type) {
        return type == AgentInboxDisposition.Type.SENT ? "PROCESSED" : type.name();
    }

    private boolean priorResultMatches(
            DecodedAgentCommandMessage message, AgentInboxResult result) {
        if (result == null
                || result.inboxId() <= 0
                || result.deliveryId() != message.deliveryId()
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(result.consumerName())
                || !message.tenantId().equals(result.tenantId())
                || !message.clientId().equals(result.clientId())
                || !message.messageId().equals(result.messageId())
                || !message.eventId().equals(result.eventId())
                || !message.commandId().equals(result.commandId())
                || result.processedAt() == null || result.processedAt() <= 0) {
            return false;
        }
        return switch (result.resultStatus()) {
            case "SENT", "WAITING_AGENT", "FAILED", "EXPIRED", "DEAD" -> true;
            default -> false;
        };
    }

    private boolean tokenMatches(
            DecodedAgentCommandMessage message, AgentInboxClaimToken token) {
        return token != null
                && AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(token.consumerName())
                && message.tenantId().equals(token.tenantId())
                && message.clientId().equals(token.clientId())
                && message.messageId().equals(token.messageId())
                && message.eventId().equals(token.eventId())
                && message.commandId().equals(token.commandId())
                && message.deliveryId() == token.deliveryId()
                && message.activeAttempt() == token.deliveryActiveAttempt()
                && message.expiresAt() == token.expiresAt();
    }

    private RetryLane retryLane(long requestedDelay, long now, long expiresAt) {
        RetryLane lane = requestedDelay <= 5_000L
                ? new RetryLane(5_000L, AgentRabbitTopologyManifest.RETRY_5S_ROUTING_KEY)
                : requestedDelay <= 30_000L
                        ? new RetryLane(30_000L, AgentRabbitTopologyManifest.RETRY_30S_ROUTING_KEY)
                        : new RetryLane(300_000L, AgentRabbitTopologyManifest.RETRY_5M_ROUTING_KEY);
        Long retryAt = boundedRetryAt(now, expiresAt, lane.delayMillis());
        return retryAt != null && retryAt == now + lane.delayMillis() ? lane : null;
    }

    private Long boundedRetryAt(long now, long expiresAt, long preferredDelay) {
        if (expiresAt <= now + 1) return null;
        long preferred;
        try {
            preferred = Math.addExact(now, preferredDelay);
        } catch (ArithmeticException overflow) {
            return null;
        }
        return Math.min(preferred, expiresAt - 1);
    }

    private long positiveNow() {
        long now = nowMillis.getAsLong();
        if (now <= 0) throw new IllegalStateException("Clock returned a non-positive epoch millis");
        return now;
    }

    private void assertNoDatabaseTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(operation + " attempted inside a database transaction");
        }
    }

    private static String requireExact(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String safeReason(String reason) {
        return reason != null && reason.matches("[A-Z0-9_]{1,128}")
                ? reason : "IDENTITY_CONFLICT";
    }

    private static String safeFailureCode(RuntimeException failure) {
        String simple = failure.getClass().getSimpleName();
        return simple != null && simple.matches("[A-Za-z0-9_$]{1,100}")
                ? simple : "RUNTIME_FAILURE";
    }

    private enum Settlement { ACK, NACK_DROP }

    private record RetryLane(long delayMillis, String routingKey) {
    }
}
