package cn.jia.agent.service.usage;

import cn.jia.economy.pricing.ModelTokenPrice.Category;
import cn.jia.economy.usage.TokenUsageCost;
import tools.jackson.databind.JsonNode;
import java.util.Map;

/** Images protocol categories, never image dimensions, file sizes or model estimates.
 * Input totals include cached input. Ambiguous cache modality or inconsistent counters
 * remain unpriced evidence rather than being guessed or double-counted. */
public final class ImageUsageNormalizer {
    private ImageUsageNormalizer() { }
    public static TokenUsageCost.Usage normalize(String channel, String model, String tier, JsonNode evidence) {
        if (channel == null || channel.isBlank() || model == null || model.isBlank() || tier == null || tier.isBlank()) return null;
        try {
            var usage = evidence.get("usage");
            var input = usage.get("input_tokens_details");
            long text = count(input.get("text_tokens")), image = count(input.get("image_tokens"));
            long output = count(usage.get("output_tokens"));
            if (Math.addExact(text, image) != count(usage.get("input_tokens"))) return null;
            if (usage.has("total_tokens") && count(usage.get("total_tokens")) != Math.addExact(Math.addExact(text, image), output)) return null;
            // Do not silently accept another output modality as image tokens.
            if (usage.has("output_tokens_details")) {
                var details = usage.get("output_tokens_details");
                if (count(details.get("image_tokens")) != output || optionalCount(details, "text_tokens") != 0) return null;
            }
            long cached = optionalCount(input, "cached_tokens"), cachedText = 0, cachedImage = 0;
            var split = input.get("cached_tokens_details");
            if (split != null && !split.isNull()) {
                cachedText = count(split.get("text_tokens")); cachedImage = count(split.get("image_tokens"));
                if (Math.addExact(cachedText, cachedImage) != cached) return null;
            } else if (cached != 0) return null;
            if (cachedText > text || cachedImage > image) return null;
            // A second unexplained top-level cache count cannot be treated as uncached.
            if (usage.has("cached_tokens") && count(usage.get("cached_tokens")) != cached) return null;
            return new TokenUsageCost.Usage(channel, model, tier, "openai-images-modalities-v1",
                    Map.of(Category.INPUT, text - cachedText, Category.CACHED_INPUT, cachedText,
                            Category.IMAGE_INPUT, image - cachedImage, Category.CACHED_IMAGE_INPUT, cachedImage,
                            Category.IMAGE_OUTPUT, output));
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) { return null; }
    }
    private static long optionalCount(JsonNode node, String key) { return node.has(key) ? count(node.get(key)) : 0; }
    private static long count(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0)
            throw new IllegalArgumentException("Invalid usage count");
        return node.longValue();
    }
}
