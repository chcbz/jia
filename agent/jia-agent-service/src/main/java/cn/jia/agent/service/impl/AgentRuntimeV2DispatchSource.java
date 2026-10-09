package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV2InternalOutboxDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2CompositeAuthority.Scope;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.jdbc.support.JdbcUtils;
import cn.jia.core.tenant.*;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Immutable source/origin validator shared by prepare, relay and duplicate handoff checks. */
public final class AgentRuntimeV2DispatchSource {
    public record Locked(Map<String,Object> delivery,Map<String,Object> source,Map<String,Object> inbox) {}
    private final AgentRuntimeV2InternalOutboxDao dao;
    /** R2d seam: independent receipt/lease projection verification, never transport versions as execution authority.
     * Implementations must obey the composite lock order; they cannot reacquire earlier control locks here. */
    @FunctionalInterface public interface ContinuationVerifier { void verify(Locked rows,AgentRuntimeV2InternalOutboxRow internal); }
    private final ContinuationVerifier continuation;
    public AgentRuntimeV2DispatchSource(AgentRuntimeV2InternalOutboxDao dao){this(dao,(rows,internal)->{throw new SecurityException("R2d continuation evidence verifier unavailable");});}
    public AgentRuntimeV2DispatchSource(AgentRuntimeV2InternalOutboxDao dao,ContinuationVerifier continuation){this.dao=dao;this.continuation=Objects.requireNonNull(continuation);}
    public Locked lock(AgentInboxClaimToken t,String owner,byte[] raw,boolean preparing){
        var d=required(dao.delivery(t.deliveryId()));var sources=dao.sources(t.deliveryId());
        var candidates=sources.stream().filter(s->same(s.get("message_id"),t.messageId())).toList();check(candidates.size()==1);var o=candidates.getFirst();
        var i=required(dao.inbox(t.inboxId()));
        var expected=row("protocol_version",2,"tenant_id",t.tenantId(),"data_domain_id",t.dataDomainId(),"execution_pool_id",t.executionPoolId(),"admission_epoch",t.admissionEpoch(),"producer_deployment_id",t.producerDeploymentId(),"module_id",t.moduleId(),"migration_epoch",t.migrationEpoch(),"client_id",t.clientId(),"command_id",t.commandId(),"expires_at",t.expiresAt());
        for(var persisted:List.of(d,o,i))expected.forEach((k,v)->check(same(persisted.get(k),v)));
        check(same(d.get("task_id"),t.taskId())&&same(d.get("target_agent_id"),t.targetAgentId())&&same(d.get("owner_jiacn"),owner)&&same(d.get("command_type"),t.commandType())&&same(d.get("active_message_id"),t.messageId())&&same(d.get("active_attempt"),t.deliveryActiveAttempt())&&same(d.get("attempt_count"),t.deliveryActiveAttempt()));
        check(same(o.get("event_id"),t.eventId())&&same(o.get("delivery_id"),t.deliveryId())&&same(o.get("active_attempt"),t.deliveryActiveAttempt())&&"PUBLISHED".equals(o.get("status")));
        check(same(i.get("consumer_name"),t.consumerName())&&same(i.get("message_id"),t.messageId())&&same(i.get("event_id"),t.eventId())&&same(i.get("delivery_id"),t.deliveryId())&&same(i.get("claimant_deployment_id"),t.claimantDeploymentId())&&same(i.get("claimant_worker_generation"),t.claimantWorkerGeneration())&&same(i.get("active_attempt"),t.activeAttempt())&&same(i.get("attempt_count"),t.activeAttempt())&&"R2B1_ENFORCED".equals(i.get("evidence_provenance")));
        for(var persisted:List.of(d,o))check(persisted.get("claimed_deployment_id")==null&&persisted.get("claimed_worker_generation")==null);
        for(var persisted:List.of(o,i))check(same(persisted.get("wire_payload"),raw)&&same(persisted.get("wire_payload_hash"),sha256(raw)));
        check(d.get("command_payload") instanceof byte[] payload&&same(d.get("command_payload_hash"),sha256(payload)));
        canonical(d,raw,t.messageId(),t.deliveryId(),t.deliveryActiveAttempt());
        var previous=sources.stream().filter(s->number(s,"active_attempt")==t.deliveryActiveAttempt()-1L).map(s->entity(s,new AgentOutboxEventEntity())).toList();
        check(AgentCommandAutomaticReplayProvenance.validImmediateParent(entity(d,new AgentCommandDeliveryEntity()),entity(o,new AgentOutboxEventEntity()),previous));
        check(AgentCommandV2WireVerifier.matches(t,owner,raw));
        check(same(i.get("claim_delivery_version"),t.deliveryVersion())&&same(i.get("claim_inbox_version"),t.inboxVersion())&&number(i,"claim_started_at")>0);
        if(preparing){
            check("CONSUMED".equals(d.get("status"))&&same(d.get("version"),t.deliveryVersion())&&"PROCESSING".equals(i.get("status"))&&same(i.get("version"),t.inboxVersion())&&same(i.get("lease_owner"),t.leaseOwner())&&same(i.get("lease_until"),t.leaseUntil())&&number(i,"claim_started_at")<t.leaseUntil());
            for(String k:List.of("next_retry_at","lease_owner","lease_until","last_error"))check(d.get(k)==null);
            for(String k:List.of("result_status","processed_at","next_retry_at","last_error","runtime_dispatch_id","runtime_binding_id","runtime_binding_generation","dispatch_runtime_instance_id","dispatch_runtime_credential_client_id","dispatch_receiver_registration_revision","dispatch_generation_fact_id","dispatch_generation_snapshot_sha256","execution_origin_snapshot_sha256","dispatch_prepared_at","ack_delivery_version","ack_message_id","ack_status","ack_at","ack_agent_id","ack_runtime_installation_id","ack_runtime_instance_id","ack_runtime_binding_id","ack_runtime_binding_generation","ack_committed_at","execution_started_at","execution_lease_until","execution_lease_revision","execution_holder_binding_id","execution_holder_binding_generation","execution_holder_runtime_instance_id","execution_current_lease_fact_id"))check(i.get(k)==null);
        }
        return new Locked(d,o,i);
    }
    public Locked lock(AgentRuntimeV2InternalOutboxRow r){
        var hint=required(dao.inboxHint(r.number("inbox_id")));var d=required(dao.delivery(r.number("delivery_id")));
        var token=new AgentInboxClaimToken(r.number("inbox_id"),text(hint,"consumer_name"),r.text("tenant_id"),r.text("data_domain_id"),r.text("execution_pool_id"),r.number("admission_epoch"),r.text("producer_deployment_id"),r.text("module_id"),r.number("migration_epoch"),2,r.text("claimant_deployment_id"),r.number("claimant_worker_generation"),r.text("command_caller_client_id"),r.text("source_message_id"),r.text("source_event_id"),r.text("command_id"),text(d,"task_id"),r.text("agent_id"),text(d,"command_type"),r.number("delivery_id"),null,0,Math.toIntExact(number(hint,"active_attempt")),number(hint,"claim_inbox_version"),Math.toIntExact(number(d,"active_attempt")),number(hint,"claim_delivery_version"),r.number("expires_at"));
        var locked=lock(token,r.text("owner_jiacn"),r.binary("command_wire_bytes"),false);var i=locked.inbox();
        check(same(locked.source().get("id"),r.get("source_outbox_id"))&&"PROCESSED".equals(i.get("status"))&&"SENT".equals(i.get("result_status")));
        for(String[] pair:new String[][]{{"runtime_dispatch_id","runtime_dispatch_id"},{"runtime_binding_id","runtime_binding_id"},{"runtime_binding_generation","dispatch_binding_generation"},{"dispatch_runtime_instance_id","runtime_instance_id"},{"dispatch_runtime_credential_client_id","runtime_credential_client_id"},{"dispatch_receiver_registration_revision","receiver_registration_revision"},{"dispatch_generation_fact_id","dispatch_generation_fact_id"},{"dispatch_generation_snapshot_sha256","dispatch_generation_snapshot_sha256"},{"execution_origin_snapshot_sha256","execution_origin_snapshot_sha256"},{"dispatch_prepared_at","prepared_at"}})check(same(i.get(pair[0]),r.get(pair[1])));
        long inboxAdvances=number(i,"version")-number(i,"claim_inbox_version"),deliveryAdvances=number(locked.delivery(),"version")-number(i,"claim_delivery_version");
        check(inboxAdvances>=1&&deliveryAdvances>=1);
        boolean unadvanced=inboxAdvances==1&&deliveryAdvances==1&&"SENT".equals(locked.delivery().get("status"));
        for(String column:List.of("ack_delivery_version","ack_message_id","ack_status","ack_at","ack_agent_id","ack_runtime_installation_id","ack_runtime_instance_id","ack_runtime_binding_id","ack_runtime_binding_generation","ack_committed_at","execution_started_at","execution_lease_until","execution_lease_revision","execution_holder_binding_id","execution_holder_binding_generation","execution_holder_runtime_instance_id","execution_current_lease_fact_id"))unadvanced&=i.get(column)==null;
        if(!unadvanced)continuation.verify(locked,r);
        return locked;
    }
    public static Scope scope(AgentInboxClaimToken t,String owner,long revision){return new Scope(t.tenantId(),t.dataDomainId(),t.executionPoolId(),t.clientId(),owner,t.targetAgentId(),t.producerDeploymentId(),t.claimantDeploymentId(),t.claimantWorkerGeneration(),revision,t.admissionEpoch(),t.moduleId(),t.migrationEpoch());}
    public static Scope scope(AgentRuntimeV2InternalOutboxRow r){return new Scope(r.text("tenant_id"),r.text("data_domain_id"),r.text("execution_pool_id"),r.text("command_caller_client_id"),r.text("owner_jiacn"),r.text("agent_id"),r.text("producer_deployment_id"),r.text("claimant_deployment_id"),r.number("claimant_worker_generation"),r.number("receiver_registration_revision"),r.number("admission_epoch"),r.text("module_id"),r.number("migration_epoch"));}
    private static <T>T entity(Map<String,Object> values,T entity){var wrapper=new BeanWrapperImpl(entity);values.forEach((k,v)->{String name=JdbcUtils.convertUnderscoreNameToPropertyName(k);if(wrapper.isWritableProperty(name))wrapper.setPropertyValue(name,v);});return entity;}
    public static void canonical(Map<String,Object> d,byte[] raw,String message,long delivery,int attempt){
        var draft=AgentCommandCanonicalCodec.decodeBusinessBytes((byte[])d.get("command_payload"));
        var scope=new ProducerScope(TenantId.of(text(d,"tenant_id")),DataDomainId.of(text(d,"data_domain_id")),text(d,"execution_pool_id"),text(d,"producer_deployment_id"),number(d,"admission_epoch"),text(d,"module_id"),number(d,"migration_epoch"));
        check(Arrays.equals(raw,AgentCommandCanonicalCodec.wireBytesV2(draft,message,delivery,attempt,scope)));
        check(same(draft.commandId(),d.get("command_id"))&&same(draft.tenantId(),d.get("tenant_id"))&&same(draft.clientId(),d.get("client_id"))&&same(draft.ownerJiacn(),d.get("owner_jiacn"))&&same(draft.taskId(),d.get("task_id"))&&same(draft.workItemId(),d.get("work_item_id"))&&same(draft.targetAgentId(),d.get("target_agent_id"))&&same(draft.commandType(),d.get("command_type"))&&same(draft.expiresAt(),d.get("expires_at")));
    }
}
