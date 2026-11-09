package cn.jia.task.api;

import cn.jia.core.entity.JsonRequest;
import cn.jia.core.entity.JsonResult;
import cn.jia.task.executor.JobExecutor;
import cn.jia.task.job.JobContext;
import cn.jia.task.job.JobHandler;
import cn.jia.task.job.JobRegistry;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JobControllerSchedulingTest {
    @Test void durableJobsCannotBeInvokedByGenericManualPermissionOrParams() {
        var registry = new JobRegistry(); var calls = new AtomicInteger();
        registry.register(new ScheduledJobHandler() {
            public String getName() { return "rent"; }
            public void executeScheduled(ScheduledInvocation invocation) { calls.incrementAndGet(); }
        });
        var executor = new JobExecutor();
        ReflectionTestUtils.setField(executor, "jobRegistry", registry);
        var controller = new JobController(); ReflectionTestUtils.setField(controller, "jobExecutor", executor);
        var request = new JobController.JobExecuteRequest(); request.setName("rent");
        request.setParams(java.util.Map.of("tenant", "forged", "businessKey", "user"));
        try {
            var envelope = new JsonRequest<>(1, "manual", request);
            assertEquals("409", ((JsonResult<?>) controller.execute(envelope)).getCode());
            assertEquals("409", ((JsonResult<?>) controller.executeAsync(envelope)).getCode());
            assertEquals(0, calls.get());
        } finally { executor.shutdown(); }
    }
    @Test void handlerErrorDoesNotBecomeSuccessEnvelope() {
        var registry = new JobRegistry();
        registry.register(new JobHandler() {
            public String getName() { return "failed"; }
            public String execute(JobContext context) { return "business failed"; }
        });
        var executor = new JobExecutor(); ReflectionTestUtils.setField(executor, "jobRegistry", registry);
        var controller = new JobController(); ReflectionTestUtils.setField(controller, "jobExecutor", executor);
        var request = new JobController.JobExecuteRequest(); request.setName("failed");
        try {
            assertEquals("409", ((JsonResult<?>) controller.execute(new JsonRequest<>(1, "manual", request))).getCode());
            assertEquals("400", ((JsonResult<?>) controller.execute(null)).getCode());
        } finally { executor.shutdown(); }
    }
}
