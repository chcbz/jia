package cn.jia.chat.service;

import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Shared bounds for the legacy chat streaming routes.
 *
 * <p>The policy is deliberately transport-only: it does not change conversation authorization,
 * persistence, delivery, replay, generation fencing or event payload semantics.</p>
 */
public final class ChatStreamPolicy {
    static final int OUTBOUND_BUFFER_LIMIT = 64;
    static final Duration FIRST_FRAME_DEADLINE = Duration.ofMillis(2500);

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
        return firstFrame(source, FIRST_FRAME_DEADLINE);
    }

    static <T> Flux<T> firstFrame(Flux<T> source, Duration deadline) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("first-frame deadline must be positive");
        }
        return source.timeout(
                Mono.delay(deadline),
                ignored -> Mono.never(),
                Flux.error(new FirstFrameTimeoutException()));
    }

    public static boolean isFirstFrameTimeout(Throwable error) {
        return error instanceof FirstFrameTimeoutException;
    }

    private static final class FirstFrameTimeoutException extends RuntimeException {
        private FirstFrameTimeoutException() {
            super("chat stream first-frame deadline exceeded");
        }
    }
}
