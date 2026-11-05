package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Pure whole-task pricing. Workload rules and prices are supplied by economy, never by the model. */
public final class TaskQuoteCalculator {
    private TaskQuoteCalculator() { }

    /**
     * Preserve exact intermediate amounts and the selected snapshots. The caller persists this
     * result against the task/requirement/candidate identity; changing prices must not reprice it.
     * No minimum charge, risk surcharge, TTL, wallet mutation or model call belongs here.
     */
    public static Quote calculate(Estimate estimate, ModelTokenPrice modelPrice,
            Map<ToolKey, ToolPrice> toolPrices) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(modelPrice, "modelPrice");
        Objects.requireNonNull(toolPrices, "toolPrices");
        BigDecimal yuan = modelPrice.cost(estimate.tokens());
        Map<ToolKey, ToolPrice> selectedPrices = new LinkedHashMap<>();
        for (var entry : estimate.toolUnits().entrySet()) {
            if (entry.getValue().signum() == 0) continue;
            ToolPrice price = toolPrices.get(entry.getKey());
            if (price == null) throw new MissingToolPrice(entry.getKey());
            selectedPrices.put(entry.getKey(), price);
            yuan = yuan.add(price.yuanPerUnit().multiply(entry.getValue()));
        }
        return new Quote(estimate, modelPrice, Map.copyOf(selectedPrices), yuan,
                CnyAmounts.quoteWen(yuan));
    }

    /** Whole-task billable quantities derived from a configured workload rule, not live usage. */
    public record Estimate(String ruleId, String ruleVersion,
            Map<ModelTokenPrice.Category, Long> tokens, Map<ToolKey, BigDecimal> toolUnits) {
        public Estimate {
            required(ruleId, "ruleId");
            required(ruleVersion, "ruleVersion");
            tokens = Map.copyOf(tokens);
            toolUnits = Map.copyOf(toolUnits);
            tokens.values().forEach(count -> {
                if (count < 0) throw new IllegalArgumentException("Estimated token count cannot be negative");
            });
            toolUnits.values().forEach(CnyAmounts::nonNegative);
        }
    }

    /** Channel and billing unit are part of the key: do not substitute a similarly named tool. */
    public record ToolKey(String channel, String tool, String unit) {
        public ToolKey {
            required(channel, "channel");
            required(tool, "tool");
            required(unit, "unit");
        }
    }

    /** Manually registered RMB price. An explicit zero is different from a missing entry. */
    public record ToolPrice(String version, String basis, BigDecimal yuanPerUnit) {
        public ToolPrice {
            required(version, "version");
            required(basis, "basis");
            CnyAmounts.nonNegative(yuanPerUnit);
        }
    }

    public record Quote(Estimate estimate, ModelTokenPrice modelPrice,
            Map<ToolKey, ToolPrice> toolPrices, BigDecimal estimatedYuan, long amountWen) { }

    /** A candidate-local pricing failure, not a reason to discard other candidate quotations. */
    public static final class MissingToolPrice extends IllegalStateException {
        private final ToolKey tool;
        public MissingToolPrice(ToolKey tool) {
            super("Missing tool price: " + tool);
            this.tool = tool;
        }
        public ToolKey tool() { return tool; }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
