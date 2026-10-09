package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2ExecutionAuthority;
import cn.jia.agent.service.AgentRuntimeV2ExecutionService;
import org.springframework.dao.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.dao.AgentRuntimeV2ExecutionStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.text;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.bytes;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.same;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.*;

/** Production raw execution reducer. No legacy ACK service, broker lease, caller now, or DateUtil authority. */
public final class AgentRuntimeV2ExecutionServiceImpl implements AgentRuntimeV2ExecutionService {
    public static final long TTL=60_000;
    private final AgentRuntimeV2ExecutionStore store;
    private final AgentRuntimeV2ExecutionAuthority authority;
    private final AgentRuntimeV2ExecutionEvidenceValidator evidence;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final java.util.function.Consumer<RuntimeException> observation;
    private record Locked(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2ExecutionAuthority.Snapshot authority,AgentRuntimeV2DispatchSource.Locked rows,AgentRuntimeV2ExecutionEvidenceValidator.Graph graph) {}
    private static final class Rejected extends SecurityException {final String code;Rejected(String code){super("execution rejected");this.code=code;}}
    public AgentRuntimeV2ExecutionServiceImpl(AgentRuntimeV2ExecutionStore store,AgentRuntimeV2ExecutionAuthority authority,AgentRuntimeV2ExecutionEvidenceValidator evidence,PlatformTransactionManager manager,Clock clock){this(store,authority,evidence,manager,clock,e->{});}
    public AgentRuntimeV2ExecutionServiceImpl(AgentRuntimeV2ExecutionStore store,AgentRuntimeV2ExecutionAuthority authority,AgentRuntimeV2ExecutionEvidenceValidator evidence,PlatformTransactionManager manager,Clock clock,java.util.function.Consumer<RuntimeException> observation){this.store=store;this.authority=authority;this.evidence=evidence;this.clock=clock;this.observation=observation;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(7);}
    public static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw new IllegalStateException("execution requires complete transaction cleanup");}
    /** No authority lookup, live() bypass, new grant or separate transaction.
     * The effect verifier already holds the exact original graph and real
     * resources. Historic predecessor holder is copied, NOT recovery actor. */
    static byte[] settleVerified(AgentRuntimeV2EffectServiceImpl.SettlementPermit permit){
        Objects.requireNonNull(permit).consume();var store=permit.store();var origin=permit.origin();var rows=permit.rows();var graph=permit.execution();var ack=decodeAck(permit.ack());long now=permit.now();
        check(Set.of("SUCCEEDED","FAILED").contains(ack.ackStatus())&&same(ack.runtimeDispatchId(),origin.get("runtime_dispatch_id"))&&same(ack.sourceMessageId(),origin.get("source_message_id"))&&ack.ackAt()<=now&&graph.receipt("STARTED")!=null&&graph.receipts().stream().noneMatch(value->Set.of("SUCCEEDED","FAILED","REJECTED").contains(value.get("status"))));
        var predecessor=required(graph.latest());check(Set.of("START_GRANTED","HOLDER_TRANSFERRED","RENEWED").contains(predecessor.get("fact_type"))&&now>=number(predecessor,"effective_at"));
        var fact=new LinkedHashMap<String,Object>(predecessor);fact.remove("id");String factId=UUID.randomUUID().toString(),receiptId=UUID.randomUUID().toString();
        fact.putAll(row("lease_fact_id",factId,"fact_sequence",Math.addExact(number(predecessor,"fact_sequence"),1),"fact_type","SETTLED","previous_lease_fact_id",predecessor.get("lease_fact_id"),"effective_at",now,"lease_until",null,"causing_receipt_id",receiptId,"reason","SETTLED","created_at",now,"create_time",now,"update_time",now));
        var receipt=new LinkedHashMap<String,Object>(required(graph.receipt("STARTED")));receipt.remove("id");
        long dv=Math.addExact(number(rows.delivery(),"version"),1),iv=Math.addExact(number(rows.inbox(),"version"),1);
        byte[] wire=AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(receiptId,ack.runtimeDispatchId(),ack.ackMessageId(),ack.sourceMessageId(),ack.ackStatus(),ack.ackAt(),now,dv,iv,factId));
        receipt.putAll(row("receipt_id",receiptId,"ack_message_id",ack.ackMessageId(),"status",ack.ackStatus(),"ack_at",ack.ackAt(),"request_fingerprint",ackFingerprint(ack),"ack_holder_generation_fact_id",predecessor.get("holder_generation_fact_id"),"ack_holder_generation_snapshot_sha256",predecessor.get("holder_generation_snapshot_sha256"),"ack_holder_binding_generation",predecessor.get("holder_binding_generation"),"postcommit_delivery_version",dv,"postcommit_inbox_version",iv,"lease_fact_id",factId,"committed_at",now,"receipt_wire_bytes",wire,"receipt_wire_sha256",sha256(wire),"create_time",now,"update_time",now));
        store.append(RECEIPT,receipt);store.append(FACT,fact);
        var changes=row("ack_delivery_version",dv,"ack_message_id",ack.ackMessageId(),"ack_status",ack.ackStatus(),"ack_at",ack.ackAt(),"ack_agent_id",origin.get("agent_id"),"ack_runtime_installation_id",null,"ack_runtime_instance_id",predecessor.get("runtime_instance_id"),"ack_runtime_binding_id",predecessor.get("runtime_binding_id"),"ack_runtime_binding_generation",predecessor.get("holder_binding_generation"),"ack_committed_at",now,"update_time",now);
        changes.putAll(row("execution_started_at",rows.inbox().get("execution_started_at"),"execution_lease_until",null,"execution_lease_revision",fact.get("lease_revision"),"execution_holder_binding_id",fact.get("runtime_binding_id"),"execution_holder_binding_generation",fact.get("holder_binding_generation"),"execution_holder_runtime_instance_id",fact.get("runtime_instance_id"),"execution_current_lease_fact_id",factId));
        store.cas(INBOX,rows.inbox(),changes);store.cas(DELIVERY,rows.delivery(),row("status",ack.ackStatus(),"update_time",now));permit.require();return wire;
    }
    @Override public Result acknowledge(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw){
        outside();Ack ack;try{ack=decodeAck(raw);}catch(IllegalArgumentException malformed){return Result.closed();}
        try{return retry(()->tx.execute(status->acknowledgeLocked(identity,socket,ack)));}catch(RuntimeException failure){outside();observation.accept(failure);return Result.closed();}
    }
    @Override public Result continueExecution(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw){outside();AgentRuntimeV2WireCodec.ContinuationRequest r;try{r=decodeContinue(raw);}catch(IllegalArgumentException malformed){return Result.closed();}return request(identity,socket,raw,"CONTINUATION",r.continuationRequestId(),r.runtimeDispatchId(),r.startedReceiptId(),hex(r.startedReceiptSha256()),null,null);}
    @Override public Result renewExecution(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw){outside();Renew r;try{r=decodeRenew(raw);}catch(IllegalArgumentException malformed){return Result.closed();}return request(identity,socket,raw,"RENEWAL",r.renewalRequestId(),r.runtimeDispatchId(),r.startedReceiptId(),r.startedReceiptSha256(),r.previousExecutionGrantId(),r.previousExecutionGrantSha256());}
    private Result request(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw,String kind,String id,String dispatch,String receipt,String receiptHash,String previousId,String previousHash){
        try{return retry(()->tx.execute(status->requestLocked(identity,socket,raw,kind,id,dispatch,receipt,receiptHash,previousId,previousHash)));}
        catch(Rejected rejected){outside();return rejection(kind,id,dispatch,receipt,rejected.code);}
        catch(SecurityException|IllegalArgumentException denied){outside();return rejection(kind,id,dispatch,receipt,kind+"_NOT_AUTHORIZED");}
        catch(RuntimeException unavailable){outside();observation.accept(unavailable);return rejection(kind,id,dispatch,receipt,kind+"_UNAVAILABLE");}
    }
    private Result rejection(String kind,String id,String dispatch,String receipt,String reason){return new Result(List.of(rejected(kind,id,dispatch,receipt,reason)),false);}
    private <T>T retry(Supplier<T> action){for(int attempt=0;;attempt++){outside();try{T result=action.get();outside();return result;}catch(DuplicateKeyException|PessimisticLockingFailureException race){outside();if(attempt>=1)throw race;}}}
    private Locked lock(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,String dispatch,String requestId){
        return lock(identity,socket,dispatch,requestId,false);
    }
    private Locked lock(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,String dispatch,String requestId,boolean history){
        var hinted=required(store.hint(INTERNAL,row("runtime_dispatch_id",dispatch)));var origin=new AgentRuntimeV2InternalOutboxRow(hinted);
        var current=authority.lock(identity,socket,origin,history);current.fresh(clock.millis());
        var d=required(store.lock(DELIVERY,row("id",origin.get("delivery_id"))));var source=required(store.lock(OUTBOX,row("id",origin.get("source_outbox_id"))));var inbox=required(store.lock(INBOX,row("id",origin.get("inbox_id"))));var rows=new AgentRuntimeV2DispatchSource.Locked(d,source,inbox);
        var graph=evidence.inspect(rows,origin,true,requestId);
        var internal=new AgentRuntimeV2InternalOutboxRow(required(store.lock(INTERNAL,row("internal_outbox_id",origin.get("internal_outbox_id")))));check(origin.sameImmutableAs(internal));return new Locked(internal,current,rows,graph);
    }
    private Result acknowledgeLocked(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,Ack ack){
        boolean history=store.hint(RECEIPT,row("runtime_dispatch_id",ack.runtimeDispatchId(),"status",ack.ackStatus()))!=null;
        var l=lock(identity,socket,ack.runtimeDispatchId(),null,history);check(same(ack.sourceMessageId(),l.origin().get("source_message_id")));long now=clock.millis();check(ack.ackAt()<=now);
        var prior=l.graph().receipt(ack.ackStatus());
        if(prior!=null){check(same(prior.get("ack_message_id"),ack.ackMessageId())&&same(prior.get("request_fingerprint"),ackFingerprint(ack)));var response=new Result(List.of(receiptDelivery((byte[])prior.get("receipt_wire_bytes"),true)),false);finish(l,null,null,null);return response;}
        check(!history);
        boolean start=ack.ackStatus().equals("STARTED"),received=ack.ackStatus().equals("RECEIVED"),terminal=Set.of("SUCCEEDED","FAILED","REJECTED").contains(ack.ackStatus());
        check(start||received||terminal);Long oldUntil=null,until=null;Map<String,Object> fact=null;
        if(l.graph().latest()==null){check(l.authority().drain()==null&&socket.equals(l.origin().tuple())&&now<l.origin().number("expires_at"));if(received)check(l.graph().receipts().isEmpty());else if(start){check(l.graph().receipt("RECEIVED")!=null&&"RECEIVED".equals(l.rows().delivery().get("status")));until=leaseUntil(l,now,true);fact=fact(l,"START_GRANTED",null,until,now);}else check("REJECTED".equals(ack.ackStatus())&&Set.of("SENT","RECEIVED").contains(l.rows().delivery().get("status")));}
        else{check(terminal);var latest=live(l,now);check(same(latest.get("holder_generation_fact_id"),l.authority().connected().get("binding_generation_fact_id")));oldUntil=number(latest,"lease_until");fact=fact(l,"SETTLED",latest,null,now);}
        if(terminal)requireNoUnverifiedR4Run(l);
        var receipt=receipt(l,ack,fact,now);if(fact!=null)fact.put("causing_receipt_id",receipt.get("receipt_id"));store.append(RECEIPT,receipt);if(fact!=null)store.append(FACT,fact);
        Map<String,Object> grant=null;if(start){grant=grant(l,receipt,fact,"START",null,now);store.append(GRANT,grant);store.append(ANCHOR,anchor(l,receipt,grant,now));}
        var changes=row("ack_delivery_version",receipt.get("postcommit_delivery_version"),"ack_message_id",ack.ackMessageId(),"ack_status",ack.ackStatus(),"ack_at",ack.ackAt(),"ack_agent_id",l.origin().get("agent_id"),"ack_runtime_installation_id",null,"ack_runtime_instance_id",socket.runtimeInstanceId(),"ack_runtime_binding_id",socket.runtimeBindingId(),"ack_runtime_binding_generation",socket.bindingGeneration(),"ack_committed_at",now,"update_time",now);
        if(fact!=null)changes.putAll(projection(l,fact,start?now:number(l.rows().inbox(),"execution_started_at")));
        store.cas(INBOX,l.rows().inbox(),changes);store.cas(DELIVERY,l.rows().delivery(),row("status",ack.ackStatus(),"update_time",now));
        var responses=new ArrayList<byte[]>();responses.add(receiptDelivery((byte[])receipt.get("receipt_wire_bytes"),false));if(grant!=null)responses.add(grantDelivery((byte[])grant.get("grant_wire_bytes"),"STARTED_ACK",ack.ackMessageId(),false));
        finish(l,oldUntil,until,l.graph().latest()==null?l.origin().number("expires_at"):null);return new Result(responses,false);
    }
    private Result requestLocked(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] raw,String kind,String id,String dispatch,String receiptId,String receiptHash,String previousId,String previousHash){
        var l=lock(identity,socket,dispatch,id);var started=required(l.graph().receipt("STARTED"));check(same(started.get("receipt_id"),receiptId)&&same(hex(bytes(started,"receipt_wire_sha256")),receiptHash));long now=clock.millis();var latest=live(l,now);
        requireOpenR4Run(l);
        var prior=l.graph().requests().stream().filter(r->same(r.get("execution_request_id"),id)).findFirst().orElse(null);
        if(prior!=null){
            if(!same(prior.get("request_wire_bytes"),raw)||!same(prior.get("request_fingerprint"),sha256(raw))||!same(prior.get("requested_holder_generation_fact_id"),l.authority().connected().get("binding_generation_fact_id")))throw new Rejected("REQUEST_CONFLICT");
            if("DENIED".equals(prior.get("decision")))throw new Rejected(text(prior,"denial_code"));var grant=required(l.graph().grant(prior.get("execution_grant_id")));currentHolder(l,latest);
            if(!same(grant.get("current_lease_fact_id"),latest.get("lease_fact_id")))throw new Rejected(kind+"_SUPERSEDED");var result=new Result(List.of(grantDelivery((byte[])grant.get("grant_wire_bytes"),kind,id,true)),false);finish(l,number(latest,"lease_until"),null,null);return result;
        }
        Map<String,Object> granted;boolean advanced=false;Long until=null;
        if(kind.equals("CONTINUATION")){
            var anchor=l.graph().anchors().stream().filter(a->same(a.get("holder_binding_generation"),socket.bindingGeneration())).findFirst().orElse(null);
            if(anchor!=null){currentHolder(l,latest);granted=required(l.graph().grant(anchor.get("execution_grant_id")));if(!same(granted.get("current_lease_fact_id"),latest.get("lease_fact_id")))throw new Rejected("CONTINUATION_SUPERSEDED");}
            else{check(socket.bindingGeneration()>number(latest,"holder_binding_generation"));var predecessor=required(l.graph().grantForFact(latest.get("lease_fact_id")));var fact=fact(l,"HOLDER_TRANSFERRED",latest,number(latest,"lease_until"),now);granted=grant(l,started,fact,"CONTINUATION",text(predecessor,"execution_grant_id"),now);store.append(FACT,fact);store.append(GRANT,granted);store.append(ANCHOR,anchor(l,started,granted,now));store.cas(INBOX,l.rows().inbox(),projection(l,fact,number(l.rows().inbox(),"execution_started_at")));advanced=true;}
        }else{
            currentHolder(l,latest);var predecessor=required(l.graph().grant(previousId));check(same(hex(bytes(predecessor,"grant_wire_sha256")),previousHash));if(!same(predecessor.get("current_lease_fact_id"),latest.get("lease_fact_id")))throw new Rejected("RENEWAL_SUPERSEDED");
            until=leaseUntil(l,now,false);check(until>number(latest,"lease_until"));var fact=fact(l,"RENEWED",latest,until,now);granted=grant(l,started,fact,"RENEWAL",previousId,now);store.append(FACT,fact);store.append(GRANT,granted);store.cas(INBOX,l.rows().inbox(),projection(l,fact,number(l.rows().inbox(),"execution_started_at")));advanced=true;
        }
        var mapping=base(l);mapping.putAll(row("execution_request_id",id,"request_kind",kind,"request_fingerprint",sha256(raw),"request_wire_bytes",raw.clone(),"previous_execution_grant_id",previousId,"previous_execution_grant_sha256",previousHash==null?null:HexFormat.of().parseHex(previousHash),"started_receipt_id",receiptId,"requested_holder_binding_generation",socket.bindingGeneration(),"requested_holder_generation_fact_id",l.authority().connected().get("binding_generation_fact_id"),"requested_holder_generation_snapshot_sha256",l.authority().connected().get("generation_snapshot_sha256"),"decision","GRANTED","execution_grant_id",granted.get("execution_grant_id"),"denial_code",null,"decided_at",now,"contract_version",2,"create_time",now,"update_time",now));store.append(REQUEST,mapping);
        var result=new Result(List.of(grantDelivery((byte[])granted.get("grant_wire_bytes"),kind,id,!advanced)),false);finish(l,number(latest,"lease_until"),until,null);return result;
    }
    private void requireNoUnverifiedR4Run(Locked l){
        // R1-R3 without an R4 catalog retain their original normal ACK path.
        // A partial R4 catalog is corruption, NOT evidence that no run exists.
        if(!r4Catalog())return;
        var run=new cn.jia.agent.mapper.AgentRuntimeV2EffectMapper(store.jdbc()).lock(cn.jia.agent.dao.AgentRuntimeV2EffectStore.RUN,row("runtime_dispatch_id",l.origin().get("runtime_dispatch_id")));
        // A new R4 terminal must pass the complete effect/resource verifier's
        // private same-TX seam. Ordinary caller raw ACK is not that proof.
        check(run==null);
    }
    private void requireOpenR4Run(Locked l){
        if(!r4Catalog())return;var run=new cn.jia.agent.mapper.AgentRuntimeV2EffectMapper(store.jdbc()).lock(cn.jia.agent.dao.AgentRuntimeV2EffectStore.RUN,row("runtime_dispatch_id",l.origin().get("runtime_dispatch_id")));if(run!=null)check("ACTIVE".equals(run.get("disposition"))&&number(run,"retired")==0);
    }
    private boolean r4Catalog(){
        return Boolean.TRUE.equals(store.jdbc().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>)connection->{
            var expected=Set.of(cn.jia.agent.dao.AgentRuntimeV2EffectStore.RUN,cn.jia.agent.dao.AgentRuntimeV2EffectStore.EFFECT,cn.jia.agent.dao.AgentRuntimeV2EffectStore.REQUEST,cn.jia.agent.dao.AgentRuntimeV2EffectStore.EVENT,cn.jia.agent.dao.AgentRuntimeV2EffectStore.BLOB);var found=new HashSet<String>();
            try(var tables=connection.getMetaData().getTables(connection.getCatalog(),null,"%",null)){while(tables.next()){String name=tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);if(expected.contains(name))found.add(name);}}
            check(found.isEmpty()||found.equals(expected));return !found.isEmpty();
        }));
    }
    private Map<String,Object> live(Locked l,long now){var f=required(l.graph().latest());check(Set.of("START_GRANTED","HOLDER_TRANSFERRED","RENEWED").contains(f.get("fact_type"))&&now<number(f,"lease_until"));if(l.authority().drain()!=null)check(number(l.graph().facts().getFirst(),"effective_at")<number(l.authority().drain().operation(),"cutoff_at"));return f;}
    private static void currentHolder(Locked l,Map<String,Object> latest){check(same(latest.get("holder_generation_fact_id"),l.authority().connected().get("binding_generation_fact_id"))&&same(latest.get("holder_binding_generation"),l.authority().binding().get("binding_generation")));}
    private long leaseUntil(Locked l,long now,boolean initial){long until=Math.addExact(now,TTL);if(initial)until=Math.min(until,l.origin().number("expires_at"));if(l.authority().credentialExpiresAt()!=null)until=Math.min(until,l.authority().credentialExpiresAt());if(l.authority().drain()!=null)until=Math.min(until,number(l.authority().drain().operation(),"deadline_at"));check(until>now);return until;}
    private void finish(Locked l,Long oldUntil,Long newUntil,Long commandUntil){
        var d=required(store.hint(DELIVERY,row("id",l.origin().get("delivery_id"))));var i=required(store.hint(INBOX,row("id",l.origin().get("inbox_id"))));evidence.inspect(new AgentRuntimeV2DispatchSource.Locked(d,l.rows().source(),i),l.origin(),false);
        Runnable fresh=()->{long now=clock.millis();l.authority().fresh(now);check((oldUntil==null||now<oldUntil)&&(newUntil==null||now<newUntil)&&(commandUntil==null||now<commandUntil));};fresh.run();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE;}@Override public void beforeCommit(boolean readOnly){fresh.run();}});
    }
    @Override public boolean current(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,byte[] grantBytes){outside();try{var grant=AgentRuntimeV2WireCodec.decodeExecutionGrant(grantBytes);return Boolean.TRUE.equals(retry(()->tx.execute(status->{var l=lock(identity,socket,grant.runtimeDispatchId(),null);var latest=live(l,clock.millis());currentHolder(l,latest);var persisted=required(l.graph().grant(grant.executionGrantId()));check(same(persisted.get("grant_wire_bytes"),grantBytes)&&same(persisted.get("current_lease_fact_id"),latest.get("lease_fact_id")));finish(l,number(latest,"lease_until"),null,null);return true;})));}catch(RuntimeException failure){outside();return false;}}
    private Map<String,Object> base(Locked l){var r=l.origin();var result=row("runtime_dispatch_id",r.get("runtime_dispatch_id"),"runtime_binding_id",r.get("runtime_binding_id"),"runtime_instance_id",r.get("runtime_instance_id"),"client_id",r.get("command_caller_client_id"),"evidence_provenance","R2B1_ENFORCED","execution_origin_snapshot_sha256",r.get("execution_origin_snapshot_sha256"));for(String k:List.of("tenant_id","data_domain_id","execution_pool_id","runtime_credential_client_id","owner_jiacn","agent_id","producer_deployment_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch"))result.put(k,r.get(k));return result;}
    private void holder(Map<String,Object> value,Locked l,String prefix){value.put(prefix+"_generation_fact_id",l.authority().connected().get("binding_generation_fact_id"));value.put(prefix+"_generation_snapshot_sha256",l.authority().connected().get("generation_snapshot_sha256"));}
    private Map<String,Object> receipt(Locked l,Ack a,Map<String,Object> fact,long now){var r=base(l);holder(r,l,"ack_holder");String id=UUID.randomUUID().toString();long dv=Math.addExact(number(l.rows().delivery(),"version"),1),iv=Math.addExact(number(l.rows().inbox(),"version"),1);var wire=AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(id,a.runtimeDispatchId(),a.ackMessageId(),a.sourceMessageId(),a.ackStatus(),a.ackAt(),now,dv,iv,fact==null?null:text(fact,"lease_fact_id")));r.putAll(row("receipt_id",id,"inbox_id",l.origin().get("inbox_id"),"delivery_id",l.origin().get("delivery_id"),"ack_message_id",a.ackMessageId(),"source_message_id",a.sourceMessageId(),"status",a.ackStatus(),"ack_at",a.ackAt(),"request_fingerprint",ackFingerprint(a),"dispatch_binding_generation",l.origin().get("dispatch_binding_generation"),"ack_holder_binding_generation",l.authority().binding().get("binding_generation"),"postcommit_delivery_version",dv,"postcommit_inbox_version",iv,"lease_fact_id",fact==null?null:fact.get("lease_fact_id"),"contract_version",2,"committed_at",now,"receipt_wire_bytes",wire,"receipt_wire_sha256",sha256(wire),"receipt_wire_contract_version",2,"create_time",now,"update_time",now));return r;}
    private Map<String,Object> fact(Locked l,String type,Map<String,Object> previous,Long until,long now){var f=base(l);holder(f,l,"holder");var a=l.authority();var op=a.drain()==null?null:a.drain().operation();f.putAll(row("lease_fact_id",UUID.randomUUID().toString(),"inbox_id",l.origin().get("inbox_id"),"delivery_id",l.origin().get("delivery_id"),"fact_sequence",previous==null?0:Math.addExact(number(previous,"fact_sequence"),1),"lease_revision",previous==null?0:type.equals("RENEWED")?Math.addExact(number(previous,"lease_revision"),1):number(previous,"lease_revision"),"fact_type",type,"previous_lease_fact_id",previous==null?null:previous.get("lease_fact_id"),"effective_at",now,"lease_until",until,"causing_receipt_id",null,"dispatch_binding_generation",l.origin().get("dispatch_binding_generation"),"holder_binding_generation",a.binding().get("binding_generation"),"tenant_control_operation_id",op==null?null:op.get("drain_operation_id"),"tenant_control_revision",a.tenant().get("control_revision"),"tenant_control_cutoff_at",op==null?null:op.get("tenant_cutoff_at"),"module_control_operation_id",op==null?null:op.get("drain_operation_id"),"module_mode_revision",a.module().get("mode_revision"),"module_control_cutoff_at",op==null?null:op.get("module_cutoff_at"),"deployment_control_operation_id",null,"deployment_registration_revision",a.deployment().get("registration_revision"),"deployment_control_cutoff_at",null,"reason",type,"created_at",now,"create_time",now,"update_time",now));return f;}
    private Map<String,Object> grant(Locked l,Map<String,Object> started,Map<String,Object> f,String kind,String previous,long now){var g=base(l);holder(g,l,"holder");var v=new AgentRuntimeV2WireCodec.ExecutionGrant(UUID.randomUUID().toString(),kind,l.origin().text("runtime_dispatch_id"),text(started,"receipt_id"),bytes(started,"receipt_wire_sha256"),l.origin().text("runtime_binding_id"),l.origin().text("runtime_instance_id"),l.origin().number("dispatch_binding_generation"),number(f,"holder_binding_generation"),kind.equals("START")?text(f,"lease_fact_id"):text(l.graph().facts().getFirst(),"lease_fact_id"),text(f,"lease_fact_id"),number(f,"lease_revision"),number(f,"lease_until"),previous,now);byte[] wire=AgentRuntimeV2WireCodec.encodeExecutionGrant(v);g.putAll(row("execution_grant_id",v.executionGrantId(),"grant_kind",kind,"started_receipt_id",v.startedReceiptId(),"started_receipt_sha256",v.startedReceiptSha256(),"dispatch_binding_generation",v.dispatchBindingGeneration(),"holder_binding_generation",v.holderBindingGeneration(),"original_start_lease_fact_id",v.originalStartLeaseFactId(),"current_lease_fact_id",v.currentLeaseFactId(),"lease_revision",v.leaseRevision(),"lease_until",v.leaseUntil(),"previous_execution_grant_id",previous,"issued_at",now,"grant_wire_bytes",wire,"grant_wire_sha256",sha256(wire),"grant_wire_contract_version",2,"create_time",now,"update_time",now));return g;}
    private Map<String,Object> anchor(Locked l,Map<String,Object> started,Map<String,Object> grant,long now){var a=base(l);holder(a,l,"holder");a.remove("runtime_binding_id");a.remove("runtime_instance_id");a.putAll(row("started_receipt_id",started.get("receipt_id"),"holder_binding_generation",grant.get("holder_binding_generation"),"execution_grant_id",grant.get("execution_grant_id"),"created_at",now,"contract_version",2,"create_time",now,"update_time",now));return a;}
    private Map<String,Object> projection(Locked l,Map<String,Object> f,long start){return row("execution_started_at",start,"execution_lease_until",f.get("lease_until"),"execution_lease_revision",f.get("lease_revision"),"execution_holder_binding_id",f.get("runtime_binding_id"),"execution_holder_binding_generation",f.get("holder_binding_generation"),"execution_holder_runtime_instance_id",f.get("runtime_instance_id"),"execution_current_lease_fact_id",f.get("lease_fact_id"),"update_time",f.get("effective_at"));}
}
