package cn.jia.agent.service.impl;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceOperationEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.exception.PersonalWorkspaceException;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceServiceImplTest {
    private static final PersonalWorkspaceService.Scope OWNER_A =
            new PersonalWorkspaceService.Scope("0", "browser", "owner-a");
    private static final PersonalWorkspaceService.Scope OWNER_B =
            new PersonalWorkspaceService.Scope("0", "browser", "owner-b");
    private static final PersonalWorkspaceService.Scope OWNER_A_OTHER_CLIENT =
            new PersonalWorkspaceService.Scope("0", "mobile", "owner-a");

    @Test
    void ownerAndClientScopeHideEveryForeignObjectBeforeStorageAccess() throws Exception {
        Fixture fixture = new Fixture();
        byte[] original = "owner-a private bytes".getBytes(StandardCharsets.UTF_8);
        var created = fixture.service.create(OWNER_A,
                upload("create-a", "private.txt", original));
        String fileId = created.file().fileId();
        String operationId = created.operation().operationId();
        assertEquals("UPLOAD", created.file().sourceKind());
        assertEquals("USER_UPLOAD", created.file().originKind());

        assertArrayEquals(original, fixture.service.readContent(OWNER_A, fileId, 1).bytes());
        int readsBeforeForeignRequests = fixture.storage.readCount;
        int storesBeforeForeignRequests = fixture.storage.storeCount;

        for (PersonalWorkspaceService.Scope intruder : List.of(OWNER_B, OWNER_A_OTHER_CLIENT)) {
            assertTrue(fixture.service.list(intruder,
                    new PersonalWorkspaceService.ListQuery(null, null, null, null)).items().isEmpty());
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.get(intruder, fileId));
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.versions(intruder, fileId));
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.readContent(intruder, fileId, 1));
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.operation(intruder, operationId));
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.rename(intruder, fileId, "stolen.txt",
                            '"' + fileId + ":1\"", new PersonalWorkspaceService.Idempotency("rename-foreign")));
            assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                    () -> fixture.service.appendVersion(intruder, fileId,
                            upload("append-foreign", "private.txt", bytes("foreign")), 1));
        }

        assertEquals(readsBeforeForeignRequests, fixture.storage.readCount,
                "foreign lookups must fail at owner-scoped metadata before private storage");
        assertEquals(storesBeforeForeignRequests, fixture.storage.storeCount,
                "foreign writes must not allocate private storage objects");
        assertEquals("private.txt", fixture.service.get(OWNER_A, fileId).file().displayName());
    }

    @Test
    void idempotencyReceiptIsScopedAndPayloadConflictFailsBeforeStorage() {
        Fixture fixture = new Fixture();
        var command = upload("shared-key", "same.txt", bytes("same request"));

        var first = fixture.service.create(OWNER_A, command);
        var replay = fixture.service.create(OWNER_A, command);

        assertEquals(first.file().fileId(), replay.file().fileId());
        assertEquals(first.operation().operationId(), replay.operation().operationId());
        assertEquals(1, fixture.storage.storeCount);

        assertReason(PersonalWorkspaceException.Reason.IDEMPOTENCY_CONFLICT,
                () -> fixture.service.create(OWNER_A,
                        upload("shared-key", "same.txt", bytes("changed request"))));
        assertEquals(1, fixture.storage.storeCount,
                "a conflicting replay must be rejected before storage");

        var otherOwner = fixture.service.create(OWNER_B,
                upload("shared-key", "same.txt", bytes("owner-b request")));
        var otherClient = fixture.service.create(OWNER_A_OTHER_CLIENT,
                upload("shared-key", "same.txt", bytes("mobile request")));
        assertNotEquals(first.file().fileId(), otherOwner.file().fileId());
        assertNotEquals(first.file().fileId(), otherClient.file().fileId());
        assertEquals(3, fixture.storage.storeCount,
                "owner and client scopes must not share idempotency receipts");
    }

    @Test
    void versionCasAllowsOneV2AndRejectsAStaleWriterWithoutOverwritingV1() {
        Fixture fixture = new Fixture();
        byte[] v1 = bytes("version one");
        byte[] v2 = bytes("version two");
        byte[] stale = bytes("stale writer");
        String fileId = fixture.service.create(OWNER_A,
                upload("create-versioned", "versioned.txt", v1)).file().fileId();

        fixture.events.clear();
        var winner = fixture.service.appendVersion(OWNER_A, fileId,
                upload("append-winner", "versioned.txt", v2), 1);
        assertEquals(2, winner.version().version());
        assertOrdered(fixture.events, "storage.store", "dao.lockFile", "dao.insertVersion");

        fixture.events.clear();
        assertReason(PersonalWorkspaceException.Reason.VERSION_CONFLICT,
                () -> fixture.service.appendVersion(OWNER_A, fileId,
                        upload("append-stale", "versioned.txt", stale), 1));
        assertOrdered(fixture.events, "storage.store", "dao.lockFile");
        assertFalse(fixture.events.contains("dao.insertVersion"));
        assertFalse(fixture.events.contains("dao.updateFile"));

        assertEquals(2, fixture.service.get(OWNER_A, fileId).file().latestVersion());
        assertEquals(List.of(2, 1), fixture.service.versions(OWNER_A, fileId).stream()
                .map(version -> version.version()).toList());
        assertArrayEquals(v1, fixture.service.readContent(OWNER_A, fileId, 1).bytes());
        assertArrayEquals(v2, fixture.service.readContent(OWNER_A, fileId, 2).bytes());
        assertReason(PersonalWorkspaceException.Reason.NOT_FOUND,
                () -> fixture.service.readContent(OWNER_A, fileId, 3));

        PersonalWorkspaceOperationEntity failed = fixture.dao.operationByKey(
                OWNER_A, "APPEND", "append-stale");
        assertNotNull(failed);
        assertEquals("FAILED", failed.getState());
        assertEquals("VERSION_CONFLICT", failed.getErrorCode());
        assertEquals(2, fixture.dao.versionCount(OWNER_A, fileId));
    }

    @Test
    void storageFailureLeavesNoReadyMetadataAndReplayDoesNotWriteAgain() {
        Fixture fixture = new Fixture();
        fixture.storage.failStoreWith = PersonalWorkspaceException.Reason.STORAGE_UNAVAILABLE;
        var command = upload("failed-upload", "failure.txt", bytes("must not become ready"));

        assertReason(PersonalWorkspaceException.Reason.STORAGE_UNAVAILABLE,
                () -> fixture.service.create(OWNER_A, command));
        assertEquals(1, fixture.storage.storeCount);
        assertEquals(0, fixture.dao.fileCount());
        assertEquals(0, fixture.dao.versionCount());

        PersonalWorkspaceOperationEntity failed = fixture.dao.operationByKey(
                OWNER_A, "CREATE", "failed-upload");
        assertNotNull(failed);
        assertEquals("FAILED", failed.getState());
        assertEquals("STORAGE_UNAVAILABLE", failed.getErrorCode());
        assertNull(failed.getFileId());

        assertReason(PersonalWorkspaceException.Reason.OPERATION_FAILED,
                () -> fixture.service.create(OWNER_A, command));
        assertEquals(1, fixture.storage.storeCount,
                "replay of a failed receipt must not create another private object");
        assertEquals(0, fixture.dao.fileCount());
        assertEquals(0, fixture.dao.versionCount());
    }

    @Test
    void metadataCasFailsClosedAndPreservesContentUntilExactEtagSucceeds() {
        Fixture fixture = new Fixture();
        byte[] content = bytes("immutable content");
        String fileId = fixture.service.create(OWNER_A,
                upload("create-rename", "before.txt", content)).file().fileId();

        assertReason(PersonalWorkspaceException.Reason.PRECONDITION_REQUIRED,
                () -> fixture.service.rename(OWNER_A, fileId, "missing.txt", null,
                        new PersonalWorkspaceService.Idempotency("rename-missing")));
        assertReason(PersonalWorkspaceException.Reason.METADATA_CHANGED,
                () -> fixture.service.rename(OWNER_A, fileId, "stale.txt",
                        '"' + fileId + ":0\"",
                        new PersonalWorkspaceService.Idempotency("rename-stale")));

        var unchanged = fixture.service.get(OWNER_A, fileId).file();
        assertEquals("before.txt", unchanged.displayName());
        assertEquals(1, unchanged.metadataRevision());
        assertArrayEquals(content, fixture.service.readContent(OWNER_A, fileId, 1).bytes());

        var renamed = fixture.service.rename(OWNER_A, fileId, "after.txt",
                '"' + fileId + ":1\"",
                new PersonalWorkspaceService.Idempotency("rename-current"));
        assertEquals("after.txt", renamed.displayName());
        assertEquals(2, renamed.metadataRevision());
        assertArrayEquals(content, fixture.service.readContent(OWNER_A, fileId, 1).bytes());
    }

    private static PersonalWorkspaceService.UploadCommand upload(
            String key, String filename, byte[] content) {
        return new PersonalWorkspaceService.UploadCommand(
                new PersonalWorkspaceService.Idempotency(key), filename, filename,
                "text/plain", content);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertReason(PersonalWorkspaceException.Reason reason, Executable executable) {
        PersonalWorkspaceException failure = assertThrows(PersonalWorkspaceException.class, executable);
        assertEquals(reason, failure.getReason());
    }

    private static void assertOrdered(List<String> events, String... expected) {
        int previous = -1;
        for (String event : expected) {
            int index = events.indexOf(event);
            assertTrue(index > previous, event + " was not ordered in " + events);
            previous = index;
        }
    }

    private static final class Fixture {
        private final List<String> events = new ArrayList<>();
        private final InMemoryDao dao = new InMemoryDao(events);
        private final RecordingStorage storage = new RecordingStorage(events);
        private final PersonalWorkspaceService service = new PersonalWorkspaceServiceImpl(
                dao, storage, new PersonalWorkspaceWriteService(dao));
    }

    private static final class RecordingStorage implements PersonalWorkspaceStorage {
        private final List<String> events;
        private final AtomicLong sequence = new AtomicLong();
        private final Map<StoredKey, StoredValue> objects = new LinkedHashMap<>();
        private PersonalWorkspaceException.Reason failStoreWith;
        private int storeCount;
        private int readCount;

        private RecordingStorage(List<String> events) {
            this.events = events;
        }

        @Override
        public StoredObject store(Scope scope, byte[] content, String mimeType) {
            events.add("storage.store");
            storeCount++;
            if (failStoreWith != null) throw new PersonalWorkspaceException(failStoreWith);
            byte[] copy = content.clone();
            String hash = sha256(copy);
            String uri = "memory://personal-workspace/" + sequence.incrementAndGet();
            objects.put(new StoredKey(scope.tenantId(), scope.clientId(), scope.ownerJiacn(), uri),
                    new StoredValue(copy, hash, mimeType));
            return new StoredObject(uri, hash, copy.length, mimeType);
        }

        @Override
        public StoredContent read(Scope scope, String storageUri, String expectedSha256,
                long expectedByteLength, String expectedMimeType) {
            events.add("storage.read");
            readCount++;
            StoredValue stored = objects.get(new StoredKey(scope.tenantId(), scope.clientId(),
                    scope.ownerJiacn(), storageUri));
            if (stored == null || !stored.hash().equals(expectedSha256)
                    || stored.content().length != expectedByteLength
                    || !stored.mimeType().equals(expectedMimeType)) {
                throw new PersonalWorkspaceException(PersonalWorkspaceException.Reason.STORAGE_CORRUPT);
            }
            return new StoredContent(stored.content(), stored.hash(), stored.content().length,
                    stored.mimeType());
        }

        private record StoredKey(String tenantId, String clientId, String ownerJiacn, String uri) { }
        private record StoredValue(byte[] content, String hash, String mimeType) {
            private StoredValue {
                content = content.clone();
            }
            @Override public byte[] content() { return content.clone(); }
        }
    }

    private static final class InMemoryDao implements PersonalWorkspaceDao {
        private final List<String> events;
        private final AtomicLong ids = new AtomicLong();
        private final Map<FileKey, PersonalWorkspaceFileEntity> files = new LinkedHashMap<>();
        private final Map<VersionKey, PersonalWorkspaceVersionEntity> versions = new LinkedHashMap<>();
        private final Map<OperationKey, PersonalWorkspaceOperationEntity> operations = new LinkedHashMap<>();

        private InMemoryDao(List<String> events) {
            this.events = events;
        }

        @Override
        public PersonalWorkspaceFileEntity findFile(String tenantId, String clientId,
                String ownerJiacn, String fileId) {
            events.add("dao.findFile");
            return copy(files.get(new FileKey(tenantId, clientId, ownerJiacn, fileId)));
        }

        @Override
        public PersonalWorkspaceFileEntity lockFile(String tenantId, String clientId,
                String ownerJiacn, String fileId) {
            events.add("dao.lockFile");
            return copy(files.get(new FileKey(tenantId, clientId, ownerJiacn, fileId)));
        }

        @Override
        public List<PersonalWorkspaceFileEntity> listFiles(String tenantId, String clientId,
                String ownerJiacn, String q, String mediaFamily, String state,
                Long beforeCreatedAt, String afterFileId, int limit) {
            events.add("dao.listFiles");
            return files.values().stream()
                    .filter(file -> scope(file, tenantId, clientId, ownerJiacn))
                    .filter(file -> q == null || file.getDisplayName().contains(q))
                    .filter(file -> mediaFamily == null || mediaFamily.equals(file.getMediaFamily()))
                    .filter(file -> state == null || state.equals(file.getState()))
                    .filter(file -> beforeCreatedAt == null
                            || file.getCreatedAt() < beforeCreatedAt
                            || file.getCreatedAt().equals(beforeCreatedAt)
                            && file.getFileId().compareTo(afterFileId) > 0)
                    .sorted(Comparator.comparing(PersonalWorkspaceFileEntity::getCreatedAt).reversed()
                            .thenComparing(PersonalWorkspaceFileEntity::getFileId))
                    .limit(limit).map(InMemoryDao::copy).toList();
        }

        @Override
        public void insertFile(PersonalWorkspaceFileEntity entity) {
            events.add("dao.insertFile");
            FileKey key = fileKey(entity);
            if (files.containsKey(key)) throw new DuplicateKeyException("file");
            if (entity.getId() == null) entity.setId(ids.incrementAndGet());
            files.put(key, copy(entity));
        }

        @Override
        public void updateFile(PersonalWorkspaceFileEntity entity) {
            events.add("dao.updateFile");
            FileKey key = fileKey(entity);
            if (!files.containsKey(key)) throw new IllegalStateException("missing file");
            files.put(key, copy(entity));
        }

        @Override
        public PersonalWorkspaceVersionEntity findVersion(String tenantId, String clientId,
                String ownerJiacn, String fileId, int version) {
            events.add("dao.findVersion");
            return copy(versions.get(new VersionKey(
                    tenantId, clientId, ownerJiacn, fileId, version)));
        }

        @Override
        public List<PersonalWorkspaceVersionEntity> listVersions(String tenantId, String clientId,
                String ownerJiacn, String fileId, int limit) {
            events.add("dao.listVersions");
            return versions.values().stream()
                    .filter(version -> scope(version, tenantId, clientId, ownerJiacn))
                    .filter(version -> fileId.equals(version.getFileId()))
                    .sorted(Comparator.comparing(PersonalWorkspaceVersionEntity::getVersion).reversed())
                    .limit(limit).map(InMemoryDao::copy).toList();
        }

        @Override
        public void insertVersion(PersonalWorkspaceVersionEntity entity) {
            events.add("dao.insertVersion");
            VersionKey key = versionKey(entity);
            if (versions.containsKey(key)) throw new DuplicateKeyException("version");
            if (entity.getId() == null) entity.setId(ids.incrementAndGet());
            versions.put(key, copy(entity));
        }

        @Override
        public PersonalWorkspaceOperationEntity findOperationByIdempotency(String tenantId,
                String clientId, String ownerJiacn, String type, String key) {
            events.add("dao.findOperationByIdempotency");
            return copy(operations.get(new OperationKey(
                    tenantId, clientId, ownerJiacn, type, key)));
        }

        @Override
        public PersonalWorkspaceOperationEntity findOperation(String tenantId, String clientId,
                String ownerJiacn, String operationId) {
            events.add("dao.findOperation");
            return operations.values().stream()
                    .filter(operation -> scope(operation, tenantId, clientId, ownerJiacn))
                    .filter(operation -> operationId.equals(operation.getOperationId()))
                    .findFirst().map(InMemoryDao::copy).orElse(null);
        }

        @Override
        public void insertOperation(PersonalWorkspaceOperationEntity entity) {
            events.add("dao.insertOperation");
            OperationKey key = operationKey(entity);
            if (operations.containsKey(key)) throw new DuplicateKeyException("operation");
            if (entity.getId() == null) entity.setId(ids.incrementAndGet());
            operations.put(key, copy(entity));
        }

        @Override
        public void updateOperation(PersonalWorkspaceOperationEntity entity) {
            events.add("dao.updateOperation");
            OperationKey key = operationKey(entity);
            if (!operations.containsKey(key)) throw new IllegalStateException("missing operation");
            operations.put(key, copy(entity));
        }

        private PersonalWorkspaceOperationEntity operationByKey(
                PersonalWorkspaceService.Scope scope, String type, String key) {
            return findOperationByIdempotency(
                    scope.tenantId(), scope.clientId(), scope.ownerJiacn(), type, key);
        }

        private int fileCount() { return files.size(); }
        private int versionCount() { return versions.size(); }
        private int versionCount(PersonalWorkspaceService.Scope scope, String fileId) {
            return (int) versions.values().stream()
                    .filter(version -> scope(version, scope.tenantId(), scope.clientId(), scope.ownerJiacn()))
                    .filter(version -> fileId.equals(version.getFileId())).count();
        }

        private static boolean scope(PersonalWorkspaceFileEntity entity, String tenantId,
                String clientId, String ownerJiacn) {
            return tenantId.equals(entity.getTenantId()) && clientId.equals(entity.getClientId())
                    && ownerJiacn.equals(entity.getOwnerJiacn());
        }

        private static boolean scope(PersonalWorkspaceVersionEntity entity, String tenantId,
                String clientId, String ownerJiacn) {
            return tenantId.equals(entity.getTenantId()) && clientId.equals(entity.getClientId())
                    && ownerJiacn.equals(entity.getOwnerJiacn());
        }

        private static boolean scope(PersonalWorkspaceOperationEntity entity, String tenantId,
                String clientId, String ownerJiacn) {
            return tenantId.equals(entity.getTenantId()) && clientId.equals(entity.getClientId())
                    && ownerJiacn.equals(entity.getOwnerJiacn());
        }

        private static FileKey fileKey(PersonalWorkspaceFileEntity entity) {
            return new FileKey(entity.getTenantId(), entity.getClientId(),
                    entity.getOwnerJiacn(), entity.getFileId());
        }

        private static VersionKey versionKey(PersonalWorkspaceVersionEntity entity) {
            return new VersionKey(entity.getTenantId(), entity.getClientId(),
                    entity.getOwnerJiacn(), entity.getFileId(), entity.getVersion());
        }

        private static OperationKey operationKey(PersonalWorkspaceOperationEntity entity) {
            return new OperationKey(entity.getTenantId(), entity.getClientId(),
                    entity.getOwnerJiacn(), entity.getOperationType(), entity.getIdempotencyKey());
        }

        private static PersonalWorkspaceFileEntity copy(PersonalWorkspaceFileEntity source) {
            if (source == null) return null;
            PersonalWorkspaceFileEntity target = new PersonalWorkspaceFileEntity()
                    .setId(source.getId()).setFileId(source.getFileId())
                    .setOwnerJiacn(source.getOwnerJiacn()).setSourceKind(source.getSourceKind())
                    .setOriginKind(source.getOriginKind())
                    .setDisplayName(source.getDisplayName()).setMediaFamily(source.getMediaFamily())
                    .setState(source.getState()).setMetadataRevision(source.getMetadataRevision())
                    .setLatestVersion(source.getLatestVersion()).setCreatedAt(source.getCreatedAt());
            target.setTenantId(source.getTenantId());
            target.setClientId(source.getClientId());
            return target;
        }

        private static PersonalWorkspaceVersionEntity copy(PersonalWorkspaceVersionEntity source) {
            if (source == null) return null;
            PersonalWorkspaceVersionEntity target = new PersonalWorkspaceVersionEntity()
                    .setId(source.getId()).setFileId(source.getFileId())
                    .setOwnerJiacn(source.getOwnerJiacn()).setVersion(source.getVersion())
                    .setOriginalFilename(source.getOriginalFilename())
                    .setContentMimeType(source.getContentMimeType())
                    .setByteLength(source.getByteLength()).setContentHash(source.getContentHash())
                    .setStorageUri(source.getStorageUri()).setCreatedAt(source.getCreatedAt());
            target.setTenantId(source.getTenantId());
            target.setClientId(source.getClientId());
            return target;
        }

        private static PersonalWorkspaceOperationEntity copy(PersonalWorkspaceOperationEntity source) {
            if (source == null) return null;
            PersonalWorkspaceOperationEntity target = new PersonalWorkspaceOperationEntity()
                    .setId(source.getId()).setOperationId(source.getOperationId())
                    .setOwnerJiacn(source.getOwnerJiacn())
                    .setOperationType(source.getOperationType())
                    .setIdempotencyKey(source.getIdempotencyKey())
                    .setRequestHash(source.getRequestHash()).setState(source.getState())
                    .setFileId(source.getFileId()).setFileVersion(source.getFileVersion())
                    .setErrorCode(source.getErrorCode()).setCreatedAt(source.getCreatedAt())
                    .setCompletedAt(source.getCompletedAt());
            target.setTenantId(source.getTenantId());
            target.setClientId(source.getClientId());
            return target;
        }

        private record FileKey(String tenantId, String clientId, String ownerJiacn, String fileId) { }
        private record VersionKey(String tenantId, String clientId, String ownerJiacn,
                String fileId, int version) { }
        private record OperationKey(String tenantId, String clientId, String ownerJiacn,
                String operationType, String idempotencyKey) { }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
    }
}
