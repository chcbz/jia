package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
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

/** Focused owner/runtime queue contract; database mapper tests retain SQL exactness coverage. */
class PersonalWorkspaceExecutionServiceImplTest {
    private static final PersonalWorkspaceExecutionService.RuntimeScope RUNTIME =
            new PersonalWorkspaceExecutionService.RuntimeScope("0", "client-a", "owner-a", "agent-a", "runtime-a");
    private PersonalWorkspaceExecutionDao executions;
    private PersonalWorkspaceStorage storage;
    private PersonalWorkspaceWriteService writes;
    private PersonalWorkspaceExecutionService service;

    @BeforeEach
    void setUp() {
        executions = mock(PersonalWorkspaceExecutionDao.class);
        storage = mock(PersonalWorkspaceStorage.class);
        when(storage.maxContentBytes()).thenReturn(4096L);
        writes = mock(PersonalWorkspaceWriteService.class);
        service = new PersonalWorkspaceExecutionServiceImpl(executions, mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class), mock(AgentRuntimeDao.class), storage, writes,
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

    private static PersonalWorkspaceExecutionEntity execution(String id, String agent, String state) {
        PersonalWorkspaceExecutionEntity item = new PersonalWorkspaceExecutionEntity()
                .setExecutionId(id).setOwnerJiacn("owner-a").setTaskId("pwe_task_1").setRunId("pwe_run_1")
                .setTargetAgentId(agent).setInstruction("请保留标题并改正文")
                .setOutputContentMimeType(PersonalWorkspaceExecutionProperties.DOCX).setExecutionState(state);
        item.setTenantId("0"); item.setClientId("client-a");
        return item;
    }

    private static PersonalWorkspaceExecutionInputEntity input(String executionId) {
        PersonalWorkspaceExecutionInputEntity input = new PersonalWorkspaceExecutionInputEntity()
                .setExecutionId(executionId).setInputRef("input_1").setFileId("pws_1").setFileVersion(1)
                .setOriginalFilename("source.docx")
                .setContentMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .setByteLength(7L).setContentHash(sha("content"));
        input.setTenantId("0"); input.setClientId("client-a"); input.setOwnerJiacn("owner-a");
        return input;
    }

    private static String sha(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
