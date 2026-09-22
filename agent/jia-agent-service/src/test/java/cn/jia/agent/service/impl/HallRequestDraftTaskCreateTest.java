package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.HallRequestDraftDaoImpl;
import cn.jia.agent.mapper.HallRequestDraftMapper;
import cn.jia.agent.entity.HallRequestDraftEntity;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HallRequestDraftTaskCreateTest {
    private static final HallRequestDraftService.OwnerScope SCOPE = new HallRequestDraftService.OwnerScope("0", "c", "o");
    private HallRequestDraftDao drafts;
    private HallTaskCreationService creation;
    private PersonalWorkspaceExecutionService executions;
    private HallPrivateCaseDao cases;
    private HallRequestDraftService service;
    private HallRequestDraftEntity editing;
    @BeforeEach void setUp() {
        drafts = mock(HallRequestDraftDao.class); creation = mock(HallTaskCreationService.class);
        executions = mock(PersonalWorkspaceExecutionService.class); cases = mock(HallPrivateCaseDao.class);
        service = new HallRequestDraftServiceImpl(drafts, cases, executions, mock(PersonalWorkspaceDao.class),
                mock(AgentTaskMetaDao.class), mock(AgentService.class), null,
                new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX)),
                () -> 1000L, creation);
        editing = new HallRequestDraftEntity().setDraftId("d").setTenantId("0").setClientId("c").setOwnerJiacn("o")
                .setKind("TASK_CREATE").setOriginRef("hall").setTitle("title").setInstruction("body")
                .setInputsJson("[]").setState("EDITING").setRevision(1L).setCreatedAt(1L).setUpdatedAt(1L);
        when(drafts.lock("0", "c", "o", "d")).thenReturn(editing);
    }
    @Test void nullableExecutionTaskReceiptReplaysWithoutPrivateExecutionOrSecondTask() {
        AtomicReference<HallRequestDraftEntity> submitted = new AtomicReference<>();
        when(drafts.findBySubmitKey("0", "c", "o", "key")).thenAnswer(i -> submitted.get());
        when(drafts.reserveSubmitIntent(eq("0"), eq("c"), eq("o"), eq("d"), eq(1L), eq("key"), anyString(), eq(1000L)))
                .thenReturn(1);
        when(drafts.markSubmitted(eq("0"), eq("c"), eq("o"), eq("d"), eq(1L), eq("key"),
                anyString(), isNull(), eq("42"), isNull(), eq(1000L))).thenAnswer(i -> {
                    submitted.set(editing.setState("SUBMITTED").setRevision(2L).setSubmitKey("key")
                            .setSubmitHash(i.getArgument(6)).setSubmissionRef("42").setUpdatedAt(1000L)); return 1;
                });
        when(drafts.find("0", "c", "o", "d")).thenAnswer(i -> submitted.get());
        var task = new HallRequestDraftService.TaskReference("42", "0");
        when(creation.create(SCOPE, "title", "body")).thenReturn(task); when(creation.get(SCOPE, "42")).thenReturn(task);
        var receipt = service.submit(SCOPE, "d", 1, true, "key");
        assertNull(receipt.execution()); assertEquals(task, receipt.task()); assertEquals("TASK", receipt.ref().sourceType());
        assertEquals(receipt, service.submit(SCOPE, "d", 1, true, "key"));
        assertEquals(receipt, service.getSubmissionByIdempotencyKey(SCOPE, "key"));
        verify(creation).create(SCOPE, "title", "body"); verifyNoInteractions(cases, executions);
        assertReason(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT,
                () -> service.submit(SCOPE, "other", 1, true, "key"));
    }
    @Test void unsupportedExecutionFieldsAndLossyTextFailBeforeReservation() {
        editing.setTargetAgentId("agent");
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.submit(SCOPE, "d", 1, true, "key"));
        editing.setTargetAgentId(null).setOutputMime(PersonalWorkspaceExecutionProperties.DOCX);
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.submit(SCOPE, "d", 1, true, "key"));
        editing.setOutputMime(null).setTitle("t".repeat(31));
        assertReason(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, () -> service.submit(SCOPE, "d", 1, true, "key"));
        verify(drafts, never()).reserveSubmitIntent(any(), any(), any(), any(), anyLong(), any(), any(), anyLong());
        verifyNoInteractions(creation, cases, executions);
    }
    @Test void noAcknowledgementOrStaleRevisionCannotCreateTask() {
        assertReason(HallRequestDraftService.Reason.BAD_REQUEST, () -> service.submit(SCOPE, "d", 1, false, "key"));
        assertReason(HallRequestDraftService.Reason.REVISION_CHANGED, () -> service.submit(SCOPE, "d", 2, true, "key"));
        verifyNoInteractions(creation, cases, executions);
    }
    @Test void taskFailureDoesNotMarkSubmissionAndCorruptExecutionOnTaskReceiptFailsClosed() {
        when(drafts.reserveSubmitIntent(eq("0"), eq("c"), eq("o"), eq("d"), eq(1L), eq("key"), anyString(), eq(1000L))).thenReturn(1);
        when(creation.create(SCOPE, "title", "body")).thenThrow(new HallRequestDraftService.Failure(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE));
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.submit(SCOPE, "d", 1, true, "key"));
        verify(drafts, never()).markSubmitted(any(), any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), anyLong());
        editing.setState("SUBMITTED").setSubmissionRef("42").setSubmitKey("key").setSubmitHash("hash").setSubmittedExecutionId("foreign");
        when(drafts.findBySubmitKey("0", "c", "o", "key")).thenReturn(editing);
        assertReason(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, () -> service.getSubmissionByIdempotencyKey(SCOPE, "key"));
    }
    @Test void realDaoForwardsNullableTaskReceiptToKindGuardedSql() {
        HallRequestDraftMapper mapper = mock(HallRequestDraftMapper.class);
        HallRequestDraftDao dao = new HallRequestDraftDaoImpl(mapper);
        String hash = "a".repeat(64);
        when(mapper.markSubmitted("0", "c", "o", "d", 1, "key", hash, null, "42", null, 1000))
                .thenReturn(1);
        assertEquals(1, dao.markSubmitted("0", "c", "o", "d", 1, "key", hash, null, "42", null, 1000));
        verify(mapper).markSubmitted("0", "c", "o", "d", 1, "key", hash, null, "42", null, 1000);
    }
    @Test void realDaoStillRejectsMalformedExecutionAndCaseWithoutExecutionBeforeSql() {
        HallRequestDraftMapper mapper = mock(HallRequestDraftMapper.class);
        HallRequestDraftDao dao = new HallRequestDraftDaoImpl(mapper);
        String hash = "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> dao.markSubmitted("0", "c", "o", "d", 1, "key", hash, null, "42", " ", 1000));
        assertThrows(IllegalArgumentException.class,
                () -> dao.markSubmitted("0", "c", "o", "d", 1, "key", hash, "case", "case", null, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> dao.markSubmitted("foreign", "c", "o", "d", 1, "key", hash, null, "42", null, 1000));
        verifyNoInteractions(mapper);
    }
    private static void assertReason(HallRequestDraftService.Reason reason, Runnable call) {
        assertEquals(reason, assertThrows(HallRequestDraftService.Failure.class, call::run).reason());
    }
}
