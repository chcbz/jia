package cn.jia.chat.service.recommendation;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationUsageRecoveryTest {
    final RecommendationUsageJournal journal = mock(RecommendationUsageJournal.class);
    final CronScheduleService schedules = mock(CronScheduleService.class);

    @Test void startupRegistersNodeBoundTenantRecoveryWithoutScanningOrCallingProvider() {
        var recovery = new RecommendationUsageRecovery(journal, schedules,
                new FixedTenantAdmissionPolicy("tenant", null), "node-a", "0 * * * * *");
        verifyNoInteractions(schedules, journal);
        recovery.onApplicationEvent(null);
        verify(schedules).register(new CronSchedule("", RecommendationUsageRecovery.HANDLER,
                "node-a", "0 * * * * *", "UTC", CronSchedule.Misfire.COALESCE));
        verifyNoInteractions(journal);
    }

    @Test void clientRestrictedProcessRegistersOnlyItsOwnJournalScope() {
        var recovery = new RecommendationUsageRecovery(journal, schedules,
                new FixedTenantAdmissionPolicy("tenant", "client"), "node-b", "0 */2 * * * *");
        recovery.onApplicationEvent(null);
        var spec = new CronSchedule("client", RecommendationUsageRecovery.HANDLER,
                "node-b", "0 */2 * * * *", "UTC", CronSchedule.Misfire.COALESCE);
        verify(schedules).register(spec);
        recovery.executeScheduled(new ScheduledInvocation("tenant", spec.occurrence(1000), 1));
        verify(journal).recover();
        verifyNoMoreInteractions(schedules, journal);
    }

    @Test void registrationFailureDoesNotBecomeSuccessfulReadinessOrProviderRetry() {
        var recovery = new RecommendationUsageRecovery(journal, schedules,
                new FixedTenantAdmissionPolicy("tenant", null), "node-a", "0 * * * * *");
        doThrow(new IllegalStateException("database unavailable")).when(schedules).register(any());
        assertThrows(IllegalStateException.class, () -> recovery.onApplicationEvent(null));
        verifyNoInteractions(journal);
    }

    @Test void localFilesCannotBeAssignedToAnArbitraryClusterWorker() {
        assertThrows(IllegalArgumentException.class, () -> new RecommendationUsageRecovery(journal, schedules,
                new FixedTenantAdmissionPolicy("tenant", null), "", "0 * * * * *"));
        verifyNoInteractions(schedules, journal);
    }
}
