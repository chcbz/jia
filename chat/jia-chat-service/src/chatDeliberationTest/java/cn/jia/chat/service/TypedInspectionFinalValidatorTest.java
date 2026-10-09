package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.jia.chat.service.TypedInspectionFinalValidator.Reason.CONTENT_MISMATCH;
import static cn.jia.chat.service.TypedInspectionFinalValidator.Reason.INVALID_RECEIPT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypedInspectionFinalValidatorTest {
    private static final String FIXTURE = "/contracts/typed-inspection-input-digests-v1.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fixedCarrierFixtureInputDigestMatchesJavaCanonicalValidation() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> receipt = receipt(fixture);
        var result = TypedInspectionFinalValidator.validate(binding(), facts(fixture),
                authority(fixture), "Inspected.", answer("Inspected."), receipt);
        assertEquals(fixture.get("inputDigest"), result.inspectionInputReceipt().inputDigest());
        assertEquals(4, result.inspectionInputReceipt().sources().size());
        assertEquals("source_0000000000000000000000000000000000000001",
                result.inspectionInputReceipt().sources().getFirst().sourceRefId());
    }

    @Test
    void reorderedMissingExtraOrChangedReceiptSourceRejects() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> reordered = receipt(fixture);
        List<Object> order = list(reordered.get("sources"));
        Object first = order.removeFirst();
        order.add(first);
        rejects(INVALID_RECEIPT, () -> validate(fixture, reordered));

        Map<String, Object> missing = receipt(fixture);
        list(missing.get("sources")).removeLast();
        rejects(INVALID_RECEIPT, () -> validate(fixture, missing));

        Map<String, Object> extra = receipt(fixture);
        map(list(extra.get("sources")).getFirst()).put("path", "/tmp/secret");
        rejects(INVALID_RECEIPT, () -> validate(fixture, extra));

        Map<String, Object> changed = receipt(fixture);
        map(list(changed.get("sources")).getFirst()).put("contributionDigest", digest('f'));
        rejects(INVALID_RECEIPT, () -> validate(fixture, changed));
    }

    @Test
    void receiptAndUnionAreBothFinalDigestInputsAndTextMustMatch() throws Exception {
        Map<String, Object> fixture = fixture();
        var first = validate(fixture, receipt(fixture));
        Map<String, Object> changed = receipt(fixture);
        changed.put("engineTurnId", "turn-engine-2");
        assertNotEquals(first.finalDigest(), validate(fixture, changed).finalDigest());
        rejects(CONTENT_MISMATCH, () -> TypedInspectionFinalValidator.validate(binding(),
                facts(fixture), authority(fixture), "different", answer("Inspected."), receipt(fixture)));
    }

    @Test
    void versionOneOutcomeAndReceiptAliasReject() throws Exception {
        Map<String, Object> fixture = fixture();
        Map<String, Object> v1 = answer("Inspected.");
        v1.put("schemaVersion", 1);
        assertThrows(TypedInspectionFinalValidator.ValidationException.class,
                () -> TypedInspectionFinalValidator.validate(binding(), facts(fixture), authority(fixture),
                        "Inspected.", v1, receipt(fixture)));
        Map<String, Object> alias = receipt(fixture);
        Map<String, Object> source = map(list(alias.get("sources")).getFirst());
        source.put("sourceRef", source.remove("sourceRefId"));
        rejects(INVALID_RECEIPT, () -> validate(fixture, alias));
    }

    @Test
    void full32MaterialFinalReceiptValidatesWithoutTruncation() throws Exception {
        var fixture = fixture(); var facts = facts(fixture); var authority = authority(fixture); var receipt = receipt(fixture);
        var factSource = map(list(facts.get("availableSources")).getFirst());
        var authoritySource = map(list(authority.get("sources")).getFirst());
        var receiptSource = map(list(receipt.get("sources")).getFirst());
        List<Object> fs = new ArrayList<>(), as = new ArrayList<>(), rs = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String id = "source_%040d".formatted(i);
            var f = new LinkedHashMap<>(factSource); f.put("sourceRefId", id); fs.add(f);
            var a = new LinkedHashMap<>(authoritySource); a.put("sourceRefId", id); as.add(a);
            var r = new LinkedHashMap<>(receiptSource); r.put("sourceRefId", id); rs.add(r);
        }
        facts.put("availableSources", fs); authority.put("sources", as); receipt.put("sources", rs);
        receipt.put("inputDigest", ChatDeliberationService.digest(mapOf("schemaVersion", 1, "authorizationId", receipt.get("authorizationId"),
                "manifestDigest", receipt.get("manifestDigest"), "sources", rs)));
        var result = TypedInspectionFinalValidator.validate(binding(), facts, authority, "Inspected.", answer("Inspected."), receipt);
        assertEquals(32, result.inspectionInputReceipt().sources().size());
        fs.add(mapOf("sourceRefId", "source_%040d".formatted(32), "kind", "TASK_WORKSPACE_FILE", "mediaType", "text"));
        assertThrows(TypedInspectionFinalValidator.ValidationException.class,
                () -> TypedInspectionFinalValidator.validate(binding(), facts, authority, "Inspected.", answer("Inspected."), receipt));
    }

    private static TypedInspectionFinalValidator.ValidatedFinal validate(
            Map<String, Object> fixture, Map<String, Object> receipt) {
        return TypedInspectionFinalValidator.validate(binding(), facts(fixture), authority(fixture),
                "Inspected.", answer("Inspected."), receipt);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture() throws Exception {
        try (InputStream input = TypedInspectionFinalValidatorTest.class.getResourceAsStream(FIXTURE)) {
            if (input == null) throw new IllegalStateException("fixture missing");
            return JSON.readValue(input, Map.class);
        }
    }

    private static Map<String, Object> binding() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tenantId", "0");
        value.put("ownerJiacn", "owner");
        value.put("clientId", "client");
        value.put("conversationId", "42");
        value.put("conversationGeneration", "1");
        value.put("requestId", "request-1");
        value.put("requestRevision", "1");
        value.put("turnId", "turn-1");
        value.put("dispatchId", "dispatch-1");
        value.put("snapshotId", "snapshot-1");
        value.put("contextDigest", digest('a'));
        value.put("targetAgentId", "agent-1");
        value.put("route", "INSPECT");
        value.put("taskId", "task-1");
        return value;
    }

    private static Map<String, Object> facts(Map<String, Object> fixture) {
        List<Object> sources = new ArrayList<>();
        for (Object item : list(fixture.get("vectors"))) {
            Map<String, Object> preimage = map(map(item).get("contributionPreimage"));
            String carrier = (String) preimage.get("carrier");
            sources.add(Map.of("sourceRefId", preimage.get("sourceRefId"),
                    "kind", "TASK_WORKSPACE_FILE",
                    "mediaType", switch (carrier) {
                        case "DIRECT_TEXT" -> "text";
                        case "LOCAL_IMAGE" -> "image";
                        case "LOCAL_AUDIO" -> "audio";
                        default -> "file";
                    }));
        }
        return mapOf("schemaVersion", 1, "referenceMode", "AVAILABLE",
                "supportedOperations", List.of("GENERATE_IMAGE", "EDIT_IMAGE"),
                "availableSources", sources);
    }

    private static Map<String, Object> authority(Map<String, Object> fixture) {
        List<Object> sources = new ArrayList<>();
        for (Object item : list(fixture.get("vectors"))) {
            Map<String, Object> preimage = map(map(item).get("contributionPreimage"));
            sources.add(mapOf("sourceRefId", preimage.get("sourceRefId"),
                    "sha256", preimage.get("sha256"), "byteLength", preimage.get("byteLength"),
                    "mimeType", preimage.get("mimeType"), "carrier", preimage.get("carrier"),
                    "carrierContractDigest", preimage.get("carrierContractDigest")));
        }
        return mapOf("authorizationId", fixture.get("authorizationId"),
                "manifestDigest", fixture.get("manifestDigest"), "sources", sources);
    }

    private static Map<String, Object> receipt(Map<String, Object> fixture) {
        List<Object> sources = new ArrayList<>();
        for (Object item : list(map(fixture.get("inputPreimage")).get("sources"))) {
            sources.add(new LinkedHashMap<>(map(item)));
        }
        return mapOf("schemaVersion", 1, "authorizationId", fixture.get("authorizationId"),
                "manifestDigest", fixture.get("manifestDigest"), "inputDigest", fixture.get("inputDigest"),
                "engineThreadId", "thread-engine-1", "engineTurnId", "turn-engine-1", "sources", sources);
    }

    private static Map<String, Object> answer(String text) {
        return mapOf("schemaVersion", 2, "kind", "ANSWER", "text", text,
                "clarification", null, "proposal", null);
    }

    private static void rejects(TypedInspectionFinalValidator.Reason reason,
            org.junit.jupiter.api.function.Executable executable) {
        var failure = assertThrows(TypedInspectionFinalValidator.ValidationException.class, executable);
        assertEquals(reason, failure.reason());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            value.put((String) entries[index], entries[index + 1]);
        }
        return value;
    }

    private static String digest(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
