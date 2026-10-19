package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatTypedReviewContextServiceTest {
    final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","123",1);
    final ChatTaskDeliveryReviewReadService reads=mock(ChatTaskDeliveryReviewReadService.class);
    final ChatTypedReviewContextStore store=mock(ChatTypedReviewContextStore.class);
    final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
    final ChatTypedReviewContextService service=new ChatTypedReviewContextService(reads,store,typed);
    final ChatDeliveryRevisionContract.Basis original=ChatDeliveryRevisionContractTest.basis();
    ChatTaskDeliveryReviewReadService.Context context(String version,String state,boolean pending) {
        var b=original;
        var batch=new ChatTaskDeliveryReviewReadService.Batch(b.deliveryId(),b.deliveryVersion(),"SUBMITTED",b.batchSha256(),"成果",1,
                b.items().stream().map(i->new ChatTaskDeliveryReviewReadService.Item(i.artifactId(),i.artifactVersion(),i.contentHash(),i.purpose(),i.displayRole(),null,null)).toList(),List.of("VIEW"));
        return new ChatTaskDeliveryReviewReadService.Context(1,b.taskId(),version,state,"1",batch,List.of(),pending?
                new ChatTaskDeliveryReviewReadService.Pending("review_op","REVIEW","PROCESSING","1",null,false):null);
    }
    void read(ChatTaskDeliveryReviewReadService.Context c) {
        when(reads.read(new ChatDeliveryReviewContract.Scope("0","client","owner"),original.taskId())).thenReturn(c);
    }
    ChatDeliveryRevisionContract.Basis freeze(String parent) {
        return service.freeze(scope,original.taskId(),"7","new_request",1,101,parent,1234);
    }
    ChatTypedDeliberationStore.Outcome parent() {
        return new ChatTypedDeliberationStore.Outcome("clarification",scope,"parent_request",1,"turn",original.taskId(),1,100,
                "sha256:"+"1".repeat(64),"CLARIFY","哪一项？","{}","{}","{}","[]",100);
    }
    @Test void persistsExactBatchAfterRealMessageIdExistsAndBeforeReturningSnapshot() {
        read(context("7","REVIEWING",false));var b=freeze(null);
        assertEquals("101",b.sourceMessageId());assertEquals("new_request",b.sourceRequestId());assertEquals(original.items(),b.items());
        verify(store).insert(scope,"new_request",1,b,1234);verifyNoInteractions(typed);
    }
    @Test void pendingReviewDoesNotTurnOrdinaryQuestionIntoMissingBatchOrNewTaskState() {
        read(context("7","REVIEWING",true));assertNotNull(freeze(null));
        verify(store).insert(eq(scope),eq("new_request"),eq(1L),notNull(),eq(1234L));
    }
    @Test void explicitNoBatchIsStoredNotSkipped() {
        read(new ChatTaskDeliveryReviewReadService.Context(1,original.taskId(),"7","RUNNING","1",null,List.of(),null));
        assertNull(freeze(null));verify(store).insert(scope,"new_request",1,null,1234);
    }
    @Test void versionOrRootContradictionRollsBackBeforeAnySnapshotWrite() {
        read(context("8","REVIEWING",false));assertThrows(ChatDeliberationException.class,()->freeze(null));
        read(context("7","RUNNING",false));assertThrows(ChatDeliberationException.class,()->freeze(null));verifyNoInteractions(store);
    }
    @Test void clarificationInheritsOriginalBatchButBindsCurrentUserProvenance() {
        read(context("7","REVIEWING",false));when(typed.findOutcome(scope,"clarification",false)).thenReturn(parent());
        when(store.require(scope,"parent_request",1)).thenReturn(original);
        var b=freeze("clarification");assertEquals(original.batchSha256(),b.batchSha256());assertEquals("101",b.sourceMessageId());
        assertEquals("new_request",b.sourceRequestId());
    }
    @Test void clarificationCannotRebaseNoBatchToNewBatchOrOldBatchToNone() {
        read(context("7","REVIEWING",false));when(typed.findOutcome(scope,"clarification",false)).thenReturn(parent());
        when(store.require(scope,"parent_request",1)).thenReturn(null);
        assertThrows(ChatDeliberationException.class,()->freeze("clarification"));
        read(new ChatTaskDeliveryReviewReadService.Context(1,original.taskId(),"7","RUNNING","1",null,List.of(),null));
        when(store.require(scope,"parent_request",1)).thenReturn(original);
        assertThrows(ChatDeliberationException.class,()->freeze("clarification"));
        verify(store,never()).insert(any(),any(),anyLong(),any(),anyLong());
    }
    @Test void rejectsWrongClarificationTaskOrMissingHistoricalSnapshot() {
        read(context("7","REVIEWING",false));assertThrows(ChatDeliberationException.class,()->freeze("missing"));
        when(typed.findOutcome(scope,"clarification",false)).thenReturn(parent());when(store.require(scope,"parent_request",1)).thenThrow(new IllegalStateException("db unavailable"));
        assertThrows(IllegalStateException.class,()->freeze("clarification"));verify(store,never()).insert(any(),any(),anyLong(),any(),anyLong());
    }
    @Test void frozenReviewBlocksSkillEvenIfTaskHasSinceMovedBackToRunning() {
        var row=parent();when(store.require(scope,row.requestId(),1)).thenReturn(original);
        assertThrows(ChatActionDispatchService.Rejected.class,()->service.requireActionAllowed(new ChatActionFinalService.BoundAction(row,null,null)));
        verifyNoInteractions(reads);
    }
    @Test void missingSnapshotRemainsTransientNotNoReviewAuthorization() {
        var row=parent();when(store.require(scope,row.requestId(),1)).thenThrow(new IllegalStateException("missing"));
        assertThrows(IllegalStateException.class,()->service.requireActionAllowed(new ChatActionFinalService.BoundAction(row,null,null)));
    }
    @Test void usesMandatoryTransactionAndNeverRequiresNewAdmissionWrite() throws Exception {
        var method=ChatTypedReviewContextService.class.getMethod("freeze",ChatTypedDeliberationStore.Scope.class,String.class,String.class,
                String.class,long.class,long.class,String.class,long.class);
        assertEquals(Propagation.MANDATORY,method.getAnnotation(Transactional.class).propagation());
    }
}
