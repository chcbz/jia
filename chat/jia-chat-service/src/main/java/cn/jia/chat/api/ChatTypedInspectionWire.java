package cn.jia.chat.api;

import cn.jia.chat.service.ChatDeliberationException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact browser and projection wire for the typed INSPECT sibling contract. */
public final class ChatTypedInspectionWire {
    public static final String CONTRACT = "juyiting-typed-inspection-v1";
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final TypeReference<Map<String,Object>> OBJECT = new TypeReference<>() { };
    private static final Set<String> BODY_KEYS = Set.of("schemaVersion", "intent", "taskId",
            "expectedAssignmentRevision", "content", "parentOutcomeId", "expectedParentStateVersion",
            "pendingQuestionId", "expectedPendingQuestionStateVersion", "sourceSelectors");
    private static final Set<String> SELECTOR_KEYS = Set.of("kind", "fileId", "version", "purpose",
            "assetId", "assetRevision");

    private ChatTypedInspectionWire() { }

    public record SourceSelector(String kind, String fileId, String version, String purpose,
            String assetId, String assetRevision) { }
    public record Command(String intent, String taskId, long expectedAssignmentRevision,
            String content, String parentOutcomeId, Long expectedParentStateVersion,
            String pendingQuestionId, Long expectedPendingQuestionStateVersion,
            List<SourceSelector> sourceSelectors) {
        public Command { sourceSelectors = List.copyOf(sourceSelectors); }
    }
    public record Accepted(int schemaVersion, String intent, String requestId, String userMessageId,
            List<String> turnIds, String state, String stateVersion, String eventCursor,
            String statusUrl, String typedOutcomeUrl, boolean replay, String pendingQuestionId) {
        public Accepted { turnIds = List.copyOf(turnIds); }
    }
    public record Projection(int schemaVersion, String contract, String conversationId,
            String conversationGeneration, String requestId, String requestRevision,
            String turnId, String state, ChatTypedDeliberationWire.Outcome outcome,
            Inspection inspection) { }
    public record Inspection(String authorizationId, String manifestDigest,
            List<String> sourceRefIds, InputSummary inputSummary) {
        public Inspection { sourceRefIds = List.copyOf(sourceRefIds); }
    }
    public record InputSummary(String inputDigest, List<InputSource> sources) {
        public InputSummary { sources = List.copyOf(sources); }
    }
    public record InputSource(String sourceRefId, String sha256, String byteLength,
            String carrier, String contributionDigest) { }

    public static Command parse(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        try { return parse(STRICT_JSON.readValue(raw, OBJECT)); }
        catch (ChatDeliberationException failure) { throw failure; }
        catch (Exception malformed) { throw invalid(); }
    }

    public static Command parse(Map<String, Object> body) {
        if (body == null || !BODY_KEYS.equals(body.keySet()) || !semanticOne(body.get("schemaVersion"))) throw invalid();
        String intent = text(body.get("intent"), 30);
        if (!Set.of("DISCUSSION", "CLARIFICATION_REPLY").contains(intent)) throw invalid();
        String taskId = text(body.get("taskId"), 100);
        long assignment = decimal(body.get("expectedAssignmentRevision"), false);
        Object rawContent = body.get("content");
        if (!(rawContent instanceof String content) || content.isBlank() || content.length() > 200_000
                || !validScalar(content)) throw invalid();
        String parent = nullableText(body.get("parentOutcomeId"), 64);
        Long parentVersion = nullableDecimal(body.get("expectedParentStateVersion"));
        String pending = nullableText(body.get("pendingQuestionId"), 64);
        Long pendingVersion = nullableDecimal(body.get("expectedPendingQuestionStateVersion"));
        if (!(body.get("sourceSelectors") instanceof List<?> raw) || raw.size() > 32) throw invalid();
        List<SourceSelector> selectors = new ArrayList<>();
        Set<SourceSelector> unique = new HashSet<>();
        for (Object item : raw) { SourceSelector selector = selector(item); if (!unique.add(selector)) throw invalid(); selectors.add(selector); }
        if ("DISCUSSION".equals(intent)) {
            if (pending != null || pendingVersion != null || (parent == null) != (parentVersion == null)) throw invalid();
        } else if (parent == null || parentVersion == null || pending == null || pendingVersion == null) throw invalid();
        return new Command(intent, taskId, assignment, content, parent, parentVersion,
                pending, pendingVersion, selectors);
    }

    public static Map<String, Object> selectorMap(SourceSelector value) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("kind",value.kind());
        result.put("fileId",value.fileId()); result.put("version",value.version());
        result.put("purpose",value.purpose()); result.put("assetId",value.assetId());
        result.put("assetRevision",value.assetRevision()); return result;
    }

    public static SourceSelector validateSelector(SourceSelector value) {
        if (value == null) throw invalid();
        return selector(selectorMap(value));
    }

    private static SourceSelector selector(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(String.class::isInstance)
                || !map.keySet().equals(SELECTOR_KEYS)) throw invalid();
        String kind=text(map.get("kind"),50),file=nullableText(map.get("fileId"),100);
        String version=nullableCanonicalDecimal(map.get("version")),purpose=nullableText(map.get("purpose"),20);
        String asset=nullableText(map.get("assetId"),64),revision=nullableCanonicalDecimal(map.get("assetRevision"));
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(kind)) {
            if (file==null||version==null||!("INPUT".equals(purpose)||"REFERENCE".equals(purpose))||asset!=null||revision!=null
                    ||Long.parseLong(version)>Integer.MAX_VALUE) throw invalid();
        } else if ("CURRENT_CONVERSATION_ASSET".equals(kind)) {
            if(file!=null||version!=null||purpose!=null||asset==null||revision==null)throw invalid();
        } else throw invalid();
        return new SourceSelector(kind,file,version,purpose,asset,revision);
    }
    private static boolean semanticOne(Object value) {
        if (!(value instanceof Number number)) return false;
        try { return new BigDecimal(number.toString()).compareTo(BigDecimal.ONE)==0; }
        catch (RuntimeException invalid) { return false; }
    }
    private static long decimal(Object value, boolean positive) {
        if (!(value instanceof String text) || !(positive?text.matches("[1-9][0-9]*"):text.matches("0|[1-9][0-9]*"))) throw invalid();
        try { long parsed=Long.parseLong(text); if(!Long.toString(parsed).equals(text))throw invalid(); return parsed; }
        catch(RuntimeException failure){throw invalid();}
    }
    private static Long nullableDecimal(Object value){return value==null?null:decimal(value,false);}
    private static String nullableCanonicalDecimal(Object value){return value==null?null:Long.toString(decimal(value,true));}
    private static String nullableText(Object value,int max){return value==null?null:text(value,max);}
    private static String text(Object value,int max){if(!(value instanceof String text)||text.isBlank()||!text.equals(text.strip())
            ||text.codePointCount(0,text.length())>max||!validScalar(text)||text.codePoints().anyMatch(Character::isISOControl))throw invalid();return text;}
    private static boolean validScalar(String value){for(int i=0;i<value.length();i++){char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    private static ChatDeliberationException invalid(){return new ChatDeliberationException(
            ChatDeliberationException.Reason.INVALID_REQUEST,"Typed inspection request is invalid");}
}
