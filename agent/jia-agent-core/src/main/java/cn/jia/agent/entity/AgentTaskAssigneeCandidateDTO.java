package cn.jia.agent.entity;

import java.util.List;

/** Task-public candidate facts only; never an Agent management/runtime DTO or an execution grant. */
public record AgentTaskAssigneeCandidateDTO(AgentTaskTargetSummaryDTO target, List<String> abilities) {
    public AgentTaskAssigneeCandidateDTO { abilities = List.copyOf(abilities); }
}
