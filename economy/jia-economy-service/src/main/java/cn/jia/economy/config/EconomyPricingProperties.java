package cn.jia.economy.config;

import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.math.BigDecimal;
import java.util.List;

/** Manually registered RMB model and tool prices. No currency conversion or defaults. */
@ConfigurationProperties(prefix = "economy.pricing")
public record EconomyPricingProperties(List<ModelTokenPrice> models, List<ToolEntry> tools) {
    public EconomyPricingProperties {
        models = models == null ? List.of() : List.copyOf(models);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
    public record ToolEntry(String channel, String tool, String unit, String version, String basis, BigDecimal yuanPerUnit) {
        public ToolEntry {
            new TaskQuoteCalculator.ToolKey(channel, tool, unit);
            new TaskQuoteCalculator.ToolPrice(version, basis, yuanPerUnit);
        }
        public TaskQuoteCalculator.ToolKey key() { return new TaskQuoteCalculator.ToolKey(channel, tool, unit); }
        public TaskQuoteCalculator.ToolPrice price() { return new TaskQuoteCalculator.ToolPrice(version, basis, yuanPerUnit); }
    }
}
