package cn.jia.chat.service;

import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
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

/** INSPECT v2 final validator/persistence sibling; it grants no execution or source authority. */
@Service
public class ChatTypedInspectionService {
    public record Prepared(TypedInspectionFinalValidator.ValidatedFinal validated,
            ChatTypedDeliberationStore.Scope scope,ChatTypedDeliberationStore.Admission admission,
            String envelopeJson,ChatTypedDeliberationStore.Outcome existing){}
    public record Persisted(ChatTypedDeliberationWire.Outcome view,Map<String,Object> eventView){}
    private final ChatTypedDeliberationStore store;private final TypedInspectionSessionRegistry sessions;
    private final ChatConversationArchiveStore archive;
    public ChatTypedInspectionService(ChatTypedDeliberationStore store,TypedInspectionSessionRegistry sessions,
            ChatConversationArchiveStore archive){this.store=Objects.requireNonNull(store);this.sessions=Objects.requireNonNull(sessions);this.archive=Objects.requireNonNull(archive);}

    public Prepared prepare(ChatTurnEntity turn,ChatContextSnapshotEntity snapshot,String content,
            Integer version,String rawOutcome,String rawReceipt){
        Map<String,Object> facts=parseMap(snapshot.getFactsManifestJson());boolean marker=facts.containsKey("typedInspection");
        if(!marker){if(version!=null||rawOutcome!=null||rawReceipt!=null)throw invalid("Typed sidecar is forbidden for a plain turn");return null;}
        if(facts.containsKey("typedDeliberation"))throw persistence("Snapshot has conflicting typed contracts");
        if(version==null||version!=2||rawOutcome==null||rawReceipt==null||!"INSPECT".equals(turn.getRoute()))throw invalid("Inspection v2 final sidecar is required");
        Map<String,Object> typed=map(facts.get("typedInspection"));validateTypedMarker(typed);
        Map<String,Object> manifest=map(typed.get("manifest"));Map<String,Object> profile=map(manifest.get("profile"));
        try{var ready=sessions.requireSingleReady(new TypedInspectionSessionRegistry.Scope(turn.getTenantId(),turn.getOwnerJiacn(),turn.getClientId()),turn.getTargetAgentId());
            if(!CanonicalContextJson.write(profile).equals(CanonicalContextJson.write(ready.manifestProfile())))throw unavailable();}
        catch(IllegalStateException stale){throw unavailable();}
        Object discussion=typed.get("discussionFacts");String taskId=taskId(facts);
        var binding=new TypedInspectionFinalValidator.Binding(turn.getTenantId(),turn.getOwnerJiacn(),turn.getClientId(),
                turn.getConversationId(),Long.toString(turn.getConversationGeneration()),turn.getRequestId(),
                Long.toString(turn.getRequestRevision()),turn.getTurnId(),turn.getDispatchId(),turn.getSnapshotId(),
                turn.getContextDigest(),turn.getTargetAgentId(),turn.getRoute(),taskId);
        Map<String,Object> authorityFacts=new LinkedHashMap<>();authorityFacts.put("authorizationId",typed.get("authorizationId"));
        authorityFacts.put("manifestDigest",typed.get("manifestDigest"));authorityFacts.put("sources",manifest.get("sources"));
        TypedInspectionFinalValidator.ValidatedFinal validated;
        try{validated=TypedInspectionFinalValidator.validateJson(binding,discussion,authorityFacts,content,rawOutcome,rawReceipt);}
        catch(TypedInspectionFinalValidator.ValidationException invalid){throw invalid(invalid.code());}
        var scope=scope(turn);var admission=store.findAdmissionByRequest(scope,turn.getRequestId());if(admission==null)throw unavailable();
        requireInspectionAdmission(admission);verifyAdmission(admission,turn);if(!taskId.equals(admission.taskId()))throw persistence("Inspection admission task differs from snapshot");
        if(!CanonicalContextJson.write(typed).equals(CanonicalContextJson.write(ChatTypedInspectionContextService.inspection(admission.sourceCatalogJson()))))throw persistence("Inspection admission differs from snapshot");
        var existing=store.findOutcomeByTurn(scope,turn.getTurnId(),true);
        if((turn.getFinalDigest()==null)!=(existing==null))throw persistence("Inspection final transaction is incomplete");
        if(existing!=null&&!existing.finalDigest().equals(validated.finalDigest()))throw conflict("Inspection final already differs");
        return new Prepared(validated,scope,admission,admission.sourceCatalogJson(),existing);
    }

    public String outcomeId(Prepared p){if(p.existing()!=null)return p.existing().outcomeId();return stable("inspection-outcome",p.scope().tenantId(),p.scope().ownerJiacn(),p.scope().clientId(),p.validated().binding().turnId());}
    public Persisted persist(Prepared p,long messageId,long now){if(p.existing()!=null)return projection(p.existing());var v=p.validated();var union=v.interactionOutcome();String id=outcomeId(p);
        Map<String,Object> stored=new LinkedHashMap<>();stored.put("schemaVersion",2);stored.put("interactionOutcome",outcomeMap(union));stored.put("inspectionInputReceipt",receiptMap(v.inspectionInputReceipt()));
        var outcome=new ChatTypedDeliberationStore.Outcome(id,p.scope(),v.binding().requestId(),Long.parseLong(v.binding().requestRevision()),v.binding().turnId(),v.binding().taskId(),p.admission().assignmentRevision(),messageId,v.finalDigest(),union.kind(),union.text(),CanonicalContextJson.write(bindingMap(v.binding())),CanonicalContextJson.write(factsMap(v.dispatchFacts())),CanonicalContextJson.write(stored),p.envelopeJson(),now);
        if(store.insertOutcome(outcome)!=1)throw persistence("Unable to persist inspection outcome");
        if("CLARIFY".equals(union.kind())){String question=stable("typed-question",id);var c=union.clarification();if(store.insertPending(new ChatTypedDeliberationStore.PendingQuestion(question,id,p.scope(),"OPEN",0,c.question(),CanonicalContextJson.write(c.requiredFacts()),null,null,null,now,now))!=1)throw persistence("Unable to persist inspection question");}
        else if("EXECUTION_PROPOSAL".equals(union.kind())){var proposal=union.proposal();List<Map<String,Object>> selected=selectedSources(p.envelopeJson(),proposal.sourceRefIds());String parentRequest=null,parentStep=null;if("EDIT_IMAGE".equals(proposal.operation())){if(selected.size()!=1)throw invalid("Inspection edit source is invalid");Map<String,Object> selector=map(selected.getFirst().get("selector"));var actual=archive.findAuthorizedSourceForUpdate(new ChatConversationArchiveStore.Scope(p.scope().tenantId(),p.scope().ownerJiacn(),p.scope().clientId()),p.scope().conversationId(),(String)selector.get("assetId"),Long.parseLong((String)selector.get("assetRevision")),v.binding().targetAgentId());if(actual==null||actual.conversationGeneration()!=p.scope().conversationGeneration()||!v.binding().taskId().equals(actual.taskId())||actual.requestId()==null||actual.stepId()==null)throw conflict("Inspection edit source changed");parentRequest=actual.requestId();parentStep=actual.stepId();}List<Object> selectors=selected.stream().map(item->item.get("selector")).toList();if(store.insertProposal(new ChatTypedDeliberationStore.Proposal(stable("typed-proposal",id),id,p.scope(),"PROPOSED",0,proposal.operation(),proposal.instruction(),CanonicalContextJson.write(proposal.sourceRefIds()),CanonicalContextJson.write(selectors),parentRequest,parentStep,now))!=1)throw persistence("Unable to persist inspection proposal");}
        return projection(outcome);
    }

    @Transactional(readOnly=true)
    public ChatTypedInspectionWire.Projection read(ChatTypedDeliberationStore.Scope scope,String requestId,String turnId,long revision){
        var admission=store.findAdmissionByRequest(scope,requestId);if(admission==null)throw unavailable();requireInspectionAdmission(admission);
        verifyAdmission(admission,requestId,revision,turnId);Map<String,Object> typed=ChatTypedInspectionContextService.inspection(admission.sourceCatalogJson());
        List<Map<String,Object>> sources=ChatTypedInspectionContextService.sources(admission.sourceCatalogJson());
        var outcome=store.findOutcomeByRequest(scope,requestId);ChatTypedDeliberationWire.Outcome view=null;ChatTypedInspectionWire.InputSummary summary=null;String state="PENDING";
        if(outcome!=null){if(!turnId.equals(outcome.turnId())||revision!=outcome.requestRevision())throw unavailable();view=projection(outcome).view();summary=inputSummary(outcome.outcomeJson());state="READY";}
        return new ChatTypedInspectionWire.Projection(2,ChatTypedInspectionWire.CONTRACT,scope.conversationId(),Long.toString(scope.conversationGeneration()),requestId,Long.toString(revision),turnId,state,view,new ChatTypedInspectionWire.Inspection((String)typed.get("authorizationId"),(String)typed.get("manifestDigest"),sources.stream().map(source->(String)source.get("sourceRefId")).toList(),summary));
    }

    private Persisted projection(ChatTypedDeliberationStore.Outcome outcome){ChatTypedInspectionContextService.parseEnvelope(outcome.sourceCatalogJson());ChatTypedDeliberationWire.Clarification clarification=null;ChatTypedDeliberationWire.Proposal proposal=null;
        if("CLARIFY".equals(outcome.kind())){var pending=store.findPendingByOutcome(outcome.scope(),outcome.outcomeId(),false);if(pending==null)throw persistence("Inspection question is missing");clarification=new ChatTypedDeliberationWire.Clarification(pending.pendingQuestionId(),pending.state(),Long.toString(pending.stateVersion()),pending.question(),parseStrings(pending.requiredFactsJson()),pending.replyRequestId());}
        if("EXECUTION_PROPOSAL".equals(outcome.kind())){var row=store.findProposalByOutcome(outcome.scope(),outcome.outcomeId());if(row==null)throw persistence("Inspection proposal is missing");proposal=new ChatTypedDeliberationWire.Proposal(row.proposalId(),row.state(),Long.toString(row.stateVersion()),row.operation(),row.instruction(),parseStrings(row.sourceRefIdsJson()),parseSelectors(row.sourceSelectorsJson()),row.parentRequestId()==null?null:new ChatTypedDeliberationWire.Parent(row.parentRequestId(),row.parentStepId()));}
        var view=new ChatTypedDeliberationWire.Outcome(outcome.outcomeId(),outcome.taskId(),Long.toString(outcome.assignmentRevision()),Long.toString(outcome.assistantMessageId()),outcome.finalDigest(),outcome.kind(),outcome.text(),clarification,proposal);Map<String,Object> event=new LinkedHashMap<>();event.put("outcomeContractVersion",2);event.put("outcomeId",view.outcomeId());event.put("taskId",view.taskId());event.put("assignmentRevision",view.assignmentRevision());event.put("assistantMessageId",view.assistantMessageId());event.put("finalDigest",view.finalDigest());event.put("kind",view.kind());event.put("text",view.text());event.put("clarification",view.clarification());event.put("proposal",view.proposal());return new Persisted(view,java.util.Collections.unmodifiableMap(event));}
    @SuppressWarnings("unchecked") private static ChatTypedInspectionWire.InputSummary inputSummary(String json){try{Map<String,Object> outer=JsonUtil.getMapper().readValue(json,Map.class);if(!Integer.valueOf(2).equals(outer.get("schemaVersion")))throw new IllegalArgumentException();Map<String,Object> receipt=(Map<String,Object>)outer.get("inspectionInputReceipt");List<Map<String,Object>> raw=(List<Map<String,Object>>)receipt.get("sources");List<ChatTypedInspectionWire.InputSource> sources=raw.stream().map(v->new ChatTypedInspectionWire.InputSource((String)v.get("sourceRefId"),(String)v.get("sha256"),(String)v.get("byteLength"),(String)v.get("carrier"),(String)v.get("contributionDigest"))).toList();return new ChatTypedInspectionWire.InputSummary((String)receipt.get("inputDigest"),sources);}catch(Exception e){throw persistence("Stored inspection outcome is invalid");}}
    private static void validateTypedMarker(Map<String,Object> typed){if(!Integer.valueOf(1).equals(typed.get("schemaVersion"))||!ChatTypedInspectionWire.CONTRACT.equals(typed.get("contract"))||!"INSPECT".equals(typed.get("purpose"))||!(typed.get("discussionFacts") instanceof Map<?,?>)||!(typed.get("manifest") instanceof Map<?,?>)||!(typed.get("manifestDigest") instanceof String digest)||!digest.matches("sha256:[0-9a-f]{64}")||!(typed.get("authorizationId") instanceof String))throw persistence("Stored inspection marker is invalid");Map<String,Object> manifest=map(typed.get("manifest"));if(!digest.equals(ChatDeliberationService.digest(manifest)))throw persistence("Stored inspection manifest digest differs");}
    private static void requireInspectionAdmission(ChatTypedDeliberationStore.Admission a){ChatTypedInspectionContextService.parseEnvelope(a.sourceCatalogJson());}
    private static void verifyAdmission(ChatTypedDeliberationStore.Admission a,ChatTurnEntity t){verifyAdmission(a,t.getRequestId(),t.getRequestRevision(),t.getTurnId());}
    private static void verifyAdmission(ChatTypedDeliberationStore.Admission a,String request,long revision,String turn){List<String> turns=parseStrings(a.turnIdsJson());if(!request.equals(a.requestId())||revision!=a.requestRevision()||turns.size()!=1||!turn.equals(turns.getFirst()))throw persistence("Inspection admission differs from request");}
    private static List<Map<String,Object>> selectedSources(String envelope,List<String> ids){Map<String,Map<String,Object>> by=new LinkedHashMap<>();for(var source:ChatTypedInspectionContextService.sources(envelope))by.put((String)source.get("sourceRefId"),source);List<Map<String,Object>> out=new ArrayList<>();for(String id:ids){var value=by.get(id);if(value==null)throw invalid("Unknown inspection source");out.add(value);}return List.copyOf(out);}
    @SuppressWarnings("unchecked") private static Map<String,Object> parseMap(String json){try{Object v=JsonUtil.getMapper().readValue(json,Map.class);if(v instanceof Map<?,?> m)return(Map<String,Object>)m;}catch(Exception ignored){}throw persistence("Stored inspection facts are invalid");}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){if(value instanceof Map<?,?> map)return(Map<String,Object>)map;throw persistence("Stored inspection object is invalid");}
    private static String taskId(Map<String,Object> facts){Object task=facts.get("task");if(task instanceof Map<?,?> map&&map.get("id") instanceof String id&&!id.isBlank())return id;throw unavailable();}
    private static ChatTypedDeliberationStore.Scope scope(ChatTurnEntity t){return new ChatTypedDeliberationStore.Scope(t.getTenantId(),t.getOwnerJiacn(),t.getClientId(),t.getConversationId(),t.getConversationGeneration());}
    private static Map<String,Object> bindingMap(TypedInspectionFinalValidator.Binding b){Map<String,Object> m=new LinkedHashMap<>();m.put("tenantId",b.tenantId());m.put("ownerJiacn",b.ownerJiacn());m.put("clientId",b.clientId());m.put("conversationId",b.conversationId());m.put("conversationGeneration",b.conversationGeneration());m.put("requestId",b.requestId());m.put("requestRevision",b.requestRevision());m.put("turnId",b.turnId());m.put("dispatchId",b.dispatchId());m.put("snapshotId",b.snapshotId());m.put("contextDigest",b.contextDigest());m.put("targetAgentId",b.targetAgentId());m.put("route",b.route());m.put("taskId",b.taskId());return m;}
    private static Map<String,Object> factsMap(TypedInspectionFinalValidator.DispatchFacts f){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("referenceMode",f.referenceMode());m.put("supportedOperations",f.supportedOperations());m.put("availableSources",f.availableSources().stream().map(s->Map.of("sourceRefId",s.sourceRefId(),"kind",s.kind(),"mediaType",s.mediaType())).toList());return m;}
    private static Map<String,Object> outcomeMap(TypedInspectionFinalValidator.InteractionOutcome o){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",2);m.put("kind",o.kind());m.put("text",o.text());m.put("clarification",o.clarification()==null?null:Map.of("question",o.clarification().question(),"requiredFacts",o.clarification().requiredFacts()));m.put("proposal",o.proposal()==null?null:Map.of("operation",o.proposal().operation(),"instruction",o.proposal().instruction(),"sourceRefIds",o.proposal().sourceRefIds()));return m;}
    private static Map<String,Object> receiptMap(TypedInspectionFinalValidator.InspectionInputReceipt r){Map<String,Object> m=new LinkedHashMap<>();m.put("schemaVersion",1);m.put("authorizationId",r.authorizationId());m.put("manifestDigest",r.manifestDigest());m.put("inputDigest",r.inputDigest());m.put("engineThreadId",r.engineThreadId());m.put("engineTurnId",r.engineTurnId());m.put("sources",r.sources().stream().map(s->Map.of("sourceRefId",s.sourceRefId(),"sha256",s.sha256(),"byteLength",s.byteLength(),"carrier",s.carrier(),"contributionDigest",s.contributionDigest())).toList());return m;}
    @SuppressWarnings("unchecked") private static List<String> parseStrings(String json){try{Object v=JsonUtil.getMapper().readValue(json,List.class);if(v instanceof List<?> l&&l.stream().allMatch(String.class::isInstance))return(List<String>)l;}catch(Exception ignored){}throw persistence("Stored inspection array is invalid");}
    @SuppressWarnings("unchecked") private static List<ChatTypedDeliberationWire.SourceSelector> parseSelectors(String json){try{List<Map<String,Object>> list=JsonUtil.getMapper().readValue(json,List.class);List<ChatTypedDeliberationWire.SourceSelector> out=new ArrayList<>();for(var m:list)out.add(new ChatTypedDeliberationWire.SourceSelector((String)m.get("kind"),(String)m.get("fileId"),(String)m.get("version"),(String)m.get("purpose"),(String)m.get("assetId"),(String)m.get("assetRevision")));return List.copyOf(out);}catch(Exception e){throw persistence("Stored inspection selectors are invalid");}}
    private static String stable(String prefix,String...parts){return prefix.substring(0,Math.min(prefix.length(),18))+"_"+sha256(String.join("\0",parts)).substring(0,40);}
    private static String sha256(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ChatDeliberationException invalid(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.INVALID_REQUEST,m);}
    private static ChatDeliberationException unavailable(){return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"Typed inspection is unavailable");}
    private static ChatDeliberationException conflict(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.CONFLICT,m);}
    private static ChatDeliberationException persistence(String m){return new ChatDeliberationException(ChatDeliberationException.Reason.PERSISTENCE_ERROR,m);}
}
