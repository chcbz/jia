package cn.jia.chat.service;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatConversationEventBrokerTest {
    @Test
    void publishSanitizesSensitiveFieldsBeforeStreaming() throws Exception {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        AtomicReference<String> eventJson = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        broker.stream("conv-001")
                .take(1)
                .subscribe(value -> {
                    eventJson.set(value);
                    latch.countDown();
                });

        broker.publish("conv-001", Map.of("type", "agent_message", "accessToken", "raw-token"));

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertFalse(eventJson.get().contains("raw-token"));
        assertTrue(eventJson.get().contains("\"accessToken\":null"));
    }
}
