package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryPublicationPayload;
import cn.jia.chat.entity.MemoryWorkItem;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.memory.MemoryRepositoryImpl;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.LongTermMemoryService;
import cn.jia.chat.service.SummaryGenerator;
import cn.jia.core.util.DateUtil;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * <p>
 * 长效记忆服务实现类
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Slf4j
@Lazy
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final String SYNC_STATUS_PENDING = "PENDING";
    private static final String SYNC_STATUS_SYNCED = "SYNCED";
    private static final String SUMMARY_TYPE_CONVERSATION = "conversation";
    private static final String AREA_ZERO_TENANT_ID = "0";
    private static final String AREA_ZERO_DATA_DOMAIN_ID = "cyf";
    private static final String MAINTENANCE_CLIENT_ID = "memory-maintenance";

    @Autowired
    private ChatMessageDao chatMessageDao;

    @Autowired
    private SummaryGenerator summaryGenerator;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private AgentTaskThreadMemoryGuard taskThreadMemoryGuard;

    @Autowired
    private cn.jia.chat.memory.MemoryRepository memoryRepository;
    @Autowired
    private MemoryPublicationDao memoryPublicationDao;

    @Autowired
    private MemoryPublicationCommitService publicationCommitService;


    @Value("${chat.memory.batch-size:100}")
    private int batchSize;

    @Value("${chat.memory.work.max-attempts:20}")
    private int pendingMaxAttempts;

    @Value("${chat.memory.work.lease-millis:60000}")
    private long workLeaseMillis;

    @Value("${chat.memory.retention-days:90}")
    private int retentionDays;

    // 日汇总配置
    @Value("${chat.memory.summary.daily-window-days:7}")
    private int dailyWindowDays;

    @Value("${chat.memory.summary.daily-summary-keep:30}")
    private int dailySummaryKeep;

    // 周汇总配置
    @Value("${chat.memory.summary.weekly-lookback-days:30}")
    private int weeklyLookbackDays;

    // 月汇总配置
    @Value("${chat.memory.summary.monthly-lookback-days:90}")
    private int monthlyLookbackDays;

    @Override
    public void syncConversation(String conversationId) {
        throw new IllegalStateException(
                "Unscoped memory synchronization is disabled; use the durable scoped work queue");
    }

    @Override
    public void syncOwnedConversation(
            String ownerJiacn, String clientId, String conversationId) {
        try {
            ChatConversationEntity conversation = chatConversationService.getOwned(
                    ownerJiacn, clientId, conversationId);
            ConversationDataScope scope = new ConversationDataScope(
                    new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"),
                            ownerJiacn, clientId),
                    ConversationDataScope.Mode.LEGACY_CLIENT);
            syncMessages(scope, conversationId,
                    requireGeneration(conversation),
                    chatMessageDao.findOwnedByConversationId(scope, conversationId),
                    null, null);
        } catch (Exception e) {
            log.error("Failed authenticated sync for conversation: {}", conversationId, e);
            throw new RuntimeException("Sync conversation failed: " + conversationId, e);
        }
    }

    private void syncMessages(
            ConversationDataScope scope, String conversationId, long generation,
            List<ChatMessageEntity> messages, MemoryWorkItem work, String workerId) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalStateException(
                    "No in-scope messages found for conversation: " + conversationId);
        }
        ValidatedMessageSnapshot snapshot = requireMessageBatch(
                scope, conversationId, generation, messages);
        long now = System.currentTimeMillis();
        String documentId = MemoryRepositoryImpl.deterministicConversationDocumentId(
                scope, conversationId, snapshot.token());
        MemoryPublicationRecord publication = memoryPublicationDao.stage(
                scope, conversationId, generation, snapshot.token(), documentId,
                snapshot.messages().size(), now);
        long publicationTimestamp = requirePublicationTimestamp(publication);
        PublicationResult publicationResult = requirePublicationPayload(
                publication, snapshot.messages());
        publication = publicationResult.publication();

        MemoryDocument document = new MemoryDocument();
        document.setJiacn(scope.ownerJiacn());
        document.setConversationId(conversationId);
        document.setContent(publicationResult.payload().summaryContent());
        document.setEmbedding(publicationResult.payload().embedding());
        document.setSummaryType(SUMMARY_TYPE_CONVERSATION);
        document.setTimestamp(publicationTimestamp);
        document.setSnapshotToken(snapshot.token());
        document.setLifecycleGeneration(generation);
        memoryRepository.saveConversationSnapshot(scope, document, publication);

        publicationCommitService.commit(
                scope, conversationId, snapshot.pendingMessageIds(), publication,
                work, workerId, System.currentTimeMillis());
        log.info("Published memory snapshot: conversation={}, token={}, revision={}, messages={}",
                conversationId, snapshot.token(), publication.getEsRevision(),
                snapshot.pendingMessageIds().size());
    }

    @Override
    public void syncPendingConversations() {
        validateWorkConfiguration();
        String workerId = "memory-" + UUID.randomUUID();
        int processed = 0;
        while (processed < batchSize) {
            long now = System.currentTimeMillis();
            MemoryWorkItem work = memoryPublicationDao.claimNext(
                    workerId, now, Math.addExact(now, workLeaseMillis));
            if (work == null) {
                break;
            }
            try {
                ConversationDataScope scope = work.toConversationScope("memory-worker");
                if (taskThreadMemoryGuard.isProtectedConversation(work.getConversationId())) {
                    memoryPublicationDao.dead(work, workerId,
                            "task-thread memory is excluded", System.currentTimeMillis());
                } else {
                    syncMessages(scope, work.getConversationId(), work.getLifecycleGeneration(),
                            chatMessageDao.findOwnedByConversationId(
                                    scope, work.getConversationId()),
                            work, workerId);
                }
            } catch (Exception failure) {
                long failedAt = System.currentTimeMillis();
                String reason = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                if (work.getAttempts() != null && work.getAttempts() >= pendingMaxAttempts) {
                    memoryPublicationDao.dead(work, workerId, reason, failedAt);
                } else {
                    int attempt = work.getAttempts() == null ? 1 : work.getAttempts();
                    long backoff = Math.min(300_000L, 1_000L << Math.min(attempt - 1, 8));
                    memoryPublicationDao.retry(work, workerId,
                            Math.addExact(failedAt, backoff), reason, failedAt);
                }
                log.error("Memory work failed: conversation={}, attempt={}",
                        work.getConversationId(), work.getAttempts(), failure);
            }
            processed++;
        }
        log.info("Durable pending memory sync completed. Claimed={}", processed);
    }
    @Override
    public void generateDailySummary() {
        throw aggregateSummariesDisabled();
    }

    @Override
    public void generateWeeklySummary() {
        throw aggregateSummariesDisabled();
    }

    @Override
    public void generateMonthlySummary() {
        throw aggregateSummariesDisabled();
    }

    private IllegalStateException aggregateSummariesDisabled() {
        return new IllegalStateException(
                "Daily/weekly/monthly memory aggregation is disabled until it is grouped by ConversationDataScope");
    }

    private ValidatedMessageSnapshot requireMessageBatch(
            ConversationDataScope scope, String conversationId, long generation,
            List<ChatMessageEntity> messages) {
        Objects.requireNonNull(scope, "scope");
        if (!StringUtils.hasText(conversationId) || generation < 1) {
            throw new IllegalStateException("Memory source identity is unavailable");
        }
        Set<Long> messageIds = new HashSet<>();
        List<Long> pendingMessageIds = new java.util.ArrayList<>();
        for (ChatMessageEntity message : messages) {
            if (message == null
                    || !conversationId.equals(message.getConversationId())
                    || !scope.tenantId().equals(message.getTenantId())
                    || !scope.ownerJiacn().equals(message.getJiacn())
                    || (scope.mode() == ConversationDataScope.Mode.LEGACY_CLIENT
                        && !scope.callerClientId().equals(message.getClientId()))
                    || (scope.mode() == ConversationDataScope.Mode.DOMAIN
                        && !scope.dataDomainId().equals(message.getDataDomainId()))) {
                throw new IllegalStateException(
                        "Memory source batch contains an out-of-scope message");
            }
            Long messageId = message.getId();
            if (messageId == null || messageId <= 0 || !messageIds.add(messageId)) {
                throw new IllegalStateException(
                        "Memory source batch contains a missing or duplicate message ID");
            }
            if (SYNC_STATUS_PENDING.equals(message.getSyncStatus())) {
                pendingMessageIds.add(messageId);
            }
        }
        if (pendingMessageIds.isEmpty()) {
            throw new IllegalStateException("Memory source snapshot has no pending messages");
        }
        return new ValidatedMessageSnapshot(
                List.copyOf(messages), List.copyOf(pendingMessageIds),
                snapshotToken(generation, messages));
    }

    static String snapshotToken(long generation, List<ChatMessageEntity> messages) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, Long.toString(generation));
            updateDigest(digest, Integer.toString(messages.size()));
            for (ChatMessageEntity message : messages) {
                updateDigest(digest, value(message.getId()));
                updateDigest(digest, message.getConversationId());
                updateDigest(digest, message.getMessageType());
                updateDigest(digest, message.getContent());
                updateDigest(digest, message.getMetadata());
                updateDigest(digest, message.getJiacn());
                updateDigest(digest, message.getTenantId());
                updateDigest(digest, message.getDataDomainId());
                updateDigest(digest, message.getClientId());
                updateDigest(digest, message.getSyncStatus());
                updateDigest(digest, message.getConversationType());
                updateDigest(digest, message.getSenderType());
                updateDigest(digest, message.getSenderName());
                updateDigest(digest, value(message.getCreateTime()));
                updateDigest(digest, value(message.getUpdateTime()));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update((byte) 0xff);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private long requireGeneration(ChatConversationEntity conversation) {
        if (conversation == null || conversation.getDeletedAt() != null
                || conversation.getLifecycleGeneration() == null
                || conversation.getLifecycleGeneration() < 1) {
            throw new IllegalStateException("Conversation lifecycle generation is unavailable");
        }
        return conversation.getLifecycleGeneration();
    }

    private long requirePublicationTimestamp(MemoryPublicationRecord publication) {
        if (publication == null || publication.getCreateTime() == null
                || publication.getCreateTime() <= 0) {
            throw new IllegalStateException(
                    "Memory publication stable timestamp is unavailable");
        }
        return publication.getCreateTime();
    }

    private PublicationResult requirePublicationPayload(
            MemoryPublicationRecord publication, List<ChatMessageEntity> messages) {
        if (publication == null) {
            throw new IllegalStateException("Memory publication is unavailable");
        }
        if ("ALLOCATED".equals(publication.getState())) {
            if (publication.getSummaryContent() != null
                    || publication.getEmbeddingBytes() != null
                    || publication.getPayloadDigest() != null) {
                throw new IllegalStateException(
                        "Allocated memory publication contains a partial payload");
            }
            String summary = summaryGenerator.summarizeConversation(messages);
            MemoryPublicationPayload candidate = MemoryPublicationPayload.generated(
                    summary, memoryRepository.generateEmbedding(summary));
            publication = memoryPublicationDao.bindPayload(
                    publication, candidate, System.currentTimeMillis());
        }
        if (!("STAGED".equals(publication.getState())
                || "PUBLISHED".equals(publication.getState()))) {
            throw new IllegalStateException("Memory publication payload is not staged");
        }
        MemoryPublicationPayload persisted = MemoryPublicationPayload.stored(
                publication.getSummaryContent(), publication.getEmbeddingBytes(),
                publication.getPayloadDigest());
        return new PublicationResult(publication, persisted);
    }

    private void validateWorkConfiguration() {
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalStateException("chat.memory.batch-size must be between 1 and 500");
        }
        if (pendingMaxAttempts < 1 || pendingMaxAttempts > 100) {
            throw new IllegalStateException("chat.memory.work.max-attempts must be between 1 and 100");
        }
        if (workLeaseMillis < 1_000L || workLeaseMillis > 3_600_000L) {
            throw new IllegalStateException("chat.memory.work.lease-millis is invalid");
        }
    }

    private record ValidatedMessageSnapshot(
            List<ChatMessageEntity> messages,
            List<Long> pendingMessageIds,
            String token) {
    }

    private record PublicationResult(
            MemoryPublicationRecord publication, MemoryPublicationPayload payload) {
    }

    @Override
    public void cleanupExpiredMemories() {
        throw new IllegalStateException(
                "Memory retention cleanup is disabled until deletion is publication-aware and scope-grouped");
    }

    @Override
    public List<ChatMessageEntity> getConversationMessagesForMaintenance(String conversationId) {
        throw new IllegalStateException("Unscoped memory maintenance reads are disabled");
    }
}
