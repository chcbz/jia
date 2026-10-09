package cn.jia.chat.voice.service;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps an exact Redis reservation owned while an activity-based provider session remains live. */
final class VoiceLeaseGuard implements AutoCloseable {
    private static final ScheduledExecutorService EXECUTOR = Executors.newScheduledThreadPool(
            1, new DaemonThreadFactory());

    private final AtomicBoolean failed = new AtomicBoolean();
    private final Object ownershipLock = new Object();
    private final Thread owner;
    private final ScheduledFuture<?> future;
    private boolean closed;

    private VoiceLeaseGuard(
            VoiceRequestCoordinator coordinator,
            VoiceReservation reservation,
            long transportTimeoutMillis) {
        this(coordinator, reservation, EXECUTOR, Math.max(1_000L, Math.min(15_000L,
                Math.max(1L, transportTimeoutMillis / 2L))));
    }

    VoiceLeaseGuard(
            VoiceRequestCoordinator coordinator,
            VoiceReservation reservation,
            ScheduledExecutorService executor,
            long periodMillis) {
        this.owner = Thread.currentThread();
        coordinator.renew(reservation);
        this.future = executor.scheduleAtFixedRate(() -> {
            synchronized (ownershipLock) {
                if (closed) {
                    return;
                }
                try {
                    coordinator.renew(reservation);
                } catch (RuntimeException exception) {
                    if (!closed && failed.compareAndSet(false, true)) {
                        owner.interrupt();
                    }
                }
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    static VoiceLeaseGuard startIfRealtime(
            String providerAlias,
            VoiceSpeechProperties properties,
            VoiceRequestCoordinator coordinator,
            VoiceReservation reservation) {
        if (!"cliproxy-realtime".equals(providerAlias)) {
            return new VoiceLeaseGuard();
        }
        try {
            return new VoiceLeaseGuard(
                    coordinator, reservation, properties.getProviderDeadlineMillis());
        } catch (RuntimeException exception) {
            throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, reservation.requestId());
        }
    }

    private VoiceLeaseGuard() {
        this.owner = null;
        this.future = null;
    }

    void requireOwned(String requestId) {
        if (failed.get()) {
            throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
        }
    }

    @Override
    public void close() {
        synchronized (ownershipLock) {
            closed = true;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "cyf-voice-lease-heartbeat");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> { });
            return thread;
        }
    }
}
