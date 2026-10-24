package cn.jia.agent.position;

import cn.jia.agent.service.*;
import cn.jia.agent.service.PositionAuthority.Scope;
import cn.jia.agent.skill.SkillPackages;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import static cn.jia.agent.service.InstalledSkillDescriptorLookup.*;

/** Legitimate non-marketplace entitlement source. Neither retained files nor a transport ACK suffice. */
@Service
@ConditionalOnProperty(prefix="agent.position-skills",name="enabled",havingValue="true")
public class PositionInstalledSkills {
    private final PositionSkillService positions;
    private final AgentIdentityService identities;
    private final EconomySkillMarketplaceMapper market;
    private final SkillPackages packages;
    public PositionInstalledSkills(PositionSkillService positions,AgentIdentityService identities,EconomySkillMarketplaceMapper market,SkillPackages packages){
        this.positions=positions;this.identities=identities;this.market=market;this.packages=packages;
    }
    public List<Descriptor> read(SkillCapabilityLookup.Scope s){
        var scope=new Scope(s.tenantId(),s.clientId(),s.ownerJiacn());
        var initial=positions.read(scope);var identity=identities.requireRegistrationIdentityInScope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId());
        List<Descriptor> result=new ArrayList<>();
        for(var i:initial.state().installations().values()){
            if(!i.agentId().equals(s.agentId()) || !Objects.equals(identity.getBindingId(),i.bindingId()) || !i.usable())continue;
            var skill=initial.state().skills().get(i.skillKey());
            var v=market.selectApprovedProductVersion(s.tenantId(),s.clientId(),skill.productVersionId());
            if(v==null || !"APPROVED".equals(v.getReviewStatus()) || v.getPackageSha256()==null
                    || !("sha256:"+HexFormat.of().formatHex(v.getPackageSha256())).equals(i.packageDigest()))continue;
            var bytes=packages.prepare(v);var manifest=packages.manifest(bytes);
            if(manifest==null || !manifest.manifestSha256().equals(i.manifestSha256()))continue;
            result.add(new Descriptor(i.skillKey(),v.getSkillVersion(),PositionSkillRuntimeService.physical(i),
                    HexFormat.of().formatHex(v.getPackageSha256()),manifest.manifestSha256(),manifest.document().toString(),
                    i.runtimeInstallationId(),i.runtimeHostId(),null));
        }
        // No stale retained entitlement may be returned after a concurrent resignation or explicit uninstall.
        if(positions.read(scope).revision()!=initial.revision())return List.of();
        return result;
    }
    /** No package IO under task admission locks. Version and current grant are persistent facts. */
    public List<cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill> entitlements(SkillCapabilityLookup.Scope s){
        var identity=identities.requireRegistrationIdentityInScope(s.tenantId(),s.clientId(),s.ownerJiacn(),s.agentId());
        var state=positions.read(new Scope(s.tenantId(),s.clientId(),s.ownerJiacn())).state();
        var result=new ArrayList<cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill>();
        for(var i:state.installations().values()){
            if(!i.agentId().equals(s.agentId()) || !Objects.equals(identity.getBindingId(),i.bindingId()) || !i.usable())continue;
            var skill=state.skills().get(i.skillKey());var v=market.selectApprovedProductVersion(s.tenantId(),s.clientId(),skill.productVersionId());
            if(v!=null && "APPROVED".equals(v.getReviewStatus()) && v.getPackageSha256()!=null
                    && ("sha256:"+HexFormat.of().formatHex(v.getPackageSha256())).equals(i.packageDigest()) && i.manifestSha256()!=null)
                result.add(new cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup.InstalledSkill(i.skillKey(),v.getSkillVersion()));
        }
        return result;
    }
}
