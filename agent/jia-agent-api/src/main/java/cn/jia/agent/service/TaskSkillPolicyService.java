package cn.jia.agent.service;

/** Task data scope/assignment authorization and the independently authenticated Agent runtime scope.
 * Runtime readiness alone and costs never grant execution authority. */
public interface TaskSkillPolicyService {
    record Snapshot(boolean allowed, String reasonCode, long assignmentRevision,
            AgentManagedSessionLookup.SessionFence fence, SkillCapabilityLookup.Scope runtimeScope) {
        public Snapshot {
            if (allowed) {
                if (runtimeScope == null) throw new IllegalArgumentException("Allowed task policy requires an authenticated runtime scope");
                SkillCapabilityLookup.validateFence(fence);
            }
        }
    }
    /** scope.ownerJiacn is the task data owner, NOT the selected Agent owner.
     * runtimeScope is derived from a current native proof only after task assignment/grant checks.
     * Its owner must never be populated from a browser field or inferred from the task owner. */
    Snapshot read(SkillCapabilityLookup.Scope scope, String taskId);
    final class Failure extends RuntimeException {
        public enum Reason { NOT_FOUND, SOURCE_UNAVAILABLE, INTEGRITY_ERROR }
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason=reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(),cause); this.reason=reason; }
        public Reason reason() { return reason; }
    }
}
