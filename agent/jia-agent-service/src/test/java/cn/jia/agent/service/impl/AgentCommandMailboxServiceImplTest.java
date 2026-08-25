package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.mapper.AgentCommandMailboxRow;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandMailboxServiceImplTest {
    private AgentCommandTransportMapper mapper;
    private AgentCommandMailboxServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(AgentCommandTransportMapper.class);
        service = new AgentCommandMailboxServiceImpl(mapper);
    }

    @Test
    void returnsBoundedStableKeysetPageAndKeepsInternalIdPrivate() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 3, false))
                .thenReturn(List.of(
                        row(30L, "cmd-30", "WAITING_AGENT", 300L),
                        row(29L, "cmd-29", "SENT", 300L),
                        row(28L, "cmd-28", "RECEIVED", 299L)));

        AgentCommandMailboxPage page = service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 2, false);

        assertEquals(List.of("cmd-30", "cmd-29"),
                page.entries().stream().map(entry -> entry.commandId()).toList());
        assertEquals(300L, page.nextBeforeCreateTime());
        assertEquals(29L, page.nextBeforeId());
        assertEquals("target-a", page.entries().getFirst().targetAgentId());
        verify(mapper).selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 3, false);
    }

    @Test
    void finalShortPageHasNoNextCursor() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                300L, 29L, 51, true))
                .thenReturn(List.of(row(28L, "cmd-28", "SUCCEEDED", 299L)));

        AgentCommandMailboxPage page = service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                300L, 29L, 50, true);

        assertEquals(1, page.entries().size());
        assertNull(page.nextBeforeCreateTime());
        assertNull(page.nextBeforeId());
    }

    @Test
    void defaultProjectionRejectsTerminalRowsEvenIfMapperDrifts() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 11, false))
                .thenReturn(List.of(row(1L, "cmd-terminal", "DEAD", 100L)));

        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 10, false));
    }

    @Test
    void crossTargetOrUnstableOrderingFailsClosed() {
        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 11, true))
                .thenReturn(List.of(new AgentCommandMailboxRow(
                        1L, "cmd-cross", "task-1", null, "target-other",
                        AgentProtocolConstants.COMMAND_CONTEXT_REFRESH,
                        "PENDING", 10_000L, 100L, 100L)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 10, true));

        when(mapper.selectMailboxPage(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 11, true))
                .thenReturn(List.of(
                        row(2L, "cmd-2", "PENDING", 100L),
                        row(3L, "cmd-3", "PENDING", 100L)));
        assertThrows(IllegalStateException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", "task-1",
                null, null, 10, true));
    }

    @Test
    void invalidScopeCursorAndBoundsNeverReachMapper() {
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                100L, null, 50, false));
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a", null,
                null, null, 0, false));
        assertThrows(IllegalArgumentException.class, () -> service.query(
                "tenant-a", "client-a", "caller-a", "target-a ", null,
                null, null, 50, false));
        verify(mapper, never()).selectMailboxPage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    private AgentCommandMailboxRow row(
            long id, String commandId, String status, long createTime) {
        return new AgentCommandMailboxRow(
                id, commandId, "task-1", "work-1", "target-a",
                AgentProtocolConstants.COMMAND_CONTEXT_REFRESH, status,
                10_000L, createTime, createTime + 1);
    }
}
