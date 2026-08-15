package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskRequestDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.core.util.JsonUtil;
import cn.jia.agent.entity.AgentTaskArtifactEntity;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskRequestCreateDTO;
import cn.jia.agent.entity.AgentTaskRequestDTO;
import cn.jia.agent.entity.AgentTaskRequestEntity;
import cn.jia.agent.entity.AgentTaskRequestQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskCollaborationServiceImplTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long NOW = 1_721_700_000_000L;

    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentTaskRequestDao requestDao;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private AgentTaskCollaborationServiceImpl service;

    @BeforeEach
    void setUp() {
        taskDao = mock(AgentTaskMetaDao.class);
        memberDao = mock(AgentTaskMemberDao.class);
        workItemDao = mock(AgentTaskWorkItemDao.class);
        requestDao = mock(AgentTaskRequestDao.class);
        artifactDao = mock(AgentTaskArtifactDao.class);
        mutationTransaction = mock(AgentTaskMutationTransaction.class);
        eventWriter = mock(AgentTaskEventWriter.class);
        when(mutationTransaction.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> mutation = invocation.getArgument(3);
            AgentTaskMetaEntity root = taskDao.findByTaskId(TENANT, CLIENT, TASK);
            return mutation.apply(root == null ? task(null) : root);
        });
        service = new AgentTaskCollaborationServiceImpl(
                taskDao, memberDao, workItemDao, requestDao, artifactDao,
                mutationTransaction, eventWriter, () -> NOW);
    }

    @Test
    void createsScopedRequestForMemberAndValidTargetAgent() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "agent", TARGET));

        var result = service.create(TENANT, CLIENT, TASK, ACTOR, createRequest());

        assertEquals("open", result.getStatus());
        ArgumentCaptor<AgentTaskRequestDTO> insert = ArgumentCaptor.forClass(AgentTaskRequestDTO.class);
        verify(requestDao).insert(eq(TENANT), eq(CLIENT), insert.capture());
        assertEquals(TASK, insert.getValue().getTaskId());
        assertEquals(ACTOR, insert.getValue().getRequesterAgentId());
        assertEquals("open", insert.getValue().getStatus());
        assertEquals("work-1", insert.getValue().getWorkItemId());
    }


    @Test
    void workerCanTargetConfiguredCoordinatorRoleWithoutCoordinatorMemberRow() {
        allow(ACTOR, "worker");
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(task(TARGET));
        when(memberDao.listByTask(TENANT, CLIENT, TASK)).thenReturn(List.of());
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "role", "coordinator"));
        AgentTaskRequestCreateDTO command = createRequest();
        command.setTargetType("role");
        command.setTargetId("coordinator");

        var result = service.create(TENANT, CLIENT, TASK, ACTOR, command);

        assertEquals("coordinator", result.getTargetId());
    }

    @Test
    void crossScopeTaskMissReturnsGenericNotFoundWithoutMemberLookup() {
        when(taskDao.findByTaskId("tenant-b", CLIENT, TASK)).thenReturn(null);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.list("tenant-b", CLIENT, TASK, ACTOR, (AgentTaskRequestQueryDTO) null));

        assertEquals(Reason.NOT_FOUND, error.getReason());
        assertEquals("Resource was not found in the requested scope", error.getMessage());
        verify(memberDao, never()).findByTaskAndAgent(any(), any(), any(), any());
    }

    @Test
    void nonMemberCannotReadOrDiscoverTaskData() {
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(task(null));
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR)).thenReturn(null);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.get(TENANT, CLIENT, TASK, ACTOR, "req-1"));

        assertEquals(Reason.FORBIDDEN, error.getReason());
        verify(requestDao, never()).findByRequestId(any(), any(), any(), any());
    }

    @Test
    void observerCannotCreateRequest() {
        allow(ACTOR, "observer");

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, createRequest()));

        assertEquals(Reason.FORBIDDEN, error.getReason());
        verify(requestDao, never()).insert(any(), any(), any());
    }

    @Test
    void requesterCannotImpersonateAnotherAgent() {
        allow(ACTOR, "worker");
        AgentTaskRequestCreateDTO command = createRequest();
        command.setRequesterAgentId(TARGET);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.FORBIDDEN, error.getReason());
        verify(requestDao, never()).insert(any(), any(), any());
    }

    @Test
    void invalidCrossTaskWorkItemFailsClosedBeforeInsert() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(null);

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, createRequest()));

        assertEquals(Reason.NOT_FOUND, error.getReason());
        verify(requestDao, never()).insert(any(), any(), any());
    }

    @Test
    void requestDescriptionAcceptsExactlyTextUtf8ByteLimit() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "agent", TARGET));
        AgentTaskRequestCreateDTO command = createRequest();
        command.setDescription("界".repeat(21_845));

        service.create(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskRequestDTO> insert = ArgumentCaptor.forClass(AgentTaskRequestDTO.class);
        verify(requestDao).insert(eq(TENANT), eq(CLIENT), insert.capture());
        assertEquals(65_535, insert.getValue().getDescription()
                .getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void requestDescriptionRejectsUtf8ByteOverflowBeforeRequestDao() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        AgentTaskRequestCreateDTO command = createRequest();
        command.setDescription("界".repeat(21_845) + "x");

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
        assertEquals("description is required and must be within its UTF-8 byte limit",
                error.getMessage());
        verify(requestDao, never()).insert(any(), any(), any());
    }

    @Test
    void nonDuplicateIntegrityFailureMapsToStablePersistedValidationWithoutSqlLeak() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenThrow(
                new DataIntegrityViolationException("INSERT INTO secret_table failed: value too long"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, createRequest()));

        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertEquals("Request could not be persisted", error.getMessage());
        assertNull(error.getCause());
        verify(requestDao, never()).findByRequestId(any(), any(), any(), any());
    }

    @Test
    void constraintNameDoesNotMakeSqlState22FailureADuplicateConflict() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        SQLException sql = new SQLException(
                "Data too long while handling uk_task_request_scope", "22001", 0);
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenThrow(
                new DataIntegrityViolationException("request insert failed", sql));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, createRequest()));

        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertEquals("Request could not be persisted", error.getMessage());
        assertNull(error.getCause());
        verify(requestDao, never()).findByRequestId(any(), any(), any(), any());
    }

    @Test
    void duplicateKeyStillMapsToStableConflictWithoutSqlLeak() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenThrow(
                new DuplicateKeyException("duplicate SQL for uk_task_request_scope"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.create(TENANT, CLIENT, TASK, ACTOR, createRequest()));

        assertEquals(Reason.VERSION_CONFLICT, error.getReason());
        assertEquals("Request changed or already exists", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void targetAcknowledgesWithCasAndStructuredResponse() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 3L, ACTOR, "agent", TARGET));
        when(requestDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(TASK), eq("req-1"),
                eq(3L), any())).thenReturn(1);
        AgentTaskRequestTransitionDTO command = transition(3L, Map.of("accepted", true));

        var result = service.acknowledge(TENANT, CLIENT, TASK, TARGET, "req-1", command);

        assertEquals("acknowledged", result.getStatus());
        assertEquals(4L, result.getVersion());
        assertEquals(NOW, result.getAcknowledgedAt());
        assertEquals(Boolean.TRUE, result.getResponse().get("accepted"));
    }

    @Test
    void openRequestCannotSkipAcknowledgedState() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "agent", TARGET));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.resolve(TENANT, CLIENT, TASK, TARGET, "req-1",
                        transition(0L, Map.of("answer", "done"))));

        assertEquals(Reason.INVALID_TRANSITION, error.getReason());
        verify(requestDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void terminalRequestCannotBeReopenedOrChanged() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "resolved", 2L, ACTOR, "agent", TARGET));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.reject(TENANT, CLIENT, TASK, TARGET, "req-1",
                        transition(2L, Map.of("reason", "late"))));

        assertEquals(Reason.INVALID_TRANSITION, error.getReason());
    }

    @Test
    void staleRequestVersionFailsBeforeCasWrite() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 4L, ACTOR, "agent", TARGET));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.acknowledge(TENANT, CLIENT, TASK, TARGET, "req-1",
                        transition(3L, null)));

        assertEquals(Reason.VERSION_CONFLICT, error.getReason());
        verify(requestDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void onlyRequesterOrCoordinatorCanCancel() {
        allow(TARGET, "worker");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "role", "reviewer"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.cancel(TENANT, CLIENT, TASK, TARGET, "req-1",
                        transition(0L, null)));

        assertEquals(Reason.FORBIDDEN, error.getReason());
    }

    @Test
    void resolveRequiresStructuredResponse() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "acknowledged", 1L, ACTOR, "agent", TARGET));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.resolve(TENANT, CLIENT, TASK, TARGET, "req-1",
                        transition(1L, null)));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
    }

    @Test
    void requestListPushesStatusWorkItemOrderAndLimitIntoDao() {
        allow(ACTOR, "worker");
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.listByTask(TENANT, CLIENT, TASK, "open", "work-1", 10))
                .thenReturn(List.of(requestWithWork("req-1", "work-1")));
        AgentTaskRequestQueryDTO query = new AgentTaskRequestQueryDTO();
        query.setStatus("open");
        query.setWorkItemId("work-1");
        query.setLimit(10);

        var result = service.list(TENANT, CLIENT, TASK, ACTOR, query);

        assertEquals(List.of("req-1"), result.stream().map(r -> r.getRequestId()).toList());
        verify(requestDao).listByTask(TENANT, CLIENT, TASK, "open", "work-1", 10);
    }

    @Test
    void publishesNextArtifactVersionWithVerifiedInlineHash() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(2, 1);
        AgentTaskArtifactEntity latest = artifact("artifact-1", 1, ACTOR, "task_members");
        AgentTaskArtifactEntity stored = artifact("artifact-1", 2, ACTOR, "task_members");
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(latest);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, "artifact-1", 2)).thenReturn(stored);

        var result = service.publish(TENANT, CLIENT, TASK, ACTOR, command);

        assertEquals(2, result.getArtifactVersion());
        ArgumentCaptor<cn.jia.agent.entity.AgentTaskArtifactDTO> insert =
                ArgumentCaptor.forClass(cn.jia.agent.entity.AgentTaskArtifactDTO.class);
        verify(artifactDao).insert(eq(TENANT), eq(CLIENT), insert.capture());
        assertEquals(TASK, insert.getValue().getTaskId());
        assertEquals(ACTOR, insert.getValue().getProducerAgentId());
        assertEquals("task_members", insert.getValue().getVisibility());
    }

    @Test
    void artifactVersionChainFailsClosedOnStaleExpectedPreviousVersion() {
        allow(ACTOR, "worker");
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(artifact("artifact-1", 2, ACTOR, "task_members"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, artifactCommand(2, 1)));

        assertEquals(Reason.VERSION_CONFLICT, error.getReason());
        verify(artifactDao, never()).insert(any(), any(), any());
    }

    @Test
    void producerImpersonationAndBadHashAreRejected() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO impersonated = artifactCommand(1, 0);
        impersonated.setProducerAgentId(TARGET);
        assertEquals(Reason.FORBIDDEN, assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, impersonated)).getReason());

        AgentTaskArtifactPublishDTO badHash = artifactCommand(1, 0);
        badHash.setContentHash("0".repeat(64));
        assertEquals(Reason.INVALID_REQUEST, assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, badHash)).getReason());
        verify(artifactDao, never()).insert(any(), any(), any());
    }


    @Test
    void inlineArtifactLimitIsEnforcedOnUtf8BytesNotJavaCharacters() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        String oversizedUtf8 = "界".repeat(100_000);
        command.setContent(oversizedUtf8);
        command.setContentHash(sha256(oversizedUtf8));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
        verify(artifactDao, never()).insert(any(), any(), any());
    }

    @Test
    void externalArtifactUriUsesSchemeWhitelistAndRequiresHash() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setContent(null);
        command.setStorageUri("file:///etc/passwd");

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
    }

    @Test
    void legacyExternalArtifactWithoutByteLengthRemainsCompatibleAndOmitsUnknownLength() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setContent(null);
        command.setStorageUri("s3://bucket/path/artifact.txt");
        command.setContentByteLength(null);
        AgentTaskArtifactEntity stored = artifact(
                "artifact-1", 1, ACTOR, "task_members");
        stored.setContent(null);
        stored.setStorageUri(command.getStorageUri());
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, "artifact-1", 1))
                .thenReturn(stored);

        service.publish(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        Map<String, Object> payload = JsonUtil.jsonToMap(event.getValue().getEventJson());
        assertEquals(command.getContentHash(), payload.get("contentSha256"));
        assertFalse(payload.containsKey("contentByteLength"));
        assertFalse(event.getValue().getEventJson().contains(command.getStorageUri()));
    }

    @Test
    void artifactNonDuplicateIntegrityFailureMapsWithoutSqlLeak() {
        allow(ACTOR, "worker");
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenThrow(
                new DataIntegrityViolationException("constraint SQL exposed by driver"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, artifactCommand(1, 0)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
        assertEquals("Artifact could not be persisted", error.getMessage());
        assertNull(error.getCause());
        verify(artifactDao, never()).findVersion(any(), any(), any(), any(), anyInt());
    }

    @Test
    void storageUriRejects1021CharactersBeforeArtifactDao() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setContent(null);
        String prefix = "https://example.com/";
        command.setStorageUri(prefix + "a".repeat(1_021 - prefix.length()));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
        assertEquals("storageUri exceeds the schema character limit", error.getMessage());
        verify(artifactDao, never()).findLatestVersionForUpdate(any(), any(), any(), any());
        verify(artifactDao, never()).insert(any(), any(), any());
    }

    @Test
    void metadataRejectsUtf8TextOverflowBeforeArtifactDao() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setMetadata(Map.of("payload", "界".repeat(21_845)));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.publish(TENANT, CLIENT, TASK, ACTOR, command));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
        assertEquals("metadata is not valid bounded JSON", error.getMessage());
        verify(artifactDao, never()).findLatestVersionForUpdate(any(), any(), any(), any());
        verify(artifactDao, never()).insert(any(), any(), any());
    }

    @Test
    void overlongTenantScopeIsRejectedBeforeTaskDao() {
        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.list("t".repeat(51), CLIENT, TASK, ACTOR,
                        (AgentTaskRequestQueryDTO) null));

        assertEquals(Reason.INVALID_REQUEST, error.getReason());
        verify(taskDao, never()).findByTaskId(any(), any(), any());
    }

    @Test
    void privateArtifactIsHiddenFromOtherMemberButVisibleToProducer() {
        allow(ACTOR, "worker");
        AgentTaskArtifactEntity privateArtifact = artifact("artifact-1", 1, TARGET, "private");
        when(artifactDao.findLatestVersion(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(privateArtifact);

        AgentTaskCollaborationException hidden = assertThrows(AgentTaskCollaborationException.class,
                () -> service.getLatest(TENANT, CLIENT, TASK, ACTOR, "artifact-1"));
        assertEquals(Reason.NOT_FOUND, hidden.getReason());

        allow(TARGET, "worker");
        assertEquals("artifact-1",
                service.getLatest(TENANT, CLIENT, TASK, TARGET, "artifact-1").getArtifactId());
    }

    @Test
    void reviewerVisibilityListFiltersWithoutReorderingVisibleRows() {
        allow(ACTOR, "worker");
        AgentTaskArtifactEntity reviewerOnly = artifact("artifact-r", 1, TARGET, "reviewer");
        AgentTaskArtifactEntity shared = artifact("artifact-s", 1, TARGET, "task_members");
        AgentTaskArtifactEntity ownPrivate = artifact("artifact-p", 1, ACTOR, "private");
        when(artifactDao.listVisibleByTask(
                TENANT, CLIENT, TASK, null, ACTOR, false, false, 100))
                .thenReturn(List.of(reviewerOnly, shared, ownPrivate));

        var result = service.list(TENANT, CLIENT, TASK, ACTOR, new AgentTaskArtifactQueryDTO());

        assertEquals(List.of("artifact-s", "artifact-p"),
                result.stream().map(a -> a.getArtifactId()).toList());
        verify(artifactDao).listVisibleByTask(
                TENANT, CLIENT, TASK, null, ACTOR, false, false, 100);
    }

    @Test
    void malformedPersistedVisibilityFailsClosed() {
        allow(ACTOR, "worker");
        when(artifactDao.findLatestVersion(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(artifact("artifact-1", 1, TARGET, "TASK_MEMBERS"));

        AgentTaskCollaborationException error = assertThrows(AgentTaskCollaborationException.class,
                () -> service.getLatest(TENANT, CLIENT, TASK, ACTOR, "artifact-1"));

        assertEquals(Reason.INVALID_PERSISTED_STATE, error.getReason());
    }

    @Test
    void ordinaryArtifactContractDoesNotExposeWorkItemResultMutation() {
        assertTrue(java.util.Arrays.stream(cn.jia.agent.service.AgentTaskArtifactService.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase().contains("result")));
        assertEquals(1, cn.jia.agent.service.AgentWorkItemResultCommitService.class.getDeclaredMethods().length);
    }


    @ParameterizedTest
    @CsvSource({
            "help, HELP_REQUESTED",
            "review, REVIEW_REQUESTED",
            "approval, REQUEST_CREATED"
    })
    void requestCreateMapsCanonicalEventType(String requestType, String expectedEventType) {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        AgentTaskRequestEntity stored = request(
                "req-1", "open", 0L, ACTOR, "agent", TARGET);
        stored.setRequestType(requestType);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1")).thenReturn(stored);
        AgentTaskRequestCreateDTO command = createRequest();
        command.setRequestType(requestType);

        service.create(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals(expectedEventType, event.getValue().getEventType());
    }

    @ParameterizedTest
    @CsvSource({
            "acknowledged, REQUEST_ACKNOWLEDGED, open",
            "resolved, REQUEST_RESOLVED, acknowledged",
            "rejected, REQUEST_REJECTED, acknowledged",
            "cancelled, REQUEST_CANCELLED, open"
    })
    void requestTransitionsAppendCanonicalEvents(
            String targetStatus, String expectedEventType, String currentStatus) {
        String actor = "cancelled".equals(targetStatus) ? ACTOR : TARGET;
        allow(actor, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", currentStatus, 2L, ACTOR, "agent", TARGET));
        when(requestDao.updateByVersion(eq(TENANT), eq(CLIENT), eq(TASK), eq("req-1"),
                eq(2L), any())).thenReturn(1);
        Map<String, Object> response = switch (targetStatus) {
            case "resolved", "rejected" -> Map.of("decision", targetStatus);
            default -> null;
        };
        AgentTaskRequestTransitionDTO command = transition(2L, response);

        switch (targetStatus) {
            case "acknowledged" -> service.acknowledge(
                    TENANT, CLIENT, TASK, actor, "req-1", command);
            case "resolved" -> service.resolve(
                    TENANT, CLIENT, TASK, actor, "req-1", command);
            case "rejected" -> service.reject(
                    TENANT, CLIENT, TASK, actor, "req-1", command);
            case "cancelled" -> service.cancel(
                    TENANT, CLIENT, TASK, actor, "req-1", command);
            default -> throw new AssertionError(targetStatus);
        }

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals(expectedEventType, event.getValue().getEventType());
    }

    @Test
    void requestCreateLocksRootAndMapsReviewEventAfterInsert() {
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 0L, ACTOR, "agent", TARGET));

        service.create(TENANT, CLIENT, TASK, ACTOR, createRequest());

        var order = inOrder(mutationTransaction, memberDao, requestDao, eventWriter);
        order.verify(mutationTransaction).executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any());
        order.verify(memberDao).findByTaskAndAgent(TENANT, CLIENT, TASK, ACTOR);
        order.verify(requestDao).insert(eq(TENANT), eq(CLIENT), any());
        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        order.verify(eventWriter).append(event.capture());
        assertEquals(TaskEventType.REVIEW_REQUESTED, event.getValue().getEventType());
        assertEquals(TaskEventType.Aggregate.REQUEST, event.getValue().getAggregateType());
        assertEquals("req-1", event.getValue().getAggregateId());
    }

    @Test
    void requestIdWithCredentialLikeSubstringsRemainsValidWithoutDescriptionLeak() {
        String requestId = "req-authorization-api_key-api-key";
        String secretDescription = "Authorization: Bearer request-secret";
        allow(ACTOR, "worker");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, TARGET))
                .thenReturn(member(TARGET, "reviewer", "accepted"));
        when(workItemDao.findByTaskAndWorkItemId(TENANT, CLIENT, TASK, "work-1"))
                .thenReturn(workItem("work-1"));
        when(requestDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, requestId))
                .thenReturn(request(requestId, "open", 0L, ACTOR, "agent", TARGET));
        AgentTaskRequestCreateDTO command = createRequest();
        command.setRequestId(requestId);
        command.setDescription(secretDescription);

        service.create(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertTrue(event.getValue().getEventJson().contains(requestId));
        assertFalse(event.getValue().getEventJson().contains(secretDescription));
        assertFalse(event.getValue().getEventJson().contains("request-secret"));
    }

    @Test
    void requestCasConflictAppendsNoEvent() {
        allow(TARGET, "reviewer");
        when(requestDao.findByRequestId(TENANT, CLIENT, TASK, "req-1"))
                .thenReturn(request("req-1", "open", 4L, ACTOR, "agent", TARGET));

        assertThrows(AgentTaskCollaborationException.class, () -> service.acknowledge(
                TENANT, CLIENT, TASK, TARGET, "req-1", transition(3L, null)));

        verify(eventWriter, never()).append(any());
    }

    @Test
    void artifactIdWithCredentialLikeSubstringsRemainsValidWithoutContentLeak() {
        String artifactId = "artifact-authorization-api_key-api-key";
        String secretContent = "Authorization: Bearer artifact-secret";
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setArtifactId(artifactId);
        command.setContent(secretContent);
        command.setContentHash(sha256(secretContent));
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, artifactId))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        AgentTaskArtifactEntity stored = artifact(
                artifactId, 1, ACTOR, "task_members");
        stored.setContent(secretContent);
        stored.setContentHash(command.getContentHash());
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, artifactId, 1))
                .thenReturn(stored);

        service.publish(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertTrue(event.getValue().getEventJson().contains(artifactId));
        assertFalse(event.getValue().getEventJson().contains(secretContent));
        assertFalse(event.getValue().getEventJson().contains("artifact-secret"));
    }

    @Test
    void artifactEventContainsOnlyBoundedDigestMetadata() {
        allow(ACTOR, "worker");
        AgentTaskArtifactPublishDTO command = artifactCommand(1, 0);
        command.setMetadata(Map.of("credential", "secret-metadata"));
        when(artifactDao.findLatestVersionForUpdate(TENANT, CLIENT, TASK, "artifact-1"))
                .thenReturn(null);
        when(artifactDao.insert(eq(TENANT), eq(CLIENT), any())).thenReturn(1);
        when(artifactDao.findVersion(TENANT, CLIENT, TASK, "artifact-1", 1))
                .thenReturn(artifact("artifact-1", 1, ACTOR, "task_members"));

        service.publish(TENANT, CLIENT, TASK, ACTOR, command);

        ArgumentCaptor<AgentTaskEventWriteCommand> event =
                ArgumentCaptor.forClass(AgentTaskEventWriteCommand.class);
        verify(eventWriter).append(event.capture());
        assertEquals(TaskEventType.ARTIFACT_PUBLISHED, event.getValue().getEventType());
        Map<String, Object> payload = JsonUtil.jsonToMap(event.getValue().getEventJson());
        assertEquals(sha256("bounded-content"), payload.get("contentSha256"));
        assertEquals((long) "bounded-content".getBytes(StandardCharsets.UTF_8).length,
                ((Number) payload.get("contentByteLength")).longValue());
        assertTrue(!event.getValue().getEventJson().contains("bounded-content"));
        assertTrue(!event.getValue().getEventJson().contains("secret-metadata"));
    }

    private void allow(String agentId, String role) {
        when(taskDao.findByTaskId(TENANT, CLIENT, TASK)).thenReturn(task(null));
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK, agentId))
                .thenReturn(member(agentId, role, "working"));
    }

    private AgentTaskMetaEntity task(String coordinator) {
        AgentTaskMetaEntity entity = new AgentTaskMetaEntity();
        entity.setTaskId(TASK);
        entity.setCoordinatorAgentId(coordinator);
        entity.setTaskVersion(0L);
        entity.setCurrentEventVersion(0L);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private AgentTaskMemberEntity member(String agentId, String role, String status) {
        AgentTaskMemberEntity entity = new AgentTaskMemberEntity();
        entity.setTaskId(TASK);
        entity.setAgentId(agentId);
        entity.setMemberRole(role);
        entity.setMemberStatus(status);
        return entity;
    }

    private AgentTaskWorkItemEntity workItem(String id) {
        AgentTaskWorkItemEntity entity = new AgentTaskWorkItemEntity();
        entity.setTaskId(TASK);
        entity.setWorkItemId(id);
        return entity;
    }

    private AgentTaskRequestCreateDTO createRequest() {
        AgentTaskRequestCreateDTO command = new AgentTaskRequestCreateDTO();
        command.setRequestId("req-1");
        command.setWorkItemId("work-1");
        command.setRequesterAgentId(ACTOR);
        command.setTargetType("agent");
        command.setTargetId(TARGET);
        command.setRequestType("review");
        command.setPriority(5);
        command.setTitle("Please review");
        command.setDescription("Review the scoped work item");
        return command;
    }

    private AgentTaskRequestTransitionDTO transition(Long version, Map<String, Object> response) {
        AgentTaskRequestTransitionDTO command = new AgentTaskRequestTransitionDTO();
        command.setExpectedVersion(version);
        command.setResponse(response);
        return command;
    }

    private AgentTaskRequestEntity request(String id, String status, Long version,
            String requester, String targetType, String targetId) {
        AgentTaskRequestEntity entity = new AgentTaskRequestEntity();
        entity.setRequestId(id);
        entity.setTaskId(TASK);
        entity.setRequesterAgentId(requester);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setRequestType("review");
        entity.setStatus(status);
        entity.setPriority(1);
        entity.setTitle("Request");
        entity.setDescription("Description");
        entity.setVersion(version);
        entity.setCreateTime(NOW - 100);
        entity.setUpdateTime(NOW - 50);
        return entity;
    }

    private AgentTaskRequestEntity requestWithWork(String id, String workItemId) {
        AgentTaskRequestEntity entity = request(id, "open", 0L, ACTOR, "agent", TARGET);
        entity.setWorkItemId(workItemId);
        return entity;
    }

    private AgentTaskArtifactPublishDTO artifactCommand(int version, int expectedPrevious) {
        AgentTaskArtifactPublishDTO command = new AgentTaskArtifactPublishDTO();
        command.setArtifactId("artifact-1");
        command.setProducerAgentId(ACTOR);
        command.setArtifactType("analysis");
        command.setTitle("Analysis");
        command.setContent("bounded-content");
        command.setContentHash(sha256("bounded-content"));
        command.setArtifactVersion(version);
        command.setExpectedPreviousVersion(expectedPrevious);
        command.setVisibility("task_members");
        command.setMetadata(Map.of("format", "text/plain"));
        return command;
    }

    private AgentTaskArtifactEntity artifact(String id, int version, String producer, String visibility) {
        AgentTaskArtifactEntity entity = new AgentTaskArtifactEntity();
        entity.setArtifactId(id);
        entity.setTaskId(TASK);
        entity.setProducerAgentId(producer);
        entity.setArtifactType("analysis");
        entity.setTitle("Artifact");
        entity.setContent("bounded-content");
        entity.setContentHash(sha256("bounded-content"));
        entity.setArtifactVersion(version);
        entity.setVisibility(visibility);
        entity.setMetadataJson("{\"format\":\"text/plain\"}");
        entity.setCreatedAt(NOW);
        return entity;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
