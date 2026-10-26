package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression for the real 2026-10-26 DB outage leaving all later normal starts PENDING. */
class ChatBountyBootstrapRelayRecoveryTest {
    private final AgentTaskBountyBootstrapOutboxService outbox = mock(AgentTaskBountyBootstrapOutboxService.class);
    private final ChatBountyBootstrapAdmissionService admission = mock(ChatBountyBootstrapAdmissionService.class);
    private final ChatTypedDeliberationContextService contexts = mock(ChatTypedDeliberationContextService.class);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final AtomicLong now = new AtomicLong(1_000L);
    private ChatBountyBootstrapRelay relay;
    private Runnable tick;

    @BeforeEach void start() {
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.READY);
        relay = new ChatBountyBootstrapRelay(outbox, admission, contexts, () -> scheduler, now::get);
        relay.start();
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(callback.capture(), eq(0L), eq(500L), eq(TimeUnit.MILLISECONDS));
        tick = callback.getValue();
    }

    @Test void twoConnectionFailuresDoNotPermanentlyDisableNormalTaskAdmission() {
        var unavailable = new CannotCreateTransactionException("JDBC unavailable",
                new SQLTransientConnectionException("pool unavailable", "08001"));
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(unavailable).thenThrow(unavailable).thenReturn(null);
        tick.run();
        now.set(1_500); tick.run(); // No repeated DB access during recovery backoff.
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        now.set(2_000); tick.run();
        assertTrue(relay.isRunning());
        now.set(3_999); tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        now.set(4_000); tick.run();
        verify(outbox, times(3)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission); // Never bypass lease acquisition with a direct dispatch.
        verify(scheduler, never()).shutdown();
    }

    @Test void successfulClaimPollResetsRecoveryDelay() {
        var unavailable = new TransientDataAccessResourceException("DB restarting");
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(unavailable).thenThrow(unavailable).thenReturn(null).thenThrow(unavailable).thenReturn(null);
        tick.run(); now.set(2_000); tick.run(); now.set(4_000); tick.run();
        now.set(4_500); tick.run();
        now.set(5_499); tick.run();
        verify(outbox, times(4)).claimNextAvailable(anyString(), anyLong());
        now.set(5_500); tick.run();
        verify(outbox, times(5)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
    }

    @Test void connectionSqlStateInsideTransactionFailureIsRecoverable() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new CannotCreateTransactionException("open failed", new SQLException("connection unavailable", "08006")));
        tick.run(); now.set(2_000); tick.run();
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void recoveryBackoffIsCappedWithoutExpiringOrClaimingWorkEarly() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new TransientDataAccessResourceException("DB unavailable"));
        long delay = ChatBountyBootstrapRelay.CLAIM_RETRY_BASE_MILLIS;
        for (int i = 0; i < 13; i++) {
            long attemptAt = now.get();
            tick.run();
            verify(outbox, times(i + 1)).claimNextAvailable(anyString(), anyLong());
            now.set(attemptAt + delay - 1); tick.run();
            verify(outbox, times(i + 1)).claimNextAvailable(anyString(), anyLong());
            now.set(attemptAt + delay);
            delay = Math.min(ChatBountyBootstrapRelay.CLAIM_RETRY_MAX_MILLIS, delay * 2);
        }
        assertTrue(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void schemaFailureIsNotClassifiedAsTransientInfrastructure() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new DataAccessResourceFailureException("schema missing", new SQLException("missing table", "42S02")));
        tick.run(); tick.run();
        assertFalse(relay.isRunning());
        verify(scheduler).shutdown();
        verifyNoInteractions(admission);
    }

    @Test void authorizationFailureStillFailsClosed() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new DataAccessResourceFailureException("access denied", new SQLException("denied", "28000")));
        tick.run(); tick.run();
        assertFalse(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void stoppedSchedulerCannotResumeClaimsAfterDatabaseRecovery() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(
                new TransientDataAccessResourceException("DB restarting"));
        tick.run(); relay.stop(); now.set(1_000_000); tick.run();
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        assertFalse(relay.isRunning());
        verifyNoInteractions(admission);
    }

    @Test void explicitLifecycleRestartDoesNotInheritOldBackoff() {
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(new TransientDataAccessResourceException("DB restarting")).thenReturn(null);
        tick.run(); relay.stop(); relay.start();
        var callbacks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).scheduleWithFixedDelay(callbacks.capture(), eq(0L), eq(500L), eq(TimeUnit.MILLISECONDS));
        callbacks.getAllValues().get(1).run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
    }
}
