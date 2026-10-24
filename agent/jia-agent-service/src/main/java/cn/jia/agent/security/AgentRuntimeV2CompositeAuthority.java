package cn.jia.agent.security;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Independent composite current reader. Never nests a worker guard or single-side resolver. */
public final class AgentRuntimeV2CompositeAuthority {
    public record Scope(String tenant,String domain,String pool,String caller,String owner,String agent,
        String producer,String claimant,long worker,long revision,long admission,String module,long migration) {}
    public record Snapshot(Scope scope,Map<String,Object> binding,Map<String,Object> connected,
        byte[] receiverFingerprint,Long credentialExpiresAt,boolean admitted) {
        public AgentRuntimeV2SocketTuple tuple(){return new AgentRuntimeV2SocketTuple(text(binding,"runtime_binding_id"),number(binding,"binding_generation"),text(binding,"runtime_instance_id"),text(binding,"current_session_fence"),text(binding,"socket_owner_instance_id"),number(binding,"socket_owner_epoch"));}
        public void fresh(long now){check(now>0&&admitted&&"ACTIVE".equals(binding.get("status"))&&now<number(binding,"socket_owner_lease_until")&&(credentialExpiresAt==null||now<credentialExpiresAt));}
    }
    private final AgentRuntimeV2RegistrationStore db;
    private final AgentRuntimeV2RegistrationKeyRing keys;
    private final Clock clock;
    public AgentRuntimeV2CompositeAuthority(AgentRuntimeV2RegistrationStore db,AgentRuntimeV2RegistrationKeyRing keys,Clock clock){this.db=db;this.keys=keys;this.clock=clock;}
    public Snapshot lock(Scope s,String bindingId,AgentRuntimeV2CompositeWorkerProof process){
        db.transaction();
        Map<String,Object> hint=required(db.hint(BINDING,bindingId==null?row("tenant_id",s.tenant(),"owner_jiacn",s.owner(),"agent_id",s.agent(),"status","ACTIVE"):row("runtime_binding_id",bindingId)));
        String credential=text(hint,"client_id"),reference=text(hint,"credential_reference");
        check("API_KEY".equals(hint.get("credential_kind")));
        Map<String,Object> tenant=required(db.lock("core_tenant_control",row("tenant_id",s.tenant())));
        Map<String,Object> module=required(db.lock("core_tenant_module_control",row("tenant_id",s.tenant(),"module_id",s.module())));
        Map<String,Map<String,Object>> clients=new HashMap<>(),clientGrants=new HashMap<>(),domainGrants=new HashMap<>();
        for(String client:sorted(s.caller(),credential)){
            clients.put(client,required(db.lock("oauth_client",row("client_id",client))));
            clientGrants.put(client,required(db.lock("oauth_client_tenant_grant",row("tenant_id",s.tenant(),"client_id",client))));
            domainGrants.put(client,required(db.lock("oauth_client_tenant_domain_grant",row("tenant_id",s.tenant(),"client_id",client,"data_domain_id",s.domain()))));
        }
        Map<String,Object> account=required(db.lock("user_info",row("jiacn",s.owner())));
        Map<String,Object> apiKey=required(db.lock("oauth_api_key",row("id",reference)));
        for(String[] pair:new String[][]{{"tenant_id",s.tenant()},{"client_id",credential},{"jiacn",s.owner()}})check(same(apiKey.get(pair[0]),pair[1]));
        Map<String,Object> runtime=required(db.lock("agent_runtime",row("tenant_id",s.tenant(),"client_id",credential,"owner_jiacn",s.owner(),"agent_id",s.agent())));
        Map<String,Object> identity=required(db.lock("agent_identity_registry",row("tenant_id",s.tenant(),"client_id",credential,"owner_jiacn",s.owner(),"canonical_agent_id",s.agent())));
        check(same(runtime.get("binding_id"),identity.get("binding_id")));
        Map<String,Object> persona=required(db.lock("agent_persona_binding",row("id",runtime.get("binding_id"),"tenant_id",s.tenant(),"client_id",credential,"jiacn",s.owner(),"agent_id",s.agent())));
        Map<String,Object> registration=required(db.lock("agent_runtime_v2_registration_authority",row("registration_authority_id",hint.get("registration_authority_id"))));
        var subject=new Subject("API_KEY",reference,s.tenant(),credential,s.owner(),s.agent());byte[] subjectHash=subject(subject);
        for(String[] pair:new String[][]{{"credential_kind","API_KEY"},{"credential_reference",reference},{"tenant_id",s.tenant()},{"client_id",credential},{"owner_jiacn",s.owner()},{"agent_id",s.agent()},{"data_domain_id",s.domain()},{"execution_pool_id",s.pool()},{"module_id",s.module()},{"claimant_deployment_id",s.claimant()}})check(same(registration.get(pair[0]),pair[1]));
        check(same(registration.get("credential_digest"),apiKeyCredential(keys.require(text(registration,"credential_key_id")),reference,text(apiKey,"api_key"))));
        Map<String,Map<String,Object>> deployments=new HashMap<>(),grants=new HashMap<>(),admissions=new HashMap<>();
        for(String deployment:sorted(s.producer(),s.claimant())){
            deployments.put(deployment,required(db.lock("core_deployment_registration",row("deployment_id",deployment))));
            grants.put(deployment,required(db.lock("core_deployment_tenant_grant",row("deployment_id",deployment,"tenant_id",s.tenant(),"data_domain_id",s.domain()))));
            admissions.put(deployment,required(db.lock("core_deployment_module_admission",row("deployment_id",deployment,"tenant_id",s.tenant(),"data_domain_id",s.domain(),"module_id",s.module()))));
        }
        boolean admitted=Set.of("ENABLED","AUTH_CLOSED").contains(tenant.get("state"))&&"DOMAIN".equals(module.get("mode_state"))&&flag(module,"drain_sealed")&&number(module,"schema_version")>0&&text(module,"mapping_digest").matches("[0-9a-f]{64}")&&same(tenant.get("admission_epoch"),s.admission())&&same(module.get("migration_epoch"),s.migration())&&"ACTIVE".equals(account.get("account_state"))&&number(apiKey,"status")==1&&number(persona,"status")==1&&"ACTIVE".equals(identity.get("lifecycle_status"))&&active(registration);
        for(String client:clients.keySet())admitted&=active(clientGrants.get(client))&&active(domainGrants.get(client));
        for(String id:deployments.keySet()){
            var d=deployments.get(id);var g=grants.get(id);var a=admissions.get(id);
            admitted&=active(d)&&Set.of("SHARED","LOCAL_TEST").contains(d.get("stage"))&&active(g)&&active(a)&&same(g.get("execution_pool_id"),s.pool())&&flag(g,"allow_read")&&flag(g,"allow_write")&&flag(a,"allow_read")&&flag(a,"allow_write")&&same(a.get("registration_revision"),d.get("registration_revision"))&&same(a.get("grant_revision"),g.get("grant_revision"))&&same(a.get("mode_state"),module.get("mode_state"))&&same(a.get("mode_revision"),module.get("mode_revision"))&&same(a.get("migration_epoch"),module.get("migration_epoch"))&&same(a.get("admission_epoch"),tenant.get("admission_epoch"))&&same(a.get("schema_version"),module.get("schema_version"));
        }
        var deployment=deployments.get(s.claimant());var grant=grants.get(s.claimant());var admission=admissions.get(s.claimant());
        admitted&=same(deployment.get("worker_generation"),s.worker())&&same(deployment.get("registration_revision"),s.revision());
        if(process!=null){var w=process.scope();check(w.tenantId().value().equals(s.tenant())&&w.dataDomainId().value().equals(s.domain())&&w.executionPoolId().equals(s.pool())&&w.deploymentId().equals(s.claimant())&&w.workerGeneration()==s.worker()&&w.registrationRevision()==s.revision()&&w.admissionEpoch()==s.admission()&&w.moduleId().equals(s.module())&&w.migrationEpoch()==s.migration());check(same(deployment.get("artifact_digest"),process.artifactSha256())&&same(deployment.get("credential_digest"),process.credentialSha256()));}
        List<String> f=new ArrayList<>();f.add(hex(subjectHash));add(f,account,"id","auth_epoch");f.add(text(registration,"credential_key_id"));f.add(hex(bytes(registration,"credential_digest")));add(f,apiKey,"expire_time");add(f,runtime,"id");add(f,identity,"id","update_time");add(f,persona,"id","update_time");add(f,clientGrants.get(credential),"grant_revision");add(f,domainGrants.get(credential),"domain_revision");add(f,registration,"registration_authority_id","authority_revision","data_domain_id","execution_pool_id");add(f,tenant,"state","control_revision","tenant_session_epoch","admission_epoch","drain_epoch");add(f,module,"module_id","mode_state","mode_revision","migration_epoch","drain_sealed","schema_version","mapping_digest","drain_cutoff","drain_deadline");add(f,deployment,"deployment_id","stage","artifact_digest","status","worker_generation","registration_revision");add(f,grant,"grant_revision","allow_read","allow_write");add(f,admission,"mode_state","mode_revision","migration_epoch","admission_epoch","schema_version","registration_revision","grant_revision","allow_read","allow_write","status");
        byte[] fingerprint;
        try{fingerprint=authority(new AgentRuntimeV2RegistrationAuthority(f));}
        catch(IllegalArgumentException invalid){if(admitted)throw invalid;fingerprint=null;}
        Map<String,Object> b=required(db.lock(BINDING,row("runtime_binding_id",hint.get("runtime_binding_id"))));
        check(number(b,"contract_version")==2&&"R2B1_ENFORCED".equals(b.get("evidence_provenance"))&&same(b.get("registration_subject_sha256"),subjectHash)&&same(b.get("credential_reference"),reference)&&same(b.get("registration_authority_id"),registration.get("registration_authority_id")));
        for(String name:List.of("tenant_id","client_id","owner_jiacn","agent_id","data_domain_id","execution_pool_id","claimant_deployment_id","module_id"))check(same(b.get(name),registration.get(name)));
        var chain=db.factChain(text(b,"runtime_binding_id"));check(!chain.isEmpty());
        Map<String,Map<String,Object>> receipts=new HashMap<>();chain.stream().map(r->(String)r.get("registration_receipt_id")).filter(Objects::nonNull).distinct().sorted().forEach(id->receipts.put(id,required(db.lock(RECEIPT,row("registration_receipt_id",id)))));
        String previous=null;long sequence=0,generation=-1,effective=0;Map<String,Object> priorReceipt=null,connected=null;
        for(var fact:chain){
            check("R2B1_ENFORCED".equals(fact.get("evidence_provenance"))&&same(fact.get("runtime_binding_id"),b.get("runtime_binding_id"))&&number(fact,"fact_sequence")==sequence++&&same(fact.get("previous_fact_id"),previous)&&number(fact,"effective_at")>=effective);effective=number(fact,"effective_at");previous=text(fact,"binding_generation_fact_id");
            if("CONNECTED".equals(fact.get("event_type"))){check(connected==null&&number(fact,"binding_generation")==++generation&&number(fact,"verifier_version")==Math.addExact(generation,1));var r=required(receipts.get(fact.get("registration_receipt_id")));AgentRuntimeV2RegistrationServiceImpl.validateReceipt(r);AgentRuntimeV2RegistrationServiceImpl.validateHolder(fact,r);check(same(fact.get("registration_subject_sha256"),subjectHash)&&same(fact.get("registration_authority_id"),b.get("registration_authority_id"))&&same(fact.get("runtime_instance_id"),b.get("runtime_instance_id"))&&same(fact.get("process_public_key_sha256"),b.get("process_public_key_sha256")));check(priorReceipt==null?r.get("previous_registration_receipt_id")==null&&r.get("previous_registration_receipt_sha256")==null:same(r.get("previous_registration_receipt_id"),priorReceipt.get("registration_receipt_id"))&&same(r.get("previous_registration_receipt_sha256"),priorReceipt.get("receipt_wire_sha256")));connected=fact;priorReceipt=r;}
            else {check("DISCONNECTED".equals(fact.get("event_type"))&&connected!=null&&number(fact,"binding_generation")==generation);for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","session_fence","socket_owner_instance_id","socket_owner_epoch"))check(same(fact.get(name),connected.get(name)));connected=null;}
        }
        check(generation==number(b,"binding_generation"));var latest=chain.getLast();
        if(connected!=null){for(String name:List.of("runtime_instance_id","tenant_id","client_id","data_domain_id","execution_pool_id","owner_jiacn","agent_id","claimant_deployment_id","claimant_worker_generation","receiver_registration_revision","admission_epoch","module_id","migration_epoch","registration_subject_sha256","registration_authority_id","registration_authority_revision","process_public_key_sha256","verifier_version"))check(same(connected.get(name),b.get(name)));check(same(connected.get("session_fence"),b.get("current_session_fence"))&&same(connected.get("socket_owner_instance_id"),b.get("socket_owner_instance_id"))&&same(connected.get("socket_owner_epoch"),b.get("socket_owner_epoch"))&&same(connected.get("registration_receipt_id"),b.get("current_registration_receipt_id"))&&same(connected.get("registration_receipt_sha256"),b.get("current_registration_receipt_sha256")));}
        admitted&="ACTIVE".equals(b.get("status"))&&"CONNECTED".equals(latest.get("event_type"))&&"CURRENT_ADMISSION".equals(latest.get("connection_admission_kind"))&&same(b.get("claimant_worker_generation"),s.worker())&&same(b.get("receiver_registration_revision"),s.revision())&&same(b.get("admission_epoch"),s.admission())&&same(b.get("migration_epoch"),s.migration())&&number(registration,"authority_revision")>=number(b,"registration_authority_revision");
        Long expires=apiKey.get("expire_time")==null?null:number(apiKey,"expire_time");
        if(expires!=null&&clock.millis()>=expires)admitted=false;
        return new Snapshot(s,b,latest,fingerprint,expires,admitted);
    }
    public static List<String> sorted(String... ids){return Arrays.stream(ids).distinct().sorted((a,b)->Arrays.compareUnsigned(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8))).toList();}
    public void original(AgentRuntimeV2InternalOutboxRow r){
        var f=required(db.lock(FACT,row("binding_generation_fact_id",r.get("dispatch_generation_fact_id"))));
        var receipt=required(db.lock(RECEIPT,row("registration_receipt_id",f.get("registration_receipt_id"))));
        AgentRuntimeV2RegistrationServiceImpl.validateReceipt(receipt);AgentRuntimeV2RegistrationServiceImpl.validateHolder(f,receipt);
        for(String[] pair:new String[][]{{"runtime_binding_id","runtime_binding_id"},{"binding_generation","dispatch_binding_generation"},{"runtime_instance_id","runtime_instance_id"},{"generation_snapshot_sha256","dispatch_generation_snapshot_sha256"},{"tenant_id","tenant_id"},{"data_domain_id","data_domain_id"},{"execution_pool_id","execution_pool_id"},{"runtime_credential_client_id","runtime_credential_client_id"},{"owner_jiacn","owner_jiacn"},{"agent_id","agent_id"},{"claimant_deployment_id","claimant_deployment_id"},{"claimant_worker_generation","claimant_worker_generation"},{"receiver_registration_revision","receiver_registration_revision"},{"admission_epoch","admission_epoch"},{"module_id","module_id"},{"migration_epoch","migration_epoch"},{"session_fence","session_fence"},{"socket_owner_instance_id","socket_owner_instance_id"},{"socket_owner_epoch","socket_owner_epoch"}})check(same(f.get(pair[0]),r.get(pair[1])));
    }
    /** Cleanup/prior-result evidence only; cannot grant a claim or send under revoked current authority. */
    public Map<String,Object> maintenance(AgentRuntimeV2InternalOutboxRow r){
        var b=required(db.lock(BINDING,row("runtime_binding_id",r.get("runtime_binding_id"))));
        check(number(b,"contract_version")==2&&"R2B1_ENFORCED".equals(b.get("evidence_provenance")));
        original(r);var f=required(db.lock(FACT,row("binding_generation_fact_id",r.get("dispatch_generation_fact_id"))));
        check(same(f.get("registration_subject_sha256"),b.get("registration_subject_sha256"))&&same(f.get("registration_authority_id"),b.get("registration_authority_id")));
        return b;
    }
    private static boolean active(Map<String,Object> r){return "ACTIVE".equals(r.get("status"));}
    private static void add(List<String> fields,Map<String,Object> row,String... names){for(String name:names){Object v=millis(row.get(name));fields.add(v==null?null:v instanceof Boolean b?b?"1":"0":v.toString());}}
}
