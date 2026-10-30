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
    void identicalOwnerClientAndBytesCannotCrossTenantStorageBoundaries() {
        var storage = new FileSystemPersonalWorkspaceStorage(temporaryDirectory.resolve("tenants"), 4096);
        var production = new PersonalWorkspaceStorage.Scope("0", "browser", "owner");
        var gray = new PersonalWorkspaceStorage.Scope("1", "browser", "owner");
        byte[] bytes = "same bytes".getBytes(StandardCharsets.UTF_8);
        var first = storage.store(production, bytes, "text/plain");
        var second = storage.store(gray, bytes, "text/plain");
        assertEquals(first.sha256(), second.sha256());
        assertNotEquals(first.storageUri(), second.storageUri());
        assertArrayEquals(bytes, storage.read(gray, second.storageUri(), second.sha256(),
                second.byteLength(), "text/plain").content());
        assertThrows(PersonalWorkspaceException.class, () -> storage.read(gray,
                first.storageUri(), first.sha256(), first.byteLength(), "text/plain"));
        assertThrows(PersonalWorkspaceException.class, () -> storage.read(production,
                second.storageUri(), second.sha256(), second.byteLength(), "text/plain"));
        for (String invalid : new String[]{"", " 1", "1 ", "1\n"}) {
            assertThrows(PersonalWorkspaceException.class, () -> storage.store(
                    new PersonalWorkspaceStorage.Scope(invalid, "browser", "owner"), bytes, "text/plain"));
        }
    }

    @Test
    void storesAnyValidMimeWhileKeepingSizeAndIntegrityBoundaries() {
        var storage = new FileSystemPersonalWorkspaceStorage(temporaryDirectory.resolve("unrestricted"), 16);
        var scope = new PersonalWorkspaceStorage.Scope("0", "browser", "owner");
        for (String mime : Set.of("image/avif", "application/x-custom", "application/octet-stream", "text/html", "image/svg+xml")) {
            byte[] content = "opaque".getBytes(StandardCharsets.UTF_8);
            var saved = storage.store(scope, content, mime);
            assertArrayEquals(content, storage.read(scope, saved.storageUri(), saved.sha256(), saved.byteLength(), mime).content());
        }
        assertThrows(PersonalWorkspaceException.class, () -> storage.store(scope, new byte[17], "application/octet-stream"));
        assertThrows(PersonalWorkspaceException.class, () -> storage.store(scope, new byte[1], "text/html\r\nX: bad"));
    }

    @Test
    void makesOnlyTheConfiguredRootPrivateWithoutMutatingDeploymentAncestors() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        Path root = temporaryDirectory.resolve("private-workspace");

        new FileSystemPersonalWorkspaceStorage(root, 4096);

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
                temporaryDirectory.resolve("private-workspace"), 4096);
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
                temporaryDirectory.resolve("private-workspace"), 4096);
        byte[] content = "same bytes".getBytes(StandardCharsets.UTF_8);
        PersonalWorkspaceStorage.StoredObject first = storage.store(
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-a"), content, "text/plain");
        PersonalWorkspaceStorage.StoredObject second = storage.store(
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-b"), content, "text/plain");

        assertEquals(first.sha256(), second.sha256());
        assertNotEquals(first.storageUri(), second.storageUri());
    }
}
