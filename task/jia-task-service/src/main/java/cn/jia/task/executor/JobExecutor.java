package cn.jia.task.executor;

import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;
import cn.jia.task.job.JobRegistry;
import cn.jia.core.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 任务执行器
 * 负责管理和执行所有注册的任务
 * 支持手动触发和定时调度两种模式
 *
 * @author chc
 */
@Slf4j
@Component
public class JobExecutor {

    @Autowired
    private JobRegistry jobRegistry;

    /**
     * 线程池，用于执行异步任务
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * 正在执行的任务记录（用于防止并发）
     */
    private final Map<String, JobContext> runningJobs = new ConcurrentHashMap<>();

    /**
     * 手动触发任务执行
     *
     * @param jobName 任务名称
     * @param params 执行参数
     * @return 执行结果
     */
    public String executeJob(String jobName, Map<String, Object> params) {
        return executeJob(jobName, params, false);
    }

    /**
     * 手动触发任务执行
     *
     * @param jobName 任务名称
     * @param params 执行参数
     * @param async 是否异步执行
     * @return 执行结果
     */
    public String executeJob(String jobName, Map<String, Object> params, boolean async) {
        JobHandler handler = jobRegistry.getHandler(jobName);
        if (handler == null) {
            String errorMsg = "Job handler not found: " + jobName;
            log.error(errorMsg);
            return errorMsg;
        }

        JobContext context = JobContext.builder()
                .jobId(generateJobId(jobName))
                .jobName(jobName)
                .group(handler.getGroup())
                .triggerTime(DateUtil.nowTime())
                .params(params)
                .build();

        if (async) {
            executorService.submit(() -> execute(handler, context));
            return null;
        } else {
            return execute(handler, context);
        }
    }

    /**
     * 异步触发任务执行
     *
     * @param jobName 任务名称
     * @param params 执行参数
     */
    public void executeJobAsync(String jobName, Map<String, Object> params) {
        executeJob(jobName, params, true);
    }

    /**
     * 执行任务
     */
    private String execute(JobHandler handler, JobContext context) {
        String jobName = handler.getName();

        // 检查是否允许并行执行
        if (!handler.isParallel() && runningJobs.containsKey(jobName)) {
            String errorMsg = "Job is already running: " + jobName;
            log.warn(errorMsg);
            return errorMsg;
        }

        runningJobs.put(jobName, context);
        long startTime = System.currentTimeMillis();

        try {
            log.info("Starting job: {}, id: {}", jobName, context.getJobId());
            String result = handler.execute(context);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Job completed: {}, duration: {}ms, result: {}", jobName, duration, result);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Job execution failed: {}, duration: {}ms, error: {}", jobName, duration, e.getMessage(), e);
            return "Execution failed: " + e.getMessage();
        } finally {
            runningJobs.remove(jobName);
        }
    }

    /**
     * 定时扫描执行任务
     * 每分钟执行一次，检查所有注册的处理器是否有cron表达式
     */
    @Scheduled(cron = "0 * * * * ?")
    public void scheduledExecute() {
        Map<String, JobHandler> handlers = jobRegistry.getAllHandlers();
        for (JobHandler handler : handlers.values()) {
            String cron = handler.getCron();
            if (cron != null && !cron.isEmpty()) {
                // 这里可以扩展为基于cron表达式的调度
                // 目前简单实现，实际生产环境建议使用Quartz等成熟框架
                log.debug("Job {} has cron expression: {}", handler.getName(), cron);
            }
        }
    }

    /**
     * 获取正在执行的任务信息
     */
    public Map<String, JobContext> getRunningJobs() {
        return new ConcurrentHashMap<>(runningJobs);
    }

    /**
     * 检查任务是否正在运行
     */
    public boolean isJobRunning(String jobName) {
        return runningJobs.containsKey(jobName);
    }

    /**
     * 生成任务ID
     */
    private String generateJobId(String jobName) {
        return jobName + "_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
    }

    /**
     * 关闭执行器
     */
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}