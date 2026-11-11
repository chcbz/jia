package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.config.TaskSchedulingConfiguration;
import cn.jia.task.job.JobRegistry;
import cn.jia.task.mapper.ScheduledWorkMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskSchedulingConfigurationTest {
    private AnnotationConfigApplicationContext context(Map<String, Object> properties, RedissonClient redis) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.registerBean(FixedTenantAdmissionPolicy.class, () -> new FixedTenantAdmissionPolicy("tenant", null));
        context.registerBean(ScheduledWorkMapper.class, () -> mock(ScheduledWorkMapper.class));
        context.registerBean(cn.jia.task.mapper.CronScheduleMapper.class, () -> mock(cn.jia.task.mapper.CronScheduleMapper.class));
        context.registerBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class));
        context.registerBean(RedissonClient.class, () -> redis);
        context.registerBean(JobRegistry.class, JobRegistry::new);
        context.register(TaskSchedulingConfiguration.class); context.refresh(); return context;
    }
    @Test void disabledFeatureDoesNotCreateStoreOrRuntime() {
        var redis = mock(RedissonClient.class);
        try (var context = context(Map.of(), redis)) {
            assertTrue(context.getBeansOfType(ScheduleService.class).isEmpty());
            assertTrue(context.getBeansOfType(CronScheduleService.class).isEmpty());
            assertTrue(context.getBeansOfType(TaskSchedulingRuntime.class).isEmpty());
            verifyNoInteractions(redis);
        }
    }
    @Test void greyProcessCanRegisterButNeverStartsBackgroundClaiming() {
        var redis = mock(RedissonClient.class);
        try (var context = context(Map.of("cyf.task.scheduling.enabled", "true",
                "cyf.security.fixed-tenant.enabled", "true"), redis)) {
            assertEquals(1, context.getBeansOfType(ScheduleService.class).size());
            assertEquals(1, context.getBeansOfType(CronScheduleService.class).size());
            verifyNoInteractions(context.getBean(cn.jia.task.mapper.CronScheduleMapper.class));
            assertTrue(context.getBeansOfType(TaskSchedulingRuntime.class).isEmpty());
            verifyNoInteractions(redis);
        }
    }
    @Test void configuredRuntimeWaitsForApplicationReadyRatherThanClaimingDuringConstruction() {
        var redis = mock(RedissonClient.class);
        try (var context = context(Map.of("cyf.task.scheduling.enabled", "true",
                "cyf.task.scheduling.node-id", "node-a", "cyf.task.scheduling.workers", "1", "cyf.task.scheduling.control-workers", "1",
                "cyf.task.scheduling.batch-size", "1", "cyf.task.scheduling.lease-ms", "60000",
                "cyf.task.scheduling.retry-ms", "1000", "cyf.task.scheduling.recovery-ms", "60000",
                "cyf.task.scheduling.owner-retry-ms", "1000"), redis)) {
            assertEquals(1, context.getBeansOfType(TaskSchedulingRuntime.class).size());
            verifyNoInteractions(context.getBean(ScheduledWorkMapper.class));
        }
    }
}
