package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Durable protocol-v2 core. Rabbit settlement remains outside every database transaction. */
public final class AgentCommandInboxV2ServiceImpl implements AgentCommandInboxV2Service {
    static final long MAX_LEASE_MILLIS = 300_000L;
    static final int EXECUTION_TRANSACTION_TIMEOUT_SECONDS = 7;
    static final String MESSAGE_EXPIRED = "MESSAGE_EXPIRED";
    static final String AGENT_REPORTED_FAILED = "AGENT_REPORTED_FAILED";
    static final String AGENT_REPORTED_REJECTED = "AGENT_REPORTED_REJECTED";
    private static final Set<String> ACK_DELIVERY_STATUSES = Set.of(
            "RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED");

    private static final Set<String> TERMINAL = Set.of(
            "PROCESSED", "WAITING_AGENT", "FAILED", "EXPIRED", "DEAD");

    private final AgentCommandInboxV2Dao dao;
    private final WorkerScope authority;
    private final TenantModuleAdmissionGuard guard;
    private final TransactionTemplate tx;
    private final TransactionTemplate executionTx;

    public AgentCommandInboxV2ServiceImpl(AgentCommandInboxV2Dao dao, WorkerScope authority,
            TenantModuleAdmissionGuard guard, PlatformTransactionManager manager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager, "manager"));
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executionTx = new TransactionTemplate(manager);
        this.executionTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.executionTx.setTimeout(EXECUTION_TRANSACTION_TIMEOUT_SECONDS);
    }

    @Override
    public AgentInboxClaim claim(AgentInboxMessage message, String leaseOwner,
            long now, long leaseMillis) {
        requireNoAmbientTransaction();
        validateMessage(message, leaseOwner, now, leaseMillis);
        exactAuthority(message);
        return Objects.requireNonNull(tx.execute(status -> guard.withWorkerAdmission(
                authority, TenantModuleAdmissionGuard.WorkerOperation.CLAIM, mode -> {
                    requireDomain(mode);
                    return claimLocked(message, leaseOwner, now, leaseMillis);
                })), "claim result");
    }

    @Override
    public AgentCommandV2ExecutionPort.Result executeAdmitted(
            AgentInboxClaimToken token, String ownerJiacn, byte[] wirePayload, long now,
            AgentCommandV2ExecutionPort executionPort) {
        requireNoAmbientTransaction();
        validateToken(token);
        exactAuthority(token);
        if (now <= 0 || !exact(ownerJiacn, 50) || wirePayload == null
                || wirePayload.length == 0
                || wirePayload.length > AgentCommandAmqpContract.MAX_WIRE_BYTES) {
            throw denied();
        }
        Objects.requireNonNull(executionPort, "executionPort");
        AgentCommandV2ExecutionPort.Command command =
                new AgentCommandV2ExecutionPort.Command(token, ownerJiacn, wirePayload);
        if(executionPort.durableHandoff()) {
            AgentRuntimeV2DispatchServiceImpl.outside();
            var result=Objects.requireNonNull(executionPort.execute(command),"execution result");
            AgentRuntimeV2DispatchServiceImpl.outside();
            return result;
        }
        return Objects.requireNonNull(executionTx.execute(status -> guard.withWorkerAdmission(
                authority, TenantModuleAdmissionGuard.WorkerOperation.EXECUTE, mode -> {
                    requireDomain(mode);
                    verifyExecutionLocked(token, now);
                    return Objects.requireNonNull(executionPort.execute(command),
                            "execution result");
                })), "execution result");
    }

    @Override
    public AgentInboxResult complete(AgentInboxClaimToken token,
            AgentInboxDisposition disposition, long now) {
        requireNoAmbientTransaction();
        validateToken(token);
        exactAuthority(token);
        Objects.requireNonNull(disposition, "disposition");
        if (now <= 0) throw denied();
        return Objects.requireNonNull(tx.execute(status -> guard.withWorkerAdmission(
                authority, TenantModuleAdmissionGuard.WorkerOperation.SETTLEMENT, mode -> {
                    requireDomain(mode);
                    return settleLocked(token, disposition, now);
                })), "completion result");
    }

    private AgentInboxClaim claimLocked(AgentInboxMessage message, String owner,
            long now, long leaseMillis) {
        LockedSource source = lockSource(message);
        AgentConsumerInboxEntity inbox = dao.lockInbox(message);
        if (inbox == null) {
            requireStatus(source.delivery(), "PUBLISHED");
            if (now >= source.delivery().getExpiresAt()) {
                return persistFirstExpiry(message, source.delivery(), now);
            }
            return acquireFirst(message, source.delivery(), owner, now, leaseMillis);
        }

        validateInboxIdentity(message, source.delivery(), inbox);
        boolean automaticReplay = AgentCommandAutomaticReplayProvenance
                .validAutomaticImmediateParent(
                        source.delivery(), source.outbox(), source.previous());
        if (TERMINAL.contains(inbox.getStatus())) {
            validateTerminal(message, source.delivery(), inbox, automaticReplay);
            return AgentInboxClaim.priorResult(result(inbox));
        }
        if ("PROCESSING".equals(inbox.getStatus())
                && ACK_DELIVERY_STATUSES.contains(source.delivery().getStatus())) {
            return recoverAcknowledged(message, source.delivery(), inbox, now, automaticReplay);
        }
        return switch (inbox.getStatus()) {
            case "PROCESSING" -> reclaimProcessing(
                    message, source.delivery(), inbox, owner, now, leaseMillis,
                    sameClaimant(message, inbox));
            case "RETRY" -> reclaimRetry(
                    message, source.delivery(), inbox, owner, now, leaseMillis,
                    sameClaimant(message, inbox));
            default -> throw denied();
        };
    }

    private AgentInboxClaim acquireFirst(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, String owner, long now, long leaseMillis) {
        long leaseUntil = leaseUntil(now, leaseMillis, delivery.getExpiresAt());
        AgentConsumerInboxEntity inbox = newInbox(message, delivery.getExpiresAt(),
                "PROCESSING", null, 1, owner, leaseUntil, 1, null, null,
                delivery.getVersion() + 1, 0L, now);
        try {
            one(dao.insert(inbox));
        } catch (DuplicateKeyException duplicate) {
            throw denied();
        }
        if (inbox.getId() == null || inbox.getId() <= 0) throw denied();
        one(dao.transitionDelivery(message, delivery.getActiveAttempt(), delivery.getVersion(),
                "PUBLISHED", "CONSUMED", null, null, now));
        return AgentInboxClaim.acquired(token(message, inbox, owner, leaseUntil, 1, 0,
                delivery.getActiveAttempt(), delivery.getVersion() + 1, delivery.getExpiresAt()));
    }

    private AgentInboxClaim persistFirstExpiry(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, long now) {
        AgentConsumerInboxEntity inbox = newInbox(message, delivery.getExpiresAt(),
                "EXPIRED", "EXPIRED", 0, null, null, 0, now, MESSAGE_EXPIRED,
                null, null, now);
        try {
            one(dao.insert(inbox));
        } catch (DuplicateKeyException duplicate) {
            throw denied();
        }
        if (inbox.getId() == null || inbox.getId() <= 0) throw denied();
        one(dao.transitionDelivery(message, delivery.getActiveAttempt(), delivery.getVersion(),
                "PUBLISHED", "EXPIRED", null, MESSAGE_EXPIRED, now));
        return AgentInboxClaim.priorResult(result(inbox));
    }

    private AgentInboxClaim reclaimProcessing(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox,
            String owner, long now, long leaseMillis, boolean sameClaimant) {
        requireStatus(delivery, "CONSUMED");
        validateConsumedDelivery(delivery);
        validateProcessing(inbox);
        if (now >= delivery.getExpiresAt()) {
            one(dao.transitionDelivery(message, delivery.getActiveAttempt(), delivery.getVersion(),
                    "CONSUMED", "EXPIRED", null, MESSAGE_EXPIRED, now));
            one(dao.expireProcessingInbox(message, inbox, MESSAGE_EXPIRED, now));
            return AgentInboxClaim.priorResult(resultAfter(
                    inbox, "EXPIRED", "EXPIRED", now, MESSAGE_EXPIRED, null));
        }
        if (now < inbox.getLeaseUntil()) {
            return AgentInboxClaim.inFlight(inbox.getLeaseUntil() - now);
        }
        long leaseUntil = leaseUntil(now, leaseMillis, delivery.getExpiresAt());
        if (sameClaimant) {
            one(dao.reclaimProcessingInbox(message, inbox, owner, leaseUntil, now));
        } else {
            one(dao.takeoverProcessingInbox(message, inbox, owner, leaseUntil,
                    delivery.getVersion(), now));
        }
        return AgentInboxClaim.acquired(token(message, inbox, owner, leaseUntil,
                inbox.getActiveAttempt() + 1, inbox.getVersion() + 1,
                delivery.getActiveAttempt(), delivery.getVersion(), delivery.getExpiresAt()));
    }

    private AgentInboxClaim reclaimRetry(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox,
            String owner, long now, long leaseMillis, boolean sameClaimant) {
        requireStatus(delivery, "RETRY");
        validateRetry(inbox, delivery);
        if (now >= delivery.getExpiresAt()) {
            one(dao.transitionDelivery(message, delivery.getActiveAttempt(), delivery.getVersion(),
                    "RETRY", "EXPIRED", null, MESSAGE_EXPIRED, now));
            one(dao.expireRetryInbox(message, inbox, MESSAGE_EXPIRED, now));
            return AgentInboxClaim.priorResult(resultAfter(
                    inbox, "EXPIRED", "EXPIRED", now, MESSAGE_EXPIRED, null));
        }
        if (now < inbox.getNextRetryAt()) {
            return AgentInboxClaim.inFlight(inbox.getNextRetryAt() - now);
        }
        long leaseUntil = leaseUntil(now, leaseMillis, delivery.getExpiresAt());
        one(dao.transitionDelivery(message, delivery.getActiveAttempt(), delivery.getVersion(),
                "RETRY", "CONSUMED", null, null, now));
        if (sameClaimant) {
            one(dao.reclaimRetryInbox(message, inbox, owner, leaseUntil,
                    delivery.getVersion() + 1, now));
        } else {
            one(dao.takeoverRetryInbox(message, inbox, owner, leaseUntil,
                    delivery.getVersion() + 1, now));
        }
        return AgentInboxClaim.acquired(token(message, inbox, owner, leaseUntil,
                inbox.getActiveAttempt() + 1, inbox.getVersion() + 1,
                delivery.getActiveAttempt(), delivery.getVersion() + 1, delivery.getExpiresAt()));
    }

    private void verifyExecutionLocked(AgentInboxClaimToken token, long now) {
        LockedClaim claim = lockClaim(token);
        validateUnacknowledgedTokenFence(token, claim.delivery(), claim.inbox());
        if (now >= token.leaseUntil() || now >= token.expiresAt()) throw denied();
    }

    private AgentInboxResult settleLocked(AgentInboxClaimToken token,
            AgentInboxDisposition disposition, long now) {
        LockedClaim claim = lockClaim(token);
        validateClaimTokenIdentity(token, claim.delivery(), claim.inbox());
        validateDispositionTiming(disposition, token, claim.delivery().getExpiresAt(), now);
        boolean automaticReplay = AgentCommandAutomaticReplayProvenance
                .validAutomaticImmediateParent(
                        claim.delivery(), claim.outbox(), claim.previous());
        if (disposition.type() == AgentInboxDisposition.Type.SENT
                && ACK_DELIVERY_STATUSES.contains(claim.delivery().getStatus())) {
            validateAcknowledgedProcessing(
                    token, claim.delivery(), claim.inbox(), automaticReplay);
            one(dao.settleInboxAfterAcknowledgement(token, claim.inbox(), now));
            return resultAfter(claim.inbox(), "PROCESSED", "SENT", now, null, null);
        }
        validateUnacknowledgedTokenFence(token, claim.delivery(), claim.inbox());
        Completion completion = completion(disposition);
        one(dao.settleDeliveryFromClaim(token, claim.inbox(),
                "CONSUMED", completion.deliveryStatus(),
                disposition.nextRetryAt(), disposition.errorCode(), now));
        one(dao.settleInbox(token, completion.inboxStatus(), completion.resultStatus(),
                disposition.nextRetryAt(), disposition.errorCode(), now));
        return resultAfter(claim.inbox(), completion.inboxStatus(),
                completion.resultStatus(), now, disposition.errorCode(),
                disposition.nextRetryAt());
    }

    private LockedSource lockSource(AgentInboxMessage message) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(message);
        if (delivery == null) throw denied();
        AgentOutboxEventEntity outbox = dao.lockOutbox(message);
        if (outbox == null) throw denied();
        List<AgentOutboxEventEntity> previous = dao.lockPrevious(message, delivery.getActiveAttempt());
        validateSource(message, delivery, outbox, previous);
        return new LockedSource(delivery, outbox, previous);
    }

    private LockedClaim lockClaim(AgentInboxClaimToken token) {
        AgentInboxMessage locator = message(token, new byte[]{1});
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(locator);
        if (delivery == null) throw denied();
        AgentOutboxEventEntity outbox = dao.lockOutbox(locator);
        if (outbox == null) throw denied();
        List<AgentOutboxEventEntity> previous = dao.lockPrevious(locator, delivery.getActiveAttempt());
        AgentConsumerInboxEntity inbox = dao.lockInbox(locator);
        if (inbox == null || inbox.getWirePayload() == null) throw denied();
        AgentInboxMessage message = message(token, inbox.getWirePayload());
        validateSource(message, delivery, outbox, previous);
        validateInboxIdentity(message, delivery, inbox);
        if (!sameClaimant(message, inbox)) throw identityConflict("CLAIMANT_IDENTITY_CONFLICT");
        return new LockedClaim(message, delivery, outbox, previous, inbox);
    }

    private void validateSource(AgentInboxMessage message, AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox, List<AgentOutboxEventEntity> previous) {
        if (!sameSource(message, delivery) || !sameSource(message, outbox)
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
                || !Objects.equals(delivery.getActiveAttempt(), outbox.getActiveAttempt())
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || delivery.getAttemptCount() == null
                || !Objects.equals(delivery.getAttemptCount(), delivery.getActiveAttempt())
                || delivery.getExpiresAt() == null || delivery.getExpiresAt() <= 0
                || delivery.getVersion() == null || delivery.getVersion() < 0
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || !"PUBLISHED".equals(outbox.getStatus())
                || !Arrays.equals(outbox.getWirePayload(), message.rawWireBytes())
                || !validHash(outbox.getWirePayload(), outbox.getWirePayloadHash())
                || !validHash(delivery.getCommandPayload(), delivery.getCommandPayloadHash())
                || !AgentCommandAutomaticReplayProvenance.validImmediateParent(
                        delivery, outbox, previous)) {
            throw identityConflict("SOURCE_IDENTITY_CONFLICT");
        }
    }

    private void validateInboxIdentity(AgentInboxMessage message, AgentCommandDeliveryEntity delivery,
            AgentConsumerInboxEntity inbox) {
        if (!sameInboxIdentity(message, inbox)
                || !Objects.equals(inbox.getConsumerName(), message.consumerName())
                || !Objects.equals(inbox.getMessageId(), message.messageId())
                || !Objects.equals(inbox.getEventId(), message.eventId())
                || !Objects.equals(inbox.getCommandId(), message.commandId())
                || !Objects.equals(inbox.getDeliveryId(), message.deliveryId())
                || !Objects.equals(inbox.getExpiresAt(), delivery.getExpiresAt())
                || !Arrays.equals(inbox.getWirePayload(), message.rawWireBytes())
                || !validHash(inbox.getWirePayload(), inbox.getWirePayloadHash())
                || inbox.getId() == null || inbox.getId() <= 0
                || inbox.getStatus() == null || !inbox.getStatus().matches("[A-Z_]{1,32}")
                || inbox.getAttemptCount() == null || inbox.getAttemptCount() < 0
                || inbox.getActiveAttempt() == null || inbox.getActiveAttempt() < 0
                || !Objects.equals(inbox.getAttemptCount(), inbox.getActiveAttempt())
                || inbox.getVersion() == null || inbox.getVersion() < 0) {
            throw identityConflict("INBOX_IDENTITY_CONFLICT");
        }
    }

    private void validateProcessing(AgentConsumerInboxEntity inbox) {
        if (inbox.getResultStatus() != null || inbox.getNextRetryAt() != null
                || inbox.getProcessedAt() != null || !exact(inbox.getLeaseOwner(), 100)
                || inbox.getLeaseUntil() == null || inbox.getLeaseUntil() <= 0
                || inbox.getLeaseUntil() > inbox.getExpiresAt()
                || inbox.getAttemptCount() < 1 || inbox.getActiveAttempt() < 1
                || inbox.getClaimDeliveryVersion() == null || inbox.getClaimDeliveryVersion() < 0
                || inbox.getClaimInboxVersion() == null || inbox.getClaimInboxVersion() < 0
                || inbox.getClaimStartedAt() == null || inbox.getClaimStartedAt() <= 0
                || inbox.getClaimStartedAt() >= inbox.getLeaseUntil()
                || inbox.getClaimStartedAt() >= inbox.getExpiresAt()
                || !validAckMetadataShape(inbox)) {
            throw denied();
        }
    }

    private void validateRetry(AgentConsumerInboxEntity inbox,
            AgentCommandDeliveryEntity delivery) {
        long expiresAt = delivery.getExpiresAt();
        if (!"RETRY".equals(inbox.getResultStatus()) || inbox.getNextRetryAt() == null
                || inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null
                || inbox.getNextRetryAt() <= inbox.getProcessedAt()
                || inbox.getNextRetryAt() >= expiresAt
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || !Objects.equals(delivery.getNextRetryAt(), inbox.getNextRetryAt())
                || !Objects.equals(delivery.getLastError(), inbox.getLastError())
                || inbox.getAttemptCount() < 1 || inbox.getActiveAttempt() < 1
                || inbox.getClaimDeliveryVersion() == null || inbox.getClaimDeliveryVersion() < 0
                || inbox.getClaimInboxVersion() == null || inbox.getClaimInboxVersion() < 0
                || inbox.getClaimStartedAt() == null || inbox.getClaimStartedAt() <= 0
                || inbox.getClaimStartedAt() > inbox.getProcessedAt()) {
            throw denied();
        }
        validateStoredDisposition(inbox, AgentInboxDisposition.Type.RETRY);
    }

    private void validateTerminal(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox,
            boolean automaticReplay) {
        AgentInboxDisposition.Type type = switch (inbox.getStatus()) {
            case "PROCESSED" -> AgentInboxDisposition.Type.SENT;
            case "WAITING_AGENT" -> AgentInboxDisposition.Type.WAITING_AGENT;
            case "FAILED" -> AgentInboxDisposition.Type.FAILED;
            case "EXPIRED" -> AgentInboxDisposition.Type.EXPIRED;
            case "DEAD" -> AgentInboxDisposition.Type.DEAD;
            default -> throw denied();
        };
        String expectedResult = type == AgentInboxDisposition.Type.SENT ? "SENT" : type.name();
        if (!expectedResult.equals(inbox.getResultStatus())) {
            throw denied();
        }
        if (inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null) {
            throw denied();
        }
        boolean unclaimedExpiry = inbox.getAttemptCount() == 0
                && inbox.getActiveAttempt() == 0
                && type == AgentInboxDisposition.Type.EXPIRED
                && inbox.getClaimDeliveryVersion() == null
                && inbox.getClaimInboxVersion() == null
                && inbox.getClaimStartedAt() == null
                && !hasAckMetadata(inbox);
        boolean claimed = inbox.getAttemptCount() >= 1 && inbox.getActiveAttempt() >= 1
                && inbox.getClaimDeliveryVersion() != null
                && inbox.getClaimDeliveryVersion() >= 0
                && inbox.getClaimInboxVersion() != null && inbox.getClaimInboxVersion() >= 0
                && inbox.getClaimStartedAt() != null && inbox.getClaimStartedAt() > 0
                && inbox.getClaimStartedAt() <= inbox.getProcessedAt();
        if (!unclaimedExpiry && !claimed) throw denied();
        validateStoredDisposition(inbox, type);
        if (inbox.getNextRetryAt() != null
                && (inbox.getNextRetryAt() <= inbox.getProcessedAt()
                    || inbox.getNextRetryAt() >= delivery.getExpiresAt())) {
            throw denied();
        }
        boolean deliveryMatches = switch (type) {
            case SENT -> Set.of("SENT", "RECEIVED", "STARTED", "SUCCEEDED",
                    "FAILED", "REJECTED").contains(delivery.getStatus());
            case WAITING_AGENT, FAILED, EXPIRED, DEAD -> type.name().equals(delivery.getStatus());
            default -> false;
        };
        validateTerminalDeliveryFields(delivery, inbox, type, automaticReplay);
        if (!deliveryMatches || (type == AgentInboxDisposition.Type.EXPIRED
                && inbox.getProcessedAt() < delivery.getExpiresAt())) {
            throw denied();
        }
    }

    private void validateConsumedDelivery(AgentCommandDeliveryEntity delivery) {
        if (delivery.getNextRetryAt() != null || delivery.getLeaseOwner() != null
                || delivery.getLeaseUntil() != null || delivery.getLastError() != null) {
            throw denied();
        }
    }

    private void validateTerminalDeliveryFields(AgentCommandDeliveryEntity delivery,
            AgentConsumerInboxEntity inbox, AgentInboxDisposition.Type type,
            boolean automaticReplay) {
        if (delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null) {
            throw denied();
        }
        if (type == AgentInboxDisposition.Type.SENT) {
            if (delivery.getNextRetryAt() != null) throw denied();
            if ("SENT".equals(delivery.getStatus()) && delivery.getLastError() != null) {
                throw denied();
            }
            if (ACK_DELIVERY_STATUSES.contains(delivery.getStatus())) {
                validateAcknowledgementEvidence(delivery, inbox, automaticReplay);
            } else if (hasAckMetadata(inbox)) {
                throw denied();
            }
            return;
        }
        if (!Objects.equals(delivery.getNextRetryAt(), inbox.getNextRetryAt())
                || !Objects.equals(delivery.getLastError(), inbox.getLastError())) {
            throw denied();
        }
    }

    private void validateStoredDisposition(AgentConsumerInboxEntity inbox,
            AgentInboxDisposition.Type type) {
        try {
            new AgentInboxDisposition(type, inbox.getNextRetryAt(), inbox.getLastError());
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
    }

    private void validateClaimTokenIdentity(AgentInboxClaimToken token,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox) {
        validateProcessing(inbox);
        if (!Objects.equals(delivery.getActiveAttempt(), token.deliveryActiveAttempt())
                || !Objects.equals(delivery.getActiveMessageId(), token.messageId())
                || !Objects.equals(inbox.getId(), token.inboxId())
                || !Objects.equals(inbox.getLeaseOwner(), token.leaseOwner())
                || !Objects.equals(inbox.getLeaseUntil(), token.leaseUntil())
                || !Objects.equals(inbox.getActiveAttempt(), token.activeAttempt())
                || !Objects.equals(inbox.getExpiresAt(), token.expiresAt())
                || !Objects.equals(inbox.getClaimDeliveryVersion(), token.deliveryVersion())
                || !Objects.equals(inbox.getClaimInboxVersion(), token.inboxVersion())) {
            throw denied();
        }
    }

    private void validateUnacknowledgedTokenFence(AgentInboxClaimToken token,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox) {
        validateClaimTokenIdentity(token, delivery, inbox);
        validateConsumedDelivery(delivery);
        if (!"CONSUMED".equals(delivery.getStatus())
                || !Objects.equals(delivery.getVersion(), token.deliveryVersion())
                || !Objects.equals(inbox.getVersion(), token.inboxVersion())
                || hasAckMetadata(inbox)) {
            throw denied();
        }
    }

    private void validateAcknowledgedProcessing(AgentInboxClaimToken token,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox,
            boolean automaticReplay) {
        validateClaimTokenIdentity(token, delivery, inbox);
        validateAcknowledgementEvidence(delivery, inbox, automaticReplay);
    }

    private AgentInboxClaim recoverAcknowledged(AgentInboxMessage message,
            AgentCommandDeliveryEntity delivery, AgentConsumerInboxEntity inbox, long now,
            boolean automaticReplay) {
        validateProcessing(inbox);
        validateAcknowledgementEvidence(delivery, inbox, automaticReplay);
        AgentInboxClaimToken token = persistedToken(inbox, delivery, inbox.getLeaseOwner(), inbox.getLeaseUntil(),
                inbox.getActiveAttempt(), inbox.getClaimInboxVersion(), delivery.getActiveAttempt(),
                inbox.getClaimDeliveryVersion(), delivery.getExpiresAt());
        one(dao.settleInboxAfterAcknowledgement(token, inbox, now));
        return AgentInboxClaim.priorResult(resultAfter(
                inbox, "PROCESSED", "SENT", now, null, null));
    }

    private void validateAcknowledgementEvidence(AgentCommandDeliveryEntity delivery,
            AgentConsumerInboxEntity inbox, boolean automaticReplay) {
        boolean settledSent = "PROCESSED".equals(inbox.getStatus())
                && "SENT".equals(inbox.getResultStatus());
        if (!hasAckMetadata(inbox) || !ACK_DELIVERY_STATUSES.contains(delivery.getStatus())
                || !Objects.equals(delivery.getStatus(), inbox.getAckStatus())
                || !Objects.equals(delivery.getVersion(), inbox.getAckDeliveryVersion())
                || !Objects.equals(delivery.getTargetAgentId(), inbox.getAckAgentId())
                || !Objects.equals(delivery.getActiveMessageId(), inbox.getMessageId())
                || inbox.getClaimStartedAt() == null
                || inbox.getAckAt() <= 0 || inbox.getAckAt() >= inbox.getExpiresAt()
                || inbox.getAckAt() < inbox.getClaimStartedAt()
                || (!settledSent && (inbox.getLeaseUntil() == null
                    || inbox.getAckAt() >= inbox.getLeaseUntil()))) {
            throw denied();
        }
        long deliveryAdvances = delivery.getVersion() - inbox.getClaimDeliveryVersion();
        long inboxAdvances = inbox.getVersion() - inbox.getClaimInboxVersion();
        long acknowledgementAdvances;
        if (!settledSent) {
            if (inboxAdvances != deliveryAdvances) throw denied();
            acknowledgementAdvances = deliveryAdvances;
        } else if (inboxAdvances == deliveryAdvances + 1L) {
            acknowledgementAdvances = deliveryAdvances;
        } else if (inboxAdvances == deliveryAdvances) {
            acknowledgementAdvances = deliveryAdvances - 1L;
        } else {
            throw denied();
        }
        if (!validAckEvidenceStep(delivery.getStatus(), acknowledgementAdvances,
                automaticReplay) || !validAckLastError(delivery)) throw denied();
    }

    private boolean validAckEvidenceStep(String status, long advances,
            boolean automaticReplay) {
        if (automaticReplay) {
            return advances == 1L && Set.of("SUCCEEDED", "FAILED", "REJECTED").contains(status);
        }
        return switch (status) {
            case "RECEIVED" -> advances == 1L;
            case "STARTED" -> advances == 2L;
            case "SUCCEEDED", "FAILED" -> advances == 3L;
            case "REJECTED" -> advances >= 1L && advances <= 3L;
            default -> false;
        };
    }
    private boolean validAckLastError(AgentCommandDeliveryEntity delivery) {
        return switch (delivery.getStatus()) {
            case "FAILED" -> AGENT_REPORTED_FAILED.equals(delivery.getLastError());
            case "REJECTED" -> AGENT_REPORTED_REJECTED.equals(delivery.getLastError());
            default -> delivery.getLastError() == null;
        };
    }

    private boolean validAckMetadataShape(AgentConsumerInboxEntity inbox) {
        int present = 0;
        present += inbox.getAckDeliveryVersion() == null ? 0 : 1;
        present += inbox.getAckMessageId() == null ? 0 : 1;
        present += inbox.getAckStatus() == null ? 0 : 1;
        present += inbox.getAckAt() == null ? 0 : 1;
        present += inbox.getAckAgentId() == null ? 0 : 1;
        present += inbox.getAckRuntimeInstallationId() == null ? 0 : 1;
        present += inbox.getAckRuntimeInstanceId() == null ? 0 : 1;
        return present == 0 || present == 7;
    }

    private boolean hasAckMetadata(AgentConsumerInboxEntity inbox) {
        return validAckMetadataShape(inbox) && inbox.getAckDeliveryVersion() != null
                && exact(inbox.getAckMessageId(), 100) && exact(inbox.getAckStatus(), 32)
                && exact(inbox.getAckAgentId(), 100)
                && exact(inbox.getAckRuntimeInstallationId(), 100)
                && exact(inbox.getAckRuntimeInstanceId(), 100);
    }

    private void validateDispositionTiming(AgentInboxDisposition disposition,
            AgentInboxClaimToken token, long expiresAt, long now) {
        if (token.expiresAt() != expiresAt) throw denied();
        if (disposition.type() == AgentInboxDisposition.Type.EXPIRED) {
            if (now < expiresAt || token.leaseUntil() != expiresAt) throw denied();
        } else if (now >= token.leaseUntil() || now >= expiresAt) {
            throw denied();
        }
        if (disposition.nextRetryAt() != null
                && (disposition.nextRetryAt() <= now
                    || disposition.nextRetryAt() >= expiresAt)) {
            throw denied();
        }
    }

    private Completion completion(AgentInboxDisposition disposition) {
        return switch (disposition.type()) {
            case SENT -> new Completion("PROCESSED", "SENT", "SENT");
            case WAITING_AGENT -> new Completion("WAITING_AGENT", "WAITING_AGENT", "WAITING_AGENT");
            case RETRY -> new Completion("RETRY", "RETRY", "RETRY");
            case FAILED -> new Completion("FAILED", "FAILED", "FAILED");
            case EXPIRED -> new Completion("EXPIRED", "EXPIRED", "EXPIRED");
            case DEAD -> new Completion("DEAD", "DEAD", "DEAD");
        };
    }

    private AgentConsumerInboxEntity newInbox(AgentInboxMessage message, long expiresAt,
            String status, String resultStatus, int attemptCount, String leaseOwner,
            Long leaseUntil, int activeAttempt, Long processedAt, String lastError,
            Long claimDeliveryVersion, Long claimInboxVersion, long now) {
        AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                .setProtocolVersion(2)
                .setDataDomainId(message.dataDomainId())
                .setExecutionPoolId(message.executionPoolId())
                .setAdmissionEpoch(message.admissionEpoch())
                .setProducerDeploymentId(message.producerDeploymentId())
                .setModuleId(message.moduleId())
                .setMigrationEpoch(message.migrationEpoch())
                .setClaimantDeploymentId(message.claimantDeploymentId())
                .setClaimantWorkerGeneration(message.claimantWorkerGeneration())
                .setConsumerName(message.consumerName())
                .setMessageId(message.messageId())
                .setEventId(message.eventId())
                .setCommandId(message.commandId())
                .setDeliveryId(message.deliveryId())
                .setWirePayload(message.rawWireBytes())
                .setWirePayloadHash(AgentCommandAmqpContract.sha256(message.rawWireBytes()))
                .setStatus(status)
                .setResultStatus(resultStatus)
                .setAttemptCount(attemptCount)
                .setLeaseOwner(leaseOwner)
                .setLeaseUntil(leaseUntil)
                .setActiveAttempt(activeAttempt)
                .setExpiresAt(expiresAt)
                .setProcessedAt(processedAt)
                .setLastError(lastError)
                .setVersion(0L)
                .setClaimDeliveryVersion(claimDeliveryVersion)
                .setClaimInboxVersion(claimInboxVersion)
                .setClaimStartedAt(claimDeliveryVersion == null ? null : now);
        inbox.setTenantId(message.tenantId());
        inbox.setClientId(message.clientId());
        inbox.setCreateTime(now);
        inbox.setUpdateTime(now);
        return inbox;
    }

    private AgentInboxClaimToken token(AgentInboxMessage message, AgentConsumerInboxEntity inbox, String owner,
            long leaseUntil, int activeAttempt, long inboxVersion,
            int deliveryActiveAttempt, long deliveryVersion, long expiresAt) {
        return new AgentInboxClaimToken(inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(),
                inbox.getDataDomainId(), inbox.getExecutionPoolId(), inbox.getAdmissionEpoch(),
                inbox.getProducerDeploymentId(), inbox.getModuleId(), inbox.getMigrationEpoch(), 2,
                message.claimantDeploymentId(), message.claimantWorkerGeneration(), inbox.getClientId(),
                inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(),
                message.taskId(), message.targetAgentId(), message.commandType(), inbox.getDeliveryId(),
                owner, leaseUntil, activeAttempt, inboxVersion,
                deliveryActiveAttempt, deliveryVersion, expiresAt);
    }

    private AgentInboxClaimToken persistedToken(AgentConsumerInboxEntity inbox,
            AgentCommandDeliveryEntity delivery, String owner,
            long leaseUntil, int activeAttempt, long inboxVersion,
            int deliveryActiveAttempt, long deliveryVersion, long expiresAt) {
        return new AgentInboxClaimToken(inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(),
                inbox.getDataDomainId(), inbox.getExecutionPoolId(), inbox.getAdmissionEpoch(),
                inbox.getProducerDeploymentId(), inbox.getModuleId(), inbox.getMigrationEpoch(), 2,
                inbox.getClaimantDeploymentId(), inbox.getClaimantWorkerGeneration(), inbox.getClientId(),
                inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(),
                delivery.getTaskId(), delivery.getTargetAgentId(), delivery.getCommandType(), inbox.getDeliveryId(),
                owner, leaseUntil, activeAttempt, inboxVersion,
                deliveryActiveAttempt, deliveryVersion, expiresAt);
    }

    private boolean sameSource(AgentInboxMessage message, Object row) {
        Integer protocol;
        String tenant;
        String client;
        String domain;
        String pool;
        String producer;
        String module;
        Long admission;
        Long migration;
        if (row instanceof AgentCommandDeliveryEntity value) {
            protocol = value.getProtocolVersion(); tenant = value.getTenantId(); client = value.getClientId();
            domain = value.getDataDomainId(); pool = value.getExecutionPoolId();
            producer = value.getProducerDeploymentId(); module = value.getModuleId();
            admission = value.getAdmissionEpoch(); migration = value.getMigrationEpoch();
            if (value.getClaimedDeploymentId() != null
                    || value.getClaimedWorkerGeneration() != null) return false;
        } else if (row instanceof AgentOutboxEventEntity value) {
            protocol = value.getProtocolVersion(); tenant = value.getTenantId(); client = value.getClientId();
            domain = value.getDataDomainId(); pool = value.getExecutionPoolId();
            producer = value.getProducerDeploymentId(); module = value.getModuleId();
            admission = value.getAdmissionEpoch(); migration = value.getMigrationEpoch();
            if (value.getClaimedDeploymentId() != null
                    || value.getClaimedWorkerGeneration() != null) return false;
        } else {
            return false;
        }
        return Objects.equals(protocol, 2)
                && Objects.equals(tenant, message.tenantId())
                && Objects.equals(client, message.clientId())
                && Objects.equals(domain, message.dataDomainId())
                && Objects.equals(pool, message.executionPoolId())
                && Objects.equals(producer, message.producerDeploymentId())
                && Objects.equals(module, message.moduleId())
                && Objects.equals(admission, message.admissionEpoch())
                && Objects.equals(migration, message.migrationEpoch());
    }

    private boolean sameInboxIdentity(AgentInboxMessage message, AgentConsumerInboxEntity value) {
        return Objects.equals(value.getProtocolVersion(), 2)
                && Objects.equals(value.getTenantId(), message.tenantId())
                && Objects.equals(value.getClientId(), message.clientId())
                && Objects.equals(value.getDataDomainId(), message.dataDomainId())
                && Objects.equals(value.getExecutionPoolId(), message.executionPoolId())
                && Objects.equals(value.getProducerDeploymentId(), message.producerDeploymentId())
                && Objects.equals(value.getModuleId(), message.moduleId())
                && Objects.equals(value.getAdmissionEpoch(), message.admissionEpoch())
                && Objects.equals(value.getMigrationEpoch(), message.migrationEpoch())
                && exact(value.getClaimantDeploymentId(), 100)
                && value.getClaimantWorkerGeneration() != null
                && value.getClaimantWorkerGeneration() >= 0;
    }

    private boolean sameClaimant(AgentInboxMessage message, AgentConsumerInboxEntity inbox) {
        return Objects.equals(inbox.getClaimantDeploymentId(), message.claimantDeploymentId())
                && Objects.equals(inbox.getClaimantWorkerGeneration(),
                        message.claimantWorkerGeneration());
    }

    private void validateMessage(AgentInboxMessage message, String owner,
            long now, long leaseMillis) {
        if (message == null || message.protocolVersion() != 2
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2.equals(message.consumerName())
                || !canonical(message.tenantId(), "0|[1-9][0-9]{0,49}")
                || !canonical(message.dataDomainId(), "[a-z][a-z0-9-]{0,63}")
                || !canonical(message.executionPoolId(), "[a-z0-9-]{1,100}")
                || !exact(message.producerDeploymentId(), 100)
                || message.admissionEpoch() < 0
                || !canonical(message.moduleId(), "[a-z][a-z0-9_-]{0,63}")
                || message.migrationEpoch() < 0
                || !exact(message.claimantDeploymentId(), 100)
                || message.claimantWorkerGeneration() < 0
                || !exact(message.clientId(), 50)
                || !exact(message.messageId(), 100)
                || !exact(message.eventId(), 100)
                || !exact(message.commandId(), 100)
                || !exact(message.taskId(), 100)
                || !exact(message.targetAgentId(), 100)
                || !canonical(message.commandType(), "[A-Z][A-Z0-9_]{0,63}")
                || message.deliveryId() <= 0
                || !exact(owner, 100)
                || now <= 0 || leaseMillis <= 0 || leaseMillis > MAX_LEASE_MILLIS
                || message.rawWireBytes() == null || message.rawWireBytes().length == 0
                || message.rawWireBytes().length > AgentCommandAmqpContract.MAX_WIRE_BYTES) {
            throw identityConflict("MESSAGE_IDENTITY_INVALID");
        }
    }

    private void validateToken(AgentInboxClaimToken token) {
        if (token == null || token.protocolVersion() != 2
                || !AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2.equals(token.consumerName())
                || token.inboxId() <= 0 || token.deliveryId() <= 0
                || !canonical(token.tenantId(), "0|[1-9][0-9]{0,49}")
                || !canonical(token.dataDomainId(), "[a-z][a-z0-9-]{0,63}")
                || !canonical(token.executionPoolId(), "[a-z0-9-]{1,100}")
                || !exact(token.producerDeploymentId(), 100) || token.admissionEpoch() < 0
                || !canonical(token.moduleId(), "[a-z][a-z0-9_-]{0,63}")
                || token.migrationEpoch() < 0 || !exact(token.claimantDeploymentId(), 100)
                || token.claimantWorkerGeneration() < 0 || !exact(token.clientId(), 50)
                || !exact(token.messageId(), 100) || !exact(token.eventId(), 100)
                || !exact(token.commandId(), 100) || !exact(token.taskId(), 100)
                || !exact(token.targetAgentId(), 100)
                || !canonical(token.commandType(), "[A-Z][A-Z0-9_]{0,63}")
                || !exact(token.leaseOwner(), 100)
                || token.leaseUntil() <= 0 || token.activeAttempt() <= 0
                || token.inboxVersion() < 0 || token.deliveryActiveAttempt() <= 0
                || token.deliveryVersion() < 0 || token.expiresAt() <= 0
                || token.leaseUntil() > token.expiresAt()) {
            throw identityConflict("CLAIM_TOKEN_IDENTITY_INVALID");
        }
    }

    private void exactAuthority(AgentInboxMessage message) {
        if (!authority.tenantId().value().equals(message.tenantId())
                || !authority.dataDomainId().value().equals(message.dataDomainId())
                || !authority.executionPoolId().equals(message.executionPoolId())
                || authority.admissionEpoch() != message.admissionEpoch()
                || !authority.moduleId().equals(message.moduleId())
                || authority.migrationEpoch() != message.migrationEpoch()
                || !authority.deploymentId().equals(message.claimantDeploymentId())
                || authority.workerGeneration() != message.claimantWorkerGeneration()) {
            throw identityConflict("WORKER_SCOPE_CONFLICT");
        }
    }

    private void exactAuthority(AgentInboxClaimToken token) {
        exactAuthority(message(token, new byte[]{1}));
    }

    private AgentInboxMessage message(AgentInboxClaimToken token, byte[] wire) {
        return new AgentInboxMessage(token.consumerName(), token.tenantId(), token.dataDomainId(),
                token.executionPoolId(), token.admissionEpoch(), token.producerDeploymentId(),
                token.moduleId(), token.migrationEpoch(), token.protocolVersion(),
                token.claimantDeploymentId(), token.claimantWorkerGeneration(), token.clientId(),
                token.messageId(), token.eventId(), token.commandId(), token.taskId(),
                token.targetAgentId(), token.commandType(), token.deliveryId(), wire);
    }

    private AgentInboxResult result(AgentConsumerInboxEntity inbox) {
        return new AgentInboxResult(inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(),
                inbox.getClientId(), inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(),
                inbox.getDeliveryId(), inbox.getStatus(), inbox.getResultStatus(),
                inbox.getProcessedAt(), inbox.getLastError(), inbox.getNextRetryAt());
    }

    private AgentInboxResult resultAfter(AgentConsumerInboxEntity inbox, String status,
            String result, long processedAt, String error, Long nextRetryAt) {
        return new AgentInboxResult(inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(),
                inbox.getClientId(), inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(),
                inbox.getDeliveryId(), status, result, processedAt, error, nextRetryAt);
    }

    private long leaseUntil(long now, long leaseMillis, long expiresAt) {
        long requested;
        try {
            requested = Math.addExact(now, leaseMillis);
        } catch (ArithmeticException overflow) {
            throw denied();
        }
        long lease = Math.min(requested, expiresAt);
        if (lease <= now) throw denied();
        return lease;
    }

    private static boolean validHash(byte[] bytes, byte[] hash) {
        return bytes != null && hash != null && hash.length == 32
                && MessageDigest.isEqual(AgentCommandAmqpContract.sha256(bytes), hash);
    }

    private static boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean canonical(String value, String regex) {
        return exact(value, 100) && value.matches(regex);
    }

    private static void requireDomain(TenantModuleAdmissionGuard.Mode mode) {
        if (mode != TenantModuleAdmissionGuard.Mode.DOMAIN) throw denied();
    }

    private static void requireNoAmbientTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) throw denied();
    }

    private static void requireStatus(AgentCommandDeliveryEntity delivery, String status) {
        if (!status.equals(delivery.getStatus())) throw denied();
    }

    private static void one(int rows) {
        if (rows != 1) throw denied();
    }

    private static AgentInboxIdentityConflictException identityConflict(String reason) {
        return new AgentInboxIdentityConflictException(reason);
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Agent command consumer v2 admission denied");
    }

    private record Completion(String inboxStatus, String resultStatus, String deliveryStatus) {}
    private record LockedSource(AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox, List<AgentOutboxEventEntity> previous) {}
    private record LockedClaim(AgentInboxMessage message, AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox, List<AgentOutboxEventEntity> previous,
            AgentConsumerInboxEntity inbox) {}
}
