package cn.jia.chat.service.usage;

import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.TokenUsageCost;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

/** One OpenAI Chat Completions text response, not Codex thread last/total notifications.
 * Source contract/basis: docs/interface-catalog/usage-cost-validation.md.
 * Unknown or incomplete usage returns null (pending), never an invented zero or guessed subtotal.
 */
public final class OpenAiTokenUsageNormalizer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OpenAiTokenUsageNormalizer() { }

    public static TokenUsageCost.Usage normalize(String channel, String actualModel, String tier, String rawUsageJson) {
        if (channel == null || channel.isBlank() || actualModel == null || actualModel.isBlank()
                || tier == null || tier.isBlank() || rawUsageJson == null) return null;
        try {
            JsonNode raw = JSON.readTree(rawUsageJson);
            if (raw == null || !raw.isObject()) return null;
            Long input = count(raw.get("prompt_tokens"));
            Long output = count(raw.get("completion_tokens"));
            Long total = count(raw.get("total_tokens"));
            if (input == null || output == null || total == null || Math.addExact(input, output) != total) return null;
            JsonNode inputDetails = raw.get("prompt_tokens_details"), outputDetails = raw.get("completion_tokens_details");
            if (hasNonzeroOrInvalid(inputDetails, "audio_tokens") || hasNonzeroOrInvalid(outputDetails, "audio_tokens")) return null;
            Long cached = count(inputDetails == null ? null : inputDetails.get("cached_tokens"));
            // Zero input logically has no cached input. Otherwise absence is not evidence of zero cache.
            if (cached == null && input == 0 && (inputDetails == null || inputDetails.isNull() || !inputDetails.has("cached_tokens"))) cached = 0L;
            if (cached == null || cached > input) return null;
            if (outputDetails != null && outputDetails.has("reasoning_tokens")) {
                Long reasoning = count(outputDetails.get("reasoning_tokens"));
                if (reasoning == null || reasoning > output) return null;
            }
            // Reasoning/prediction counters are parts of completion_tokens; never add them again.
            // OpenAI caching has no additional write charge: write counters are not manufactured.
            return new TokenUsageCost.Usage(channel, actualModel, tier, "openai-chat-completions-text-v1",
                    Map.of(ModelTokenPrice.Category.INPUT, input - cached,
                            ModelTokenPrice.Category.CACHED_INPUT, cached, ModelTokenPrice.Category.OUTPUT, output));
        } catch (JacksonException | ArithmeticException invalid) {
            return null;
        }
    }

    private static Long count(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0
                ? value.longValue() : null;
    }
    private static boolean hasNonzeroOrInvalid(JsonNode details, String field) {
        if (details == null || details.isNull()) return false;
        if (!details.isObject()) return true;
        if (!details.has(field)) return false;
        Long value = count(details.get(field));
        return value == null || value != 0;
    }
}
