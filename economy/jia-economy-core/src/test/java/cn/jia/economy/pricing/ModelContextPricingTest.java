package cn.jia.economy.pricing;

import cn.jia.economy.usage.TokenUsageCost;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic CNY rates: verify applicability, not a claim about a live provider's price. */
class ModelContextPricingTest {
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static ModelTokenPrice price(Map<ModelTokenPrice.Category, BigDecimal> longer) {
        return new ModelTokenPrice("channel", "model", "standard", "v1", "synthetic-cny-context-prices",
                Map.of(INPUT,d("1"),CACHED_INPUT,d("0.1"),OUTPUT,d("2")),100L,longer);
    }
    private static ModelTokenPrice fullPrice() { return price(Map.of(INPUT,d("3"),CACHED_INPUT,d("0.5"),OUTPUT,d("4"))); }
    @Test void exactThresholdUsesBaseAndOutputDoesNotCountAsInput() {
        assertEquals(d("2.000064"), CnyAmounts.recordedCost(fullPrice().cost(Map.of(INPUT,60L,CACHED_INPUT,40L,OUTPUT,1000000L))));
    }
    @Test void cachedInputCrossesThresholdAndAllCategoriesUseLongPriceNotMarginalPrice() {
        assertEquals(d("0.000243"), CnyAmounts.recordedCost(fullPrice().cost(Map.of(INPUT,61L,CACHED_INPUT,40L,OUTPUT,10L))));
        assertEquals(d("0.000343"), CnyAmounts.recordedCost(fullPrice().cost(Map.of(INPUT,101L,OUTPUT,10L))));
        assertDoesNotThrow(() -> fullPrice().cost(Map.of(INPUT,Long.MAX_VALUE,CACHED_INPUT,Long.MAX_VALUE)));
    }
    @Test void cacheWritesAreInputWhenTheSourceHasVerifiedDisjointCategories() {
        var price=price(Map.of(INPUT,d("3"),CACHE_WRITE_30M,d("5"),CACHE_WRITE_12H,d("7")));
        assertEquals(d("0.000329"), CnyAmounts.recordedCost(price.cost(Map.of(INPUT,90L,CACHE_WRITE_30M,9L,CACHE_WRITE_12H,2L))));
    }
    @Test void absentLongRateIsPendingPriceAndNeverFallsBackToBaseOrZero() {
        var usage=new TokenUsageCost.Usage("channel","model","standard","fixture-disjoint",Map.of(INPUT,101L,OUTPUT,1L));
        for(var missing:new ModelTokenPrice[]{price(Map.of()),price(Map.of(INPUT,d("3")))}) {
            var result=TokenUsageCost.assess(usage,missing);
            assertEquals(TokenUsageCost.Status.PENDING_PRICE,result.status());assertNull(result.costYuan());assertSame(missing,result.price());
        }
        var result=TokenUsageCost.assess(usage,fullPrice());
        assertEquals(TokenUsageCost.Status.PRICED,result.status());assertEquals(d("0.000307"),result.costYuan());
    }
    @Test void wholeTaskEstimateCannotPretendItsSumIsOneLongContextRequest() {
        var estimate=new TaskQuoteCalculator.Estimate("rule","v1",Map.of(INPUT,101L,OUTPUT,10L),Map.of());
        var quote=TaskQuoteCalculator.calculate(estimate,fullPrice(),Map.of());
        assertEquals(d("0.000121"),quote.estimatedYuan());assertEquals(1,quote.amountWen());
        assertEquals(d("0.000343"),fullPrice().cost(estimate.tokens()));
    }
    @Test void retainedPriceSnapshotIncludesBothBandsAndRejectsUnscopedLongRates() {
        var rates=new EnumMap<ModelTokenPrice.Category,BigDecimal>(ModelTokenPrice.Category.class);
        rates.put(INPUT,d("3"));var snapshot=price(rates);rates.put(INPUT,d("999"));
        assertEquals(d("0.000303"),snapshot.cost(Map.of(INPUT,101L)));
        assertThrows(UnsupportedOperationException.class,()->snapshot.longContextYuanPerMillionTokens().put(INPUT,d("0")));
        assertThrows(IllegalArgumentException.class,()->new ModelTokenPrice("c","m","t","v","basis",Map.of(INPUT,d("1")),null,rates));
        assertThrows(IllegalArgumentException.class,()->new ModelTokenPrice("c","m","t","v","basis",Map.of(INPUT,d("1")),0L,Map.of()));
    }
}
