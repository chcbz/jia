package cn.jia.build.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dependency-free verifier for public bootJar output. The command line emits only
 * value-redacted diagnostics and never propagates parser or filesystem messages.
 */
public final class PublicArtifactVerifier {
    static final long CONFIGURATION_LIMIT = 1L << 20;
    static final long NESTED_JAR_LIMIT = 256L << 20;
    static final long CUMULATIVE_NESTED_JAR_LIMIT = 2L << 30;

    private static final String PUBLIC_BOOTJAR_QUALIFIER = "public-bootjar";
    private static final String NESTED_TEMP_QUALIFIER = "nested-temp";
    private static final Pattern SAFE_QUALIFIER = Pattern.compile(
            "(?:<public-bootjar>|<nested-temp>|[-A-Za-z0-9_./!]+)");
    private static final Pattern SAFE_API_KEY = Pattern.compile(
            "[-A-Za-z0-9_.]*api-key[-A-Za-z0-9_.]*");

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
            if (code == null || diagnostic == null || !isSafeDiagnostic(code, diagnostic)) {
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

    /** Package-private mutation seam; production always uses the no-op implementation. */
    interface TempMutationHook {
        TempMutationHook NONE = new TempMutationHook() { };

        default void afterDirectoryCreated(Path directory) throws IOException { }

        default void afterDirectoryValidated(Path directory) throws IOException { }

        default void afterFileCreated(Path file) throws IOException { }

        default void beforeFileWrite(Path file) throws IOException { }

        default void beforeFileRead(Path file) throws IOException { }

        default void beforeDelete(Path path) throws IOException { }
    }

    interface TempOperations {
        PrivateDirectory createPrivateDirectory(Path root) throws TempCreationException;

        PrivateFile createPrivateFile(PrivateDirectory directory) throws TempCreationException;

        OutputStream openForWrite(PrivateFile file) throws IOException, VerificationException;

        OccurrenceZipArchive openForRead(PrivateFile file) throws IOException, VerificationException;

        void deleteFile(PrivateFile file) throws IOException, VerificationException;

        void deleteDirectory(PrivateDirectory directory) throws IOException, VerificationException;

        void deleteResidual(TempIdentity residual) throws IOException, VerificationException;
    }

    record TempIdentity(Path path, Object fileKey, boolean directory) { }

    record PrivateDirectory(TempIdentity directory, TempIdentity root, boolean deleteRoot) { }

    static final class PrivateFile {
        private final TempIdentity identity;
        private final FileChannel channel;
        private boolean outputOpened;
        private boolean outputClosed;
        private boolean channelTransferred;

        PrivateFile(TempIdentity identity, FileChannel channel) {
            this.identity = identity;
            this.channel = channel;
        }
    }

    static final class TempCreationException extends Exception {
        private final List<TempIdentity> residuals;
        private final boolean cleanupFailed;

        TempCreationException(List<TempIdentity> residuals, boolean cleanupFailed) {
            super(null, null, false, false);
            this.residuals = List.copyOf(residuals);
            this.cleanupFailed = cleanupFailed;
        }

        List<TempIdentity> residuals() {
            return residuals;
        }

        boolean cleanupFailed() {
            return cleanupFailed;
        }
    }

    /*
     * Threat boundary: same-UID build processes and trusted task-temp ancestry are TCB.
     * Pathname checks are fail-closed snapshots, not atomic resistance to hostile same-UID mutation.
     */
    static final class PosixTempOperations implements TempOperations {
        private static final int CREATE_ATTEMPTS = 32;
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Collections.unmodifiableSet(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
        private static final Set<PosixFilePermission> FILE_PERMISSIONS = Collections.unmodifiableSet(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        private static final FileAttribute<Set<PosixFilePermission>> DIRECTORY_ATTRIBUTE =
                PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS);
        private static final FileAttribute<Set<PosixFilePermission>> FILE_ATTRIBUTE =
                PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
        private static final Set<OpenOption> FILE_OPEN_OPTIONS = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);

        private final TempMutationHook hook;

        PosixTempOperations(TempMutationHook hook) {
            this.hook = hook;
        }

        @Override
        public PrivateDirectory createPrivateDirectory(Path suppliedRoot) throws TempCreationException {
            Path root = suppliedRoot.toAbsolutePath().normalize();
            List<TempIdentity> created = new ArrayList<>();
            try {
                boolean rootCreated = false;
                TempIdentity rootIdentity;
                try {
                    rootIdentity = capture(root, true);
                } catch (NoSuchFileException missing) {
                    Files.createDirectory(root, DIRECTORY_ATTRIBUTE);
                    rootCreated = true;
                    created.add(new TempIdentity(root, null, true));
                    rootIdentity = capture(root, true);
                    created.set(created.size() - 1, rootIdentity);
                }
                verifySame(rootIdentity, DIRECTORY_PERMISSIONS);

                Path directoryPath = createRandomDirectory(root);
                created.add(new TempIdentity(directoryPath, null, true));
                TempIdentity directoryIdentity = capture(directoryPath, true);
                created.set(created.size() - 1, directoryIdentity);
                hook.afterDirectoryCreated(directoryPath);
                verifySame(rootIdentity, DIRECTORY_PERMISSIONS);
                verifySame(directoryIdentity, DIRECTORY_PERMISSIONS);
                hook.afterDirectoryValidated(directoryPath);
                return new PrivateDirectory(directoryIdentity, rootIdentity, rootCreated);
            } catch (IOException | VerificationException | UnsupportedOperationException failure) {
                List<TempIdentity> residuals = rollback(created);
                throw new TempCreationException(residuals, !residuals.isEmpty());
            }
        }

        @Override
        public PrivateFile createPrivateFile(PrivateDirectory directory) throws TempCreationException {
            List<TempIdentity> created = new ArrayList<>();
            FileChannel channel = null;
            try {
                verifySame(directory.root(), DIRECTORY_PERMISSIONS);
                verifySame(directory.directory(), DIRECTORY_PERMISSIONS);
                Path filePath = randomChild(directory.directory().path(), "nested-", ".jar");
                for (int attempt = 0; ; attempt++) {
                    try {
                        channel = FileChannel.open(filePath, FILE_OPEN_OPTIONS, FILE_ATTRIBUTE);
                        break;
                    } catch (FileAlreadyExistsException collision) {
                        if (attempt + 1 >= CREATE_ATTEMPTS) {
                            throw collision;
                        }
                        filePath = randomChild(directory.directory().path(), "nested-", ".jar");
                    }
                }
                created.add(new TempIdentity(filePath, null, false));
                TempIdentity identity = capture(filePath, false);
                created.set(created.size() - 1, identity);
                hook.afterFileCreated(filePath);
                verifySame(directory.directory(), DIRECTORY_PERMISSIONS);
                verifySame(identity, FILE_PERMISSIONS);
                return new PrivateFile(identity, channel);
            } catch (IOException | VerificationException | UnsupportedOperationException failure) {
                boolean closeFailed = false;
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException ignored) {
                        closeFailed = true;
                    }
                }
                List<TempIdentity> residuals = rollback(created);
                throw new TempCreationException(residuals, closeFailed || !residuals.isEmpty());
            }
        }

        @Override
        public OutputStream openForWrite(PrivateFile file) throws IOException, VerificationException {
            if (file.outputOpened || file.outputClosed || file.channelTransferred || !file.channel.isOpen()) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
            hook.beforeFileWrite(file.identity.path());
            verifySame(file.identity, FILE_PERMISSIONS);
            file.outputOpened = true;
            return new IdentityBoundOutputStream(file);
        }

        @Override
        public OccurrenceZipArchive openForRead(PrivateFile file) throws IOException, VerificationException {
            if (!file.outputOpened || !file.outputClosed || file.channelTransferred || !file.channel.isOpen()) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
            hook.beforeFileRead(file.identity.path());
            verifySame(file.identity, FILE_PERMISSIONS);
            file.channelTransferred = true;
            return OccurrenceZipArchive.open(file.channel);
        }

        @Override
        public void deleteFile(PrivateFile file) throws IOException, VerificationException {
            if (file.channel.isOpen()) {
                file.channel.close();
            }
            deleteExact(file.identity, false);
        }

        @Override
        public void deleteDirectory(PrivateDirectory directory) throws IOException, VerificationException {
            deleteExact(directory.directory(), true);
            if (directory.deleteRoot()) {
                deleteExact(directory.root(), true);
            }
        }

        @Override
        public void deleteResidual(TempIdentity residual) throws IOException, VerificationException {
            deleteExact(residual, residual.directory());
        }

        private Path createRandomDirectory(Path root) throws IOException {
            FileAlreadyExistsException lastCollision = null;
            for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
                Path candidate = randomChild(root, "public-artifact-verifier-", "");
                try {
                    return Files.createDirectory(candidate, DIRECTORY_ATTRIBUTE);
                } catch (FileAlreadyExistsException collision) {
                    lastCollision = collision;
                }
            }
            throw lastCollision == null ? new IOException("temp-directory-create") : lastCollision;
        }

        private static Path randomChild(Path parent, String prefix, String suffix) {
            return parent.resolve(prefix + Long.toUnsignedString(RANDOM.nextLong(), 16)
                    + Long.toUnsignedString(RANDOM.nextLong(), 16) + suffix);
        }

        private List<TempIdentity> rollback(List<TempIdentity> created) {
            List<TempIdentity> residuals = new ArrayList<>();
            for (int index = created.size() - 1; index >= 0; index--) {
                TempIdentity identity = created.get(index);
                try {
                    deleteExact(identity, identity.directory());
                } catch (IOException | VerificationException ignored) {
                    residuals.add(identity);
                }
            }
            return residuals;
        }

        private void deleteExact(TempIdentity identity, boolean requireEmpty)
                throws IOException, VerificationException {
            Set<PosixFilePermission> expected = identity.directory()
                    ? DIRECTORY_PERMISSIONS
                    : FILE_PERMISSIONS;
            verifySame(identity, expected);
            if (requireEmpty) {
                try (var entries = Files.newDirectoryStream(identity.path())) {
                    if (entries.iterator().hasNext()) {
                        throw new IOException("residual-entry");
                    }
                }
                verifySame(identity, expected);
            }
            hook.beforeDelete(identity.path());
            verifySame(identity, expected);
            Files.delete(identity.path());
        }

        private static TempIdentity capture(Path path, boolean directory)
                throws IOException, VerificationException {
            PosixFileAttributes attributes = readAttributes(path);
            if (attributes.isSymbolicLink()
                    || directory && !attributes.isDirectory()
                    || !directory && !attributes.isRegularFile()
                    || attributes.fileKey() == null) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
            return new TempIdentity(path, attributes.fileKey(), directory);
        }

        private static void verifySame(TempIdentity identity, Set<PosixFilePermission> expected)
                throws IOException, VerificationException {
            PosixFileAttributes attributes = readAttributes(identity.path());
            if (attributes.isSymbolicLink()
                    || identity.directory() && !attributes.isDirectory()
                    || !identity.directory() && !attributes.isRegularFile()
                    || attributes.fileKey() == null
                    || !attributes.fileKey().equals(identity.fileKey())
                    || !attributes.permissions().equals(expected)
                    || !attributes.owner().equals(currentUser())) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
        }

        private static PosixFileAttributes readAttributes(Path path)
                throws IOException, VerificationException {
            PosixFileAttributes attributes = Files.readAttributes(
                    path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            FileStore store = Files.getFileStore(path);
            if (!store.supportsFileAttributeView("posix")) {
                throw new VerificationException(FailureCode.TEMP_SECURITY_ERROR);
            }
            return attributes;
        }

        private static UserPrincipal currentUser() throws IOException {
            UserPrincipalLookupService lookup = pathFileSystem().getUserPrincipalLookupService();
            return lookup.lookupPrincipalByName(System.getProperty("user.name", ""));
        }

        private static java.nio.file.FileSystem pathFileSystem() {
            return java.nio.file.FileSystems.getDefault();
        }

        private static final class IdentityBoundOutputStream extends OutputStream {
            private final PrivateFile file;
            private boolean closed;

            private IdentityBoundOutputStream(PrivateFile file) {
                this.file = file;
            }

            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                if (closed) {
                    throw new IOException("closed-output");
                }
                ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
                while (buffer.hasRemaining()) {
                    if (file.channel.write(buffer) <= 0) {
                        throw new IOException("short-temp-write");
                    }
                }
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    file.channel.force(true);
                    file.outputClosed = true;
                }
            }
        }
    }

    private final TempOperations tempOperations;

    public PublicArtifactVerifier() {
        this(new PosixTempOperations(TempMutationHook.NONE));
    }

    PublicArtifactVerifier(TempOperations tempOperations) {
        this.tempOperations = tempOperations;
    }

    static TempOperations posixTempOperations(TempMutationHook hook) {
        return new PosixTempOperations(hook);
    }

    public static void main(String[] args) {
        int exit = run(args, System.out);
        if (exit != 0) {
            System.exit(exit);
        }
    }

    static int run(String[] args, PrintStream output) {
        return run(args, output, new PublicArtifactVerifier());
    }

    static int run(String[] args, PrintStream output, PublicArtifactVerifier verifier) {
        try {
            if (args.length != 2) {
                output.println(FailureCode.INTERNAL_ERROR.token());
                return 1;
            }
            Result result = verifier.verify(Path.of(args[0]), Path.of(args[1]));
            for (Finding finding : result.findings()) {
                output.println(finding.diagnostic());
            }
            return result.accepted() ? 0 : 1;
        } catch (Throwable ignored) {
            output.println(FailureCode.INTERNAL_ERROR.token());
            return 1;
        }
    }

    public Result verify(Path archive, Path temporaryRoot) {
        List<Finding> findings = new ArrayList<>();
        List<TempIdentity> residuals = new ArrayList<>();
        PrivateDirectory privateDirectory = null;
        String cleanupQualifier = NESTED_TEMP_QUALIFIER;
        long cumulativeNestedBytes = 0;
        try (OccurrenceZipArchive outer = OccurrenceZipArchive.open(archive)) {
            for (OccurrenceZipArchive.Occurrence occurrence : outer.occurrences()) {
                String memberName = occurrence.name();
                String normalizedName = normalize(memberName);
                if (isForbiddenMember(memberName)) {
                    findings.add(forbidden(memberName, occurrence.id()));
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
                    cleanupQualifier = safeQualifier(memberName, NESTED_TEMP_QUALIFIER);
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
                        } catch (TempCreationException failure) {
                            findings.add(failure(memberName, FailureCode.TEMP_SECURITY_ERROR, occurrence.id()));
                            recordCreationCleanupFailure(
                                    failure, memberName, occurrence.id(), findings, residuals);
                            outer.copyPayload(occurrence, OutputStream.nullOutputStream(), NESTED_JAR_LIMIT);
                            continue;
                        }
                    }
                    scanNestedOccurrence(
                            outer, occurrence, privateDirectory, memberName, findings, residuals);
                    continue;
                }
                outer.copyPayload(occurrence, OutputStream.nullOutputStream(), occurrence.uncompressedSize());
            }
        } catch (VerificationException failure) {
            findings.add(failure("<public-bootjar>", failure.code(), null));
        } catch (IOException ignored) {
            findings.add(failure("<public-bootjar>", FailureCode.INTERNAL_ERROR, null));
        } finally {
            for (TempIdentity residual : residuals) {
                try {
                    tempOperations.deleteResidual(residual);
                } catch (IOException | VerificationException ignored) {
                    addOnce(findings, failure(cleanupQualifier, FailureCode.TEMP_CLEANUP_ERROR, null));
                }
            }
            if (privateDirectory != null) {
                try {
                    tempOperations.deleteDirectory(privateDirectory);
                } catch (VerificationException ignored) {
                    addOnce(findings, failure(cleanupQualifier, FailureCode.TEMP_SECURITY_ERROR, null));
                    addOnce(findings, failure(cleanupQualifier, FailureCode.TEMP_CLEANUP_ERROR, null));
                } catch (IOException ignored) {
                    addOnce(findings, failure(cleanupQualifier, FailureCode.TEMP_CLEANUP_ERROR, null));
                }
            }
        }
        return new Result(findings);
    }

    private void scanNestedOccurrence(
            OccurrenceZipArchive outer,
            OccurrenceZipArchive.Occurrence outerOccurrence,
            PrivateDirectory privateDirectory,
            String outerMemberName,
            List<Finding> findings,
            List<TempIdentity> residuals) throws VerificationException {
        PrivateFile nestedFile = null;
        try {
            try {
                nestedFile = tempOperations.createPrivateFile(privateDirectory);
            } catch (TempCreationException failure) {
                findings.add(failure(outerMemberName, FailureCode.TEMP_SECURITY_ERROR, outerOccurrence.id()));
                recordCreationCleanupFailure(
                        failure, outerMemberName, outerOccurrence.id(), findings, residuals);
                return;
            }
            try (OutputStream output = tempOperations.openForWrite(nestedFile)) {
                outer.copyPayload(outerOccurrence, output, NESTED_JAR_LIMIT);
            }
            try (OccurrenceZipArchive nested = tempOperations.openForRead(nestedFile)) {
                for (OccurrenceZipArchive.Occurrence occurrence : nested.occurrences()) {
                    String qualifiedName = outerMemberName + "!" + occurrence.name();
                    if (isForbiddenMember(occurrence.name())) {
                        findings.add(forbidden(qualifiedName, occurrence.id()));
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
                } catch (VerificationException ignored) {
                    if (!residuals.contains(nestedFile.identity)) {
                        residuals.add(nestedFile.identity);
                    }
                    addOnce(findings, failure(
                            outerMemberName, FailureCode.TEMP_SECURITY_ERROR, outerOccurrence.id()));
                    addOnce(findings, failure(
                            outerMemberName, FailureCode.TEMP_CLEANUP_ERROR, outerOccurrence.id()));
                } catch (IOException ignored) {
                    if (!residuals.contains(nestedFile.identity)) {
                        residuals.add(nestedFile.identity);
                    }
                    addOnce(findings, failure(
                            outerMemberName, FailureCode.TEMP_CLEANUP_ERROR, outerOccurrence.id()));
                }
            }
        }
    }

    private static void recordCreationCleanupFailure(
            TempCreationException failure,
            String qualifier,
            OccurrenceZipArchive.OccurrenceId occurrenceId,
            List<Finding> findings,
            List<TempIdentity> residuals) {
        residuals.addAll(failure.residuals());
        if (failure.cleanupFailed()) {
            addOnce(findings, failure(qualifier, FailureCode.TEMP_CLEANUP_ERROR, occurrenceId));
        }
    }

    private static void classifyConfiguration(
            OccurrenceZipArchive archive,
            OccurrenceZipArchive.Occurrence occurrence,
            String qualifiedName,
            List<Finding> findings) throws IOException, VerificationException {
        ByteArrayOutputStream content = new ByteArrayOutputStream((int) Math.min(occurrence.uncompressedSize(), 8192));
        archive.copyPayload(occurrence, content, CONFIGURATION_LIMIT);
        String diagnosticQualifier = safeQualifier(qualifiedName, PUBLIC_BOOTJAR_QUALIFIER);
        try {
            List<ConfigurationSecurityClassifier.Match> matches =
                    ConfigurationSecurityClassifier.classify(qualifiedName, content.toByteArray());
            for (ConfigurationSecurityClassifier.Match match : matches) {
                if (!SAFE_API_KEY.matcher(match.key()).matches()) {
                    findings.add(failure(
                            diagnosticQualifier, FailureCode.CONFIGURATION_PARSE_ERROR, occurrence.id()));
                    return;
                }
                findings.add(new Finding(
                        match.code(),
                        diagnosticQualifier + ": " + match.key(),
                        occurrence.id()));
            }
        } catch (VerificationException failure) {
            if (failure.code() != FailureCode.CONFIGURATION_PARSE_ERROR) {
                throw failure;
            }
            findings.add(failure(
                    diagnosticQualifier, FailureCode.CONFIGURATION_PARSE_ERROR, occurrence.id()));
        }
    }

    private static Finding forbidden(
            String qualifier,
            OccurrenceZipArchive.OccurrenceId occurrenceId) {
        return new Finding(
                FailureCode.FORBIDDEN_MEMBER,
                safeQualifier(qualifier, PUBLIC_BOOTJAR_QUALIFIER),
                occurrenceId);
    }

    private static Finding failure(
            String qualifier,
            FailureCode code,
            OccurrenceZipArchive.OccurrenceId occurrenceId) {
        String fallback = code == FailureCode.TEMP_SECURITY_ERROR || code == FailureCode.TEMP_CLEANUP_ERROR
                ? NESTED_TEMP_QUALIFIER
                : PUBLIC_BOOTJAR_QUALIFIER;
        return new Finding(code, safeQualifier(qualifier, fallback) + ": " + code.token(), occurrenceId);
    }

    private static Finding internal(OccurrenceZipArchive.OccurrenceId occurrenceId) {
        return new Finding(FailureCode.INTERNAL_ERROR, FailureCode.INTERNAL_ERROR.token(), occurrenceId);
    }

    private static void addOnce(List<Finding> findings, Finding candidate) {
        for (Finding existing : findings) {
            if (existing.code() == candidate.code()
                    && existing.diagnostic().equals(candidate.diagnostic())) {
                return;
            }
        }
        findings.add(candidate);
    }

    private static boolean isSafeDiagnostic(FailureCode code, String diagnostic) {
        if (diagnostic.equals(code.token())) {
            return code == FailureCode.INTERNAL_ERROR;
        }
        if (code == FailureCode.FORBIDDEN_MEMBER) {
            return isSafeQualifier(diagnostic);
        }
        int separator = diagnostic.lastIndexOf(": ");
        if (separator < 0 || !isSafeQualifier(diagnostic.substring(0, separator))) {
            return false;
        }
        String detail = diagnostic.substring(separator + 2);
        return code == FailureCode.API_KEY_VIOLATION
                ? SAFE_API_KEY.matcher(detail).matches()
                : detail.equals(code.token());
    }

    private static String safeQualifier(String qualifier, String fallback) {
        return isSafeQualifier(qualifier) ? qualifier : fallback;
    }

    private static boolean isSafeQualifier(String qualifier) {
        return qualifier != null && SAFE_QUALIFIER.matcher(qualifier).matches();
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
