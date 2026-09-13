package cn.jia.agent.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskTeamRecommendationGreedyTest {

    @Test
    void selectsHighestMarginalCoveragePerCost() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend", "frontend", "mysql"),
                List.of(
                        candidate("agent-expensive", List.of("backend", "frontend", "mysql"),
                                List.of("executor"), 100, 3),
                        candidate("agent-pair", List.of("backend", "frontend"),
                                List.of("executor"), 80, 1),
                        candidate("agent-db", List.of("mysql"),
                                List.of("executor"), 70, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(3, 2, false));

        assertEquals(List.of("agent-pair", "agent-db"), result.members().stream()
                .map(member -> member.candidate().agentId()).toList());
        assertEquals(List.of("backend", "frontend", "mysql"), result.coveredAbilities());
        assertEquals(List.of(), result.missingAbilities());
        assertEquals(2, result.totalCostUnits());
        assertTrue(result.constraintsSatisfied());
    }

    @Test
    void preservesInputOrderForEqualRatioAndScoreAndDeduplicatesAgentIds() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend", "frontend"),
                List.of(
                        candidate("agent-z", List.of("backend"), List.of("executor"), 90, 1),
                        candidate("agent-a", List.of("backend"), List.of("executor"), 90, 1),
                        candidate("agent-z", List.of("frontend"), List.of("executor"), 100, 1),
                        candidate("agent-front", List.of("frontend"), List.of("executor"), 70, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(2, 2, false));

        assertEquals(List.of("agent-z", "agent-front"), result.members().stream()
                .map(member -> member.candidate().agentId()).toList());
        assertEquals(List.of("agent-z"), result.duplicateAgentIds());
        assertTrue(result.constraintsSatisfied());
    }

    @Test
    void reportsMissingCoverageAtTeamOrBudgetLimit() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend", "frontend"),
                List.of(
                        candidate("agent-back", List.of("backend"), List.of("executor"), 90, 1),
                        candidate("agent-front", List.of("frontend"), List.of("executor"), 80, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(1, 1, false));

        assertEquals(List.of("frontend"), result.missingAbilities());
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_ABILITY_COVERAGE_INCOMPLETE));
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_MAX_TEAM_SIZE_REACHED));
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_BUDGET_LIMIT_REACHED));
        assertFalse(result.constraintsSatisfied());
    }

    @Test
    void reservesReviewerCapableAgentWhenGreedyProducerChoiceWouldConsumeIt() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend"),
                List.of(
                        candidate("agent-reviewer-producer", List.of("backend"),
                                List.of("executor", "reviewer"), 100, 1),
                        candidate("agent-producer", List.of("backend"),
                                List.of("executor"), 90, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(2, 2, true));

        assertEquals(List.of("agent-producer", "agent-reviewer-producer"),
                result.members().stream()
                        .map(member -> member.candidate().agentId()).toList());
        assertEquals(List.of(
                AgentTaskTeamRecommendationGreedy.ROLE_PRODUCER,
                AgentTaskTeamRecommendationGreedy.ROLE_REVIEWER), result.members().stream()
                .map(AgentTaskTeamRecommendationGreedy.SelectedMember::role).toList());
        assertTrue(result.constraintsSatisfied());
    }

    @Test
    void reportsReviewerSizeAndBudgetBlockersWithoutReusingProducer() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend"),
                List.of(
                        candidate("agent-producer", List.of("backend"),
                                List.of("executor", "reviewer"), 100, 1),
                        candidate("agent-reviewer", List.of(),
                                List.of("reviewer"), 90, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(1, 1, true));

        assertEquals(List.of("agent-producer"), result.members().stream()
                .map(member -> member.candidate().agentId()).toList());
        assertFalse(result.independentReviewerSatisfied());
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_MAX_TEAM_SIZE_REACHED));
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_BUDGET_LIMIT_REACHED));
        assertTrue(result.blockingReasons().contains(
                AgentTaskTeamRecommendationGreedy.REASON_INDEPENDENT_REVIEWER_UNAVAILABLE));
        assertFalse(result.constraintsSatisfied());
    }

    @Test
    void requiresReviewerDifferentFromEveryProducer() {
        var result = AgentTaskTeamRecommendationGreedy.select(
                List.of("backend"),
                List.of(
                        candidate("agent-producer", List.of("backend"),
                                List.of("executor", "reviewer"), 100, 1),
                        candidate("agent-reviewer", List.of("backend"),
                                List.of("reviewer"), 80, 1)),
                new AgentTaskTeamRecommendationGreedy.Constraints(2, 2, true));

        assertEquals(List.of("agent-producer", "agent-reviewer"), result.members().stream()
                .map(member -> member.candidate().agentId()).toList());
        assertEquals(List.of(
                AgentTaskTeamRecommendationGreedy.ROLE_PRODUCER,
                AgentTaskTeamRecommendationGreedy.ROLE_REVIEWER), result.members().stream()
                .map(AgentTaskTeamRecommendationGreedy.SelectedMember::role).toList());
        assertTrue(result.independentReviewerSatisfied());
        assertTrue(result.constraintsSatisfied());
    }

    private static AgentTaskTeamRecommendationGreedy.Candidate candidate(
            String agentId, List<String> abilities, List<String> roles, int score, int cost) {
        return new AgentTaskTeamRecommendationGreedy.Candidate(
                agentId, agentId, abilities, roles, score, cost);
    }
}
