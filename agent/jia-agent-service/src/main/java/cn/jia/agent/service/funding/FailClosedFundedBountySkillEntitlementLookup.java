package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.common.TaskEventPayload;

import java.util.List;

/** Temporary W05 default until W09 supplies the scoped entitlement implementation. */
public final class FailClosedFundedBountySkillEntitlementLookup implements FundedBountySkillEntitlementLookup {
    private static final String EMPTY_HASH = "sha256:" + TaskEventPayload.ContentDigest.fromUtf8(
            "funded-bounty-empty-skill-snapshot-v0").sha256();
    private static final String UNAVAILABLE_HASH = "sha256:" + TaskEventPayload.ContentDigest.fromUtf8(
            "funded-bounty-skill-entitlement-lookup-unavailable-v0").sha256();

    @Override
    public VerifiedSkillSnapshot lookup(FundedBountyActor actor, String canonicalAgentId,
            List<AgentSkillRequirementDTO> requirements) {
        if (requirements == null) throw new IllegalArgumentException("requirements are required");
        return requirements.isEmpty()
                ? new VerifiedSkillSnapshot(true, EMPTY_HASH, List.of())
                : new VerifiedSkillSnapshot(false, UNAVAILABLE_HASH, List.of());
    }
}
