package cn.jia.chat.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, fail-closed parser for the independent typed INSPECT runtime profile. */
public final class TypedInspectionDeclaration {
    public static final String CONTRACT = "juyiting-typed-inspection-v1";
    public static final String RECOVERY = "durable-inbox-turn-readback-v1";
    private static final Set<String> KEYS = Set.of("schemaVersion", "contract", "enabled",
            "profileId", "engineContractId", "enginePolicyDigest", "toolPolicyDigest",
            "inputPolicyDigest", "toolPolicy", "recovery", "supportedInputs");
    private static final Set<String> INPUT_KEYS = Set.of(
            "mediaKind", "mimeType", "carrier", "carrierContractDigest");
    private static final Set<String> MEDIA = Set.of("text", "image", "audio", "file");
    private static final Set<String> CARRIERS = Set.of(
            "DIRECT_TEXT", "LOCAL_IMAGE", "LOCAL_AUDIO", "PARSED_TEXT");
    private static final Set<String> TOOL_POLICIES = Set.of("STRICT_NO_TOOLS", "MANIFEST_READ_ONLY");

    public enum State { UNDECLARED, DISABLED, READY, UNSUPPORTED }
    public record Input(String mediaKind, String mimeType, String carrier,
            String carrierContractDigest) { }

    private final State state;
    private final Map<String, Object> frozen;
    private final List<Input> inputs;

    private TypedInspectionDeclaration(State state, Map<String, Object> frozen, List<Input> inputs) {
        this.state = state;
        this.frozen = frozen == null ? Map.of() : Map.copyOf(frozen);
        this.inputs = inputs == null ? List.of() : List.copyOf(inputs);
    }

    public static TypedInspectionDeclaration parse(Object raw) {
        if (raw == null) return new TypedInspectionDeclaration(State.UNDECLARED, null, null);
        try {
            if (!(raw instanceof Map<?, ?> candidate)
                    || !candidate.keySet().stream().allMatch(String.class::isInstance)
                    || !candidate.keySet().equals(KEYS)) throw invalid();
            @SuppressWarnings("unchecked") Map<String, Object> value = (Map<String, Object>) candidate;
            exactInteger(value.get("schemaVersion"), 1);
            exact(value.get("contract"), CONTRACT);
            if (!(value.get("enabled") instanceof Boolean enabled)) throw invalid();
            String profileId = identifier(value.get("profileId"), 100);
            String engineContractId = identifier(value.get("engineContractId"), 100);
            String enginePolicyDigest = digest(value.get("enginePolicyDigest"));
            String toolPolicyDigest = digest(value.get("toolPolicyDigest"));
            String inputPolicyDigest = digest(value.get("inputPolicyDigest"));
            String toolPolicy = text(value.get("toolPolicy"));
            if (!TOOL_POLICIES.contains(toolPolicy)) throw invalid();
            exact(value.get("recovery"), RECOVERY);
            if (!(value.get("supportedInputs") instanceof List<?> rawInputs)) throw invalid();
            List<Input> inputs = new ArrayList<>();
            List<Map<String, Object>> frozenInputs = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (Object item : rawInputs) {
                if (!(item instanceof Map<?, ?> map)
                        || !map.keySet().stream().allMatch(String.class::isInstance)
                        || !map.keySet().equals(INPUT_KEYS)) throw invalid();
                String media = text(map.get("mediaKind"));
                String mime = text(map.get("mimeType"));
                String carrier = text(map.get("carrier"));
                String carrierDigest = digest(map.get("carrierContractDigest"));
                if (!MEDIA.contains(media) || !mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                        || !CARRIERS.contains(carrier)
                        || !unique.add(media + "\0" + mime)) throw invalid();
                if (("DIRECT_TEXT".equals(carrier) && !"text".equals(media))
                        || ("LOCAL_IMAGE".equals(carrier) && !"image".equals(media))
                        || ("LOCAL_AUDIO".equals(carrier) && !"audio".equals(media))
                        || ("PARSED_TEXT".equals(carrier) && !"file".equals(media))) throw invalid();
                inputs.add(new Input(media, mime, carrier, carrierDigest));
                Map<String, Object> frozenInput = new LinkedHashMap<>();
                frozenInput.put("mediaKind", media); frozenInput.put("mimeType", mime);
                frozenInput.put("carrier", carrier); frozenInput.put("carrierContractDigest", carrierDigest);
                frozenInputs.add(Collections.unmodifiableMap(frozenInput));
            }
            if (enabled && inputs.isEmpty()) throw invalid();
            Map<String, Object> frozen = new LinkedHashMap<>();
            frozen.put("schemaVersion", 1); frozen.put("contract", CONTRACT); frozen.put("enabled", enabled);
            frozen.put("profileId", profileId); frozen.put("engineContractId", engineContractId);
            frozen.put("enginePolicyDigest", enginePolicyDigest); frozen.put("toolPolicyDigest", toolPolicyDigest);
            frozen.put("inputPolicyDigest", inputPolicyDigest); frozen.put("toolPolicy", toolPolicy);
            frozen.put("recovery", RECOVERY); frozen.put("supportedInputs", List.copyOf(frozenInputs));
            return new TypedInspectionDeclaration(enabled ? State.READY : State.DISABLED,
                    Collections.unmodifiableMap(frozen), inputs);
        } catch (RuntimeException ignored) {
            return new TypedInspectionDeclaration(State.UNSUPPORTED, null, null);
        }
    }

    public State state() { return state; }
    public Map<String, Object> frozenReceipt() { return frozen; }
    public Map<String, Object> manifestProfile() {
        if (state != State.READY) throw new IllegalStateException("Typed inspection runtime is unavailable");
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("profileId", frozen.get("profileId"));
        profile.put("engineContractId", frozen.get("engineContractId"));
        profile.put("enginePolicyDigest", frozen.get("enginePolicyDigest"));
        profile.put("toolPolicyDigest", frozen.get("toolPolicyDigest"));
        profile.put("inputPolicyDigest", frozen.get("inputPolicyDigest"));
        return Collections.unmodifiableMap(profile);
    }
    public Input requireInput(String mediaKind, String mimeType) {
        if (state != State.READY) throw new IllegalStateException("Typed inspection runtime is unavailable");
        List<Input> matches = inputs.stream().filter(input -> input.mediaKind().equals(mediaKind)
                && input.mimeType().equals(mimeType)).toList();
        if (matches.size() != 1) throw new IllegalStateException("Typed inspection input is unsupported");
        return matches.getFirst();
    }

    private static void exactInteger(Object value, int expected) {
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() != expected) throw invalid();
    }
    private static String text(Object value) { if (!(value instanceof String text)) throw invalid(); return text; }
    private static String identifier(Object value, int max) {
        String text = text(value);
        if (text.isBlank() || !text.equals(text.strip()) || text.codePointCount(0, text.length()) > max
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }
    private static String digest(Object value) {
        String text = text(value);
        if (!text.matches("sha256:[0-9a-f]{64}")) throw invalid();
        return text;
    }
    private static void exact(Object value, String expected) {
        if (!(value instanceof String text) || !expected.equals(text)) throw invalid();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid typedInspection declaration");
    }
}
