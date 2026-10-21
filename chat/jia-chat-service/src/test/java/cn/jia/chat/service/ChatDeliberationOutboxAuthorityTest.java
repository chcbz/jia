package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatDeliberationOutboxAuthorityTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatDispatchAuthorityService authority=mock(ChatDispatchAuthorityService.class);
    final ChatDeliberationOutboxService service=new ChatDeliberationOutboxService(dao);
    final ChatTurnEntity turn=new ChatTurnEntity().setOwnerJiacn("A").setTargetAgentId("agent-B");
    final ChatDispatchOutboxEntity row=new ChatDispatchOutboxEntity().setDispatchId("dispatch")
            .setEventId("event").setStatus("AWAITING_ACK").setPayloadJson("{\"targetAgentId\":\"agent-B\"}");
    ChatDeliberationOutboxAuthorityTest() {
        service.setDispatchAuthority(authority);
        when(authority.incoming("0","client","B","agent-B","dispatch"))
                .thenReturn(new ChatDispatchAuthorityService.Binding(row,turn,"B"));
        when(dao.lockOutboxByDispatch("0","A","client","dispatch")).thenReturn(row);
        when(dao.acknowledgeOutbox(row,10L)).thenReturn(1);
    }
    @Test void ackResolvesBThenLocksAAndRequiresExactMessageId() {
        assertTrue(service.acknowledgeHostedDispatch("0","B","client","agent-B","dispatch","event",10));
        var order=inOrder(authority,dao);
        order.verify(authority).incoming("0","client","B","agent-B","dispatch");
        order.verify(dao).lockOutboxByDispatch("0","A","client","dispatch");
        order.verify(dao).acknowledgeOutbox(row,10L);
        assertFalse(service.acknowledgeHostedDispatch("0","B","client","agent-B","dispatch","other-event",10));
        verify(dao,times(1)).acknowledgeOutbox(row,10L);
    }
    @Test void finalReceiptAndDuplicateAckRetainAAndDoNotRepeatWrites() {
        assertTrue(service.acknowledgeHostedReceipt("0","B","client","agent-B","dispatch",10));
        row.setStatus("SENT");
        assertTrue(service.acknowledgeHostedDispatch("0","B","client","agent-B","dispatch","event",10));
        verify(dao,times(1)).acknowledgeOutbox(row,10L);
    }
    @Test void revokedOrForeignExecutorCannotAcknowledgeEvenPreviouslySentRows() {
        row.setStatus("SENT");
        when(authority.incoming("0","client","B","agent-B","dispatch")).thenThrow(
                new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"revoked"));
        assertThrows(ChatDeliberationException.class,()->service.acknowledgeHostedReceipt("0","B","client","agent-B","dispatch",10));
        verifyNoInteractions(dao);
    }
}
