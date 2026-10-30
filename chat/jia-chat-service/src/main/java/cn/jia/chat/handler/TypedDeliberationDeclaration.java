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
    private static final List<String> KINDS = List.of("ANSWER", "CLARIFY", "ACTION_REQUEST", "REVISION_REQUEST", "SUBMISSION_REQUEST");
    private static final String CARRIER = "CHAT_MESSAGE_FINAL_SIDECAR_V5";
    private static final String ENGINE = "CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA";
    private static final String TOOL_POLICY = "read-only-constrained";

    private final State state;
    private final Map<String,Object> materials;
    private final Map<String,Object> nativeExecution;

    private TypedDeliberationDeclaration(State state) { this(state,null,null); }

    private TypedDeliberationDeclaration(State state,Map<String,Object> materials,Map<String,Object> nativeExecution) {this.state=state;this.materials=materials;this.nativeExecution=nativeExecution;}

    public static TypedDeliberationDeclaration parse(Object raw) {
        if (raw == null) return new TypedDeliberationDeclaration(State.UNDECLARED);
        try {
            if (!(raw instanceof Map<?, ?> candidate)
                    || !candidate.keySet().stream().allMatch(String.class::isInstance)
                    || !allowedKeys(candidate.keySet())) throw invalid();
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) candidate;
            exactInteger(value.get("schemaVersion"), 5);
            String state = exactText(value.get("state"));
            if (!Set.of("READY", "UNAVAILABLE").contains(state)) throw invalid();
            exact(value.get("carrier"), CARRIER);
            exactList(value.get("referenceModes"), MODES);
            exactList(value.get("outcomeKinds"), KINDS);
            exact(value.get("engine"), ENGINE);
            if (!(value.get("strictNoToolsVerified") instanceof Boolean verified) || verified) throw invalid();
            exact(value.get("toolPolicy"), TOOL_POLICY);
            return new TypedDeliberationDeclaration(State.valueOf(state),value.containsKey("materialsInput")?cn.jia.chat.service.ChatMaterialsInputContract.declaration(value.get("materialsInput")):null,
                    value.containsKey("nativeExecution")?nativeExecution(value.get("nativeExecution")):null);
        } catch (RuntimeException invalid) {
            return new TypedDeliberationDeclaration(State.UNSUPPORTED);
        }
    }

    public State state() { return state; }
    public List<String> supportedOperations() {
        // Planner readiness is not evidence of any execution adapter or paid authority.
        return List.of();
    }
    public Map<String, Object> frozenReceipt() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 5);
        value.put("state", state.name());
        value.put("carrier", CARRIER);
        value.put("referenceModes", MODES);
        value.put("outcomeKinds", KINDS);
        value.put("engine", ENGINE);
        value.put("strictNoToolsVerified", false);
        value.put("toolPolicy", TOOL_POLICY);
        if(materials!=null)value.put("materialsInput",materials);
        if(nativeExecution!=null)value.put("nativeExecution",nativeExecution);
        return Map.copyOf(value);
    }

    private static boolean allowedKeys(Set<?> keys) {
        var allowed=new java.util.HashSet<>(KEYS);allowed.add("materialsInput");allowed.add("nativeExecution");
        return keys.containsAll(KEYS)&&allowed.containsAll(keys);
    }
    private static Map<String,Object> nativeExecution(Object raw) {
        if(!(raw instanceof Map<?,?> m)||!m.keySet().containsAll(Set.of("schemaVersion","executionProtocolVersion","toolPolicyRef","outputMimeTypes"))
                ||!Set.of("schemaVersion","executionProtocolVersion","toolPolicyRef","outputMimeTypes","supportedToolPolicyRefs").containsAll(m.keySet()))throw invalid();
        exactInteger(m.get("schemaVersion"),1);exactInteger(m.get("executionProtocolVersion"),5);exact(m.get("toolPolicyRef"),"NATIVE_LOCAL_FILES_V1");
        if(!(m.get("outputMimeTypes") instanceof List<?> types)||types.stream().anyMatch(x->!(x instanceof String t)||!Set.of("text/plain","text/markdown","text/html","application/json","image/png",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet").contains(t))||new java.util.HashSet<>(types).size()!=types.size())throw invalid();
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("schemaVersion",1);result.put("executionProtocolVersion",5);result.put("toolPolicyRef","NATIVE_LOCAL_FILES_V1");result.put("outputMimeTypes",List.copyOf(types));
        if(m.containsKey("supportedToolPolicyRefs")) {
            if(!(m.get("supportedToolPolicyRefs") instanceof List<?> refs)||refs.stream().anyMatch(x->!"NATIVE_SKILL_SCRIPT_V1".equals(x))||new java.util.HashSet<>(refs).size()!=refs.size())throw invalid();
            result.put("supportedToolPolicyRefs",List.copyOf(refs));
        }
        return java.util.Collections.unmodifiableMap(result);
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
