package cn.jia.agent.api;
import cn.jia.agent.entity.PersonalWorkspaceViews;
import cn.jia.agent.service.PersonalWorkspaceConversationLinkService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import java.util.List;
/** Explicit HTTP projections; storage/service version and revision arithmetic stay integral. */
public final class PersonalWorkspaceWire {
    private PersonalWorkspaceWire() { }
    public record FileView(String fileId, String sourceKind, String originKind, String displayName, String mediaFamily, String state, String metadataRevision, String latestVersion, long createdAt, PersonalWorkspaceViews.Capabilities capabilities) { }
    public static FileView from(PersonalWorkspaceViews.FileView v) { return v == null ? null : new FileView(v.fileId(), v.sourceKind(), v.originKind(), v.displayName(), v.mediaFamily(), v.state(), String.valueOf(v.metadataRevision()), String.valueOf(v.latestVersion()), v.createdAt(), v.capabilities()); }
    public record VersionView(String fileId, String version, String originalFilename, String contentMimeType, String byteLength, String sha256, long createdAt, String previewState) { }
    public static VersionView from(PersonalWorkspaceViews.VersionView v) { return v == null ? null : new VersionView(v.fileId(), String.valueOf(v.version()), v.originalFilename(), v.contentMimeType(), String.valueOf(v.byteLength()), v.sha256(), v.createdAt(), v.previewState()); }
    public record ListView(List<FileView> items, String nextCursor, boolean hasMore) { }
    public static ListView from(PersonalWorkspaceViews.ListView v) { return v == null ? null : new ListView(v.items().stream().map(PersonalWorkspaceWire::from).toList(), v.nextCursor(), v.nextCursor() != null); }
    public record FileDetailView(FileView file, VersionView latestVersion, List<VersionView> versions, List<Object> relations, List<Object> derivation) { }
    public static FileDetailView from(PersonalWorkspaceViews.FileDetailView v) { return v == null ? null : new FileDetailView(from(v.file()), from(v.latestVersion()), v.versions().stream().map(PersonalWorkspaceWire::from).toList(), v.relations(), v.derivation()); }
    public record OperationView(String operationId, String state, String fileId, String fileVersion, String errorCode) { }
    public static OperationView from(PersonalWorkspaceViews.OperationView v) { return v == null ? null : new OperationView(v.operationId(), v.state(), v.fileId(), v.fileVersion() == null ? null : v.fileVersion().toString(), v.errorCode()); }
    public record UploadView(OperationView operation, FileView file, VersionView version) { }
    public static UploadView from(PersonalWorkspaceViews.UploadView v) { return v == null ? null : new UploadView(from(v.operation()), from(v.file()), from(v.version())); }
    public record UsageCounts(String taskReferences, String conversationReferences, String activeInputGrants) { }
    public record UsageView(String fileId, String impactRevision, UsageCounts counts) { }
    public static UsageView from(PersonalWorkspaceViews.UsageView v) {
        return v == null ? null : new UsageView(v.fileId(), String.valueOf(v.impactRevision()),
                new UsageCounts(String.valueOf(v.counts().taskReferences()),
                        String.valueOf(v.counts().conversationReferences()), String.valueOf(v.counts().activeInputGrants())));
    }
    public record TaskReferenceView(String taskId, String relationId, String version, String role, String relationRevision, long createdAt) { }
    public static TaskReferenceView from(PersonalWorkspaceViews.TaskReferenceView v) { return v == null ? null : new TaskReferenceView(v.taskId(), v.relationId(), String.valueOf(v.version()), v.role(), String.valueOf(v.relationRevision()), v.createdAt()); }
    public record ConversationLinkView(String relationId, String conversationId, String fileId, String version, String role, String state, String relationRevision, long createdAt) { }
    public static ConversationLinkView from(PersonalWorkspaceConversationLinkService.LinkView v) { return v == null ? null : new ConversationLinkView(v.relationId(), v.conversationId(), v.fileId(), String.valueOf(v.version()), v.role(), v.state(), String.valueOf(v.relationRevision()), v.createdAt()); }
    public record ConversationLinkListView(List<ConversationLinkView> items, String nextCursor, boolean hasMore) { }
    public static ConversationLinkListView from(PersonalWorkspaceConversationLinkService.LinkListView v) { return v == null ? null : new ConversationLinkListView(v.items().stream().map(PersonalWorkspaceWire::from).toList(), v.nextCursor(), v.nextCursor() != null); }
    public record ConversationDetachView(ConversationLinkView link, boolean executionSnapshotsPreserved) { }
    public static ConversationDetachView from(PersonalWorkspaceConversationLinkService.DetachView v) { return v == null ? null : new ConversationDetachView(from(v.link()), v.executionSnapshotsPreserved()); }
    public record TaskLinkView(String relationId, String taskId, String fileId, String version, String role, String state, String relationRevision, long createdAt) { }
    public static TaskLinkView from(PersonalWorkspaceTaskLinkService.LinkView v) { return v == null ? null : new TaskLinkView(v.relationId(), v.taskId(), v.fileId(), String.valueOf(v.version()), v.role(), v.state(), String.valueOf(v.relationRevision()), v.createdAt()); }
    public record TaskLinkListView(List<TaskLinkView> items, String nextCursor, boolean hasMore) { }
    public static TaskLinkListView from(PersonalWorkspaceTaskLinkService.LinkListView v) { return v == null ? null : new TaskLinkListView(v.items().stream().map(PersonalWorkspaceWire::from).toList(), v.nextCursor(), v.nextCursor() != null); }
    public record TaskDetachView(TaskLinkView link, boolean executionSnapshotsPreserved) { }
    public static TaskDetachView from(PersonalWorkspaceTaskLinkService.DetachView v) { return v == null ? null : new TaskDetachView(from(v.link()), v.executionSnapshotsPreserved()); }
    public record VersionPage(List<VersionView> items, String nextCursor, boolean hasMore) { }
    public static VersionPage from(PersonalWorkspaceViews.VersionPage v) { return new VersionPage(v.items().stream().map(PersonalWorkspaceWire::from).toList(), v.nextCursor(), v.hasMore()); }
}
