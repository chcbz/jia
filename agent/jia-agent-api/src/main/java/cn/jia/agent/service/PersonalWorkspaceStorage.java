package cn.jia.agent.service;

/** Private owner-scoped storage separate from task artifact storage. */
public interface PersonalWorkspaceStorage {
    StoredObject store(Scope scope, byte[] content, String mimeType);
    StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType);
    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record StoredObject(String storageUri, String sha256, long byteLength, String mimeType) { }
    record StoredContent(byte[] content, String sha256, long byteLength, String mimeType) {
        public StoredContent { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
}
