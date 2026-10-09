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

/** Exact browser wire for typed discussion admission and read-only outcome projection. */
public final class ChatTypedDeliberationWire {
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final TypeReference<Map<String,Object>> OBJECT = new TypeReference<>() { };
    private static final Set<String> BODY_KEYS = Set.of("schemaVersion", "intent", "taskId",
            "expectedAssignmentRevision", "content", "parentOutcomeId", "expectedParentStateVersion",
            "pendingQuestionId", "expectedPendingQuestionStateVersion", "sourceSelectors");
    private static final Set<String> SELECTOR_KEYS = Set.of("kind", "fileId", "version", "purpose",
            "assetId", "assetRevision");

    private ChatTypedDeliberationWire() { }

    public record SourceSelector(String kind, String fileId, String version, String purpose,
            String assetId, String assetRevision) { }
    public record DiscussionCommand(String intent, String taskId, long expectedAssignmentRevision,
            String content, String parentOutcomeId, Long expectedParentStateVersion,
            String pendingQuestionId, Long expectedPendingQuestionStateVersion,
            List<SourceSelector> sourceSelectors) {
        public DiscussionCommand { sourceSelectors = List.copyOf(sourceSelectors); }
    }
    public record Accepted(int schemaVersion, String intent, String requestId, String userMessageId,
            List<String> turnIds, String state, String stateVersion, String eventCursor,
            String statusUrl, String typedOutcomeUrl, boolean replay, String pendingQuestionId) {
        public Accepted { turnIds = List.copyOf(turnIds); }
    }
    public record TypedProjection(int schemaVersion, String conversationId, String conversationGeneration,
            String requestId, String requestRevision, String turnId, String state, Outcome outcome) { }
    public record Outcome(String outcomeId, String taskId, String assignmentRevision,
            String assistantMessageId, String finalDigest, String kind, String text,
            Clarification clarification, Proposal proposal) { }
    public record Clarification(String pendingQuestionId, String state, String stateVersion,
            String question, List<String> requiredFacts, String replyRequestId) {
        public Clarification { requiredFacts = List.copyOf(requiredFacts); }
    }
    public record Proposal(String proposalId, String state, String stateVersion, String operation,
            String instruction, List<String> sourceRefIds, List<SourceSelector> sourceSelectors,
            Parent parent) {
        public Proposal { sourceRefIds=List.copyOf(sourceRefIds); sourceSelectors=List.copyOf(sourceSelectors); }
    }
    public record Parent(String requestId, String stepId) { }

    public static DiscussionCommand parse(String raw) {
        if (raw == null || raw.isBlank()) throw invalid();
        try {
            return parse(STRICT_JSON.readValue(raw, OBJECT));
        } catch (ChatDeliberationException failure) {
            throw failure;
        } catch (Exception malformed) {
            throw invalid();
        }
    }

    public static DiscussionCommand parse(Map<String, Object> body) {
        if (body == null || !BODY_KEYS.equals(body.keySet()) || !semanticOne(body.get("schemaVersion"))) throw invalid();
        String intent = text(body.get("intent"), 30);
        if (!Set.of("DISCUSSION", "CLARIFICATION_REPLY").contains(intent)) throw invalid();
        String taskId = text(body.get("taskId"), 100);
        long assignment = decimal(body.get("expectedAssignmentRevision"), false);
        Object rawContent = body.get("content");
        if (!(rawContent instanceof String content) || content.length() > 200_000
                || !validScalar(content)) throw invalid();
        String parent = nullableText(body.get("parentOutcomeId"), 64);
        Long parentVersion = nullableDecimal(body.get("expectedParentStateVersion"));
        String pending = nullableText(body.get("pendingQuestionId"), 64);
        Long pendingVersion = nullableDecimal(body.get("expectedPendingQuestionStateVersion"));
        if (!(body.get("sourceSelectors") instanceof List<?> raw) || raw.size() > 32) throw invalid();
        List<SourceSelector> selectors = new ArrayList<>();
        Set<SourceSelector> uniqueSelectors = new HashSet<>();
        for (Object item : raw) {
            SourceSelector value = selector(item);
            if (!uniqueSelectors.add(value)) throw invalid();
            selectors.add(value);
        }
        if (content.isBlank() && selectors.isEmpty()) throw invalid();
        if ("DISCUSSION".equals(intent)) {
            if (pending != null || pendingVersion != null || (parent == null) != (parentVersion == null)) throw invalid();
        } else if (parent == null || parentVersion == null || pending == null || pendingVersion == null) {
            throw invalid();
        }
        return new DiscussionCommand(intent, taskId, assignment, content, parent, parentVersion,
                pending, pendingVersion, selectors);
    }

    public static Map<String, Object> selectorMap(SourceSelector value) {
        Map<String,Object> result=new LinkedHashMap<>(); result.put("kind",value.kind());
        result.put("fileId",value.fileId()); result.put("version",value.version());
        result.put("purpose",value.purpose()); result.put("assetId",value.assetId());
        result.put("assetRevision",value.assetRevision()); return result;
    }

    /** Validate server-constructed selectors too; scope/role is never inferred by the SQL edge. */
    public static SourceSelector validateSelector(SourceSelector value) {
        if (value == null) throw invalid();
        return selector(selectorMap(value));
    }

    private static SourceSelector selector(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(String.class::isInstance)
                || !map.keySet().equals(SELECTOR_KEYS)) throw invalid();
        String kind = text(map.get("kind"), 50);
        String fileId = nullableText(map.get("fileId"), 100);
        String version = nullableCanonicalDecimal(map.get("version"));
        String purpose = nullableText(map.get("purpose"), 20);
        String assetId = nullableText(map.get("assetId"), 64);
        String assetRevision = nullableCanonicalDecimal(map.get("assetRevision"));
        if ("TASK_LINKED_WORKSPACE_VERSION".equals(kind)) {
            if (fileId == null || version == null || !Set.of("INPUT", "REFERENCE").contains(purpose == null ? "" : purpose)
                    || assetId != null || assetRevision != null
                    || Long.parseLong(version) > Integer.MAX_VALUE) throw invalid();
        } else if ("CURRENT_CONVERSATION_ASSET".equals(kind)) {
            if (fileId != null || version != null || purpose != null || assetId == null || assetRevision == null) throw invalid();
        } else throw invalid();
        return new SourceSelector(kind,fileId,version,purpose,assetId,assetRevision);
    }

    private static boolean semanticOne(Object value) {
        if (!(value instanceof Number number)) return false;
        try { return new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static long decimal(Object value, boolean positive) {
        if (!(value instanceof String text) || !(positive ? text.matches("[1-9][0-9]*") : text.matches("0|[1-9][0-9]*"))) throw invalid();
        try { long result=Long.parseLong(text); if (!Long.toString(result).equals(text)) throw invalid(); return result; }
        catch (NumberFormatException error) { throw invalid(); }
    }
    private static Long nullableDecimal(Object value) { return value == null ? null : decimal(value, false); }
    private static String nullableCanonicalDecimal(Object value) {
        if (value == null) return null; long parsed=decimal(value,true); return Long.toString(parsed);
    }
    private static String nullableText(Object value, int max) { return value == null ? null : text(value,max); }
    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
                || text.codePointCount(0,text.length())>max || !validScalar(text)
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return text;
    }
    private static boolean validScalar(String value) {
        for (int i=0;i<value.length();i++) { char c=value.charAt(i); if (Character.isHighSurrogate(c)) {
            if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
        } else if (Character.isLowSurrogate(c)) return false; } return true;
    }
    private static ChatDeliberationException invalid() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,
                "Typed discussion request is invalid");
    }
}
