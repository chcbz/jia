package cn.jia.chat.service;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.ChatBountyRequestIndexStore;
import cn.jia.chat.deliberation.ChatDeliveryContextStore;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.LongStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatDeliveryContextPageTest {
    private ChatDeliveryContextStore deliveries;
    private ChatBountyRequestIndexStore catalogue;
    private ChatConversationDao conversations;
    private ChatBountyRequestIndexService service;
    private final ChatDeliveryContextStore.Scope scope = new ChatDeliveryContextStore.Scope("0","owner","client","101",1,"task",3,"wuyong");
    private final ChatBountyRequestIndexService.DeliveryQuery query = new ChatBountyRequestIndexService.DeliveryQuery("1","3",null,null);
    @BeforeEach void setup() {
        deliveries=mock(ChatDeliveryContextStore.class);catalogue=mock(ChatBountyRequestIndexStore.class);
        conversations=mock(ChatConversationDao.class);var scopes=mock(JuyitingConversationScopeService.class);
        var conversation=new ChatConversationEntity().setId(101L).setJiacn("owner")
                .setConversationType("juyiting").setConversationScopeType("bounty").setConversationScopeKey("task:task")
                .setTaskId("task").setLifecycleGeneration(1L).setTargetAgentIds("[\"wuyong\"]");
        conversation.setTenantId("0");conversation.setClientId("client");
        when(conversations.findScopedById("owner","client","101")).thenReturn(conversation);
        when(scopes.parsePersistedTargetAgentIds("[\"wuyong\"]")).thenReturn(List.of("wuyong"));
        when(deliveries.assignment("0","owner","client","task",101)).thenReturn(3L);
        service=new ChatBountyRequestIndexService(catalogue,conversations,new ChatBountyRequestIndexPageTest.TaskRoots(),deliveries,scopes);
    }
    @Test void pageExpandsNoHistoricalRequestAndDropsOnlyLookahead() {
        when(deliveries.heads(scope,null,51)).thenReturn(LongStream.range(1,52).mapToObj(n ->
                new ChatDeliveryContextStore.Choice("o"+n,"r"+n,100-n,"sha256:"+"a".repeat(64),"ANSWER","label")).toList());
        var result=service.deliveryContext("0","owner","client","101",query);
        assertEquals(50,result.items().size());assertTrue(result.hasMore());
        assertEquals("50",result.nextBeforeMessageId());assertEquals("o50",result.nextBeforeOutcomeId());
        assertEquals("a".repeat(64),result.items().getFirst().finalSha256());
        verify(deliveries).assignment("0","owner","client","task",101);
        verify(deliveries).heads(scope,null,51);verify(deliveries).pendingExecution(scope);
        verifyNoMoreInteractions(deliveries);verifyNoInteractions(catalogue);
    }
    @Test void pendingExecutionIsReportedWithoutPretendingToBeADeliverableOrRejectingRead() {
        when(deliveries.heads(scope,null,51)).thenReturn(List.of());when(deliveries.pendingExecution(scope)).thenReturn(true);
        var result=service.deliveryContext("0","owner","client","101",query);
        assertTrue(result.pendingExecution());assertTrue(result.items().isEmpty());assertFalse(result.hasMore());
        assertNull(result.nextBeforeMessageId());assertNull(result.nextBeforeOutcomeId());
    }
    @Test void ownerAndReassignmentFailBeforeChoiceQuery() {
        when(conversations.findScopedById("owner","client","101")).thenReturn(null);
        assertEquals(ChatBountyRequestIndexService.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(ChatBountyRequestIndexService.Failure.class,()->service.deliveryContext("0","owner","client","101",query)).reason());
        verifyNoInteractions(deliveries,catalogue);
    }
    @Test void staleAssignmentAndPartialCursorAreNotTreatedAsEmptyHistory() {
        var stale=new ChatBountyRequestIndexService.DeliveryQuery("1","2",null,null);
        assertEquals(ChatBountyRequestIndexService.Reason.CONFLICT,
                assertThrows(ChatBountyRequestIndexService.Failure.class,()->service.deliveryContext("0","owner","client","101",stale)).reason());
        verify(deliveries).assignment("0","owner","client","task",101);verifyNoMoreInteractions(deliveries);
        var partial=new ChatBountyRequestIndexService.DeliveryQuery("1","3","8",null);
        assertEquals(ChatBountyRequestIndexService.Reason.INVALID_REQUEST,
                assertThrows(ChatBountyRequestIndexService.Failure.class,()->service.deliveryContext("0","owner","client","101",partial)).reason());
    }
}
