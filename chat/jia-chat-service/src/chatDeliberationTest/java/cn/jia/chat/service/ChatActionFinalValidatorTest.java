package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatActionFinalValidatorTest {
    @Test void chatAnswerClarificationAndGenericActionsKeepTheirOwnUnion() throws Exception {
        var fixture = fixture("/unified-action-outcome-v3.json");
        for (Object item : list(fixture.get("outcomes"))) {
            var outcome = map(item);
            var value = chat(binding("CHAT"), fixture.get("facts"), outcome);
            assertEquals(outcome, ChatActionFinalValidator.outcomeMap(value.interactionOutcome()));
            assertEquals(fixture.get("facts"), ChatActionFinalValidator.factsMap(value.dispatchFacts()));
            assertNull(value.inspectionInputReceipt());
            assertTrue(value.finalDigest().matches("sha256:[0-9a-f]{64}"));
            if ("ACTION_REQUEST".equals(outcome.get("kind")))
                assertEquals(ChatActionFinalValidator.actionEventId(value), ChatActionFinalValidator.actionEventId(
                        chat(binding("CHAT"), fixture.get("facts"), outcome)));
            else assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.actionEventId(value));
        }
    }

    @Test void canonicalFinalAndActionIdentityMatchIndependentNodeVector() throws Exception {
        var fixture = fixture("/contracts/action-final-digest-v3.json");
        var result = chat(map(fixture.get("binding")), fixture.get("facts"), map(fixture.get("outcome")));
        assertEquals(fixture.get("canonicalDigestInput"), result.canonicalDigestInput());
        assertEquals(fixture.get("finalDigest"), result.finalDigest());
        assertEquals(fixture.get("actionEventId"), ChatActionFinalValidator.actionEventId(result));
    }

    @Test void everyScopeAndTurnIdentityFieldChangesDigestAndActionIdentity() throws Exception {
        var fixture = fixture("/contracts/action-final-digest-v3.json");
        var binding = map(fixture.get("binding"));
        var outcome = map(fixture.get("outcome"));
        var original = chat(binding, fixture.get("facts"), outcome);
        for (String key : List.of("ownerJiacn", "clientId", "conversationId", "conversationGeneration", "requestId",
                "requestRevision", "turnId", "dispatchId", "snapshotId", "contextDigest", "targetAgentId", "taskId")) {
            var changed = new LinkedHashMap<>(binding);
            changed.put(key, switch (key) {
                case "conversationId", "conversationGeneration", "requestRevision" -> "2";
                case "contextDigest" -> digest('b');
                default -> binding.get(key) + "-other";
            });
            var actual = chat(changed, fixture.get("facts"), outcome);
            assertNotEquals(original.finalDigest(), actual.finalDigest(), key);
            assertNotEquals(ChatActionFinalValidator.actionEventId(original), ChatActionFinalValidator.actionEventId(actual), key);
        }
        assertThrows(UnsupportedOperationException.class, () -> original.binding().put("ownerJiacn", "other"));
    }

    @Test void malformedAndForeignBindingIsNotAccepted() throws Exception {
        var fixture = fixture("/contracts/action-final-digest-v3.json");
        var binding = map(fixture.get("binding"));
        for (var field : Map.of("tenantId", "other", "route", "EXECUTE", "conversationId", "01",
                "conversationGeneration", "0", "requestRevision", "9223372036854775808", "contextDigest", "abc",
                "ownerJiacn", "bad\nowner", "snapshotId", "x".repeat(65), "targetAgentId", "\ud800").entrySet()) {
            var bad = new LinkedHashMap<>(binding); bad.put(field.getKey(), field.getValue());
            assertThrows(IllegalArgumentException.class, () -> chat(bad, fixture.get("facts"), map(fixture.get("outcome"))), field.getKey());
        }
        var bad = new LinkedHashMap<>(binding); bad.put("grant", true);
        assertThrows(IllegalArgumentException.class, () -> chat(bad, fixture.get("facts"), map(fixture.get("outcome"))));
    }

    @Test void finalVersionTextAndDuplicateKeysMustAgreeExactly() throws Exception {
        var fixture = fixture("/unified-action-outcome-v3.json");
        var outcome = map(list(fixture.get("outcomes")).getFirst());
        String raw = JsonUtil.toJson(outcome), text = (String) outcome.get("text");
        for (Integer version : new Integer[]{null, 1, 2, 4})
            assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                    fixture.get("facts"), null, text, version, raw, null));
        assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                fixture.get("facts"), null, text + " ", 3, raw, null));
        assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                fixture.get("facts"), null, text, 3, raw.replaceFirst("\\{", "{\"schemaVersion\":3,"), null));
        assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                fixture.get("facts"), null, text, 3, raw + " true", null));
    }

    @Test void chatNeverAcceptsAnInspectionReceiptOrAuthority() throws Exception {
        var fixture = fixture("/unified-action-outcome-v3.json");
        var outcome = map(list(fixture.get("outcomes")).getFirst());
        assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                fixture.get("facts"), null, (String)outcome.get("text"), 3, JsonUtil.toJson(outcome), "{}"));
        assertThrows(IllegalArgumentException.class, () -> ChatActionFinalValidator.validateJson(binding("CHAT"),
                fixture.get("facts"), Map.of(), (String)outcome.get("text"), 3, JsonUtil.toJson(outcome), null));
    }

    @Test void inspectionBindsActualMixedMediaInputProofNotAPlaceholderV2Union() throws Exception {
        var input = inspection();
        var result = inspect(input, map(input.get("receipt")));
        assertEquals(input.get("inputDigest"), result.inspectionInputReceipt().inputDigest());
        assertEquals(4, result.inspectionInputReceipt().sources().size());
        assertEquals(3, result.interactionOutcome().schemaVersion());
        assertFalse(result.canonicalDigestInput().contains("proposal"));
        var changed = map(input.get("receipt")); changed.put("engineTurnId", "other-native-turn");
        assertNotEquals(result.finalDigest(), inspect(input, changed).finalDigest());
    }

    @Test void inspectionRejectsMissingForgedExtraAndChangedSourceReceipt() throws Exception {
        var input = inspection();
        assertThrows(IllegalArgumentException.class, () -> inspect(input, null));
        for (String key : List.of("authorizationId", "manifestDigest", "inputDigest")) {
            var changed = new LinkedHashMap<>(map(input.get("receipt"))); changed.put(key, "wrong");
            assertThrows(IllegalArgumentException.class, () -> inspect(input, changed), key);
        }
        for (var field : Map.of("sourceRefId", "foreign", "sha256", "e".repeat(64), "byteLength", "999999",
                "carrier", "UNTRUSTED", "contributionDigest", digest('f')).entrySet()) {
            var changed = inspection();
            map(list(map(changed.get("receipt")).get("sources")).getFirst()).put(field.getKey(), field.getValue());
            assertThrows(IllegalArgumentException.class, () -> inspect(changed, map(changed.get("receipt"))), field.getKey());
        }
        var extra = inspection(); map(list(map(extra.get("receipt")).get("sources")).getFirst()).put("path", "/tmp/file");
        assertThrows(IllegalArgumentException.class, () -> inspect(extra, map(extra.get("receipt"))));
        var reordered = inspection(); Collections.reverse(list(map(reordered.get("receipt")).get("sources")));
        assertThrows(IllegalArgumentException.class, () -> inspect(reordered, map(reordered.get("receipt"))));
        var missing = inspection(); list(map(missing.get("receipt")).get("sources")).removeLast();
        assertThrows(IllegalArgumentException.class, () -> inspect(missing, map(missing.get("receipt"))));
    }

    @Test void sourceCatalogueAndAuthorityMustDescribeTheSameInputs() throws Exception {
        var input = inspection();
        list(map(input.get("facts")).get("availableSources")).removeLast();
        assertThrows(IllegalArgumentException.class, () -> inspect(input, map(input.get("receipt"))));
        var another = inspection();
        map(list(map(another.get("facts")).get("availableSources")).getFirst()).put("sourceRefId", "other-source");
        assertThrows(IllegalArgumentException.class, () -> inspect(another, map(another.get("receipt"))));
    }

    @Test void genericReceiptPrimitiveRejectsDuplicateIdsAndMalformedRawJson() throws Exception {
        var input = inspection(); var authority = input.get("authority");
        var refs = list(map(input.get("facts")).get("availableSources")).stream().map(s -> (String)map(s).get("sourceRefId")).toList();
        String raw = JsonUtil.toJson(input.get("receipt"));
        var duplicate = new ArrayList<>(refs); duplicate.set(1, refs.getFirst());
        assertThrows(IllegalArgumentException.class, () -> TypedInspectionFinalValidator.validateInputReceiptJson(authority, duplicate, raw));
        assertThrows(IllegalArgumentException.class, () -> TypedInspectionFinalValidator.validateInputReceiptJson(authority, refs,
                raw.replaceFirst("\\{", "{\"schemaVersion\":1,")));
        assertThrows(IllegalArgumentException.class, () -> TypedInspectionFinalValidator.validateInputReceiptJson(authority, refs, raw + " true"));
    }

    private static ChatActionFinalValidator.ValidatedFinal chat(Map<String,Object> binding, Object facts, Map<String,Object> outcome) {
        return ChatActionFinalValidator.validateJson(binding, facts, null, (String)outcome.get("text"), 3, JsonUtil.toJson(outcome), null);
    }
    private static ChatActionFinalValidator.ValidatedFinal inspect(Map<String,Object> input, Map<String,Object> receipt) {
        var outcome = new LinkedHashMap<String,Object>(); outcome.put("schemaVersion", 3); outcome.put("kind", "ANSWER");
        outcome.put("text", "Inspected."); outcome.put("clarification", null); outcome.put("action", null);
        return ChatActionFinalValidator.validateJson(binding("INSPECT"), input.get("facts"), input.get("authority"),
                "Inspected.", 3, JsonUtil.toJson(outcome), receipt == null ? null : JsonUtil.toJson(receipt));
    }
    private static Map<String,Object> binding(String route) {
        var value = new LinkedHashMap<String,Object>(); value.put("tenantId", "0"); value.put("ownerJiacn", "owner");
        value.put("clientId", "client"); value.put("conversationId", "42"); value.put("conversationGeneration", "1");
        value.put("requestId", "request-1"); value.put("requestRevision", "1"); value.put("turnId", "turn-1");
        value.put("dispatchId", "dispatch-1"); value.put("snapshotId", "snapshot-1"); value.put("contextDigest", digest('a'));
        value.put("targetAgentId", "agent-1"); value.put("route", route); value.put("taskId", "task-1"); return value;
    }
    private static Map<String,Object> inspection() throws Exception {
        var input = fixture("/contracts/typed-inspection-input-digests-v1.json");
        List<Object> sources = new ArrayList<>(), available = new ArrayList<>();
        for (Object vector : list(input.get("vectors"))) {
            var source = map(map(vector).get("contributionPreimage")); var authoritySource = new LinkedHashMap<String,Object>();
            for (String key : List.of("sourceRefId", "sha256", "byteLength", "mimeType", "carrier", "carrierContractDigest"))
                authoritySource.put(key, source.get(key));
            sources.add(authoritySource);
            available.add(new LinkedHashMap<>(Map.of("sourceRefId", source.get("sourceRefId"), "kind", "TASK_WORKSPACE_FILE",
                    "mediaType", switch ((String)source.get("carrier")) {
                        case "DIRECT_TEXT" -> "text"; case "LOCAL_IMAGE" -> "image"; case "LOCAL_AUDIO" -> "audio"; default -> "file";
                    })));
        }
        input.put("facts", new LinkedHashMap<>(Map.of("schemaVersion", 3, "availableSources", available,
                "availableActions", List.of(), "inspectedSourceRefIds", List.of())));
        input.put("authority", Map.of("authorizationId", input.get("authorizationId"), "manifestDigest", input.get("manifestDigest"), "sources", sources));
        input.put("receipt", new LinkedHashMap<>(Map.of("schemaVersion", 1, "authorizationId", input.get("authorizationId"),
                "manifestDigest", input.get("manifestDigest"), "inputDigest", input.get("inputDigest"), "engineThreadId", "native-thread",
                "engineTurnId", "native-turn", "sources", map(input.get("inputPreimage")).get("sources"))));
        return input;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> fixture(String name) throws Exception {
        try (var input = ChatActionFinalValidatorTest.class.getResourceAsStream(name)) {
            assertNotNull(input, name); return JsonUtil.getMapper().readValue(input, Map.class);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>)value; }
    private static String digest(char character) { return "sha256:" + String.valueOf(character).repeat(64); }
}
