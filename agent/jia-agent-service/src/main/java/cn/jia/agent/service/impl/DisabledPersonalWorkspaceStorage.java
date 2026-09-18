package cn.jia.agent.service.impl;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;

/** Explicit fail-closed default until a private root is configured. */
public final class DisabledPersonalWorkspaceStorage implements PersonalWorkspaceStorage {
    @Override public StoredObject store(Scope scope, byte[] content, String mimeType) { throw unavailable(); }
    @Override public StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType) { throw unavailable(); }
    private static PersonalWorkspaceException unavailable() {
        return new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_UNAVAILABLE);
    }
}
