package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
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
import cn.jia.agent.entity.AgentInboxSourceNotSettledException;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.AgentCommandInboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

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
    private static final Set<String> COMMAND_TYPES = Set.of(
            AgentProtocolConstants.COMMAND_TASK_INVITE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
            AgentProtocolConstants.COMMAND_WORK_ITEM_RESUME,
            AgentProtocolConstants.COMMAND_WORK_ITEM_CANCEL,
            AgentProtocolConstants.COMMAND_REQUEST_RESPOND,
            AgentProtocolConstants.COMMAND_REVIEW_EXECUTE,
            AgentProtocolConstants.COMMAND_CONTEXT_REFRESH);
    private static final ObjectMapper STRICT_WIRE_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

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
            // Poison validation wins over the publish-before-settle transient. A source cannot
            // legitimately return to CLAIMED after this consumer has created its durable Inbox.
            validateInbox(message, source, inbox);
            validateExistingInboxSourceStatus(message, source);
            return claimExisting(message, source, inbox, leaseOwner, now, leaseMillis);
        }

        // A broker-confirmed old message can be stale, but a CLAIMED source with a drifted active
        // fence is not the canonical D03 publish-before-settle race and must fail closed.
        if (source.staleActiveFence()) {
            if (!"PUBLISHED".equals(source.outbox().getStatus())
                    || !"PUBLISHED".equals(source.delivery().getStatus())) {
                throw conflict(message, "ACTIVE_MESSAGE_FENCE_DRIFT_DURING_SOURCE_SETTLEMENT");
            }
            return persistStaleMessage(message, source, now);
        }
        validateFirstClaimSourceStatus(message, source, now);
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
        java.util.List<AgentOutboxEventEntity> previousAttempts =
                lockPreviousAttempts(delivery, outbox);
        AgentConsumerInboxEntity inbox = dao.lockInbox(
                token.tenantId(), token.clientId(), token.consumerName(), token.messageId());
        validateCompletionRows(token, disposition, delivery, outbox, previousAttempts, inbox);
        validateDispositionTiming(disposition, token, delivery.getExpiresAt(), now);

        Completion completion = completion(disposition);
        ValidatedMessage identity = tokenIdentity(token, inbox.getWirePayload(), inbox.getWirePayloadHash());
        if ("CONSUMED".equals(delivery.getStatus())) {
            requireOne(updateDelivery(
                    identity, delivery, "CONSUMED", completion.deliveryStatus(),
                    disposition.nextRetryAt(), disposition.errorCode(), now),
                    "completion delivery disposition", identity);
        } else if (!validAckBeforeSentCompletion(token, disposition, delivery)) {
            throw new AgentInboxFenceException(
                    "only fenced in-flight ACK progress may precede SENT completion");
        }
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
        java.util.List<AgentOutboxEventEntity> previousAttempts =
                lockPreviousAttempts(delivery, outbox);
        validateOutbox(message, delivery, outbox);
        validateReplayAudit(message, delivery, outbox, previousAttempts);

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
        boolean activeFence = Objects.equals(
                delivery.getActiveMessageId(), outbox.getMessageId());
        return new Source(delivery, outbox, !activeFence, false, delivery.getExpiresAt());
    }

    private void validateExistingInboxSourceStatus(
            ValidatedMessage message, Source source) {
        if (!"PUBLISHED".equals(source.outbox().getStatus())) {
            throw conflict(message, "OUTBOX_NOT_PUBLISHED_WITH_EXISTING_INBOX");
        }
    }

    private void validateFirstClaimSourceStatus(
            ValidatedMessage message, Source source, long now) {
        String outboxStatus = source.outbox().getStatus();
        String deliveryStatus = source.delivery().getStatus();
        if ("CLAIMED".equals(outboxStatus)) {
            validateCanonicalClaimedSource(message, source, now);
            throw sourceNotSettled(message, "OUTBOX_NOT_PUBLISHED");
        }
        if (!"PUBLISHED".equals(outboxStatus)) {
            throw conflict(message, "OUTBOX_NOT_PUBLISHED");
        }
        if (!"PUBLISHED".equals(deliveryStatus)) {
            throw conflict(message, "DELIVERY_NOT_PUBLISHED");
        }
    }

    private void validateCanonicalClaimedSource(
            ValidatedMessage message, Source source, long now) {
        AgentCommandDeliveryEntity delivery = source.delivery();
        AgentOutboxEventEntity outbox = source.outbox();
        boolean pending = "PENDING".equals(delivery.getStatus());
        boolean retry = "RETRY".equals(delivery.getStatus());
        boolean deliveryLaneValid = (pending && delivery.getNextRetryAt() == null)
                || (retry && delivery.getNextRetryAt() != null
                && delivery.getNextRetryAt() > 0
                && delivery.getNextRetryAt() <= now
                && delivery.getNextRetryAt() < message.expiresAt());
        // D03 also recognizes an expired CLAIMED lease as a canonical stale-claim redrive lane;
        // the durable lease identity must still be present and byte-exact on both rows.
        boolean leaseValid = validExact(outbox.getLeaseOwner(), 100)
                && outbox.getLeaseUntil() != null && outbox.getLeaseUntil() > 0
                && Objects.equals(delivery.getLeaseOwner(), outbox.getLeaseOwner())
                && Objects.equals(delivery.getLeaseUntil(), outbox.getLeaseUntil());
        // Outbox attempt_count is the independent Rabbit publish claim/retry fence. The shared
        // provenance validator separately requires outbox.active_attempt to remain equal to the
        // delivery transport/reissue attempt throughout claim and settlement.
        boolean publishFenceValid = outbox.getAttemptCount() != null
                && outbox.getAttemptCount() >= 1
                && outbox.getActiveAttempt() != null && outbox.getActiveAttempt() > 0
                && outbox.getVersion() != null && outbox.getVersion() > 0
                && outbox.getVersion() < Long.MAX_VALUE
                && delivery.getVersion() != null && delivery.getVersion() > 0
                && delivery.getVersion() < Long.MAX_VALUE;
        boolean dispositionEmpty = outbox.getNextRetryAt() == null
                && outbox.getConfirmedAt() == null
                && outbox.getConfirmError() == null
                && outbox.getReturnedAt() == null
                && outbox.getReturnReplyCode() == null
                && outbox.getReturnReplyText() == null
                && outbox.getPublishedAt() == null
                && outbox.getLastError() == null
                && delivery.getLastError() == null;
        boolean confirmationPending = "PENDING".equals(outbox.getPublisherConfirmStatus())
                && "PENDING".equals(outbox.getMandatoryReturnStatus());
        if (!deliveryLaneValid || !leaseValid || !publishFenceValid
                || !dispositionEmpty || !confirmationPending) {
            throw conflict(message, "CLAIMED_SOURCE_SHAPE_CORRUPT");
        }
    }

    private void validateReplayAudit(
            ValidatedMessage message,
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            java.util.List<AgentOutboxEventEntity> previousAttempts) {
        if (!AgentCommandAutomaticReplayProvenance.validImmediateParent(
                delivery, outbox, previousAttempts)) {
            throw conflict(message, "REPLAY_PROVENANCE_CORRUPT");
        }
    }

    private java.util.List<AgentOutboxEventEntity> lockPreviousAttempts(
            AgentCommandDeliveryEntity delivery, AgentOutboxEventEntity outbox) {
        if (delivery == null || outbox == null || delivery.getActiveAttempt() == null
                || delivery.getActiveAttempt() <= 1) {
            return java.util.List.of();
        }
        return dao.lockPreviousAttemptOutboxes(
                delivery.getTenantId(), delivery.getClientId(), delivery.getId(),
                delivery.getActiveAttempt() - 1);
    }

    private void validateDeliveryCore(
            ValidatedMessage message, AgentCommandDeliveryEntity delivery) {
        if (!Objects.equals(delivery.getId(), message.deliveryId())
                || !Objects.equals(delivery.getTenantId(), message.tenantId())
                || !Objects.equals(delivery.getClientId(), message.clientId())
                || !Objects.equals(delivery.getCommandId(), message.commandId())
                || !Objects.equals(delivery.getTaskId(), message.taskId())
                || !Objects.equals(delivery.getTargetAgentId(), message.targetAgentId())
                || !Objects.equals(delivery.getCommandType(), message.commandType())
                || !Objects.equals(delivery.getActiveAttempt(), message.activeAttempt())
                || !Objects.equals(delivery.getExpiresAt(), message.expiresAt())) {
            throw conflict(message, "DELIVERY_IDENTITY_DRIFT");
        }
        if (delivery.getExpiresAt() == null || delivery.getExpiresAt() <= 0
                || delivery.getActiveMessageId() == null || delivery.getActiveMessageId().isEmpty()
                || delivery.getActiveMessageId().length() > 100
                || delivery.getActiveMessageId().codePoints().anyMatch(Character::isISOControl)
                // Transport issue/reissue count is the delivery attempt encoded in the wire;
                // it must never be confused with the independent outbox publish attempt.
                || delivery.getAttemptCount() == null
                || !Objects.equals(delivery.getAttemptCount(), message.activeAttempt())
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
                || !"task".equals(outbox.getAggregateType())
                || !Objects.equals(outbox.getAggregateId(), message.taskId())
                || !Objects.equals(outbox.getExpiresAt(), message.expiresAt())
                || !Objects.equals(outbox.getExpiresAt(), delivery.getExpiresAt())) {
            throw conflict(message, "OUTBOX_IDENTITY_DRIFT");
        }
        if (outbox.getId() == null || outbox.getId() <= 0
                || outbox.getAttemptCount() == null || outbox.getAttemptCount() < 0
                || outbox.getActiveAttempt() == null || outbox.getActiveAttempt() <= 0
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
                    "FAILED", "REJECTED", "EXPIRED", "DEAD");
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
            AgentInboxDisposition disposition,
            AgentCommandDeliveryEntity delivery,
            AgentOutboxEventEntity outbox,
            java.util.List<AgentOutboxEventEntity> previousAttempts,
            AgentConsumerInboxEntity inbox) {
        if (delivery == null || outbox == null || inbox == null) {
            throw new AgentInboxFenceException("claim source row is missing");
        }
        ValidatedMessage identity = tokenIdentity(token, inbox.getWirePayload(), inbox.getWirePayloadHash());
        validateDeliveryCore(identity, delivery);
        validateOutbox(identity, delivery, outbox);
        validateReplayAudit(identity, delivery, outbox, previousAttempts);
        if (!"PUBLISHED".equals(outbox.getStatus())) {
            throw tokenConflict(token, "OUTBOX_NOT_PUBLISHED");
        }
        validateInbox(identity, new Source(
                delivery, outbox, false, false, delivery.getExpiresAt()), inbox);
        boolean ordinaryCompletion = "CONSUMED".equals(delivery.getStatus())
                && Objects.equals(delivery.getVersion(), token.deliveryVersion());
        boolean ackBeforeSentCompletion = validAckBeforeSentCompletion(
                token, disposition, delivery);
        if ((!ordinaryCompletion && !ackBeforeSentCompletion)
                || !Objects.equals(delivery.getActiveMessageId(), token.messageId())
                || !Objects.equals(delivery.getActiveAttempt(), token.deliveryActiveAttempt())
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

    private boolean validAckBeforeSentCompletion(
            AgentInboxClaimToken token,
            AgentInboxDisposition disposition,
            AgentCommandDeliveryEntity delivery) {
        if (disposition.type() != AgentInboxDisposition.Type.SENT
                || delivery.getVersion() == null) {
            return false;
        }
        long versionDelta;
        try {
            versionDelta = Math.subtractExact(
                    delivery.getVersion(), token.deliveryVersion());
        } catch (ArithmeticException overflow) {
            return false;
        }
        return switch (delivery.getStatus()) {
            case "RECEIVED" -> versionDelta == 1;
            case "STARTED" -> versionDelta == 2;
            case "SUCCEEDED", "FAILED" -> versionDelta == 3
                    || (versionDelta == 1 && token.deliveryActiveAttempt() > 1);
            case "REJECTED" -> versionDelta >= 1 && versionDelta <= 3;
            default -> false;
        };
    }

    private ValidatedMessage tokenIdentity(
            AgentInboxClaimToken token, byte[] wirePayload, byte[] wireHash) {
        if (!storedHashMatches(wirePayload, wireHash)) {
            throw tokenConflict(token, "INBOX_STORED_HASH_CORRUPT");
        }
        FrozenWireProvenance provenance;
        try {
            provenance = decodeFrozenWire(wirePayload);
        } catch (IllegalArgumentException malformed) {
            throw tokenConflict(token, "INBOX_WIRE_PROVENANCE_INVALID");
        }
        if (!token.messageId().equals(provenance.messageId())
                || !token.commandId().equals(provenance.commandId())
                || !token.tenantId().equals(provenance.tenantId())
                || !token.clientId().equals(provenance.clientId())
                || token.deliveryActiveAttempt() != provenance.activeAttempt()
                || token.expiresAt() != provenance.expiresAt()) {
            throw tokenConflict(token, "INBOX_WIRE_PROVENANCE_DRIFT");
        }
        return new ValidatedMessage(
                token.consumerName(), token.tenantId(), token.clientId(), token.messageId(),
                token.eventId(), token.commandId(), token.deliveryId(),
                provenance.taskId(), provenance.targetAgentId(), provenance.commandType(),
                provenance.activeAttempt(), provenance.expiresAt(),
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
        FrozenWireProvenance provenance;
        try {
            provenance = decodeFrozenWire(wireBytes);
        } catch (IllegalArgumentException malformed) {
            throw messageConflict(message, "WIRE_CANONICAL_PROVENANCE_INVALID");
        }
        ValidatedMessage validated = new ValidatedMessage(
                message.consumerName(), message.tenantId(), message.clientId(),
                message.messageId(), message.eventId(), message.commandId(), message.deliveryId(),
                provenance.taskId(), provenance.targetAgentId(), provenance.commandType(),
                provenance.activeAttempt(), provenance.expiresAt(),
                wireBytes, sha256(wireBytes));
        if (!message.messageId().equals(provenance.messageId())
                || !message.commandId().equals(provenance.commandId())
                || !message.tenantId().equals(provenance.tenantId())
                || !message.clientId().equals(provenance.clientId())) {
            throw conflict(validated, "WIRE_CALLER_IDENTITY_CONFLICT");
        }
        return validated;
    }

    private FrozenWireProvenance decodeFrozenWire(byte[] raw) {
        if (raw == null || raw.length == 0
                || raw.length > AgentCommandAmqpContract.MAX_WIRE_BYTES) {
            throw new IllegalArgumentException("wire size is invalid");
        }
        validateWireUtf8(raw);
        JsonNode root;
        try {
            root = STRICT_WIRE_JSON.readTree(raw);
        } catch (Exception malformed) {
            throw new IllegalArgumentException("wire JSON is invalid", malformed);
        }
        if (root == null || !root.isObject()
                || !integralEquals(root, "schemaVersion", AgentProtocolConstants.VERSION_1)
                || !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(wireText(root, "messageType"))
                || root.has("eventId") || root.has("deliveryId") || root.has("type")) {
            throw new IllegalArgumentException("wire envelope is invalid");
        }
        String messageId = wireExact(root, "messageId", 100);
        String commandId = wireExact(root, "commandId", 100);
        String tenantId = wireExact(root, "tenantId", 50);
        String clientId = wireExact(root, "clientId", 50);
        String taskId = wireExact(root, "taskId", 100);
        String targetAgentId = wireExact(root, "targetAgentId", 100);
        String commandType = wireExact(root, "commandType", 64);
        if (!COMMAND_TYPES.contains(commandType)) {
            throw new IllegalArgumentException("wire command type is invalid");
        }
        long activeAttempt = wireLong(root, "attempt");
        long expiresAt = wireLong(root, "expiresAt");
        if (activeAttempt <= 0 || activeAttempt > Integer.MAX_VALUE || expiresAt <= 0) {
            throw new IllegalArgumentException("wire numeric provenance is invalid");
        }
        if (root.has("agentId")
                && !targetAgentId.equals(wireText(root, "agentId"))) {
            throw new IllegalArgumentException("wire target alias conflicts");
        }
        rejectNestedWireConflict(root.get("payload"), "tenantId", tenantId);
        rejectNestedWireConflict(root.get("payload"), "clientId", clientId);
        rejectNestedWireConflict(root.get("payload"), "taskId", taskId);
        rejectNestedWireConflict(root.get("payload"), "targetAgentId", targetAgentId);
        rejectNestedWireConflict(root.get("payload"), "agentId", targetAgentId);
        rejectNestedWireConflict(root.get("payload"), "messageId", messageId);
        rejectNestedWireConflict(root.get("payload"), "commandId", commandId);
        return new FrozenWireProvenance(
                messageId, commandId, tenantId, clientId, taskId, targetAgentId,
                commandType, (int) activeAttempt, expiresAt);
    }

    private void validateWireUtf8(byte[] raw) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException("wire UTF-8 is invalid", malformed);
        }
    }

    private String wireExact(JsonNode root, String field, int maxChars) {
        String value = wireText(root, field);
        if (!validExact(value, maxChars)) {
            throw new IllegalArgumentException("wire field is invalid: " + field);
        }
        return value;
    }

    private String wireText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private long wireLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("wire numeric field is invalid: " + field);
        }
        return value.longValue();
    }

    private boolean integralEquals(JsonNode root, String field, long expected) {
        JsonNode value = root.get(field);
        return value != null && value.isIntegralNumber()
                && value.canConvertToLong() && value.longValue() == expected;
    }

    private void rejectNestedWireConflict(JsonNode payload, String field, String expected) {
        if (payload == null || !payload.isObject() || !payload.has(field)) return;
        JsonNode value = payload.get(field);
        if (!value.isTextual() || !expected.equals(value.textValue())) {
            throw new IllegalArgumentException("wire nested provenance conflicts: " + field);
        }
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

    private AgentInboxSourceNotSettledException sourceNotSettled(
            ValidatedMessage message, String reasonCode) {
        LOG.warn("event={} reason={} consumerName={} tenantId={} clientId={} messageId={} "
                        + "eventId={} commandId={} deliveryId={}",
                AgentInboxSourceNotSettledException.CODE, reasonCode,
                message.consumerName(), message.tenantId(), message.clientId(),
                message.messageId(), message.eventId(), message.commandId(),
                message.deliveryId());
        return new AgentInboxSourceNotSettledException(reasonCode);
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

    private AgentInboxIdentityConflictException messageConflict(
            AgentInboxMessage message, String reasonCode) {
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

    private boolean validExact(String value, int maxChars) {
        return value != null && !value.isEmpty() && value.length() <= maxChars
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl)
                && validSurrogates(value);
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
            String taskId,
            String targetAgentId,
            String commandType,
            int activeAttempt,
            long expiresAt,
            byte[] wireBytes,
            byte[] wireHash) {
    }

    private record FrozenWireProvenance(
            String messageId,
            String commandId,
            String tenantId,
            String clientId,
            String taskId,
            String targetAgentId,
            String commandType,
            int activeAttempt,
            long expiresAt) {
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
