package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryDecisionDTO;
import cn.jia.agent.service.AgentTaskFormalDeliveryDecisionService;
import cn.jia.agent.service.AgentTaskFormalDeliveryReadService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Durable message-origin revision. No HTTP command, synthetic USER or reopened parent final. */
@Service
@ConditionalOnProperty(prefix="chat.delivery-review",name="enabled",havingValue="true")
public class ChatDeliveryRevisionService {
    public static final String EVENT="REVIEW_REQUESTED";
    public record BoundRevision(ChatActionFinalService.BoundAction parent,ChatDeliveryRevisionContract.Basis basis) {
        public BoundRevision {
            Objects.requireNonNull(parent);Objects.requireNonNull(basis);
            var row=parent.outcome();
            if(!"REVISION_REQUEST".equals(parent.validated().interactionOutcome().kind()) || !row.taskId().equals(basis.taskId())
                    || !row.requestId().equals(basis.sourceRequestId()) || !Long.toString(parent.admission().userMessageId()).equals(basis.sourceMessageId()))throw new IllegalArgumentException("DELIVERY_REVISION_INVALID");
        }
    }
    private final ChatDeliberationDao dao;
    private final ObjectProvider<ChatActionFinalService> finals;
    private final ObjectProvider<ChatDeliberationService> deliberation;
    private final ChatTaskDeliveryReviewStore operations;
    private final ChatTypedDeliberationStore typed;
    private final AgentTaskMutationTransaction tasks;
    private final AgentTaskFormalDeliveryReadService deliveries;
    private final AgentTaskFormalDeliveryDecisionService decisions;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService scopes;
    private final ChatTypedDeliberationContextService contexts;
    private final ChatSkillPackagePreparationService packages;
    private final ChatDeliberationOutboxService outbox;
    private final ChatDispatchAuthorityService dispatchAuthority;
    private ChatFormalDeliveryRetentionService retention;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRetention(ChatFormalDeliveryRetentionService value) {retention=Objects.requireNonNull(value);}
    public ChatDeliveryRevisionService(ChatDeliberationDao dao,ObjectProvider<ChatActionFinalService> finals,
            ObjectProvider<ChatDeliberationService> deliberation,ChatTaskDeliveryReviewStore operations,ChatTypedDeliberationStore typed,
            AgentTaskMutationTransaction tasks,AgentTaskFormalDeliveryReadService deliveries,AgentTaskFormalDeliveryDecisionService decisions,
            ChatBountyBindingStore bindings,ChatConversationDao conversations,JuyitingConversationScopeService scopes,
            ChatTypedDeliberationContextService contexts,ChatSkillPackagePreparationService packages,ChatDeliberationOutboxService outbox,
            ChatDispatchAuthorityService dispatchAuthority) {
        this.dao=dao;this.finals=finals;this.deliberation=deliberation;this.operations=operations;this.typed=typed;this.tasks=tasks;
        this.deliveries=deliveries;this.decisions=decisions;this.bindings=bindings;this.conversations=conversations;this.scopes=scopes;
        this.contexts=contexts;this.packages=packages;this.outbox=outbox;this.dispatchAuthority=dispatchAuthority;
    }
    public static String eventId(ChatTypedDeliberationStore.Outcome row) {
        return "review_"+ChatDeliveryReviewContract.sha(ChatDeliveryReviewContract.json(List.of("hall-message-review-event-v1",
                row.scope().tenantId(),row.scope().ownerJiacn(),row.scope().clientId(),row.turnId(),row.finalDigest()))).substring(0,57);
    }
    static Map<String,Object> payload(ChatTypedDeliberationStore.Outcome row) {
        return Map.of("schemaVersion",1,"taskId",row.taskId(),"requestId",row.requestId(),"outcomeId",row.outcomeId(),"finalDigest",row.finalDigest());
    }
    public static String digest(BoundRevision bound) {
        return ChatDeliveryRevisionContract.revisionDigest(bound.basis(),bound.parent().outcome().outcomeId(),
                bound.parent().outcome().finalDigest(),bound.parent().validated().interactionOutcome().revision());
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void enqueue(ChatTypedDeliberationStore.Outcome row,ChatActionFinalValidator.ValidatedFinal validated,long now) {
        if(!"REVISION_REQUEST".equals(row.kind()) || !row.finalDigest().equals(validated.finalDigest())
                || !"CHAT".equals(validated.binding().get("route")))throw new IllegalArgumentException("DELIVERY_REVISION_INVALID");
        var event=new ChatDispatchOutboxEntity().setEventId(eventId(row)).setTenantId(row.scope().tenantId())
                .setOwnerJiacn(row.scope().ownerJiacn()).setClientId(row.scope().clientId()).setTurnId(row.turnId())
                .setDispatchId((String)validated.binding().get("dispatchId")).setEventType(EVENT).setStatus("READY")
                .setPayloadJson(CanonicalContextJson.write(payload(row))).setVersion(0L).setAvailableAt(now).setAttemptCount(0)
                .setFencingToken(0L).setCreatedAt(now).setUpdatedAt(now);
        if(dao.insertOutbox(event)!=1)throw new IllegalStateException("DELIVERY_REVISION_EVENT_FAILED");
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void consume(ChatDeliberationOutboxService.Claim claim) {
        var bound=finals.getObject().loadRevision(claim.row());var row=bound.parent().outcome();var s=row.scope();
        String target=(String)bound.parent().validated().binding().get("targetAgentId");
        try {
            // A committed/rejected replay needs no current executor or package access. Reuse its
            // immutable receipt under the original owner root; never turn a replay into new work.
            boolean settled=tasks.executeWithLockedTaskRootInOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn(),row.taskId(),root->{
                var prior=operations.byKey(new ChatDeliveryReviewContract.Scope(s.tenantId(),s.clientId(),s.ownerJiacn()),
                        row.taskId(),eventId(row),true);
                if(prior==null)return false;
                ChatTaskDeliveryReviewStore.requireDigest(prior,digest(bound));
                if("PROCESSING".equals(prior.state()))return false;
                ChatTaskDeliveryReviewStore.committedReceipt(prior);ack(claim);return true;
            });
            if(settled)return;
            var executor=revisionExecutor(bound);
            // Cold package I/O is strictly outside every task/Chat/outbox lock. The saved
            // dispatch grant supplies B's identity; A still owns the task and its documents.
            packages.prepare(new cn.jia.agent.service.SkillCapabilityLookup.Scope(s.tenantId(),s.clientId(),executor.executorOwner(),target));
            if(retention==null)throw new IllegalStateException("Revision source service unavailable");
            var formalSources=retention.prepareRevisionSources(bound);
            tasks.executeWithLockedTaskRootInOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn(),row.taskId(),root->{
                var op=operations.admitRevision(bound);var owner=new ChatDeliveryReviewContract.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
                if(!"PROCESSING".equals(op.state())) {ChatTaskDeliveryReviewStore.committedReceipt(op);ack(claim);return null;}
                var b=bound.basis();
                if(!"reviewing".equals(root.getRewardStatus()) || !b.taskVersion().equals(String.valueOf(root.getTaskVersion()))
                        || !target.equals(root.getAssignedAgentId()) || operations.assignmentRevision(owner,row.taskId())!=Long.parseLong(b.assignmentRevision()))throw new ChatDeliveryReviewContract.Conflict();
                var binding=bindings.lock(new ChatBountyBindingStore.Scope(s.tenantId(),s.ownerJiacn(),s.clientId()),row.taskId());
                if(binding==null || binding.conversationId()==null || !s.conversationId().equals(binding.conversationId().toString())
                        || binding.assignmentRevision()!=row.assignmentRevision())throw new ChatDeliveryReviewContract.Conflict();
                var conversation=conversations.lockScopedById(s.ownerJiacn(),s.clientId(),s.conversationId());
                if(conversation==null || conversation.getDeletedAt()!=null || !Objects.equals(s.conversationGeneration(),conversation.getLifecycleGeneration())
                        || !s.tenantId().equals(conversation.getTenantId()) || !row.taskId().equals(conversation.getTaskId())
                        || !List.of(target).equals(scopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds())))throw new ChatDeliveryReviewContract.Conflict();
                // Re-read final and USER under root/binding/conversation locks before deciding.
                var locked=finals.getObject().loadRevision(claim.row());
                if(!digest(bound).equals(digest(locked)))throw new ChatDeliveryReviewContract.Conflict();
                deliberation.getObject().requireActionUser(locked.parent());
                // Preparation is not authorization: recheck the exact parent grant under the
                // task root before changing a delivery or admitting a child. Never resolve a
                // replacement grant for an old queued event.
                var currentExecutor=revisionExecutor(locked);
                if(!executor.executorOwner().equals(currentExecutor.executorOwner()))throw new ChatDeliveryReviewContract.Conflict();
                var submitted=deliveries.listForTaskOwner(s.tenantId(),s.clientId(),s.ownerJiacn(),row.taskId()).stream()
                        .filter(v->"submitted".equals(v.getState())).toList();
                if(submitted.size()!=1)throw new ChatDeliveryReviewContract.Conflict();var batch=submitted.getFirst();
                if(!b.deliveryId().equals(batch.getDeliveryId()) || !b.deliveryVersion().equals(String.valueOf(batch.getDeliveryVersion()))
                        || !b.batchSha256().equals(ChatDeliveryReviewContract.batchDigest(row.taskId(),batch)))throw new ChatDeliveryReviewContract.Conflict();
                var command=new AgentTaskFormalDeliveryDecisionDTO();command.setDeliveryId(b.deliveryId());
                command.setExpectedTaskVersion(Long.parseLong(b.taskVersion()));command.setExpectedDeliveryVersion(Long.parseLong(b.deliveryVersion()));
                command.setDecision("changes_requested");command.setReviewReason(bound.parent().validated().interactionOutcome().revision().instruction());
                var result=decisions.decide(s.tenantId(),s.clientId(),row.taskId(),s.ownerJiacn(),command);
                if(!"changes_requested".equals(result.getState()) || result.getTaskVersion()==null || result.getDeliveryVersion()==null)throw new ChatDeliveryReviewContract.Conflict();
                var contextScope=new ChatTypedDeliberationContextService.Scope(s.tenantId(),s.ownerJiacn(),s.clientId(),s.conversationId(),s.conversationGeneration());
                var selectors=ChatTypedDeliberationContextService.parseCatalog(bound.parent().admission().sourceCatalogJson()).stream()
                        .filter(item->item.containsKey("selector"))
                        .map(item->cn.jia.chat.api.ChatTypedDeliberationWire.selector(item.get("selector"))).toList();
                var context=contexts.withFormalRevisionSources(contextScope,row.taskId(),target,
                        contexts.resolve(contextScope,row.taskId(),target,selectors),formalSources);
                var chatScope=new JuyitingConversationScope("bounty","task:"+row.taskId(),row.taskId(),target,List.of(target),List.of(target));
                var admitted=deliberation.getObject().admitDeliveryRevisionContinuation(bound,op.operationId(),context,chatScope);
                String child=ChatDeliberationService.deliveryRevisionRequestId(s,op.operationId());
                var turns=admitted.dispatches().stream().map(ChatDeliberationService.Dispatch::turnId).toList();if(turns.size()!=1)throw new IllegalStateException("DELIVERY_REVISION_CHILD_INVALID");
                long now=System.currentTimeMillis();
                var admission=new ChatTypedDeliberationStore.Admission("revision-admission_"+ChatDeliveryReviewContract.sha(op.operationId()).substring(0,40),s,
                        op.operationId(),"sha256:"+digest(bound),"sha256:"+digest(bound),"DISCUSSION",row.taskId(),row.assignmentRevision(),null,null,child,1,
                        bound.parent().admission().userMessageId(),CanonicalContextJson.write(turns),context.sourceCatalogJson(),"ADMITTED",0,
                        dao.eventHighWatermark(s.tenantId(),s.ownerJiacn(),s.clientId(),s.conversationId(),s.conversationGeneration()),now);
                if(typed.insertAdmission(admission)!=1)throw new IllegalStateException("DELIVERY_REVISION_ADMISSION_FAILED");
                operations.bindRevisionChild(owner,op,child);
                var receipt=new ChatDeliveryReviewContract.Receipt(1,op.operationId(),row.taskId(),b.deliveryId(),"REQUEST_CHANGES","COMMITTED",
                        Long.toString(op.stateVersion()+1),"RUNNING",result.getTaskVersion().toString(),"CHANGES_REQUESTED",result.getDeliveryVersion().toString(),
                        child,row.requestId(),s.conversationId(),null,false);
                operations.settle(owner,op,receipt);ack(claim);return null;
            });
        } catch(cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable unavailable) {
            if(unavailable.reason()==cn.jia.agent.service.InstalledSkillDescriptorLookup.SourceUnavailable.Reason.FORBIDDEN)
                reject(claim,bound,"DELIVERY_REVIEW_FORBIDDEN");
            else throw unavailable; // An unavailable dependency is not proof of revoked authority.
        } catch(ChatDeliveryReviewContract.SourceUnavailable unavailable) {reject(claim,bound,"DELIVERY_SOURCE_UNAVAILABLE");}
        catch(ChatDeliveryReviewContract.Conflict conflict) {reject(claim,bound,"DELIVERY_REVIEW_CONFLICT");}
        catch(cn.jia.agent.exception.AgentTaskCollaborationException failure) {
            switch(failure.getReason()) {
                case INVALID_TRANSITION, VERSION_CONFLICT -> reject(claim,bound,"DELIVERY_REVIEW_CONFLICT");
                case FORBIDDEN -> reject(claim,bound,"DELIVERY_REVIEW_FORBIDDEN");
                case NOT_FOUND -> reject(claim,bound,"DELIVERY_REVIEW_NOT_FOUND");
                default -> throw failure;
            }
        } catch(ChatDeliberationException failure) {
            if(failure.reason()==ChatDeliberationException.Reason.CONFLICT)reject(claim,bound,"DELIVERY_REVIEW_CONFLICT");
            else if(failure.reason()==ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN)reject(claim,bound,"DELIVERY_REVIEW_FORBIDDEN");
            else throw failure;
        }
    }
    private ChatDispatchAuthorityService.Binding revisionExecutor(BoundRevision bound) {
        var row=bound.parent().outcome();var scope=row.scope();
        var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
        if(turn==null || !row.requestId().equals(turn.getRequestId())
                || !scope.conversationId().equals(turn.getConversationId())
                || !Objects.equals(scope.conversationGeneration(),turn.getConversationGeneration())
                || !row.finalDigest().equals(turn.getFinalDigest())
                || !Objects.equals(bound.parent().validated().binding().get("dispatchId"),turn.getDispatchId())
                || !Objects.equals(bound.parent().validated().binding().get("targetAgentId"),turn.getTargetAgentId()))
            throw new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Revision authority unavailable");
        return dispatchAuthority.forTurn(turn);
    }
    private void reject(ChatDeliberationOutboxService.Claim claim,BoundRevision bound,String code) {
        var row=bound.parent().outcome();var s=row.scope();
        try { tasks.executeWithLockedTaskRootInOwnerScope(s.tenantId(),s.clientId(),s.ownerJiacn(),row.taskId(),root->{
            var op=operations.admitRevision(bound);var owner=new ChatDeliveryReviewContract.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
            if("PROCESSING".equals(op.state())) {
                var batch=deliveries.listForTaskOwner(s.tenantId(),s.clientId(),s.ownerJiacn(),row.taskId()).stream()
                        .filter(v->bound.basis().deliveryId().equals(v.getDeliveryId())).findFirst().orElse(null);
                operations.settle(owner,op,new ChatDeliveryReviewContract.Receipt(1,op.operationId(),row.taskId(),bound.basis().deliveryId(),"REQUEST_CHANGES","REJECTED",
                        Long.toString(op.stateVersion()+1),root.getRewardStatus().toUpperCase(java.util.Locale.ROOT),root.getTaskVersion().toString(),
                        batch==null?null:batch.getState().toUpperCase(java.util.Locale.ROOT),batch==null?null:batch.getDeliveryVersion().toString(),null,row.requestId(),s.conversationId(),code,false));
            }
            ack(claim);return null;
        }); } catch(cn.jia.agent.exception.AgentTaskCollaborationException unavailable) {
            if(unavailable.getReason()!=cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND
                    && unavailable.getReason()!=cn.jia.agent.exception.AgentTaskCollaborationException.Reason.FORBIDDEN)throw unavailable;
            operations.rejectUnavailableRevision(bound,outbox,claim,code);
        }
    }
    /** Independent mutable projection. GET never creates/retries an operation or dispatch. */
    @Transactional(readOnly=true)
    public Map<String,Object> read(ChatTypedDeliberationStore.Outcome row) {
        if(!"REVISION_REQUEST".equals(row.kind()))return null;
        var s=row.scope();var event=dao.findOutboxById(s.tenantId(),s.ownerJiacn(),s.clientId(),eventId(row));
        if(event==null || !EVENT.equals(event.getEventType()) || !row.turnId().equals(event.getTurnId())
                || !CanonicalContextJson.write(payload(row)).equals(CanonicalContextJson.write(parse(event.getPayloadJson()))))throw new IllegalStateException("DELIVERY_REVISION_PROGRESS_INVALID");
        var owner=new ChatDeliveryReviewContract.Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
        var op=operations.byKey(owner,row.taskId(),eventId(row),false);
        var progress=new java.util.LinkedHashMap<String,Object>();progress.put("operationId",op==null?null:op.operationId());
        progress.put("state",op==null?"QUEUED":op.state());progress.put("stateVersion",op==null?"0":Long.toString(op.stateVersion()));
        progress.put("sourceRequestId",row.requestId());progress.put("childRequestId",null);progress.put("childState",null);progress.put("childStateVersion",null);
        if(op!=null) {
            if("PROCESSING".equals(op.state()))throw new IllegalStateException("DELIVERY_REVISION_PARTIAL_COMMIT");
            var receipt=ChatTaskDeliveryReviewStore.committedReceipt(op);
            if(!"REQUEST_CHANGES".equals(receipt.decision()) || !row.requestId().equals(receipt.sourceRequestId())
                    || !s.conversationId().equals(receipt.conversationId()))throw new IllegalStateException("DELIVERY_REVISION_PROGRESS_INVALID");
            if("COMMITTED".equals(receipt.state())) {
                var child=dao.findRequest(s.tenantId(),s.ownerJiacn(),s.clientId(),receipt.requestId());
                if(child==null || !s.conversationId().equals(child.getConversationId()) || !Objects.equals(s.conversationGeneration(),child.getConversationGeneration())
                        || child.getStateVersion()==null || child.getStateVersion()<0)throw new IllegalStateException("DELIVERY_REVISION_CHILD_INVALID");
                progress.put("childRequestId",receipt.requestId());progress.put("childState",child.getAggregateState());
                progress.put("childStateVersion",Long.toString(child.getStateVersion()));
            }
        } else if("SENT".equals(event.getStatus()) || "DEAD".equals(event.getStatus()))throw new IllegalStateException("DELIVERY_REVISION_RECEIPT_MISSING");
        return java.util.Collections.unmodifiableMap(progress);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String raw) {
        return cn.jia.core.util.JsonUtil.getMapper().readValue(raw,Map.class);
    }
    private void ack(ChatDeliberationOutboxService.Claim claim) {
        long now=System.currentTimeMillis();outbox.requireActiveClaim(claim,now);
        if(!outbox.sent(claim,now))throw new IllegalStateException("DELIVERY_REVISION_CLAIM_LOST");
    }
}
