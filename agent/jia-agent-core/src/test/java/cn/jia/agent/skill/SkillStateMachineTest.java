package cn.jia.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStateMachineTest {
    @Test
    void installationAndEntitlementTerminalStatesDoNotReopen() {
        assertTrue(SkillInstallationStatus.REQUESTED.canTransitionTo(SkillInstallationStatus.INSTALLING));
        assertTrue(SkillInstallationStatus.INSTALLING.canTransitionTo(SkillInstallationStatus.SUCCEEDED));
        assertTrue(SkillInstallationStatus.INSTALLING.canTransitionTo(SkillInstallationStatus.FAILED));
        assertFalse(SkillInstallationStatus.SUCCEEDED.canTransitionTo(SkillInstallationStatus.FAILED));
        assertTrue(SkillEntitlementStatus.PENDING_INSTALLATION.canTransitionTo(SkillEntitlementStatus.ACTIVE));
        assertTrue(SkillEntitlementStatus.PENDING_INSTALLATION.canTransitionTo(SkillEntitlementStatus.FAILED));
        assertFalse(SkillEntitlementStatus.ACTIVE.canTransitionTo(SkillEntitlementStatus.FAILED));
    }
}
