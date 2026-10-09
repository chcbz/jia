package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentRuntimeV2ExecutionStore;
import cn.jia.agent.dao.AgentRuntimeV2EffectStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeV2ExecutionAuthority;
import cn.jia.agent.security.AgentRuntimeV2RecoveryAuthority;
import cn.jia.agent.service.AgentRuntimeV2EffectService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.skill.SkillInstallResultService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static cn.jia.agent.dao.AgentRuntimeV2EffectStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.text;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.bytes;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.same;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static cn.jia.agent.security.AgentRuntimeV2RegistrationAuthorityResolver.*;

/** Composite raw R4 reducer. Trusted route/socket/credential are never body
 * authority. Hashing/private file materialization precedes the final locked
 * resource transaction. All network responses leave after complete cleanup.
 * This bean is NOT an isolation/readiness provider or a legacy execution lane.
 */
public final class AgentRuntimeV2EffectServiceImpl implements AgentRuntimeV2EffectService {
    private final AgentRuntimeV2ExecutionStore executionStore;
    private final AgentRuntimeV2EffectStore store;
    private final AgentRuntimeV2ExecutionAuthority authority;
    private final AgentRuntimeV2RecoveryAuthority recovery;
    private final AgentRuntimeV2ExecutionEvidenceValidator executionEvidence;
    private final AgentRuntimeV2HistoricalSettlementEvidenceValidator effectsEvidence;
    private final PersonalWorkspaceExecutionService workspace;
    private final SkillInstallResultService skills;
    private final TransactionTemplate tx;
    private final Clock clock;
    private record Locked(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2DispatchSource.Locked rows,
            AgentRuntimeV2ExecutionEvidenceValidator.Graph execution,
            AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph effects,
            AgentRuntimeV2ExecutionAuthority.Snapshot live,AgentRuntimeV2RecoveryAuthority.Snapshot recovery,
            AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket) { }
    private record Prepared(PersonalWorkspaceExecutionService.V2PreparedInput input,
            PersonalWorkspaceExecutionService.V2PreparedOutput output,byte[] completeStage) {
        Prepared { completeStage=completeStage==null?null:completeStage.clone(); }
        @Override public byte[] completeStage(){return completeStage==null?null:completeStage.clone();}
        static Prepared none(){return new Prepared(null,null,null);}
    }
    /** Only this verifier can mint the once-consumed settlement capability.
     * Package visibility is an implementation seam, not a public constructor,
     * wire object, historical-read permission or a transferable TX token. */
    static final class SettlementPermit implements TransactionSynchronization {
        private final AgentRuntimeV2ExecutionStore executionStore;
        private final Locked locked;
        private final byte[] terminalAck;
        private final long committedAt;
        private final Thread thread=Thread.currentThread();
        private final Object datasource;
        private final org.springframework.jdbc.datasource.ConnectionHolder holder;
        private final java.sql.Connection connection;
        private boolean consumed,ended,suspended;
        private SettlementPermit(AgentRuntimeV2EffectServiceImpl creator,Locked locked,byte[] terminalAck,long now){
            this.executionStore=creator.executionStore;this.locked=locked;this.terminalAck=terminalAck.clone();committedAt=now;
            datasource=Objects.requireNonNull(executionStore.jdbc().getDataSource());
            check(TransactionSynchronizationManager.getResource(datasource) instanceof org.springframework.jdbc.datasource.ConnectionHolder);
            holder=(org.springframework.jdbc.datasource.ConnectionHolder)TransactionSynchronizationManager.getResource(datasource);connection=holder.getConnection();
            TransactionSynchronizationManager.registerSynchronization(this);require();
        }
        void require(){
            check(Thread.currentThread()==thread&&!ended&&!suspended&&TransactionSynchronizationManager.isActualTransactionActive()&&TransactionSynchronizationManager.isSynchronizationActive()&&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()&&holder.isSynchronizedWithTransaction()&&TransactionSynchronizationManager.getResource(datasource)==holder&&holder.getConnection()==connection&&TransactionSynchronizationManager.getSynchronizations().stream().anyMatch(value->value==this)&&TransactionSynchronizationManager.getResourceMap().values().stream().filter(value->value instanceof org.springframework.jdbc.datasource.ConnectionHolder).count()==1);
            locked.recovery().requireSettlement();
        }
        void consume(){require();check(!consumed);consumed=true;}
        AgentRuntimeV2ExecutionStore store(){require();return executionStore;}
        AgentRuntimeV2InternalOutboxRow origin(){require();return locked.origin();}
        AgentRuntimeV2DispatchSource.Locked rows(){require();return locked.rows();}
        AgentRuntimeV2ExecutionEvidenceValidator.Graph execution(){require();return locked.execution();}
        byte[] ack(){require();return terminalAck.clone();}
        long now(){require();return committedAt;}
        public int getOrder(){return Integer.MIN_VALUE;}
        public void suspend(){suspended=true;}
        public void resume(){suspended=false;}
        public void beforeCompletion(){ended=true;}
        public void afterCompletion(int status){ended=true;}
    }
    public AgentRuntimeV2EffectServiceImpl(AgentRuntimeV2ExecutionStore executionStore,AgentRuntimeV2EffectStore store,
            AgentRuntimeV2ExecutionAuthority authority,AgentRuntimeV2RecoveryAuthority recovery,
            AgentRuntimeV2ExecutionEvidenceValidator executionEvidence,AgentRuntimeV2HistoricalSettlementEvidenceValidator effectsEvidence,
            PersonalWorkspaceExecutionService workspace,SkillInstallResultService skills,PlatformTransactionManager manager,Clock clock) {
        this.executionStore=Objects.requireNonNull(executionStore);this.store=Objects.requireNonNull(store);this.authority=Objects.requireNonNull(authority);this.recovery=Objects.requireNonNull(recovery);this.executionEvidence=Objects.requireNonNull(executionEvidence);this.effectsEvidence=Objects.requireNonNull(effectsEvidence);this.workspace=Objects.requireNonNull(workspace);this.skills=Objects.requireNonNull(skills);this.clock=Objects.requireNonNull(clock);
        check(executionStore.jdbc().getDataSource()==store.jdbc().getDataSource());
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setTimeout(7);
    }
    @Override public Result inspect(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,byte[] raw){return reduce(identity,socket,route,raw,AgentRuntimeV2EffectCodec.Inspect.class);}
    @Override public Result effect(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,byte[] raw){return reduce(identity,socket,route,raw,AgentRuntimeV2EffectCodec.EffectRequest.class);}
    @Override public Result reconcile(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,byte[] raw){return reduce(identity,socket,route,raw,AgentRuntimeV2EffectCodec.Reconcile.class);}
    private Result reduce(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,byte[] raw,Class<?> family){
        outside();try{
            check(route!=null);route.requireSocket(socket);var request=AgentRuntimeV2EffectCodec.decode(raw);check(family.isInstance(request));
            // An authenticated route without executable registration may ONLY
            // recover the exact already-saved original success. Missing or
            // different bytes never fall through to prepare/admit/publication.
            if(socket==null&&request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect)return exactReceiptReplay(identity,route,effect,raw);
            // A known original receipt is historical evidence, not another
            // publication. Resolve it before ANY storage precomputation or
            // QUEUED-only descriptor check. Recovery read ACL is independently
            // current, while the saved resource/result bytes stay immutable.
            var replay=historicalReplay(identity,socket,route,request,raw);if(replay!=null)return replay;
            Prepared prepared=request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect?prepare(identity,socket,effect):Prepared.none();
            return retry(()->tx.execute(status->{
                String dispatch=dispatch(request);var locked=lock(identity,socket,dispatch,request instanceof AgentRuntimeV2EffectCodec.Reconcile,wantsSettlement(request));var prior=store.lock(REQUEST,row("request_id",requestId(request)));
                if(prior!=null){check(same(prior.get("runtime_dispatch_id"),dispatch)&&same(prior.get("request_wire_bytes"),raw)&&same(prior.get("request_sha256"),sha256(raw)));finish(locked,request);return new Result(List.of(rawBytes(prior,"response_wire_bytes")),false);}
                String decision;AgentRuntimeV2EffectCodec.Result result;
                if(request instanceof AgentRuntimeV2EffectCodec.Inspect inspect){var control=control(locked,inspect.grant());decision=control.access().name();result=control;}
                else if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect){var operation=effectLocked(locked,effect,prepared);decision=operation.decision();result=operation.result();}
                else {var statusResult=reconcileLocked(locked,(AgentRuntimeV2EffectCodec.Reconcile)request);decision=statusResult.disposition();result=statusResult;}
                long now=result instanceof AgentRuntimeV2EffectCodec.Reconciliation r&&r.proof() instanceof AgentRuntimeV2EffectCodec.SettlementProof proof&&!"SETTLED_KNOWN".equals(locked.effects().run().get("disposition"))?AgentRuntimeV2WireCodec.decodeReceipt(proof.settlementReceipt()).committedAt():clock.millis();byte[] response=AgentRuntimeV2EffectCodec.encodeReceipt(new AgentRuntimeV2EffectCodec.Receipt(responseType(request),requestId(request),dispatch,UUID.randomUUID().toString(),hex(sha256(raw)),decision,now,result));
                appendRequest(identity,socket,route,locked,request,raw,response,now);
                if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect)recordEffect(locked,effect,raw,response,decision,now);
                else if(request instanceof AgentRuntimeV2EffectCodec.Reconcile reconcile)recordReconciliation(locked,reconcile,response,now,result);
                // finish reloads BOTH graphs in the original transaction.
                // The entry snapshot predates a newly appended terminal fact.
                finish(locked,request);
                var persisted=required(store.hint(REQUEST,row("request_id",requestId(request))));check(same(persisted.get("request_wire_bytes"),raw)&&same(persisted.get("response_wire_bytes"),response));
                return new Result(List.of(rawBytes(persisted,"response_wire_bytes")),false);
            }));
        }catch(RuntimeException failure){outside();return Result.closed();}
    }
    private boolean wantsSettlement(AgentRuntimeV2EffectCodec.Request request){
        if(!(request instanceof AgentRuntimeV2EffectCodec.Reconcile reconcile)||reconcile.subjectKind()!=AgentRuntimeV2EffectCodec.Subject.EXECUTION||reconcile.originalRequest()==null)return false;
        var run=store.hint(RUN,row("runtime_dispatch_id",reconcile.runtimeDispatchId()));return run!=null&&!"SETTLED_KNOWN".equals(run.get("disposition"));
    }
    private Result exactReceiptReplay(AgentRuntimeV2RegistrationContext identity,TrustedRoute route,AgentRuntimeV2EffectCodec.EffectRequest request,byte[] raw){
        return retry(()->tx.execute(status->{
            var locked=lock(identity,null,dispatch(request),true,false);
            var saved=required(store.lock(REQUEST,row("request_id",requestId(request))));
            check("EFFECT".equals(saved.get("request_family"))&&same(saved.get("runtime_dispatch_id"),dispatch(request))&&same(saved.get("effect_id"),request.effectId())&&same(saved.get("request_wire_bytes"),raw)&&same(saved.get("request_sha256"),sha256(raw)));
            byte[] response=rawBytes(saved,"response_wire_bytes");check(same(saved.get("response_sha256"),sha256(response)));
            var effect=required(locked.effects().effect(request.effectId()));var original=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccessReference(locked.effects(),effect);check(original!=null);
            check(original.originalRequestId().equals(requestId(request))&&original.originalRequestSha256().equals(hex(sha256(raw)))&&original.originalResponseSha256().equals(hex(sha256(response)))&&original.executionGrantSha256().equals(request.grant().executionGrantSha256()));
            var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);check(Set.of("DESCRIBED","READ","STAGED","COMMITTED","SETTLED","OBSERVED").contains(receipt.decision())&&observational(receipt));
            route.requireSocket(null);finish(locked,request);return new Result(List.of(response),false);
        }));
    }
    private Result historicalReplay(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,AgentRuntimeV2EffectCodec.Request request,byte[] raw){
        if(request instanceof AgentRuntimeV2EffectCodec.Inspect)return null;
        // An untrusted hint selects only the historical read path. Missing
        // mappings proceed through the ordinary current-admission reducer;
        // present mappings are reloaded and byte-checked under recovery locks.
        // The final reducer still resolves a concurrent unique-id winner.
        var requestHint=store.hint(REQUEST,row("request_id",requestId(request)));
        var effectHint=request instanceof AgentRuntimeV2EffectCodec.EffectRequest q?store.hint(EFFECT,row("effect_id",q.effectId())):null;
        if(requestHint==null&&(effectHint==null||effectHint.get("result_wire_bytes")==null||!Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(effectHint.get("disposition"))))return null;
        return retry(()->tx.execute(status->{
            var locked=lock(identity,socket,dispatch(request),true,false);var saved=store.lock(REQUEST,row("request_id",requestId(request)));
            if(saved==null){
                check(request instanceof AgentRuntimeV2EffectCodec.EffectRequest);var effect=(AgentRuntimeV2EffectCodec.EffectRequest)request;requireGrant(locked,effect.grant());
                var run=required(locked.effects().run());check(effect.runToken().equals(run.get("run_token")));
                String expected=AgentRuntimeV2EffectCodec.effectId(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(locked.origin()),hex(sha256(rawBytes(locked.rows().delivery(),"command_payload"))),dispatch(request),text(locked.execution().facts().getFirst(),"lease_fact_id"),effect.runToken(),effect.effectKind(),effect.phase(),effect.resource());check(expected.equals(effect.effectId()));
                var original=knownEffect(locked,effect);if(original==null){finish(locked,request);return null;}
                long now=clock.millis();byte[] response=AgentRuntimeV2EffectCodec.encodeReceipt(new AgentRuntimeV2EffectCodec.Receipt(responseType(request),requestId(request),dispatch(request),UUID.randomUUID().toString(),hex(sha256(raw)),original.decision(),now,original.result()));
                appendRequest(identity,socket,route,locked,request,raw,response,now);recordEffect(locked,effect,raw,response,original.decision(),now);finish(locked,request);return new Result(List.of(response),false);
            }
            check(same(saved.get("runtime_dispatch_id"),dispatch(request))&&same(saved.get("request_wire_bytes"),raw)&&same(saved.get("request_sha256"),sha256(raw)));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(saved,"response_wire_bytes"));
            if(!(request instanceof AgentRuntimeV2EffectCodec.Reconcile)&&!observational(receipt))return null;
            finish(locked,request);return new Result(List.of(rawBytes(saved,"response_wire_bytes")),false);
        }));
    }
    private Prepared prepare(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,AgentRuntimeV2EffectCodec.EffectRequest request){
        if(!(request.resource() instanceof AgentRuntimeV2EffectCodec.FileRead||request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage||request.resource() instanceof AgentRuntimeV2EffectCodec.FileCommit))return Prepared.none();
        record Plan(PersonalWorkspaceExecutionService.V2Scope scope,byte[] complete) { }
        var plan=retry(()->tx.execute(status->{
            var locked=lock(identity,socket,request.grant().runtimeDispatchId(),false,false);requireEffect(locked,request);
            // Effect idempotency is separate from request idempotency. A new
            // request for an already committed effect must not read storage or
            // traverse the now-terminal resource's QUEUED-only descriptor.
            if(knownEffect(locked,request)!=null){finish(locked,request);return new Plan(null,null);}
            var scope=fileScope(locked.origin(),request.resource());var descriptor=requireFileDescriptor(locked,scope);
            if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage stage)requireStageBound(descriptor,stage);
            byte[] complete=request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage stage?stageContent(locked,request,stage):null;finish(locked,request);return new Plan(scope,complete);
        }));outside();
        if(plan.scope()==null)return Prepared.none();
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileRead read)return new Prepared(workspace.v2PrepareInput(plan.scope(),read.inputId()),null,null);
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileCommit)return new Prepared(null,workspace.v2PrepareCommit(plan.scope(),"output_1"),null);
        return plan.complete()==null?Prepared.none():new Prepared(null,workspace.v2PrepareOutput(plan.scope(),plan.complete()),plan.complete());
    }
    private Locked lock(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,String dispatch,boolean historical,boolean settlement){
        var origin=new AgentRuntimeV2InternalOutboxRow(required(executionStore.hint(AgentRuntimeV2ExecutionStore.INTERNAL,row("runtime_dispatch_id",dispatch))));
        AgentRuntimeV2ExecutionAuthority.Snapshot live=null;AgentRuntimeV2RecoveryAuthority.Snapshot audit=null;
        if(historical)audit=recovery.lock(identity,socket,origin,settlement);else{check(socket!=null);live=authority.lock(identity,socket,origin);live.fresh(clock.millis());}
        var delivery=required(executionStore.lock(AgentRuntimeV2ExecutionStore.DELIVERY,row("id",origin.get("delivery_id"))));var source=required(executionStore.lock(AgentRuntimeV2ExecutionStore.OUTBOX,row("id",origin.get("source_outbox_id"))));var inbox=required(executionStore.lock(AgentRuntimeV2ExecutionStore.INBOX,row("id",origin.get("inbox_id"))));var rows=new AgentRuntimeV2DispatchSource.Locked(delivery,source,inbox);
        var graph=executionEvidence.inspect(rows,origin,true);var current=new AgentRuntimeV2InternalOutboxRow(required(executionStore.lock(AgentRuntimeV2ExecutionStore.INTERNAL,row("internal_outbox_id",origin.get("internal_outbox_id")))));check(origin.sameImmutableAs(current));
        var effects=effectsEvidence.inspect(current,rows,graph,true);return new Locked(current,rows,graph,effects,live,audit,identity,socket);
    }
    private AgentRuntimeV2EffectCodec.Control control(Locked locked,AgentRuntimeV2EffectCodec.GrantReference reference){
        var grant=requireGrant(locked,reference);var current=locked.live();long now=clock.millis();var fact=required(locked.execution().latest());
        check(Set.of("START_GRANTED","HOLDER_TRANSFERRED","RENEWED").contains(fact.get("fact_type"))&&now<number(fact,"lease_until")&&same(fact.get("holder_generation_fact_id"),current.connected().get("binding_generation_fact_id"))&&same(grant.get("current_lease_fact_id"),fact.get("lease_fact_id")));
        long until=Math.min(Math.addExact(now,1000),Math.min(number(fact,"lease_until"),number(current.binding(),"socket_owner_lease_until")));if(current.credentialExpiresAt()!=null)until=Math.min(until,current.credentialExpiresAt());if(current.drain()!=null)until=Math.min(until,number(current.drain().operation(),"deadline_at"));check(now<until);
        var operation=current.drain()==null?null:current.drain().operation();
        return new AgentRuntimeV2EffectCodec.Control(operation==null?AgentRuntimeV2EffectCodec.Access.OPEN:AgentRuntimeV2EffectCodec.Access.SETTLE_ONLY,text(current.binding(),"runtime_binding_id"),text(current.binding(),"runtime_instance_id"),number(current.binding(),"binding_generation"),text(current.connected(),"binding_generation_fact_id"),hex(bytes(current.connected(),"generation_snapshot_sha256")),text(grant,"execution_grant_id"),hex(bytes(grant,"grant_wire_sha256")),text(fact,"lease_fact_id"),number(fact,"lease_revision"),number(fact,"lease_until"),number(current.tenant(),"control_revision"),number(current.module(),"mode_revision"),number(current.deployment(),"registration_revision"),text(current.tenant(),"state"),text(current.module(),"mode_state"),text(current.deployment(),"status"),operation==null?null:text(operation,"drain_operation_id"),operation==null?null:number(operation,"cutoff_at"),operation==null?null:number(operation,"deadline_at"),now,until);
    }
    private Map<String,Object> requireGrant(Locked locked,AgentRuntimeV2EffectCodec.GrantReference reference){var started=required(locked.execution().receipt("STARTED"));var grant=required(locked.execution().grant(reference.executionGrantId()));check(reference.runtimeDispatchId().equals(locked.origin().text("runtime_dispatch_id"))&&reference.startedReceiptId().equals(started.get("receipt_id"))&&reference.startedReceiptSha256().equals(hex(bytes(started,"receipt_wire_sha256")))&&reference.executionGrantSha256().equals(hex(bytes(grant,"grant_wire_sha256"))));return grant;}
    private void requireEffect(Locked locked,AgentRuntimeV2EffectCodec.EffectRequest request){
        var control=control(locked,request.grant());check(control.access()==AgentRuntimeV2EffectCodec.Access.OPEN);
        var run=locked.effects().run();if(run!=null)check(request.runToken().equals(run.get("run_token"))&&"ACTIVE".equals(run.get("disposition"))&&number(run,"retired")==0);
        var started=required(locked.execution().receipt("STARTED"));String expected=AgentRuntimeV2EffectCodec.effectId(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(locked.origin()),hex(sha256(rawBytes(locked.rows().delivery(),"command_payload"))),locked.origin().text("runtime_dispatch_id"),text(locked.execution().facts().getFirst(),"lease_fact_id"),request.runToken(),request.effectKind(),request.phase(),request.resource());check(expected.equals(request.effectId()));
        var draft=draft(locked);var resource=request.resource();
        if(draft.payload() instanceof AgentSkillInstallPayload payload){
            check(Set.of(AgentRuntimeV2EffectCodec.Kind.SKILL_PACKAGE_READ,AgentRuntimeV2EffectCodec.Kind.SKILL_ACTIVATE,AgentRuntimeV2EffectCodec.Kind.SKILL_RESULT).contains(request.effectKind()));var skill=resource instanceof AgentRuntimeV2EffectCodec.SkillRead read?read.identity():(AgentRuntimeV2EffectCodec.SkillIdentity)resource;check(skill.installationId().equals(payload.installationId())&&skill.productVersionId().equals(payload.productVersionId())&&skill.packageDigest().equals(payload.packageDigest())&&skill.skillKey().equals(payload.skillKey())&&skill.skillVersion().equals(payload.skillVersion()));
        }else{
            check(!Set.of(AgentRuntimeV2EffectCodec.Kind.SKILL_PACKAGE_READ,AgentRuntimeV2EffectCodec.Kind.SKILL_ACTIVATE,AgentRuntimeV2EffectCodec.Kind.SKILL_RESULT).contains(request.effectKind()));
            String task,workItem;if(resource instanceof AgentRuntimeV2EffectCodec.FileDescribe value){task=value.taskId();workItem=value.workItemId();}else if(resource instanceof AgentRuntimeV2EffectCodec.WorkspacePrepare value){task=value.taskId();workItem=value.workItemId();check(workspaceKey(locked.origin()).equals(value.workspaceKey()));}else if(resource instanceof AgentRuntimeV2EffectCodec.WorkspaceMutate value){task=value.taskId();workItem=value.workItemId();check(workspaceKey(locked.origin()).equals(value.workspaceKey()));}else if(resource instanceof AgentRuntimeV2EffectCodec.ProcessStart value){task=value.taskId();workItem=value.workItemId();check(workspaceKey(locked.origin()).equals(value.workspaceKey()));}else if(resource instanceof AgentRuntimeV2EffectCodec.ProcessExit value){task=value.taskId();workItem=value.workItemId();check(workspaceKey(locked.origin()).equals(value.workspaceKey()));}else{var scope=fileScope(locked.origin(),resource);task=scope.taskId();workItem=scope.workItemId();}
            check(draft.taskId().equals(task)&&Objects.equals(draft.workItemId(),workItem));
            if(Set.of(AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,AgentRuntimeV2EffectCodec.Kind.FILE_INPUT_READ,AgentRuntimeV2EffectCodec.Kind.FILE_STAGE,AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT).contains(request.effectKind()))check("WORK_ITEM_EXECUTE".equals(draft.commandType())&&workItem!=null);
        }
    }
    private record Operation(String decision,AgentRuntimeV2EffectCodec.Result result) { }
    private Operation effectLocked(Locked locked,AgentRuntimeV2EffectCodec.EffectRequest request,Prepared prepared){
        requireEffect(locked,request);var previous=locked.effects().effect(request.effectId());byte[] resource=AgentRuntimeV2EffectCodec.encodeResource(request.effectKind(),request.phase(),request.resource());if(previous!=null)check(same(previous.get("resource_wire_bytes"),resource)&&same(previous.get("run_token"),request.runToken()));
        var known=knownEffect(locked,request);if(known!=null)return new Operation(known.decision(),known.result());
        var draft=draft(locked);
        if(request.phase()==AgentRuntimeV2EffectCodec.Phase.DESCRIBE){
            AgentRuntimeV2EffectCodec.DescribedResult descriptor=null;
            if(draft.workItemId()!=null&&"WORK_ITEM_EXECUTE".equals(draft.commandType()))descriptor=workspace.v2Describe(new PersonalWorkspaceExecutionService.V2Scope(locked.origin().text("tenant_id"),locked.origin().text("command_caller_client_id"),locked.origin().text("owner_jiacn"),locked.origin().text("agent_id"),draft.taskId(),draft.workItemId(),null,null));
            if(descriptor==null){check(request.effectKind()==AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE);var value=(AgentRuntimeV2EffectCodec.WorkspacePrepare)request.resource();descriptor=new AgentRuntimeV2EffectCodec.DescribedResult("CODEX",null,draft.workItemId(),new AgentRuntimeV2EffectCodec.CodexDescriptor(draft.taskId(),draft.workItemId(),"legacy-codex",workspaceKey(locked.origin()),value.baseTreeSha256()));}
            return new Operation("DESCRIBED",descriptor);
        }
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.SkillRead read)return new Operation("READ",skills.v2Package(locked.origin(),(AgentSkillInstallPayload)draft.payload(),read));
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileRead read){var scope=fileScope(locked.origin(),read);requireFileDescriptor(locked,scope);return new Operation("READ",workspace.v2Input(scope,read,prepared.input()));}
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileStage stage){
            var scope=fileScope(locked.origin(),stage);requireStageBound(requireFileDescriptor(locked,scope),stage);byte[] complete=stageContent(locked,request,stage);check(same(complete,prepared.completeStage()));
            if(complete!=null){check(prepared.output()!=null&&prepared.output().sha256().equals(stage.contentSha256())&&prepared.output().length()==stage.totalLength());workspace.v2Stage(scope,stage.outputId(),prepared.output());}
            return new Operation("STAGED",new AgentRuntimeV2EffectCodec.StagedResult(stage.outputId(),stage.contentSha256(),stage.totalLength()));
        }
        if(request.resource() instanceof AgentRuntimeV2EffectCodec.FileCommit commit&&request.phase()==AgentRuntimeV2EffectCodec.Phase.COMMIT){var scope=fileScope(locked.origin(),commit);var projection=workspace.v2Commit(scope,commit,prepared.output());return new Operation("COMMITTED",new AgentRuntimeV2EffectCodec.CommittedResult(request.effectId(),hex(sha256(resource)),UUID.randomUUID().toString(),clock.millis(),projection));}
        if(request.phase()==AgentRuntimeV2EffectCodec.Phase.ADMIT){
            if(previous!=null&&previous.get("admission_request_id")!=null){var prior=required(locked.effects().request(previous.get("admission_request_id")));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(prior,"response_wire_bytes"));return new Operation(receipt.decision(),receipt.result());}
            // A strict-file association must not become an ordinary tool scope.
            if(draft.workItemId()!=null&&"WORK_ITEM_EXECUTE".equals(draft.commandType())&&!(draft.payload() instanceof AgentSkillInstallPayload)){
                var selected=workspace.v2Describe(new PersonalWorkspaceExecutionService.V2Scope(locked.origin().text("tenant_id"),locked.origin().text("command_caller_client_id"),locked.origin().text("owner_jiacn"),locked.origin().text("agent_id"),draft.taskId(),draft.workItemId(),null,null));
                if(selected!=null||hadFileProcessSelection(locked)){check(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart);requireFileProcessSelection(locked,selected);}
            }
            var control=control(locked,request.grant());return new Operation("ADMITTED",new AgentRuntimeV2EffectCodec.AdmittedResult(request.effectId(),hex(sha256(resource)),control.checkedAt(),control.validUntil(),control.holderGenerationFactId(),control.currentLeaseFactId()));
        }
        if(request.phase()==AgentRuntimeV2EffectCodec.Phase.OBSERVE&&(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessExit||request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessStart)){
            AgentRuntimeV2EffectCodec.DescribedResult selected=null;
            if(draft.workItemId()!=null&&"WORK_ITEM_EXECUTE".equals(draft.commandType()))selected=workspace.v2Describe(new PersonalWorkspaceExecutionService.V2Scope(locked.origin().text("tenant_id"),locked.origin().text("command_caller_client_id"),locked.origin().text("owner_jiacn"),locked.origin().text("agent_id"),draft.taskId(),draft.workItemId(),null,null));
            // Ordinary CODEX has no FILE association and keeps its factual
            // external uncertainty. A previously selected FILE must still
            // match live resource identity; disappearance is NOT a fallback.
            if(selected!=null||hadFileProcessSelection(locked)){
                requireFileProcessSelection(locked,selected);
                var admission=AgentRuntimeV2HistoricalSettlementEvidenceValidator.processAdmission(locked.effects(),request.resource());check(same(admission.get("actor_registration_fact_id"),control(locked,request.grant()).holderGenerationFactId()));AgentRuntimeV2EffectCodec.ProcessDrainObservation observation;
                if(request.resource() instanceof AgentRuntimeV2EffectCodec.ProcessExit){check(request.content()!=null);observation=(AgentRuntimeV2EffectCodec.ProcessDrainObservation)AgentRuntimeV2EffectCodec.decodeEvidence(request.content());AgentRuntimeV2HistoricalSettlementEvidenceValidator.verifyProcessExit(locked.effects(),request,observation,clock.millis());}
                else observation=AgentRuntimeV2HistoricalSettlementEvidenceValidator.matchingProcessDrain(locked.effects(),request);
                return new Operation("OBSERVED",new AgentRuntimeV2EffectCodec.ObservedResult(request.effectId(),hex(sha256(resource)),"PROCESS_DRAIN",observation));
            }
        }
        // OBSERVE/SETTLE cannot manufacture a known commit from a wire status,
        // exitCode, missing row or an admission ticket. Typed evidence enters
        // the authenticated reconciliation family and retains uncertainty.
        var uncertainty=new AgentRuntimeV2EffectCodec.RequestUncertain(request.grant().requestId(),hex(sha256(AgentRuntimeV2EffectCodec.encode(request))),request.runToken(),"RECOVERY",clock.millis());
        return new Operation("UNKNOWN",new AgentRuntimeV2EffectCodec.ObservedResult(request.effectId(),hex(sha256(resource)),"EXTERNAL_UNKNOWN",uncertainty));
    }
    private AgentRuntimeV2EffectCodec.Receipt knownEffect(Locked locked,AgentRuntimeV2EffectCodec.EffectRequest request){
        var effect=locked.effects().effect(request.effectId());if(effect==null||!Set.of("SERVER_COMMITTED","SETTLED_KNOWN","LOCAL_COMMIT_OBSERVED").contains(effect.get("disposition")))return null;
        check(same(effect.get("resource_wire_bytes"),AgentRuntimeV2EffectCodec.encodeResource(request.effectKind(),request.phase(),request.resource()))&&same(effect.get("run_token"),request.runToken())&&same(effect.get("effect_kind"),request.effectKind().name()));
        if(request.phase()==AgentRuntimeV2EffectCodec.Phase.OBSERVE&&Set.of(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT).contains(request.effectKind())){byte[] original=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(locked.effects(),effect);if(original!=null){var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(original);if("OBSERVED".equals(receipt.decision()))return receipt;}}
        if(effect.get("result_wire_bytes")==null)return null;
        byte[] raw=rawBytes(effect,"result_wire_bytes");check(same(sha256(raw),effect.get("result_sha256")));var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);check(observational(receipt));return receipt;
    }
    private void requireFileProcessSelection(Locked locked,AgentRuntimeV2EffectCodec.DescribedResult selected){
        check(selected!=null&&"WORKSPACE_FILE".equals(selected.branch())&&selected.descriptor() instanceof AgentRuntimeV2EffectCodec.FileDescriptor);boolean found=false;
        for(var effect:locked.effects().effects())if(Set.of("WORKSPACE_PREPARE","FILE_DESCRIBE").contains(effect.get("effect_kind"))){byte[] raw=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(locked.effects(),effect);check(raw!=null);var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);check(receipt.result() instanceof AgentRuntimeV2EffectCodec.DescribedResult&&same(AgentRuntimeV2EffectCodec.encodeEffectResult("DESCRIBED",selected),AgentRuntimeV2EffectCodec.encodeEffectResult("DESCRIBED",receipt.result())));found=true;}
        check(found);
    }
    private boolean hadFileProcessSelection(Locked locked){
        for(var effect:locked.effects().effects())if(Set.of("WORKSPACE_PREPARE","FILE_DESCRIBE").contains(effect.get("effect_kind"))){
            byte[] raw=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(locked.effects(),effect);if(raw==null)continue;
            var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(raw);
            if(receipt.result() instanceof AgentRuntimeV2EffectCodec.DescribedResult described&&"WORKSPACE_FILE".equals(described.branch()))return true;
        }
        return false;
    }
    private AgentRuntimeV2EffectCodec.FileDescriptor requireFileDescriptor(Locked locked,PersonalWorkspaceExecutionService.V2Scope scope){var descriptor=workspace.v2Describe(scope);check(descriptor!=null&&scope.executionId().equals(descriptor.executionId())&&scope.workItemId().equals(descriptor.workItemId())&&descriptor.descriptor() instanceof AgentRuntimeV2EffectCodec.FileDescriptor&&scope.runId().equals(((AgentRuntimeV2EffectCodec.FileDescriptor)descriptor.descriptor()).runId()));return (AgentRuntimeV2EffectCodec.FileDescriptor)descriptor.descriptor();}
    private static void requireStageBound(AgentRuntimeV2EffectCodec.FileDescriptor descriptor,AgentRuntimeV2EffectCodec.FileStage stage){
        var outputs=descriptor.outputManifest().stream().filter(output->output.outputId().equals(stage.outputId())).toList();check(outputs.size()==1&&stage.totalLength()>0&&stage.totalLength()<=outputs.getFirst().maxLength()&&stage.totalLength()<=Integer.MAX_VALUE);
    }
    private byte[] stageContent(Locked locked,AgentRuntimeV2EffectCodec.EffectRequest current,AgentRuntimeV2EffectCodec.FileStage stage){
        check(stage.totalLength()>0&&stage.totalLength()<=Integer.MAX_VALUE&&stage.length()>0);var chunks=new TreeMap<Long,byte[]>();
        for(var effect:locked.effects().effects())if("FILE_STAGE".equals(effect.get("effect_kind"))){var request=locked.effects().requests().stream().filter(value->"EFFECT".equals(value.get("request_family"))&&same(value.get("effect_id"),effect.get("effect_id"))).findFirst().orElseThrow(()->denied());var previous=(AgentRuntimeV2EffectCodec.EffectRequest)AgentRuntimeV2EffectCodec.decode(rawBytes(request,"request_wire_bytes"));var resource=(AgentRuntimeV2EffectCodec.FileStage)previous.resource();
            if(resource.executionId().equals(stage.executionId())&&resource.runId().equals(stage.runId())&&resource.outputId().equals(stage.outputId())){check(resource.contentSha256().equals(stage.contentSha256())&&resource.totalLength()==stage.totalLength());var blob=locked.effects().blobs().get(previous.effectId());check(blob.size()==1);byte[] content=rawBytes(blob.getFirst(),"chunk_bytes");check(chunks.put(resource.offset(),content)==null);}
        }
        byte[] prior=chunks.putIfAbsent(stage.offset(),current.content());if(prior!=null)check(same(prior,current.content()));long next=0;for(var entry:chunks.entrySet()){check(entry.getKey()==next);next=Math.addExact(next,entry.getValue().length);check(next<=stage.totalLength());}if(next<stage.totalLength())return null;
        var content=new byte[Math.toIntExact(next)];for(var entry:chunks.entrySet())System.arraycopy(entry.getValue(),0,content,Math.toIntExact(entry.getKey()),entry.getValue().length);check(hex(sha256(content)).equals(stage.contentSha256()));return content;
    }
    private AgentRuntimeV2EffectCodec.Reconciliation reconcileLocked(Locked locked,AgentRuntimeV2EffectCodec.Reconcile request){
        String original=request.originalRequest()==null?null:hex(sha256(request.originalRequest()));
        if(request.subjectKind()==AgentRuntimeV2EffectCodec.Subject.RENEWAL&&request.originalRequest()!=null){
            var renewal=AgentRuntimeV2ExecutionCodec.decodeRenew(request.originalRequest());var predecessor=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalRenewalPredecessor(locked.origin(),locked.execution(),renewal);var mapping=locked.execution().requests().stream().filter(value->same(value.get("execution_request_id"),renewal.renewalRequestId())).findFirst().orElse(null);
            if(mapping!=null){check("RENEWAL".equals(mapping.get("request_kind"))&&same(mapping.get("request_wire_bytes"),request.originalRequest())&&same(mapping.get("request_fingerprint"),sha256(request.originalRequest())));byte[] response;String disposition;
                if("GRANTED".equals(mapping.get("decision"))){response=rawBytes(required(locked.execution().grant(mapping.get("execution_grant_id"))),"grant_wire_bytes");disposition="GRANTED_HISTORICAL";}else{check("DENIED".equals(mapping.get("decision")));response=AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",renewal.renewalRequestId(),renewal.runtimeDispatchId(),renewal.startedReceiptId(),text(mapping,"denial_code"));disposition="DENIED_HISTORICAL";}
                // Mapping identity is the actual immutable execution request;
                // its proof hash binds exact raw/provenance, never an invented
                // late current-holder rejection or missing-row denial.
                var proof=new AgentRuntimeV2EffectCodec.RenewalMapping(renewal.renewalRequestId(),original,renewal.runtimeDispatchId(),renewal.startedReceiptId(),renewal.startedReceiptSha256(),renewal.previousExecutionGrantId(),renewal.previousExecutionGrantSha256(),renewal.renewalRequestId(),mappingHash(mapping),response);return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),disposition,original,proof);
            }
            var barrier=retirementBarrier(locked,renewal,predecessor,original);
            if(barrier!=null)return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"NOT_COMMITTED",original,barrier);
            // An active/untransferred old holder can still commit the original
            // renewal. Missing mapping alone therefore remains UNKNOWN.
        }
        if(request.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EFFECT){var effect=locked.effects().effect(request.subjectId());
            if(effect!=null&&effect.get("result_wire_bytes")!=null&&Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(effect.get("disposition"))){byte[] receipt=rawBytes(effect,"result_wire_bytes");var decoded=AgentRuntimeV2EffectCodec.decodeReceipt(receipt);var proof=new AgentRuntimeV2EffectCodec.EffectReceiptProof(request.subjectId(),hex(bytes(effect,"resource_sha256")),receipt);return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"SETTLED".equals(decoded.decision())?"SETTLED_KNOWN":"COMMITTED_PROVEN",original,proof);}
        }
        if(request.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EFFECT_SUCCESS){
            var effect=locked.effects().effect(request.subjectId());
            var reference=effect==null?null:AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccessReference(locked.effects(),effect);
            if(reference!=null)return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"SUCCESS_REFERENCE",original,reference);
        }
        if(request.subjectKind()==AgentRuntimeV2EffectCodec.Subject.EXECUTION&&locked.effects().run()!=null){var proofs=new ArrayList<AgentRuntimeV2EffectCodec.EffectReceiptProof>();
            if("SETTLED_KNOWN".equals(locked.effects().run().get("disposition"))){
                var saved=locked.effects().requests().stream().filter(value->"RECONCILE".equals(value.get("request_family"))).map(value->AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(value,"response_wire_bytes"))).filter(value->value.result() instanceof AgentRuntimeV2EffectCodec.Reconciliation r&&r.proof() instanceof AgentRuntimeV2EffectCodec.SettlementProof).findFirst().orElseThrow(()->denied());
                var result=(AgentRuntimeV2EffectCodec.Reconciliation)saved.result();var proof=(AgentRuntimeV2EffectCodec.SettlementProof)result.proof();check(request.originalRequest()==null||same(request.originalRequest(),proof.terminalAck()));
                return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"SETTLED_KNOWN",original,proof);
            }
            var scope=effectsEvidence.completeFileScope(locked.origin(),locked.rows(),locked.effects());
            if(request.originalRequest()!=null&&scope!=null){
                check(request.subjectId().equals(request.runtimeDispatchId()));var ack=AgentRuntimeV2ExecutionCodec.decodeAck(request.originalRequest());check(Set.of("SUCCEEDED","FAILED").contains(ack.ackStatus()));locked.recovery().requireSettlement();workspace.v2LockKnownOutcome(scope);effectsEvidence.verifyProspectiveCompleteFile(locked.origin(),locked.rows(),locked.effects());
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE;}@Override public void beforeCommit(boolean readOnly){locked.recovery().requireSettlement();effectsEvidence.verifyProspectiveCompleteFile(locked.origin(),locked.rows(),locked.effects());}});
                var references=new ArrayList<AgentRuntimeV2EffectCodec.EffectSuccessReference>();
                for(var effect:locked.effects().effects()){var reference=AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccessReference(locked.effects(),effect);check(reference!=null);references.add(reference);}
                // Keep every loader cap unchanged. Prospective closure adds
                // one audit row and one closure event per actual effect.
                if(locked.effects().requests().size()>=4096||locked.effects().events().size()+references.size()>4096)return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"UNKNOWN",original,null);
                String root=AgentRuntimeV2HistoricalSettlementEvidenceValidator.completeGraphSha256(locked.effects(),references);
                long now=clock.millis();var permit=new SettlementPermit(this,locked,request.originalRequest(),now);byte[] terminal=AgentRuntimeV2ExecutionServiceImpl.settleVerified(permit);
                var proof=new AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof(request.runtimeDispatchId(),text(locked.effects().run(),"run_token"),request.originalRequest(),terminal,references.size(),root);AgentRuntimeV2EffectCodec.encodeProof(proof);
                store.cas(RUN,locked.effects().run(),row("disposition","SETTLED_KNOWN","retired",1,"updated_at",now));
                return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"SETTLED_KNOWN",original,proof);
            }
            for(var effect:locked.effects().effects())if("SERVER_COMMITTED".equals(effect.get("disposition")))proofs.add(new AgentRuntimeV2EffectCodec.EffectReceiptProof(text(effect,"effect_id"),hex(bytes(effect,"resource_sha256")),rawBytes(effect,"result_wire_bytes")));
            if(!proofs.isEmpty())return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"COMMITTED_PROVEN",original,new AgentRuntimeV2EffectCodec.ExecutionEffectsProof(request.runtimeDispatchId(),text(locked.effects().run(),"run_token"),proofs));
        }
        return new AgentRuntimeV2EffectCodec.Reconciliation(request.subjectKind(),request.subjectId(),"UNKNOWN",original,null);
    }
    private AgentRuntimeV2EffectCodec.RetirementBarrier retirementBarrier(Locked locked,AgentRuntimeV2ExecutionCodec.Renew renewal,Map<String,Object> predecessor,String originalSha){
        var recovery=Objects.requireNonNull(locked.recovery());recovery.fresh();var history=recovery.originalHistory();
        int retiredIndex=-1;for(int i=0;i<history.size();i++)if(same(history.get(i).get("binding_generation_fact_id"),predecessor.get("holder_generation_fact_id"))){check(retiredIndex==-1);retiredIndex=i;}
        check(retiredIndex>=0);var retired=history.get(retiredIndex);check("CONNECTED".equals(retired.get("event_type"))&&same(retired.get("generation_snapshot_sha256"),predecessor.get("holder_generation_snapshot_sha256")));
        if(retiredIndex+1>=history.size())return null;var barrier=history.get(retiredIndex+1);check("DISCONNECTED".equals(barrier.get("event_type"))&&same(barrier.get("previous_fact_id"),retired.get("binding_generation_fact_id")));
        var current=history.stream().filter(value->"CONNECTED".equals(value.get("event_type"))).reduce((left,right)->right).orElseThrow();
        if(number(current,"binding_generation")<=number(retired,"binding_generation"))return null;
        check(number(current,"fact_sequence")>number(barrier,"fact_sequence")&&same(current.get("binding_generation"),recovery.originalBinding().get("binding_generation")));long now=clock.millis();check(now>=number(current,"effective_at"));
        // RecoveryAuthority already owns the SAME original binding roots and
        // full generation chain; lock() then took the SAME delivery/inbox roots
        // as renew. Any in-flight old renewal must have finished before these
        // committed retirement/transfer facts can be observed. finish() does
        // fresh tuple/whole-graph checks again before this transaction commits.
        return new AgentRuntimeV2EffectCodec.RetirementBarrier(renewal.renewalRequestId(),originalSha,renewal.runtimeDispatchId(),text(retired,"binding_generation_fact_id"),hex(bytes(retired,"generation_snapshot_sha256")),text(current,"binding_generation_fact_id"),hex(bytes(current,"generation_snapshot_sha256")),text(barrier,"binding_generation_fact_id"),mappingHash(barrier),now);
    }
    private void recordReconciliation(Locked locked,AgentRuntimeV2EffectCodec.Reconcile request,byte[] response,long now,AgentRuntimeV2EffectCodec.Result result){
        if(!(result instanceof AgentRuntimeV2EffectCodec.Reconciliation r)||!(r.proof() instanceof AgentRuntimeV2EffectCodec.SettlementProof proof)||"SETTLED_KNOWN".equals(locked.effects().run().get("disposition")))return;
        check(request.evidence()==null&&AgentRuntimeV2WireCodec.decodeReceipt(proof.settlementReceipt()).committedAt()==now);
        for(var effect:locked.effects().effects()){
            store.cas(EFFECT,effect,row("disposition","SETTLED_KNOWN","updated_at",now));
            var prior=locked.effects().events().stream().filter(event->same(event.get("effect_id"),effect.get("effect_id"))).reduce((left,right)->right).orElseThrow(()->denied());
            appendEffectEvent(request.runtimeDispatchId(),text(locked.effects().run(),"run_token"),text(effect,"effect_id"),request.requestId(),"SETTLED_KNOWN",response,now,prior);
        }
    }
    private void appendRequest(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,Locked locked,AgentRuntimeV2EffectCodec.Request request,byte[] raw,byte[] response,long now){
        String family=request instanceof AgentRuntimeV2EffectCodec.Inspect?"CONTROL":request instanceof AgentRuntimeV2EffectCodec.EffectRequest?"EFFECT":"RECONCILE";var actor=locked.live()!=null?locked.live().connected():locked.recovery().actorFact();
        var value=row("request_id",requestId(request),"runtime_dispatch_id",dispatch(request),"request_family",family,"effect_id",request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect?effect.effectId():null,"subject_kind",request instanceof AgentRuntimeV2EffectCodec.Reconcile reconcile?reconcile.subjectKind().name():null,"subject_id",request instanceof AgentRuntimeV2EffectCodec.Reconcile reconcile?reconcile.subjectId():null,"request_wire_bytes",raw.clone(),"request_sha256",sha256(raw),"response_wire_bytes",response.clone(),"response_sha256",sha256(response),"actor_credential_reference",identity.credentialReference(),"actor_registration_fact_id",actor==null?null:actor.get("binding_generation_fact_id"),"actor_runtime_binding_id",socket==null?null:socket.runtimeBindingId(),"actor_runtime_instance_id",socket==null?null:socket.runtimeInstanceId(),"actor_binding_generation",socket==null?null:socket.bindingGeneration(),"actor_session_fence",route.sessionFence(),"actor_socket_owner_instance_id",route.socketOwnerInstanceId(),"actor_socket_owner_epoch",route.socketOwnerEpoch(),"created_at",now);store.append(REQUEST,value);
    }
    private void recordEffect(Locked locked,AgentRuntimeV2EffectCodec.EffectRequest request,byte[] raw,byte[] response,String decision,long now){
        if(locked.effects().run()==null){var started=required(locked.execution().receipt("STARTED"));store.append(RUN,row("runtime_dispatch_id",dispatch(request),"run_token",request.runToken(),"execution_origin_snapshot_sha256",locked.origin().get("execution_origin_snapshot_sha256"),"dispatch_wire_sha256",locked.origin().get("dispatch_wire_sha256"),"started_receipt_id",started.get("receipt_id"),"started_receipt_sha256",started.get("receipt_wire_sha256"),"original_start_lease_fact_id",locked.execution().facts().getFirst().get("lease_fact_id"),"disposition","ACTIVE","retired",0,"version",0L,"created_at",now,"updated_at",now));}
        var prior=locked.effects().effect(request.effectId());byte[] resource=AgentRuntimeV2EffectCodec.encodeResource(request.effectKind(),request.phase(),request.resource());var grant=requireGrant(locked,request.grant());var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);
        if(prior==null){var created=row("effect_id",request.effectId(),"runtime_dispatch_id",dispatch(request),"run_token",request.runToken(),"effect_kind",request.effectKind().name(),"resource_wire_bytes",resource,"resource_sha256",sha256(resource),"execution_grant_id",grant.get("execution_grant_id"),"execution_grant_sha256",grant.get("grant_wire_sha256"),"holder_generation_fact_id",grant.get("holder_generation_fact_id"),"current_lease_fact_id",grant.get("current_lease_fact_id"),"admission_request_id",null,"admitted_at",null,"valid_until",null,"disposition","DESCRIBED","commit_receipt_id",null,"result_wire_bytes",null,"result_sha256",null,"version",0L,"created_at",now,"updated_at",now);store.append(EFFECT,created);prior=required(store.lock(EFFECT,row("effect_id",request.effectId())));}
        var changes=row("updated_at",now);String eventKind="OUTCOME_OBSERVED";
        if(receipt.result() instanceof AgentRuntimeV2EffectCodec.AdmittedResult admitted&&prior.get("admission_request_id")==null){changes.putAll(row("admission_request_id",request.grant().requestId(),"admitted_at",admitted.admittedAt(),"valid_until",admitted.validUntil(),"disposition","ADMITTED_IN_FLIGHT"));eventKind="ADMITTED_IN_FLIGHT";}
        else if("COMMITTED".equals(decision)){
            var committed=(AgentRuntimeV2EffectCodec.CommittedResult)receipt.result();
            if(prior.get("result_wire_bytes")!=null){
                // Preserve the first authoritative receipt forever. A new
                // transport request wraps exactly the same business result,
                // not a second SERVER_COMMIT or a fresh commit receipt ID.
                var original=AgentRuntimeV2EffectCodec.decodeReceipt(rawBytes(prior,"result_wire_bytes"));check(Set.of("SERVER_COMMITTED","SETTLED_KNOWN").contains(prior.get("disposition"))&&original.result().equals(committed)&&same(prior.get("commit_receipt_id"),committed.commitReceiptId()));
            }else{changes.putAll(row("disposition","SERVER_COMMITTED","commit_receipt_id",committed.commitReceiptId(),"result_wire_bytes",response.clone(),"result_sha256",sha256(response)));eventKind="SERVER_COMMIT";}
        }
        else if("STAGED".equals(decision)){if(!"SETTLED_KNOWN".equals(prior.get("disposition")))changes.put("disposition","STAGED");var stage=(AgentRuntimeV2EffectCodec.FileStage)request.resource();var existing=store.lock(BLOB,row("effect_id",request.effectId(),"chunk_offset",stage.offset()));if(existing==null)store.append(BLOB,row("effect_id",request.effectId(),"chunk_offset",stage.offset(),"chunk_length",stage.length(),"total_length",stage.totalLength(),"content_sha256",HexFormat.of().parseHex(stage.contentSha256()),"chunk_sha256",sha256(request.content()),"chunk_bytes",request.content(),"created_at",now));else check(same(existing.get("chunk_bytes"),request.content()));}
        else if("OBSERVED".equals(decision)){check(receipt.result() instanceof AgentRuntimeV2EffectCodec.ObservedResult&&"PROCESS_DRAIN".equals(((AgentRuntimeV2EffectCodec.ObservedResult)receipt.result()).category()));if(!"SETTLED_KNOWN".equals(prior.get("disposition")))changes.put("disposition","LOCAL_COMMIT_OBSERVED");}
        else if("UNKNOWN".equals(decision)){check(!Set.of("SERVER_COMMITTED","SETTLED_KNOWN","LOCAL_COMMIT_OBSERVED").contains(prior.get("disposition")));changes.put("disposition","EXTERNAL_UNKNOWN");}
        check(locked.effects().events().size()+2<=4096);
        store.cas(EFFECT,prior,changes);
        var tip=locked.effects().events().stream().filter(event->same(event.get("effect_id"),request.effectId())).reduce((left,right)->right).orElse(null);
        tip=appendEffectEvent(dispatch(request),request.runToken(),request.effectId(),request.grant().requestId(),"REQUEST_SENT",raw,now,tip);
        appendEffectEvent(dispatch(request),request.runToken(),request.effectId(),request.grant().requestId(),eventKind,response,now,tip);
    }
    private Map<String,Object> appendEffectEvent(String dispatch,String runToken,String effectId,String requestId,String kind,byte[] raw,long now,Map<String,Object> prior){
        // Predecessor comes from this transaction's locked complete validated
        // graph, or the immediately appended own predecessor. Never cross-TX.
        var event=row("effect_id",effectId,"event_sequence",prior==null?0L:Math.addExact(number(prior,"event_sequence"),1),"runtime_dispatch_id",dispatch,"run_token",runToken,"request_id",requestId,"previous_sha256",prior==null?null:prior.get("event_sha256"),"event_kind",kind,"evidence_wire_bytes",raw.clone(),"evidence_sha256",sha256(raw),"event_sha256",null,"created_at",now);event.put("event_sha256",AgentRuntimeV2HistoricalSettlementEvidenceValidator.eventHash(event));store.append(EVENT,event);return event;
    }
    private void finish(Locked locked,AgentRuntimeV2EffectCodec.Request request){
        Runnable fresh=()->{
            var origin=locked.origin();AgentRuntimeV2ExecutionAuthority.Snapshot live=null;
            if(locked.recovery()!=null)locked.recovery().fresh();else{
                // The original live Snapshot.fresh checks its retained time
                // bounds, not changes made by an adapter inside this SAME TX.
                // Re-read the actual ACL/holder rows in the original order.
                live=authority.lock(locked.identity(),locked.socket(),origin);live.fresh(clock.millis());
            }
            // These are fresh reads in the SAME original transaction and lock
            // order, including our newly appended request/event/projections.
            // The initial graph is not a beforeCommit evidence cache.
            var delivery=required(executionStore.lock(AgentRuntimeV2ExecutionStore.DELIVERY,row("id",origin.get("delivery_id"))));var source=required(executionStore.lock(AgentRuntimeV2ExecutionStore.OUTBOX,row("id",origin.get("source_outbox_id"))));var inbox=required(executionStore.lock(AgentRuntimeV2ExecutionStore.INBOX,row("id",origin.get("inbox_id"))));var rows=new AgentRuntimeV2DispatchSource.Locked(delivery,source,inbox);
            var execution=executionEvidence.inspect(rows,origin,true);var current=new AgentRuntimeV2InternalOutboxRow(required(executionStore.lock(AgentRuntimeV2ExecutionStore.INTERNAL,row("internal_outbox_id",origin.get("internal_outbox_id")))));check(origin.sameImmutableAs(current));var effects=effectsEvidence.inspect(current,rows,execution,true);
            if(live!=null){var reference=request instanceof AgentRuntimeV2EffectCodec.Inspect inspect?inspect.grant():((AgentRuntimeV2EffectCodec.EffectRequest)request).grant();control(new Locked(current,rows,execution,effects,live,null,locked.identity(),locked.socket()),reference);}
        };fresh.run();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE;}@Override public void beforeCommit(boolean readOnly){fresh.run();}});
    }
    @Override public boolean current(AgentRuntimeV2RegistrationContext identity,AgentRuntimeV2SocketTuple socket,TrustedRoute route,byte[] response){
        outside();try{check(route!=null);route.requireSocket(socket);var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(response);return Boolean.TRUE.equals(retry(()->tx.execute(status->{
            var saved=required(store.hint(REQUEST,row("request_id",receipt.requestId())));check(same(saved.get("response_wire_bytes"),response)&&same(saved.get("response_sha256"),sha256(response)));var request=AgentRuntimeV2EffectCodec.decode(rawBytes(saved,"request_wire_bytes"));var locked=lock(identity,socket,receipt.runtimeDispatchId(),request instanceof AgentRuntimeV2EffectCodec.Reconcile||observational(receipt),false);
            if(receipt.result() instanceof AgentRuntimeV2EffectCodec.Control control)check(clock.millis()<control.validUntil());if(receipt.result() instanceof AgentRuntimeV2EffectCodec.AdmittedResult admitted)check(clock.millis()<admitted.validUntil());
            if(request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect&&!observational(receipt))requireEffect(locked,effect);finish(locked,request);return true;
        })));}catch(RuntimeException failure){outside();return false;}
    }
    private static PersonalWorkspaceExecutionService.V2Scope fileScope(AgentRuntimeV2InternalOutboxRow origin,AgentRuntimeV2EffectCodec.Resource resource){String execution,task,item,run;if(resource instanceof AgentRuntimeV2EffectCodec.FileRead value){execution=value.executionId();task=value.taskId();item=value.workItemId();run=value.runId();}else if(resource instanceof AgentRuntimeV2EffectCodec.FileStage value){execution=value.executionId();task=value.taskId();item=value.workItemId();run=value.runId();}else if(resource instanceof AgentRuntimeV2EffectCodec.FileCommit value){execution=value.executionId();task=value.taskId();item=value.workItemId();run=value.runId();}else throw denied();return new PersonalWorkspaceExecutionService.V2Scope(origin.text("tenant_id"),origin.text("command_caller_client_id"),origin.text("owner_jiacn"),origin.text("agent_id"),task,item,execution,run);}
    private static AgentCommandDraft draft(Locked locked){return AgentCommandCanonicalCodec.decodeBusinessBytes(rawBytes(locked.rows().delivery(),"command_payload"));}
    public static String workspaceKey(AgentRuntimeV2InternalOutboxRow origin){return "r4_"+hex(sha256(AgentRuntimeV2HistoricalSettlementEvidenceValidator.stableKey(origin).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static String mappingHash(Map<String,Object> mapping){return AgentRuntimeV2HistoricalSettlementEvidenceValidator.durableRowHash(mapping);}
    private static String requestId(AgentRuntimeV2EffectCodec.Request request){return request instanceof AgentRuntimeV2EffectCodec.Inspect inspect?inspect.grant().requestId():request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect?effect.grant().requestId():((AgentRuntimeV2EffectCodec.Reconcile)request).requestId();}
    private static String dispatch(AgentRuntimeV2EffectCodec.Request request){return request instanceof AgentRuntimeV2EffectCodec.Inspect inspect?inspect.grant().runtimeDispatchId():request instanceof AgentRuntimeV2EffectCodec.EffectRequest effect?effect.grant().runtimeDispatchId():((AgentRuntimeV2EffectCodec.Reconcile)request).runtimeDispatchId();}
    private static String responseType(AgentRuntimeV2EffectCodec.Request request){return request instanceof AgentRuntimeV2EffectCodec.Inspect?"command.execution.control.receipt":request instanceof AgentRuntimeV2EffectCodec.EffectRequest?"command.execution.effect.receipt":"command.execution.reconcile.receipt";}
    private static boolean observational(AgentRuntimeV2EffectCodec.Receipt receipt){return receipt.result() instanceof AgentRuntimeV2EffectCodec.CommittedResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.SettledResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.ObservedResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.DescribedResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.ReadResult||receipt.result() instanceof AgentRuntimeV2EffectCodec.StagedResult;}
    /** Opaque persisted bytes are not the RegistrationStore's 32-byte digest.
     * Fixed columns retain their existing command/codec limits; every caller
     * additionally verifies the appropriate canonical codec or chunk hash. */
    private static byte[] rawBytes(Map<String,Object> row,String column){
        int cap=switch(column){
            case "command_payload" -> AgentCommandCanonicalCodec.MAX_CANONICAL_BYTES;
            case "request_wire_bytes","response_wire_bytes","evidence_wire_bytes" -> AgentRuntimeV2EffectCodec.FRAME_CAP;
            case "result_wire_bytes" -> 98304;
            case "resource_wire_bytes" -> AgentRuntimeV2EffectCodec.RESOURCE_CAP;
            case "chunk_bytes" -> AgentRuntimeV2EffectCodec.CHUNK_CAP;
            case "grant_wire_bytes" -> 32768;
            default -> throw denied();
        };
        Object value=row.get(column);check(value instanceof byte[]);byte[] raw=(byte[])value;
        check(raw.length<=cap&&(raw.length>0||"chunk_bytes".equals(column)));return raw.clone();
    }
    private static void outside(){AgentRuntimeV2ExecutionServiceImpl.outside();}
    private static <T>T retry(Supplier<T> action){for(int attempt=0;;attempt++){outside();try{T result=action.get();outside();return result;}catch(DuplicateKeyException|PessimisticLockingFailureException race){outside();if(attempt>=1)throw race;}}}
}
