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
    public interface Executor { String admit(ChatActionFinalService.BoundAction action); }

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

    @Transactional(rollbackFor = Exception.class)
    public void consume(ChatDeliberationOutboxService.Claim claim) {
        var initial = finals.loadAction(claim.row());
        var parent = initial.outcome(); var scope = parent.scope();
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
            if ("INSPECT_INPUTS".equals(capability.kind())) {
                inspect(action, target);
            } else {
                var executor = executors.getIfUnique();
                if (executor == null) throw new IllegalStateException("Action execution adapter is unavailable");
                executor.admit(action);
            }
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

    private void inspect(ChatActionFinalService.BoundAction action, String target) {
        var parent=action.outcome(); var scope=parent.scope();
        String eventId=ChatActionFinalValidator.actionEventId(action.validated());
        String requestId=ChatDeliberationService.inspectionContinuationRequestId(eventId);
        String body=ChatDeliberationService.digest(Map.of("schemaVersion",3,"actionRequestId",eventId,
                "parentOutcomeId",parent.outcomeId(),"parentFinalDigest",parent.finalDigest()));
        var prior=typed.findAdmissionByKey(scope,eventId,true);
        if (prior != null) {
            if (!scope.equals(prior.scope()) || !eventId.equals(prior.idempotencyKey()) || !requestId.equals(prior.requestId())
                    || !body.equals(prior.bodyDigest()) || !parent.outcomeId().equals(prior.parentOutcomeId())
                    || !parent.taskId().equals(prior.taskId()) || prior.assignmentRevision()!=parent.assignmentRevision()
                    || prior.userMessageId()!=action.admission().userMessageId() || prior.requestRevision()!=1
                    || !"ADMITTED".equals(prior.state()) || prior.stateVersion()!=0 || !"DISCUSSION".equals(prior.intent()))
                throw conflict("Action child identity changed");
            ChatTypedInspectionContextService.parseEnvelope(prior.sourceCatalogJson());
            var child=dao.findRequest(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),requestId);
            if (child==null || !Objects.equals(prior.userMessageId(),child.getUserMessageId())
                    || !scope.conversationId().equals(child.getConversationId())
                    || !Objects.equals(scope.conversationGeneration(),child.getConversationGeneration())
                    || !Objects.equals(1L,child.getRequestRevision())) throw conflict("Action child is incomplete");
            return; // Recovery only. Do not rebuild a live manifest or repeat any dispatch/provider call.
        }
        var context=inspections.resolve(new ChatTypedInspectionContextService.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),
                scope.conversationId(),scope.conversationGeneration(),parent.taskId(),parent.assignmentRevision(),requestId,1,target), selectors(action));
        var chatScope=new JuyitingConversationScope("bounty","task:"+parent.taskId(),parent.taskId(),target,List.of(target),List.of(target));
        var admitted=deliberation.admitInspectionContinuation(action,context,chatScope);
        var turns=admitted.dispatches().stream().map(ChatDeliberationService.Dispatch::turnId).toList();
        if (turns.size()!=1 || !requestId.equals(admitted.requestId())
                || !Long.toString(action.admission().userMessageId()).equals(admitted.userMessageId())) throw conflict("Action child differs");
        long now=System.currentTimeMillis();
        var row=new ChatTypedDeliberationStore.Admission("action-admit_"+eventId.substring(4),scope,eventId,
                ChatDeliberationService.digest(Map.of("bodyDigest",body,"typedInspection",context.typedInspection())),body,
                "DISCUSSION",parent.taskId(),parent.assignmentRevision(),parent.outcomeId(),null,requestId,1,
                action.admission().userMessageId(),CanonicalContextJson.write(turns),context.admissionEnvelopeJson(),"ADMITTED",0,
                dao.eventHighWatermark(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),scope.conversationId(),scope.conversationGeneration()),now);
        if (typed.insertAdmission(row)!=1) throw new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                "Unable to persist action child admission");
        deliberation.persistActionStarted(action, requestId, "INSPECT", now);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatTypedInspectionWire.SourceSelector> selectors(ChatActionFinalService.BoundAction action) {
        var catalog="CHAT".equals(action.validated().binding().get("route"))
                ? ChatTypedDeliberationContextService.parseCatalog(action.outcome().sourceCatalogJson())
                : ChatTypedInspectionContextService.sources(action.outcome().sourceCatalogJson());
        var byId=new LinkedHashMap<String,Map<String,Object>>();
        for (var entry:catalog) byId.put((String)entry.get("sourceRefId"),entry);
        var selected=new ArrayList<ChatTypedInspectionWire.SourceSelector>();
        for (String id:action.validated().interactionOutcome().action().sourceRefIds()) {
            var entry=byId.get(id);
            if (entry==null || !(entry.get("selector") instanceof Map<?,?>)) throw conflict("Action source is unavailable");
            var m=(Map<String,Object>)entry.get("selector");
            selected.add(ChatTypedInspectionWire.validateSelector(new ChatTypedInspectionWire.SourceSelector(
                    (String)m.get("kind"),(String)m.get("fileId"),(String)m.get("version"),(String)m.get("purpose"),
                    (String)m.get("assetId"),(String)m.get("assetRevision"))));
        }
        return List.copyOf(selected);
    }

    private static ChatDeliberationException conflict(String message) {
        return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,message);
    }
}
