package cn.jia.agent.platform;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthentication;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.LongSupplier;
import static cn.jia.agent.platform.PlatformSkillException.require;
import static cn.jia.agent.platform.PlatformInstallationStore.*;

/** Platform provisioning joins the shared command/outbox transaction; never purchases or settles. */
@Service
@ConditionalOnProperty(prefix="agent.platform-skills",name="enabled",havingValue="true")
public final class PlatformSkillInstallationService implements AgentControlledCommandDispatcher {
    public static final String TYPE="PLATFORM_SKILL_INSTALL";
    private static final Set<String> ACTIVE_DELIVERY=Set.of("CONSUMED","SENT","RECEIVED","STARTED");
    // A transport ACK never proves installation. A successful ACK may arrive before the native receipt.
    private static final Set<String> RESULT_DELIVERY=Set.of("CONSUMED","SENT","RECEIVED","STARTED","SUCCEEDED");
    private static final Set<String> FAILED_DELIVERY=Set.of("DEAD","EXPIRED","CANCELLED","FAILED");
    private static final Set<String> FAILURE_CODES=Set.of("PLATFORM_SKILL_PACKAGE_INVALID","PLATFORM_SKILL_DIGEST_MISMATCH",
            "PLATFORM_SKILL_INSTALL_CONFLICT","PLATFORM_SKILL_INSTALL_IO_FAILED","PLATFORM_SKILL_INSTALL_DISABLED");
    public record Actor(String actorId,Scope scope) { }
    public record Request(String agentId,String bindingVersion,String skillKey,String skillVersion) { }
    public record Result(Integer schemaVersion,String installationId,String commandId,Integer attempt,
            String executionEpoch,String challengeId,String packageSha256,String outcome,String errorCode) { }
    public record View(String installationId,String agentId,String bindingVersion,String skillKey,String skillVersion,
            String packageSha256,String origin,String state,String errorCode,String revision) { }
    private final PlatformInstallationStore store;
    private final PlatformSkillCatalog catalog;
    private final AgentIdentityService identities;
    private final AgentRuntimeDao runtimes;
    private final AgentRuntimeAuthenticationService authentication;
    private final AgentCommandTransportDao deliveries;
    private final ObjectProvider<AgentCommandTransportWriter> writer;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final ObjectProvider<AgentPlatformSkillProvisioningPolicy> policies;
    private final TransactionTemplate tx;
    private final LongSupplier now;
    private ScanCursor reconciliationCursor;
    @Autowired
    public PlatformSkillInstallationService(PlatformInstallationStore store,PlatformSkillCatalog catalog,
            AgentIdentityService identities,AgentRuntimeDao runtimes,AgentRuntimeAuthenticationService authentication,
            AgentCommandTransportDao deliveries,ObjectProvider<AgentCommandTransportWriter> writer,
            ObjectProvider<AgentManagedSessionLookup> sessions,ObjectProvider<AgentPlatformSkillProvisioningPolicy> policies,PlatformTransactionManager manager) {
        this(store,catalog,identities,runtimes,authentication,deliveries,writer,sessions,policies,manager,System::currentTimeMillis);
    }
    PlatformSkillInstallationService(PlatformInstallationStore store,PlatformSkillCatalog catalog,
            AgentIdentityService identities,AgentRuntimeDao runtimes,AgentRuntimeAuthenticationService authentication,
            AgentCommandTransportDao deliveries,ObjectProvider<AgentCommandTransportWriter> writer,
            ObjectProvider<AgentManagedSessionLookup> sessions,ObjectProvider<AgentPlatformSkillProvisioningPolicy> policies,
            PlatformTransactionManager manager,LongSupplier now) {
        this.store=store;this.catalog=catalog;this.identities=identities;this.runtimes=runtimes;
        this.authentication=authentication;this.deliveries=deliveries;this.writer=writer;this.sessions=sessions;this.policies=policies;
        this.now=Objects.requireNonNull(now);tx=new TransactionTemplate(manager);
    }
    @Override public String commandType() { return TYPE; }
    /** Read-only approved metadata. This deliberately does not consult installation policy, stores or transports. */
    public List<PlatformSkillCatalogView> catalog(Actor a) {
        actor(a);
        return List.of(new PlatformSkillCatalogView(PlatformSkillCatalog.SKILL_KEY,PlatformSkillCatalog.SKILL_VERSION,
                catalog.sha256(),PlatformSkillCatalog.PROTOCOL));
    }
    public View request(Actor a,String key,Request request) {
        actor(a); idempotencyKey(key);
        require(request!=null,400,"PLATFORM_SKILL_REQUEST_INVALID");
        id(request.agentId()); long binding=positive(request.bindingVersion());
        require(PlatformSkillCatalog.SKILL_KEY.equals(request.skillKey()) && PlatformSkillCatalog.SKILL_VERSION.equals(request.skillVersion()),
                400,"PLATFORM_SKILL_NOT_APPROVED");
        String requestSha=hash(request.agentId()+"\0"+request.bindingVersion()+"\0"+request.skillKey()+"\0"+request.skillVersion());
        return tx.execute(status->{
            var target=current(a.scope(),request.agentId(),binding);
            authorize(a.scope());
            store.lockScope(a.scope());
            var old=store.byKey(a.scope(),a.actorId(),key);
            if(old!=null) {
                require(requestSha.equals(old.requestSha()),409,"PLATFORM_SKILL_IDEMPOTENCY_CONFLICT");
                currentInstallation(old,target);
                return view(old);
            }
            var transport=writer.getIfAvailable(); require(transport!=null,503,"PLATFORM_SKILL_TRANSPORT_UNAVAILABLE");
            String installation=newId("psi_"), challenge=newId("psc_");
            String command=AgentCommandCanonicalCodec.controlledCommandId(a.scope().tenant(),a.scope().client(),a.scope().owner(),
                    installation,request.agentId(),TYPE);
            long now=this.now.getAsLong();
            var payload=new AgentPlatformSkillInstallPayload(1,installation,request.bindingVersion(),request.skillKey(),request.skillVersion(),
                    catalog.sha256(),challenge,"/internal/agent/platform-skills/installations/"+installation+"/package");
            var draft=new AgentCommandDraft(1,command,installation,challenge,a.scope().tenant(),a.scope().client(),a.scope().owner(),
                    installation,null,request.agentId(),TYPE,now,Math.addExact(now,AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS),payload);
            transport.write(draft);
            var delivery=deliveries.lockDelivery(a.scope().tenant(),a.scope().client(),a.scope().owner(),command);
            require(delivery!=null && "PENDING".equals(delivery.getStatus()),503,"PLATFORM_SKILL_TRANSPORT_UNAVAILABLE");
            var record=new Installation(installation,a.scope(),a.actorId(),key,requestSha,request.agentId(),binding,
                    target.runtimeInstanceId(),target.registrationHash(),request.skillKey(),request.skillVersion(),catalog.sha256(),
                    challenge,command,"REQUESTED",null,null,1,now);
            store.insert(record); return view(record);
        });
    }
    public View status(Actor a,String installation) {
        actor(a);id(installation);
        return tx.execute(status->{
            authorize(a.scope());
            store.lockScope(a.scope());
            var i=found(a.scope(),installation,true);
            require(a.actorId().equals(i.actorId()),404,"PLATFORM_SKILL_INSTALLATION_NOT_FOUND");
            if("REQUESTED".equals(i.state())) {
                String failure=requestedFailure(i,delivery(i),now.getAsLong());
                if(failure!=null) { failRequested(i,failure); i=found(a.scope(),installation,false); }
            }
            return view(i);
        });
    }
    /** Package authentication is native runtime scope, never the old marketplace API-key route. */
    public byte[] packageBytes(AgentRuntimeAuthentication.Scope nativeScope,String installation) {
        var scope=scope(nativeScope);id(installation);
        return tx.execute(status->{
            var hint=found(scope,installation,false);
            var target=current(scope,hint.agentId(),hint.bindingId());
            authorize(scope);
            store.lockScope(scope);
            var i=found(scope,installation,true); nativeMatch(nativeScope,i,target);
            require("REQUESTED".equals(i.state()),409,"PLATFORM_SKILL_INSTALLATION_TERMINAL");
            var delivery=delivery(i);
            require(delivery.getStatus()!=null && ACTIVE_DELIVERY.contains(delivery.getStatus()) && delivery.getExpiresAt()!=null
                    && delivery.getExpiresAt()>now.getAsLong(),403,"PLATFORM_SKILL_DELIVERY_FENCED");
            return catalog.packageBytes(i.skillKey(),i.skillVersion(),i.packageSha());
        });
    }
    public View result(AgentRuntimeAuthentication.Scope nativeScope,String installation,Result result) {
        var scope=scope(nativeScope); id(installation); validateResultShape(result);
        return tx.execute(status->{
            var hint=found(scope,installation,false);
            var target=current(scope,hint.agentId(),hint.bindingId());
            authorize(scope);
            store.lockScope(scope);
            var i=found(scope,installation,true); nativeMatch(nativeScope,i,target);
            var delivery=delivery(i);
            require(i.id().equals(result.installationId()) && i.commandId().equals(result.commandId())
                    && "1".equals(result.executionEpoch())
                    && i.challengeId().equals(result.challengeId()) && i.packageSha().equals(result.packageSha256()),
                    409,"PLATFORM_SKILL_RESULT_CONFLICT");
            String resultSha=hash(result.installationId()+"\0"+result.commandId()+"\0"+result.attempt()+"\0"+result.executionEpoch()
                    +"\0"+result.challengeId()+"\0"+result.packageSha256()+"\0"+result.outcome()+"\0"+result.errorCode());
            if(i.resultSha()!=null) {
                require(resultSha.equals(i.resultSha()),409,"PLATFORM_SKILL_RESULT_CONFLICT");return view(i);
            }
            require(result.attempt().equals(delivery.getActiveAttempt()),409,"PLATFORM_SKILL_RESULT_CONFLICT");
            require("REQUESTED".equals(i.state()) && delivery.getStatus()!=null && RESULT_DELIVERY.contains(delivery.getStatus())
                    && delivery.getExpiresAt()!=null && delivery.getExpiresAt()>now.getAsLong(),403,"PLATFORM_SKILL_DELIVERY_FENCED");
            require(store.finish(scope,i.id(),i.revision(),result.outcome(),resultSha,result.errorCode())==1,409,"PLATFORM_SKILL_RESULT_CONFLICT");
            return view(found(scope,installation,false));
        });
    }
    @Override public AgentRawCommandDispatchResult dispatch(String tenant,String client,String owner,String resource,
            String agent,String commandId,byte[] wire) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),503,"PLATFORM_SKILL_DISPATCH_TRANSACTION_OPEN");
        var scope=new Scope(tenant,client,owner); actor(new Actor(owner,scope));id(resource);id(agent);id(commandId);
        final AgentRuntimeAuthenticationService.ControlledTarget target;
        try {
            target=tx.execute(status->{
                var hint=found(scope,resource,false);
                require(agent.equals(hint.agentId()) && commandId.equals(hint.commandId()),403,"PLATFORM_SKILL_DELIVERY_FENCED");
                AgentRuntimeAuthenticationService.ControlledTarget proof=null;
                PlatformSkillException deniedTarget=null;
                try {
                    proof=current(scope,agent,hint.bindingId());currentInstallation(hint,proof);
                } catch(PlatformSkillException denied) {
                    if(denied.status()!=403 && denied.status()!=409) throw denied;
                    deniedTarget=denied;
                }
                PlatformSkillException deniedPolicy=null;
                try { authorize(scope); } catch(PlatformSkillException denied) {
                    if(denied.status()!=403) throw denied; deniedPolicy=denied;
                }
                store.lockScope(scope);
                var i=found(scope,resource,true);
                if(deniedPolicy!=null) { failRequested(i,deniedPolicy.code()); return null; }
                if(deniedTarget!=null) { failRequested(i,deniedTarget.code()); return null; }
                require("REQUESTED".equals(i.state()),403,"PLATFORM_SKILL_DELIVERY_FENCED");
                var d=delivery(i);
                if(d.getStatus()!=null && FAILED_DELIVERY.contains(d.getStatus())) {
                    failRequested(i,"PLATFORM_SKILL_DELIVERY_TERMINAL");return null;
                }
                require(d.getActiveMessageId()!=null && d.getActiveAttempt()!=null && d.getStatus()!=null && ACTIVE_DELIVERY.contains(d.getStatus()),403,"PLATFORM_SKILL_DELIVERY_FENCED");
                var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload());
                require(Arrays.equals(wire,AgentCommandCanonicalCodec.wireBytes(draft,d.getActiveMessageId(),d.getActiveAttempt())),403,"PLATFORM_SKILL_DELIVERY_FENCED");
                return proof;
            });
        } catch(PlatformSkillException denied) {
            if(denied.status()==403 || denied.status()==404 || denied.status()==409) return AgentRawCommandDispatchResult.rejected();
            throw denied;
        }
        if(target==null) return AgentRawCommandDispatchResult.rejected();
        var managed=sessions.getIfAvailable();
        require(managed!=null,503,"PLATFORM_SKILL_TRANSPORT_UNAVAILABLE");
        var sent=managed.dispatch(tenant,client,agent,target.apiKeyId(),target.registrationHash(),wire);
        if(sent!=null && sent.status()==AgentRawCommandDispatchResult.rejected().status()) {
            tx.executeWithoutResult(status->{
                store.lockScope(scope);var i=found(scope,resource,true);
                require(agent.equals(i.agentId()) && commandId.equals(i.commandId()),403,"PLATFORM_SKILL_DELIVERY_FENCED");
                failRequested(i,"PLATFORM_SKILL_SESSION_REJECTED");
            });
        }
        return sent;
    }
    /** Observe committed negative transport facts even when nobody opens the management panel.
     * This does not dispatch/reissue commands or infer installation from successful ACKs. */
    @Scheduled(fixedDelayString="${agent.platform-skills.reconciliation-delay-ms:5000}")
    public synchronized void reconcileTerminalDeliveries() {
        // The cursor is process-local scan progress only. Durable rows remain the source of truth.
        final int limit=100;
        long observedAt=now.getAsLong();
        var hints=store.terminalDeliveryCandidates(reconciliationCursor,observedAt,limit);
        if(hints.isEmpty()) { reconciliationCursor=null; return; }
        for(var hint:hints) {
            // Advance before processing so one corrupt/unavailable row cannot starve later rows.
            reconciliationCursor=hint.scanCursor();
            try {
                tx.executeWithoutResult(status -> {
                    store.lockScope(hint.scope());
                    var i=found(hint.scope(),hint.id(),true);
                    if(!"REQUESTED".equals(i.state())) return;
                    String failure=requestedFailure(i,delivery(i),observedAt);
                    if(failure!=null) failRequested(i,failure);
                });
            } catch(RuntimeException unavailable) {
                // The durable candidate is revisited after the bounded keyset scan wraps. Never log payload/credentials.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Platform installation reconciliation deferred");
            }
        }
        if(hints.size()<limit) reconciliationCursor=null;
    }
    private AgentRuntimeAuthenticationService.ControlledTarget current(Scope scope,String agent,long binding) {
        try {
            identities.lockActiveCanonicalAgentIdsInScope(scope.tenant(),scope.client(),scope.owner(),List.of(agent));
            var identity=identities.requireActiveIdentityForBinding(scope.tenant(),scope.client(),scope.owner(),binding,agent);
            require(identity!=null && agent.equals(identity.getCanonicalAgentId()),403,"PLATFORM_SKILL_TARGET_FORBIDDEN");
        } catch(cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException denied) {
            throw new PlatformSkillException(403,"PLATFORM_SKILL_TARGET_FORBIDDEN");
        }
        var runtime=runtimes.findByAgentIdForUpdate(agent);
        require(runtime!=null && scope.tenant().equals(runtime.getTenantId()) && scope.client().equals(runtime.getClientId())
                && scope.owner().equals(runtime.getOwnerJiacn()) && Long.valueOf(binding).equals(runtime.getBindingId()),403,"PLATFORM_SKILL_TARGET_FORBIDDEN");
        try { return authentication.requireControlledTarget(scope.tenant(),scope.client(),scope.owner(),agent,binding,TYPE+"/v1"); }
        catch(IllegalArgumentException denied) { throw new PlatformSkillException(409,"PLATFORM_SKILL_PROTOCOL_UNAVAILABLE"); }
    }
    private void authorize(Scope scope) {
        var policy=policies.getIfAvailable(); require(policy!=null,503,"PLATFORM_SKILL_POLICY_UNAVAILABLE");
        try { policy.requireAllowed(scope.tenant(),scope.client(),scope.owner(),PlatformSkillCatalog.SKILL_KEY,true); }
        catch(IllegalArgumentException denied) { throw new PlatformSkillException(403,"PLATFORM_SKILL_PROVISIONING_FORBIDDEN"); }
    }
    private static String requestedFailure(Installation i,AgentCommandDeliveryEntity d,long observedAt) {
        if(!"REQUESTED".equals(i.state()) || i.resultSha()!=null || d.getStatus()==null) return null;
        if(FAILED_DELIVERY.contains(d.getStatus())) return "PLATFORM_SKILL_DELIVERY_TERMINAL";
        if("SUCCEEDED".equals(d.getStatus()) && d.getExpiresAt()!=null && d.getExpiresAt()<=observedAt)
            return "PLATFORM_SKILL_RECEIPT_TIMEOUT";
        return null;
    }

    private void failRequested(Installation i,String code) {
        if("REQUESTED".equals(i.state()))
            require(store.finish(i.scope(),i.id(),i.revision(),"FAILED",null,code)==1,409,"PLATFORM_SKILL_RESULT_CONFLICT");
    }

    private void currentInstallation(Installation i,AgentRuntimeAuthenticationService.ControlledTarget target) {
        require(i.runtimeInstanceId().equals(target.runtimeInstanceId()) && Arrays.equals(i.registrationHash(),target.registrationHash()),
                409,"PLATFORM_SKILL_RUNTIME_FENCED");
        catalog.packageBytes(i.skillKey(),i.skillVersion(),i.packageSha());
    }
    private void nativeMatch(AgentRuntimeAuthentication.Scope n,Installation i,AgentRuntimeAuthenticationService.ControlledTarget target) {
        require(n.agentId().equals(i.agentId()) && n.runtimeInstanceId().equals(i.runtimeInstanceId()),403,"PLATFORM_SKILL_NATIVE_FORBIDDEN");
        currentInstallation(i,target);
    }
    private AgentCommandDeliveryEntity delivery(Installation i) {
        var s=i.scope();var d=deliveries.lockDelivery(s.tenant(),s.client(),s.owner(),i.commandId());
        require(d!=null && TYPE.equals(d.getCommandType()) && i.agentId().equals(d.getTargetAgentId())
                && i.id().equals(d.getTaskId()) && s.owner().equals(d.getOwnerJiacn()) && s.tenant().equals(d.getTenantId())
                && s.client().equals(d.getClientId()) && d.getCommandPayload()!=null
                && Arrays.equals(d.getCommandPayloadHash(),AgentCommandCanonicalCodec.sha256(d.getCommandPayload())),403,"PLATFORM_SKILL_DELIVERY_FENCED");
        var draft=AgentCommandCanonicalCodec.decodeBusinessBytes(d.getCommandPayload());
        require(draft.payload() instanceof AgentPlatformSkillInstallPayload,403,"PLATFORM_SKILL_DELIVERY_FENCED");
        var p=(AgentPlatformSkillInstallPayload)draft.payload();
        require(i.commandId().equals(draft.commandId()) && i.id().equals(p.installationId()) && Long.toString(i.bindingId()).equals(p.bindingVersion())
                && i.challengeId().equals(p.challengeId()) && i.packageSha().equals(p.packageSha256())
                && i.skillKey().equals(p.skillKey()) && i.skillVersion().equals(p.skillVersion())
                && s.tenant().equals(draft.tenantId()) && s.client().equals(draft.clientId()) && s.owner().equals(draft.ownerJiacn())
                && i.agentId().equals(draft.targetAgentId()),403,"PLATFORM_SKILL_DELIVERY_FENCED");
        return d;
    }
    private Installation found(Scope scope,String id,boolean lock) {
        var i=store.find(scope,id,lock);require(i!=null,404,"PLATFORM_SKILL_INSTALLATION_NOT_FOUND");return i;
    }
    private static View view(Installation i) { return new View(i.id(),i.agentId(),Long.toString(i.bindingId()),i.skillKey(),i.skillVersion(),
            i.packageSha(),"PLATFORM_PROVISIONED",i.state(),i.errorCode(),Long.toString(i.revision())); }
    static void validateResultShape(Result r) {
        require(r!=null && Integer.valueOf(1).equals(r.schemaVersion()) && r.attempt()!=null && r.attempt()>0,400,"PLATFORM_SKILL_RESULT_INVALID");
        id(r.installationId());id(r.commandId());id(r.challengeId());positive(r.executionEpoch());
        require(r.packageSha256()!=null && r.packageSha256().matches("[0-9a-f]{64}"),400,"PLATFORM_SKILL_RESULT_INVALID");
        require("SUCCEEDED".equals(r.outcome()) && r.errorCode()==null
                || "FAILED".equals(r.outcome()) && r.errorCode()!=null && FAILURE_CODES.contains(r.errorCode()),400,"PLATFORM_SKILL_RESULT_INVALID");
    }
    private static Scope scope(AgentRuntimeAuthentication.Scope n) {
        require(n!=null,401,"PLATFORM_SKILL_NATIVE_UNAUTHENTICATED");
        var s=new Scope(n.tenantId(),n.clientId(),n.ownerJiacn());actor(new Actor(n.ownerJiacn(),s));return s;
    }
    private static void actor(Actor a) {
        require(a!=null && a.scope()!=null && "0".equals(a.scope().tenant()) && exact(a.actorId(),100)
                && exact(a.scope().client(),50) && exact(a.scope().owner(),50) && !"0".equals(a.scope().owner()),403,"PLATFORM_SKILL_FORBIDDEN");
    }
    private static boolean exact(String s,int length) { return s!=null && !s.isEmpty() && s.length()<=length && s.equals(s.strip()) && s.chars().noneMatch(c->Character.isISOControl(c)||Character.isSurrogate((char)c)); }
    private static void id(String s) { require(s!=null && s.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}"),400,"PLATFORM_SKILL_REQUEST_INVALID"); }
    private static void idempotencyKey(String s) { require(exact(s,128),400,"PLATFORM_SKILL_IDEMPOTENCY_REQUIRED"); }
    private static long positive(String s) {
        require(s!=null && s.matches("[1-9][0-9]{0,18}"),400,"PLATFORM_SKILL_REQUEST_INVALID");
        try{return Long.parseLong(s);}catch(NumberFormatException e){throw new PlatformSkillException(400,"PLATFORM_SKILL_REQUEST_INVALID");}
    }
    private static String newId(String prefix) { return prefix+UUID.randomUUID().toString().replace("-",""); }
    private static String hash(String s) { return HexFormat.of().formatHex(AgentCommandCanonicalCodec.sha256(s.getBytes(StandardCharsets.UTF_8))); }
}
