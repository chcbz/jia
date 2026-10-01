package cn.jia.chat.service;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure validator and canonical digest builder for typed INSPECT v2 finals.
 * A validated execution proposal is descriptive data only; it grants no authority or consent.
 */
public final class TypedInspectionFinalValidator {
    private static final ObjectMapper STRICT_JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final BigInteger MAX_SIGNED_BIGINT = BigInteger.valueOf(Long.MAX_VALUE);
    private static final int MAX_CONTENT_UTF16 = 200_000;
    private static final int MAX_INSTRUCTION_CODE_POINTS = 4_000;
    private static final int MAX_SOURCE_ID_UTF16 = 512;
    private static final int MAX_SOURCES = 16;
    private static final Set<String> FACT_KEYS = Set.of(
            "schemaVersion", "referenceMode", "supportedOperations", "availableSources");
    private static final Set<String> SOURCE_KEYS = Set.of("sourceRefId", "kind", "mediaType");
    private static final Set<String> OUTCOME_KEYS = Set.of(
            "schemaVersion", "kind", "text", "clarification", "proposal");
    private static final Set<String> CLARIFICATION_KEYS = Set.of("question", "requiredFacts");
    private static final Set<String> PROPOSAL_KEYS = Set.of("operation", "instruction", "sourceRefIds");
    private static final Set<String> BINDING_KEYS = Set.of(
            "tenantId", "ownerJiacn", "clientId", "conversationId", "conversationGeneration",
            "requestId", "requestRevision", "turnId", "dispatchId", "snapshotId",
            "contextDigest", "targetAgentId", "route", "taskId");
    private static final Set<String> OPERATIONS = Set.of("GENERATE_IMAGE", "EDIT_IMAGE");
    private static final Set<String> SOURCE_KINDS = Set.of(
            "TASK_WORKSPACE_FILE", "CURRENT_CONVERSATION_ASSET");
    private static final Set<String> MEDIA_TYPES = Set.of("text", "image", "audio", "file");
    private static final Set<String> REQUIRED_FACTS = Set.of(
            "SOURCE_SELECTION", "REFERENCE_REQUIRED", "REQUIREMENT_DETAILS", "OPERATION_CHOICE");
    private static final Set<String> RECEIPT_KEYS = Set.of("schemaVersion", "authorizationId",
            "manifestDigest", "inputDigest", "engineThreadId", "engineTurnId", "sources");
    private static final Set<String> RECEIPT_SOURCE_KEYS = Set.of(
            "sourceRefId", "sha256", "byteLength", "carrier", "contributionDigest");

    private TypedInspectionFinalValidator() { }

    public enum Reason {
        INVALID_FACTS,
        INVALID_OUTCOME,
        INVALID_JSON,
        INVALID_BINDING,
        INVALID_CONTENT,
        INVALID_RECEIPT,
        CONTENT_MISMATCH
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final Reason reason;
        private final String code;

        private ValidationException(Reason reason) {
            super("TYPED_FINAL_" + reason.name());
            this.reason = reason;
            this.code = "TYPED_FINAL_" + reason.name();
        }

        public Reason reason() { return reason; }
        public String code() { return code; }
    }

    public record Binding(
            String tenantId,
            String ownerJiacn,
            String clientId,
            String conversationId,
            String conversationGeneration,
            String requestId,
            String requestRevision,
            String turnId,
            String dispatchId,
            String snapshotId,
            String contextDigest,
            String targetAgentId,
            String route,
            String taskId) { }

    public record Source(String sourceRefId, String kind, String mediaType) { }

    public record DispatchFacts(
            int schemaVersion,
            String referenceMode,
            List<String> supportedOperations,
            List<Source> availableSources) {
        public DispatchFacts {
            supportedOperations = List.copyOf(supportedOperations);
            availableSources = List.copyOf(availableSources);
        }
    }

    public record Clarification(String question, List<String> requiredFacts) {
        public Clarification { requiredFacts = List.copyOf(requiredFacts); }
    }

    public record Proposal(String operation, String instruction, List<String> sourceRefIds) {
        public Proposal { sourceRefIds = List.copyOf(sourceRefIds); }
    }

    public record InteractionOutcome(
            int schemaVersion,
            String kind,
            String text,
            Clarification clarification,
            Proposal proposal) { }

    public record ManifestSource(String sourceRefId, String sha256, String byteLength,
            String mimeType, String carrier, String carrierContractDigest) { }

    public record InspectionAuthority(String authorizationId, String manifestDigest,
            List<ManifestSource> sources) {
        public InspectionAuthority { sources = List.copyOf(sources); }
    }

    public record ReceiptSource(String sourceRefId, String sha256, String byteLength,
            String carrier, String contributionDigest) { }

    public record InspectionInputReceipt(int schemaVersion, String authorizationId,
            String manifestDigest, String inputDigest, String engineThreadId, String engineTurnId,
            List<ReceiptSource> sources) {
        public InspectionInputReceipt { sources = List.copyOf(sources); }
    }

    public record ValidatedFinal(
            Binding binding,
            DispatchFacts dispatchFacts,
            String content,
            InteractionOutcome interactionOutcome,
            InspectionInputReceipt inspectionInputReceipt,
            String canonicalDigestInput,
            String finalDigest) { }

    /** Validates already-decoded ordinary Java JSON values without coercion. */
    public static ValidatedFinal validate(
            Object binding, Object dispatchFacts, Object authority, String content,
            Object interactionOutcome, Object inspectionInputReceipt) {
        Binding normalizedBinding = binding(binding);
        DispatchFacts normalizedFacts = facts(dispatchFacts);
        InspectionAuthority normalizedAuthority = authority(authority, normalizedFacts);
        String normalizedContent = content(content);
        InteractionOutcome normalizedOutcome = outcome(interactionOutcome, normalizedFacts);
        InspectionInputReceipt normalizedReceipt = receipt(inspectionInputReceipt, normalizedAuthority);
        if (!normalizedContent.equals(normalizedOutcome.text())) fail(Reason.CONTENT_MISMATCH);
        String canonical = canonicalDigestInput(normalizedBinding, normalizedContent, normalizedOutcome, normalizedReceipt);
        return new ValidatedFinal(normalizedBinding, normalizedFacts, normalizedContent, normalizedOutcome,
                normalizedReceipt, canonical, "sha256:" + sha256(canonical));
    }

    public static ValidatedFinal validateJson(
            Object binding, Object dispatchFacts, Object authority, String content,
            String rawInteractionOutcomeJson, String rawInspectionInputReceiptJson) {
        return validate(binding, dispatchFacts, authority, content, parseJson(rawInteractionOutcomeJson),
                parseJson(rawInspectionInputReceiptJson));
    }

    private static Object parseJson(String raw) {
        if (raw == null) fail(Reason.INVALID_JSON);
        try {
            JsonNode root = STRICT_JSON.readTree(raw);
            if (root == null) fail(Reason.INVALID_JSON);
            return fromJson(root);
        } catch (ValidationException error) {
            throw error;
        } catch (Exception error) {
            fail(Reason.INVALID_JSON);
            return null;
        }
    }

    private static Object fromJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> value = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String name = property.getKey();
                if (!validScalar(name)) fail(Reason.INVALID_JSON);
                value.put(name, fromJson(property.getValue()));
            }
            return value;
        }
        if (node.isArray()) {
            List<Object> value = new ArrayList<>();
            for (JsonNode item : node) value.add(fromJson(item));
            return value;
        }
        if (node.isTextual()) {
            String value = node.textValue();
            if (!validScalar(value)) fail(Reason.INVALID_JSON);
            return value;
        }
        if (node.isIntegralNumber() || node.isFloatingPointNumber()) {
            try { return new BigDecimal(node.toString()); }
            catch (NumberFormatException error) { fail(Reason.INVALID_JSON); }
        }
        if (node.isBoolean()) return node.booleanValue();
        fail(Reason.INVALID_JSON);
        return null;
    }

    private static Binding binding(Object value) {
        Binding candidate;
        if (value instanceof Binding supplied) {
            candidate = supplied;
        } else {
            Map<String, Object> map = exactObject(value, BINDING_KEYS, Reason.INVALID_BINDING);
            candidate = new Binding(
                    string(map.get("tenantId"), Reason.INVALID_BINDING),
                    string(map.get("ownerJiacn"), Reason.INVALID_BINDING),
                    string(map.get("clientId"), Reason.INVALID_BINDING),
                    string(map.get("conversationId"), Reason.INVALID_BINDING),
                    string(map.get("conversationGeneration"), Reason.INVALID_BINDING),
                    string(map.get("requestId"), Reason.INVALID_BINDING),
                    string(map.get("requestRevision"), Reason.INVALID_BINDING),
                    string(map.get("turnId"), Reason.INVALID_BINDING),
                    string(map.get("dispatchId"), Reason.INVALID_BINDING),
                    string(map.get("snapshotId"), Reason.INVALID_BINDING),
                    string(map.get("contextDigest"), Reason.INVALID_BINDING),
                    string(map.get("targetAgentId"), Reason.INVALID_BINDING),
                    string(map.get("route"), Reason.INVALID_BINDING),
                    string(map.get("taskId"), Reason.INVALID_BINDING));
        }
        if (!"0".equals(candidate.tenantId()) || !"INSPECT".equals(candidate.route())) {
            fail(Reason.INVALID_BINDING);
        }
        bindingIdentifier(candidate.ownerJiacn(), 50);
        bindingIdentifier(candidate.clientId(), 50);
        positiveDecimal(candidate.conversationId());
        positiveDecimal(candidate.conversationGeneration());
        bindingIdentifier(candidate.requestId(), 100);
        positiveDecimal(candidate.requestRevision());
        bindingIdentifier(candidate.turnId(), 100);
        bindingIdentifier(candidate.dispatchId(), 100);
        bindingIdentifier(candidate.snapshotId(), 64);
        if (candidate.contextDigest() == null
                || !candidate.contextDigest().matches("sha256:[0-9a-f]{64}")) {
            fail(Reason.INVALID_BINDING);
        }
        bindingIdentifier(candidate.targetAgentId(), 100);
        bindingIdentifier(candidate.taskId(), 100);
        return candidate;
    }

    private static DispatchFacts facts(Object value) {
        Map<String, Object> map = exactObject(value, FACT_KEYS, Reason.INVALID_FACTS);
        if (!semanticOne(map.get("schemaVersion"))) fail(Reason.INVALID_FACTS);
        String referenceMode = string(map.get("referenceMode"), Reason.INVALID_FACTS);
        if (!Set.of("NONE", "AVAILABLE").contains(referenceMode)) fail(Reason.INVALID_FACTS);
        List<Object> rawOperations = list(map.get("supportedOperations"), Reason.INVALID_FACTS);
        List<String> operations = new ArrayList<>();
        Set<String> uniqueOperations = new HashSet<>();
        for (Object item : rawOperations) {
            String operation = string(item, Reason.INVALID_FACTS);
            if (!OPERATIONS.contains(operation) || !uniqueOperations.add(operation)) {
                fail(Reason.INVALID_FACTS);
            }
            operations.add(operation);
        }
        List<Object> rawSources = list(map.get("availableSources"), Reason.INVALID_FACTS);
        if (rawSources.size() > MAX_SOURCES) fail(Reason.INVALID_FACTS);
        List<Source> sources = new ArrayList<>();
        Set<String> sourceIds = new HashSet<>();
        for (Object item : rawSources) {
            Map<String, Object> source = exactObject(item, SOURCE_KEYS, Reason.INVALID_FACTS);
            String sourceRefId = string(source.get("sourceRefId"), Reason.INVALID_FACTS);
            String kind = string(source.get("kind"), Reason.INVALID_FACTS);
            String mediaType = string(source.get("mediaType"), Reason.INVALID_FACTS);
            if (!boundedSourceIdentifier(sourceRefId) || !SOURCE_KINDS.contains(kind)
                    || !MEDIA_TYPES.contains(mediaType) || !sourceIds.add(sourceRefId)) {
                fail(Reason.INVALID_FACTS);
            }
            sources.add(new Source(sourceRefId, kind, mediaType));
        }
        if ("NONE".equals(referenceMode) && !sources.isEmpty()) fail(Reason.INVALID_FACTS);
        return new DispatchFacts(1, referenceMode, operations, sources);
    }

    private static InspectionAuthority authority(Object value, DispatchFacts facts) {
        if (!(value instanceof Map<?, ?>)) fail(Reason.INVALID_RECEIPT);
        Map<?, ?> raw = (Map<?, ?>) value;
        Object authorization = raw.get("authorizationId"), manifestDigest = raw.get("manifestDigest"), sourcesValue = raw.get("sources");
        String authorizationId = string(authorization, Reason.INVALID_RECEIPT);
        String digest = string(manifestDigest, Reason.INVALID_RECEIPT);
        bindingIdentifier(authorizationId, 100);
        if (!digest.matches("sha256:[0-9a-f]{64}")) fail(Reason.INVALID_RECEIPT);
        List<Object> sources = list(sourcesValue, Reason.INVALID_RECEIPT);
        List<ManifestSource> normalized = new ArrayList<>();
        String prior = null;
        for (Object item : sources) {
            if (!(item instanceof Map<?, ?>)) fail(Reason.INVALID_RECEIPT);
            Map<?, ?> source = (Map<?, ?>) item;
            String id = string(source.get("sourceRefId"), Reason.INVALID_RECEIPT);
            String sha = string(source.get("sha256"), Reason.INVALID_RECEIPT);
            String bytes = string(source.get("byteLength"), Reason.INVALID_RECEIPT);
            String mime = string(source.get("mimeType"), Reason.INVALID_RECEIPT);
            String carrier = string(source.get("carrier"), Reason.INVALID_RECEIPT);
            String carrierDigest = string(source.get("carrierContractDigest"), Reason.INVALID_RECEIPT);
            if (!boundedSourceIdentifier(id) || prior != null && prior.compareTo(id) >= 0
                    || !sha.matches("[0-9a-f]{64}") || !bytes.matches("0|[1-9][0-9]*")
                    || !mime.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                    || !Set.of("DIRECT_TEXT","LOCAL_IMAGE","LOCAL_AUDIO","PARSED_TEXT").contains(carrier)
                    || !carrierDigest.matches("sha256:[0-9a-f]{64}")) fail(Reason.INVALID_RECEIPT);
            prior = id; normalized.add(new ManifestSource(id, sha, bytes, mime, carrier, carrierDigest));
        }
        if (normalized.size() != facts.availableSources().size()) fail(Reason.INVALID_RECEIPT);
        for (int i=0;i<normalized.size();i++) if(!normalized.get(i).sourceRefId().equals(facts.availableSources().get(i).sourceRefId())) fail(Reason.INVALID_RECEIPT);
        return new InspectionAuthority(authorizationId, digest, normalized);
    }

    private static InspectionInputReceipt receipt(Object value, InspectionAuthority authority) {
        Map<String,Object> map=exactObject(value, RECEIPT_KEYS, Reason.INVALID_RECEIPT);
        if(!semanticOne(map.get("schemaVersion")))fail(Reason.INVALID_RECEIPT);
        String authorization=string(map.get("authorizationId"),Reason.INVALID_RECEIPT);
        String manifest=string(map.get("manifestDigest"),Reason.INVALID_RECEIPT);
        String input=string(map.get("inputDigest"),Reason.INVALID_RECEIPT);
        String thread=string(map.get("engineThreadId"),Reason.INVALID_RECEIPT);
        String turn=string(map.get("engineTurnId"),Reason.INVALID_RECEIPT);
        bindingIdentifier(thread,100);bindingIdentifier(turn,100);
        if(!authorization.equals(authority.authorizationId())||!manifest.equals(authority.manifestDigest())
                ||!input.matches("sha256:[0-9a-f]{64}"))fail(Reason.INVALID_RECEIPT);
        List<Object> raw=list(map.get("sources"),Reason.INVALID_RECEIPT);
        if(raw.size()!=authority.sources().size())fail(Reason.INVALID_RECEIPT);
        List<ReceiptSource> sources=new ArrayList<>();String prior=null;
        for(int i=0;i<raw.size();i++){Map<String,Object> item=exactObject(raw.get(i),RECEIPT_SOURCE_KEYS,Reason.INVALID_RECEIPT);
            String id=string(item.get("sourceRefId"),Reason.INVALID_RECEIPT),sha=string(item.get("sha256"),Reason.INVALID_RECEIPT);
            String bytes=string(item.get("byteLength"),Reason.INVALID_RECEIPT),carrier=string(item.get("carrier"),Reason.INVALID_RECEIPT);
            String contribution=string(item.get("contributionDigest"),Reason.INVALID_RECEIPT);ManifestSource expected=authority.sources().get(i);
            if(prior!=null&&prior.compareTo(id)>=0||!id.equals(expected.sourceRefId())||!sha.equals(expected.sha256())
                    ||!bytes.equals(expected.byteLength())||!carrier.equals(expected.carrier())
                    ||!contribution.matches("sha256:[0-9a-f]{64}"))fail(Reason.INVALID_RECEIPT);
            prior=id;sources.add(new ReceiptSource(id,sha,bytes,carrier,contribution));}
        Map<String,Object> preimage=new LinkedHashMap<>();preimage.put("schemaVersion",1);preimage.put("authorizationId",authorization);
        preimage.put("manifestDigest",manifest);preimage.put("sources",sources.stream().map(TypedInspectionFinalValidator::receiptSourceMap).toList());
        if(!input.equals("sha256:"+sha256(CanonicalContextJson.write(preimage))))fail(Reason.INVALID_RECEIPT);
        return new InspectionInputReceipt(1,authorization,manifest,input,thread,turn,sources);
    }

    private static String content(String value) {
        if (value == null || value.length() > MAX_CONTENT_UTF16 || !validScalar(value)
                || value.isBlank()) {
            fail(Reason.INVALID_CONTENT);
        }
        return value;
    }

    private static InteractionOutcome outcome(Object value, DispatchFacts dispatchFacts) {
        Map<String, Object> map = exactObject(value, OUTCOME_KEYS, Reason.INVALID_OUTCOME);
        if (!semanticInteger(map.get("schemaVersion"), 2)) fail(Reason.INVALID_OUTCOME);
        String kind = string(map.get("kind"), Reason.INVALID_OUTCOME);
        String text = prose(map.get("text"), Reason.INVALID_OUTCOME);
        Object rawClarification = map.get("clarification");
        Object rawProposal = map.get("proposal");
        Clarification clarification = null;
        Proposal proposal = null;
        if ("ANSWER".equals(kind)) {
            if (rawClarification != null || rawProposal != null) fail(Reason.INVALID_OUTCOME);
        } else if ("CLARIFY".equals(kind)) {
            if (rawProposal != null) fail(Reason.INVALID_OUTCOME);
            Map<String, Object> object = exactObject(
                    rawClarification, CLARIFICATION_KEYS, Reason.INVALID_OUTCOME);
            String question = prose(object.get("question"), Reason.INVALID_OUTCOME);
            List<Object> rawRequired = list(object.get("requiredFacts"), Reason.INVALID_OUTCOME);
            if (rawRequired.isEmpty()) fail(Reason.INVALID_OUTCOME);
            List<String> required = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (Object item : rawRequired) {
                String fact = string(item, Reason.INVALID_OUTCOME);
                if (!REQUIRED_FACTS.contains(fact) || !unique.add(fact)) fail(Reason.INVALID_OUTCOME);
                required.add(fact);
            }
            clarification = new Clarification(question, required);
        } else if ("EXECUTION_PROPOSAL".equals(kind)) {
            if (rawClarification != null) fail(Reason.INVALID_OUTCOME);
            Map<String, Object> object = exactObject(rawProposal, PROPOSAL_KEYS, Reason.INVALID_OUTCOME);
            String operation = string(object.get("operation"), Reason.INVALID_OUTCOME);
            String instruction = instruction(object.get("instruction"));
            List<Object> rawIds = list(object.get("sourceRefIds"), Reason.INVALID_OUTCOME);
            List<String> sourceRefIds = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (Object item : rawIds) {
                String sourceRefId = string(item, Reason.INVALID_OUTCOME);
                if (!boundedSourceIdentifier(sourceRefId) || !unique.add(sourceRefId)) {
                    fail(Reason.INVALID_OUTCOME);
                }
                sourceRefIds.add(sourceRefId);
            }
            validateProposal(operation, sourceRefIds, dispatchFacts);
            proposal = new Proposal(operation, instruction, sourceRefIds);
        } else {
            fail(Reason.INVALID_OUTCOME);
        }
        return new InteractionOutcome(2, kind, text, clarification, proposal);
    }

    private static void validateProposal(
            String operation, List<String> sourceRefIds, DispatchFacts dispatchFacts) {
        if (!OPERATIONS.contains(operation) || !dispatchFacts.supportedOperations().contains(operation)) {
            fail(Reason.INVALID_OUTCOME);
        }
        Map<String, Source> catalog = new LinkedHashMap<>();
        for (Source source : dispatchFacts.availableSources()) catalog.put(source.sourceRefId(), source);
        for (String sourceRefId : sourceRefIds) {
            Source source = catalog.get(sourceRefId);
            if (source == null || !"image".equals(source.mediaType())) fail(Reason.INVALID_OUTCOME);
        }
        if ("EDIT_IMAGE".equals(operation)) {
            if (sourceRefIds.size() != 1) fail(Reason.INVALID_OUTCOME);
            Source source = catalog.get(sourceRefIds.getFirst());
            if (!"CURRENT_CONVERSATION_ASSET".equals(source.kind())) fail(Reason.INVALID_OUTCOME);
        }
    }

    private static String instruction(Object value) {
        String instruction = string(value, Reason.INVALID_OUTCOME);
        if (!validScalar(instruction) || isEcmaBlank(instruction)
                || instruction.codePointCount(0, instruction.length()) > MAX_INSTRUCTION_CODE_POINTS
                || instruction.codePoints().anyMatch(Character::isISOControl)) {
            fail(Reason.INVALID_OUTCOME);
        }
        return instruction;
    }

    private static String prose(Object value, Reason reason) {
        String prose = string(value, reason);
        if (!validScalar(prose) || isEcmaBlank(prose)) fail(reason);
        return prose;
    }

    private static boolean boundedSourceIdentifier(String value) {
        return value != null && value.length() <= MAX_SOURCE_ID_UTF16 && validScalar(value)
                && !isEcmaBlank(value) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static void bindingIdentifier(String value, int maxUtf16) {
        if (value == null || value.length() > maxUtf16 || !validScalar(value) || value.isBlank()
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            fail(Reason.INVALID_BINDING);
        }
    }

    private static void positiveDecimal(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) fail(Reason.INVALID_BINDING);
        try {
            if (new BigInteger(value).compareTo(MAX_SIGNED_BIGINT) > 0) fail(Reason.INVALID_BINDING);
        } catch (NumberFormatException error) {
            fail(Reason.INVALID_BINDING);
        }
    }

    private static boolean semanticOne(Object value) {
        if (!(value instanceof Number number)) return false;
        try {
            if (number instanceof Double decimal && !Double.isFinite(decimal)) return false;
            if (number instanceof Float decimal && !Float.isFinite(decimal)) return false;
            return new BigDecimal(number.toString()).compareTo(BigDecimal.ONE) == 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static boolean semanticInteger(Object value, int expected) {
        if (!(value instanceof Number number)) return false;
        try { return new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(expected)) == 0; }
        catch (RuntimeException invalid) { return false; }
    }

    private static Map<String, Object> exactObject(Object value, Set<String> keys, Reason reason) {
        if (!(value instanceof Map<?, ?>)) fail(reason);
        Map<?, ?> raw = (Map<?, ?>) value;
        if (raw.size() != keys.size()) fail(reason);
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            Object rawKey = entry.getKey();
            if (!(rawKey instanceof String)) fail(reason);
            String key = (String) rawKey;
            if (!keys.contains(key) || result.containsKey(key)) fail(reason);
            result.put(key, entry.getValue());
        }
        if (!result.keySet().equals(keys)) fail(reason);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value, Reason reason) {
        if (!(value instanceof List<?>)) fail(reason);
        return (List<Object>) value;
    }

    private static String string(Object value, Reason reason) {
        if (!(value instanceof String)) fail(reason);
        String text = (String) value;
        if (!validScalar(text)) fail(reason);
        return text;
    }

    private static boolean validScalar(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(unit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEcmaBlank(String value) {
        if (value.isEmpty()) return true;
        return value.codePoints().allMatch(TypedInspectionFinalValidator::isEcmaTrimCodePoint);
    }

    private static boolean isEcmaTrimCodePoint(int point) {
        return point == 0x0009 || point == 0x000B || point == 0x000C || point == 0x0020
                || point == 0x00A0 || point == 0xFEFF || point == 0x000A || point == 0x000D
                || point == 0x2028 || point == 0x2029 || Character.getType(point) == Character.SPACE_SEPARATOR;
    }

    private static String canonicalDigestInput(
            Binding binding, String content, InteractionOutcome outcome, InspectionInputReceipt receipt) {
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("domain", "juyiting.typed-inspection-final");
        digestInput.put("contract", "juyiting-typed-inspection-v1");
        digestInput.put("digestSchemaVersion", 1);
        digestInput.put("outcomeContractVersion", 2);
        digestInput.put("binding", bindingMap(binding));
        digestInput.put("content", content);
        digestInput.put("interactionOutcome", outcomeMap(outcome));
        digestInput.put("inspectionInputReceipt", receiptMap(receipt));
        return CanonicalContextJson.write(digestInput);
    }

    private static Map<String, Object> bindingMap(Binding value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", value.tenantId());
        map.put("ownerJiacn", value.ownerJiacn());
        map.put("clientId", value.clientId());
        map.put("conversationId", value.conversationId());
        map.put("conversationGeneration", value.conversationGeneration());
        map.put("requestId", value.requestId());
        map.put("requestRevision", value.requestRevision());
        map.put("turnId", value.turnId());
        map.put("dispatchId", value.dispatchId());
        map.put("snapshotId", value.snapshotId());
        map.put("contextDigest", value.contextDigest());
        map.put("targetAgentId", value.targetAgentId());
        map.put("route", value.route());
        map.put("taskId", value.taskId());
        return map;
    }

    private static Map<String, Object> outcomeMap(InteractionOutcome value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", 2);
        map.put("kind", value.kind());
        map.put("text", value.text());
        map.put("clarification", value.clarification() == null ? null
                : clarificationMap(value.clarification()));
        map.put("proposal", value.proposal() == null ? null : proposalMap(value.proposal()));
        return map;
    }

    private static Map<String,Object> receiptMap(InspectionInputReceipt value) {
        Map<String,Object> map=new LinkedHashMap<>();map.put("schemaVersion",1);map.put("authorizationId",value.authorizationId());
        map.put("manifestDigest",value.manifestDigest());map.put("inputDigest",value.inputDigest());
        map.put("engineThreadId",value.engineThreadId());map.put("engineTurnId",value.engineTurnId());
        map.put("sources",value.sources().stream().map(TypedInspectionFinalValidator::receiptSourceMap).toList());return map;
    }
    private static Map<String,Object> receiptSourceMap(ReceiptSource value){Map<String,Object> map=new LinkedHashMap<>();
        map.put("sourceRefId",value.sourceRefId());map.put("sha256",value.sha256());map.put("byteLength",value.byteLength());
        map.put("carrier",value.carrier());map.put("contributionDigest",value.contributionDigest());return map;}

    private static Map<String, Object> clarificationMap(Clarification value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("question", value.question());
        map.put("requiredFacts", value.requiredFacts());
        return map;
    }

    private static Map<String, Object> proposalMap(Proposal value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("operation", value.operation());
        map.put("instruction", value.instruction());
        map.put("sourceRefIds", value.sourceRefIds());
        return map;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void fail(Reason reason) { throw new ValidationException(reason); }
}
