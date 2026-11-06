package cn.jia.economy.usage;

import cn.jia.economy.pricing.CnyAmounts;
import cn.jia.economy.pricing.ModelTokenPrice;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/** Cost assessment only: never reads or writes a task's quote, reservation or wallet. */
public final class TokenUsageCost {
    private TokenUsageCost() { }

    public enum Status { PENDING_USAGE, PENDING_PRICE, PRICED }

    /**
     * One completed billable attempt, normalized by its source adapter, not notification deltas.
     * The adapter must establish overlapping-token semantics before constructing this value.
     * An explicit empty map is reported zero usage; absent/unverified usage remains null.
     */
    public record Usage(String channel, String model, String tier, String normalizationBasis,
            Map<ModelTokenPrice.Category, Long> tokens) {
        public Usage {
            for (String value : new String[]{channel, model, tier, normalizationBasis}) {
                if (value == null || value.isBlank())
                    throw new IllegalArgumentException("Usage identity and normalization basis are required");
            }
            tokens = Map.copyOf(tokens);
            tokens.values().forEach(count -> {
                if (count < 0) throw new IllegalArgumentException("Token count cannot be negative");
            });
        }
    }

    /** A missing amount means pending, never zero. Retain the immutable price as audit evidence. */
    public record Assessment(Status status, BigDecimal costYuan, ModelTokenPrice price) {
        public Assessment {
            Objects.requireNonNull(status, "status");
            if (status == Status.PRICED) {
                Objects.requireNonNull(price, "price");
                costYuan = CnyAmounts.recordedCost(costYuan);
            } else if (costYuan != null) {
                throw new IllegalArgumentException("Pending cost cannot have a posted amount");
            }
        }
    }

    /** Price is the attempt's retained snapshot, not a lookup of today's default model rate. */
    public static Assessment assess(Usage usage, ModelTokenPrice price) {
        if (usage == null) return new Assessment(Status.PENDING_USAGE, null, price);
        if (price == null || !price.channel().equals(usage.channel())
                || !price.model().equals(usage.model()) || !price.tier().equals(usage.tier()))
            return new Assessment(Status.PENDING_PRICE, null, null);
        try {
            return new Assessment(Status.PRICED, price.cost(usage.tokens()), price);
        } catch (ModelTokenPrice.MissingPrice missing) {
            return new Assessment(Status.PENDING_PRICE, null, price);
        }
    }
}
