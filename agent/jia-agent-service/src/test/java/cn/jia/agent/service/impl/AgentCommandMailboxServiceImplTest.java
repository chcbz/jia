package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.mapper.AgentCommandMailboxRow;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentCommandMailboxAccessDeniedException;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandMailboxServiceImplTest {
    private static final long NOW = 1_000L;

    private AgentCommandTransportMapper mapper;
    private AgentService agentService;
    private AgentTaskCollaborationAccessService accessService;
    private PlatformTransactionManager transactions;
    private AgentCommandMailboxServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentCommandTransportMapper.class);
        agentService = mock(AgentService.class);
        accessService = mock(AgentTaskCollaborationAccessService.class);
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(accessService.resolveMemberAccessForUpdate(any(), any(), any(), any()))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(agentService.requireApiKeyOwnedAgentForUpdate(any(), any(), any()))
                .thenAnswer(invocation -> runtime(invocation.getArgument(2)));
        service = new AgentCommandMailboxServiceImpl(
                mapper, agentService, accessService, transactions, () -> NOW);
    }

    @Test
    void locksMembershipAndOwnershipInsideRequiredBoundaryBeforeProjection() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 3, false, NOW))
                .thenReturn(List.of(
                        row(30L, "cmd-30", "WAITING_AGENT", 300L, 10_000L),
                        row(29L, "cmd-29", "SENT", 300L, 10_000L),
                        row(28L, "cmd-28", "RECEIVED", 299L, 10_000L)));

        AgentCommandMailboxPage page = service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 2, false);

        assertEquals(List.of("cmd-30", "cmd-29"),
                page.entries().stream().map(entry -> entry.commandId()).toList());
        assertEquals(300L, page.nextBeforeCreateTime());
        assertEquals(29L, page.nextBeforeId());
        InOrder order = inOrder(transactions, accessService, agentService, mapper);
        order.verify(transactions).getTransaction(any());
        order.verify(accessService).resolveMemberAccessForUpdate(
                "tenant-a", "client-a", "task-1", "caller-a");
        order.verify(accessService).resolveMemberAccessForUpdate(
                "tenant-a", "client-a", "task-1", "target-a");
        order.verify(agentService).requireApiKeyOwnedAgentForUpdate(
                "client-a", "tenant-a", "caller-a");
        order.verify(agentService).requireApiKeyOwnedAgentForUpdate(
                "client-a", "tenant-a", "target-a");
        order.verify(mapper).selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 3, false, NOW);
        order.verify(transactions).commit(any(TransactionStatus.class));
    }

    @Test
    void revokeObservedByLockedAccessReturnsNoProjectionAndRollsBack() {
        when(accessService.resolveMemberAccessForUpdate(
                "tenant-a", "client-a", "task-1", "target-a"))
                .thenReturn(AgentTaskAccessLevel.NONE);

        assertThrows(AgentCommandMailboxAccessDeniedException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 10, false));

        verify(mapper, never()).selectMailboxPage(
                any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    @Test
    void finalShortPageHasNoNextCursor() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                300L, 29L, 51, true, NOW))
                .thenReturn(List.of(row(28L, "cmd-28", "SUCCEEDED", 299L, 900L)));

        AgentCommandMailboxPage page = service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                300L, 29L, 50, true);

        assertEquals(1, page.entries().size());
        assertNull(page.nextBeforeCreateTime());
        assertNull(page.nextBeforeId());
    }

    @Test
    void defaultProjectionRejectsTerminalAndExpiredNonTerminalRowsIfMapperDrifts() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 11, false, NOW))
                .thenReturn(List.of(row(1L, "cmd-terminal", "DEAD", 100L, 10_000L)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 10, false));

        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 11, false, NOW))
                .thenReturn(List.of(row(1L, "cmd-expired-pending", "PENDING", 100L, NOW)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 10, false));
    }

    @Test
    void crossTargetOrUnstableOrderingFailsClosed() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 11, true, NOW))
                .thenReturn(List.of(new AgentCommandMailboxRow(
                        1L, "cmd-cross", "task-1", null, "target-other",
                        AgentProtocolConstants.COMMAND_CONTEXT_REFRESH,
                        "PENDING", 10_000L, 100L, 100L)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 10, true));

        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 11, true, NOW))
                .thenReturn(List.of(
                        row(2L, "cmd-2", "PENDING", 100L, 10_000L),
                        row(3L, "cmd-3", "PENDING", 100L, 10_000L)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 10, true));
    }

    @Test
    void malformedScopeCursorAndBoundsNeverStartTransaction() {
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                100L, null, 50, false));
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a ", null,
                null, null, 50, false));
        verify(transactions, never()).getTransaction(any());
    }

    @Test
    void mapperInfrastructureFailureIsObservableAndRollsBack() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 11, false, NOW))
                .thenThrow(new DataAccessResourceFailureException("mysql unavailable"));

        DataAccessResourceFailureException failure = assertThrows(
                DataAccessResourceFailureException.class, () -> service.query(
                        "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                        null, null, 10, false));

        assertEquals("mysql unavailable", failure.getMessage());
        verify(transactions).rollback(any(TransactionStatus.class));
    }

    private AgentRuntimeDTO runtime(String agentId) {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(agentId);
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        return runtime;
    }

    private AgentCommandMailboxRow row(
            long id, String commandId, String status, long createTime, long expiresAt) {
        return new AgentCommandMailboxRow(
                id, commandId, "task-1", "work-1", "target-a",
                AgentProtocolConstants.COMMAND_CONTEXT_REFRESH, status,
                expiresAt, createTime, createTime + 1);
    }
}
