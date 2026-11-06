package cn.jia.chat.service.recommendation;

import cn.jia.agent.service.AgentAssigneeRecommendationModel.Context;
import cn.jia.chat.service.usage.OpenAiUsageEvidence;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.chat.completions.ChatCompletion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Records the source attempt before any paid call. No business transaction, wallet mutation,
 * provider replay, background thread, or estimation. A crash/transport error remains visible
 * as PENDING_USAGE; it is never retried or interpreted as free to repair accounting.
 */
@Component
public final class RecommendationCostRecorder {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Attempt(UsageCostService.Binding binding, ModelTokenPrice price, long revision) { }
    private final UsageCostService costs;
    private final ModelPriceCatalog prices;
    private final String channel;
    private final String expectedTier;

    public RecommendationCostRecorder(UsageCostService costs, ModelPriceCatalog prices,
            @Value("${jia.chat.recommendation.usage-channel:}") String channel,
            @Value("${jia.chat.recommendation.usage-tier:}") String expectedTier) {
        this.costs = costs;
        this.prices = prices;
        this.channel = channel.strip();
        this.expectedTier = expectedTier.strip();
    }

    public Attempt begin(Context context, String requestedModel) {
        var binding = new UsageCostService.Binding(new EconomyScope(context.tenantId(), context.clientId()),
                context.ownerJiacn(), context.taskId(), "ASSIGNEE_RECOMMENDATION", UUID.randomUUID().toString());
        var price = prices.find(channel, requestedModel, expectedTier).orElse(null);
        var raw = new LinkedHashMap<String, Object>();
        raw.put("phase", "STARTING"); raw.put("requestedModel", requestedModel);
        raw.put("requestedChannel", channel); raw.put("requestedTier", expectedTier);
        var receipt = costs.record(new UsageCostService.Observation(binding, "started", 0, json(raw),
                null, price, System.currentTimeMillis()));
        return new Attempt(binding, price, receipt.revision());
    }

    /** Save native usage before validating business JSON; an invalid answer can still cost money.
     * The price remains the pre-call snapshot even if configuration changes during the call.
     */
    public void complete(Attempt attempt, ChatCompletion response) {
        var evidence = OpenAiUsageEvidence.capture(response, channel);
        var raw = new LinkedHashMap<String, Object>();
        raw.put("phase", "RESPONDED"); raw.put("providerReceiptId", evidence.providerReceiptId());
        raw.put("actualModel", evidence.actualModel()); raw.put("serviceTier", evidence.actualTier());
        raw.put("usage", evidence.rawUsageJson());
        costs.record(new UsageCostService.Observation(attempt.binding(), "responded", attempt.revision(),
                json(raw), evidence.normalizedUsage(), attempt.price(), System.currentTimeMillis()));
    }

    private static String json(Map<String, Object> value) {
        try { return JSON.writeValueAsString(value); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Cannot serialize usage receipt", invalid); }
    }
}
