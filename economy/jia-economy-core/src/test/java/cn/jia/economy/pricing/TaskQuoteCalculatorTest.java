package cn.jia.economy.pricing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static cn.jia.economy.pricing.TaskQuoteCalculator.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskQuoteCalculatorTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static ModelTokenPrice price(String rate) {
        return new ModelTokenPrice("fixture-channel", "fixture-model", "standard", "v1", "synthetic-test-price",
                Map.of(INPUT, d(rate), OUTPUT, d(rate)), null, Map.of());
    }
    private static Estimate estimate(Map<ModelTokenPrice.Category, Long> tokens,
            Map<ToolKey, BigDecimal> tools) {
        return new Estimate("fixture-rule", "r1", tokens, tools);
    }
    private static final ToolKey TOOL = new ToolKey("fixture-channel", "render", "page");

    @Test void tokenAndToolCostsAreSummedBeforeOneFinalCeiling() {
        var quote = calculate(estimate(Map.of(INPUT, 1L, OUTPUT, 1L), Map.of(TOOL, d("1"))),
                price("4000"), Map.of(TOOL, new ToolPrice("t1", "synthetic-test-price", d("0.002"))));
        assertEquals(0, d("0.01").compareTo(quote.estimatedYuan()));
        assertEquals(1, quote.amountWen()); // per-component rounding would incorrectly charge 3
    }
    @Test void quotingDoesNotFirstRoundToCostLedgerPrecision() {
        var quote = calculate(estimate(Map.of(INPUT, 1L), Map.of()), price("10000.01"), Map.of());
        assertEquals(d("0.01000001"), quote.estimatedYuan());
        assertEquals(d("0.010000"), CnyAmounts.recordedCost(quote.estimatedYuan()));
        assertEquals(2, quote.amountWen());
    }
    @Test void capturesOnlySelectedImmutablePricesAndRuleQuantities() {
        var tokens = new HashMap<ModelTokenPrice.Category, Long>(Map.of(INPUT, 1L));
        var units = new HashMap<ToolKey, BigDecimal>(Map.of(TOOL, d("2")));
        var prices = new HashMap<ToolKey, ToolPrice>(Map.of(TOOL, new ToolPrice("t1", "synthetic-test-price", d("0.10"))));
        var quote = calculate(estimate(tokens, units), price("0"), prices);
        tokens.put(INPUT, 999L); units.put(TOOL, d("999"));
        prices.put(TOOL, new ToolPrice("t2", "synthetic-test-price", d("999")));
        assertEquals(20, quote.amountWen());
        assertEquals(1L, quote.estimate().tokens().get(INPUT));
        assertEquals("t1", quote.toolPrices().get(TOOL).version());
        assertEquals("r1", quote.estimate().ruleVersion());
        assertThrows(UnsupportedOperationException.class, () -> quote.toolPrices().clear());
        assertThrows(UnsupportedOperationException.class, () -> quote.estimate().toolUnits().clear());
    }
    @Test void missingPriceIsNotFreeOrSubstitutedFromAnotherChannelOrUnit() {
        var otherChannel = new ToolKey("other", "render", "page");
        var otherUnit = new ToolKey("fixture-channel", "render", "request");
        var error = assertThrows(MissingToolPrice.class, () -> calculate(
                estimate(Map.of(INPUT, 1L), Map.of(TOOL, d("1"))), price("1"),
                Map.of(otherChannel, new ToolPrice("v1", "synthetic-test-price", BigDecimal.ZERO),
                        otherUnit, new ToolPrice("v1", "synthetic-test-price", BigDecimal.ZERO))));
        assertEquals(TOOL, error.tool());
        assertThrows(ModelTokenPrice.MissingPrice.class, () -> calculate(
                estimate(Map.of(CACHED_INPUT, 1L), Map.of()), price("1"), Map.of()));
    }
    @Test void explicitFreePriceHasNoArtificialMinimumAndUnusedToolNeedsNoPrice() {
        assertEquals(0, calculate(estimate(Map.of(INPUT, 1L), Map.of(TOOL, d("0"))),
                price("0"), Map.of()).amountWen());
        assertEquals(0, calculate(estimate(Map.of(), Map.of(TOOL, d("1"))),
                price("0"), Map.of(TOOL, new ToolPrice("v1", "synthetic-test-price", BigDecimal.ZERO))).amountWen());
    }
    @Test void fractionalToolBillingQuantitiesRemainExact() {
        var quote = calculate(estimate(Map.of(), Map.of(TOOL, d("0.125"))), price("0"),
                Map.of(TOOL, new ToolPrice("v1", "synthetic-test-price", d("0.08"))));
        assertEquals(1, quote.amountWen());
        assertEquals(0, d("0.01").compareTo(quote.estimatedYuan()));
    }
    @Test void invalidQuantitiesAndMissingRuleCannotBecomeValidQuotes() {
        assertThrows(IllegalArgumentException.class, () -> estimate(Map.of(INPUT, -1L), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> estimate(Map.of(), Map.of(TOOL, d("-1"))));
        assertThrows(IllegalArgumentException.class, () -> new ToolPrice("v1", "synthetic-test-price", d("-1")));
        assertThrows(IllegalArgumentException.class, () -> new Estimate("", "v1", Map.of(), Map.of()));
        assertThrows(NullPointerException.class, () -> calculate(null, price("1"), Map.of()));
    }
}
