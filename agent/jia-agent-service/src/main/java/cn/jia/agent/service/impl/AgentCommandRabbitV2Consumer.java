package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentInboxSourceNotSettledException;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.core.tenant.WorkerScope;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.LongConsumer;

/** Protocol-v2 broker-to-durable-Inbox boundary; Rabbit settlement is post-transaction. */
public final class AgentCommandRabbitV2Consumer {
    static final long CLAIM_LEASE_MILLIS = 60_000L;
    static final long FALLBACK_RETRY_MILLIS = 5_000L;
    static final long DISABLED_RETRY_MILLIS = 30_000L;
    static final long PARK_FAILURE_BACKOFF_MILLIS = 5_000L;
    static final int MAX_SOURCE_SETTLEMENT_RETRIES = 64;
    static final String EXECUTION_RETRY_WINDOW_EXHAUSTED = "EXECUTION_RETRY_WINDOW_EXHAUSTED";
    static final String EXECUTION_RETRYABLE = "EXECUTION_RETRYABLE";
    static final String EXECUTION_UNCERTAIN = "EXECUTION_UNCERTAIN";
    static final String EXECUTION_REJECTED = "EXECUTION_REJECTED";

    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandRabbitV2Consumer.class);
    private static final Set<String> PRIOR_RESULTS = Set.of(
            "PROCESSED|SENT", "WAITING_AGENT|WAITING_AGENT", "FAILED|FAILED",
            "EXPIRED|EXPIRED", "DEAD|DEAD");

    private final AgentCommandInboxV2Service inboxService;
    private final AgentCommandV2ExecutionPort executionPort;
    private final WorkerScope workerScope;
    private final AgentConfirmedRabbitPublisher publisher;
    private final AgentRabbitV2TopologyManifest manifest;
    private final AgentCommandRabbitV2MessageDecoder decoder;
    private final LongSupplier nowMillis;
    private final String leaseOwner;
    private final long confirmTimeoutMillis;
    private final LongConsumer parkFailureBackoff;

    public AgentCommandRabbitV2Consumer(AgentCommandInboxV2Service inboxService,
            AgentCommandV2ExecutionPort executionPort,
            AgentConfirmedRabbitPublisher publisher, WorkerScope workerScope,
            AgentRabbitV2TopologyManifest manifest, long confirmTimeoutMillis) {
        this(inboxService, executionPort, publisher, workerScope, manifest,
                confirmTimeoutMillis, Clock.systemUTC()::millis,
                "v2-" + UUID.randomUUID(), AgentCommandRabbitV2Consumer::parkBeforeRequeue);
    }

    AgentCommandRabbitV2Consumer(AgentCommandInboxV2Service inboxService,
            AgentCommandV2ExecutionPort executionPort,
            AgentConfirmedRabbitPublisher publisher, WorkerScope workerScope,
            AgentRabbitV2TopologyManifest manifest, long confirmTimeoutMillis,
            LongSupplier nowMillis, String leaseOwner, LongConsumer parkFailureBackoff) {
        this.inboxService = Objects.requireNonNull(inboxService, "inboxService");
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.workerScope = Objects.requireNonNull(workerScope, "workerScope");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.decoder = new AgentCommandRabbitV2MessageDecoder(manifest);
        if (confirmTimeoutMillis <= 0 || confirmTimeoutMillis > 60_000L) {
            throw new IllegalArgumentException("confirmTimeoutMillis is out of range");
        }
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        this.leaseOwner = exact(leaseOwner, 100, "leaseOwner");
        this.parkFailureBackoff = Objects.requireNonNull(
                parkFailureBackoff, "parkFailureBackoff");
    }

    public void consume(Message message, Channel channel) throws IOException {
        assertNoDatabaseTransaction("consumer entry");
        Objects.requireNonNull(channel, "channel");
        long deliveryTag = message == null || message.getMessageProperties() == null
                ? 0L : message.getMessageProperties().getDeliveryTag();
        if (deliveryTag <= 0) {
            throw new IllegalArgumentException("Rabbit deliveryTag must be positive");
        }

        DecodedAgentCommandMessage decoded = null;
        Settlement settlement;
        try {
            decoded = decoder.decode(message);
            settlement = process(decoded);
        } catch (AgentCommandRabbitDecodeException invalid) {
            LOG.warn("Rejecting invalid protocol-v2 Agent command, reason={}",
                    invalid.reasonCode());
            settlement = Settlement.NACK_DROP;
        } catch (AgentInboxIdentityConflictException conflict) {
            LOG.warn("Rejecting conflicting protocol-v2 Agent command, reason={}",
                    safeReason(conflict.reasonCode()));
            settlement = Settlement.NACK_DROP;
        } catch (AgentInboxSourceNotSettledException sourceRace) {
            LOG.warn("Parking protocol-v2 Agent command before source settlement, reason={}",
                    safeReason(sourceRace.reasonCode()));
            settlement = decoded == null
                    ? Settlement.NACK_REQUEUE
                    : scheduleRetryCopy(decoded, FALLBACK_RETRY_MILLIS);
        } catch (RuntimeException transientFailure) {
            LOG.warn("Parking uncertain protocol-v2 Agent command failure, reason={}",
                    safeFailureCode(transientFailure));
            settlement = decoded == null
                    ? Settlement.NACK_REQUEUE
                    : scheduleRetryCopy(decoded, FALLBACK_RETRY_MILLIS);
        }

        assertNoDatabaseTransaction("Rabbit settlement");
        switch (settlement) {
            case ACK -> channel.basicAck(deliveryTag, false);
            case NACK_DROP -> channel.basicNack(deliveryTag, false, false);
            case NACK_REQUEUE -> {
                parkFailureBackoff.accept(PARK_FAILURE_BACKOFF_MILLIS);
                assertNoDatabaseTransaction("Rabbit requeue after parking backoff");
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    private Settlement process(DecodedAgentCommandMessage message) {
        long claimNow = positiveNow();
        AgentInboxClaim claim = inboxService.claim(message.toV2InboxMessage(
                        AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                        workerScope.deploymentId(), workerScope.workerGeneration()),
                leaseOwner, claimNow, CLAIM_LEASE_MILLIS);
        if (claim == null) return scheduleRetryCopy(message, FALLBACK_RETRY_MILLIS);
        return switch (claim.kind()) {
            case DISABLED -> scheduleRetryCopy(message, DISABLED_RETRY_MILLIS);
            case IN_FLIGHT -> scheduleRetryCopy(message, claim.retryAfterMillis());
            case PRIOR_RESULT -> priorResult(message,claim.priorResult());
            case ACQUIRED -> executeClaimed(message, claim.token());
        };
    }

    private Settlement executeClaimed(
            DecodedAgentCommandMessage message, AgentInboxClaimToken token) {
        if (!tokenMatches(message, token)) return Settlement.NACK_DROP;

        AgentCommandV2ExecutionPort.Result execution;
        try {
            execution = inboxService.executeAdmitted(
                    token, message.ownerJiacn(), message.rawWireBytes(), positiveNow(),
                    executionPort);
        } catch (RuntimeException uncertain) {
            return completeRetry(
                    message, token, FALLBACK_RETRY_MILLIS, EXECUTION_UNCERTAIN);
        }
        if (execution == null) {
            return completeRetry(
                    message, token, FALLBACK_RETRY_MILLIS, EXECUTION_UNCERTAIN);
        }
        return switch (execution.kind()) {
            case DISPATCHED -> executionPort.durableHandoff() ? durableAck() :
                    completeOrPark(message, token, AgentInboxDisposition.sent(),
                    "PROCESSED", "SENT", remainingLeaseDelay(token, positiveNow()));
            case RETRYABLE -> completeRetry(
                    message, token, execution.retryAfterMillis(), EXECUTION_RETRYABLE);
            case REJECTED -> completeOrPark(message, token,
                    new AgentInboxDisposition(AgentInboxDisposition.Type.DEAD,
                            null, EXECUTION_REJECTED),
                    "DEAD", "DEAD", FALLBACK_RETRY_MILLIS);
        };
    }

    private Settlement durableAck(){assertNoDatabaseTransaction("durable handoff ACK");return Settlement.ACK;}
    private Settlement priorResult(DecodedAgentCommandMessage message,AgentInboxResult prior){
        if(!priorResultMatches(message,prior))return Settlement.NACK_DROP;
        if(executionPort.durableHandoff())try{executionPort.verifyPrior(prior.inboxId());}
        catch(SecurityException|IllegalArgumentException corrupt){return Settlement.NACK_DROP;}
        return durableAck();
    }

    private Settlement completeRetry(DecodedAgentCommandMessage message,
            AgentInboxClaimToken token, long retryAfterMillis, String errorCode) {
        long now = positiveNow();
        long retryAt;
        try {
            retryAt = Math.addExact(now, retryAfterMillis);
        } catch (ArithmeticException overflow) {
            retryAt = Long.MAX_VALUE;
        }
        if (retryAt >= token.expiresAt()) {
            return completeOrPark(message, token,
                    new AgentInboxDisposition(AgentInboxDisposition.Type.DEAD,
                            null, EXECUTION_RETRY_WINDOW_EXHAUSTED),
                    "DEAD", "DEAD", FALLBACK_RETRY_MILLIS);
        }
        return completeAtOrPark(message, token, new AgentInboxDisposition(
                AgentInboxDisposition.Type.RETRY, retryAt, errorCode),
                now, "RETRY", "RETRY", retryAfterMillis);
    }

    private Settlement completeOrPark(DecodedAgentCommandMessage message,
            AgentInboxClaimToken token, AgentInboxDisposition disposition,
            String expectedStatus, String expectedResult, long parkDelayMillis) {
        return completeAtOrPark(message, token, disposition, positiveNow(),
                expectedStatus, expectedResult, parkDelayMillis);
    }

    private Settlement completeAtOrPark(DecodedAgentCommandMessage message,
            AgentInboxClaimToken token, AgentInboxDisposition disposition, long now,
            String expectedStatus, String expectedResult, long parkDelayMillis) {
        AgentInboxResult result = inboxService.complete(token, disposition, now);
        if (!resultMatches(token, result, disposition, expectedStatus, expectedResult)) {
            return scheduleRetryCopy(message, parkDelayMillis);
        }
        if (disposition.type() == AgentInboxDisposition.Type.RETRY) {
            return scheduleRetryCopy(message, disposition.nextRetryAt() - now);
        }
        return Settlement.ACK;
    }

    private Settlement scheduleRetryCopy(
            DecodedAgentCommandMessage message, long requestedDelayMillis) {
        long now;
        try {
            now = positiveNow();
        } catch (RuntimeException unavailableClock) {
            return Settlement.NACK_REQUEUE;
        }
        if (now >= message.expiresAt()
                || message.sourceSettlementRetry() >= MAX_SOURCE_SETTLEMENT_RETRIES) {
            return Settlement.NACK_DROP;
        }
        RetryLane lane = retryLaneCeiling(
                requestedDelayMillis, now, message.expiresAt());
        if (lane == null) return Settlement.NACK_REQUEUE;
        return tryPublishRetry(message, lane) == PublishOutcome.CONFIRMED
                ? Settlement.ACK : Settlement.NACK_REQUEUE;
    }

    private PublishOutcome tryPublishRetry(
            DecodedAgentCommandMessage message, RetryLane lane) {
        try {
            assertNoDatabaseTransaction("protocol-v2 Rabbit retry publish");
            AgentConfirmedPublishRequest request = new AgentConfirmedPublishRequest(
                    manifest.deadLetterExchange(), lane.routingKey(),
                    message.rawWireBytes(), message.wireSha256(),
                    message.messageId(), message.eventId(), message.deliveryId(),
                    message.commandId(), message.tenantId(), message.dataDomainId(),
                    message.executionPoolId(), message.admissionEpoch(),
                    message.producerDeploymentId(), message.moduleId(),
                    message.migrationEpoch(), AgentCommandAmqpContract.WIRE_VERSION,
                    message.clientId(), message.taskId(), message.targetAgentId(),
                    message.commandType(), message.activeAttempt(), message.expiresAt(),
                    message.topologySha256(), message.sourceSettlementRetry() + 1);
            AgentRabbitPublishResult result = publisher.publish(request, confirmTimeoutMillis);
            if (result == null) return PublishOutcome.UNCERTAIN;
            return switch (result.type()) {
                case ACK -> PublishOutcome.CONFIRMED;
                case RETURNED, NACK -> PublishOutcome.DEFINITIVE_REJECT;
                case TIMEOUT, EXCEPTION -> PublishOutcome.UNCERTAIN;
            };
        } catch (RuntimeException publishFailure) {
            LOG.warn("Confirmed protocol-v2 Agent command retry publish uncertain, reason={}",
                    safeFailureCode(publishFailure));
            return PublishOutcome.UNCERTAIN;
        }
    }

    private RetryLane retryLaneCeiling(
            long requestedDelayMillis, long now, long expiresAt) {
        RetryLane lane;
        if (requestedDelayMillis <= 0L) {
            return null;
        } else if (requestedDelayMillis <= 5_000L) {
            lane = new RetryLane(5_000L, manifest.retry5sRoutingKey());
        } else if (requestedDelayMillis <= 30_000L) {
            lane = new RetryLane(30_000L, manifest.retry30sRoutingKey());
        } else if (requestedDelayMillis <= 300_000L) {
            lane = new RetryLane(300_000L, manifest.retry5mRoutingKey());
        } else {
            return null;
        }
        return laneFits(now, expiresAt, lane.delayMillis()) ? lane : null;
    }

    private boolean laneFits(long now, long expiresAt, long delayMillis) {
        try {
            return Math.addExact(now, delayMillis) < expiresAt;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private long remainingLeaseDelay(AgentInboxClaimToken token, long now) {
        return Math.max(1L, token.leaseUntil() - now);
    }

    private boolean priorResultMatches(
            DecodedAgentCommandMessage message, AgentInboxResult result) {
        return result != null && result.inboxId() > 0
                && AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2.equals(result.consumerName())
                && message.tenantId().equals(result.tenantId())
                && message.clientId().equals(result.clientId())
                && message.messageId().equals(result.messageId())
                && message.eventId().equals(result.eventId())
                && message.commandId().equals(result.commandId())
                && message.deliveryId() == result.deliveryId()
                && result.processedAt() != null && result.processedAt() > 0
                && PRIOR_RESULTS.contains(result.status() + "|" + result.resultStatus());
    }

    private boolean resultMatches(AgentInboxClaimToken token, AgentInboxResult result,
            AgentInboxDisposition disposition, String expectedStatus, String expectedResult) {
        return result != null && token.inboxId() == result.inboxId()
                && token.deliveryId() == result.deliveryId()
                && token.consumerName().equals(result.consumerName())
                && token.tenantId().equals(result.tenantId())
                && token.clientId().equals(result.clientId())
                && token.messageId().equals(result.messageId())
                && token.eventId().equals(result.eventId())
                && token.commandId().equals(result.commandId())
                && expectedStatus.equals(result.status())
                && expectedResult.equals(result.resultStatus())
                && Objects.equals(disposition.errorCode(), result.lastError())
                && Objects.equals(disposition.nextRetryAt(), result.nextRetryAt())
                && result.processedAt() != null && result.processedAt() > 0;
    }

    private boolean tokenMatches(
            DecodedAgentCommandMessage message, AgentInboxClaimToken token) {
        return token != null && token.protocolVersion() == 2 && token.inboxId() > 0
                && AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2.equals(token.consumerName())
                && message.tenantId().equals(token.tenantId())
                && message.dataDomainId().equals(token.dataDomainId())
                && message.executionPoolId().equals(token.executionPoolId())
                && message.admissionEpoch() == token.admissionEpoch()
                && message.producerDeploymentId().equals(token.producerDeploymentId())
                && message.moduleId().equals(token.moduleId())
                && message.migrationEpoch() == token.migrationEpoch()
                && workerScope.deploymentId().equals(token.claimantDeploymentId())
                && workerScope.workerGeneration() == token.claimantWorkerGeneration()
                && message.clientId().equals(token.clientId())
                && message.messageId().equals(token.messageId())
                && message.eventId().equals(token.eventId())
                && message.commandId().equals(token.commandId())
                && message.taskId().equals(token.taskId())
                && message.targetAgentId().equals(token.targetAgentId())
                && message.commandType().equals(token.commandType())
                && message.deliveryId() == token.deliveryId()
                && message.activeAttempt() == token.deliveryActiveAttempt()
                && message.expiresAt() == token.expiresAt()
                && leaseOwner.equals(token.leaseOwner())
                && token.leaseUntil() > 0 && token.leaseUntil() <= token.expiresAt();
    }

    private long positiveNow() {
        long now = nowMillis.getAsLong();
        if (now <= 0) throw new IllegalStateException("Clock is unavailable");
        return now;
    }

    private static String exact(String value, int maximum, String name) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
        return value;
    }

    private static void assertNoDatabaseTransaction(String operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalStateException(operation + " cannot run in a database transaction");
        }
    }

    private static void parkBeforeRequeue(long delayMillis) {
        if (delayMillis <= 0L) return;
        LockSupport.parkNanos(delayMillis * 1_000_000L);
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

    private enum Settlement { ACK, NACK_DROP, NACK_REQUEUE }

    private enum PublishOutcome { CONFIRMED, DEFINITIVE_REJECT, UNCERTAIN }

    private record RetryLane(long delayMillis, String routingKey) {
    }
}
