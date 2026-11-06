package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskCancellationServiceImplTest {
    AgentTaskMetaEntity root;
    AgentTaskMutationTransaction tx;
    AgentTaskStateService states;
    AgentTaskWorkItemDao items;
    AgentTaskExecutionGrantDao grants;
    AgentTaskCancellationDao dao;
    AgentTaskCancellationServiceImpl service;
    TaskBillingService billing;
    TaskExecutionCancellationService executions=mock(TaskExecutionCancellationService.class);
    cn.jia.chat.service.TaskDiscussionCancellation discussions=mock(cn.jia.chat.service.TaskDiscussionCancellation.class);
    AgentWorkItemLeaseService leases=mock(AgentWorkItemLeaseService.class);
    AgentTaskProviderCostConsentService consents=mock(AgentTaskProviderCostConsentService.class);

    @BeforeEach void setup() {
        root=new AgentTaskMetaEntity().setTaskId("417").setOwnerJiacn("owner").setRewardStatus("assigned")
                .setTaskVersion(1L).setCurrentEventVersion(3L);
        root.setTenantId("0"); root.setClientId("client");
        tx=mock(AgentTaskMutationTransaction.class); states=mock(AgentTaskStateService.class);
        items=mock(AgentTaskWorkItemDao.class); grants=mock(AgentTaskExecutionGrantDao.class);
        dao=mock(AgentTaskCancellationDao.class); billing=mock(TaskBillingService.class);
        when(tx.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any()))
                .thenAnswer(i -> {
                    if (!Objects.equals(root.getOwnerJiacn(),i.getArgument(2)))
                        throw new cn.jia.agent.exception.AgentTaskCollaborationException(
                            cn.jia.agent.exception.AgentTaskCollaborationException.Reason.NOT_FOUND,"scope");
                    return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(root);
                });
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(member("a","accepted")));
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w","ready")));
        when(dao.lockGrants("0","client","owner","417")).thenReturn(List.of(grant()));
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of());
        when(grants.revoke(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong()))
                .thenReturn(true);
        when(states.transitionTask(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i -> {
            var state=new AgentTaskStateDTO(); state.setTaskId("417"); state.setStatus("cancelled"); state.setVersion(2L);
            return state;
        });
        service=new AgentTaskCancellationServiceImpl(tx,states,items,grants,dao,billing,
                executions, discussions, leases, consents);
    }
    @Test void assignedCancellationReleasesInRootTransactionBeforeStateWrites() {
        cancel();
        var order=inOrder(billing,states);
        order.verify(billing).release(argThat(t -> t.taskId().equals("417")),eq(true));
        order.verify(states).transitionMember(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void neverAssignedCancellationAllowsMissingBill() {
        root.setRewardStatus("open");
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of());
        when(dao.lockGrants("0","client","owner","417")).thenReturn(List.of());
        cancel();
        verify(billing).release(any(),eq(false));
    }
    @Test void missingAssignedBillPreventsStateAndAuthorizationChanges() {
        when(billing.release(any(),eq(true))).thenThrow(new IllegalStateException("missing bill"));
        assertThrows(IllegalStateException.class,this::cancel);
        verifyNoInteractions(states,grants);
        verify(dao,never()).releaseTaskOccupation(anyString(),anyString(),anyString(),anyString(),anyLong());
    }
    static AgentTaskMemberEntity member(String id,String status) {
        var row=new AgentTaskMemberEntity().setTaskId("417").setOwnerJiacn("owner").setAgentId(id)
                .setMemberStatus(status).setVersion(0L);
        row.setTenantId("0"); row.setClientId("client"); return row;
    }
    static AgentTaskWorkItemEntity item(String id,String status) {
        var row=new AgentTaskWorkItemEntity().setTaskId("417").setOwnerJiacn("owner").setWorkItemId(id)
                .setStatus(status).setVersion(0L).setAttemptCount(0);
        row.setTenantId("0"); row.setClientId("client"); return row;
    }
    static AgentTaskExecutionGrantEntity grant() {
        var row=new AgentTaskExecutionGrantEntity().setTaskId("417").setOwnerJiacn("owner").setGrantId("g")
                .setState("ACTIVE").setGrantVersion(1L).setCreatedAt(1L);
        row.setTenantId("0"); row.setClientId("client"); return row;
    }
    static AgentTaskBountyBootstrapOutboxEntity bootstrap(String status) {
        var row=new AgentTaskBountyBootstrapOutboxEntity().setId(5L).setTaskId("417").setOwnerJiacn("owner")
                .setStatus(status).setVersion(10L).setAttemptCount(100).setCreatedAt(1L).setNextRetryAt(100L);
        row.setTenantId("0"); row.setClientId("client"); return row;
    }
    AgentTaskCancellationService.Receipt cancel() { return service.cancel("0","client","owner","actor","417",1); }
    void noWrites() {
        verifyNoInteractions(states,grants,billing);
        verify(dao,never()).releaseTaskOccupation(anyString(),anyString(),anyString(),anyString(),anyLong());
        verify(dao,never()).cancelUnadmittedBootstrap(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
    }
    @Test void grayCancellationLocksAndMutatesOnlyGrayPartition() {
        root.setTenantId("1");
        when(dao.lockMembers("1","client","owner","417")).thenReturn(List.of());
        when(items.listByTaskForUpdate("1","client","owner","417",501)).thenReturn(List.of());
        when(dao.lockGrants("1","client","owner","417")).thenReturn(List.of());
        when(dao.lockBootstraps("1","client","owner","417")).thenReturn(List.of());
        assertEquals("cancelled", service.cancel("1","client","owner","actor","417",1).status());
        verify(tx).executeWithLockedTaskRootInOwnerScope(eq("1"),eq("client"),eq("owner"),eq("417"),any());
        verify(states).transitionTask(eq("1"),eq("client"),eq("owner"),eq("417"),any());
        verify(dao,never()).lockMembers("0","client","owner","417");
        verify(grants,never()).revoke(anyString(),anyString(),anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyLong());
    }

    @Test void multipleMembersItemsTerminalPreservationAndUnusedGrant() {
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(
                member("a","accepted"),member("b","invited"),member("c","left").setCompletedAt(1L),member("d","rejected").setCompletedAt(2L)));
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(
                item("w","ready"),item("p","pending"),item("x","cancelled")));
        assertEquals(new AgentTaskCancellationService.Receipt("417","cancelled",2),cancel());
        verify(states).transitionMember(eq("0"),eq("client"),eq("owner"),eq("417"),eq("a"),
                argThat(t -> "left".equals(t.getTargetStatus()) && t.getExpectedVersion()==0));
        verify(states).transitionMember(eq("0"),eq("client"),eq("owner"),eq("417"),eq("b"),
                argThat(t -> "rejected".equals(t.getTargetStatus())));
        verify(states,times(2)).transitionMember(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        verify(states,times(2)).transitionWorkItem(anyString(),anyString(),anyString(),anyString(),any());
        verify(grants).revoke(eq("0"),eq("client"),eq("owner"),eq("417"),eq("g"),eq(1L),
                startsWith("cancel_"),matches("[0-9a-f]{64}"),anyLong());
        verify(dao).releaseTaskOccupation(eq("0"),eq("client"),eq("owner"),eq("417"),anyLong());
    }
    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}") @ValueSource(strings={"open","planning","assigned","running","reviewing","blocked","failed"})
    void allCancellableRootStates(String status) { root.setRewardStatus(status); assertEquals("cancelled",cancel().status()); }
    @Test void staleVersionBeforeAnyWrite() {
        root.setTaskVersion(2L);
        assertEquals(AgentTaskStateException.Reason.VERSION_CONFLICT,assertThrows(AgentTaskStateException.class,this::cancel).getReason());
        noWrites();
    }
    @Test void closedReplayWithOriginalAndCurrentVersionHasNoDuplicateEvents() {
        root.setRewardStatus("cancelled").setTaskVersion(2L);
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(
                member("a","left").setCompletedAt(1L),member("b","rejected").setCompletedAt(2L)));
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w","cancelled")));
        when(dao.lockGrants("0","client","owner","417")).thenReturn(List.of(grant().setState("REVOKED")));
        assertEquals(2,cancel().taskVersion());
        assertEquals(2,service.cancel("0","client","owner","actor","417",2).taskVersion());
        noWrites();
    }
    @Test void cancelledRootWithLiveChildFailsClosed() {
        root.setRewardStatus("cancelled"); assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void wrongOwnerCannotReachGraphOrWrite() {
        assertThrows(cn.jia.agent.exception.AgentTaskCollaborationException.class,
                () -> service.cancel("0","client","foreign","actor","417",1));
        verifyNoInteractions(dao,items,states,grants);
    }
    @ParameterizedTest(name="{displayName} [{index}] {argumentsWithNames}") @ValueSource(strings={"completed","archived","Assigned","?"})
    void unsupportedOrUnknownRoot(String status) { root.setRewardStatus(status); assertThrows(AgentTaskStateException.class,this::cancel); noWrites(); }
    @Test void startedRootPreservesItsStartFactOnCancel() { root.setStartedAt(1L); assertEquals("cancelled",cancel().status()); assertEquals(1L,root.getStartedAt()); }
    @Test void ordinaryRewardAndFundingFactsBothRejected() {
        root.setReward(1); assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        root.setReward(null); when(dao.hasMoneyFacts("0","client","owner","417")).thenReturn(true);
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"READY","?"})
    void unknownChildStateRejected(String status) {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w",status)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"claimed","running"})
    void leasedWorkUsesLeaseOwnerServiceNotGenericStateTransition(String status) {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w",status)));
        cancel(); verify(leases).cancelForTask("0","client","owner","417","w",0L);
        verify(states,never()).transitionWorkItem(anyString(),anyString(),anyString(),anyString(),any());
    }
    @ParameterizedTest @ValueSource(strings={"pending","ready","submitted","blocked"})
    void otherNonterminalWorkUsesExistingStateService(String status) {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w",status)));
        cancel(); verify(states).transitionWorkItem(eq("0"),eq("client"),eq("owner"),eq("w"),argThat(c->"cancelled".equals(c.getTargetStatus())));
        verifyNoInteractions(leases);
    }
    @ParameterizedTest @ValueSource(strings={"completed","failed","cancelled"})
    void terminalWorkAndArtifactsArePreserved(String status) {
        var row=item("w",status).setAttemptCount(3).setSubmittedAt(1L).setCompletedAt(2L).setResultArtifactId("saved-artifact");
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(row));
        cancel(); assertEquals(status,row.getStatus()); assertEquals("saved-artifact",row.getResultArtifactId());
        verifyNoInteractions(leases); verify(states,never()).transitionWorkItem(anyString(),anyString(),anyString(),anyString(),any());
    }
    @ParameterizedTest @ValueSource(strings={"accepted","working","blocked"})
    void activeMemberExitsWithoutErasingStartHistory(String status) {
        var row=member("a",status).setStartedAt(1L);
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(row));
        cancel(); verify(states).transitionMember(eq("0"),eq("client"),eq("owner"),eq("417"),eq("a"),argThat(c->"left".equals(c.getTargetStatus())));
        assertEquals(1L,row.getStartedAt());
    }
    @ParameterizedTest @ValueSource(strings={"left","rejected","done","failed"})
    void terminalMemberHistoryIsNotRewritten(String status) {
        var row=member("a",status).setStartedAt(1L).setCompletedAt(2L);
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(row));
        cancel(); verify(states,never()).transitionMember(anyString(),anyString(),anyString(),anyString(),anyString(),any());
        assertEquals(status,row.getMemberStatus()); assertEquals(2L,row.getCompletedAt());
    }
    @Test void completeGraphOverflowAndForeignScopeRejected() {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(Collections.nCopies(501,item("w","ready")));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w","ready").setOwnerJiacn("foreign")));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void closesExecutionAndConsentBeforeDiscussionAndWalletWithinRootOperation() {
        cancel(); var order=inOrder(executions,consents,discussions,billing,states);
        order.verify(executions).cancelTaskExecutions("0","client","owner","417");
        order.verify(consents).cancelForTask(new AgentTaskProviderCostConsentService.Scope("0","client","owner"),"417");
        order.verify(discussions).cancelTaskDiscussions("0","client","owner","417");
        order.verify(billing).release(any(),eq(true));
        order.verify(states).transitionMember(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void shutdownFailureDoesNotProceedToMoneyOrBusinessStateWrites() {
        doThrow(new IllegalStateException("discussion shutdown failed")).when(discussions).cancelTaskDiscussions(anyString(),anyString(),anyString(),anyString());
        assertThrows(IllegalStateException.class,this::cancel);
        verifyNoInteractions(billing,states,grants,leases);
    }
    @Test void retryBootstrapTerminalizesWithoutInventingAttemptAndAdmittedPreserved() {
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of(bootstrap("RETRY")));
        when(dao.cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong())).thenReturn(1);
        assertEquals(2,cancel().taskVersion());
        verify(dao).cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong());
    }
    @Test void pendingBootstrapClosesWithoutAttemptOrLease() {
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of(
                bootstrap("PENDING").setAttemptCount(0).setNextRetryAt(null)));
        when(dao.cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong())).thenReturn(1);
        assertEquals(2,cancel().taskVersion());
        verify(dao).cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong());
    }
    @Test void ownedCancellationClosesClaimedButUnadmittedBootstrap() {
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of(
                bootstrap("CLAIMED").setLeaseOwner("worker").setLeaseUntil(Long.MAX_VALUE).setNextRetryAt(null)));
        when(dao.cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong())).thenReturn(1);
        assertEquals(2,cancel().taskVersion());
        verify(dao).cancelUnadmittedBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong());
    }
    @Test void claimedBootstrapWithoutLeaseOwnerRejected() {
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of(bootstrap("CLAIMED").setLeaseUntil(1L)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void invalidIdentityAndVersionDoNotAcquireRoot() {
        assertThrows(AgentTaskStateException.class,() -> service.cancel("0","client","0","actor","417",1));
        assertThrows(AgentTaskStateException.class,() -> service.cancel("0","client","owner"," actor","417",1));
        assertThrows(AgentTaskStateException.class,() -> service.cancel("0","client","owner","actor","417",Long.MAX_VALUE));
        verifyNoInteractions(tx); noWrites();
    }
}
