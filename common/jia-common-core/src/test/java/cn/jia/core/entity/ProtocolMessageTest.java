package cn.jia.core.entity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
class ProtocolMessageTest {
    @Test void eventAndReplyHaveDistinctClosedRoots() {
        var request = ProtocolMessage.event("r", "hosting.capabilities", "m", null);
        var json = JsonMapper.builder().build();
        var event = json.readTree(json.writeValueAsBytes(request));
        assertEquals(5, event.size()); assertTrue(event.has("data")); assertFalse(event.has("code"));
        var reply = ProtocolMessage.reply(request, "hosting.capabilities.result", "reply", "E0", "ok", null);
        var response = json.readTree(json.writeValueAsBytes(reply));
        assertEquals(8, response.size()); assertEquals("m", reply.correlationId()); assertEquals("r", reply.requestId());
    }
    @Test void rejectsAliasesAndIncompleteReply() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessage.event("r", "agent_registered", "m", null));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMessage<>(1, "r", "agent.registered", "m", null, "original", null, "ok"));
        assertThrows(IllegalArgumentException.class, () -> ProtocolMessage.event("r\n", "agent.register", "m", null));
    }
}
