package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventAccessService.AuthorizedSubject;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskEventAccessServiceTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Mock AgentIdentityService identity;
    @Mock AgentTaskWorkspaceDao dao;

    private AgentTaskWorkspaceServiceImpl service;
    private TaskRow task;
    private MemberRow member;

    @BeforeEach
    void setUp() {
        service = new AgentTaskWorkspaceServiceImpl(identity, dao);
        task = task();
        member = member("accepted");
        lenient().doReturn(ACTOR).when(identity)
                .requireCanonicalAgentIdInScope(TENANT, CLIENT, TENANT, ACTOR);
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(task);
        when(dao.findActorMember(TENANT, CLIENT, TASK, ACTOR)).thenReturn(member);
    }

    @Test
    void authorizationIsShortRequiredReadOnlyTransactionNotSnapshotBoundary()
            throws Exception {
        Method authorize = AgentTaskWorkspaceServiceImpl.class.getMethod(
                "authorize", String.class, String.class, String.class, String.class);
        Transactional tx = authorize.getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRED, tx.propagation());
        assertEquals(Isolation.DEFAULT, tx.isolation());
        assertTrue(tx.readOnly());
        assertEquals(1, tx.rollbackFor().length);
        assertEquals(Exception.class, tx.rollbackFor()[0]);

        AuthorizedSubject subject = service.authorize(TENANT, CLIENT, TASK, ACTOR);
        assertEquals(TENANT, subject.tenantId());
        assertEquals(CLIENT, subject.clientId());
        assertEquals(TASK, subject.taskId());
        assertEquals(ACTOR, subject.actorAgentId());
        assertEquals("worker", subject.actorRole());
        assertFalse(subject.coordinatorAccess());
        assertFalse(subject.reviewerAccess());
        InOrder aclOrder = inOrder(identity, dao);
        aclOrder.verify(identity).requireCanonicalAgentIdInScope(
                TENANT, CLIENT, TENANT, ACTOR);
        aclOrder.verify(dao).findTask(TENANT, CLIENT, TASK);
        aclOrder.verify(dao).findActorMember(TENANT, CLIENT, TASK, ACTOR);
        verify(dao, never()).findMembers(TENANT, CLIENT, TASK);
        verify(dao, never()).findWorkItems(TENANT, CLIENT, TASK);
        verify(dao, never()).findOpenRequests(TENANT, CLIENT, TASK);
        verify(dao, never()).findLatestEvents(TENANT, CLIENT, TASK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"accepted", "working", "blocked", "done", "failed"})
    void exactReadableMemberStatusMatrixIsAccepted(String status) {
        member.setMemberStatus(status);
        AuthorizedSubject subject = service.authorize(TENANT, CLIENT, TASK, ACTOR);
        assertEquals("worker", subject.actorRole());
    }

    @ParameterizedTest
    @ValueSource(strings = {"invited", "rejected", "left", "unknown"})
    void nonReadableMemberStatusMatrixFailsWithOneGenericReason(String status) {
        member.setMemberStatus(status);
        assertGenericNotFound(() -> service.authorize(TENANT, CLIENT, TASK, ACTOR));
    }

    @Test
    void absentForeignInactiveAliasAndCoordinatorOnlyAllFailClosed() {
        doThrow(new AgentServiceImpl.AgentBizException(
                "AGENT_FORBIDDEN", "foreign or inactive"))
                .when(identity).requireCanonicalAgentIdInScope(
                        TENANT, CLIENT, TENANT, ACTOR);
        assertGenericNotFound(() -> service.authorize(TENANT, CLIENT, TASK, ACTOR));

        doReturn("agt_alias_target").when(identity)
                .requireCanonicalAgentIdInScope(TENANT, CLIENT, TENANT, ACTOR);
        assertGenericNotFound(() -> service.authorize(TENANT, CLIENT, TASK, ACTOR));

        when(identity.requireCanonicalAgentIdInScope(TENANT, CLIENT, TENANT, ACTOR))
                .thenReturn(ACTOR);
        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(null);
        assertGenericNotFound(() -> service.authorize(TENANT, CLIENT, TASK, ACTOR));

        when(dao.findTask(TENANT, CLIENT, TASK)).thenReturn(task);
        task.setCoordinatorAgentId(ACTOR);
        when(dao.findActorMember(TENANT, CLIENT, TASK, ACTOR)).thenReturn(null);
        assertGenericNotFound(() -> service.authorize(TENANT, CLIENT, TASK, ACTOR));
    }

    @Test
    void roleAndCoordinatorCapabilitiesAreFrozenInImmutableSubject() {
        member.setMemberRole("reviewer");
        AuthorizedSubject reviewer = service.authorize(TENANT, CLIENT, TASK, ACTOR);
        assertTrue(reviewer.reviewerAccess());
        assertFalse(reviewer.coordinatorAccess());

        member.setMemberRole("worker");
        task.setCoordinatorAgentId(ACTOR);
        AuthorizedSubject coordinator = service.authorize(TENANT, CLIENT, TASK, ACTOR);
        assertTrue(coordinator.coordinatorAccess());
        assertFalse(coordinator.reviewerAccess());
    }

    private static void assertGenericNotFound(Runnable invocation) {
        AgentTaskWorkspaceException exception = assertThrows(
                AgentTaskWorkspaceException.class, invocation::run);
        assertEquals(AgentTaskWorkspaceException.Reason.NOT_FOUND_OR_FORBIDDEN,
                exception.getReason());
    }

    private static TaskRow task() {
        TaskRow row = new TaskRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setCoordinatorAgentId("agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        return row;
    }

    private static MemberRow member(String status) {
        MemberRow row = new MemberRow();
        row.setTenantId(TENANT);
        row.setClientId(CLIENT);
        row.setTaskId(TASK);
        row.setAgentId(ACTOR);
        row.setMemberRole("worker");
        row.setMemberStatus(status);
        row.setAssignmentSource("manual");
        row.setVersion(1L);
        return row;
    }
}
