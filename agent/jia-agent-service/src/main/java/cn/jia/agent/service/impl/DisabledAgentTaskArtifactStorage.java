package cn.jia.agent.service.impl;

import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.exception.AgentTaskArtifactStorageException.Reason;
import cn.jia.agent.service.AgentTaskArtifactStorage;

/** Fail-closed default: existing inline/external metadata flows remain available without file I/O. */
public final class DisabledAgentTaskArtifactStorage implements AgentTaskArtifactStorage {
    @Override
    public StoredObject store(Scope scope, byte[] content, String mimeType) {
        throw disabled();
    }

    @Override
    public StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType) {
        throw disabled();
    }

    @Override
    public boolean owns(String storageUri) {
        return FileSystemAgentTaskArtifactStorage.isOwnedUri(storageUri);
    }

    @Override
    public boolean matches(Scope scope, String storageUri, String expectedSha256) {
        return FileSystemAgentTaskArtifactStorage.matchesReference(
                scope, storageUri, expectedSha256);
    }

    private AgentTaskArtifactStorageException disabled() {
        return new AgentTaskArtifactStorageException(
                Reason.DISABLED, "Managed artifact storage is unavailable");
    }
}
