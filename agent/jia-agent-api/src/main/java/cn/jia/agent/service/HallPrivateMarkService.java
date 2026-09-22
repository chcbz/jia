package cn.jia.agent.service;

/** Private organization only; never a task acceptance/settlement or execution-state mutation. */
public interface HallPrivateMarkService {
    View get(HallRequestDraftService.OwnerScope scope, String sourceType, String sourceId);
    View mark(HallRequestDraftService.OwnerScope scope, String sourceType, String sourceId,
              Command command, String idempotencyKey);
    record ResultRef(String executionId, String manifestId) { }
    record Command(long expectedRevision, boolean archived, ResultRef viewedResultRef) { }
    record View(HallReadService.Ref ref, long revision, boolean archived,
                ResultRef viewedResultRef, long updatedAt) { }
}
