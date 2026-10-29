package cn.jia.agent.position;

import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.PositionAuthority;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.*;
import static cn.jia.agent.position.PositionSkillException.require;
import static cn.jia.agent.position.PositionSkillState.*;

@Service
public class PositionSkillService implements PositionAuthority {
    public record View(String operationId, String resultId, long revision, PositionSkillState state) { }
    private final PositionSkillStore store;
    private final AgentIdentityService identities;
    private final EconomySkillMarketplaceMapper packages;
    private final TransactionTemplate tx;
    private final ObjectMapper codec = JsonMapper.builderWithJackson2Defaults().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public PositionSkillService(PositionSkillStore store, AgentIdentityService identities,
            EconomySkillMarketplaceMapper packages, PlatformTransactionManager manager) {
        this.store=store;this.identities=identities;this.packages=packages;this.tx=new TransactionTemplate(manager);
    }
    public View read(Scope scope) {
        scope(scope); var row=store.read(scope);
        return new View(null,null,row==null?0:row.revision(),row==null?PositionSkillState.empty():decode(row.json()));
    }
    /** Current (locking) read for callers already holding the native/position transaction. */
    public PositionSkillState currentState(Scope scope) {
        scope(scope);
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),503,"POSITION_TRANSACTION_REQUIRED");
        return decode(store.lock(scope,encode(PositionSkillState.empty())).json());
    }
    public View recovery(Scope scope, String key) {
        scope(scope); PositionSkillPolicy.key(key);
        return tx.execute(status->{
            var row=store.lock(scope,encode(PositionSkillState.empty()));
            var operation=store.operation(scope,key); require(operation!=null,404,"POSITION_OPERATION_NOT_FOUND");
            return new View(operation.id(),operation.resultId(),row.revision(),decode(row.json()));
        });
    }
    public View defineSkill(Scope scope,String key,Skill skill) {
        return mutate(scope,key,"DEFINE_SKILL",skill,p->{
            require(skill!=null,400,"POSITION_SKILL_INVALID");
            var version=packages.selectApprovedProductVersion(scope.tenantId(),scope.clientId(),skill.productVersionId());
            require(version!=null && "APPROVED".equals(version.getReviewStatus())
                    && skill.key().equals(version.getSkillKey()),400,"POSITION_SKILL_NOT_APPROVED");
            p.defineSkill(skill);return skill.key();
        });
    }
    public View configure(Scope scope,String key,Position definition,long expectedRevision) {
        require(definition!=null,400,"POSITION_INVALID");
        return mutate(scope,key,"CONFIGURE_POSITION",List.of(definition,expectedRevision),p->{
            authority(scope,definition.resource(),definition.allowPublish());p.configure(definition,expectedRevision);return definition.id();
        });
    }
    public View appoint(Scope scope,String key,String positionId,String agentId) {
        scope(scope);PositionSkillPolicy.key(positionId);PositionSkillPolicy.key(agentId);
        // Match native lock order: binding/identity first, then the scope root. Never root -> identity.
        return tx.execute(status->{
            identities.lockActiveCanonicalAgentIdsInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),List.of(agentId));
            return mutate(scope,key,"APPOINT",List.of(positionId,agentId),p->{
            var position=p.state().positions().get(positionId);require(position!=null,404,"POSITION_NOT_FOUND");
            authority(scope,position.resource(),position.allowPublish());
            var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),agentId);
            require(agentId.equals(identity.getCanonicalAgentId()) && identity.getBindingId()!=null,403,"POSITION_AGENT_FORBIDDEN");
            return p.appoint(positionId,agentId,identity.getBindingId()).id();
            });
        });
    }
    public View resign(Scope scope,String key,String appointment) {
        return mutate(scope,key,"RESIGN",appointment,p->{p.resign(appointment);return appointment;});
    }
    public View uninstall(Scope scope,String key,String installation) {
        return mutate(scope,key,"UNINSTALL_GENERAL",installation,p->{p.uninstallGeneral(installation);return installation;});
    }
    public View retry(Scope scope,String key,String installation) {
        return mutate(scope,key,"RETRY_INSTALLATION",installation,p->{p.retry(installation);return installation;});
    }
    @Override public <T>T withAuthority(Scope scope,Grant grant,Supplier<T> commit) {
        scope(scope); require(grant!=null,403,"POSITION_AUTHORITY_REQUIRED");
        return tx.execute(status->{
            validateAuthority(scope,grant);
            return commit.get(); // The callback and revoke share the same REQUIRED DB transaction/root lock.
        });
    }
    /** Read-only omission check inside the caller's transaction. A denied pending job must not mark
     * the native poll transaction rollback-only; callback mutations still use withAuthority. */
    public boolean authorityAvailable(Scope scope,Grant grant){
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),503,"POSITION_TRANSACTION_REQUIRED");
        try{scope(scope);require(grant!=null,403,"POSITION_AUTHORITY_REQUIRED");validateAuthority(scope,grant);return true;}
        catch(PositionSkillException denied){if(denied.status()==403 || denied.status()==409)return false;throw denied;}
    }
    private void validateAuthority(Scope scope,Grant grant){
            var row=store.lock(scope,encode(PositionSkillState.empty()));
            var state=decode(row.json());
            new PositionSkillPolicy(state,PositionSkillService::id).authorize(grant.appointmentId(),grant.appointmentRevision(),
                    grant.positionRevision(),grant.agentId(),grant.bindingId(),grant.workId(),grant.publish());
            var position=state.positions().get(state.appointments().get(grant.appointmentId()).positionId());
            authority(scope,position.resource(),grant.publish());
            var identity=identities.requireRegistrationIdentityInScope(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),grant.agentId());
            require(grant.agentId().equals(identity.getCanonicalAgentId()) && Objects.equals(identity.getBindingId(),grant.bindingId()),
                    403,"POSITION_BINDING_CHANGED");
    }
    /** Called only inside an authenticated native fence; no user-provided scope or binding is accepted. */
    public <T>T runtimeState(Scope scope,String agentId,Function<PositionSkillPolicy,T> operation) {
        scope(scope);
        return tx.execute(status->{
            var row=store.lock(scope,encode(PositionSkillState.empty()));
            var policy=new PositionSkillPolicy(decode(row.json()),PositionSkillService::id);
            var result=operation.apply(policy);
            String next=encode(policy.state());
            if(!next.equals(encode(decode(row.json()))))
                require(store.save(scope,row.revision(),next)==1,409,"POSITION_REVISION_CONFLICT");
            return result;
        });
    }
    private View mutate(Scope scope,String key,String action,Object request,Function<PositionSkillPolicy,String> mutation) {
        scope(scope);PositionSkillPolicy.key(key);String digest=hash(action+"\0"+encode(request));
        return tx.execute(status->{
            var row=store.lock(scope,encode(PositionSkillState.empty()));
            require(store.authorized(scope,"MANAGE_POSITIONS","positions"),403,"POSITION_MANAGEMENT_FORBIDDEN");
            var old=store.operation(scope,key);
            if(old!=null) {
                require(old.digest().equals(digest),409,"POSITION_IDEMPOTENCY_CONFLICT");
                return new View(old.id(),old.resultId(),row.revision(),decode(row.json()));
            }
            var policy=new PositionSkillPolicy(decode(row.json()),PositionSkillService::id);
            var result=mutation.apply(policy);
            require(store.save(scope,row.revision(),encode(policy.state()))==1,409,"POSITION_REVISION_CONFLICT");
            var operation=new PositionSkillStore.Operation(id(),digest,result);store.operation(scope,key,operation,action);
            return new View(operation.id(),result,row.revision()+1,policy.state());
        });
    }
    private void authority(Scope scope,String resource,boolean publish) {
        require(store.authorized(scope,"ARCHIVE_WRITE",resource),403,"POSITION_RESOURCE_FORBIDDEN");
        if(publish)require(store.authorized(scope,"ARCHIVE_PUBLISH",resource),403,"POSITION_PUBLISH_FORBIDDEN");
    }
    private static void scope(Scope scope) {
        require(scope!=null,403,"POSITION_SCOPE_REQUIRED");
        for(String v:Arrays.asList(scope.tenantId(),scope.clientId(),scope.ownerJiacn()))
            require(v!=null && !v.isBlank() && v.equals(v.strip()) && v.length()<=50,403,"POSITION_SCOPE_INVALID");
        require(!"0".equals(scope.ownerJiacn()),403,"POSITION_SCOPE_INVALID");
    }
    private String encode(Object value) {
        try{return codec.writeValueAsString(value);}catch(Exception failure){throw new PositionSkillException(503,"POSITION_STATE_UNAVAILABLE");}
    }
    private PositionSkillState decode(String json) {
        try{return codec.readValue(json,PositionSkillState.class);}catch(Exception failure){throw new PositionSkillException(503,"POSITION_STATE_UNAVAILABLE");}
    }
    public static String hash(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String id(){return "ps_"+UUID.randomUUID().toString().replace("-","");}
}
