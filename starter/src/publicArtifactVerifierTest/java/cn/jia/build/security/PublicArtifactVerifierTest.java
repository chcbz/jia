package cn.jia.build.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublicArtifactVerifierTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void temp01TempSecurityFailureOccursBeforeNestedFileCreation() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder().add("safe.txt", "safe").build();
        Path outer = write("temp-security.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", nested.copy()).stored())
                .build().copy());
        RejectingTempOperations temp = new RejectingTempOperations();

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier(temp)
                .verify(outer, temporaryDirectory.resolve("root"));

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-security-error>"), diagnostics(result));
        assertEquals(List.of(PublicArtifactVerifier.FailureCode.TEMP_SECURITY_ERROR), codes(result));
        assertFalse(temp.fileCreationAttempted);
    }

    @Test
    void temp02DeleteFailurePreventsAcceptanceAfterClosedStreams() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder().add("safe.txt", "safe").build();
        Path outer = write("temp-cleanup.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/library.jar", nested.copy()).stored())
                .build().copy());
        DeleteFailingTempOperations temp = new DeleteFailingTempOperations();

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier(temp)
                .verify(outer, temporaryDirectory.resolve("root"));

        assertEquals(List.of("BOOT-INF/lib/library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        assertEquals(List.of(PublicArtifactVerifier.FailureCode.TEMP_CLEANUP_ERROR), codes(result));
        assertTrue(temp.fileWasReopenableAtDelete);
    }

    @Test
    void e2e01FlatAndNestedForbiddenMembersAndApiKeysAreQualified() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder()
                .add("application-dev.properties", "fixture=true\n")
                .add("certificates/client.p12", "synthetic-container")
                .add("config/application.yaml", "provider:\n  api-key: live-secret\n")
                .build();
        Path outer = write("public.jar", new ZipFixtureBuilder()
                .add("BOOT-INF/classes/application-dev.properties", "fixture=true\n")
                .add("BOOT-INF/classes/application-prod.properties", "api-key=outer-secret\n")
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/first-party.jar", nested.copy()).deflated())
                .build().copy());

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier()
                .verify(outer, temporaryDirectory.resolve("verification-root"));

        assertEquals(List.of(
                "BOOT-INF/classes/application-dev.properties",
                "BOOT-INF/classes/application-prod.properties: api-key",
                "BOOT-INF/lib/first-party.jar!application-dev.properties",
                "BOOT-INF/lib/first-party.jar!certificates/client.p12",
                "BOOT-INF/lib/first-party.jar!config/application.yaml: api-key"), diagnostics(result));
    }

    @Test
    void e2e02DiagnosticsAreRedactedAcrossParseIntegrityAndCleanupFailures() throws Exception {
        ZipFixtureBuilder.BuiltZip nested = new ZipFixtureBuilder()
                .add("config/application.yaml", "api-key: adversarial-secret-canary\n")
                .build();
        byte[] corrupted = nested.copy();
        ZipFixtureBuilder.Layout layout = nested.layouts().get(0);
        corrupted[layout.payloadOffset()] ^= 1;
        Path outer = write("redaction.jar", new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("BOOT-INF/lib/canary-library.jar", corrupted).stored())
                .build().copy());

        PublicArtifactVerifier.Result result = new PublicArtifactVerifier(new DeleteFailingTempOperations())
                .verify(outer, temporaryDirectory.resolve("redaction-root"));

        assertEquals(List.of(
                "BOOT-INF/lib/canary-library.jar: <nested-zip-payload-integrity-error>",
                "BOOT-INF/lib/canary-library.jar: <nested-temp-cleanup-error>"), diagnostics(result));
        String output = String.join("\n", diagnostics(result));
        assertFalse(output.contains("adversarial-secret-canary"));
        assertFalse(output.contains("redaction-root"));
        assertFalse(output.contains("Exception"));
        assertFalse(output.contains("embedded-value"));
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

    private static final class RejectingTempOperations implements PublicArtifactVerifier.TempOperations {
        private boolean fileCreationAttempted;

        @Override
        public Path createPrivateDirectory(Path root) throws PublicArtifactVerifier.VerificationException {
            throw new PublicArtifactVerifier.VerificationException(
                    PublicArtifactVerifier.FailureCode.TEMP_SECURITY_ERROR);
        }

        @Override
        public Path createPrivateFile(Path directory) {
            fileCreationAttempted = true;
            throw new AssertionError("nested bytes must not be written");
        }

        @Override
        public void deleteFile(Path file) { }

        @Override
        public void deleteDirectory(Path directory) { }
    }

    private final class DeleteFailingTempOperations implements PublicArtifactVerifier.TempOperations {
        private boolean fileWasReopenableAtDelete;

        @Override
        public Path createPrivateDirectory(Path root) throws IOException {
            Files.createDirectories(root);
            return Files.createTempDirectory(root, "private-");
        }

        @Override
        public Path createPrivateFile(Path directory) throws IOException {
            return Files.createTempFile(directory, "nested-", ".jar");
        }

        @Override
        public void deleteFile(Path file) throws IOException {
            try (var channel = Files.newByteChannel(file)) {
                fileWasReopenableAtDelete = channel.isOpen();
            }
            Files.delete(file);
            throw new IOException("injected-delete-failure");
        }

        @Override
        public void deleteDirectory(Path directory) throws IOException {
            Files.delete(directory);
        }
    }
}
