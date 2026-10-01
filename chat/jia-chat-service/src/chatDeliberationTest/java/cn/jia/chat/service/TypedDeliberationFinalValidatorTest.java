package cn.jia.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.CONTENT_MISMATCH;
import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.INVALID_BINDING;
import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.INVALID_CONTENT;
import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.INVALID_FACTS;
import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.INVALID_JSON;
import static cn.jia.chat.service.TypedDeliberationFinalValidator.Reason.INVALID_OUTCOME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedDeliberationFinalValidatorTest {
    private static final String FIXTURE = "/contracts/typed-deliberation-api-final-validator-v1.json";
    private static final String FIXTURE_SHA256 =
            "f1327d5fd1041a0149b3b0b788e503504a955cd24cbe9a0847d0d0bc1019af63";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void tv01AnswerExactContentAndNullUnion() {
        String content = "这是普通讨论正文。\n允许换行。";
        var result = validate(content, answer(content));
        assertEquals("ANSWER", result.interactionOutcome().kind());
        assertEquals(content, result.content());
        assertEquals(null, result.interactionOutcome().clarification());
        assertEquals(null, result.interactionOutcome().proposal());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tv02ClarifyNonemptyUniqueFactsAndGoldenFixture() throws Exception {
        byte[] bytes = resource(FIXTURE);
        assertEquals(FIXTURE_SHA256, sha256(bytes));
        Map<String, Object> fixture = JSON.readValue(bytes, Map.class);
        Map<String, Object> golden = (Map<String, Object>) fixture.get("golden");
        var result = TypedDeliberationFinalValidator.validate(
                golden.get("binding"), golden.get("dispatchFacts"),
                (String) golden.get("content"), golden.get("interactionOutcome"));
        assertEquals(golden.get("canonicalDigestInput"), result.canonicalDigestInput());
        assertEquals(golden.get("finalDigest"), result.finalDigest());
        assertEquals(List.of("REQUIREMENT_DETAILS"),
                result.interactionOutcome().clarification().requiredFacts());
        List<Map<String, Object>> expectations =
                (List<Map<String, Object>>) fixture.get("runtimeExpectations");
        assertEquals(30, expectations.size());
        assertTrue(expectations.stream().allMatch(value -> "NOT_RUN".equals(value.get("status"))));
    }

    @Test
    void tv03GenerateImageNoSource() {
        Map<String, Object> facts = facts(List.of("GENERATE_IMAGE"), List.of(), "AVAILABLE");
        var result = TypedDeliberationFinalValidator.validate(binding(), facts, "可以生成。",
                proposal("可以生成。", "GENERATE_IMAGE", "生成一只黄鹂", List.of()));
        assertEquals(List.of(), result.interactionOutcome().proposal().sourceRefIds());
    }

    @Test
    void tv04EditImageExactCurrentSource() {
        var result = TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "可以修改。",
                proposal("可以修改。", "EDIT_IMAGE", "把羽毛改成黄色", List.of("source_1")));
        assertEquals("source_1", result.interactionOutcome().proposal().sourceRefIds().getFirst());
    }

    @Test
    void tv05RawKeyOrderDoesNotChangeDigest() {
        String factsA = "{\"schemaVersion\":1,\"referenceMode\":\"AVAILABLE\","
                + "\"supportedOperations\":[\"GENERATE_IMAGE\"],\"availableSources\":[]}";
        String factsB = "{\"availableSources\":[],\"supportedOperations\":[\"GENERATE_IMAGE\"],"
                + "\"referenceMode\":\"AVAILABLE\",\"schemaVersion\":1}";
        String outcomeA = "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"相同\","
                + "\"clarification\":null,\"proposal\":null}";
        String outcomeB = "{\"proposal\":null,\"text\":\"相同\",\"kind\":\"ANSWER\","
                + "\"schemaVersion\":1,\"clarification\":null}";
        var left = TypedDeliberationFinalValidator.validateJson(binding(), factsA, "相同", outcomeA);
        var right = TypedDeliberationFinalValidator.validateJson(binding(), factsB, "相同", outcomeB);
        assertEquals(left.finalDigest(), right.finalDigest());
        assertEquals(left.canonicalDigestInput(), right.canonicalDigestInput());
    }

    @Test
    void tv06SchemaNumberVariantsNormalizeToIntegerOne() {
        String factsTemplate = "{\"schemaVersion\":%s,\"referenceMode\":\"NONE\","
                + "\"supportedOperations\":[],\"availableSources\":[]}";
        String outcomeTemplate = "{\"schemaVersion\":%s,\"kind\":\"ANSWER\","
                + "\"text\":\"数字语义\",\"clarification\":null,\"proposal\":null}";
        String digest = null;
        for (String number : List.of("1", "1.0", "1e0")) {
            var result = TypedDeliberationFinalValidator.validateJson(binding(),
                    factsTemplate.formatted(number), "数字语义", outcomeTemplate.formatted(number));
            assertEquals(1, result.dispatchFacts().schemaVersion());
            assertEquals(1, result.interactionOutcome().schemaVersion());
            if (digest == null) digest = result.finalDigest(); else assertEquals(digest, result.finalDigest());
        }
        var objectResult = TypedDeliberationFinalValidator.validate(binding(),
                map("schemaVersion", new BigDecimal("1.00"), "referenceMode", "NONE",
                        "supportedOperations", List.of(), "availableSources", List.of()),
                "数字语义", map("schemaVersion", 1.0D, "kind", "ANSWER", "text", "数字语义",
                        "clarification", null, "proposal", null));
        assertEquals(digest, objectResult.finalDigest());
    }

    @Test
    void tv07SameTextChangedKindChangesDigest() {
        String text = "同一句正文";
        var answer = validate(text, answer(text));
        var clarify = validate(text, clarify(text, "需要确认吗？", List.of("REQUIREMENT_DETAILS")));
        assertNotEquals(answer.finalDigest(), clarify.finalDigest());
    }

    @Test
    void tv08SameTextChangedQuestionChangesDigest() {
        String text = "请补充说明。";
        var left = validate(text, clarify(text, "颜色是什么？", List.of("REQUIREMENT_DETAILS")));
        var right = validate(text, clarify(text, "姿态是什么？", List.of("REQUIREMENT_DETAILS")));
        assertNotEquals(left.finalDigest(), right.finalDigest());
    }

    @Test
    void tv09SameTextChangedSourceChangesDigest() {
        List<Object> sources = List.of(
                source("source_1", "CURRENT_CONVERSATION_ASSET", "image"),
                source("source_2", "CURRENT_CONVERSATION_ASSET", "image"));
        Map<String, Object> facts = facts(List.of("GENERATE_IMAGE"), sources, "AVAILABLE");
        String text = "可以按引用生成。";
        var left = TypedDeliberationFinalValidator.validate(binding(), facts, text,
                proposal(text, "GENERATE_IMAGE", "生成鸟", List.of("source_1")));
        var right = TypedDeliberationFinalValidator.validate(binding(), facts, text,
                proposal(text, "GENERATE_IMAGE", "生成鸟", List.of("source_2")));
        assertNotEquals(left.finalDigest(), right.finalDigest());
    }

    @Test
    void tv10ChangedDispatchOrContextChangesDigest() {
        String text = "绑定摘要";
        Map<String, Object> first = binding();
        Map<String, Object> changedDispatch = binding();
        changedDispatch.put("dispatchId", "dispatch_2");
        Map<String, Object> changedContext = binding();
        changedContext.put("contextDigest", "sha256:" + "b".repeat(64));
        String original = TypedDeliberationFinalValidator.validate(
                first, noneFacts(), text, answer(text)).finalDigest();
        assertNotEquals(original, TypedDeliberationFinalValidator.validate(
                changedDispatch, noneFacts(), text, answer(text)).finalDigest());
        assertNotEquals(original, TypedDeliberationFinalValidator.validate(
                changedContext, noneFacts(), text, answer(text)).finalDigest());
    }

    @Test
    void tv11MissingTrustedFactsRejects() {
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), null, "正文", answer("正文")));
        Map<String, Object> missing = noneFacts();
        missing.remove("availableSources");
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), missing, "正文", answer("正文")));
        Map<String, Object> unknown = noneFacts();
        unknown.put("untrusted", true);
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), unknown, "正文", answer("正文")));
        Map<String, Object> duplicateOperation = facts(
                List.of("GENERATE_IMAGE", "GENERATE_IMAGE"), List.of(), "AVAILABLE");
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), duplicateOperation, "正文", answer("正文")));
        Map<String, Object> duplicateSource = facts(List.of(), List.of(
                source("same", "TASK_WORKSPACE_FILE", "file"),
                source("same", "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), duplicateSource, "正文", answer("正文")));
    }

    @Test
    void tv12NumericOrMissingBindingRejects() {
        Map<String, Object> numeric = binding();
        numeric.put("conversationGeneration", 7L);
        rejects(INVALID_BINDING, () -> TypedDeliberationFinalValidator.validate(
                numeric, noneFacts(), "正文", answer("正文")));
        Map<String, Object> missing = binding();
        missing.remove("turnId");
        rejects(INVALID_BINDING, () -> TypedDeliberationFinalValidator.validate(
                missing, noneFacts(), "正文", answer("正文")));
        Map<String, Object> nonCanonical = binding();
        nonCanonical.put("requestRevision", "01");
        rejects(INVALID_BINDING, () -> TypedDeliberationFinalValidator.validate(
                nonCanonical, noneFacts(), "正文", answer("正文")));
    }

    @Test
    void tv13UnknownUnionKeyRejects() {
        Map<String, Object> outcome = answer("正文");
        outcome.put("unknown", true);
        rejects(INVALID_OUTCOME, () -> validate("正文", outcome));
        Map<String, Object> clarification = clarify("正文", "问题？", List.of("SOURCE_SELECTION"));
        clarificationMap(clarification).put("unknown", "x");
        rejects(INVALID_OUTCOME, () -> validate("正文", clarification));
    }

    @Test
    void tv14DuplicateJsonKeyRejectsAtAnyLayer() {
        String duplicateRoot = "{\"schemaVersion\":1,\"schemaVersion\":1,\"kind\":\"ANSWER\","
                + "\"text\":\"正文\",\"clarification\":null,\"proposal\":null}";
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", duplicateRoot));
        String duplicateNested = "{\"schemaVersion\":1,\"kind\":\"CLARIFY\",\"text\":\"正文\","
                + "\"clarification\":{\"question\":\"一？\",\"question\":\"二？\","
                + "\"requiredFacts\":[\"SOURCE_SELECTION\"]},\"proposal\":null}";
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", duplicateNested));
        String duplicateFacts = "{\"schemaVersion\":1,\"referenceMode\":\"NONE\","
                + "\"supportedOperations\":[],\"availableSources\":[],\"availableSources\":[]}";
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), duplicateFacts, "正文",
                "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"正文\","
                        + "\"clarification\":null,\"proposal\":null}"));
    }

    @Test
    void tv15TrailingJsonOrMarkdownRejects() {
        String valid = "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"正文\","
                + "\"clarification\":null,\"proposal\":null}";
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", valid + " true"));
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", "```json\n" + valid + "\n```"));
    }

    @Test
    void tv16LoneHighAndLowSurrogateRejects() {
        String slash = "\\";
        String prefix = "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"";
        String suffix = "\",\"clarification\":null,\"proposal\":null}";
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", prefix + slash + "uD800" + suffix));
        rejects(INVALID_JSON, () -> TypedDeliberationFinalValidator.validateJson(
                binding(), noneFacts(), "正文", prefix + slash + "uDC00" + suffix));
        String lone = String.valueOf((char) 0xD800);
        rejects(INVALID_OUTCOME, () -> validate("正文", answer(lone)));
    }

    @Test
    void tv17NestedTextDoesNotReplaceTopText() {
        Map<String, Object> outcome = clarify("顶层正文", "嵌套正文", List.of("REQUIREMENT_DETAILS"));
        rejects(CONTENT_MISMATCH, () -> validate("嵌套正文", outcome));
    }

    @Test
    void tv18ContentTextMismatchRejects() {
        rejects(CONTENT_MISMATCH, () -> validate("服务端正文", answer("模型正文")));
        rejects(INVALID_CONTENT, () -> validate("", answer("")));
        String maximum = "文".repeat(200_000);
        assertEquals(maximum, validate(maximum, answer(maximum)).content());
        String tooLong = maximum + "文";
        rejects(INVALID_CONTENT, () -> validate(tooLong, answer(tooLong)));
    }

    @Test
    void tv19BlankTextQuestionRejects() {
        rejects(INVALID_OUTCOME, () -> validate("正文", answer(" \u3000")));
        rejects(INVALID_OUTCOME, () -> validate("正文",
                clarify("正文", "\u00a0", List.of("REQUIREMENT_DETAILS"))));
        String multiline = "第一行\n第二行";
        assertEquals(multiline, validate(multiline, answer(multiline)).content());
    }

    @Test
    void tv20DuplicateOrUnknownRequiredFactsRejects() {
        rejects(INVALID_OUTCOME, () -> validate("正文",
                clarify("正文", "问题？", List.of("SOURCE_SELECTION", "SOURCE_SELECTION"))));
        rejects(INVALID_OUTCOME, () -> validate("正文",
                clarify("正文", "问题？", List.of("UNKNOWN"))));
        rejects(INVALID_OUTCOME, () -> validate("正文", clarify("正文", "问题？", List.of())));
    }

    @Test
    void tv21UnsupportedOperationRejects() {
        Map<String, Object> facts = facts(List.of("GENERATE_IMAGE"), List.of(), "AVAILABLE");
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), facts, "正文",
                proposal("正文", "EDIT_IMAGE", "修改", List.of())));
        Map<String, Object> unknown = proposal("正文", "DELETE_IMAGE", "删除", List.of());
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(
                binding(), imageFacts(), "正文", unknown));
    }

    @Test
    void tv22UnlistedOrNonimageSourceRejects() {
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of("missing"))));
        Map<String, Object> textFacts = facts(List.of("GENERATE_IMAGE"),
                List.of(source("text_1", "TASK_WORKSPACE_FILE", "text")), "AVAILABLE");
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), textFacts, "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of("text_1"))));
    }

    @Test
    void tv23EditZeroMultiOrTaskSourceRejects() {
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "EDIT_IMAGE", "修改", List.of())));
        Map<String, Object> twoFacts = facts(List.of("EDIT_IMAGE"), List.of(
                source("source_1", "CURRENT_CONVERSATION_ASSET", "image"),
                source("source_2", "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), twoFacts, "正文",
                proposal("正文", "EDIT_IMAGE", "修改", List.of("source_1", "source_2"))));
        Map<String, Object> taskFacts = facts(List.of("EDIT_IMAGE"),
                List.of(source("task_image", "TASK_WORKSPACE_FILE", "image")), "AVAILABLE");
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), taskFacts, "正文",
                proposal("正文", "EDIT_IMAGE", "修改", List.of("task_image"))));
    }

    @Test
    void tv24InstructionUnicodeScalarBoundary() {
        String accepted = "🐦".repeat(4_000);
        String rejected = accepted + "🐦";
        var result = TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", accepted, List.of()));
        assertEquals(4_000, result.interactionOutcome().proposal().instruction()
                .codePointCount(0, result.interactionOutcome().proposal().instruction().length()));
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", rejected, List.of())));
    }

    @Test
    void tv25InstructionIsoControlRejectsWhileProseLfIsAllowed() {
        String prose = "正文第一行\n正文第二行";
        assertEquals(prose, validate(prose, answer(prose)).content());
        for (String rejected : List.of("\u00a0", "\ufeff", "\u001c", "\u0085")) {
            rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(
                    binding(), imageFacts(), "正文",
                    proposal("正文", "GENERATE_IMAGE", rejected, List.of())));
        }
        String zeroWidthSpace = "\u200b";
        assertEquals(zeroWidthSpace, TypedDeliberationFinalValidator.validate(
                binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", zeroWidthSpace, List.of()))
                .interactionOutcome().proposal().instruction());
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", "第一行\n第二行", List.of())));
        String nel = Character.toString(0x85);
        rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", "前" + nel + "后", List.of())));
    }

    @Test
    void tv26ValidSupplementarySourceIdScalarAccepts() {
        for (String rejected : List.of("\u00a0", "\ufeff", "\u001c", "\u0085")) {
            Map<String, Object> rejectedFacts = facts(List.of(),
                    List.of(source(rejected, "TASK_WORKSPACE_FILE", "file")), "AVAILABLE");
            rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                    binding(), rejectedFacts, "正文", answer("正文")));
        }
        String zeroWidthSpace = "\u200b";
        Map<String, Object> zeroWidthFacts = facts(List.of("GENERATE_IMAGE"),
                List.of(source(zeroWidthSpace, "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        assertEquals(zeroWidthSpace, TypedDeliberationFinalValidator.validate(
                binding(), zeroWidthFacts, "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of(zeroWidthSpace)))
                .interactionOutcome().proposal().sourceRefIds().getFirst());

        String sourceId = "source_🐦";
        Map<String, Object> facts = facts(List.of("GENERATE_IMAGE"),
                List.of(source(sourceId, "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        var result = TypedDeliberationFinalValidator.validate(binding(), facts, "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of(sourceId)));
        assertEquals(sourceId, result.interactionOutcome().proposal().sourceRefIds().getFirst());
        String maximumUtf16 = "s".repeat(510) + "🐦";
        Map<String, Object> maximumFacts = facts(List.of("GENERATE_IMAGE"),
                List.of(source(maximumUtf16, "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        assertEquals(maximumUtf16, TypedDeliberationFinalValidator.validate(binding(), maximumFacts, "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of(maximumUtf16)))
                .interactionOutcome().proposal().sourceRefIds().getFirst());
        String tooLong = maximumUtf16 + "x";
        Map<String, Object> tooLongFacts = facts(List.of(),
                List.of(source(tooLong, "TASK_WORKSPACE_FILE", "file")), "AVAILABLE");
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), tooLongFacts, "正文", answer("正文")));
    }

    @Test
    void tv27NoneWithSourcesRejects() {
        Map<String, Object> invalid = facts(List.of(),
                List.of(source("source_1", "CURRENT_CONVERSATION_ASSET", "image")), "NONE");
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                binding(), invalid, "正文", answer("正文")));
    }

    @Test
    void tv28ReferenceCatalogOriginal16BoundPreserved() {
        List<Object> sixteen = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            sixteen.add(source("source_" + index, "TASK_WORKSPACE_FILE", "file"));
        }
        var result = TypedDeliberationFinalValidator.validate(binding(),
                facts(List.of(), sixteen, "AVAILABLE"), "正文", answer("正文"));
        assertEquals(16, result.dispatchFacts().availableSources().size());
        List<Object> seventeen = new ArrayList<>(sixteen);
        seventeen.add(source("source_16", "TASK_WORKSPACE_FILE", "file"));
        rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(binding(),
                facts(List.of(), seventeen, "AVAILABLE"), "正文", answer("正文")));
    }

    @Test
    void tv29ProposalCannotContainAuthorityOrLineageFields() {
        for (String forbidden : List.of("grantId", "consent", "parent", "producer")) {
            Map<String, Object> outcome = proposal(
                    "正文", "GENERATE_IMAGE", "生成", new ArrayList<>());
            proposalMap(outcome).put(forbidden, "forbidden");
            rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(
                    binding(), imageFacts(), "正文", outcome));
        }
    }

    @Test
    void tv30DeepImmutableCopyDoesNotMutateInput() {
        List<Object> sources = new ArrayList<>();
        Map<String, Object> source = source("source_1", "CURRENT_CONVERSATION_ASSET", "image");
        sources.add(source);
        List<Object> operations = new ArrayList<>(List.of("GENERATE_IMAGE"));
        Map<String, Object> facts = facts(operations, sources, "AVAILABLE");
        List<Object> sourceIds = new ArrayList<>(List.of("source_1"));
        Map<String, Object> outcome = proposal("正文", "GENERATE_IMAGE", "生成", sourceIds);
        var result = TypedDeliberationFinalValidator.validate(binding(), facts, "正文", outcome);
        String digest = result.finalDigest();

        operations.clear();
        sources.clear();
        source.put("mediaType", "text");
        sourceIds.clear();
        proposalMap(outcome).put("instruction", "篡改");

        assertEquals(List.of("GENERATE_IMAGE"), result.dispatchFacts().supportedOperations());
        assertEquals("image", result.dispatchFacts().availableSources().getFirst().mediaType());
        assertEquals(List.of("source_1"), result.interactionOutcome().proposal().sourceRefIds());
        assertEquals("生成", result.interactionOutcome().proposal().instruction());
        assertEquals(digest, result.finalDigest());
        assertThrows(UnsupportedOperationException.class,
                () -> result.dispatchFacts().supportedOperations().add("EDIT_IMAGE"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.dispatchFacts().availableSources().add(
                        new TypedDeliberationFinalValidator.Source("x", "TASK_WORKSPACE_FILE", "file")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.interactionOutcome().proposal().sourceRefIds().add("source_2"));
    }

    @Test
    void ws01ContentUsesJavaIsBlankForInformationSeparatorFour() {
        String informationSeparatorFour = "\u001c";
        rejects(INVALID_CONTENT, () -> validate(
                informationSeparatorFour, answer(informationSeparatorFour)));
    }

    @Test
    void ws02QuestionRetainsClientTrimDomain() {
        String informationSeparatorFour = "\u001c";
        assertEquals(informationSeparatorFour, validate("正文",
                clarify("正文", informationSeparatorFour, List.of("REQUIREMENT_DETAILS")))
                .interactionOutcome().clarification().question());
    }

    @Test
    void ws03TextAndQuestionRejectEcmaBlankNbspAndBom() {
        for (String ecmaBlank : List.of("\u00a0", "\ufeff")) {
            rejects(INVALID_OUTCOME, () -> validate(ecmaBlank, answer(ecmaBlank)));
            rejects(INVALID_OUTCOME, () -> validate("正文", answer(ecmaBlank)));
            rejects(INVALID_OUTCOME, () -> validate("正文",
                    clarify("正文", ecmaBlank, List.of("REQUIREMENT_DETAILS"))));
        }
    }

    @Test
    void ws04BindingOpaqueIdentifiersRetainJavaDomain() {
        for (String field : List.of("ownerJiacn", "clientId", "requestId", "turnId",
                "dispatchId", "snapshotId", "targetAgentId", "taskId")) {
            for (String javaNonblank : List.of("\u00a0", "\ufeff")) {
                Map<String, Object> candidate = binding();
                candidate.put(field, javaNonblank);
                var result = TypedDeliberationFinalValidator.validate(
                        candidate, noneFacts(), "正文", answer("正文"));
                assertEquals(javaNonblank, bindingField(result.binding(), field));
            }
        }
    }

    @Test
    void ws05InstructionAndSourceKeepClientTrimAndControlRules() {
        for (String rejected : List.of("\u00a0", "\ufeff", "\u001c", "\u0085")) {
            rejects(INVALID_OUTCOME, () -> TypedDeliberationFinalValidator.validate(
                    binding(), imageFacts(), "正文",
                    proposal("正文", "GENERATE_IMAGE", rejected, List.of())));
            Map<String, Object> rejectedFacts = facts(List.of(),
                    List.of(source(rejected, "TASK_WORKSPACE_FILE", "file")), "AVAILABLE");
            rejects(INVALID_FACTS, () -> TypedDeliberationFinalValidator.validate(
                    binding(), rejectedFacts, "正文", answer("正文")));
        }
    }

    @Test
    void ws06ZeroWidthSpaceAcceptedAcrossApplicableDomains() {
        String zeroWidthSpace = "\u200b";
        assertEquals(zeroWidthSpace, validate(
                zeroWidthSpace, answer(zeroWidthSpace)).content());
        assertEquals(zeroWidthSpace, validate("正文",
                clarify("正文", zeroWidthSpace, List.of("REQUIREMENT_DETAILS")))
                .interactionOutcome().clarification().question());
        assertEquals(zeroWidthSpace, TypedDeliberationFinalValidator.validate(
                binding(), imageFacts(), "正文",
                proposal("正文", "GENERATE_IMAGE", zeroWidthSpace, List.of()))
                .interactionOutcome().proposal().instruction());
        Map<String, Object> facts = facts(List.of("GENERATE_IMAGE"),
                List.of(source(zeroWidthSpace, "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
        assertEquals(zeroWidthSpace, TypedDeliberationFinalValidator.validate(
                binding(), facts, "正文",
                proposal("正文", "GENERATE_IMAGE", "生成", List.of(zeroWidthSpace)))
                .interactionOutcome().proposal().sourceRefIds().getFirst());
    }

    @Test
    void ws07ContentAndTextPreserveLfExactly() {
        String multiline = "第一行\n第二行";
        var result = validate(multiline, answer(multiline));
        assertEquals(multiline, result.content());
        assertEquals(multiline, result.interactionOutcome().text());
    }

    @Test
    void ws08ContentAndBindingRejectUnpairedSurrogates() {
        for (String unpaired : List.of(
                String.valueOf((char) 0xd800), String.valueOf((char) 0xdc00))) {
            rejects(INVALID_CONTENT, () -> validate(unpaired, answer(unpaired)));
            for (String field : List.of("ownerJiacn", "clientId", "requestId", "turnId",
                    "dispatchId", "snapshotId", "targetAgentId", "taskId")) {
                Map<String, Object> candidate = binding();
                candidate.put(field, unpaired);
                rejects(INVALID_BINDING, () -> TypedDeliberationFinalValidator.validate(
                        candidate, noneFacts(), "正文", answer("正文")));
            }
        }
    }

    private static String bindingField(
            TypedDeliberationFinalValidator.Binding binding, String field) {
        return switch (field) {
            case "ownerJiacn" -> binding.ownerJiacn();
            case "clientId" -> binding.clientId();
            case "requestId" -> binding.requestId();
            case "turnId" -> binding.turnId();
            case "dispatchId" -> binding.dispatchId();
            case "snapshotId" -> binding.snapshotId();
            case "targetAgentId" -> binding.targetAgentId();
            case "taskId" -> binding.taskId();
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static TypedDeliberationFinalValidator.ValidatedFinal validate(
            String content, Map<String, Object> outcome) {
        return TypedDeliberationFinalValidator.validate(binding(), noneFacts(), content, outcome);
    }

    private static Map<String, Object> binding() {
        return map(
                "tenantId", "0",
                "ownerJiacn", "owner_1",
                "clientId", "client_1",
                "conversationId", "42",
                "conversationGeneration", "7",
                "requestId", "request_1",
                "requestRevision", "1",
                "turnId", "turn_1",
                "dispatchId", "dispatch_1",
                "snapshotId", "snapshot_1",
                "contextDigest", "sha256:" + "a".repeat(64),
                "targetAgentId", "agent_1",
                "route", "CHAT",
                "taskId", "task_1");
    }

    private static Map<String, Object> noneFacts() {
        return facts(List.of(), List.of(), "NONE");
    }

    private static Map<String, Object> imageFacts() {
        return facts(List.of("GENERATE_IMAGE", "EDIT_IMAGE"),
                List.of(source("source_1", "CURRENT_CONVERSATION_ASSET", "image")), "AVAILABLE");
    }

    private static Map<String, Object> facts(
            List<?> operations, List<?> sources, String referenceMode) {
        return map("schemaVersion", 1, "referenceMode", referenceMode,
                "supportedOperations", operations, "availableSources", sources);
    }

    private static Map<String, Object> source(String id, String kind, String mediaType) {
        return map("sourceRefId", id, "kind", kind, "mediaType", mediaType);
    }

    private static Map<String, Object> answer(String text) {
        return map("schemaVersion", 1, "kind", "ANSWER", "text", text,
                "clarification", null, "proposal", null);
    }

    private static Map<String, Object> clarify(
            String text, String question, List<?> requiredFacts) {
        return map("schemaVersion", 1, "kind", "CLARIFY", "text", text,
                "clarification", map("question", question, "requiredFacts", requiredFacts),
                "proposal", null);
    }

    private static Map<String, Object> proposal(
            String text, String operation, String instruction, List<?> sourceRefIds) {
        return map("schemaVersion", 1, "kind", "EXECUTION_PROPOSAL", "text", text,
                "clarification", null,
                "proposal", map("operation", operation, "instruction", instruction,
                        "sourceRefIds", sourceRefIds));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> clarificationMap(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("clarification");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> proposalMap(Map<String, Object> outcome) {
        return (Map<String, Object>) outcome.get("proposal");
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            value.put((String) pairs[index], pairs[index + 1]);
        }
        return value;
    }

    private static void rejects(
            TypedDeliberationFinalValidator.Reason reason, Executable executable) {
        var error = assertThrows(TypedDeliberationFinalValidator.ValidationException.class, executable);
        assertEquals(reason, error.reason());
        assertEquals("TYPED_FINAL_" + reason.name(), error.code());
        assertEquals(error.code(), error.getMessage());
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream stream = TypedDeliberationFinalValidatorTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, "Missing contract resource " + name);
            return stream.readAllBytes();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
