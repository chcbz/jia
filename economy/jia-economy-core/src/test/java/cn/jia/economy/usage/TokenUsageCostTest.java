package cn.jia.economy.usage;

import cn.jia.economy.pricing.ModelTokenPrice;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static cn.jia.economy.usage.TokenUsageCost.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class TokenUsageCostTest {
    private ModelTokenPrice price(Map<ModelTokenPrice.Category, BigDecimal> rates) {
        return new ModelTokenPrice("channel", "model", "standard", "v1", "manual-cny", rates);
    }
    private TokenUsageCost.Usage usage(Map<ModelTokenPrice.Category, Long> tokens) {
        return new TokenUsageCost.Usage("channel", "model", "standard", "fixture-disjoint-v1", tokens);
    }
    @Test void absentUsageIsPendingNotFreeEvenWithPrice() {
        var result = TokenUsageCost.assess(null, price(Map.of()));
        assertEquals(PENDING_USAGE, result.status()); assertNull(result.costYuan());
    }
    @Test void explicitZeroIsDifferentFromMissingUsage() {
        var result = TokenUsageCost.assess(usage(Map.of()), price(Map.of()));
        assertEquals(PRICED, result.status()); assertEquals(new BigDecimal("0.000000"), result.costYuan());
    }
    @Test void absentOrIncompletePriceRemainsPending() {
        var usage = usage(Map.of(INPUT, 1L, CACHED_INPUT, 1L));
        assertEquals(PENDING_PRICE, TokenUsageCost.assess(usage, null).status());
        var result = TokenUsageCost.assess(usage, price(Map.of(INPUT, BigDecimal.ONE)));
        assertEquals(PENDING_PRICE, result.status()); assertNull(result.costYuan());
        assertNotNull(result.price());
    }
    @Test void actualRouteCannotUseOtherChannelModelOrTierPrice() {
        for (var route : new String[][]{{"other", "model", "standard"},
                {"channel", "other", "standard"}, {"channel", "model", "other"}}) {
            var usage = new TokenUsageCost.Usage(route[0], route[1], route[2], "fixture-v1", Map.of(INPUT, 1L));
            var result = TokenUsageCost.assess(usage, price(Map.of(INPUT, BigDecimal.ONE)));
            assertEquals(PENDING_PRICE, result.status()); assertNull(result.costYuan()); assertNull(result.price());
        }
    }
    @Test void categoriesAreAddedBeforeSixDecimalHalfUpRounding() {
        var snapshot = price(Map.of(INPUT, new BigDecimal("0.25"), OUTPUT, new BigDecimal("0.25")));
        var result = TokenUsageCost.assess(usage(Map.of(INPUT, 1L, OUTPUT, 1L)), snapshot);
        assertEquals(PRICED, result.status()); assertEquals(new BigDecimal("0.000001"), result.costYuan());
        assertSame(snapshot, result.price()); assertEquals("v1", result.price().version());
    }
    @Test void zeroCategoryDoesNotRequireAUnitPrice() {
        assertEquals(PRICED, TokenUsageCost.assess(usage(Map.of(INPUT, 0L)), price(Map.of())).status());
    }
    @Test void usageCannotChangeAfterItsAssessmentOrContainNegativeCounts() {
        var input = new HashMap<ModelTokenPrice.Category, Long>(); input.put(INPUT, 1L);
        var usage = usage(input); input.put(INPUT, 100L);
        assertEquals(1L, usage.tokens().get(INPUT));
        assertThrows(UnsupportedOperationException.class, () -> usage.tokens().put(INPUT, 2L));
        assertThrows(IllegalArgumentException.class, () -> usage(Map.of(INPUT, -1L)));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsageCost.Assessment(PENDING_USAGE, BigDecimal.ZERO, null));
    }
}
