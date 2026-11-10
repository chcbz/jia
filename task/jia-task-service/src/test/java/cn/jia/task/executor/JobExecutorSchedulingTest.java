package cn.jia.task.executor;

import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;
import cn.jia.task.job.JobRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JobExecutorSchedulingTest {
    @Test void manualExecutorHasNoIndependentCronPollingEntry() {
        for (var method : JobExecutor.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Scheduled.class), method.getName());
        }
        assertThrows(NoSuchMethodException.class, () -> JobExecutor.class.getDeclaredMethod("scheduledExecute"));
    }

    @Test void legacyCronMetadataRemainsManualWithoutActivatingItsBusiness() {
        var calls = new AtomicInteger();
        var registry = new JobRegistry();
        registry.register(new JobHandler() {
            public String getName() { return "legacy-alert"; }
            public String getCron() { return "0 0/10 * * * ?"; }
            public String execute(JobContext context) {
                assertEquals("legacy-alert", context.getJobName());
                calls.incrementAndGet();
                return "done";
            }
        });
        var executor = new JobExecutor();
        ReflectionTestUtils.setField(executor, "jobRegistry", registry);
        try {
            assertEquals(0, calls.get());
            assertEquals("done", executor.executeJob("legacy-alert", Map.of()));
            assertEquals(1, calls.get());
            assertTrue(executor.getRunningJobs().isEmpty());
        } finally { executor.shutdown(); }
    }
}
