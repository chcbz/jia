package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationDurabilityContractTest {
    @Test
    void noSubscriberIsNotDeliveryAndSubscriberReceivesStableEvent() {
        ChatConversationEventBroker broker = new ChatConversationEventBroker();
        assertFalse(broker.publishIfSubscribed("42", 3L, () -> true, Map.of("eventId", "evt-1")));
        AtomicReference<String> received = new AtomicReference<>();
        Disposable subscription = broker.stream("42", 3L, () -> true)
                .subscribe(received::set);
        assertTrue(broker.publishIfSubscribed("42", 3L, () -> true,
                Map.of("eventId", "evt-1", "eventVersion", "9223372036854775806")));
        for (int i=0;i<20 && received.get()==null;i++) {
            try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertNotNull(received.get());
        assertTrue(received.get().contains("9223372036854775806"));
        subscription.dispose();
    }

    @Test
    void workerAndCursorContractsAreIndependentOfHttpSubscription() throws Exception {
        String worker = source("src/main/java/cn/jia/chat/service/ChatDeliberationOutboxRelay.java");
        String relay = source("src/main/java/cn/jia/chat/service/JuyitingAgentRelayService.java");
        String controller = source("src/main/java/cn/jia/chat/api/ChatController.java");
        assertTrue(worker.contains("scheduleWithFixedDelay"));
        assertTrue(worker.contains("recoveredStaleLease"));
        assertTrue(worker.contains("failBuiltinRecovery"));
        assertTrue(worker.contains("builtin.isBuiltinAgent(agentId)"));
        assertTrue(worker.contains("sendDirectMessageToAgent(row.getTenantId()"));
        assertTrue(relay.contains("builtin work is intentionally not tied"));
        assertTrue(controller.contains("Last-Event-ID"));
        assertTrue(controller.contains("eventSequence"));
        assertFalse(controller.contains("createBuiltinSongJiangStream"));
    }

    private String source(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative), StandardCharsets.UTF_8);
    }
}
