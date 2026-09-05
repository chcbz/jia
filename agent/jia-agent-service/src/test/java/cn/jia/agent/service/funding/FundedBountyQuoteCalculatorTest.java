package cn.jia.agent.service.funding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundedBountyQuoteCalculatorTest {
    @Test
    void fourTokenClassesUseIndependentCeilTermsWithoutCachedInputOverlap() {
        long fixed = FundedBountyPreviewPriceBook.FIXED_REQUEST_FEE_MICRO;
        assertEquals(fixed + 2, FundedBountyQuoteCalculator.tokenCost(tokens(1, 0, 0, 0), 0));
        assertEquals(fixed + 1, FundedBountyQuoteCalculator.tokenCost(tokens(0, 1, 0, 0), 0));
        assertEquals(fixed + 6, FundedBountyQuoteCalculator.tokenCost(tokens(0, 0, 1, 0), 0));
        assertEquals(fixed + 8, FundedBountyQuoteCalculator.tokenCost(tokens(0, 0, 0, 1), 0));
        assertEquals(fixed + 17, FundedBountyQuoteCalculator.tokenCost(tokens(1, 1, 1, 1), 0));
    }

    @Test
    void budgetCoverageIncludesWorstComputeFeeAndMinimumPayout() {
        FundedBountyQuoteCalculator.QuoteAmounts covered = FundedBountyQuoteCalculator.calculate(
                1_000_000_000L, 700_000_000L,
                FundedBountyPreviewPriceBook.ESTIMATED_TOKENS,
                FundedBountyPreviewPriceBook.WORST_TOKENS);
        assertTrue(covered.budgetCovered());
        assertTrue(covered.budgetHeadroomMicro() >= 0);

        FundedBountyQuoteCalculator.QuoteAmounts rejected = FundedBountyQuoteCalculator.calculate(
                10_000_000L, 10_000_000L,
                FundedBountyPreviewPriceBook.ESTIMATED_TOKENS,
                FundedBountyPreviewPriceBook.WORST_TOKENS);
        assertFalse(rejected.budgetCovered());
        assertEquals(0L, rejected.budgetHeadroomMicro());
    }

    @Test
    void intermediateAndResultOverflowFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                FundedBountyQuoteCalculator.tokenCost(tokens(Long.MAX_VALUE, 0, 0, 0), 0));
    }

    private static FundedBountyPreviewPriceBook.TokenClasses tokens(
            long input, long cached, long output, long reasoning) {
        return new FundedBountyPreviewPriceBook.TokenClasses(input, cached, output, reasoning);
    }
}
