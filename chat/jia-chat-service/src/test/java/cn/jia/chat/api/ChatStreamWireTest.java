package cn.jia.chat.api;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ChatStreamWireTest {
    @Test void contentAndTitleHaveOneClosedEnvelopeWithoutChangingText() {
        for (var pair : Map.of("v", "chat.stream.delta", "t", "chat.stream.title").entrySet()) {
            String content = "中文\n\"title\"\\token";
            Map<?, ?> wire = JsonUtil.fromJson(ChatStreamWire.encode("request", JsonUtil.toJson(Map.of(pair.getKey(), content))), Map.class);
            assertEquals(Set.of("protocolVersion", "requestId", "messageType", "messageId", "data"), wire.keySet());
            assertEquals("request", wire.get("requestId"));
            assertEquals(pair.getValue(), wire.get("messageType"));
            assertEquals(content, ((Map<?, ?>) wire.get("data")).get(pair.getKey()));
        }
    }
    @Test void durableIdentityAndLongVersionsAreNotOverwrittenByTransportRequestId() {
        String original = "{\"requestId\":\"durable-request\",\"eventId\":\"original-event\",\"eventVersion\":\"9007199254740993\",\"occurredAt\":\"1000\"}";
        Map<?, ?> first = JsonUtil.fromJson(ChatStreamWire.encode("transport-request", original), Map.class);
        Map<?, ?> again = JsonUtil.fromJson(ChatStreamWire.encode("transport-request", original), Map.class);
        assertEquals("original-event", first.get("messageId"));
        assertEquals(first, again);
        Map<?, ?> data = (Map<?, ?>) first.get("data");
        assertEquals("durable-request", data.get("requestId"));
        assertEquals("9007199254740993", data.get("eventVersion"));
        assertEquals(1000, data.get("occurredAt"));
        assertTrue(original.contains("\"occurredAt\":\"1000\""));
    }
    @Test void privateErrorsAreNotExposed() {
        String wire = ChatStreamWire.encode("request", "{\"error\":\"secret SQL password\",\"conversationId\":\"42\"}");
        assertFalse(wire.contains("secret"));
        assertTrue(wire.contains("chat.stream.error"));
        assertTrue(wire.contains("42"));
    }
    @Test void invalidPrivateFramesCannotBecomeSuccessfulPublicEvents() {
        for (String invalid : new String[] {"plain", "{}", "null", "[]", "{\"v\":1,\"v\":2}",
                "{\"occurredAt\":\"01\"}", "{\"occurredAt\":\"9007199254740992\"}"})
            assertThrows(IllegalArgumentException.class, () -> ChatStreamWire.encode("request", invalid));
    }
}
