package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskRequestDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskArtifactDTO;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskArtifactStorageException;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskArtifactStorageF02Test {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-a";
    private static final String ACTOR = "agent-a";
    private static final String ARTIFACT = "artifact-a";
    private static final long NOW = 1_800_000_000_000L;
    private static final Set<String> MIME_TYPES = Set.of(
            "application/octet-stream", "application/json", "text/plain");

    @TempDir
    Path temporaryDirectory;

    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskRequestDao requestDao;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskMutationTransaction transaction;
    private AgentTaskEventWriter eventWriter;

    @BeforeEach
    void setUp() {
        taskDao = mock(AgentTaskMetaDao.class);
        memberDao = mock(AgentTaskMemberDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        requestDao = mock(AgentTaskRequestDao.class);
        artifactDao = mock(AgentTaskArtifactDao.class);
        transaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        AgentTaskMetaEntity task = task();
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(task);
        when(transaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
            return mutation.apply(task);
        });
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR))
                .thenReturn(member(ACTOR, "worker"));
    }

    @Test
    void fileStoreComputesDigestScopesUrisAndNeverOverwrites() throws Exception {
        FileSystemAgentTaskArtifactStorage storage = storage(2L * 1024L * 1024L);
        byte[] content = "x".repeat(300_000).getBytes(StandardCharsets.UTF_8);
        AgentTaskArtifactStorage.Scope firstScope =
                new AgentTaskArtifactStorage.Scope(TENANT, CLIENT, TASK);

        AgentTaskArtifactStorage.StoredObject first = storage.store(
                firstScope, content, "application/octet-stream");
        AgentTaskArtifactStorage.StoredObject duplicate = storage.store(
                firstScope, content, "application/octet-stream");
        AgentTaskArtifactStorage.StoredObject otherScope = storage.store(
                new AgentTaskArtifactStorage.Scope(TENANT, CLIENT, "task-b"),
                content, "application/octet-stream");

        assertEquals(sha256(content), first.sha256());
        assertEquals(content.length, first.byteLength());
        assertTrue(first.newlyCreated());
        assertFalse(duplicate.newlyCreated());
        assertEquals(first.storageUri(), duplicate.storageUri());
        assertNotEquals(first.storageUri(), otherScope.storageUri());
        assertFalse(first.storageUri().contains(TENANT));
        assertFalse(first.storageUri().contains(TASK));
        assertFalse(first.storageUri().contains(temporaryDirectory.toString()));
        assertArrayEquals(content, storage.read(firstScope, first.storageUri(),
                first.sha256(), first.byteLength(), first.mimeType()).content());
        assertEquals(AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.read(
                                new AgentTaskArtifactStorage.Scope(TENANT, CLIENT, "task-b"),
                                first.storageUri(), first.sha256(), first.byteLength(),
                                first.mimeType())).getReason());
        assertEquals(2, managedObjectCount());
    }

    @Test
    void concurrentSameDigestPublicationCreatesExactlyOneImmutableObject() throws Exception {
        FileSystemAgentTaskArtifactStorage storage = storage(1024 * 1024);
        AgentTaskArtifactStorage.Scope scope =
                new AgentTaskArtifactStorage.Scope(TENANT, CLIENT, TASK);
        byte[] content = "concurrent-content".getBytes(StandardCharsets.UTF_8);
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        try {
            List<java.util.concurrent.Future<AgentTaskArtifactStorage.StoredObject>> futures =
                    new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return storage.store(scope, content, "application/octet-stream");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AgentTaskArtifactStorage.StoredObject> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream()
                    .filter(AgentTaskArtifactStorage.StoredObject::newlyCreated).count());
            assertEquals(1, results.stream().map(
                    AgentTaskArtifactStorage.StoredObject::storageUri).distinct().count());
            assertEquals(1, managedObjectCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fileStoreRejectsUnboundedMimePathAndSymlinkTampering() throws Exception {
        FileSystemAgentTaskArtifactStorage storage = storage(16);
        AgentTaskArtifactStorage.Scope scope =
                new AgentTaskArtifactStorage.Scope(TENANT, CLIENT, TASK);
        assertEquals(AgentTaskArtifactStorageException.Reason.INVALID_REQUEST,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.store(scope, new byte[17], "application/octet-stream"))
                        .getReason());
        assertEquals(AgentTaskArtifactStorageException.Reason.INVALID_REQUEST,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.store(scope, new byte[1], "text/plain; charset=utf-8"))
                        .getReason());
        assertEquals(AgentTaskArtifactStorageException.Reason.INVALID_REQUEST,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.store(
                                new AgentTaskArtifactStorage.Scope(" tenant-a", CLIENT, TASK),
                                new byte[1], "text/plain"))
                        .getReason());

        byte[] content = "immutable".getBytes(StandardCharsets.UTF_8);
        AgentTaskArtifactStorage.StoredObject stored = storage.store(scope, content, "text/plain");
        Path object = Files.walk(temporaryDirectory.resolve("objects"))
                .filter(path -> path.getFileName().toString().equals(stored.sha256()))
                .findFirst().orElseThrow();
        Path outside = temporaryDirectory.resolve("outside");
        Files.writeString(outside, "attacker");
        Files.delete(object);
        Files.createSymbolicLink(object, outside);

        assertEquals(AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.read(scope, stored.storageUri(), stored.sha256(),
                                stored.byteLength(), stored.mimeType())).getReason());
        assertEquals(AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> storage.store(scope, content, "text/plain")).getReason());
    }

    @Test
    void configuredRootCannotBeASymbolicLink() throws Exception {
        Path real = temporaryDirectory.resolve("real-root");
        Files.createDirectory(real);
        Path link = temporaryDirectory.resolve("linked-root");
        Files.createSymbolicLink(link, real);

        assertEquals(AgentTaskArtifactStorageException.Reason.INVALID_REQUEST,
                assertThrows(AgentTaskArtifactStorageException.class,
                        () -> new FileSystemAgentTaskArtifactStorage(
                                link, 1024, MIME_TYPES)).getReason());
    }

    @Test
    void managedPublishStoresRealBytesAfterAclAndVersionLockThenReadsByExactAcl() {
        FileSystemAgentTaskArtifactStorage realStorage = storage(1024 * 1024);
        AgentTaskArtifactStorage storage = spy(realStorage);
        AgentTaskCollaborationServiceImpl service = service(storage);
        AgentTaskArtifactPublishDTO command = managedCommand(
                "managed bytes".getBytes(StandardCharsets.UTF_8));
        AtomicReference<AgentTaskArtifactDTO> inserted = successfulArtifactPersistence();

        var published = service.publish(TENANT, CLIENT, TASK, ACTOR, command);

        AgentTaskArtifactDTO row = inserted.get();
        assertNull(row.getContent());
        assertTrue(row.getStorageUri().startsWith("cyf-artifact://"));
        assertEquals(sha256(command.getContentBytes()), row.getContentHash());
        assertFalse(row.getMetadataJson().contains("managed bytes"));
        assertEquals(Map.of("format", "binary"), published.getMetadata());
        assertEquals((long) command.getContentBytes().length, published.getContentByteLength());
        assertEquals("application/octet-stream", published.getContentMimeType());
        assertTrue(published.getManagedStorage());

        var content = service.readContent(
                TENANT, CLIENT, TASK, ACTOR, ARTIFACT, 1);
        assertArrayEquals(command.getContentBytes(), content.getContent());
        assertEquals(row.getContentHash(), content.getContentHash());
        ArgumentCaptor<byte[]> storedBytes = ArgumentCaptor.forClass(byte[].class);
        verify(storage).store(any(), storedBytes.capture(), eq("application/octet-stream"));
        assertArrayEquals(command.getContentBytes(), storedBytes.getValue());
        verify(storage).read(any(), eq(row.getStorageUri()), eq(row.getContentHash()),
                eq((long) command.getContentBytes().length), eq("application/octet-stream"));

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals(TaskEventType.ARTIFACT_PUBLISHED, event.getValue().getEventType());
        String eventJson = event.getValue().getEventJson();
        assertTrue(eventJson.contains(row.getContentHash()));
        assertFalse(eventJson.contains("managed bytes"));
        assertFalse(eventJson.contains("application/octet-stream"));
        assertFalse(eventJson.contains("cyf-artifact"));
        assertFalse(eventJson.contains(MANAGED_KEY));
    }

    @Test
    void aclAndStaleVersionRejectBeforeAnyFilesystemWrite() {
        AgentTaskArtifactStorage storage = spy(storage(1024));
        AgentTaskCollaborationServiceImpl service = service(storage);
        AgentTaskArtifactPublishDTO command = managedCommand(new byte[] {1, 2, 3});

        AgentTaskCollaborationException forbidden = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, "outsider", command));
        assertEquals(Reason.FORBIDDEN, forbidden.getReason());
        verify(storage, never()).store(any(), any(), any());

        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, ARTIFACT))
                .thenReturn(artifactFrom(managedRow(command, "cyf-artifact://"
                        + "a".repeat(64) + "/" + "b".repeat(64), "b".repeat(64))));
        AgentTaskCollaborationException conflict = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));
        assertEquals(Reason.VERSION_CONFLICT, conflict.getReason());
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void declaredDigestAndLengthMismatchRejectBeforeFilesystemWrite() {
        AgentTaskArtifactStorage storage = spy(storage(1024));
        AgentTaskCollaborationServiceImpl service = service(storage);
        AgentTaskArtifactPublishDTO badHash = managedCommand(new byte[] {1, 2, 3});
        badHash.setContentHash("0".repeat(64));
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, ARTIFACT))
                .thenReturn(null);

        AgentTaskCollaborationException hashFailure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, badHash));
        assertEquals(Reason.INVALID_REQUEST, hashFailure.getReason());
        verify(storage, never()).store(any(), any(), any());

        AgentTaskArtifactPublishDTO badLength = managedCommand(new byte[] {1, 2, 3});
        badLength.setContentByteLength(4L);
        AgentTaskCollaborationException lengthFailure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, badLength));
        assertEquals(Reason.INVALID_REQUEST, lengthFailure.getReason());
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void databaseFailureLeavesOnlyReusableImmutableOrphanAndNoDatabaseContent() throws Exception {
        FileSystemAgentTaskArtifactStorage storage = storage(1024);
        AgentTaskCollaborationServiceImpl service = service(storage);
        byte[] bytes = "orphan-safe".getBytes(StandardCharsets.UTF_8);
        AgentTaskArtifactPublishDTO command = managedCommand(bytes);
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, ARTIFACT))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("db rejected"));

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_PERSISTED_STATE, failure.getReason());
        assertEquals(1, managedObjectCount());
        verify(artifactDao, never()).findVersion(any(), any(), any(), any(), anyInt());

        org.mockito.Mockito.reset(artifactDao);
        AtomicReference<AgentTaskArtifactDTO> inserted = successfulArtifactPersistence();
        var retried = service.publish(TENANT, CLIENT, TASK, ACTOR, command);
        assertEquals(1, managedObjectCount());
        assertEquals(sha256(bytes), inserted.get().getContentHash());
        assertEquals(sha256(bytes), retried.getContentHash());
    }

    @Test
    void contentReadRejectsNonMemberBeforeStorageAccess() {
        AgentTaskArtifactStorage storage = spy(storage(1024));
        AgentTaskCollaborationServiceImpl service = service(storage);

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.readContent(
                        TENANT, CLIENT, TASK, "outsider", ARTIFACT, 1));

        assertEquals(Reason.FORBIDDEN, failure.getReason());
        verify(artifactDao, never()).findVersion(any(), any(), any(), any(), anyInt());
        verify(storage, never()).read(any(), any(), any(), anyLong(), any());
    }

    @Test
    void legacyExternalUriRemainsMetadataOnlyAndIsNeverFetched() {
        AgentTaskArtifactStorage storage = spy(storage(1024));
        AgentTaskCollaborationServiceImpl service = service(storage);
        AgentTaskArtifactEntity external = new AgentTaskArtifactEntity()
                .setArtifactId(ARTIFACT).setTaskId(TASK).setProducerAgentId(ACTOR)
                .setArtifactType("document").setTitle("External").setContent(null)
                .setStorageUri("https://example.invalid/private/object")
                .setContentHash("a".repeat(64)).setArtifactVersion(1)
                .setVisibility("task_members").setMetadataJson("{\"format\":\"external\"}")
                .setCreatedAt(NOW);
        external.setTenantId(TENANT);
        external.setClientId(CLIENT);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, ARTIFACT, 1))
                .thenReturn(external);

        AgentTaskCollaborationException failure = assertThrows(
                AgentTaskCollaborationException.class,
                () -> service.readContent(TENANT, CLIENT, TASK, ACTOR, ARTIFACT, 1));

        assertEquals(Reason.INVALID_REQUEST, failure.getReason());
        verify(storage, never()).read(any(), any(), any(), anyLong(), any());
    }

    private static final String MANAGED_KEY = "_cyfArtifactStorageV1";

    private FileSystemAgentTaskArtifactStorage storage(long maxBytes) {
        return new FileSystemAgentTaskArtifactStorage(
                temporaryDirectory.resolve("objects").toAbsolutePath(), maxBytes, MIME_TYPES);
    }

    private AgentTaskCollaborationServiceImpl service(AgentTaskArtifactStorage storage) {
        return new AgentTaskCollaborationServiceImpl(taskDao, memberDao, workItemDao,
                requestDao, artifactDao, storage, transaction, eventWriter, () -> NOW);
    }

    private AtomicReference<AgentTaskArtifactDTO> successfulArtifactPersistence() {
        AtomicReference<AgentTaskArtifactDTO> inserted = new AtomicReference<>();
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, ARTIFACT))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(2));
            return 1;
        });
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, ARTIFACT, 1))
                .thenAnswer(invocation -> artifactFrom(inserted.get()));
        return inserted;
    }

    private AgentTaskArtifactDTO managedRow(
            AgentTaskArtifactPublishDTO command, String uri, String hash) {
        AgentTaskArtifactDTO row = new AgentTaskArtifactDTO();
        row.setArtifactId(command.getArtifactId());
        row.setTaskId(TASK);
        row.setProducerAgentId(ACTOR);
        row.setArtifactType(command.getArtifactType());
        row.setTitle(command.getTitle());
        row.setStorageUri(uri);
        row.setContentHash(hash);
        row.setArtifactVersion(1);
        row.setVisibility("task_members");
        row.setMetadataJson("{}");
        row.setCreatedAt(NOW);
        return row;
    }

    private AgentTaskArtifactEntity artifactFrom(AgentTaskArtifactDTO row) {
        if (row == null) return null;
        AgentTaskArtifactEntity entity = new AgentTaskArtifactEntity()
                .setArtifactId(row.getArtifactId()).setTaskId(row.getTaskId())
                .setWorkItemId(row.getWorkItemId()).setProducerAgentId(row.getProducerAgentId())
                .setArtifactType(row.getArtifactType()).setTitle(row.getTitle())
                .setContent(row.getContent()).setStorageUri(row.getStorageUri())
                .setContentHash(row.getContentHash()).setArtifactVersion(row.getArtifactVersion())
                .setVisibility(row.getVisibility()).setMetadataJson(row.getMetadataJson())
                .setCreatedAt(row.getCreatedAt());
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private AgentTaskArtifactPublishDTO managedCommand(byte[] content) {
        AgentTaskArtifactPublishDTO command = new AgentTaskArtifactPublishDTO();
        command.setArtifactId(ARTIFACT);
        command.setProducerAgentId(ACTOR);
        command.setArtifactType("document");
        command.setTitle("Managed artifact");
        command.setContentBytes(content);
        command.setContentMimeType("application/octet-stream");
        command.setContentHash(sha256(content));
        command.setArtifactVersion(1);
        command.setExpectedPreviousVersion(0);
        command.setVisibility("task_members");
        command.setMetadata(Map.of("format", "binary"));
        return command;
    }

    private AgentTaskMetaEntity task() {
        AgentTaskMetaEntity task = new AgentTaskMetaEntity();
        task.setTaskId(TASK);
        task.setTaskVersion(0L);
        task.setCurrentEventVersion(0L);
        task.setTenantId(TENANT);
        task.setClientId(CLIENT);
        return task;
    }

    private AgentTaskMemberEntity member(String agentId, String role) {
        AgentTaskMemberEntity member = new AgentTaskMemberEntity();
        member.setTaskId(TASK);
        member.setAgentId(agentId);
        member.setMemberRole(role);
        member.setMemberStatus("working");
        member.setTenantId(TENANT);
        member.setClientId(CLIENT);
        return member;
    }

    private long managedObjectCount() throws Exception {
        try (var paths = Files.walk(temporaryDirectory.resolve("objects"))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith(".upload-"))
                    .count();
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }
}
