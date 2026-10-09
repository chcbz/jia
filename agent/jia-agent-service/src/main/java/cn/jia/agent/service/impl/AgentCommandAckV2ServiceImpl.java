package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandAckV2;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandAckV2Service;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Durable v2 ACK state machine. Runtime/Rabbit/WebSocket I/O is deliberately outside this class. */
public final class AgentCommandAckV2ServiceImpl implements AgentCommandAckV2Service {
    private enum AckLane { PROCESSING, SETTLED_SENT }

    private static final Set<String> ACK_STATUSES = Set.of(
            "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED");
    private static final Set<String> TERMINAL = Set.of(
            "SUCCEEDED", "FAILED", "REJECTED");

    private final AgentCommandInboxV2Dao dao;
    private final WorkerScope authority;
    private final TenantModuleAdmissionGuard guard;
    private final TransactionTemplate tx;

    public AgentCommandAckV2ServiceImpl(AgentCommandInboxV2Dao dao, WorkerScope authority,
            TenantModuleAdmissionGuard guard, PlatformTransactionManager manager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AgentCommandAckResult acknowledge(AgentCommandAckV2 acknowledgement, long now) {
        requireNoAmbientTransaction();
        validateRequest(acknowledgement, now);
        exactAuthority(acknowledgement.claimToken());
        return Objects.requireNonNull(tx.execute(status -> guard.withWorkerAdmission(
                authority, TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT, mode -> {
                    if (mode != TenantModuleAdmissionGuard.Mode.DOMAIN) throw denied();
                    return acknowledgeLocked(acknowledgement, now);
                })), "acknowledgement result");
    }

    private AgentCommandAckResult acknowledgeLocked(AgentCommandAckV2 ack, long now) {
        AgentInboxClaimToken token = ack.claimToken();
        AgentInboxMessage locator = message(token, new byte[]{1});
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(locator);
        if (delivery == null) throw denied();
        AgentOutboxEventEntity outbox = dao.lockOutbox(locator);
        if (outbox == null) throw denied();
        List<AgentOutboxEventEntity> previous = dao.lockPrevious(locator, delivery.getActiveAttempt());
        AgentConsumerInboxEntity inbox = dao.lockInbox(locator);
        if (inbox == null || inbox.getWirePayload() == null) throw denied();
        AgentInboxMessage source = message(token, inbox.getWirePayload());
        validateSource(source, delivery, outbox, previous, inbox);
        boolean automaticReplay = AgentCommandAutomaticReplayProvenance
                .validAutomaticImmediateParent(delivery, outbox, previous);
        AckLane lane = validateClaim(token, delivery, inbox, ack, now, automaticReplay);

        if (ack.ackStatus().equals(delivery.getStatus())) {
            if (!ack.ackMessageId().equals(inbox.getAckMessageId())
                    || ack.ackAt() != inbox.getAckAt()
                    || !ack.authenticatedAgentId().equals(inbox.getAckAgentId())
                    || !ack.authenticatedRuntimeInstallationId().equals(
                            inbox.getAckRuntimeInstallationId())
                    || !ack.authenticatedRuntimeInstanceId().equals(inbox.getAckRuntimeInstanceId())) {
                throw denied();
            }
            return new AgentCommandAckResult(AgentCommandAckResult.Kind.PRIOR,
                    delivery.getStatus(), delivery.getVersion());
        }
        if (Objects.equals(ack.ackMessageId(), inbox.getAckMessageId())) throw denied();

        String expectedStatus = delivery.getStatus();
        if (!validTransition(expectedStatus, ack.ackStatus(), automaticReplay)) throw denied();
        long expectedVersion = delivery.getVersion();
        long nextVersion;
        try {
            nextVersion = Math.addExact(expectedVersion, 1L);
        } catch (ArithmeticException exhausted) {
            throw denied();
        }
        String lastError = errorFor(ack.ackStatus());
        if (lane == AckLane.PROCESSING) {
            one(dao.advanceAcknowledgement(token, inbox, expectedVersion, expectedStatus,
                    ack.ackStatus(), lastError, now));
            one(dao.recordAcknowledgement(token, inbox, ack.ackMessageId(), ack.ackStatus(),
                    ack.ackAt(), ack.authenticatedAgentId(),
                    ack.authenticatedRuntimeInstallationId(), ack.authenticatedRuntimeInstanceId(),
                    nextVersion, now));
        } else {
            one(dao.advanceSettledAcknowledgement(token, inbox, expectedVersion, expectedStatus,
                    ack.ackStatus(), lastError, now));
            one(dao.recordSettledAcknowledgement(token, inbox, ack.ackMessageId(), ack.ackStatus(),
                    ack.ackAt(), ack.authenticatedAgentId(),
                    ack.authenticatedRuntimeInstallationId(), ack.authenticatedRuntimeInstanceId(),
                    nextVersion, now));
        }
        return new AgentCommandAckResult(
                AgentCommandAckResult.Kind.ADVANCED, ack.ackStatus(), nextVersion);
    }

    private AckLane validateClaim(AgentInboxClaimToken token, AgentCommandDeliveryEntity delivery,
            AgentConsumerInboxEntity inbox, AgentCommandAckV2 ack, long now,
            boolean automaticReplay) {
        if (!Objects.equals(inbox.getActiveAttempt(), token.activeAttempt())
                || !Objects.equals(inbox.getExpiresAt(), token.expiresAt())
                || !Objects.equals(inbox.getClaimDeliveryVersion(), token.deliveryVersion())
                || !Objects.equals(inbox.getClaimInboxVersion(), token.inboxVersion())
                || !Objects.equals(delivery.getActiveAttempt(), token.deliveryActiveAttempt())
                || !Objects.equals(delivery.getActiveMessageId(), token.messageId())
                || !Objects.equals(delivery.getTargetAgentId(), ack.authenticatedAgentId())
                || inbox.getClaimStartedAt() == null || inbox.getClaimStartedAt() <= 0
                || now >= token.leaseUntil() || now >= token.expiresAt()
                || ack.ackAt() < inbox.getClaimStartedAt()
                || ack.ackAt() >= token.leaseUntil() || ack.ackAt() >= token.expiresAt()) {
            throw denied();
        }

        AckLane lane;
        if ("PROCESSING".equals(inbox.getStatus()) && inbox.getResultStatus() == null
                && inbox.getNextRetryAt() == null && inbox.getProcessedAt() == null
                && Objects.equals(inbox.getLeaseOwner(), token.leaseOwner())
                && Objects.equals(inbox.getLeaseUntil(), token.leaseUntil())) {
            lane = AckLane.PROCESSING;
        } else if ("PROCESSED".equals(inbox.getStatus())
                && "SENT".equals(inbox.getResultStatus())
                && inbox.getNextRetryAt() == null && inbox.getLeaseOwner() == null
                && inbox.getLeaseUntil() == null && inbox.getProcessedAt() != null
                && inbox.getProcessedAt() > 0 && inbox.getProcessedAt() < token.leaseUntil()
                && inbox.getProcessedAt() < token.expiresAt() && inbox.getLastError() == null) {
            lane = AckLane.SETTLED_SENT;
        } else {
            throw denied();
        }

        boolean noAck = inbox.getAckDeliveryVersion() == null && inbox.getAckMessageId() == null
                && inbox.getAckStatus() == null && inbox.getAckAt() == null
                && inbox.getAckAgentId() == null && inbox.getAckRuntimeInstallationId() == null
                && inbox.getAckRuntimeInstanceId() == null;
        if (noAck) {
            boolean processingOrigin = lane == AckLane.PROCESSING
                    && "CONSUMED".equals(delivery.getStatus())
                    && Objects.equals(delivery.getVersion(), token.deliveryVersion())
                    && Objects.equals(inbox.getVersion(), token.inboxVersion());
            boolean completeFirst = lane == AckLane.SETTLED_SENT
                    && "SENT".equals(delivery.getStatus())
                    && Objects.equals(delivery.getVersion(), token.deliveryVersion() + 1L)
                    && Objects.equals(inbox.getVersion(), token.inboxVersion() + 1L);
            if ((!processingOrigin && !completeFirst)
                    || delivery.getNextRetryAt() != null || delivery.getLeaseOwner() != null
                    || delivery.getLeaseUntil() != null || delivery.getLastError() != null) {
                throw denied();
            }
            return lane;
        }
        validateAckEvidence(delivery, inbox, token, lane, automaticReplay);
        if (!Objects.equals(inbox.getAckAgentId(), ack.authenticatedAgentId())
                || !Objects.equals(inbox.getAckRuntimeInstallationId(),
                        ack.authenticatedRuntimeInstallationId())
                || !Objects.equals(inbox.getAckRuntimeInstanceId(),
                        ack.authenticatedRuntimeInstanceId())
                || ack.ackAt() < inbox.getAckAt()) {
            throw denied();
        }
        return lane;
    }

    private void validateAckEvidence(AgentCommandDeliveryEntity delivery,
            AgentConsumerInboxEntity inbox, AgentInboxClaimToken token, AckLane lane,
            boolean automaticReplay) {
        if (!ACK_STATUSES.contains(inbox.getAckStatus())
                || !Objects.equals(delivery.getStatus(), inbox.getAckStatus())
                || !Objects.equals(delivery.getVersion(), inbox.getAckDeliveryVersion())
                || !Objects.equals(delivery.getTargetAgentId(), inbox.getAckAgentId())
                || !exact(inbox.getAckMessageId(), 100) || !exact(inbox.getAckAgentId(), 100)
                || !exact(inbox.getAckRuntimeInstallationId(), 100)
                || !exact(inbox.getAckRuntimeInstanceId(), 100)
                || inbox.getAckAt() == null || inbox.getAckAt() <= 0
                || inbox.getClaimStartedAt() == null
                || inbox.getAckAt() < inbox.getClaimStartedAt()
                || inbox.getAckAt() >= token.leaseUntil() || inbox.getAckAt() >= token.expiresAt()
                || delivery.getNextRetryAt() != null || delivery.getLeaseOwner() != null
                || delivery.getLeaseUntil() != null || !validLastError(delivery)) {
            throw denied();
        }
        long deliveryAdvances = delivery.getVersion() - token.deliveryVersion();
        long inboxAdvances = inbox.getVersion() - token.inboxVersion();
        long acknowledgementAdvances;
        if (lane == AckLane.PROCESSING) {
            if (deliveryAdvances != inboxAdvances) throw denied();
            acknowledgementAdvances = deliveryAdvances;
        } else if (inboxAdvances == deliveryAdvances + 1L) {
            acknowledgementAdvances = deliveryAdvances;
        } else if (inboxAdvances == deliveryAdvances) {
            acknowledgementAdvances = deliveryAdvances - 1L;
        } else {
            throw denied();
        }
        if (!validEvidenceStep(delivery.getStatus(), acknowledgementAdvances,
                automaticReplay)) throw denied();
    }

    private void validateSource(AgentInboxMessage message, AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox, List<AgentOutboxEventEntity> previous,
            AgentConsumerInboxEntity inbox) {
        if (!sameProducerScope(message, delivery) || !sameProducerScope(message, outbox)
                || !sameInboxScope(message, inbox)
                || delivery.getClaimedDeploymentId() != null
                || delivery.getClaimedWorkerGeneration() != null
                || outbox.getClaimedDeploymentId() != null
                || outbox.getClaimedWorkerGeneration() != null
                || !Objects.equals(delivery.getId(), message.deliveryId())
                || !Objects.equals(delivery.getCommandId(), message.commandId())
                || !Objects.equals(delivery.getTaskId(), message.taskId())
                || !Objects.equals(delivery.getTargetAgentId(), message.targetAgentId())
                || !Objects.equals(delivery.getCommandType(), message.commandType())
                || !Objects.equals(delivery.getActiveMessageId(), message.messageId())
                || !Objects.equals(outbox.getEventId(), message.eventId())
                || !Objects.equals(outbox.getMessageId(), message.messageId())
                || !Objects.equals(outbox.getCommandId(), message.commandId())
                || !Objects.equals(outbox.getDeliveryId(), message.deliveryId())
                || !Objects.equals(inbox.getConsumerName(), message.consumerName())
                || !Objects.equals(inbox.getMessageId(), message.messageId())
                || !Objects.equals(inbox.getEventId(), message.eventId())
                || !Objects.equals(inbox.getCommandId(), message.commandId())
                || !Objects.equals(inbox.getDeliveryId(), message.deliveryId())
                || !Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                || !Objects.equals(delivery.getExpiresAt(), inbox.getExpiresAt())
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || delivery.getAttemptCount() == null
                || !Objects.equals(delivery.getAttemptCount(), delivery.getActiveAttempt())
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || inbox.getVersion() == null || inbox.getVersion() < 0
                || !"PUBLISHED".equals(outbox.getStatus())
                || !Arrays.equals(outbox.getWirePayload(), inbox.getWirePayload())
                || !validHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || !validHash(inbox.getWirePayload(), inbox.getWirePayloadHash())
                || !validHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())
                || !AgentCommandAutomaticReplayProvenance.validImmediateParent(
                        delivery, outbox, previous)) {
            throw denied();
        }
    }

    private boolean sameProducerScope(AgentInboxMessage m, Object row) {
        if (row instanceof AgentCommandDeliveryEntity d) {
            return Objects.equals(d.getProtocolVersion(), 2) && Objects.equals(d.getTenantId(), m.tenantId())
                    && Objects.equals(d.getClientId(), m.clientId())
                    && Objects.equals(d.getDataDomainId(), m.dataDomainId())
                    && Objects.equals(d.getExecutionPoolId(), m.executionPoolId())
                    && Objects.equals(d.getAdmissionEpoch(), m.admissionEpoch())
                    && Objects.equals(d.getProducerDeploymentId(), m.producerDeploymentId())
                    && Objects.equals(d.getModuleId(), m.moduleId())
                    && Objects.equals(d.getMigrationEpoch(), m.migrationEpoch());
        }
        if (row instanceof AgentOutboxEventEntity o) {
            return Objects.equals(o.getProtocolVersion(), 2) && Objects.equals(o.getTenantId(), m.tenantId())
                    && Objects.equals(o.getClientId(), m.clientId())
                    && Objects.equals(o.getDataDomainId(), m.dataDomainId())
                    && Objects.equals(o.getExecutionPoolId(), m.executionPoolId())
                    && Objects.equals(o.getAdmissionEpoch(), m.admissionEpoch())
                    && Objects.equals(o.getProducerDeploymentId(), m.producerDeploymentId())
                    && Objects.equals(o.getModuleId(), m.moduleId())
                    && Objects.equals(o.getMigrationEpoch(), m.migrationEpoch());
        }
        return false;
    }

    private boolean sameInboxScope(AgentInboxMessage m, AgentConsumerInboxEntity i) {
        return Objects.equals(i.getProtocolVersion(), 2) && Objects.equals(i.getTenantId(), m.tenantId())
                && Objects.equals(i.getClientId(), m.clientId())
                && Objects.equals(i.getDataDomainId(), m.dataDomainId())
                && Objects.equals(i.getExecutionPoolId(), m.executionPoolId())
                && Objects.equals(i.getAdmissionEpoch(), m.admissionEpoch())
                && Objects.equals(i.getProducerDeploymentId(), m.producerDeploymentId())
                && Objects.equals(i.getModuleId(), m.moduleId())
                && Objects.equals(i.getMigrationEpoch(), m.migrationEpoch())
                && Objects.equals(i.getClaimantDeploymentId(), m.claimantDeploymentId())
                && Objects.equals(i.getClaimantWorkerGeneration(), m.claimantWorkerGeneration());
    }

    private void validateRequest(AgentCommandAckV2 ack, long now) {
        if (ack == null || ack.claimToken() == null || now <= 0
                || !exact(ack.ackMessageId(), 100) || !exact(ack.sourceMessageId(), 100)
                || !ack.sourceMessageId().equals(ack.claimToken().messageId())
                || ack.ackMessageId().equals(ack.sourceMessageId())
                || !exact(ack.authenticatedAgentId(), 100)
                || !exact(ack.authenticatedRuntimeInstallationId(), 100)
                || !exact(ack.authenticatedRuntimeInstanceId(), 100)
                || ack.authenticatedAgentId().equals(ack.authenticatedRuntimeInstanceId())
                || !ACK_STATUSES.contains(ack.ackStatus()) || ack.ackAt() <= 0
                || ack.ackAt() > safeFuture(now) || !validToken(ack.claimToken())) {
            throw denied();
        }
    }

    private boolean validToken(AgentInboxClaimToken t) {
        return t.protocolVersion() == 2
                && AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2.equals(t.consumerName())
                && t.inboxId() > 0 && t.deliveryId() > 0 && t.deliveryActiveAttempt() > 0
                && t.activeAttempt() > 0 && t.inboxVersion() >= 0 && t.deliveryVersion() >= 0
                && t.leaseUntil() > 0 && t.expiresAt() > 0 && t.leaseUntil() <= t.expiresAt()
                && exact(t.tenantId(), 50) && exact(t.dataDomainId(), 64)
                && exact(t.executionPoolId(), 100) && t.admissionEpoch() >= 0
                && exact(t.producerDeploymentId(), 100) && exact(t.moduleId(), 64)
                && t.migrationEpoch() >= 0 && exact(t.claimantDeploymentId(), 100)
                && t.claimantWorkerGeneration() >= 0 && exact(t.clientId(), 50)
                && exact(t.messageId(), 100) && exact(t.eventId(), 100)
                && exact(t.commandId(), 100) && exact(t.taskId(), 100)
                && exact(t.targetAgentId(), 100)
                && exact(t.commandType(), 64)
                && t.commandType().matches("[A-Z][A-Z0-9_]{0,63}")
                && exact(t.leaseOwner(), 100);
    }

    private void exactAuthority(AgentInboxClaimToken token) {
        if (!authority.tenantId().value().equals(token.tenantId())
                || !authority.dataDomainId().value().equals(token.dataDomainId())
                || !authority.executionPoolId().equals(token.executionPoolId())
                || authority.admissionEpoch() != token.admissionEpoch()
                || !authority.moduleId().equals(token.moduleId())
                || authority.migrationEpoch() != token.migrationEpoch()
                || !authority.deploymentId().equals(token.claimantDeploymentId())
                || authority.workerGeneration() != token.claimantWorkerGeneration()) throw denied();
    }

    private AgentInboxMessage message(AgentInboxClaimToken token, byte[] wire) {
        return new AgentInboxMessage(token.consumerName(), token.tenantId(), token.dataDomainId(),
                token.executionPoolId(), token.admissionEpoch(), token.producerDeploymentId(),
                token.moduleId(), token.migrationEpoch(), token.protocolVersion(),
                token.claimantDeploymentId(), token.claimantWorkerGeneration(), token.clientId(),
                token.messageId(), token.eventId(), token.commandId(), token.taskId(),
                token.targetAgentId(), token.commandType(), token.deliveryId(), wire);
    }

    private boolean validTransition(String current, String requested, boolean automaticReplay) {
        if (TERMINAL.contains(current)) return false;
        return switch (current) {
            case "CONSUMED", "SENT" -> "REJECTED".equals(requested)
                    || (!automaticReplay && "RECEIVED".equals(requested))
                    || (automaticReplay && Set.of("SUCCEEDED", "FAILED").contains(requested));
            case "RECEIVED" -> Set.of("STARTED", "REJECTED").contains(requested);
            case "STARTED" -> TERMINAL.contains(requested);
            default -> false;
        };
    }

    private boolean validEvidenceStep(String status, long advances,
            boolean automaticReplay) {
        if (automaticReplay) {
            return advances == 1L && TERMINAL.contains(status);
        }
        return switch (status) {
            case "RECEIVED" -> advances == 1L;
            case "STARTED" -> advances == 2L;
            case "SUCCEEDED", "FAILED" -> advances == 3L;
            case "REJECTED" -> advances >= 1L && advances <= 3L;
            default -> false;
        };
    }

    private String errorFor(String status) {
        return switch (status) {
            case "FAILED" -> AgentCommandInboxV2ServiceImpl.AGENT_REPORTED_FAILED;
            case "REJECTED" -> AgentCommandInboxV2ServiceImpl.AGENT_REPORTED_REJECTED;
            default -> null;
        };
    }

    private boolean validLastError(AgentCommandDeliveryEntity delivery) {
        return Objects.equals(delivery.getLastError(), errorFor(delivery.getStatus()));
    }

    private long safeFuture(long now) {
        try {
            return Math.addExact(now, 300_000L);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private boolean validHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && MessageDigest.isEqual(AgentCommandAmqpContract.sha256(bytes), hash);
    }

    private boolean exact(String value, int maximum) {
        return value != null && !value.isEmpty() && value.length() <= maximum
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private void one(int rows) {
        if (rows != 1) throw denied();
    }

    private void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw denied();
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Agent command ACK v2 admission denied");
    }
}
