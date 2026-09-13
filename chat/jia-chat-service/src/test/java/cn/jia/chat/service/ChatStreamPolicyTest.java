package cn.jia.chat.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamPolicyTest {
    @Test
    void slowFirstFrameIsObservedWithoutCancellingOrReplacingIt() {
        MutableClock clock = new MutableClock();
        AtomicBoolean cancelled = new AtomicBoolean();
        Flux<String> source = Flux.defer(() -> {
            clock.advanceMillis(30);
            return Flux.just("private-first-frame", "second");
        }).doOnCancel(() -> cancelled.set(true));

        List<ILoggingEvent> events = captureLogs(() -> assertEquals(List.of("private-first-frame", "second"),
                ChatStreamPolicy.firstFrame(source, Duration.ofMillis(20), clock)
                        .collectList().block(Duration.ofSeconds(1))));

        assertFalse(cancelled.get());
        assertEquals(1, events.size());
        assertTrue(events.get(0).getFormattedMessage().contains("outcome=success"));
        assertFalse(events.get(0).getFormattedMessage().contains("private-first-frame"));
    }

    @Test
    void downstreamCancellationStillCancelsUpstreamWithoutSyntheticTimeout() throws Exception {
        MutableClock clock = new MutableClock();
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Flux<String> source = Flux.<String>never()
                .doOnSubscribe(ignored -> subscribed.countDown())
                .doOnCancel(cancelled::countDown);

        Disposable subscription = ChatStreamPolicy.firstFrame(source, Duration.ofMillis(20), clock)
                .subscribe(ignored -> { }, failure::set);
        assertTrue(subscribed.await(1, TimeUnit.SECONDS));
        clock.advanceMillis(30);
        List<ILoggingEvent> events = captureLogs(subscription::dispose);

        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        assertEquals(null, failure.get());
        assertEquals(1, events.size());
        assertTrue(events.get(0).getFormattedMessage().toLowerCase().contains("outcome=cancel"));
    }

    @Test
    void upstreamErrorIsPreserved() {
        IllegalStateException expected = new IllegalStateException("upstream failure");
        AtomicReference<Throwable> actual = new AtomicReference<>();

        ChatStreamPolicy.firstFrame(Flux.error(expected), Duration.ofSeconds(1))
                .subscribe(ignored -> { }, actual::set);

        assertEquals(expected, actual.get());
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

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ChatStreamPolicy.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class MutableClock implements LongSupplier {
        private long now;

        @Override
        public long getAsLong() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
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
