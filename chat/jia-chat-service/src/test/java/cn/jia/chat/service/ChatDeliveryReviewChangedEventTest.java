package cn.jia.chat.service;

import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatDeliveryReviewChangedEventTest {
    @Test void eventNamesTheParentAndChildWithoutOverwritingTurnStateOrCreatingAReply() throws Exception {
        var dao = mock(ChatDeliberationDao.class);
        var messages = mock(ChatMessageDao.class);
        var service = new ChatDeliberationService(dao, mock(ChatConversationDao.class), messages, mock(AgentService.class));
        var row = ChatFormalDeliveryRetentionServiceTest.bound().parent().outcome();
        var scope = row.scope();
        var turn = new ChatTurnEntity().setTurnId(row.turnId()).setRequestId(row.requestId()).setRequestRevision(row.requestRevision())
                .setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn()).setClientId(scope.clientId())
                .setConversationId(scope.conversationId()).setConversationGeneration(scope.conversationGeneration())
                .setFinalDigest(row.finalDigest()).setState("FINAL_PERSISTED").setStateVersion(3L);
        when(dao.lockTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId())).thenReturn(turn);
        when(dao.insertEvent(any())).thenAnswer(call->{call.<ChatConversationEventEntity>getArgument(0).setEventSequence(42L);return 1;});
        when(dao.assignEventVersion(42L)).thenReturn(1);
        for (String state : java.util.List.of("COMMITTED", "REJECTED")) {
            String child = "COMMITTED".equals(state) ? "child" : null;
            var receipt = new ChatDeliveryReviewContract.Receipt(1, "operation", row.taskId(), "delivery", "REQUEST_CHANGES",
                    state, "2", "RUNNING", "8", "CHANGES_REQUESTED", "1", child, row.requestId(), scope.conversationId(), null, false);
            var event = service.persistDeliveryReviewChanged(row, receipt, 1000L);
            var data = new ObjectMapper().readTree(event.getPayloadJson());
            assertEquals("delivery_review_changed", event.getEventType());
            assertEquals(row.requestId(), data.get("requestId").asText());
            assertEquals(row.outcomeId(), data.get("parentOutcomeId").asText());
            assertEquals(state, data.get("operationState").asText());
            if (child == null) assertTrue(data.get("childRequestId").isNull());
            else assertEquals(child, data.get("childRequestId").asText());
            assertFalse(data.has("state")); assertFalse(data.has("stateVersion"));
            assertEquals(42L, event.getEventVersion());
        }
        assertEquals("FINAL_PERSISTED", turn.getState()); assertEquals(3L, turn.getStateVersion());
        verifyNoInteractions(messages);
        verify(dao,times(2)).lockTurn(scope.tenantId(),scope.ownerJiacn(),scope.clientId(),row.turnId());
        verify(dao,times(2)).insertEvent(any()); verify(dao,times(2)).assignEventVersion(42L); verifyNoMoreInteractions(dao);
    }
}
