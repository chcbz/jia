package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV2EffectStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentRuntimeV2EffectMapper;
import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2EffectStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.text;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.bytes;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.same;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Read-only durable R4 graph verification. No recovery ACL, executable
 * permission, live() exception, mutation, recursive execution-graph validation
 * or network operation is provided by this class. Missing tables/proofs fail
 * closed. Callers acquire original authority/execution locks BEFORE this graph.
 */
public final class AgentRuntimeV2HistoricalSettlementEvidenceValidator {
    // Immutable default codec configuration; reuse its serializer/parser
    // metadata, not any evidence, row, ACL decision or transaction snapshot.
    private static final tools.jackson.databind.ObjectMapper JSON=new tools.jackson.databind.ObjectMapper();
    public static final class Graph {
        private final Map<String,Object> run;
        private final List<Map<String,Object>> effects,requests,events;
        private final Map<String,List<Map<String,Object>>> blobs;
        private final Map<Object,Map<String,Object>> requestsById,effectsById;
        private final Map<Object,List<Map<String,Object>>> requestsByEffect,eventsByEffect;
        public Graph(Map<String,Object> run,List<Map<String,Object>> effects,
                List<Map<String,Object>> requests,List<Map<String,Object>> events,
                Map<String,List<Map<String,Object>>> blobs) {
            this.run=run==null?null:copy(run);this.effects=effects.stream().map(AgentRuntimeV2HistoricalSettlementEvidenceValidator::copy).toList();
            this.requests=requests.stream().map(AgentRuntimeV2HistoricalSettlementEvidenceValidator::copy).toList();this.events=events.stream().map(AgentRuntimeV2HistoricalSettlementEvidenceValidator::copy).toList();
            var immutable=new LinkedHashMap<String,List<Map<String,Object>>>();blobs.forEach((id,rows)->immutable.put(id,rows.stream().map(AgentRuntimeV2HistoricalSettlementEvidenceValidator::copy).toList()));this.blobs=Collections.unmodifiableMap(immutable);
            // Membership indices belong ONLY to these freshly copied rows.
            // They contain no decoded receipt, mutable evidence-byte cache,
            // ACL result or accepted transaction/entry/finish validation.
            requestsById=index(this.requests,"request_id");effectsById=index(this.effects,"effect_id");
            requestsByEffect=group(this.requests.stream().filter(value->"EFFECT".equals(value.get("request_family"))).toList(),false);
            eventsByEffect=group(this.events,true);
        }
        private static Map<Object,Map<String,Object>> index(List<Map<String,Object>> values,String key){
            var indexed=new LinkedHashMap<Object,Map<String,Object>>();
            for(var value:values){Object id=value.get(key);check(id instanceof String&&indexed.put(id,value)==null);}
            return Collections.unmodifiableMap(indexed);
        }
        private static Map<Object,List<Map<String,Object>>> group(List<Map<String,Object>> values,boolean ordered){
            var grouped=new LinkedHashMap<Object,List<Map<String,Object>>>();
            for(var value:values){Object id=value.get("effect_id");check(id instanceof String);grouped.computeIfAbsent(id,ignored->new ArrayList<>()).add(value);}
            grouped.replaceAll((id,members)->{if(ordered)members.sort(Comparator.comparingLong(value->number(value,"event_sequence")));return List.copyOf(members);});
            return Collections.unmodifiableMap(grouped);
        }
        public Map<String,Object> run(){return run;}
        public List<Map<String,Object>> effects(){return effects;}
        public List<Map<String,Object>> requests(){return requests;}
        public List<Map<String,Object>> events(){return events;}
        public Map<String,List<Map<String,Object>>> blobs(){return blobs;}
        public Map<String,Object> request(Object id){return requestsById.get(id);}
        public Map<String,Object> effect(Object id){return effectsById.get(id);}
        private List<Map<String,Object>> effectRequests(Object id){return requestsByEffect.getOrDefault(id,List.of());}
        private List<Map<String,Object>> effectEvents(Object id){return eventsByEffect.getOrDefault(id,List.of());}
    }
    private final AgentRuntimeV2EffectStore store;
    public AgentRuntimeV2HistoricalSettlementEvidenceValidator(AgentRuntimeV2EffectStore store){this.store=Objects.requireNonNull(store);}

    public Graph inspect(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,
            AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,boolean lock) {
        String dispatch=origin.text("runtime_dispatch_id");
        var run=lock?store.lock(RUN,row("runtime_dispatch_id",dispatch)):store.hint(RUN,row("runtime_dispatch_id",dispatch));
        var effects=store.history(EFFECT,dispatch,lock);var requests=store.history(REQUEST,dispatch,lock);var events=store.history(EVENT,dispatch,lock);
        var blobs=new LinkedHashMap<String,List<Map<String,Object>>>();for(var effect:effects)check(blobs.put(text(effect,"effect_id"),new ArrayList<>())==null);
        for(var blob:store.dispatchBlobs(dispatch,lock)){var associated=blobs.get(text(blob,"effect_id"));check(associated!=null);associated.add(blob);check(associated.size()<=4096);}
        var graph=new Graph(run,effects,requests,events,blobs);validate(origin,rows,execution,graph);return graph;
    }
    public void validate(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,
            AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,Graph graph) {
        var started=execution.receipt("STARTED");String dispatch=origin.text("runtime_dispatch_id");
        if(graph.run()!=null){
            var run=graph.run();columns(RUN,run);check(text(run,"run_token").matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
            check(started!=null&&!execution.facts().isEmpty()&&same(run.get("runtime_dispatch_id"),dispatch)&&same(run.get("execution_origin_snapshot_sha256"),origin.get("execution_origin_snapshot_sha256"))&&same(run.get("dispatch_wire_sha256"),origin.get("dispatch_wire_sha256"))&&same(run.get("started_receipt_id"),started.get("receipt_id"))&&same(run.get("started_receipt_sha256"),started.get("receipt_wire_sha256"))&&same(run.get("original_start_lease_fact_id"),execution.facts().getFirst().get("lease_fact_id")));
            check(Set.of("ACTIVE","CANCELLING","DRAINED_KNOWN","EFFECT_UNKNOWN","SETTLED_KNOWN").contains(run.get("disposition"))&&Set.of(0L,1L).contains(number(run,"retired"))&&("ACTIVE".equals(run.get("disposition"))||number(run,"retired")==1));times(run);
        }else check(graph.effects().isEmpty()&&graph.events().isEmpty()&&graph.blobs().isEmpty());
        var requestIds=new HashSet<Object>();
        // One complete validation reads each immutable producer holder once.
        // This is scoped to this call, not retained across entry/finish/final
        // beforeCommit checks, transactions, ACL decisions or later requests.
        var actorFacts=new HashMap<Object,Map<String,Object>>();
        for(var request:graph.requests()){
            columns(REQUEST,request);check(requestIds.add(request.get("request_id"))&&same(request.get("runtime_dispatch_id"),dispatch));
            byte[] raw=rawBytes(request,"request_wire_bytes"),response=rawBytes(request,"response_wire_bytes");check(same(sha256(raw),request.get("request_sha256"))&&same(sha256(response),request.get("response_sha256")));
            var decoded=AgentRuntimeV2EffectCodec.decode(raw);var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);
            String family,id,target,effectId=null,subjectKind=null,subjectId=null;
            if(decoded instanceof AgentRuntimeV2EffectCodec.Inspect q){family="CONTROL";id=q.grant().requestId();target=q.grant().runtimeDispatchId();reference(q.grant(),execution);}
            else if(decoded instanceof AgentRuntimeV2EffectCodec.EffectRequest q){family="EFFECT";id=q.grant().requestId();target=q.grant().runtimeDispatchId();effectId=q.effectId();reference(q.grant(),execution);check(graph.run()!=null&&same(q.runToken(),graph.run().get("run_token")));}
            else {var q=(AgentRuntimeV2EffectCodec.Reconcile)decoded;family="RECONCILE";id=q.requestId();target=q.runtimeDispatchId();subjectKind=q.subjectKind().name();subjectId=q.subjectId();}
            check(same(id,request.get("request_id"))&&same(target,dispatch)&&same(family,request.get("request_family"))&&same(effectId,request.get("effect_id"))&&same(subjectKind,request.get("subject_kind"))&&same(subjectId,request.get("subject_id"))&&receipt.requestId().equals(id)&&receipt.runtimeDispatchId().equals(dispatch)&&receipt.requestSha256().equals(hex(sha256(raw)))&&receipt.messageType().equals("command.execution."+family.toLowerCase(Locale.ROOT)+".receipt")&&receipt.serverAt()==number(request,"created_at"));
            actor(origin,request,family,actorFacts);
            if(decoded instanceof AgentRuntimeV2EffectCodec.EffectRequest q){var effect=required(graph.effect(q.effectId()));check(same(effect.get("resource_wire_bytes"),AgentRuntimeV2EffectCodec.encodeResource(q.effectKind(),q.phase(),q.resource()))&&same(effect.get("run_token"),q.runToken())&&same(effect.get("effect_kind"),q.effectKind().name()));validateOutcome(q,receipt,effect,execution,graph);}
        }
        String stable=stableKey(origin);String semantic=hex(sha256(rawBytes(rows.delivery(),"command_payload")));var effectIds=new HashSet<Object>();
        for(var effect:graph.effects()){
            columns(EFFECT,effect);check(effectIds.add(effect.get("effect_id"))&&same(effect.get("runtime_dispatch_id"),dispatch)&&graph.run()!=null&&same(effect.get("run_token"),graph.run().get("run_token")));times(effect);
            byte[] raw=rawBytes(effect,"resource_wire_bytes");check(same(sha256(raw),effect.get("resource_sha256")));
            var originalRequests=graph.effectRequests(effect.get("effect_id"));check(!originalRequests.isEmpty());
            var first=originalRequests.stream().min(Comparator.comparingLong(q->number(q,"created_at"))).orElseThrow();
            var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(first,"request_wire_bytes"));
            check(AgentRuntimeV2EffectCodec.effectId(stable,semantic,dispatch,text(graph.run(),"original_start_lease_fact_id"),q.runToken(),q.effectKind(),q.phase(),q.resource()).equals(effect.get("effect_id")));
            var grant=required(execution.grant(effect.get("execution_grant_id")));check(same(grant.get("grant_wire_sha256"),effect.get("execution_grant_sha256"))&&same(grant.get("holder_generation_fact_id"),effect.get("holder_generation_fact_id"))&&same(grant.get("current_lease_fact_id"),effect.get("current_lease_fact_id")));
            check(Set.of("DESCRIBED","STAGED","ADMITTED_IN_FLIGHT","SERVER_COMMITTED","LOCAL_COMMIT_OBSERVED","EXTERNAL_UNKNOWN","SETTLED_KNOWN").contains(effect.get("disposition")));
            if(effect.get("admission_request_id")!=null){var admission=required(graph.request(effect.get("admission_request_id")));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(admission,"response_wire_bytes"));check(receipt.result() instanceof AgentRuntimeV2EffectCodec.AdmittedResult);var admitted=(AgentRuntimeV2EffectCodec.AdmittedResult)receipt.result();check(admitted.effectId().equals(effect.get("effect_id"))&&admitted.resourceSha256().equals(hex(bytes(effect,"resource_sha256")))&&admitted.admittedAt()==number(effect,"admitted_at")&&admitted.validUntil()==number(effect,"valid_until"));}
            else check(effect.get("admitted_at")==null&&effect.get("valid_until")==null);
            if(effect.get("result_wire_bytes")!=null){
                byte[] result=rawBytes(effect,"result_wire_bytes");check(same(sha256(result),effect.get("result_sha256")));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(result);check(receipt.runtimeDispatchId().equals(dispatch));
                var request=required(graph.request(receipt.requestId()));check("EFFECT".equals(request.get("request_family"))&&same(request.get("effect_id"),effect.get("effect_id"))&&same(result,request.get("response_wire_bytes")));
                check(receipt.result() instanceof AgentRuntimeV2EffectCodec.CommittedResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.SettledResult);
                if(receipt.result() instanceof AgentRuntimeV2EffectCodec.CommittedResult committed)check(Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(effect.get("disposition"))&&same(effect.get("commit_receipt_id"),committed.commitReceiptId()));
                else if(receipt.result() instanceof AgentRuntimeV2EffectCodec.SettledResult settled)check("SETTLED_KNOWN".equals(effect.get("disposition"))&&same(effect.get("commit_receipt_id"),settled.commitReceiptId()));
            }
            else check(effect.get("result_sha256")==null&&effect.get("commit_receipt_id")==null);
            validateBlobs(effect,q,graph.blobs().get(text(effect,"effect_id")));
        }
        var previous=new HashMap<Object,Map<String,Object>>();var seenRequests=new HashSet<Object>();var responseRequests=new HashSet<Object>();var projections=new HashMap<Object,String>();var versions=new HashMap<Object,Long>();var authoritative=new HashMap<Object,byte[]>();
        for(var event:graph.events()){
            columns(EVENT,event);var effect=required(graph.effect(event.get("effect_id")));check(same(event.get("runtime_dispatch_id"),dispatch)&&same(event.get("run_token"),effect.get("run_token"))&&number(event,"created_at")>=number(effect,"created_at"));
            var prior=previous.put(event.get("effect_id"),event);check(number(event,"event_sequence")== (prior==null?0:Math.addExact(number(prior,"event_sequence"),1))&&same(event.get("previous_sha256"),prior==null?null:prior.get("event_sha256"))&&(prior==null||number(event,"created_at")>=number(prior,"created_at")));
            byte[] raw=rawBytes(event,"evidence_wire_bytes");check(same(sha256(raw),event.get("evidence_sha256"))&&same(eventHash(event),event.get("event_sha256")));
            String kind=text(event,"event_kind");check(Set.of("REQUEST_SENT","OUTCOME_OBSERVED","CANCELLED","ADMITTED_IN_FLIGHT","SERVER_COMMIT","LOCAL_COMMIT_OBSERVED","EXTERNAL_UNKNOWN","RECONCILIATION","SETTLED_KNOWN").contains(kind));
            if("REQUEST_SENT".equals(kind)){var request=required(graph.request(event.get("request_id")));check("EFFECT".equals(request.get("request_family"))&&same(request.get("effect_id"),event.get("effect_id"))&&same(raw,request.get("request_wire_bytes"))&&seenRequests.add(event.get("request_id")));}
            else if("SETTLED_KNOWN".equals(kind)){
                var request=required(graph.request(event.get("request_id")));check("RECONCILE".equals(request.get("request_family"))&&same(raw,request.get("response_wire_bytes"))&&number(event,"created_at")==number(request,"created_at"));
                var decoded=(AgentRuntimeV2EffectCodec.Reconcile)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);check(decoded.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EXECUTION&&decoded.subjectId().equals(dispatch)&&"SETTLED_KNOWN".equals(receipt.decision())&&receipt.result() instanceof AgentRuntimeV2EffectCodec.Reconciliation);
                var result=(AgentRuntimeV2EffectCodec.Reconciliation)receipt.result();check("SETTLED_KNOWN".equals(result.disposition())&&result.proof() instanceof AgentRuntimeV2EffectCodec.SettlementProof);
                String state=projections.get(event.get("effect_id"));check(state!=null&&!"SETTLED_KNOWN".equals(state)&&authoritative.get(event.get("effect_id"))!=null);projections.put(event.get("effect_id"),"SETTLED_KNOWN");versions.merge(event.get("effect_id"),1L,Math::addExact);
            }
            else if(Set.of("OUTCOME_OBSERVED","ADMITTED_IN_FLIGHT","SERVER_COMMIT").contains(kind)){
                var request=required(graph.request(event.get("request_id")));check(seenRequests.contains(event.get("request_id"))&&responseRequests.add(event.get("request_id"))&&same(request.get("effect_id"),event.get("effect_id"))&&same(raw,request.get("response_wire_bytes"))&&number(event,"created_at")==number(request,"created_at"));
                var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);String state=projections.getOrDefault(event.get("effect_id"),"DESCRIBED");
                if("SERVER_COMMIT".equals(kind)){
                    var actual=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));
                    check(actual.effectKind()==AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT&&actual.phase()==AgentRuntimeV2EffectCodec.Phase.COMMIT);
                    check("COMMITTED".equals(receipt.decision())&&receipt.result() instanceof AgentRuntimeV2EffectCodec.CommittedResult&&!Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(state)&&authoritative.putIfAbsent(event.get("effect_id"),raw)==null);state="SERVER_COMMITTED";
                }else if("ADMITTED_IN_FLIGHT".equals(kind)){
                    check("ADMITTED".equals(receipt.decision())&&receipt.result() instanceof AgentRuntimeV2EffectCodec.AdmittedResult&&"DESCRIBED".equals(state));state="ADMITTED_IN_FLIGHT";
                }else if("COMMITTED".equals(receipt.decision())||"SETTLED".equals(receipt.decision())){
                    // A distinct transport replay may observe the original
                    // known result but cannot mint another commit/settlement.
                    byte[] original=authoritative.get(event.get("effect_id"));check(original!=null&&AgentRuntimeV2EffectCodec.decodeReceipt(original).result().equals(receipt.result()));
                }else if("STAGED".equals(receipt.decision())){check(Set.of("DESCRIBED","STAGED","SETTLED_KNOWN").contains(state));if(!"SETTLED_KNOWN".equals(state))state="STAGED";authoritative.putIfAbsent(event.get("effect_id"),raw);}
                else if("UNKNOWN".equals(receipt.decision())){check(!Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(state));state="EXTERNAL_UNKNOWN";}
                else if("ADMITTED".equals(receipt.decision()))check(Set.of("ADMITTED_IN_FLIGHT","LOCAL_COMMIT_OBSERVED","SETTLED_KNOWN").contains(state));
                else if("OBSERVED".equals(receipt.decision())){var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));check(verifiedProcessObservation(graph,q,receipt));if(!"SETTLED_KNOWN".equals(state))state="LOCAL_COMMIT_OBSERVED";authoritative.putIfAbsent(event.get("effect_id"),raw);}
                else {check(Set.of("READ","DESCRIBED").contains(receipt.decision()));authoritative.putIfAbsent(event.get("effect_id"),raw);}
                projections.put(event.get("effect_id"),state);versions.merge(event.get("effect_id"),1L,Math::addExact);
            }else if("RECONCILIATION".equals(kind)){var request=required(graph.request(event.get("request_id")));check("RECONCILE".equals(request.get("request_family"))&&same(raw,request.get("response_wire_bytes")));}
            else {var observation=AgentRuntimeV2EffectCodec.decodeEvidence(raw);if(observation instanceof AgentRuntimeV2EffectCodec.LocalCommitObservation o)check(o.effectId().equals(event.get("effect_id"))&&o.runToken().equals(event.get("run_token"))&&o.resourceSha256().equals(hex(bytes(effect,"resource_sha256"))));else if(observation instanceof AgentRuntimeV2EffectCodec.WorkspaceTreeObservation o)check(o.effectId().equals(event.get("effect_id"))&&o.runToken().equals(event.get("run_token"))&&o.resourceSha256().equals(hex(bytes(effect,"resource_sha256"))));else if(observation instanceof AgentRuntimeV2EffectCodec.ProcessDrainObservation o)check(o.runToken().equals(event.get("run_token")));else if(observation instanceof AgentRuntimeV2EffectCodec.RequestUncertain o)check(o.runToken().equals(event.get("run_token")));else throw denied();}
        }
        for(var effect:graph.effects()){
            Object id=effect.get("effect_id");check(previous.containsKey(id)&&same(effect.get("disposition"),projections.get(id))&&number(effect,"version")==versions.getOrDefault(id,0L)&&number(effect,"updated_at")==number(previous.get(id),"created_at"));
            if(effect.get("result_wire_bytes")!=null)check(same(effect.get("result_wire_bytes"),authoritative.get(id)));
            else {check(effect.get("result_sha256")==null&&effect.get("commit_receipt_id")==null);byte[] success=authoritative.get(id);if(success!=null)check(Set.of("DESCRIBED","READ","STAGED","OBSERVED").contains(AgentRuntimeV2EffectCodec.decodeReceipt(success).decision()));}
        }
        for(var request:graph.requests())if("EFFECT".equals(request.get("request_family")))check(seenRequests.contains(request.get("request_id"))&&responseRequests.contains(request.get("request_id")));
        // A source reference is status evidence selected by the original event
        // sequence, not by the caller's optional transport correlation.
        for(var request:graph.requests())if("RECONCILE".equals(request.get("request_family"))){
            var q=(AgentRuntimeV2EffectCodec.Reconcile)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));
            var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(request,"response_wire_bytes"));
            check(receipt.result() instanceof AgentRuntimeV2EffectCodec.Reconciliation);
            var result=(AgentRuntimeV2EffectCodec.Reconciliation)receipt.result();
            check(result.subjectKind()==q.subjectKind()&&result.subjectId().equals(q.subjectId())&&same(result.originalRequestSha256(),q.originalRequest()==null?null:hex(sha256(q.originalRequest()))));
            if(q.subjectKind()==AgentRuntimeV2EffectCodec.Subject.RENEWAL&&q.originalRequest()!=null)validateRenewalObservation(origin,execution,q,receipt,result);
            if(q.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EFFECT_SUCCESS&&"SUCCESS_REFERENCE".equals(result.disposition())){
                var selected=originalSuccessReference(graph,required(graph.effect(q.subjectId())));check(selected!=null);
                check(result.proof() instanceof AgentRuntimeV2EffectCodec.EffectSuccessReference&&same(AgentRuntimeV2EffectCodec.encodeProof(selected),AgentRuntimeV2EffectCodec.encodeProof(result.proof())));
                var original=required(graph.request(selected.originalRequestId()));check(number(original,"created_at")<=number(request,"created_at"));
            }
        }
        if(graph.run()!=null&&"SETTLED_KNOWN".equals(graph.run().get("disposition"))){var settled=required(execution.latest());check("SETTLED".equals(settled.get("fact_type")));verifySettlementGraph(origin,rows,execution,graph,settled);}
    }

    /** Hash the complete actual immutable row, not a caller-selected subset. */
    public static String durableRowHash(Map<String,Object> value){var fields=new TreeMap<String,String>();value.forEach((key,item)->fields.put(key,item==null?null:item instanceof byte[] raw?hex(raw):Objects.toString(item)));return hex(sha256(JSON.writeValueAsBytes(fields)));}
    public static Map<String,Object> originalRenewalPredecessor(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,AgentRuntimeV2ExecutionCodec.Renew renewal){
        var started=required(execution.receipt("STARTED"));var prior=required(execution.grant(renewal.previousExecutionGrantId()));
        check(renewal.runtimeDispatchId().equals(origin.text("runtime_dispatch_id"))&&same(started.get("receipt_id"),renewal.startedReceiptId())&&hex(bytes(started,"receipt_wire_sha256")).equals(renewal.startedReceiptSha256())&&hex(bytes(prior,"grant_wire_sha256")).equals(renewal.previousExecutionGrantSha256()));return prior;
    }
    private void validateRenewalObservation(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,AgentRuntimeV2EffectCodec.Reconcile query,AgentRuntimeV2EffectCodec.Receipt receipt,AgentRuntimeV2EffectCodec.Reconciliation result){
        var renewal=AgentRuntimeV2ExecutionCodec.decodeRenew(query.originalRequest());var prior=originalRenewalPredecessor(origin,execution,renewal);String fingerprint=hex(sha256(query.originalRequest()));
        check(renewal.renewalRequestId().equals(query.subjectId()));
        if("UNKNOWN".equals(result.disposition())){check(result.proof()==null);return;}
        var mappings=execution.requests().stream().filter(value->same(value.get("execution_request_id"),renewal.renewalRequestId())).toList();check(mappings.size()<=1);
        if(result.proof() instanceof AgentRuntimeV2EffectCodec.RenewalMapping proof){
            check(mappings.size()==1);var mapping=mappings.getFirst();
            check(proof.requestId().equals(renewal.renewalRequestId())&&proof.requestSha256().equals(fingerprint)&&proof.runtimeDispatchId().equals(renewal.runtimeDispatchId())&&proof.startedReceiptId().equals(renewal.startedReceiptId())&&proof.startedReceiptSha256().equals(renewal.startedReceiptSha256())&&proof.previousExecutionGrantId().equals(renewal.previousExecutionGrantId())&&proof.previousExecutionGrantSha256().equals(renewal.previousExecutionGrantSha256())&&proof.mappingEventId().equals(renewal.renewalRequestId())&&proof.mappingEventSha256().equals(durableRowHash(mapping)));
            check("RENEWAL".equals(mapping.get("request_kind"))&&same(mapping.get("request_wire_bytes"),query.originalRequest())&&same(mapping.get("request_fingerprint"),sha256(query.originalRequest()))&&same(mapping.get("requested_holder_generation_fact_id"),prior.get("holder_generation_fact_id"))&&same(mapping.get("requested_holder_generation_snapshot_sha256"),prior.get("holder_generation_snapshot_sha256")));
            if("GRANTED_HISTORICAL".equals(result.disposition()))check("GRANTED".equals(mapping.get("decision"))&&same(proof.response(),required(execution.grant(mapping.get("execution_grant_id"))).get("grant_wire_bytes")));
            else check("DENIED_HISTORICAL".equals(result.disposition())&&"DENIED".equals(mapping.get("decision"))&&same(proof.response(),AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",renewal.renewalRequestId(),renewal.runtimeDispatchId(),renewal.startedReceiptId(),text(mapping,"denial_code"))));return;
        }
        check("NOT_COMMITTED".equals(result.disposition())&&mappings.isEmpty()&&result.proof() instanceof AgentRuntimeV2EffectCodec.RetirementBarrier);var proof=(AgentRuntimeV2EffectCodec.RetirementBarrier)result.proof();
        check(proof.requestId().equals(renewal.renewalRequestId())&&proof.requestSha256().equals(fingerprint)&&proof.runtimeDispatchId().equals(renewal.runtimeDispatchId())&&same(proof.retiredHolderGenerationFactId(),prior.get("holder_generation_fact_id"))&&proof.retiredHolderGenerationSnapshotSha256().equals(hex(bytes(prior,"holder_generation_snapshot_sha256"))));
        var history=store.jdbc().queryForList("SELECT * FROM agent_runtime_v2_binding_generation_fact WHERE runtime_binding_id=? ORDER BY fact_sequence",origin.get("runtime_binding_id"));
        var facts=new HashMap<Object,Map<String,Object>>();Object previous=null;long sequence=0;
        for(var fact:history){check(facts.put(fact.get("binding_generation_fact_id"),fact)==null&&number(fact,"fact_sequence")==sequence++&&same(fact.get("previous_fact_id"),previous));previous=fact.get("binding_generation_fact_id");}
        var retired=required(facts.get(proof.retiredHolderGenerationFactId()));var barrier=required(facts.get(proof.barrierEventId()));var current=required(facts.get(proof.currentHolderGenerationFactId()));
        for(var holder:List.of(retired,current)){check("CONNECTED".equals(holder.get("event_type")));var saved=store.jdbc().queryForList("SELECT * FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=?",holder.get("registration_receipt_id"));check(saved.size()==1);AgentRuntimeV2RegistrationServiceImpl.validateReceipt(saved.getFirst());AgentRuntimeV2RegistrationServiceImpl.validateHolder(holder,saved.getFirst());}
        check(proof.retiredHolderGenerationSnapshotSha256().equals(hex(bytes(retired,"generation_snapshot_sha256")))&&proof.currentHolderGenerationSnapshotSha256().equals(hex(bytes(current,"generation_snapshot_sha256")))&&"DISCONNECTED".equals(barrier.get("event_type"))&&same(barrier.get("previous_fact_id"),retired.get("binding_generation_fact_id"))&&number(barrier,"fact_sequence")==Math.addExact(number(retired,"fact_sequence"),1)&&same(barrier.get("binding_generation"),retired.get("binding_generation"))&&proof.barrierEventSha256().equals(durableRowHash(barrier))&&number(current,"binding_generation")>number(retired,"binding_generation")&&number(current,"fact_sequence")>number(barrier,"fact_sequence")&&number(barrier,"effective_at")>=number(retired,"effective_at")&&number(current,"effective_at")>=number(barrier,"effective_at")&&proof.checkedAt()>=number(current,"effective_at")&&proof.checkedAt()<=receipt.serverAt());
    }

    /** Typed request/result binding, independent of projection flags. A hash
     * of an unrelated but canonical receipt is not an effect outcome. */
    private static void validateOutcome(AgentRuntimeV2EffectCodec.EffectRequest request,AgentRuntimeV2EffectCodec.Receipt receipt,Map<String,Object> effect,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,Graph graph){
        var result=receipt.result();String fingerprint=hex(bytes(effect,"resource_sha256"));
        if(result instanceof AgentRuntimeV2EffectCodec.CommittedResult committed){
            check(request.effectKind()==AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT&&Set.of(AgentRuntimeV2EffectCodec.Phase.COMMIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE).contains(request.phase())&&request.resource() instanceof AgentRuntimeV2EffectCodec.FileCommit&&committed.effectId().equals(request.effectId())&&committed.resourceSha256().equals(fingerprint)&&committed.committedAt()<=receipt.serverAt());
            var resource=(AgentRuntimeV2EffectCodec.FileCommit)request.resource();var projection=committed.projection();check(resource.executionId().equals(projection.executionId())&&resource.runId().equals(projection.runId())&&resource.manifestId().equals(projection.manifestId())&&resource.manifestSha256().equals(projection.manifestSha256()));
        }else if(result instanceof AgentRuntimeV2EffectCodec.SettledResult settled){
            check(settled.effectId().equals(request.effectId())&&settled.resourceSha256().equals(fingerprint)&&settled.settledAt()<=receipt.serverAt());
        }else if(result instanceof AgentRuntimeV2EffectCodec.AdmittedResult admitted){
            // A distinct transport ADMIT may use a newer current grant but
            // returns the once-created ticket. Bind that ticket to its actual
            // original request/grant, never relabel it as a fresh admission.
            var admission=required(graph.request(effect.get("admission_request_id")));var original=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(admission,"request_wire_bytes"));var saved=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(admission,"response_wire_bytes"));
            var grant=required(execution.grant(original.grant().executionGrantId()));check(request.phase()==AgentRuntimeV2EffectCodec.Phase.ADMIT&&original.phase()==AgentRuntimeV2EffectCodec.Phase.ADMIT&&original.effectId().equals(request.effectId())&&same(admission.get("effect_id"),request.effectId())&&"EFFECT".equals(admission.get("request_family"))&&admitted.equals(saved.result())&&admitted.effectId().equals(request.effectId())&&admitted.resourceSha256().equals(fingerprint)&&admitted.admittedAt()<=saved.serverAt()&&admitted.admittedAt()<=receipt.serverAt()&&admitted.admittedAt()>=number(grant,"issued_at")&&admitted.admittedAt()<number(grant,"lease_until")&&admitted.validUntil()<=number(grant,"lease_until")&&same(admitted.holderGenerationFactId(),grant.get("holder_generation_fact_id"))&&same(admitted.currentLeaseFactId(),grant.get("current_lease_fact_id")));
        }else if(result instanceof AgentRuntimeV2EffectCodec.StagedResult staged){
            check(request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage);var resource=(AgentRuntimeV2EffectCodec.FileStage)request.resource();check(resource.outputId().equals(staged.stagedId())&&resource.contentSha256().equals(staged.contentSha256())&&resource.totalLength()==staged.length());
        }else if(result instanceof AgentRuntimeV2EffectCodec.ReadResult read){
            long offset,length;if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileRead resource){offset=resource.offset();length=resource.length();}else{check(request.resource() instanceof AgentRuntimeV2EffectCodec.SkillRead);var resource=(AgentRuntimeV2EffectCodec.SkillRead)request.resource();offset=resource.offset();length=resource.length();}check(offset==read.offset()&&length==read.length());
        }else if(result instanceof AgentRuntimeV2EffectCodec.DescribedResult){check(request.phase()==AgentRuntimeV2EffectCodec.Phase.DESCRIBE);}
        else if(result instanceof AgentRuntimeV2EffectCodec.ObservedResult observed){
            check(observed.effectId().equals(request.effectId())&&observed.resourceSha256().equals(fingerprint));
            if("OBSERVED".equals(receipt.decision())){
                check(verifiedProcessObservation(graph,request,receipt));var admission=processAdmission(graph,request.resource());var original=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(admission,"request_wire_bytes"));
                var admittedGrant=required(execution.grant(original.grant().executionGrantId()));var observingGrant=required(execution.grant(request.grant().executionGrantId()));check(same(admittedGrant.get("holder_generation_fact_id"),observingGrant.get("holder_generation_fact_id"))&&same(admission.get("actor_registration_fact_id"),required(graph.request(request.grant().requestId())).get("actor_registration_fact_id")));
            }
            if(observed.observation() instanceof AgentRuntimeV2EffectCodec.RequestUncertain uncertain)check(uncertain.requestId().equals(request.grant().requestId())&&uncertain.requestSha256().equals(hex(sha256(AgentRuntimeV2EffectCodec.encode(request))))&&uncertain.runToken().equals(request.runToken()));
        }else throw denied();
    }

    private static boolean sameProcessScope(AgentRuntimeV2EffectCodec.ProcessStart start,AgentRuntimeV2EffectCodec.Resource resource){
        if(resource instanceof AgentRuntimeV2EffectCodec.ProcessStart value)return start.equals(value);
        if(resource instanceof AgentRuntimeV2EffectCodec.ProcessExit value)return start.taskId().equals(value.taskId())&&Objects.equals(start.workItemId(),value.workItemId())&&start.engine().equals(value.engine())&&start.workspaceKey().equals(value.workspaceKey());return false;
    }
    /** Unique real original admission, not a newer transport/grant alias. */
    public static Map<String,Object> processAdmission(Graph graph,AgentRuntimeV2EffectCodec.Resource resource){
        var admissions=new ArrayList<Map<String,Object>>();
        for(var effect:graph.effects())if("PROCESS_START".equals(effect.get("effect_kind"))&&effect.get("admission_request_id")!=null){var saved=required(graph.request(effect.get("admission_request_id")));var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(saved,"request_wire_bytes"));check(q.phase()==AgentRuntimeV2EffectCodec.Phase.ADMIT&&q.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart);if(sameProcessScope((AgentRuntimeV2EffectCodec.ProcessStart)q.resource(),resource)){var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(saved,"response_wire_bytes"));check("ADMITTED".equals(receipt.decision())&&receipt.result() instanceof AgentRuntimeV2EffectCodec.AdmittedResult&&same(saved.get("actor_registration_fact_id"),((AgentRuntimeV2EffectCodec.AdmittedResult)receipt.result()).holderGenerationFactId()));admissions.add(saved);}}
        check(admissions.size()==1);return admissions.getFirst();
    }
    public static void verifyProcessExit(Graph graph,AgentRuntimeV2EffectCodec.EffectRequest request,AgentRuntimeV2EffectCodec.ProcessDrainObservation observation,long serverAt){
        check(request.phase()==AgentRuntimeV2EffectCodec.Phase.OBSERVE&&request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessExit);var resource=(AgentRuntimeV2EffectCodec.ProcessExit)request.resource();var admission=processAdmission(graph,resource);var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(admission,"request_wire_bytes"));var ticket=(AgentRuntimeV2EffectCodec.AdmittedResult)AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(admission,"response_wire_bytes")).result();var proof=observation.proof();
        check(q.runToken().equals(request.runToken())&&q.grant().runtimeDispatchId().equals(request.grant().runtimeDispatchId())&&q.grant().startedReceiptId().equals(request.grant().startedReceiptId())&&q.grant().startedReceiptSha256().equals(request.grant().startedReceiptSha256())&&request.runToken().equals(observation.runToken())&&proof.runToken().equals(request.runToken()));
        check("DRAINED_KNOWN".equals(proof.disposition())&&proof.startupReleased()&&proof.directExit()!=null&&proof.directClose()!=null&&proof.directExit().observedAt()>=ticket.admittedAt()&&proof.directClose().observedAt()>=proof.directExit().observedAt()&&proof.directClose().observedAt()<=observation.observedAt()&&observation.observedAt()<=serverAt&&Objects.equals(resource.exitCode(),proof.directClose().code())&&Objects.equals(resource.signal(),proof.directClose().signal()));
        var roots=proof.processes().stream().filter(p->p.pid()==resource.pid()&&p.startTime()==resource.startTime()).toList();check(roots.size()==1&&roots.getFirst().pinned()&&roots.getFirst().pidfdExitObserved()&&proof.namespaces().stream().anyMatch(ns->!ns.pidNamespace().equals(roots.getFirst().pidNamespace())&&ns.held()&&ns.pinned()&&ns.pidfdExitObserved()));
    }
    /** PROCESS_START/OBSERVE is a graph read, with NULL content. It cannot
     * invent drain evidence or resolve an admission without the actual exit. */
    public static AgentRuntimeV2EffectCodec.ProcessDrainObservation matchingProcessDrain(Graph graph,AgentRuntimeV2EffectCodec.EffectRequest request){
        check(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart&&request.content()==null);var admission=processAdmission(graph,request.resource());check(same(admission.get("effect_id"),request.effectId()));var matches=new ArrayList<AgentRuntimeV2EffectCodec.ProcessDrainObservation>();
        for(var effect:graph.effects())if("PROCESS_EXIT".equals(effect.get("effect_kind"))){byte[] raw=originalSuccess(graph,effect);if(raw==null)continue;var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);var saved=required(graph.request(receipt.requestId()));var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(saved,"request_wire_bytes"));if(sameProcessScope((AgentRuntimeV2EffectCodec.ProcessStart)request.resource(),q.resource())){check(same(saved.get("actor_registration_fact_id"),admission.get("actor_registration_fact_id")));matches.add((AgentRuntimeV2EffectCodec.ProcessDrainObservation)((AgentRuntimeV2EffectCodec.ObservedResult)receipt.result()).observation());}}
        check(matches.size()==1);return matches.getFirst();
    }
    private static boolean verifiedProcessObservation(Graph graph,AgentRuntimeV2EffectCodec.EffectRequest request,AgentRuntimeV2EffectCodec.Receipt receipt){
        if(!"OBSERVED".equals(receipt.decision())||!(receipt.result() instanceof AgentRuntimeV2EffectCodec.ObservedResult result)||!"PROCESS_DRAIN".equals(result.category())||!(result.observation() instanceof AgentRuntimeV2EffectCodec.ProcessDrainObservation observation)||request.phase()!=AgentRuntimeV2EffectCodec.Phase.OBSERVE)return false;
        check(result.effectId().equals(request.effectId())&&result.resourceSha256().equals(hex(sha256(AgentRuntimeV2EffectCodec.encodeResource(request.effectKind(),request.phase(),request.resource())))));
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessExit){check(request.content()!=null&&same(request.content(),AgentRuntimeV2EffectCodec.encodeEvidence(observation)));verifyProcessExit(graph,request,observation,receipt.serverAt());return true;}
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart){check(same(AgentRuntimeV2EffectCodec.encodeEvidence(matchingProcessDrain(graph,request)),AgentRuntimeV2EffectCodec.encodeEvidence(observation))&&observation.observedAt()<=receipt.serverAt());return true;}return false;
    }

    /** Complete file branch, not a SERVER_COMMITTED subset. Unsupported or
     * unresolved process/local effects return no completion. The caller then
     * retains UNKNOWN; it does not manufacture a failure or a no-run result. */
    public cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope completeFileScope(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,Graph graph){
        if(graph.run()==null||graph.effects().isEmpty()||!"0".equals(origin.get("tenant_id")))return null;
        var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(rawBytes(rows.delivery(),"command_payload"));if(!"WORK_ITEM_EXECUTE".equals(draft.commandType())||draft.workItemId()==null)return null;
        AgentRuntimeV2EffectCodec.CommitProjection projection=null;
        for(var effect:graph.effects()){
            if(!Set.of("WORKSPACE_PREPARE","FILE_DESCRIBE","FILE_INPUT_READ","FILE_STAGE","FILE_COMMIT","PROCESS_START","PROCESS_EXIT").contains(effect.get("effect_kind"))||Set.of("ADMITTED_IN_FLIGHT","EXTERNAL_UNKNOWN").contains(effect.get("disposition")))return null;
            byte[] raw=originalSuccess(graph,effect);if(raw==null)return null;var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);if(!Set.of("DESCRIBED","READ","STAGED","COMMITTED","OBSERVED").contains(receipt.decision()))return null;
            if(Set.of("PROCESS_START","PROCESS_EXIT").contains(effect.get("effect_kind"))&&!"OBSERVED".equals(receipt.decision()))return null;
            // The actual dispatcher asks the server to SELECT the branch.
            // A WORKSPACE_PREPARE descriptor selecting strict FILE is a real
            // description member, never ordinary workspace admission.
            if("WORKSPACE_PREPARE".equals(effect.get("effect_kind"))&&!(receipt.result() instanceof AgentRuntimeV2EffectCodec.DescribedResult value&&"WORKSPACE_FILE".equals(value.branch())))return null;
            if(receipt.result() instanceof AgentRuntimeV2EffectCodec.CommittedResult result){check(projection==null);projection=result.projection();}
        }
        for(var request:graph.requests())if("EFFECT".equals(request.get("request_family"))){var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(request,"response_wire_bytes"));if("ADMITTED".equals(receipt.decision())){if(q.effectKind()!=AgentRuntimeV2EffectCodec.Kind.PROCESS_START||originalSuccess(graph,required(graph.effect(q.effectId())))==null)return null;}else if(!Set.of("DESCRIBED","READ","STAGED","COMMITTED","OBSERVED").contains(receipt.decision()))return null;}
        if(projection==null)return null;
        return new cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope(origin.text("tenant_id"),origin.text("command_caller_client_id"),origin.text("owner_jiacn"),origin.text("agent_id"),draft.taskId(),draft.workItemId(),projection.executionId(),projection.runId());
    }
    /** SQL-only durable resource truth. Original preparation has verified real
     * storage bytes; a partial read proves only its original authorized chunk,
     * not an independent recalculation of the full-file digest. */
    public void verifyCompleteFile(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,Graph graph){
        var scope=completeFileScope(origin,rows,graph);check(scope!=null);
        scopedOne("agent_task_meta",scope,row("task_id",scope.taskId()));
        var execution=scopedOne("agent_personal_workspace_execution",scope,row("execution_id",scope.executionId()));
        check(same(execution.get("task_id"),scope.taskId())&&same(execution.get("work_item_id"),scope.workItemId())&&same(execution.get("run_id"),scope.runId())&&same(execution.get("target_agent_id"),scope.agentId())&&"TASK".equals(execution.get("execution_mode"))&&"OUTPUT_COMMITTED".equals(execution.get("execution_state")));
        scopedOne("agent_task_work_item",scope,row("task_id",scope.taskId(),"work_item_id",scope.workItemId()));
        var inputs=scopedRows("agent_personal_workspace_execution_input",scope,row("execution_id",scope.executionId()));
        check(inputs.size()<=128);var byInput=new HashMap<Object,Map<String,Object>>();
        for(var input:inputs){check(byInput.put(input.get("input_ref"),input)==null);scopedOne("agent_personal_workspace_file",scope,row("file_id",input.get("file_id")));var version=scopedOne("agent_personal_workspace_file_version",scope,row("file_id",input.get("file_id"),"version",input.get("file_version")));sameContent(input,version);}
        var outputs=scopedRows("agent_personal_workspace_execution_output",scope,row("execution_id",scope.executionId()));check(outputs.size()==1);var output=outputs.getFirst();
        check("output_1".equals(output.get("output_id"))&&"COMMITTED".equals(output.get("output_state"))&&"PUBLISHED".equals(output.get("publication_state"))&&same(output.get("content_mime_type"),execution.get("output_content_mime_type"))&&number(output,"workspace_file_version")==1&&number(output,"artifact_version")==1&&same(output.get("artifact_id"),"pwe_art_"+scope.executionId()));
        var file=scopedOne("agent_personal_workspace_file",scope,row("file_id",output.get("workspace_file_id")));check("AGENT_DELIVERY".equals(file.get("origin_kind")));
        var version=scopedOne("agent_personal_workspace_file_version",scope,row("file_id",output.get("workspace_file_id"),"version",1));sameContent(output,version);
        String document="pwe_art_"+scope.executionId(),manifest="pwe_manifest_"+scope.executionId();
        byte[] manifestContent=("{\"schemaVersion\":1,\"executionId\":\""+scope.executionId()+"\",\"outputId\":\"output_1\",\"sha256\":\""+text(output,"content_hash")+"\",\"byteLength\":"+number(output,"byte_length")+",\"contentMimeType\":\""+text(output,"content_mime_type")+"\"}").getBytes(StandardCharsets.UTF_8);
        var doc=scopedOne("agent_task_artifact",scope,row("task_id",scope.taskId(),"artifact_id",document,"artifact_version",1));var man=scopedOne("agent_task_artifact",scope,row("task_id",scope.taskId(),"artifact_id",manifest,"artifact_version",1));
        artifact(doc,scope,"document",text(output,"content_hash"),number(output,"byte_length"),text(output,"content_mime_type"));artifact(man,scope,"summary",hex(sha256(manifestContent)),manifestContent.length,"application/json");
        check(same(doc.get("title"),output.get("original_filename"))&&"Delivery manifest".equals(man.get("title")));
        String deliveryId="pwe_delivery_"+hex(sha256((scope.executionId()+"\n"+scope.runId()).getBytes(StandardCharsets.UTF_8)));
        check(same(output.get("formal_delivery_id"),deliveryId));
        var delivery=unownedOne("agent_task_formal_delivery",scope,row("task_id",scope.taskId(),"delivery_id",deliveryId));
        check(same(delivery.get("work_item_id"),scope.workItemId())&&same(delivery.get("run_id"),scope.runId())&&same(delivery.get("producer_agent_id"),scope.agentId())&&same(delivery.get("manifest_artifact_id"),manifest)&&number(delivery,"manifest_artifact_version")==1);
        var items=unownedRows("agent_task_formal_delivery_item",scope,row("delivery_id",deliveryId));check(items.size()==2);
        for(var item:items){String expected;String purpose;long order;if(same(item.get("artifact_id"),document)){expected=text(doc,"content_hash");purpose="deliverable";order=0;}else{check(same(item.get("artifact_id"),manifest));expected=text(man,"content_hash");purpose="manifest";order=1;}check(number(item,"artifact_version")==1&&same(item.get("content_hash"),expected)&&same(item.get("purpose"),purpose)&&number(item,"item_order")==order);}
        verifyDeliverySubmission(scope,delivery,items);
        var stages=new TreeMap<Long,byte[]>();boolean described=false;
        for(var effect:graph.effects()){
            byte[] original=originalSuccess(graph,effect);check(original!=null);var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(original);var request=required(graph.request(receipt.requestId()));var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));
            if(q.resource() instanceof AgentRuntimeV2EffectCodec.FileDescribe||q.resource() instanceof AgentRuntimeV2EffectCodec.WorkspacePrepare){
                check(q.phase()==AgentRuntimeV2EffectCodec.Phase.DESCRIBE);
                if(q.resource() instanceof AgentRuntimeV2EffectCodec.FileDescribe resource)check(resource.taskId().equals(scope.taskId())&&resource.workItemId().equals(scope.workItemId()));
                else{var resource=(AgentRuntimeV2EffectCodec.WorkspacePrepare)q.resource();check(resource.taskId().equals(scope.taskId())&&scope.workItemId().equals(resource.workItemId())&&resource.workspaceKey().equals(AgentRuntimeV2EffectServiceImpl.workspaceKey(origin))&&resource.baseTreeSha256()==null);}
                var result=(AgentRuntimeV2EffectCodec.DescribedResult)receipt.result();check("WORKSPACE_FILE".equals(result.branch())&&scope.executionId().equals(result.executionId())&&scope.workItemId().equals(result.workItemId())&&result.descriptor() instanceof AgentRuntimeV2EffectCodec.FileDescriptor);var descriptor=(AgentRuntimeV2EffectCodec.FileDescriptor)result.descriptor();check(scope.taskId().equals(descriptor.taskId())&&scope.runId().equals(descriptor.runId())&&descriptor.inputManifest().size()==inputs.size()&&descriptor.outputManifest().size()==1);
                for(var input:descriptor.inputManifest()){var saved=required(byInput.get(input.inputId()));check(input.length()==number(saved,"byte_length")&&input.sha256().equals(saved.get("content_hash"))&&input.relativePath().equals("inputs/"+input.inputId()+extension(text(saved,"content_mime_type")))&&input.downloadPath().equals("/internal/agent/tasks/"+scope.taskId()+"/runs/"+scope.runId()+"/inputs/"+input.inputId()+"/content"));}
                var declared=descriptor.outputManifest().getFirst();check("output_1".equals(declared.outputId())&&declared.contentType().equals(output.get("content_mime_type"))&&declared.maxLength()>=number(output,"byte_length")&&declared.relativePath().equals("outputs/result"+extension(text(output,"content_mime_type")))&&declared.uploadPath().equals("/internal/agent/tasks/"+scope.taskId()+"/runs/"+scope.runId()+"/outputs/output_1/content"));described=true;
            }else if(q.resource() instanceof AgentRuntimeV2EffectCodec.FileRead resource){
                fileIdentity(scope,resource.executionId(),resource.taskId(),resource.workItemId(),resource.runId());var input=required(byInput.get(resource.inputId()));var result=(AgentRuntimeV2EffectCodec.ReadResult)receipt.result();check(result.totalLength()==number(input,"byte_length")&&result.offset()==resource.offset()&&result.length()==resource.length()&&result.sha256().equals(hex(sha256(result.content()))));
            }else if(q.resource() instanceof AgentRuntimeV2EffectCodec.FileStage resource){
                fileIdentity(scope,resource.executionId(),resource.taskId(),resource.workItemId(),resource.runId());check("output_1".equals(resource.outputId())&&resource.contentSha256().equals(output.get("content_hash"))&&resource.totalLength()==number(output,"byte_length"));var blob=graph.blobs().get(q.effectId());check(blob.size()==1&&same(q.content(),blob.getFirst().get("chunk_bytes"))&&stages.put(resource.offset(),rawBytes(blob.getFirst(),"chunk_bytes"))==null);
            }else if(q.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart||q.resource() instanceof AgentRuntimeV2EffectCodec.ProcessExit){check(verifiedProcessObservation(graph,q,receipt));var admission=processAdmission(graph,q.resource());var start=(AgentRuntimeV2EffectCodec.ProcessStart)((AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(admission,"request_wire_bytes"))).resource();check(start.taskId().equals(scope.taskId())&&scope.workItemId().equals(start.workItemId())&&start.workspaceKey().equals(AgentRuntimeV2EffectServiceImpl.workspaceKey(origin)));}
            else{check(q.resource() instanceof AgentRuntimeV2EffectCodec.FileCommit);var resource=(AgentRuntimeV2EffectCodec.FileCommit)q.resource();fileIdentity(scope,resource.executionId(),resource.taskId(),resource.workItemId(),resource.runId());var result=(AgentRuntimeV2EffectCodec.CommittedResult)receipt.result();check(q.phase()==AgentRuntimeV2EffectCodec.Phase.COMMIT&&result.projection().items().size()==1);var item=result.projection().items().getFirst();check("output_1".equals(item.outputId())&&same(item.fileId(),output.get("workspace_file_id"))&&item.fileVersion()==1&&same(item.sha256(),output.get("content_hash"))&&same(item.contentType(),output.get("content_mime_type"))&&item.length()==number(output,"byte_length"));String material=scope.taskId()+"\n"+scope.runId()+"\noutput_1\n"+text(output,"content_hash")+"\n"+number(output,"byte_length")+"\n";String content="{\"outputs\":[{\"outputId\":\"output_1\",\"sha256\":\""+text(output,"content_hash")+"\",\"length\":"+number(output,"byte_length")+"}]}";check(resource.manifestId().equals("pwe_m_"+hex(sha256(material.getBytes(StandardCharsets.UTF_8))))&&resource.manifestSha256().equals(hex(sha256(content.getBytes(StandardCharsets.UTF_8)))));}
        }
        check(described&&!stages.isEmpty());long next=0;var digest=shaDigest();for(var entry:stages.entrySet()){check(entry.getKey()==next);digest.update(entry.getValue());next=Math.addExact(next,entry.getValue().length);}check(next==number(output,"byte_length")&&hex(digest.digest()).equals(output.get("content_hash")));
    }
    /** Current resource authorization is required only before a NEW terminal
     * write, including its original beforeCommit guard. It is deliberately
     * absent from startup/historical reads: later trash, departure, review or
     * reassignment must not erase an already committed immutable outcome. */
    public void verifyProspectiveCompleteFile(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,Graph graph){
        verifyCompleteFile(origin,rows,graph);var scope=completeFileScope(origin,rows,graph);check(scope!=null);
        var execution=scopedOne("agent_personal_workspace_execution",scope,row("execution_id",scope.executionId()));check(execution.get("revoked_at")==null);
        var member=scopedOne("agent_task_member",scope,row("task_id",scope.taskId(),"agent_id",scope.agentId()));check(Set.of("accepted","working","blocked","done").contains(member.get("member_status"))&&Set.of("worker","reviewer","coordinator").contains(member.get("member_role")));
        var work=scopedOne("agent_task_work_item",scope,row("task_id",scope.taskId(),"work_item_id",scope.workItemId()));check(same(work.get("assignee_agent_id"),scope.agentId())&&Set.of("submitted","completed").contains(work.get("status"))&&work.get("lease_token")==null&&work.get("lease_until")==null&&same(work.get("result_artifact_id"),"pwe_manifest_"+scope.executionId()));
        for(var input:scopedRows("agent_personal_workspace_execution_input",scope,row("execution_id",scope.executionId()))){check("ACTIVE".equals(input.get("grant_state")));check("ACTIVE".equals(scopedOne("agent_personal_workspace_file",scope,row("file_id",input.get("file_id"))).get("state")));}
        for(var output:scopedRows("agent_personal_workspace_execution_output",scope,row("execution_id",scope.executionId())))check("ACTIVE".equals(scopedOne("agent_personal_workspace_file",scope,row("file_id",output.get("workspace_file_id"))).get("state")));
    }
    private void verifyDeliverySubmission(cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,Map<String,Object> delivery,List<Map<String,Object>> items){
        var submission=scopedOne("agent_task_event",scope,row("task_id",scope.taskId(),"event_type","FORMAL_DELIVERY_SUBMITTED","aggregate_type","formal_delivery","aggregate_id",delivery.get("delivery_id")));
        long time=number(delivery,"submitted_at");check(number(submission,"occurred_at")==time&&"agent".equals(submission.get("actor_type"))&&same(submission.get("actor_id"),scope.agentId()));var payload=eventPayload(submission);
        for(String key:List.of("deliveryId","workItemId","producerAgentId","runId","submissionDigest")){String column=switch(key){case "deliveryId"->"delivery_id";case "workItemId"->"work_item_id";case "producerAgentId"->"producer_agent_id";case "runId"->"run_id";default->"submission_digest";};check(same(payload.get(key),delivery.get(column)));}
        check(same(payload.get("artifactId"),delivery.get("manifest_artifact_id"))&&number(payload,"artifactVersion")==number(delivery,"manifest_artifact_version")&&number(payload,"deliveryRevision")==number(delivery,"revision")&&"running".equals(payload.get("fromStatus"))&&"submitted".equals(payload.get("toStatus")));
        var work=scopedOne("agent_task_event",scope,row("task_id",scope.taskId(),"event_type","WORK_ITEM_SUBMITTED","event_version",Math.addExact(number(submission,"event_version"),1)));
        var task=scopedOne("agent_task_event",scope,row("task_id",scope.taskId(),"event_type","TASK_REVIEWING","event_version",Math.addExact(number(submission,"event_version"),2)));
        check("work_item".equals(work.get("aggregate_type"))&&same(work.get("aggregate_id"),scope.workItemId())&&"task".equals(task.get("aggregate_type"))&&same(task.get("aggregate_id"),scope.taskId()));
        for(var event:List.of(work,task))check(number(event,"occurred_at")==time&&"agent".equals(event.get("actor_type"))&&same(event.get("actor_id"),scope.agentId()));
        var wp=eventPayload(work);var tp=eventPayload(task);check(same(wp.get("workItemId"),scope.workItemId())&&same(wp.get("artifactId"),delivery.get("manifest_artifact_id"))&&"running".equals(wp.get("fromStatus"))&&"submitted".equals(wp.get("toStatus"))&&"running".equals(tp.get("fromStatus"))&&"reviewing".equals(tp.get("toStatus")));
        for(var p:List.of(wp,tp))check(number(p,"expectedVersion")>=0&&number(p,"resultVersion")==Math.addExact(number(p,"expectedVersion"),1));
        var canonical=new StringBuilder("formal-delivery-r2\\n");for(String value:List.of(text(delivery,"delivery_id"),text(delivery,"run_id"),text(delivery,"work_item_id"),Long.toString(number(tp,"expectedVersion")),Long.toString(number(wp,"expectedVersion")),text(delivery,"summary"),text(delivery,"manifest_artifact_id"),Long.toString(number(delivery,"manifest_artifact_version"))))appendDeliveryComponent(canonical,value);
        for(var item:items.stream().sorted(Comparator.comparingLong(value->number(value,"item_order"))).toList())for(String value:List.of(text(item,"artifact_id"),Long.toString(number(item,"artifact_version")),text(item,"content_hash"),text(item,"purpose")))appendDeliveryComponent(canonical,value);
        check(hex(sha256(canonical.toString().getBytes(StandardCharsets.UTF_8))).equals(delivery.get("submission_digest")));
    }
    private static Map<String,Object> eventPayload(Map<String,Object> event){String json=text(event,"event_json");check(cn.jia.agent.common.TaskEventPayload.normalizeAllowedJson(json).equals(json));return cn.jia.core.util.JsonUtil.fromJson(json,Map.class);}
    private static void appendDeliveryComponent(StringBuilder target,String value){target.append(value.length()).append(':').append(value).append('\n');}
    private static void fileIdentity(cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,String execution,String task,String item,String run){check(scope.executionId().equals(execution)&&scope.taskId().equals(task)&&scope.workItemId().equals(item)&&scope.runId().equals(run));}
    private static void sameContent(Map<String,Object> left,Map<String,Object> right){for(String column:List.of("original_filename","content_mime_type","byte_length","content_hash","storage_uri"))check(same(left.get(column),right.get(column)));}
    private static String extension(String mime){return switch(mime){case "image/png"->".png";case "image/jpeg"->".jpg";case "application/pdf"->".pdf";case "text/plain"->".txt";case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"->".docx";case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"->".xlsx";case "application/vnd.openxmlformats-officedocument.presentationml.presentation"->".pptx";default->throw denied();};}
    private static void artifact(Map<String,Object> artifact,cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,String type,String hash,long length,String mime){
        var digest=shaDigest();for(String component:List.of(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.taskId())){byte[] content=component.getBytes(StandardCharsets.UTF_8);digest.update(ByteBuffer.allocate(4).putInt(content.length).array());digest.update(content);}String uri="cyf-artifact://"+hex(digest.digest())+"/"+hash;
        check(same(artifact.get("work_item_id"),scope.workItemId())&&same(artifact.get("producer_agent_id"),scope.agentId())&&same(artifact.get("artifact_type"),type)&&"task_members".equals(artifact.get("visibility"))&&same(artifact.get("content_hash"),hash)&&artifact.get("content")==null&&same(artifact.get("storage_uri"),uri));
        var metadata=JSON.readTree(text(artifact,"metadata_json"));var managed=metadata.get("_cyfArtifactStorageV1");check(metadata.size()==1&&managed!=null&&managed.size()==4&&managed.get("schemaVersion").asInt()==1&&managed.get("sha256").asString().equals(hash)&&managed.get("byteLength").asLong()==length&&managed.get("mimeType").asString().equals(mime));
    }
    private Map<String,Object> scopedOne(String table,cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,Map<String,Object> key){var values=scopedRows(table,scope,key);check(values.size()==1);return values.getFirst();}
    private List<Map<String,Object>> scopedRows(String table,cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,Map<String,Object> key){var exact=new LinkedHashMap<>(key);exact.put("owner_jiacn",scope.ownerJiacn());return unownedRows(table,scope,exact);}
    private Map<String,Object> unownedOne(String table,cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,Map<String,Object> key){var values=unownedRows(table,scope,key);check(values.size()==1);return values.getFirst();}
    private List<Map<String,Object>> unownedRows(String table,cn.jia.agent.service.PersonalWorkspaceExecutionService.V2Scope scope,Map<String,Object> key){
        check(Set.of("agent_task_meta","agent_task_member","agent_task_work_item","agent_task_event","agent_personal_workspace_execution","agent_personal_workspace_execution_input","agent_personal_workspace_execution_output","agent_personal_workspace_file","agent_personal_workspace_file_version","agent_task_artifact","agent_task_formal_delivery","agent_task_formal_delivery_item").contains(table));var exact=new LinkedHashMap<>(key);exact.put("tenant_id",scope.tenantId());exact.put("client_id",scope.clientId());var terms=new ArrayList<String>();exact.forEach((name,value)->{check(name.matches("[a-z][a-z0-9_]*")&&value!=null);terms.add(name+"=?");});var values=store.jdbc().queryForList("SELECT * FROM "+table+" WHERE "+String.join(" AND ",terms)+" LIMIT 129",exact.values().toArray());check(values.size()<=128);for(var value:values)exact.forEach((name,expected)->check(same(value.get(name),expected)));return values;
    }
    private static java.security.MessageDigest shaDigest(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    /** Full READ receipts legitimately exceed the mutation projection's98304
     * bytes. Keep original frame/request/event caps; descriptive success stays
     * in its original durable request/outcome, not a widened result column. */
    public static byte[] originalSuccess(Graph graph,Map<String,Object> effect){
        for(var event:graph.effectEvents(effect.get("effect_id")))if(Set.of("OUTCOME_OBSERVED","SERVER_COMMIT").contains(event.get("event_kind"))){byte[] raw=rawBytes(event,"evidence_wire_bytes");var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);if(Set.of("DESCRIBED","READ","STAGED","COMMITTED","SETTLED").contains(receipt.decision()))return raw;if("OBSERVED".equals(receipt.decision())){var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(required(graph.request(receipt.requestId())),"request_wire_bytes"));if(verifiedProcessObservation(graph,q,receipt))return raw;}}
        return null;
    }
    public static AgentRuntimeV2EffectCodec.EffectSuccessReference originalSuccessReference(Graph graph,Map<String,Object> effect){
        byte[] response=originalSuccess(graph,effect);if(response==null)return null;
        var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);var saved=required(graph.request(receipt.requestId()));byte[] raw=rawBytes(saved,"request_wire_bytes");
        check("EFFECT".equals(saved.get("request_family"))&&same(saved.get("response_wire_bytes"),response)&&same(saved.get("request_sha256"),sha256(raw))&&same(saved.get("response_sha256"),sha256(response)));
        var q=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(raw);var run=required(graph.run());String resource=hex(sha256(AgentRuntimeV2EffectCodec.encodeResource(q.effectKind(),q.phase(),q.resource())));
        check(q.effectId().equals(effect.get("effect_id"))&&q.runToken().equals(run.get("run_token"))&&q.grant().runtimeDispatchId().equals(run.get("runtime_dispatch_id"))&&receipt.runtimeDispatchId().equals(q.grant().runtimeDispatchId())&&receipt.requestId().equals(q.grant().requestId())&&receipt.requestSha256().equals(hex(sha256(raw)))&&resource.equals(hex(bytes(effect,"resource_sha256"))));
        var reference=new AgentRuntimeV2EffectCodec.EffectSuccessReference(q.grant().runtimeDispatchId(),q.runToken(),q.effectId(),q.effectKind(),q.phase(),receipt.decision(),resource,q.grant().requestId(),hex(sha256(raw)),hex(sha256(response)),q.grant().executionGrantSha256());
        AgentRuntimeV2EffectCodec.encodeProof(reference);return reference;
    }
    public static String completeGraphSha256(Graph graph,List<AgentRuntimeV2EffectCodec.EffectSuccessReference> references){
        var run=required(graph.run());return AgentRuntimeV2EffectCodec.completeGraphSha256(text(run,"runtime_dispatch_id"),text(run,"run_token"),hex(bytes(run,"dispatch_wire_sha256")),hex(bytes(run,"started_receipt_sha256")),text(run,"original_start_lease_fact_id"),references);
    }

    /** Exact late/equality proof used by the SAME startup/dispatch validator.
     * It does not call ExecutionEvidenceValidator.inspect/validate recursively.
     * The caller still performs all original holder/raw/projection checks.
     */
    public void verifyLateSettlement(AgentRuntimeV2InternalOutboxRow origin,
            AgentRuntimeV2DispatchSource.Locked rows,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,
            Map<String,Object> settled) {
        check("SETTLED".equals(settled.get("fact_type")));var graph=inspect(origin,rows,execution,false);check(graph.run()!=null&&"SETTLED_KNOWN".equals(graph.run().get("disposition"))&&same(settled.get("lease_fact_id"),required(execution.latest()).get("lease_fact_id")));
    }
    /** Early R4 settlements need the SAME durable completeness validation as
     * equality/late ones. An absent R4 catalog/run preserves ordinary R1-R3;
     * a partial catalog or existing unclosed R4 run cannot hide a raw terminal. */
    void verifySettlementIfPresent(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution){
        boolean present=Boolean.TRUE.equals(store.jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            var expected=Set.of(RUN,EFFECT,REQUEST,EVENT,BLOB);var found=new HashSet<String>();try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",null)){while(tables.next()){String name=tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);if(expected.contains(name))found.add(name);}}check(found.isEmpty()||found.equals(expected));return !found.isEmpty();
        }));
        if(!present)return;var run=store.hint(RUN,row("runtime_dispatch_id",origin.get("runtime_dispatch_id")));if(run==null)return;check("SETTLED_KNOWN".equals(run.get("disposition")));inspect(origin,rows,execution,false);
    }
    private void verifySettlementGraph(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,Graph graph,Map<String,Object> settled){
        var run=required(graph.run());check("SETTLED_KNOWN".equals(run.get("disposition"))&&number(run,"retired")==1);
        var terminal=execution.receipts().stream().filter(value->same(value.get("receipt_id"),settled.get("causing_receipt_id"))).findFirst().orElseThrow(()->denied());
        check(Set.of("SUCCEEDED","FAILED").contains(terminal.get("status"))&&same(terminal.get("lease_fact_id"),settled.get("lease_fact_id"))&&number(terminal,"committed_at")==number(settled,"effective_at"));
        var matching=new ArrayList<AgentRuntimeV2EffectCodec.SettlementProof>();boolean originalSettlement=false;
        for(var request:graph.requests())if("RECONCILE".equals(request.get("request_family"))){
            var q=(AgentRuntimeV2EffectCodec.Reconcile)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(request,"response_wire_bytes"));
            if(q.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EXECUTION&&receipt.result() instanceof AgentRuntimeV2EffectCodec.Reconciliation result&&result.proof() instanceof AgentRuntimeV2EffectCodec.SettlementProof proof&&same(proof.settlementReceipt(),terminal.get("receipt_wire_bytes"))){
                // The once-created proof must be persisted with the truthful
                // original terminal commit time. Later NEW transport IDs may
                // read the exact same proof at a later serverAt; they are not
                // new settlements and must not poison the original history.
                check((q.originalRequest()==null||same(proof.terminalAck(),q.originalRequest()))&&q.runtimeDispatchId().equals(origin.text("runtime_dispatch_id"))&&q.subjectId().equals(origin.text("runtime_dispatch_id"))&&proof.runtimeDispatchId().equals(origin.text("runtime_dispatch_id"))&&proof.runToken().equals(run.get("run_token"))&&receipt.serverAt()>=number(terminal,"committed_at")&&"SETTLED_KNOWN".equals(result.disposition()));
                if(receipt.serverAt()==number(terminal,"committed_at")){check(q.originalRequest()!=null);originalSettlement=true;}matching.add(proof);
            }
        }
        check(!matching.isEmpty()&&originalSettlement);
        byte[] first=AgentRuntimeV2EffectCodec.encodeProof(matching.getFirst());for(var proof:matching)check(same(first,AgentRuntimeV2EffectCodec.encodeProof(proof)));
        var proof=matching.getFirst();var ack=AgentRuntimeV2ExecutionCodec.decodeAck(proof.terminalAck());check(same(AgentRuntimeV2ExecutionCodec.ackFingerprint(ack),terminal.get("request_fingerprint"))&&ack.sourceMessageId().equals(origin.text("source_message_id"))&&ack.ackAt()<=number(terminal,"committed_at"));
        var membership=new HashSet<String>();var references=new ArrayList<AgentRuntimeV2EffectCodec.EffectSuccessReference>();Object closureRequest=null;
        for(var effect:graph.effects()){
            String id=text(effect,"effect_id");check(membership.add(id)&&"SETTLED_KNOWN".equals(effect.get("disposition")));
            byte[] success=originalSuccess(graph,effect);check(success!=null);var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(success);check(Set.of("DESCRIBED","READ","STAGED","COMMITTED","SETTLED","OBSERVED").contains(receipt.decision())&&receipt.serverAt()<=number(terminal,"committed_at"));
            var reference=originalSuccessReference(graph,effect);check(reference!=null);references.add(reference);
            var closures=graph.events().stream().filter(event->same(event.get("effect_id"),id)&&"SETTLED_KNOWN".equals(event.get("event_kind"))).toList();check(closures.size()==1&&number(closures.getFirst(),"created_at")==number(terminal,"committed_at"));
            if(closureRequest==null)closureRequest=closures.getFirst().get("request_id");else check(same(closureRequest,closures.getFirst().get("request_id")));
        }
        check(!membership.isEmpty());
        var original=required(graph.request(closureRequest));var originalQuery=(AgentRuntimeV2EffectCodec.Reconcile)AgentRuntimeV2EffectCodec.decode(rawBytes(original,"request_wire_bytes"));var originalResult=(AgentRuntimeV2EffectCodec.Reconciliation)AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(original,"response_wire_bytes")).result();
        check(originalQuery.originalRequest()!=null&&same(originalQuery.originalRequest(),proof.terminalAck())&&number(original,"created_at")==number(terminal,"committed_at")&&same(first,AgentRuntimeV2EffectCodec.encodeProof(originalResult.proof())));
        if(proof instanceof AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof compact)check(compact.effectCount()==references.size()&&compact.effectGraphSha256().equals(completeGraphSha256(graph,references)));
        else{
            var inline=(AgentRuntimeV2EffectCodec.ExecutionSettlementProof)proof;var listed=new HashSet<String>();for(var member:inline.effects()){check(listed.add(member.effectId()));var effect=required(graph.effect(member.effectId()));check(same(member.receipt(),originalSuccess(graph,effect))&&member.resourceSha256().equals(hex(bytes(effect,"resource_sha256"))));}check(listed.equals(membership));
        }
        check(number(run,"updated_at")==number(terminal,"committed_at"));
        verifyCompleteFile(origin,rows,graph);
    }
    private void actor(AgentRuntimeV2InternalOutboxRow origin,Map<String,Object> request,String family,Map<Object,Map<String,Object>> actors){
        for(String name:List.of("actor_credential_reference","actor_session_fence","actor_socket_owner_instance_id"))exactActor(text(request,name));check(number(request,"actor_socket_owner_epoch")>=0&&number(request,"created_at")>0);
        var names=List.of("actor_registration_fact_id","actor_runtime_binding_id","actor_runtime_instance_id","actor_binding_generation");long present=names.stream().filter(name->request.get(name)!=null).count();
        if(present==0){check("RECONCILE".equals(family));return;}check(present==4);
        var fact=actors.get(request.get("actor_registration_fact_id"));
        if(fact==null){
            var found=store.jdbc().queryForList("SELECT * FROM agent_runtime_v2_binding_generation_fact WHERE binding_generation_fact_id=?",request.get("actor_registration_fact_id"));check(found.size()==1);fact=found.getFirst();
            var receipts=store.jdbc().queryForList("SELECT * FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=?",fact.get("registration_receipt_id"));check(receipts.size()==1);AgentRuntimeV2RegistrationServiceImpl.validateReceipt(receipts.getFirst());AgentRuntimeV2RegistrationServiceImpl.validateHolder(fact,receipts.getFirst());
            actors.put(request.get("actor_registration_fact_id"),fact);
        }
        for(String[] pair:new String[][]{{"runtime_binding_id","actor_runtime_binding_id"},{"runtime_instance_id","actor_runtime_instance_id"},{"binding_generation","actor_binding_generation"},{"session_fence","actor_session_fence"},{"socket_owner_instance_id","actor_socket_owner_instance_id"},{"socket_owner_epoch","actor_socket_owner_epoch"}})check(same(fact.get(pair[0]),request.get(pair[1])));
        for(String name:List.of("tenant_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","runtime_credential_client_id","claimant_deployment_id","module_id"))check(same(fact.get(name),origin.get(name)));
    }
    private static void reference(AgentRuntimeV2EffectCodec.GrantReference reference,AgentRuntimeV2ExecutionEvidenceValidator.Graph execution){var started=required(execution.receipt("STARTED"));var grant=required(execution.grant(reference.executionGrantId()));check(reference.startedReceiptId().equals(started.get("receipt_id"))&&reference.startedReceiptSha256().equals(hex(bytes(started,"receipt_wire_sha256")))&&reference.executionGrantSha256().equals(hex(bytes(grant,"grant_wire_sha256")))&&reference.runtimeDispatchId().equals(grant.get("runtime_dispatch_id")));}
    private static void validateBlobs(Map<String,Object> effect,AgentRuntimeV2EffectCodec.EffectRequest request,List<Map<String,Object>> blobs){
        check(blobs!=null);if(!(request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage staged)){check(blobs.isEmpty());return;}
        long offset=staged.offset();for(var blob:blobs){columns(BLOB,blob);byte[] content=rawBytes(blob,"chunk_bytes");check(same(blob.get("effect_id"),effect.get("effect_id"))&&number(blob,"chunk_offset")==offset&&number(blob,"chunk_length")==content.length&&content.length<=AgentRuntimeV2EffectCodec.CHUNK_CAP&&number(blob,"total_length")==staged.totalLength()&&same(blob.get("content_sha256"),HexFormat.of().parseHex(staged.contentSha256()))&&same(blob.get("chunk_sha256"),sha256(content))&&number(blob,"created_at")>=number(effect,"created_at"));offset=Math.addExact(offset,content.length);check(offset<=staged.totalLength());}
        // A FILE_STAGE effect identity includes its exact offset/length. Other
        // chunks are other immutable effects, not rewritten resource bytes.
        check(blobs.size()==1&&number(blobs.getFirst(),"chunk_offset")==staged.offset()&&number(blobs.getFirst(),"chunk_length")==staged.length()&&same(request.content(),blobs.getFirst().get("chunk_bytes")));
    }
    public static byte[] eventHash(Map<String,Object> event){
        var output=new ByteArrayOutputStream();for(String name:List.of("effect_id","event_sequence","runtime_dispatch_id","run_token","request_id","previous_sha256","event_kind","evidence_sha256","created_at")){
            Object value=event.get(name);byte[] content=value==null?null:value instanceof byte[] bytes?bytes:Objects.toString(value).getBytes(StandardCharsets.UTF_8);output.writeBytes(ByteBuffer.allocate(4).putInt(content==null?-1:content.length).array());if(content!=null)output.writeBytes(content);
        }
        byte[] domain="agent-runtime-v2-effect-event-v1".getBytes(StandardCharsets.UTF_8);return sha256(ByteBuffer.allocate(4+domain.length+output.size()).putInt(domain.length).put(domain).put(output.toByteArray()).array());
    }
    public static String stableKey(AgentRuntimeV2InternalOutboxRow origin){
        // Preserve all original getter evaluations before opening the local
        // configured generator; only Databind List serializer discovery goes.
        var fields=List.of(origin.text("tenant_id"),origin.text("data_domain_id"),origin.text("execution_pool_id"),origin.text("command_id"),Long.toString(origin.number("delivery_id")));
        var output=new StringWriter();
        try(var generator=JSON.createGenerator(output)){
            generator.writeStartArray();for(String field:fields)generator.writeString(field);generator.writeEndArray();
        }
        return output.toString();
    }
    private static void columns(String table,Map<String,Object> value){check(value.keySet().equals(new HashSet<>(AgentRuntimeV2EffectMapper.DEFINITIONS.get(table).columns())));}
    // Core's exact-identity utility is deliberately package-private. Keep this
    // durable actor-row check here; do not widen the accepted core API.
    private static void exactActor(String value){
        check(value!=null&&!value.isBlank()&&value.equals(value.strip())&&!value.codePoints().anyMatch(Character::isISOControl)&&value.getBytes(StandardCharsets.UTF_8).length<=100);
        for(int index=0;index<value.length();index++){char character=value.charAt(index);if(Character.isHighSurrogate(character))check(index+1<value.length()&&Character.isLowSurrogate(value.charAt(++index)));else check(!Character.isLowSurrogate(character));}
    }
    private static void times(Map<String,Object> value){check(number(value,"version")>=0&&number(value,"created_at")>0&&number(value,"updated_at")>=number(value,"created_at"));}
    /** Read raw data only from known bounded columns. Digest columns continue
     * through the unchanged strict RegistrationStore.bytes helper. */
    private static byte[] rawBytes(Map<String,Object> row,String column){
        int cap=switch(column){
            case "command_payload" -> AgentCommandCanonicalCodec.MAX_CANONICAL_BYTES;
            case "request_wire_bytes","response_wire_bytes","evidence_wire_bytes" -> AgentRuntimeV2EffectCodec.FRAME_CAP;
            case "result_wire_bytes" -> 98304;
            case "resource_wire_bytes" -> AgentRuntimeV2EffectCodec.RESOURCE_CAP;
            case "chunk_bytes" -> AgentRuntimeV2EffectCodec.CHUNK_CAP;
            default -> throw denied();
        };
        Object value=row.get(column);check(value instanceof byte[]);byte[] raw=(byte[])value;
        check(raw.length<=cap&&(raw.length>0||"chunk_bytes".equals(column)));return raw.clone();
    }
    private static Map<String,Object> copy(Map<String,Object> values){var copy=new LinkedHashMap<String,Object>();values.forEach((key,value)->copy.put(key,value instanceof byte[] bytes?bytes.clone():value));return Collections.unmodifiableMap(copy);}
}
