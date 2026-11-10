package cn.jia.chat.archive.config;

import cn.jia.chat.archive.service.ArchiveQuestionWorker;
import org.junit.jupiter.api.Test;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduleKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ArchiveQuestionBootstrapTest {
    @Test
    void enabledQuestionBootstrapStartsWorkerWithoutSchemaMaintenance() {
        ArchiveQuestionWorker worker = mock(ArchiveQuestionWorker.class);
        var schedules = mock(ScheduleService.class);
        ArchiveQuestionBootstrap bootstrap = bootstrap(policy(true, true), worker, schedules);

        bootstrap.onApplicationEvent(null);
        verify(schedules).signal(key(), 1000L);
        verify(worker, never()).submit();

        verify(worker).start();
    }

    @Test
    void questionOrReaderFlagOffTouchesNeitherQuestionSchemaNorWorker() {
        for (ArchiveQuestionAccessPolicy policy : List.of(policy(false, true), policy(true, false))) {
            ArchiveQuestionWorker worker = mock(ArchiveQuestionWorker.class);
            var schedules = mock(ScheduleService.class);
            var bootstrap = bootstrap(policy, worker, schedules);
            bootstrap.onApplicationEvent(null);
            bootstrap.executeScheduled(null);
            verifyNoInteractions(schedules);
            verify(worker, never()).start();
        }
    }

    @Test
    void callbacksWaitForReadyAndPersistRecoveryBeforeSubmission() {
        var worker = mock(ArchiveQuestionWorker.class);
        var schedules = mock(ScheduleService.class);
        var bootstrap = bootstrap(policy(true, true), worker, schedules);
        bootstrap.executeScheduled(null);
        verifyNoInteractions(worker, schedules);
        bootstrap.onApplicationEvent(null);
        bootstrap.onApplicationEvent(null);
        verify(worker).start();
        verify(schedules).signal(key(), 1000L);
        clearInvocations(worker, schedules);
        bootstrap.executeScheduled(null);
        var order = inOrder(schedules, worker);
        order.verify(schedules).signal(key(), 1250L);
        order.verify(worker).submit();
        bootstrap.stop();
        clearInvocations(worker, schedules);
        bootstrap.executeScheduled(null);
        bootstrap.onApplicationEvent(null);
        verifyNoInteractions(worker, schedules);
    }

    @Test
    void failedDeadlineCannotStartProviderWork() {
        var worker = mock(ArchiveQuestionWorker.class);
        var schedules = mock(ScheduleService.class);
        var bootstrap = bootstrap(policy(true, true), worker, schedules);
        bootstrap.onApplicationEvent(null);
        doThrow(new IllegalStateException("DB unavailable")).when(schedules).signal(eq(key()), eq(1250L));
        assertThrows(IllegalStateException.class, () -> bootstrap.executeScheduled(null));
        verify(worker, never()).submit();
    }

    private ArchiveQuestionBootstrap bootstrap(ArchiveQuestionAccessPolicy policy,
            ArchiveQuestionWorker worker, ScheduleService schedules) {
        return new ArchiveQuestionBootstrap(policy, worker, schedules,
                new FixedTenantAdmissionPolicy("tenant-a", "client-a"), "node-a",
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));
    }

    private ScheduleKey key() {
        return new ScheduleKey("client-a", ArchiveQuestionBootstrap.HANDLER, "recovery", "node-a");
    }

    private ArchiveQuestionAccessPolicy policy(boolean questionEnabled, boolean readerEnabled) {
        ArchiveReaderProperties reader = new ArchiveReaderProperties();
        reader.setEnabled(readerEnabled);
        if (readerEnabled) {
            ArchiveReaderProperties.AllowedScope scope = new ArchiveReaderProperties.AllowedScope();
            scope.setTenantId("owner-a");
            scope.setClientId("client-a");
            reader.setAllowedScopes(new ArrayList<>(List.of(scope)));
        }
        ArchiveQuestionProperties question = new ArchiveQuestionProperties();
        question.setEnabled(questionEnabled);
        return ArchiveQuestionAccessPolicy.from(question, ArchiveReaderAccessPolicy.from(reader));
    }
}
