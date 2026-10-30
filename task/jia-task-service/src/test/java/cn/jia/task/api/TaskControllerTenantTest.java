package cn.jia.task.api;

import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TaskControllerTenantTest {
    @AfterEach void clear() { EsContextHolder.clearContext(); }

    @Test void requestTenantCannotOverrideProductionOrGrayProcessOnCreateOrUpdate() {
        for (String tenant : new String[]{"0", "1", "tenant-blue"}) {
            var service = mock(TaskService.class);
            var controller = new TaskController();
            ReflectionTestUtils.setField(controller, "taskService", service);
            ReflectionTestUtils.setField(controller, "tenantPolicy",
                    new FixedTenantAdmissionPolicy(tenant, "client"));
            var context = new EsContext(); context.setClientId("client");
            EsContextHolder.setContext(context);
            var plan = new TaskPlanEntity(); plan.setTenantId("forged");
            controller.create(new JsonRequest<>(1, "create", plan));
            assertEquals(tenant, plan.getTenantId());
            assertEquals("client", plan.getClientId());
            verify(service).create(plan);
            plan.setTenantId("forged");
            controller.update(new JsonRequest<>(1, "update", plan));
            assertEquals(tenant, plan.getTenantId());
            verify(service).update(plan);
        }
    }
}
