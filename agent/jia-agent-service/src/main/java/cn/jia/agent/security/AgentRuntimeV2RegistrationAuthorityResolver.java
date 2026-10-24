package cn.jia.agent.security;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;

/** All 51 evidence inputs are locking current reads, never WorkerScope or handshake snapshots. */
public final class AgentRuntimeV2RegistrationAuthorityResolver {
    public record Authority(AgentRuntimeV2RegistrationFingerprints.Subject subject, byte[] subjectHash,
        Map<String,Object> registration, Map<String,Object> tenant, Map<String,Object> module,
        Map<String,Object> deployment, Map<String,Object> grant, Map<String,Object> admission,
        Long credentialExpiresAt, byte[] fingerprint, boolean permitted, boolean frozenRequired) {}
    public record Drain(Map<String,Object> operation,Map<String,Object> completion) {}
    private final AgentRuntimeV2RegistrationStore db;
    private final AgentRuntimeV2RegistrationKeyRing keys;
    private final Clock clock;
    public AgentRuntimeV2RegistrationAuthorityResolver(AgentRuntimeV2RegistrationStore db,
            AgentRuntimeV2RegistrationKeyRing keys, Clock clock) { this.db=db;this.keys=keys;this.clock=clock; }
    public AgentRuntimeV2RegistrationContext proof(String reference,String agent,String secret) {
        var key=keys.active();return new AgentRuntimeV2RegistrationContext("API_KEY",reference,agent,key.keyId(),apiKeyCredential(key,reference,secret));
    }
    public Authority resolve(AgentRuntimeV2RegistrationContext identity) {
        db.transaction(); if(identity==null||!"API_KEY".equals(identity.credentialKind())) throw denied();
        Map<String,Object> hint=required(db.hint("oauth_api_key",row("id",identity.credentialReference())));
        String tenant=text(hint,"tenant_id"), client=text(hint,"client_id"), owner=text(hint,"jiacn");
        Map<String,Object> authorityHint=required(db.hint("agent_runtime_v2_registration_authority",
            row("credential_kind","API_KEY","credential_reference",identity.credentialReference(),"tenant_id",tenant,
                "client_id",client,"owner_jiacn",owner,"agent_id",identity.agentId())));
        String domain=text(authorityHint,"data_domain_id"), moduleId=text(authorityHint,"module_id");
        Map<String,Object> control=db.lock("core_tenant_control",row("tenant_id",tenant));
        Map<String,Object> module=db.lock("core_tenant_module_control",row("tenant_id",tenant,"module_id",moduleId));
        Map<String,Object> oauthClient=db.lock("oauth_client",row("client_id",client));
        Map<String,Object> clientGrant=db.lock("oauth_client_tenant_grant",row("tenant_id",tenant,"client_id",client));
        Map<String,Object> domainGrant=db.lock("oauth_client_tenant_domain_grant",row("tenant_id",tenant,"client_id",client,"data_domain_id",domain));
        Map<String,Object> account=required(db.lock("user_info",row("jiacn",owner)));
        check("ACTIVE".equals(account.get("account_state")));number(account,"auth_epoch");number(account,"id");
        Map<String,Object> key=required(db.lock("oauth_api_key",row("id",identity.credentialReference())));
        check(same(tenant,key.get("tenant_id"))&&same(client,key.get("client_id"))&&same(owner,key.get("jiacn")));
        check(number(key,"status")==1 && (key.get("expire_time")==null || number(key,"expire_time")>clock.millis()));
        String secret=text(key,"api_key");
        check(constantTimeEquals(identity.credentialProof(),apiKeyCredential(keys.require(identity.credentialKeyId()),identity.credentialReference(),secret)));
        Map<String,Object> runtime=required(db.lock("agent_runtime",row("tenant_id",tenant,"client_id",client,"owner_jiacn",owner,"agent_id",identity.agentId())));
        Map<String,Object> registry=required(db.lock("agent_identity_registry",row("tenant_id",tenant,"client_id",client,"owner_jiacn",owner,"canonical_agent_id",identity.agentId())));
        check("ACTIVE".equals(registry.get("lifecycle_status"))&&same(runtime.get("binding_id"),registry.get("binding_id")));
        Map<String,Object> persona=required(db.lock("agent_persona_binding",row("id",runtime.get("binding_id"),"tenant_id",tenant,"client_id",client,"jiacn",owner,"agent_id",identity.agentId())));
        check(number(persona,"status")==1);
        Map<String,Object> registration=required(db.lock("agent_runtime_v2_registration_authority",row("registration_authority_id",authorityHint.get("registration_authority_id"))));
        for(String field:List.of("credential_kind","credential_reference","tenant_id","client_id","owner_jiacn","agent_id","data_domain_id","module_id","claimant_deployment_id","execution_pool_id"))check(same(authorityHint.get(field),registration.get(field)));
        check("ACTIVE".equals(registration.get("status")));
        check(constantTimeEquals(bytes(registration,"credential_digest"),apiKeyCredential(keys.require(text(registration,"credential_key_id")),identity.credentialReference(),secret)));
        var subject=new AgentRuntimeV2RegistrationFingerprints.Subject("API_KEY",identity.credentialReference(),tenant,client,owner,identity.agentId());
        byte[] subjectHash=subject(subject);
        String deploymentId=text(registration,"claimant_deployment_id");
        Map<String,Object> deployment=db.lock("core_deployment_registration",row("deployment_id",deploymentId));
        Map<String,Object> grant=db.lock("core_deployment_tenant_grant",row("deployment_id",deploymentId,"tenant_id",tenant,"data_domain_id",domain));
        Map<String,Object> admission=db.lock("core_deployment_module_admission",row("deployment_id",deploymentId,"tenant_id",tenant,"data_domain_id",domain,"module_id",moduleId));
        boolean frozen=control!=null&&module!=null&&("DRAINING".equals(control.get("state"))||"QUIESCING".equals(module.get("mode_state"))||deployment!=null&&"DRAINING".equals(deployment.get("status")));
        boolean valid=control!=null&&module!=null&&oauthClient!=null&&active(clientGrant)&&active(domainGrant)&&active(grant)&&active(admission)&&deployment!=null;
        if(valid) valid=List.of("ENABLED","AUTH_CLOSED","DRAINING").contains(control.get("state"))
            &&List.of("DOMAIN","QUIESCING").contains(module.get("mode_state"))
            &&List.of("ACTIVE","DRAINING").contains(deployment.get("status"))
            &&List.of("SHARED","LOCAL_TEST").contains(deployment.get("stage"))
            &&same(registration.get("execution_pool_id"),grant.get("execution_pool_id"))
            &&flag(grant,"allow_read")&&flag(grant,"allow_write")&&flag(admission,"allow_read")&&flag(admission,"allow_write")
            &&same(admission.get("registration_revision"),deployment.get("registration_revision"))
            &&same(admission.get("grant_revision"),grant.get("grant_revision"))
            &&(frozen||same(admission.get("mode_state"),module.get("mode_state"))
                &&same(admission.get("mode_revision"),module.get("mode_revision"))
                &&same(admission.get("migration_epoch"),module.get("migration_epoch"))
                &&same(admission.get("admission_epoch"),control.get("admission_epoch"))
                &&same(admission.get("schema_version"),module.get("schema_version")))
            &&(frozen||domainSeal(module));
        byte[] fingerprint=null;
        try {
            List<String> f=new ArrayList<>();f.add(hex(subjectHash));
            add(f,account,"id","auth_epoch");f.add(text(registration,"credential_key_id"));f.add(hex(bytes(registration,"credential_digest")));add(f,key,"expire_time");
            add(f,runtime,"id");add(f,registry,"id","update_time");add(f,persona,"id","update_time");
            add(f,clientGrant,"grant_revision");add(f,domainGrant,"domain_revision");
            add(f,registration,"registration_authority_id","authority_revision","data_domain_id","execution_pool_id");
            add(f,control,"state","control_revision","tenant_session_epoch","admission_epoch","drain_epoch");
            add(f,module,"module_id","mode_state","mode_revision","migration_epoch","drain_sealed","schema_version","mapping_digest","drain_cutoff","drain_deadline");
            add(f,deployment,"deployment_id","stage","artifact_digest","status","worker_generation","registration_revision");
            add(f,grant,"grant_revision","allow_read","allow_write");
            add(f,admission,"mode_state","mode_revision","migration_epoch","admission_epoch","schema_version","registration_revision","grant_revision","allow_read","allow_write","status");
            fingerprint=authority(new AgentRuntimeV2RegistrationAuthority(f));
        } catch(IllegalArgumentException|NullPointerException ignored) { valid=false; }
        return new Authority(subject,subjectHash,registration,control,module,deployment,grant,admission,key.get("expire_time")==null?null:number(key,"expire_time"),fingerprint,valid,frozen);
    }
    public Drain lockDrain(Authority a,String binding) {
        if(!a.frozenRequired())return null;
        if(binding==null||!a.permitted())throw denied();
        Map<String,Object> hint=required(db.hint(OPERATION,row("tenant_id",a.subject().tenantId(),"module_id",a.registration().get("module_id"),"status","OPEN")));
        Map<String,Object> source=null;
        if(hint.get("source_tenant_operator_id")!=null) source=required(db.lock("core_tenant_operation",row("tenant_id",a.subject().tenantId(),"operator_id",hint.get("source_tenant_operator_id"),"idempotency_key",hint.get("source_tenant_idempotency_key"))));
        Map<String,Object> op=required(db.lock(OPERATION,row("drain_operation_id",hint.get("drain_operation_id"))));
        Map<String,Object> grant=required(db.lock(COMPLETION,row("drain_operation_id",op.get("drain_operation_id"),"runtime_binding_id",binding)));
        check(same(op.get("tenant_id"),a.subject().tenantId())&&same(op.get("module_id"),a.registration().get("module_id")));
        for(String field:List.of("source_tenant_operator_id","source_tenant_idempotency_key"))check(same(op.get(field),hint.get(field)));
        check("OPEN".equals(op.get("status"))&&"ACTIVE".equals(grant.get("status")));
        check(constantTimeEquals(AgentRuntimeV2RegistrationDeltaHashes.operation(op),bytes(op,"operation_snapshot_sha256")));
        check(constantTimeEquals(AgentRuntimeV2RegistrationDeltaHashes.completion(grant),bytes(grant,"completion_snapshot_sha256")));
        check(same(op.get("operation_snapshot_sha256"),grant.get("operation_snapshot_sha256")));
        long now=clock.millis();check(number(op,"cutoff_at")>0&&number(op,"cutoff_at")<=number(op,"issued_at")&&now>=number(op,"issued_at")&&now<number(op,"deadline_at")&&number(grant,"issued_at")>=number(op,"issued_at")&&number(grant,"issued_at")<number(op,"deadline_at"));
        for(String[] pair:new String[][]{{"tenant_state","state"},{"tenant_control_revision","control_revision"},{"tenant_session_epoch","tenant_session_epoch"},{"tenant_admission_epoch","admission_epoch"},{"tenant_drain_epoch","drain_epoch"}})check(same(op.get(pair[0]),a.tenant().get(pair[1])));
        for(String[] pair:new String[][]{{"module_mode_state","mode_state"},{"module_mode_revision","mode_revision"},{"module_migration_epoch","migration_epoch"},{"module_schema_version","schema_version"},{"module_mapping_digest","mapping_digest"}})check(same(op.get(pair[0]),a.module().get(pair[1])));
        String scope=text(op,"operation_scope");
        check(List.of("TENANT_DRAIN","MODULE_QUIESCE","COMBINED").contains(scope));
        if("TENANT_DRAIN".equals(scope))check("DOMAIN".equals(a.module().get("mode_state"))&&domainSeal(a.module())&&op.get("module_cutoff_at")==null&&op.get("module_deadline_at")==null);
        else check("QUIESCING".equals(a.module().get("mode_state"))&&!flag(a.module(),"drain_sealed"));
        if("MODULE_QUIESCE".equals(scope))check(List.of("ENABLED","AUTH_CLOSED").contains(a.tenant().get("state"))&&op.get("tenant_cutoff_at")==null&&op.get("tenant_deadline_at")==null&&op.get("source_tenant_operator_id")==null&&op.get("source_tenant_idempotency_key")==null);
        if(List.of("TENANT_DRAIN","COMBINED").contains(scope)) {
            check("DRAINING".equals(a.tenant().get("state"))&&source!=null&&"DRAINING".equals(source.get("to_state"))&&same(source.get("result_revision"),op.get("tenant_control_revision")));
            check(same(source.get("tenant_id"),op.get("tenant_id"))&&same(source.get("operator_id"),op.get("source_tenant_operator_id"))&&same(source.get("idempotency_key"),op.get("source_tenant_idempotency_key")));
            check(Objects.equals(source.get("result_snapshot"),"DRAINING|"+op.get("tenant_control_revision")+"|"+op.get("tenant_session_epoch")+"|"+op.get("tenant_admission_epoch")));
            check(same(op.get("tenant_cutoff_at"),millis(a.tenant().get("drain_started_at")))&&same(op.get("tenant_deadline_at"),millis(a.tenant().get("drain_deadline"))));
        }
        if(List.of("MODULE_QUIESCE","COMBINED").contains(scope))check("QUIESCING".equals(a.module().get("mode_state"))&&same(op.get("module_cutoff_at"),millis(a.module().get("drain_cutoff")))&&same(op.get("module_deadline_at"),millis(a.module().get("drain_deadline"))));
        long cutoff=Long.MAX_VALUE,deadline=Long.MAX_VALUE;
        for(String lane:List.of("tenant","module"))if(op.get(lane+"_cutoff_at")!=null){long start=number(op,lane+"_cutoff_at"),end=number(op,lane+"_deadline_at");check(start>0&&end>start);cutoff=Math.min(cutoff,start);deadline=Math.min(deadline,end);}
        check(cutoff==number(op,"cutoff_at")&&deadline==number(op,"deadline_at"));
        check(same(grant.get("tenant_id"),a.subject().tenantId())&&same(grant.get("module_id"),a.registration().get("module_id"))&&same(grant.get("registration_subject_sha256"),a.subjectHash())&&same(grant.get("registration_authority_id"),a.registration().get("registration_authority_id"))&&same(grant.get("claimant_deployment_id"),a.deployment().get("deployment_id"))&&same(grant.get("claimant_worker_generation"),a.deployment().get("worker_generation")));
        return new Drain(op,grant);
    }
    public static boolean flag(Map<String,Object> r,String name) { Object value=r.get(name);return Boolean.TRUE.equals(value)||value instanceof Number n&&n.longValue()==1; }
    private static boolean domainSeal(Map<String,Object> module) { return flag(module,"drain_sealed")&&number(module,"schema_version")>0&&module.get("mapping_digest") instanceof String digest&&digest.matches("[0-9a-f]{64}"); }
    private static boolean active(Map<String,Object> r) { return r!=null&&"ACTIVE".equals(r.get("status")); }
    public static Object millis(Object value) { return value instanceof Timestamp time?time.toInstant().toEpochMilli():value; }
    private static void add(List<String> fields,Map<String,Object> row,String... names) {
        if(row==null)throw new IllegalArgumentException("missing authority row");
        for(String name:names){Object v=millis(row.get(name));fields.add(v==null?null:v instanceof Boolean b?b?"1":"0":v.toString());}
    }
    public static void check(boolean value) { if(!value)throw denied(); }
    public static Map<String,Object> required(Map<String,Object> row) { if(row==null)throw denied();return row; }
    public static SecurityException denied() { return new SecurityException("registration not authorized"); }
}
