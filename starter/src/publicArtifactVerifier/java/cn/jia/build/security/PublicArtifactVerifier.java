package cn.jia.build.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dependency-free verifier for public bootJar output. The command line emits only
 * value-redacted diagnostics and never propagates parser or filesystem messages.
 */
public final class PublicArtifactVerifier {
    static final long CONFIGURATION_LIMIT = 1L << 20;
    static final long NESTED_JAR_LIMIT = 256L << 20;
    static final long CUMULATIVE_NESTED_JAR_LIMIT = 2L << 30;

    public enum FailureCode {
        EOCD_ERROR("<nested-zip-eocd-error>"),
        CENTRAL_DIRECTORY_ERROR("<nested-zip-central-directory-error>"),
        LOCAL_HEADER_ERROR("<nested-zip-local-header-error>"),
        DATA_DESCRIPTOR_ERROR("<nested-zip-data-descriptor-error>"),
        OCCURRENCE_MISMATCH("<nested-zip-occurrence-mismatch>"),
        PAYLOAD_INTEGRITY_ERROR("<nested-zip-payload-integrity-error>"),
        UNSUPPORTED("<nested-zip-unsupported>"),
        LIMIT_ERROR("<nested-zip-limit-error>"),
        CONFIGURATION_PARSE_ERROR("<configuration-parse-error>"),
        API_KEY_VIOLATION("<api-key-violation>"),
        TEMP_SECURITY_ERROR("<nested-temp-security-error>"),
        TEMP_CLEANUP_ERROR("<nested-temp-cleanup-error>"),
        FORBIDDEN_MEMBER("<forbidden-member>"),
        INTERNAL_ERROR("<public-artifact-verifier-error>");

        private final String token;

        FailureCode(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    static final class VerificationException extends Exception {
        private final FailureCode code;

        VerificationException(FailureCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        FailureCode code() {
            return code;
        }
    }

    public record Finding(
            FailureCode code,
            String diagnostic,
            OccurrenceZipArchive.OccurrenceId occurrenceId) {
        public Finding {
            if (code == null || diagnostic == null || diagnostic.indexOf('\n') >= 0 || diagnostic.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("invalid-redacted-finding");
            }
        }
    }

    public record Result(List<Finding> findings) {
        public Result {
            findings = List.copyOf(findings);
        }

        public boolean accepted() {
            return findings.isEmpty();
        }
    }

    interface TempOperations {
        Path createPrivateDirectory(Path root) throws IOException, VerificationException;

        Path createPrivateFile(Path directory) throws IOException, VerificationException;

        void deleteFile(Path file) throws IOException;

        void deleteDirectory(Path directory) throws IOException;
    }

    private static final class PosixTempOperations implements TempOperations {
        private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Collections.unmodifiableSet(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        private static final Set<PosixFilePermission> FILE_PERMISSIONS = Collections.unmodifiableSet(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_ATTRIBUTE =
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
        private static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE =
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);

        @Override
        public Path createPrivateDirectory(Path root) throws IOException, VerificationException {
            Files.createDirectories(root);
            requirePosix(root);
            Path directory = Files.createTempDirectory(root, "public-artifact-verifier-", DIRECTORY_ATTRIBUTE);
            try {
                verify(directory, true, DIRECTORY_PERMISSIONS);
                return directory;
            } catch (IOException | VerificationException failure) {
                try {
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // The caller receives a fixed temp-security finding; no path is exposed.
                }
                throw failure;
            }
        }

        @Override
        public Path createPrivateFile(Path directory) throws IOException, VerificationException {
            verify(directory, true, DIRECTORY_PERMISSIONS);
            Path file = Files.createTempFile(directory, "nested-", ".jar", FILE_ATTRIBUTE);
            try {
                verify(file, false, FILE_PERMISSIONS);
                return file;
            } catch (IOException | VerificationException failure) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // The caller receives a fixed temp-security finding; no path is exposed.
                }
                throw failure;
            }
        }

        @Override
        public void deleteFile(Path file) throws IOException {
            Files.delete(file);
        }

        @Override
        public void deleteDirectory(Path directory) throws IOException {
            try (var entries = Files.newDirectoryStream(directory)) {
                if (entries.iterator().hasNext()) {
                    throw new IOException("residual-entry");
                }
            }
            Files.delete(directory);
        }

        private static void requirePosix(Path path) throws IOException, VerificationException {
            FileStore store = Files.getFileStore(path);
            if (!store.supportsFileAttributeView("posix")) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
        }

        private static void verify(Path path, boolean directory, Set<PosixFilePermission> expected)
                throws IOException, VerificationException {
            requirePosix(path);
            if (Files.isSymbolicLink(path)
                    || (directory && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    || (!directory && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    || !Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)
                    || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(currentUser())) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
        }

        private static UserPrincipal currentUser() throws IOException {
            UserPrincipalLookupService lookup = pathFileSystem().getUserPrincipalLookupService();
            return lookup.lookupPrincipalByName(System.getProperty("user.name", ""));
        }

        private static java.nio.file.FileSystem pathFileSystem() {
            return java.nio.file.FileSystems.getDefault();
        }
    }

    private final TempOperations tempOperations;

    public PublicArtifactVerifier() {
        this(new PosixTempOperations());
    }

    PublicArtifactVerifier(TempOperations tempOperations) {
        this.tempOperations = tempOperations;
    }

    public static void main(String[] args) {
        int exit = 1;
        try {
            if (args.length != 2) {
                System.out.println(FailureCode.INTERNAL_ERROR.token());
            } else {
                Result result = new PublicArtifactVerifier().verify(Path.of(args[0]), Path.of(args[1]));
                for (Finding finding : result.findings()) {
                    System.out.println(finding.diagnostic());
                }
                exit = result.accepted() ? 0 : 1;
            }
        } catch (Throwable ignored) {
            System.out.println(FailureCode.INTERNAL_ERROR.token());
        }
        if (exit != 0) {
            System.exit(exit);
        }
    }

    public Result verify(Path archive, Path temporaryRoot) {
        List<Finding> findings = new ArrayList<>();
        Path privateDirectory = null;
        String cleanupQualifier = "<nested-temp>";
        long cumulativeNestedBytes = 0;
        try (OccurrenceZipArchive outer = OccurrenceZipArchive.open(archive)) {
            for (OccurrenceZipArchive.Occurrence occurrence : outer.occurrences()) {
                String memberName = occurrence.name();
                String normalizedName = normalize(memberName);
                if (isForbiddenMember(memberName)) {
                    findings.add(new Finding(FailureCode.FORBIDDEN_MEMBER, memberName, occurrence.id()));
                }
                if (!occurrence.directory()
                        && normalizedName.startsWith("boot-inf/classes/")
                        && ConfigurationSecurityClassifier.isConfigurationResource(memberName)) {
                    classifyConfiguration(outer, occurrence, memberName, findings);
                    continue;
                }
                if (!occurrence.directory()
                        && normalizedName.startsWith("boot-inf/lib/")
                        && normalizedName.endsWith(".jar")) {
                    cleanupQualifier = memberName;
                    if (occurrence.uncompressedSize() > NESTED_JAR_LIMIT) {
                        findings.add(failure(memberName, FailureCode.LIMIT_ERROR, occurrence.id()));
                        continue;
                    }
                    if (cumulativeNestedBytes > CUMULATIVE_NESTED_JAR_LIMIT - occurrence.uncompressedSize()) {
                        findings.add(failure(memberName, FailureCode.LIMIT_ERROR, occurrence.id()));
                        outer.copyPayload(occurrence, OutputStream.nullOutputStream(), NESTED_JAR_LIMIT);
                        continue;
                    }
                    cumulativeNestedBytes += occurrence.uncompressedSize();
                    if (privateDirectory == null) {
                        try {
                            privateDirectory = tempOperations.createPrivateDirectory(temporaryRoot);
                        } catch (IOException | VerificationException failure) {
                            findings.add(failure(memberName, FailureCode.TEMP_SECURITY_ERROR, occurrence.id()));
                            outer.copyPayload(occurrence, OutputStream.nullOutputStream(), NESTED_JAR_LIMIT);
                            continue;
                        }
                    }
                    scanNestedOccurrence(outer, occurrence, privateDirectory, memberName, findings);
                    continue;
                }
                outer.copyPayload(occurrence, OutputStream.nullOutputStream(), occurrence.uncompressedSize());
            }
        } catch (VerificationException failure) {
            findings.add(failure("<public-bootjar>", failure.code(), null));
        } catch (IOException ignored) {
            findings.add(failure("<public-bootjar>", FailureCode.INTERNAL_ERROR, null));
        } finally {
            if (privateDirectory != null) {
                try {
                    tempOperations.deleteDirectory(privateDirectory);
                } catch (IOException ignored) {
                    findings.add(failure(cleanupQualifier, FailureCode.TEMP_CLEANUP_ERROR, null));
                }
            }
        }
        return new Result(findings);
    }

    private void scanNestedOccurrence(
            OccurrenceZipArchive outer,
            OccurrenceZipArchive.Occurrence outerOccurrence,
            Path privateDirectory,
            String outerMemberName,
            List<Finding> findings) throws VerificationException {
        Path nestedFile = null;
        boolean cleanupFailed = false;
        try {
            nestedFile = tempOperations.createPrivateFile(privateDirectory);
            try (OutputStream output = Files.newOutputStream(nestedFile)) {
                outer.copyPayload(outerOccurrence, output, NESTED_JAR_LIMIT);
            }
            try (OccurrenceZipArchive nested = OccurrenceZipArchive.open(nestedFile)) {
                for (OccurrenceZipArchive.Occurrence occurrence : nested.occurrences()) {
                    String qualifiedName = outerMemberName + "!" + occurrence.name();
                    if (isForbiddenMember(occurrence.name())) {
                        findings.add(new Finding(FailureCode.FORBIDDEN_MEMBER, qualifiedName, occurrence.id()));
                    }
                    if (!occurrence.directory()
                            && ConfigurationSecurityClassifier.isConfigurationResource(occurrence.name())) {
                        classifyConfiguration(nested, occurrence, qualifiedName, findings);
                    } else {
                        nested.copyPayload(occurrence, OutputStream.nullOutputStream(), NESTED_JAR_LIMIT);
                    }
                }
            } catch (VerificationException failure) {
                findings.add(failure(outerMemberName, failure.code(), outerOccurrence.id()));
            } catch (IOException ignored) {
                findings.add(failure(outerMemberName, FailureCode.INTERNAL_ERROR, outerOccurrence.id()));
            }
        } catch (VerificationException failure) {
            findings.add(failure(outerMemberName, failure.code(), outerOccurrence.id()));
        } catch (IOException ignored) {
            findings.add(failure(outerMemberName, FailureCode.TEMP_SECURITY_ERROR, outerOccurrence.id()));
        } finally {
            if (nestedFile != null) {
                try {
                    tempOperations.deleteFile(nestedFile);
                } catch (IOException ignored) {
                    cleanupFailed = true;
                }
            }
            if (cleanupFailed) {
                findings.add(failure(outerMemberName, FailureCode.TEMP_CLEANUP_ERROR, outerOccurrence.id()));
            }
        }
    }

    private static void classifyConfiguration(
            OccurrenceZipArchive archive,
            OccurrenceZipArchive.Occurrence occurrence,
            String qualifiedName,
            List<Finding> findings) throws VerificationException {
        ByteArrayOutputStream content = new ByteArrayOutputStream((int) Math.min(occurrence.uncompressedSize(), 8192));
        archive.copyPayload(occurrence, content, CONFIGURATION_LIMIT);
        try {
            List<ConfigurationSecurityClassifier.Match> matches =
                    ConfigurationSecurityClassifier.classify(qualifiedName, content.toByteArray());
            for (ConfigurationSecurityClassifier.Match match : matches) {
                findings.add(new Finding(
                        match.code(),
                        qualifiedName + ": " + match.key(),
                        occurrence.id()));
            }
        } catch (VerificationException failure) {
            if (failure.code() != FailureCode.CONFIGURATION_PARSE_ERROR) {
                throw failure;
            }
            findings.add(failure(qualifiedName, FailureCode.CONFIGURATION_PARSE_ERROR, occurrence.id()));
        }
    }

    private static Finding failure(
            String qualifier,
            FailureCode code,
            OccurrenceZipArchive.OccurrenceId occurrenceId) {
        return new Finding(code, qualifier + ": " + code.token(), occurrenceId);
    }

    static boolean isForbiddenMember(String memberName) {
        String normalized = normalize(memberName);
        int slash = normalized.lastIndexOf('/');
        String basename = normalized.substring(slash + 1);
        return basename.equals("application-dev.properties")
                || basename.equals("application-dev.yml")
                || basename.equals("application-dev.yaml")
                || normalized.endsWith(".p12")
                || normalized.endsWith(".pfx")
                || normalized.endsWith(".jks")
                || normalized.endsWith(".keystore")
                || normalized.endsWith(".pem")
                || normalized.endsWith(".key");
    }

    static String normalize(String value) {
        return value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
