package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConversationEventBrokerTest {

    @Test
    void initialControlFrameIsEmittedAfterLiveRegistrationWithoutChangingEventPayloads() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);

        Disposable subscription = broker.stream(
                        "conv-ready", 1L, () -> true, "{\"type\":\"stream_ready\"}")
                .subscribe(event -> {
                    events.add(event);
                    received.countDown();
                });
        assertEquals(1, broker.subscriberCount("conv-ready"));
        assertTrue(broker.publishIfLive(
                "conv-ready", 1L, () -> true,
                Map.of("type", "agent_message", "content", "ready")));

        assertTrue(received.await(2, TimeUnit.SECONDS));
        assertEquals("{\"type\":\"stream_ready\"}", events.getFirst());
        assertTrue(events.get(1).contains("\"type\":\"agent_message\""));
        subscription.dispose();
        assertEquals(0, broker.subscriberCount("conv-ready"));
    }

    @Test
    void slowConversationSubscriberOverflowsAtFixedBoundAndReleasesBrokerState() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        NoDemandSubscriber subscriber = new NoDemandSubscriber();
        broker.stream("conv-slow", 1L, () -> true).subscribe(subscriber);
        assertEquals(1, broker.subscriberCount("conv-slow"));

        for (int index = 0; index <= ChatStreamPolicy.OUTBOUND_BUFFER_LIMIT; index++) {
            broker.publishIfLive(
                    "conv-slow", 1L, () -> true,
                    Map.of("type", "agent_message_delta", "content", Integer.toString(index)));
        }

        assertEquals(0, broker.subscriberCount("conv-slow"),
                "overflow cleanup must not wait for the consumer to drain buffered frames");
        subscriber.drain();
        assertTrue(subscriber.failed.await(2, TimeUnit.SECONDS));
        assertTrue(subscriber.failure.get() instanceof IllegalStateException);
    }
    @Test
    void publishSanitizesSensitiveFieldsBeforeStreaming() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        AtomicReference<String> eventJson = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        broker.stream("conv-001", 1L, () -> true).take(1).subscribe(value -> {
            eventJson.set(value);
            latch.countDown();
        });
        assertTrue(broker.publishIfLive("conv-001", 1L, () -> true,
                Map.of("type", "agent_message", "accessToken", "raw-token")));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertFalse(eventJson.get().contains("raw-token"));
        assertTrue(eventJson.get().contains("\"accessToken\":null"));
    }

    @Test
    void committedDeletionCompletesExistingSubscribersAndLeavesNoSubscriberState() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        CountDownLatch completed = new CountDownLatch(2);
        AtomicInteger events = new AtomicInteger();
        Disposable subscriber = broker.stream("42", 7L, () -> true)
                .subscribe(ignored -> events.incrementAndGet(), ignored -> { }, completed::countDown);
        Disposable watcher = broker.deletionSignal("42", 7L, () -> true)
                .subscribe(ignored -> { }, ignored -> { }, completed::countDown);
        assertEquals(1, broker.subscriberCount("42"));
        assertEquals(1, broker.watcherCount("42"));

        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("42")) {
            fence.commitDeleted(7L);
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(0, broker.subscriberCount("42"));
        assertEquals(0, broker.watcherCount("42"));
        assertFalse(broker.publishIfLive("42", 7L, () -> false,
                Map.of("type", "agent_message")));
        assertEquals(0, events.get());
        subscriber.dispose();
        watcher.dispose();
    }

    @Test
    void staleGenerationCannotPublishOrResubscribeAfterDeletion() {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("77")) {
            fence.commitDeleted(3L);
        }

        assertFalse(broker.runIfLive("77", 3L, () -> false,
                () -> { throw new AssertionError("stale callback ran"); }));
        assertTrue(broker.stream("77", 3L, () -> false).collectList().block().isEmpty());
        assertTrue(broker.deletionSignal("77", 3L, () -> false)
                .collectList().block().contains(Boolean.TRUE));
        assertEquals(0, broker.subscriberCount("77"));
        assertEquals(0, broker.watcherCount("77"));
    }

    private static final class NoDemandSubscriber extends BaseSubscriber<String> {
        private final CountDownLatch failed = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private void drain() {
            requestUnbounded();
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // Do not request: the per-subscriber transport buffer must terminate at its bound.
        }

        @Override
        protected void hookOnError(Throwable throwable) {
            failure.set(throwable);
            failed.countDown();
        }
    }
}
