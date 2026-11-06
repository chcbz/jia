package cn.jia.agent.service.usage;

import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;

/** Receives native execution evidence independently of business delivery. An app-server
 * turn's last/total counters do not prove individual provider invocations or prices:
 * preserve them as pending evidence, never fabricate an actual model or billable delta.
 */
@Service
public final class NativeExecutionUsageCostRecorder {
    public record Evidence(String executionId, String requestedModel, String usageEvidenceJson) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PersonalWorkspaceExecutionDao executions;
    private final UsageCostService costs;

    public NativeExecutionUsageCostRecorder(PersonalWorkspaceExecutionDao executions, UsageCostService costs) {
        this.executions = executions;
        this.costs = costs;
    }

    /** Caller supplies authenticated Runtime context, never a request-provided task owner.
     * Dedicated historical lookup checks the original executor binding, not current grant
     * state/version. No task/grant/lease writes or provider calls occur in this adapter.
     */
    public UsageCostService.Receipt record(PersonalWorkspaceExecutionService.RuntimeScope scope,
            String taskId, String runId, Evidence evidence) {
        var execution = executions.findForNativeUsageCost(scope.tenantId(), scope.clientId(),
                scope.executorOwnerJiacn(), scope.agentId(), taskId, runId, evidence.executionId());
        if (execution == null) throw new PersonalWorkspaceExecutionService.Failure(
                PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
        var binding = new UsageCostService.Binding(new EconomyScope(scope.tenantId(), scope.clientId()),
                execution.getOwnerJiacn(), execution.getTaskId(), "NATIVE_EXECUTION_EVIDENCE", execution.getExecutionId());
        var raw = new LinkedHashMap<String, Object>();
        raw.put("executionId", execution.getExecutionId()); raw.put("runId", execution.getRunId());
        raw.put("requestedModel", evidence.requestedModel());
        raw.put("usageEvidence", JSON.readTree(evidence.usageEvidenceJson()));
        // One immutable terminal observation per native execution. Redelivery uses exactly
        // the same identity/content; conflicting evidence is not silently replaced. Price
        // cannot be looked up after the call and misrepresented as a pre-call snapshot.
        return costs.record(new UsageCostService.Observation(binding, "terminal", 0,
                JSON.writeValueAsString(raw), null, null, 0));
    }
}
