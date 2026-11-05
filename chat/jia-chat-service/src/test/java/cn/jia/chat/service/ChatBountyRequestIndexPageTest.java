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
        service = new ChatBountyRequestIndexService(store, conversations, new TaskRoots(),
                mock(cn.jia.chat.deliberation.ChatDeliveryContextStore.class), mock(JuyitingConversationScopeService.class));
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
    @Test void messageWindowUsesOnlyPersistedPageReferencesAndCurrentRequestInOneSnapshot() {
        var conversation = conversations.findScopedById("owner", "client", "101");
        clearInvocations(conversations, store);
        var message = new cn.jia.chat.entity.ChatMessageEntity().setId(21L)
                .setMetadata("{\"requestId\":\"r7\"}");
        var duplicate = new cn.jia.chat.entity.ChatMessageEntity().setId(22L)
                .setMetadata("{\"requestId\":\"r7\"}");
        when(store.byRequests(scope, List.of("r7"))).thenReturn(List.of(row));
        when(store.latest(scope)).thenReturn(List.of(row));
        when(store.eventCursor(scope)).thenReturn(9007199254740993L);
        var result = service.messageWindow(new ChatConversationService.MessagePage(
                List.of(message, duplicate), false, null, conversation), true);
        assertEquals(1, result.entries().size());
        assertEquals("r7", result.entries().getFirst().request().requestId());
        assertEquals("9007199254740993", result.eventCursor());
        verify(store).byRequests(scope, List.of("r7"));
        verify(store).latest(scope); verify(store).eventCursor(scope);
        verify(store).projections(scope, List.of(row));
        verifyNoMoreInteractions(store); verifyNoInteractions(conversations);
    }
    @Test void olderMessageWindowNeverReadsOrAdvancesLiveEventCursor() {
        var conversation = conversations.findScopedById("owner", "client", "101");
        var message = new cn.jia.chat.entity.ChatMessageEntity().setId(21L).setMetadata("plain legacy value");
        when(store.projections(scope, List.of())).thenReturn(new ChatBountyRequestIndexStore.ProjectionRows(List.of(), List.of(), List.of()));
        var result = service.messageWindow(new ChatConversationService.MessagePage(
                List.of(message), false, null, conversation), false);
        assertTrue(result.entries().isEmpty()); assertNull(result.eventCursor());
        verify(store, never()).latest(any()); verify(store, never()).eventCursor(any());
        verify(store, never()).byRequests(any(), any());
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
    @Test void eventNamedRequestSkipsWatermarkAndWholePageDiscovery() {
        when(store.byRequest(scope, "r7")).thenReturn(List.of(row));
        var result = service.read("0", "owner", "client", "101",
                new ChatBountyRequestIndexService.Query("1", null, null, null, "r7"));
        assertEquals("7", result.through()); assertFalse(result.hasMore()); assertNull(result.nextAfter());
        assertEquals("r7", result.entries().getFirst().request().requestId());
        verify(store).byRequest(scope, "r7"); verify(store).projections(scope, List.of(row));
        verifyNoMoreInteractions(store);
    }
    @Test void targetedQueryCannotAlsoScanOrEscapeConversationScope() {
        assertEquals(ChatBountyRequestIndexService.Reason.INVALID_REQUEST,
                assertThrows(ChatBountyRequestIndexService.Failure.class, () -> service.read("0", "owner", "client", "101",
                        new ChatBountyRequestIndexService.Query("1", "0", null, null, "r7"))).reason());
        verifyNoInteractions(store);
        when(store.byRequest(scope, "foreign")).thenReturn(List.of());
        assertEquals(ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatBountyRequestIndexService.Failure.class, () -> service.read("0", "owner", "client", "101",
                        new ChatBountyRequestIndexService.Query("1", null, null, null, "foreign"))).reason());
        verify(store).byRequest(scope, "foreign"); verifyNoMoreInteractions(store);
    }
    @Test void displayedRequestsUseExactBatchWithoutWholeHistoryDiscovery() {
        var selected=List.of("r7");
        when(store.byRequests(scope,selected)).thenReturn(List.of(row));
        var result=service.read("0","owner","client","101",new ChatBountyRequestIndexService.Query("1",null,null,null,null,null,selected));
        assertEquals("r7",result.entries().getFirst().request().requestId());assertFalse(result.hasMore());
        verify(store).byRequests(scope,selected);verify(store).projections(scope,List.of(row));verifyNoMoreInteractions(store);
    }
    @Test void windowCannotMixDiscoveryModesOrTreatForeignMissingRowsAsComplete() {
        for (var query:List.of(
                new ChatBountyRequestIndexService.Query("1","0",null,null,null,null,List.of("r7")),
                new ChatBountyRequestIndexService.Query("1",null,null,null,"r7",null,List.of("r7")),
                new ChatBountyRequestIndexService.Query("1",null,null,null,null,"true",List.of("r7")),
                new ChatBountyRequestIndexService.Query("1",null,null,null,null,null,List.of("r7","r7")),
                new ChatBountyRequestIndexService.Query("1",null,null,null,null,null,List.of()))) {
            assertEquals(ChatBountyRequestIndexService.Reason.INVALID_REQUEST,assertThrows(ChatBountyRequestIndexService.Failure.class,
                    ()->service.read("0","owner","client","101",query)).reason());
        }
        verifyNoInteractions(store);
        when(store.byRequests(scope,List.of("r7","foreign"))).thenReturn(List.of(row));
        assertEquals(ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN,assertThrows(ChatBountyRequestIndexService.Failure.class,
                ()->service.read("0","owner","client","101",new ChatBountyRequestIndexService.Query("1",null,null,null,null,null,List.of("r7","foreign")))).reason());
        verify(store).byRequests(scope,List.of("r7","foreign"));verifyNoMoreInteractions(store);
    }
    @Test void latestRecoveryExpandsOnlyTheNewestRoundWithoutWatermarkOrPaging() {
        when(store.latest(scope)).thenReturn(List.of(row));
        when(store.eventCursor(scope)).thenReturn(9007199254740993L);
        var result = service.read("0", "owner", "client", "101",
                new ChatBountyRequestIndexService.Query(null, null, null, null, null, "true"));
        assertEquals("7", result.through()); assertEquals("0", result.after());
        assertEquals("r7", result.entries().getFirst().request().requestId());
        assertFalse(result.hasMore()); assertNull(result.nextAfter());
        assertEquals("9007199254740993", result.eventCursor());
        verify(store).latest(scope); verify(store).projections(scope, List.of(row)); verify(store).eventCursor(scope); verifyNoMoreInteractions(store);
    }
    @Test void latestEmptyConversationReturnsNoRequest() {
        when(store.latest(scope)).thenReturn(List.of());
        when(store.projections(scope, List.of())).thenReturn(new ChatBountyRequestIndexStore.ProjectionRows(List.of(), List.of(), List.of()));
        var result = service.read("0", "owner", "client", "101",
                new ChatBountyRequestIndexService.Query("1", null, null, null, null, "true"));
        assertEquals("0", result.through()); assertTrue(result.entries().isEmpty()); assertFalse(result.hasMore());
        assertEquals("0", result.eventCursor());
        verify(store).latest(scope); verify(store).projections(scope, List.of()); verify(store).eventCursor(scope); verifyNoMoreInteractions(store);
    }
    @Test void latestDoesNotAcceptAmbiguousPagingOrTargetSelectors() {
        for (var query : List.of(
                new ChatBountyRequestIndexService.Query(null, null, null, null, null, "false"),
                new ChatBountyRequestIndexService.Query(null, "0", null, null, null, "true"),
                new ChatBountyRequestIndexService.Query(null, null, "7", null, null, "true"),
                new ChatBountyRequestIndexService.Query(null, null, null, "1", null, "true"),
                new ChatBountyRequestIndexService.Query(null, null, null, null, "r7", "true"))) {
            assertEquals(ChatBountyRequestIndexService.Reason.INVALID_REQUEST,
                    assertThrows(ChatBountyRequestIndexService.Failure.class,
                            () -> service.read("0", "owner", "client", "101", query)).reason());
        }
        verifyNoInteractions(store);
    }

}
