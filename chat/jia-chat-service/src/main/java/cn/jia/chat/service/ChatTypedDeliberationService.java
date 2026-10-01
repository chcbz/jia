package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates and persists typed final sidecars. It creates no grant, consent, step or execution. */
@Service
public class ChatTypedDeliberationService {
    public record Prepared(TypedDeliberationFinalValidator.ValidatedFinal validated,
            ChatTypedDeliberationStore.Scope scope, ChatTypedDeliberationStore.Admission admission,
            String sourceCatalogJson, ChatTypedDeliberationStore.Outcome existing) { }
    public record Persisted(ChatTypedDeliberationWire.Outcome view, Map<String,Object> eventView) { }

    private final ChatTypedDeliberationStore store;
    public ChatTypedDeliberationService(ChatTypedDeliberationStore store){this.store=Objects.requireNonNull(store);}

    public Prepared prepare(ChatTurnEntity turn, ChatContextSnapshotEntity snapshot, String content,
            Integer outcomeContractVersion, String rawOutcomeJson) {
        Map<String,Object> facts=parseMap(snapshot.getFactsManifestJson());
        boolean marker=facts.containsKey("typedDeliberation");
        if(!marker){if(outcomeContractVersion!=null||rawOutcomeJson!=null)throw invalid("Typed sidecar is forbidden for a plain turn");return null;}
        if(outcomeContractVersion==null||outcomeContractVersion!=1||rawOutcomeJson==null)throw invalid("Typed final sidecar is required");
        Object typedFacts=facts.get("typedDeliberation");
        String taskId=taskId(facts);
        var binding=new TypedDeliberationFinalValidator.Binding(turn.getTenantId(),turn.getOwnerJiacn(),
                turn.getClientId(),turn.getConversationId(),Long.toString(turn.getConversationGeneration()),
                turn.getRequestId(),Long.toString(turn.getRequestRevision()),turn.getTurnId(),turn.getDispatchId(),
                turn.getSnapshotId(),turn.getContextDigest(),turn.getTargetAgentId(),turn.getRoute(),taskId);
        TypedDeliberationFinalValidator.ValidatedFinal validated;
        try{validated=TypedDeliberationFinalValidator.validateJson(binding,typedFacts,content,rawOutcomeJson);}
        catch(TypedDeliberationFinalValidator.ValidationException error){throw invalid(error.code());}
        var scope=scope(turn);
        var admission=store.findAdmissionByRequest(scope,turn.getRequestId());
        if(admission==null)throw unavailable();
        verifyAdmission(admission,turn.getRequestId(),turn.getRequestRevision(),turn.getTurnId());
        if(!taskId.equals(admission.taskId()))throw persistence("Typed admission task differs from snapshot");
        verifyCatalog(scope,admission.sourceCatalogJson(), validated.dispatchFacts());
        var existing=store.findOutcomeByTurn(scope,turn.getTurnId(),true);
        if ((turn.getFinalDigest() == null) != (existing == null))
            throw persistence("Typed final transaction is incomplete");
        if(existing!=null&&!existing.finalDigest().equals(validated.finalDigest()))throw conflict("Typed final already differs");
        return new Prepared(validated,scope,admission,admission.sourceCatalogJson(),existing);
    }


    public String outcomeId(Prepared prepared) {
        if (prepared.existing() != null) return prepared.existing().outcomeId();
        var b=prepared.validated().binding();
        return stable("typed-outcome",prepared.scope().tenantId(),prepared.scope().ownerJiacn(),
                prepared.scope().clientId(),b.turnId());
    }

    public Persisted persist(Prepared prepared,long assistantMessageId,long now){
        if(prepared==null)return null;
        if(prepared.existing()!=null)return projection(prepared.existing());
        var v=prepared.validated();var union=v.interactionOutcome();String outcomeId=outcomeId(prepared);
        var outcome=new ChatTypedDeliberationStore.Outcome(outcomeId,prepared.scope(),v.binding().requestId(),
                Long.parseLong(v.binding().requestRevision()),v.binding().turnId(),v.binding().taskId(),
                prepared.admission().assignmentRevision(),assistantMessageId,v.finalDigest(),union.kind(),union.text(),
                CanonicalContextJson.write(bindingMap(v.binding())),CanonicalContextJson.write(factsMap(v.dispatchFacts())),
                CanonicalContextJson.write(outcomeMap(union)),prepared.sourceCatalogJson(),now);
        if(store.insertOutcome(outcome)!=1)throw persistence("Unable to persist typed outcome");
        if("CLARIFY".equals(union.kind())){
            String id=stable("typed-question",outcomeId);var clarification=union.clarification();
            var pending=new ChatTypedDeliberationStore.PendingQuestion(id,outcomeId,prepared.scope(),"OPEN",0,
                    clarification.question(),CanonicalContextJson.write(clarification.requiredFacts()),null,null,null,now,now);
            if(store.insertPending(pending)!=1)throw persistence("Unable to persist typed question");
        } else if("EXECUTION_PROPOSAL".equals(union.kind())){
            String id=stable("typed-proposal",outcomeId);var proposal=union.proposal();
            List<Map<String,Object>> selected=selectedSources(prepared.sourceCatalogJson(),proposal.sourceRefIds());
            String parentRequest=null,parentStep=null;
            if("EDIT_IMAGE".equals(proposal.operation())&&!selected.isEmpty()){
                parentRequest=(String)selected.getFirst().get("parentRequestId");parentStep=(String)selected.getFirst().get("parentStepId");
            }
            List<Object> selectors=selected.stream().map(item->item.get("selector")).toList();
            var row=new ChatTypedDeliberationStore.Proposal(id,outcomeId,prepared.scope(),"PROPOSED",0,
                    proposal.operation(),proposal.instruction(),CanonicalContextJson.write(proposal.sourceRefIds()),
                    CanonicalContextJson.write(selectors),parentRequest,parentStep,now);
            if(store.insertProposal(row)!=1)throw persistence("Unable to persist typed proposal");
        }
        return projection(outcome);
    }

    @Transactional(readOnly=true)
    public ChatTypedDeliberationWire.TypedProjection read(ChatTypedDeliberationStore.Scope scope,String requestId,
            String expectedTurnId,long requestRevision){
        var admission=store.findAdmissionByRequest(scope,requestId);if(admission==null)throw unavailable();
        verifyAdmission(admission,requestId,requestRevision,expectedTurnId);
        var outcome=store.findOutcomeByRequest(scope,requestId);
        if(outcome==null)return new ChatTypedDeliberationWire.TypedProjection(1,scope.conversationId(),
                Long.toString(scope.conversationGeneration()),requestId,Long.toString(requestRevision),expectedTurnId,"PENDING",null);
        if(!expectedTurnId.equals(outcome.turnId())||outcome.requestRevision()!=requestRevision)throw unavailable();
        return new ChatTypedDeliberationWire.TypedProjection(1,scope.conversationId(),
                Long.toString(scope.conversationGeneration()),requestId,Long.toString(requestRevision),outcome.turnId(),"READY",projection(outcome).view());
    }

    public ChatTypedDeliberationStore.Outcome requireParent(ChatTypedDeliberationStore.Scope scope,String outcomeId){
        var value=store.findOutcome(scope,outcomeId,true);if(value==null)throw unavailable();return value;
    }
    public ChatTypedDeliberationStore.PendingQuestion requirePending(ChatTypedDeliberationStore.Scope scope,String pendingId){
        var value=store.findPending(scope,pendingId,true);if(value==null)throw unavailable();return value;
    }
    public ChatTypedDeliberationStore.Proposal proposal(ChatTypedDeliberationStore.Scope scope,String outcomeId){return store.findProposalByOutcome(scope,outcomeId);}
    public ChatTypedDeliberationStore store(){return store;}

    private Persisted projection(ChatTypedDeliberationStore.Outcome outcome){
        ChatTypedDeliberationWire.Clarification clarification=null;ChatTypedDeliberationWire.Proposal proposal=null;
        if("CLARIFY".equals(outcome.kind())){var pending=store.findPendingByOutcome(outcome.scope(),outcome.outcomeId(),false);if(pending==null)throw persistence("Typed question is missing");clarification=new ChatTypedDeliberationWire.Clarification(pending.pendingQuestionId(),pending.state(),Long.toString(pending.stateVersion()),pending.question(),parseStrings(pending.requiredFactsJson()),pending.replyRequestId());}
        if("EXECUTION_PROPOSAL".equals(outcome.kind())){var row=store.findProposalByOutcome(outcome.scope(),outcome.outcomeId());if(row==null)throw persistence("Typed proposal is missing");proposal=new ChatTypedDeliberationWire.Proposal(row.proposalId(),row.state(),Long.toString(row.stateVersion()),row.operation(),row.instruction(),parseStrings(row.sourceRefIdsJson()),parseSelectors(row.sourceSelectorsJson()),row.parentRequestId()==null?null:new ChatTypedDeliberationWire.Parent(row.parentRequestId(),row.parentStepId()));}
        var view=new ChatTypedDeliberationWire.Outcome(outcome.outcomeId(),outcome.taskId(),Long.toString(outcome.assignmentRevision()),Long.toString(outcome.assistantMessageId()),outcome.finalDigest(),outcome.kind(),outcome.text(),clarification,proposal);
        Map<String,Object> event=new LinkedHashMap<>();event.put("outcomeId",view.outcomeId());event.put("taskId",view.taskId());event.put("assignmentRevision",view.assignmentRevision());event.put("assistantMessageId",view.assistantMessageId());event.put("finalDigest",view.finalDigest());event.put("kind",view.kind());event.put("text",view.text());event.put("clarification",view.clarification());event.put("proposal",view.proposal());
        return new Persisted(view,java.util.Collections.unmodifiableMap(event));
    }

    private static void verifyAdmission(ChatTypedDeliberationStore.Admission admission,String requestId,
            long requestRevision,String turnId) {
        List<String> turns=parseStrings(admission.turnIdsJson());
        if(!requestId.equals(admission.requestId())||admission.requestRevision()!=requestRevision
                ||turns.size()!=1||!turnId.equals(turns.getFirst())) {
            throw persistence("Typed admission receipt differs from request");
        }
    }

    private static void verifyCatalog(ChatTypedDeliberationStore.Scope scope,String catalogJson,
            TypedDeliberationFinalValidator.DispatchFacts facts) {
        List<Map<String,Object>> catalog=ChatTypedDeliberationContextService.parseCatalog(catalogJson);
        if(catalog.size()!=facts.availableSources().size())throw persistence("Typed source catalogue differs from snapshot");
        Map<String,Map<String,Object>> byId=new LinkedHashMap<>();
        var sourceScope=new ChatTypedDeliberationContextService.Scope(scope.tenantId(),scope.ownerJiacn(),
                scope.clientId(),scope.conversationId(),scope.conversationGeneration());
        for(Map<String,Object> item:catalog){Object id=item.get("sourceRefId");
            if(!(id instanceof String sourceId)||!sourceId.equals(ChatTypedDeliberationContextService.sourceRefId(sourceScope,item))
                    ||byId.put(sourceId,item)!=null)throw persistence("Typed source catalogue is ambiguous");}
        for(var source:facts.availableSources()){Map<String,Object> item=byId.remove(source.sourceRefId());if(item==null
                ||!source.kind().equals(item.get("sourceKind"))||!source.mediaType().equals(item.get("mediaType")))
            throw persistence("Typed source catalogue differs from snapshot");}
        if(!byId.isEmpty())throw persistence("Typed source catalogue differs from snapshot");
    }

    private static List<Map<String,Object>> selectedSources(String catalogJson,List<String> ids){List<Map<String,Object>> catalog=ChatTypedDeliberationContextService.parseCatalog(catalogJson);Map<String,Map<String,Object>> byId=new LinkedHashMap<>();for(var item:catalog)byId.put((String)item.get("sourceRefId"),item);List<Map<String,Object>> result=new ArrayList<>();for(String id:ids){var item=byId.get(id);if(item==null)throw invalid("Unknown typed source");result.add(item);}return List.copyOf(result);}
    @SuppressWarnings("unchecked") private static Map<String,Object> parseMap(String json){try{Object v=JsonUtil.getMapper().readValue(json,Map.class);if(v instanceof Map<?,?> m)return(Map<String,Object>)m;}catch(Exception ignored){}throw persistence("Stored typed facts are invalid");}
    private static String taskId(Map<String,Object> facts){Object task=facts.get("task");if(task instanceof Map<?,?> map&&map.get("id") instanceof String id&&!id.isBlank())return id;throw unavailable();}
    private static ChatTypedDeliberationStore.Scope scope(ChatTurnEntity t){return new ChatTypedDeliberationStore.Scope(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getConversationId(),t.getConversationGeneration());}
    private static Map<String,Object> bindingMap(TypedDeliberationFinalValidator.Binding b){Map<String,Object> m=new LinkedHashMap<>();m.put("tenantId",b.tenantId());m.put("ownerJiacn",b.ownerJiacn());m.put("clientId",b.clientId());m.put("conversationId",b.conversationId());m.put("conversationGeneration",b.conversationGeneration());m.put("requestId",b.requestId());m.put("requestRevision",b.requestRevision());m.put("turnId",b.turnId());m.put("dispatchId",b.dispatchId());m.put("snapshotId",b.snapshotId());m.put("contextDigest",b.contextDigest());m.put("targetAgentId",b.targetAgentId());m.put("route",b.route());m.put("taskId",b.taskId());return m;}
    private static Map<String,Object> factsMap(TypedDeliberationFinalValidator.DispatchFacts f){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("referenceMode",f.referenceMode());m.put("supportedOperations",f.supportedOperations());m.put("availableSources",f.availableSources().stream().map(s->Map.of("sourceRefId",s.sourceRefId(),"kind",s.kind(),"mediaType",s.mediaType())).toList());return m;}
    private static Map<String,Object> outcomeMap(TypedDeliberationFinalValidator.InteractionOutcome o){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("kind",o.kind());m.put("text",o.text());m.put("clarification",o.clarification()==null?null:Map.of("question",o.clarification().question(),"requiredFacts",o.clarification().requiredFacts()));m.put("proposal",o.proposal()==null?null:Map.of("operation",o.proposal().operation(),"instruction",o.proposal().instruction(),"sourceRefIds",o.proposal().sourceRefIds()));return m;}
    @SuppressWarnings("unchecked") private static List<String> parseStrings(String json){try{Object v=JsonUtil.getMapper().readValue(json,List.class);if(v instanceof List<?> l&&l.stream().allMatch(String.class::isInstance))return(List<String>)l;}catch(Exception ignored){}throw persistence("Stored typed array is invalid");}
    @SuppressWarnings("unchecked") private static List<ChatTypedDeliberationWire.SourceSelector> parseSelectors(String json){try{Object v=JsonUtil.getMapper().readValue(json,List.class);if(!(v instanceof List<?> list))throw new IllegalArgumentException();List<ChatTypedDeliberationWire.SourceSelector> r=new ArrayList<>();for(Object item:list){Map<String,Object> m=(Map<String,Object>)item;r.add(new ChatTypedDeliberationWire.SourceSelector((String)m.get("kind"),(String)m.get("fileId"),(String)m.get("version"),(String)m.get("purpose"),(String)m.get("assetId"),(String)m.get("assetRevision")));}return List.copyOf(r);}catch(Exception e){throw persistence("Stored typed selectors are invalid");}}
    private static String stable(String prefix,String...parts){return prefix.substring(0,Math.min(prefix.length(),16))+"_"+sha256(String.join("\0",parts)).substring(0,40);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ChatDeliberationException invalid(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,m);}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed discussion is unavailable");}
    private static ChatDeliberationException conflict(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,m);}
    private static ChatDeliberationException persistence(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,m);}
}
