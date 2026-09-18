package cn.jia.agent.entity;

import java.util.List;

/** Browser-safe views for the personal workspace HTTP contract. */
public final class PersonalWorkspaceViews {
    private PersonalWorkspaceViews() { }

    public record FileView(String fileId, String sourceKind, String originKind, String displayName, String mediaFamily,
            String state, long metadataRevision, int latestVersion, long createdAt,
            Capabilities capabilities) { }
    public record VersionView(String fileId, int version, String originalFilename,
            String contentMimeType, long byteLength, String sha256, long createdAt,
            String previewState) { }
    public record Capabilities(String upload, String read, String edit, String preview, String download) { }
    public record ListView(List<FileView> items, String nextCursor) { }
    public record FileDetailView(FileView file, VersionView latestVersion,
            List<VersionView> versions, List<Object> relations, List<Object> derivation) { }
    public record OperationView(String operationId, String state, String fileId,
            Integer fileVersion, String errorCode) { }
    public record UploadView(OperationView operation, FileView file, VersionView version) { }
    public record UsageView(long impactRevision, List<TaskReferenceView> taskReferences,
            List<Object> activeExecutions) { }
    public record TaskReferenceView(String taskId, String relationId, int version, String role,
            long relationRevision, long createdAt) { }
    public record PreviewView(String state, List<PreviewPart> parts, boolean partial, String reason) { }
    public record PreviewPart(String partId, String contentMimeType) { }
}
