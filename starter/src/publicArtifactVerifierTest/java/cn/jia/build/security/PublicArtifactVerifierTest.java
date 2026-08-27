package cn.jia.build.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicArtifactVerifierTest {
    private static final Set<PosixFilePermission> MODE_0700 = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> MODE_0755 = PosixFilePermissions.fromString("rwxr-xr-x");
    private static final Set<PosixFilePermission> MODE_0600 = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> MODE_0644 = PosixFilePermissions.fromString("rw-r--r--");

    @TempDir
    Path temporaryDirectory;

    @Test
    void temp01RealPosixImplementationCreatesExactModesBeforeAnyNestedByte() throws Exception {
        Path outer = outerWithSafeNested("temp-modes.jar");
        RecordingModeHook hook = new RecordingModeHook();
        Path root = temporaryDirectory.resolve("mode-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertTrue(result.accepted());
        assertEquals(MODE_0700, hook.rootMode);
        assertEquals(MODE_0700, hook.directoryMode);
        assertEquals(MODE_0600, hook.fileMode);
        assertEquals(0L, hook.fileSizeBeforeWrite);
        assertFalse(Files.exists(root));
    }

    @Test
    void temp01NonPosixRootFailsClosedBeforeNestedWrite() throws Exception {
        Path outer = outerWithSafeNested("temp-non-posix.jar");
        Path zip = temporaryDirectory.resolve("non-posix-root.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path root = Files.createDirectory(fileSystem.getPath("/verification-root"));
            PublicArtifactVerifier.Result result = new PublicArtifactVerifier().verify(outer, root);

            assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-security-error>"), diagnostics(result));
            assertEquals(List.of(PublicArtifactVerifier.FailureCode.TEMP_SECURITY_ERROR), codes(result));
        }
    }

    @Test
    void temp01PermissiveRootFailsClosedBeforeNestedWrite() throws Exception {
        Path outer = outerWithSafeNested("temp-permissive-root.jar");
        Path root = Files.createDirectory(temporaryDirectory.resolve("permissive-root"));
        Files.setPosixFilePermissions(root, MODE_0755);
        RecordingModeHook hook = new RecordingModeHook();

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-security-error>"), diagnostics(result));
        assertFalse(hook.fileWriteAttempted);
    }

    @Test
    void temp01SymlinkRootFailsClosedBeforeNestedWrite() throws Exception {
        Path outer = outerWithSafeNested("temp-symlink-root.jar");
        Path target = Files.createDirectory(temporaryDirectory.resolve("real-root"));
        Files.setPosixFilePermissions(target, MODE_0700);
        Path root = temporaryDirectory.resolve("symlink-root");
        Files.createSymbolicLink(root, target);
        RecordingModeHook hook = new RecordingModeHook();

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-security-error>"), diagnostics(result));
        assertFalse(hook.fileWriteAttempted);
        assertTrue(Files.isSymbolicLink(root));
    }

    @Test
    void temp01PermissiveFileIsRolledBackBeforeNestedWrite() throws Exception {
        Path outer = outerWithSafeNested("temp-permissive-file.jar");
        RecordingModeHook hook = new RecordingModeHook() {
            @Override
            public void afterFileCreated(Path file) throws IOException {
                Files.setPosixFilePermissions(file, MODE_0644);
            }
        };
        Path root = temporaryDirectory.resolve("permissive-file-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-security-error>"), diagnostics(result));
        assertFalse(hook.fileWriteAttempted);
        assertFalse(Files.exists(root));
    }

    @Test
    void temp01IdentitySwapAfterWriteFailsSecurityAndCleanupWithoutClassifyingReplacement() throws Exception {
        ZipFixtureBuilder.BuiltZip replacement = new ZipFixtureBuilder()
                .add("config/application.properties", "api-key=replacement-secret-canary\n")
                .build();
        Path outer = outerWithSafeNested("temp-identity-swap.jar");
        PublicArtifactVerifier.TempMutationHook hook = new PublicArtifactVerifier.TempMutationHook() {
            @Override
            public void beforeFileRead(Path file) throws IOException {
                Files.delete(file);
                Files.write(file, replacement.copy());
                Files.setPosixFilePermissions(file, MODE_0600);
            }
        };

        PublicArtifactVerifier.Result result = verifier(hook)
                .verify(outer, temporaryDirectory.resolve("identity-root"));

        assertEquals(List.of(
                "BOOT-INF/lib/library.jar: <nested-temp-security-error>",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertFalse(String.join("\n", diagnostics(result)).contains("replacement-secret-canary"));
    }

    @Test
    void temp01FileCreationSecurityAndRollbackFailureAreBothRetainedAndRetried() throws Exception {
        Path outer = outerWithSafeNested("temp-file-rollback.jar");
        RollbackFailOnceHook hook = new RollbackFailOnceHook(false);
        Path root = temporaryDirectory.resolve("file-rollback-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of(
                "BOOT-INF/lib/library.jar: <nested-temp-security-error>",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertEquals(2, hook.fileDeleteAttempts);
        assertFalse(Files.exists(root));
    }

    @Test
    void temp01DirectoryCreationSecurityAndRollbackFailureAreBothRetainedAndRetried() throws Exception {
        Path outer = outerWithSafeNested("temp-directory-rollback.jar");
        RollbackFailOnceHook hook = new RollbackFailOnceHook(true);
        Path root = temporaryDirectory.resolve("directory-rollback-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of(
                "BOOT-INF/lib/library.jar: <nested-temp-security-error>",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertTrue(hook.directoryDeleteAttempts >= 2);
        assertFalse(Files.exists(root));
    }

    @Test
    void temp02DeleteFailurePreventsAcceptanceAfterClosedStreamsAndIsRetried() throws Exception {
        Path outer = outerWithSafeNested("temp-cleanup.jar");
        DeleteOnceHook hook = new DeleteOnceHook("injected-delete-exception-canary");
        Path root = temporaryDirectory.resolve("cleanup-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertEquals(List.of(PublicArtifactVerifier.FailureCode.TEMP_CLEANUP_ERROR), codes(result));
        assertTrue(hook.fileWasReopenableAtDelete);
        assertEquals(2, hook.fileDeleteAttempts);
        assertFalse(Files.exists(root));
    }

    @Test
    void e2e01FlatAndNestedDevCertificateKeyAndApiKeyFindingsAreExact() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder()
                .add("application-dev.properties", "fixture=true\n")
                .add("certificates/client.p12", "synthetic-container")
                .add("keys/client.key", "synthetic-private-key")
                .add("config/application.yaml", "provider:\n  api-key: live-secret\n")
                .build();
        Path outer = write("public.jar", new ZipFixtureBuilder()
                .add("BOOT-INF/classes/application-dev.properties", "fixture=true\n")
                .add("BOOT-INF/classes/certificates/client.p12", "synthetic-container")
                .add("BOOT-INF/classes/keys/client.key", "synthetic-private-key")
                .add("BOOT-INF/classes/application-prod.properties", "api-key=outer-secret\n")
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first-party.jar", nested.copy()).deflated())
                .build().copy());

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier()
                .verify(outer, temporaryDirectory.resolve("verification-root"));

        assertEquals(List.of(
                "BOOT-INF/classes/application-dev.properties",
                "BOOT-INF/classes/certificates/client.p12",
                "BOOT-INF/classes/keys/client.key",
                "BOOT-INF/classes/application-prod.properties: api-key",
                "BOOT-INF/lib/first-party.jar!application-dev.properties",
                "BOOT-INF/lib/first-party.jar!certificates/client.p12",
                "BOOT-INF/lib/first-party.jar!keys/client.key",
                "BOOT-INF/lib/first-party.jar!config/application.yaml: api-key"), diagnostics(result));
    }

    @Test
    void e2e02ParseDefaultTempAndExceptionCanariesNeverReachDiagnostics() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder().add("safe.txt", "safe").build();
        Path outer = write("redaction.jar", new ZipFixtureBuilder()
                .add("BOOT-INF/classes/config/application.yaml", "api-key: [parse-secret-canary\n")
                .add("BOOT-INF/classes/config/application.properties",
                        "api-key=${PUBLIC_API_KEY:embedded-default-canary}\n")
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", nested.copy()).stored())
                .build().copy());
        DeleteOnceHook hook = new DeleteOnceHook("sensitive-exception-canary");

        PublicArtifactVerifier.Result result = verifier(hook)
                .verify(outer, temporaryDirectory.resolve("sensitive-temp-path-canary"));

        assertEquals(List.of(
                "BOOT-INF/classes/config/application.yaml: <configuration-parse-error>",
                "BOOT-INF/classes/config/application.properties: api-key",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        String output = String.join("\n", diagnostics(result));
        for (String forbidden : List.of(
                "parse-secret-canary",
                "embedded-default-canary",
                "sensitive-temp-path-canary",
                "sensitive-exception-canary",
                "Exception")) {
            assertFalse(output.contains(forbidden));
        }
    }

    @Test
    void controlCharacterKeysBecomeFixedParseDiagnosticsAtDirectCliBoundary() throws Exception {
        Path outer = write("control-key.jar", new ZipFixtureBuilder()
                .add("BOOT-INF/classes/config/application.yaml", "\"bad\\u001bapi-key\": live-secret\n")
                .add("BOOT-INF/classes/config/application.properties", "bad\\u0000api-key=live-secret\n")
                .build().copy());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            exit = PublicArtifactVerifier.run(new String[]{
                    outer.toString(), temporaryDirectory.resolve("cli-root").toString()}, output);
        }

        assertEquals(1, exit);
        assertEquals(
                "BOOT-INF/classes/config/application.yaml: <configuration-parse-error>\n"
                        + "BOOT-INF/classes/config/application.properties: <configuration-parse-error>\n",
                bytes.toString(StandardCharsets.UTF_8));
        assertFalse(bytes.toString(StandardCharsets.UTF_8).chars()
                .anyMatch(value -> value == 0 || value == 0x1b));
    }

    @Test
    void duplicateNestedConfigurationNamesRetainEarlierOccurrenceIdentity() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder()
                .add("config/application.properties", ZipFixtureBuilder.COLLIDING_UNSAFE_PROPERTIES)
                .add("config/application.properties", ZipFixtureBuilder.COLLIDING_SAFE_PROPERTIES)
                .build();
        Path outer = write("duplicate.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/duplicate.jar", nested.copy()).stored())
                .build().copy());

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier()
                .verify(outer, temporaryDirectory.resolve("duplicate-root"));

        assertEquals(List.of("BOOT-INF/lib/duplicate.jar!config/application.properties: api-key"),
                diagnostics(result));
        PublicArtifactVerifier.Finding finding = result.findings().get(0);
        assertEquals(0, finding.occurrenceId().centralDirectoryOrdinal());
    }

    private PublicArtifactVerifier verifier(PublicArtifactVerifier.TempMutationHook hook) {
        return new PublicArtifactVerifier(PublicArtifactVerifier.posixTempOperations(hook));
    }

    private Path outerWithSafeNested(String name) throws IOException {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder().add("safe.txt", "safe").build();
        return write(name, new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", nested.copy()).stored())
                .build().copy());
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path path = temporaryDirectory.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    private static List<PublicArtifactVerifier.FailureCode> codes(PublicArtifactVerifier.Result result) {
        return result.findings().stream().map(PublicArtifactVerifier.Finding::code).toList();
    }

    private static List<String> diagnostics(PublicArtifactVerifier.Result result) {
        List<String> values = new ArrayList<>();
        for (PublicArtifactVerifier.Finding finding : result.findings()) {
            values.add(finding.diagnostic());
        }
        return values;
    }

    private static class RecordingModeHook implements PublicArtifactVerifier.TempMutationHook {
        private Set<PosixFilePermission> rootMode;
        private Set<PosixFilePermission> directoryMode;
        private Set<PosixFilePermission> fileMode;
        private long fileSizeBeforeWrite = -1;
        private boolean fileWriteAttempted;

        @Override
        public void afterDirectoryValidated(Path directory) throws IOException {
            rootMode = Files.getPosixFilePermissions(directory.getParent());
            directoryMode = Files.getPosixFilePermissions(directory);
        }

        @Override
        public void beforeFileWrite(Path file) throws IOException {
            fileWriteAttempted = true;
            fileMode = Files.getPosixFilePermissions(file);
            fileSizeBeforeWrite = Files.size(file);
        }
    }

    private static final class RollbackFailOnceHook implements PublicArtifactVerifier.TempMutationHook {
        private final boolean directoryFailure;
        private int fileDeleteAttempts;
        private int directoryDeleteAttempts;
        private boolean failed;

        private RollbackFailOnceHook(boolean directoryFailure) {
            this.directoryFailure = directoryFailure;
        }

        @Override
        public void afterDirectoryCreated(Path directory) throws IOException {
            if (directoryFailure) {
                Files.setPosixFilePermissions(directory, MODE_0755);
            }
        }

        @Override
        public void afterFileCreated(Path file) throws IOException {
            if (!directoryFailure) {
                Files.setPosixFilePermissions(file, MODE_0644);
            }
        }

        @Override
        public void beforeDelete(Path path) throws IOException {
            boolean file = path.getFileName().toString().startsWith("nested-");
            if (file) {
                fileDeleteAttempts++;
            } else {
                directoryDeleteAttempts++;
            }
            if (!failed && file != directoryFailure) {
                failed = true;
                throw new IOException("rollback-delete-exception-canary");
            }
        }
    }

    private static final class DeleteOnceHook implements PublicArtifactVerifier.TempMutationHook {
        private final String failureMessage;
        private int fileDeleteAttempts;
        private boolean fileWasReopenableAtDelete;

        private DeleteOnceHook(String failureMessage) {
            this.failureMessage = failureMessage;
        }

        @Override
        public void beforeDelete(Path path) throws IOException {
            if (!path.getFileName().toString().startsWith("nested-")) {
                return;
            }
            fileDeleteAttempts++;
            try (var channel = Files.newByteChannel(path)) {
                fileWasReopenableAtDelete = channel.isOpen();
            }
            if (fileDeleteAttempts == 1) {
                throw new IOException(failureMessage);
            }
        }
    }
}
