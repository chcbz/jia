package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
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

    @BeforeEach void setup() {
        root=new AgentTaskMetaEntity().setTaskId("417").setOwnerJiacn("owner").setRewardStatus("assigned")
                .setTaskVersion(1L).setCurrentEventVersion(3L);
        root.setTenantId("0"); root.setClientId("client");
        tx=mock(AgentTaskMutationTransaction.class); states=mock(AgentTaskStateService.class);
        items=mock(AgentTaskWorkItemDao.class); grants=mock(AgentTaskExecutionGrantDao.class);
        dao=mock(AgentTaskCancellationDao.class);
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
        service=new AgentTaskCancellationServiceImpl(tx,states,items,grants,dao);
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
        verifyNoInteractions(states,grants);
        verify(dao,never()).releaseTaskOccupation(anyString(),anyString(),anyString(),anyString(),anyLong());
        verify(dao,never()).cancelRetryBootstrap(anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong(),anyLong());
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
    @ParameterizedTest @ValueSource(strings={"open","planning","assigned"})
    void allInitialRootStates(String status) { root.setRewardStatus(status); assertEquals("cancelled",cancel().status()); }
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
    @ParameterizedTest @ValueSource(strings={"running","reviewing","blocked","completed","failed","archived","Assigned","?"})
    void unsupportedOrUnknownRoot(String status) { root.setRewardStatus(status); assertThrows(AgentTaskStateException.class,this::cancel); noWrites(); }
    @Test void startedAssignedRootRejected() { root.setStartedAt(1L); assertThrows(AgentTaskStateException.class,this::cancel); noWrites(); }
    @Test void ordinaryRewardAndFundingFactsBothRejected() {
        root.setReward(1); assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        root.setReward(null); when(dao.hasMoneyFacts("0","client","owner","417")).thenReturn(true);
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"claimed","running","submitted","blocked","completed","failed","READY","?"})
    void unsafeChildStateRejected(String status) {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w",status)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void attemptsOrLeaseOrSubmittedEvidenceEvenOnReadyRejected() {
        for (var row:List.of(item("w","ready").setAttemptCount(1),item("w","ready").setLeaseToken("expired"),
                item("w","ready").setLeaseUntil(0L),item("w","ready").setSubmittedAt(1L),
                item("w","ready").setResultArtifactId("artifact"))) {
            when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(row));
            assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        }
    }
    @Test void memberStartedOrWorkingIsNotCancelled() {
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(member("a","accepted").setStartedAt(1L)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(member("a","working")));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"working","blocked","done","failed"})
    void workedMemberStatesRejectEvenWithoutStartedTimestamp(String status) {
        when(dao.lockMembers("0","client","owner","417")).thenReturn(List.of(member("a",status)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"invited","accepted"})
    void nonterminalCompletedClockIsStillUncertainAndRejected(String status) {
        when(dao.lockMembers("0","client","owner","417"))
                .thenReturn(List.of(member("a",status).setCompletedAt(1L)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @ParameterizedTest @ValueSource(strings={"left","rejected"})
    void memberExitClockDoesNotHideActualStartEvidence(String status) {
        when(dao.lockMembers("0","client","owner","417"))
                .thenReturn(List.of(member("a",status).setStartedAt(1L).setCompletedAt(2L)));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void completeGraphOverflowAndForeignScopeRejected() {
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(Collections.nCopies(501,item("w","ready")));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        when(items.listByTaskForUpdate("0","client","owner","417",501)).thenReturn(List.of(item("w","ready").setOwnerJiacn("foreign")));
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void nativeOrChatInflightAndCommandsRejectBeforeMutation() {
        when(dao.hasExecutionFacts("0","client","owner","417")).thenReturn(true);
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
        when(dao.hasExecutionFacts("0","client","owner","417")).thenReturn(false);
        when(dao.hasCommandFacts("0","client","owner","417")).thenReturn(true);
        assertThrows(AgentTaskStateException.class,this::cancel); noWrites();
    }
    @Test void retryBootstrapTerminalizesWithoutInventingAttemptAndAdmittedPreserved() {
        when(dao.lockBootstraps("0","client","owner","417")).thenReturn(List.of(bootstrap("RETRY")));
        when(dao.cancelRetryBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong())).thenReturn(1);
        assertEquals(2,cancel().taskVersion());
        verify(dao).cancelRetryBootstrap(eq("0"),eq("client"),eq("owner"),eq("417"),eq(5L),eq(10L),anyLong());
    }
    @Test void claimedBootstrapEvenExpiredRejected() {
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
