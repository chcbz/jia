package cn.jia.agent.service.usage;

import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import static cn.jia.economy.usage.UsageCostConflict.Reason.*;

/** Trusted stopped-Runtime maintenance source. Original relationship grants evidence
 * access, never user billing. STARTED acknowledgement precedes the external model call. */
@Service
public final class PlatformKnowledgeUsageRecorder {
    public enum Phase { STARTED, RESPONDED }
    public record Evidence(String batchId, String stage, Phase phase, String channel,
                           String requestedModel, String requestedTier, JsonNode evidence) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HostingRentFinishingMapper relationships;
    private final UsageCostService costs;
    private final ModelPriceCatalog prices;
    public PlatformKnowledgeUsageRecorder(HostingRentFinishingMapper relationships, UsageCostService costs, ModelPriceCatalog prices) {
        this.relationships = relationships; this.costs = costs; this.prices = prices;
    }
    public UsageCostService.Receipt record(AuthenticatedRuntimeOperation.Scope scope, String leaseId,
                                           String handoverId, Evidence evidence) {
        var relationship = relationships.findFinishingBinding(scope.tenantId(), scope.clientId(), leaseId);
        if (relationship == null || !Objects.equals(scope.ownerJiacn(), relationship.getJiacn())
                || !Objects.equals(scope.agentId(), relationship.getAgentId()))
            throw new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        var binding = UsageCostService.platformBinding(new EconomyScope(scope.tenantId(), scope.clientId()),
                "PUBLIC_KNOWLEDGE_EXTRACTION", JSON.writeValueAsString(List.of(scope.agentId(), leaseId,
                        handoverId, evidence.batchId(), evidence.stage())));
        boolean starting = evidence.phase() == Phase.STARTED;
        String receiptId = starting ? "started" : "responded";
        String raw = JSON.writeValueAsString(evidence);
        var replay = costs.observation(binding, receiptId);
        if (replay.isPresent()) {
            var saved = replay.get();
            return costs.record(new UsageCostService.Observation(binding, receiptId, saved.expectedRevision(), raw,
                    saved.normalizedUsage(), saved.price(), 0));
        }
        ModelTokenPrice price;
        if (starting) price = prices.find(evidence.channel(), evidence.requestedModel(), evidence.requestedTier()).orElse(null);
        else {
            var start = costs.observation(binding, "started").orElseThrow(() -> new UsageCostConflict(REVISION));
            var original = JSON.readValue(start.rawUsageJson(), Evidence.class);
            if (!Objects.equals(original.channel(), evidence.channel())
                    || !Objects.equals(original.requestedModel(), evidence.requestedModel())
                    || !Objects.equals(original.requestedTier(), evidence.requestedTier()))
                throw new UsageCostConflict(ATTEMPT_BINDING);
            price = start.price(); // including null: late current tariffs cannot silently price an earlier call
        }
        return costs.record(new UsageCostService.Observation(binding, receiptId, starting ? 0 : 1, raw,
                starting ? null : ResponsesUsageNormalizer.normalize(evidence.channel(), evidence.evidence()), price, 0));
    }
}
