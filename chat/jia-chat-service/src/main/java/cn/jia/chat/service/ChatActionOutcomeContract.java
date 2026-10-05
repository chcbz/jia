package cn.jia.chat.service;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unified v3 planning DATA. Validation never grants authority or starts an execution. */
public final class ChatActionOutcomeContract {
    public static final int VERSION = 3;
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> MEDIA = Set.of("text", "image", "audio", "file");
    private ChatActionOutcomeContract() { }

    public record Source(String sourceRefId, String kind, String mediaType) { }
    public record Capability(String actionId, String kind, String operation,
            List<String> inputMediaTypes, int minSources, int maxSources) {
        public Capability { inputMediaTypes = List.copyOf(inputMediaTypes); }
    }
    public record Facts(int schemaVersion, List<Capability> availableActions,
            List<Source> availableSources, List<String> inspectedSourceRefIds) {
        public Facts { availableActions = List.copyOf(availableActions);
            availableSources = List.copyOf(availableSources); inspectedSourceRefIds = List.copyOf(inspectedSourceRefIds); }
    }
    public record Clarification(String question, List<String> requiredFacts) {
        public Clarification { requiredFacts = List.copyOf(requiredFacts); }
    }
    public record Action(String actionId, String instruction, List<String> sourceRefIds) {
        public Action { sourceRefIds = List.copyOf(sourceRefIds); }
    }
    public record DeliveryRelation(String mode, String parentOutcomeId, String parentFinalDigest,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String targetOutcomeId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String targetFinalDigest) {
        public DeliveryRelation(String mode,String parentOutcomeId,String parentFinalDigest) {
            this(mode,parentOutcomeId,parentFinalDigest,null,null);
        }
    }
    public record Outcome(int schemaVersion, String kind, String text, Clarification clarification, Action action,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean deliverable,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DeliveryRelation deliveryRelation) {
        public Outcome(int schemaVersion, String kind, String text, Clarification clarification, Action action) {
            this(schemaVersion,kind,text,clarification,action,null,null);
        }
        public Outcome(int schemaVersion, String kind, String text, Clarification clarification, Action action, Boolean deliverable) {
            this(schemaVersion,kind,text,clarification,action,deliverable,null);
        }
    }

    public static Facts facts(Object value) { return factsNode(JSON.valueToTree(value)); }
    public static Facts factsJson(String value) { return factsNode(parse(value)); }
    public static Outcome outcomeJson(String raw, Facts facts) { return outcomeNode(parse(raw), facts); }
    public static Outcome outcome(Object raw, Facts facts) { return outcomeNode(JSON.valueToTree(raw), facts); }

    private static Facts factsNode(JsonNode raw) {
        exact(raw, "ACTION_FACTS_INVALID", "schemaVersion", "availableActions", "availableSources", "inspectedSourceRefIds");
        if (integer(raw.get("schemaVersion"), "ACTION_FACTS_INVALID") != VERSION) throw invalid("ACTION_FACTS_INVALID");
        var rawSources = array(raw.get("availableSources"), "ACTION_SOURCES_INVALID");
        if (rawSources.size() > 32) throw invalid("ACTION_SOURCES_INVALID");
        List<Source> sources = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (var source : rawSources) {
            exact(source, "ACTION_SOURCES_INVALID", "sourceRefId", "kind", "mediaType");
            String id = string(source.get("sourceRefId"), true, "ACTION_SOURCES_INVALID");
            String kind = string(source.get("kind"), true, "ACTION_SOURCES_INVALID");
            String media = string(source.get("mediaType"), true, "ACTION_SOURCES_INVALID");
            if (!ids.add(id) || !Set.of("TASK_WORKSPACE_FILE", "CURRENT_CONVERSATION_ASSET").contains(kind)
                    || !MEDIA.contains(media)) throw invalid("ACTION_SOURCES_INVALID");
            sources.add(new Source(id, kind, media));
        }
        List<String> inspected = strings(raw.get("inspectedSourceRefIds"), true, "ACTION_INSPECTED_SOURCES_INVALID");
        if (!ids.containsAll(inspected)) throw invalid("ACTION_INSPECTED_SOURCES_INVALID");
        List<Capability> capabilities = new ArrayList<>(); Set<String> actionIds = new HashSet<>();
        for (var action : array(raw.get("availableActions"), "ACTION_CAPABILITIES_INVALID")) {
            String error = "ACTION_CAPABILITIES_INVALID";
            exact(action, error, "actionId", "kind", "operation", "inputMediaTypes", "minSources", "maxSources");
            String id = string(action.get("actionId"), true, error);
            String kind = string(action.get("kind"), true, error);
            String operation = string(action.get("operation"), true, error);
            List<String> media = strings(action.get("inputMediaTypes"), true, error);
            int min = integer(action.get("minSources"), error), max = integer(action.get("maxSources"), error);
            if (!actionIds.add(id) || !Set.of("INSPECT_INPUTS", "EXECUTE").contains(kind) || !MEDIA.containsAll(media)
                    || min < 0 || max < min || max > 32 || (max > 0 && media.isEmpty())
                    || ("INSPECT_INPUTS".equals(kind) && (!"INSPECT_INPUTS".equals(operation) || min < 1))
                    || ("EXECUTE".equals(kind) && "INSPECT_INPUTS".equals(operation))) throw invalid(error);
            capabilities.add(new Capability(id, kind, operation, media, min, max));
        }
        return new Facts(VERSION, capabilities, sources, inspected);
    }

    private static Outcome outcomeNode(JsonNode value, Facts suppliedFacts) {
        // Do not trust a caller-constructed record more than a parsed JSON fact set.
        Facts facts = facts(suppliedFacts);
        List<String> keys = new ArrayList<>(List.of("schemaVersion","kind","text","clarification","action"));
        Boolean deliverable = null; DeliveryRelation relation = null;
        if (value != null && value.isObject() && value.has("deliverable")) {
            keys.add("deliverable");
            if (!value.get("deliverable").isBoolean()) throw invalid("ACTION_OUTCOME_INVALID");
            deliverable = value.get("deliverable").booleanValue();
        }
        if (value != null && value.isObject() && value.has("deliveryRelation")) {
            keys.add("deliveryRelation"); var raw = value.get("deliveryRelation");
            if (!raw.isNull()) {
                boolean targeted=raw.has("targetOutcomeId")||raw.has("targetFinalDigest");
                if(targeted)exact(raw,"ACTION_DELIVERY_RELATION_INVALID","mode","parentOutcomeId","parentFinalDigest","targetOutcomeId","targetFinalDigest");
                else exact(raw,"ACTION_DELIVERY_RELATION_INVALID","mode","parentOutcomeId","parentFinalDigest");
                String mode=string(raw.get("mode"),true,"ACTION_DELIVERY_RELATION_INVALID");
                String parent=string(raw.get("parentOutcomeId"),true,"ACTION_DELIVERY_RELATION_INVALID");
                String digest=string(raw.get("parentFinalDigest"),true,"ACTION_DELIVERY_RELATION_INVALID");
                if (!Set.of("APPEND","REPLACE","RESET").contains(mode)
                        || !parent.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                        || !digest.matches("sha256:[0-9a-f]{64}")) throw invalid("ACTION_DELIVERY_RELATION_INVALID");
                String target=null,targetDigest=null;
                if(targeted) {
                    target=string(raw.get("targetOutcomeId"),true,"ACTION_DELIVERY_RELATION_INVALID");
                    targetDigest=string(raw.get("targetFinalDigest"),true,"ACTION_DELIVERY_RELATION_INVALID");
                    if(!"REPLACE".equals(mode)||!target.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                            ||!targetDigest.matches("sha256:[0-9a-f]{64}"))throw invalid("ACTION_DELIVERY_RELATION_INVALID");
                }
                relation=new DeliveryRelation(mode,parent,digest,target,targetDigest);
            }
        }
        exact(value,"ACTION_OUTCOME_INVALID",keys.toArray(String[]::new));
        if (integer(value.get("schemaVersion"), "ACTION_OUTCOME_INVALID") != VERSION) throw invalid("ACTION_OUTCOME_INVALID");
        String kind = string(value.get("kind"), true, "ACTION_OUTCOME_INVALID");
        String text = string(value.get("text"), false, "ACTION_OUTCOME_INVALID");
        Clarification clarification = null; Action action = null;
        switch (kind) {
            case "ANSWER" -> {
                if (!value.get("clarification").isNull() || !value.get("action").isNull()) throw invalid("ACTION_OUTCOME_UNION_INVALID");
            }
            case "CLARIFY" -> {
                String error = "ACTION_OUTCOME_UNION_INVALID";
                if (!value.get("action").isNull()) throw invalid(error);
                var c = value.get("clarification"); exact(c, error, "question", "requiredFacts");
                String question = string(c.get("question"), false, error);
                List<String> required = strings(c.get("requiredFacts"), false, error);
                if (required.isEmpty()) throw invalid(error);
                clarification = new Clarification(question, required);
            }
            case "ACTION_REQUEST" -> {
                if (!value.get("clarification").isNull()) throw invalid("ACTION_OUTCOME_UNION_INVALID");
                var a = value.get("action"); exact(a, "ACTION_OUTCOME_UNION_INVALID", "actionId", "instruction", "sourceRefIds");
                String id = string(a.get("actionId"), true, "ACTION_NOT_ADVERTISED");
                String instruction = string(a.get("instruction"), false, "ACTION_OUTCOME_UNION_INVALID");
                var descriptor = facts.availableActions().stream().filter(item -> item.actionId().equals(id)).findFirst()
                        .orElseThrow(() -> invalid("ACTION_NOT_ADVERTISED"));
                List<String> selected = strings(a.get("sourceRefIds"), true, "ACTION_SELECTION_INVALID");
                Map<String, Source> catalog = new LinkedHashMap<>(); facts.availableSources().forEach(s -> catalog.put(s.sourceRefId(), s));
                if (selected.size() < descriptor.minSources() || selected.size() > descriptor.maxSources()
                        || selected.stream().anyMatch(ref -> !catalog.containsKey(ref)
                        || !descriptor.inputMediaTypes().contains(catalog.get(ref).mediaType()))) throw invalid("ACTION_SELECTION_INVALID");
                action = new Action(id, instruction, selected);
            }
            default -> throw invalid("ACTION_OUTCOME_KIND_INVALID");
        }
        if (Boolean.TRUE.equals(deliverable) && !"ANSWER".equals(kind)) throw invalid("ACTION_OUTCOME_UNION_INVALID");
        if (relation != null && !Boolean.TRUE.equals(deliverable)) throw invalid("ACTION_DELIVERY_RELATION_INVALID");
        return new Outcome(VERSION, kind, text, clarification, action, deliverable, relation);
    }

    private static JsonNode parse(String value) {
        try { if (value == null) throw invalid("ACTION_JSON_INVALID"); return JSON.readTree(value); }
        catch (RuntimeException failure) { throw invalid("ACTION_JSON_INVALID"); }
    }
    private static void exact(JsonNode value, String code, String... keys) {
        if (value == null || !value.isObject() || value.size() != keys.length) throw invalid(code);
        for (String key : keys) if (!value.has(key)) throw invalid(code);
    }
    private static List<JsonNode> array(JsonNode value, String code) {
        if (value == null || !value.isArray()) throw invalid(code);
        List<JsonNode> result = new ArrayList<>(); value.forEach(result::add); return result;
    }
    private static List<String> strings(JsonNode value, boolean identifier, String code) {
        List<String> result = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (var element : array(value, code)) { String s = string(element, identifier, code);
            if (!unique.add(s)) throw invalid(code); result.add(s); }
        return List.copyOf(result);
    }
    private static int integer(JsonNode value, String code) {
        if (value == null || !value.isNumber()) throw invalid(code);
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw invalid(code); }
    }
    private static String string(JsonNode value, boolean identifier, String code) {
        if (value == null || !value.isTextual()) throw invalid(code);
        String s = value.textValue();
        if (s.isEmpty() || s.codePoints().allMatch(ChatActionOutcomeContract::ecmaWhitespace)
                || (identifier && (s.length() > 512 || s.codePoints().anyMatch(Character::isISOControl)))) throw invalid(code);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i == s.length() || !Character.isLowSurrogate(s.charAt(i))) throw invalid(code); }
            else if (Character.isLowSurrogate(c)) throw invalid(code);
        }
        return s;
    }
    private static boolean ecmaWhitespace(int point) {
        return point == 0x09 || point == 0x0a || point == 0x0b || point == 0x0c || point == 0x0d || point == 0x20
                || point == 0xa0 || point == 0x1680 || (point >= 0x2000 && point <= 0x200a)
                || point == 0x2028 || point == 0x2029 || point == 0x202f || point == 0x205f || point == 0x3000 || point == 0xfeff;
    }
    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
