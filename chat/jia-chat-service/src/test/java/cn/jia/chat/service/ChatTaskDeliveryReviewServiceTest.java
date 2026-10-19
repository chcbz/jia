package cn.jia.chat.service;

import cn.jia.chat.service.ChatDeliveryReviewContract.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTaskDeliveryReviewServiceTest {
    final Scope scope = new Scope("0", "client", "owner");
    final Accept command = new Accept("delivery", 7, 0, 1, "a".repeat(64));
    final ChatTaskDeliveryReviewStore store = mock(ChatTaskDeliveryReviewStore.class);
    final ChatTaskDeliveryReviewTransaction tx = mock(ChatTaskDeliveryReviewTransaction.class);
    final ChatTaskDeliveryReviewService service = new ChatTaskDeliveryReviewService(store, tx);
    ChatTaskDeliveryReviewStore.Operation operation(String state, String json) {
        return new ChatTaskDeliveryReviewStore.Operation("review_1", "task", "review-key", ChatDeliveryReviewContract.requestDigest(command), "delivery", state, "PROCESSING".equals(state)?1:2, json);
    }
    Receipt committed() { return new Receipt(1,"review_1","task","delivery","ACCEPT","COMMITTED","2","COMPLETED","8","ACCEPTED","1",null,null,null,null,false); }
    @Test void sameKeyReplayReturnsImmutableReceiptWithoutReevaluatingVersionsOrDeciding() {
        var receipt = committed(); when(tx.admit(scope,"task","review-key",command)).thenReturn(operation("COMMITTED", ChatDeliveryReviewContract.json(receipt)));
        assertEquals(receipt, service.accept(scope,"task","review-key",command)); verify(tx,never()).commit(any(),any(),any(),any());
    }
    @Test void resumesOriginalProcessingAndReadsNeverWrite() {
        when(tx.admit(scope,"task","review-key",command)).thenReturn(operation("PROCESSING",null));
        when(tx.commit(scope,"task","review_1",command)).thenReturn(committed());
        assertEquals(committed(), service.accept(scope,"task","review-key",command));
        reset(tx); when(store.byKey(scope,"task","review-key",false)).thenReturn(operation("COMMITTED",ChatDeliveryReviewContract.json(committed())));
        when(store.require(scope,"task","review_1",false)).thenReturn(operation("COMMITTED",ChatDeliveryReviewContract.json(committed())));
        assertEquals(committed(),service.byKey(scope,"task","review-key")); assertEquals(committed(),service.byOperation(scope,"task","review_1")); verifyNoInteractions(tx);
    }
    @Test void conflictIsRejectedAfterRollbackButTransientFailureRetainsRecoverableIntent() {
        when(tx.admit(scope,"task","review-key",command)).thenReturn(operation("PROCESSING",null));
        when(tx.commit(scope,"task","review_1",command)).thenThrow(new Conflict());
        service.accept(scope,"task","review-key",command); verify(tx).reject(scope,"task","review_1",command,"DELIVERY_REVIEW_CONFLICT");
        reset(tx); when(tx.admit(scope,"task","review-key",command)).thenReturn(operation("PROCESSING",null));
        when(tx.commit(scope,"task","review_1",command)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection lost"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> service.accept(scope,"task","review-key",command));
        verify(tx,never()).reject(any(),any(),any(),any(),any());
    }
}
