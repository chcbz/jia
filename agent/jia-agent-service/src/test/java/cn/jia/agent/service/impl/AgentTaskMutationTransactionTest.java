package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskMutationTransactionTest extends BaseMockTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";

    @Mock
    AgentTaskMetaDao taskMetaDao;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    AgentTaskMutationTransaction.LockedTaskMutation<String> lockedMutation;
    @Mock
    AgentTaskMutationTransaction.TaskRootReservation reservation;
    @Mock
    AgentTaskMutationTransaction.ReservedTaskMutation<String> reservedMutation;

    AgentTaskMutationTransactionImpl transaction;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        transaction = new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
    }

    @Test
    void existingTaskUsesRequiredTransactionAndLocksRootBeforeMutation() {
        AgentTaskMetaEntity root = root();
        when(taskMetaDao.findByTaskIdForUpdate(TENANT, CLIENT, TASK)).thenReturn(root);
        when(lockedMutation.apply(root)).thenReturn("changed");

        assertEquals("changed", transaction.executeWithLockedTaskRoot(
                TENANT, CLIENT, TASK, lockedMutation));

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED,
                definition.getValue().getPropagationBehavior());
        InOrder order = inOrder(transactionManager, taskMetaDao, lockedMutation);
        order.verify(transactionManager).getTransaction(any());
        order.verify(taskMetaDao).findByTaskIdForUpdate(TENANT, CLIENT, TASK);
        order.verify(lockedMutation).apply(root);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void workItemRouteLocksItsScopedTaskRootBeforeMutation() {
        AgentTaskMetaEntity root = root();
        when(taskMetaDao.findByWorkItemIdForUpdate(TENANT, CLIENT, "work-1"))
                .thenReturn(root);
        when(lockedMutation.apply(root)).thenReturn("changed");

        assertEquals("changed", transaction.executeWithLockedTaskRootForWorkItem(
                TENANT, CLIENT, "work-1", lockedMutation));

        InOrder order = inOrder(transactionManager, taskMetaDao, lockedMutation);
        order.verify(transactionManager).getTransaction(any());
        order.verify(taskMetaDao).findByWorkItemIdForUpdate(TENANT, CLIENT, "work-1");
        order.verify(lockedMutation).apply(root);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void missingOrDriftedRootFailsClosedBeforeMutation() {
        when(taskMetaDao.findByTaskIdForUpdate(TENANT, CLIENT, TASK)).thenReturn(null);

        AgentTaskCollaborationException missing = assertThrows(
                AgentTaskCollaborationException.class,
                () -> transaction.executeWithLockedTaskRoot(
                        TENANT, CLIENT, TASK, lockedMutation));
        assertEquals(AgentTaskCollaborationException.Reason.NOT_FOUND, missing.getReason());
        verify(lockedMutation, never()).apply(any());

        AgentTaskMetaEntity drifted = root();
        drifted.setClientId("other-client");
        when(taskMetaDao.findByTaskIdForUpdate(TENANT, CLIENT, TASK)).thenReturn(drifted);
        AgentTaskCollaborationException drift = assertThrows(
                AgentTaskCollaborationException.class,
                () -> transaction.executeWithLockedTaskRoot(
                        TENANT, CLIENT, TASK, lockedMutation));
        assertEquals(AgentTaskCollaborationException.Reason.INVALID_PERSISTED_STATE,
                drift.getReason());
        verify(lockedMutation, never()).apply(any());
    }

    @Test
    void rootReservationRunsBeforeRootLockAndReportsWhetherCreated() {
        AgentTaskMetaEntity root = root();
        when(reservation.reserve()).thenReturn(1);
        when(taskMetaDao.findByTaskIdForUpdate(TENANT, CLIENT, TASK)).thenReturn(root);
        when(reservedMutation.apply(root, true)).thenReturn("created");

        assertEquals("created", transaction.executeAfterTaskRootReservation(
                TENANT, CLIENT, TASK, reservation, reservedMutation));

        InOrder order = inOrder(transactionManager, reservation, taskMetaDao, reservedMutation);
        order.verify(transactionManager).getTransaction(any());
        order.verify(reservation).reserve();
        order.verify(taskMetaDao).findByTaskIdForUpdate(TENANT, CLIENT, TASK);
        order.verify(reservedMutation).apply(root, true);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void reservationRejectsUnexpectedRowCountAndInvalidInputBeforeBusinessMutation() {
        when(reservation.reserve()).thenReturn(2);

        assertThrows(IllegalStateException.class,
                () -> transaction.executeAfterTaskRootReservation(
                        TENANT, CLIENT, TASK, reservation, reservedMutation));
        verify(taskMetaDao, never()).findByTaskIdForUpdate(any(), any(), any());
        verifyNoInteractions(reservedMutation);

        assertThrows(IllegalArgumentException.class,
                () -> transaction.executeWithLockedTaskRoot(
                        " tenant-a", CLIENT, TASK, lockedMutation));
        assertFalse(transaction.isValidScopeId(" tenant-a", 50));
        assertTrue(transaction.isValidScopeId(TENANT, 50));
    }

    private AgentTaskMetaEntity root() {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity()
                .setTaskId(TASK)
                .setTaskVersion(3L)
                .setCurrentEventVersion(4L);
        root.setTenantId(TENANT);
        root.setClientId(CLIENT);
        return root;
    }
}
