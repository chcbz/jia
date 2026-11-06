package cn.jia.chat.service.usage;

import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchAuthorityMapper;
import cn.jia.chat.service.ChatDeliberationException;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.UsageCostService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Source attribution only: original CHAT dispatch, not current task admission or final-message authority. */
@Service
public final class ChatUsageCostRecorder {
    public enum Phase { STARTED, TERMINAL }
    public record Evidence(Phase phase, String requestedModel, String usageEvidenceJson) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChatDispatchAuthorityMapper dispatches;
    private final ChatDeliberationDao turns;
    private final UsageCostService costs;

    public ChatUsageCostRecorder(ChatDispatchAuthorityMapper dispatches, ChatDeliberationDao turns, UsageCostService costs) {
        this.dispatches = dispatches; this.turns = turns; this.costs = costs;
    }

    public UsageCostService.Receipt record(AgentRuntimeAuthentication.Scope scope, String dispatchId, Evidence evidence) {
        var rows = dispatches.findDispatch(scope.tenantId(), scope.clientId(), dispatchId);
        if (rows == null || rows.size() != 1) throw denied();
        var dispatch = rows.getFirst();
        var turn = turns.findTurn(scope.tenantId(), dispatch.getOwnerJiacn(), scope.clientId(), dispatch.getTurnId());
        Map<?, ?> payload;
        try { payload = JSON.readValue(dispatch.getPayloadJson(), Map.class); }
        catch (RuntimeException invalid) { throw denied(); }
        if (payload == null || turn == null || !"CHAT".equals(payload.get("route"))
                || !Objects.equals(dispatchId, payload.get("dispatchId"))
                || !Objects.equals(dispatch.getTurnId(), payload.get("turnId"))
                || !Objects.equals(scope.agentId(), payload.get("targetAgentId"))
                || !Objects.equals(turn.getTargetAgentId(), payload.get("targetAgentId"))
                || !Objects.equals(turn.getConversationId(), payload.get("conversationId"))) throw denied();
        Object taskValue = payload.get("taskId");
        if (taskValue != null && (!(taskValue instanceof String) || ((String) taskValue).isBlank())) throw denied();
        String taskId = (String) taskValue;
        boolean multiMember = payload.get("factsManifest") instanceof Map<?, ?> facts
                && facts.get("participantAgentIds") instanceof List<?> members
                && members.size() > 1 && members.contains(scope.agentId());
        if (dispatch.getTaskGrantId() == null && dispatch.getTaskGrantVersion() == null
                && (!"bounty".equals(payload.get("conversationScopeType")) || multiMember)) {
            // Preserve original owner-only transport for private/public/team conversations.
            if (!Objects.equals(dispatch.getOwnerJiacn(), scope.ownerJiacn())) throw denied();
        } else if (taskId == null || dispatch.getTaskGrantId() == null || dispatch.getTaskGrantVersion() == null
                || !dispatches.hasOriginalUsageExecutor(scope.tenantId(), scope.clientId(), dispatch.getOwnerJiacn(),
                    taskId, dispatch.getTaskGrantId(), scope.agentId(), scope.ownerJiacn())) throw denied();
        var binding = new UsageCostService.Binding(new EconomyScope(scope.tenantId(), scope.clientId()),
                dispatch.getOwnerJiacn(), taskId, "CHAT_EXECUTION_EVIDENCE", dispatchId);
        var raw = new LinkedHashMap<String, Object>();
        raw.put("dispatchId", dispatchId); raw.put("turnId", dispatch.getTurnId());
        raw.put("conversationId", turn.getConversationId()); raw.put("phase", evidence.phase().name());
        raw.put("requestedModel", evidence.requestedModel()); raw.put("usageEvidence", JSON.readTree(evidence.usageEvidenceJson()));
        boolean started = evidence.phase() == Phase.STARTED;
        // Native thread/turn counters are evidence, not verified individual provider billing.
        return costs.record(new UsageCostService.Observation(binding, started ? "started" : "terminal", started ? 0 : 1,
                JSON.writeValueAsString(raw), null, null, 0));
    }

    private static ChatDeliberationException denied() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Original CHAT usage authority is unavailable");
    }
}
