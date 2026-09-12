package cn.jia.economy.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillStateMachineTest {
    @Test
    void orderAllowsOnlyV0FundsHeldInstallingActiveOrPreactivationRefund() {
        assertTrue(SkillOrderStatus.FUNDS_HELD.canTransitionTo(SkillOrderStatus.INSTALLING));
        assertTrue(SkillOrderStatus.FUNDS_HELD.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertTrue(SkillOrderStatus.INSTALLING.canTransitionTo(SkillOrderStatus.ACTIVE));
        assertTrue(SkillOrderStatus.INSTALLING.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertFalse(SkillOrderStatus.ACTIVE.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertFalse(SkillOrderStatus.REFUNDED.canTransitionTo(SkillOrderStatus.INSTALLING));
    }

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
