package cn.jia.agent.service.impl;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentTaskEventReplayRecoveryTest {
    @Test void registersOnlyNodeRecoveryAfterReadyAndDispatchesExistingWorker() {
        var replay = mock(AgentTaskEventReplayServiceImpl.class);
        var schedules = mock(CronScheduleService.class);
        var recovery = new AgentTaskEventReplayRecovery(replay, schedules,
                new FixedTenantAdmissionPolicy("tenant-a", "client-a"), "node-a", "0/5 * * * * *");
        verifyNoInteractions(replay, schedules);
        recovery.onApplicationEvent(null);
        verify(schedules).register(new CronSchedule("client-a", AgentTaskEventReplayRecovery.HANDLER,
                "node-a", "0/5 * * * * *", "UTC", CronSchedule.Misfire.COALESCE));
        verifyNoInteractions(replay);
        recovery.executeScheduled(null);
        verify(replay).scheduleRecovery();
        assertEquals(AgentTaskEventReplayRecovery.HANDLER, recovery.getName());
    }

    @Test void unrestrictedClientRegistersTenantMaintenanceNotInventedClient() {
        var schedules = mock(CronScheduleService.class);
        new AgentTaskEventReplayRecovery(mock(AgentTaskEventReplayServiceImpl.class), schedules,
                new FixedTenantAdmissionPolicy("tenant-b", null), "node-b", "0/5 * * * * *")
                .onApplicationEvent(null);
        verify(schedules).register(new CronSchedule("", AgentTaskEventReplayRecovery.HANDLER,
                "node-b", "0/5 * * * * *", "UTC", CronSchedule.Misfire.COALESCE));
    }

    @Test void failedRegistrationPropagatesWithoutStartingLocalTimerOrQuery() {
        var replay = mock(AgentTaskEventReplayServiceImpl.class);
        var schedules = mock(CronScheduleService.class);
        var definition = new CronSchedule("", AgentTaskEventReplayRecovery.HANDLER,
                "node-a", "0/5 * * * * *", "UTC", CronSchedule.Misfire.COALESCE);
        doThrow(new IllegalStateException("SQL unavailable")).when(schedules).register(definition);
        var recovery = new AgentTaskEventReplayRecovery(replay, schedules,
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a", "0/5 * * * * *");
        assertThrows(IllegalStateException.class, () -> recovery.onApplicationEvent(null));
        verifyNoInteractions(replay);
    }

    @Test void requiresStableNodeAndExplicitUnifiedSchedulingEnablement() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTaskEventReplayRecovery(
                mock(AgentTaskEventReplayServiceImpl.class), mock(CronScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "", "0/5 * * * * *"));
        var condition = AgentTaskEventReplayRecovery.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("cyf.task.scheduling.enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
        assertEquals(false, condition.matchIfMissing());
    }
}
