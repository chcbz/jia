package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxFenceException;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentInboxResult;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandInboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/**
 * D07 durable Inbox processor. It owns no Rabbit listener, broker ACK, WebSocket send, or domain mutation.
 */
public final class AgentCommandInboxServiceImpl implements AgentCommandInboxService {
    public static final String IDENTITY_CONFLICT = AgentInboxIdentityConflictException.CODE;
    public static final String DB_SHADOW_MARKER = AgentCommandTransportWriterImpl.DB_SHADOW_MARKER;
    public static final String STALE_MESSAGE_FENCE = "STALE_MESSAGE_FENCE";
    public static final String MESSAGE_EXPIRED = "MESSAGE_EXPIRED";
    public static final long MAX_LEASE_MILLIS = 300_000L;
    public static final int MAX_WIRE_BYTES = 16_777_215;

    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandInboxServiceImpl.class);

    private final AgentCommandInboxDao dao;
    private final AgentRabbitSafetyGate gate;
    private final TransactionTemplate transaction;

    public AgentCommandInboxServiceImpl(
            AgentCommandInboxDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.transaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public AgentInboxClaim claim(
            AgentInboxMessage message, String leaseOwner, long now, long leaseMillis) {
        AgentInboxClaim.DisabledReason disabled = disabledReason();
        if (disabled != null) {
            return AgentInboxClaim.disabled(disabled);
        }
        ValidatedMessage validated = validateMessage(message, leaseOwner, now, leaseMillis);
        return transaction.execute(status -> claimInTransaction(validated, leaseOwner, now, leaseMillis));
    }

    @Override
    public AgentInboxResult complete(
            AgentInboxClaimToken token, AgentInboxDisposition disposition, long now) {
        if (disabledReason() != null) {
            throw new IllegalStateException(
                    "Agent command Inbox completion called while Rabbit consume is disabled");
        }
        validateToken(token);
        Objects.requireNonNull(disposition, "disposition");
        if (now <= 0) throw new IllegalArgumentException("now must be positive");
        return transaction.execute(status -> completeInTransaction(token, disposition, now));
    }

    private AgentInboxClaim claimInTransaction(
            ValidatedMessage message, String leaseOwner, long now, long leaseMillis) {
        Source source = lockAndValidateSource(message);
        if (source.dbShadowCaptureOnly()) {
            return AgentInboxClaim.disabled(AgentInboxClaim.DisabledReason.DB_SHADOW_CAPTURE_ONLY);
        }

        AgentConsumerInboxEntity inbox = dao.lockInbox(
                message.tenantId(), message.clientId(), message.consumerName(), message.messageId());
        if (inbox != null) {
            validateInbox(message, source, inbox);
            return claimExisting(message, source, inbox, leaseOwner, now, leaseMillis);
        }

        if (!"PUBLISHED".equals(source.outbox().getStatus())) {
            throw conflict(message, "OUTBOX_NOT_PUBLISHED");
        }
        if (source.staleActiveFence()) {
            return persistStaleMessage(message, source, now);
        }
        if (!"PUBLISHED".equals(source.delivery().getStatus())) {
            throw conflict(message, "DELIVERY_NOT_PUBLISHED");
        }
        if (now >= source.expiresAt()) {
            return persistFirstExpiry(message, source, now);
        }
        return acquireFirst(message, source, leaseOwner, now, leaseMillis);
    }

    private AgentInboxClaim claimExisting(
            ValidatedMessage message,
            Source source,
            AgentConsumerInboxEntity inbox,
            String leaseOwner,
            long now,
            long leaseMillis) {
        String status = inbox.getStatus();
        if (isTerminal(status)) {
            validateTerminalDeliveryConsistency(message, source, inbox);
            return AgentInboxClaim.priorResult(result(inbox));
        }
        if (source.staleActiveFence()) {
            throw conflict(message, "NON_TERMINAL_INBOX_LOST_ACTIVE_FENCE");
        }
        return switch (status) {
            case "PROCESSING" -> reclaimProcessing(
                    message, source, inbox, leaseOwner, now, leaseMillis);
            case "RETRY" -> reclaimRetry(
                    message, source, inbox, leaseOwner, now, leaseMillis);
            default -> throw conflict(message, "UNSUPPORTED_INBOX_STATUS_" + safeStatus(status));
        };
    }

    private AgentInboxClaim reclaimProcessing(
            ValidatedMessage message,
            Source source,
            AgentConsumerInboxEntity inbox,
            String leaseOwner,
            long now,
            long leaseMillis) {
        requireDeliveryStatus(message, source.delivery(), "CONSUMED");
        if (inbox.getLeaseOwner() == null || inbox.getLeaseUntil() == null) {
            throw conflict(message, "PROCESSING_LEASE_MISSING");
        }
        if (now >= source.expiresAt()) {
            return expireProcessing(message, source, inbox, now);
        }
        if (now < inbox.getLeaseUntil()) {
            return AgentInboxClaim.inFlight(inbox.getLeaseUntil() - now);
        }
        long leaseUntil = leaseUntil(now, leaseMillis, source.expiresAt());
        int nextActiveAttempt = inbox.getActiveAttempt() + 1;
        long nextInboxVersion = inbox.getVersion() + 1;
        int deliveryActiveAttempt = source.delivery().getActiveAttempt();
        long deliveryVersion = source.delivery().getVersion();
        requireOne(dao.reclaimProcessingInbox(inbox, leaseOwner, leaseUntil, now),
                "processing Inbox reclaim", message);
        return AgentInboxClaim.acquired(token(
                inbox, leaseOwner, leaseUntil,
                nextActiveAttempt, nextInboxVersion,
                deliveryActiveAttempt, deliveryVersion,
                source.expiresAt()));
    }

    private AgentInboxClaim reclaimRetry(
            ValidatedMessage message,
            Source source,
            AgentConsumerInboxEntity inbox,
            String leaseOwner,
            long now,
            long leaseMillis) {
        validateRetryShape(message, source, inbox);
        requireDeliveryStatus(message, source.delivery(), "RETRY");
        if (inbox.getNextRetryAt() == null) {
            throw conflict(message, "RETRY_TIME_MISSING");
        }
        if (now >= source.expiresAt()) {
            requireOne(updateDelivery(
                    message, source.delivery(), "RETRY", "EXPIRED", null, MESSAGE_EXPIRED, now),
                    "retry delivery expiry", message);
            requireOne(dao.expireRetryInbox(inbox, now, MESSAGE_EXPIRED, now),
                    "retry Inbox expiry", message);
            return AgentInboxClaim.priorResult(resultAfter(
                    inbox, "EXPIRED", "EXPIRED", now, MESSAGE_EXPIRED));
        }
        if (now < inbox.getNextRetryAt()) {
            return AgentInboxClaim.inFlight(inbox.getNextRetryAt() - now);
        }
        long leaseUntil = leaseUntil(now, leaseMillis, source.expiresAt());
        int nextActiveAttempt = inbox.getActiveAttempt() + 1;
        long nextInboxVersion = inbox.getVersion() + 1;
        int deliveryActiveAttempt = source.delivery().getActiveAttempt();
        long nextDeliveryVersion = source.delivery().getVersion() + 1;
        requireOne(dao.reclaimRetryInbox(inbox, leaseOwner, leaseUntil, now),
                "retry Inbox reclaim", message);
        requireOne(updateDelivery(
                message, source.delivery(), "RETRY", "CONSUMED", null, null, now),
                "retry delivery consume", message);
        return AgentInboxClaim.acquired(token(
                inbox, leaseOwner, leaseUntil,
                nextActiveAttempt, nextInboxVersion,
                deliveryActiveAttempt, nextDeliveryVersion,
                source.expiresAt()));
    }

    private AgentInboxClaim expireProcessing(
            ValidatedMessage message,
            Source source,
            AgentConsumerInboxEntity inbox,
            long now) {
        requireOne(updateDelivery(
                message, source.delivery(), "CONSUMED", "EXPIRED", null, MESSAGE_EXPIRED, now),
                "processing delivery expiry", message);
        requireOne(dao.completeInbox(
                inbox.getId(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getConsumerName(), inbox.getMessageId(), inbox.getLeaseOwner(),
                inbox.getLeaseUntil(), inbox.getActiveAttempt(), inbox.getVersion(),
                "EXPIRED", "EXPIRED", null, now, MESSAGE_EXPIRED, now),
                "processing Inbox expiry", message);
        return AgentInboxClaim.priorResult(resultAfter(
                inbox, "EXPIRED", "EXPIRED", now, MESSAGE_EXPIRED));
    }

    private AgentInboxClaim acquireFirst(
            ValidatedMessage message,
            Source source,
            String leaseOwner,
            long now,
            long leaseMillis) {
        long leaseUntil = leaseUntil(now, leaseMillis, source.expiresAt());
        AgentConsumerInboxEntity inbox = newInbox(
                message, source.expiresAt(), "PROCESSING", null,
                1, leaseOwner, leaseUntil, 1, null, null, now);
        try {
            requireOne(dao.insertInbox(inbox), "Inbox insert", message);
        } catch (DuplicateKeyException duplicate) {
            throw conflict(message, "CONCURRENT_INBOX_KEY_CONFLICT");
        }
        if (inbox.getId() == null || inbox.getId() <= 0) {
            throw new IllegalStateException("Inbox insert did not return a generated id");
        }
        long nextDeliveryVersion = source.delivery().getVersion() + 1;
        int deliveryActiveAttempt = source.delivery().getActiveAttempt();
        requireOne(updateDelivery(
                message, source.delivery(), "PUBLISHED", "CONSUMED", null, null, now),
                "delivery consume", message);
        return AgentInboxClaim.acquired(new AgentInboxClaimToken(
                inbox.getId(), message.consumerName(), message.tenantId(), message.clientId(),
                message.messageId(), message.eventId(), message.commandId(), message.deliveryId(),
                leaseOwner, leaseUntil, 1, 0,
                deliveryActiveAttempt, nextDeliveryVersion,
                source.expiresAt()));
    }

    private AgentInboxClaim persistFirstExpiry(
            ValidatedMessage message, Source source, long now) {
        AgentConsumerInboxEntity inbox = newInbox(
                message, source.expiresAt(), "EXPIRED", "EXPIRED",
                0, null, null, 0, now, MESSAGE_EXPIRED, now);
        requireOne(dao.insertInbox(inbox), "expired Inbox insert", message);
        requireOne(updateDelivery(
                message, source.delivery(), "PUBLISHED", "EXPIRED", null, MESSAGE_EXPIRED, now),
                "delivery expiry", message);
        return AgentInboxClaim.priorResult(result(inbox));
    }

    private AgentInboxClaim persistStaleMessage(
            ValidatedMessage message, Source source, long now) {
        AgentConsumerInboxEntity inbox = newInbox(
                message, source.expiresAt(), "DEAD", "DEAD",
                0, null, null, 0, now, STALE_MESSAGE_FENCE, now);
        requireOne(dao.insertInbox(inbox), "stale Inbox insert", message);
        return AgentInboxClaim.priorResult(result(inbox));
    }

    private AgentInboxResult completeInTransaction(
            AgentInboxClaimToken token, AgentInboxDisposition disposition, long now) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                token.tenantId(), token.clientId(), token.deliveryId());
        AgentOutboxEventEntity outbox = dao.lockOutbox(
                token.tenantId(), token.clientId(), token.eventId());
        AgentConsumerInboxEntity inbox = dao.lockInbox(
                token.tenantId(), token.clientId(), token.consumerName(), token.messageId());
        validateCompletionRows(token, delivery, outbox, inbox);
        validateDispositionTiming(disposition, token, delivery.getExpiresAt(), now);

        Completion completion = completion(disposition);
        ValidatedMessage identity = tokenIdentity(token, inbox.getWirePayload(), inbox.getWirePayloadHash());
        requireOne(updateDelivery(
                identity, delivery, "CONSUMED", completion.deliveryStatus(),
                disposition.nextRetryAt(), disposition.errorCode(), now),
                "completion delivery disposition", identity);
        int inboxRows = dao.completeInbox(
                token.inboxId(), token.tenantId(), token.clientId(),
                token.consumerName(), token.messageId(), token.leaseOwner(),
                token.leaseUntil(), token.activeAttempt(), token.inboxVersion(),
                completion.inboxStatus(), completion.resultStatus(),
                disposition.nextRetryAt(), now, disposition.errorCode(), now);
        if (inboxRows != 1) {
            throw new AgentInboxFenceException(
                    "Inbox completion CAS returned " + inboxRows + " rows; expected 1");
        }
        return resultAfter(
                inbox, completion.inboxStatus(), completion.resultStatus(),
                now, disposition.errorCode());
    }

    private Source lockAndValidateSource(ValidatedMessage message) {
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(
                message.tenantId(), message.clientId(), message.deliveryId());
        if (delivery == null) throw conflict(message, "DELIVERY_NOT_FOUND");
        validateDeliveryCore(message, delivery);

        AgentOutboxEventEntity outbox = dao.lockOutbox(
                message.tenantId(), message.clientId(), message.eventId());
        if (outbox == null) throw conflict(message, "OUTBOX_NOT_FOUND");
        validateOutbox(message, delivery, outbox);

        boolean shadowDelivery = "DEAD".equals(delivery.getStatus())
                && DB_SHADOW_MARKER.equals(delivery.getLastError());
        boolean shadowOutbox = "DEAD".equals(outbox.getStatus())
                && DB_SHADOW_MARKER.equals(outbox.getLastError());
        if (shadowDelivery || shadowOutbox) {
            if (shadowDelivery && shadowOutbox) {
                return new Source(delivery, outbox, false, true, delivery.getExpiresAt());
            }
            throw conflict(message, "PARTIAL_DB_SHADOW_MARKER");
        }
        if (DB_SHADOW_MARKER.equals(delivery.getLastError())
                || DB_SHADOW_MARKER.equals(outbox.getLastError())) {
            throw conflict(message, "DB_SHADOW_MARKER_STATUS_DRIFT");
        }
        if (!"PUBLISHED".equals(outbox.getStatus())) {
            throw conflict(message, "OUTBOX_NOT_PUBLISHED");
        }

        boolean activeFence = Objects.equals(
                delivery.getActiveMessageId(), outbox.getMessageId());
        return new Source(delivery, outbox, !activeFence, false, delivery.getExpiresAt());
    }

    private void validateDeliveryCore(
            ValidatedMessage message, AgentCommandDeliveryEntity delivery) {
        if (!Objects.equals(delivery.getId(), message.deliveryId())
                || !Objects.equals(delivery.getTenantId(), message.tenantId())
                || !Objects.equals(delivery.getClientId(), message.clientId())
                || !Objects.equals(delivery.getCommandId(), message.commandId())) {
            throw conflict(message, "DELIVERY_IDENTITY_DRIFT");
        }
        if (delivery.getExpiresAt() == null || delivery.getExpiresAt() <= 0
                || delivery.getActiveMessageId() == null || delivery.getActiveMessageId().isEmpty()
                || delivery.getActiveMessageId().length() > 100
                || delivery.getActiveMessageId().codePoints().anyMatch(Character::isISOControl)
                || delivery.getActiveAttempt() == null || delivery.getActiveAttempt() <= 0
                || delivery.getVersion() == null || delivery.getVersion() < 0) {
            throw conflict(message, "DELIVERY_FENCE_CORRUPT");
        }
        if (!storedHashMatches(delivery.getCommandPayload(), delivery.getCommandPayloadHash())) {
            throw conflict(message, "DELIVERY_STORED_HASH_CORRUPT");
        }
    }

    private void validateOutbox(
            ValidatedMessage message,
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox) {
        if (!Objects.equals(outbox.getTenantId(), message.tenantId())
                || !Objects.equals(outbox.getClientId(), message.clientId())
                || !Objects.equals(outbox.getEventId(), message.eventId())
                || !Objects.equals(outbox.getMessageId(), message.messageId())
                || !Objects.equals(outbox.getCommandId(), message.commandId())
                || !Objects.equals(outbox.getDeliveryId(), message.deliveryId())
                || !Objects.equals(outbox.getDeliveryId(), delivery.getId())
                || !Objects.equals(outbox.getExpiresAt(), delivery.getExpiresAt())) {
            throw conflict(message, "OUTBOX_IDENTITY_DRIFT");
        }
        if (outbox.getActiveAttempt() == null || outbox.getActiveAttempt() <= 0
                || outbox.getVersion() == null || outbox.getVersion() < 0) {
            throw conflict(message, "OUTBOX_FENCE_CORRUPT");
        }
        if (!storedHashMatches(outbox.getWirePayload(), outbox.getWirePayloadHash())) {
            throw conflict(message, "OUTBOX_STORED_HASH_CORRUPT");
        }
        if (!MessageDigest.isEqual(outbox.getWirePayloadHash(), message.wireHash())
                || !Arrays.equals(outbox.getWirePayload(), message.wireBytes())) {
            throw conflict(message, "OUTBOX_WIRE_BYTES_DRIFT");
        }
    }

    private void validateInbox(
            ValidatedMessage message, Source source, AgentConsumerInboxEntity inbox) {
        if (!Objects.equals(inbox.getTenantId(), message.tenantId())
                || !Objects.equals(inbox.getClientId(), message.clientId())
                || !Objects.equals(inbox.getConsumerName(), message.consumerName())
                || !Objects.equals(inbox.getMessageId(), message.messageId())
                || !Objects.equals(inbox.getEventId(), message.eventId())
                || !Objects.equals(inbox.getCommandId(), message.commandId())
                || !Objects.equals(inbox.getDeliveryId(), message.deliveryId())
                || !Objects.equals(inbox.getExpiresAt(), source.expiresAt())) {
            throw conflict(message, "INBOX_IDENTITY_DRIFT");
        }
        if (!storedHashMatches(inbox.getWirePayload(), inbox.getWirePayloadHash())) {
            throw conflict(message, "INBOX_STORED_HASH_CORRUPT");
        }
        if (!MessageDigest.isEqual(inbox.getWirePayloadHash(), message.wireHash())
                || !Arrays.equals(inbox.getWirePayload(), message.wireBytes())) {
            throw conflict(message, "INBOX_WIRE_BYTES_DRIFT");
        }
        if (inbox.getId() == null || inbox.getId() <= 0
                || inbox.getStatus() == null || !inbox.getStatus().matches("[A-Z_]{1,32}")
                || inbox.getAttemptCount() == null || inbox.getAttemptCount() < 0
                || inbox.getActiveAttempt() == null || inbox.getActiveAttempt() < 0
                || inbox.getVersion() == null || inbox.getVersion() < 0) {
            throw conflict(message, "INBOX_FENCE_CORRUPT");
        }
        if (STALE_MESSAGE_FENCE.equals(inbox.getLastError())
                && (!"DEAD".equals(inbox.getStatus())
                || !"DEAD".equals(inbox.getResultStatus()))) {
            throw conflict(message, "STALE_MARKER_SHAPE_CORRUPT");
        }
    }

    private void validateTerminalDeliveryConsistency(
            ValidatedMessage message, Source source, AgentConsumerInboxEntity inbox) {
        validateTerminalShape(message, source, inbox);
        if (STALE_MESSAGE_FENCE.equals(inbox.getLastError())) return;

        String result = inbox.getResultStatus();
        if (source.staleActiveFence()) return;
        boolean deliveryMatches = switch (result) {
            case "SENT" -> isOneOf(source.delivery().getStatus(),
                    "SENT", "RECEIVED", "STARTED", "SUCCEEDED",
                    "FAILED", "EXPIRED", "DEAD");
            case "WAITING_AGENT", "FAILED", "EXPIRED", "DEAD" ->
                    result.equals(source.delivery().getStatus());
            default -> false;
        };
        if (!deliveryMatches) throw conflict(message, "TERMINAL_DELIVERY_DRIFT");
    }

    private void validateTerminalShape(
            ValidatedMessage message, Source source, AgentConsumerInboxEntity inbox) {
        String result = inbox.getResultStatus();
        boolean resultMatches = switch (inbox.getStatus()) {
            case "PROCESSED" -> "SENT".equals(result);
            case "WAITING_AGENT" -> "WAITING_AGENT".equals(result);
            case "FAILED" -> "FAILED".equals(result);
            case "EXPIRED" -> "EXPIRED".equals(result);
            case "DEAD" -> "DEAD".equals(result);
            default -> false;
        };
        if (!resultMatches) throw conflict(message, "TERMINAL_RESULT_DRIFT");
        if (inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null) {
            throw conflict(message, "TERMINAL_FENCE_SHAPE_CORRUPT");
        }
        validateStoredDisposition(message, inbox, result);
        if (inbox.getNextRetryAt() != null
                && (inbox.getNextRetryAt() <= inbox.getProcessedAt()
                || inbox.getNextRetryAt() >= source.expiresAt())) {
            throw conflict(message, "TERMINAL_RETRY_SHAPE_CORRUPT");
        }
        if ("EXPIRED".equals(inbox.getStatus())
                && inbox.getProcessedAt() < source.expiresAt()) {
            throw conflict(message, "TERMINAL_EXPIRY_SHAPE_CORRUPT");
        }
        if (STALE_MESSAGE_FENCE.equals(inbox.getLastError())
                && !source.staleActiveFence()) {
            throw conflict(message, "STALE_MARKER_ACTIVE_SOURCE_CORRUPT");
        }
    }

    private void validateRetryShape(
            ValidatedMessage message, Source source, AgentConsumerInboxEntity inbox) {
        if (!"RETRY".equals(inbox.getResultStatus())
                || inbox.getProcessedAt() == null || inbox.getProcessedAt() <= 0
                || inbox.getLeaseOwner() != null || inbox.getLeaseUntil() != null) {
            throw conflict(message, "RETRY_FENCE_SHAPE_CORRUPT");
        }
        validateStoredDisposition(message, inbox, "RETRY");
        if (inbox.getNextRetryAt() <= inbox.getProcessedAt()
                || inbox.getNextRetryAt() >= source.expiresAt()) {
            throw conflict(message, "RETRY_TIME_SHAPE_CORRUPT");
        }
    }

    private void validateStoredDisposition(
            ValidatedMessage message, AgentConsumerInboxEntity inbox, String resultStatus) {
        try {
            new AgentInboxDisposition(
                    AgentInboxDisposition.Type.valueOf(resultStatus),
                    inbox.getNextRetryAt(), inbox.getLastError());
        } catch (IllegalArgumentException invalidShape) {
            throw conflict(message, "STORED_DISPOSITION_SHAPE_CORRUPT");
        }
    }

    private void validateCompletionRows(
            AgentInboxClaimToken token,
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            AgentConsumerInboxEntity inbox) {
        if (delivery == null || outbox == null || inbox == null) {
            throw new AgentInboxFenceException("claim source row is missing");
        }
        ValidatedMessage identity = tokenIdentity(token, inbox.getWirePayload(), inbox.getWirePayloadHash());
        validateDeliveryCore(identity, delivery);
        validateOutbox(identity, delivery, outbox);
        if (!"PUBLISHED".equals(outbox.getStatus())) {
            throw tokenConflict(token, "OUTBOX_NOT_PUBLISHED");
        }
        validateInbox(identity, new Source(
                delivery, outbox, false, false, delivery.getExpiresAt()), inbox);
        if (!"CONSUMED".equals(delivery.getStatus())
                || !Objects.equals(delivery.getActiveMessageId(), token.messageId())
                || !Objects.equals(delivery.getActiveAttempt(), token.deliveryActiveAttempt())
                || !Objects.equals(delivery.getVersion(), token.deliveryVersion())
                || !"PROCESSING".equals(inbox.getStatus())
                || !Objects.equals(inbox.getLeaseOwner(), token.leaseOwner())
                || !Objects.equals(inbox.getLeaseUntil(), token.leaseUntil())
                || !Objects.equals(inbox.getActiveAttempt(), token.activeAttempt())
                || !Objects.equals(inbox.getVersion(), token.inboxVersion())
                || !Objects.equals(inbox.getId(), token.inboxId())
                || !Objects.equals(inbox.getExpiresAt(), token.expiresAt())) {
            throw new AgentInboxFenceException("claim fence no longer matches durable rows");
        }
    }

    private ValidatedMessage tokenIdentity(
            AgentInboxClaimToken token, byte[] wirePayload, byte[] wireHash) {
        if (!storedHashMatches(wirePayload, wireHash)) {
            throw tokenConflict(token, "INBOX_STORED_HASH_CORRUPT");
        }
        return new ValidatedMessage(
                token.consumerName(), token.tenantId(), token.clientId(), token.messageId(),
                token.eventId(), token.commandId(), token.deliveryId(),
                Arrays.copyOf(wirePayload, wirePayload.length), Arrays.copyOf(wireHash, wireHash.length));
    }

    private AgentConsumerInboxEntity newInbox(
            ValidatedMessage message,
            long expiresAt,
            String status,
            String resultStatus,
            int attemptCount,
            String leaseOwner,
            Long leaseUntil,
            int activeAttempt,
            Long processedAt,
            String lastError,
            long now) {
        AgentConsumerInboxEntity inbox = new AgentConsumerInboxEntity()
                .setConsumerName(message.consumerName())
                .setMessageId(message.messageId())
                .setEventId(message.eventId())
                .setCommandId(message.commandId())
                .setDeliveryId(message.deliveryId())
                .setWirePayload(Arrays.copyOf(message.wireBytes(), message.wireBytes().length))
                .setWirePayloadHash(Arrays.copyOf(message.wireHash(), message.wireHash().length))
                .setStatus(status)
                .setResultStatus(resultStatus)
                .setAttemptCount(attemptCount)
                .setLeaseOwner(leaseOwner)
                .setLeaseUntil(leaseUntil)
                .setActiveAttempt(activeAttempt)
                .setExpiresAt(expiresAt)
                .setProcessedAt(processedAt)
                .setLastError(lastError)
                .setVersion(0L);
        inbox.setTenantId(message.tenantId());
        inbox.setClientId(message.clientId());
        inbox.setCreateTime(now);
        inbox.setUpdateTime(now);
        return inbox;
    }

    private AgentInboxClaimToken token(
            AgentConsumerInboxEntity inbox,
            String leaseOwner,
            long leaseUntil,
            int activeAttempt,
            long inboxVersion,
            int deliveryActiveAttempt,
            long deliveryVersion,
            long expiresAt) {
        return new AgentInboxClaimToken(
                inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(), inbox.getDeliveryId(),
                leaseOwner, leaseUntil, activeAttempt, inboxVersion,
                deliveryActiveAttempt, deliveryVersion, expiresAt);
    }

    private int updateDelivery(
            ValidatedMessage message,
            AgentCommandDeliveryEntity delivery,
            String expectedStatus,
            String newStatus,
            Long nextRetryAt,
            String lastError,
            long now) {
        return dao.updateDeliveryDisposition(
                message.tenantId(), message.clientId(), message.deliveryId(),
                message.messageId(), delivery.getActiveAttempt(), expectedStatus,
                delivery.getVersion(), newStatus, nextRetryAt, lastError, now);
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

    private void validateDispositionTiming(
            AgentInboxDisposition disposition,
            AgentInboxClaimToken token,
            long authoritativeExpiresAt,
            long now) {
        if (disposition.type() == AgentInboxDisposition.Type.EXPIRED) {
            if (now < authoritativeExpiresAt) {
                throw new IllegalArgumentException(
                        "EXPIRED disposition requires now >= authoritative expiresAt");
            }
            if (token.leaseUntil() != authoritativeExpiresAt) {
                throw new AgentInboxFenceException(
                        "claim lease did not own the expiry boundary");
            }
        } else if (now >= token.leaseUntil()) {
            throw new AgentInboxFenceException("claim lease is no longer valid");
        }
        if (disposition.nextRetryAt() != null
                && (disposition.nextRetryAt() <= now
                || disposition.nextRetryAt() >= authoritativeExpiresAt)) {
            throw new IllegalArgumentException("nextRetryAt must be after now and before expiresAt");
        }
    }

    private ValidatedMessage validateMessage(
            AgentInboxMessage message, String leaseOwner, long now, long leaseMillis) {
        if (message == null) throw new IllegalArgumentException("message is required");
        requireExact(message.consumerName(), "consumerName", 100);
        if (!AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(message.consumerName())) {
            throw new IllegalArgumentException(
                    "consumerName must be the frozen stable logical Agent command consumer");
        }
        requireExact(message.tenantId(), "tenantId", 50);
        requireExact(message.clientId(), "clientId", 50);
        requireExact(message.messageId(), "messageId", 100);
        requireExact(message.eventId(), "eventId", 100);
        requireExact(message.commandId(), "commandId", 100);
        requireExact(leaseOwner, "leaseOwner", 100);
        if (message.deliveryId() <= 0) throw new IllegalArgumentException("deliveryId must be positive");
        if (now <= 0) throw new IllegalArgumentException("now must be positive");
        if (leaseMillis <= 0 || leaseMillis > MAX_LEASE_MILLIS) {
            throw new IllegalArgumentException("leaseMillis must be in 1..300000");
        }
        byte[] wireBytes = message.rawWireBytes();
        if (wireBytes == null || wireBytes.length == 0 || wireBytes.length > MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("rawWireBytes must contain 1..16777215 bytes");
        }
        return new ValidatedMessage(
                message.consumerName(), message.tenantId(), message.clientId(),
                message.messageId(), message.eventId(), message.commandId(),
                message.deliveryId(), wireBytes, sha256(wireBytes));
    }

    private void validateToken(AgentInboxClaimToken token) {
        if (token == null) throw new IllegalArgumentException("token is required");
        requireExact(token.consumerName(), "consumerName", 100);
        if (!AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1.equals(token.consumerName())) {
            throw new IllegalArgumentException("token consumerName is not frozen");
        }
        requireExact(token.tenantId(), "tenantId", 50);
        requireExact(token.clientId(), "clientId", 50);
        requireExact(token.messageId(), "messageId", 100);
        requireExact(token.eventId(), "eventId", 100);
        requireExact(token.commandId(), "commandId", 100);
        requireExact(token.leaseOwner(), "leaseOwner", 100);
        if (token.inboxId() <= 0 || token.deliveryId() <= 0
                || token.leaseUntil() <= 0 || token.activeAttempt() <= 0
                || token.inboxVersion() < 0 || token.deliveryActiveAttempt() <= 0
                || token.deliveryVersion() < 0 || token.expiresAt() <= 0) {
            throw new IllegalArgumentException("token fence fields are invalid");
        }
    }

    private AgentInboxClaim.DisabledReason disabledReason() {
        if (!gate.rabbitConsumeEnabled()) {
            if (gate.state() == AgentRabbitActivationState.OFF) {
                return AgentInboxClaim.DisabledReason.TRANSPORT_OFF;
            }
            if (gate.state() == AgentRabbitActivationState.DB_SHADOW) {
                return AgentInboxClaim.DisabledReason.DB_SHADOW;
            }
            return AgentInboxClaim.DisabledReason.RABBIT_CONSUME_DISABLED;
        }
        if (gate.state() == AgentRabbitActivationState.OFF) {
            return AgentInboxClaim.DisabledReason.TRANSPORT_OFF;
        }
        if (gate.state() == AgentRabbitActivationState.DB_SHADOW) {
            return AgentInboxClaim.DisabledReason.DB_SHADOW;
        }
        return null;
    }

    private long leaseUntil(long now, long leaseMillis, long expiresAt) {
        long requested;
        try {
            requested = Math.addExact(now, leaseMillis);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("leaseUntil overflow", overflow);
        }
        long leaseUntil = Math.min(requested, expiresAt);
        if (leaseUntil <= now) throw new IllegalArgumentException("lease cannot extend beyond expiry");
        return leaseUntil;
    }

    private void requireDeliveryStatus(
            ValidatedMessage message, AgentCommandDeliveryEntity delivery, String expected) {
        if (!expected.equals(delivery.getStatus())) {
            throw conflict(message, "DELIVERY_STATUS_NOT_" + expected);
        }
    }

    private void requireOne(int rows, String operation, ValidatedMessage message) {
        if (rows != 1) {
            throw conflict(message, operation.replace(' ', '_').toUpperCase() + "_CAS_" + rows);
        }
    }

    private AgentInboxResult result(AgentConsumerInboxEntity inbox) {
        return new AgentInboxResult(
                inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(), inbox.getDeliveryId(),
                inbox.getStatus(), inbox.getResultStatus(), inbox.getProcessedAt(), inbox.getLastError());
    }

    private AgentInboxResult resultAfter(
            AgentConsumerInboxEntity inbox,
            String status,
            String resultStatus,
            Long processedAt,
            String lastError) {
        return new AgentInboxResult(
                inbox.getId(), inbox.getConsumerName(), inbox.getTenantId(), inbox.getClientId(),
                inbox.getMessageId(), inbox.getEventId(), inbox.getCommandId(), inbox.getDeliveryId(),
                status, resultStatus, processedAt, lastError);
    }

    private boolean isTerminal(String status) {
        return isOneOf(status, "PROCESSED", "WAITING_AGENT", "FAILED", "EXPIRED", "DEAD");
    }

    private boolean isOneOf(String value, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return true;
        return false;
    }

    private String safeStatus(String status) {
        if (status == null) return "NULL";
        return status.matches("[A-Z0-9_]{1,32}") ? status : "INVALID";
    }

    private boolean storedHashMatches(byte[] bytes, byte[] storedHash) {
        return bytes != null && storedHash != null && storedHash.length == 32
                && MessageDigest.isEqual(storedHash, sha256(bytes));
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private AgentInboxIdentityConflictException conflict(
            ValidatedMessage message, String reasonCode) {
        LOG.warn("event={} reason={} consumerName={} tenantId={} clientId={} messageId={} "
                        + "eventId={} commandId={} deliveryId={}",
                IDENTITY_CONFLICT, reasonCode, message.consumerName(), message.tenantId(),
                message.clientId(), message.messageId(), message.eventId(),
                message.commandId(), message.deliveryId());
        return new AgentInboxIdentityConflictException(reasonCode);
    }

    private AgentInboxIdentityConflictException tokenConflict(
            AgentInboxClaimToken token, String reasonCode) {
        LOG.warn("event={} reason={} consumerName={} tenantId={} clientId={} messageId={} "
                        + "eventId={} commandId={} deliveryId={}",
                IDENTITY_CONFLICT, reasonCode, token.consumerName(), token.tenantId(),
                token.clientId(), token.messageId(), token.eventId(),
                token.commandId(), token.deliveryId());
        return new AgentInboxIdentityConflictException(reasonCode);
    }

    private void requireExact(String value, String field, int maxChars) {
        if (value == null || value.isEmpty() || value.length() > maxChars
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)
                || !validSurrogates(value)) {
            throw new IllegalArgumentException(
                    field + " must be byte-exact, unpadded, control-free and <= " + maxChars + " chars");
        }
    }

    private boolean validSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private record ValidatedMessage(
            String consumerName,
            String tenantId,
            String clientId,
            String messageId,
            String eventId,
            String commandId,
            long deliveryId,
            byte[] wireBytes,
            byte[] wireHash) {
    }

    private record Source(
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            boolean staleActiveFence,
            boolean dbShadowCaptureOnly,
            long expiresAt) {
    }

    private record Completion(String inboxStatus, String resultStatus, String deliveryStatus) {
    }
}
