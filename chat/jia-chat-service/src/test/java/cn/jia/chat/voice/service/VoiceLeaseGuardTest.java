package cn.jia.chat.voice.service;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class VoiceLeaseGuardTest {
    private static final VoiceReservation RESERVATION = new VoiceReservation(
            VoiceOperation.SYNTHESIS, "scope", "request", "digest", "lease");

    @Test
    void renewsExactReservationAndCloseDropsLateHeartbeatWithoutInterrupt() {
        CapturingCoordinator coordinator = new CapturingCoordinator(false);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(executor).scheduleAtFixedRate(
                task.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));

        VoiceLeaseGuard guard = new VoiceLeaseGuard(
                coordinator, RESERVATION, executor, 5);
        assertEquals(1, coordinator.renewals.get());
        assertSame(RESERVATION, coordinator.lastReservation);

        guard.close();
        task.getValue().run();

        assertEquals(1, coordinator.renewals.get());
        verify(future).cancel(false);
    }

    @Test
    void heartbeatOwnershipLossFailsClosedAndInterruptsOnlyWhileOpen() {
        CapturingCoordinator coordinator = new CapturingCoordinator(true);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        doReturn(future).when(executor).scheduleAtFixedRate(
                task.capture(), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        VoiceLeaseGuard guard = new VoiceLeaseGuard(
                coordinator, RESERVATION, executor, 5);
        try {
            task.getValue().run();
            VoiceException failure = assertThrows(VoiceException.class,
                    () -> guard.requireOwned("request"));
            assertEquals(VoiceErrorCode.RESULT_UNKNOWN, failure.error());
            assertEquals(2, coordinator.renewals.get());
            assertSame(RESERVATION, coordinator.lastReservation);
        } finally {
            guard.close();
            Thread.interrupted();
        }
    }

    private static final class CapturingCoordinator implements VoiceRequestCoordinator {
        private final AtomicInteger renewals = new AtomicInteger();
        private final boolean failAfterInitial;
        private VoiceReservation lastReservation;

        private CapturingCoordinator(boolean failAfterInitial) {
            this.failAfterInitial = failAfterInitial;
        }

        @Override
        public void renew(VoiceReservation reservation) {
            lastReservation = reservation;
            if (renewals.incrementAndGet() > 1 && failAfterInitial) {
                throw new VoiceStateUnavailableException();
            }
        }

        @Override public cn.jia.chat.voice.state.VoiceAdmissionResult admit(
                VoiceOperation operation, String identityScope, String requestId) {
            throw new UnsupportedOperationException();
        }
        @Override public VoiceBeginResult begin(
                cn.jia.chat.voice.state.VoiceAdmission admission, String digest) {
            throw new UnsupportedOperationException();
        }
        @Override public VoiceBeginResult begin(
                VoiceOperation operation, String identityScope, String requestId, String digest) {
            throw new UnsupportedOperationException();
        }
        @Override public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
            throw new UnsupportedOperationException();
        }
        @Override public void failKnown(VoiceReservation reservation) {
            throw new UnsupportedOperationException();
        }
        @Override public void failUnknown(VoiceReservation reservation) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(cn.jia.chat.voice.state.VoiceAdmission admission) {
            throw new UnsupportedOperationException();
        }
        @Override public void release(VoiceReservation reservation) {
            throw new UnsupportedOperationException();
        }
    }
}
