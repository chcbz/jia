package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskRiskAutonomyPolicyTest {
    @Test
    void lowAndMediumTransfersRemainSupervisedAndRequireApproval() {
        for (String risk : new String[] {"low", "medium"}) {
            AgentTaskRiskAutonomyPolicy.Decision decision =
                    AgentTaskRiskAutonomyPolicy.expiredLeaseReassignment(task(risk, false));
            assertEquals("supervised", decision.autonomyLevel());
            assertTrue(decision.requiresApproval());
        }
    }

    @Test
    void highRiskTransferIsManualAndRequiresReviewBoundary() {
        AgentTaskRiskAutonomyPolicy.Decision decision =
                AgentTaskRiskAutonomyPolicy.expiredLeaseReassignment(task("high", true));
        assertEquals("manual", decision.autonomyLevel());
        assertTrue(decision.requiresApproval());
        assertThrows(IllegalArgumentException.class,
                () -> AgentTaskRiskAutonomyPolicy.expiredLeaseReassignment(task("high", false)));
    }

    @Test
    void malformedPersistedRiskFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> AgentTaskRiskAutonomyPolicy.expiredLeaseReassignment(task(null, false)));
        assertThrows(IllegalArgumentException.class,
                () -> AgentTaskRiskAutonomyPolicy.expiredLeaseReassignment(task("HIGH", true)));
    }

    private static AgentTaskMetaEntity task(String riskLevel, boolean reviewRequired) {
        return new AgentTaskMetaEntity()
                .setRiskLevel(riskLevel)
                .setReviewRequired(reviewRequired);
    }
}
