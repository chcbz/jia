package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.model.*;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.model.*;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArchiveMaintenanceServiceImplTest {
    private static final String COLLECTION = "platform-classics";
    private static final String JOB = "aj_job";
    private static final String RUN = "ar_run";
    private static final String DRAFT = "ad_draft";
    private static final String APPOINTMENT = "apt_editor";
    private static final String AGENT = "agent-wuyong";
    private static final String SHA = "a".repeat(64);
    private static final ArchiveActorScope MANAGER = new ArchiveActorScope("0", "client-a", "owner-a");

    private AgentTaskArtifactStorage sourceStorage;
    private ArchiveMaintenanceStore store;
    private ArchiveContentStore content;
    private AgentIdentityService identities;
    private ArchiveMaintenanceServiceImpl service;

    @BeforeEach
    void setUp() {
        sourceStorage = mock(AgentTaskArtifactStorage.class);
        store = mock(ArchiveMaintenanceStore.class);
        when(store.findSource("src_1")).thenReturn(new ArchiveSourceSnapshotRecord(
                "src_1", COLLECTION, "0", "client-a", "owner-a", "cyf-artifact://source",
                SHA, 1, "固定来源", "摘要", "已授权公开用途", "UTF8_EXACT_V1", "READY"));
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new AgentTaskArtifactStorage.StoredContent(new byte[] { 1 }, SHA, 1, "text/plain"));
        content = mock(ArchiveContentStore.class);
        identities = mock(AgentIdentityService.class);
        ArchiveTransactions transactions = new ArchiveTransactions() {
            @Override public <T> T required(Supplier<T> action) { return action.get(); }
        };
        service = new ArchiveMaintenanceServiceImpl(store, content, transactions, identities,
                new ObjectMapper(), sourceStorage, Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void jobManageWithoutJobCreateOrAppointReadsJobsEventsAndExactRecoverySnapshots() {
        allowManager("job.manage");
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveAppointmentRecord revoked = appointment("REVOKED", 2);
        ArchiveAppointmentRecord current = new ArchiveAppointmentRecord("apt_current", COLLECTION,
                "ARCHIVE_EDITOR", "0", "client-a", "owner-a", "agent-lin", "9",
                "COLLECTION", "", "PUBLISH_VALIDATED", "archive-maintainer", "1.0.0",
                "b".repeat(64), "ACTIVE", 4, Instant.parse("2026-09-28T00:00:01Z"), null);
        ArchiveJobEventRecord event = new ArchiveJobEventRecord(JOB, 2, 1,
                "EXECUTION_FENCED", 1, "{\"reason\":\"revoked\"}",
                "2026-09-28T00:00:02Z");
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findAppointment(APPOINTMENT, false)).thenReturn(revoked);
        when(store.findCurrentAppointment(COLLECTION, false)).thenReturn(current);
        when(store.listJobs(MANAGER, COLLECTION, 20)).thenReturn(List.of(job));
        when(store.listJobEvents(JOB, 0, 20)).thenReturn(List.of(event));

        ArchiveRecoveryContextDTO result = service.recoveryContext(MANAGER, JOB);

        assertEquals(JOB, result.jobId());
        assertEquals("1", result.jobRevision());
        assertEquals(APPOINTMENT, result.previousAppointment().appointmentId());
        assertEquals("1", result.previousAppointment().revision(),
                "previous revision is the immutable job snapshot, not the later revoke revision");
        assertEquals("REVOKED", result.previousAppointment().status());
        assertEquals(new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA),
                result.previousAppointment().requiredSkill());
        assertEquals(1, result.candidates().size());
        assertEquals(new ArchiveRecoveryContextDTO.CandidateAppointment("apt_current", "4",
                new ArchiveSkillRef("archive-maintainer", "1.0.0", "b".repeat(64)),
                "ACTIVE", "agent-lin"), result.candidates().getFirst());
        assertEquals(JOB, service.getJob(MANAGER, JOB).jobId());
        assertEquals(JOB, service.listJobs(MANAGER, COLLECTION, 20).getFirst().jobId());
        assertEquals("EXECUTION_FENCED",
                service.jobEvents(MANAGER, JOB, 0, 20).getFirst().type());
        verify(store, never()).listAppointments(any(), anyString());

        ArchiveAppointmentRecord otherOwner = new ArchiveAppointmentRecord(current.appointmentId(),
                current.collectionId(), current.roleCode(), current.tenantId(), current.clientId(),
                "owner-b", current.agentId(), current.bindingVersion(), current.workScopeMode(),
                current.workIds(), current.permissionProfile(), current.requiredSkillKey(),
                current.requiredSkillVersion(), current.requiredSkillSha256(), current.status(),
                current.revision(), current.createdAt(), current.revokedAt());
        when(store.findCurrentAppointment(COLLECTION, false)).thenReturn(otherOwner);
        assertTrue(service.recoveryContext(MANAGER, JOB).candidates().isEmpty(),
                "another owner's current slot must not be disclosed");
    }

    @Test
    void recoveryContextHidesForeignJobsAndRequiresJobManage() {
        ArchiveMaintenanceJobRecord base = job("DRAFT_ONLY");
        for (ArchiveMaintenanceJobRecord foreign : List.of(
                jobScope(base, "1", "client-a", "owner-a"),
                jobScope(base, "0", "client-b", "owner-a"),
                jobScope(base, "0", "client-a", "owner-b"))) {
            when(store.findJob(JOB, false)).thenReturn(foreign);
            ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                    () -> service.recoveryContext(MANAGER, JOB));
            assertEquals(404, failure.status());
            assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", failure.code());
        }
        when(store.findJob(JOB, false)).thenReturn(base);
        ArchiveMaintenanceException forbidden = assertThrows(ArchiveMaintenanceException.class,
                () -> service.recoveryContext(MANAGER, JOB));
        assertEquals(403, forbidden.status());
        assertEquals("ARCHIVE_FORBIDDEN", forbidden.code());
        verify(store, never()).findAppointment(anyString(), anyBoolean());
        verify(store, never()).findCurrentAppointment(anyString(), anyBoolean());
    }

    @Test
    void sourceSnapshotUsesServerComputedBytesAndOwnerBoundStorageNotCallerDigestAlone() {
        allowManager("source.prepare");
        byte[] bytes = "第一回\n正文".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes);
        when(sourceStorage.store(any(AgentTaskArtifactStorage.Scope.class), any(byte[].class), eq("text/plain")))
                .thenReturn(new AgentTaskArtifactStorage.StoredObject("cyf-artifact://stored", hash,
                        bytes.length, "text/plain", true));
        when(sourceStorage.matches(any(), eq("cyf-artifact://stored"), eq(hash))).thenReturn(true);
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION + "/source-snapshots", "SOURCE", "src_operation");
        ArchiveSourcePrepareRequest request = new ArchiveSourcePrepareRequest("实验底本", "v1",
                "明确许可公开", hash, java.util.Base64.getEncoder().encodeToString(bytes));
        ArchiveOperationAcceptedDTO accepted = service.prepareSource(MANAGER, COLLECTION, "key-source", request);
        assertEquals("COMMITTED", accepted.state());
        assertNull(accepted.jobId());
        assertTrue(accepted.operationId().startsWith("src_"));
        ArgumentCaptor<ArchiveSourceSnapshotRecord> saved = ArgumentCaptor.forClass(ArchiveSourceSnapshotRecord.class);
        verify(store).insertSource(saved.capture());
        assertEquals(accepted.operationId(), saved.getValue().sourceId());
        assertEquals(hash, saved.getValue().rawSha256());
        assertEquals(bytes.length, saved.getValue().rawByteLength());
        assertEquals("cyf-artifact://stored", saved.getValue().storageUri());
        verify(sourceStorage).store(eq(new AgentTaskArtifactStorage.Scope("0", "client-a", "owner-a", accepted.operationId())),
                eq(bytes), eq("text/plain"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.source(new ArchiveActorScope("0", "client-a", "owner-b"), "src_1")).code());
        ArchiveSourcePrepareRequest forged = new ArchiveSourcePrepareRequest("实验底本", "v1",
                "明确许可公开", SHA, request.contentBase64());
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> service.prepareSource(MANAGER, COLLECTION, "forged", forged)).code());
    }

    @Test
    void sourceReservationSurvivesRetryAndCommittedReplayDoesNotStoreAgain() {
        allowManager("source.prepare");
        byte[] bytes = "固定来源".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes);
        var request = new ArchiveSourcePrepareRequest("固定来源", "v1", "许可公开", hash,
                java.util.Base64.getEncoder().encodeToString(bytes));
        var id = new java.util.concurrent.atomic.AtomicReference<String>();
        var originalDigest = new java.util.concurrent.atomic.AtomicReference<String>();
        var state = new java.util.concurrent.atomic.AtomicReference<>("PENDING");
        var saved = new java.util.concurrent.atomic.AtomicReference<ArchiveSourceSnapshotRecord>();
        when(store.beginOperation(eq(MANAGER), eq("same-key"), eq("POST"), anyString(),
                anyString(), eq("SOURCE"), anyString())).thenAnswer(invocation -> {
            boolean created = id.compareAndSet(null, invocation.getArgument(6));
            originalDigest.compareAndSet(null, invocation.getArgument(4));
            return new ArchiveMaintenanceStore.Operation(created, "POST", invocation.getArgument(3),
                    originalDigest.get(), "SOURCE", id.get(), state.get());
        });
        when(sourceStorage.store(any(), eq(bytes), eq("text/plain"))).thenAnswer(invocation ->
                new AgentTaskArtifactStorage.StoredObject("cyf-artifact://reserved", hash,
                        bytes.length, "text/plain", true));
        when(sourceStorage.matches(any(), eq("cyf-artifact://reserved"), eq(hash))).thenReturn(true);
        doAnswer(invocation -> { saved.set(invocation.getArgument(0)); return null; })
                .when(store).insertSource(any());
        doAnswer(invocation -> { state.set("COMMITTED"); return null; })
                .when(store).commitOperation(eq(MANAGER), eq("same-key"), anyString());
        when(store.findSource(anyString())).thenAnswer(invocation ->
                saved.get() != null && saved.get().sourceId().equals(invocation.getArgument(0))
                        ? saved.get() : null);
        ArchiveOperationAcceptedDTO first = service.prepareSource(MANAGER, COLLECTION, "same-key", request);
        ArchiveOperationAcceptedDTO replay = service.prepareSource(MANAGER, COLLECTION, "same-key", request);
        assertEquals(first, replay);
        assertEquals(id.get(), first.operationId());
        verify(sourceStorage, times(1)).store(any(), eq(bytes), eq("text/plain"));
        verify(store, times(1)).insertSource(any());
        ArchiveSourcePrepareRequest changed = new ArchiveSourcePrepareRequest("不同来源", "v1",
                "许可公开", hash, request.contentBase64());
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ArchiveMaintenanceException.class,
                () -> service.prepareSource(MANAGER, COLLECTION, "same-key", changed)).code());
        verify(sourceStorage, times(1)).store(any(), eq(bytes), eq("text/plain"));
    }

    @Test
    void source202OperationUsesDurableSourceIdentityAndRechecksCurrentPrepareAuthority() {
        allowManager("source.prepare");
        String path = "/archive/admin/v1/collections/" + COLLECTION + "/source-snapshots";
        when(store.findOperationByTarget(MANAGER, "SOURCE", "src_1"))
                .thenReturn(new ArchiveMaintenanceStore.TargetOperation("source-key", "POST", path,
                        "b".repeat(64), "SOURCE", "src_1", "COMMITTED"));

        ArchiveAdminOperationDTO committed = service.operation(MANAGER, "src_1");

        assertEquals("src_1", committed.operationId());
        assertEquals("source-key", committed.key());
        assertEquals("SOURCE_PREPARE", committed.action());
        assertNull(committed.jobId());
        assertNull(committed.draftId());
        assertEquals("src_1", committed.result().get("sourceId"));
        assertFalse(committed.result().containsKey("storageUri"));

        when(store.findOperationByTarget(MANAGER, "SOURCE", "src_pending"))
                .thenReturn(new ArchiveMaintenanceStore.TargetOperation("pending-key", "POST", path,
                        "c".repeat(64), "SOURCE", "src_pending", "PENDING"));
        ArchiveAdminOperationDTO pending = service.operation(MANAGER, "src_pending");
        assertEquals("PENDING", pending.state());
        assertNull(pending.result());

        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), anyBoolean()))
                .thenReturn(new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        "source.prepare", 4, "REVOKED"));
        assertEquals(403, assertThrows(ArchiveMaintenanceException.class,
                () -> service.operation(MANAGER, "src_1")).status());
        ArchiveActorScope foreign = new ArchiveActorScope("0", "client-a", "owner-b");
        assertEquals(404, assertThrows(ArchiveMaintenanceException.class,
                () -> service.operation(foreign, "src_1")).status());
    }

    @Test
    void corruptedStoredSourceBlocksValidatedPublication() throws Exception {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveAppointmentRecord currentAppointment = appointment("ACTIVE", 1);
        allowManager("publish");
        when(store.findCurrentAppointment(COLLECTION, false)).thenReturn(currentAppointment);
        activeAppointment(currentAppointment);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7L, AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        ArchiveDraftUpdateRequest body = body("甲", "乙");
        ArchiveDraftRecord draft = new ArchiveDraftRecord(DRAFT, JOB, 1,
                "VALIDATED", new ObjectMapper().writeValueAsString(body), SHA, 1L, "val_1");
        when(store.findDraftByJob(JOB, false)).thenReturn(draft);
        when(store.findDraftByJob(JOB, true)).thenReturn(draft);
        when(store.findValidation("val_1")).thenReturn(new ArchiveValidationRecord("val_1", DRAFT,
                1, "PASSED", SHA, "[]"));
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenThrow(new cn.jia.agent.exception.AgentTaskArtifactStorageException(
                        cn.jia.agent.exception.AgentTaskArtifactStorageException.Reason.CORRUPT_CONTENT,
                        "stored bytes changed"));
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/publish", "PUBLICATION", "pub_1");
        ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                () -> service.publish(MANAGER, JOB, "publish-corrupt", 1,
                        new ArchivePublishRequest("val_1", null, "0")));
        assertEquals("CONTENT_VALIDATION_FAILED", failure.code());
        verify(content, never()).insertEdition(any());
    }
    @Test
    void validationCannotPassWhenDraftClaimsTextNotPresentInStoredSource() throws Exception {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        allowManager("validate");
        activeAppointment(appointment("ACTIVE", 1));
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        ArchiveDraftUpdateRequest body = body("甲", "乙");
        when(store.findDraftByJob(JOB, true)).thenReturn(new ArchiveDraftRecord(DRAFT, JOB, 1,
                "EDITABLE", new ObjectMapper().writeValueAsString(body), SHA, null, null));
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/validate", "VALIDATION", "val_claim");
        when(store.updateDraft(eq(DRAFT), eq(1L), eq(1L), eq("CHANGES_REQUIRED"),
                anyString(), anyString(), isNull(), isNull())).thenReturn(1);
        when(store.updateJobState(JOB, 1, "NEEDS_CHANGES", null, null)).thenReturn(1);
        ArchiveValidationDTO result = service.validate(MANAGER, JOB, "validate-source", 1);
        assertEquals("FAILED", result.outcome());
        ArgumentCaptor<String> eventPayload = ArgumentCaptor.forClass(String.class);
        verify(store).appendJobEvent(eq(JOB), eq(2L), eq("VALIDATION_FINISHED"), eventPayload.capture());
        var audit = new ObjectMapper().readTree(eventPayload.getValue());
        assertEquals("HUMAN", audit.path("actorType").asText());
        assertEquals("owner-a", audit.path("actorId").asText());
        assertEquals("3", audit.path("authorizationRevision").asText());
        assertEquals("FAILED", audit.path("outcome").asText());
        assertTrue(result.findings().stream().anyMatch(f -> f.contains("source")));
        verify(content, never()).insertEdition(any());
    }
    @Test
    void ordinaryReaderCannotManageAndIsNeverAutoPromoted() {
        when(store.findJob(JOB, false)).thenReturn(job("DRAFT_ONLY"));
        assertTrue(service.capabilities(MANAGER, COLLECTION).allowedActions().isEmpty());
        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.appointments(MANAGER, COLLECTION));
        assertEquals(403, denied.status());
        assertEquals("ARCHIVE_FORBIDDEN", denied.code());
        for (Runnable read : List.<Runnable>of(
                () -> service.getJob(MANAGER, JOB),
                () -> service.listJobs(MANAGER, COLLECTION, 20),
                () -> service.jobEvents(MANAGER, JOB, 0, 20),
                () -> service.recoveryContext(MANAGER, JOB))) {
            ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                    read::run);
            assertEquals(403, failure.status());
            assertEquals("ARCHIVE_FORBIDDEN", failure.code());
        }
        verify(store, never()).listJobs(any(), anyString(), anyInt());
        verify(store, never()).listJobEvents(anyString(), anyLong(), anyInt());
        verify(store, never()).findAppointment(anyString(), anyBoolean());
        verify(store, never()).insertManagerGrant(any());
    }

    @Test
    void eventFeedIsAuthorizedPerCollectionAndCursorNeverLeaksForeignJob() {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        when(store.findJob(JOB, false)).thenReturn(job);
        ArchiveJobEventDTO event = new ArchiveJobEventDTO(JOB, "2", "1", "VALIDATION_FINISHED",
                "3", java.util.Map.of("outcome", "PASSED"), "2026-09-28T00:00:00Z");
        when(store.listJobEvents(JOB, 1, 20)).thenReturn(List.of(new ArchiveJobEventRecord(JOB, 2, 1,
                "VALIDATION_FINISHED", 3, "{\"outcome\":\"PASSED\"}", "2026-09-28T00:00:00Z")));
        assertEquals("ARCHIVE_FORBIDDEN", assertThrows(ArchiveMaintenanceException.class,
                () -> service.jobEvents(MANAGER, JOB, 1, 20)).code());
        verify(store, never()).listJobEvents(anyString(), anyLong(), anyInt());
        allowManager("job.create");
        assertEquals(List.of(event), service.jobEvents(MANAGER, JOB, 1, 20));
        assertEquals("INVALID_REQUEST", assertThrows(ArchiveMaintenanceException.class,
                () -> service.jobEvents(MANAGER, JOB, -1, 20)).code());
    }
    @Test
    void operationLookupExposesOnlyAuthorizedAdminIntentAndPreservesPending() {
        var pending = new ArchiveMaintenanceStore.Operation(false, "POST",
                "/archive/admin/v1/collections/" + COLLECTION + "/source-snapshots",
                SHA, "SOURCE", "src_reserved", "PENDING");
        when(store.findOperation(MANAGER, "original-key")).thenReturn(pending);
        assertEquals("ARCHIVE_FORBIDDEN", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "original-key")).code());
        allowManager("source.prepare");
        ArchiveOperationDTO result = service.operationByKey(MANAGER, "original-key");
        assertEquals("PENDING", result.state());
        assertEquals("src_reserved", result.targetId());
        verify(store, never()).findSource(anyString());
        when(store.findOperation(MANAGER, "missing-key")).thenReturn(null);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "missing-key")).code());
        when(store.findOperation(MANAGER, "native-key")).thenReturn(new ArchiveMaintenanceStore.Operation(
                false, "PUT", "/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/draft",
                SHA, "DRAFT", DRAFT, "COMMITTED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "native-key")).code());
        when(store.findOperation(MANAGER, "manager-revoke-key")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST",
                        "/archive/admin/v1/collections/" + COLLECTION + "/manager-authorization/revoke",
                        SHA, "MANAGER_AUTHORIZATION", COLLECTION, "COMMITTED"));
        ArchiveOperationDTO revokedReceipt = service.operationByKey(MANAGER, "manager-revoke-key");
        assertEquals("COMMITTED", revokedReceipt.state());
        assertEquals("MANAGER_AUTHORIZATION", revokedReceipt.targetType());
    }

    @Test
    void resolveInputOperationLookupRequiresExactJobTargetOwnerAndCurrentManageGrant() {
        ArchiveMaintenanceJobRecord own = new ArchiveMaintenanceJobRecord(
                JOB, null, COLLECTION, "0", "client-a", "owner-a", null, null,
                null, null, null, 3, "MANUAL", "ADD_WORK", "work-new", "key-new",
                "New Work", null, null, null, null, "WAITING_INPUT", "SOURCE_REQUIRED",
                1, null, null, "intent", "d".repeat(64), null);
        ArchiveMaintenanceStore.Operation pending = new ArchiveMaintenanceStore.Operation(false,
                "POST", "/archive/admin/v1/jobs/" + JOB + "/resolve-input", SHA,
                "JOB_INPUT", JOB, "PENDING");
        when(store.findJob(JOB, false)).thenReturn(own);
        when(store.findOperation(MANAGER, "resolve-pending")).thenReturn(pending);
        allowManager("job.manage");

        ArchiveOperationDTO pendingResult = service.operationByKey(MANAGER, "resolve-pending");
        assertEquals("PENDING", pendingResult.state());
        assertEquals("JOB_INPUT", pendingResult.targetType());
        assertEquals(JOB, pendingResult.targetId());

        when(store.findOperation(MANAGER, "resolve-committed")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST", pending.canonicalPath(), SHA,
                        "JOB_INPUT", JOB, "COMMITTED"));
        assertEquals("COMMITTED", service.operationByKey(MANAGER, "resolve-committed").state());

        when(store.findOperation(MANAGER, "resolve-wrong-target")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST", pending.canonicalPath(), SHA,
                        "JOB_INPUT", "job-other", "COMMITTED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "resolve-wrong-target")).code());

        when(store.findOperation(MANAGER, "resolve-wrong-type")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST", pending.canonicalPath(), SHA,
                        "JOB", JOB, "COMMITTED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "resolve-wrong-type")).code());

        ArchiveMaintenanceJobRecord foreign = jobScope(own, "0", "client-a", "owner-b");
        when(store.findOperation(MANAGER, "resolve-foreign")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST", pending.canonicalPath(), SHA,
                        "JOB_INPUT", JOB, "COMMITTED"));
        when(store.findJob(JOB, false)).thenReturn(foreign);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "resolve-foreign")).code());

        when(store.findJob(JOB, false)).thenReturn(own);
        when(store.findOperation(MANAGER, "resolve-revoked")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "POST", pending.canonicalPath(), SHA,
                        "JOB_INPUT", JOB, "COMMITTED"));
        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), anyBoolean())).thenReturn(
                new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        "job.manage", 4, "REVOKED"));
        assertEquals("ARCHIVE_FORBIDDEN", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "resolve-revoked")).code());
    }
    @Test
    void operationLookupChecksJobOwnerEvenWithCollectionGrant() {
        allowManager("publish");
        when(store.findOperation(MANAGER, "publish-key")).thenReturn(new ArchiveMaintenanceStore.Operation(
                false, "POST", "/archive/admin/v1/jobs/" + JOB + "/publish", SHA,
                "PUBLICATION", "pub_1", "COMMITTED"));
        ArchiveMaintenanceJobRecord own = job("DRAFT_ONLY");
        ArchiveMaintenanceJobRecord foreign = new ArchiveMaintenanceJobRecord(own.jobId(), own.runId(),
                own.collectionId(), own.tenantId(), own.clientId(), "owner-b", own.appointmentId(),
                own.appointmentRevision(), own.agentId(), own.bindingVersion(), own.permissionProfile(),
                own.managerAuthorizationRevision(), own.publicationMode(), own.operation(), own.workId(), own.canonicalKey(), own.title(),
                own.sourceId(), own.sourceSha256(), own.sourceSummary(), own.rightsBasis(), own.state(),
                own.waitReason(), own.revision(), own.draftId(), own.publicationId(),
                own.requestIntentId(), own.requestSha256());
        when(store.findJob(JOB, false)).thenReturn(foreign);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(MANAGER, "publish-key")).code());
    }

    @Test
    void cancellationFencesRunAndMakesOldNativeContextUnusable() {
        ArchiveMaintenanceJobRecord before = job("DRAFT_ONLY");
        ArchiveMaintenanceJobRecord cancelled = new ArchiveMaintenanceJobRecord(before.jobId(),
                before.runId(), before.collectionId(), before.tenantId(), before.clientId(),
                before.ownerJiacn(), before.appointmentId(), before.appointmentRevision(),
                before.agentId(), before.bindingVersion(), before.permissionProfile(),
                before.managerAuthorizationRevision(), before.publicationMode(), before.operation(), before.workId(), before.canonicalKey(),
                before.title(), before.sourceId(), before.sourceSha256(), before.sourceSummary(),
                before.rightsBasis(), "CANCELLED", "USER_CANCELLED", 2, before.draftId(),
                null, before.requestIntentId(), before.requestSha256());
        allowManager("job.manage,draft.write,publish");
        when(store.findJob(JOB, false)).thenReturn(before, cancelled);
        when(store.findJob(JOB, true)).thenReturn(before);
        when(store.fenceRun(RUN)).thenReturn(1);
        when(store.updateJobState(JOB, 1, "CANCELLED", "USER_CANCELLED", null)).thenReturn(1);
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/cancel", "JOB", JOB);
        ArchiveJobDTO result = service.cancel(MANAGER, JOB, "cancel-key", 1,
                new ArchiveCancelRequest("不再处理"));
        assertEquals("CANCELLED", result.state());
        verify(store).fenceRun(RUN);
        verify(store).appendJobEvent(eq(JOB), eq(2L), eq("JOB_CANCELLED"), anyString());
        when(store.findJob(JOB, false)).thenReturn(cancelled);
        when(store.findJob(JOB, true)).thenReturn(cancelled);
        activeAppointment(appointment("ACTIVE", 1));
        ArchiveRuntimeScope runtime = new ArchiveRuntimeScope("0", "client-a", "owner-a",
                AGENT, "runtime-1", "grant-1", "execution-1", "command-1", 1, 1);
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeContext(runtime, JOB, RUN)).code());
    }

    @Test
    void offlineTargetStillAllowsCancellationToFencePersistedExecution() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceJobRecord before = job("DRAFT_ONLY");
        ArchiveMaintenanceJobRecord cancelled = new ArchiveMaintenanceJobRecord(before.jobId(),
                before.runId(), before.collectionId(), before.tenantId(), before.clientId(),
                before.ownerJiacn(), before.appointmentId(), before.appointmentRevision(),
                before.agentId(), before.bindingVersion(), before.permissionProfile(),
                before.managerAuthorizationRevision(), before.publicationMode(), before.operation(),
                before.workId(), before.canonicalKey(), before.title(), before.sourceId(),
                before.sourceSha256(), before.sourceSummary(), before.rightsBasis(), "CANCELLED",
                "USER_CANCELLED", 2, before.draftId(), null, before.requestIntentId(),
                before.requestSha256());
        allowManager("job.manage");
        when(store.findJob(JOB, false)).thenReturn(before, cancelled);
        when(store.findJob(JOB, true)).thenReturn(before);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any()))
                .thenThrow(new ArchiveAgentExecutionPort.Denied("OFFLINE"));
        when(store.fenceRun(RUN)).thenReturn(1);
        when(store.updateJobState(JOB, 1, "CANCELLED", "USER_CANCELLED", null)).thenReturn(1);
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/cancel", "JOB", JOB);

        ArchiveJobDTO result = service.cancel(MANAGER, JOB, "offline-cancel", 1,
                new ArchiveCancelRequest("目标已离线"));

        assertEquals("CANCELLED", result.state());
        verify(port).lockIdentityRoot(any());
        verify(port, never()).requireControlledTarget(any(), any());
        verify(store).fenceRun(RUN);
    }

    @Test
    void publishedJobCannotBeCancelledOrRewritten() {
        ArchiveMaintenanceJobRecord pending = job("DRAFT_ONLY");
        ArchiveMaintenanceJobRecord published = new ArchiveMaintenanceJobRecord(pending.jobId(),
                pending.runId(), pending.collectionId(), pending.tenantId(), pending.clientId(),
                pending.ownerJiacn(), pending.appointmentId(), pending.appointmentRevision(),
                pending.agentId(), pending.bindingVersion(), pending.permissionProfile(),
                pending.managerAuthorizationRevision(), pending.publicationMode(), pending.operation(), pending.workId(), pending.canonicalKey(),
                pending.title(), pending.sourceId(), pending.sourceSha256(), pending.sourceSummary(),
                pending.rightsBasis(), "PUBLISHED", null, 2, pending.draftId(), "pub_1",
                pending.requestIntentId(), pending.requestSha256());
        allowManager("job.manage");
        when(store.findJob(JOB, false)).thenReturn(published);
        when(store.findJob(JOB, true)).thenReturn(published);
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/cancel", "JOB", JOB);
        assertEquals("ARCHIVE_JOB_NOT_MUTABLE", assertThrows(ArchiveMaintenanceException.class,
                () -> service.cancel(MANAGER, JOB, "cancel-late", 2,
                        new ArchiveCancelRequest("已经发布"))).code());
        verify(store, never()).fenceRun(anyString());
    }

    @Test
    void slotAndAppointmentHistoryDoNotExposeForeignOwners() {
        allowManager("appoint");
        ArchiveAppointmentRecord own = appointment("ACTIVE", 1);
        ArchiveAppointmentRecord foreign = new ArchiveAppointmentRecord(own.appointmentId(),
                own.collectionId(), own.roleCode(), own.tenantId(), own.clientId(), "owner-b",
                own.agentId(), own.bindingVersion(), own.workScopeMode(), own.workIds(),
                own.permissionProfile(), own.requiredSkillKey(), own.requiredSkillVersion(),
                own.requiredSkillSha256(), own.status(), own.revision(), own.createdAt(), own.revokedAt());
        when(store.findSlot(COLLECTION, "ARCHIVE_EDITOR")).thenReturn(
                new ArchiveMaintenanceStore.Slot(COLLECTION, "ARCHIVE_EDITOR", APPOINTMENT, 4));
        when(store.findAppointment(APPOINTMENT, false)).thenReturn(foreign);
        assertNull(service.slot(MANAGER, COLLECTION).currentAppointmentId());
        service.appointments(MANAGER, COLLECTION);
        verify(store).listAppointments(MANAGER, COLLECTION);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.revokeAppointment(MANAGER, APPOINTMENT, "key-revoke", 1,
                        new ArchiveAppointmentRevokeRequest(null))).code());
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void managementGrantDoesNotRevealOrModifyOtherOwnersJobsInSameCollection() {
        ArchiveMaintenanceJobRecord own = job("DRAFT_ONLY");
        ArchiveMaintenanceJobRecord foreign = new ArchiveMaintenanceJobRecord(own.jobId(), own.runId(),
                own.collectionId(), own.tenantId(), own.clientId(), "owner-b", own.appointmentId(),
                own.appointmentRevision(), own.agentId(), own.bindingVersion(), own.permissionProfile(),
                own.managerAuthorizationRevision(), own.publicationMode(), own.operation(), own.workId(), own.canonicalKey(), own.title(),
                own.sourceId(), own.sourceSha256(), own.sourceSummary(), own.rightsBasis(), own.state(),
                own.waitReason(), own.revision(), own.draftId(), own.publicationId(),
                own.requestIntentId(), own.requestSha256());
        when(store.findJob(JOB, false)).thenReturn(foreign);
        allowManager("job.create,draft.write,publish,validate");
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.getJob(MANAGER, JOB)).code());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.jobEvents(MANAGER, JOB, 0, 20)).code());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.getDraft(MANAGER, JOB)).code());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.updateDraft(MANAGER, JOB, "key-foreign", 0,
                        new ArchiveDraftUpdateRequest(List.of(), List.of()))).code());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.validate(MANAGER, JOB, "key-foreign", 0)).code());
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.publish(MANAGER, JOB, "key-foreign", 0,
                        new ArchivePublishRequest("val_1", null, "0"))).code());
        verify(store, never()).listJobEvents(anyString(), anyLong(), anyInt());
        verify(store, never()).findDraftByJob(anyString(), anyBoolean());
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        service.listJobs(MANAGER, COLLECTION, 20);
        verify(store).listJobs(MANAGER, COLLECTION, 20);
    }

    @Test
    void draftCasRejectsStaleRevisionAndSuccessfulWriteInvalidatesValidation() throws Exception {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveDraftUpdateRequest body = body("甲", "乙");
        ArchiveDraftRecord current = new ArchiveDraftRecord(DRAFT, JOB, 4, "VALIDATED",
                new ObjectMapper().writeValueAsString(body), SHA, 4L, "val_old");
        ArchiveDraftRecord updated = new ArchiveDraftRecord(DRAFT, JOB, 5, "EDITABLE",
                new ObjectMapper().writeValueAsString(body), "b".repeat(64), null, null);
        allowManager("draft.write");
        activeAppointment(appointment("ACTIVE", 1));
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findDraftByJob(JOB, true)).thenReturn(current);
        when(store.findDraftByJob(JOB, false)).thenReturn(updated);
        when(store.updateDraft(eq(DRAFT), eq(4L), eq(5L), eq("EDITABLE"), anyString(), anyString(),
                isNull(), isNull())).thenReturn(1);
        operation("PUT", "/archive/admin/v1/jobs/" + JOB + "/draft", "DRAFT", DRAFT);

        ArchiveMaintenanceException stale = assertThrows(ArchiveMaintenanceException.class,
                () -> service.updateDraft(MANAGER, JOB, "stale-key", 3, body));
        assertEquals("ARCHIVE_REVISION_CONFLICT", stale.code());
        assertEquals("4", stale.details().get("currentRevision"));

        ArchiveDraftDTO result = service.updateDraft(MANAGER, JOB, "write-key", 4, body);
        assertEquals("5", result.revision());
        assertNull(result.validatedRevision());
        assertNull(result.validationId());
        ArgumentCaptor<String> eventPayload = ArgumentCaptor.forClass(String.class);
        verify(store).appendJobEvent(eq(JOB), eq(1L), eq("DRAFT_UPDATED"), eventPayload.capture());
        var audit = new ObjectMapper().readTree(eventPayload.getValue());
        assertEquals("HUMAN", audit.path("actorType").asText());
        assertEquals("owner-a", audit.path("actorId").asText());
        assertEquals("3", audit.path("authorizationRevision").asText());
        assertEquals(DRAFT, audit.path("draftId").asText());
        assertEquals("5", audit.path("draftRevision").asText());
    }

    @Test
    void revokedAppointmentRejectsNativeContextAndThereIsNoRuntimePublishServiceMethod() {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        allowManager("draft.write");
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        // Native authorization is wired so this test reaches the revoked appointment check,
        // rather than stopping early at the default-disabled execution bridge.
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        service.setArchiveAgentExecutionPort(port);
        var properties = new cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        when(store.lockSlot(COLLECTION, "ARCHIVE_EDITOR"))
                .thenReturn(new ArchiveMaintenanceStore.Slot(COLLECTION, "ARCHIVE_EDITOR", null, 2));
        ArchiveRuntimeScope runtime = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-1", "execution-1", "command-1", 1, 1);

        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeContext(runtime, JOB, RUN));
        assertEquals(403, denied.status());
        assertEquals("ARCHIVE_ASSIGNMENT_CHANGED", denied.code());
        assertTrue(java.util.Arrays.stream(cn.jia.chat.archive.maintenance.service.ArchiveMaintenanceService.class
                .getMethods()).anyMatch(method -> method.getName().equals("runtimePublish")));
    }

    @Test
    void publishesValidatedTwoChapterEditionWithoutPrefaceInOneTransactionShape() throws Exception {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveAppointmentRecord appointment = appointment("ACTIVE", 1);
        ArchiveDraftUpdateRequest body = body("第一回正文", "第二回正文");
        byte[] source = "第一回第一回正文第二回第二回正文"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new AgentTaskArtifactStorage.StoredContent(source, SHA, source.length, "text/plain"));
        String json = new ObjectMapper().writeValueAsString(body);
        ArchiveDraftRecord draft = new ArchiveDraftRecord(DRAFT, JOB, 2, "VALIDATED", json,
                "b".repeat(64), 2L, "val_2");
        ArchiveValidationRecord validation = new ArchiveValidationRecord("val_2", DRAFT, 2,
                "PASSED", "c".repeat(64), "[]");
        allowManager("publish");
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findCurrentAppointment(COLLECTION, false)).thenReturn(appointment);
        activeAppointment(appointment);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7L, AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        when(store.findDraftByJob(JOB, false)).thenReturn(draft);
        when(store.findDraftByJob(JOB, true)).thenReturn(draft);
        when(store.findValidation("val_2")).thenReturn(validation);
        operation("POST", "/archive/admin/v1/jobs/" + JOB + "/publish", "PUBLICATION", "pub_1");
        ArchiveMaintenanceStore.CollectionWork collectionWork =
                new ArchiveMaintenanceStore.CollectionWork(COLLECTION, "wrk_new", "tiny-book", 1);
        when(store.lockCollectionWork(COLLECTION, "wrk_new")).thenReturn(null, collectionWork);
        ArchiveWorkRecord insertedWork = new ArchiveWorkRecord("wrk_new", "小书", null);
        when(content.lockWork("wrk_new")).thenReturn(null, insertedWork);
        when(content.markReady(anyString())).thenReturn(1);
        when(content.switchActiveEdition(eq("wrk_new"), anyString())).thenReturn(1);
        when(content.markActivated(anyString())).thenReturn(1);
        when(store.bumpCollectionWork(COLLECTION, "wrk_new", 1)).thenReturn(1);
        when(store.updateDraft(DRAFT, 2, 2, "SEALED", json, "b".repeat(64), 2L, "val_2")).thenReturn(1);
        when(store.updateJobState(JOB, 1, "PUBLISHED", null, "pub_1")).thenReturn(1);
        java.util.concurrent.atomic.AtomicReference<ArchivePublicationReadbackRecord> readback =
                new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(call -> {
            readback.set(call.getArgument(0));
            return null;
        }).when(store).insertPublicationReadback(any());
        when(store.findPublicationReadback(anyString())).thenAnswer(call -> {
            ArchivePublicationReadbackRecord value = readback.get();
            return value != null && value.publicationId().equals(call.getArgument(0)) ? value : null;
        });

        ArchivePublicationDTO publication = service.publish(MANAGER, JOB, "publish-key", 2,
                new ArchivePublishRequest("val_2", null, "0"));

        assertEquals("PUBLISHED", publication.state());
        ArgumentCaptor<ArchiveEditionRecord> edition = ArgumentCaptor.forClass(ArchiveEditionRecord.class);
        verify(content).insertEdition(edition.capture());
        assertEquals(2, edition.getValue().chapterCount());
        assertEquals(0, edition.getValue().prefaceParagraphCount());
        assertEquals(2, edition.getValue().chapterParagraphCount());
        assertTrue(edition.getValue().sourceUtf8ByteLength() > 1);
        ArgumentCaptor<ArchiveBlockRecord> blocks = ArgumentCaptor.forClass(ArchiveBlockRecord.class);
        verify(content, times(2)).insertBlock(blocks.capture());
        assertTrue(blocks.getAllValues().stream().allMatch(block -> "CHAPTER".equals(block.blockType())));
        verify(store).insertPublication(any(ArchivePublicationRecord.class));
        verify(store).appendJobEvent(eq(JOB), eq(2L), eq("PUBLICATION_COMMITTED"), anyString());
    }

    @Test
    void publicationReplayReturnsOriginalOnlyForSameCommittedKeyAndRevision() {
        ArchiveMaintenanceJobRecord published = new ArchiveMaintenanceJobRecord(JOB, RUN, COLLECTION,
                "0", "client-a", "owner-a", APPOINTMENT, 1, AGENT, "7", "DRAFT_ONLY", 3,
                "MANUAL", "ADD_WORK", "wrk_new", "tiny-book", "小书", "src_1", SHA,
                "固定来源 / 摘要", "已授权公开用途", "PUBLISHED", null, 2, DRAFT,
                "pub_1", "intent-1", "d".repeat(64));
        allowManager("publish");
        activeAppointment(appointment("ACTIVE", 1));
        when(store.findJob(JOB, false)).thenReturn(published);
        when(store.findJob(JOB, true)).thenReturn(published);
        ArchivePublicationRecord original = new ArchivePublicationRecord("pub_1", JOB, COLLECTION,
                "wrk_new", "aed_1", 2, SHA, SHA, "PUBLISHED", "HUMAN", "owner-a", 3);
        when(store.findPublicationByJob(JOB)).thenReturn(original);
        when(store.findPublicationReadback("pub_1")).thenReturn(
                new ArchivePublicationReadbackRecord("pub_1", "PENDING", 1, null, "[]", null));
        ArchivePublishRequest body = new ArchivePublishRequest("val_2", null, "0");
        when(store.findOperation(MANAGER, "same-key")).thenReturn(new ArchiveMaintenanceStore.Operation(
                false, "POST", "/archive/admin/v1/jobs/" + JOB + "/publish",
                publicationRequestSha(2, body), "PUBLICATION", "pub_1", "COMMITTED"));
        assertEquals("pub_1", service.publish(MANAGER, JOB, "same-key", 2, body).publicationId());
        ArchiveMaintenanceException differentRevision = assertThrows(ArchiveMaintenanceException.class,
                () -> service.publish(MANAGER, JOB, "same-key", 3, body));
        assertEquals("IDEMPOTENCY_CONFLICT", differentRevision.code());
        verify(sourceStorage, never()).read(any(), anyString(), anyString(), anyLong(), anyString());
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(content, never()).insertEdition(any());
    }

    @Test
    void pendingIdempotencyRecordCannotBeReplayed() {
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        allowManager("publish");
        activeAppointment(appointment("ACTIVE", 1));
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        ArchivePublishRequest body = new ArchivePublishRequest("val_2", null, "0");
        when(store.findOperation(MANAGER, "pending-key")).thenReturn(new ArchiveMaintenanceStore.Operation(
                false, "POST", "/archive/admin/v1/jobs/" + JOB + "/publish",
                publicationRequestSha(2, body), "PUBLICATION", "pub_1", "PENDING"));
        ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                () -> service.publish(MANAGER, JOB, "pending-key", 2, body));
        assertEquals("ARCHIVE_OPERATION_IN_PROGRESS", failure.code());
    }

    @Test
    void executionIsDefaultOffEvenWhenAResolverWouldReportVerified() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                () -> service.ensureExecution(MANAGER, JOB, "execute-disabled", 1));
        assertEquals("ARCHIVE_EXECUTION_DISABLED", failure.code());
        verifyNoInteractions(port);
    }

    @Test
    void expiredExecutionReplayFencesDurablyWithoutOnlineTargetOrReadmission() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        allowManager("job.manage");
        activeAppointment(appointment("ACTIVE", 1));
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findRun(RUN, true)).thenReturn(new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "AUTHORIZED", 2));
        ArchiveExecutionGrantRecord expired = new ArchiveExecutionGrantRecord(
                "grant-a", RUN, "0", "client-a", "owner-a", APPOINTMENT, 1, 3,
                AGENT, 7, "execution-a", "command-a", 1, 1, "runtime-a", new byte[32],
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED.name(), "psi_verified", 4,
                "archive-maintainer", "1.0.0", SHA, "dispatch-a", "e".repeat(64),
                "/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/context",
                1, "ACTIVE", 1);
        when(store.findExecutionGrant(RUN, true)).thenReturn(expired);
        when(store.beginOperation(eq(MANAGER), eq("expired-key"), eq("POST"), anyString(),
                anyString(), eq("EXECUTION"), anyString())).thenAnswer(call ->
                new ArchiveMaintenanceStore.Operation(false, "POST", call.getArgument(3),
                        call.getArgument(4), "EXECUTION", "grant-a", "COMMITTED"));
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(store.fenceRun(RUN)).thenReturn(1);
        when(store.updateJobState(JOB, 1, "WAITING_SKILL", "EXECUTION_EXPIRED", null)).thenReturn(1);

        ArchiveMaintenanceException failure = assertThrows(ArchiveMaintenanceException.class,
                () -> service.ensureExecution(MANAGER, JOB, "expired-key", 1));

        assertEquals("ARCHIVE_EXECUTION_EXPIRED", failure.code());
        verify(store).fenceRun(RUN);
        verify(store).updateJobState(JOB, 1, "WAITING_SKILL", "EXECUTION_EXPIRED", null);
        verify(port, never()).requireControlledTarget(any(), any());
        verify(port, never()).ensureExecution(any(), any());
    }

    @Test
    void exactExecutionGrantIsPersistedOnceAndCommittedReplayDoesNotAllocateAgain() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveAppointmentRecord appointment = appointment("ACTIVE", 1);
        allowManager("job.manage");
        activeAppointment(appointment);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        when(store.findRun(RUN, true)).thenReturn(
                new ArchiveJobRunRecord(RUN, JOB, 1, null, 1, "WAITING", 1),
                new ArchiveJobRunRecord(RUN, JOB, 1, "runtime-a", 1, "AUTHORIZED", 2));
        byte[] registration = new byte[32];
        registration[0] = 7;
        var proof = new InstalledSkillResolver.Proof(
                "psi_verified", 4, "archive-maintainer", "1.0.0", SHA);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        when(port.ensureExecution(any(), any())).thenAnswer(call -> {
            ArchiveAgentExecutionPort.Request request = call.getArgument(0);
            return new ArchiveAgentExecutionPort.Grant(request.grantRef(), request.executionRef(),
                    "cmd-exact", 1, request.executionEpoch(), 2_000_000_000_000L,
                    "runtime-a", registration, proof, false);
        });
        java.util.concurrent.atomic.AtomicReference<String> operationTarget = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ArchiveExecutionGrantRecord> saved = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        when(store.beginOperation(eq(MANAGER), eq("execute-key"), eq("POST"),
                eq("/archive/admin/v1/jobs/" + JOB + "/execute"), anyString(), eq("EXECUTION"), anyString()))
                .thenAnswer(call -> {
                    operationTarget.compareAndSet(null, call.getArgument(6));
                    return new ArchiveMaintenanceStore.Operation(!committed.get(), "POST", call.getArgument(3),
                            call.getArgument(4), "EXECUTION", operationTarget.get(),
                            committed.get() ? "COMMITTED" : "PENDING");
                });
        when(store.findExecutionGrant(RUN, false)).thenAnswer(call -> saved.get());
        when(store.findExecutionGrant(RUN, true)).thenAnswer(call -> saved.get());
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; })
                .when(store).insertExecutionGrant(any());
        doAnswer(call -> { committed.set(true); return null; })
                .when(store).commitOperation(eq(MANAGER), eq("execute-key"), anyString());
        when(store.activateRun(RUN, 1, "runtime-a")).thenReturn(1);
        when(store.updateJobState(JOB, 1, "EXECUTION_REQUESTED", "AGENT_DISPATCH_PENDING", null)).thenReturn(1);

        ArchiveExecutionDTO first = service.ensureExecution(MANAGER, JOB, "execute-key", 1);
        ArchiveExecutionDTO replay = service.ensureExecution(MANAGER, JOB, "execute-key", 1);
        assertEquals(first, replay);
        assertEquals("cmd-exact", first.commandId());
        assertEquals("psi_verified", first.installationRef());
        verify(port, times(1)).ensureExecution(any(), any());
        verify(store, times(1)).insertExecutionGrant(any());
        verify(store, times(1)).activateRun(RUN, 1, "runtime-a");
        ArgumentCaptor<ArchiveAgentExecutionPort.Request> request = ArgumentCaptor.forClass(ArchiveAgentExecutionPort.Request.class);
        verify(port).ensureExecution(request.capture(), any());
        assertTrue(request.getValue().dispatchKey().startsWith("adk_"));
        assertEquals(68, request.getValue().dispatchKey().length());
        assertEquals(InstalledSkillResolver.Origin.PLATFORM_PROVISIONED, request.getValue().skillOrigin());
        assertEquals("/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/context",
                request.getValue().contextRef());
    }

    @Test
    void managerRevocationPreflightsExactActorLocksAgentFirstAndFencesCurrentRuns() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        allowManager("appoint");
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION
                + "/manager-authorization/revoke", "MANAGER_AUTHORIZATION", COLLECTION);
        ArchiveMaintenanceStore.ManagerRunTarget target =
                new ArchiveMaintenanceStore.ManagerRunTarget(RUN, AGENT, "7");
        when(store.listUnfencedRunTargetsForManager(MANAGER, COLLECTION, false))
                .thenReturn(List.of(target));
        when(store.listUnfencedRunTargetsForManager(MANAGER, COLLECTION, true))
                .thenReturn(List.of(target));
        when(store.fenceRun(RUN)).thenReturn(1);
        when(store.revokeManagerGrant(MANAGER, COLLECTION, 3)).thenReturn(1);

        ArchiveOperationDTO result = service.revokeManagerAuthorization(MANAGER, COLLECTION,
                "manager-revoke", 3, new ArchiveManagerRevokeRequest("权限撤销"));

        assertEquals("COMMITTED", result.state());
        var order = inOrder(port, store);
        order.verify(store).findManagerGrant(MANAGER, COLLECTION, false);
        order.verify(store).listUnfencedRunTargetsForManager(MANAGER, COLLECTION, false);
        order.verify(port).lockIdentityRoot(any());
        order.verify(store).findManagerGrant(MANAGER, COLLECTION, true);
        order.verify(store).listUnfencedRunTargetsForManager(MANAGER, COLLECTION, true);
        order.verify(store).fenceRun(RUN);
        order.verify(store).revokeManagerGrant(MANAGER, COLLECTION, 3);
        verify(port, never()).requireControlledTarget(any(), any());
        verify(store, never()).findCurrentAppointment(anyString(), anyBoolean());
        verify(store, never()).insertManagerGrant(any());
    }

    @Test
    void changedManagerAuthorizationRevisionFencesAdmissionAndNativeBeforeProofInspection() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        when(store.findJob(JOB, false)).thenReturn(job);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), eq(true))).thenReturn(
                new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        "job.manage,draft.write", 4, "ACTIVE"));

        assertEquals("ARCHIVE_MANAGER_AUTHORIZATION_CHANGED", assertThrows(
                ArchiveMaintenanceException.class,
                () -> service.ensureExecution(MANAGER, JOB, "stale-manager", 1)).code());
        verify(port, never()).ensureExecution(any(), any());

        ArchiveRuntimeScope runtime = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-a", "execution-a", "command-a", 1, 1);
        assertEquals("ARCHIVE_MANAGER_AUTHORIZATION_CHANGED", assertThrows(
                ArchiveMaintenanceException.class,
                () -> service.runtimeContext(runtime, JOB, RUN)).code());
        verify(port, never()).inspectExecution(any(), any());
    }

    @Test
    void nativeWriteRequiresExactPersistedGrantCommandAttemptEpochAndCurrentProof() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveAppointmentRecord appointment = appointment("ACTIVE", 1);
        ArchiveExecutionGrantRecord grant = executionGrant("ACTIVE", 1);
        allowManager("draft.write");
        activeAppointment(appointment);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findRun(RUN, true)).thenReturn(new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "AUTHORIZED", 2));
        when(store.findExecutionGrant(RUN, true)).thenReturn(grant);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
        when(store.findAppointment(APPOINTMENT, false)).thenReturn(appointment);
        when(store.findDraftByJob(JOB, false)).thenReturn(new ArchiveDraftRecord(
                DRAFT, JOB, 0, "EDITABLE", "{\"blocks\":[],\"excludedSourceRanges\":[]}", SHA, null, null));
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        when(port.inspectExecution(any(), any())).thenReturn(new ArchiveAgentExecutionPort.Inspection(true, "SENT"));
        ArchiveRuntimeScope exact = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-a", "execution-a", "command-a", 1, 1);
        ArchiveRuntimeContextDTO context = service.runtimeContext(exact, JOB, RUN);
        assertEquals(JOB, context.jobId());
        assertEquals("wrk_new", context.workId());
        assertEquals("ADD_WORK", context.operation());
        assertEquals("0", context.expectedWorkRevision());
        assertNull(context.expectedActiveEditionId());
        ArchiveRuntimeScope wrongAttempt = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-a", "execution-a", "command-a", 2, 1);
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeContext(wrongAttempt, JOB, RUN)).code());
        ArchiveRuntimeScope wrongRuntime = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-b", "grant-a", "execution-a", "command-a", 1, 1);
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeContext(wrongRuntime, JOB, RUN)).code());
        verify(port, times(1)).inspectExecution(any(), any());
    }

    @Test
    void nativeStartClaimsExactMessageAndExactReplayIsStable() {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveAppointmentRecord appointment = appointment("ACTIVE", 1);
        ArchiveMaintenanceJobRecord requested = jobState("EXECUTION_REQUESTED", null, 2);
        ArchiveMaintenanceJobRecord running = jobState("RUNNING", null, 3);
        ArchiveJobRunRecord authorized = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, null, null, null, null, "AUTHORIZED", 2);
        ArchiveJobRunRecord started = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-a", null, null, null, "RUNNING", 3);
        allowManager("draft.write");
        activeAppointment(appointment);
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(requested, running);
        when(store.findJob(JOB, true)).thenReturn(requested, running);
        when(store.findRun(RUN, true)).thenReturn(authorized, started);
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("ACTIVE", 1));
        when(store.findDraftByJob(JOB, false)).thenReturn(emptyDraft());
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "STARTED", "message-a"));
        when(store.startRun(RUN, 2, "message-a")).thenReturn(1);
        when(store.updateJobState(JOB, 2, "RUNNING", null, null)).thenReturn(1);
        ArchiveRuntimeScope runtime = runtime();
        ArchiveRuntimeStartRequest claim = new ArchiveRuntimeStartRequest(
                "command-a", "message-a", "1", "1");

        ArchiveRuntimeResultDTO first = service.runtimeStart(runtime, JOB, RUN, claim);
        ArchiveRuntimeResultDTO replay = service.runtimeStart(runtime, JOB, RUN, claim);

        assertEquals("RUNNING", first.runState());
        assertEquals("RUNNING", replay.runState());
        assertEquals("3", replay.runRevision());
        verify(store, times(1)).startRun(RUN, 2, "message-a");
        verify(store, times(1)).appendJobEvent(eq(JOB), eq(3L), eq("EXECUTION_STARTED"), anyString());
    }

    @Test
    void nativeStartRejectsNonCurrentMessageWithoutClaiming() {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord requested = jobState("EXECUTION_REQUESTED", null, 2);
        allowManager("draft.write");
        activeAppointment(appointment("ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(requested);
        when(store.findJob(JOB, true)).thenReturn(requested);
        when(store.findRun(RUN, true)).thenReturn(new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "AUTHORIZED", 2));
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("ACTIVE", 1));
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "SENT", "message-current"));

        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeStart(runtime(), JOB, RUN,
                        new ArchiveRuntimeStartRequest("command-a", "message-stale", "1", "1")));

        assertEquals("EXECUTION_FENCED", denied.code());
        verify(store, never()).startRun(anyString(), anyLong(), anyString());
    }

    @Test
    void nativeFailureReleasesProducerAndExactTerminalReplayUsesReadOnlyReceipt() {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord runningJob = jobState("RUNNING", null, 3);
        ArchiveMaintenanceJobRecord failedJob = jobState("FAILED", "RUNNER_CRASH", 4);
        ArchiveJobRunRecord running = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-a", null, null, null, "RUNNING", 3);
        ArchiveJobRunRecord failed = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-a", "RUNNER", "RUNNER_CRASH", true, "FAILED", 4);
        allowManager("draft.write");
        activeAppointment(appointment("ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(runningJob, failedJob);
        when(store.findJob(JOB, true)).thenReturn(runningJob, failedJob);
        when(store.findRun(RUN, true)).thenReturn(running, failed);
        when(store.findExecutionGrant(RUN, true)).thenReturn(
                executionGrant("ACTIVE", 1), executionGrant("READ_ONLY", 1));
        when(store.findDraftByJob(JOB, false)).thenReturn(emptyDraft());
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "STARTED", "message-a"));
        when(port.inspectResult(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "FAILED", "message-a"));
        when(store.failRun(RUN, 3, "RUNNER", "RUNNER_CRASH", true)).thenReturn(1);
        when(store.releaseExecutionGrant(RUN, 1)).thenReturn(1);
        when(store.updateJobState(JOB, 3, "FAILED", "RUNNER_CRASH", null)).thenReturn(1);
        ArchiveRuntimeFailureRequest failure = new ArchiveRuntimeFailureRequest(
                "RUNNER", "RUNNER_CRASH", true);

        ArchiveRuntimeResultDTO first = service.runtimeFailure(runtime(), JOB, RUN, failure);
        ArchiveRuntimeResultDTO replay = service.runtimeFailure(runtime(), JOB, RUN, failure);

        assertEquals("FAILED", first.runState());
        assertEquals("RUNNER_CRASH", replay.failureCode());
        verify(store, times(1)).failRun(RUN, 3, "RUNNER", "RUNNER_CRASH", true);
        verify(store, times(1)).releaseExecutionGrant(RUN, 1);
        verify(port).inspectResult(any(), any());
    }

    @Test
    void currentDeliveryMessageFencesRunningProducerFailureAndResultBeforeMutation() {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord runningJob = jobState("RUNNING", null, 3);
        ArchiveJobRunRecord running = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-original", null, null, null, "RUNNING", 3);
        allowManager("draft.write,validate");
        activeAppointment(appointment("ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(runningJob);
        when(store.findJob(JOB, true)).thenReturn(runningJob);
        when(store.findRun(RUN, true)).thenReturn(running);
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("ACTIVE", 1));
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "STARTED", "message-replaced"));

        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeUpdateDraft(runtime(), JOB, RUN, "write-stale-message", 0,
                        new ArchiveDraftUpdateRequest(List.of(), List.of()))).code());
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeFailure(runtime(), JOB, RUN,
                        new ArchiveRuntimeFailureRequest("RUNNER", "RUNNER_CRASH", true))).code());
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(runtime(), JOB, RUN)).code());
        verify(store, never()).updateDraft(anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), any(), any());
        verify(store, never()).failRun(anyString(), anyLong(), anyString(), anyString(), anyBoolean());
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void terminalReceiptRequiresPersistedStartedMessageToMatchCurrentDelivery() {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord failedJob = jobState("FAILED", "RUNNER_CRASH", 4);
        ArchiveJobRunRecord failed = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-original", "RUNNER", "RUNNER_CRASH",
                true, "FAILED", 4);
        allowManager("draft.write");
        activeAppointment(appointment("ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(failedJob);
        when(store.findJob(JOB, true)).thenReturn(failedJob);
        when(store.findRun(RUN, true)).thenReturn(failed);
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("READ_ONLY", 1));
        when(port.inspectResult(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "FAILED", "message-replaced"));

        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(runtime(), JOB, RUN)).code());
        verify(store, never()).findDraftByJob(anyString(), anyBoolean());
    }

    @Test
    void draftOnlySuccessfulRuntimeValidationCompletesRunAndReleasesGrant() throws Exception {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord runningJob = jobState("RUNNING", null, 3);
        ArchiveJobRunRecord running = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-a", null, null, null, "RUNNING", 3);
        ArchiveDraftUpdateRequest body = body("甲", "乙");
        byte[] source = "第一回甲第二回乙".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bodyJson = new ObjectMapper().writeValueAsString(body);
        ArchiveDraftRecord draft = new ArchiveDraftRecord(DRAFT, JOB, 1, "EDITABLE",
                bodyJson, "b".repeat(64), null, null);
        allowManager("draft.write,validate");
        activeAppointment(appointment("ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(runningJob);
        when(store.findJob(JOB, true)).thenReturn(runningJob);
        when(store.findRun(RUN, true)).thenReturn(running);
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("ACTIVE", 1));
        when(store.findDraftByJob(JOB, true)).thenReturn(draft);
        when(store.findSource("src_1")).thenReturn(new ArchiveSourceSnapshotRecord(
                "src_1", COLLECTION, "0", "client-a", "owner-a", "cyf-artifact://source",
                SHA, source.length, "固定来源", "摘要", "已授权公开用途", "UTF8_EXACT_V1", "READY"));
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new AgentTaskArtifactStorage.StoredContent(source, SHA, source.length, "text/plain"));
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "STARTED", "message-a"));
        operation("POST", "/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/validate",
                "VALIDATION", "val_native");
        when(store.updateDraft(eq(DRAFT), eq(1L), eq(1L), eq("VALIDATED"),
                eq(bodyJson), eq("b".repeat(64)), eq(1L), eq("val_native"))).thenReturn(1);
        when(store.updateJobState(JOB, 3, "AWAITING_PUBLISH", null, null)).thenReturn(1);
        when(store.completeRun(RUN, 3)).thenReturn(1);
        when(store.releaseExecutionGrant(RUN, 1)).thenReturn(1);

        ArchiveValidationDTO validation = service.runtimeValidate(runtime(), JOB, RUN,
                "validate-native", 1);

        assertEquals("PASSED", validation.outcome());
        verify(store).completeRun(RUN, 3);
        verify(store).releaseExecutionGrant(RUN, 1);
        verify(store).appendJobEvent(eq(JOB), eq(4L), eq("EXECUTION_COMPLETED"), anyString());
    }

    @Test
    void manualPublishValidatedValidationStillCompletesAndReleasesProducer() throws Exception {
        ArchiveValidationDTO validation = successfulRuntimeValidation("MANUAL", "PUBLISH_VALIDATED");
        assertEquals("PASSED", validation.outcome());
        verify(store).completeRun(RUN, 3);
        verify(store).releaseExecutionGrant(RUN, 1);
    }

    @Test
    void autoPublishValidatedValidationRetainsProducerOnlyForNativePublish() throws Exception {
        ArchiveValidationDTO validation = successfulRuntimeValidation("AUTO", "PUBLISH_VALIDATED");
        assertEquals("PASSED", validation.outcome());
        verify(store, never()).completeRun(anyString(), anyLong());
        verify(store, never()).releaseExecutionGrant(anyString(), anyLong());
    }

    private ArchiveValidationDTO successfulRuntimeValidation(String publicationMode,
            String permissionProfile) throws Exception {
        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceJobRecord runningJob = executionJob(permissionProfile, publicationMode,
                "RUNNING", 3);
        ArchiveJobRunRecord running = new ArchiveJobRunRecord(
                RUN, JOB, 1, "runtime-a", 1, "message-a", null, null, null, "RUNNING", 3);
        ArchiveDraftUpdateRequest body = body("甲", "乙");
        byte[] source = "第一回甲第二回乙".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String bodyJson = new ObjectMapper().writeValueAsString(body);
        ArchiveDraftRecord draft = new ArchiveDraftRecord(DRAFT, JOB, 1, "EDITABLE",
                bodyJson, "b".repeat(64), null, null);
        allowManager("draft.write,validate,publish");
        activeAppointment(appointment(permissionProfile, "ACTIVE", 1));
        allowCurrentBinding();
        when(store.findJob(JOB, false)).thenReturn(runningJob);
        when(store.findJob(JOB, true)).thenReturn(runningJob);
        when(store.findRun(RUN, true)).thenReturn(running);
        when(store.findExecutionGrant(RUN, true)).thenReturn(executionGrant("ACTIVE", 1));
        when(store.findDraftByJob(JOB, true)).thenReturn(draft);
        when(store.findSource("src_1")).thenReturn(new ArchiveSourceSnapshotRecord(
                "src_1", COLLECTION, "0", "client-a", "owner-a", "cyf-artifact://source",
                SHA, source.length, "固定来源", "摘要", "已授权公开用途", "UTF8_EXACT_V1", "READY"));
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(new AgentTaskArtifactStorage.StoredContent(source, SHA, source.length, "text/plain"));
        when(port.inspectExecution(any(), any())).thenReturn(
                new ArchiveAgentExecutionPort.Inspection(true, "STARTED", "message-a"));
        operation("POST", "/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/validate",
                "VALIDATION", "val_mode");
        when(store.updateDraft(eq(DRAFT), eq(1L), eq(1L), eq("VALIDATED"),
                eq(bodyJson), eq("b".repeat(64)), eq(1L), eq("val_mode"))).thenReturn(1);
        when(store.updateJobState(JOB, 3, "AWAITING_PUBLISH", null, null)).thenReturn(1);
        when(store.completeRun(RUN, 3)).thenReturn(1);
        when(store.releaseExecutionGrant(RUN, 1)).thenReturn(1);
        return service.runtimeValidate(runtime(), JOB, RUN, "validate-mode", 1);
    }

    private ArchiveDraftUpdateRequest body(String first, String second) {
        int title1 = "第一回".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int text1 = first.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int title2 = "第二回".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int text2 = second.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return new ArchiveDraftUpdateRequest(List.of(
                new ArchiveDraftBlockInput("CHAPTER", "one", 1, "第一回",
                        List.of(new ArchiveSourceRangeInput(0, title1)),
                        List.of(new ArchiveDraftParagraphInput(1, first,
                                List.of(new ArchiveSourceRangeInput(title1, title1 + text1))))),
                new ArchiveDraftBlockInput("CHAPTER", "two", 2, "第二回",
                        List.of(new ArchiveSourceRangeInput(title1 + text1, title1 + text1 + title2)),
                        List.of(new ArchiveDraftParagraphInput(1, second,
                                List.of(new ArchiveSourceRangeInput(title1 + text1 + title2,
                                        title1 + text1 + title2 + text2)))))), List.of());
    }

    @Test
    void exactBlockAndPatchPersistImmutableNullableSnapshotsAndHumanTakeoverNeedsNoAppointment() throws Exception {
        allowManager("draft.write");
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveDraftUpdateRequest initial = body("甲", "乙");
        String initialJson = new ObjectMapper().writeValueAsString(initial);
        ArchiveDraftRecord revision4 = new ArchiveDraftRecord(DRAFT, JOB, 4, "CHANGES_REQUIRED",
                initialJson, "b".repeat(64), null, null);
        when(store.findDraft(DRAFT, false)).thenReturn(revision4);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findDraftByJob(JOB, true)).thenReturn(revision4);
        ArchiveDraftBlockInput replacement = new ArchiveDraftBlockInput("CHAPTER", "one", 1,
                "第一回", initial.blocks().getFirst().titleSourceRanges(),
                initial.blocks().getFirst().paragraphs());
        String blockPath = "/archive/admin/v1/drafts/" + DRAFT + "/blocks/one";
        when(store.beginOperation(eq(MANAGER), eq("block-key"), eq("PUT"), eq(blockPath),
                anyString(), eq("DRAFT"), eq(DRAFT))).thenAnswer(call ->
                new ArchiveMaintenanceStore.Operation(true, "PUT", blockPath,
                        call.getArgument(4), "DRAFT", DRAFT, "PENDING"));
        when(store.findAdminOperationByKey(MANAGER, "block-key", true)).thenReturn(null);
        when(store.updateDraft(eq(DRAFT), eq(4L), eq(5L), eq("EDITABLE"), anyString(),
                anyString(), isNull(), isNull())).thenReturn(1);
        ArchiveDraftRecord revision5 = new ArchiveDraftRecord(DRAFT, JOB, 5, "EDITABLE",
                initialJson, "c".repeat(64), null, null);
        when(store.findDraftByJob(JOB, false)).thenReturn(revision5);
        when(store.commitAdminOperation(anyString(), anyString())).thenReturn(1);
        ArgumentCaptor<ArchiveAdminOperationRecord> receipts =
                ArgumentCaptor.forClass(ArchiveAdminOperationRecord.class);
        ArgumentCaptor<String> snapshots = ArgumentCaptor.forClass(String.class);

        ArchiveDraftBlockDTO block = service.putDraftBlock(MANAGER, DRAFT, "one",
                "block-key", 4, replacement);

        assertEquals("5", block.revision());
        verify(store).insertAdminOperation(receipts.capture());
        verify(store).commitAdminOperation(eq(receipts.getValue().operationId()), snapshots.capture());
        ArchiveDraftBlockDTO storedBlock = new ObjectMapper().readValue(
                snapshots.getValue(), ArchiveDraftBlockDTO.class);
        assertEquals(block, storedBlock);

        reset(store);
        allowManager("draft.write");
        when(store.findDraft(DRAFT, false)).thenReturn(revision5);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findDraftByJob(JOB, true)).thenReturn(revision5);
        String patchPath = "/archive/admin/v1/drafts/" + DRAFT;
        when(store.beginOperation(eq(MANAGER), eq("patch-key"), eq("PATCH"), eq(patchPath),
                anyString(), eq("DRAFT"), eq(DRAFT))).thenAnswer(call ->
                new ArchiveMaintenanceStore.Operation(true, "PATCH", patchPath,
                        call.getArgument(4), "DRAFT", DRAFT, "PENDING"));
        when(store.findAdminOperationByKey(MANAGER, "patch-key", true)).thenReturn(null);
        when(store.updateDraft(eq(DRAFT), eq(5L), eq(6L), eq("EDITABLE"), anyString(),
                anyString(), isNull(), isNull())).thenReturn(1);
        ArchiveDraftRecord revision6 = new ArchiveDraftRecord(DRAFT, JOB, 6, "EDITABLE",
                initialJson, "d".repeat(64), null, null);
        when(store.findDraftByJob(JOB, false)).thenReturn(revision6);
        when(store.commitAdminOperation(anyString(), anyString())).thenReturn(1);
        ArchiveDraftPatchRequest patch = new ArchiveDraftPatchRequest(List.of(
                new ArchiveDraftBlockMetadataPatch("one", 1, "第一回",
                        replacement.titleSourceRanges())), null);

        ArchiveDraftDTO patched = service.patchDraft(MANAGER, DRAFT, "patch-key", 5, patch);

        ArgumentCaptor<ArchiveAdminOperationRecord> patchReceipt =
                ArgumentCaptor.forClass(ArchiveAdminOperationRecord.class);
        ArgumentCaptor<String> patchSnapshot = ArgumentCaptor.forClass(String.class);
        verify(store).insertAdminOperation(patchReceipt.capture());
        verify(store).commitAdminOperation(eq(patchReceipt.getValue().operationId()),
                patchSnapshot.capture());
        ArchiveDraftDTO storedPatch = new ObjectMapper().readValue(
                patchSnapshot.getValue(), ArchiveDraftDTO.class);
        assertEquals(patched, storedPatch);
        assertNull(storedPatch.validatedRevision());
        assertNull(storedPatch.validationId());

        ArchiveAdminOperationRecord committed = new ArchiveAdminOperationRecord(
                patchReceipt.getValue().operationId(), "0", "client-a", "owner-a", "patch-key",
                COLLECTION, JOB, DRAFT, "DRAFT_PATCH", 3, "COMMITTED", patchSnapshot.getValue());
        when(store.findAdminOperationById(committed.operationId(), false)).thenReturn(committed);
        when(store.findAdminOperationById(committed.operationId(), true)).thenReturn(committed);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findJob(JOB, true)).thenReturn(job);
        when(store.findOperation(MANAGER, "patch-key")).thenReturn(
                new ArchiveMaintenanceStore.Operation(false, "PATCH", patchPath, "e".repeat(64),
                        "DRAFT", DRAFT, "COMMITTED"));
        ArchiveAdminOperationDTO status = service.operation(MANAGER, committed.operationId());
        assertTrue(status.result().containsKey("validatedRevision"));
        assertNull(status.result().get("validatedRevision"));
        assertNull(status.result().get("validationId"));
        verify(store, never()).findCurrentAppointment(anyString(), anyBoolean());
        verify(store, never()).findAppointment(anyString(), anyBoolean());
    }

    @Test
    void appointmentSkillReadinessIsReadOnlyAdditiveAndNeverUnlocksExecution() {
        allowManager("appoint");
        ArchiveAppointmentRecord active=appointment("ACTIVE",1);
        when(store.listAppointments(MANAGER,COLLECTION)).thenReturn(List.of(active));
        InstalledSkillResolver resolver=mock(InstalledSkillResolver.class);
        when(resolver.resolve(any())).thenReturn(new InstalledSkillResolver.Resolution(
                InstalledSkillResolver.State.VERIFIED,new InstalledSkillResolver.Proof(
                        "psi_verified",4,"archive-maintainer","1.0.0",SHA)));
        service.setInstalledSkillResolver(resolver);

        ArchiveAppointmentDTO result=service.appointments(MANAGER,COLLECTION).getFirst();
        assertEquals("CLIENT_UPDATE_REQUIRED",result.readiness());
        assertEquals("VERIFIED",result.skillReadiness().state());
        assertEquals("psi_verified",result.skillReadiness().proof().installationRef());
        assertEquals("4",result.skillReadiness().proof().revision());
        assertFalse(result.skillReadiness().executable());
        assertEquals("EXECUTION_NOT_WIRED",result.skillReadiness().blocker());
        ArgumentCaptor<InstalledSkillResolver.Request> request=ArgumentCaptor.forClass(InstalledSkillResolver.Request.class);
        verify(resolver).resolve(request.capture());
        assertEquals(new InstalledSkillResolver.Request("0","client-a","owner-a",AGENT,7,
                InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"archive-maintainer","1.0.0",SHA),request.getValue());
        verify(store,never()).lockSlot(anyString(),anyString());
        verify(store,never()).beginOperation(any(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void revokedOrForeignAppointmentNeverQueriesOrLeaksInstalledProof() {
        allowManager("appoint");
        InstalledSkillResolver resolver=mock(InstalledSkillResolver.class);
        service.setInstalledSkillResolver(resolver);
        ArchiveAppointmentRecord revoked=appointment("REVOKED",2);
        when(store.listAppointments(MANAGER,COLLECTION)).thenReturn(List.of(revoked));
        ArchiveAppointmentDTO result=service.appointments(MANAGER,COLLECTION).getFirst();
        assertEquals("REVOKED",result.skillReadiness().state());assertNull(result.skillReadiness().proof());

        ArchiveAppointmentRecord own=appointment("ACTIVE",1);
        ArchiveAppointmentRecord foreign=new ArchiveAppointmentRecord(own.appointmentId(),own.collectionId(),own.roleCode(),
                own.tenantId(),own.clientId(),"owner-b",own.agentId(),own.bindingVersion(),own.workScopeMode(),own.workIds(),
                own.permissionProfile(),own.requiredSkillKey(),own.requiredSkillVersion(),own.requiredSkillSha256(),
                own.status(),own.revision(),own.createdAt(),own.revokedAt());
        when(store.listAppointments(MANAGER,COLLECTION)).thenReturn(List.of(foreign));
        result=service.appointments(MANAGER,COLLECTION).getFirst();
        assertEquals("UNAVAILABLE",result.skillReadiness().state());assertNull(result.skillReadiness().proof());
        verifyNoInteractions(resolver);
    }

    @Test
    void managerConfirmationPersistsImmutableServerIntentBeforeCommonRequestAndReplays() throws Exception {
        allowManager("job.create");
        ArchiveAppointmentRecord active = appointment("ACTIVE", 1);
        activeAppointment(active);
        allowCurrentBinding();
        when(store.lockCollectionWork(COLLECTION, "work-1")).thenReturn(
                new ArchiveMaintenanceStore.CollectionWork(COLLECTION, "work-1", "tiny-book", 1));
        when(content.lockWork("work-1")).thenReturn(new ArchiveWorkRecord("work-1", "小书", null));
        ArchiveMaintenanceRequest business = new ArchiveMaintenanceRequest(COLLECTION,
                "REVISE_WORK", null, "work-1", "src_1", "MANUAL");
        java.util.concurrent.atomic.AtomicReference<String> confirmationSha =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<ArchiveConfirmedRequestRecord> confirmation =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(store.beginOperation(eq(MANAGER), eq("manager-key"), eq("POST"),
                eq("/archive/admin/v1/collections/" + COLLECTION + "/requests"), anyString(),
                eq("REQUEST_CONFIRMATION"), anyString())).thenAnswer(call -> {
                    boolean created = confirmationSha.compareAndSet(null, call.getArgument(4));
                    return new ArchiveMaintenanceStore.Operation(created, "POST", call.getArgument(3),
                            confirmationSha.get(), "REQUEST_CONFIRMATION", "acf_server",
                            created ? "PENDING" : "COMMITTED");
                });
        doAnswer(call -> { confirmation.set(call.getArgument(0)); return null; })
                .when(store).insertConfirmedRequest(any());
        when(store.findConfirmedRequest(eq(MANAGER), eq("acf_server"), anyBoolean()))
                .thenAnswer(call -> confirmation.get());
        java.util.concurrent.atomic.AtomicReference<ArchiveMaintenanceJobRecord> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(store.findJobByIntent(eq(MANAGER), anyString(), eq(true)))
                .thenAnswer(call -> saved.get());
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; })
                .when(store).insertJob(any());
        when(store.findJob(anyString(), eq(false))).thenAnswer(call -> saved.get());
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION + "/jobs", "JOB", "job-op");

        ArchiveMaintenanceRequestResultDTO first = service.confirmRequest(
                MANAGER, COLLECTION, "manager-key", business);
        ArchiveMaintenanceRequestResultDTO replay = service.confirmRequest(
                MANAGER, COLLECTION, "manager-key", business);

        assertEquals("acf_server", first.confirmationRef());
        assertEquals("acf_server", replay.confirmationRef());
        assertEquals(first.job().jobId(), replay.job().jobId());
        assertTrue(confirmation.get().requestIntentId().startsWith("ari_"));
        assertNotEquals("manager-key", confirmation.get().requestIntentId());
        assertEquals("MANUAL", confirmation.get().publicationModeCeiling());
        assertEquals(business, new ObjectMapper().readValue(
                confirmation.get().requestJson(), ArchiveMaintenanceRequest.class));
        verify(store, times(1)).insertConfirmedRequest(any());
        verify(store, times(1)).insertJob(any());

        ArchiveMaintenanceRequest changed = new ArchiveMaintenanceRequest(COLLECTION,
                "REVISE_WORK", null, "work-1", "src_1", "AUTO");
        ArchiveMaintenanceException conflict = assertThrows(ArchiveMaintenanceException.class,
                () -> service.confirmRequest(MANAGER, COLLECTION, "manager-key", changed));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.code());
        verify(store, times(1)).insertConfirmedRequest(any());
    }

    @Test
    void chatConfirmationBindsOneCanonicalServerTurnAndRejectsForeignOrDriftedReuse()
            throws Exception {
        ArchiveMaintenanceRequest business = new ArchiveMaintenanceRequest(COLLECTION,
                "REVISE_WORK", null, "work-1", "src_1", "MANUAL");
        String json = new ObjectMapper().writeValueAsString(business);
        String sha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.util.concurrent.atomic.AtomicReference<ArchiveConfirmedRequestRecord> record =
                new java.util.concurrent.atomic.AtomicReference<>(new ArchiveConfirmedRequestRecord(
                        "acf_server", "ari_server", "0", "client-a", "owner-a", COLLECTION,
                        json, sha, "MANUAL", null, null, null, null, null, null, 1));
        when(store.findConfirmedRequest(eq(MANAGER), eq("acf_server"), eq(true)))
                .thenAnswer(call -> record.get());
        when(store.bindConfirmedRequest(eq(MANAGER), eq("acf_server"), eq(1L),
                eq("conversation-1"), eq("901"), eq(4L), eq("b".repeat(64)),
                eq("DIRECT_PRIVATE"), eq(AGENT))).thenAnswer(call -> {
                    ArchiveConfirmedRequestRecord old = record.get();
                    record.set(new ArchiveConfirmedRequestRecord(old.confirmationRef(),
                            old.requestIntentId(), old.tenantId(), old.clientId(), old.ownerJiacn(),
                            old.collectionId(), old.requestJson(), old.requestSha256(),
                            old.publicationModeCeiling(), "conversation-1", "901", 4L,
                            "b".repeat(64), "DIRECT_PRIVATE", AGENT, 2));
                    return 1;
                });
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        Supplier<String> writer = () -> {
            writes.incrementAndGet();
            return "901";
        };

        ArchiveRequestContext first = service.bindChatConfirmation(MANAGER, "acf_server",
                "conversation-1", 4, "b".repeat(64), "DIRECT_PRIVATE", AGENT, writer);
        ArchiveRequestContext retry = service.bindChatConfirmation(MANAGER, "acf_server",
                "conversation-1", 4, "b".repeat(64), "DIRECT_PRIVATE", AGENT, writer);

        assertEquals("ari_server", first.requestIntentId());
        assertEquals("conversation-1:901", first.conversationRef());
        assertEquals(first, retry);
        assertEquals(1, writes.get());
        assertEquals(business.operation(), first.confirmedPolicyRef().operation());
        assertEquals(business.sourceId(), first.confirmedPolicyRef().sourceId());

        ArchiveMaintenanceException turnMismatch = assertThrows(ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(MANAGER, "acf_server", "conversation-1", 4,
                        "c".repeat(64), "DIRECT_PRIVATE", AGENT, writer));
        assertEquals("ARCHIVE_CONFIRMATION_BINDING_MISMATCH", turnMismatch.code());
        ArchiveMaintenanceException conversationMismatch = assertThrows(
                ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(MANAGER, "acf_server", "conversation-2", 4,
                        "b".repeat(64), "DIRECT_PRIVATE", AGENT, writer));
        assertEquals("ARCHIVE_CONFIRMATION_BINDING_MISMATCH", conversationMismatch.code());
        ArchiveMaintenanceException generationMismatch = assertThrows(
                ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(MANAGER, "acf_server", "conversation-1", 5,
                        "b".repeat(64), "DIRECT_PRIVATE", AGENT, writer));
        assertEquals("ARCHIVE_CONFIRMATION_BINDING_MISMATCH", generationMismatch.code());
        ArchiveMaintenanceException entryMismatch = assertThrows(ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(MANAGER, "acf_server", "conversation-1", 4,
                        "b".repeat(64), "SONGJIANG", null, writer));
        assertEquals("ARCHIVE_CONFIRMATION_BINDING_MISMATCH", entryMismatch.code());
        ArchiveMaintenanceException foreign = assertThrows(ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(
                        new ArchiveActorScope("0", "client-a", "owner-b"), "acf_server",
                        "conversation-1", 4, "b".repeat(64), "DIRECT_PRIVATE", AGENT, writer));
        assertEquals("ARCHIVE_CONFIRMATION_NOT_AVAILABLE", foreign.code());
        assertEquals(1, writes.get());
    }

    @Test
    void restrictedRequestRejectsSourceAndPublicationWideningBeforeStoreAccess() {
        ArchiveRequestContext context = new ArchiveRequestContext(MANAGER, "intent-m7", "SONGJIANG",
                "conversation-1:turn-1", null,
                new ArchiveConfirmedPolicyRef("policy-m7", COLLECTION, "REVISE_WORK",
                        null, "work-1", "src_1", "MANUAL"));
        clearInvocations(store, content, identities, sourceStorage);
        ArchiveMaintenanceException sourceDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(context, new ArchiveMaintenanceRequest(COLLECTION,
                        "REVISE_WORK", null, "work-1", "src_other", "MANUAL")));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", sourceDenied.code());
        ArchiveMaintenanceException modeDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(context, new ArchiveMaintenanceRequest(COLLECTION,
                        "REVISE_WORK", null, "work-1", "src_1", "AUTO")));
        assertEquals("ARCHIVE_PUBLICATION_MODE_WIDENING", modeDenied.code());
        verifyNoInteractions(content, identities);
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void restrictedRequestRejectsOperationWorkAndNewWorkChangesBeforeStoreAccess() {
        ArchiveRequestContext revise = new ArchiveRequestContext(MANAGER, "intent-revise", "SONGJIANG",
                "conversation-1:turn-2", null,
                new ArchiveConfirmedPolicyRef("policy-revise", COLLECTION, "REVISE_WORK",
                        null, "work-1", "src_1", "AUTO"));
        ArchiveMaintenanceException workDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(revise, new ArchiveMaintenanceRequest(COLLECTION,
                        "REVISE_WORK", null, "work-2", "src_1", "MANUAL")));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", workDenied.code());

        ArchiveNewWorkRequest fixedNewWork = new ArchiveNewWorkRequest(
                "fixed-key", "固定书名", "zh-CN");
        ArchiveRequestContext add = new ArchiveRequestContext(MANAGER, "intent-add", "SONGJIANG",
                "conversation-1:turn-3", null,
                new ArchiveConfirmedPolicyRef("policy-add", COLLECTION, "ADD_WORK",
                        fixedNewWork, null, "src_1", "MANUAL"));
        ArchiveMaintenanceException titleDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(add, new ArchiveMaintenanceRequest(COLLECTION,
                        "ADD_WORK", new ArchiveNewWorkRequest("fixed-key", "被模型改写", "zh-CN"),
                        null, "src_1", "MANUAL")));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", titleDenied.code());
        ArchiveMaintenanceException operationDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(add, new ArchiveMaintenanceRequest(COLLECTION,
                        "REVISE_WORK", null, "work-1", "src_1", "MANUAL")));
        assertEquals("ARCHIVE_REQUEST_POLICY_VIOLATION", operationDenied.code());
        verifyNoInteractions(content, identities);
        verify(store, never()).beginOperation(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void sameConfirmedIntentAcrossAllEntriesAndSameTurnRetryReturnsOneDurableJob() {
        allowManager("job.create");
        ArchiveAppointmentRecord active = appointment("ACTIVE", 1);
        activeAppointment(active);
        allowCurrentBinding();
        when(store.lockCollectionWork(COLLECTION, "work-1")).thenReturn(
                new ArchiveMaintenanceStore.CollectionWork(COLLECTION, "work-1", "tiny-book", 1));
        when(content.lockWork("work-1")).thenReturn(new ArchiveWorkRecord("work-1", "小书", null));
        java.util.concurrent.atomic.AtomicReference<ArchiveMaintenanceJobRecord> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(store.findJobByIntent(eq(MANAGER), eq("confirmed-shared"), eq(true)))
                .thenAnswer(call -> saved.get());
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; })
                .when(store).insertJob(any());
        when(store.findJob(anyString(), eq(false))).thenAnswer(call -> {
            ArchiveMaintenanceJobRecord job = saved.get();
            return job != null && job.jobId().equals(call.getArgument(0)) ? job : null;
        });
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION + "/jobs", "JOB", "aj_route");
        ArchiveMaintenanceRequest business = new ArchiveMaintenanceRequest(COLLECTION,
                "REVISE_WORK", null, "work-1", "src_1", "MANUAL");
        ArchiveConfirmedPolicyRef policy = new ArchiveConfirmedPolicyRef("confirmed:confirmed-shared",
                COLLECTION, "REVISE_WORK", null, "work-1", "src_1", "MANUAL");
        ArchiveMaintenanceRequestResultDTO manager = service.request(new ArchiveRequestContext(
                MANAGER, "confirmed-shared", "MANAGER_UI", null, null, policy), business);
        ArchiveRequestContext directContext = new ArchiveRequestContext(
                MANAGER, "confirmed-shared", "DIRECT_PRIVATE", "conversation-1:turn-1",
                AGENT, policy);
        ArchiveMaintenanceRequestResultDTO direct = service.request(directContext, business);
        ArchiveMaintenanceRequestResultDTO directRetry = service.request(directContext, business);
        ArchiveMaintenanceRequestResultDTO songjiang = service.request(new ArchiveRequestContext(
                MANAGER, "confirmed-shared", "SONGJIANG", "conversation-2:turn-1",
                null, policy), business);

        assertEquals(manager.job().jobId(), direct.job().jobId());
        assertEquals(manager.job().jobId(), directRetry.job().jobId());
        assertEquals(manager.job().jobId(), songjiang.job().jobId());
        assertEquals("confirmed-shared", saved.get().requestIntentId());
        verify(store, times(1)).insertJob(any());
        verify(store, times(1)).insertRun(anyString(), anyString(), eq(1L), eq(1L));
    }

    @Test
    void missingSourceAndAssigneeCreateDurableWaitingJobThenResolveExactCandidateWithoutDispatch() {
        allowManager("job.create,job.manage");
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION + "/jobs", "JOB", "aj_waiting");
        ArgumentCaptor<ArchiveMaintenanceJobRecord> inserted =
                ArgumentCaptor.forClass(ArchiveMaintenanceJobRecord.class);
        ArchiveJobDTO waiting = service.createJob(MANAGER, COLLECTION, "create-waiting",
                new ArchiveJobCreateRequest("ADD_WORK",
                        new ArchiveNewWorkRequest("waiting-book", "待补底本", null),
                        null, null, "MANUAL", "intent-waiting"));
        verify(store).insertJob(inserted.capture());
        ArchiveMaintenanceJobRecord persisted = inserted.getValue();
        assertEquals("WAITING_INPUT", waiting.state());
        assertEquals("SOURCE_AND_ASSIGNEE_REQUIRED", waiting.waitReason());
        assertNull(waiting.runId());
        assertNull(waiting.draftId());
        assertNull(waiting.appointmentId());
        assertNull(persisted.appointmentRevision());
        verify(store, never()).insertRun(anyString(), anyString(), anyLong(), anyLong());
        verify(store, never()).insertDraft(any());

        ArchiveAppointmentRecord active = appointment("ACTIVE", 1);
        activeAppointment(active);
        allowCurrentBinding();
        when(store.findJob(persisted.jobId(), false)).thenReturn(persisted);
        when(store.findJob(persisted.jobId(), true)).thenReturn(persisted);
        when(store.resolveWaitingJob(any(), eq(1L))).thenReturn(1);
        when(store.beginOperation(eq(MANAGER), eq("resolve-waiting"), eq("POST"),
                eq("/archive/admin/v1/jobs/" + persisted.jobId() + "/resolve-input"),
                anyString(), eq("JOB_INPUT"), eq(persisted.jobId())))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB_INPUT",
                        persisted.jobId(), "PENDING"));
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveJobDTO ready = service.resolveInput(MANAGER, persisted.jobId(), "resolve-waiting", 1,
                new ArchiveResolveInputRequest("src_1", null, null,
                        APPOINTMENT, "1", skill));

        assertEquals("WAITING_SKILL", ready.state());
        assertEquals("CLIENT_UPDATE_REQUIRED", ready.waitReason());
        assertEquals(APPOINTMENT, ready.appointmentId());
        assertNotNull(ready.runId());
        assertNotNull(ready.draftId());
        ArgumentCaptor<ArchiveMaintenanceJobRecord> resolved =
                ArgumentCaptor.forClass(ArchiveMaintenanceJobRecord.class);
        verify(store).resolveWaitingJob(resolved.capture(), eq(1L));
        assertEquals(AGENT, resolved.getValue().agentId());
        assertEquals("src_1", resolved.getValue().sourceId());
        verify(store).insertRun(eq(ready.runId()), eq(persisted.jobId()), eq(1L), eq(1L));
        verify(store).insertDraft(argThat(draft -> draft.jobId().equals(persisted.jobId())
                && draft.revision() == 0 && "EDITABLE".equals(draft.state())));
    }

    @Test
    void waitingInputsAreFrozenScopedAndNativeExecutionRemainsFailClosed() {
        allowManager("job.manage");
        ArchiveMaintenanceJobRecord waiting = new ArchiveMaintenanceJobRecord(
                JOB, null, COLLECTION, "0", "client-a", "owner-a", null, null,
                null, null, null, 3, "MANUAL", "REVISE_WORK", "work-1", "tiny-book",
                "小书", null, null, null, null, "WAITING_INPUT", "SOURCE_REQUIRED",
                2, null, null, "intent-waiting", "d".repeat(64), AGENT);
        when(store.findJob(JOB, false)).thenReturn(waiting);
        when(store.findJob(JOB, true)).thenReturn(waiting);
        when(store.lockSlot(COLLECTION, "ARCHIVE_EDITOR")).thenReturn(
                new ArchiveMaintenanceStore.Slot(COLLECTION, "ARCHIVE_EDITOR", null, 2));
        when(store.beginOperation(eq(MANAGER), eq("resolve-frozen"), eq("POST"), anyString(),
                anyString(), eq("JOB_INPUT"), eq(JOB)))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB_INPUT", JOB, "PENDING"));

        ArchiveMaintenanceException frozen = assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(MANAGER, JOB, "resolve-frozen", 2,
                        new ArchiveResolveInputRequest("src_1", null, "work-other",
                                null, null, null)));
        assertEquals("ARCHIVE_INPUT_FROZEN", frozen.code());
        verify(store, never()).resolveWaitingJob(any(), anyLong());

        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveMaintenanceException nativeDenied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeStart(runtime(), JOB, "run-forged",
                        new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1")));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", nativeDenied.code());
        assertEquals(404, nativeDenied.status(),
                "native authority must not reveal a management-only waiting job without a run/grant");
        verifyNoInteractions(port);
    }

    @Test
    void waitingJobCanBeCancelledWithoutInventingOrFencingARun() {
        allowManager("job.manage");
        ArchiveMaintenanceJobRecord waiting = new ArchiveMaintenanceJobRecord(
                JOB, null, COLLECTION, "0", "client-a", "owner-a", null, null,
                null, null, null, 3, "MANUAL", "ADD_WORK", null, null, null,
                null, null, null, null, "WAITING_INPUT",
                "SOURCE_AND_WORK_INPUT_AND_ASSIGNEE_REQUIRED", 1, null, null,
                "intent-cancel", "d".repeat(64), null);
        ArchiveMaintenanceJobRecord cancelledRecord = new ArchiveMaintenanceJobRecord(
                waiting.jobId(), null, waiting.collectionId(), waiting.tenantId(),
                waiting.clientId(), waiting.ownerJiacn(), null, null, null, null, null,
                waiting.managerAuthorizationRevision(), waiting.publicationMode(),
                waiting.operation(), null, null, null, null, null, null, null,
                "CANCELLED", "USER_CANCELLED", 2, null, null,
                waiting.requestIntentId(), waiting.requestSha256(), null);
        when(store.findJob(JOB, false)).thenReturn(waiting, cancelledRecord);
        when(store.findJob(JOB, true)).thenReturn(waiting);
        when(store.updateJobState(JOB, 1, "CANCELLED", "USER_CANCELLED", null)).thenReturn(1);
        when(store.beginOperation(eq(MANAGER), eq("cancel-waiting"), eq("POST"),
                eq("/archive/admin/v1/jobs/" + JOB + "/cancel"), anyString(),
                eq("JOB"), eq(JOB)))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB", JOB, "PENDING"));

        ArchiveAgentExecutionPort port = enableRuntime();
        ArchiveJobDTO cancelled = service.cancel(MANAGER, JOB, "cancel-waiting", 1,
                new ArchiveCancelRequest("not enough source facts"));

        assertEquals("CANCELLED", cancelled.state());
        assertNull(cancelled.runId());
        verify(store, never()).fenceRun(anyString());
        verifyNoInteractions(port);
        verify(store).updateJobState(JOB, 1, "CANCELLED", "USER_CANCELLED", null);
    }

    @Test
    void waitingIntentBindsFirstDirectTargetAndRejectsRetargetOrDifferentCurrentAppointment() {
        allowManager("job.create,job.manage");
        java.util.concurrent.atomic.AtomicReference<ArchiveMaintenanceJobRecord> saved =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(store.findJobByIntent(eq(MANAGER), eq("waiting-shared"), eq(true)))
                .thenAnswer(call -> saved.get());
        when(store.findJob(anyString(), eq(true))).thenAnswer(call -> {
            ArchiveMaintenanceJobRecord current = saved.get();
            return current != null && current.jobId().equals(call.getArgument(0)) ? current : null;
        });
        when(store.findJob(anyString(), eq(false))).thenAnswer(call -> {
            ArchiveMaintenanceJobRecord current = saved.get();
            return current != null && current.jobId().equals(call.getArgument(0)) ? current : null;
        });
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; })
                .when(store).insertJob(any());
        when(store.bindWaitingJobTarget(anyString(), eq(1L), eq(AGENT))).thenAnswer(call -> {
            ArchiveMaintenanceJobRecord current = saved.get();
            saved.set(jobTarget(current, AGENT, 2));
            return 1;
        });
        operation("POST", "/archive/admin/v1/collections/" + COLLECTION + "/jobs",
                "JOB", "aj_waiting_shared");
        ArchiveMaintenanceRequest business = new ArchiveMaintenanceRequest(COLLECTION,
                "ADD_WORK", null, null, null, "MANUAL");
        ArchiveConfirmedPolicyRef policy = new ArchiveConfirmedPolicyRef(
                "confirmed:waiting-shared", COLLECTION, "ADD_WORK", null, null, null, "MANUAL");
        ArchiveJobDTO manager = service.request(new ArchiveRequestContext(MANAGER,
                "waiting-shared", "MANAGER_UI", null, null, policy), business).job();
        ArchiveJobDTO songjiang = service.request(new ArchiveRequestContext(MANAGER,
                "waiting-shared", "SONGJIANG", "conversation-s:1", null, policy), business).job();
        ArchiveRequestContext directA = new ArchiveRequestContext(MANAGER,
                "waiting-shared", "DIRECT_PRIVATE", "conversation-d:1", AGENT, policy);
        ArchiveJobDTO direct = service.request(directA, business).job();
        ArchiveJobDTO directReplay = service.request(directA, business).job();

        assertEquals(manager.jobId(), songjiang.jobId());
        assertEquals(manager.jobId(), direct.jobId());
        assertEquals(direct, directReplay);
        assertEquals("WAITING_INPUT", manager.state());
        assertEquals("SOURCE_AND_WORK_INPUT_AND_ASSIGNEE_REQUIRED", manager.waitReason());
        assertEquals(AGENT, saved.get().targetAgentId());
        assertEquals("2", direct.revision());
        ArchiveMaintenanceException retargeted = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(new ArchiveRequestContext(MANAGER, "waiting-shared",
                        "DIRECT_PRIVATE", "conversation-d:2", "agent-c", policy), business));
        assertEquals("ARCHIVE_INTENT_TARGET_CONFLICT", retargeted.code());

        ArchiveAppointmentRecord appointmentB = new ArchiveAppointmentRecord(APPOINTMENT, COLLECTION,
                "ARCHIVE_EDITOR", "0", "client-a", "owner-a", "agent-b", "8", "COLLECTION", "",
                "DRAFT_ONLY", "archive-maintainer", "1.0.0", SHA, "ACTIVE", 1,
                Instant.parse("2026-09-28T00:00:00Z"), null);
        activeAppointment(appointmentB);
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 8, "agent-b"))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId("agent-b"));
        when(store.beginOperation(eq(MANAGER), eq("resolve-target-b"), eq("POST"),
                eq("/archive/admin/v1/jobs/" + manager.jobId() + "/resolve-input"), anyString(),
                eq("JOB_INPUT"), eq(manager.jobId())))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB_INPUT",
                        manager.jobId(), "PENDING"));
        ArchiveMaintenanceException changedTarget = assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(MANAGER, manager.jobId(), "resolve-target-b", 2,
                        new ArchiveResolveInputRequest("src_1",
                                new ArchiveNewWorkRequest("waiting-key", "Waiting Work", null), null,
                                APPOINTMENT, "1", new ArchiveSkillRef(
                                        "archive-maintainer", "1.0.0", SHA))));
        assertEquals("ARCHIVE_TARGET_NOT_APPOINTED", changedTarget.code());
        verify(store, times(1)).insertJob(any());
        verify(store, times(1)).bindWaitingJobTarget(manager.jobId(), 1, AGENT);
        verify(store, never()).resolveWaitingJob(any(), anyLong());
        verify(store, never()).insertRun(anyString(), anyString(), anyLong(), anyLong());
        verify(store, never()).insertDraft(any());
    }

    @Test
    void resolveInputRejectsRevokedManagerChangedAppointmentAndForeignSource() {
        ArchiveMaintenanceJobRecord waiting = new ArchiveMaintenanceJobRecord(
                JOB, null, COLLECTION, "0", "client-a", "owner-a", null, null,
                null, null, null, 3, "MANUAL", "ADD_WORK", "work-new", "key-new",
                "New Work", null, null, null, null, "WAITING_INPUT", "SOURCE_REQUIRED",
                1, null, null, "intent", "d".repeat(64), null);
        when(store.findJob(JOB, false)).thenReturn(waiting);
        when(store.findJob(JOB, true)).thenReturn(waiting);
        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), anyBoolean()))
                .thenReturn(new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        "job.manage", 4, "REVOKED"));
        ArchiveMaintenanceException revoked = assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(MANAGER, JOB, "revoked", 1,
                        new ArchiveResolveInputRequest("src_1", null, null,
                                null, null, null)));
        assertEquals(403, revoked.status());
        verify(store, never()).resolveWaitingJob(any(), anyLong());

        allowManager("job.manage");
        ArchiveAppointmentRecord changed = appointment("ACTIVE", 2);
        activeAppointment(changed);
        allowCurrentBinding();
        when(store.beginOperation(eq(MANAGER), eq("changed-appointment"), eq("POST"),
                anyString(), anyString(), eq("JOB_INPUT"), eq(JOB)))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB_INPUT", JOB, "PENDING"));
        ArchiveMaintenanceException assignment = assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(MANAGER, JOB, "changed-appointment", 1,
                        new ArchiveResolveInputRequest("src_1", null, null,
                                APPOINTMENT, "1", new ArchiveSkillRef(
                                        "archive-maintainer", "1.0.0", SHA))));
        assertEquals("ARCHIVE_ASSIGNMENT_CHANGED", assignment.code());

        ArchiveSourceSnapshotRecord foreign = new ArchiveSourceSnapshotRecord(
                "src_foreign", COLLECTION, "0", "client-a", "owner-b", "cyf-artifact://foreign",
                SHA, 1, "foreign", "v1", "authorized", "UTF8_EXACT_V1", "READY");
        when(store.findSource("src_foreign")).thenReturn(foreign);
        when(store.beginOperation(eq(MANAGER), eq("foreign-source"), eq("POST"),
                eq("/archive/admin/v1/jobs/" + JOB + "/resolve-input"), anyString(),
                eq("JOB_INPUT"), eq(JOB)))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB_INPUT", JOB, "PENDING"));
        ArchiveMaintenanceException hidden = assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(MANAGER, JOB, "foreign-source", 1,
                        new ArchiveResolveInputRequest("src_foreign", null, null,
                                null, null, null)));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", hidden.code());
    }

    @Test
    void directRequestRequiresExactCurrentAppointedAgentBeforeIncompleteInputCanWait() {
        allowManager("job.create");
        ArchiveAppointmentRecord active = appointment("ACTIVE", 1);
        activeAppointment(active);
        allowCurrentBinding();
        when(store.beginOperation(eq(MANAGER), anyString(), eq("POST"),
                eq("/archive/admin/v1/collections/" + COLLECTION + "/jobs"), anyString(),
                eq("JOB"), anyString()))
                .thenAnswer(call -> new ArchiveMaintenanceStore.Operation(true, "POST",
                        call.getArgument(3), call.getArgument(4), "JOB",
                        call.getArgument(6), "PENDING"));
        ArchiveRequestContext context = new ArchiveRequestContext(MANAGER, "intent-direct",
                "DIRECT_PRIVATE", "conversation-1:turn-2", "agent-other",
                new ArchiveConfirmedPolicyRef("policy-direct", COLLECTION, "ADD_WORK",
                        null, null, null, "MANUAL"));
        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.request(context, new ArchiveMaintenanceRequest(COLLECTION,
                        "ADD_WORK", null, null, null, "MANUAL")));
        assertEquals("ARCHIVE_TARGET_NOT_APPOINTED", denied.code());
        verify(store, never()).findJobByIntent(any(), anyString(), anyBoolean());
        verify(store, never()).insertJob(any());
        verify(store, never()).insertRun(anyString(), anyString(), anyLong(), anyLong());
        verify(store, never()).insertDraft(any());
    }

    private ArchiveAgentExecutionPort enableRuntime() {
        ArchiveAgentExecutionPort port = mock(ArchiveAgentExecutionPort.class);
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        when(port.lockIdentityRoot(any())).thenAnswer(call -> root(call.getArgument(0)));
        when(port.requireControlledTarget(any(), any())).thenReturn(lockedTarget());
        return port;
    }

    private void allowCurrentBinding() {
        when(identities.requireActiveIdentityForBinding("0", "client-a", "owner-a", 7, AGENT))
                .thenReturn(new AgentIdentityRegistryEntity().setCanonicalAgentId(AGENT));
    }

    private ArchiveRuntimeScope runtime() {
        return new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-a", "execution-a", "command-a", 1, 1);
    }

    private ArchiveDraftRecord emptyDraft() {
        return new ArchiveDraftRecord(DRAFT, JOB, 0, "EDITABLE",
                "{\"blocks\":[],\"excludedSourceRanges\":[]}", SHA, null, null);
    }

    private ArchiveMaintenanceJobRecord jobTarget(ArchiveMaintenanceJobRecord base,
            String targetAgentId, long revision) {
        return new ArchiveMaintenanceJobRecord(base.jobId(), base.runId(), base.collectionId(),
                base.tenantId(), base.clientId(), base.ownerJiacn(), base.appointmentId(),
                base.appointmentRevision(), base.agentId(), base.bindingVersion(),
                base.permissionProfile(), base.managerAuthorizationRevision(), base.publicationMode(),
                base.operation(), base.workId(), base.canonicalKey(), base.title(), base.sourceId(),
                base.sourceSha256(), base.sourceSummary(), base.rightsBasis(), base.state(),
                base.waitReason(), revision, base.draftId(), base.publicationId(),
                base.requestIntentId(), base.requestSha256(), targetAgentId);
    }

    private ArchiveMaintenanceJobRecord jobScope(ArchiveMaintenanceJobRecord base,
            String tenantId, String clientId, String ownerJiacn) {
        return new ArchiveMaintenanceJobRecord(base.jobId(), base.runId(), base.collectionId(),
                tenantId, clientId, ownerJiacn, base.appointmentId(), base.appointmentRevision(),
                base.agentId(), base.bindingVersion(), base.permissionProfile(),
                base.managerAuthorizationRevision(), base.publicationMode(), base.operation(),
                base.workId(), base.canonicalKey(), base.title(), base.sourceId(),
                base.sourceSha256(), base.sourceSummary(), base.rightsBasis(), base.state(),
                base.waitReason(), base.revision(), base.draftId(), base.publicationId(),
                base.requestIntentId(), base.requestSha256(), base.targetAgentId());
    }

    private ArchiveMaintenanceJobRecord jobState(String state, String waitReason, long revision) {
        ArchiveMaintenanceJobRecord base = job("DRAFT_ONLY");
        return new ArchiveMaintenanceJobRecord(base.jobId(), base.runId(), base.collectionId(),
                base.tenantId(), base.clientId(), base.ownerJiacn(), base.appointmentId(),
                base.appointmentRevision(), base.agentId(), base.bindingVersion(),
                base.permissionProfile(), base.managerAuthorizationRevision(), base.publicationMode(),
                base.operation(), base.workId(), base.canonicalKey(), base.title(), base.sourceId(),
                base.sourceSha256(), base.sourceSummary(), base.rightsBasis(), state, waitReason,
                revision, base.draftId(), base.publicationId(), base.requestIntentId(),
                base.requestSha256());
    }

    @Test
    void withdrawalUsesIndependentPermissionWorkCasExplicitPointerAndExactReplay() {
        allowManager("edition.withdraw");
        String workId = "work-a";
        String editionId = "edition-a";
        ArchiveEditionVersionRecord version = new ArchiveEditionVersionRecord("pub-a", null,
                COLLECTION, workId, editionId, 4, SHA, "b".repeat(64), "PUBLISHED",
                "HUMAN", "owner-a", 3, Instant.parse("2026-09-27T00:00:00Z"), null);
        when(store.findPublication(workId, editionId, false)).thenReturn(version);
        when(store.lockCollectionWork(COLLECTION, workId)).thenReturn(
                new ArchiveMaintenanceStore.CollectionWork(COLLECTION, workId, "key-a", 7));
        when(content.lockWork(workId)).thenReturn(new ArchiveWorkRecord(workId, "title", editionId));
        when(store.listPublications(workId, true)).thenReturn(List.of(version));
        when(content.lockEdition(editionId)).thenReturn(new ArchiveEditionRecord(editionId, workId,
                "READY", "b".repeat(64), SHA, "c".repeat(64), 1, 0, 0, 0, 0, 0, 0, 0));
        when(store.withdrawPublication("pub-a")).thenReturn(1);
        when(content.switchActiveEdition(workId, editionId, null)).thenReturn(1);
        when(store.bumpCollectionWork(COLLECTION, workId, 7)).thenReturn(1);
        var saved = new java.util.concurrent.atomic.AtomicReference<ArchiveWithdrawalRecord>();
        var requestDigest = new java.util.concurrent.atomic.AtomicReference<String>();
        when(store.beginOperation(eq(MANAGER), eq("withdraw-key"), eq("POST"), anyString(),
                anyString(), eq("EDITION_WITHDRAWAL"), anyString())).thenAnswer(call -> {
            if (saved.get() == null) {
                requestDigest.set(call.getArgument(4));
                return new ArchiveMaintenanceStore.Operation(true, "POST", call.getArgument(3),
                        requestDigest.get(), "EDITION_WITHDRAWAL", call.getArgument(6), "PENDING");
            }
            return new ArchiveMaintenanceStore.Operation(false, "POST", call.getArgument(3),
                    requestDigest.get(), "EDITION_WITHDRAWAL", saved.get().withdrawalId(), "COMMITTED");
        });
        doAnswer(call -> { saved.set(call.getArgument(0)); return null; })
                .when(store).insertWithdrawal(any());
        when(store.findWithdrawalByPublication("pub-a", true)).thenAnswer(call -> saved.get());
        when(store.findWithdrawal(anyString())).thenAnswer(call -> saved.get());

        ArchiveWithdrawalDTO first = service.withdraw(MANAGER, workId, editionId, "withdraw-key", 7,
                new ArchiveWithdrawRequest("rights correction", null));
        ArchiveWithdrawalDTO replay = service.withdraw(MANAGER, workId, editionId, "withdraw-key", 7,
                new ArchiveWithdrawRequest("rights correction", null));

        assertEquals(first, replay);
        assertNull(first.resultingActiveEditionId());
        assertEquals("8", first.resultingWorkRevision());
        assertEquals("PENDING", first.outboxState());
        verify(content, times(1)).switchActiveEdition(workId, editionId, null);
        verify(store, times(1)).withdrawPublication("pub-a");
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ArchiveMaintenanceException.class,
                () -> service.withdraw(MANAGER, workId, editionId, "withdraw-key", 7,
                        new ArchiveWithdrawRequest("changed reason", null))).code());
    }

    @Test
    void withdrawalHidesForeignScopeAndDoesNotFollowPublishOrAgentAuthority() {
        String workId = "work-a";
        String editionId = "edition-a";
        when(store.findPublication(workId, editionId, false)).thenReturn(
                new ArchiveEditionVersionRecord("pub-a", null, COLLECTION, workId, editionId,
                        1, SHA, SHA, "PUBLISHED", "AGENT", AGENT, 3,
                        Instant.parse("2026-09-27T00:00:00Z"), null));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.withdraw(MANAGER, workId, editionId, "k", 1,
                        new ArchiveWithdrawRequest("reason", null))).code());
        ArchiveActorScope appointedAgent = new ArchiveActorScope("0", "client-a", AGENT);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.withdraw(appointedAgent, workId, editionId, "agent-k", 1,
                        new ArchiveWithdrawRequest("reason", null))).code());
        allowManager("publish");
        ArchiveMaintenanceException denied = assertThrows(ArchiveMaintenanceException.class,
                () -> service.withdraw(MANAGER, workId, editionId, "k", 1,
                        new ArchiveWithdrawRequest("reason", null)));
        assertEquals(403, denied.status());
        verify(store, never()).withdrawPublication(anyString());
    }

    @Test
    void editionHistoryExposesImmutablePublishAndWithdrawalFactsOnlyToActiveAuthorizedManager() {
        allowManager("edition.withdraw");
        ArchiveWithdrawalRecord withdrawal = new ArchiveWithdrawalRecord("awd-a", "pub-a",
                COLLECTION, "work-a", "edition-a", "rights correction", "0", "client-a",
                "owner-a", "HUMAN", "owner-a", 5, null, null, 8, "withdraw-key",
                Instant.parse("2026-10-01T00:00:00Z"), "PENDING");
        ArchiveEditionVersionRecord version = new ArchiveEditionVersionRecord("pub-a", "job-a",
                COLLECTION, "work-a", "edition-a", 4, SHA, "b".repeat(64), "WITHDRAWN",
                "HUMAN", "owner-a", 3, Instant.parse("2026-09-30T00:00:00Z"), withdrawal);
        when(store.listPublications("work-a", false)).thenReturn(List.of(version));
        when(store.listPublications("work-a", true)).thenReturn(List.of(version));
        when(store.findPublication("work-a", "edition-a", false)).thenReturn(version);
        when(store.findPublication("work-a", "edition-a", true)).thenReturn(version);
        when(store.findPublicationById("pub-a")).thenReturn(new ArchivePublicationRecord(
                "pub-a", "job-a", COLLECTION, "work-a", "edition-a", 4, SHA,
                "b".repeat(64), "WITHDRAWN", "HUMAN", "owner-a", 3));
        when(store.findPublicationReadback("pub-a")).thenReturn(
                new ArchivePublicationReadbackRecord("pub-a", "PASSED", 2, SHA, "[]",
                        Instant.parse("2026-09-30T00:01:00Z")));
        when(store.lockCollectionWork(COLLECTION, "work-a")).thenReturn(
                new ArchiveMaintenanceStore.CollectionWork(COLLECTION, "work-a", "key-a", 8));
        when(content.lockWork("work-a")).thenReturn(new ArchiveWorkRecord("work-a", "title", null));

        ArchiveEditionHistoryDTO history = service.editionHistory(MANAGER, "work-a");
        ArchiveEditionVersionDTO detail = service.edition(MANAGER, "work-a", "edition-a");

        assertEquals("8", history.workRevision());
        assertNull(history.activeEditionId());
        assertEquals(detail, history.editions().getFirst());
        assertEquals(SHA, detail.manifestSha256());
        assertEquals("b".repeat(64), detail.sourceSha256());
        assertEquals("HUMAN", detail.actorType());
        assertEquals("2026-09-30T00:00:00Z", detail.publishedAt());
        assertEquals("WITHDRAWN", detail.state());
        assertEquals("rights correction", detail.withdrawal().reason());
        assertEquals("5", detail.withdrawal().authorizationRevision());
        assertEquals("withdraw-key", detail.withdrawal().operationKey());

        when(store.findPublicationReadback("pub-a")).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> service.edition(MANAGER, "work-a", "edition-a"),
                "a committed publication without durable readback must never synthesize PENDING");

        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), anyBoolean()))
                .thenReturn(new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        "edition.withdraw", 6, "REVOKED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.edition(MANAGER, "work-a", "edition-a")).code());
    }

    @Test
    void validationReadReturnsOnlyExactCurrentRevisionForOwningActor() {
        allowManager("job.manage");
        ArchiveMaintenanceJobRecord job = job("DRAFT_ONLY");
        ArchiveDraftRecord draft = new ArchiveDraftRecord(DRAFT, JOB, 4, "CHANGES_REQUIRED",
                "{\"blocks\":[],\"excludedSourceRanges\":[]}", SHA, null, null);
        when(store.findDraft(DRAFT, false)).thenReturn(draft);
        when(store.findJob(JOB, false)).thenReturn(job);
        when(store.findLatestValidation(DRAFT, 4)).thenReturn(new ArchiveValidationRecord(
                "val-current", DRAFT, 4, "FAILED", "b".repeat(64), "[\"missing chapter\"]"));

        ArchiveValidationDTO result = service.validation(MANAGER, DRAFT);

        assertEquals("4", result.draftRevision());
        assertEquals("FAILED", result.outcome());
        assertEquals(List.of("missing chapter"), result.findings());
        verify(store).findLatestValidation(DRAFT, 4);
        verify(store, never()).findCurrentValidation(anyString(), anyLong());

        ArchiveMaintenanceJobRecord foreign = jobScope(job, "0", "client-a", "owner-b");
        when(store.findJob(JOB, false)).thenReturn(foreign);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.validation(MANAGER, DRAFT)).code());
    }

    private ArchiveAgentExecutionPort.LockedIdentityRoot root(
            ArchiveAgentExecutionPort.TargetRequest target) {
        return new ArchiveAgentExecutionPort.LockedIdentityRoot(target.tenant(), target.client(),
                target.owner(), target.canonicalAgent(), target.binding());
    }

    private ArchiveAgentExecutionPort.LockedTarget lockedTarget() {
        byte[] registration = new byte[32];
        registration[0] = 7;
        return new ArchiveAgentExecutionPort.LockedTarget("0", "client-a", "owner-a", AGENT,
                7, "runtime-a", registration);
    }

    private ArchiveExecutionGrantRecord executionGrant(String state, long epoch) {
        byte[] registration = new byte[32];
        registration[0] = 7;
        return new ArchiveExecutionGrantRecord("grant-a", RUN, "0", "client-a", "owner-a",
                APPOINTMENT, 1, 3, AGENT, 7, "execution-a", "command-a", 1, epoch,
                "runtime-a", registration, InstalledSkillResolver.Origin.PLATFORM_PROVISIONED.name(),
                "psi_verified", 4, "archive-maintainer", "1.0.0", SHA, "execute-key",
                "e".repeat(64), "/internal/archive/v1/jobs/" + JOB + "/runs/" + RUN + "/context",
                2_000_000_000_000L, state, 1);
    }

    private ArchiveMaintenanceJobRecord job(String profile) {
        return new ArchiveMaintenanceJobRecord(JOB, RUN, COLLECTION, "0", "client-a", "owner-a",
                APPOINTMENT, 1, AGENT, "7", profile, 3, "MANUAL", "ADD_WORK", "wrk_new",
                "tiny-book", "小书", "src_1", SHA, "固定来源 / 摘要", "已授权公开用途", "WAITING_SKILL",
                "CLIENT_UPDATE_REQUIRED", 1, DRAFT, null, "intent-1", "d".repeat(64));
    }

    private ArchiveMaintenanceJobRecord executionJob(String profile, String publicationMode,
            String state, long revision) {
        ArchiveMaintenanceJobRecord base = job(profile);
        return new ArchiveMaintenanceJobRecord(base.jobId(), base.runId(), base.collectionId(),
                base.tenantId(), base.clientId(), base.ownerJiacn(), base.appointmentId(),
                base.appointmentRevision(), base.agentId(), base.bindingVersion(), profile,
                base.managerAuthorizationRevision(), publicationMode, base.operation(), base.workId(),
                base.canonicalKey(), base.title(), base.sourceId(), base.sourceSha256(),
                base.sourceSummary(), base.rightsBasis(), state, null, revision, base.draftId(),
                base.publicationId(), base.requestIntentId(), base.requestSha256());
    }

    private ArchiveAppointmentRecord appointment(String status, long revision) {
        return appointment("DRAFT_ONLY", status, revision);
    }

    private ArchiveAppointmentRecord appointment(String profile, String status, long revision) {
        return new ArchiveAppointmentRecord(APPOINTMENT, COLLECTION, "ARCHIVE_EDITOR", "0", "client-a",
                "owner-a", AGENT, "7", "COLLECTION", "", profile, "archive-maintainer",
                "1.0.0", SHA, status, revision, Instant.parse("2026-09-28T00:00:00Z"), null);
    }

    private void activeAppointment(ArchiveAppointmentRecord appointment) {
        when(store.lockSlot(COLLECTION, "ARCHIVE_EDITOR")).thenReturn(
                new ArchiveMaintenanceStore.Slot(COLLECTION, "ARCHIVE_EDITOR", APPOINTMENT, 1));
        when(store.findAppointment(APPOINTMENT, true)).thenReturn(appointment);
    }

    private void allowManager(String permission) {
        when(store.findManagerGrant(eq(MANAGER), eq(COLLECTION), anyBoolean()))
                .thenReturn(new ArchiveManagerGrantRecord(COLLECTION, "0", "client-a", "owner-a",
                        permission, 3, "ACTIVE"));
    }


    private String publicationRequestSha(long expectedDraftRevision, ArchivePublishRequest request) {
        try {
            String requestSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                    new ObjectMapper().writeValueAsBytes(request));
            return cn.jia.chat.archive.content.ArchiveEtags.sha256(
                    (expectedDraftRevision + ":" + requestSha)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new AssertionError(failure);
        }
    }

    private void operation(String method, String path, String type, String target) {
        when(store.beginOperation(eq(MANAGER), anyString(), eq(method), eq(path), anyString(), eq(type), anyString()))
                .thenAnswer(invocation -> new ArchiveMaintenanceStore.Operation(true, method, path,
                        invocation.getArgument(4), type, target, "PENDING"));
    }
}
