package cn.jia.agent.service;

import java.util.List;

/** Owner edits an unassigned ordinary matter; never assigns, funds or runs a model. */
public interface AgentTaskRequirementEditService {
    record Attachment(String fileId, int version) { }
    record Command(long expectedTaskVersion, long expectedRequirementRevision,
                   String title, String description, List<Attachment> attachments) { }
    record View(String taskId, String taskVersion, String requirementRevision,
                String title, String description, List<Attachment> attachments) { }
    View read(AgentTaskExecutionGrantService.Scope scope, String taskId);
    View readSaved(AgentTaskExecutionGrantService.Scope scope,String taskId,String key);
    View save(AgentTaskExecutionGrantService.Scope scope, String taskId, String key, Command command);
    enum Reason { BAD_REQUEST, NOT_FOUND, CONFLICT, NOT_EDITABLE, UNAVAILABLE }
    final class Failure extends RuntimeException {
        private final Reason reason;
        public Failure(Reason reason) { super(reason.name()); this.reason=reason; }
        public Reason reason() { return reason; }
    }
}
