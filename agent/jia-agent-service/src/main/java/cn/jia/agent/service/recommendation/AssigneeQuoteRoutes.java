package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AssigneeRecommendation.Workload;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.pricing.TaskWorkload;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Operator-registered billing identity, never inferred from display models or skill/provider names. */
@Component
@EnableConfigurationProperties(AssigneeQuoteRoutes.Properties.class)
public final class AssigneeQuoteRoutes {
    @ConfigurationProperties(prefix = "jia.agent.task-pricing")
    public record Properties(List<Route> routes) {
        public Properties { routes = routes == null ? List.of() : List.copyOf(routes); }
    }
    public record Tool(String channel, String tool, String unit, BigDecimal unitsPerDeliverable) {
        public Tool {
            new TaskQuoteCalculator.ToolKey(channel, tool, unit);
            if (unitsPerDeliverable == null || unitsPerDeliverable.signum() < 0)
                throw new IllegalArgumentException("Tool estimate must be explicitly nonnegative");
        }
        TaskQuoteCalculator.ToolKey key() { return new TaskQuoteCalculator.ToolKey(channel, tool, unit); }
    }
    public record Route(String agentId, Workload.Kind kind, String channel, String model, String tier, List<Tool> tools) {
        public Route {
            if (agentId == null || agentId.isBlank() || channel == null || channel.isBlank()
                    || model == null || model.isBlank() || tier == null || tier.isBlank())
                throw new IllegalArgumentException("Explicit billing route identity required");
            Objects.requireNonNull(kind, "kind");
            tools = List.copyOf(Objects.requireNonNull(tools, "Explicit billable tool list required"));
            if (tools.stream().map(Tool::key).distinct().count() != tools.size())
                throw new IllegalArgumentException("Duplicate tool estimate");
        }
        public TaskPricingService.ModelKey modelKey() { return new TaskPricingService.ModelKey(channel, model, tier); }
        public TaskWorkload workload(Workload judgement, long inputFiles) {
            var units = new HashMap<TaskQuoteCalculator.ToolKey, BigDecimal>();
            for (var tool : tools) units.put(tool.key(), tool.unitsPerDeliverable().multiply(BigDecimal.valueOf(judgement.deliverables())));
            return new TaskWorkload(judgement.kind().name(), TaskWorkload.Complexity.valueOf(judgement.complexity().name()),
                    inputFiles, judgement.deliverables(), units);
        }
    }
    private record Key(String agentId, Workload.Kind kind) { }
    private final Map<Key, Route> routes;
    public AssigneeQuoteRoutes(Properties properties) {
        var index = new HashMap<Key, Route>();
        for (var route : properties.routes()) if (index.putIfAbsent(new Key(route.agentId(), route.kind()), route) != null)
            throw new IllegalArgumentException("Duplicate current billing route");
        routes = Map.copyOf(index);
    }
    public Route find(String agentId, Workload.Kind kind) { return routes.get(new Key(agentId, kind)); }
}
