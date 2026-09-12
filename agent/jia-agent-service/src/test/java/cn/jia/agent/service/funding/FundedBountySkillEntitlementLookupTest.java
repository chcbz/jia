package cn.jia.agent.service.funding;

import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundedBountySkillEntitlementLookupTest {
    private final FailClosedFundedBountySkillEntitlementLookup lookup =
            new FailClosedFundedBountySkillEntitlementLookup();
    private final FundedBountyActor actor = new FundedBountyActor("tenant", "client", "user");

    @Test
    void emptyRequirementsMatchWithoutInventingInstalledSkills() {
        var snapshot = lookup.lookup(actor, "agt_00000000000000000000000000000001", List.of());
        assertTrue(snapshot.allRequirementsMatched());
        assertTrue(snapshot.installedSkills().isEmpty());
    }

    @Test
    void nonEmptyRequirementsFailClosedUntilW09ProvidesExactEntitlements() {
        AgentSkillRequirementDTO requirement = new AgentSkillRequirementDTO();
        requirement.setSkillKey("repo-inspector");
        requirement.setVersionRange("1.x");
        var snapshot = lookup.lookup(actor, "agt_00000000000000000000000000000001", List.of(requirement));
        assertFalse(snapshot.allRequirementsMatched());
        assertTrue(snapshot.installedSkills().isEmpty());
    }
}
