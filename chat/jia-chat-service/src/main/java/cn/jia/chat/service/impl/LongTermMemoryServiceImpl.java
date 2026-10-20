package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.core.lock.IDistributedLock;
import cn.jia.core.lock.ILock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.LongTermMemoryService;
import cn.jia.chat.service.SummaryGenerator;
import cn.jia.core.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

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

    @Autowired
    private ChatMessageDao chatMessageDao;

    @Autowired
    private ChatConversationDao chatConversationDao;

    @Autowired
    private IDistributedLock distributedLock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SummaryGenerator summaryGenerator;

    @Autowired
    private ChatConversationService chatConversationService;

    @Autowired
    private AgentTaskThreadMemoryGuard taskThreadMemoryGuard;

    @Autowired
    private cn.jia.chat.memory.MemoryRepository memoryRepository;

    @Value("${chat.memory.batch-size:100}")
    private int batchSize;

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
        long numericId = requireConversationId(conversationId);
        try (ILock ignored = distributedLock.lock("chat:memory:conversation:" + numericId)) {
            if (taskThreadMemoryGuard.isProtectedConversation(conversationId)) {
                chatMessageDao.updateSyncStatusByConversationId(
                        conversationId, AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED);
                return;
            }
            // Parent is the trusted source of identity, never the first message in an unscoped read.
            ChatConversationEntity parent = chatConversationDao.selectById(numericId);
            if (parent == null || parent.getDeletedAt() != null || !"0".equals(parent.getTenantId())) {
                return;
            }
            syncOwnedSnapshot(parent.getJiacn(), parent.getClientId(), conversationId);
        }
    }

    @Override
    public void syncOwnedConversation(String ownerJiacn, String clientId, String conversationId) {
        long numericId = requireConversationId(conversationId);
        try (ILock ignored = distributedLock.lock("chat:memory:conversation:" + numericId)) {
            syncOwnedSnapshot(ownerJiacn, clientId, conversationId);
        }
    }

    private void syncOwnedSnapshot(String owner, String client, String conversationId) {
        ChatConversationEntity parent = chatConversationDao.findScopedById(owner, client, conversationId);
        if (parent == null || parent.getDeletedAt() != null || !"0".equals(parent.getTenantId())
                || !Objects.equals(owner, parent.getJiacn()) || !Objects.equals(client, parent.getClientId())
                || !StringUtils.hasText(owner) || !StringUtils.hasText(client)) {
            throw new IllegalStateException("Memory source conversation is not live or owned");
        }
        if (taskThreadMemoryGuard.isProtectedConversation(conversationId)) {
            throw new IllegalStateException("Task threads are excluded from account memory");
        }
        List<ChatMessageEntity> messages = chatConversationService.findOwnedMessages(owner, client, conversationId);
        for (ChatMessageEntity message : messages) {
            if (message == null || message.getId() == null || message.getId() <= 0
                    || !owner.equals(message.getJiacn()) || !client.equals(message.getClientId())
                    || !"0".equals(message.getTenantId()) || !conversationId.equals(message.getConversationId())) {
                throw new IllegalStateException("Memory message snapshot identity mismatch");
            }
        }
        List<ChatMessageEntity> snapshot = messages.stream()
                .filter(m -> "USER".equals(m.getMessageType()) || "ASSISTANT".equals(m.getMessageType()))
                .filter(m -> !AgentTaskThreadConstants.MEMORY_SYNC_EXCLUDED.equals(m.getSyncStatus()))
                .toList();
        List<Long> pendingIds = snapshot.stream().filter(m -> SYNC_STATUS_PENDING.equals(m.getSyncStatus()))
                .map(ChatMessageEntity::getId).toList();
        if (pendingIds.isEmpty()) {
            return;
        }
        MemoryDocument document = new MemoryDocument();
        // One account/client/conversation snapshot overwrites itself on retry after an ES/DB partial failure.
        String snapshotKey = owner + "\0" + client + "\0" + conversationId + "\0"
                + snapshot.stream().map(m -> Long.toString(m.getId())).sorted().toList();
        document.setId("conversation-" + UUID.nameUUIDFromBytes(snapshotKey.getBytes(StandardCharsets.UTF_8)));
        document.setJiacn(owner);
        document.setConversationId(conversationId);
        document.setContent(summaryGenerator.summarizeConversation(snapshot));
        document.setSummaryType(SUMMARY_TYPE_CONVERSATION);
        document.setTimestamp(System.currentTimeMillis());
        // Both model calls happen outside the database lock/transaction.
        document.setEmbedding(memoryRepository.generateEmbedding(document.getContent()));
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ChatConversationEntity locked = chatConversationDao.lockScopedById(owner, client, conversationId);
            if (locked == null || locked.getDeletedAt() != null
                    || !Objects.equals(parent.getLifecycleGeneration(), locked.getLifecycleGeneration())
                    || taskThreadMemoryGuard.isProtectedConversation(conversationId)) {
                throw new IllegalStateException("Memory source changed during summarization");
            }
            memoryRepository.savePrepared(document);
            int updated = chatMessageDao.markMemorySnapshotSynced(owner, client, conversationId, pendingIds);
            if (updated != pendingIds.size()) {
                throw new IllegalStateException("Memory snapshot status update was incomplete");
            }
        });
    }

    private long requireConversationId(String value) {
        long id = Long.parseLong(value);
        if (id <= 0 || !Long.toString(id).equals(value)) {
            throw new IllegalArgumentException("Canonical conversationId required");
        }
        return id;
    }

    @Override
    public void syncPendingConversations() {
        long afterId = 0;
        boolean failed = false;
        while (true) {
            List<String> ids = chatMessageDao.findPendingConversationIds(SYNC_STATUS_PENDING, batchSize, afterId);
            if (ids.isEmpty()) {
                break;
            }
            for (String id : ids) {
                long numericId = requireConversationId(id);
                if (numericId <= afterId) {
                    throw new IllegalStateException("Memory scan cursor did not advance");
                }
                afterId = numericId;
                try {
                    syncConversation(id);
                } catch (RuntimeException exception) {
                    failed = true;
                    log.error("Memory sync failed; preserving pending snapshot, conversationId={}", id, exception);
                }
            }
        }
        if (failed) {
            throw new IllegalStateException("Memory sync failed for some conversations; pending snapshots retained");
        }
    }

    @Override
    public void generateDailySummary() {
        log.info("Starting generate daily summary...");

        try {
            long windowMillis = dailyWindowDays * 24 * 60 * 60 * 1000L;
            long sinceTime = DateUtil.nowTime() - windowMillis;
            List<String> activeJiacns = chatMessageDao.findActiveJiacns(sinceTime);

            LocalDate today = LocalDate.now();
            String dateStr = today.toString();

            boolean failed = false;
            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的会话摘要
                    List<String> conversationSummaries = memoryRepository.findConversationSummariesByJiacn(jiacn, sinceTime);

                    if (conversationSummaries != null && !conversationSummaries.isEmpty()) {
                        // 生成日汇总
                        String dailySummary = summaryGenerator.generateDailySummary(conversationSummaries, jiacn, dateStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
                        document.setId("daily-" + UUID.nameUUIDFromBytes(
                                (jiacn + "\0" + dateStr).getBytes(StandardCharsets.UTF_8)));
                        document.setJiacn(jiacn);
                        document.setContent(dailySummary);
                        document.setSummaryType("daily_summary");
                        document.setTimestamp(DateUtil.nowTime());
                        document.setPeriodStart(sinceTime);
                        document.setPeriodEnd(DateUtil.nowTime());

                        memoryRepository.save(document);

                        log.debug("Generated daily summary for user: {}", jiacn);
                    }
                } catch (Exception e) {
                    failed = true;
                    log.error("Failed to generate daily summary for user: {}", jiacn, e);
                    // 继续处理下一个用户
                }
            }

            if (failed) {
                throw new IllegalStateException("daily memory summary failed for some owners");
            }
            log.info("Generate daily summary completed. Processed {} users", activeJiacns.size());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate daily summary", e);
        }
    }

    @Override
    public void generateWeeklySummary() {
        log.info("Starting generate weekly summary...");

        try {
            long lookbackMillis = weeklyLookbackDays * 24 * 60 * 60 * 1000L;
            long sinceTime = DateUtil.nowTime() - lookbackMillis;

            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.minusWeeks(1);
            String weekStartStr = weekStart.toString();

            List<String> activeJiacns = chatMessageDao.findActiveJiacns(sinceTime);

            boolean failed = false;
            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的日汇总
                    List<String> dailySummaries = memoryRepository.findDailySummariesByJiacn(jiacn, sinceTime);

                    if (dailySummaries != null && !dailySummaries.isEmpty()) {
                        // 生成周汇总
                        String weeklySummary = summaryGenerator.generateWeeklySummary(dailySummaries, jiacn, weekStartStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
                        document.setId("weekly-" + UUID.nameUUIDFromBytes(
                                (jiacn + "\0" + weekStartStr).getBytes(StandardCharsets.UTF_8)));
                        document.setJiacn(jiacn);
                        document.setContent(weeklySummary);
                        document.setSummaryType("weekly_summary");
                        document.setTimestamp(DateUtil.nowTime());
                        document.setPeriodStart(weekStart.toEpochDay() * 24 * 60 * 60 * 1000L);
                        document.setPeriodEnd(today.toEpochDay() * 24 * 60 * 60 * 1000L);

                        memoryRepository.save(document);

                        log.debug("Generated weekly summary for user: {}", jiacn);
                    }
                } catch (Exception e) {
                    failed = true;
                    log.error("Failed to generate weekly summary for user: {}", jiacn, e);
                }
            }

            if (failed) {
                throw new IllegalStateException("weekly memory summary failed for some owners");
            }
            log.info("Generate weekly summary completed");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate weekly summary", e);
        }
    }

    @Override
    public void generateMonthlySummary() {
        log.info("Starting generate monthly summary...");

        try {
            long lookbackMillis = monthlyLookbackDays * 24 * 60 * 60 * 1000L;
            long sinceTime = DateUtil.nowTime() - lookbackMillis;

            LocalDate today = LocalDate.now();
            LocalDate monthStart = today.withDayOfMonth(1);
            String monthStr = monthStart.toString().substring(0, 7);

            List<String> activeJiacns = chatMessageDao.findActiveJiacns(sinceTime);

            boolean failed = false;
            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的周汇总
                    List<String> weeklySummaries = memoryRepository.findWeeklySummariesByJiacn(jiacn, sinceTime);

                    if (weeklySummaries != null && !weeklySummaries.isEmpty()) {
                        // 生成月汇总
                        String monthlySummary = summaryGenerator.generateMonthlySummary(weeklySummaries, jiacn, monthStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
                        document.setId("monthly-" + UUID.nameUUIDFromBytes(
                                (jiacn + "\0" + monthStr).getBytes(StandardCharsets.UTF_8)));
                        document.setJiacn(jiacn);
                        document.setContent(monthlySummary);
                        document.setSummaryType("monthly_summary");
                        document.setTimestamp(DateUtil.nowTime());
                        document.setPeriodStart(monthStart.toEpochDay() * 24 * 60 * 60 * 1000L);
                        document.setPeriodEnd(today.toEpochDay() * 24 * 60 * 60 * 1000L);

                        memoryRepository.save(document);

                        log.debug("Generated monthly summary for user: {}", jiacn);
                    }
                } catch (Exception e) {
                    failed = true;
                    log.error("Failed to generate monthly summary for user: {}", jiacn, e);
                }
            }

            if (failed) {
                throw new IllegalStateException("monthly memory summary failed for some owners");
            }
            log.info("Generate monthly summary completed");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate monthly summary", e);
        }
    }

    @Override
    public void cleanupExpiredMemories() {
        log.info("Starting cleanup expired memories...");

        try {
            long retentionMillis = retentionDays * 24 * 60 * 60 * 1000L;
            long beforeTime = DateUtil.nowTime() - retentionMillis;

            int totalDeleted = 0;
            List<Long> expiredMessageIds;

            do {
                // 批量删除过期消息
                expiredMessageIds = chatMessageDao.findExpiredMessageIds(beforeTime, batchSize);

                if (!expiredMessageIds.isEmpty()) {
                    chatMessageDao.deleteBatchIds(expiredMessageIds);
                    totalDeleted += expiredMessageIds.size();
                    log.debug("Deleted {} expired messages", expiredMessageIds.size());
                }
            } while (!expiredMessageIds.isEmpty());

            // 清理向量库中的过期会话摘要
            memoryRepository.deleteExpiredDocuments(beforeTime);

            log.info("Cleanup expired memories completed. Total deleted: {}", totalDeleted);
        } catch (Exception e) {
            log.error("Failed to cleanup expired memories", e);
        }
    }

    @Override
    public List<ChatMessageEntity> getConversationMessagesForMaintenance(String conversationId) {
        return chatMessageDao.findByConversationIdForMaintenance(conversationId);
    }
}
