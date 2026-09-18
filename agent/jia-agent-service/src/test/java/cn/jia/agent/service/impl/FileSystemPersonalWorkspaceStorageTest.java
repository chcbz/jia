package cn.jia.agent.service.impl;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemPersonalWorkspaceStorageTest {
    @TempDir Path temporaryDirectory;

    @Test
    void makesOnlyTheConfiguredRootPrivateWithoutMutatingDeploymentAncestors() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        Path root = temporaryDirectory.resolve("private-workspace");

        new FileSystemPersonalWorkspaceStorage(root, 4096, Set.of("text/plain"));

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE),
                Files.getPosixFilePermissions(temporaryDirectory));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(root));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void storesImmutableContentUnderExactOwnerScopeAndVerifiesReadback() {
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                temporaryDirectory.resolve("private-workspace"), 4096,
                Set.of("text/plain", "image/png"));
        PersonalWorkspaceStorage.Scope first = new PersonalWorkspaceStorage.Scope("0", "browser", "chcbz");
        byte[] content = "个人工作空间".getBytes(StandardCharsets.UTF_8);

        PersonalWorkspaceStorage.StoredObject stored = storage.store(first, content, "text/plain");
        PersonalWorkspaceStorage.StoredObject repeated = storage.store(first, content, "text/plain");

        assertEquals(stored.storageUri(), repeated.storageUri());
        assertEquals(stored.sha256(), repeated.sha256());
        assertArrayEquals(content, storage.read(first, stored.storageUri(), stored.sha256(),
                stored.byteLength(), "text/plain").content());
        assertThrows(PersonalWorkspaceException.class, () -> storage.read(
                new PersonalWorkspaceStorage.Scope("0", "browser", "another-owner"),
                stored.storageUri(), stored.sha256(), stored.byteLength(), "text/plain"));
    }

    @Test
    void keepsTheSameDigestPrivateToEachOwnerScope() {
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                temporaryDirectory.resolve("private-workspace"), 4096, Set.of("text/plain"));
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        PersonalWorkspaceStorage.StoredObject first = storage.store(
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-a"), content, "text/plain");
        PersonalWorkspaceStorage.StoredObject second = storage.store(
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-b"), content, "text/plain");

        assertEquals(first.sha256(), second.sha256());
        assertNotEquals(first.storageUri(), second.storageUri());
    }
}
