package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.HallPrivateCaseEntity;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HallRequestDraftSubmissionServiceTest {
    private static final HallRequestDraftService.OwnerScope SCOPE =
            new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
    private static final String MIME = PersonalWorkspaceExecutionProperties.DOCX;

    private HallRequestDraftDao drafts;
    private HallPrivateCaseDao cases;
    private PersonalWorkspaceExecutionService executions;
    private AgentTaskMetaDao tasks;
    private WorkspaceConversationAccessService conversations;
    private AgentService agents;
    private AtomicLong now;
    private HallRequestDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        drafts = mock(HallRequestDraftDao.class);
        cases = mock(HallPrivateCaseDao.class);
        executions = mock(PersonalWorkspaceExecutionService.class);
        agents = mock(AgentService.class);
        tasks = mock(AgentTaskMetaDao.class);
        conversations = mock(WorkspaceConversationAccessService.class);
        AgentRuntimeDTO agent = new AgentRuntimeDTO();
        agent.setAgentId("agent-a");
        agent.setOwnerJiacn("owner-a");
        when(agents.requireApiKeyOwnedAgent("client-a", "owner-a", "agent-a"))
                .thenReturn(agent);
        now = new AtomicLong(1_790_000_000_000L);
        service = new HallRequestDraftServiceImpl(drafts, cases, executions,
                mock(PersonalWorkspaceDao.class), tasks, agents, conversations,
                new PersonalWorkspaceExecutionProperties(List.of(MIME)), now::get);
    }

    @Test
    void createSubmitReservesKeyCreatesPrivateCaseAndReturnsDurableReplay() {
        HallRequestDraftEntity editing = draft("hdr_1", "CREATE", 3, "EDITING");
        HallRequestDraftEntity submitted = copy(editing).setState("SUBMITTED").setRevision(4L)
                .setCaseId("hpc_fixed").setSubmissionRef("hpc_fixed")
                .setSubmittedExecutionId("pwe_1").setSubmitKey("submit-key")
                .setSubmitHash(hashForTest("SUBMIT", "hdr_1", "3", "true"));
        when(drafts.findBySubmitKey("0", "client-a", "owner-a", "submit-key"))
                .thenReturn(null, submitted);
        when(drafts.lock("0", "client-a", "owner-a", "hdr_1")).thenReturn(editing);
        when(drafts.reserveSubmitIntent(eq("0"), eq("client-a"), eq("owner-a"),
                eq("hdr_1"), eq(3L), eq("submit-key"), any(), eq(now.get()))).thenReturn(1);
        when(drafts.markSubmitted(eq("0"), eq("client-a"), eq("owner-a"), eq("hdr_1"),
                eq(3L), eq("submit-key"), any(), any(), any(), eq("pwe_1"), eq(now.get())))
                .thenAnswer(invocation -> {
                    String caseId = invocation.getArgument(7);
                    submitted.setCaseId(caseId).setSubmissionRef(caseId);
                    return 1;
                });
        when(drafts.find("0", "client-a", "owner-a", "hdr_1")).thenReturn(submitted);
        when(executions.create(any(), any(), eq("submit-key"))).thenReturn(execution("pwe_1"));
        when(executions.get(any(), eq("pwe_1"))).thenReturn(execution("pwe_1"));

        HallRequestDraftService.SubmissionReceipt receipt = service.submit(
                SCOPE, "hdr_1", 3, true, "submit-key");
        assertEquals("PRIVATE_CASE", receipt.ref().sourceType());
        assertEquals(submitted.getCaseId(), receipt.ref().sourceId());
        assertEquals("pwe_1", receipt.execution().executionId());
        assertEquals(now.get(), receipt.submittedAt());

        ArgumentCaptor<HallPrivateCaseEntity> privateCase =
                ArgumentCaptor.forClass(HallPrivateCaseEntity.class);
        verify(cases).insert(privateCase.capture());
        assertEquals(SCOPE.ownerJiacn(), privateCase.getValue().getOwnerJiacn());
        assertEquals(1, privateCase.getValue().getRevision());
        verify(cases).insertExecution(any());

        HallRequestDraftService.SubmissionReceipt replay =
                service.getSubmissionByIdempotencyKey(SCOPE, "submit-key");
        assertEquals(receipt, replay);
    }

    @Test
    void taskCreateIsExplicit422BeforeKeyReservationOrExecution() {
        HallRequestDraftEntity editing = draft("hdr_task", "TASK_CREATE", 1, "EDITING");
        when(drafts.findBySubmitKey("0", "client-a", "owner-a", "key")).thenReturn(null);
        when(drafts.lock("0", "client-a", "owner-a", "hdr_task")).thenReturn(editing);

        HallRequestDraftService.Failure failure = assertThrows(
                HallRequestDraftService.Failure.class,
                () -> service.submit(SCOPE, "hdr_task", 1, true, "key"));
        assertEquals(HallRequestDraftService.Reason.SUBMISSION_UNAVAILABLE, failure.reason());
        verify(drafts, never()).reserveSubmitIntent(any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong(), any(), any(),
                org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(cases, executions);
    }

    @Test
    void submitRequiresAcknowledgementAndCompleteFieldsWithoutSideEffects() {
        HallRequestDraftEntity incomplete = draft("hdr_2", "CREATE", 1, "EDITING")
                .setTargetAgentId(null);
        when(drafts.findBySubmitKey("0", "client-a", "owner-a", "key")).thenReturn(null);
        when(drafts.lock("0", "client-a", "owner-a", "hdr_2")).thenReturn(incomplete);

        assertReason(HallRequestDraftService.Reason.BAD_REQUEST,
                () -> service.submit(SCOPE, "hdr_2", 1, false, "key"));
        assertReason(HallRequestDraftService.Reason.BAD_REQUEST,
                () -> service.submit(SCOPE, "hdr_2", 1, true, "key"));
        verifyNoInteractions(cases, executions);
    }

    @Test
    void taskActionDelegatesToExistingTaskExecutionContractWithoutFormalReworkFields() {
        HallRequestDraftEntity editing = draft("hdr_task_action", "TASK_ACTION", 2, "EDITING")
                .setTaskId("task-1").setConversationId("conversation-1");
        HallRequestDraftEntity submitted = copy(editing).setState("SUBMITTED").setRevision(3L)
                .setSubmissionRef("task-1").setSubmittedExecutionId("pwe_task_1")
                .setSubmitKey("task-submit")
                .setSubmitHash(hashForTest("SUBMIT", "hdr_task_action", "2", "true"));
        AgentTaskMetaEntity task = new AgentTaskMetaEntity().setTaskId("task-1")
                .setOwnerJiacn("owner-a");
        task.setTenantId("0"); task.setClientId("client-a");
        when(tasks.findByTaskIdInOwnerScope("0", "client-a", "owner-a", "task-1"))
                .thenReturn(task);
        when(conversations.requireAccessible(any(), eq("conversation-1"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView(
                        "conversation-1", "TASK", "task-1", "task-1",
                        List.of("agent-a"), 1, 1));
        when(drafts.findBySubmitKey("0", "client-a", "owner-a", "task-submit"))
                .thenReturn(null);
        when(drafts.lock("0", "client-a", "owner-a", "hdr_task_action"))
                .thenReturn(editing);
        when(drafts.reserveSubmitIntent(eq("0"), eq("client-a"), eq("owner-a"),
                eq("hdr_task_action"), eq(2L), eq("task-submit"), any(), eq(now.get())))
                .thenReturn(1);
        when(drafts.markSubmitted(eq("0"), eq("client-a"), eq("owner-a"),
                eq("hdr_task_action"), eq(2L), eq("task-submit"), any(),
                eq(null), eq("task-1"), eq("pwe_task_1"), eq(now.get()))).thenReturn(1);
        when(drafts.find("0", "client-a", "owner-a", "hdr_task_action"))
                .thenReturn(submitted);
        PersonalWorkspaceExecutionService.ExecutionView taskExecution =
                new PersonalWorkspaceExecutionService.ExecutionView(
                        "pwe_task_1", "task-1", "run-1", "conversation-1", "agent-a",
                        "QUEUED", null, null, 1, MIME, List.of(), null,
                        "TASK", "task-1", "work-1", "RUNNING");
        when(executions.create(any(), any(), eq("task-submit"))).thenReturn(taskExecution);
        when(executions.get(any(), eq("pwe_task_1"))).thenReturn(taskExecution);

        HallRequestDraftService.SubmissionReceipt receipt = service.submit(
                SCOPE, "hdr_task_action", 2, true, "task-submit");
        assertEquals("TASK", receipt.ref().sourceType());
        assertEquals("task-1", receipt.ref().sourceId());
        ArgumentCaptor<PersonalWorkspaceExecutionService.CreateCommand> command =
                ArgumentCaptor.forClass(PersonalWorkspaceExecutionService.CreateCommand.class);
        verify(executions).create(any(), command.capture(), eq("task-submit"));
        assertEquals("task-1", command.getValue().taskId());
        assertEquals("conversation-1", command.getValue().conversationId());
        assertEquals(null, command.getValue().sourceOutputRef(),
                "ordinary TASK_ACTION must not impersonate formal-delivery rework");
        verifyNoInteractions(cases);
    }

    @Test
    void sameKeyDifferentDraftCannotReplayAnotherSubmission() {
        HallRequestDraftEntity submitted = draft("hdr_other", "CREATE", 2, "SUBMITTED")
                .setCaseId("hpc_other").setSubmissionRef("hpc_other")
                .setSubmittedExecutionId("pwe_other").setSubmitKey("shared")
                .setSubmitHash(hashForTest("SUBMIT", "hdr_other", "1", "true"));
        when(drafts.findBySubmitKey("0", "client-a", "owner-a", "shared"))
                .thenReturn(submitted);
        assertReason(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.submit(SCOPE, "hdr_target", 1, true, "shared"));
        verify(drafts, never()).lock(any(), any(), any(), any());
        verifyNoInteractions(cases, executions);
    }

    @Test
    void invisibleCaseReturns404WithoutExecutionLookup() {
        when(cases.find("0", "client-a", "owner-a", "hpc_foreign")).thenReturn(null);
        assertReason(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getCase(SCOPE, "hpc_foreign"));
        verifyNoInteractions(executions);
    }

    private HallRequestDraftEntity draft(String id, String kind, long revision, String state) {
        return new HallRequestDraftEntity().setDraftId(id).setTenantId("0")
                .setClientId("client-a").setOwnerJiacn("owner-a").setKind(kind)
                .setOriginRef("map").setTitle("title").setInstruction("instruction")
                .setTargetAgentId("agent-a").setOutputMime(MIME).setInputsJson("[]")
                .setRevision(revision).setState(state).setCreateKey("create-key-" + id)
                .setCreateHash("a".repeat(64)).setCreatedAt(now.get()).setUpdatedAt(now.get());
    }

    private static HallRequestDraftEntity copy(HallRequestDraftEntity source) {
        return new HallRequestDraftEntity().setDraftId(source.getDraftId())
                .setTenantId(source.getTenantId()).setClientId(source.getClientId())
                .setOwnerJiacn(source.getOwnerJiacn()).setKind(source.getKind())
                .setOriginRef(source.getOriginRef()).setTitle(source.getTitle())
                .setInstruction(source.getInstruction()).setTargetAgentId(source.getTargetAgentId())
                .setOutputMime(source.getOutputMime()).setInputsJson(source.getInputsJson())
                .setRevision(source.getRevision()).setState(source.getState())
                .setCreateKey(source.getCreateKey()).setCreateHash(source.getCreateHash())
                .setCreatedAt(source.getCreatedAt()).setUpdatedAt(source.getUpdatedAt());
    }

    private static PersonalWorkspaceExecutionService.ExecutionView execution(String id) {
        return new PersonalWorkspaceExecutionService.ExecutionView(id, "private-task", "run-1",
                null, "agent-a", "QUEUED", null, null, 1, MIME, List.of(), null,
                "PRIVATE", null, null, null);
    }

    private static void assertReason(HallRequestDraftService.Reason reason, Runnable call) {
        HallRequestDraftService.Failure failure = assertThrows(
                HallRequestDraftService.Failure.class, call::run);
        assertEquals(reason, failure.reason());
    }

    private static String hashForTest(String... values) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
