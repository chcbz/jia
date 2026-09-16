package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskMetaEntity;

import java.util.Set;

/**
 * E06 server-side risk/autonomy policy for collaboration commands.
 *
 * <p>The task root is authoritative: callers cannot select an autonomy level or clear approval
 * requirements through a request body. L0/L1 remain the default; no path in this policy grants
 * an autonomous (L3) command. A high-risk work-item transfer additionally requires the task to
 * have an explicit review boundary before a command can be emitted.</p>
 */
final class AgentTaskRiskAutonomyPolicy {
    private static final Set<String> RISK_LEVELS = Set.of("low", "medium", "high");

    private AgentTaskRiskAutonomyPolicy() {
    }

    static Decision expiredLeaseReassignment(AgentTaskMetaEntity task) {
        String riskLevel = requireRiskLevel(task);
        if ("high".equals(riskLevel) && !Boolean.TRUE.equals(task.getReviewRequired())) {
            throw new IllegalArgumentException(
                    "High-risk reassignment requires an authoritative review boundary");
        }
        // Reassignment changes the target agent and must never be an autonomous command.
        // High-risk work is manual; low/medium work stays within L1 supervision.
        return "high".equals(riskLevel)
                ? new Decision("manual", true)
                : new Decision("supervised", true);
    }

    static String requireRiskLevel(AgentTaskMetaEntity task) {
        if (task == null || task.getRiskLevel() == null
                || !RISK_LEVELS.contains(task.getRiskLevel())) {
            throw new IllegalArgumentException("Persisted task riskLevel is invalid");
        }
        return task.getRiskLevel();
    }

    record Decision(String autonomyLevel, boolean requiresApproval) {
        Decision {
            if (!("manual".equals(autonomyLevel) || "supervised".equals(autonomyLevel))
                    || !requiresApproval) {
                throw new IllegalArgumentException("Unsafe task autonomy decision");
            }
        }
    }
}
