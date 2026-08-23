package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.service.AgentOutboxRelayService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D03 fail-closed relay database state machine. Rabbit network calls are intentionally absent. */
public final class AgentOutboxRelayServiceImpl implements AgentOutboxRelayService {
    public static final String UNADMITTED_PENDING = "UNADMITTED_PENDING_V1";
    public static final String STALE_DELIVERY_FENCE = "STALE_DELIVERY_FENCE";
    public static final String MESSAGE_EXPIRED = "MESSAGE_EXPIRED";
    public static final String ATTEMPT_EXHAUSTED = "ATTEMPT_EXHAUSTED";

    private static final Set<String> TERMINAL_OUTBOX =
            Set.of("PUBLISHED", "FAILED", "EXPIRED", "DEAD");
    private final AgentOutboxRelayDao dao;
    private final AgentRabbitSafetyGate gate;
    private final AgentRabbitTopologyManifest manifest;
    private final AgentRabbitTopologyReadiness readiness;
    private final AgentOutboxRelaySettings settings;
    private final TransactionTemplate claimTransaction;
    private final TransactionTemplate settleTransaction;

    public AgentOutboxRelayServiceImpl(
            AgentOutboxRelayDao dao,
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyManifest manifest,
            AgentRabbitTopologyReadiness readiness,
            AgentOutboxRelaySettings settings,
            PlatformTransactionManager transactionManager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.settings = Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(transactionManager, "transactionManager");
        claimTransaction = requiresNew(transactionManager);
        settleTransaction = requiresNew(transactionManager);
    }

    @Override
    public List<AgentOutboxCandidate> discover(long now, int requested) {
        if (!globallyPublishable() || requested <= 0) {
            return List.of();
        }
        int limit = settings.discoveryLimit(requested);
        if (limit == 0) {
            return List.of();
        }
        List<AgentOutboxCandidate> merged = new ArrayList<>(limit * 2);
        List<AgentOutboxCandidate> due = dao.selectDueCandidates(now, limit);
        List<AgentOutboxCandidate> stale = dao.selectStaleCandidates(now, limit);
        if (due != null) merged.addAll(due);
        if (stale != null) merged.addAll(stale);
        merged.sort(Comparator.comparingLong(AgentOutboxCandidate::eligibleAt)
                .thenComparingLong(AgentOutboxCandidate::outboxId));
        Set<Long> seen = new HashSet<>();
        List<AgentOutboxCandidate> result = new ArrayList<>(limit);
        for (AgentOutboxCandidate candidate : merged) {
            if (candidate != null && candidate.outboxId() > 0 && candidate.deliveryId() > 0
                    && validExact(candidate.tenantId(), 50)
                    && validExact(candidate.clientId(), 50)
                    && seen.add(candidate.outboxId())) {
                result.add(candidate);
                if (result.size() == limit) break;
            }
        }
        return List.copyOf(result);
    }

    @Override
    public AgentOutboxClaim claim(
            AgentOutboxCandidate candidate, String leaseOwner, long now) {
        if (!globallyPublishable()) {
            return AgentOutboxClaim.disabled();
        }
        if (candidate == null || candidate.outboxId() <= 0 || candidate.deliveryId() <= 0
                || !validExact(candidate.tenantId(), 50)
                || !validExact(candidate.clientId(), 50)
                || !validExact(leaseOwner, 100) || now <= 0) {
            return AgentOutboxClaim.skipped();
        }
        AgentOutboxClaim result = claimTransaction.execute(
                ignored -> claimInTransaction(candidate, leaseOwner, now));
        return result == null ? AgentOutboxClaim.skipped() : result;
    }

    @Override
    public AgentOutboxSettleResult settle(
            AgentOutboxClaimToken token, AgentRabbitPublishResult result, long now) {
        if (token == null || result == null || now <= 0) {
            return AgentOutboxSettleResult.STALE;
        }
        AgentOutboxSettleResult settled = settleTransaction.execute(
                ignored -> settleInTransaction(token, result, now));
        return settled == null ? AgentOutboxSettleResult.STALE : settled;
    }

    private AgentOutboxClaim claimInTransaction(
            AgentOutboxCandidate candidate, String leaseOwner, long now) {
        if (!globallyPublishable()) {
            return AgentOutboxClaim.disabled();
        }
        // Frozen lock order: delivery -> outbox. A missing delivery is still an attempted first lock.
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                candidate.tenantId(), candidate.clientId(), candidate.deliveryId());
        AgentOutboxEventEntity outbox = dao.lockOutbox(
                candidate.tenantId(), candidate.clientId(), candidate.outboxId());
        if (outbox == null || !candidateMatches(candidate, outbox)
                || isTerminalOutbox(outbox.getStatus())) {
            return AgentOutboxClaim.skipped();
        }
        if (delivery == null) {
            disposeOutbox(outbox, "DEAD", null, "NONE", null, null,
                    "NONE", null, null, null, null, "DELIVERY_NOT_FOUND", now);
            return AgentOutboxClaim.skipped();
        }

        boolean pending = "PENDING".equals(outbox.getStatus());
        if (pending && (!AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER.equals(
                outbox.getLastError())
                || !AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER.equals(
                delivery.getLastError()))) {
            disposeBoth(delivery, outbox, "DEAD", null, UNADMITTED_PENDING, now);
            return AgentOutboxClaim.skipped();
        }
        if (!gate.allowsDispatch(outbox.getTenantId(), outbox.getClientId())) {
            disposeBoth(delivery, outbox, "DEAD", null,
                    AgentCommandTransportWriterImpl.DISPATCH_SCOPE_MARKER, now);
            return AgentOutboxClaim.skipped();
        }
        // Discovery is an unlocked hint. A competing claimant may have moved a due/stale row.
        if (("RETRY".equals(outbox.getStatus()) && outbox.getNextRetryAt() != null
                && outbox.getNextRetryAt() > now)
                || ("CLAIMED".equals(outbox.getStatus()) && outbox.getLeaseUntil() != null
                && outbox.getLeaseUntil() > now)) {
            return AgentOutboxClaim.skipped();
        }

        String corruption = validateCommon(delivery, outbox);
        if (corruption == null) {
            corruption = validateLane(delivery, outbox, now);
        }
        if (corruption != null) {
            disposeBoth(delivery, outbox, "DEAD", null, corruption, now);
            return AgentOutboxClaim.skipped();
        }
        if (now >= outbox.getExpiresAt()) {
            disposeBoth(delivery, outbox, "EXPIRED", null, MESSAGE_EXPIRED, now);
            return AgentOutboxClaim.skipped();
        }
        if (outbox.getAttemptCount() >= settings.maxAttempts()) {
            disposeBoth(delivery, outbox, "FAILED", null, ATTEMPT_EXHAUSTED, now);
            return AgentOutboxClaim.skipped();
        }

        long leaseUntil;
        try {
            leaseUntil = Math.addExact(now, settings.leaseMillis());
        } catch (ArithmeticException overflow) {
            disposeBoth(delivery, outbox, "DEAD", null, "LEASE_TIME_OVERFLOW", now);
            return AgentOutboxClaim.skipped();
        }
        requireOne(dao.claimDelivery(delivery, leaseOwner, leaseUntil, null, now),
                "delivery claim CAS");
        requireOne(dao.claimOutbox(outbox, leaseOwner, leaseUntil, null, now),
                "outbox claim CAS");

        return AgentOutboxClaim.acquired(new AgentOutboxClaimToken(
                outbox.getId(), delivery.getId(), outbox.getTenantId(), outbox.getClientId(),
                outbox.getEventId(), outbox.getMessageId(), outbox.getCommandId(),
                delivery.getTaskId(), delivery.getTargetAgentId(), delivery.getCommandType(),
                outbox.getDestination(), outbox.getRoutingKey(), outbox.getWirePayload(),
                outbox.getWirePayloadHash(), outbox.getExpiresAt(), leaseOwner, leaseUntil,
                outbox.getActiveAttempt() + 1, outbox.getVersion() + 1,
                delivery.getStatus(), delivery.getActiveMessageId(),
                delivery.getActiveAttempt(), delivery.getVersion() + 1));
    }

    private AgentOutboxSettleResult settleInTransaction(
            AgentOutboxClaimToken token, AgentRabbitPublishResult result, long now) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                token.tenantId(), token.clientId(), token.deliveryId());
        AgentOutboxEventEntity outbox = dao.lockOutbox(
                token.tenantId(), token.clientId(), token.outboxId());
        if (!tokenMatchesOutbox(token, outbox)) {
            return AgentOutboxSettleResult.STALE;
        }
        boolean activeDelivery = tokenMatchesDelivery(token, delivery);

        if (now >= token.expiresAt()) {
            if (activeDelivery) {
                disposeDelivery(delivery, "EXPIRED", null, MESSAGE_EXPIRED, now);
            }
            disposeOutbox(outbox, "EXPIRED", null,
                    result.confirmStatus(), confirmedAt(result, now), result.errorCode(),
                    result.returnStatus(), returnedAt(result, now), result.returnReplyCode(),
                    result.returnReplyText(), null, MESSAGE_EXPIRED, now);
            return AgentOutboxSettleResult.EXPIRED;
        }

        if (result.type() == AgentRabbitPublishResult.Type.ACK) {
            if (activeDelivery) {
                disposeDelivery(delivery, "PUBLISHED", null, null, now);
            }
            disposeOutbox(outbox, "PUBLISHED", null,
                    "ACK", now, null, "NOT_RETURNED", null,
                    null, null, now, null, now);
            return AgentOutboxSettleResult.PUBLISHED;
        }

        if (!activeDelivery) {
            disposeOutbox(outbox, "DEAD", null,
                    result.confirmStatus(), confirmedAt(result, now), result.errorCode(),
                    result.returnStatus(), returnedAt(result, now), result.returnReplyCode(),
                    result.returnReplyText(), null, STALE_DELIVERY_FENCE, now);
            return AgentOutboxSettleResult.DEAD;
        }
        if (outbox.getAttemptCount() >= settings.maxAttempts()) {
            disposeDelivery(delivery, "FAILED", null, ATTEMPT_EXHAUSTED, now);
            disposeOutbox(outbox, "FAILED", null,
                    result.confirmStatus(), confirmedAt(result, now), result.errorCode(),
                    result.returnStatus(), returnedAt(result, now), result.returnReplyCode(),
                    result.returnReplyText(), null, ATTEMPT_EXHAUSTED, now);
            return AgentOutboxSettleResult.FAILED;
        }

        long nextRetryAt = settings.nextRetryAt(
                outbox.getEventId(), outbox.getActiveAttempt(), outbox.getAttemptCount(),
                now, outbox.getExpiresAt());
        disposeDelivery(delivery, "RETRY", nextRetryAt, result.errorCode(), now);
        disposeOutbox(outbox, "RETRY", nextRetryAt,
                result.confirmStatus(), confirmedAt(result, now), result.errorCode(),
                result.returnStatus(), returnedAt(result, now), result.returnReplyCode(),
                result.returnReplyText(), null, result.errorCode(), now);
        return AgentOutboxSettleResult.RETRY;
    }

    private boolean globallyPublishable() {
        AgentRabbitActivationState state = gate.state();
        if (state != AgentRabbitActivationState.DISPATCH_CANARY
                && state != AgentRabbitActivationState.DISPATCH_SCOPED) {
            return false;
        }
        AgentRabbitTopologyReadiness.Snapshot snapshot = readiness.snapshot();
        return snapshot.canonicalTopologyReady()
                && manifest.sha256().equals(snapshot.manifestSha256());
    }

    private String validateCommon(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        if (delivery.getId() == null || outbox.getId() == null
                || !Objects.equals(delivery.getId(), outbox.getDeliveryId())
                || !Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                || !Objects.equals(delivery.getClientId(), outbox.getClientId())
                || !Objects.equals(delivery.getCommandId(), outbox.getCommandId())
                || !Objects.equals(delivery.getActiveMessageId(), outbox.getMessageId())
                || !Objects.equals(delivery.getTaskId(), outbox.getAggregateId())
                || !"task".equals(outbox.getAggregateType())) {
            return "SOURCE_IDENTITY_DRIFT";
        }
        if (!validExact(outbox.getEventId(), 100)
                || !validExact(outbox.getMessageId(), 100)
                || !validExact(outbox.getCommandId(), 100)
                || !validExact(delivery.getTaskId(), 100)
                || !validExact(delivery.getTargetAgentId(), 100)
                || !validExact(delivery.getCommandType(), 64)
                || delivery.getExpiresAt() == null || delivery.getExpiresAt() <= 0
                || !Objects.equals(delivery.getExpiresAt(), outbox.getExpiresAt())) {
            return "SOURCE_IDENTITY_CORRUPT";
        }
        if (delivery.getVersion() == null || delivery.getVersion() < 0
                || outbox.getVersion() == null || outbox.getVersion() < 0
                || delivery.getAttemptCount() == null || delivery.getAttemptCount() < 1
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() < 1
                || outbox.getAttemptCount() == null || outbox.getAttemptCount() < 0
                || outbox.getActiveAttempt() == null || outbox.getActiveAttempt() < 1) {
            return "SOURCE_FENCE_CORRUPT";
        }
        if (hasReplayProvenance(delivery) || hasReplayProvenance(outbox)) {
            return "UNSUPPORTED_REPLAY_PROVENANCE";
        }
        if (!storedHashMatches(delivery.getCommandPayload(), delivery.getCommandPayloadHash())) {
            return "DELIVERY_STORED_HASH_CORRUPT";
        }
        byte[] wire = outbox.getWirePayload();
        if (wire == null || wire.length == 0 || wire.length > settings.maxWireBytes()) {
            return "WIRE_PAYLOAD_SIZE_INVALID";
        }
        if (!storedHashMatches(wire, outbox.getWirePayloadHash())) {
            return "OUTBOX_STORED_HASH_CORRUPT";
        }
        if (!manifest.allowsCommandPublish(outbox.getDestination(), outbox.getRoutingKey())) {
            return "DESTINATION_POLICY_REJECTED";
        }
        try {
            AgentCommandAmqpContract.validate(new AgentConfirmedPublishRequest(
                    outbox.getDestination(), outbox.getRoutingKey(), wire,
                    outbox.getWirePayloadHash(), outbox.getMessageId(), outbox.getEventId(),
                    outbox.getDeliveryId(), outbox.getCommandId(), outbox.getTenantId(),
                    outbox.getClientId(), delivery.getTaskId(), delivery.getTargetAgentId(),
                    delivery.getCommandType(), delivery.getActiveAttempt(), outbox.getExpiresAt(),
                    manifest.sha256(), AgentCommandAmqpContract.INITIAL_SOURCE_SETTLEMENT_RETRY));
            return null;
        } catch (IllegalArgumentException ignored) {
            return "WIRE_IDENTITY_DRIFT";
        }
    }

    private String validateLane(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox, long now) {
        String status = outbox.getStatus();
        if ("PENDING".equals(status)) return validatePendingShape(delivery, outbox);
        if ("RETRY".equals(status)) return validateRetryShape(delivery, outbox, now);
        if ("CLAIMED".equals(status)) return validateStaleClaimShape(delivery, outbox, now);
        return "OUTBOX_STATUS_CORRUPT";
    }

    private String validatePendingShape(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        if (!"PENDING".equals(delivery.getStatus())
                || delivery.getNextRetryAt() != null
                || outbox.getAttemptCount() != 0 || outbox.getActiveAttempt() != 1
                || outbox.getNextRetryAt() != null
                || outbox.getLeaseOwner() != null || outbox.getLeaseUntil() != null
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || !"NONE".equals(outbox.getPublisherConfirmStatus())
                || !"NONE".equals(outbox.getMandatoryReturnStatus())
                || anyPublishDisposition(outbox)) {
            return "PENDING_SHAPE_CORRUPT";
        }
        return null;
    }

    private String validateRetryShape(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox, long now) {
        if (!"RETRY".equals(delivery.getStatus())
                || outbox.getAttemptCount() < 1
                || outbox.getActiveAttempt() != outbox.getAttemptCount() + 1
                || outbox.getNextRetryAt() == null || outbox.getNextRetryAt() > now
                || outbox.getNextRetryAt() > outbox.getExpiresAt()
                || outbox.getLeaseOwner() != null || outbox.getLeaseUntil() != null
                || delivery.getLeaseOwner() != null || delivery.getLeaseUntil() != null
                || !Objects.equals(delivery.getNextRetryAt(), outbox.getNextRetryAt())
                || !Objects.equals(delivery.getLastError(), outbox.getLastError())
                || !validErrorCode(outbox.getLastError())
                || !Objects.equals(outbox.getConfirmError(), outbox.getLastError())
                || !validCompletedPublishShape(outbox)) {
            return "RETRY_SHAPE_CORRUPT";
        }
        return null;
    }

    private String validateStaleClaimShape(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox, long now) {
        boolean pendingDelivery = "PENDING".equals(delivery.getStatus());
        boolean retryDelivery = "RETRY".equals(delivery.getStatus());
        if ((!pendingDelivery && !retryDelivery)
                || (pendingDelivery && delivery.getNextRetryAt() != null)
                || (retryDelivery && (delivery.getNextRetryAt() == null
                        || delivery.getNextRetryAt() > now))
                || outbox.getAttemptCount() < 1
                || outbox.getActiveAttempt() != outbox.getAttemptCount() + 1
                || outbox.getNextRetryAt() != null
                || !validExact(outbox.getLeaseOwner(), 100)
                || outbox.getLeaseUntil() == null || outbox.getLeaseUntil() > now
                || !Objects.equals(delivery.getLeaseOwner(), outbox.getLeaseOwner())
                || !Objects.equals(delivery.getLeaseUntil(), outbox.getLeaseUntil())
                || outbox.getLastError() != null || delivery.getLastError() != null
                || !"PENDING".equals(outbox.getPublisherConfirmStatus())
                || !"PENDING".equals(outbox.getMandatoryReturnStatus())
                || anyPublishDisposition(outbox)) {
            return "STALE_CLAIM_SHAPE_CORRUPT";
        }
        return null;
    }

    private boolean tokenMatchesOutbox(
            AgentOutboxClaimToken token, AgentOutboxEventEntity outbox) {
        return outbox != null
                && Objects.equals(outbox.getId(), token.outboxId())
                && Objects.equals(outbox.getDeliveryId(), token.deliveryId())
                && Objects.equals(outbox.getTenantId(), token.tenantId())
                && Objects.equals(outbox.getClientId(), token.clientId())
                && Objects.equals(outbox.getEventId(), token.eventId())
                && Objects.equals(outbox.getMessageId(), token.messageId())
                && Objects.equals(outbox.getCommandId(), token.commandId())
                && Objects.equals(outbox.getDestination(), token.destination())
                && Objects.equals(outbox.getRoutingKey(), token.routingKey())
                && Arrays.equals(outbox.getWirePayload(), token.wirePayload())
                && outbox.getWirePayloadHash() != null
                && MessageDigest.isEqual(outbox.getWirePayloadHash(), token.wirePayloadHash())
                && Objects.equals(outbox.getExpiresAt(), token.expiresAt())
                && "CLAIMED".equals(outbox.getStatus())
                && Objects.equals(outbox.getLeaseOwner(), token.leaseOwner())
                && Objects.equals(outbox.getLeaseUntil(), token.leaseUntil())
                && Objects.equals(outbox.getActiveAttempt(), token.publishAttempt())
                && Objects.equals(outbox.getAttemptCount(), token.publishAttempt() - 1)
                && Objects.equals(outbox.getVersion(), token.outboxVersion())
                && "PENDING".equals(outbox.getPublisherConfirmStatus())
                && "PENDING".equals(outbox.getMandatoryReturnStatus());
    }

    private boolean tokenMatchesDelivery(
            AgentOutboxClaimToken token, AgentCommandDeliveryEntity delivery) {
        return delivery != null
                && Objects.equals(delivery.getId(), token.deliveryId())
                && Objects.equals(delivery.getTenantId(), token.tenantId())
                && Objects.equals(delivery.getClientId(), token.clientId())
                && Objects.equals(delivery.getCommandId(), token.commandId())
                && Objects.equals(delivery.getTaskId(), token.taskId())
                && Objects.equals(delivery.getTargetAgentId(), token.targetAgentId())
                && Objects.equals(delivery.getCommandType(), token.commandType())
                && Objects.equals(delivery.getActiveMessageId(), token.deliveryActiveMessageId())
                && Objects.equals(delivery.getActiveAttempt(), token.deliveryActiveAttempt())
                && Objects.equals(delivery.getVersion(), token.deliveryVersion())
                && Objects.equals(delivery.getStatus(), token.deliveryStatus())
                && Objects.equals(delivery.getLeaseOwner(), token.leaseOwner())
                && Objects.equals(delivery.getLeaseUntil(), token.leaseUntil());
    }

    private void disposeBoth(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            String status,
            Long nextRetryAt,
            String errorCode,
            long now) {
        if (safeAssociation(delivery, outbox)) {
            disposeDelivery(delivery, status, nextRetryAt, errorCode, now);
        }
        disposeOutbox(outbox, status, nextRetryAt,
                "NONE", null, null, "NONE", null,
                null, null, null, errorCode, now);
    }

    private void disposeDelivery(
            AgentCommandDeliveryEntity delivery, String status, Long nextRetryAt,
            String errorCode, long now) {
        requireOne(dao.disposeDelivery(delivery, status, nextRetryAt, errorCode, now),
                "delivery disposition CAS");
    }

    private void disposeOutbox(
            AgentOutboxEventEntity outbox, String status, Long nextRetryAt,
            String confirmStatus, Long confirmedAt, String confirmError,
            String returnStatus, Long returnedAt, Integer returnReplyCode,
            String returnReplyText, Long publishedAt, String errorCode, long now) {
        requireOne(dao.disposeOutbox(outbox, status, nextRetryAt,
                confirmStatus, confirmedAt, confirmError, returnStatus, returnedAt,
                returnReplyCode, returnReplyText, publishedAt, errorCode, now),
                "outbox disposition CAS");
    }


    private static boolean isTerminalOutbox(String status) {
        return status != null && TERMINAL_OUTBOX.contains(status);
    }

    private static boolean candidateMatches(
            AgentOutboxCandidate candidate, AgentOutboxEventEntity outbox) {
        return Objects.equals(outbox.getId(), candidate.outboxId())
                && Objects.equals(outbox.getDeliveryId(), candidate.deliveryId())
                && Objects.equals(outbox.getTenantId(), candidate.tenantId())
                && Objects.equals(outbox.getClientId(), candidate.clientId());
    }

    private static boolean safeAssociation(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        return delivery != null && outbox != null
                && Objects.equals(delivery.getId(), outbox.getDeliveryId())
                && Objects.equals(delivery.getTenantId(), outbox.getTenantId())
                && Objects.equals(delivery.getClientId(), outbox.getClientId())
                && Objects.equals(delivery.getCommandId(), outbox.getCommandId())
                && Objects.equals(delivery.getActiveMessageId(), outbox.getMessageId())
                && Objects.equals(delivery.getTaskId(), outbox.getAggregateId())
                && "task".equals(outbox.getAggregateType());
    }

    private static boolean storedHashMatches(byte[] payload, byte[] storedHash) {
        return payload != null && storedHash != null && storedHash.length == 32
                && MessageDigest.isEqual(AgentCommandCanonicalCodec.sha256(payload), storedHash);
    }

    private static boolean hasReplayProvenance(AgentCommandDeliveryEntity delivery) {
        return delivery.getReplayParentMessageId() != null
                || delivery.getReplayRequesterId() != null
                || delivery.getReplayApproverId() != null
                || delivery.getReplayReason() != null;
    }

    private static boolean hasReplayProvenance(AgentOutboxEventEntity outbox) {
        return outbox.getReplayParentMessageId() != null
                || outbox.getReplayRequesterId() != null
                || outbox.getReplayApproverId() != null
                || outbox.getReplayReason() != null;
    }

    private static boolean anyPublishDisposition(AgentOutboxEventEntity outbox) {
        return outbox.getConfirmedAt() != null || outbox.getConfirmError() != null
                || outbox.getReturnedAt() != null || outbox.getReturnReplyCode() != null
                || outbox.getReturnReplyText() != null || outbox.getPublishedAt() != null;
    }

    private static boolean validCompletedPublishShape(AgentOutboxEventEntity outbox) {
        String confirm = outbox.getPublisherConfirmStatus();
        boolean confirmShape = ("ACK".equals(confirm) || "NACK".equals(confirm)
                || "TIMEOUT".equals(confirm))
                ? outbox.getConfirmedAt() != null
                : "NONE".equals(confirm) && outbox.getConfirmedAt() == null;
        if (!confirmShape || outbox.getPublishedAt() != null) return false;
        String returned = outbox.getMandatoryReturnStatus();
        if ("RETURNED".equals(returned)) {
            return outbox.getReturnedAt() != null && outbox.getReturnReplyCode() != null
                    && sanitizedReply(outbox.getReturnReplyText());
        }
        return !"ACK".equals(confirm) && "NOT_RETURNED".equals(returned)
                && outbox.getReturnedAt() == null && outbox.getReturnReplyCode() == null
                && outbox.getReturnReplyText() == null;
    }

    private static boolean sanitizedReply(String value) {
        return value != null && value.length() <= 1000
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validErrorCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,2000}");
    }

    private static boolean validExact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static Long confirmedAt(AgentRabbitPublishResult result, long now) {
        return Set.of("ACK", "NACK", "TIMEOUT").contains(result.confirmStatus()) ? now : null;
    }

    private static Long returnedAt(AgentRabbitPublishResult result, long now) {
        return "RETURNED".equals(result.returnStatus()) ? now : null;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " returned an unexpected row count");
        }
    }
}
