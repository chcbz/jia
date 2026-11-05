package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatBountyRequestIndexPageTest {
    public static class TaskRoots {
        public AgentTaskMetaEntity findByTaskIdInOwnerScope(String tenant, String client, String owner, String taskId) {
            var task = new AgentTaskMetaEntity().setTaskId(taskId).setOwnerJiacn(owner);
            task.setTenantId(tenant); task.setClientId(client);
            return task;
        }
    }
    private ChatBountyRequestIndexStore store;
    private ChatBountyRequestIndexService service;
    private ChatConversationDao conversations;
    private final ChatBountyRequestIndexStore.Scope scope = new ChatBountyRequestIndexStore.Scope("0", "owner", "client", "101", 1);
    private final ChatBountyRequestIndexStore.Row row = new ChatBountyRequestIndexStore.Row(7, "0", "owner", "client", "r7", 1, "101", 1);
    private ChatRequestEntity request;

    @BeforeEach void setup() {
        store = mock(ChatBountyRequestIndexStore.class);
        conversations = mock(ChatConversationDao.class);
        var conversation = new ChatConversationEntity().setId(101L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty")
                .setConversationScopeKey("task:task").setTaskId("task").setLifecycleGeneration(1L);
        conversation.setTenantId("0"); conversation.setClientId("client");
        when(conversations.findScopedById("owner", "client", "101")).thenReturn(conversation);
        service = new ChatBountyRequestIndexService(store, conversations, new TaskRoots());
        request = new ChatRequestEntity().setId(7L).setRequestId("r7").setRequestRevision(1L)
                .setConversationId("101").setConversationGeneration(1L).setUserMessageId(9007199254740993L)
                .setAggregateState("RUNNING").setStateVersion(2L);
        when(store.highWatermark(scope)).thenReturn(7L);
        when(store.page(scope, 0, 7, 2)).thenReturn(List.of(row));
        when(store.projections(scope, List.of(row))).thenReturn(new ChatBountyRequestIndexStore.ProjectionRows(List.of(request), List.of(), List.of()));
    }
    private ChatBountyRequestIndexService.Page read() {
        return service.read("0", "owner", "client", "101", new ChatBountyRequestIndexService.Query(null, null, null, "1"));
    }
    @Test void mapsOneBatchWithoutPerRequestServiceReadsAndKeepsWire() {
        var page = read();
        assertEquals("9007199254740993", page.entries().getFirst().request().userMessageId());
        assertEquals("7", page.through()); assertEquals("0", page.after());
        assertFalse(page.hasMore()); assertNull(page.nextAfter());
        verify(store).highWatermark(scope); verify(store).page(scope, 0, 7, 2);
        verify(store).projections(scope, List.of(row)); verifyNoMoreInteractions(store);
        verify(conversations).findScopedById("owner", "client", "101"); verifyNoMoreInteractions(conversations);
    }
    @Test void probeRowDoesNotCauseProjectionWork() {
        var probe = new ChatBountyRequestIndexStore.Row(8, "0", "owner", "client", "r8", 1, "101", 1);
        when(store.highWatermark(scope)).thenReturn(8L);
        when(store.page(scope, 0, 8, 2)).thenReturn(List.of(row, probe));
        var page = read();
        assertTrue(page.hasMore()); assertEquals("7", page.nextAfter());
        verify(store).projections(scope, List.of(row));
    }
    @Test void missingExecutionLinkAndMissingPlanningStepRemainReadFailures() {
        var step = new ChatBountyRequestIndexStore.StepRow("r7", 1, "s7", 1, "task", 0, "agent", "EXECUTE", "RUNNING", 1, null, null, null);
        when(store.projections(scope, List.of(row))).thenReturn(new ChatBountyRequestIndexStore.ProjectionRows(List.of(request), List.of(), List.of(step)));
        assertEquals(ChatBountyRequestIndexService.Reason.UNAVAILABLE,
                assertThrows(ChatBountyRequestIndexService.Failure.class, this::read).reason());
        request.setAggregateState("PLANNING");
        when(store.projections(scope, List.of(row))).thenReturn(new ChatBountyRequestIndexStore.ProjectionRows(List.of(request), List.of(), List.of()));
        assertEquals(ChatBountyRequestIndexService.Reason.UNAVAILABLE,
                assertThrows(ChatBountyRequestIndexService.Failure.class, this::read).reason());
    }
    @Test void ownerBoundaryStopsBeforePageReads() {
        when(conversations.findScopedById("owner", "client", "101")).thenReturn(null);
        assertEquals(ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatBountyRequestIndexService.Failure.class, this::read).reason());
        verifyNoInteractions(store);
    }
}
