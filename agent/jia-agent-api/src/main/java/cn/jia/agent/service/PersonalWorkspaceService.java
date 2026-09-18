package cn.jia.agent.service;

import cn.jia.agent.entity.PersonalWorkspaceViews.FileDetailView;
import cn.jia.agent.entity.PersonalWorkspaceViews.FileView;
import cn.jia.agent.entity.PersonalWorkspaceViews.ListView;
import cn.jia.agent.entity.PersonalWorkspaceViews.OperationView;
import cn.jia.agent.entity.PersonalWorkspaceViews.PreviewView;
import cn.jia.agent.entity.PersonalWorkspaceViews.UploadView;
import cn.jia.agent.entity.PersonalWorkspaceViews.UsageView;
import cn.jia.agent.entity.PersonalWorkspaceViews.VersionView;

import java.util.List;

/** User JWT scoped service. Task links, runtime grants and artifact projection are intentionally absent in 1.10. */
public interface PersonalWorkspaceService {
    ListView list(Scope scope, ListQuery query);
    FileDetailView get(Scope scope, String fileId);
    List<VersionView> versions(Scope scope, String fileId);
    UploadView create(Scope scope, UploadCommand command);
    UploadView appendVersion(Scope scope, String fileId, UploadCommand command, int expectedPreviousVersion);
    FileView rename(Scope scope, String fileId, String displayName, String ifMatch, Idempotency idempotency);
    FileView trash(Scope scope, String fileId, long impactRevision, boolean acknowledgeExistingReferences,
            String ifMatch, Idempotency idempotency);
    FileView restore(Scope scope, String fileId, String ifMatch, Idempotency idempotency);
    Content readContent(Scope scope, String fileId, int version);
    PreviewView preview(Scope scope, String fileId, int version);
    Content readPreviewPart(Scope scope, String fileId, int version, String partId);
    UsageView usage(Scope scope, String fileId);
    OperationView operation(Scope scope, String operationId);

    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record ListQuery(String q, String mediaFamily, String state, String cursor) { }
    record Idempotency(String key) { }
    record UploadCommand(Idempotency idempotency, String displayName, String originalFilename,
            String contentMimeType, byte[] content) {
        public UploadCommand { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
    record Content(String filename, String contentMimeType, byte[] bytes) {
        public Content { bytes = bytes == null ? null : bytes.clone(); }
        @Override public byte[] bytes() { return bytes == null ? null : bytes.clone(); }
    }
}
