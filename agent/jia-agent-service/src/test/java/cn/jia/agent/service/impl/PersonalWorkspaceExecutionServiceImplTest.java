package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.entity.AgentTaskArtifactViewDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentWorkItemLeaseDTO;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskFormalDeliveryService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;

/** Focused owner/runtime queue contract; database mapper tests retain SQL exactness coverage. */
class PersonalWorkspaceExecutionServiceImplTest {
    private static final PersonalWorkspaceExecutionService.RuntimeScope RUNTIME =
            new PersonalWorkspaceExecutionService.RuntimeScope("0", "client-a", "owner-a", "agent-a", "runtime-a");
    private PersonalWorkspaceExecutionDao executions;
    private AgentRuntimeDao runtimes;
    private PersonalWorkspaceStorage storage;
    private PersonalWorkspaceWriteService writes;
    private PersonalWorkspaceExecutionService service;

    @BeforeEach
    void setUp() {
        executions = mock(PersonalWorkspaceExecutionDao.class);
        runtimes = mock(AgentRuntimeDao.class);
        storage = mock(PersonalWorkspaceStorage.class);
        when(storage.maxContentBytes()).thenReturn(4096L);
        writes = mock(PersonalWorkspaceWriteService.class);
        service = new PersonalWorkspaceExecutionServiceImpl(executions, mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class), runtimes, storage, writes,
                new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX)));
    }

    @Test
    void durableQueueReturnsOnlyExactQueuedAgentItemsWithStrictRuntimePayload() throws Exception {
        PersonalWorkspaceExecutionEntity accepted = execution("pwe_1", "agent-a", "QUEUED");
        PersonalWorkspaceExecutionEntity wrongAgent = execution("pwe_2", "agent-b", "QUEUED");
        PersonalWorkspaceExecutionEntity revoked = execution("pwe_3", "agent-a", "INPUTS_REVOKED");
        when(executions.listQueuedByTarget("0", "client-a", "owner-a", "agent-a", 16))
                .thenReturn(List.of(accepted, wrongAgent, revoked));
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_1"))
                .thenReturn(List.of(input("pwe_1")));

        List<PersonalWorkspaceExecutionService.RuntimeQueuedCommand> items = service.runtimeQueuedCommands(RUNTIME, 16);

        assertEquals(1, items.size());
        var item = items.getFirst();
        assertEquals(1, item.schemaVersion());
        assertEquals("command.dispatch", item.messageType());
        assertEquals("WORKSPACE_FILE_EXECUTE", item.commandType());
        assertEquals("agent-a", item.targetAgentId());
        assertEquals("pwe_task_1", item.taskId());
        assertEquals("pwe_run_1", item.runId());
        assertEquals("请保留标题并改正文", item.instruction());
        assertTrue(item.messageId().matches("pwe_msg_[0-9a-f]{64}"));
        assertTrue(item.commandId().matches("pwe_cmd_[0-9a-f]{64}"));
        assertEquals(List.of(new PersonalWorkspaceExecutionService.RuntimeInputCommand("input_1",
                "inputs/input_1.docx", "/internal/agent/tasks/pwe_task_1/runs/pwe_run_1/inputs/input_1/content",
                7L, sha("content"))), item.payload().inputManifest());
        assertEquals(List.of(new PersonalWorkspaceExecutionService.RuntimeOutput("output_1", "outputs/result.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 4096L,
                "/internal/agent/tasks/pwe_task_1/runs/pwe_run_1/outputs/output_1/content")), item.payload().outputManifest());
        assertFalse(item.toString().contains("AgentRuntime"));
    }

    @Test
    void durableQueuePinsMultipleCrossFormatMaterialsForOneExplicitOutput() throws Exception {
        service = new PersonalWorkspaceExecutionServiceImpl(executions, mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class), mock(AgentRuntimeDao.class), storage, writes,
                new PersonalWorkspaceExecutionProperties(List.of(
                        PersonalWorkspaceExecutionProperties.DOCX,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/pdf",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation")));
        PersonalWorkspaceExecutionEntity accepted = execution("pwe_multi", "agent-a", "QUEUED");
        accepted.setOutputContentMimeType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        when(executions.listQueuedByTarget("0", "client-a", "owner-a", "agent-a", 16)).thenReturn(List.of(accepted));
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_multi")).thenReturn(List.of(
                input("pwe_multi", "input_1", "pws_sheet", 3, "source.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                input("pwe_multi", "input_2", "pws_pdf", 1, "brief.pdf", "application/pdf")));

        var items = service.runtimeQueuedCommands(RUNTIME, 16);

        assertEquals(1, items.size());
        assertEquals(List.of(
                new PersonalWorkspaceExecutionService.RuntimeInputCommand("input_1", "inputs/input_1.xlsx",
                        "/internal/agent/tasks/pwe_task_1/runs/pwe_run_1/inputs/input_1/content", 7L, sha("content")),
                new PersonalWorkspaceExecutionService.RuntimeInputCommand("input_2", "inputs/input_2.pdf",
                        "/internal/agent/tasks/pwe_task_1/runs/pwe_run_1/inputs/input_2/content", 7L, sha("content"))),
                items.getFirst().payload().inputManifest());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                items.getFirst().payload().outputManifest().getFirst().contentType());
        assertEquals("outputs/result.pptx", items.getFirst().payload().outputManifest().getFirst().relativePath());
    }

    @Test
    void configuredCapabilitiesExposeOnlyEnabledFormatsAndFailClosedWhenStorageIsUnavailable() {
        assertEquals(List.of(PersonalWorkspaceExecutionProperties.DOCX), service.capabilities().allowedMimeTypes());
        assertTrue(service.capabilities().generationEnabled());

        PersonalWorkspaceStorage unavailableStorage = mock(PersonalWorkspaceStorage.class);
        PersonalWorkspaceExecutionService unavailable = new PersonalWorkspaceExecutionServiceImpl(executions,
                mock(PersonalWorkspaceDao.class), mock(PersonalWorkspaceTaskLinkDao.class),
                mock(AgentRuntimeDao.class), unavailableStorage, writes,
                new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX)));
        assertEquals(List.of(), unavailable.capabilities().allowedMimeTypes());
        assertFalse(unavailable.capabilities().generationEnabled());
        var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> unavailable.create(
                new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a"),
                new PersonalWorkspaceExecutionService.CreateCommand(null, "agent-a", null, "生成提纲",
                        PersonalWorkspaceExecutionProperties.DOCX, List.of()), "create-key"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE, failure.getReason());
    }

    @Test
    void queuedGenerationUsesEmptyInputManifestAndConfiguredOutputFormat() throws Exception {
        PersonalWorkspaceExecutionEntity generated = execution("pwe_generate", "agent-a", "QUEUED");
        generated.setInstruction("生成项目介绍文档");
        when(executions.listQueuedByTarget("0", "client-a", "owner-a", "agent-a", 16))
                .thenReturn(List.of(generated));
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_generate")).thenReturn(List.of());

        var items = service.runtimeQueuedCommands(RUNTIME, 16);

        assertEquals(1, items.size());
        assertEquals(List.of(), items.getFirst().payload().inputManifest());
        assertEquals(List.of(new PersonalWorkspaceExecutionService.RuntimeOutput("output_1", "outputs/result.docx",
                PersonalWorkspaceExecutionProperties.DOCX, 4096L,
                "/internal/agent/tasks/pwe_task_1/runs/pwe_run_1/outputs/output_1/content")),
                items.getFirst().payload().outputManifest());
    }

    @Test
    void taskExecutionStartsExistingRequiredWorkItemWithoutExposingLeaseToBrowser() {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId("agent-a"); runtime.setClientId("client-a"); runtime.setOwnerJiacn("owner-a");
        when(runtimes.findCandidateRosterByOwner("client-a", "owner-a")).thenReturn(List.of(runtime));
        WorkspaceConversationAccessService access = mock(WorkspaceConversationAccessService.class);
        AgentTaskWorkItemDao workItems = mock(AgentTaskWorkItemDao.class);
        AgentWorkItemLeaseService leases = mock(AgentWorkItemLeaseService.class);
        service.setTaskExecutionDependencies(access, workItems, leases);
        when(access.requireAccessible(any())).thenReturn(new WorkspaceConversationAccessService.ConversationView(
                "conversation-1", "TASK", "task-1", "task-1", List.of("agent-a"), 1L, 1L));
        AgentTaskWorkItemEntity ready = taskWorkItem("ready", 7L, null, null, null);
        AgentTaskWorkItemEntity running = taskWorkItem("running", 9L, "agent-a", "lease-secret", 9_999_999_999_999L);
        when(workItems.listByTask("0", "client-a", "owner-a", "task-1", "ready", 32)).thenReturn(List.of(ready));
        when(workItems.findByTaskAndWorkItemId("0", "client-a", "owner-a", "task-1", "work-1")).thenReturn(running);
        when(leases.claim(any(), any(), any(), any(), any(), any())).thenReturn(lease("claimed", 8L));
        when(leases.start(any(), any(), any(), any(), any(), any())).thenReturn(lease("running", 9L));
        when(executions.findByIdempotency("0", "client-a", "owner-a", "task-key")).thenReturn(null);
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_created")).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<PersonalWorkspaceExecutionEntity>getArgument(0).setExecutionId("pwe_created");
            return null;
        }).when(executions).insert(any(PersonalWorkspaceExecutionEntity.class));
        var captured = ArgumentCaptor.forClass(PersonalWorkspaceExecutionEntity.class);

        var view = service.create(new PersonalWorkspaceExecutionService.OwnerScope("0", "client-a", "owner-a"),
                new PersonalWorkspaceExecutionService.CreateCommand("conversation-1", "agent-a", "task-1",
                        "生成交付", PersonalWorkspaceExecutionProperties.DOCX, List.of()), "task-key");

        verify(executions).insert(captured.capture());
        assertEquals("TASK", captured.getValue().getExecutionMode());
        assertEquals("work-1", captured.getValue().getWorkItemId());
        assertEquals("TASK", view.executionMode());
        assertEquals("task-1", view.businessTaskId());
        assertEquals("work-1", view.workItemId());
        assertEquals("running", view.workItemState());
        assertFalse(view.toString().contains("lease-secret"));
        assertFalse(view.toString().contains("storageUri"));
        verify(leases, times(1)).claim(any(), any(), any(), any(), any(), any());
        verify(leases, times(1)).start(any(), any(), any(), any(), any(), any());
    }

    @Test
    void stageOutputRejectsMislabeledRuntimeBytesBeforeDurableStorage() {
        PersonalWorkspaceExecutionEntity execution = execution("pwe_1", "agent-a", "QUEUED");
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(execution);

        var failure = assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> service.stageOutput(
                RUNTIME, "pwe_task_1", "pwe_run_1", "output_1", "result.docx",
                PersonalWorkspaceExecutionProperties.DOCX, "not an OOXML package".getBytes(StandardCharsets.UTF_8)));

        assertEquals(PersonalWorkspaceExecutionService.Reason.BAD_REQUEST, failure.getReason());
        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void exactRuntimeFailureMovesQueuedExecutionToTerminalStateAndPreventsQueueReplay() {
        PersonalWorkspaceExecutionEntity execution = execution("pwe_1", "agent-a", "QUEUED");
        when(executions.findByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(execution);
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(execution);
        when(executions.listInputs("0", "client-a", "owner-a", "pwe_1")).thenReturn(List.of());

        var failed = service.fail(RUNTIME, "pwe_task_1", "pwe_run_1", "OUTPUT_MISSING");

        assertEquals("FAILED", failed.state());
        assertEquals("AGENT_DELIVERY_FAILED", failed.failureCode());
        assertEquals("Agent 未能完成本次交付，请调整需求后重新创建执行。", failed.failureMessage());
        assertEquals("FAILED", execution.getExecutionState());
        assertTrue(execution.getFailedAt() > 0);
        verify(executions).update(execution);
        when(executions.listQueuedByTarget("0", "client-a", "owner-a", "agent-a", 16))
                .thenReturn(List.of(execution));
        assertEquals(List.of(), service.runtimeQueuedCommands(RUNTIME, 16));
    }

    @Test
    void runtimeFailureCannotAffectAnotherAgentOrACompletedExecution() {
        PersonalWorkspaceExecutionEntity other = execution("pwe_1", "agent-b", "QUEUED");
        when(executions.findByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(other);
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(other);
        var denied = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.fail(RUNTIME, "pwe_task_1", "pwe_run_1", "OUTPUT_MISSING"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, denied.getReason());
        verify(executions, never()).update(other);

        PersonalWorkspaceExecutionEntity committed = execution("pwe_1", "agent-a", "OUTPUT_COMMITTED");
        when(executions.findByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(committed);
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(committed);
        var completed = assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.fail(RUNTIME, "pwe_task_1", "pwe_run_1", "OUTPUT_MISSING"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND, completed.getReason());
        verify(executions, never()).update(committed);
    }

    @Test
    void committedRuntimeOutputKeepsLegacySourceAndMarksAgentDeliveryOrigin() throws Exception {
        PersonalWorkspaceExecutionEntity execution = execution("pwe_1", "agent-a", "QUEUED");
        String contentHash = sha("agent result");
        PersonalWorkspaceExecutionOutputEntity output = new PersonalWorkspaceExecutionOutputEntity()
                .setOutputId("output_1").setExecutionId("pwe_1").setOwnerJiacn("owner-a")
                .setOriginalFilename("result.docx")
                .setContentMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .setByteLength(12L).setContentHash(contentHash).setStorageUri("memory://result")
                .setOutputState("STAGED");
        output.setTenantId("0"); output.setClientId("client-a");
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "pwe_task_1", "pwe_run_1"))
                .thenReturn(execution);
        when(executions.lockOutputs("0", "client-a", "owner-a", "pwe_1"))
                .thenReturn(List.of(output));
        String manifestId = "pwe_m_" + sha("pwe_task_1\npwe_run_1\noutput_1\n"
                + contentHash + "\n12\n");

        var committed = service.commitOutputs(RUNTIME, "pwe_task_1", "pwe_run_1", manifestId,
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration(
                        "output_1", contentHash, 12L)));

        ArgumentCaptor<PersonalWorkspaceFileEntity> file = ArgumentCaptor.forClass(
                PersonalWorkspaceFileEntity.class);
        ArgumentCaptor<PersonalWorkspaceVersionEntity> version = ArgumentCaptor.forClass(
                PersonalWorkspaceVersionEntity.class);
        verify(writes).archiveRuntimeOutput(any(PersonalWorkspaceWriteService.Scope.class),
                file.capture(), version.capture());
        assertEquals("UPLOAD", file.getValue().getSourceKind());
        assertEquals("AGENT_DELIVERY", file.getValue().getOriginKind());
        assertEquals(file.getValue().getFileId(), version.getValue().getFileId());
        assertEquals("COMMITTED", committed.state());
    }

    @Test
    void taskOutputPublishesTrustedWorkspaceArtifactAndFormalDeliveryInOneRootMutation() throws Exception {
        AgentTaskWorkItemDao workItems = mock(AgentTaskWorkItemDao.class);
        AgentWorkItemLeaseService leases = mock(AgentWorkItemLeaseService.class);
        service.setTaskExecutionDependencies(mock(WorkspaceConversationAccessService.class), workItems, leases);
        AgentTaskArtifactService artifacts = mock(AgentTaskArtifactService.class);
        AgentTaskFormalDeliveryService formalDeliveries = mock(AgentTaskFormalDeliveryService.class);
        AgentTaskMutationTransaction mutations = mock(AgentTaskMutationTransaction.class);
        service.setTaskPublicationDependencies(artifacts, formalDeliveries, mutations);

        PersonalWorkspaceExecutionEntity execution = execution("pwe_task_execution", "agent-a", "QUEUED")
                .setTaskId("task-1").setExecutionMode("TASK").setWorkItemId("work-1")
                .setLeaseToken("lease-secret").setLeaseWorkItemVersion(9L)
                .setLeaseExpiresAt(9_999_999_999_999L);
        String contentHash = sha("agent result");
        PersonalWorkspaceExecutionOutputEntity output = new PersonalWorkspaceExecutionOutputEntity()
                .setOutputId("output_1").setExecutionId("pwe_task_execution").setOwnerJiacn("owner-a")
                .setOriginalFilename("result.docx")
                .setContentMimeType(PersonalWorkspaceExecutionProperties.DOCX)
                .setByteLength(12L).setContentHash(contentHash).setStorageUri("memory://result")
                .setOutputState("STAGED").setPublicationState("PENDING").setPublicationRevision(0L);
        output.setTenantId("0"); output.setClientId("client-a");
        AgentTaskMetaEntity root = new AgentTaskMetaEntity().setTaskId("task-1")
                .setTaskVersion(4L).setRewardStatus("running").setAssignedAgentId("agent-a");
        root.setTenantId("0"); root.setClientId("client-a"); root.setOwnerJiacn("owner-a");
        AgentTaskWorkItemEntity running = taskWorkItem("running", 9L, "agent-a", "lease-secret", 9_999_999_999_999L);
        when(executions.findByTaskRun("0", "client-a", "owner-a", "task-1", "pwe_run_1"))
                .thenReturn(execution);
        when(executions.lockByTaskRun("0", "client-a", "owner-a", "task-1", "pwe_run_1"))
                .thenReturn(execution);
        when(executions.lockOutputs("0", "client-a", "owner-a", "pwe_task_execution"))
                .thenReturn(List.of(output));
        when(workItems.findByTaskAndWorkItemId("0", "client-a", "owner-a", "task-1", "work-1"))
                .thenReturn(running);
        when(storage.read(any(), org.mockito.ArgumentMatchers.eq("memory://result"),
                org.mockito.ArgumentMatchers.eq(contentHash), org.mockito.ArgumentMatchers.eq(12L),
                org.mockito.ArgumentMatchers.eq(PersonalWorkspaceExecutionProperties.DOCX)))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent("agent result".getBytes(StandardCharsets.UTF_8),
                        contentHash, 12L, PersonalWorkspaceExecutionProperties.DOCX));
        AgentTaskArtifactViewDTO delivery = artifact("pwe_art_pwe_task_execution", contentHash,
                PersonalWorkspaceExecutionProperties.DOCX);
        AgentTaskArtifactViewDTO manifest = artifact("pwe_manifest_pwe_task_execution", null, "application/json");
        when(artifacts.publish(any(), any(), any(), any(), any(), any()))
                .thenReturn(delivery, manifest);
        AgentTaskFormalDeliveryViewDTO formal = new AgentTaskFormalDeliveryViewDTO();
        formal.setTaskId("task-1"); formal.setWorkItemId("work-1");
        formal.setDeliveryId("pwe_delivery_" + sha("pwe_task_execution\npwe_run_1"));
        formal.setState("submitted");
        when(formalDeliveries.submit(any(), any(), any(), any(), any(), any())).thenReturn(formal);
        doAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> callback = invocation.getArgument(4);
            return callback.apply(root);
        }).when(mutations).executeWithLockedTaskRootInOwnerScope(any(), any(), any(), any(), any());
        String manifestId = "pwe_m_" + sha("task-1\npwe_run_1\noutput_1\n" + contentHash + "\n12\n");

        var committed = service.commitOutputs(RUNTIME, "task-1", "pwe_run_1", manifestId,
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1", contentHash, 12L)));

        assertEquals("COMMITTED", committed.state());
        assertEquals("PUBLISHED", output.getPublicationState());
        assertEquals("pwe_art_pwe_task_execution", output.getArtifactId());
        assertEquals(1, output.getArtifactVersion());
        assertEquals(formal.getDeliveryId(), output.getFormalDeliveryId());
        assertEquals("OUTPUT_COMMITTED", execution.getExecutionState());
        verify(writes).archiveRuntimeOutput(any(), any(), any());
        verify(artifacts, times(2)).publish(any(), any(), any(), org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("agent-a"), any());
        verify(formalDeliveries).submit(any(), any(), any(), org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq("agent-a"), any());
    }

    private static AgentTaskArtifactViewDTO artifact(String id, String contentHash, String mime) {
        AgentTaskArtifactViewDTO value = new AgentTaskArtifactViewDTO();
        value.setArtifactId(id).setArtifactVersion(1).setTaskId("task-1").setWorkItemId("work-1")
                .setProducerAgentId("agent-a").setContentHash(contentHash == null ? sha("manifest") : contentHash)
                .setContentMimeType(mime);
        return value;
    }

    private static AgentTaskWorkItemEntity taskWorkItem(String state, long version, String assignee,
            String token, Long until) {
        AgentTaskWorkItemEntity item = new AgentTaskWorkItemEntity();
        item.setTaskId("task-1"); item.setWorkItemId("work-1"); item.setRequiredItem(true);
        item.setStatus(state); item.setVersion(version); item.setAssigneeAgentId(assignee);
        item.setLeaseToken(token); item.setLeaseUntil(until);
        item.setTenantId("0"); item.setClientId("client-a"); item.setOwnerJiacn("owner-a");
        return item;
    }

    private static AgentWorkItemLeaseDTO lease(String state, long version) {
        AgentWorkItemLeaseDTO value = new AgentWorkItemLeaseDTO();
        value.setTaskId("task-1"); value.setWorkItemId("work-1"); value.setAgentId("agent-a");
        value.setStatus(state); value.setLeaseToken("lease-secret"); value.setVersion(version);
        value.setLeaseUntil(9_999_999_999_999L);
        return value;
    }

    private static PersonalWorkspaceExecutionEntity execution(String id, String agent, String state) {
        PersonalWorkspaceExecutionEntity item = new PersonalWorkspaceExecutionEntity()
                .setExecutionId(id).setOwnerJiacn("owner-a").setTaskId("pwe_task_1").setRunId("pwe_run_1")
                .setTargetAgentId(agent).setInstruction("请保留标题并改正文")
                .setOutputContentMimeType(PersonalWorkspaceExecutionProperties.DOCX).setExecutionState(state).setGrantRevision(1L);
        item.setTenantId("0"); item.setClientId("client-a");
        return item;
    }

    private static PersonalWorkspaceExecutionInputEntity input(String executionId) {
        return input(executionId, "input_1", "pws_1", 1, "source.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }

    private static PersonalWorkspaceExecutionInputEntity input(String executionId, String inputRef, String fileId,
            int version, String filename, String contentMimeType) {
        PersonalWorkspaceExecutionInputEntity input = new PersonalWorkspaceExecutionInputEntity()
                .setExecutionId(executionId).setInputRef(inputRef).setFileId(fileId).setFileVersion(version)
                .setOriginalFilename(filename).setContentMimeType(contentMimeType)
                .setByteLength(7L).setContentHash(sha("content"));
        input.setTenantId("0"); input.setClientId("client-a"); input.setOwnerJiacn("owner-a");
        return input;
    }

    private static String sha(String text) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required for workspace fixture hashes", unavailable);
        }
    }
}
