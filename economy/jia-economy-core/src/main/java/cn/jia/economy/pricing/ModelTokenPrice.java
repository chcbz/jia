package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Immutable RMB-per-million-token snapshot. Model/channel/tier and version are exact keys. */
public record ModelTokenPrice(String channel, String model, String tier, String version, String basis,
        Map<Category, BigDecimal> yuanPerMillionTokens) {
    public enum Category { INPUT, OUTPUT, CACHED_INPUT, CACHE_WRITE_30M, CACHE_WRITE_12H }

    public ModelTokenPrice {
        for (String key : new String[]{channel, model, tier, version, basis}) {
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Price identity is required");
        }
        yuanPerMillionTokens = Map.copyOf(yuanPerMillionTokens);
        yuanPerMillionTokens.values().forEach(CnyAmounts::nonNegative);
    }

    /**
     * Usage must already be normalized by the provider adapter into non-overlapping billable
     * categories (e.g. cached input removed from ordinary input). No cumulative/delta guessing.
     * Missing usage is NOT an empty map: the caller keeps it pending rather than pricing it.
     * Retain this exact result for aggregation/quoting; round once when posting the final cost.
     */
    public BigDecimal cost(Map<Category, Long> normalizedUsage) {
        Objects.requireNonNull(normalizedUsage, "normalizedUsage");
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : normalizedUsage.entrySet()) {
            Category category = Objects.requireNonNull(entry.getKey(), "category");
            long count = Objects.requireNonNull(entry.getValue(), "token count");
            if (count < 0) throw new IllegalArgumentException("Token count cannot be negative");
            if (count == 0) continue;
            BigDecimal rate = yuanPerMillionTokens.get(category);
            if (rate == null) throw new MissingPrice(category);
            total = total.add(rate.multiply(BigDecimal.valueOf(count)).movePointLeft(6));
        }
        return total;
    }

    /** A local unquotable/pending-cost result, never a fallback to free or a different model. */
    public static final class MissingPrice extends IllegalStateException {
        private final Category category;
        public MissingPrice(Category category) {
            super("Missing token price: " + category);
            this.category = category;
        }
        public Category category() { return category; }
    }
}
