package cn.jia.agent.skill;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.funding.FundedBountyActor;
import cn.jia.agent.service.funding.FundedBountySkillEntitlementLookup;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import cn.jia.economy.mapper.EconomySkillApplicationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.BigInteger;

/** W05 adapter only: persisted ACTIVE order + SUCCEEDED installation + ACTIVE entitlement.
 * Runtime ability strings are never skill ownership evidence. */
@Service
public class InstalledSkillEntitlementLookup implements FundedBountySkillEntitlementLookup {
    private final EconomySkillMarketplaceMapper market;
    private final EconomySkillApplicationMapper application;
    private final SkillAgentVersions versions;
    private final SkillMarketplaceService skills;
    public InstalledSkillEntitlementLookup(EconomySkillMarketplaceMapper market,EconomySkillApplicationMapper application,
            SkillAgentVersions versions,SkillMarketplaceService skills) {
        this.market=market;this.application=application;this.versions=versions;this.skills=skills;
    }
    @Override @Transactional(rollbackFor=Exception.class)
    public VerifiedSkillSnapshot lookup(FundedBountyActor actor,String agentId,List<AgentSkillRequirementDTO> requirements) {
        Objects.requireNonNull(requirements,"requirements");
        var a=new HostingRentHttp.Actor(actor.userId(),actor.tenantId(),actor.clientId());
        if(!skills.available(a)) return snapshot(requirements.isEmpty(),List.of());
        // The caller already holds task/binding/runtime roots; this joins the same REQUIRED transaction.
        versions.requireOwned(a,agentId,null,false);
        List<InstalledSkill> installed=new ArrayList<>();
        for(var e:market.selectEntitlementsByAgent(a.tenantId(),a.clientId(),agentId)) {
            if(!"ACTIVE".equals(e.getStatus())) continue;
            var i=application.installation(a.tenantId(),a.clientId(),e.getInstallationId());
            var o=application.order(a.tenantId(),a.clientId(),e.getOrderId());
            if(i==null || o==null || !"SUCCEEDED".equals(i.getStatus()) || !"ACTIVE".equals(o.getStatus())
                    || !agentId.equals(i.getTargetAgentId()) || !agentId.equals(o.getTargetAgentId())
                    || !e.getOrderId().equals(i.getOrderId()) || !e.getProductVersionId().equals(i.getProductVersionId())
                    || !e.getProductVersionId().equals(o.getProductVersionId()) || !e.getSkillKey().equals(i.getSkillKey())
                    || !e.getSkillVersion().equals(i.getSkillVersion()) || !Objects.equals(e.getActivatedAt(),i.getInstalledAt())) continue;
            installed.add(new InstalledSkill(e.getSkillKey(),e.getSkillVersion()));
        }
        installed.sort(Comparator.comparing(InstalledSkill::skillKey).thenComparing(InstalledSkill::version));
        boolean matched=requirements.stream().allMatch(r->r!=null && installed.stream().anyMatch(i->
                i.skillKey().equals(r.getSkillKey()) && matchesVersion(i.version(),r.getVersionRange())));
        return snapshot(matched,installed);
    }
    private static VerifiedSkillSnapshot snapshot(boolean matched,List<InstalledSkill> installed) {
        Map<String,String> fields=new TreeMap<>();
        for(int i=0;i<installed.size();i++) { fields.put(i+":key",installed.get(i).skillKey());fields.put(i+":version",installed.get(i).version()); }
        return new VerifiedSkillSnapshot(matched,SkillMarketplaceService.digest(HostingRentHttp.hash("installed-active-skills-v1",fields)),installed);
    }
    /** Bounded V0 range grammar: exact release triplet or a single >= release triplet. Unknown grammar denies. */
    static boolean matchesVersion(String actual,String range) {
        if(actual==null || range==null || !actual.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) return false;
        boolean minimum=range.startsWith(">="); String target=minimum?range.substring(2):range;
        if(!target.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")) return false;
        if(!minimum) return actual.equals(target);
        String[] left=actual.split("\\."),right=target.split("\\.");
        for(int i=0;i<3;i++) { int c=new BigInteger(left[i]).compareTo(new BigInteger(right[i])); if(c!=0)return c>0; }
        return true;
    }
}
