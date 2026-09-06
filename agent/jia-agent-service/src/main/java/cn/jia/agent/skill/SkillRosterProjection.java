package cn.jia.agent.skill;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.hosting.*;
import cn.jia.agent.service.AgentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public final class SkillRosterProjection {
    private final HostingRentOwnerResolver owners;
    private final ObjectProvider<AgentService> agents;
    private final SkillAgentVersions versions;
    private final SkillMarketplaceService skills;
    public SkillRosterProjection(HostingRentOwnerResolver owners,ObjectProvider<AgentService> agents,SkillAgentVersions versions,SkillMarketplaceService skills) {
        this.owners=owners;this.agents=agents;this.versions=versions;this.skills=skills;
    }
    public void project(Authentication authentication,List<AgentRuntimeDTO> rows) {
        HostingRentHttp.Actor actor=null; String owner=null;
        try { actor=HostingRentHttp.actor(authentication); owner=owners.requireOwner(actor); } catch(RuntimeException denied) { /* no fallback */ }
        for(var row:rows) {
            row.setVersion(null);row.setBoundToMe(false);row.setCanOperate(false);
            if(actor==null || owner==null || row.getAgentId()==null || Boolean.TRUE.equals(row.getSystemAgent())) continue;
            try {
                var trusted=agents.getObject().requireApiKeyOwnedAgent(actor.clientId(),owner,row.getAgentId());
                if(!row.getAgentId().equals(trusted.getAgentId())) continue;
                row.setBoundToMe(true); row.setCanOperate(true);
                if(versions.enabled()) row.setVersion(Long.toString(versions.requireOwned(actor,row.getAgentId(),null,false)));
            } catch(RuntimeException denied) { row.setVersion(null); }
        }
    }
}
