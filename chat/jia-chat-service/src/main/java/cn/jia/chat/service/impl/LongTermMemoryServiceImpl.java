package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.memory.MemoryDocument;
import cn.jia.chat.service.LongTermMemoryService;
import cn.jia.chat.service.SummaryGenerator;
import cn.jia.core.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
@Service
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final String SYNC_STATUS_PENDING = "PENDING";
    private static final String SYNC_STATUS_SYNCED = "SYNCED";
    private static final String SUMMARY_TYPE_CONVERSATION = "conversation";

    @Autowired
    private ChatMessageDao chatMessageDao;

    @Autowired
    private SummaryGenerator summaryGenerator;

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
        if (!StringUtils.hasText(conversationId)) {
            log.warn("Skipping sync: conversationId is empty");
            return;
        }

        try {
            log.info("Starting sync for conversation: {}", conversationId);

            // 1. 获取会话消息
            List<ChatMessageEntity> messages = chatMessageDao.findByConversationId(conversationId);
            if (messages == null || messages.isEmpty()) {
                log.warn("No messages found for conversation: {}", conversationId);
                chatMessageDao.updateSyncStatusByConversationId(conversationId, SYNC_STATUS_SYNCED);
                return;
            }

            // 2. 调用 LLM 生成摘要
            String summary = summaryGenerator.summarizeConversation(messages);

            // 3. 获取第一个消息的 jiacn
            String jiacn = messages.getFirst().getJiacn();

            // 4. 写入向量库
            MemoryDocument document = new MemoryDocument();
            document.setJiacn(jiacn);
            document.setConversationId(conversationId);
            document.setContent(summary);
            document.setSummaryType(SUMMARY_TYPE_CONVERSATION);
            document.setTimestamp(System.currentTimeMillis());

            memoryRepository.save(document);

            // 5. 更新同步状态
            chatMessageDao.updateSyncStatusByConversationId(conversationId, SYNC_STATUS_SYNCED);

            log.info("Successfully synced conversation: {}", conversationId);
        } catch (Exception e) {
            log.error("Failed to sync conversation: {}", conversationId, e);
            throw new RuntimeException("Sync conversation failed: " + conversationId, e);
        }
    }

    @Override
    public void syncPendingConversations() {
        log.info("Starting sync pending conversations...");

        int totalProcessed = 0;
        List<String> pendingConversationIds;

        do {
            pendingConversationIds = chatMessageDao.findPendingConversationIds(SYNC_STATUS_PENDING, batchSize);

            for (String conversationId : pendingConversationIds) {
                try {
                    syncConversation(conversationId);
                    totalProcessed++;
                } catch (Exception e) {
                    log.error("Failed to sync conversation: {}", conversationId, e);
                    // 继续处理下一个会话
                }
            }
        } while (!pendingConversationIds.isEmpty());

        log.info("Sync pending conversations completed. Total processed: {}", totalProcessed);
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

            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的会话摘要
                    List<String> conversationSummaries = memoryRepository.findConversationSummariesByJiacn(jiacn, sinceTime);

                    if (conversationSummaries != null && !conversationSummaries.isEmpty()) {
                        // 生成日汇总
                        String dailySummary = summaryGenerator.generateDailySummary(conversationSummaries, jiacn, dateStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
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
                    log.error("Failed to generate daily summary for user: {}", jiacn, e);
                    // 继续处理下一个用户
                }
            }

            log.info("Generate daily summary completed. Processed {} users", activeJiacns.size());
        } catch (Exception e) {
            log.error("Failed to generate daily summary", e);
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

            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的日汇总
                    List<String> dailySummaries = memoryRepository.findDailySummariesByJiacn(jiacn, sinceTime);

                    if (dailySummaries != null && !dailySummaries.isEmpty()) {
                        // 生成周汇总
                        String weeklySummary = summaryGenerator.generateWeeklySummary(dailySummaries, jiacn, weekStartStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
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
                    log.error("Failed to generate weekly summary for user: {}", jiacn, e);
                }
            }

            log.info("Generate weekly summary completed");
        } catch (Exception e) {
            log.error("Failed to generate weekly summary", e);
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

            for (String jiacn : activeJiacns) {
                try {
                    // 获取该用户近窗口期的周汇总
                    List<String> weeklySummaries = memoryRepository.findWeeklySummariesByJiacn(jiacn, sinceTime);

                    if (weeklySummaries != null && !weeklySummaries.isEmpty()) {
                        // 生成月汇总
                        String monthlySummary = summaryGenerator.generateMonthlySummary(weeklySummaries, jiacn, monthStr);

                        // 写入向量库
                        MemoryDocument document = new MemoryDocument();
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
                    log.error("Failed to generate monthly summary for user: {}", jiacn, e);
                }
            }

            log.info("Generate monthly summary completed");
        } catch (Exception e) {
            log.error("Failed to generate monthly summary", e);
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
    public List<ChatMessageEntity> getConversationMessages(String conversationId) {
        return chatMessageDao.findByConversationId(conversationId);
    }
}