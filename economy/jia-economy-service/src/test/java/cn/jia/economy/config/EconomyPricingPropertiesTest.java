package cn.jia.economy.config;

import cn.jia.economy.pricing.*;
import cn.jia.economy.service.impl.ConfiguredTaskPricingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static cn.jia.economy.pricing.TaskWorkload.Complexity.*;
import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyPricingPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EconomyPricingProperties.class)
    @Import(ConfiguredTaskPricingService.class)
    static class Config { }
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner().withUserConfiguration(Config.class);
    private static final TaskPricingService.ModelKey MODEL = new TaskPricingService.ModelKey("channel-a", "model-a", "standard");
    private static final TaskWorkload WORK = new TaskWorkload("analysis", STANDARD, 2, 1, Map.of());

    @Test void noConfigurationStartsWithoutImplicitPricesOrRules() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(EconomyPricingProperties.class).models().isEmpty());
            assertEquals(ESTIMATE_RULE_MISSING, assertThrows(TaskPricingService.Unavailable.class,
                    () -> context.getBean(TaskPricingService.class).prepare(WORK, MODEL)).reason());
        });
    }
    @Test void bindsRmbDecimalsAndRuleBasisFromExistingConfigurationMechanism() {
        RUNNER.withPropertyValues(
                "economy.pricing.models[0].channel=channel-a", "economy.pricing.models[0].model=model-a",
                "economy.pricing.models[0].tier=standard", "economy.pricing.models[0].version=manual-v1",
                "economy.pricing.models[0].basis=synthetic-test-price",
                "economy.pricing.models[0].yuan-per-million-tokens.input=0.668790",
                "economy.pricing.models[0].yuan-per-million-tokens.output=3.343950",
                "economy.pricing.rules[0].id=analysis-standard", "economy.pricing.rules[0].version=test-rule-v1",
                "economy.pricing.rules[0].basis=synthetic-test-coefficients", "economy.pricing.rules[0].kind=analysis",
                "economy.pricing.rules[0].complexity=STANDARD", "economy.pricing.rules[0].base-tokens.input=100",
                "economy.pricing.rules[0].per-input-file-tokens.input=10",
                "economy.pricing.rules[0].per-deliverable-tokens.output=20",
                "economy.pricing.tools[0].channel=tool-channel", "economy.pricing.tools[0].tool=render",
                "economy.pricing.tools[0].unit=image", "economy.pricing.tools[0].version=tool-v1",
                "economy.pricing.tools[0].basis=synthetic-test-price", "economy.pricing.tools[0].yuan-per-unit=0.01")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var prepared = context.getBean(TaskPricingService.class).prepare(WORK, MODEL);
                    assertEquals(Map.of(INPUT, 120L, OUTPUT, 20L), prepared.estimate().tokens());
                    assertEquals(new BigDecimal("0.668790"), prepared.modelPrice().yuanPerMillionTokens().get(INPUT));
                    assertEquals("synthetic-test-coefficients", prepared.rule().basis());
                    assertEquals("synthetic-test-price", prepared.modelPrice().basis());
                    assertEquals(1L, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
                    var tool = context.getBean(EconomyPricingProperties.class).tools().getFirst();
                    assertEquals(new BigDecimal("0.01"), tool.price().yuanPerUnit());
                });
    }
    @Test void sameModelDifferentChannelOrTierNeverSubstitutesPrice() {
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of(), List.of(rule()));
        for (var route : List.of(new TaskPricingService.ModelKey("channel-b", "model-a", "standard"),
                new TaskPricingService.ModelKey("channel-a", "model-a", "batch"))) {
            assertEquals(MODEL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(WORK, route)).reason());
        }
        assertNotNull(service.prepare(WORK, MODEL));
    }
    @Test void missingTokenCategoryIsNotFreeButExplicitZeroIsValid() {
        var missing = service(List.of(price("channel-a", "standard", Map.of(OUTPUT, BigDecimal.ZERO))), List.of(), List.of(rule()));
        assertEquals(TOKEN_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> missing.prepare(WORK, MODEL)).reason());
        var zero = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ZERO))), List.of(), List.of(rule()));
        var p = zero.prepare(WORK, MODEL);
        assertEquals(0L, TaskQuoteCalculator.calculate(p.estimate(), p.modelPrice(), p.toolPrices()).amountWen());
    }
    @Test void missingBillableToolIsCandidateLocalAndZeroQuantityNeedsNoPrice() {
        var key = new TaskQuoteCalculator.ToolKey("tool-channel", "render", "image");
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of(), List.of(rule()));
        assertEquals(TOOL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(
                new TaskWorkload("analysis", STANDARD, 0, 1, Map.of(key, BigDecimal.ONE)), MODEL)).reason());
        assertTrue(service.prepare(new TaskWorkload("analysis", STANDARD, 0, 1, Map.of(key, BigDecimal.ZERO)), MODEL).toolPrices().isEmpty());
        assertNotNull(service.prepare(WORK, MODEL));
    }
    @Test void duplicateCurrentRoutesAndRulesCannotSilentlyPickFirstOrLast() {
        var p = price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE));
        var tool = new EconomyPricingProperties.ToolEntry("tools", "render", "image", "v1", "test", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service(List.of(p, p), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service(List.of(), List.of(tool, tool), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service(List.of(), List.of(), List.of(rule(), rule())));
    }
    @Test void overflowHasExplicitUnavailableStatusRatherThanZeroOrSaturation() {
        var rule = new TaskEstimateRule("overflow", "v1", "synthetic", "analysis", STANDARD,
                Map.of(INPUT, 0L), Map.of(INPUT, Long.MAX_VALUE), Map.of());
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of(), List.of(rule));
        assertEquals(ESTIMATE_OVERFLOW, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(WORK, MODEL)).reason());
    }
    @Test void changedCatalogDoesNotMutatePreviouslyPreparedSnapshot() {
        var before = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of(), List.of(rule())).prepare(WORK, MODEL);
        var after = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.TEN))), List.of(), List.of(rule())).prepare(WORK, MODEL);
        assertEquals(BigDecimal.ONE, before.modelPrice().yuanPerMillionTokens().get(INPUT));
        assertEquals(BigDecimal.TEN, after.modelPrice().yuanPerMillionTokens().get(INPUT));
    }
    private static ConfiguredTaskPricingService service(List<ModelTokenPrice> models,
            List<EconomyPricingProperties.ToolEntry> tools, List<TaskEstimateRule> rules) {
        return new ConfiguredTaskPricingService(new EconomyPricingProperties(models, tools, rules));
    }
    private static TaskEstimateRule rule() {
        return new TaskEstimateRule("analysis-standard", "v1", "synthetic-test-rule", "analysis", STANDARD, Map.of(INPUT, 100L), Map.of(), Map.of());
    }
    private static ModelTokenPrice price(String channel, String tier, Map<ModelTokenPrice.Category, BigDecimal> rates) {
        return new ModelTokenPrice(channel, "model-a", tier, "v1", "synthetic-test-price", rates);
    }
}
