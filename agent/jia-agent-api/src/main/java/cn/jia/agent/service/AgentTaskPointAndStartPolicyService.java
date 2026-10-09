package cn.jia.agent.service;

/** Exact owner/task/target read used only for point-and-start capability negotiation. */
public interface AgentTaskPointAndStartPolicyService {
    Snapshot read(AgentTaskExecutionGrantService.Scope scope, String taskId, String targetAgentId);

    record Snapshot(String taskId, String targetAgentId, String taskState,
            boolean eligible, String blockingReason) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason reason() { return reason; }
        public enum Reason { NOT_FOUND, SOURCE_UNAVAILABLE, INTEGRITY_ERROR }
    }
}
