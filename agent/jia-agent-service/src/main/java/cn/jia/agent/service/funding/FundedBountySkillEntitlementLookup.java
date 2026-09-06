package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;

import java.util.List;

/**
 * W09 integration contract. Implementations must return an exact, scoped, currently installed
 * skill/version snapshot; unknown, stale, cross-scope, or unavailable state must fail closed.
 */
public interface FundedBountySkillEntitlementLookup {
    VerifiedSkillSnapshot lookup(FundedBountyActor actor, String canonicalAgentId,
            List<AgentSkillRequirementDTO> requirements);

    record InstalledSkill(String skillKey, String version) {
    }

    record VerifiedSkillSnapshot(boolean allRequirementsMatched, String skillSetHash,
            List<InstalledSkill> installedSkills) {
        public VerifiedSkillSnapshot {
            installedSkills = List.copyOf(installedSkills);
        }
    }
}
