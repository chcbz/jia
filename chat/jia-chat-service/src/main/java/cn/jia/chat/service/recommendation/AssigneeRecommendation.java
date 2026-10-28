package cn.jia.chat.service.recommendation;

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
    public record Recommendation(Agent agent, String reason, List<Evidence> evidence, List<String> gaps) { }
    public record Result(String taskId, String taskVersion, long observedAt, String state,
                         String requirementSummary, List<Recommendation> recommendations,
                         List<String> questions, boolean candidateSetComplete) { }
    public static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        public Failure(int status, String code) { super(code); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
