package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import java.util.ArrayList;
import java.util.List;

/** Starts after registry initialization, not during bean construction. No schema changes here. */
public final class TaskSchedulingRuntime implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {
    private final ScheduledJobRunner runner;
    private final List<RedisScheduleCoordinator> coordinators = new ArrayList<>();

    public TaskSchedulingRuntime(ScheduledWorkStore store, JobRegistry registry, RedissonClient redis,
            FixedTenantAdmissionPolicy scope, String nodeId, int concurrency, int controlConcurrency, int batch, long leaseMillis,
            long retryMillis, long recoveryMillis, long ownerRetryMillis) {
        runner = new ScheduledJobRunner(store, registry, scope, "", concurrency, controlConcurrency, batch, leaseMillis, retryMillis,
                () -> coordinators.forEach(RedisScheduleCoordinator::wake));
        coordinators.add(new RedisScheduleCoordinator(redis, scope, "", runner::tick, recoveryMillis, ownerRetryMillis));
        if (!nodeId.isBlank()) coordinators.add(new RedisScheduleCoordinator(redis, scope, nodeId,
                () -> runner.tick(nodeId), recoveryMillis, ownerRetryMillis));
    }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        coordinators.forEach(RedisScheduleCoordinator::start);
    }
    @Override public void close() {
        coordinators.forEach(RedisScheduleCoordinator::close);
        runner.close();
    }
}
