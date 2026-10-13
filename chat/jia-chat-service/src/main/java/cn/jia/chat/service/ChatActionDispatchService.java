package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable action admission, independent of browser subscribers. Never invokes a provider here. */
@Service
public class ChatActionDispatchService {
    /** Existing execution adapter must persist its own stable child under the caller's transaction. */
    public interface Executor {
        default void prepare(ChatActionFinalService.BoundAction action) { }
        String admit(ChatActionFinalService.BoundAction action);
    }

    /** Confirmed stale immutable request, not a transient DB/lease/runtime failure. */
    public static final class Rejected extends ChatDeliberationException {
        public Rejected() { super(Reason.CONFLICT, "Action request no longer applies"); }
    }


    private final ChatActionFinalService finals;
    private final AgentTaskMutationTransaction tasks;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService scopes;
    private final ChatTypedDeliberationStore typed;
    private final ChatTypedInspectionContextService inspections;
    private final ChatDeliberationService deliberation;
    private final ChatDeliberationDao dao;
    private final ChatDeliberationOutboxService outbox;
    private final ObjectProvider<Executor> executors;

    public ChatActionDispatchService(ChatActionFinalService finals, AgentTaskMutationTransaction tasks,
            ChatBountyBindingStore bindings, ChatConversationDao conversations, JuyitingConversationScopeService scopes,
            ChatTypedDeliberationStore typed, ChatTypedInspectionContextService inspections,
            ChatDeliberationService deliberation, ChatDeliberationDao dao, ChatDeliberationOutboxService outbox,
            ObjectProvider<Executor> executors) {
        this.finals=finals; this.tasks=tasks; this.bindings=bindings; this.conversations=conversations;
        this.scopes=scopes; this.typed=typed; this.inspections=inspections; this.deliberation=deliberation;
        this.dao=dao; this.outbox=outbox; this.executors=executors;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void consume(ChatDeliberationOutboxService.Claim claim) {
        var initial = finals.loadAction(claim.row());
        var parent = initial.outcome(); var scope = parent.scope();
        var executor=executors.getIfUnique();
        if(executor==null)throw new IllegalStateException("Skill execution adapter is unavailable");
        // No root/conversation/outbox lock across immutable package I/O. Replay skips preparation entirely.
        if(typed.findAdmissionByKey(scope,ChatActionFinalValidator.actionEventId(initial.validated()),false)==null)
            executor.prepare(initial);
        tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), parent.taskId(), root -> {
            var binding = bindings.lock(new ChatBountyBindingStore.Scope(scope.tenantId(), scope.ownerJiacn(), scope.clientId()), parent.taskId());
            String target = (String) initial.validated().binding().get("targetAgentId");
            if (root == null || root.getTaskVersion() == null || root.getTaskVersion() < parent.assignmentRevision()
                    || !target.equals(root.getAssignedAgentId()) || binding == null || binding.conversationId() == null
                    || !scope.conversationId().equals(Long.toString(binding.conversationId()))
                    || parent.assignmentRevision() != binding.assignmentRevision()) throw new Rejected();
            var conversation = conversations.lockScopedById(scope.ownerJiacn(), scope.clientId(), scope.conversationId());
            if (conversation == null || conversation.getId() == null || conversation.getDeletedAt() != null
                    || !scope.tenantId().equals(conversation.getTenantId()) || !scope.ownerJiacn().equals(conversation.getJiacn())
                    || !scope.clientId().equals(conversation.getClientId()) || !scope.conversationId().equals(Long.toString(conversation.getId()))
                    || !Objects.equals(scope.conversationGeneration(), conversation.getLifecycleGeneration())
                    || !"juyiting".equals(conversation.getConversationType()) || !"bounty".equals(conversation.getConversationScopeType())
                    || !parent.taskId().equals(conversation.getTaskId()) || !("task:"+parent.taskId()).equals(conversation.getConversationScopeKey())
                    || !List.of(target).equals(scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds())))
                throw new Rejected();
            // Lock order: task root -> binding -> conversation -> outbox. Claim/retry transactions never take task locks.
            var locked = outbox.requireActiveClaim(claim, System.currentTimeMillis());
            var action = finals.loadAction(locked);
            if (!initial.equals(action)) throw conflict("Action final changed");
            var capability = action.validated().dispatchFacts().availableActions().stream().filter(c -> c.actionId().equals(
                    action.validated().interactionOutcome().action().actionId())).findFirst().orElseThrow();
            if(!"EXECUTE".equals(capability.kind())||!"EXECUTE_SKILL".equals(capability.operation()))throw new Rejected();
            executor.admit(action);
            if (!outbox.sent(claim, System.currentTimeMillis())) throw conflict("Action outbox lease changed");
            return null;
        });
    }

    /** Runs only after consume has rolled back a confirmed rejection. No child can be discarded.
     * Same lock order as admission; the failure event and DEAD settlement commit together. */
    @Transactional(rollbackFor = Exception.class)
    public void reject(ChatDeliberationOutboxService.Claim claim) {
        var initial = finals.loadAction(claim.row()); var parent = initial.outcome(); var scope = parent.scope();
        tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), parent.taskId(), root -> {
            bindings.lock(new ChatBountyBindingStore.Scope(scope.tenantId(), scope.ownerJiacn(), scope.clientId()), parent.taskId());
            var conversation = conversations.lockScopedById(scope.ownerJiacn(), scope.clientId(), scope.conversationId());
            var locked = outbox.requireActiveClaim(claim, System.currentTimeMillis());
            var action = finals.loadAction(locked);
            if (!initial.equals(action) || typed.findAdmissionByKey(scope, locked.getEventId(), true) != null)
                throw conflict("Rejected action already has a child");
            // A removed or replaced generation receives no new visible event; its old action still terminates.
            if (conversation != null && conversation.getDeletedAt() == null
                    && Objects.equals(scope.conversationGeneration(), conversation.getLifecycleGeneration())
                    && scope.tenantId().equals(conversation.getTenantId()) && scope.ownerJiacn().equals(conversation.getJiacn())
                    && scope.clientId().equals(conversation.getClientId())
                    && scope.conversationId().equals(String.valueOf(conversation.getId())))
                deliberation.persistActionFailed(action, System.currentTimeMillis());
            if (!outbox.dead(claim, "ACTION_REQUEST_CHANGED", System.currentTimeMillis()))
                throw conflict("Rejected action lease changed");
            return null;
        });
    }

    private static ChatDeliberationException conflict(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,message);
    }
}
