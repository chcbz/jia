package cn.jia.agent.entity;

import java.util.List;

/** R1 business data; common protocol envelope is supplied by the HTTP boundary. */
public final class AssigneeRecommendation {
    private AssigneeRecommendation() { }
    public record Request(String expectedTaskVersion) { }
    public record Agent(String agentId, String name, String status, List<String> abilities,
                        boolean canOperate, boolean boundToMe, String providerDisplayName) { }
    /** available means this observation may be cited, NOT an execution authorization. */
    public record Evidence(String id, String kind, String label, String description, boolean available) { }
    public record Candidate(Agent agent, List<Evidence> evidence) { }
    public record Recommendation(Agent agent, String recommendationLevel, String reason, List<Evidence> evidence, List<String> gaps, Quotation quote) { }
    /** Public fixed quote, not a reservation. An unavailable candidate has no monetary amount. */
    public record Quotation(String state, String quoteId, String recommendationId,
                            cn.jia.economy.common.Money amount, String unavailableReason) { }
    /** Internal model judgement, not a price or execution authorization. */
    public record Workload(Kind kind, long inputTokens, long outputTokens, long deliverables) {
        public enum Kind { DOCUMENT, DATA, CODE, MEDIA, GENERAL }
    }
    public record Result(String taskId, String taskVersion, long observedAt, String state,
                         String requirementSummary, List<Recommendation> recommendations,
                         List<String> questions, boolean candidateSetComplete,
                         @com.fasterxml.jackson.annotation.JsonIgnore Workload workload) { }
    public static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code) { super(code); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
