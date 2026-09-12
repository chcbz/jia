package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskAggregationCommandDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentTaskAggregationDeliveryPolicyGuardTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";

    private AgentTaskMetaDao taskDao;
    private AgentIdentityService identities;
    private AgentTaskMutationTransaction transactions;
    private AgentTaskEventWriter events;
    private AgentTaskAggregationCalculator calculator;
    private AgentTaskMetaEntity root;
    private AgentTaskAggregationServiceImpl service;

    @BeforeEach
    void setUp() {
        taskDao = mock(AgentTaskMetaDao.class);
        identities = mock(AgentIdentityService.class);
        transactions = mock(AgentTaskMutationTransaction.class);
        events = mock(AgentTaskEventWriter.class);
        calculator = mock(AgentTaskAggregationCalculator.class);
        root = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus("running")
                .setTaskVersion(4L).setCurrentEventVersion(0L);
        root.setTenantId(TENANT);
        root.setClientId(CLIENT);
        when(transactions.executeWithLockedTaskRoot(
                eq(TENANT), eq(CLIENT), eq(TASK), any())).thenAnswer(invocation ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(root));
        service = new AgentTaskAggregationServiceImpl(
                taskDao, identities, transactions, events, calculator, () -> 1_000L);
    }

    @Test
    void policy1CompletionIsReservedBeforeSnapshotOrMutation() {
        root.setDeliveryPolicyVersion(1);

        AgentTaskStateException denied = assertThrows(
                AgentTaskStateException.class,
                () -> service.aggregate(TENANT, CLIENT, TASK, command()));

        assertEquals(AgentTaskStateException.Reason.RESERVED_FOR_CLAIM_PROTOCOL,
                denied.getReason());
        verifyNoInteractions(taskDao, identities, events, calculator);
    }

    @Test
    void unsupportedDeliveryPolicyFailsClosedBeforeSnapshotOrMutation() {
        root.setDeliveryPolicyVersion(2);

        AgentTaskStateException denied = assertThrows(
                AgentTaskStateException.class,
                () -> service.aggregate(TENANT, CLIENT, TASK, command()));

        assertEquals(AgentTaskStateException.Reason.INVALID_PERSISTED_STATE,
                denied.getReason());
        verifyNoInteractions(taskDao, identities, events, calculator);
    }

    private AgentTaskAggregationCommandDTO command() {
        AgentTaskAggregationCommandDTO command = new AgentTaskAggregationCommandDTO();
        command.setExpectedVersion(4L);
        return command;
    }
}
