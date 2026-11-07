package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.Map;

/** Whole-task token estimates supplied by the recommendation model. Not measured usage.
 * The server owns model/tool routing and prices; no complexity coefficients or cache assumptions. */
public record TaskWorkload(String kind, long inputTokens, long outputTokens, long deliverables,
        Map<TaskQuoteCalculator.ToolKey, BigDecimal> toolUnits) {
    public TaskWorkload {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Workload kind is required");
        if (inputTokens < 0 || outputTokens < 0 || (inputTokens == 0 && outputTokens == 0) || deliverables < 1)
            throw new IllegalArgumentException("Nonnegative token estimates with a nonzero total and positive deliverables required");
        toolUnits = Map.copyOf(toolUnits);
        toolUnits.values().forEach(CnyAmounts::nonNegative);
    }
    public TaskQuoteCalculator.Estimate estimate() {
        return new TaskQuoteCalculator.Estimate("MODEL_TOKEN_ESTIMATE", "1",
                Map.of(ModelTokenPrice.Category.INPUT, inputTokens, ModelTokenPrice.Category.OUTPUT, outputTokens), toolUnits);
    }
}
