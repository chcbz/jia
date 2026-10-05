package cn.jia.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V3 final identity and byte-input proof. This pure validator never authorizes or executes an action. */
public final class ChatActionFinalValidator {
    private static final Set<String> BINDING_KEYS = Set.of("tenantId", "ownerJiacn", "clientId",
            "conversationId", "conversationGeneration", "requestId", "requestRevision", "turnId",
            "dispatchId", "snapshotId", "contextDigest", "targetAgentId", "route", "taskId");

    private ChatActionFinalValidator() { }

    public record ValidatedFinal(Map<String, Object> binding, ChatActionOutcomeContract.Facts dispatchFacts,
            String content, ChatActionOutcomeContract.Outcome interactionOutcome,
            TypedInspectionFinalValidator.InspectionInputReceipt inspectionInputReceipt,
            String canonicalDigestInput, String finalDigest) {
        public ValidatedFinal { binding = Map.copyOf(binding); }
    }

    /** Facts/authority come only from the immutable server snapshot, never the Agent's sidecar. */
    public static ValidatedFinal validateJson(Map<String, Object> rawBinding, Object rawFacts,
            Object inspectionAuthority, String content, Integer outcomeContractVersion,
            String rawOutcome, String rawReceipt) {
        Map<String, Object> binding = binding(rawBinding);
        if (outcomeContractVersion == null || outcomeContractVersion != ChatActionOutcomeContract.VERSION)
            throw invalid("ACTION_FINAL_VERSION_INVALID");
        var facts = ChatActionOutcomeContract.facts(rawFacts);
        var outcome = ChatActionOutcomeContract.outcomeJson(rawOutcome, facts);
        if (Boolean.TRUE.equals(outcome.deliverable()) && !"CHAT".equals(binding.get("route")))
            throw invalid("ACTION_FINAL_DELIVERABLE_ROUTE_INVALID");
        if (outcome.deliveryRelation()!=null && !"CHAT".equals(binding.get("route")))
            throw invalid("ACTION_DELIVERY_PARENT_INVALID");
        if (content == null || content.length() > 200_000 || !content.equals(outcome.text()))
            throw invalid("ACTION_FINAL_CONTENT_MISMATCH");
        TypedInspectionFinalValidator.InspectionInputReceipt receipt = null;
        if ("INSPECT".equals(binding.get("route"))) {
            receipt = TypedInspectionFinalValidator.validateInputReceiptJson(inspectionAuthority,
                    facts.availableSources().stream().map(ChatActionOutcomeContract.Source::sourceRefId).toList(),
                    rawReceipt);
        } else if (inspectionAuthority != null || rawReceipt != null) {
            throw invalid("ACTION_FINAL_UNEXPECTED_RECEIPT");
        }
        Map<String, Object> preimage = new LinkedHashMap<>();
        preimage.put("domain", "juyiting.action-final");
        preimage.put("digestSchemaVersion", 1);
        preimage.put("outcomeContractVersion", 3);
        preimage.put("binding", binding);
        preimage.put("content", content);
        preimage.put("interactionOutcome", outcomeMap(outcome));
        preimage.put("inspectionInputReceipt", receipt == null ? null : TypedInspectionFinalValidator.receiptMap(receipt));
        String canonical = CanonicalContextJson.write(preimage);
        return new ValidatedFinal(binding, facts, content, outcome, receipt, canonical, "sha256:" + sha256(canonical));
    }

    /** Explicit JSON primitives; CanonicalContextJson intentionally does not serialize arbitrary records. */
    public static Map<String, Object> outcomeMap(ChatActionOutcomeContract.Outcome outcome) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 3);
        value.put("kind", outcome.kind());
        value.put("text", outcome.text());
        // Absence remains absence: legacy immutable finals retain their exact digest preimage.
        if (outcome.deliverable() != null) value.put("deliverable", outcome.deliverable());
        if (outcome.deliveryRelation() != null) value.put("deliveryRelation", relationMap(outcome.deliveryRelation()));
        value.put("clarification", outcome.clarification() == null ? null : Map.of(
                "question", outcome.clarification().question(), "requiredFacts", outcome.clarification().requiredFacts()));
        value.put("action", outcome.action() == null ? null : Map.of("actionId", outcome.action().actionId(),
                "instruction", outcome.action().instruction(), "sourceRefIds", outcome.action().sourceRefIds()));
        return java.util.Collections.unmodifiableMap(value);
    }

    public static Map<String,Object> relationMap(ChatActionOutcomeContract.DeliveryRelation relation) {
        var value=new LinkedHashMap<String,Object>();value.put("mode",relation.mode());
        value.put("parentOutcomeId",relation.parentOutcomeId());value.put("parentFinalDigest",relation.parentFinalDigest());
        if(relation.targetOutcomeId()!=null) {
            value.put("targetOutcomeId",relation.targetOutcomeId());value.put("targetFinalDigest",relation.targetFinalDigest());
        }
        return java.util.Collections.unmodifiableMap(value);
    }

    public static Map<String, Object> factsMap(ChatActionOutcomeContract.Facts facts) {
        return Map.of("schemaVersion", 3, "availableSources", facts.availableSources().stream().map(source -> Map.of(
                        "sourceRefId", source.sourceRefId(), "kind", source.kind(), "mediaType", source.mediaType())).toList(),
                "inspectedSourceRefIds", facts.inspectedSourceRefIds(),
                "availableActions", facts.availableActions().stream().map(action -> Map.of(
                        "actionId", action.actionId(), "kind", action.kind(), "operation", action.operation(),
                        "inputMediaTypes", action.inputMediaTypes(), "minSources", action.minSources(),
                        "maxSources", action.maxSources())).toList());
    }

    /** Separate event identity, stable for retries but distinct for each immutable bound final. */
    public static String actionEventId(ValidatedFinal value) {
        if (!"ACTION_REQUEST".equals(value.interactionOutcome().kind()))
            throw invalid("ACTION_FINAL_HAS_NO_ACTION");
        return "act_" + sha256(CanonicalContextJson.write(Map.of("domain", "juyiting.action-event",
                "binding", value.binding(), "finalDigest", value.finalDigest()))).substring(0, 40);
    }

    private static Map<String, Object> binding(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(BINDING_KEYS) || !"0".equals(value.get("tenantId"))
                || !("CHAT".equals(value.get("route")) || "INSPECT".equals(value.get("route"))))
            throw invalid("ACTION_FINAL_BINDING_INVALID");
        for (String key : List.of("ownerJiacn", "clientId")) identifier(value.get(key), 50);
        for (String key : List.of("requestId", "turnId", "dispatchId", "targetAgentId", "taskId"))
            identifier(value.get(key), 100);
        identifier(value.get("snapshotId"), 64);
        for (String key : List.of("conversationId", "conversationGeneration", "requestRevision")) {
            if (!(value.get(key) instanceof String decimal) || !decimal.matches("[1-9][0-9]*"))
                throw invalid("ACTION_FINAL_BINDING_INVALID");
            try { Long.parseLong(decimal); }
            catch (NumberFormatException failure) { throw invalid("ACTION_FINAL_BINDING_INVALID"); }
        }
        if (!(value.get("contextDigest") instanceof String digest) || !digest.matches("sha256:[0-9a-f]{64}"))
            throw invalid("ACTION_FINAL_BINDING_INVALID");
        return Map.copyOf(value);
    }

    private static void identifier(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max
                || text.codePoints().anyMatch(Character::isISOControl)) throw invalid("ACTION_FINAL_BINDING_INVALID");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw invalid("ACTION_FINAL_BINDING_INVALID");
            } else if (Character.isLowSurrogate(c)) throw invalid("ACTION_FINAL_BINDING_INVALID");
        }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }

    private static IllegalArgumentException invalid(String code) { return new IllegalArgumentException(code); }
}
