package cn.jia.agent.hosting;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.service.EconomyAvailableBalanceListener;
import cn.jia.economy.service.EconomyScope;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostingRentBalanceWakeupTest {
    private EconomyAvailableBalanceListener.Increase fact(String tenant, String client) {
        return new EconomyAvailableBalanceListener.Increase(new EconomyScope(tenant, client), "user", 50, "transaction", 1000);
    }
    @Test void onlyRegistersUserKeyWithoutAgentQueriesOrHostClockDeadline() {
        var schedules = mock(ScheduleService.class);
        new HostingRentBalanceWakeup(schedules, new FixedTenantAdmissionPolicy("tenant", "client"))
                .increased(fact("tenant", "client"));
        verify(schedules).signal(new ScheduleKey("client", HostingRentBalanceWakeup.HANDLER, "user", ""), 0);
        verifyNoMoreInteractions(schedules);
    }
    @Test void doesNotRelabelAnotherTenantBalanceAsCurrentTenantWork() {
        var schedules = mock(ScheduleService.class);
        var wakeup = new HostingRentBalanceWakeup(schedules, new FixedTenantAdmissionPolicy("tenant", "client"));
        assertThrows(IllegalArgumentException.class, () -> wakeup.increased(fact("other", "client")));
        assertThrows(IllegalArgumentException.class, () -> wakeup.increased(fact("tenant", "other")));
        verifyNoInteractions(schedules);
    }
    @Test void registrationFailurePropagatesForPostingRollback() {
        var schedules = mock(ScheduleService.class);
        doThrow(new IllegalStateException("database unavailable")).when(schedules).signal(any(), anyLong());
        var wakeup = new HostingRentBalanceWakeup(schedules, new FixedTenantAdmissionPolicy("tenant", "client"));
        assertThrows(IllegalStateException.class, () -> wakeup.increased(fact("tenant", "client")));
    }
    @Test void autoRenewDisabledDoesNotRegisterConsumer() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                    Map.of("agent.hosting-rent.auto-renew-enabled", "false")));
            context.register(HostingRentBalanceWakeup.class); context.refresh();
            assertTrue(context.getBeansOfType(EconomyAvailableBalanceListener.class).isEmpty());
        }
    }
}
