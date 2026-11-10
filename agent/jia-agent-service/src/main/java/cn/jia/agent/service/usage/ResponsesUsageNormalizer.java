package cn.jia.agent.service.usage;

import cn.jia.economy.pricing.ModelTokenPrice.Category;
import cn.jia.economy.usage.TokenUsageCost;
import tools.jackson.databind.JsonNode;
import java.util.Map;

/** Text-only Responses invocation evidence. Output already includes reasoning;
 * input already includes cached input. Never add either counter a second time. */
public final class ResponsesUsageNormalizer {
    private ResponsesUsageNormalizer() { }
    public static TokenUsageCost.Usage normalize(String channel, JsonNode evidence) {
        if (channel == null || channel.isBlank() || evidence == null) return null;
        try {
            String id = text(evidence.get("providerReceiptId"));
            String model = text(evidence.get("actualModel"));
            String tier = text(evidence.get("serviceTier"));
            if (id == null || model == null || tier == null) return null;
            if ("default".equals(tier)) tier = "standard";
            var usage = evidence.get("usage");
            long input = count(usage.get("input_tokens")), output = count(usage.get("output_tokens"));
            long cached = count(usage.get("input_tokens_details").get("cached_tokens"));
            if (cached > input || usage.has("total_tokens") && count(usage.get("total_tokens")) != Math.addExact(input, output)) return null;
            return new TokenUsageCost.Usage(channel, model, tier, "openai-responses-text-v1",
                    Map.of(Category.INPUT, input - cached, Category.CACHED_INPUT, cached, Category.OUTPUT, output));
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) { return null; }
    }
    private static String text(JsonNode value) {
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
    private static long count(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new IllegalArgumentException("Invalid usage count");
        return value.longValue();
    }
}
