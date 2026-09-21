package cn.jia.agent.service;

import java.util.List;
import java.util.Map;

/** Independent Hall read model. Partitions are independently paged, never a synthetic global total. */
public interface HallReadService {
    Overview overview(HallRequestDraftService.OwnerScope scope);
    Items items(HallRequestDraftService.OwnerScope scope, String kind, String view, String q, String cursor);

    record Ref(String sourceType, String sourceId) { }
    record Status(String code, String evidenceSource, long observedAt) { }
    record TargetAgent(String agentId) { }
    record ItemSummary(Ref ref, String title, Status status, TargetAgent targetAgent,
                       String nextAction, List<String> allowedActions, long updatedAt) { }
    /** count is unknown (null); complete means the source query succeeded, not end-of-pagination. */
    record Partition(List<ItemSummary> items, String status, String nextCursor,
                     Long count, String errorCode) { }
    record Section(String status, Map<String, Partition> partitions) { }
    record SourceStatus(String status, String errorCode) { }
    record Overview(int schemaVersion, Map<String, Section> sections,
                    Map<String, Map<String, SourceStatus>> sourceStatus, long asOf) { }
    record Items(int schemaVersion, String kind, String view, String q, Section section,
                 Map<String, SourceStatus> sourceStatus, long asOf) { }

    enum Reason { BAD_REQUEST, VIEW_UNAVAILABLE }
    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason = reason; }
        public Reason reason() { return reason; }
    }
}
