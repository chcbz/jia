package cn.jia.chat.service.usage;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiTokenUsageNormalizerTest {
    private cn.jia.economy.usage.TokenUsageCost.Usage normalize(String raw) {
        return OpenAiTokenUsageNormalizer.normalize("fixture-channel", "actual-model", "standard", raw);
    }
    @Test void subtractsCacheAndDoesNotDoubleCountReasoningOrPredictionTokens() {
        var usage = normalize("""
                {"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,
                 "prompt_tokens_details":{"cached_tokens":80,"audio_tokens":0},
                 "completion_tokens_details":{"reasoning_tokens":10,"audio_tokens":0,
                    "accepted_prediction_tokens":5,"rejected_prediction_tokens":2}}
                """);
        assertNotNull(usage); assertEquals("actual-model", usage.model());
        assertEquals(Map.of(INPUT, 20L, CACHED_INPUT, 80L, OUTPUT, 20L), usage.tokens());
        assertFalse(usage.tokens().containsKey(CACHE_WRITE_30M)); assertFalse(usage.tokens().containsKey(CACHE_WRITE_12H));
    }
    @Test void explicitZeroDiffersFromMissingUsageOrCacheEvidence() {
        assertNotNull(normalize("{\"prompt_tokens\":0,\"completion_tokens\":0,\"total_tokens\":0}"));
        for (String raw : new String[]{"null", "{}", "[]", "bad-json", "{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}"})
            assertNull(normalize(raw), raw);
    }
    @Test void codexThreadSnapshotsAreNotInterpretedAsChatCompletions() {
        assertNull(normalize("{\"last\":{\"inputTokens\":10,\"outputTokens\":2},\"total\":{\"totalTokens\":12}}"));
    }
    @Test void contradictoryCountsUnknownAudioOrNonIntegerCountsRemainPending() {
        String good = "{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_tokens_details\":{\"cached_tokens\":80},\"completion_tokens_details\":{\"reasoning_tokens\":10}}";
        for (String raw : new String[]{good.replace(":120", ":121"), good.replace(":80", ":101"),
                good.replace(":80", ":-1"), good.replace(":100", ":100.0"), good.replace(":10}", ":21}"),
                good.replace("\"cached_tokens\":80", "\"cached_tokens\":80,\"audio_tokens\":1"),
                good.replace("\"reasoning_tokens\":10", "\"reasoning_tokens\":10,\"audio_tokens\":1"),
                good.replace(":100", ":9223372036854775808")}) assertNull(normalize(raw), raw);
        assertNotNull(normalize(good.replace("\"cached_tokens\":80", "\"cached_tokens\":80,\"new_metadata\":\"harmless\"")));
    }
    @Test void unknownActualRouteDoesNotSubstituteARequestedModel() {
        assertNull(OpenAiTokenUsageNormalizer.normalize("channel", null, "standard", "{}"));
        assertNull(OpenAiTokenUsageNormalizer.normalize(null, "model", "standard", "{}"));
    }
}
