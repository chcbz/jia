package cn.jia.economy.service.impl;

import cn.jia.economy.config.EconomyPricingProperties;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.pricing.TaskWorkload;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static cn.jia.economy.pricing.TaskPricingService.Unavailable.Reason.*;

/** Build exact-key indexes once. A request resolves only its model and requested billable tools. */
@Service
public final class ConfiguredTaskPricingService implements TaskPricingService, ModelPriceCatalog {
    private final Map<ModelKey, ModelTokenPrice> models;
    private final Map<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice> tools;

    public ConfiguredTaskPricingService(EconomyPricingProperties properties) {
        var models = new HashMap<ModelKey, ModelTokenPrice>();
        for (var price : properties.models()) {
            if (models.putIfAbsent(new ModelKey(price.channel(), price.model(), price.tier()), price) != null)
                throw new IllegalArgumentException("Duplicate current model price route");
        }
        var tools = new HashMap<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice>();
        for (var entry : properties.tools()) {
            if (tools.putIfAbsent(entry.key(), entry.price()) != null)
                throw new IllegalArgumentException("Duplicate current tool price route");
        }
        this.models = Map.copyOf(models);
        this.tools = Map.copyOf(tools);
    }

    @Override
    public java.util.Optional<ModelTokenPrice> find(String channel, String model, String tier) {
        return java.util.Optional.ofNullable(models.get(new ModelKey(channel, model, tier)));
    }

    @Override
    public PreparedQuote prepare(TaskWorkload workload, ModelKey model) {
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(model, "model");
        var price = models.get(model);
        if (price == null) throw new Unavailable(MODEL_PRICE_MISSING);
        var estimate = workload.estimate();
        for (var entry : estimate.tokens().entrySet()) {
            if (entry.getValue() > 0 && !price.yuanPerMillionTokens().containsKey(entry.getKey()))
                throw new Unavailable(TOKEN_PRICE_MISSING);
        }
        var selectedTools = new HashMap<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice>();
        for (var entry : workload.toolUnits().entrySet()) {
            if (entry.getValue().signum() == 0) continue;
            var toolPrice = tools.get(entry.getKey());
            if (toolPrice == null) throw new Unavailable(TOOL_PRICE_MISSING);
            selectedTools.put(entry.getKey(), toolPrice);
        }
        return new PreparedQuote(estimate, price, selectedTools, workload);
    }
}
