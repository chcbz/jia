package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Child provider invocations use the original execution authority and the existing cost ledger.
 * Price and normalizer snapshots are reused on ACK loss; changing tariffs cannot reprice a retry. */
@Service
public final class NativeProviderUsageCostRecorder {
    public record Evidence(int schemaVersion, String invocationId, String protocol, String channel, String tier,
            String priceVersion, String requestedModel, String operation, NativeExecutionUsageCostRecorder.Phase phase,
            long observedAt, JsonNode evidence) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PersonalWorkspaceExecutionDao executions;
    private final UsageCostService costs;
    private final ModelPriceCatalog prices;
    public NativeProviderUsageCostRecorder(PersonalWorkspaceExecutionDao executions, UsageCostService costs, ModelPriceCatalog prices) {
        this.executions = executions; this.costs = costs; this.prices = prices;
    }
    public UsageCostService.Receipt record(PersonalWorkspaceExecutionService.RuntimeScope scope, String taskId,
            String runId, String executionId, Evidence evidence) {
        var execution = executions.findForNativeUsageCost(scope.tenantId(), scope.clientId(), scope.executorOwnerJiacn(),
                scope.agentId(), taskId, runId, executionId);
        if (execution == null) throw new PersonalWorkspaceExecutionService.Failure(PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        var binding = new UsageCostService.Binding(new EconomyScope(scope.tenantId(), scope.clientId()), execution.getOwnerJiacn(),
                execution.getTaskId(), "NATIVE_PROVIDER_CALL", executionId + ":" + evidence.invocationId());
        boolean started = evidence.phase() == NativeExecutionUsageCostRecorder.Phase.STARTED;
        String receiptId = started ? "started" : "terminal";
        String raw = JSON.writeValueAsString(evidence);
        var replay = costs.observation(binding, receiptId);
        if (replay.isPresent()) {
            var saved = replay.get();
            // record() retains the original digest/conflict check; never silently accept changed raw evidence.
            return costs.record(new UsageCostService.Observation(binding, receiptId, saved.expectedRevision(), raw,
                    saved.normalizedUsage(), saved.price(), evidence.observedAt()));
        }
        ModelTokenPrice price;
        var start = costs.observation(binding, "started");
        if (start.isPresent()) price = start.get().price();
        else if (evidence.channel() == null || evidence.tier() == null) price = null;
        else price = prices.find(evidence.channel(), evidence.requestedModel(), evidence.tier())
                .filter(p -> p.version().equals(evidence.priceVersion())).orElse(null);
        var responseModel = evidence.evidence() == null ? null : evidence.evidence().get("responseModel");
        String model = responseModel != null && responseModel.isTextual() ? responseModel.asText() : evidence.requestedModel();
        var usage = !started && "openai-images-v1".equals(evidence.protocol())
                ? ImageUsageNormalizer.normalize(evidence.channel(), model, evidence.tier(), evidence.evidence()) : null;
        return costs.record(new UsageCostService.Observation(binding, receiptId, started ? 0 : 1, raw, usage, price, evidence.observedAt()));
    }
}
