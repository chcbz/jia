package cn.jia.agent.entity;

import java.util.List;

/** Internal authorized recommendation candidate. Provider scope comes from candidate discovery, never request input. */
public record AgentTaskAssigneeCandidateDTO(AgentTaskTargetSummaryDTO target, List<String> abilities,
        @com.fasterxml.jackson.annotation.JsonIgnore String skillProviderOwnerJiacn) {
    public AgentTaskAssigneeCandidateDTO { abilities = List.copyOf(abilities); }
}
