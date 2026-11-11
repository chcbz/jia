package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledJobRunnerTest {
    @Test void idleTickDoesOneIndexReadAndNoClaimQuery() {
        var store = mock(ScheduledWorkStore.class);
        when(store.nextDelay("", java.util.Set.of(), false)).thenReturn(null);
        try (var runner = new ScheduledJobRunner(store, new JobRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 1, 60000, 1000, () -> { })) {
            assertEquals(Long.MAX_VALUE, runner.tick());
            verify(store).nextDelay("", java.util.Set.of(), false); verifyNoMoreInteractions(store);
        }
    }
    @Test void futureDeadlineDoesNotIssueDueQuery() {
        var store = mock(ScheduledWorkStore.class);
        when(store.nextDelay("", java.util.Set.of(), false)).thenReturn(7000L);
        try (var runner = new ScheduledJobRunner(store, new JobRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 1, 60000, 1000, () -> { })) {
            assertEquals(7000, runner.tick());
            verify(store).nextDelay("", java.util.Set.of(), false); verifyNoMoreInteractions(store);
            runner.close(); assertEquals(Long.MAX_VALUE, runner.tick()); verifyNoMoreInteractions(store);
        }
    }
    @Test void skippedLockedDueRowsBackOffRatherThanSpin() {
        var store = mock(ScheduledWorkStore.class);
        when(store.nextDelay("", java.util.Set.of(), false)).thenReturn(0L);
        when(store.claimDue("", 1, 60000, java.util.Set.of(), false)).thenReturn(java.util.List.of());
        try (var runner = new ScheduledJobRunner(store, new JobRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 1, 60000, 1000, () -> { })) {
            assertEquals(1000, runner.tick());
            verify(store).nextDelay("", java.util.Set.of(), false); verify(store).claimDue("", 1, 60000, java.util.Set.of(), false); verifyNoMoreInteractions(store);
        }
    }

    private JobRegistry controlRegistry() {
        var registry = new JobRegistry();
        registry.register(new ScheduledJobHandler() {
            public String getName() { return "control"; }
            public ExecutionClass executionClass() { return ExecutionClass.CONTROL; }
            public void executeScheduled(ScheduledInvocation invocation) { }
        });
        return registry;
    }
    @Test void eachLaneUsesItsOwnDeadlineWithoutClaimingFutureWork() {
        var store = mock(ScheduledWorkStore.class); var controls = java.util.Set.of("control");
        when(store.nextDelay("", controls, true)).thenReturn(1000L);
        when(store.nextDelay("", controls, false)).thenReturn(9000L);
        try (var runner = new ScheduledJobRunner(store, controlRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 1, 60000, 1000, () -> { })) {
            assertEquals(1000, runner.tick());
            verify(store).nextDelay("", controls, true); verify(store).nextDelay("", controls, false);
            verifyNoMoreInteractions(store);
        }
    }
    @Test void failedControlClaimReleasesReservedCapacityForRecovery() {
        var store = mock(ScheduledWorkStore.class); var controls = java.util.Set.of("control");
        when(store.nextDelay("", controls, true)).thenReturn(0L);
        when(store.nextDelay("", controls, false)).thenReturn(null);
        when(store.claimDue("", 1, 60000, controls, true))
                .thenThrow(new IllegalStateException("database unavailable")).thenReturn(java.util.List.of());
        try (var runner = new ScheduledJobRunner(store, controlRegistry(), new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 1, 60000, 1000, () -> { })) {
            assertThrows(IllegalStateException.class, runner::tick);
            assertEquals(1000, runner.tick());
            verify(store, times(2)).claimDue("", 1, 60000, controls, true);
        }
    }

}
