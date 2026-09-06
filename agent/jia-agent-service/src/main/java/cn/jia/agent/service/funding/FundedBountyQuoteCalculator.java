package cn.jia.agent.service.funding;

import java.math.BigInteger;

/** Exact integer-only quote arithmetic. Cached input is a distinct, non-overlapping token class. */
public final class FundedBountyQuoteCalculator {
    private static final BigInteger MILLION = BigInteger.valueOf(1_000_000L);
    private static final BigInteger BPS = BigInteger.valueOf(10_000L);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private FundedBountyQuoteCalculator() {
    }

    public static QuoteAmounts calculate(long gross, long minimumAcceptedPayout,
            FundedBountyPreviewPriceBook.TokenClasses estimated,
            FundedBountyPreviewPriceBook.TokenClasses worst) {
        nonNegative(gross, "gross");
        nonNegative(minimumAcceptedPayout, "minimumAcceptedPayout");
        long estimatedCompute = tokenCost(estimated, 0L);
        long worstCompute = tokenCost(worst, FundedBountyPreviewPriceBook.WORST_RISK_RESERVE_MICRO);
        long platformFee = minimum(FundedBountyPreviewPriceBook.FEE_CAP_MICRO,
                ceilProduct(gross, FundedBountyPreviewPriceBook.FEE_BPS, BPS, "platform fee"));
        long estimatedPayout = subtractFloorZero(gross, estimatedCompute, platformFee);
        long worstPayout = subtractFloorZero(gross, worstCompute, platformFee);
        BigInteger required = BigInteger.valueOf(worstCompute)
                .add(BigInteger.valueOf(platformFee))
                .add(BigInteger.valueOf(minimumAcceptedPayout));
        boolean covered = required.compareTo(BigInteger.valueOf(gross)) <= 0;
        long headroom = covered ? exactLong(BigInteger.valueOf(gross).subtract(required), "budget headroom") : 0L;
        return new QuoteAmounts(estimatedCompute, worstCompute, platformFee, estimatedPayout,
                worstPayout, headroom, covered);
    }

    public static long tokenCost(FundedBountyPreviewPriceBook.TokenClasses tokens, long extraReserve) {
        if (tokens == null) throw new IllegalArgumentException("tokens are required");
        nonNegative(extraReserve, "extraReserve");
        BigInteger total = BigInteger.ZERO;
        total = total.add(BigInteger.valueOf(ceilProduct(tokens.input(),
                FundedBountyPreviewPriceBook.INPUT_RATE_PER_MILLION, MILLION, "input token cost")));
        total = total.add(BigInteger.valueOf(ceilProduct(tokens.cachedInput(),
                FundedBountyPreviewPriceBook.CACHED_INPUT_RATE_PER_MILLION, MILLION, "cached input token cost")));
        total = total.add(BigInteger.valueOf(ceilProduct(tokens.output(),
                FundedBountyPreviewPriceBook.OUTPUT_RATE_PER_MILLION, MILLION, "output token cost")));
        total = total.add(BigInteger.valueOf(ceilProduct(tokens.reasoning(),
                FundedBountyPreviewPriceBook.REASONING_RATE_PER_MILLION, MILLION, "reasoning token cost")));
        total = total.add(BigInteger.valueOf(FundedBountyPreviewPriceBook.FIXED_REQUEST_FEE_MICRO));
        total = total.add(BigInteger.valueOf(extraReserve));
        return exactLong(total, "token cost");
    }

    static long ceilProduct(long value, long rate, BigInteger divisor, String field) {
        nonNegative(value, field + " value");
        nonNegative(rate, field + " rate");
        if (divisor == null || divisor.signum() <= 0) throw new IllegalArgumentException("divisor must be positive");
        BigInteger product = BigInteger.valueOf(value).multiply(BigInteger.valueOf(rate));
        if (product.signum() == 0) return 0L;
        return exactLong(product.add(divisor).subtract(BigInteger.ONE).divide(divisor), field);
    }

    private static long subtractFloorZero(long gross, long first, long second) {
        BigInteger value = BigInteger.valueOf(gross).subtract(BigInteger.valueOf(first)).subtract(BigInteger.valueOf(second));
        return value.signum() <= 0 ? 0L : exactLong(value, "payout");
    }

    private static long minimum(long left, long right) {
        return Math.min(left, right);
    }

    private static void nonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
    }

    private static long exactLong(BigInteger value, String field) {
        if (value.signum() < 0 || value.compareTo(LONG_MAX) > 0) {
            throw new IllegalArgumentException(field + " exceeds the supported range");
        }
        return value.longValueExact();
    }

    public record QuoteAmounts(long estimatedComputeMicro, long worstComputeMicro,
            long platformFeeMicro, long estimatedAgentPayoutMicro, long worstAgentPayoutMicro,
            long budgetHeadroomMicro, boolean budgetCovered) {
    }
}
