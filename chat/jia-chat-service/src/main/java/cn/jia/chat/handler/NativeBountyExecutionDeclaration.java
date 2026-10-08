package cn.jia.chat.handler;

import cn.jia.agent.service.NativeBountyExecutionSessionLookup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact agent.register sibling parser; deliberately independent of fast runtimeCapabilities v1. */
final class NativeBountyExecutionDeclaration {
    static final String TRANSPORT = "PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1";
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "enabled", "transport",
            "commandSchemaVersions", "leaseProtocolVersions", "providerStartFenceVersions",
            "resultCommitProtocolVersions", "operations");
    private static final Set<String> OPERATION_KEYS = Set.of("operation", "inputManifest", "resultManifest");
    private static final Set<String> INPUT_KEYS = Set.of("schemaVersion", "minItems", "maxItems", "mimeTypes");
    private static final Set<String> RESULT_KEYS = Set.of("schemaVersion", "minItems", "maxItems",
            "outputId", "mimeTypes");
    private static final List<String> INPUT_MIMES = List.of("image/jpeg", "image/png");
    private static final List<String> RESULT_MIMES = List.of("image/png");

    private final NativeBountyExecutionSessionLookup.State state;
    private final List<String> operations;

    private NativeBountyExecutionDeclaration(NativeBountyExecutionSessionLookup.State state,
            List<String> operations) {
        this.state = state;
        this.operations = List.copyOf(operations);
    }

    static NativeBountyExecutionDeclaration parse(Object raw) {
        if (raw == null) return new NativeBountyExecutionDeclaration(
                NativeBountyExecutionSessionLookup.State.UNDECLARED, List.of());
        try {
            Map<String, Object> root = exactMap(raw, ROOT_KEYS);
            exactInteger(root.get("schemaVersion"), 1);
            boolean enabled = exactBoolean(root.get("enabled"));
            exactText(root.get("transport"), TRANSPORT);
            exactList(root.get("commandSchemaVersions"), List.of(1));
            exactList(root.get("leaseProtocolVersions"), List.of(1));
            exactList(root.get("providerStartFenceVersions"), List.of(1));
            exactList(root.get("resultCommitProtocolVersions"), List.of(1));
            if (!(root.get("operations") instanceof List<?> declared)) throw invalid();
            if (!enabled) {
                if (!declared.isEmpty()) throw invalid();
                return new NativeBountyExecutionDeclaration(
                        NativeBountyExecutionSessionLookup.State.DISABLED, List.of());
            }
            if (declared.size() != 1) throw invalid();
            Map<String, Object> operation = exactMap(declared.getFirst(), OPERATION_KEYS);
            exactText(operation.get("operation"), "GENERATE_IMAGE");
            Map<String, Object> input = exactMap(operation.get("inputManifest"), INPUT_KEYS);
            exactInteger(input.get("schemaVersion"), 1);
            exactInteger(input.get("minItems"), 0);
            exactInteger(input.get("maxItems"), 32);
            exactList(input.get("mimeTypes"), INPUT_MIMES);
            Map<String, Object> result = exactMap(operation.get("resultManifest"), RESULT_KEYS);
            exactInteger(result.get("schemaVersion"), 1);
            exactInteger(result.get("minItems"), 1);
            exactInteger(result.get("maxItems"), 1);
            exactText(result.get("outputId"), "output_1");
            exactList(result.get("mimeTypes"), RESULT_MIMES);
            return new NativeBountyExecutionDeclaration(
                    NativeBountyExecutionSessionLookup.State.READY, List.of("GENERATE_IMAGE"));
        } catch (RuntimeException invalid) {
            return new NativeBountyExecutionDeclaration(
                    NativeBountyExecutionSessionLookup.State.UNSUPPORTED, List.of());
        }
    }

    NativeBountyExecutionSessionLookup.Snapshot snapshot() {
        return state == NativeBountyExecutionSessionLookup.State.READY
                ? new NativeBountyExecutionSessionLookup.Snapshot(state, 1, TRANSPORT, operations)
                : new NativeBountyExecutionSessionLookup.Snapshot(state, null, null, List.of());
    }

    Map<String, Object> normalizedForReceipt() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("state", state.name());
        if (state == NativeBountyExecutionSessionLookup.State.READY) {
            value.put("schemaVersion", 1);
            value.put("transport", TRANSPORT);
            value.put("supportedOperations", operations);
        }
        return Map.copyOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exactMap(Object raw, Set<String> keys) {
        if (!(raw instanceof Map<?, ?> value)
                || !value.keySet().stream().allMatch(String.class::isInstance)
                || !value.keySet().equals(keys)) throw invalid();
        return (Map<String, Object>) value;
    }

    private static void exactList(Object raw, List<?> expected) {
        if (!(raw instanceof List<?> value) || !value.equals(expected)) throw invalid();
    }

    private static boolean exactBoolean(Object raw) {
        if (!(raw instanceof Boolean value)) throw invalid();
        return value;
    }

    private static void exactInteger(Object raw, int expected) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)
                || ((Number) raw).longValue() != expected) throw invalid();
    }

    private static void exactText(Object raw, String expected) {
        if (!(raw instanceof String value) || !expected.equals(value)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid nativeBountyExecution declaration");
    }
}
