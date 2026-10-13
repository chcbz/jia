package cn.jia.agent.service;

/** Exact owner/task/assignment authorization and current authenticated runtime fence.
 * Runtime readiness alone and costs never grant execution authority. */
public interface TaskSkillPolicyService {
    record Snapshot(boolean allowed, String reasonCode, long assignmentRevision,
            AgentManagedSessionLookup.SessionFence fence) { }
    Snapshot read(SkillCapabilityLookup.Scope scope, String taskId);
    final class Failure extends RuntimeException {
        public enum Reason { NOT_FOUND, SOURCE_UNAVAILABLE, INTEGRITY_ERROR }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason=reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(),cause); this.reason=reason; }
        public Reason reason() { return reason; }
    }
}
