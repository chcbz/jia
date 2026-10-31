package cn.jia.agent.service;

/** Private owner-scoped storage separate from task artifact storage. */
public interface PersonalWorkspaceStorage {
    StoredObject store(Scope scope, byte[] content, String mimeType);

    /** Stream an exact-length upload without buffering the file. Caller owns the input stream. */
    StoredObject storeStream(Scope scope, java.io.InputStream content, long byteLength, String mimeType);

    /** Verify before returning; the caller must close the returned file descriptor. */
    java.io.InputStream openStream(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType);

    /** Verify scope, length and digest before copying any bytes. Caller owns the output stream. */
    void copyTo(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType, java.io.OutputStream output);

    StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType);
    /** Exact configured storage ceiling; negative means this backend cannot safely accept runtime outputs. */
    default long maxContentBytes() { return -1L; }
    record Scope(String tenantId, String clientId, String ownerJiacn) { }
    record StoredObject(String storageUri, String sha256, long byteLength, String mimeType) { }
    record StoredContent(byte[] content, String sha256, long byteLength, String mimeType) {
        public StoredContent { content = content == null ? null : content.clone(); }
        @Override public byte[] content() { return content == null ? null : content.clone(); }
    }
}
