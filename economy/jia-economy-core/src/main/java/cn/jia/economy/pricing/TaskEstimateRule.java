package cn.jia.economy.pricing;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Explicit calibrated whole-task coefficients. No hidden preview defaults, multiplier or margin. */
public record TaskEstimateRule(String id, String version, String basis, String kind,
        TaskWorkload.Complexity complexity, Map<ModelTokenPrice.Category, Long> baseTokens,
        Map<ModelTokenPrice.Category, Long> perInputFileTokens,
        Map<ModelTokenPrice.Category, Long> perDeliverableTokens) {
    public TaskEstimateRule {
        for (String value : new String[]{id, version, basis, kind}) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("Estimate rule identity and basis are required");
        }
        Objects.requireNonNull(complexity, "complexity");
        baseTokens = coefficients(baseTokens);
        perInputFileTokens = perInputFileTokens == null ? Map.of() : coefficients(perInputFileTokens);
        perDeliverableTokens = perDeliverableTokens == null ? Map.of() : coefficients(perDeliverableTokens);
    }

    public TaskQuoteCalculator.Estimate estimate(TaskWorkload workload) {
        if (!kind.equals(workload.kind()) || complexity != workload.complexity()) {
            throw new IllegalArgumentException("Estimate rule does not match workload");
        }
        var tokens = new EnumMap<ModelTokenPrice.Category, Long>(ModelTokenPrice.Category.class);
        for (var category : ModelTokenPrice.Category.values()) {
            long count = Math.addExact(baseTokens.getOrDefault(category, 0L),
                    Math.addExact(Math.multiplyExact(perInputFileTokens.getOrDefault(category, 0L), workload.inputFiles()),
                            Math.multiplyExact(perDeliverableTokens.getOrDefault(category, 0L), workload.deliverables())));
            if (count > 0) tokens.put(category, count);
        }
        return new TaskQuoteCalculator.Estimate(id, version, tokens, workload.toolUnits());
    }

    private static Map<ModelTokenPrice.Category, Long> coefficients(Map<ModelTokenPrice.Category, Long> values) {
        var result = Map.copyOf(Objects.requireNonNull(values, "Token coefficients must be explicit, including zero/empty"));
        if (result.values().stream().anyMatch(value -> value < 0)) throw new IllegalArgumentException("Negative token coefficient");
        return result;
    }
}
