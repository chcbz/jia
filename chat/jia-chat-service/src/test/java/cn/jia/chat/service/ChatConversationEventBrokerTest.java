package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConversationEventBrokerTest {
    @Test
    void publishSanitizesSensitiveFieldsBeforeStreaming() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        AtomicReference<String> eventJson = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        broker.stream("conv-001", 1L).take(1).subscribe(value -> {
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
        Disposable subscriber = broker.stream("42", 7L)
                .subscribe(ignored -> events.incrementAndGet(), ignored -> { }, completed::countDown);
        Disposable watcher = broker.deletionSignal("42", 7L)
                .subscribe(ignored -> { }, ignored -> { }, completed::countDown);
        assertEquals(1, broker.subscriberCount("42"));
        assertEquals(1, broker.watcherCount("42"));

        try (ChatConversationEventBroker.DeletionFence fence = broker.beginDeletion("42")) {
            fence.commitDeleted(7L);
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertEquals(0, broker.subscriberCount("42"));
        assertEquals(0, broker.watcherCount("42"));
        assertFalse(broker.publishIfLive("42", 7L, () -> true,
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

        assertFalse(broker.runIfLive("77", 3L, () -> true,
                () -> { throw new AssertionError("stale callback ran"); }));
        assertTrue(broker.stream("77", 3L).collectList().block().isEmpty());
        assertTrue(broker.deletionSignal("77", 3L).collectList().block().contains(Boolean.TRUE));
        assertEquals(0, broker.subscriberCount("77"));
        assertEquals(0, broker.watcherCount("77"));
    }
}
