package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2CompositeAuthority;
import cn.jia.agent.service.*;
import org.springframework.dao.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Atomic durable handoff, with no socket dependency and no nested legacy admission guard. */
public final class AgentRuntimeV2DispatchServiceImpl implements AgentRuntimeV2DispatchService {
    private final AgentRuntimeV2InternalOutboxDao dao;
    private final AgentRuntimeV2CompositeAuthority authority;
    private final AgentRuntimeV2CompositeWorkerProof process;
    private final AgentRuntimeV2DispatchSource sources;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final java.util.function.Consumer<RuntimeException> unavailableObservation;
    public AgentRuntimeV2DispatchServiceImpl(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2CompositeWorkerProof process,PlatformTransactionManager manager,Clock clock){this(dao,authority,process,manager,clock,failure->{});}
    public AgentRuntimeV2DispatchServiceImpl(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2CompositeWorkerProof process,PlatformTransactionManager manager,Clock clock,java.util.function.Consumer<RuntimeException> unavailableObservation){this(dao,authority,process,manager,clock,new AgentRuntimeV2DispatchSource(dao),unavailableObservation);}
    public AgentRuntimeV2DispatchServiceImpl(AgentRuntimeV2InternalOutboxDao dao,AgentRuntimeV2CompositeAuthority authority,AgentRuntimeV2CompositeWorkerProof process,PlatformTransactionManager manager,Clock clock,AgentRuntimeV2DispatchSource sources,java.util.function.Consumer<RuntimeException> unavailableObservation){this.dao=dao;this.authority=authority;this.process=process;this.sources=Objects.requireNonNull(sources);this.clock=clock;this.unavailableObservation=Objects.requireNonNull(unavailableObservation);tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(7);}
    @Override public AgentCommandV2ExecutionPort.Result prepare(AgentCommandV2ExecutionPort.Command command){
        outside();Objects.requireNonNull(command);var t=command.claimToken();
        if(!AgentCommandV2WireVerifier.matches(t,command.ownerJiacn(),command.wirePayload()))return AgentCommandV2ExecutionPort.Result.rejected();
        try {retry(()->tx.execute(status->{
            var current=authority.lock(AgentRuntimeV2DispatchSource.scope(t,command.ownerJiacn(),process.scope().registrationRevision()),null,process);
            long now=clock.millis();current.fresh(now);check(now<t.leaseUntil()&&now<t.expiresAt());
            var locked=sources.lock(t,command.ownerJiacn(),command.wirePayload(),true);
            check(dao.forInbox(t.inboxId())==null);
            String dispatch=UUID.randomUUID().toString();var b=current.binding();var f=current.connected();var s=current.scope();
            var origin=new ExecutionOriginSnapshot(dispatch,t.inboxId(),t.deliveryId(),s.tenant(),s.domain(),s.pool(),s.caller(),text(b,"client_id"),s.owner(),s.agent(),s.producer(),s.claimant(),s.worker(),s.revision(),s.admission(),s.module(),s.migration(),text(b,"runtime_binding_id"),number(b,"binding_generation"),text(b,"runtime_instance_id"),text(f,"binding_generation_fact_id"),bytes(f,"generation_snapshot_sha256"));
            byte[] raw=command.wirePayload(),outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(dispatch,raw));
            var values=row("internal_outbox_id",UUID.randomUUID().toString(),"contract_version",2,"runtime_dispatch_id",dispatch,"inbox_id",t.inboxId(),"delivery_id",t.deliveryId(),"source_outbox_id",locked.source().get("id"),"source_event_id",t.eventId(),"source_message_id",t.messageId(),"command_id",t.commandId(),"tenant_id",s.tenant(),"data_domain_id",s.domain(),"execution_pool_id",s.pool(),"command_caller_client_id",s.caller(),"runtime_credential_client_id",b.get("client_id"),"owner_jiacn",s.owner(),"agent_id",s.agent(),"producer_deployment_id",s.producer(),"claimant_deployment_id",s.claimant(),"claimant_worker_generation",s.worker(),"receiver_registration_revision",s.revision(),"admission_epoch",s.admission(),"module_id",s.module(),"migration_epoch",s.migration(),"runtime_binding_id",b.get("runtime_binding_id"),"dispatch_binding_generation",b.get("binding_generation"),"runtime_instance_id",b.get("runtime_instance_id"),"dispatch_generation_fact_id",f.get("binding_generation_fact_id"),"dispatch_generation_snapshot_sha256",f.get("generation_snapshot_sha256"),"execution_origin_snapshot_sha256",origin.sha256(),"session_fence",b.get("current_session_fence"),"socket_owner_instance_id",b.get("socket_owner_instance_id"),"socket_owner_epoch",b.get("socket_owner_epoch"),"prepared_socket_owner_lease_until",b.get("socket_owner_lease_until"),"command_wire_bytes",raw,"command_wire_sha256",sha256(raw),"dispatch_wire_bytes",outer,"dispatch_wire_sha256",sha256(outer),"prepared_at",now,"expires_at",t.expiresAt(),"status","PENDING","attempt_count",0,"next_attempt_at",now,"claim_id",null,"claim_until",null,"last_attempt_at",null,"sent_at",null,"finished_at",null,"last_error_code",null,"version",0L,"update_time",now);
            var internal=new AgentRuntimeV2InternalOutboxRow(values);dao.insert(internal);dao.handoff(t,internal,now);
            Runnable fresh=()->{long finalNow=clock.millis();current.fresh(finalNow);check(finalNow<t.leaseUntil()&&finalNow<t.expiresAt());};
            fresh.run();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){fresh.run();}});return dispatch;
        }));outside();return AgentCommandV2ExecutionPort.Result.dispatched();}
        catch(SecurityException|IllegalArgumentException denied){outside();return AgentCommandV2ExecutionPort.Result.rejected();}
        catch(RuntimeException unavailable){outside();unavailableObservation.accept(unavailable);return AgentCommandV2ExecutionPort.Result.retryable(5000);}
    }
    @Override public void verifyPrior(long inboxId){outside();tx.executeWithoutResult(status->{var hint=required(dao.inboxHint(inboxId));if(hint.get("runtime_dispatch_id")==null)return;var internal=requiredInternal(dao.forInboxHint(inboxId));authority.maintenance(internal);sources.lock(internal);check(internal.sameImmutableAs(requiredInternal(dao.forInbox(inboxId))));});outside();}
    private static AgentRuntimeV2InternalOutboxRow requiredInternal(AgentRuntimeV2InternalOutboxRow row){if(row==null)throw denied();return row;}
    private <T>T retry(Supplier<T> work){for(int attempt=0;;attempt++){outside();try{return work.get();}catch(DuplicateKeyException|PessimisticLockingFailureException conflict){outside();if(attempt>=1)throw conflict;}}}
    public static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isSynchronizationActive()||!TransactionSynchronizationManager.getResourceMap().isEmpty())throw new IllegalStateException("dispatch boundary requires complete transaction cleanup");}
}
