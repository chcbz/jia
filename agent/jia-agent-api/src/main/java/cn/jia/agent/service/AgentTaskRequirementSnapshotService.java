package cn.jia.agent.service;

/** Owner-scoped current requirements and immutable business snapshots. Not an audit log. */
public interface AgentTaskRequirementSnapshotService {
    record Snapshot(String tenantId, String clientId, String ownerJiacn, String taskId,
            long revision, String title, String description, String sha256, String source) { }

    /** Owner-safe current projection captured while the task root lock is held. */
    record CurrentSnapshot(String taskId, long taskVersion, long requirementRevision,
            String title, String description, String sha256, String source) { }

    /** Caller holds the newly-created owner-scoped task root inside its creation transaction. */
    Snapshot captureOnCreate(AgentTaskExecutionGrantService.Scope scope, String taskId,
            String originalTitle, String originalDescription);

    /** Explicit authenticated owner re-confirmation, not inference from an old task plan. */
    Snapshot reconfirm(AgentTaskExecutionGrantService.Scope scope, String taskId,
            long expectedTaskVersion, String confirmationId, String confirmedTitle, String confirmedDescription);

    /** Exact immutable revision only; never falls back to the current task plan. */
    Snapshot read(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);

    /**
     * Locks the exact owner-scoped task root and reads its single current requirement row,
     * and returns one read-only observation. It never captures or re-confirms history.
     */
    CurrentSnapshot readCurrent(AgentTaskExecutionGrantService.Scope scope, String taskId);

    /** Mutable current-state replacement; caller holds the task root and authorizes the edit. */
    Snapshot updateCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long expectedRevision,
            String title,String description);

    /** Freeze an exact business input within the assignment transaction, never on an ordinary read. */
    Snapshot freezeCurrent(AgentTaskExecutionGrantService.Scope scope,String taskId,long revision);

    /** Read current state under task-root lock; rejects stale client revisions, never freezes. */
    Snapshot requireCurrent(AgentTaskExecutionGrantService.Scope scope, String taskId, long revision);
}
