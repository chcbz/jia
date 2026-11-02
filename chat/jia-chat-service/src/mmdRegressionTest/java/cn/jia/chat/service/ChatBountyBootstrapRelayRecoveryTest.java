package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.ArrayList;
import java.util.List;
import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
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
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();

    @BeforeEach void start() {
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.READY);
        doAnswer(call -> {
            callbacks.add(call.getArgument(0));
            delays.add(call.getArgument(1));
            return mock(ScheduledFuture.class);
        }).when(scheduler).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
        relay = new ChatBountyBootstrapRelay(outbox, admission, contexts, () -> scheduler, now::get);
        relay.start();
        assertEquals(List.of(0L), delays);
        tick = () -> callbacks.get(callbacks.size() - 1).run();
    }

    @Test void twoConnectionFailuresDoNotPermanentlyDisableNormalTaskAdmission() {
        var unavailable = new CannotCreateTransactionException("JDBC unavailable",
                new SQLTransientConnectionException("pool unavailable", "08001"));
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(unavailable).thenThrow(unavailable).thenReturn(null);
        tick.run();
        assertEquals(1_000L, delays.get(delays.size() - 1).longValue());
        now.set(1_500); tick.run(); // No repeated DB access during recovery backoff.
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        now.set(2_000); tick.run();
        assertEquals(2_000L, delays.get(delays.size() - 1).longValue());
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
        tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        assertTrue(relay.isRunning());
    }

    @Test void emptyQueueSleepsAndCommittedHintWakesWithoutWaitingForRecovery() {
        tick.run();
        assertEquals(5_000L, delays.get(delays.size() - 1));
        Runnable staleRecovery = callbacks.get(callbacks.size() - 1);
        now.set(1_100);
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(0L, delays.get(delays.size() - 1));
        staleRecovery.run(); // A cancelled timer may already have been queued.
        verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
        tick.run();
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test void notificationStormCoalescesAndInFlightHintIsNotLost() {
        tick.run();
        int before = callbacks.size();
        for (int i = 0; i < 1000; i++) relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(before + 1, callbacks.size());
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenAnswer(ignored -> {
            relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
            return null;
        });
        tick.run();
        assertEquals(0L, delays.get(delays.size() - 1));
        assertEquals(before + 2, callbacks.size());
    }

    @Test void retryHintSurvivesImmediateDrainAndWakesAtItsOriginalDueTime() {
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(2_000));
        tick.run();
        assertEquals(1_000L, delays.get(delays.size() - 1));
        now.set(2_000); tick.run();
        assertEquals(5_000L, delays.get(delays.size() - 1));
    }

    @Test void stoppedAndReplacedLifecycleRejectsOldCallbacksAndHints() {
        Runnable old = callbacks.get(0);
        relay.stop();
        int before = callbacks.size();
        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
        assertEquals(before, callbacks.size());
        relay.start(); old.run();
        verifyNoInteractions(outbox);
        tick.run();
        verify(outbox).claimNextAvailable(anyString(), anyLong());
    }

    /** Both outbox and native lane use non-authoritative, coalesced wakeups. */
    @org.junit.jupiter.api.Nested class NativeWorkNotifications {
        final cn.jia.chat.handler.AgentWebSocketHandler sockets=mock(cn.jia.chat.handler.AgentWebSocketHandler.class);
        final cn.jia.agent.service.NativeConversationWorkDiscovery discovery=mock(cn.jia.agent.service.NativeConversationWorkDiscovery.class);
        final ScheduledExecutorService worker=mock(ScheduledExecutorService.class);
        final cn.jia.agent.entity.NativeConversationWorkAvailable local=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client","local");
        final cn.jia.agent.entity.NativeConversationWorkAvailable remote=new cn.jia.agent.entity.NativeConversationWorkAvailable("0","client","remote");
        final List<Runnable> drains=new ArrayList<>();
        Runnable recovery;
        NativeConversationWorkNotifier notifier;
        @BeforeEach void setupNotifier() {
            when(sockets.nativeWorkTargets()).thenReturn(List.of(local));
            doAnswer(call->{drains.add(call.getArgument(0));return null;}).when(worker).execute(any(Runnable.class));
            doAnswer(call->{recovery=call.getArgument(0);return mock(ScheduledFuture.class);})
                .when(worker).scheduleWithFixedDelay(any(Runnable.class),eq(30L),eq(30L),eq(TimeUnit.SECONDS));
            notifier=new NativeConversationWorkNotifier(sockets,discovery,()->worker);notifier.start();
        }
        @Test void burstsCoalesceAndNotificationsDuringSendGetOneTail() {
            notifier.available(remote);
            for(int i=0;i<1000;i++)notifier.available(local);
            assertEquals(1,drains.size());verify(sockets,never()).notifyNativeWork(any());
            doAnswer(call->{for(int i=0;i<1000;i++)notifier.available(local);return null;})
                .doNothing().when(sockets).notifyNativeWork(local);
            drains.get(0).run();assertEquals(2,drains.size());drains.get(1).run();
            verify(sockets,times(2)).notifyNativeWork(local);verifyNoInteractions(discovery);
        }
        @Test void missedOrRemoteCommitIsRecoveredByOneLocalSubscriptionQuery() {
            when(discovery.pendingFor(List.of(local))).thenReturn(List.of(local));
            recovery.run();verify(discovery).pendingFor(List.of(local));verify(sockets).notifyNativeWork(local);
            verify(sockets,never()).notifyNativeWork(remote);
        }
        @Test void noSubscribersMeansNoDatabaseWorkAndEmptyQueueMeansNoHint() {
            when(sockets.nativeWorkTargets()).thenReturn(List.of());recovery.run();verifyNoInteractions(discovery);
            when(sockets.nativeWorkTargets()).thenReturn(List.of(local));
            when(discovery.pendingFor(List.of(local))).thenReturn(List.of());recovery.run();
            verify(sockets,never()).notifyNativeWork(any());
        }
        @Test void stoppedCallbacksCannotDiscoverOrSendAndFailedScanCanRecover() {
            when(discovery.pendingFor(List.of(local))).thenThrow(new IllegalStateException("offline")).thenReturn(List.of(local));
            assertDoesNotThrow(recovery::run);recovery.run();verify(sockets).notifyNativeWork(local);
            notifier.available(local);notifier.stop();drains.get(0).run();recovery.run();
            verify(discovery,times(2)).pendingFor(any());verify(sockets,times(1)).notifyNativeWork(local);
            verify(worker).shutdown();assertFalse(notifier.isRunning());
        }
    }
}
