package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryWorkItem;
import cn.jia.chat.mapper.MemoryPublicationMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Named
public class MemoryPublicationDaoImpl implements MemoryPublicationDao {
    private static final int MAX_VISIBILITY_BATCH = 500;

    @Inject private MemoryPublicationMapper mapper;
    @Inject private ChatConversationDao conversationDao;

    @Override
    public void signalInsertedMessage(long messageId, long now) {
        if (messageId <= 0 || mapper.signalForInsertedMessage(messageId, now) != 1) {
            throw new IllegalStateException("Pending memory message could not signal durable work");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryWorkItem claimNext(String workerId, long now, long leaseUntil) {
        requireIdentity(workerId, "workerId");
        if (leaseUntil <= now) throw new IllegalArgumentException("leaseUntil is invalid");
        for (int attempt = 0; attempt < 16; attempt++) {
            MemoryWorkItem candidate = mapper.findClaimCandidate(now);
            if (candidate == null) return null;
            if (mapper.claim(candidate.getId(), workerId, now, leaseUntil) == 1) {
                MemoryWorkItem claimed = mapper.findWorkById(candidate.getId());
                if (claimed == null || !"LEASED".equals(claimed.getState())
                        || !workerId.equals(claimed.getLeaseOwner())) {
                    throw new IllegalStateException("Claimed memory work disappeared");
                }
                return claimed;
            }
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryPublicationRecord stage(
            ConversationDataScope scope, String conversationId, long generation,
            String snapshotToken, String documentId, int messageCount, long now) {
        requireScope(scope);
        requireIdentity(conversationId, "conversationId");
        requireIdentity(snapshotToken, "snapshotToken");
        requireIdentity(documentId, "documentId");
        if (generation < 1 || messageCount < 1) throw new IllegalArgumentException("snapshot is invalid");
        if (now < 1) {
            throw new IllegalArgumentException("publication timestamp is invalid");
        }
        ChatConversationEntity conversation = conversationDao.lockScopedById(scope, conversationId);
        if (conversation == null || !Objects.equals(generation, conversation.getLifecycleGeneration())) {
            throw new IllegalStateException("Conversation generation is no longer live");
        }
        String scopeKey = scopeKey(scope);
        MemoryPublicationRecord existing = mapper.findByToken(
                scope.tenantId(), scope.mode().name(), scopeKey, scope.ownerJiacn(),
                conversationId, generation, snapshotToken);
        if (existing != null) {
            if (!documentId.equals(existing.getDocumentId())
                    || messageCount != existing.getMessageCount()) {
                throw new IllegalStateException("Snapshot token collision");
            }
            requireReusablePublication(existing);
            return existing;
        }
        long revision = Math.addExact(mapper.maxRevision(
                scope.tenantId(), scope.mode().name(), scopeKey,
                scope.ownerJiacn(), conversationId), 1L);
        MemoryPublicationRecord publication = new MemoryPublicationRecord()
                .setTenantId(scope.tenantId()).setScopeMode(scope.mode().name())
                .setScopeKey(scopeKey).setOwnerJiacn(scope.ownerJiacn())
                .setConversationId(conversationId).setLifecycleGeneration(generation)
                .setSnapshotToken(snapshotToken).setEsRevision(revision)
                .setDocumentId(documentId).setMessageCount(messageCount)
                .setState("ALLOCATED").setCreateTime(now).setUpdateTime(now);
        if (mapper.insertPublication(publication) != 1 || publication.getId() == null) {
            throw new IllegalStateException("Memory publication could not be staged");
        }
        return publication;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MemoryPublicationRecord bindPayload(
            MemoryPublicationRecord publication, MemoryPublicationPayload payload, long now) {
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(payload, "payload");
        if (publication.getId() == null || publication.getId() <= 0 || now < 1) {
            throw new IllegalArgumentException("publication payload binding is invalid");
        }
        requireAllocatedPublication(publication);
        MemoryPublicationPayload verified = MemoryPublicationPayload.stored(
                payload.summaryContent(), payload.embeddingBytes(), payload.payloadDigest());
        int bound = mapper.bindPayload(publication.getId(), verified.summaryContent(),
                verified.embeddingBytes(), verified.payloadDigest(), now);
        if (bound < 0 || bound > 1) {
            throw new IllegalStateException("Memory publication payload CAS was ambiguous");
        }
        MemoryPublicationRecord winner = mapper.findByIdForUpdate(publication.getId());
        requireSamePublication(publication, winner);
        requireCompletePayload(winner);
        return winner;
    }

    private void requireAllocatedPublication(MemoryPublicationRecord publication) {
        if (!"ALLOCATED".equals(publication.getState())
                || publication.getSummaryContent() != null
                || publication.getEmbeddingBytes() != null
                || publication.getPayloadDigest() != null) {
            throw new IllegalStateException(
                    "Only an empty allocated memory publication can bind a payload");
        }
    }

    private void requireReusablePublication(MemoryPublicationRecord publication) {
        if (publication.getCreateTime() == null || publication.getCreateTime() <= 0) {
            throw new IllegalStateException(
                    "Memory publication stable timestamp is unavailable");
        }
        if ("ALLOCATED".equals(publication.getState())) {
            if (publication.getSummaryContent() != null
                    || publication.getEmbeddingBytes() != null
                    || publication.getPayloadDigest() != null) {
                throw new IllegalStateException(
                        "Allocated memory publication contains a partial payload");
            }
            return;
        }
        requireCompletePayload(publication);
    }

    private void requireCompletePayload(MemoryPublicationRecord publication) {
        if (publication == null
                || !("STAGED".equals(publication.getState())
                || "PUBLISHED".equals(publication.getState()))) {
            throw new IllegalStateException("Memory publication payload is not staged");
        }
        MemoryPublicationPayload.stored(publication.getSummaryContent(),
                publication.getEmbeddingBytes(), publication.getPayloadDigest());
    }

    private void requireSamePublication(
            MemoryPublicationRecord expected, MemoryPublicationRecord actual) {
        if (actual == null || !Objects.equals(expected.getId(), actual.getId())
                || !Objects.equals(expected.getTenantId(), actual.getTenantId())
                || !Objects.equals(expected.getScopeMode(), actual.getScopeMode())
                || !Objects.equals(expected.getScopeKey(), actual.getScopeKey())
                || !Objects.equals(expected.getOwnerJiacn(), actual.getOwnerJiacn())
                || !Objects.equals(expected.getConversationId(), actual.getConversationId())
                || !Objects.equals(expected.getSnapshotToken(), actual.getSnapshotToken())
                || !Objects.equals(expected.getEsRevision(), actual.getEsRevision())
                || !Objects.equals(expected.getDocumentId(), actual.getDocumentId())
                || !Objects.equals(expected.getLifecycleGeneration(), actual.getLifecycleGeneration())
                || !Objects.equals(expected.getMessageCount(), actual.getMessageCount())
                || !Objects.equals(expected.getCreateTime(), actual.getCreateTime())) {
            throw new IllegalStateException("Memory publication payload winner is inconsistent");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publish(MemoryPublicationRecord publication, long now) {
        Objects.requireNonNull(publication, "publication");
        requireCompletePayload(publication);
        long numericConversationId;
        try { numericConversationId = Long.parseLong(publication.getConversationId()); }
        catch (NumberFormatException e) { throw new IllegalStateException("Invalid conversation ID", e); }
        if (mapper.pointConversationAtPublication(
                numericConversationId, publication.getTenantId(), publication.getScopeMode(),
                publication.getScopeKey(), publication.getOwnerJiacn(),
                publication.getLifecycleGeneration(), publication.getSnapshotToken(),
                publication.getEsRevision(), now) != 1) {
            throw new IllegalStateException("Conversation was deleted before publication");
        }
        if (mapper.markPublished(publication.getId(), now) != 1) {
            throw new IllegalStateException("Memory publication state is not publishable");
        }
    }

    @Override public void complete(MemoryWorkItem work, String workerId, long now) {
        if (mapper.completeWork(work.getId(), workerId, now) != 1)
            throw new IllegalStateException("Memory work lease was lost");
    }

    @Override public void retry(MemoryWorkItem work, String workerId, long dueAt, String error, long now) {
        release(work, workerId, "RETRY", dueAt, error, now);
    }

    @Override public void dead(MemoryWorkItem work, String workerId, String error, long now) {
        release(work, workerId, "DEAD", now, error, now);
    }

    private void release(MemoryWorkItem work, String workerId, String state,
                         long dueAt, String error, long now) {
        String bounded = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        if (mapper.releaseWork(work.getId(), workerId, state, dueAt, bounded, now) != 1)
            throw new IllegalStateException("Memory work lease was lost");
    }

    @Override
    public Set<String> findVisibleDocumentIds(
            ConversationDataScope scope, List<String> documentIds) {
        requireScope(scope);
        if (documentIds == null || documentIds.isEmpty()) return Set.of();
        List<String> distinct = documentIds.stream().distinct().toList();
        if (distinct.size() > MAX_VISIBILITY_BATCH) throw new IllegalArgumentException("visibility batch too large");
        distinct.forEach(id -> requireIdentity(id, "documentId"));
        List<String> visible = mapper.findVisibleDocumentIds(
                scope.tenantId(), scope.mode().name(), scopeKey(scope),
                scope.ownerJiacn(), distinct);
        if (visible == null) throw new IllegalStateException("Visibility query returned null");
        return Set.copyOf(new LinkedHashSet<>(visible));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void tombstone(ConversationDataScope scope, String conversationId, long now) {
        requireScope(scope);
        String key = scopeKey(scope);
        mapper.tombstonePublications(scope.tenantId(), scope.mode().name(), key,
                scope.ownerJiacn(), conversationId, now);
        mapper.cancelWork(scope.tenantId(), scope.mode().name(), key,
                scope.ownerJiacn(), conversationId, now);
    }

    private String scopeKey(ConversationDataScope scope) {
        return scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                ? scope.callerClientId() : scope.dataDomainId();
    }
    private void requireScope(ConversationDataScope scope) {
        Objects.requireNonNull(scope, "scope");
        requireIdentity(scope.tenantId(), "tenantId");
        requireIdentity(scope.ownerJiacn(), "ownerJiacn");
        requireIdentity(scopeKey(scope), "scopeKey");
    }
    private void requireIdentity(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " is invalid");
    }
}
