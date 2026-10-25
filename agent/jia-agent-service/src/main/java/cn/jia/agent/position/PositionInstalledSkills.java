package cn.jia.agent.position;

import cn.jia.agent.service.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.skill.SkillPackages;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.jia.agent.service.InstalledSkillDescriptorLookup.*;

/** Legitimate non-marketplace entitlement source. Neither retained files nor a transport ACK suffice. */
@Service
public class PositionInstalledSkills {
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final EconomySkillMarketplaceMapper market;
    private final SkillPackages packages;
    public PositionInstalledSkills(PositionSkillService positions,AgentIdentityService identities,EconomySkillMarketplaceMapper market,SkillPackages packages){
        this.positions=positions;this.identities=identities;this.market=market;this.packages=packages;
    }
    private cn.jia.agent.skill.SkillManagementService management;
    private cn.jia.economy.mapper.EconomySkillManagementMapper managed;
    @org.springframework.beans.factory.annotation.Autowired
    public void setManagement(cn.jia.agent.skill.SkillManagementService service,cn.jia.economy.mapper.EconomySkillManagementMapper mapper){management=service;managed=mapper;}
    public List<Descriptor> read(SkillCapabilityLookup.Scope s){
        var state=positions.read(new Scope(s.tenantId(),s.clientId(),s.ownerJiacn())).state();
        var ids=new HashSet<String>();
        for(var i:state.installations().values())if(i.agentId().equals(s.agentId())&&i.usable()&&i.managedInstallationId()!=null)ids.add(i.managedInstallationId());
        return management.descriptors(s).stream().filter(d->ids.contains(d.installationId())).toList();
    }
    /** Joins the caller's task transaction: live source + shared enabled/admission/READY, not a stale role receipt. */
    public List<cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill> entitlements(SkillCapabilityLookup.Scope s){
        var identity=identities.requireRegistrationIdentityInScope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId());
        var state=positions.read(new Scope(s.tenantId(),s.clientId(),s.ownerJiacn())).state();
        var result=new ArrayList<cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill>();
        for(var i:state.installations().values()){
            if(!i.agentId().equals(s.agentId())||!Objects.equals(identity.getBindingId(),i.bindingId())||!i.usable()||i.managedInstallationId()==null)continue;
            var authorization=managed.findAuthorization(s.tenantId(),s.clientId(),i.managedInstallationId());
            if(authorization!=null&&management.qualifies(s,authorization)){
                var target=cn.jia.agent.skill.SkillManagementCodec.read(authorization.getPackageJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),"PackageRef");
                result.add(new cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill(i.skillKey(),(String)target.get("skillVersion")));
            }
        }
        return result;
    }
}
