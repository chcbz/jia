package cn.jia.economy.skill;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SkillOrderStateMachineTest {
    @Test
    void orderAllowsOnlyV0FundsHeldInstallingActiveOrPreactivationRefund() {
        assertTrue(SkillOrderStatus.FUNDS_HELD.canTransitionTo(SkillOrderStatus.INSTALLING));
        assertTrue(SkillOrderStatus.FUNDS_HELD.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertTrue(SkillOrderStatus.INSTALLING.canTransitionTo(SkillOrderStatus.ACTIVE));
        assertTrue(SkillOrderStatus.INSTALLING.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertFalse(SkillOrderStatus.ACTIVE.canTransitionTo(SkillOrderStatus.REFUNDED));
        assertFalse(SkillOrderStatus.REFUNDED.canTransitionTo(SkillOrderStatus.INSTALLING));
    }

}
