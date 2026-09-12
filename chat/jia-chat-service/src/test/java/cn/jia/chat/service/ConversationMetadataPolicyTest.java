package cn.jia.chat.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConversationMetadataPolicyTest {
    @Test
    void keepsOnlyBoundedPrimitiveAllowlistedValues() {
        Map<String, Object> safe = ConversationMetadataPolicy.copyAllowed(Map.of(
                "conversationId", "41",
                "chunkIndex", 2,
                "targetAgentIds", List.of("agent-a", "agent-b"),
                "accessToken", "secret",
                "senderName", Map.of("nested", "payload"),
                "taskId", "x".repeat(513)));

        assertEquals("41", safe.get("conversationId"));
        assertEquals(2, safe.get("chunkIndex"));
        assertEquals(List.of("agent-a", "agent-b"), safe.get("targetAgentIds"));
        assertFalse(safe.containsKey("accessToken"));
        assertFalse(safe.containsKey("senderName"));
        assertFalse(safe.containsKey("taskId"));
    }

    @Test
    void rejectsControlCharactersAndMixedOrOversizedCollections() {
        Map<String, Object> safe = ConversationMetadataPolicy.copyAllowed(Map.of(
                "requestId", "bad\nvalue",
                "participantAgentIds", List.of("agent-a", 7),
                "targetAgentIds", java.util.Collections.nCopies(101, "agent"),
                "phase", true));

        assertEquals(Map.of("phase", true), safe);
    }
}
