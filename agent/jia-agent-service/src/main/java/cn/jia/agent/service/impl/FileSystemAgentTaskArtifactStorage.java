package cn.jia.agent.service.impl;

import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.exception.AgentTaskArtifactStorageException.Reason;
import cn.jia.agent.service.AgentTaskArtifactStorage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact-scope, content-addressed, immutable private file store. Object URIs never expose the local
 * root. Final objects are created with an atomic hard-link create-if-absent operation and are never
 * replaced. Temporary files use the private {@code .upload-*.tmp} namespace and are deleted on a
 * best-effort basis; a cleanup failure leaves no URI-addressable object and is safely reclaimable by
 * prefix and age after active writers have stopped. A final object that precedes a database rollback
 * is intentionally retained as a harmless immutable orphan: the same scoped digest can be reused,
 * and a future collector can delete unreferenced digests after scanning artifact rows.
 */
public final class FileSystemAgentTaskArtifactStorage implements AgentTaskArtifactStorage {
    public static final String URI_SCHEME = "cyf-artifact";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME = Pattern.compile(
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/[a-z0-9][a-z0-9!#$&^_.+-]{0,63}");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ);
    private static final long MAX_CONFIGURED_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_INITIAL_READ_BUFFER_BYTES = 64 * 1024;

    private final Path root;
    private final Object rootFileKey;
    private final long maxContentBytes;

    public FileSystemAgentTaskArtifactStorage(
            Path root, long maxContentBytes) {
        if (root == null || !root.isAbsolute() || root.normalize().getParent() == null) {
            throw invalid("Artifact storage root must be an absolute non-filesystem-root path");
        }
        if (maxContentBytes < 1 || maxContentBytes > MAX_CONFIGURED_BYTES) {
            throw invalid("Artifact storage size limit is invalid");
        }
        this.root = initializeRoot(root.normalize());
        setPermissions(this.root, DIRECTORY_PERMISSIONS);
        this.rootFileKey = attributes(this.root).fileKey();
        this.maxContentBytes = maxContentBytes;
    }

    @Override
    public StoredObject store(Scope scope, byte[] content, String mimeType) {
        requireScope(scope);
        String canonicalMime = requireMimeSyntax(mimeType);
        if (content == null) {
            throw invalid("Managed artifact content is required");
        }
        if (content.length > maxContentBytes) {
            throw invalid("Managed artifact content exceeds the configured limit");
        }
        verifyRootIdentity();
        String scopeKey = scopeKey(scope);
        String contentHash = sha256(content);
        Path directory = ensureObjectDirectory(scopeKey, contentHash);
        Path target = objectPath(directory, contentHash);
        boolean created = false;
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExisting(target, contentHash, content.length);
        } else {
            Path temporary = null;
            try {
                temporary = Files.createTempFile(directory, ".upload-", ".tmp");
                rejectSymbolicLink(temporary);
                writeAndSync(temporary, content);
                makeReadOnly(temporary);
                try {
                    Files.createLink(target, temporary);
                    created = true;
                } catch (FileAlreadyExistsException raced) {
                    verifyExisting(target, contentHash, content.length);
                }
            } catch (AgentTaskArtifactStorageException failure) {
                throw failure;
            } catch (IOException failure) {
                throw ioFailure(failure);
            } finally {
                deleteTemporary(temporary);
            }
            verifyExisting(target, contentHash, content.length);
        }
        verifyRootIdentity();
        return new StoredObject(storageUri(scopeKey, contentHash), contentHash,
                content.length, canonicalMime, created);
    }

    @Override
    public StoredObject storeStream(Scope scope, java.io.InputStream content, long byteLength, String mimeType) {
        requireScope(scope); String canonicalMime = requireMimeSyntax(mimeType);
        if (content == null || byteLength < 0) throw invalid("Managed artifact content is required");
        verifyRootIdentity(); Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            rejectSymbolicLink(temporary);
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (FileChannel channel = FileChannel.open(temporary,
                    Set.<OpenOption>of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                byte[] buffer = new byte[8192];
                for (int n; (n = content.read(buffer)) != -1;) {
                    if (n > byteLength - count) throw invalid("Managed artifact length mismatch");
                    count += n; digest.update(buffer, 0, n);
                    ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, n);
                    while (bytes.hasRemaining()) channel.write(bytes);
                }
                if (count != byteLength) throw invalid("Managed artifact length mismatch");
                channel.force(true);
            }
            String hash = HexFormat.of().formatHex(digest.digest()); String key = scopeKey(scope);
            Path target = objectPath(ensureObjectDirectory(key, hash), hash);
            makeReadOnly(temporary); boolean created = false;
            try { Files.createLink(target, temporary); created = true; }
            catch (FileAlreadyExistsException raced) { /* Verify the existing immutable object below. */ }
            verifyExisting(target, hash, count); verifyRootIdentity();
            return new StoredObject(storageUri(key, hash), hash, count, canonicalMime, created);
        } catch (IOException failure) { throw ioFailure(failure); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        finally { deleteTemporary(temporary); }
    }

    @Override
    public java.io.InputStream openStream(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType) {
        requireScope(scope); requireMimeSyntax(expectedMimeType);
        if (expectedByteLength < 0
                || !SHA256.matcher(Objects.requireNonNullElse(expectedSha256, "")).matches())
            throw invalid("Managed artifact read expectations are invalid");
        verifyRootIdentity(); UriParts parts = parseOwnedUri(storageUri);
        if (!constantTimeEquals(scopeKey(scope), parts.scopeKey())
                || !constantTimeEquals(expectedSha256, parts.contentHash()))
            throw corrupt("Managed artifact URI does not match its exact scope and digest");
        Path target = objectPath(requireObjectDirectory(parts.scopeKey(), parts.contentHash()), parts.contentHash());
        rejectSymbolicLink(target);
        try {
            FileChannel channel = FileChannel.open(target,
                    Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            try {
                verifyChannel(channel, expectedSha256, expectedByteLength);
                verifyRootIdentity(); channel.position(0);
                return java.nio.channels.Channels.newInputStream(channel);
            } catch (IOException | RuntimeException failure) {
                try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        } catch (IOException failure) { throw ioFailure(failure); }
    }

    @Override
    public void copyTo(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType, java.io.OutputStream output) {
        try (var input = openStream(scope, storageUri, expectedSha256, expectedByteLength, expectedMimeType)) {
            input.transferTo(output);
        } catch (IOException failure) { throw ioFailure(failure); }
    }

    private static void verifyChannel(FileChannel channel, String hash, long length) throws IOException {
        if (channel.size() != length) throw corrupt("Managed artifact length mismatch");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(8192); long count = 0;
            while (channel.read(buffer) != -1) {
                buffer.flip(); count += buffer.remaining(); digest.update(buffer); buffer.clear();
            }
            if (count != length || !constantTimeEquals(HexFormat.of().formatHex(digest.digest()), hash))
                throw corrupt("Managed artifact content failed integrity verification");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @Override
    public StoredContent read(Scope scope, String storageUri, String expectedSha256,
            long expectedByteLength, String expectedMimeType) {
        requireScope(scope);
        if (!SHA256.matcher(Objects.requireNonNullElse(expectedSha256, "")).matches()
                || expectedByteLength < 0 || expectedByteLength > maxContentBytes) {
            throw invalid("Managed artifact read expectations are invalid");
        }
        String canonicalMime = requireMimeSyntax(expectedMimeType);
        verifyRootIdentity();
        UriParts parts = parseOwnedUri(storageUri);
        String exactScopeKey = scopeKey(scope);
        if (!constantTimeEquals(exactScopeKey, parts.scopeKey())
                || !constantTimeEquals(expectedSha256, parts.contentHash())) {
            throw corrupt("Managed artifact URI does not match its exact scope and digest");
        }
        Path directory = requireObjectDirectory(parts.scopeKey(), parts.contentHash());
        Path target = objectPath(directory, parts.contentHash());
        byte[] content = readBounded(target);
        String actualHash = sha256(content);
        if (!constantTimeEquals(actualHash, expectedSha256)
                || content.length != expectedByteLength) {
            throw corrupt("Managed artifact content failed integrity verification");
        }
        verifyRootIdentity();
        return new StoredContent(content, actualHash, content.length, canonicalMime);
    }

    @Override
    public boolean owns(String storageUri) {
        return isOwnedUri(storageUri);
    }

    @Override
    public boolean matches(Scope scope, String storageUri, String expectedSha256) {
        return matchesReference(scope, storageUri, expectedSha256);
    }

    public static boolean isOwnedUri(String storageUri) {
        if (storageUri == null) {
            return false;
        }
        try {
            parseOwnedUri(storageUri);
            return true;
        } catch (AgentTaskArtifactStorageException invalid) {
            return false;
        }
    }

    public static boolean matchesReference(
            Scope scope, String storageUri, String expectedSha256) {
        try {
            requireScope(scope);
            if (!SHA256.matcher(Objects.requireNonNullElse(expectedSha256, "")).matches()) {
                return false;
            }
            UriParts parts = parseOwnedUri(storageUri);
            return constantTimeEquals(scopeKey(scope), parts.scopeKey())
                    && constantTimeEquals(expectedSha256, parts.contentHash());
        } catch (AgentTaskArtifactStorageException invalid) {
            return false;
        }
    }

    private Path ensureObjectDirectory(String scopeKey, String contentHash) {
        return ensureDirectory(objectDirectory(scopeKey, contentHash));
    }

    private Path requireObjectDirectory(String scopeKey, String contentHash) {
        Path directory = objectDirectory(scopeKey, contentHash);
        verifyRootIdentity();
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            BasicFileAttributes currentAttributes = attributes(current);
            if (currentAttributes.isSymbolicLink() || !currentAttributes.isDirectory()) {
                throw corrupt("Managed artifact directory is not a private directory");
            }
        }
        return directory;
    }

    private Path objectDirectory(String scopeKey, String contentHash) {
        Path directory = root.resolve("v1")
                .resolve(scopeKey.substring(0, 2))
                .resolve(scopeKey)
                .resolve(contentHash.substring(0, 2))
                .normalize();
        if (!directory.startsWith(root)) {
            throw corrupt("Managed artifact path escaped its configured root");
        }
        return directory;
    }

    private Path objectPath(Path directory, String contentHash) {
        Path target = directory.resolve(contentHash).normalize();
        if (!target.startsWith(root) || !target.getParent().equals(directory)) {
            throw corrupt("Managed artifact path escaped its configured root");
        }
        return target;
    }

    private Path ensureDirectory(Path directory) {
        verifyRootIdentity();
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = attributes(current);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw corrupt("Managed artifact directory is not a private directory");
                }
                setPermissions(current, DIRECTORY_PERMISSIONS);
            } else {
                try {
                    Files.createDirectory(current);
                    setPermissions(current, DIRECTORY_PERMISSIONS);
                } catch (FileAlreadyExistsException raced) {
                    BasicFileAttributes attributes = attributes(current);
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw corrupt("Managed artifact directory is not a private directory");
                    }
                    setPermissions(current, DIRECTORY_PERMISSIONS);
                } catch (IOException failure) {
                    throw ioFailure(failure);
                }
            }
        }
        return directory;
    }

    private static Path initializeRoot(Path root) {
        Path current = root.getRoot();
        if (current == null) {
            throw invalid("Artifact storage root must be absolute");
        }
        for (Path component : root) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = attributes(current);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw invalid("Artifact storage root contains a non-directory component");
                }
            } else {
                try {
                    Files.createDirectory(current);
                    setPermissions(current, DIRECTORY_PERMISSIONS);
                } catch (FileAlreadyExistsException raced) {
                    BasicFileAttributes attributes = attributes(current);
                    if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                        throw invalid("Artifact storage root contains a non-directory component");
                    }
                } catch (IOException failure) {
                    throw new AgentTaskArtifactStorageException(
                            Reason.IO_FAILURE, "Artifact storage root could not be initialized", failure);
                }
            }
        }
        return root;
    }

    private void verifyRootIdentity() {
        BasicFileAttributes current = attributes(root);
        if (current.isSymbolicLink() || !current.isDirectory()
                || rootFileKey != null && !Objects.equals(rootFileKey, current.fileKey())) {
            throw corrupt("Artifact storage root identity changed");
        }
    }

    private void verifyExisting(Path target, String expectedHash, long expectedLength) {
        rejectSymbolicLink(target);
        try (FileChannel channel = FileChannel.open(target,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            verifyChannel(channel, expectedHash, expectedLength);
        } catch (IOException failure) { throw ioFailure(failure); }
        makeReadOnly(target);
    }

    private byte[] readBounded(Path target) {
        BasicFileAttributes initial = attributes(target);
        if (initial.isSymbolicLink() || !initial.isRegularFile()) {
            throw corrupt("Managed artifact object is not an immutable regular file");
        }
        if (initial.size() > maxContentBytes) {
            throw corrupt("Managed artifact object exceeds the configured limit");
        }
        try (FileChannel channel = FileChannel.open(target,
                Set.<OpenOption>of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream result = new ByteArrayOutputStream(
                    (int) Math.min(initial.size(), MAX_INITIAL_READ_BUFFER_BYTES));
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            long total = 0;
            while (channel.read(buffer) != -1) {
                buffer.flip();
                int count = buffer.remaining();
                total += count;
                if (total > maxContentBytes) {
                    throw corrupt("Managed artifact object exceeds the configured limit");
                }
                result.write(buffer.array(), buffer.position(), count);
                buffer.clear();
            }
            return result.toByteArray();
        } catch (AgentTaskArtifactStorageException failure) {
            throw failure;
        } catch (IOException failure) {
            throw ioFailure(failure);
        }
    }

    private static void writeAndSync(Path temporary, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(temporary,
                Set.<OpenOption>of(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
    }

    private static void makeReadOnly(Path target) {
        setPermissions(target, FILE_PERMISSIONS);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException unsupported) {
            throw new AgentTaskArtifactStorageException(
                    Reason.IO_FAILURE, "Artifact storage requires POSIX permission support",
                    unsupported);
        } catch (IOException failure) {
            throw new AgentTaskArtifactStorageException(
                    Reason.IO_FAILURE, "Artifact storage permissions could not be secured", failure);
        }
    }

    private static void rejectSymbolicLink(Path path) {
        BasicFileAttributes attributes = attributes(path);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw corrupt("Managed artifact temporary object is invalid");
        }
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new AgentTaskArtifactStorageException(
                    Reason.IO_FAILURE, "Artifact storage object is unavailable", failure);
        }
    }

    private static void deleteTemporary(Path temporary) {
        if (temporary == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
            // Private .upload-*.tmp residues are never returned and are reclaimable by prefix/age.
        }
    }

    private static String requireMimeSyntax(String mimeType) {
        if (mimeType == null || !MIME.matcher(mimeType).matches()) {
            throw invalid("Managed artifact MIME type is invalid");
        }
        return mimeType;
    }

    private static void requireScope(Scope scope) {
        if (scope == null) {
            throw invalid("Artifact storage scope is required");
        }
        requireExactIdentifier(scope.tenantId(), 50);
        requireExactIdentifier(scope.clientId(), 50);
        requireExactIdentifier(scope.ownerJiacn(), 50);
        if ("0".equals(scope.ownerJiacn())) {
            throw invalid("Artifact storage owner is invalid");
        }
        requireExactIdentifier(scope.taskId(), 100);
    }

    private static void requireExactIdentifier(String value, int maxCodePoints) {
        if (value == null || value.isEmpty() || hasUnpairedSurrogate(value)
                || value.codePointCount(0, value.length()) > maxCodePoints
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Artifact storage scope is invalid");
        }
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return true;
                }
            } else if (Character.isLowSurrogate(unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static String scopeKey(Scope scope) {
        MessageDigest digest = sha256Digest();
        updateComponent(digest, scope.tenantId());
        updateComponent(digest, scope.clientId());
        updateComponent(digest, scope.ownerJiacn());
        updateComponent(digest, scope.taskId());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateComponent(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String storageUri(String scopeKey, String contentHash) {
        try {
            return new URI(URI_SCHEME, scopeKey, "/" + contentHash, null).toASCIIString();
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException("Managed artifact URI construction failed", impossible);
        }
    }

    private static UriParts parseOwnedUri(String storageUri) {
        try {
            URI uri = new URI(storageUri);
            if (!URI_SCHEME.equals(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                    || !SHA256.matcher(Objects.requireNonNullElse(uri.getHost(), "")).matches()) {
                throw invalid("Managed artifact URI is invalid");
            }
            String path = uri.getRawPath();
            if (path == null || path.length() != 65 || path.charAt(0) != '/'
                    || !SHA256.matcher(path.substring(1)).matches()) {
                throw invalid("Managed artifact URI is invalid");
            }
            return new UriParts(uri.getHost(), path.substring(1));
        } catch (URISyntaxException | NullPointerException failure) {
            throw invalid("Managed artifact URI is invalid");
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static AgentTaskArtifactStorageException invalid(String message) {
        return new AgentTaskArtifactStorageException(Reason.INVALID_REQUEST, message);
    }

    private static AgentTaskArtifactStorageException corrupt(String message) {
        return new AgentTaskArtifactStorageException(Reason.CORRUPT_CONTENT, message);
    }

    private static AgentTaskArtifactStorageException ioFailure(Throwable cause) {
        return new AgentTaskArtifactStorageException(
                Reason.IO_FAILURE, "Artifact storage I/O failed", cause);
    }

    private record UriParts(String scopeKey, String contentHash) {
    }
}
