package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamPolicyTest {
    @Test
    void firstFrameDeadlineCancelsSourceWithExplicitTimeoutSignal() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ChatStreamPolicy.firstFrame(
                        Flux.<String>never().doOnCancel(cancelled::countDown),
                        Duration.ofMillis(25))
                .subscribe(ignored -> { }, error -> {
                    failure.set(error);
                    failed.countDown();
                });

        assertTrue(failed.await(1, TimeUnit.SECONDS));
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertTrue(ChatStreamPolicy.isFirstFrameTimeout(failure.get()));
    }

    @Test
    void firstFrameDeadlineStopsAfterFirstApplicationFrame() {
        List<String> values = ChatStreamPolicy.firstFrame(
                        Flux.concat(
                                Flux.just("first"),
                                Mono.delay(Duration.ofMillis(75)).map(ignored -> "second")),
                        Duration.ofMillis(20))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(List.of("first", "second"), values);
    }

    @Test
    void boundedBufferCancelsProducerAtLimitAndRetainsOnlyFixedBacklog() throws Exception {
        AtomicInteger overflowed = new AtomicInteger();
        CountDownLatch cancelled = new CountDownLatch(1);
        NoDemandSubscriber<Integer> subscriber = new NoDemandSubscriber<>();

        Flux<Integer> nonBackpressuredSource = Flux.<Integer>create(emitter -> {
            for (int index = 0; index <= ChatStreamPolicy.OUTBOUND_BUFFER_LIMIT; index++) {
                emitter.next(index);
            }
        }, FluxSink.OverflowStrategy.IGNORE).doOnCancel(cancelled::countDown);

        ChatStreamPolicy.bounded(
                        nonBackpressuredSource,
                        ignored -> overflowed.incrementAndGet())
                .subscribe(subscriber);

        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(1, overflowed.get());
        assertEquals(0, subscriber.received.get());

        subscriber.drain();
        assertTrue(subscriber.failed.await(1, TimeUnit.SECONDS));
        assertEquals(ChatStreamPolicy.OUTBOUND_BUFFER_LIMIT, subscriber.received.get());
        assertTrue(subscriber.failure.get() instanceof IllegalStateException);
    }

    private static final class NoDemandSubscriber<T> extends BaseSubscriber<T> {
        private final CountDownLatch failed = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicInteger received = new AtomicInteger();

        private void drain() {
            requestUnbounded();
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // Intentionally request nothing: a producer must fail at the fixed bound.
        }

        @Override
        protected void hookOnNext(T value) {
            received.incrementAndGet();
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            failure.set(throwable);
            failed.countDown();
        }
    }
}
