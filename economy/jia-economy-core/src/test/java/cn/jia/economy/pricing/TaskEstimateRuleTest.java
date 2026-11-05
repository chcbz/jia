package cn.jia.economy.pricing;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static cn.jia.economy.pricing.TaskWorkload.Complexity.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskEstimateRuleTest {
    @Test void coefficientsProduceWholeTaskTokenEstimateWithoutRoundingOrMarkup() {
        var rule = new TaskEstimateRule("analysis", "v1", "synthetic-test-only", "analysis", STANDARD,
                Map.of(INPUT, 100L, OUTPUT, 10L), Map.of(INPUT, 25L), Map.of(OUTPUT, 20L));
        var estimate = rule.estimate(new TaskWorkload("analysis", STANDARD, 3, 2, Map.of()));
        assertEquals(Map.of(INPUT, 175L, OUTPUT, 50L), estimate.tokens());
        assertEquals("v1", estimate.ruleVersion());
    }
    @Test void zeroIsExplicitAndAbsentVariableCoefficientsMeanNoVariableTerm() {
        var rule = new TaskEstimateRule("zero", "v1", "synthetic-test-only", "analysis", SIMPLE, Map.of(INPUT, 0L), null, null);
        assertTrue(rule.estimate(new TaskWorkload("analysis", SIMPLE, 0, 1, Map.of())).tokens().isEmpty());
        assertThrows(NullPointerException.class, () -> new TaskEstimateRule("missing", "v1", "test", "analysis", SIMPLE, null, null, null));
    }
    @Test void invalidWorkloadAndNegativeCoefficientsAreNotBillableEstimates() {
        assertThrows(IllegalArgumentException.class, () -> new TaskWorkload("analysis", STANDARD, -1, 1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskWorkload("analysis", STANDARD, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new TaskEstimateRule("bad", "v1", "test", "analysis", SIMPLE, Map.of(INPUT, -1L), null, null));
    }
    @Test void overflowIsNotSaturatedOrWrappedIntoFree() {
        var rule = new TaskEstimateRule("big", "v1", "test", "analysis", COMPLEX,
                Map.of(INPUT, 0L), Map.of(INPUT, Long.MAX_VALUE), null);
        assertThrows(ArithmeticException.class, () -> rule.estimate(new TaskWorkload("analysis", COMPLEX, 2, 1, Map.of())));
    }
    @Test void ruleCannotBeAppliedToAnotherKindOrComplexity() {
        var rule = new TaskEstimateRule("rule", "v1", "test", "analysis", SIMPLE, Map.of(INPUT, 1L), null, null);
        assertThrows(IllegalArgumentException.class, () -> rule.estimate(new TaskWorkload("code", SIMPLE, 0, 1, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> rule.estimate(new TaskWorkload("analysis", COMPLEX, 0, 1, Map.of())));
    }
    @Test void coefficientSnapshotsDoNotAliasConfigurationMaps() {
        var base = new HashMap<>(Map.of(INPUT, 1L));
        var rule = new TaskEstimateRule("rule", "v1", "test", "analysis", SIMPLE, base, null, null);
        base.put(INPUT, 999L);
        assertEquals(1L, rule.baseTokens().get(INPUT));
    }
}
