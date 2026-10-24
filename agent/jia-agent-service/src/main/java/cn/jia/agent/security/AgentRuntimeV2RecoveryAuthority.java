package cn.jia.agent.security;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Current independently authenticated historical ACL. Never an executable
 * registration/grant, and deliberately does not call execution history mode.
 * A null socket means authenticated new-process audit, not an invented holder.
 */
public final class AgentRuntimeV2RecoveryAuthority {
    private final AgentRuntimeV2RegistrationStore db;
    private final AgentRuntimeV2RegistrationKeyRing keys;
    private final Clock clock;
    public AgentRuntimeV2RecoveryAuthority(AgentRuntimeV2RegistrationStore db,AgentRuntimeV2RegistrationKeyRing keys,Clock clock){this.db=Objects.requireNonNull(db);this.keys=Objects.requireNonNull(keys);this.clock=Objects.requireNonNull(clock);}

    public static final class Snapshot {
        private final String credentialReference,receiverClientId;
        private final AgentRuntimeV2SocketTuple socket;
        private final Map<String,Object> originalBinding,originalFact,actorFact;
        private final List<Map<String,Object>> originalHistory;
        private final boolean settlement;
        private final Runnable fresh;
        private Snapshot(String reference,String receiver,AgentRuntimeV2SocketTuple socket,History original,Map<String,Object> originalFact,Map<String,Object> actorFact,boolean settlement,Runnable fresh){this.credentialReference=reference;this.receiverClientId=receiver;this.socket=socket;this.originalBinding=copy(original.binding());this.originalFact=copy(originalFact);this.actorFact=actorFact==null?null:copy(actorFact);this.originalHistory=original.facts().stream().map(AgentRuntimeV2RecoveryAuthority::copy).toList();this.settlement=settlement;this.fresh=fresh;}
        public String credentialReference(){return credentialReference;}
        public String receiverClientId(){return receiverClientId;}
        public AgentRuntimeV2SocketTuple socket(){return socket;}
        public Map<String,Object> originalBinding(){return copy(originalBinding);}
        public Map<String,Object> originalFact(){return copy(originalFact);}
        public Map<String,Object> actorFact(){return actorFact==null?null:copy(actorFact);}
        public List<Map<String,Object>> originalHistory(){return originalHistory.stream().map(AgentRuntimeV2RecoveryAuthority::copy).toList();}
        public void fresh(){fresh.run();}
        public void requireSettlement(){fresh();check(settlement);}
    }
    private record LockedRow(String table,Map<String,Object> key,Map<String,Object> values) {}
    private record History(Map<String,Object> binding,List<Map<String,Object>> facts,Map<String,Object> connected) {}
    /** Neither another active transaction nor a retained/rebound JDBC holder
     * inherits the locks. The private synchronization pins this exact lifetime.
     */
    private final class TransactionLease implements TransactionSynchronization {
        private final Thread owner=Thread.currentThread();
        private final Object dataSource=Objects.requireNonNull(db.jdbc().getDataSource());
        private final ConnectionHolder holder;
        private final AtomicBoolean live=new AtomicBoolean(true);
        private volatile boolean suspended;
        private TransactionLease(){
            db.transaction();
            if(!TransactionSynchronizationManager.isSynchronizationActive()||!(TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder current))throw new IllegalStateException("original JDBC transaction required");
            holder=current;
            requireSingleDataSource();
            TransactionSynchronizationManager.registerSynchronization(this);
        }
        private void requireSingleDataSource(){
            // A second manager may suspend only synchronizations while leaving
            // this data source's OUTER holder bound. It is not our transaction.
            if(TransactionSynchronizationManager.getResourceMap().values().stream().anyMatch(value->value instanceof ConnectionHolder&&value!=holder))throw new IllegalStateException("single data source recovery transaction required");
        }
        private void require(){
            if(Thread.currentThread()!=owner||!live.get()||suspended||!TransactionSynchronizationManager.isSynchronizationActive()||TransactionSynchronizationManager.getResource(dataSource)!=holder||TransactionSynchronizationManager.getSynchronizations().stream().noneMatch(item->item==this))throw new IllegalStateException("original recovery transaction ended or suspended");
            db.transaction();
            requireSingleDataSource();
        }
        public int getOrder(){return Integer.MIN_VALUE;}
        public void suspend(){suspended=true;}
        public void resume(){suspended=false;}
        // Final guards run in beforeCommit. No authority survives physical
        // completion, including afterCommit callbacks before afterCompletion.
        public void beforeCompletion(){live.set(false);}
        public void afterCommit(){live.set(false);}
        public void afterCompletion(int status){live.set(false);}
    }
    private Map<String,Object> current(List<LockedRow> locked,String table,Map<String,Object> key){var values=required(db.lock(table,key));locked.add(new LockedRow(table,copy(key),copy(values)));return values;}

    /** Hint chooses scope only. All actual permissions/proofs are locking current
     * reads in the original order. Replacement credentials use their OWN active
     * authority, not the historical credential/verifier copied from the origin.
     */
    public Snapshot lock(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,AgentRuntimeV2InternalOutboxRow origin,boolean settlement){
        var transaction=new TransactionLease();check(identity!=null&&origin!=null&&"API_KEY".equals(identity.credentialKind()));
        var keyHint=required(db.hint("oauth_api_key",row("id",identity.credentialReference())));
        String tenant=text(keyHint,"tenant_id"),receiver=text(keyHint,"client_id"),owner=text(keyHint,"jiacn"),agent=identity.agentId();
        check(same(origin.get("tenant_id"),tenant)&&same(origin.get("runtime_credential_client_id"),receiver)&&same(origin.get("owner_jiacn"),owner)&&same(origin.get("agent_id"),agent));
        var registrationHint=required(db.hint("agent_runtime_v2_registration_authority",row("credential_kind","API_KEY","credential_reference",identity.credentialReference(),"tenant_id",tenant,"client_id",receiver,"owner_jiacn",owner,"agent_id",agent)));
        String caller=origin.text("command_caller_client_id"),domain=origin.text("data_domain_id"),pool=origin.text("execution_pool_id"),moduleId=origin.text("module_id"),producer=origin.text("producer_deployment_id"),claimant=origin.text("claimant_deployment_id");
        var locked=new ArrayList<LockedRow>();
        var tenantRow=current(locked,"core_tenant_control",row("tenant_id",tenant));
        var module=current(locked,"core_tenant_module_control",row("tenant_id",tenant,"module_id",moduleId));
        check(Set.of("ENABLED","AUTH_CLOSED","DRAINING").contains(tenantRow.get("state"))&&Set.of("DOMAIN","QUIESCING").contains(module.get("mode_state")));
        boolean historicalControls="DRAINING".equals(tenantRow.get("state"))||"QUIESCING".equals(module.get("mode_state"));
        // C and R stay separate, even when the receiver has a replacement key.
        for(String client:AgentRuntimeV2ExecutionAuthority.sorted(caller,receiver)){
            current(locked,"oauth_client",row("client_id",client));
            check(active(current(locked,"oauth_client_tenant_grant",row("tenant_id",tenant,"client_id",client))));
            check(active(current(locked,"oauth_client_tenant_domain_grant",row("tenant_id",tenant,"client_id",client,"data_domain_id",domain))));
        }
        var account=current(locked,"user_info",row("jiacn",owner));check("ACTIVE".equals(account.get("account_state")));number(account,"auth_epoch");number(account,"id");
        var apiKey=current(locked,"oauth_api_key",row("id",identity.credentialReference()));
        check(same(apiKey.get("tenant_id"),tenant)&&same(apiKey.get("client_id"),receiver)&&same(apiKey.get("jiacn"),owner)&&number(apiKey,"status")==1);
        Long expires=apiKey.get("expire_time")==null?null:number(apiKey,"expire_time");check(expires==null||clock.millis()<expires);
        check(constantTimeEquals(identity.credentialProof(),apiKeyCredential(keys.require(identity.credentialKeyId()),identity.credentialReference(),text(apiKey,"api_key"))));
        var runtime=current(locked,"agent_runtime",row("tenant_id",tenant,"client_id",receiver,"owner_jiacn",owner,"agent_id",agent));
        var registry=current(locked,"agent_identity_registry",row("tenant_id",tenant,"client_id",receiver,"owner_jiacn",owner,"canonical_agent_id",agent));
        check("ACTIVE".equals(registry.get("lifecycle_status"))&&same(runtime.get("binding_id"),registry.get("binding_id")));
        var persona=current(locked,"agent_persona_binding",row("id",runtime.get("binding_id"),"tenant_id",tenant,"client_id",receiver,"jiacn",owner,"agent_id",agent));check(number(persona,"status")==1);
        var registration=current(locked,"agent_runtime_v2_registration_authority",row("registration_authority_id",registrationHint.get("registration_authority_id")));
        for(String[] pair:new String[][]{{"credential_kind","API_KEY"},{"credential_reference",identity.credentialReference()},{"tenant_id",tenant},{"client_id",receiver},{"owner_jiacn",owner},{"agent_id",agent},{"data_domain_id",domain},{"execution_pool_id",pool},{"module_id",moduleId},{"claimant_deployment_id",claimant}})check(same(registration.get(pair[0]),pair[1]));
        check(active(registration)&&constantTimeEquals(bytes(registration,"credential_digest"),apiKeyCredential(keys.require(text(registration,"credential_key_id")),identity.credentialReference(),text(apiKey,"api_key"))));
        for(String deploymentId:AgentRuntimeV2ExecutionAuthority.sorted(producer,claimant)){
            var deployment=current(locked,"core_deployment_registration",row("deployment_id",deploymentId));
            var grant=current(locked,"core_deployment_tenant_grant",row("deployment_id",deploymentId,"tenant_id",tenant,"data_domain_id",domain));
            var admission=current(locked,"core_deployment_module_admission",row("deployment_id",deploymentId,"tenant_id",tenant,"data_domain_id",domain,"module_id",moduleId));
            check(Set.of("ACTIVE","DRAINING").contains(deployment.get("status"))&&Set.of("SHARED","LOCAL_TEST").contains(deployment.get("stage"))&&active(grant)&&active(admission)&&same(grant.get("execution_pool_id"),pool)&&flag(grant,"allow_read")&&flag(admission,"allow_read"));
            check(same(admission.get("registration_revision"),deployment.get("registration_revision"))&&same(admission.get("grant_revision"),grant.get("grant_revision"))&&same(admission.get("migration_epoch"),module.get("migration_epoch"))&&same(admission.get("schema_version"),module.get("schema_version")));
            // Explicit CURRENT read grants remain checked above. Drain's new
            // executable cutoff/epoch is not a reason to deny historical read,
            // nor is this exception a permit to execute or admit a new effect.
            check(historicalControls||same(admission.get("admission_epoch"),tenantRow.get("admission_epoch"))&&same(admission.get("mode_state"),module.get("mode_state"))&&same(admission.get("mode_revision"),module.get("mode_revision")));
            if(settlement)check(flag(grant,"allow_write")&&flag(admission,"allow_write"));
        }
        // All binding roots precede fact/receipt history. A disconnected or
        // expired ORIGINAL holder is historical evidence, not current authority.
        var bindings=new HashMap<String,Map<String,Object>>();String originalId=origin.text("runtime_binding_id");
        for(String bindingId:AgentRuntimeV2ExecutionAuthority.sorted(originalId,socket==null?originalId:socket.runtimeBindingId()))bindings.put(bindingId,current(locked,BINDING,row("runtime_binding_id",bindingId)));
        var histories=new HashMap<String,History>();
        for(String bindingId:AgentRuntimeV2ExecutionAuthority.sorted(originalId,socket==null?originalId:socket.runtimeBindingId()))histories.put(bindingId,history(bindings.get(bindingId)));
        var original=histories.get(originalId);
        var originalFact=original.facts().stream().filter(f->same(f.get("binding_generation_fact_id"),origin.get("dispatch_generation_fact_id"))).findFirst().orElseThrow(AgentRuntimeV2RegistrationAuthorityResolver::denied);
        check("CONNECTED".equals(originalFact.get("event_type")));
        for(String[] pair:new String[][]{{"runtime_binding_id","runtime_binding_id"},{"binding_generation","dispatch_binding_generation"},{"runtime_instance_id","runtime_instance_id"},{"generation_snapshot_sha256","dispatch_generation_snapshot_sha256"},{"tenant_id","tenant_id"},{"data_domain_id","data_domain_id"},{"execution_pool_id","execution_pool_id"},{"runtime_credential_client_id","runtime_credential_client_id"},{"owner_jiacn","owner_jiacn"},{"agent_id","agent_id"},{"claimant_deployment_id","claimant_deployment_id"},{"claimant_worker_generation","claimant_worker_generation"},{"receiver_registration_revision","receiver_registration_revision"},{"admission_epoch","admission_epoch"},{"module_id","module_id"},{"migration_epoch","migration_epoch"},{"session_fence","session_fence"},{"socket_owner_instance_id","socket_owner_instance_id"},{"socket_owner_epoch","socket_owner_epoch"}})check(same(originalFact.get(pair[0]),origin.get(pair[1])));
        Map<String,Object> actorFact=null;
        if(socket!=null){
            var actor=histories.get(socket.runtimeBindingId());var binding=actor.binding();actorFact=required(actor.connected());
            check("ACTIVE".equals(binding.get("status"))&&tuple(binding).equals(socket)&&clock.millis()<number(binding,"socket_owner_lease_until"));
            check(same(binding.get("registration_subject_sha256"),subject(new Subject("API_KEY",identity.credentialReference(),tenant,receiver,owner,agent)))&&same(binding.get("registration_authority_id"),registration.get("registration_authority_id"))&&same(binding.get("credential_reference"),identity.credentialReference()));
            for(String name:List.of("tenant_id","client_id","owner_jiacn","agent_id","data_domain_id","execution_pool_id","claimant_deployment_id","module_id"))check(same(binding.get(name),registration.get(name)));
        }
        var actorBinding=socket==null?null:bindings.get(socket.runtimeBindingId());
        Runnable fresh=()->{
            transaction.require();long now=clock.millis();check(now>0&&(expires==null||now<expires));
            if(actorBinding!=null)check(now<number(actorBinding,"socket_owner_lease_until"));
            // Same transaction still owns all rows. This also detects accidental
            // authority edits by a resource adapter within this transaction.
            for(var item:locked){var current=required(db.hint(item.table(),item.key()));check(rowsEqual(item.values(),current));}
        };
        fresh.run();return new Snapshot(identity.credentialReference(),receiver,socket,original,originalFact,actorFact,settlement,fresh);
    }
    private History history(Map<String,Object> binding){
        check(number(binding,"contract_version")==2&&"R2B1_ENFORCED".equals(binding.get("evidence_provenance")));
        var facts=db.factChain(text(binding,"runtime_binding_id"));check(!facts.isEmpty());
        Map<String,Object> connected=null,lastConnected=null,previousReceipt=null;String previous=null;long sequence=0,generation=-1,effective=0;
        var receipts=new HashMap<String,Map<String,Object>>();
        facts.stream().map(f->(String)f.get("registration_receipt_id")).filter(Objects::nonNull).distinct().sorted().forEach(id->receipts.put(id,required(db.lock(RECEIPT,row("registration_receipt_id",id)))));
        for(var fact:facts){
            check("R2B1_ENFORCED".equals(fact.get("evidence_provenance"))&&same(fact.get("runtime_binding_id"),binding.get("runtime_binding_id"))&&number(fact,"fact_sequence")==sequence++&&same(fact.get("previous_fact_id"),previous)&&number(fact,"effective_at")>=effective);
            effective=number(fact,"effective_at");previous=text(fact,"binding_generation_fact_id");
            if("CONNECTED".equals(fact.get("event_type"))){
                check(connected==null&&number(fact,"binding_generation")==++generation&&number(fact,"verifier_version")==Math.addExact(generation,1));
                var receipt=required(receipts.get(fact.get("registration_receipt_id")));AgentRuntimeV2RegistrationServiceImpl.validateReceipt(receipt);AgentRuntimeV2RegistrationServiceImpl.validateHolder(fact,receipt);
                for(String name:List.of("runtime_binding_id","runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","module_id","claimant_deployment_id","registration_subject_sha256","registration_authority_id","process_public_key_sha256"))check(same(fact.get(name),binding.get(name)));
                check(same(fact.get("runtime_credential_client_id"),binding.get("client_id")));
                check(previousReceipt==null?receipt.get("previous_registration_receipt_id")==null&&receipt.get("previous_registration_receipt_sha256")==null:same(receipt.get("previous_registration_receipt_id"),previousReceipt.get("registration_receipt_id"))&&same(receipt.get("previous_registration_receipt_sha256"),previousReceipt.get("receipt_wire_sha256")));
                connected=fact;lastConnected=fact;previousReceipt=receipt;
            }else{
                check("DISCONNECTED".equals(fact.get("event_type"))&&connected!=null&&number(fact,"binding_generation")==generation&&(fact.get("reason")==null||Set.of("SOCKET_CLOSED","PROTOCOL_CLOSED","ROUTING_LEASE_EXPIRED","REPLACED_BY_AUTHENTICATED_RECONNECT").contains(fact.get("reason"))));
                for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","session_fence","socket_owner_instance_id","socket_owner_epoch"))check(same(fact.get(name),connected.get(name)));
                connected=null;
            }
        }
        check(generation==number(binding,"binding_generation"));lastConnected=required(lastConnected);
        // Disconnect clears the route projection, not the last authenticated
        // holder/receipt links. They still have to match for audit-only reads.
        for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","module_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","migration_epoch","registration_subject_sha256","registration_authority_id","registration_authority_revision","process_public_key_sha256","verifier_version"))check(same(lastConnected.get(name),binding.get(name)));
        check(same(lastConnected.get("runtime_credential_client_id"),binding.get("client_id"))&&same(lastConnected.get("registration_receipt_id"),binding.get("current_registration_receipt_id"))&&same(lastConnected.get("registration_receipt_sha256"),binding.get("current_registration_receipt_sha256"))&&same(lastConnected.get("effective_at"),binding.get("connected_at")));
        if(connected!=null){
            check("ACTIVE".equals(binding.get("status"))&&binding.get("disconnected_at")==null&&same(connected.get("session_fence"),binding.get("current_session_fence"))&&same(connected.get("socket_owner_instance_id"),binding.get("socket_owner_instance_id"))&&same(connected.get("socket_owner_epoch"),binding.get("socket_owner_epoch"))&&number(binding,"socket_owner_lease_until")>=number(connected,"socket_owner_lease_until"));
        }else{
            var latest=facts.getLast();check("DISCONNECTED".equals(binding.get("status"))&&same(latest.get("previous_fact_id"),lastConnected.get("binding_generation_fact_id"))&&same(latest.get("effective_at"),binding.get("disconnected_at")));
            for(String name:List.of("current_session_fence","socket_owner_instance_id","socket_owner_epoch","socket_owner_lease_until"))check(binding.get(name)==null);
        }
        return new History(binding,facts,connected);
    }
    private static AgentRuntimeV2SocketTuple tuple(Map<String,Object> binding){return new AgentRuntimeV2SocketTuple(text(binding,"runtime_binding_id"),number(binding,"binding_generation"),text(binding,"runtime_instance_id"),text(binding,"current_session_fence"),text(binding,"socket_owner_instance_id"),number(binding,"socket_owner_epoch"));}
    private static boolean active(Map<String,Object> values){return "ACTIVE".equals(values.get("status"));}
    private static Map<String,Object> copy(Map<String,Object> values){var result=new LinkedHashMap<String,Object>();values.forEach((key,value)->result.put(key,value instanceof byte[] bytes?bytes.clone():value));return Collections.unmodifiableMap(result);}
    private static boolean rowsEqual(Map<String,Object> left,Map<String,Object> right){return left.keySet().equals(right.keySet())&&left.entrySet().stream().allMatch(e->same(e.getValue(),right.get(e.getKey())));}
}
