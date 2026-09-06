package cn.jia.agent.service.funding;

/**
 * Immutable synthetic V0 preview fixture. These values are deliberately not represented as
 * current or historical provider prices and must not be used outside economy.preview.
 */
public final class FundedBountyPreviewPriceBook {
    public static final String VERSION = "pb_preview_fixture_2026_09_v1";
    public static final String MODEL_ROUTE_VERSION = "route_preview_fixture_v0";
    public static final String PROVIDER = "openai";
    public static final String MODEL = "configured-model";
    public static final String RATE_PROVENANCE = "SYNTHETIC_PREVIEW_FIXTURE_NOT_PROVIDER_PRICING";

    public static final long INPUT_RATE_PER_MILLION = 1_500_000L;
    public static final long CACHED_INPUT_RATE_PER_MILLION = 500_000L;
    public static final long OUTPUT_RATE_PER_MILLION = 6_000_000L;
    public static final long REASONING_RATE_PER_MILLION = 8_000_000L;
    public static final long FIXED_REQUEST_FEE_MICRO = 1_000_000L;
    public static final long WORST_RISK_RESERVE_MICRO = 20_000_000L;
    public static final long FEE_BPS = 300L;
    public static final long FEE_CAP_MICRO = 100_000_000L;
    public static final long QUOTE_TTL_MILLIS = 300_000L;

    public static final TokenClasses ESTIMATED_TOKENS = new TokenClasses(18_000L, 4_000L, 6_000L, 3_000L);
    public static final TokenClasses WORST_TOKENS = new TokenClasses(36_000L, 8_000L, 12_000L, 6_000L);

    private FundedBountyPreviewPriceBook() {
    }

    public record TokenClasses(long input, long cachedInput, long output, long reasoning) {
        public TokenClasses {
            if (input < 0 || cachedInput < 0 || output < 0 || reasoning < 0) {
                throw new IllegalArgumentException("token classes must be non-negative");
            }
        }
    }
}
