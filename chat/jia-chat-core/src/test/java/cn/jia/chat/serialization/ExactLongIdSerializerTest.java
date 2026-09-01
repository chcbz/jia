package cn.jia.chat.serialization;

import cn.jia.chat.entity.AgentTaskThreadMessageDTO;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactLongIdSerializerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesConversationAndMessageIdsAsExactDecimalStringsAcrossJsUnsafeBoundary()
            throws Exception {
        long unsafe = 9_007_199_254_740_993L;
        ChatConversationEntity conversation = new ChatConversationEntity().setId(unsafe);
        ChatMessageEntity message = new ChatMessageEntity().setId(Long.MAX_VALUE)
                .setConversationId(Long.toString(unsafe));
        AgentTaskThreadMessageDTO threadMessage = new AgentTaskThreadMessageDTO()
                .setMessageId(Long.MIN_VALUE)
                .setConversationId(Long.toString(unsafe));

        JsonNode conversationJson = mapper.readTree(mapper.writeValueAsBytes(conversation));
        JsonNode messageJson = mapper.readTree(mapper.writeValueAsBytes(message));
        JsonNode threadJson = mapper.readTree(mapper.writeValueAsBytes(threadMessage));

        assertTrue(conversationJson.get("id").isTextual());
        assertEquals("9007199254740993", conversationJson.get("id").textValue());
        assertEquals("9223372036854775807", messageJson.get("id").textValue());
        assertEquals("-9223372036854775808", threadJson.get("messageId").textValue());
        assertEquals("9007199254740993", messageJson.get("conversationId").textValue());
        assertEquals("9007199254740993", ExactWireIds.decimal(unsafe));
        assertEquals("9223372036854775807", ExactWireIds.decimal(Long.MAX_VALUE));

        JsonNode eventJson = mapper.valueToTree(Map.of(
                "eventId", ExactWireIds.decimal(unsafe),
                "messageId", ExactWireIds.decimal(Long.MAX_VALUE),
                "conversationId", ExactWireIds.decimal(Long.MIN_VALUE)));
        assertEquals("9007199254740993", eventJson.get("eventId").textValue());
        assertEquals("9223372036854775807", eventJson.get("messageId").textValue());
        assertEquals("-9223372036854775808", eventJson.get("conversationId").textValue());
    }
}
