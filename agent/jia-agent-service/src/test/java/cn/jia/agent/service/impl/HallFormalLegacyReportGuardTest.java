package cn.jia.agent.service.impl;

import cn.jia.agent.api.AgentController;
import cn.jia.agent.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** No task origin, status or billing mode may opt back into the retired writer. */
class HallFormalLegacyReportGuardTest {
    @Test void taskReportHasNoHttpWriteRoute() {
        assertFalse(Arrays.stream(AgentController.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(PostMapping.class)).filter(a -> a != null)
                .flatMap(a -> Arrays.stream(a.value())).anyMatch(p -> p.endsWith("/report")));
    }
    @Test void taskReportHasNoApplicationServiceEntry() {
        for (Class<?> type : new Class<?>[] {AgentService.class, AgentServiceImpl.class}) {
            assertFalse(Arrays.stream(type.getDeclaredMethods()).anyMatch(m -> m.getName().equals("reportTask")));
        }
    }
    @Test void legacyAdapterCannotWriteCompletion() {
        assertFalse(Arrays.stream(AgentLegacyTaskCompatibilityService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("report") || m.getName().equals("reportResolved")));
    }
}
