package cn.jia.agent.service.impl;

import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.state.AgentTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentTaskAggregationCalculatorTest {
    private final AgentTaskAggregationCalculator calculator = new AgentTaskAggregationCalculator();

    @Test
    void oneMemberAndOneRequiredItemCannotCompleteMultiItemTeamTask() {
        var decision = calculator.calculate(AgentTaskStatus.RUNNING,
                List.of(member("agent-a", "done"), member("agent-b", "done")),
                List.of(
                        item("required-a", true, "completed", 1, 3, "artifact-a", 100L),
                        item("required-b", true, "running", 0, 3, null, null)));

        assertEquals(AgentTaskStatus.RUNNING, decision.status());
        assertEquals(1, decision.counts().requiredCompletedCount());
        assertEquals(2, decision.counts().requiredWorkItemCount());
    }

    @Test
    void optionalItemsDoNotBlockRequiredCompletion() {
        var decision = calculator.calculate(AgentTaskStatus.REVIEWING,
                List.of(member("agent-a", "done"), member("agent-b", "working")),
                List.of(
                        item("required-a", true, "completed", 1, 3, "artifact-a", 100L),
                        item("required-b", true, "completed", 1, 3, "artifact-b", 101L),
                        item("optional-failed", false, "failed", 3, 3, null, null),
                        item("optional-pending", false, "pending", 0, 3, null, null)));

        assertEquals(AgentTaskStatus.COMPLETED, decision.status());
        assertEquals(2, decision.counts().requiredCompletedCount());
        assertEquals(2, decision.counts().optionalWorkItemCount());
    }

    @Test
    void completedStatusWithoutAcceptedArtifactFailsClosedInReviewing() {
        var decision = calculator.calculate(AgentTaskStatus.RUNNING, List.of(),
                List.of(item("required", true, "completed", 1, 3, null, 100L)));

        assertEquals(AgentTaskStatus.REVIEWING, decision.status());
        assertEquals(0, decision.counts().requiredCompletedCount());
        assertEquals(1, decision.counts().requiredSubmittedCount());
    }

    @Test
    void allRequiredSubmittedHasPriorityOverGenericActiveWork() {
        var decision = calculator.calculate(AgentTaskStatus.RUNNING, List.of(),
                List.of(
                        item("submitted", true, "submitted", 0, 3, null, null),
                        item("accepted", true, "completed", 1, 3, "artifact", 100L)));

        assertEquals(AgentTaskStatus.REVIEWING, decision.status());
        assertEquals("all_required_submitted", decision.reason());
    }

    @Test
    void recoverableRequiredBlockHasPriorityOverOtherRunningWork() {
        var decision = calculator.calculate(AgentTaskStatus.RUNNING, List.of(),
                List.of(
                        item("blocked", true, "blocked", 1, 3, null, null),
                        item("running", true, "running", 0, 3, null, null)));

        assertEquals(AgentTaskStatus.BLOCKED, decision.status());
        assertEquals("blocked", decision.relatedWorkItemId());
    }

    @Test
    void exhaustedRequiredFailureHasHighestNonterminalPriority() {
        var decision = calculator.calculate(AgentTaskStatus.RUNNING, List.of(),
                List.of(
                        item("failed", true, "failed", 3, 3, null, null),
                        item("accepted", true, "completed", 1, 3, "artifact", 100L)));

        assertEquals(AgentTaskStatus.FAILED, decision.status());
        assertEquals("failed", decision.relatedWorkItemId());
    }

    @Test
    void terminalTaskIsNeverAutomaticallyReversed() {
        var decision = calculator.calculate(AgentTaskStatus.COMPLETED, List.of(),
                List.of(item("failed", true, "failed", 3, 3, null, null)));

        assertEquals(AgentTaskStatus.COMPLETED, decision.status());
        assertEquals("terminal_preserved", decision.reason());
    }

    @Test
    void emptyTaskDoesNotVacuouslyCompleteOrReview() {
        var decision = calculator.calculate(AgentTaskStatus.PLANNING,
                List.of(member("agent-a", "done")), List.of());

        assertEquals(AgentTaskStatus.PLANNING, decision.status());
        assertEquals("no_required_work_items", decision.reason());
    }

    @Test
    void exactLegacyOpenCanAdvanceButIsNeverProducedAsNewState() {
        var decision = calculator.calculate(AgentTaskStatus.OPEN,
                List.of(member("agent-a", "accepted")),
                List.of(item("ready", true, "ready", 0, 3, null, null)));

        assertEquals(AgentTaskStatus.ASSIGNED, decision.status());
    }

    private AgentTaskMemberEntity member(String agentId, String status) {
        return new AgentTaskMemberEntity()
                .setAgentId(agentId)
                .setMemberStatus(status);
    }

    private AgentTaskWorkItemEntity item(
            String id, boolean required, String status,
            int attempts, int maxAttempts, String artifactId, Long completedAt) {
        return new AgentTaskWorkItemEntity()
                .setWorkItemId(id)
                .setRequiredItem(required)
                .setStatus(status)
                .setAttemptCount(attempts)
                .setMaxAttempts(maxAttempts)
                .setResultArtifactId(artifactId)
                .setCompletedAt(completedAt);
    }
}
