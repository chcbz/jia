package cn.jia.chat.service;

import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual revision consumer/receipt codecs; persistence and external IO are boundary mocks. */
class ChatDeliveryRevisionServiceTest {
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final ChatActionFinalService finals=mock(ChatActionFinalService.class);
    final ChatDeliberationService deliberation=mock(ChatDeliberationService.class);
    final ChatTaskDeliveryReviewStore operations=mock(ChatTaskDeliveryReviewStore.class);
    final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final AgentTaskFormalDeliveryReadService deliveries=mock(AgentTaskFormalDeliveryReadService.class);
    final AgentTaskFormalDeliveryDecisionService decisions=mock(AgentTaskFormalDeliveryDecisionService.class);
    final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
    final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
    final ChatTypedDeliberationContextService contexts=mock(ChatTypedDeliberationContextService.class);
    final ChatSkillPackagePreparationService packages=mock(ChatSkillPackagePreparationService.class);
    final ChatDeliberationOutboxService outbox=mock(ChatDeliberationOutboxService.class);
    final ChatDispatchAuthorityService authority=mock(ChatDispatchAuthorityService.class);
    final ChatFormalDeliveryRetentionService retention=mock(ChatFormalDeliveryRetentionService.class);
    final ChatDeliveryRevisionService.BoundRevision bound=ChatFormalDeliveryRetentionServiceTest.bound();
    final ChatTypedDeliberationStore.Outcome row=bound.parent().outcome();
    final ChatDeliveryReviewContract.Scope owner=new ChatDeliveryReviewContract.Scope("0","client","owner");
    final ChatDispatchOutboxEntity event=new ChatDispatchOutboxEntity().setEventId(ChatDeliveryRevisionService.eventId(row));
    final ChatDeliberationOutboxService.Claim claim=new ChatDeliberationOutboxService.Claim(event,false);
    final ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
            .setConversationId("7").setConversationGeneration(1L).setTurnId("turn").setRequestId(row.requestId())
            .setDispatchId((String)bound.parent().validated().binding().get("dispatchId"))
            .setTargetAgentId("wuyong").setFinalDigest(row.finalDigest());
    final AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskVersion(7L).setRewardStatus("reviewing").setAssignedAgentId("wuyong");
    final ChatTaskDeliveryReviewStore.Operation op=new ChatTaskDeliveryReviewStore.Operation("operation","task_fixture",
            event.getEventId(),ChatDeliveryRevisionService.digest(bound),"delivery","PROCESSING",1,null);
    final ChatDeliveryRevisionService service=new ChatDeliveryRevisionService(dao,provider(finals),provider(deliberation),operations,
            typed,tasks,deliveries,decisions,bindings,conversations,scopes,contexts,packages,outbox,authority);
    boolean inRoot;
    @SuppressWarnings("unchecked") static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getObject()).thenReturn(value);return p;
    }
    @BeforeEach void setup() {
        service.setRetention(retention);
        when(finals.loadRevision(event)).thenReturn(bound);
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(authority.forTurn(turn)).thenAnswer(call->new ChatDispatchAuthorityService.Binding(event,turn,"executor-owner"));
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task_fixture"),any()))
                .thenAnswer(call->{assertFalse(inRoot);inRoot=true;try {
                    return call.<AgentTaskMutationTransaction.LockedTaskMutation<Object>>getArgument(4).apply(root);
                } finally {inRoot=false;}});
        when(operations.admitRevision(bound)).thenReturn(op);
        when(operations.assignmentRevision(owner,"task_fixture")).thenReturn(1L);
        when(bindings.lock(any(),eq("task_fixture"))).thenReturn(new ChatBountyBindingStore.Binding(1,7L));
        when(conversations.lockScopedById("owner","client","7")).thenReturn(new ChatConversationEntity()
                .setTenantId("0").setLifecycleGeneration(1L).setTaskId("task_fixture").setTargetAgentIds("[\"wuyong\"]"));
        when(scopes.parsePersistedTargetAgentIds(anyString())).thenReturn(List.of("wuyong"));
        when(retention.prepareRevisionSources(bound)).thenReturn(List.of());
        var batch=new AgentTaskFormalDeliveryViewDTO();batch.setTaskId("task_fixture");batch.setDeliveryId("delivery");
        batch.setState("submitted");batch.setDeliveryVersion(0L);
        batch.setItems(bound.basis().items().stream().map(item->{var v=new AgentTaskFormalDeliveryItemDTO();
            v.setArtifactId(item.artifactId());v.setArtifactVersion(Integer.parseInt(item.artifactVersion()));
            v.setContentHash(item.contentHash());v.setPurpose(item.purpose());return v;}).toList());
        when(deliveries.listForTaskOwner("0","client","owner","task_fixture")).thenReturn(List.of(batch));
        var decided=new AgentTaskFormalDeliveryViewDTO();decided.setState("changes_requested");decided.setTaskVersion(8L);decided.setDeliveryVersion(1L);
        when(decisions.decide(eq("0"),eq("client"),eq("task_fixture"),eq("owner"),any())).thenReturn(decided);
        var context=new ChatTypedDeliberationContextService.Context(Map.of(),"[]",List.of(),Map.of());
        when(contexts.resolve(any(),eq("task_fixture"),eq("wuyong"),any())).thenReturn(context);
        when(contexts.withFormalRevisionSources(any(),eq("task_fixture"),eq("wuyong"),eq(context),any())).thenReturn(context);
        var dispatch=new ChatDeliberationService.Dispatch("child","child-turn","child-dispatch","child-event","wuyong","CHAT",
                "READY","snapshot","hash",Map.of(),Map.of());
        when(deliberation.admitDeliveryRevisionContinuation(eq(bound),eq("operation"),eq(context),any()))
                .thenReturn(new ChatDeliberationService.Admission("child",1,"100","7",1,InteractionRoute.CHAT,List.of(dispatch),false));
        when(typed.insertAdmission(any())).thenReturn(1);
        when(outbox.sent(eq(claim),anyLong())).thenReturn(true);
    }
    void rejected(String code) {
        var captor=org.mockito.ArgumentCaptor.forClass(ChatDeliveryReviewContract.Receipt.class);
        verify(operations).settle(eq(owner),eq(op),captor.capture());
        var receipt=ChatDeliveryReviewContract.receipt(ChatDeliveryReviewContract.json(captor.getValue()));
        assertEquals("REJECTED",receipt.state());assertEquals(code,receipt.errorCode());assertNull(receipt.requestId());
        verify(outbox).sent(eq(claim),anyLong());verifyNoInteractions(decisions,typed);
        verify(deliberation,never()).admitDeliveryRevisionContinuation(any(),anyString(),any(),any());
    }
    @Test void crossOwnerUsesSavedExecutorButTaskAndDecisionRemainWithOriginalOwner() {
        doAnswer(call->{assertFalse(inRoot);assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;})
                .when(packages).prepare(any());
        service.consume(claim);
        verify(packages).prepare(new SkillCapabilityLookup.Scope("0","client","executor-owner","wuyong"));
        verify(authority,times(2)).forTurn(turn);verify(authority,never()).admissionTarget(any(),any(),any(),any(),any());
        verify(decisions).decide(eq("0"),eq("client"),eq("task_fixture"),eq("owner"),any());
        var receipt=org.mockito.ArgumentCaptor.forClass(ChatDeliveryReviewContract.Receipt.class);
        verify(operations).settle(eq(owner),eq(op),receipt.capture());assertEquals("COMMITTED",receipt.getValue().state());
        assertEquals("RUNNING",receipt.getValue().taskState());assertNotNull(receipt.getValue().requestId());
        verify(outbox).sent(eq(claim),anyLong());
    }
    @Test void sameOwnerUsesTheSameAuthorityPath() {
        when(authority.forTurn(turn)).thenReturn(new ChatDispatchAuthorityService.Binding(event,turn,"owner"));
        service.consume(claim);verify(packages).prepare(new SkillCapabilityLookup.Scope("0","client","owner","wuyong"));
        verify(typed).insertAdmission(any());
    }
    @Test void revokedParentIsRejectedBeforePackageIO() {
        when(authority.forTurn(turn)).thenThrow(denied());service.consume(claim);
        rejected("DELIVERY_REVIEW_FORBIDDEN");verifyNoInteractions(packages,retention);
    }
    @Test void identityRevokedDuringPackageReadIsPermanentNotAnInfiniteRetry() {
        doThrow(new InstalledSkillDescriptorLookup.SourceUnavailable(InstalledSkillDescriptorLookup.SourceUnavailable.Reason.FORBIDDEN,null))
                .when(packages).prepare(any());service.consume(claim);rejected("DELIVERY_REVIEW_FORBIDDEN");
    }
    @Test void unavailablePackageDependencyPreservesRetryWithoutInventingAReceipt() {
        var failure=new InstalledSkillDescriptorLookup.SourceUnavailable(new java.io.IOException("transport unavailable"));
        doThrow(failure).when(packages).prepare(any());assertSame(failure,assertThrows(RuntimeException.class,()->service.consume(claim)));
        verify(operations,never()).admitRevision(any());verify(operations,never()).settle(any(),any(),any());verifyNoInteractions(outbox,decisions);
    }
    @Test void authorityChangedAfterPreparationCannotCreateAChild() {
        when(authority.forTurn(turn)).thenReturn(new ChatDispatchAuthorityService.Binding(event,turn,"executor-owner")).thenThrow(denied());
        service.consume(claim);rejected("DELIVERY_REVIEW_FORBIDDEN");
    }
    @Test void taskVersionChangedDuringPreparationCannotApplyOldDecision() {
        doAnswer(call->{root.setTaskVersion(8L);return null;}).when(packages).prepare(any());
        service.consume(claim);rejected("DELIVERY_REVIEW_CONFLICT");
    }
    @Test void changedAssignmentCannotRouteOldEventToReplacementAgent() {
        root.setAssignedAgentId("replacement");service.consume(claim);rejected("DELIVERY_REVIEW_CONFLICT");
        verify(authority,never()).admissionTarget(any(),any(),any(),any(),any());
    }
    @Test void forgedParentDispatchCannotReadAnExecutorPackage() {
        turn.setDispatchId("foreign-dispatch");service.consume(claim);rejected("DELIVERY_REVIEW_FORBIDDEN");verifyNoInteractions(authority,packages);
    }
    @Test void committedDuplicateAcknowledgesOriginalReceiptWithoutRecheckingChangedAuthority() {
        var receipt=new ChatDeliveryReviewContract.Receipt(1,"operation","task_fixture","delivery","REQUEST_CHANGES","COMMITTED","2",
                "RUNNING","8","CHANGES_REQUESTED","1","child",row.requestId(),"7",null,false);
        var prior=new ChatTaskDeliveryReviewStore.Operation(op.operationId(),op.taskId(),op.key(),op.digest(),op.deliveryId(),"COMMITTED",2,
                ChatDeliveryReviewContract.json(receipt));
        when(operations.byKey(owner,"task_fixture",event.getEventId(),true)).thenReturn(prior);
        service.consume(claim);verifyNoInteractions(authority,packages,retention,decisions,typed);verify(outbox).sent(eq(claim),anyLong());
    }
    @Test void transientPreparationCanRecoverOnSameEventWithoutDuplicateExecution() {
        doThrow(new InstalledSkillDescriptorLookup.SourceUnavailable()).doNothing().when(packages).prepare(any());
        assertThrows(InstalledSkillDescriptorLookup.SourceUnavailable.class,()->service.consume(claim));
        service.consume(claim);verify(typed,times(1)).insertAdmission(any());verify(decisions,times(1)).decide(any(),any(),any(),any(),any());
    }
    @Test void lostClaimCannotBeAcknowledgedAsSuccess() {
        doThrow(new IllegalStateException("claim lost")).when(outbox).requireActiveClaim(eq(claim),anyLong());
        assertThrows(IllegalStateException.class,()->service.consume(claim));verify(outbox,never()).sent(any(),anyLong());
    }
    private static ChatDeliberationException denied() {
        return new ChatDeliberationException(ChatDeliberationException.Reason.NOT_FOUND_OR_FORBIDDEN,"authority unavailable");
    }
}
