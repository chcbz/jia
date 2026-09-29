package cn.jia.agent.service;

/** Internal, owner-scoped, append-only requirement history. Not a task-plan projection. */
public interface AgentTaskRequirementSnapshotService {
    record Snapshot(String tenantId, String clientId, String ownerJiacn, String taskId,
            long revision, String title, String description, String sha256, String source) { }

    /** Caller holds the newly-created owner-scoped task root inside its creation transaction. */
    Snapshot captureOnCreate(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String originalTitle, String originalDescription);

    /** Explicit authenticated owner re-confirmation, not inference from an old task plan. */
    Snapshot reconfirm(AgentTaskExecutionGrantService.Scope scope, String taskId,
            long expectedTaskVersion, String confirmationId, String confirmedTitle, String confirmedDescription);

    /** Exact immutable revision only; never falls back to the current task plan. */
    Snapshot read(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);

    /** Must run under task-root lock before assignment/grant; rejects stale client revisions. */
    Snapshot requireCurrent(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);
}
