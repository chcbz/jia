package cn.jia.chat.job;

import cn.jia.chat.memory.MemoryJobRunStore;
import cn.jia.chat.memory.MemoryJobRunStore.Outcome;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobContext;
import cn.jia.task.scheduling.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryScheduledJobTest {
    final long at = 1_700_000_000_000L;
    MemoryJobHandler business;
    MemoryJobRunStore runs;
    CronScheduleService schedules;
    PlatformTransactionManager manager;
    TransactionStatus transaction;
    MemoryScheduledJob job;

    @BeforeEach void setup() throws Exception {
        business = mock(MemoryJobHandler.class); runs = mock(MemoryJobRunStore.class);
        schedules = mock(CronScheduleService.class); manager = mock(PlatformTransactionManager.class);
        transaction = mock(TransactionStatus.class);
        when(manager.getTransaction(any())).thenReturn(transaction);
        when(business.getName()).thenReturn("memory_sync"); when(business.getGroup()).thenReturn("chat");
        when(business.getCron()).thenReturn("0 0 * * * ?"); when(business.execute(any())).thenReturn("done");
        when(runs.start(anyLong(), anyString(), anyLong())).thenReturn(true);
        job = create(new FixedTenantAdmissionPolicy("tenant", "client"));
        clearInvocations(business, runs, manager);
    }
    MemoryScheduledJob create(FixedTenantAdmissionPolicy scope) {
        return new MemoryScheduledJob(business, runs, schedules, scope, manager, "Asia/Shanghai",
                Clock.fixed(Instant.ofEpochMilli(at + 100), ZoneOffset.UTC));
    }
    ScheduledInvocation occurrence(String key) {
        return new ScheduledInvocation("tenant", new ScheduleKey("client", MemoryScheduledJob.HANDLER, key, ""), 99);
    }
    ScheduledInvocation occurrence() { return occurrence("cron:" + at); }

    @Test void readyExplicitlyRegistersStableClusterCronAndDoesNotExecuteBusiness() throws Exception {
        job.onApplicationEvent(null); job.onApplicationEvent(null);
        verify(schedules, times(2)).register(new CronSchedule("client", MemoryScheduledJob.HANDLER,
                "", "0 0 * * * ?", "Asia/Shanghai", CronSchedule.Misfire.COALESCE));
        verify(business, never()).execute(any()); verifyNoInteractions(runs);
        var condition = MemoryScheduledJob.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("true", condition.havingValue()); assertFalse(condition.matchIfMissing());
    }
    @Test void unrestrictedProcessRegistersTenantMaintenanceWithoutInventingClient() {
        create(new FixedTenantAdmissionPolicy("tenant", null)).onApplicationEvent(null);
        verify(schedules).register(new CronSchedule("", MemoryScheduledJob.HANDLER, "", "0 0 * * * ?",
                "Asia/Shanghai", CronSchedule.Misfire.COALESCE));
    }
    @Test void acceptanceCommitsBeforeBusinessAndCompletionCommitsAfterIt() throws Exception {
        job.executeScheduled(occurrence());
        var ordered = inOrder(runs, manager, business);
        ordered.verify(manager).getTransaction(argThat(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        ordered.verify(runs).start(eq(at), anyString(), eq(at + 100));
        ordered.verify(manager).commit(transaction);
        ordered.verify(business).execute(argThat(ctx -> ctx.getTriggerTime() == at
                && ctx.getJobId().equals("memory-sync-" + at) && ctx.getJobName().equals("memory_sync")));
        ordered.verify(manager).getTransaction(any());
        ordered.verify(runs).finish(eq(at), anyString(), eq(Outcome.SUCCEEDED), eq(at + 100));
        ordered.verify(manager).commit(transaction);
    }
    @ParameterizedTest @EnumSource(Outcome.class)
    void everyExistingOutcomeIncludingUnknownStartedPreventsProviderReplay(Outcome outcome) throws Exception {
        when(runs.start(anyLong(), anyString(), anyLong())).thenReturn(false);
        when(runs.find(at)).thenReturn(new MemoryJobRunStore.Run(at, "original", outcome, at, null));
        job.executeScheduled(occurrence());
        verify(business, never()).execute(any()); verify(runs, never()).finish(anyLong(), anyString(), any(), anyLong());
    }
    @Test void acceptanceFailureNeverStartsModelWork() throws Exception {
        when(runs.start(anyLong(), anyString(), anyLong())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> job.executeScheduled(occurrence()));
        verify(business, never()).execute(any()); verify(manager).rollback(transaction);
    }
    @Test void failedBusinessIsDurablyFailedRatherThanRetriedByDispatcher() throws Exception {
        when(business.execute(any())).thenThrow(new IllegalStateException("provider outcome unknown"));
        job.executeScheduled(occurrence());
        verify(runs).finish(eq(at), anyString(), eq(Outcome.FAILED), anyLong());
        verify(business).execute(any());
    }
    @Test void completionWriteFailureDoesNotAuthorizeAnotherModelCall() throws Exception {
        when(runs.start(anyLong(), anyString(), anyLong())).thenReturn(true, false);
        when(runs.find(at)).thenReturn(new MemoryJobRunStore.Run(at, "original", Outcome.STARTED, at, null));
        doThrow(new IllegalStateException("completion unavailable")).when(runs).finish(anyLong(), anyString(), any(), anyLong());
        assertThrows(IllegalStateException.class, () -> job.executeScheduled(occurrence()));
        job.executeScheduled(occurrence());
        verify(business).execute(any());
    }
    @Test void missingReplayEvidenceDoesNotFabricateCompletion() throws Exception {
        when(runs.start(anyLong(), anyString(), anyLong())).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> job.executeScheduled(occurrence()));
        verify(business, never()).execute(any());
    }
    @Test void existingManualLockContentionHasExplicitSkippedOutcome() throws Exception {
        when(business.execute(any())).thenReturn(MemoryJobHandler.ALREADY_RUNNING);
        job.executeScheduled(occurrence());
        verify(runs).finish(eq(at), anyString(), eq(Outcome.SKIPPED), anyLong());
    }
    @Test void laterOccurrenceRetainsOriginalPeriodicPendingRetryAbility() throws Exception {
        job.executeScheduled(occurrence()); job.executeScheduled(occurrence("cron:" + (at + 3_600_000)));
        verify(business, times(2)).execute(any());
        verify(runs).start(eq(at + 3_600_000), anyString(), anyLong());
    }
    @ParameterizedTest @ValueSource(strings = {"manual", "cron:-1", "cron:01", "cron:abc", "cron:99999999999999999999999"})
    void invalidOccurrenceCannotStartPaidWork(String key) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> job.executeScheduled(occurrence(key)));
        verifyNoInteractions(runs); verify(business, never()).execute(any());
    }
}
