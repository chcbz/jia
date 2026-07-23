package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMemberDTO;
import cn.jia.agent.entity.AgentTaskMemberEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskStateDTO;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.exception.AgentTaskStateException.Reason;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.state.AgentTaskMemberStatus;
import cn.jia.agent.state.AgentTaskStatus;
import cn.jia.agent.state.AgentTaskWorkItemStatus;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskStateServiceImplTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK_ID = "task-1";
    private static final String AGENT_ID = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String WORK_ITEM_ID = "work-1";
    private static final long NOW = 10_000L;

    @Mock
    AgentTaskMetaDao taskMetaDao;
    @Mock
    AgentTaskMemberDao memberDao;
    @Mock
    AgentTaskWorkItemDao workItemDao;

    AgentTaskStateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskStateServiceImpl(taskMetaDao, memberDao, workItemDao, () -> NOW);
    }

    @Test
    void stateDefinitionsCoverRequiredTransitionsAndLegacyTaskStatuses() {
        assertTrue(AgentTaskMemberStatus.INVITED.canTransitionTo(AgentTaskMemberStatus.ACCEPTED));
        assertTrue(AgentTaskMemberStatus.INVITED.canTransitionTo(AgentTaskMemberStatus.REJECTED));
        assertTrue(AgentTaskMemberStatus.ACCEPTED.canTransitionTo(AgentTaskMemberStatus.WORKING));
        assertTrue(AgentTaskMemberStatus.ACCEPTED.canTransitionTo(AgentTaskMemberStatus.LEFT));
        assertTrue(AgentTaskMemberStatus.WORKING.canTransitionTo(AgentTaskMemberStatus.DONE));
        assertTrue(AgentTaskMemberStatus.WORKING.canTransitionTo(AgentTaskMemberStatus.BLOCKED));
        assertTrue(AgentTaskMemberStatus.WORKING.canTransitionTo(AgentTaskMemberStatus.FAILED));
        assertTrue(AgentTaskMemberStatus.WORKING.canTransitionTo(AgentTaskMemberStatus.LEFT));
        assertTrue(AgentTaskMemberStatus.BLOCKED.canTransitionTo(AgentTaskMemberStatus.WORKING));
        assertTrue(AgentTaskMemberStatus.BLOCKED.canTransitionTo(AgentTaskMemberStatus.FAILED));
        assertTrue(AgentTaskMemberStatus.BLOCKED.canTransitionTo(AgentTaskMemberStatus.LEFT));

        assertTrue(AgentTaskWorkItemStatus.PENDING.canTransitionTo(AgentTaskWorkItemStatus.READY));
        assertTrue(AgentTaskWorkItemStatus.READY.canTransitionTo(AgentTaskWorkItemStatus.CLAIMED));
        assertTrue(AgentTaskWorkItemStatus.CLAIMED.canTransitionTo(AgentTaskWorkItemStatus.RUNNING));
        assertTrue(AgentTaskWorkItemStatus.CLAIMED.canTransitionTo(AgentTaskWorkItemStatus.READY));
        assertTrue(AgentTaskWorkItemStatus.RUNNING.canTransitionTo(AgentTaskWorkItemStatus.BLOCKED));
        assertTrue(AgentTaskWorkItemStatus.BLOCKED.canTransitionTo(AgentTaskWorkItemStatus.READY));
        assertTrue(AgentTaskWorkItemStatus.RUNNING.canTransitionTo(AgentTaskWorkItemStatus.SUBMITTED));
        assertTrue(AgentTaskWorkItemStatus.SUBMITTED.canTransitionTo(AgentTaskWorkItemStatus.COMPLETED));
        assertTrue(AgentTaskWorkItemStatus.SUBMITTED.canTransitionTo(AgentTaskWorkItemStatus.READY));
        assertTrue(AgentTaskWorkItemStatus.RUNNING.canTransitionTo(AgentTaskWorkItemStatus.FAILED));
        assertTrue(AgentTaskWorkItemStatus.READY.canTransitionTo(AgentTaskWorkItemStatus.CANCELLED));

        for (String legacy : new String[] {"open", "assigned", "running", "completed", "failed", "archived"}) {
            assertEquals(legacy, AgentTaskStatus.fromValue(legacy).value());
        }
    }

    @Test
    void legalTaskTransitionUsesScopedTaskVersionCas() {
        AgentTaskMetaEntity current = task("assigned", 4L);
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(current);
        when(taskMetaDao.updateStatusByVersion(
                TENANT, CLIENT, TASK_ID, 4L, "running", NOW, null, null)).thenReturn(1);

        AgentTaskStateDTO result = service.transitionTask(
                TENANT, CLIENT, TASK_ID, transition("running", 4L, null));

        assertEquals("task", result.getAggregateType());
        assertEquals("running", result.getStatus());
        assertEquals(5L, result.getVersion());
        assertEquals(NOW, result.getChangedAt());
        verify(taskMetaDao).findByTaskId(TENANT, CLIENT, TASK_ID);
        verify(taskMetaDao).updateStatusByVersion(
                TENANT, CLIENT, TASK_ID, 4L, "running", NOW, null, null);
    }

    @Test
    void illegalAndTerminalTaskTransitionsFailClosedWithoutCasWrite() {
        AgentTaskMetaEntity completed = task("completed", 8L);
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(completed);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionTask(
                        TENANT, CLIENT, TASK_ID, transition("running", 8L, null)));

        assertEquals(Reason.INVALID_TRANSITION, exception.getReason());
        verify(taskMetaDao, never()).updateStatusByVersion(
                any(), any(), any(), anyLong(), any(), any(), any(), any());
        assertFalse(AgentTaskStatus.ARCHIVED.canTransitionTo(AgentTaskStatus.RUNNING));
        assertFalse(AgentTaskMemberStatus.DONE.canTransitionTo(AgentTaskMemberStatus.WORKING));
        assertFalse(AgentTaskWorkItemStatus.COMPLETED.canTransitionTo(AgentTaskWorkItemStatus.READY));
    }

    @Test
    void memberDoneUsesScopedCasAndNeverCompletesTask() {
        AgentTaskMemberEntity current = member("working", 3L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID)).thenReturn(current);
        when(memberDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(3L), any())).thenReturn(1);

        AgentTaskStateDTO result = service.transitionMember(
                TENANT, CLIENT, TASK_ID, AGENT_ID, transition("done", 3L, null));

        assertEquals("done", result.getStatus());
        assertEquals(4L, result.getVersion());
        ArgumentCaptor<AgentTaskMemberDTO> update = ArgumentCaptor.forClass(AgentTaskMemberDTO.class);
        verify(memberDao).updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(3L), update.capture());
        assertEquals("done", update.getValue().getMemberStatus());
        assertEquals(NOW, update.getValue().getCompletedAt());
        verifyNoInteractions(taskMetaDao);
    }

    @Test
    void blockedAndFailedStatesRequireAReasonBeforeAnyWrite() {
        AgentTaskMemberEntity member = member("working", 3L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID)).thenReturn(member);

        AgentTaskStateException memberFailure = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMember(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, transition("blocked", 3L, " ")));
        assertEquals(Reason.INVALID_REQUEST, memberFailure.getReason());
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());

        AgentTaskMetaEntity task = task("running", 5L);
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(task);
        AgentTaskStateException taskFailure = assertThrows(AgentTaskStateException.class,
                () -> service.transitionTask(
                        TENANT, CLIENT, TASK_ID, transition("failed", 5L, null)));
        assertEquals(Reason.INVALID_REQUEST, taskFailure.getReason());
        verify(taskMetaDao, never()).updateStatusByVersion(
                any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void legalWorkItemTransitionsPreserveSnapshotAndSetSubmissionTime() {
        AgentTaskWorkItemEntity current = workItem("running", 6L);
        current.setDescription("preserve me");
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID)).thenReturn(current);
        when(workItemDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(WORK_ITEM_ID), eq(6L), any())).thenReturn(1);

        AgentTaskStateDTO result = service.transitionWorkItem(
                TENANT, CLIENT, WORK_ITEM_ID, transition("submitted", 6L, null));

        assertEquals("submitted", result.getStatus());
        assertEquals(7L, result.getVersion());
        ArgumentCaptor<AgentTaskWorkItemDTO> update = ArgumentCaptor.forClass(AgentTaskWorkItemDTO.class);
        verify(workItemDao).updateByVersion(
                eq(TENANT), eq(CLIENT), eq(WORK_ITEM_ID), eq(6L), update.capture());
        assertEquals("submitted", update.getValue().getStatus());
        assertEquals("preserve me", update.getValue().getDescription());
        assertEquals(NOW, update.getValue().getSubmittedAt());
    }

    @Test
    void claimSensitiveWorkItemTransitionsAreDefinedButReservedForB04() {
        AgentTaskWorkItemEntity current = workItem("ready", 2L);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionWorkItem(
                        TENANT, CLIENT, WORK_ITEM_ID, transition("claimed", 2L, null)));

        assertEquals(Reason.RESERVED_FOR_CLAIM_PROTOCOL, exception.getReason());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
        assertTrue(AgentTaskWorkItemStatus.READY.requiresClaimProtocol(AgentTaskWorkItemStatus.CLAIMED));
    }

    @Test
    void scopeMissIsNotFoundWithoutLeakingOrWritingAcrossScope() {
        when(memberDao.findByTaskAndAgent(
                "tenant-wrong", CLIENT, TASK_ID, AGENT_ID)).thenReturn(null);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMember(
                        "tenant-wrong", CLIENT, TASK_ID, AGENT_ID, transition("accepted", 0L, null)));

        assertEquals(Reason.NOT_FOUND, exception.getReason());
        verify(memberDao).findByTaskAndAgent("tenant-wrong", CLIENT, TASK_ID, AGENT_ID);
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
        verifyNoInteractions(taskMetaDao, workItemDao);
    }

    @Test
    void staleVersionAndRacingCasReturnExplicitConflict() {
        AgentTaskMemberEntity current = member("accepted", 5L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID)).thenReturn(current);

        AgentTaskStateException stale = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMember(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, transition("working", 4L, null)));
        assertEquals(Reason.VERSION_CONFLICT, stale.getReason());
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());

        when(memberDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(5L), any())).thenReturn(0);
        AgentTaskStateException race = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMember(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, transition("working", 5L, null)));
        assertEquals(Reason.VERSION_CONFLICT, race.getReason());
    }

    @Test
    void combinedTransitionValidatesBothStatesBeforeTheFirstWrite() {
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID))
                .thenReturn(member("working", 3L));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID))
                .thenReturn(workItem("completed", 6L));

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        transition("done", 3L, null), transition("running", 6L, null)));

        assertEquals(Reason.INVALID_TRANSITION, exception.getReason());
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
        verifyNoInteractions(taskMetaDao);
    }

    @Test
    void secondCasConflictRollsBackTheCombinedMemberAndWorkItemTransaction() throws Exception {
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID))
                .thenReturn(member("working", 3L));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID))
                .thenReturn(workItem("running", 6L));
        when(memberDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(3L), any())).thenReturn(1);
        when(workItemDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(WORK_ITEM_ID), eq(6L), any())).thenReturn(0);

        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        AgentTaskStateService transactionalService = (AgentTaskStateService) proxyFactory.getProxy();

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> transactionalService.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        transition("done", 3L, null), transition("submitted", 6L, null)));

        assertEquals(Reason.VERSION_CONFLICT, exception.getReason());
        InOrder order = inOrder(memberDao, workItemDao);
        order.verify(memberDao).updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(3L), any());
        order.verify(workItemDao).updateByVersion(
                eq(TENANT), eq(CLIENT), eq(WORK_ITEM_ID), eq(6L), any());
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(taskMetaDao);

        Transactional annotation = AgentTaskStateServiceImpl.class.getMethod(
                "transitionMemberAndWorkItem",
                String.class, String.class, String.class, String.class, String.class,
                AgentTaskStateTransitionDTO.class, AgentTaskStateTransitionDTO.class)
                .getAnnotation(Transactional.class);
        assertTrue(Arrays.asList(annotation.rollbackFor()).contains(Exception.class));
    }


    // ── P1-2: Combined transition fails closed on blank/mismatched assignee ──

    @Test
    void combinedTransitionRejectsBlankAssigneeBeforeAnyWrite() {
        AgentTaskWorkItemEntity wi = workItem("running", 6L);
        wi.setAssigneeAgentId(null);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID))
                .thenReturn(member("working", 3L));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID))
                .thenReturn(wi);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        transition("done", 3L, null), transition("submitted", 6L, null)));

        assertEquals(Reason.INVALID_REQUEST, exception.getReason());
        assertTrue(exception.getMessage().contains("non-blank assignee"));
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
    }

    @Test
    void combinedTransitionRejectsEmptyStringAssigneeBeforeAnyWrite() {
        AgentTaskWorkItemEntity wi = workItem("running", 6L);
        wi.setAssigneeAgentId("   ");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID))
                .thenReturn(member("working", 3L));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID))
                .thenReturn(wi);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        transition("done", 3L, null), transition("submitted", 6L, null)));

        assertEquals(Reason.INVALID_REQUEST, exception.getReason());
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
    }

    @Test
    void combinedTransitionRejectsMismatchedAssigneeBeforeAnyWrite() {
        AgentTaskWorkItemEntity wi = workItem("running", 6L);
        wi.setAssigneeAgentId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID))
                .thenReturn(member("working", 3L));
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID))
                .thenReturn(wi);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMemberAndWorkItem(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, WORK_ITEM_ID,
                        transition("done", 3L, null), transition("submitted", 6L, null)));

        assertEquals(Reason.INVALID_REQUEST, exception.getReason());
        assertTrue(exception.getMessage().contains("assignee does not match"));
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
    }

    // ── P2-3: Persisted noncanonical status values are rejected ──

    @Test
    void persistedNoncanonicalTaskStatusIsRejectedWithoutNormalization() {
        AgentTaskMetaEntity current = task("RUNNING", 5L); // uppercase, not canonical "running"
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionTask(
                        TENANT, CLIENT, TASK_ID, transition("completed", 5L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
        verify(taskMetaDao, never()).updateStatusByVersion(
                any(), any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void persistedNoncanonicalTaskStatusWhitespaceIsRejected() {
        AgentTaskMetaEntity current = task(" running ", 5L); // whitespace not canonical
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionTask(
                        TENANT, CLIENT, TASK_ID, transition("completed", 5L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
    }

    @Test
    void persistedNoncanonicalTaskStatusEmptyIsRejected() {
        AgentTaskMetaEntity current = task("", 5L);
        when(taskMetaDao.findByTaskId(TENANT, CLIENT, TASK_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionTask(
                        TENANT, CLIENT, TASK_ID, transition("completed", 5L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
    }

    @Test
    void persistedNoncanonicalMemberStatusIsRejected() {
        AgentTaskMemberEntity current = member("WORKING", 3L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionMember(
                        TENANT, CLIENT, TASK_ID, AGENT_ID, transition("done", 3L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
        verify(memberDao, never()).updateByVersion(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void persistedNoncanonicalWorkItemStatusIsRejected() {
        AgentTaskWorkItemEntity current = workItem("RUNNING", 6L);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionWorkItem(
                        TENANT, CLIENT, WORK_ITEM_ID, transition("submitted", 6L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
        verify(workItemDao, never()).updateByVersion(any(), any(), any(), anyLong(), any());
    }

    @Test
    void persistedNoncanonicalWorkerItemStatusWhitespaceIsRejected() {
        AgentTaskWorkItemEntity current = workItem(" running ", 6L);
        when(workItemDao.findByWorkItemId(TENANT, CLIENT, WORK_ITEM_ID)).thenReturn(current);

        AgentTaskStateException exception = assertThrows(AgentTaskStateException.class,
                () -> service.transitionWorkItem(
                        TENANT, CLIENT, WORK_ITEM_ID, transition("submitted", 6L, null)));

        assertEquals(Reason.INVALID_PERSISTED_STATE, exception.getReason());
    }

    @Test
    void requestedTargetStatusStillAcceptsNormalizedValues() {
        // Verify that request target normalization is preserved (P2-3 scope)
        // This test confirms that the target " DONE " (with whitespace) is normalized
        // while persisted non-canonical values are rejected.
        AgentTaskMemberEntity current = member("working", 3L);
        when(memberDao.findByTaskAndAgent(TENANT, CLIENT, TASK_ID, AGENT_ID)).thenReturn(current);
        when(memberDao.updateByVersion(
                eq(TENANT), eq(CLIENT), eq(TASK_ID), eq(AGENT_ID), eq(3L), any())).thenReturn(1);

        // Target " DONE " is normalized to "done" by requestedMemberStatus
        AgentTaskStateDTO result = service.transitionMember(
                TENANT, CLIENT, TASK_ID, AGENT_ID, transition(" DONE ", 3L, null));

        assertEquals("done", result.getStatus());
    }


    private AgentTaskMetaEntity task(String status, long version) {
        AgentTaskMetaEntity entity = new AgentTaskMetaEntity();
        entity.setTaskId(TASK_ID);
        entity.setRewardStatus(status);
        entity.setTaskVersion(version);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private AgentTaskMemberEntity member(String status, long version) {
        AgentTaskMemberEntity entity = new AgentTaskMemberEntity();
        entity.setTaskId(TASK_ID);
        entity.setAgentId(AGENT_ID);
        entity.setMemberRole("worker");
        entity.setMemberStatus(status);
        entity.setAssignmentSource("manual");
        entity.setVersion(version);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private AgentTaskWorkItemEntity workItem(String status, long version) {
        AgentTaskWorkItemEntity entity = new AgentTaskWorkItemEntity();
        entity.setWorkItemId(WORK_ITEM_ID);
        entity.setTaskId(TASK_ID);
        entity.setTitle("Implement B03");
        entity.setWorkType("implementation");
        entity.setAssigneeAgentId(AGENT_ID);
        entity.setStatus(status);
        entity.setPriority(10);
        entity.setRequiredItem(true);
        entity.setAttemptCount(1);
        entity.setMaxAttempts(3);
        entity.setVersion(version);
        entity.setTenantId(TENANT);
        entity.setClientId(CLIENT);
        return entity;
    }

    private AgentTaskStateTransitionDTO transition(String status, long version, String failureReason) {
        AgentTaskStateTransitionDTO transition = new AgentTaskStateTransitionDTO();
        transition.setTargetStatus(status);
        transition.setExpectedVersion(version);
        transition.setFailureReason(failureReason);
        return transition;
    }
}
