package cn.jia.agent.service.impl;

import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PersonalWorkspaceStorageBoundaryTest {
    @TempDir Path temporaryDirectory;

    @Test
    void rejectsSameLengthObjectReplacementInsteadOfReturningTamperedBytes() throws Exception {
        Path root = temporaryDirectory.resolve("private-workspace");
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                root, 4096, Set.of("text/plain"));
        PersonalWorkspaceStorage.Scope owner =
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-a");
        byte[] original = "owner-private".getBytes(StandardCharsets.UTF_8);
        PersonalWorkspaceStorage.StoredObject stored = storage.store(owner, original, "text/plain");

        Path object = objectPath(root, stored.storageUri());
        Files.delete(object);
        Files.write(object, "attacker-data".getBytes(StandardCharsets.UTF_8));

        PersonalWorkspaceException failure = assertThrows(PersonalWorkspaceException.class,
                () -> storage.read(owner, stored.storageUri(), stored.sha256(),
                        stored.byteLength(), "text/plain"));
        assertEquals(PersonalWorkspaceException.Reason.STORAGE_CORRUPT, failure.getReason());
    }

    @Test
    void rejectsSymlinkReplacementEvenWhenTargetContainsTheExpectedBytes() throws Exception {
        Path root = temporaryDirectory.resolve("private-workspace");
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                root, 4096, Set.of("text/plain"));
        PersonalWorkspaceStorage.Scope owner =
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-a");
        byte[] original = "owner-private".getBytes(StandardCharsets.UTF_8);
        PersonalWorkspaceStorage.StoredObject stored = storage.store(owner, original, "text/plain");

        Path outside = temporaryDirectory.resolve("outside-object");
        Files.write(outside, original);
        Path object = objectPath(root, stored.storageUri());
        Files.delete(object);
        Files.createSymbolicLink(object, outside);

        PersonalWorkspaceException failure = assertThrows(PersonalWorkspaceException.class,
                () -> storage.read(owner, stored.storageUri(), stored.sha256(),
                        stored.byteLength(), "text/plain"));
        assertEquals(PersonalWorkspaceException.Reason.STORAGE_CORRUPT, failure.getReason());
    }

    @Test
    void rejectsAValidObjectUriWhenEitherOwnerOrClientScopeChanges() {
        Path root = temporaryDirectory.resolve("private-workspace");
        PersonalWorkspaceStorage storage = new FileSystemPersonalWorkspaceStorage(
                root, 4096, Set.of("text/plain"));
        PersonalWorkspaceStorage.Scope owner =
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-a");
        byte[] original = "owner-private".getBytes(StandardCharsets.UTF_8);
        PersonalWorkspaceStorage.StoredObject stored = storage.store(owner, original, "text/plain");

        for (PersonalWorkspaceStorage.Scope foreign : Set.of(
                new PersonalWorkspaceStorage.Scope("0", "browser", "owner-b"),
                new PersonalWorkspaceStorage.Scope("0", "mobile", "owner-a"))) {
            PersonalWorkspaceException failure = assertThrows(PersonalWorkspaceException.class,
                    () -> storage.read(foreign, stored.storageUri(), stored.sha256(),
                            stored.byteLength(), "text/plain"));
            assertEquals(PersonalWorkspaceException.Reason.STORAGE_CORRUPT, failure.getReason());
        }
    }

    private static Path objectPath(Path root, String storageUri) {
        URI uri = URI.create(storageUri);
        String scopeKey = uri.getHost();
        String hash = uri.getPath().substring(1);
        return root.resolve("v1").resolve(scopeKey.substring(0, 2)).resolve(scopeKey)
                .resolve(hash.substring(0, 2)).resolve(hash);
    }
}
