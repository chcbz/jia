package cn.jia.agent.skill;
import cn.jia.agent.dao.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.entity.*;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.agent.mapper.skill.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import java.util.*;
import static cn.jia.agent.skill.SkillMarketplaceException.require;
import static cn.jia.agent.skill.SkillMarketplaceService.*;

/** Replaces task-member ACL only for an exact durable skill order/installation. All sends after commit. */
@Service
public final class SkillInstallDispatchService {
    private final SkillInstallationMapper app;
    private final SkillCatalogMapper market;
    private final SkillMarketplaceService skills;
    private final SkillAgentVersions versions;
    private final AgentRuntimeAuthenticationService authentication;
    private final AgentCommandTransportDao commands;
    private final ObjectProvider<AgentManagedSessionLookup> sessions;
    private final TransactionTemplate tx;
    public SkillInstallDispatchService(SkillInstallationMapper app,SkillCatalogMapper market,
            SkillMarketplaceService skills,SkillAgentVersions versions,AgentRuntimeAuthenticationService authentication,
            AgentCommandTransportDao commands,ObjectProvider<AgentManagedSessionLookup> sessions,PlatformTransactionManager manager) {
        this.app=app;this.market=market;this.skills=skills;this.versions=versions;this.authentication=authentication;
        this.commands=commands;this.sessions=sessions;tx=new TransactionTemplate(manager);
    }
    private SkillManagementService management;
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(SkillManagementService management) {this.management=management;}
    /** Local socket ownership only; original dispatch still checks the full business/fencing contract. */
    public boolean canDispatch(String tenant,String client,String agent) {
        var transport=sessions.getIfAvailable();
        if(transport==null)return false;
        try {
            var proof=authentication.currentRegisteredProof(tenant,client,agent);
            return transport.isReady(tenant,client,agent,SkillMarketplaceService.sessionFence(proof));
        } catch(IllegalArgumentException disconnected) { return false; }
    }
    public AgentRawCommandDispatchResult dispatch(String tenant,String client,String owner,String change,String agent,String commandId,byte[] wire) {
        require(management!=null,503,"SKILL_STATE_UNAVAILABLE");
        return management.dispatch(tenant,client,owner,change,agent,commandId,wire);
    }
}
