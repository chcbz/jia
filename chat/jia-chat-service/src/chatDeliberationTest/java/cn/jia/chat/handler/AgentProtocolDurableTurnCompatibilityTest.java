package cn.jia.chat.handler;

import cn.jia.agent.common.AgentProtocolConstants;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentProtocolDurableTurnCompatibilityTest {
    private final AgentProtocolMessageNormalizer normalizer = new AgentProtocolMessageNormalizer();

    @Test
    void legacyV1RequestIdStillAliasesMessageId() {
        var normalized = normalizer.normalizeInbound(new HashMap<>(Map.of(
                "schemaVersion", 1,
                "messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE,
                "requestId", "legacy-message-1",
                "agentId", "agent-a",
                "conversationId", "42",
                "content", "done")));
        assertEquals("legacy-message-1", normalized.envelope().getMessageId());
    }

    @Test
    void durableTurnAllowsDistinctTransportMessageAndBusinessRequestIds() {
        Map<String, Object> payload = new HashMap<>(Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("messageType", AgentProtocolConstants.TYPE_CHAT_MESSAGE),
                Map.entry("messageId", "transport-final-1"),
                Map.entry("requestId", "request-1"),
                Map.entry("turnId", "turn-1"),
                Map.entry("dispatchId", "dispatch-1"),
                Map.entry("contextSnapshotId", "ctx-1"),
                Map.entry("contextHash", "sha256:abc"),
                Map.entry("agentId", "agent-a"),
                Map.entry("conversationId", "42"),
                Map.entry("content", "done")));
        var normalized = normalizer.normalizeInbound(payload);
        assertEquals("transport-final-1", normalized.envelope().getMessageId());
        assertEquals("request-1", normalized.payload().get("requestId"));
    }
}
