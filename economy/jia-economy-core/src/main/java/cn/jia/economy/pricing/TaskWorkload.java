package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Model supplies kind/complexity/deliverable count, never token prices or currency amounts.
 * Input file count comes from authorized task inputs; billing tool keys come from server routing.
 */
public record TaskWorkload(String kind, Complexity complexity, long inputFiles, long deliverables,
        Map<TaskQuoteCalculator.ToolKey, BigDecimal> toolUnits) {
    public enum Complexity { SIMPLE, STANDARD, COMPLEX }

    public TaskWorkload {
        if (kind == null || kind.isBlank()) throw new IllegalArgumentException("Workload kind is required");
        Objects.requireNonNull(complexity, "complexity");
        if (inputFiles < 0 || deliverables < 1) throw new IllegalArgumentException("Invalid workload counts");
        toolUnits = Map.copyOf(toolUnits);
        toolUnits.values().forEach(CnyAmounts::nonNegative);
    }
}
