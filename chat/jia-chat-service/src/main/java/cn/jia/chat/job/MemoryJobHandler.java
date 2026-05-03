package cn.jia.chat.job;

import cn.jia.chat.service.LongTermMemoryService;
import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * <p>
 * 记忆同步定时任务处理器
 * </p>
 *
 * @author chc
 * @since 2026-05-01
 */
@Slf4j
@Component
public class MemoryJobHandler implements JobHandler {

    private static final String JOB_NAME = "memory_sync";
    private static final String GROUP = "chat";
    private static final String DESCRIPTION = "对话记忆同步和汇总";
    private static final String DEFAULT_CRON = "0 0 * * * ?";  // 默认每小时执行

    @Value("${chat.memory.job.sync-cron:0 0 * * * ?}")
    private String syncCron;

    @Value("${chat.memory.job.daily-summary-hour:2}")
    private int dailySummaryHour;

    @Value("${chat.memory.job.weekly-summary-hour:3}")
    private int weeklySummaryHour;

    @Value("${chat.memory.job.monthly-summary-hour:4}")
    private int monthlySummaryHour;

    @Autowired
    private LongTermMemoryService longTermMemoryService;

    @Override
    public String getName() {
        return JOB_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public String getGroup() {
        return GROUP;
    }

    @Override
    public String getCron() {
        return syncCron != null && !syncCron.isEmpty() ? syncCron : DEFAULT_CRON;
    }

    @Override
    public boolean isParallel() {
        return true;
    }

    @Override
    public String execute(JobContext context) throws Exception {
        log.info("Starting memory sync job, jobId: {}", context.getJobId());
        long startTime = System.currentTimeMillis();

        try {
            // 获取当前小时
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            int hour = now.getHour();
            int dayOfWeek = now.getDayOfWeek().getValue();  // 1=Monday, 7=Sunday
            int dayOfMonth = now.getDayOfMonth();

            // 同步待处理会话
            longTermMemoryService.syncPendingConversations();

            // 生成日汇总 (在配置的小时执行)
            if (hour == dailySummaryHour) {
                log.info("Executing daily summary generation");
                longTermMemoryService.generateDailySummary();
            }

            // 生成周汇总 (在配置的小时执行，默认为周日)
            if (hour == weeklySummaryHour && dayOfWeek == 7) {
                log.info("Executing weekly summary generation");
                longTermMemoryService.generateWeeklySummary();
            }

            // 生成月汇总 (在配置的小时执行，默认为每月1日)
            if (hour == monthlySummaryHour && dayOfMonth == 1) {
                log.info("Executing monthly summary generation");
                longTermMemoryService.generateMonthlySummary();
            }

            long duration = System.currentTimeMillis() - startTime;
            String result = String.format("Memory sync job completed in %dms", duration);
            log.info(result);
            return result;
        } catch (Exception e) {
            log.error("Memory sync job failed", e);
            return "FAILED: " + e.getMessage();
        }
    }
}
