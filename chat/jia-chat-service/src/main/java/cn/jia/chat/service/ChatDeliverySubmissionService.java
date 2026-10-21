package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Browser-independent submit only. Final/marker/retained sources are immutable server facts,
 * never a browser-provided selection or an instruction to accept a delivery.
 * Both existing gates remain default-off; enabling is a separate validated release action. */
@Service
@ConditionalOnProperty(prefix="chat",name={"delivery-review.enabled","selected-output-finalization.enabled"},havingValue="true")
public class ChatDeliverySubmissionService {
    private static boolean submissionKind(String kind, Object deliverable) {
        return "ANSWER".equals(kind) && Boolean.TRUE.equals(deliverable)
                || "SUBMISSION_REQUEST".equals(kind) && Boolean.FALSE.equals(deliverable);
    }
    public static final String EVENT="DELIVERY_REQUESTED";
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ChatDeliberationDao dao;
    private final ChatTypedDeliberationStore typed;
    private final ObjectProvider<ChatActionFinalService> finals;
    private final ChatTypedReviewContextStore contexts;
    private final ChatSelectedOutputFinalizationStore operations;
    // Resolve only in outbox delivery, never while wiring the final/admission graph.
    private final ObjectProvider<ChatSelectedOutputFinalizationService> submissions;
    private final ChatTaskDeliveryReviewReadService reviews;
    private final AgentTaskMutationTransaction tasks;
    private final ChatBountyBindingStore bindings;
    private final ChatConversationDao conversations;
    private final JuyitingConversationScopeService conversationScopes;
    private final ChatDeliberationOutboxService outbox;
    private final ObjectProvider<ChatDeliberationService> deliberation;

    public ChatDeliverySubmissionService(ChatDeliberationDao dao,ChatTypedDeliberationStore typed,
            ObjectProvider<ChatActionFinalService> finals,ChatTypedReviewContextStore contexts,
            ChatSelectedOutputFinalizationStore operations,ObjectProvider<ChatSelectedOutputFinalizationService> submissions,
            ChatTaskDeliveryReviewReadService reviews,AgentTaskMutationTransaction tasks,ChatBountyBindingStore bindings,
            ChatConversationDao conversations,JuyitingConversationScopeService conversationScopes,ChatDeliberationOutboxService outbox,
            ObjectProvider<ChatDeliberationService> deliberation) {
        this.dao=Objects.requireNonNull(dao);this.typed=Objects.requireNonNull(typed);this.finals=Objects.requireNonNull(finals);
        this.contexts=Objects.requireNonNull(contexts);this.operations=Objects.requireNonNull(operations);
        this.submissions=Objects.requireNonNull(submissions);this.reviews=Objects.requireNonNull(reviews);
        this.tasks=Objects.requireNonNull(tasks);this.bindings=Objects.requireNonNull(bindings);
        this.conversations=Objects.requireNonNull(conversations);this.conversationScopes=Objects.requireNonNull(conversationScopes);
        this.outbox=Objects.requireNonNull(outbox);this.deliberation=Objects.requireNonNull(deliberation);
    }
    private ChatFormalDeliveryRetentionService retention;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setRetention(ChatFormalDeliveryRetentionService value) {retention=Objects.requireNonNull(value);}

    public record Candidate(ChatTypedDeliberationStore.Outcome outcome,ChatTypedDeliberationStore.Admission admission,
            String targetAgentId,List<Map<String,Object>> targets) {
        public Candidate {targets=targets.stream().map(Map::copyOf).toList();}
    }
    public static final class Rejected extends RuntimeException {
        private final String code;
        Rejected(String code) {super(code);this.code=code;}
        public String code() {return code;}
    }

    /** Joins the original assistant-message/final/outcome transaction; no provider or source I/O here. */
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void enqueue(ChatTypedDeliberationStore.Outcome row,ChatActionFinalValidator.ValidatedFinal validated,long now) {
        var union=validated.interactionOutcome();
        if(!submissionKind(union.kind(), union.deliverable())
                || !"CHAT".equals(validated.binding().get("route")) || !row.finalDigest().equals(validated.finalDigest())
                || !row.turnId().equals(validated.binding().get("turnId")))throw new IllegalArgumentException("DELIVERY_CANDIDATE_INVALID");
        var event=new ChatDispatchOutboxEntity().setEventId(eventId(row)).setTenantId(row.scope().tenantId())
                .setOwnerJiacn(row.scope().ownerJiacn()).setClientId(row.scope().clientId()).setTurnId(row.turnId())
                .setDispatchId((String)validated.binding().get("dispatchId")).setEventType(EVENT).setStatus("READY")
                .setPayloadJson(CanonicalContextJson.write(payload(row,(String)validated.binding().get("targetAgentId"))))
                .setVersion(0L).setAvailableAt(now).setAttemptCount(0).setFencingToken(0L).setCreatedAt(now).setUpdatedAt(now);
        if(dao.insertOutbox(event)!=1)throw storage();
    }

    /** Reconstructs the final using the ordinary strict reader, then replays only its verified retained set. */
    @Transactional(readOnly=true)
    public Candidate load(ChatDispatchOutboxEntity event) {
        try {return loadCandidate(event);}
        catch(ChatDeliberationException failure) {
            if(failure.reason()==ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN || failure.reason()==ChatDeliberationException.Reason.CONFLICT)
                throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
            throw failure; // Missing/corrupt persistence and transport failures are not authorization denials.
        }
    }
    private Candidate loadCandidate(ChatDispatchOutboxEntity event) {
        if(event==null || !EVENT.equals(event.getEventType()))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
        var turn=dao.findTurn(event.getTenantId(),event.getOwnerJiacn(),event.getClientId(),event.getTurnId());
        if(turn==null || !Objects.equals(event.getDispatchId(),turn.getDispatchId()) || !Objects.equals(event.getTurnId(),turn.getTurnId())
                || !Objects.equals(event.getTenantId(),turn.getTenantId()) || !Objects.equals(event.getOwnerJiacn(),turn.getOwnerJiacn())
                || !Objects.equals(event.getClientId(),turn.getClientId()) || !"CHAT".equals(turn.getRoute())
                || turn.getConversationGeneration()==null || turn.getRequestRevision()==null)throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
        var scope=new ChatTypedDeliberationStore.Scope(turn.getTenantId(),turn.getOwnerJiacn(),turn.getClientId(),turn.getConversationId(),turn.getConversationGeneration());
        var view=finals.getObject().readActionIfPresent(scope,turn.getRequestId(),turn.getTurnId(),turn.getRequestRevision(),"CHAT");
        var row=typed.findOutcomeByTurn(scope,turn.getTurnId(),false);
        if(view==null || !"READY".equals(view.get("state")) || !(view.get("outcome") instanceof Map<?,?> outcome)
                || !submissionKind((String)outcome.get("kind"), outcome.get("deliverable")) || row==null
                || !scope.equals(row.scope()) || !Objects.equals(row.kind(),outcome.get("kind")) || !row.requestId().equals(turn.getRequestId())
                || row.requestRevision()!=turn.getRequestRevision() || !row.outcomeId().equals(outcome.get("outcomeId"))
                || !row.finalDigest().equals(turn.getFinalDigest()) || !row.finalDigest().equals(outcome.get("finalDigest"))
                || !eventId(row).equals(event.getEventId())
                || !CanonicalContextJson.write(payload(row,turn.getTargetAgentId())).equals(CanonicalContextJson.write(parse(event.getPayloadJson()))))
            throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
        var admission=typed.findAdmissionByRequest(scope,row.requestId());
        if(admission==null || !scope.equals(admission.scope()) || !row.taskId().equals(admission.taskId())
                || row.requestRevision()!=admission.requestRevision()
                || row.assignmentRevision()!=admission.assignmentRevision())throw storage();
        var targets=finals.getObject().deliveryTargets(scope,Map.of("outcomeId",row.outcomeId(),"finalDigest",row.finalDigest()));
        if(targets.isEmpty())throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
        return new Candidate(row,admission,turn.getTargetAgentId(),targets);
    }

    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public cn.jia.chat.deliberation.ChatConversationEventEntity consume(ChatDeliberationOutboxService.Claim claim) {
        Candidate candidate=load(claim.row());
        var row=candidate.outcome();var scope=row.scope();String key=claim.row().getEventId();
        // REQUIRED root transaction freezes the FIRST command once. Restarts load it unchanged;
        // a later task version or different retained set is never silently substituted.
        var operation=tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),row.taskId(),root->{
            var existing=operations.findByKey(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.taskId(),key);
            if(existing!=null) {
                if(existing.contractVersion()!=2)throw rejected("DELIVERY_CONTRACT_CHANGED");
                return existing;
            }
            if(contexts.require(scope,row.requestId(),row.requestRevision())!=null)throw rejected("DELIVERY_REVIEW_REQUIRED");
            if(root.getRewardStatus()==null || !Set.of("planning","assigned","running","blocked").contains(root.getRewardStatus())
                    || root.getTaskVersion()==null || !candidate.targetAgentId().equals(root.getAssignedAgentId()))throw rejected("DELIVERY_TASK_CHANGED");
            var binding=bindings.lock(new ChatBountyBindingStore.Scope(scope.tenantId(),scope.ownerJiacn(),scope.clientId()),row.taskId());
            if(binding==null || binding.conversationId()==null || !scope.conversationId().equals(Long.toString(binding.conversationId()))
                    || row.assignmentRevision()!=binding.assignmentRevision())throw rejected("DELIVERY_ASSIGNMENT_CHANGED");
            var conversation=conversations.lockScopedById(scope.ownerJiacn(),scope.clientId(),scope.conversationId());
            if(conversation==null || conversation.getDeletedAt()!=null || !scope.tenantId().equals(conversation.getTenantId())
                    || !scope.ownerJiacn().equals(conversation.getJiacn()) || !scope.clientId().equals(conversation.getClientId())
                    || conversation.getId()==null || !scope.conversationId().equals(Long.toString(conversation.getId()))
                    || !Objects.equals(scope.conversationGeneration(),conversation.getLifecycleGeneration())
                    || !"juyiting".equals(conversation.getConversationType()) || !"bounty".equals(conversation.getConversationScopeType())
                    || !row.taskId().equals(conversation.getTaskId()) || !("task:"+row.taskId()).equals(conversation.getConversationScopeKey())
                    || !List.of(candidate.targetAgentId()).equals(conversationScopes.parsePersistedTargetAgentIds(conversation.getTargetAgentIds())))
                throw rejected("DELIVERY_CONVERSATION_CHANGED");
            var review=reviews.read(new ChatDeliveryReviewContract.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),row.taskId());
            if(review.currentDelivery()!=null)throw rejected("DELIVERY_REVIEW_REQUIRED");
            if(review.pendingOperation()!=null)throw rejected("DELIVERY_OPERATION_CONFLICT");
            if(!Long.toString(root.getTaskVersion()).equals(review.taskVersion()) || !row.taskId().equals(review.taskId())
                    || !Long.toString(row.assignmentRevision()).equals(review.assignmentRevision()))throw rejected("DELIVERY_ASSIGNMENT_CHANGED");
            var selections=selections(candidate);
            var lineage=retention==null?null:retention.lineage(scope,row.taskId(),row.requestId());
            if(lineage!=null) {
                try {retention.requireReplacementProof(scope,row.taskId(),lineage,selections);}
                catch(ChatDeliveryReviewContract.SourceUnavailable invalid) {throw rejected("DELIVERY_SOURCE_UNAVAILABLE");}
                selections=retention.retain(lineage,selections);
            }
            var locked=outbox.requireActiveClaim(claim,System.currentTimeMillis());
            if(!candidate.equals(load(locked)))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
            String summary="本轮议事成果";
            String digest=SelectedOutputFinalizationDigest.requestV2(row.taskId(),root.getTaskVersion(),row.assignmentRevision(),scope.conversationId(),summary,
                    selections.stream().map(s->new SelectedOutputFinalizationDigest.Selection(s.requestId(),s.stepId(),s.outputId(),s.sha256(),s.title(),s.purpose(),s.messageSource(),s.formalArtifactSource())).toList());
            return operations.create(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),ChatSelectedOutputFinalizationService.operationId(new ChatSelectedOutputFinalizationService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),row.taskId(),key),row.taskId(),key,digest,
                    scope.conversationId(),root.getTaskVersion(),row.assignmentRevision(),summary,selections);
        });
        if(!claim.active())throw storage();
        var command=new ChatSelectedOutputFinalizationService.Command(operation.expectedTaskVersion(),operation.expectedAssignmentRevision(),
                operation.conversationId(),operation.summary(),operation.selections().stream().map(s->new ChatSelectedOutputFinalizationService.Selection(
                        s.requestId(),s.stepId(),s.outputId(),s.sha256(),s.title(),s.purpose(),s.messageSource(),s.formalArtifactSource())).toList());
        // Existing promotion checks bytes, current owner/grant/lease and reconciles a prior durable
        // commit before stale-source checks. It submits; it never accepts.
        var submitScope=new ChatSelectedOutputFinalizationService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn());
        var lineage=retention==null?null:retention.lineage(scope,row.taskId(),row.requestId());
        var submissionService=submissions.getObject();
        var receipt=lineage==null?submissionService.submit(submitScope,row.taskId(),key,command):submissionService.submitRevision(submitScope,row.taskId(),key,command,lineage);
        if(!operation.operationId().equals(receipt.operationId()))throw storage();
        if("completed".equals(receipt.state()) && "SUBMITTED".equals(receipt.stage()) && Set.of("submitted","accepted","changes_requested").contains(receipt.deliveryState())
                && receipt.deliveryId()!=null) {
            return tasks.executeWithLockedTaskRootInOwnerScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),row.taskId(),root->{
                long now=System.currentTimeMillis();
                // Event and settlement commit together. Consumers re-read the immutable final plus
                // mutable submission projection; the hint itself is not acceptance authority.
                var event=deliberation.getObject().persistDeliverySubmissionCompleted(row,key,receipt.deliveryId(),now);
                if(!outbox.sent(claim,now))throw storage();
                return event;
            });
        } else if("failed".equals(receipt.state()) && !receipt.retryable()) {
            if(!outbox.dead(claim,receipt.errorCode()==null?"DELIVERY_SUBMISSION_REJECTED":receipt.errorCode(),System.currentTimeMillis()))throw storage();
            return null;
        } else throw storage(); // Retain the original event/key for transient retry; no version/key regeneration.
    }

    public void reject(ChatDeliberationOutboxService.Claim claim,Rejected failure) {
        if(!outbox.dead(claim,failure.code(),System.currentTimeMillis()))throw storage();
    }

    /** Mutable, read-only submission facts. The caller has already verified this actual final;
     * never call load()/consume() here (load itself uses the ordinary final reader). */
    @Transactional(readOnly=true)
    public Map<String,Object> read(ChatTypedDeliberationStore.Outcome row,ChatActionFinalValidator.ValidatedFinal validated) {
        if(row==null || validated==null || !submissionKind(validated.interactionOutcome().kind(), validated.interactionOutcome().deliverable()) || !"CHAT".equals(validated.binding().get("route")))return null;
        if(!row.finalDigest().equals(validated.finalDigest()) || !row.turnId().equals(validated.binding().get("turnId")))throw storage();
        var scope=row.scope();String key=eventId(row);
        var event=dao.findOutboxById(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),key);
        if(event==null)return null; // Pre-cutover history is audit only, not an invitation to create an event.
        if(!scope.tenantId().equals(event.getTenantId()) || !scope.ownerJiacn().equals(event.getOwnerJiacn())
                || !scope.clientId().equals(event.getClientId()) || !key.equals(event.getEventId()) || !row.turnId().equals(event.getTurnId())
                || !Objects.equals(validated.binding().get("dispatchId"),event.getDispatchId()) || !EVENT.equals(event.getEventType())
                || event.getVersion()==null || event.getVersion()<0 || event.getStatus()==null || !Set.of("READY","CLAIMED","RETRY","SENT","DEAD").contains(event.getStatus()))throw storage();
        try {
            if(!CanonicalContextJson.write(payload(row,(String)validated.binding().get("targetAgentId"))).equals(CanonicalContextJson.write(parse(event.getPayloadJson()))))throw storage();
        }catch(Rejected malformed) {throw storage();}
        operations.task(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.taskId()); // Current root owner, including historical operation reads.
        var operation=operations.findByKey(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.taskId(),key);
        String state="PENDING",deliveryId=null,error=null,operationId=null,operationVersion=null;boolean retryable=false;
        if(operation!=null) {
            operationId=ChatSelectedOutputFinalizationService.operationId(new ChatSelectedOutputFinalizationService.Scope(scope.tenantId(),scope.clientId(),scope.ownerJiacn()),row.taskId(),key);
            if(!operationId.equals(operation.operationId()) || !row.taskId().equals(operation.taskId()) || !key.equals(operation.key())
                    || !scope.conversationId().equals(operation.conversationId()) || row.assignmentRevision()!=operation.expectedAssignmentRevision()
                    || operation.stateVersion()<1 || operation.selections()==null || operation.selections().isEmpty())throw storage();
            if(operation.contractVersion()!=1 && operation.contractVersion()!=2)throw storage();
            var frozen=operation.selections().stream().map(s->new SelectedOutputFinalizationDigest.Selection(s.requestId(),s.stepId(),s.outputId(),
                    s.sha256(),s.title(),s.purpose(),s.messageSource(),s.formalArtifactSource())).toList();
            String digest=operation.contractVersion()==1?SelectedOutputFinalizationDigest.request(row.taskId(),operation.expectedTaskVersion(),operation.expectedAssignmentRevision(),
                    operation.conversationId(),operation.summary(),frozen):SelectedOutputFinalizationDigest.requestV2(row.taskId(),operation.expectedTaskVersion(),operation.expectedAssignmentRevision(),
                    operation.conversationId(),operation.summary(),frozen);
            if(!digest.equals(operation.digest()))throw storage();
            operationVersion=Long.toString(operation.stateVersion());
            switch(operation.state()) {
                case "completed" -> {
                    if(!"SUBMITTED".equals(operation.stage()) || operation.deliveryId()==null || operation.deliveryId().isBlank()
                            || operation.deliveryState()==null || !Set.of("submitted","accepted","changes_requested").contains(operation.deliveryState())
                            || operation.errorCode()!=null || operation.retryable() || "DEAD".equals(event.getStatus()))throw storage();
                    state="COMPLETED";deliveryId=operation.deliveryId();
                }
                case "failed" -> {
                    if(operation.errorCode()==null)throw storage();
                    if(!operation.retryable()) {state="FAILED";error=publicError(operation.errorCode());}
                    else retryable=true;
                }
                case "pending" -> {if(operation.errorCode()!=null)throw storage();}
                default -> throw storage();
            }
        }
        if("SENT".equals(event.getStatus()) && !"COMPLETED".equals(state))throw storage();
        if("DEAD".equals(event.getStatus())) {state="FAILED";error=publicError(event.getLastError());retryable=false;}
        var result=new LinkedHashMap<String,Object>();result.put("eventId",key);result.put("operationId",operationId);result.put("state",state);
        result.put("dispatchVersion",Long.toString(event.getVersion()));result.put("operationStateVersion",operationVersion);
        result.put("deliveryId",deliveryId);result.put("errorCode",error);result.put("retryable",retryable);
        return Collections.unmodifiableMap(result);
    }
    private static String publicError(String reason) {
        return reason!=null && Set.of("DELIVERY_SOURCE_UNAVAILABLE","DELIVERY_REVIEW_REQUIRED","DELIVERY_TASK_CHANGED","DELIVERY_ASSIGNMENT_CHANGED",
                "DELIVERY_CONVERSATION_CHANGED","DELIVERY_OPERATION_CONFLICT").contains(reason)?reason:"DELIVERY_SUBMISSION_REJECTED";
    }

    /** Fixed text references or exact byte-backed outputs only. Formal-artifact retention is
     * supplied by the separately specified v2 promotion branch, never guessed from history here. */
    List<ChatSelectedOutputFinalizationStore.Selection> selections(Candidate candidate) {
        var scope=candidate.outcome().scope();var result=new ArrayList<ChatSelectedOutputFinalizationStore.Selection>();var unique=new java.util.HashSet<String>();
        for(var target:candidate.targets()) {
            String title="议事成果 "+(result.size()+1);
            if(target.containsKey("text")) {
                if(!target.keySet().equals(Set.of("outcomeId","finalDigest","text")))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                var source=typed.findOutcome(scope,(String)target.get("outcomeId"),false);
                if(source==null || !scope.equals(source.scope()) || !candidate.outcome().taskId().equals(source.taskId())
                        || source.assignmentRevision()!=candidate.outcome().assignmentRevision() || !"ANSWER".equals(source.kind())
                        || !source.finalDigest().equals(target.get("finalDigest")) || !source.text().equals(target.get("text")))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                var turn=dao.findTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),source.turnId());
                if(turn==null || !Objects.equals(turn.getFinalMessageId(),source.assistantMessageId()) || turn.getSnapshotId()==null
                        || !source.requestId().equals(turn.getRequestId()) || !source.finalDigest().equals(turn.getFinalDigest()))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                String identity="text:"+source.outcomeId();if(!unique.add(identity))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                result.add(new ChatSelectedOutputFinalizationStore.Selection(source.requestId(),null,null,
                        ChatDeliveryReviewContract.sha(source.text()),title,"result",new SelectedOutputFinalizationDigest.MessageSource(
                                source.turnId(),Long.toString(source.assistantMessageId()),turn.getSnapshotId(),source.finalDigest())));
            } else {
                if(!target.keySet().equals(Set.of("outcomeId","finalDigest","outputSource")) || !(target.get("outputSource") instanceof Map<?,?> output)
                        || !output.keySet().equals(Set.of("requestId","stepId","outputId","sha256")))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                String request=reference(output,"requestId"),step=reference(output,"stepId"),id=reference(output,"outputId");
                Object hash=output.get("sha256");if(!(hash instanceof String sha) || !sha.matches("[0-9a-f]{64}"))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                if(!unique.add("output:"+CanonicalContextJson.write(List.of(request,step,id))))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
                result.add(new ChatSelectedOutputFinalizationStore.Selection(request,step,id,sha,title,"result"));
            }
        }
        return List.copyOf(result);
    }
    public static String eventId(ChatTypedDeliberationStore.Outcome row) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String value:List.of(row.scope().tenantId(),row.scope().clientId(),row.scope().ownerJiacn(),row.turnId(),row.finalDigest())) {
                byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(8).putLong(bytes.length).array());digest.update(bytes);
            }
            return "delivery-submit-"+HexFormat.of().formatHex(digest.digest()).substring(0,48);
        } catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    static Map<String,Object> payload(ChatTypedDeliberationStore.Outcome row,String target) {
        var p=new LinkedHashMap<String,Object>();p.put("schemaVersion",1);p.put("taskId",row.taskId());p.put("requestId",row.requestId());
        p.put("requestRevision",Long.toString(row.requestRevision()));p.put("turnId",row.turnId());p.put("outcomeId",row.outcomeId());
        p.put("finalDigest",row.finalDigest());p.put("assignmentRevision",Long.toString(row.assignmentRevision()));
        p.put("conversationId",row.scope().conversationId());p.put("conversationGeneration",Long.toString(row.scope().conversationGeneration()));p.put("targetAgentId",target);
        return Collections.unmodifiableMap(p);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> parse(String raw) {
        try {return JSON.readValue(raw,Map.class);}catch(Exception invalid) {throw rejected("DELIVERY_SOURCE_UNAVAILABLE");}
    }
    private static String reference(Map<?,?> value,String key) {
        Object raw=value.get(key);if(!(raw instanceof String text))throw rejected("DELIVERY_SOURCE_UNAVAILABLE");
        try {ChatDeliveryReviewContract.id(text,100);}catch(RuntimeException invalid) {throw rejected("DELIVERY_SOURCE_UNAVAILABLE");}return text;
    }
    private static Rejected rejected(String code) {return new Rejected(code);}
    private static IllegalStateException storage() {return new IllegalStateException("DELIVERY_SUBMISSION_UNAVAILABLE");}
}
