package cn.jia.chat.archive.maintenance.service;

import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.service.BuiltinHallAgentSupport;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.JuyitingConversationScopeService;
import cn.jia.core.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded durable projector for archive business facts. Chat persistence and the outbox
 * acknowledgement share one local database transaction; websocket publication is best-effort
 * after commit and is never the delivery authority.
 */
@Component
@ConditionalOnProperty(prefix = "archive.maintenance.business-outbox", name = "enabled", havingValue = "true")
@Slf4j
public class ArchiveBusinessOutboxDispatcher {
    private static final int BATCH = 32;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Set<String> READ_PERMISSIONS = Set.of("job.create", "job.manage");

    private final ArchiveMaintenanceStore store;
    private final ArchiveTransactions transactions;
    private final ChatConversationDao conversationDao;
    private final ChatConversationService conversations;
    private final ChatConversationEventBroker broker;
    private final JuyitingConversationScopeService scopes;
    private final BuiltinHallAgentSupport builtin;
    private final Clock clock;

    @Autowired
    public ArchiveBusinessOutboxDispatcher(ArchiveMaintenanceStore store,
            ArchiveTransactions transactions,
            ChatConversationDao conversationDao,
            ChatConversationService conversations,
            ChatConversationEventBroker broker,
            JuyitingConversationScopeService scopes,
            BuiltinHallAgentSupport builtin) {
        this(store, transactions, conversationDao, conversations, broker, scopes, builtin,
                Clock.systemUTC());
    }

    ArchiveBusinessOutboxDispatcher(ArchiveMaintenanceStore store,
            ArchiveTransactions transactions,
            ChatConversationDao conversationDao,
            ChatConversationService conversations,
            ChatConversationEventBroker broker,
            JuyitingConversationScopeService scopes,
            BuiltinHallAgentSupport builtin,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.conversationDao = Objects.requireNonNull(conversationDao);
        this.conversations = Objects.requireNonNull(conversations);
        this.broker = Objects.requireNonNull(broker);
        this.scopes = Objects.requireNonNull(scopes);
        this.builtin = Objects.requireNonNull(builtin);
        this.clock = Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString = "${archive.maintenance.business-outbox.delay-ms:5000}")
    public void scheduledRecovery() {
        try {
            dispatchOnce(BATCH);
        } catch (Throwable unavailable) {
            log.warn("Archive business outbox recovery pass failed", unavailable);
        }
    }

    public int dispatchOnce() {
        return dispatchOnce(BATCH);
    }

    int dispatchOnce(int limit) {
        Instant now = clock.instant();
        List<ArchiveBusinessOutboxRecord> candidates = store.findBusinessOutboxCandidates(
                now, null, null, Math.max(1, Math.min(BATCH, limit)));
        int completed = 0;
        for (ArchiveBusinessOutboxRecord candidate : candidates) {
            ArchiveBusinessOutboxRecord claim = claim(candidate, now);
            if (claim == null) continue;
            try {
                if (deliver(claim)) completed++;
            } catch (Throwable failure) {
                retry(claim, failure);
            }
        }
        return completed;
    }

    private ArchiveBusinessOutboxRecord claim(ArchiveBusinessOutboxRecord candidate, Instant now) {
        try {
            return transactions.requiresNew(() -> {
                if (store.claimBusinessOutbox(candidate.projectionKey(), candidate.fencingToken(),
                        candidate.state(), now, now.plus(LEASE)) != 1) return null;
                ArchiveBusinessOutboxRecord claimed = store.findBusinessOutbox(
                        candidate.projectionKey(), true);
                if (claimed == null || !"LEASED".equals(claimed.state())
                        || claimed.fencingToken() != candidate.fencingToken() + 1) {
                    throw new IllegalStateException("Archive business outbox claim changed");
                }
                return claimed;
            });
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private boolean deliver(ArchiveBusinessOutboxRecord claim) {
        ArchiveMaintenanceJobRecord observed = store.findJob(claim.jobId(), false);
        if (observed == null) throw new IllegalStateException("Archive business outbox job is unavailable");
        ArchiveActorScope actor = new ArchiveActorScope(observed.tenantId(), observed.clientId(),
                observed.ownerJiacn());
        return Boolean.TRUE.equals(transactions.requiresNew(() -> {
            ArchiveManagerGrantRecord manager = store.findManagerGrant(actor,
                    observed.collectionId(), true);
            ArchiveMaintenanceJobRecord job = store.findJob(claim.jobId(), true);
            if (job == null || !sameJobScope(observed, job)) {
                throw new IllegalStateException("Archive business outbox job scope changed");
            }
            ArchiveBusinessOutboxRecord current = store.findBusinessOutbox(
                    claim.projectionKey(), true);
            if (!currentClaim(current, claim)) return false;
            if (!canRead(manager) || manager.revision() < job.managerAuthorizationRevision()) {
                return noTarget(current, "MANAGER_AUTHORIZATION_REVOKED");
            }
            // Resolve and lock the immutable archive source facts before crossing into the
            // shared chat root. This preserves the archive-root -> conversation lock order.
            Projection projection = projection(current, job);
            ArchiveConfirmedRequestRecord confirmation = store.findConfirmedRequestForJob(
                    job.jobId(), true);
            if (!validConfirmation(job, confirmation)) {
                return noTarget(current, "CONFIRMED_CHAT_TARGET_UNAVAILABLE");
            }
            ChatConversationEntity conversation = conversationDao.lockScopedByIdIncludingDeleted(
                    job.ownerJiacn(), job.clientId(), confirmation.conversationId());
            if (!validConversation(conversation, confirmation)) {
                return noTarget(current, "CONVERSATION_GENERATION_FENCED");
            }

            ChatMessageEntity message = message(conversation, confirmation, projection.content());
            ChatMessageEntity saved = conversations.appendOwnedMessage(job.ownerJiacn(),
                    job.clientId(), message, confirmation.conversationGeneration());
            if (saved == null || saved.getId() == null) {
                throw new IllegalStateException("Archive business projection was not persisted");
            }
            if (store.completeBusinessOutbox(current.projectionKey(), current.fencingToken(),
                    "DELIVERED", saved.getId(), null) != 1) {
                throw new IllegalStateException("Archive business projection acknowledgement raced");
            }
            long generation = confirmation.conversationGeneration();
            String conversationId = confirmation.conversationId();
            String agentId = "DIRECT_PRIVATE".equals(confirmation.entryPoint())
                    ? confirmation.targetAgentId() : builtin.defaultAgentId();
            String senderName = "DIRECT_PRIVATE".equals(confirmation.entryPoint())
                    ? "appointed-agent" : BuiltinHallAgentSupport.SONGJIANG_NAME;
            Map<String, Object> event = messageEvent(saved, conversationId, agentId,
                    senderName, projection.content(), conversation.getConversationType());
            transactions.afterCommit(() -> broker.publishIfLive(conversationId, generation,
                    () -> conversations.isLiveGeneration(job.ownerJiacn(), job.clientId(),
                            conversationId, generation), event));
            return true;
        }));
    }

    private Projection projection(ArchiveBusinessOutboxRecord outbox,
            ArchiveMaintenanceJobRecord job) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "archive_maintenance_receipt");
        root.put("schemaVersion", 1);
        root.put("projectionKey", outbox.projectionKey());
        // Existing Web v1 receipt parsing requires this compatibility envelope. It remains a
        // reference only; every displayed fact must still be reauthorized through the Admin API.
        root.put("archiveMaintenance", Map.of("jobId", job.jobId()));

        Map<String, Object> jobRef = new LinkedHashMap<>();
        jobRef.put("jobId", job.jobId());
        jobRef.put("collectionId", job.collectionId());
        jobRef.put("state", job.state());
        jobRef.put("revision", Long.toString(job.revision()));
        jobRef.put("workId", job.workId());
        jobRef.put("publicationId", job.publicationId());
        root.put("jobRef", jobRef);

        if ("JOB_EVENT".equals(outbox.sourceType())) {
            ArchiveJobEventRecord event = store.findJobEvent(job.jobId(),
                    Objects.requireNonNull(outbox.eventSequence()), true);
            if (event == null || !job.jobId().equals(event.jobId())
                    || event.sequence() != outbox.eventSequence()) {
                throw new IllegalStateException("Archive business event is unavailable");
            }
            root.put("event", Map.of("type", event.type(),
                    "sequence", Long.toString(event.sequence()),
                    "jobRevision", Long.toString(event.jobRevision()),
                    "occurredAt", event.occurredAt()));
        } else if (!"WITHDRAWAL".equals(outbox.sourceType())) {
            throw new IllegalStateException("Archive business source type is invalid");
        }

        ArchivePublicationRecord publication = job.publicationId() == null ? null
                : store.findPublicationById(job.publicationId(), true);
        if (publication != null && (!job.jobId().equals(publication.jobId())
                || !job.collectionId().equals(publication.collectionId())
                || !Objects.equals(job.workId(), publication.workId()))) {
            throw new IllegalStateException("Archive business publication scope changed");
        }
        ArchiveWithdrawalRecord withdrawal = null;
        if (publication != null) {
            ArchivePublicationReadbackRecord readback = store.findPublicationReadback(
                    publication.publicationId(), true);
            withdrawal = store.findWithdrawalByPublication(publication.publicationId(), true);
            Map<String, Object> publicationFact = new LinkedHashMap<>();
            publicationFact.put("publicationId", publication.publicationId());
            publicationFact.put("workId", publication.workId());
            publicationFact.put("editionId", publication.editionId());
            publicationFact.put("state", publication.state());
            if (readback != null) {
                Map<String, Object> verification = new LinkedHashMap<>();
                verification.put("state", readback.state());
                verification.put("revision", Long.toString(readback.revision()));
                verification.put("verificationDigest", readback.verificationDigest());
                verification.put("checkedAt", readback.checkedAt() == null
                        ? null : readback.checkedAt().toString());
                publicationFact.put("verification", verification);
            }
            if (withdrawal != null) {
                publicationFact.put("withdrawal", Map.of(
                        "withdrawalId", withdrawal.withdrawalId(),
                        "resultingWorkRevision", Long.toString(withdrawal.resultingWorkRevision()),
                        "withdrawnAt", withdrawal.withdrawnAt().toString()));
            }
            root.put("publication", publicationFact);
        }
        if ("WITHDRAWAL".equals(outbox.sourceType())) {
            if (publication == null || withdrawal == null
                    || !Objects.equals(outbox.withdrawalId(), withdrawal.withdrawalId())
                    || !publication.publicationId().equals(withdrawal.publicationId())) {
                throw new IllegalStateException("Archive business withdrawal is unavailable");
            }
        }
        return new Projection(JsonUtil.toSafeJson(root));
    }

    private ChatMessageEntity message(ChatConversationEntity conversation,
            ArchiveConfirmedRequestRecord confirmation, String content) {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setConversationId(confirmation.conversationId());
        message.setConversationType(conversation.getConversationType());
        message.setMessageType("ASSISTANT");
        message.setContent(content);
        message.setSenderType("agent");
        message.setSenderName("DIRECT_PRIVATE".equals(confirmation.entryPoint())
                ? "appointed-agent" : BuiltinHallAgentSupport.SONGJIANG_NAME);
        message.setSyncStatus("PENDING");
        message.setMetadata(JsonUtil.toJson(Map.of("archiveMaintenance", true,
                "projection", true)));
        return message;
    }

    private boolean noTarget(ArchiveBusinessOutboxRecord current, String code) {
        if (store.completeBusinessOutbox(current.projectionKey(), current.fencingToken(),
                "NO_TARGET", null, code) != 1) {
            throw new IllegalStateException("Archive business no-target acknowledgement raced");
        }
        return true;
    }

    private void retry(ArchiveBusinessOutboxRecord claim, Throwable failure) {
        try {
            long seconds = Math.min(60L, 1L << Math.min(6L, Math.max(0L, claim.attemptCount() - 1L)));
            transactions.requiresNew(() -> {
                store.retryBusinessOutbox(claim.projectionKey(), claim.fencingToken(),
                        clock.instant().plusSeconds(seconds), "PROJECTION_UNAVAILABLE");
                return null;
            });
        } catch (Throwable retryFailure) {
            log.warn("Archive business outbox retry could not be persisted. projectionKey={}",
                    claim.projectionKey(), retryFailure);
        }
        log.warn("Archive business projection failed. projectionKey={}", claim.projectionKey(), failure);
    }

    private boolean validConfirmation(ArchiveMaintenanceJobRecord job,
            ArchiveConfirmedRequestRecord confirmation) {
        return confirmation != null
                && same(job.tenantId(), confirmation.tenantId())
                && same(job.clientId(), confirmation.clientId())
                && same(job.ownerJiacn(), confirmation.ownerJiacn())
                && same(job.collectionId(), confirmation.collectionId())
                && same(job.requestIntentId(), confirmation.requestIntentId())
                && confirmation.conversationId() != null
                && confirmation.conversationGeneration() != null
                && confirmation.conversationGeneration() >= 1
                && Set.of("SONGJIANG", "DIRECT_PRIVATE").contains(confirmation.entryPoint())
                && ("SONGJIANG".equals(confirmation.entryPoint())
                    ? confirmation.targetAgentId() == null
                    : confirmation.targetAgentId() != null);
    }

    private boolean validConversation(ChatConversationEntity conversation,
            ArchiveConfirmedRequestRecord confirmation) {
        if (conversation == null || conversation.getId() == null || conversation.getDeletedAt() != null
                || !confirmation.conversationId().equals(Long.toString(conversation.getId()))
                || !same(confirmation.ownerJiacn(), conversation.getJiacn())
                || !same(confirmation.clientId(), conversation.getClientId())
                || !same(confirmation.tenantId(), conversation.getTenantId())
                || !JuyitingConversationScopeService.CONVERSATION_TYPE_JUYITING.equals(
                        conversation.getConversationType())
                || !confirmation.conversationGeneration().equals(conversation.getLifecycleGeneration())) {
            return false;
        }
        try {
            if ("DIRECT_PRIVATE".equals(confirmation.entryPoint())) {
                return JuyitingConversationScopeService.SCOPE_PRIVATE.equals(
                        conversation.getConversationScopeType())
                        && same(confirmation.targetAgentId(), conversation.getTargetAgentId())
                        && scopes.parsePersistedTargetAgentIds(
                                conversation.getTargetAgentIds()).equals(List.of(confirmation.targetAgentId()));
            }
            return !JuyitingConversationScopeService.SCOPE_PRIVATE.equals(
                    conversation.getConversationScopeType())
                    && scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds())
                    .equals(List.of(builtin.defaultAgentId()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private boolean canRead(ArchiveManagerGrantRecord manager) {
        if (manager == null || !"ACTIVE".equals(manager.state())) return false;
        for (String permission : manager.permissions().split(",", -1)) {
            if (READ_PERMISSIONS.contains(permission.strip())) return true;
        }
        return false;
    }

    private boolean currentClaim(ArchiveBusinessOutboxRecord current,
            ArchiveBusinessOutboxRecord claim) {
        return current != null && "LEASED".equals(current.state())
                && current.fencingToken() == claim.fencingToken()
                && same(current.projectionKey(), claim.projectionKey())
                && same(current.jobId(), claim.jobId())
                && validProjectionKey(current);
    }

    private boolean validProjectionKey(ArchiveBusinessOutboxRecord outbox) {
        if ("JOB_EVENT".equals(outbox.sourceType()) && outbox.eventSequence() != null
                && outbox.withdrawalId() == null) {
            return outbox.projectionKey().equals("EVENT:" + outbox.jobId()
                    + ":" + outbox.eventSequence());
        }
        if ("WITHDRAWAL".equals(outbox.sourceType()) && outbox.eventSequence() == null
                && outbox.withdrawalId() != null) {
            return outbox.projectionKey().equals("WITHDRAWAL:" + outbox.withdrawalId());
        }
        return false;
    }

    private boolean sameJobScope(ArchiveMaintenanceJobRecord observed,
            ArchiveMaintenanceJobRecord locked) {
        return same(observed.jobId(), locked.jobId())
                && same(observed.collectionId(), locked.collectionId())
                && same(observed.tenantId(), locked.tenantId())
                && same(observed.clientId(), locked.clientId())
                && same(observed.ownerJiacn(), locked.ownerJiacn());
    }

    private Map<String, Object> messageEvent(ChatMessageEntity saved, String conversationId,
            String agentId, String senderName, String content, String conversationType) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "agent_message");
        event.put("messageId", Long.toString(saved.getId()));
        event.put("conversationId", conversationId);
        event.put("conversationType", conversationType);
        event.put("agentId", agentId);
        event.put("senderType", "agent");
        event.put("senderName", senderName);
        event.put("content", content);
        return event;
    }

    private boolean same(String left, String right) {
        return Objects.equals(left, right);
    }

    private record Projection(String content) { }
}
