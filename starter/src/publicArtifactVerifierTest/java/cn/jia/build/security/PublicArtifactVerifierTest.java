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
    void cumulativeLimitBelowBoundaryScansEveryNestedOccurrence() throws Exception {
        byte[] nested = safeNestedJar();
        long budget = nested.length * 2L + 1;
        CountingNestedIoHook hook = new CountingNestedIoHook();
        Path root = temporaryDirectory.resolve("below-boundary-root");
        Path outer = write("below-boundary.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first.jar", nested).stored())
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/second.jar", nested).stored())
                .build().copy());

        PublicArtifactVerifier.Result result = verifier(hook, budget).verify(outer, root);

        assertTrue(result.accepted());
        assertEquals(2, hook.fileCreations);
        assertEquals(2, hook.writeAttempts);
        assertEquals(2, hook.readAttempts);
        assertFalse(Files.exists(root));
    }

    @Test
    void cumulativeLimitExactBoundaryScansEveryNestedOccurrence() throws Exception {
        byte[] nested = safeNestedJar();
        long budget = nested.length * 2L;
        CountingNestedIoHook hook = new CountingNestedIoHook();
        Path root = temporaryDirectory.resolve("exact-boundary-root");
        Path outer = write("exact-boundary.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first.jar", nested).stored())
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/second.jar", nested).stored())
                .build().copy());

        PublicArtifactVerifier.Result result = verifier(hook, budget).verify(outer, root);

        assertTrue(result.accepted());
        assertEquals(2, hook.fileCreations);
        assertEquals(2, hook.writeAttempts);
        assertEquals(2, hook.readAttempts);
        assertFalse(Files.exists(root));
    }

    @Test
    void cumulativeLimitFirstOverBoundaryFailsClosedWithoutCopyingThatOccurrence() throws Exception {
        byte[] nested = safeNestedJar();
        ZipFixtureBuilder.BuiltZip built = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first.jar", nested).stored())
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/over.jar", nested).stored())
                .build();
        ZipFixtureBuilder.Layout over = built.layouts().get(1);
        Path outer = write("first-over-boundary.jar",
                ZipFixtureBuilder.withByte(built.copy(), over.payloadOffset(), 0x00));
        CountingNestedIoHook hook = new CountingNestedIoHook();
        Path root = temporaryDirectory.resolve("first-over-boundary-root");

        PublicArtifactVerifier.Result result = verifier(hook, nested.length).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/over.jar: <nested-zip-limit-error>"), diagnostics(result));
        assertEquals(List.of(PublicArtifactVerifier.FailureCode.LIMIT_ERROR), codes(result));
        assertEquals(new OccurrenceZipArchive.OccurrenceId(1, over.localOffset()),
                result.findings().get(0).occurrenceId());
        assertEquals(1, hook.fileCreations);
        assertEquals(1, hook.writeAttempts);
        assertEquals(1, hook.readAttempts);
        assertFalse(Files.exists(root));
    }

    @Test
    void cumulativeLimitStopsBeforeOpeningOrDecompressingLaterOccurrences() throws Exception {
        byte[] nested = safeNestedJar();
        ZipFixtureBuilder.BuiltZip built = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first.jar", nested).stored())
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/over.jar", nested).stored())
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/later-corrupt.jar", nested).stored())
                .build();
        ZipFixtureBuilder.Layout over = built.layouts().get(1);
        ZipFixtureBuilder.Layout later = built.layouts().get(2);
        Path outer = write("later-occurrence.jar",
                ZipFixtureBuilder.withByte(built.copy(), later.payloadOffset(), 0x00));
        CountingNestedIoHook hook = new CountingNestedIoHook();
        Path root = temporaryDirectory.resolve("later-occurrence-root");

        PublicArtifactVerifier.Result result = verifier(hook, nested.length).verify(outer, root);

        assertEquals(List.of("BOOT-INF/lib/over.jar: <nested-zip-limit-error>"), diagnostics(result));
        assertEquals(List.of(PublicArtifactVerifier.FailureCode.LIMIT_ERROR), codes(result));
        assertEquals(new OccurrenceZipArchive.OccurrenceId(1, over.localOffset()),
                result.findings().get(0).occurrenceId());
        assertEquals(1, hook.fileCreations);
        assertEquals(1, hook.writeAttempts);
        assertEquals(1, hook.readAttempts);
        assertFalse(Files.exists(root));
    }

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
    void temp01PermissiveFileFailsClosedBeforeWriteAndRetainsCleanupFailure() throws Exception {
        Path outer = outerWithSafeNested("temp-permissive-file.jar");
        Path[] widenedFile = new Path[1];
        RecordingModeHook hook = new RecordingModeHook() {
            @Override
            public void afterFileCreated(Path file) throws IOException {
                widenedFile[0] = file;
                Files.setPosixFilePermissions(file, MODE_0644);
            }
        };
        Path root = temporaryDirectory.resolve("permissive-file-root");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertEquals(List.of(
                "BOOT-INF/lib/library.jar: <nested-temp-security-error>",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertFalse(hook.fileWriteAttempted);
        assertTrue(Files.exists(root));
        removeModeWidenedResidual(root, widenedFile[0]);
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
    void temp02DeleteModeWideningFailsSecurityAndCleanupWithoutLeakingPayloadOrPath() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder()
                .add("safe.txt", "delete-mode-secret-canary")
                .build();
        Path outer = write("temp-delete-mode.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", nested.copy()).stored())
                .build().copy());
        DeleteModeWideningHook hook = new DeleteModeWideningHook();
        Path root = temporaryDirectory.resolve("delete-mode-temp-path-canary");

        PublicArtifactVerifier.Result result = verifier(hook).verify(outer, root);

        assertFalse(result.accepted());
        assertEquals(List.of(
                PublicArtifactVerifier.FailureCode.TEMP_SECURITY_ERROR,
                PublicArtifactVerifier.FailureCode.TEMP_CLEANUP_ERROR), codes(result));
        assertEquals(List.of(
                "BOOT-INF/lib/library.jar: <nested-temp-security-error>",
                "BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        String output = String.join("\n", diagnostics(result));
        for (String forbidden : List.of(
                "delete-mode-secret-canary",
                "delete-mode-temp-path-canary",
                "Exception")) {
            assertFalse(output.contains(forbidden));
        }
        assertEquals(MODE_0644, Files.getPosixFilePermissions(hook.widenedFile));
        removeModeWidenedResidual(root, hook.widenedFile);
    }

    @Test
    void validUnsafeMemberNamesPreserveCodesAndUseFixedQualifiersAtCliBoundary() throws Exception {
        String configurationName = "BOOT-INF/classes/config/application prod.properties";
        String forbiddenName = "BOOT-INF/classes/证书 client.key";
        String nestedName = "BOOT-INF/lib/嵌套 temp.jar";
        String secret = "valid-name-secret-canary";
        String exception = "valid-name-delete-exception-canary";
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder().add("safe.txt", "safe").build();
        Path outer = write("valid-name.jar", new ZipFixtureBuilder()
                .add(configurationName, "api-key=" + secret + "\n")
                .add(forbiddenName, "synthetic-private-key")
                .add(new ZipFixtureBuilder.EntrySpec(nestedName, nested.copy()).stored())
                .build().copy());

        Path resultRoot = temporaryDirectory.resolve("valid-name-result-temp-path-canary");
        DeleteOnceHook resultHook = new DeleteOnceHook(exception);
        PublicArtifactVerifier.Result result = verifier(resultHook).verify(outer, resultRoot);

        assertFalse(result.accepted());
        assertEquals(List.of(
                PublicArtifactVerifier.FailureCode.API_KEY_VIOLATION,
                PublicArtifactVerifier.FailureCode.FORBIDDEN_MEMBER,
                PublicArtifactVerifier.FailureCode.TEMP_CLEANUP_ERROR), codes(result));
        List<String> expectedDiagnostics = List.of(
                "public-bootjar: api-key",
                "public-bootjar",
                "nested-temp: <nested-temp-cleanup-error>");
        assertEquals(expectedDiagnostics, diagnostics(result));
        assertTrue(resultHook.fileWasReopenableAtDelete);
        assertEquals(2, resultHook.fileDeleteAttempts);
        assertFalse(Files.exists(resultRoot));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Path cliRoot = temporaryDirectory.resolve("valid-name-cli-temp-path-canary");
        DeleteOnceHook cliHook = new DeleteOnceHook(exception);
        int exit;
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            exit = PublicArtifactVerifier.run(
                    new String[]{outer.toString(), cliRoot.toString()},
                    output,
                    verifier(cliHook));
        }

        assertEquals(1, exit);
        assertEquals(String.join("\n", expectedDiagnostics) + "\n", bytes.toString(StandardCharsets.UTF_8));
        assertTrue(cliHook.fileWasReopenableAtDelete);
        assertEquals(2, cliHook.fileDeleteAttempts);
        assertFalse(Files.exists(cliRoot));
        String output = bytes.toString(StandardCharsets.UTF_8);
        for (String forbidden : List.of(
                configurationName,
                forbiddenName,
                nestedName,
                secret,
                "synthetic-private-key",
                exception,
                resultRoot.toString(),
                cliRoot.toString(),
                "Exception")) {
            assertFalse(output.contains(forbidden));
        }
        assertFalse(output.chars().anyMatch(value -> value != '\n' && Character.isISOControl(value)));
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

    private PublicArtifactVerifier verifier(
            PublicArtifactVerifier.TempMutationHook hook,
            long cumulativeNestedJarLimit) {
        return new PublicArtifactVerifier(
                PublicArtifactVerifier.posixTempOperations(hook), cumulativeNestedJarLimit);
    }

    private Path outerWithSafeNested(String name) throws IOException {
        return write(name, new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", safeNestedJar()).stored())
                .build().copy());
    }

    private static byte[] safeNestedJar() {
        return new ZipFixtureBuilder().add("safe.txt", "safe").build().copy();
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

    private static final class CountingNestedIoHook implements PublicArtifactVerifier.TempMutationHook {
        private int fileCreations;
        private int writeAttempts;
        private int readAttempts;

        @Override
        public void afterFileCreated(Path file) {
            fileCreations++;
        }

        @Override
        public void beforeFileWrite(Path file) {
            writeAttempts++;
        }

        @Override
        public void beforeFileRead(Path file) {
            readAttempts++;
        }
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
                throw new IOException("directory-create-security-canary");
            }
        }

        @Override
        public void afterFileCreated(Path file) throws IOException {
            if (!directoryFailure) {
                throw new IOException("file-create-security-canary");
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

    private static void removeModeWidenedResidual(Path root, Path file) throws IOException {
        Files.delete(file);
        Files.delete(file.getParent());
        Files.delete(root);
    }

    private static final class DeleteModeWideningHook implements PublicArtifactVerifier.TempMutationHook {
        private Path widenedFile;
        private boolean widened;

        @Override
        public void beforeDelete(Path path) throws IOException {
            if (!widened && path.getFileName().toString().startsWith("nested-")) {
                widened = true;
                widenedFile = path;
                Files.setPosixFilePermissions(path, MODE_0644);
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
