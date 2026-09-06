package cn.jia.agent.skill;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.mapper.*;
import cn.jia.economy.entity.skill.SkillManagedCredentialEntity;
import cn.jia.oauth.entity.OauthApiKeyEntity;
import cn.jia.oauth.service.ApiKeyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.*;

/** A small binding onto existing ApiKeyService, not a new identity platform. Secret is stored only
 * by that existing credential service, returned once and never copied into our receipt tables. */
@Service
public final class SkillManagedCredentials {
    private final EconomySkillCredentialMapper mapper;
    private final EconomySkillApplicationMapper roots;
    private final EconomyHostingRentMapper rent;
    private final SkillAgentVersions versions;
    private final AgentRuntimeDao runtimes;
    private final ObjectProvider<ApiKeyService> keys;
    private final EconomyPreviewGate preview;
    private final boolean enabled,adoptHosting;
    private final TransactionTemplate tx;
    public SkillManagedCredentials(EconomySkillCredentialMapper mapper,EconomySkillApplicationMapper roots,
            EconomyHostingRentMapper rent,SkillAgentVersions versions,AgentRuntimeDao runtimes,
            ObjectProvider<ApiKeyService> keys,EconomyPreviewGate preview,PlatformTransactionManager manager,
            @Value("${economy.skill.marketplace.enabled:false}") boolean enabled,
            @Value("${economy.skill.marketplace.adopt-hosting-credentials:true}") boolean adoptHosting) {
        this.mapper=mapper;this.roots=roots;this.rent=rent;this.versions=versions;this.runtimes=runtimes;
        this.keys=keys;this.preview=preview;this.enabled=enabled;this.adoptHosting=adoptHosting;this.tx=new TransactionTemplate(manager);
    }
    public Map<String,Object> rotate(HostingRentHttp.Actor actor,String agent,String idem,long expected) {
        require(enabled && preview.allows(actor.tenantId(),actor.clientId()) && keys.getIfAvailable()!=null,503,"SKILL_MARKETPLACE_DISABLED");
        HostingRentHttp.key(idem); HostingRentHttp.exact(agent,100); require(expected>0,400,"BAD_REQUEST");
        byte[] hash=HostingRentHttp.hash("skill-credential-rotation-v1",Map.of("agent",agent,"expectedVersion",Long.toString(expected)));
        return tx.execute(s->{
            roots.ensureActor(actor.tenantId(),actor.clientId(),actor.actorId());
            require(roots.lockActor(actor.tenantId(),actor.clientId(),actor.actorId())!=null,503,"SKILL_STATE_UNAVAILABLE");
            var op=mapper.operation(actor.tenantId(),actor.clientId(),actor.actorId(),bytes(idem));
            if(op!=null) { same(op.getRequestHash(),hash);throw new SkillMarketplaceException(409,"SKILL_CREDENTIAL_SECRET_NOT_REPLAYABLE"); }
            versions.requireOwned(actor,agent,expected,false);
            var runtime=runtimes.findByAgentIdForUpdate(agent);
            var old=mapper.active(actor.tenantId(),actor.clientId(),agent);
            // Hosted profiles already use their original fresh key. Do not silently orphan that runner.
            require(mapper.hostingCandidates(actor.tenantId(),actor.clientId(),agent).isEmpty(),409,"SKILL_HOSTING_CREDENTIAL_ROTATION_UNSUPPORTED");
            require(roots.pendingInstalls(actor.tenantId(),actor.clientId(),agent)==0,409,"SKILL_INSTALLATION_PENDING");
            if(old!=null) {
                var prior=keys.getObject().get(old.getApiKeyId());
                require(prior!=null && old.getApiKeyId().equals(prior.getId()) && actor.tenantId().equals(prior.getTenantId())
                        && actor.tenantId().equals(prior.getJiacn()) && actor.clientId().equals(prior.getClientId()),403,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
                require((prior.getKeyName()==null || !prior.getKeyName().startsWith("hosting:")),409,"SKILL_HOSTING_CREDENTIAL_ROTATION_UNSUPPORTED");
                prior.setStatus(0); require(keys.getObject().update(prior)!=null,503,"SKILL_STATE_UNAVAILABLE");
                one(mapper.retire(actor.tenantId(),actor.clientId(),old.getCredentialId(),old.getVersion(),System.currentTimeMillis()));
            }
            String credentialId=id("sc_"); var key=new OauthApiKeyEntity();
            key.setTenantId(actor.tenantId());key.setClientId(actor.clientId());key.setJiacn(actor.tenantId());key.setStatus(1);
            key.setKeyName("skill-agent:"+credentialId);key.setDescription("Canonical managed Agent "+agent);
            key.setApiKey("cdx_"+UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
            var created=keys.getObject().create(key);
            require(created!=null && created.getId()!=null && key.getApiKey().equals(created.getApiKey())
                    && key.getKeyName().equals(created.getKeyName()) && actor.tenantId().equals(created.getTenantId())
                    && actor.tenantId().equals(created.getJiacn()) && actor.clientId().equals(created.getClientId()),503,"SKILL_STATE_UNAVAILABLE");
            one(mapper.insert(credentialId,created.getId(),agent,runtime.getBindingId(),actor.tenantId(),actor.clientId(),System.currentTimeMillis()));
            versions.consume(actor,agent,expected);
            one(mapper.operationInsert(actor.tenantId(),actor.clientId(),actor.actorId(),bytes(idem),hash,credentialId,System.currentTimeMillis()));
            return Map.of("credentialId",credentialId,"targetAgentId",agent,"version",Long.toString(Math.addExact(expected,1)),
                    "status","CREATED","apiKey",created.getApiKey(),"recovery","ROTATE_WITH_NEW_KEY");
        });
    }
    /** Caller holds the canonical binding/runtime lock and joins its transaction. */
    String requireCurrent(HostingRentHttp.Actor actor,String agent) {
        var runtime=runtimes.findByAgentIdForUpdate(agent);
        require(runtime!=null && runtime.getBindingId()!=null,403,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        var binding=mapper.active(actor.tenantId(),actor.clientId(),agent);
        if(binding==null && adoptHosting) binding=adopt(actor,agent,runtime.getBindingId());
        require(binding!=null && runtime.getBindingId().equals(binding.getBindingId()),409,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        var key=keys.getObject().get(binding.getApiKeyId());
        require(validKey(actor,key,binding.getApiKeyId()),409,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        return binding.getApiKeyId();
    }
    private SkillManagedCredentialEntity adopt(HostingRentHttp.Actor actor,String agent,long bindingId) {
        var candidates=mapper.hostingCandidates(actor.tenantId(),actor.clientId(),agent);
        require(candidates.size()==1,409,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        var intent=candidates.getFirst(); var key=keys.getObject().get(intent.getManagedApiKeyId());
        require("USER".equals(intent.getPrincipalType()) && actor.actorId().equals(intent.getPrincipalId())
                && validKey(actor,key,intent.getManagedApiKeyId()) && ("hosting:"+intent.getIntentId()).equals(key.getKeyName())
                && mapper.hostingReferenceCount(intent.getManagedApiKeyId())==1,409,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        var lease=rent.selectLeaseForUpdate(actor.tenantId(),actor.clientId(),intent.getLeaseId());
        require(lease!=null && agent.equals(lease.getAgentId()) && Long.toString(bindingId).equals(lease.getBindingId())
                && "ACTIVE".equals(lease.getStatus()),409,"SKILL_AGENT_CREDENTIAL_UNPROVEN");
        // The global UNIQUE(api_key_id) prevents importing this key for any other Agent/scope.
        one(mapper.insert(id("sc_"),key.getId(),agent,bindingId,actor.tenantId(),actor.clientId(),System.currentTimeMillis()));
        return mapper.active(actor.tenantId(),actor.clientId(),agent);
    }
    private static boolean validKey(HostingRentHttp.Actor a,OauthApiKeyEntity k,String id) {
        return k!=null && id.equals(k.getId()) && a.tenantId().equals(k.getTenantId()) && a.tenantId().equals(k.getJiacn())
                && a.clientId().equals(k.getClientId()) && Integer.valueOf(1).equals(k.getStatus())
                && (k.getExpireTime()==null || k.getExpireTime()>System.currentTimeMillis());
    }
}
