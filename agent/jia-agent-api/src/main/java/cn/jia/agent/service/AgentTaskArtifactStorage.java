package cn.jia.agent.service;

/**
 * Private content-addressed storage used by task artifacts. Implementations must derive every
 * object location from the exact tenant/client/task scope and must never accept a filesystem path
 * or an outbound URL from an artifact producer.
 */
public interface AgentTaskArtifactStorage {
    StoredObject store(Scope scope, byte[] content, String mimeType);

    StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType);

    boolean owns(String storageUri);

    boolean matches(Scope scope, String storageUri, String expectedSha256);

    record Scope(String tenantId, String clientId, String taskId) {
    }

    record StoredObject(String storageUri, String sha256, long byteLength,
            String mimeType, boolean newlyCreated) {
    }

    record StoredContent(byte[] content, String sha256, long byteLength, String mimeType) {
        public StoredContent {
            content = content == null ? null : content.clone();
        }

        @Override
        public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
