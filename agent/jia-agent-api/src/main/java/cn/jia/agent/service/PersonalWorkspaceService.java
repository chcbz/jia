package cn.jia.agent.service;

import cn.jia.agent.entity.PersonalWorkspaceViews.FileDetailView;
import cn.jia.agent.entity.PersonalWorkspaceViews.FileView;
import cn.jia.agent.entity.PersonalWorkspaceViews.ListView;
import cn.jia.agent.entity.PersonalWorkspaceViews.OperationView;
import cn.jia.agent.entity.PersonalWorkspaceViews.PreviewView;
import cn.jia.agent.entity.PersonalWorkspaceViews.UploadView;
import cn.jia.agent.entity.PersonalWorkspaceViews.UsageView;
import cn.jia.agent.entity.PersonalWorkspaceViews.VersionView;
import cn.jia.agent.entity.PersonalWorkspaceViews.VersionPage;

import java.util.List;

/** User JWT scoped service. Task links, runtime grants and artifact projection are intentionally absent in 1.10. */
public interface PersonalWorkspaceService {
    ListView list(Scope scope, ListQuery query);
    FileDetailView get(Scope scope, String fileId);
    List<VersionView> versions(Scope scope, String fileId);
    VersionPage versionsPage(Scope scope, String fileId, String cursor);
    UploadView create(Scope scope, UploadCommand command);
    /** Trusted server-side archive of an already authorized, immutable conversation asset.
     * Callers must verify the source's owner/conversation and committed bytes before invoking.
     * This is not a browser upload endpoint and cannot grant access to the source asset.
     */
    UploadView archiveConversationAsset(Scope scope, ConversationArchiveCommand command);
    /** Trusted server-side archive of an immutable persisted Chat text selection. */
    UploadView archiveConversationText(Scope scope, ConversationTextArchiveCommand command);
    UploadView appendVersion(Scope scope, String fileId, UploadCommand command, int expectedPreviousVersion);
    FileView rename(Scope scope, String fileId, String displayName, String ifMatch, Idempotency idempotency);
    FileView trash(Scope scope, String fileId, long impactRevision, boolean acknowledgeExistingReferences,
            String ifMatch, Idempotency idempotency);
    FileView restore(Scope scope, String fileId, String ifMatch, Idempotency idempotency);
    Content readContent(Scope scope, String fileId, int version);
    Download download(Scope scope, String fileId, int version);
    PreviewView preview(Scope scope, String fileId, int version);
    Content readPreviewPart(Scope scope, String fileId, int version, String partId);
    UsageView usage(Scope scope, String fileId);
    OperationView operation(Scope scope, String operationId);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record ListQuery(String q, String mediaFamily, String state, String cursor) { }
    record Idempotency(String key) { }
    record ConversationArchiveCommand(Idempotency idempotency, String assetId, long revision,
            String sha256, String displayName, String filename, String contentMimeType, UploadSource source,long byteLength) {
        public ConversationArchiveCommand(Idempotency idempotency,String assetId,long revision,String sha256,
                String displayName,String filename,String contentMimeType,byte[] content) {
            this(idempotency,assetId,revision,sha256,displayName,filename,contentMimeType,
                    UploadCommand.memorySource(content),content==null?-1:content.length);
        }
    }
    record ConversationTextArchiveCommand(Idempotency idempotency, String sourceSnapshotKey,
            String sha256, String displayName, String filename, byte[] content) {
        public ConversationTextArchiveCommand { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    /** Reopenable for hashing followed by storage; lifetime is the current request. */
    @FunctionalInterface interface UploadSource {
        java.io.InputStream open() throws java.io.IOException;
    }
    record UploadCommand(Idempotency idempotency, String displayName, String originalFilename,
            String contentMimeType, UploadSource source, long byteLength) {
        /** In-memory sources such as existing chat text; HTTP uploads use the stream constructor. */
        public UploadCommand(Idempotency idempotency, String displayName, String originalFilename,
                String contentMimeType, byte[] bytes) {
            this(idempotency, displayName, originalFilename, contentMimeType,
                    memorySource(bytes), bytes == null ? -1 : bytes.length);
        }
        private static UploadSource memorySource(byte[] bytes) {
            if (bytes == null) return null;
            byte[] immutable = bytes.clone();
            return () -> new java.io.ByteArrayInputStream(immutable);
        }
    }
    /** Authorized, verified file descriptor; caller closes it even if response writing fails. */
    record Download(String filename, String contentMimeType, long byteLength, java.io.InputStream content)
            implements AutoCloseable {
        @Override public void close() throws java.io.IOException { content.close(); }
    }
    record Content(String filename, String contentMimeType, byte[] bytes) {
        public Content { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
}
