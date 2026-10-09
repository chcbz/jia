package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentCommandRedriveRabbitSettings;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.GetResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded raw one-shot DLQ redrive with fail-closed authority and lifecycle fences. */
public final class AgentCommandDlqRedriverImpl
        implements AgentCommandDlqRedriver, AutoCloseable {
    private enum Lifecycle { RUNNING, CLOSING, CLOSED, POISONED }

    private final AgentCommandRedriveRawProvider rawProvider;
    private final AgentCommandOperationsDao dao;
    private final WorkerScope workerScope;
    private final AgentRabbitV2TopologyManifest topology;
    private final TenantModuleAdmissionGuard admissionGuard;
    private final AgentCommandRedriveRabbitSettings settings;
    private final TransactionTemplate publishTransaction;
    private final ThreadPoolExecutor publishExecutor;
    private final AtomicReference<Lifecycle> lifecycle =
            new AtomicReference<>(Lifecycle.RUNNING);
    private final AtomicBoolean activeRedrive = new AtomicBoolean();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private final AtomicReference<AgentCommandRedriveSourceSession> activeSource =
            new AtomicReference<>();
    private final AtomicReference<AgentCommandRedrivePublishAttempt> activeAttempt =
            new AtomicReference<>();
    private final AtomicReference<Future<AgentRabbitPublishResult>> activeFuture =
            new AtomicReference<>();

    public AgentCommandDlqRedriverImpl(
            AgentCommandRedriveRawProvider rawProvider,
            AgentCommandOperationsDao dao,
            WorkerScope workerScope,
            TenantModuleAdmissionGuard admissionGuard,
            PlatformTransactionManager transactionManager,
            AgentCommandRedriveRabbitSettings settings) {
        this.rawProvider = Objects.requireNonNull(rawProvider, "rawProvider");
        this.dao = Objects.requireNonNull(dao, "dao");
        this.workerScope = Objects.requireNonNull(workerScope, "workerScope");
        this.topology = AgentRabbitV2TopologyManifest.forScope(workerScope);
        this.admissionGuard = Objects.requireNonNull(admissionGuard, "admissionGuard");
        this.settings = Objects.requireNonNull(settings, "settings");
        publishTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        publishTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        publishTransaction.setTimeout(settings.admissionTransactionTimeoutSeconds());
        publishExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "agent-command-redrive-one-shot");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public AgentRabbitPublishResult redrive(
            AgentConfirmedPublishRequest expected,
            long confirmTimeoutMillis,
            int scanLimit) {
        AgentRabbitPublishResult invalid = validateRequest(
                expected, confirmTimeoutMillis, scanLimit);
        if (invalid != null) return invalid;
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.POISONED) return failure("DLQ_REDRIVE_SERVICE_POISONED");
        if (current != Lifecycle.RUNNING) return failure("DLQ_REDRIVE_SERVICE_CLOSED");
        if (!activeRedrive.compareAndSet(false, true)) {
            return failure("DLQ_REDRIVE_CAPACITY_EXHAUSTED");
        }
        try {
            return redriveOne(expected, confirmTimeoutMillis, scanLimit);
        } finally {
            activeRedrive.set(false);
        }
    }

    private AgentRabbitPublishResult validateRequest(
            AgentConfirmedPublishRequest expected,
            long confirmTimeoutMillis,
            int scanLimit) {
        if (expected == null || scanLimit < 1 || scanLimit > 500) {
            return failure("DLQ_REDRIVE_REQUEST_INVALID");
        }
        try {
            settings.hardPublishTimeoutMillis(confirmTimeoutMillis);
        } catch (IllegalArgumentException invalid) {
            return failure("DLQ_REDRIVE_REQUEST_INVALID");
        }
        if (expected.protocolVersion() != AgentCommandAmqpContract.WIRE_VERSION) {
            return failure("LEGACY_DLQ_REDRIVE_UNSUPPORTED");
        }
        try {
            AgentCommandAmqpContract.validate(expected);
        } catch (IllegalArgumentException invalid) {
            return failure("DLQ_REDRIVE_PROVENANCE_INVALID");
        }
        if (!requestMatchesScope(workerScope, expected)) {
            return failure("DLQ_REDRIVE_SCOPE_MISMATCH");
        }
        return null;
    }

    private AgentRabbitPublishResult redriveOne(
            AgentConfirmedPublishRequest expected,
            long confirmTimeoutMillis,
            int scanLimit) {
        AgentCommandRedriveSourceSession source = null;
        List<Long> held = new ArrayList<>();
        Long targetTag = null;
        boolean targetSettled = false;
        boolean publishAcknowledged = false;
        AgentRabbitPublishResult result = failure("DLQ_MESSAGE_NOT_FOUND");
        try {
            source = rawProvider.openSource();
            if (!registerSource(source)) {
                return failure("DLQ_REDRIVE_SERVICE_CLOSED");
            }
            source.declarePassive(topology.deadLetterQueue());
            for (int scanned = 0; scanned < scanLimit; scanned++) {
                if (lifecycle.get() != Lifecycle.RUNNING) {
                    result = failure("DLQ_REDRIVE_SERVICE_CLOSED");
                    break;
                }
                GetResponse response = source.get(
                        topology.deadLetterQueue());
                if (response == null) break;
                long tag = response.getEnvelope().getDeliveryTag();
                held.add(tag);
                if (response.getProps() == null
                        || !expected.messageId().equals(response.getProps().getMessageId())) {
                    continue;
                }
                targetTag = tag;
                AgentConfirmedPublishRequest brokerExpected = brokerExpected(expected, response);
                if (brokerExpected == null) {
                    result = failure("DLQ_MESSAGE_PROVENANCE_INVALID");
                    break;
                }
                AgentRabbitPublishResult invalid = validateBrokerMessage(brokerExpected, response);
                if (invalid != null) {
                    result = invalid;
                    break;
                }
                Map<String, Object> brokerHeaders = response.getProps().getHeaders();
                try {
                    AgentConfirmedRabbitPublisherImpl.validatePreservedHeaders(
                            brokerExpected, brokerHeaders);
                } catch (RuntimeException headerDrift) {
                    result = failure("DLQ_MESSAGE_PROVENANCE_INVALID");
                    break;
                }
                AgentCommandRedrivePublishAttempt attempt;
                try {
                    attempt = rawProvider.preparePublish(brokerExpected, brokerHeaders);
                } catch (TimeoutException timeout) {
                    result = failure("DLQ_REDRIVE_PREPARE_TIMEOUT");
                    break;
                } catch (Exception prepareFailure) {
                    result = failure("DLQ_REDRIVE_PREPARE_FAILED");
                    break;
                }
                if (!registerAttempt(attempt)) {
                    result = failure("DLQ_REDRIVE_SERVICE_CLOSED");
                    break;
                }
                long deadline = deadlineNanos(confirmTimeoutMillis);
                try {
                    result = publishAdmitted(brokerExpected, attempt, deadline);
                } finally {
                    activeAttempt.compareAndSet(attempt, null);
                }
                boolean cleanAttemptClose = attempt.closeAfterKnownOutcome();
                if (!cleanAttemptClose && result.type() == AgentRabbitPublishResult.Type.ACK) {
                    result = failure("DLQ_REDRIVE_PUBLISH_CONNECTION_CLOSE_UNCERTAIN");
                }
                publishAcknowledged = result.type() == AgentRabbitPublishResult.Type.ACK;
                if (publishAcknowledged) {
                    try {
                        source.ack(tag);
                        targetSettled = true;
                    } catch (IOException | RuntimeException sourceAckFailure) {
                        result = failure("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH");
                    }
                }
                break;
            }
        } catch (Exception sourceFailure) {
            result = publishAcknowledged || uncertain(result)
                    ? failure("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH")
                    : failure("DLQ_SOURCE_SESSION_UNCERTAIN");
        } finally {
            if (source != null) activeSource.compareAndSet(source, null);
        }

        boolean settlementProven = settleHeld(source, held, targetTag, targetSettled);
        boolean sourceClosed = source == null || source.closeBounded();
        if (!settlementProven || !sourceClosed) {
            return publishAcknowledged || uncertain(result)
                    ? failure("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH")
                    : failure("DLQ_SOURCE_SETTLEMENT_UNCERTAIN");
        }
        return result;
    }

    private boolean registerSource(AgentCommandRedriveSourceSession source) {
        if (lifecycle.get() != Lifecycle.RUNNING
                || !activeSource.compareAndSet(null, source)) {
            source.abort("redrive closed during source preparation");
            return false;
        }
        if (lifecycle.get() != Lifecycle.RUNNING) {
            activeSource.compareAndSet(source, null);
            source.abort("redrive closed after source preparation");
            return false;
        }
        return true;
    }

    private boolean registerAttempt(AgentCommandRedrivePublishAttempt attempt) {
        if (lifecycle.get() != Lifecycle.RUNNING
                || !activeAttempt.compareAndSet(null, attempt)) {
            attempt.abort("redrive closed during publish preparation");
            return false;
        }
        if (lifecycle.get() != Lifecycle.RUNNING) {
            activeAttempt.compareAndSet(attempt, null);
            attempt.abort("redrive closed after publish preparation");
            return false;
        }
        return true;
    }

    private AgentRabbitPublishResult publishAdmitted(
            AgentConfirmedPublishRequest expected,
            AgentCommandRedrivePublishAttempt attempt,
            long deadlineNanos) {
        AtomicBoolean admitted = new AtomicBoolean();
        try {
            AgentRabbitPublishResult result = publishTransaction.execute(status ->
                    admissionGuard.withWorkerAdmission(
                            workerScope, TenantModuleAdmissionGuard.WorkerOperation.PUBLISH,
                            mode -> {
                                if (mode != TenantModuleAdmissionGuard.Mode.DOMAIN) {
                                    throw new IllegalStateException(
                                            "DLQ redrive requires DOMAIN mode");
                                }
                                admitted.set(true);
                                if (lifecycle.get() != Lifecycle.RUNNING) {
                                    attempt.expireBeforeSendAtomically();
                                    return failure("DLQ_REDRIVE_SERVICE_CLOSED");
                                }
                                if (!requestMatchesScope(workerScope, expected)
                                        || !persistentSourceMatches(expected)) {
                                    attempt.expireBeforeSendAtomically();
                                    return failure("DLQ_REDRIVE_SOURCE_DRIFT");
                                }
                                if (System.nanoTime() >= deadlineNanos) {
                                    return deadlineExpiredBeforeSubmission(attempt);
                                }
                                Future<AgentRabbitPublishResult> future;
                                try {
                                    future = publishExecutor.submit(
                                            () -> attempt.execute(deadlineNanos));
                                } catch (RejectedExecutionException capacity) {
                                    attempt.expireBeforeSendAtomically();
                                    return failure(lifecycle.get() == Lifecycle.RUNNING
                                            ? "DLQ_REDRIVE_CAPACITY_EXHAUSTED"
                                            : "DLQ_REDRIVE_SERVICE_CLOSED");
                                }
                                activeFuture.set(future);
                                try {
                                    long remaining = deadlineNanos - System.nanoTime();
                                    if (remaining <= 0) {
                                        return deadlineExpired(attempt, future);
                                    }
                                    AgentRabbitPublishResult publish = future.get(
                                            remaining, TimeUnit.NANOSECONDS);
                                    if (publish == null) {
                                        poisonAndAbort(attempt, future,
                                                "redrive publish result missing");
                                        return failure(
                                                "DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
                                    }
                                    if ("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN".equals(
                                            publish.errorCode())) {
                                        poisonAndAbort(attempt, future,
                                                "redrive confirm deadline reached");
                                    }
                                    return publish;
                                } catch (TimeoutException timeout) {
                                    return deadlineExpired(attempt, future);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    poisonAndAbort(attempt, future,
                                            "redrive transaction interrupted");
                                    return failure(
                                            "DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
                                } catch (CancellationException cancelled) {
                                    if (lifecycle.get() == Lifecycle.RUNNING) {
                                        poisonAndAbort(attempt, future,
                                                "redrive publish task cancelled");
                                    } else {
                                        attempt.abort("redrive service closing");
                                    }
                                    return failure(
                                            "DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
                                } catch (ExecutionException executionFailure) {
                                    poisonAndAbort(attempt, future,
                                            "redrive publish task failed");
                                    return failure(
                                            "DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED");
                                } finally {
                                    activeFuture.compareAndSet(future, null);
                                }
                            }));
            return result == null
                    ? failure("DLQ_REDRIVE_AUTHORITY_TRANSACTION_FAILED") : result;
        } catch (RuntimeException deniedOrUncertain) {
            if (attempt.expireBeforeSendAtomically()
                    == AgentCommandRedrivePublishAttempt.ExpiryDecision
                    .SEND_ALREADY_STARTED_OR_TERMINAL) {
                poisonAndAbort(attempt, activeFuture.get(),
                        "redrive admission transaction outcome unknown");
                return failure("DLQ_REDRIVE_PUBLISH_TRANSACTION_UNCERTAIN");
            }
            return failure(admitted.get()
                    ? "DLQ_REDRIVE_AUTHORITY_TRANSACTION_FAILED"
                    : "WORKER_ADMISSION_REJECTED");
        }
    }

    private AgentRabbitPublishResult deadlineExpired(
            AgentCommandRedrivePublishAttempt attempt,
            Future<AgentRabbitPublishResult> future) {
        if (attempt.expireBeforeSendAtomically()
                == AgentCommandRedrivePublishAttempt.ExpiryDecision.EXPIRED_BEFORE_SEND) {
            future.cancel(true);
            return failure("DLQ_REDRIVE_PREPARE_TIMEOUT");
        }
        poisonAndAbort(attempt, future, "redrive hard publish deadline reached");
        return failure("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN");
    }

    private AgentRabbitPublishResult deadlineExpiredBeforeSubmission(
            AgentCommandRedrivePublishAttempt attempt) {
        if (attempt.expireBeforeSendAtomically()
                == AgentCommandRedrivePublishAttempt.ExpiryDecision.EXPIRED_BEFORE_SEND) {
            return failure("DLQ_REDRIVE_PREPARE_TIMEOUT");
        }
        lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.POISONED);
        attempt.abort("redrive deadline raced with publish submission");
        return failure("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN");
    }

    private void poisonAndAbort(
            AgentCommandRedrivePublishAttempt attempt,
            Future<AgentRabbitPublishResult> future,
            String reason) {
        lifecycle.compareAndSet(Lifecycle.RUNNING, Lifecycle.POISONED);
        attempt.abort(reason);
        if (future != null) future.cancel(true);
    }

    private long deadlineNanos(long confirmTimeoutMillis) {
        long budget = TimeUnit.MILLISECONDS.toNanos(
                settings.hardPublishTimeoutMillis(confirmTimeoutMillis));
        return Math.addExact(System.nanoTime(), budget);
    }

    private boolean persistentSourceMatches(AgentConfirmedPublishRequest expected) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                expected.tenantId(), expected.clientId(), expected.deliveryId());
        List<AgentOutboxEventEntity> outboxes = dao.lockActiveOutboxes(
                expected.tenantId(), expected.clientId(), expected.deliveryId(),
                expected.messageId());
        if (delivery == null || outboxes == null || outboxes.size() != 1
                || outboxes.getFirst() == null) {
            return false;
        }
        AgentOutboxEventEntity outbox = outboxes.getFirst();
        return deliveryMatches(expected, delivery) && outboxMatches(expected, outbox);
    }

    private boolean deliveryMatches(
            AgentConfirmedPublishRequest expected,
            AgentCommandDeliveryEntity delivery) {
        return Objects.equals(expected.deliveryId(), delivery.getId())
                && Objects.equals(AgentCommandAmqpContract.WIRE_VERSION,
                        delivery.getProtocolVersion())
                && Objects.equals(expected.tenantId(), delivery.getTenantId())
                && Objects.equals(expected.clientId(), delivery.getClientId())
                && Objects.equals(expected.dataDomainId(), delivery.getDataDomainId())
                && Objects.equals(expected.executionPoolId(), delivery.getExecutionPoolId())
                && Objects.equals(expected.admissionEpoch(), delivery.getAdmissionEpoch())
                && Objects.equals(expected.producerDeploymentId(),
                        delivery.getProducerDeploymentId())
                && Objects.equals(expected.moduleId(), delivery.getModuleId())
                && Objects.equals(expected.migrationEpoch(), delivery.getMigrationEpoch())
                && Objects.equals(expected.messageId(), delivery.getActiveMessageId())
                && Objects.equals(expected.commandId(), delivery.getCommandId())
                && Objects.equals(expected.taskId(), delivery.getTaskId())
                && Objects.equals(expected.targetAgentId(), delivery.getTargetAgentId())
                && Objects.equals(expected.commandType(), delivery.getCommandType())
                && Objects.equals(expected.activeAttempt(), delivery.getActiveAttempt())
                && Objects.equals(expected.expiresAt(), delivery.getExpiresAt())
                && "PUBLISHED".equals(delivery.getStatus())
                && delivery.getNextRetryAt() == null
                && delivery.getLeaseOwner() == null
                && delivery.getLeaseUntil() == null;
    }

    private boolean outboxMatches(
            AgentConfirmedPublishRequest expected,
            AgentOutboxEventEntity outbox) {
        return Objects.equals(AgentCommandAmqpContract.WIRE_VERSION,
                        outbox.getProtocolVersion())
                && Objects.equals(expected.tenantId(), outbox.getTenantId())
                && Objects.equals(expected.clientId(), outbox.getClientId())
                && Objects.equals(expected.dataDomainId(), outbox.getDataDomainId())
                && Objects.equals(expected.executionPoolId(), outbox.getExecutionPoolId())
                && Objects.equals(expected.admissionEpoch(), outbox.getAdmissionEpoch())
                && Objects.equals(expected.producerDeploymentId(),
                        outbox.getProducerDeploymentId())
                && Objects.equals(expected.moduleId(), outbox.getModuleId())
                && Objects.equals(expected.migrationEpoch(), outbox.getMigrationEpoch())
                && Objects.equals(expected.deliveryId(), outbox.getDeliveryId())
                && Objects.equals(expected.eventId(), outbox.getEventId())
                && Objects.equals(expected.messageId(), outbox.getMessageId())
                && Objects.equals(expected.commandId(), outbox.getCommandId())
                && "task".equals(outbox.getAggregateType())
                && Objects.equals(expected.taskId(), outbox.getAggregateId())
                && Objects.equals(expected.destination(), outbox.getDestination())
                && Objects.equals(expected.routingKey(), outbox.getRoutingKey())
                && outbox.getWirePayload() != null
                && outbox.getWirePayloadHash() != null
                && MessageDigest.isEqual(expected.wirePayload(), outbox.getWirePayload())
                && MessageDigest.isEqual(expected.wirePayloadHash(), outbox.getWirePayloadHash())
                && MessageDigest.isEqual(
                        AgentCommandAmqpContract.sha256(outbox.getWirePayload()),
                        outbox.getWirePayloadHash())
                && Objects.equals(expected.activeAttempt(), outbox.getActiveAttempt())
                && Objects.equals(expected.expiresAt(), outbox.getExpiresAt())
                && "PUBLISHED".equals(outbox.getStatus())
                && outbox.getAttemptCount() != null && outbox.getAttemptCount() > 0
                && outbox.getNextRetryAt() == null
                && outbox.getLeaseOwner() == null
                && outbox.getLeaseUntil() == null
                && "ACK".equals(outbox.getPublisherConfirmStatus())
                && outbox.getConfirmedAt() != null && outbox.getConfirmedAt() > 0
                && outbox.getConfirmError() == null
                && "NOT_RETURNED".equals(outbox.getMandatoryReturnStatus())
                && outbox.getReturnedAt() == null
                && outbox.getReturnReplyCode() == null
                && outbox.getReturnReplyText() == null
                && outbox.getPublishedAt() != null && outbox.getPublishedAt() > 0
                && outbox.getLastError() == null;
    }

    private boolean settleHeld(
            AgentCommandRedriveSourceSession source,
            List<Long> held,
            Long targetTag,
            boolean targetSettled) {
        if (source == null) return held.isEmpty();
        try {
            for (Long tag : held) {
                if (targetSettled && Objects.equals(tag, targetTag)) continue;
                source.nack(tag);
            }
            source.settlementBarrier(topology.deadLetterQueue());
            return true;
        } catch (IOException | RuntimeException settlementFailure) {
            return false;
        }
    }

    private AgentRabbitPublishResult validateBrokerMessage(
            AgentConfirmedPublishRequest expected,
            GetResponse response) {
        AMQP.BasicProperties properties = response.getProps();
        if (properties == null || response.getEnvelope() == null
                || !topology.deadLetterExchange().equals(
                        response.getEnvelope().getExchange())
                || !topology.deadRoutingKey().equals(
                        response.getEnvelope().getRoutingKey())
                || response.getBody() == null
                || !MessageDigest.isEqual(expected.wirePayload(), response.getBody())
                || !AgentCommandAmqpContract.CONTENT_TYPE.equals(properties.getContentType())
                || !AgentCommandAmqpContract.CONTENT_ENCODING.equals(
                        properties.getContentEncoding())
                || !Integer.valueOf(2).equals(properties.getDeliveryMode())
                || !expected.messageId().equals(properties.getMessageId())
                || !AgentCommandAmqpContract.MESSAGE_TYPE.equals(properties.getType())
                || properties.getPriority() != null || properties.getTimestamp() != null
                || properties.getCorrelationId() != null || properties.getReplyTo() != null
                || properties.getExpiration() != null || properties.getUserId() != null
                || properties.getAppId() != null || properties.getClusterId() != null) {
            return failure("DLQ_MESSAGE_PROVENANCE_INVALID");
        }
        return null;
    }

    private AgentConfirmedPublishRequest brokerExpected(
            AgentConfirmedPublishRequest expected,
            GetResponse response) {
        try {
            Object value = response.getProps().getHeaders()
                    .get(AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);
            if (!(value instanceof Number number)
                    || !(value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long)) return null;
            long retry = number.longValue();
            if (retry < 0 || retry > 1_000) return null;
            return new AgentConfirmedPublishRequest(
                    expected.destination(), expected.routingKey(), expected.wirePayload(),
                    expected.wirePayloadHash(), expected.messageId(), expected.eventId(),
                    expected.deliveryId(), expected.commandId(), expected.tenantId(),
                    expected.dataDomainId(), expected.executionPoolId(),
                    expected.admissionEpoch(), expected.producerDeploymentId(),
                    expected.moduleId(), expected.migrationEpoch(), expected.protocolVersion(),
                    expected.clientId(), expected.taskId(), expected.targetAgentId(),
                    expected.commandType(), expected.activeAttempt(), expected.expiresAt(),
                    expected.topologySha256(), (int) retry);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean requestMatchesScope(
            WorkerScope scope,
            AgentConfirmedPublishRequest request) {
        return scope.tenantId().value().equals(request.tenantId())
                && scope.dataDomainId().value().equals(request.dataDomainId())
                && scope.executionPoolId().equals(request.executionPoolId())
                && scope.admissionEpoch() == request.admissionEpoch()
                && scope.moduleId().equals(request.moduleId())
                && scope.migrationEpoch() == request.migrationEpoch();
    }

    private static boolean uncertain(AgentRabbitPublishResult result) {
        return result != null && result.errorCode() != null
                && (result.errorCode().endsWith("_UNCERTAIN")
                || result.errorCode().contains("TRANSACTION_UNCERTAIN"));
    }

    private static AgentRabbitPublishResult failure(String code) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null, code);
    }

    @Override
    public void close() {
        Lifecycle before = lifecycle.getAndUpdate(current ->
                current == Lifecycle.RUNNING ? Lifecycle.CLOSING : current);
        if (before == Lifecycle.CLOSED || !closeStarted.compareAndSet(false, true)) return;

        AgentCommandRedriveSourceSession source = activeSource.get();
        AgentCommandRedrivePublishAttempt attempt = activeAttempt.get();
        Future<AgentRabbitPublishResult> future = activeFuture.get();
        if (source != null) source.abort("redrive service closing");
        if (attempt != null) attempt.abort("redrive service closing");
        if (future != null) future.cancel(true);
        publishExecutor.shutdownNow();

        boolean terminated = false;
        try {
            terminated = publishExecutor.awaitTermination(
                    settings.controlCloseBudgetMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Lifecycle current = lifecycle.get();
        if (current == Lifecycle.CLOSING) {
            lifecycle.compareAndSet(Lifecycle.CLOSING,
                    terminated ? Lifecycle.CLOSED : Lifecycle.POISONED);
        }
    }
}
