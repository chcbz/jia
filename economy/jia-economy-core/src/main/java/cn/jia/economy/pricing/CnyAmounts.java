package cn.jia.economy.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** RMB cost precision and integer-wen quoting; no silver conversion or runtime FX. */
public final class CnyAmounts {
    private CnyAmounts() { }

    /** Round only the final cost being posted, not each token category. */
    public static BigDecimal recordedCost(BigDecimal yuan) {
        return nonNegative(yuan).setScale(6, RoundingMode.HALF_UP);
    }

    /** Quote directly from the unrounded estimate: 1 yuan = 100 wen. */
    public static long quoteWen(BigDecimal estimatedYuan) {
        return nonNegative(estimatedYuan).movePointRight(2)
                .setScale(0, RoundingMode.CEILING).longValueExact();
    }

    static BigDecimal nonNegative(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) throw new IllegalArgumentException("Cost cannot be negative");
        return amount;
    }
}
