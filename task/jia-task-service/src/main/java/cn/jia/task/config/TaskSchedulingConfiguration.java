package cn.jia.task.config;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobRegistry;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.RedisScheduleWakeup;
import cn.jia.task.scheduling.ScheduledWorkStore;
import cn.jia.task.scheduling.TaskSchedulingRuntime;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

/** Enabled only with the authorized schema/consumer rollout. No migration or legacy cron activation. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public class TaskSchedulingConfiguration {
    @Bean
    public ScheduledWorkStore scheduledWorkStore(ScheduledWorkMapper mapper, FixedTenantAdmissionPolicy scope,
            PlatformTransactionManager transactions, RedissonClient redis) {
        return new ScheduledWorkStore(mapper, scope, transactions, new RedisScheduleWakeup(redis, scope));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "cyf.security.fixed-tenant.enabled", havingValue = "false", matchIfMissing = true)
    public TaskSchedulingRuntime taskSchedulingRuntime(ScheduledWorkStore store, JobRegistry registry,
            RedissonClient redis, FixedTenantAdmissionPolicy scope, Environment environment) {
        // Deployment chooses capacity/recovery settings explicitly, rather than inheriting an arbitrary library limit.
        String prefix = "cyf.task.scheduling.";
        return new TaskSchedulingRuntime(store, registry, redis, scope,
                environment.getRequiredProperty(prefix + "node-id"),
                environment.getRequiredProperty(prefix + "workers", Integer.class),
                environment.getRequiredProperty(prefix + "batch-size", Integer.class),
                environment.getRequiredProperty(prefix + "lease-ms", Long.class),
                environment.getRequiredProperty(prefix + "retry-ms", Long.class),
                environment.getRequiredProperty(prefix + "recovery-ms", Long.class),
                environment.getRequiredProperty(prefix + "owner-retry-ms", Long.class));
    }
}
