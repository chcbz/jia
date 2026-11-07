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
import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class EconomyPricingPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EconomyPricingProperties.class)
    @Import(ConfiguredTaskPricingService.class)
    static class Config { }
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner().withUserConfiguration(Config.class);
    private static final TaskPricingService.ModelKey MODEL = new TaskPricingService.ModelKey("channel-a", "model-a", "standard");
    private static final TaskWorkload WORK = new TaskWorkload("analysis", 100, 0, 1, Map.of());

    @Test void noConfigurationStartsWithoutImplicitPrices() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBean(EconomyPricingProperties.class).models().isEmpty());
            assertTrue(context.getBean(ModelPriceCatalog.class).find("channel-a", "model-a", "standard").isEmpty());
            assertEquals(MODEL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class,
                    () -> context.getBean(TaskPricingService.class).prepare(WORK, MODEL)).reason());
        });
    }
    @Test void bindsRmbDecimalsAndPriceBasisFromExistingConfigurationMechanism() {
        RUNNER.withPropertyValues(
                "economy.pricing.models[0].channel=channel-a", "economy.pricing.models[0].model=model-a",
                "economy.pricing.models[0].tier=standard", "economy.pricing.models[0].version=manual-v1",
                "economy.pricing.models[0].basis=synthetic-test-price",
                "economy.pricing.models[0].yuan-per-million-tokens.input=0.668790",
                "economy.pricing.models[0].yuan-per-million-tokens.output=3.343950",
                "economy.pricing.models[0].short-context-max-input-tokens=100",
                "economy.pricing.models[0].long-context-yuan-per-million-tokens.input=1.337580",
                "economy.pricing.models[0].long-context-yuan-per-million-tokens.output=5.015925",
                "economy.pricing.tools[0].channel=tool-channel", "economy.pricing.tools[0].tool=render",
                "economy.pricing.tools[0].unit=image", "economy.pricing.tools[0].version=tool-v1",
                "economy.pricing.tools[0].basis=synthetic-test-price", "economy.pricing.tools[0].yuan-per-unit=0.01")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var prepared = context.getBean(TaskPricingService.class).prepare(new TaskWorkload("analysis", 120, 20, 1, Map.of()), MODEL);
                    assertEquals(Map.of(INPUT, 120L, OUTPUT, 20L), prepared.estimate().tokens());
                    assertEquals(new BigDecimal("0.668790"), prepared.modelPrice().yuanPerMillionTokens().get(INPUT));
                    assertEquals("synthetic-test-price", prepared.modelPrice().basis());
                    assertEquals(100L, prepared.modelPrice().shortContextMaxInputTokens());
                    assertEquals(new BigDecimal("1.337580"), prepared.modelPrice().longContextYuanPerMillionTokens().get(INPUT));
                    assertTrue(prepared.modelPrice().cost(prepared.estimate().tokens()).compareTo(
                            prepared.modelPrice().estimateCost(prepared.estimate().tokens())) > 0);
                    assertEquals(1L, TaskQuoteCalculator.calculate(prepared.estimate(), prepared.modelPrice(), prepared.toolPrices()).amountWen());
                    var tool = context.getBean(EconomyPricingProperties.class).tools().getFirst();
                    assertEquals(new BigDecimal("0.01"), tool.price().yuanPerUnit());
                });
    }
    @Test void sameModelDifferentChannelOrTierNeverSubstitutesPrice() {
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of());
        for (var route : List.of(new TaskPricingService.ModelKey("channel-b", "model-a", "standard"),
                new TaskPricingService.ModelKey("channel-a", "model-a", "batch"))) {
            assertEquals(MODEL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(WORK, route)).reason());
        }
        assertNotNull(service.prepare(WORK, MODEL));
    }
    @Test void missingTokenCategoryIsNotFreeButExplicitZeroIsValid() {
        var missing = service(List.of(price("channel-a", "standard", Map.of(OUTPUT, BigDecimal.ZERO))), List.of());
        assertEquals(TOKEN_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> missing.prepare(WORK, MODEL)).reason());
        var zero = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ZERO))), List.of());
        var p = zero.prepare(WORK, MODEL);
        assertEquals(0L, TaskQuoteCalculator.calculate(p.estimate(), p.modelPrice(), p.toolPrices()).amountWen());
    }
    @Test void missingBillableToolIsCandidateLocalAndZeroQuantityNeedsNoPrice() {
        var key = new TaskQuoteCalculator.ToolKey("tool-channel", "render", "image");
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of());
        assertEquals(TOOL_PRICE_MISSING, assertThrows(TaskPricingService.Unavailable.class, () -> service.prepare(
                new TaskWorkload("analysis", 100, 0, 1, Map.of(key, BigDecimal.ONE)), MODEL)).reason());
        assertTrue(service.prepare(new TaskWorkload("analysis", 100, 0, 1, Map.of(key, BigDecimal.ZERO)), MODEL).toolPrices().isEmpty());
        assertNotNull(service.prepare(WORK, MODEL));
    }
    @Test void duplicateCurrentPriceRoutesCannotSilentlyPickFirstOrLast() {
        var p = price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE));
        var tool = new EconomyPricingProperties.ToolEntry("tools", "render", "image", "v1", "test", BigDecimal.ZERO);
        assertThrows(IllegalArgumentException.class, () -> service(List.of(p, p), List.of()));
        assertThrows(IllegalArgumentException.class, () -> service(List.of(), List.of(tool, tool)));
    }
    @Test void noConfiguredWorkloadRuleIsNeededAndEstimatedTokensAreUsedExactly() {
        var service = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of());
        var prepared = service.prepare(new TaskWorkload("arbitrary-kind", 987654, 0, 9, Map.of()), MODEL);
        assertEquals(987654L, prepared.estimate().tokens().get(INPUT));
        assertEquals("MODEL_TOKEN_ESTIMATE", prepared.estimate().ruleId());
    }
    @Test void changedCatalogDoesNotMutatePreviouslyPreparedSnapshot() {
        var before = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE))), List.of()).prepare(WORK, MODEL);
        var after = service(List.of(price("channel-a", "standard", Map.of(INPUT, BigDecimal.TEN))), List.of()).prepare(WORK, MODEL);
        assertEquals(BigDecimal.ONE, before.modelPrice().yuanPerMillionTokens().get(INPUT));
        assertEquals(BigDecimal.TEN, after.modelPrice().yuanPerMillionTokens().get(INPUT));
    }
    @Test void costCatalogDoesNotRequireTaskWorkloadOrEstimationRules() {
        var snapshot = price("channel-a", "standard", Map.of(INPUT, BigDecimal.ONE));
        ModelPriceCatalog catalog = service(List.of(snapshot), List.of());
        assertSame(snapshot, catalog.find("channel-a", "model-a", "standard").orElseThrow());
        assertTrue(catalog.find("channel-b", "model-a", "standard").isEmpty());
        assertTrue(catalog.find("channel-a", "other-model", "standard").isEmpty());
        assertTrue(catalog.find("channel-a", "model-a", "batch").isEmpty());
        assertTrue(catalog.find(null, "model-a", "standard").isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.yuanPerMillionTokens().put(INPUT, BigDecimal.TEN));
    }

    private static ConfiguredTaskPricingService service(List<ModelTokenPrice> models,
            List<EconomyPricingProperties.ToolEntry> tools) {
        return new ConfiguredTaskPricingService(new EconomyPricingProperties(models, tools));
    }
    private static ModelTokenPrice price(String channel, String tier, Map<ModelTokenPrice.Category, BigDecimal> rates) {
        return new ModelTokenPrice(channel, "model-a", tier, "v1", "synthetic-test-price", rates, null, Map.of());
    }
}
