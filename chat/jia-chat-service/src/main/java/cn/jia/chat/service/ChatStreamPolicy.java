package cn.jia.chat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Shared bounds and observations for the legacy chat streaming routes.
 *
 * <p>The policy is deliberately transport-only: it does not change conversation authorization,
 * persistence, delivery, replay, generation fencing or event payload semantics.</p>
 */
public final class ChatStreamPolicy {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamPolicy.class);

    static final int OUTBOUND_BUFFER_LIMIT = 64;
    static final Duration FIRST_FRAME_SLOW_THRESHOLD = Duration.ofMillis(2500);

    private ChatStreamPolicy() {
    }

    public static <T> Flux<T> bounded(Flux<T> source) {
        return bounded(source, ignored -> { });
    }

    static <T> Flux<T> bounded(Flux<T> source, Consumer<T> overflowHandler) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(overflowHandler, "overflowHandler");
        return source.onBackpressureBuffer(
                OUTBOUND_BUFFER_LIMIT,
                overflowHandler,
                BufferOverflowStrategy.ERROR);
    }

    public static <T> Flux<T> firstFrame(Flux<T> source) {
        return firstFrame(source, FIRST_FRAME_SLOW_THRESHOLD, System::nanoTime);
    }

    static <T> Flux<T> firstFrame(Flux<T> source, Duration threshold) {
        return firstFrame(source, threshold, System::nanoTime);
    }

    static <T> Flux<T> firstFrame(Flux<T> source, Duration threshold, LongSupplier monotonicClock) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(monotonicClock, "monotonicClock");
        if (threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("first-frame observation threshold must be positive");
        }
        return Flux.defer(() -> {
            long startedNanos = monotonicClock.getAsLong();
            AtomicBoolean firstFrameSeen = new AtomicBoolean();
            return source
                    .doOnNext(ignored -> {
                        if (firstFrameSeen.compareAndSet(false, true)) {
                            observeSlowFirstFrame(monotonicClock, startedNanos, threshold, "success");
                        }
                    })
                    .doFinally(signal -> {
                        if (firstFrameSeen.compareAndSet(false, true)) {
                            observeSlowFirstFrame(monotonicClock, startedNanos, threshold, signal.name());
                        }
                    });
        });
    }

    private static void observeSlowFirstFrame(LongSupplier monotonicClock, long startedNanos,
            Duration threshold, String outcome) {
        long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
        long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        if (elapsedMillis >= threshold.toMillis()) {
            log.warn("Slow chat stream first frame observed: elapsedMs={}, thresholdMs={}, outcome={}",
                    elapsedMillis, threshold.toMillis(), outcome);
        }
    }
}
