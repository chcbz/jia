package cn.jia.agent.service.funding;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FundedBountyRequestDigestTest {
    @Test
    void equalSemanticBodiesHaveEqualDigestAndEveryFundedFieldIsBound() {
        AgentTaskCreateDTO first = request("100", ">=1.0.0");
        AgentTaskCreateDTO same = request("100", ">=1.0.0");
        AgentTaskCreateDTO changedAmount = request("101", ">=1.0.0");
        AgentTaskCreateDTO changedSkill = request("100", ">=2.0.0");

        assertArrayEquals(FundedBountyRequestDigest.create(first), FundedBountyRequestDigest.create(same));
        assertFalse(java.util.Arrays.equals(FundedBountyRequestDigest.create(first),
                FundedBountyRequestDigest.create(changedAmount)));
        assertFalse(java.util.Arrays.equals(FundedBountyRequestDigest.create(first),
                FundedBountyRequestDigest.create(changedSkill)));
        assertFalse(java.util.Arrays.equals(FundedBountyRequestDigest.cancel("task-a", "0"),
                FundedBountyRequestDigest.cancel("task-b", "0")));
    }

    @Test
    void malformedSurrogatesAreRejectedInsteadOfCollidingThroughUtf8Replacement() {
        AgentTaskCreateDTO highD800 = request("100", ">=1.0.0");
        highD800.setTitle("x\uD800");
        AgentTaskCreateDTO highD801 = request("100", ">=1.0.0");
        highD801.setTitle("x\uD801");

        assertThrows(IllegalArgumentException.class,
                () -> FundedBountyRequestDigest.create(highD800));
        assertThrows(IllegalArgumentException.class,
                () -> FundedBountyRequestDigest.create(highD801));

        AgentTaskCreateDTO malformedDescription = request("100", ">=1.0.0");
        malformedDescription.setDescription("bad\uDC00");
        assertThrows(IllegalArgumentException.class,
                () -> FundedBountyRequestDigest.create(malformedDescription));

        AgentTaskCreateDTO malformedAbility = request("100", ">=1.0.0");
        malformedAbility.setRequiredAbilities(List.of("ability\uD800"));
        assertThrows(IllegalArgumentException.class,
                () -> FundedBountyRequestDigest.create(malformedAbility));

        AgentTaskCreateDTO malformedSkill = request("100", ">=1.0.0");
        malformedSkill.getRequiredSkillRequirements().getFirst().setSkillKey("skill\uD801");
        assertThrows(IllegalArgumentException.class,
                () -> FundedBountyRequestDigest.create(malformedSkill));
    }

    private static AgentTaskCreateDTO request(String amount, String range) {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("title");
        request.setDescription("description");
        request.setRequiredAbilities(List.of("test"));
        request.setReward(1);
        request.setGrossBountyAmountMicro(amount);
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        AgentSkillRequirementDTO requirement = new AgentSkillRequirementDTO();
        requirement.setSkillKey("repo-test");
        requirement.setVersionRange(range);
        request.setRequiredSkillRequirements(List.of(requirement));
        return request;
    }
}
