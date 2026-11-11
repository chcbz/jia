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
    private final cn.jia.agent.dao.AgentPersonaBindingDao bindings;
    private final cn.jia.economy.hosting.HostingRentRenewalService renewal;
    private final UsageCostService costs;
    private final ModelPriceCatalog prices;
    public PlatformKnowledgeUsageRecorder(HostingRentFinishingMapper relationships, UsageCostService costs, ModelPriceCatalog prices,
            cn.jia.agent.dao.AgentPersonaBindingDao bindings, cn.jia.economy.hosting.HostingRentRenewalService renewal) {
        this.relationships = relationships; this.costs = costs; this.prices = prices;
        this.bindings = bindings; this.renewal = renewal;
    }
    /** Caller owns the authenticated installation lock, then binding -> intent -> lease. */
    public UsageCostService.Receipt record(cn.jia.agent.entity.AgentRuntimeV1InstallationView installation,
                                           String leaseId, String handoverId, Evidence evidence) {
        var scope = authorizeMaintenance(installation, leaseId, handoverId);
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
    private AuthenticatedRuntimeOperation.Scope authorizeMaintenance(
            cn.jia.agent.entity.AgentRuntimeV1InstallationView installation, String leaseId, String handoverId) {
        var partition = new EconomyScope(installation.tenantId(), installation.clientId());
        var discovered = relationships.findFinishingBinding(partition.tenantId(), partition.clientId(), leaseId);
        require(discovered != null && discovered.getId() != null);
        var binding = bindings.findByIdForUpdate(discovered.getId());
        require(binding != null && Integer.valueOf(1).equals(binding.getStatus())
                && Objects.equals(partition.tenantId(), binding.getTenantId())
                && Objects.equals(partition.clientId(), binding.getClientId())
                && Objects.equals(installation.canonicalAgentId(), binding.getAgentId()));
        var initial = renewal.initialHandoverIntent(partition, leaseId);
        require(initial != null);
        var financial = renewal.lockHandover(partition, initial.getIntentId(), leaseId);
        var intent = financial.intent(); var lease = financial.lease();
        require(intent != null && lease != null && "ACTIVE".equals(installation.status())
                && "INITIAL".equals(intent.getQuotePurpose()) && "ACTIVE".equals(intent.getStatus())
                && "USER".equals(intent.getPrincipalType()) && intent.getRefundTransactionId() == null
                && Objects.equals(installation.installationId(), intent.getRuntimeInstallationId())
                && Objects.equals(installation.manifestSha256(), intent.getRuntimeManifestSha256())
                && Objects.equals(leaseId, intent.getLeaseId()) && Objects.equals(binding.getAgentId(), intent.getAgentId())
                && "FINISHING".equals(lease.getStatus()) && lease.getExecutorStoppedAt() != null
                && Objects.equals(handoverId, lease.getHandoverId())
                && Objects.equals(leaseId, lease.getLeaseId())
                && Objects.equals(String.valueOf(binding.getId()), lease.getBindingId())
                && Objects.equals(binding.getAgentId(), lease.getAgentId())
                && Objects.equals(binding.getPersonaCode(), lease.getPersonaCode())
                && Objects.equals(intent.getPrincipalType(), lease.getPrincipalType())
                && Objects.equals(intent.getPrincipalId(), lease.getPrincipalId()));
        return new AuthenticatedRuntimeOperation.Scope(partition.tenantId(), partition.clientId(),
                binding.getJiacn(), binding.getAgentId());
    }
    private static void require(boolean valid) {
        if (!valid) throw new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
    }
}
