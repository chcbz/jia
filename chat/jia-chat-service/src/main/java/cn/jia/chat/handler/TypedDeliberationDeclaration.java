package cn.jia.chat.handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict parser for the client-owned typed deliberation registration sibling. */
public final class TypedDeliberationDeclaration {
    public enum State { UNDECLARED, UNAVAILABLE, READY, UNSUPPORTED }

    private static final Set<String> KEYS = Set.of("schemaVersion", "state", "carrier",
            "referenceModes", "outcomeKinds", "engine", "strictNoToolsVerified", "toolPolicy");
    private static final List<String> MODES = List.of("NONE", "AVAILABLE");
    private static final List<String> KINDS = List.of("ANSWER", "CLARIFY", "EXECUTION_PROPOSAL");
    private static final List<String> OPERATIONS = List.of("GENERATE_IMAGE", "EDIT_IMAGE");
    private static final String CARRIER = "CHAT_MESSAGE_FINAL_SIDECAR_V1";
    private static final String ENGINE = "CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA";
    private static final String TOOL_POLICY = "read-only-constrained";

    private final State state;

    private TypedDeliberationDeclaration(State state) { this.state = state; }

    public static TypedDeliberationDeclaration parse(Object raw) {
        if (raw == null) return new TypedDeliberationDeclaration(State.UNDECLARED);
        try {
            if (!(raw instanceof Map<?, ?> candidate)
                    || !candidate.keySet().stream().allMatch(String.class::isInstance)
                    || !candidate.keySet().equals(KEYS)) throw invalid();
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) candidate;
            exactInteger(value.get("schemaVersion"), 1);
            String state = exactText(value.get("state"));
            if (!Set.of("READY", "UNAVAILABLE").contains(state)) throw invalid();
            exact(value.get("carrier"), CARRIER);
            exactList(value.get("referenceModes"), MODES);
            exactList(value.get("outcomeKinds"), KINDS);
            exact(value.get("engine"), ENGINE);
            if (!(value.get("strictNoToolsVerified") instanceof Boolean verified) || verified) throw invalid();
            exact(value.get("toolPolicy"), TOOL_POLICY);
            return new TypedDeliberationDeclaration(State.valueOf(state));
        } catch (RuntimeException invalid) {
            return new TypedDeliberationDeclaration(State.UNSUPPORTED);
        }
    }

    public State state() { return state; }
    public List<String> supportedOperations() {
        return state == State.READY ? OPERATIONS : List.of();
    }
    public Map<String, Object> frozenReceipt() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("state", state.name());
        value.put("carrier", CARRIER);
        value.put("referenceModes", MODES);
        value.put("outcomeKinds", KINDS);
        value.put("engine", ENGINE);
        value.put("strictNoToolsVerified", false);
        value.put("toolPolicy", TOOL_POLICY);
        return Map.copyOf(value);
    }

    private static void exactInteger(Object value, int expected) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() != expected) throw invalid();
    }
    private static String exactText(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }
    private static void exact(Object value, String expected) {
        if (!(value instanceof String text) || !expected.equals(text)) throw invalid();
    }
    private static void exactList(Object value, List<String> expected) {
        if (!(value instanceof List<?> list) || !list.equals(expected)) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid typedDeliberation declaration");
    }
}
