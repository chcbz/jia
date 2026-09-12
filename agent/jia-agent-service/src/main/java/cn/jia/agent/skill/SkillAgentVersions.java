package cn.jia.agent.skill;

import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.service.AgentService;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;

/** Runtime/binding locks precede this persistent CAS root; heartbeat timestamps are not versions. */
@Service
public final class SkillAgentVersions {
    private final EconomySkillApplicationMapper mapper;
    private final AgentRuntimeDao runtimes;
    private final ObjectProvider<AgentService> agents;
    private final HostingRentOwnerResolver owners;
    private final TransactionTemplate tx;
    private final boolean enabled;
    public SkillAgentVersions(EconomySkillApplicationMapper mapper, AgentRuntimeDao runtimes,
            ObjectProvider<AgentService> agents, HostingRentOwnerResolver owners,
            PlatformTransactionManager manager, @Value("${economy.skill.marketplace.enabled:false}") boolean enabled) {
        this.mapper=mapper; this.runtimes=runtimes; this.agents=agents; this.owners=owners;
        this.tx=new TransactionTemplate(manager); this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    public long requireOwned(HostingRentHttp.Actor actor, String agentId, Long expected, boolean ready) {
        require(enabled, 503, "SKILL_MARKETPLACE_DISABLED");
        return tx.execute(s -> {
            String owner=owners.requireOwner(actor);
            var dto=agents.getObject().requireApiKeyOwnedAgentForUpdate(actor.clientId(), owner, agentId);
            require(agentId.equals(dto.getAgentId()) && !Boolean.TRUE.equals(dto.getSystemAgent()),403,"AGENT_FORBIDDEN");
            if(ready) {
                require(dto.getStatus()!=null && Set.of("online","busy").contains(dto.getStatus()),409,"AGENT_NOT_READY");
                agents.getObject().requireHostingNewWork(actor.tenantId(),actor.clientId(),agentId);
            }
            long version=observeLocked(runtimes.findByAgentIdForUpdate(agentId));
            require(expected==null || expected==version,409,"AGENT_VERSION_CONFLICT");
            return version;
        });
    }
    /** Called for every existing runtime persistence path, inside its lifecycle transaction. */
    public void observe(AgentRuntimeEntity row) {
        if(!enabled || row==null || row.getOwnerJiacn()==null || row.getBindingId()==null) return;
        tx.executeWithoutResult(s -> observeLocked(runtimes.findByAgentIdForUpdate(row.getAgentId())));
    }
    private long observeLocked(AgentRuntimeEntity row) {
        require(row!=null && row.getBindingId()!=null,403,"AGENT_FORBIDDEN");
        byte[] hash=sourceHash(row);
        mapper.ensureVersion(row.getOwnerJiacn(),row.getClientId(),row.getAgentId(),hash);
        var state=mapper.lockVersion(row.getOwnerJiacn(),row.getClientId(),row.getAgentId());
        require(state!=null && state.getVersion()!=null && state.getVersion()>0,503,"SKILL_STATE_UNAVAILABLE");
        if(!Arrays.equals(hash,state.getSourceHash())) {
            require(mapper.advanceVersion(row.getOwnerJiacn(),row.getClientId(),row.getAgentId(),hash,state.getVersion())==1,409,"AGENT_VERSION_CONFLICT");
            return Math.addExact(state.getVersion(),1);
        }
        return state.getVersion();
    }
    public void consume(HostingRentHttp.Actor actor,String agentId,long expected) {
        var state=mapper.lockVersion(actor.tenantId(),actor.clientId(),agentId);
        require(state!=null && state.getVersion()==expected,409,"AGENT_VERSION_CONFLICT");
        require(mapper.advanceVersion(actor.tenantId(),actor.clientId(),agentId,state.getSourceHash(),expected)==1,409,"AGENT_VERSION_CONFLICT");
    }
    static byte[] sourceHash(AgentRuntimeEntity r) {
        Map<String,String> fields=new TreeMap<>();
        fields.put("agentId",r.getAgentId()); fields.put("clientId",r.getClientId());
        fields.put("owner",r.getOwnerJiacn()); fields.put("binding",String.valueOf(r.getBindingId()));
        fields.put("status",r.getStatus()); fields.put("registration",r.getTokenHash());
        fields.put("abilities",r.getAbilities()); fields.put("endpoint",r.getEndpoint());
        return HostingRentHttp.hash("skill-agent-lifecycle-v1",fields);
    }
}
