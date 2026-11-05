package cn.jia.chat.deliberation;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatContextDeliveryTest {
    @Test void queuedLaneDoesNotFreezeBeforePriorResult() {
        assertTrue(ChatContextDelivery.prepare(false, null, 102).isEmpty());
        var previous = new ChatContextDelivery.Checkpoint("turn-1", "native-A", 100);
        assertTrue(ChatContextDelivery.prepare(false, previous, 102).isEmpty());
        var plan = ChatContextDelivery.prepare(true, previous, 102).orElseThrow();
        assertEquals(ChatContextDelivery.Mode.DELTA, plan.mode());
        assertEquals(100, plan.afterMessageId());
        assertEquals("native-A", plan.engineThreadId());
    }
    @Test void absentNativeCheckpointBootstrapsRatherThanGuessingFromMessageId() {
        var plan = ChatContextDelivery.prepare(true, null, 102).orElseThrow();
        assertEquals(ChatContextDelivery.Mode.BOOTSTRAP, plan.mode());
        assertNull(plan.engineThreadId());
        assertEquals(0, plan.afterMessageId());
        assertEquals(plan, ChatContextDelivery.readPlan(plan.wire()));
    }
    @Test void onlyExactCompletedReceiptCreatesTheNextCheckpoint() {
        var plan = ChatContextDelivery.prepare(true, null, 102).orElseThrow();
        var receipt = new ChatContextDelivery.Receipt(ChatContextDelivery.Mode.BOOTSTRAP, "native-A", "native-turn-1", 102);
        var cp = ChatContextDelivery.complete("turn-1", plan, receipt);
        assertEquals(102, cp.throughMessageId());
        var delta = ChatContextDelivery.prepare(true, cp, 104).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.complete("turn-2", delta,
                new ChatContextDelivery.Receipt(ChatContextDelivery.Mode.DELTA, "native-B", "native-turn-2", 104)));
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.complete("turn-2", delta,
                new ChatContextDelivery.Receipt(ChatContextDelivery.Mode.DELTA, "native-A", "native-turn-2", 105)));
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.complete("turn-2", delta, receipt));
        assertEquals("turn-2", ChatContextDelivery.complete("turn-2", delta,
                new ChatContextDelivery.Receipt(ChatContextDelivery.Mode.DELTA, "native-A", "native-turn-2", 104)).completedTurnId());
    }
    @Test void cannotRewindAndPositionsRemainExactAboveJavascriptSafeInteger() {
        var cp = new ChatContextDelivery.Checkpoint("turn-1", "native-A", 9007199254740993L);
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.prepare(true, cp, 9007199254740992L));
        var plan = ChatContextDelivery.prepare(true, cp, 9007199254740995L).orElseThrow();
        assertEquals("9007199254740995", plan.wire().get("throughMessageId"));
        assertEquals(plan, ChatContextDelivery.readPlan(plan.wire()));
        Map<String, Object> bad = new LinkedHashMap<>(plan.wire());
        bad.put("throughMessageId", 9007199254740995L);
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.readPlan(bad));
        bad.put("throughMessageId", "9223372036854775808");
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.readPlan(bad));
    }
    @Test void harmlessAdditionsDoNotInvalidateKnownProtocol() {
        var plan = ChatContextDelivery.prepare(true, null, 10).orElseThrow();
        Map<String, Object> extended = new LinkedHashMap<>(plan.wire());
        extended.put("diagnosticHint", "ignored");
        assertEquals(plan, ChatContextDelivery.readPlan(extended));
        extended.put("mode", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.readPlan(extended));
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.readReceipt(Map.of(
                "schemaVersion", 1, "mode", "DELTA", "engineThreadId", "native-A", "throughMessageId", "10")));
    }
    @Test void finalReceiptIsOptionalOnlyForSnapshotsWithoutAPlan() {
        var plan = ChatContextDelivery.prepare(true, null, 102).orElseThrow();
        var receipt = new ChatContextDelivery.Receipt(ChatContextDelivery.Mode.BOOTSTRAP, "native-A", "native-turn-1", 102);
        var facts = Map.<String, Object>of("contextDelivery", plan.wire());
        assertTrue(ChatContextDelivery.finalReceipt("turn-1", Map.of(), null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.finalReceipt("turn-1", facts, null));
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.finalReceipt("turn-1", Map.of(), receipt.wire()));
        assertThrows(IllegalArgumentException.class, () -> ChatContextDelivery.finalReceipt("turn-1", facts, "not-an-object"));
        assertEquals(receipt, ChatContextDelivery.finalReceipt("turn-1", facts, receipt.wire()).orElseThrow());
        assertEquals(receipt, ChatContextDelivery.readReceipt(receipt.wire()));
    }

    @Test void javaProducerMatchesTheSharedBridgeFixture() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        try (var input = getClass().getResourceAsStream("/contracts/chat-context-delivery-v1.json")) {
            assertNotNull(input);
            var fixture = mapper.readTree(input);
            var bootstrap = ChatContextDelivery.prepare(true, null, 9007199254740993L).orElseThrow();
            var delta = ChatContextDelivery.prepare(true,
                    new ChatContextDelivery.Checkpoint("turn-1", "native-A", 9007199254740993L),
                    9007199254740995L).orElseThrow();
            assertEquals(fixture.get("bootstrap"), mapper.valueToTree(bootstrap.wire()));
            assertEquals(fixture.get("delta"), mapper.valueToTree(delta.wire()));
        }
    }

    @Test void continuationCanReuseAnOldUserMessageWithoutAdvancingTheHistoryWatermark() {
        var cp = new ChatContextDelivery.Checkpoint("prior-turn", "native-A", 104);
        var plan = ChatContextDelivery.prepare(true, cp, 104).orElseThrow();
        assertEquals(104, plan.afterMessageId());
        assertEquals(104, plan.throughMessageId());
        assertEquals("prior-turn", plan.baseTurnId());
    }

}
