package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.TaskAssigneeAuthorityService;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchAuthorityMapper;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Separates A's persisted conversation from B's authenticated transport. No public read ACL is widened. */
@Service
public class ChatDispatchAuthorityService {
    private final ChatDispatchAuthorityMapper mapper;
    private final ChatDeliberationDao turns;
    private final TaskAssigneeAuthorityService authority;
    private final AgentTaskExecutionGrantService grants;
    private final AgentTaskMutationTransaction tasks;

    public ChatDispatchAuthorityService(ChatDispatchAuthorityMapper mapper, ChatDeliberationDao turns,
            TaskAssigneeAuthorityService authority, AgentTaskExecutionGrantService grants, AgentTaskMutationTransaction tasks) {
        this.mapper=mapper; this.turns=turns; this.authority=authority; this.grants=grants; this.tasks=tasks;
    }

    /** Only NEW admission may resolve a current baseline; every subsequent use reads the saved tuple. */
    public AgentTaskExecutionGrantService.Admission admission(String tenant, String owner, String client,
            String taskId, String agentId) {
        var scope=new AgentTaskExecutionGrantService.Scope(tenant,client,owner);
        var current=grants.currentFollowupContext(scope,taskId,agentId);
        return grants.resolveFollowupBaseline(scope,taskId,current.baselineGrantVersion(),
                current.taskVersion(),current.assignmentRevision(),current.requirementRevision(),agentId);
    }

    public TaskAssigneeAuthorityService.Target admissionTarget(String tenant, String owner, String client,
            String taskId, String agentId) {
        var grant=admission(tenant,owner,client,taskId,agentId);
        return authority.requireGrantTarget(tenant,client,owner,taskId,grant.grantId(),grant.grantVersion());
    }

    public TaskAssigneeAuthorityService.Target grantTarget(String tenant,String owner,String client,
            String taskId,String grantId,long version) {
        return authority.requireGrantTarget(tenant,client,owner,taskId,grantId,version);
    }

    public record Binding(ChatDispatchOutboxEntity dispatch, ChatTurnEntity turn, String executorOwner) { }

    public Binding outgoing(ChatDispatchOutboxEntity candidate) {
        if (!"DISPATCH".equals(candidate.getEventType()) && !"CANCEL_REQUESTED".equals(candidate.getEventType())) throw denied();
        var row="DISPATCH".equals(candidate.getEventType()) ? candidate : unique(mapper.findDispatch(
                candidate.getTenantId(),candidate.getClientId(),candidate.getDispatchId()));
        if (!Objects.equals(row.getOwnerJiacn(),candidate.getOwnerJiacn())
                || !Objects.equals(row.getTurnId(),candidate.getTurnId())) throw denied();
        return bind(row, "DISPATCH".equals(candidate.getEventType()));
    }

    public Binding incoming(String tenant, String client, String executorOwner, String agentId, String dispatchId) {
        var row=unique(mapper.findDispatch(tenant,client,dispatchId));
        if (!Objects.equals(tenant,row.getTenantId()) || !Objects.equals(client,row.getClientId())
                || !Objects.equals(dispatchId,row.getDispatchId())) throw denied();
        return authenticate(bind(row, true),executorOwner,agentId);
    }

    public Binding incomingTurn(String tenant, String client, String executorOwner, String agentId, String turnId) {
        var row=unique(mapper.findTurnDispatch(tenant,client,turnId));
        if (!Objects.equals(tenant,row.getTenantId()) || !Objects.equals(client,row.getClientId())
                || !Objects.equals(turnId,row.getTurnId())) throw denied();
        return authenticate(bind(row, true),executorOwner,agentId);
    }

    public Binding forTurn(ChatTurnEntity turn) {
        var result=outgoing(unique(mapper.findDispatch(turn.getTenantId(),turn.getClientId(),turn.getDispatchId())));
        if (!Objects.equals(result.turn().getOwnerJiacn(),turn.getOwnerJiacn())
                || !Objects.equals(result.turn().getTurnId(),turn.getTurnId())) throw denied();
        return result;
    }

    private Binding authenticate(Binding binding,String owner,String agent) {
        if (!Objects.equals(owner,binding.executorOwner()) || !Objects.equals(agent,binding.turn().getTargetAgentId()))
            throw denied();
        return binding;
    }

    private Binding bind(ChatDispatchOutboxEntity row, boolean requireActive) {
        var turn=turns.findTurn(row.getTenantId(),row.getOwnerJiacn(),row.getClientId(),row.getTurnId());
        if (turn==null || !Objects.equals(row.getDispatchId(),turn.getDispatchId())
                || !Objects.equals(row.getTenantId(),turn.getTenantId())
                || !Objects.equals(row.getClientId(),turn.getClientId())
                || !Objects.equals(row.getOwnerJiacn(),turn.getOwnerJiacn())) throw denied();
        Map<?,?> payload;
        try { payload=JsonUtil.getMapper().readValue(row.getPayloadJson(),Map.class); }
        catch (Exception invalid) { throw denied(); }
        if (!Objects.equals(turn.getTargetAgentId(),payload.get("targetAgentId"))
                || !Objects.equals(turn.getConversationId(),payload.get("conversationId"))) throw denied();
        Object taskId=payload.get("taskId");
        // Non-task and multi-member task conversations are outside the single-Agent slice.
        // They retain owner-only transport; this never lets an external executor into team chat.
        boolean multiMember = payload.get("factsManifest") instanceof Map<?,?> legacyFacts
                && legacyFacts.get("participantAgentIds") instanceof List<?> participants
                && participants.size()>1 && participants.contains(turn.getTargetAgentId());
        if ((!"bounty".equals(payload.get("conversationScopeType")) || multiMember)
                && row.getTaskGrantId()==null && row.getTaskGrantVersion()==null)
            return new Binding(row,turn,row.getOwnerJiacn());
        if (!(taskId instanceof String task) || task.isBlank() || row.getTaskGrantId()==null
                || row.getTaskGrantVersion()==null || row.getTaskGrantVersion()<1) throw denied();
        if (row.getTaskAssignmentRevision()==null || row.getTaskAssignmentRevision()<0
                || row.getTaskRequirementRevision()==null || row.getTaskRequirementRevision()<1) throw denied();
        long revision=row.getTaskAssignmentRevision();
        long requirement=row.getTaskRequirementRevision();
        // New dispatches duplicate this tuple in their context. Historical backfill changes only
        // these authority columns, proven from immutable admission/bootstrap; never the old hash.
        if (!(payload.get("factsManifest") instanceof Map<?,?> facts)) throw denied();
        if (facts.containsKey("taskAuthority")) {
            if (!(facts.get("taskAuthority") instanceof Map<?,?> frozen)
                    || !Objects.equals(row.getTaskGrantId(),frozen.get("grantId"))
                    || !Objects.equals(Long.toString(row.getTaskGrantVersion()),frozen.get("grantVersion"))
                    || !Objects.equals(Long.toString(revision),frozen.get("assignmentRevision"))
                    || !Objects.equals(Long.toString(requirement),frozen.get("requirementRevision"))) throw denied();
        }
        // Validate this original grant, never resolve a current replacement for an old dispatch.
        TaskAssigneeAuthorityService.Target target;
        try {
            // STOP is a restrictive operation: address only the original B, even after revocation.
            // It must not grant new execution or switch to the task's replacement executor.
            if (requireActive) {
                var scope=new AgentTaskExecutionGrantService.Scope(row.getTenantId(),row.getClientId(),row.getOwnerJiacn());
                if ("INSPECT".equals(turn.getRoute())) {
                    grants.admit(scope,task,row.getTaskGrantId(),row.getTaskGrantVersion(),revision,
                            turn.getTargetAgentId(),"INSPECT_INPUTS",false);
                } else {
                    // Discussion is not an EXECUTE/DELIBERATE operation. Revalidate the exact saved
                    // baseline under A's root, without requiring a permission absent from skill grants.
                    tasks.executeWithLockedTaskRootInOwnerScope(row.getTenantId(),row.getClientId(),row.getOwnerJiacn(),task,root -> {
                        if (root==null || root.getTaskVersion()==null || "cancelled".equals(root.getRewardStatus())) throw denied();
                        return grants.admitFollowupBaseline(scope,task,row.getTaskGrantId(),row.getTaskGrantVersion(),
                                root.getTaskVersion(),revision,requirement,turn.getTargetAgentId());
                    });
                }
            }
            long targetVersion=row.getTaskGrantVersion();
            if (!requireActive) {
                var saved=mapper.findOriginalGrantForStop(row.getTenantId(),row.getClientId(),row.getOwnerJiacn(),task,row.getTaskGrantId());
                if (saved==null || saved.grantVersion()==null || !Objects.equals(saved.targetAgentId(),turn.getTargetAgentId())) throw denied();
                boolean original=Objects.equals(saved.grantVersion(),row.getTaskGrantVersion());
                boolean closedSuccessor=("REVOKED".equals(saved.state()) || "SUPERSEDED".equals(saved.state()))
                        && targetVersion<Long.MAX_VALUE && saved.grantVersion()==targetVersion+1;
                if (!original && !closedSuccessor) throw denied();
                targetVersion=saved.grantVersion();
            }
            // The common authority stays strict. Only STOP may use the same grant's single
            // terminal version increment; identity/binding replacement still fails closed.
            target=authority.requireGrantTarget(row.getTenantId(),row.getClientId(),row.getOwnerJiacn(),
                    task,row.getTaskGrantId(),targetVersion);
        } catch (cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException rejected) {
            if (cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN.equals(rejected.getCode())) throw denied();
            throw rejected;
        } catch (cn.jia.agent.service.AgentTaskExecutionGrantException | NumberFormatException rejected) {
            throw denied();
        }
        if (target==null || target.ownerJiacn()==null || target.bindingId()<1
                || !Objects.equals(target.tenantId(),row.getTenantId())
                || !Objects.equals(target.clientId(),row.getClientId())
                || !Objects.equals(target.agentId(),turn.getTargetAgentId())) throw denied();
        return new Binding(row,turn,target.ownerJiacn());
    }

    private static ChatDispatchOutboxEntity unique(List<ChatDispatchOutboxEntity> rows) {
        if (rows==null || rows.size()!=1) throw denied();
        return rows.getFirst();
    }
    private static ChatDeliberationException denied() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,
                "Chat dispatch authority is unavailable");
    }
}
