package cn.jia.chat.service.usage;

import cn.jia.economy.usage.TokenUsageCost;
import com.openai.models.chat.completions.ChatCompletion;

/** Native provider receipt: requested model/tier never substitutes actual billing identity. */
public record OpenAiUsageEvidence(String providerReceiptId, String actualModel, String actualTier,
        String rawUsageJson, TokenUsageCost.Usage normalizedUsage) {
    public static OpenAiUsageEvidence capture(ChatCompletion response, String channel) {
        if (response == null) return new OpenAiUsageEvidence(null, null, null, "null", null);
        String id = field(response::id), model = field(response::model);
        String tier = field(() -> response.serviceTier().map(value -> value.asString()).orElse(null));
        String normalizedTier = "default".equals(tier) ? "standard" : tier;
        String raw;
        try { raw = com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(response._usage()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { raw = "null"; }
        var normalized = id == null || id.isBlank() ? null
                : OpenAiTokenUsageNormalizer.normalize(channel, model, normalizedTier, raw);
        return new OpenAiUsageEvidence(id, model, tier, raw, normalized);
    }

    private static String field(java.util.function.Supplier<String> read) {
        try { return read.get(); }
        catch (com.openai.errors.OpenAIInvalidDataException invalid) { return null; }
    }
}
