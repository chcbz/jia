package cn.jia.agent.api;
import cn.jia.agent.service.HallRequestDraftService;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
/** HTTP-only projection. Domain revisions, canonical snapshots and arithmetic stay integral. */
public final class HallDraftWire {
    private HallDraftWire() { }
    private static String decimal(long value) { if(value<0) throw new IllegalArgumentException("Negative revision"); return Long.toString(value); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record InputSelection(String fileId, String version) { }
    public static InputSelection from(HallRequestDraftService.InputSelection v) { return v==null?null:new InputSelection(v.fileId(),decimal(v.version())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceRef(String sourceType, String sourceId, String version) { }
    public static SourceRef from(HallRequestDraftService.SourceRef v) { return v==null?null:new SourceRef(v.sourceType(),v.sourceId(),v.version()==null?null:decimal(v.version())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceOutputRef(String executionId, String outputId, String fileId, String fileVersion) { }
    public static SourceOutputRef from(HallRequestDraftService.SourceOutputRef v) { return v==null?null:new SourceOutputRef(v.executionId(),v.outputId(),v.fileId(),decimal(v.fileVersion())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EditableFields(String title, String instruction, String targetAgentId, String outputMime, List<InputSelection> inputs) { }
    public static EditableFields from(HallRequestDraftService.EditableFields v) { return v==null?null:new EditableFields(v.title(),v.instruction(),v.targetAgentId(),v.outputMime(),v.inputs().stream().map(HallDraftWire::from).toList()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceSummary(String originRef, SourceRef sourceRef, String caseId, String taskId, String conversationId, SourceOutputRef sourceOutputRef) { }
    public static SourceSummary from(HallRequestDraftService.SourceSummary v) { return v==null?null:new SourceSummary(v.originRef(),from(v.sourceRef()),v.caseId(),v.taskId(),v.conversationId(),from(v.sourceOutputRef())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftView(String draftId, String revision, String state, long savedAt, String kind, EditableFields editableFields, SourceSummary sourceSummary, String submissionRef) { }
    public static DraftView from(HallRequestDraftService.DraftView v) { return v==null?null:new DraftView(v.draftId(),decimal(v.revision()),v.state(),v.savedAt(),v.kind(),from(v.editableFields()),from(v.sourceSummary()),v.submissionRef()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftSummary(String draftId, String revision, String state, long savedAt, String kind, String title, String targetAgentId, String outputMime, SourceSummary sourceSummary) { }
    public static DraftSummary from(HallRequestDraftService.DraftSummary v) { return v==null?null:new DraftSummary(v.draftId(),decimal(v.revision()),v.state(),v.savedAt(),v.kind(),v.title(),v.targetAgentId(),v.outputMime(),from(v.sourceSummary())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DraftPage(List<DraftSummary> items, String nextCursor, boolean hasMore) { }
    public static DraftPage from(HallRequestDraftService.DraftPage v) { return v==null?null:new DraftPage(v.items().stream().map(HallDraftWire::from).toList(),v.nextCursor(),v.nextCursor()!=null); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SubmissionReceipt(HallRequestDraftService.SubmissionReference ref, WorkspaceExecutionWire.Execution execution, HallRequestDraftService.TaskReference task, long submittedAt) { }
    public static SubmissionReceipt from(HallRequestDraftService.SubmissionReceipt v) { return v==null?null:new SubmissionReceipt(v.ref(),WorkspaceExecutionWire.from(v.execution()),v.task(),v.submittedAt()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CaseExecutionView(String revisionNo, String parentExecutionId, SourceOutputRef sourceOutputRef, WorkspaceExecutionWire.Execution execution) { }
    public static CaseExecutionView from(HallRequestDraftService.CaseExecutionView v) { return v==null?null:new CaseExecutionView(decimal(v.revisionNo()),v.parentExecutionId(),from(v.sourceOutputRef()),WorkspaceExecutionWire.from(v.execution())); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CaseView(String caseId, String title, String revision, List<CaseExecutionView> executions, List<String> allowedActions, HallRequestDraftService.CaseSourceRef sourceRef) { }
    public static CaseView from(HallRequestDraftService.CaseView v) { return v==null?null:new CaseView(v.caseId(),v.title(),decimal(v.revision()),v.executions().stream().map(HallDraftWire::from).toList(),v.allowedActions(),v.sourceRef()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ResultItemView(String outputId, String fileId, String fileVersion, String mime, String filename, String byteLength, String sha256, String availability) { }
    public static ResultItemView from(HallRequestDraftService.ResultItemView v) { return v==null?null:new ResultItemView(v.outputId(),v.fileId(),decimal(v.fileVersion()),v.mime(),v.filename(),decimal(v.byteLength()),v.sha256(),v.availability()); }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExecutionResultsView(String executionId, String state, String manifestId, List<ResultItemView> items, List<String> allowedActions) { }
    public static ExecutionResultsView from(HallRequestDraftService.ExecutionResultsView v) { return v==null?null:new ExecutionResultsView(v.executionId(),v.state(),v.manifestId(),v.items().stream().map(HallDraftWire::from).toList(),v.allowedActions()); }
}
