package cn.jia.agent.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure, deterministic E02 greedy team-cover selector. */
final class AgentTaskTeamRecommendationGreedy {
    static final String ROLE_PRODUCER = "PRODUCER";
    static final String ROLE_REVIEWER = "REVIEWER";

    static final String REASON_NO_ELIGIBLE_PRODUCER = "NO_ELIGIBLE_PRODUCER";
    static final String REASON_ABILITY_COVERAGE_INCOMPLETE = "ABILITY_COVERAGE_INCOMPLETE";
    static final String REASON_NO_CANDIDATE_FOR_MISSING_ABILITIES =
            "NO_ELIGIBLE_CANDIDATE_FOR_MISSING_ABILITIES";
    static final String REASON_MAX_TEAM_SIZE_REACHED = "MAX_TEAM_SIZE_REACHED";
    static final String REASON_BUDGET_LIMIT_REACHED = "BUDGET_LIMIT_REACHED";
    static final String REASON_INDEPENDENT_REVIEWER_UNAVAILABLE =
            "INDEPENDENT_REVIEWER_UNAVAILABLE";

    private AgentTaskTeamRecommendationGreedy() {
    }

    record Candidate(
            String agentId,
            String name,
            List<String> matchedAbilities,
            List<String> roles,
            int score,
            int costUnits) {
        Candidate {
            requireExactText(agentId, "agentId");
            matchedAbilities = immutableExactTextList(matchedAbilities, "matchedAbilities");
            roles = immutableExactTextList(roles, "roles");
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("score must be between 0 and 100");
            }
            if (costUnits <= 0) {
                throw new IllegalArgumentException("costUnits must be positive");
            }
        }

        boolean reviewerCapable() {
            return roles.stream().anyMatch(role -> "reviewer".equalsIgnoreCase(role));
        }
    }

    record Constraints(int maxTeamSize, int budgetUnits, boolean independentReviewerRequired) {
        Constraints {
            if (maxTeamSize <= 0) {
                throw new IllegalArgumentException("maxTeamSize must be positive");
            }
            if (budgetUnits < 0) {
                throw new IllegalArgumentException("budgetUnits must not be negative");
            }
        }
    }

    record SelectedMember(Candidate candidate, String role, List<String> marginalCoveredAbilities) {
        SelectedMember {
            Objects.requireNonNull(candidate, "candidate");
            if (!ROLE_PRODUCER.equals(role) && !ROLE_REVIEWER.equals(role)) {
                throw new IllegalArgumentException("selection role is invalid");
            }
            marginalCoveredAbilities = List.copyOf(marginalCoveredAbilities);
        }
    }

    record Result(
            List<SelectedMember> members,
            List<String> coveredAbilities,
            List<String> missingAbilities,
            List<String> duplicateAgentIds,
            List<String> blockingReasons,
            int totalCostUnits,
            boolean independentReviewerSatisfied,
            boolean constraintsSatisfied) {
        Result {
            members = List.copyOf(members);
            coveredAbilities = List.copyOf(coveredAbilities);
            missingAbilities = List.copyOf(missingAbilities);
            duplicateAgentIds = List.copyOf(duplicateAgentIds);
            blockingReasons = List.copyOf(blockingReasons);
        }
    }

    static Result select(
            List<String> requiredAbilities,
            List<Candidate> candidates,
            Constraints constraints) {
        Objects.requireNonNull(constraints, "constraints");
        LinkedHashMap<String, String> requiredByKey = canonicalAbilities(
                requiredAbilities, "requiredAbilities");
        LinkedHashMap<String, Candidate> uniqueCandidates = new LinkedHashMap<>();
        LinkedHashSet<String> duplicateAgentIds = new LinkedHashSet<>();
        for (Candidate candidate : Objects.requireNonNullElse(candidates, List.<Candidate>of())) {
            Objects.requireNonNull(candidate, "candidate");
            if (uniqueCandidates.putIfAbsent(candidate.agentId(), candidate) != null) {
                duplicateAgentIds.add(candidate.agentId());
            }
        }

        List<Candidate> orderedCandidates = List.copyOf(uniqueCandidates.values());
        ProducerSelection producerSelection = selectProducers(
                requiredByKey, orderedCandidates, Set.of(),
                constraints.maxTeamSize(), constraints.budgetUnits());
        LinkedHashMap<String, String> missingByKey =
                new LinkedHashMap<>(producerSelection.missingByKey());
        List<SelectedMember> selected = new ArrayList<>(producerSelection.members());
        LinkedHashSet<String> selectedAgentIds =
                new LinkedHashSet<>(producerSelection.selectedAgentIds());
        int spent = producerSelection.totalCostUnits();

        boolean reviewerSatisfied = !constraints.independentReviewerRequired();
        if (constraints.independentReviewerRequired()) {
            Candidate reviewer = bestReviewer(
                    orderedCandidates, selectedAgentIds, constraints, spent);
            if (reviewer != null) {
                selected.add(new SelectedMember(reviewer, ROLE_REVIEWER, List.of()));
                selectedAgentIds.add(reviewer.agentId());
                spent += reviewer.costUnits();
                reviewerSatisfied = true;
            } else {
                ReservedReviewerPlan reservedPlan = selectWithReservedReviewer(
                        requiredByKey, orderedCandidates, constraints);
                if (reservedPlan != null) {
                    missingByKey = new LinkedHashMap<>(reservedPlan.missingByKey());
                    selected = new ArrayList<>(reservedPlan.members());
                    selectedAgentIds = new LinkedHashSet<>(reservedPlan.selectedAgentIds());
                    spent = reservedPlan.totalCostUnits();
                    reviewerSatisfied = true;
                }
            }
        }

        List<String> covered = new ArrayList<>();
        for (Map.Entry<String, String> required : requiredByKey.entrySet()) {
            if (!missingByKey.containsKey(required.getKey())) {
                covered.add(required.getValue());
            }
        }
        covered = List.copyOf(covered);
        List<String> missing = List.copyOf(missingByKey.values());
        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        boolean producerSelected = selected.stream()
                .anyMatch(member -> ROLE_PRODUCER.equals(member.role()));
        if (!producerSelected) {
            blockers.add(REASON_NO_ELIGIBLE_PRODUCER);
        }
        if (!missing.isEmpty()) {
            blockers.add(REASON_ABILITY_COVERAGE_INCOMPLETE);
            if (!hasContributingCandidate(orderedCandidates, selectedAgentIds, missingByKey)) {
                blockers.add(REASON_NO_CANDIDATE_FOR_MISSING_ABILITIES);
            }
        }
        if ((!missing.isEmpty() || !reviewerSatisfied || !producerSelected)
                && selected.size() >= constraints.maxTeamSize()) {
            blockers.add(REASON_MAX_TEAM_SIZE_REACHED);
        }
        if ((!missing.isEmpty() || !reviewerSatisfied || !producerSelected)
                && budgetPreventsProgress(
                        orderedCandidates, selectedAgentIds, missingByKey,
                        constraints.independentReviewerRequired() && !reviewerSatisfied,
                        constraints.budgetUnits() - spent)) {
            blockers.add(REASON_BUDGET_LIMIT_REACHED);
        }
        if (!reviewerSatisfied) {
            blockers.add(REASON_INDEPENDENT_REVIEWER_UNAVAILABLE);
        }

        boolean constraintsSatisfied = producerSelected && missing.isEmpty() && reviewerSatisfied;
        return new Result(selected, covered, missing, List.copyOf(duplicateAgentIds),
                List.copyOf(blockers), spent, reviewerSatisfied, constraintsSatisfied);
    }

    private record ProducerSelection(
            List<SelectedMember> members,
            Map<String, String> missingByKey,
            Set<String> selectedAgentIds,
            int totalCostUnits) {
        ProducerSelection {
            members = List.copyOf(members);
            missingByKey = Collections.unmodifiableMap(new LinkedHashMap<>(missingByKey));
            selectedAgentIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(selectedAgentIds));
        }
    }

    private record ReservedReviewerPlan(
            List<SelectedMember> members,
            Map<String, String> missingByKey,
            Set<String> selectedAgentIds,
            int totalCostUnits) {
        ReservedReviewerPlan {
            members = List.copyOf(members);
            missingByKey = Collections.unmodifiableMap(new LinkedHashMap<>(missingByKey));
            selectedAgentIds = Collections.unmodifiableSet(
                    new LinkedHashSet<>(selectedAgentIds));
        }
    }

    private static ProducerSelection selectProducers(
            Map<String, String> requiredByKey,
            List<Candidate> candidates,
            Set<String> excludedAgentIds,
            int maxProducers,
            int budgetUnits) {
        LinkedHashMap<String, String> missingByKey = new LinkedHashMap<>(requiredByKey);
        List<SelectedMember> selected = new ArrayList<>();
        LinkedHashSet<String> selectedAgentIds = new LinkedHashSet<>();
        LinkedHashSet<String> unavailableAgentIds = new LinkedHashSet<>(excludedAgentIds);
        int spent = 0;

        if (maxProducers > 0 && requiredByKey.isEmpty()) {
            Candidate producer = bestDefaultProducer(
                    candidates, unavailableAgentIds, budgetUnits, spent);
            if (producer != null) {
                selected.add(new SelectedMember(producer, ROLE_PRODUCER, List.of()));
                selectedAgentIds.add(producer.agentId());
                spent += producer.costUnits();
            }
        } else {
            while (!missingByKey.isEmpty() && selected.size() < maxProducers) {
                Candidate producer = bestCoverageCandidate(
                        candidates, unavailableAgentIds, missingByKey, budgetUnits, spent);
                if (producer == null) {
                    break;
                }
                List<String> marginal = marginalAbilities(producer, missingByKey);
                selected.add(new SelectedMember(producer, ROLE_PRODUCER, marginal));
                selectedAgentIds.add(producer.agentId());
                unavailableAgentIds.add(producer.agentId());
                spent += producer.costUnits();
                marginal.stream().map(AgentTaskTeamRecommendationGreedy::canonicalKey)
                        .forEach(missingByKey::remove);
            }
        }
        return new ProducerSelection(selected, missingByKey, selectedAgentIds, spent);
    }

    private static ReservedReviewerPlan selectWithReservedReviewer(
            Map<String, String> requiredByKey,
            List<Candidate> candidates,
            Constraints constraints) {
        if (constraints.maxTeamSize() < 2) {
            return null;
        }
        List<Candidate> reviewerCandidates = candidates.stream()
                .filter(Candidate::reviewerCapable)
                .sorted((left, right) -> {
                    long leftWeighted = (long) left.score() * right.costUnits();
                    long rightWeighted = (long) right.score() * left.costUnits();
                    int ratio = Long.compare(rightWeighted, leftWeighted);
                    if (ratio != 0) return ratio;
                    return Integer.compare(right.score(), left.score());
                })
                .toList();
        for (Candidate reviewer : reviewerCandidates) {
            if (reviewer.costUnits() > constraints.budgetUnits()) {
                continue;
            }
            ProducerSelection producers = selectProducers(
                    requiredByKey, candidates, Set.of(reviewer.agentId()),
                    constraints.maxTeamSize() - 1,
                    constraints.budgetUnits() - reviewer.costUnits());
            if (producers.members().isEmpty() || !producers.missingByKey().isEmpty()) {
                continue;
            }
            List<SelectedMember> members = new ArrayList<>(producers.members());
            members.add(new SelectedMember(reviewer, ROLE_REVIEWER, List.of()));
            LinkedHashSet<String> memberIds = new LinkedHashSet<>(producers.selectedAgentIds());
            memberIds.add(reviewer.agentId());
            return new ReservedReviewerPlan(
                    members, producers.missingByKey(), memberIds,
                    producers.totalCostUnits() + reviewer.costUnits());
        }
        return null;
    }

    private static Candidate bestCoverageCandidate(
            List<Candidate> candidates,
            Set<String> selectedAgentIds,
            Map<String, String> missingByKey,
            int budgetUnits,
            int spent) {
        Candidate best = null;
        int bestCoverage = 0;
        for (Candidate candidate : candidates) {
            if (selectedAgentIds.contains(candidate.agentId())
                    || spent + candidate.costUnits() > budgetUnits) {
                continue;
            }
            int coverage = marginalAbilities(candidate, missingByKey).size();
            if (coverage == 0) {
                continue;
            }
            if (best == null || ratioGreater(coverage, candidate.costUnits(),
                    bestCoverage, best.costUnits())
                    || ratioEqual(coverage, candidate.costUnits(), bestCoverage, best.costUnits())
                    && candidate.score() > best.score()) {
                best = candidate;
                bestCoverage = coverage;
            }
        }
        return best;
    }

    private static Candidate bestDefaultProducer(
            List<Candidate> candidates,
            Set<String> selectedAgentIds,
            int budgetUnits,
            int spent) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (selectedAgentIds.contains(candidate.agentId())
                    || spent + candidate.costUnits() > budgetUnits) {
                continue;
            }
            if (best == null
                    || ratioGreater(candidate.score(), candidate.costUnits(),
                            best.score(), best.costUnits())
                    || ratioEqual(candidate.score(), candidate.costUnits(),
                            best.score(), best.costUnits())
                    && candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    private static Candidate bestReviewer(
            List<Candidate> candidates,
            Set<String> selectedAgentIds,
            Constraints constraints,
            int spent) {
        if (selectedAgentIds.size() >= constraints.maxTeamSize()) {
            return null;
        }
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (!candidate.reviewerCapable()
                    || selectedAgentIds.contains(candidate.agentId())
                    || spent + candidate.costUnits() > constraints.budgetUnits()) {
                continue;
            }
            if (best == null
                    || ratioGreater(candidate.score(), candidate.costUnits(),
                            best.score(), best.costUnits())
                    || ratioEqual(candidate.score(), candidate.costUnits(),
                            best.score(), best.costUnits())
                    && candidate.score() > best.score()) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean hasContributingCandidate(
            List<Candidate> candidates,
            Set<String> selectedAgentIds,
            Map<String, String> missingByKey) {
        return candidates.stream()
                .filter(candidate -> !selectedAgentIds.contains(candidate.agentId()))
                .anyMatch(candidate -> !marginalAbilities(candidate, missingByKey).isEmpty());
    }

    private static boolean budgetPreventsProgress(
            List<Candidate> candidates,
            Set<String> selectedAgentIds,
            Map<String, String> missingByKey,
            boolean reviewerNeeded,
            int remainingBudget) {
        return candidates.stream()
                .filter(candidate -> !selectedAgentIds.contains(candidate.agentId()))
                .filter(candidate -> reviewerNeeded && candidate.reviewerCapable()
                        || !missingByKey.isEmpty()
                        && !marginalAbilities(candidate, missingByKey).isEmpty()
                        || missingByKey.isEmpty() && !reviewerNeeded)
                .anyMatch(candidate -> candidate.costUnits() > remainingBudget);
    }

    private static List<String> marginalAbilities(
            Candidate candidate, Map<String, String> missingByKey) {
        LinkedHashSet<String> marginalKeys = new LinkedHashSet<>();
        for (String ability : candidate.matchedAbilities()) {
            String key = canonicalKey(ability);
            if (missingByKey.containsKey(key)) {
                marginalKeys.add(key);
            }
        }
        return marginalKeys.stream().map(missingByKey::get).toList();
    }

    private static boolean ratioGreater(int leftValue, int leftCost, int rightValue, int rightCost) {
        return (long) leftValue * rightCost > (long) rightValue * leftCost;
    }

    private static boolean ratioEqual(int leftValue, int leftCost, int rightValue, int rightCost) {
        return (long) leftValue * rightCost == (long) rightValue * leftCost;
    }

    private static LinkedHashMap<String, String> canonicalAbilities(
            List<String> abilities, String field) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String ability : Objects.requireNonNullElse(abilities, List.<String>of())) {
            requireExactText(ability, field);
            result.putIfAbsent(canonicalKey(ability), ability);
        }
        return result;
    }

    private static List<String> immutableExactTextList(List<String> values, String field) {
        List<String> result = new ArrayList<>();
        for (String value : Objects.requireNonNullElse(values, List.<String>of())) {
            requireExactText(value, field);
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static String canonicalKey(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void requireExactText(String value, String field) {
        if (value == null || value.isEmpty() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains invalid text");
        }
    }
}
