package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledJobRunnerTest {
    @Test void idleTickDoesOneIndexReadAndNoClaimQuery() {
        var store = mock(ScheduledWorkStore.class);
        when(store.nextDelay("")).thenReturn(null);
        try (var runner = new ScheduledJobRunner(store, new JobRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 60000, 1000, () -> { })) {
            assertEquals(Long.MAX_VALUE, runner.tick());
            verify(store).nextDelay(""); verifyNoMoreInteractions(store);
        }
    }
    @Test void futureDeadlineDoesNotIssueDueQuery() {
        var store = mock(ScheduledWorkStore.class);
        when(store.nextDelay("")).thenReturn(7000L);
        try (var runner = new ScheduledJobRunner(store, new JobRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 60000, 1000, () -> { })) {
            assertEquals(7000, runner.tick());
            verify(store).nextDelay(""); verifyNoMoreInteractions(store);
            runner.close(); assertEquals(Long.MAX_VALUE, runner.tick()); verifyNoMoreInteractions(store);
        }
    }
}
