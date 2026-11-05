package cn.jia.economy.pricing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;

class CnyPricingTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static ModelTokenPrice price(Map<ModelTokenPrice.Category, BigDecimal> rates) {
        return new ModelTokenPrice("test-channel", "test-model", "standard", "v1", "synthetic-test-price", rates);
    }
    @Test void quotesRoundUpOnlyAtFinalWenBoundary() {
        assertEquals(13, CnyAmounts.quoteWen(d("0.123456")));
        assertEquals(101, CnyAmounts.quoteWen(d("1.000001")));
        assertEquals(1, CnyAmounts.quoteWen(d("0.00000001")));
        assertEquals(100, CnyAmounts.quoteWen(d("1.000000")));
        assertEquals(0, CnyAmounts.quoteWen(BigDecimal.ZERO));
        assertEquals(Long.MAX_VALUE, CnyAmounts.quoteWen(d("92233720368547758.07")));
        assertThrows(ArithmeticException.class, () -> CnyAmounts.quoteWen(d("92233720368547758.08")));
        assertThrows(IllegalArgumentException.class, () -> CnyAmounts.quoteWen(d("-0.01")));
    }
    @Test void recordedCostUsesHalfUpSixPlaces() {
        assertEquals(d("0.000001"), CnyAmounts.recordedCost(d("0.0000005")));
        assertEquals(d("0.000000"), CnyAmounts.recordedCost(d("0.00000049")));
        assertEquals(d("1.000000"), CnyAmounts.recordedCost(BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> CnyAmounts.recordedCost(d("-1")));
    }
    @Test void millionTokenRatesAreNotMistakenForPerTokenRates() {
        var price = price(Map.of(INPUT, d("0.668790"), OUTPUT, d("3.343950"), CACHED_INPUT, d("0.066879")));
        assertEquals(d("4.079619"), CnyAmounts.recordedCost(price.cost(Map.of(INPUT, 1000000L, OUTPUT, 1000000L, CACHED_INPUT, 1000000L))));
        assertEquals(d("0.000001"), CnyAmounts.recordedCost(price.cost(Map.of(INPUT, 1L))));
    }
    @Test void categoriesAreSummedBeforeRoundingAndSnapshotCannotChange() {
        var rates = new EnumMap<ModelTokenPrice.Category, BigDecimal>(ModelTokenPrice.Category.class);
        rates.put(INPUT, d("0.4")); rates.put(OUTPUT, d("0.4"));
        var price = price(rates); rates.put(INPUT, d("999"));
        assertEquals(d("0.000001"), CnyAmounts.recordedCost(price.cost(Map.of(INPUT, 1L, OUTPUT, 1L))));
        assertThrows(UnsupportedOperationException.class, () -> price.yuanPerMillionTokens().put(INPUT, BigDecimal.ZERO));
    }
    @Test void missingPriceAndUsageNeverSilentlyBecomeFree() {
        var price = price(Map.of(INPUT, BigDecimal.ONE));
        var error = assertThrows(ModelTokenPrice.MissingPrice.class, () -> price.cost(Map.of(OUTPUT, 1L)));
        assertEquals(OUTPUT, error.category());
        assertEquals(BigDecimal.ZERO, price.cost(Map.of(OUTPUT, 0L)));
        assertThrows(NullPointerException.class, () -> price.cost(null));
        assertThrows(IllegalArgumentException.class, () -> price.cost(Map.of(INPUT, -1L)));
        assertThrows(IllegalArgumentException.class, () -> price(Map.of(INPUT, d("-1"))));
    }
    @Test void countsBeyondJavascriptSafeIntegerRemainExact() {
        var price = price(Map.of(INPUT, d("1")));
        assertEquals(d("9007199254.740993"), price.cost(Map.of(INPUT, 9007199254740993L)));
    }
}
