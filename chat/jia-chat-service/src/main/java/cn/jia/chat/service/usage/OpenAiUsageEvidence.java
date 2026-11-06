package cn.jia.chat.service.usage;

import cn.jia.economy.usage.TokenUsageCost;
import com.openai.models.completions.CompletionUsage;
import org.springframework.ai.chat.model.ChatResponse;

/** Captures the actual provider receipt and its native usage, not a text-token estimate. */
public record OpenAiUsageEvidence(String providerReceiptId, String actualModel, String rawUsageJson,
        TokenUsageCost.Usage normalizedUsage) {
    public static OpenAiUsageEvidence capture(ChatResponse response, String channel, String tier) {
        if (response == null || response.getMetadata() == null)
            return new OpenAiUsageEvidence(null, null, "null", null);
        var metadata = response.getMetadata();
        Object nativeUsage = metadata.getUsage() == null ? null : metadata.getUsage().getNativeUsage();
        if (!(nativeUsage instanceof CompletionUsage usage))
            return new OpenAiUsageEvidence(metadata.getId(), metadata.getModel(), "null", null);
        String raw;
        try {
            // Use the SDK's own serializer: its Optional/JsonValue fields are not ordinary bean properties.
            raw = com.openai.core.ObjectMappers.jsonMapper().writeValueAsString(usage);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return new OpenAiUsageEvidence(metadata.getId(), metadata.getModel(), "null", null);
        }
        var normalized = metadata.getId() == null || metadata.getId().isBlank() ? null
                : OpenAiTokenUsageNormalizer.normalize(channel, metadata.getModel(), tier, raw);
        return new OpenAiUsageEvidence(metadata.getId(), metadata.getModel(), raw, normalized);
    }
}
