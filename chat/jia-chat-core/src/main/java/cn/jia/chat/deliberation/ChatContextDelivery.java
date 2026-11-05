package cn.jia.chat.deliberation;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Context transport contract, not a second conversation history store.
 * The dispatch owner calls prepare under the existing lane/conversation lock,
 * after its predecessor is terminal, and freezes the result before the first send.
 * Message positions cover non-admitted history. Admitted USER input is selected
 * separately by existing request admission order (baseTurnId identifies its lower
 * bound). Thus queued USER IDs below a later assistant ID cannot be skipped.
 * An arbitrary global message id is not a delivery acknowledgement.
 */
public final class ChatContextDelivery {
    public enum Mode { BOOTSTRAP, DELTA }
    public record Plan(Mode mode, String baseTurnId, String engineThreadId,
                       long afterMessageId, long throughMessageId) {
        public Plan {
            Objects.requireNonNull(mode);
            if (throughMessageId <= 0 || afterMessageId < 0 || afterMessageId > throughMessageId)
                throw invalid("Invalid history range");
            if (mode == Mode.BOOTSTRAP) {
                if (baseTurnId != null || engineThreadId != null || afterMessageId != 0)
                    throw invalid("Bootstrap cannot reuse a native cursor");
            } else if (!identity(baseTurnId) || !identity(engineThreadId)) {
                throw invalid("Delta requires a completed predecessor and its native thread");
            }
        }
        public Map<String, Object> wire() {
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("schemaVersion", 1);
            wire.put("mode", mode.name());
            wire.put("baseTurnId", baseTurnId);
            wire.put("engineThreadId", engineThreadId);
            wire.put("afterMessageId", Long.toString(afterMessageId));
            wire.put("throughMessageId", Long.toString(throughMessageId));
            return java.util.Collections.unmodifiableMap(wire);
        }
    }
    /** Stored with the already completed turn, never advanced by dispatch/stream ACKs. */
    public record Checkpoint(String completedTurnId, String engineThreadId, long throughMessageId) {
        public Checkpoint {
            if (!identity(completedTurnId) || !identity(engineThreadId) || throughMessageId <= 0)
                throw invalid("Incomplete context checkpoint");
        }
    }
    public record Receipt(Mode mode, String engineThreadId, String engineTurnId, long throughMessageId) {
        public Receipt {
            if (mode == null || !identity(engineThreadId) || !identity(engineTurnId) || throughMessageId <= 0)
                throw invalid("Incomplete native completion receipt");
        }
        public Map<String, Object> wire() {
            return Map.of("schemaVersion", 1, "mode", mode.name(), "engineThreadId", engineThreadId,
                    "engineTurnId", engineTurnId, "throughMessageId", Long.toString(throughMessageId));
        }
    }
    private ChatContextDelivery() { }

    /** Empty means leave queued and use the existing completion wakeup, not a new polling timer. */
    public static Optional<Plan> prepare(boolean predecessorTerminal, Checkpoint sameLaneCheckpoint,
            long throughMessageId) {
        if (!predecessorTerminal) return Optional.empty();
        if (sameLaneCheckpoint == null)
            return Optional.of(new Plan(Mode.BOOTSTRAP, null, null, 0, throughMessageId));
        return Optional.of(new Plan(Mode.DELTA, sameLaneCheckpoint.completedTurnId(),
                sameLaneCheckpoint.engineThreadId(), sameLaneCheckpoint.throughMessageId(), throughMessageId));
    }

    /** Called in final persistence, after existing task/snapshot/identity validation. */
    public static Checkpoint complete(String turnId, Plan frozen, Receipt receipt) {
        Objects.requireNonNull(frozen);
        Objects.requireNonNull(receipt);
        if (frozen.mode() != receipt.mode() || frozen.throughMessageId() != receipt.throughMessageId()
                || (frozen.mode() == Mode.DELTA && !frozen.engineThreadId().equals(receipt.engineThreadId())))
            throw invalid("Native receipt differs from the frozen context");
        return new Checkpoint(turnId, receipt.engineThreadId(), receipt.throughMessageId());
    }

    /** Existing snapshots remain valid but never manufacture a native-history checkpoint. */
    public static Optional<Receipt> finalReceipt(String turnId, Map<String, Object> frozenFacts, Object rawReceipt) {
        if (!frozenFacts.containsKey("contextDelivery")) {
            if (rawReceipt != null) throw invalid("Context receipt has no frozen delivery plan");
            return Optional.empty();
        }
        Plan plan = readPlan(object(frozenFacts.get("contextDelivery")));
        Receipt receipt = readReceipt(object(rawReceipt));
        complete(turnId, plan, receipt);
        return Optional.of(receipt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) throw invalid("Context delivery object required");
        return (Map<String, Object>) map;
    }

    public static Plan readPlan(Map<String, Object> wire) {
        version(wire);
        return new Plan(mode(wire.get("mode")), nullableText(wire.get("baseTurnId")),
                nullableText(wire.get("engineThreadId")), decimal(wire.get("afterMessageId"), true),
                decimal(wire.get("throughMessageId"), false));
    }
    public static Receipt readReceipt(Map<String, Object> wire) {
        version(wire);
        return new Receipt(mode(wire.get("mode")), nullableText(wire.get("engineThreadId")),
                nullableText(wire.get("engineTurnId")), decimal(wire.get("throughMessageId"), false));
    }
    private static void version(Map<String, Object> wire) {
        if (wire == null || !(wire.get("schemaVersion") instanceof Number n) || n.doubleValue() != 1)
            throw invalid("Unsupported context delivery version");
    }
    private static Mode mode(Object raw) {
        try { return Mode.valueOf((String) raw); }
        catch (RuntimeException malformed) { throw invalid("Unknown context delivery mode"); }
    }
    private static String nullableText(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String text && identity(text)) return text;
        throw invalid("Invalid context identity");
    }
    private static long decimal(Object raw, boolean zero) {
        if (!(raw instanceof String text) || !text.matches(zero ? "0|[1-9][0-9]*" : "[1-9][0-9]*"))
            throw invalid("Context position must be a canonical decimal string");
        try { return Long.parseLong(text); }
        catch (NumberFormatException overflow) { throw invalid("Context position out of range"); }
    }
    private static boolean identity(String text) {
        return text != null && !text.isBlank() && text.equals(text.trim());
    }
    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
