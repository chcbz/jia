package cn.jia.chat.api;

import cn.jia.core.entity.ProtocolMessage;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Projects private relay/model frames at the HTTP boundary; never rewrites durable event bytes. */
final class ChatStreamWire {
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ChatStreamWire() { }

    static ProtocolMessage<Map<String, Object>> message(String requestId, String privateFrame) {
        final Map<String, Object> data;
        try {
            data = JSON.readValue(privateFrame, new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid internal chat frame", invalid);
        }
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("Empty internal chat frame");
        if (data.containsKey("occurredAt")) {
            Object timestamp = data.get("occurredAt");
            if (!(timestamp instanceof String value) || !value.matches("0|[1-9][0-9]*"))
                throw new IllegalArgumentException("Invalid internal chat timestamp");
            long epoch = Long.parseLong(value);
            if (epoch > MAX_SAFE_INTEGER) throw new IllegalArgumentException("Unsafe chat timestamp");
            data.put("occurredAt", epoch);
        }
        String type;
        if (data.containsKey("error")) {
            type = "chat.stream.error";
            // Internal exception text may contain provider/SQL credentials. Keep it out of public frames.
            data.put("error", "Stream processing failed");
        } else if ("stream_started".equals(data.get("type"))) type = "chat.stream.started";
        else if ("stream_completed".equals(data.get("type"))) type = "chat.stream.completed";
        else if (data.containsKey("v")) type = "chat.stream.delta";
        else if (data.containsKey("t")) type = "chat.stream.title";
        else if ("agent_message".equals(data.get("type"))) type = "chat.message";
        else if ("agent_message_delta".equals(data.get("type"))) type = "chat.message.delta";
        else type = "chat.stream.event";
        String messageId = data.get("eventId") instanceof String eventId && !eventId.isBlank()
                ? eventId : UUID.randomUUID().toString();
        return ProtocolMessage.event(requestId, type, messageId, data);
    }
}
