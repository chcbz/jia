package cn.jia.agent.service;

import java.util.List;

/** Browser JWT scoped task-to-file selection. This contract does not grant runtime file access. */
public interface PersonalWorkspaceTaskLinkService {
    LinkListView list(Scope scope, String taskId, String cursor);
    LinkView create(Scope scope, String taskId, CreateCommand command);
    DetachView detach(Scope scope, String taskId, String relationId,
            String ifMatch, String idempotencyKey);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record CreateCommand(String fileId, int version, String role, String idempotencyKey) { }
    record LinkView(String relationId, String taskId, String fileId, int version,
            String role, String state, long relationRevision, long createdAt) { }
    record LinkListView(List<LinkView> items, String nextCursor) { }
    record DetachView(LinkView link, boolean executionSnapshotsPreserved) { }

    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Failure(Reason reason, Throwable cause) { super(reason.name(), cause); this.reason = reason; }
        public Reason getReason() { return reason; }
    }

    enum Reason {
        BAD_REQUEST,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        OUTPUT_MANAGED_BY_EXECUTION,
        PRECONDITION_REQUIRED,
        LINK_CHANGED,
        UNAVAILABLE
    }
}
