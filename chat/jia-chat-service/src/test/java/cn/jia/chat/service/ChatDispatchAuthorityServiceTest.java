package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.TaskAssigneeAuthorityService;
import cn.jia.chat.deliberation.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Authorization boundary tests, not DB/WS or formal Flow evidence. */
class ChatDispatchAuthorityServiceTest {
    final ChatDispatchAuthorityMapper mapper=mock(ChatDispatchAuthorityMapper.class);
    final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    final TaskAssigneeAuthorityService targets=mock(TaskAssigneeAuthorityService.class);
    final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
    final ChatDispatchAuthorityService service=new ChatDispatchAuthorityService(mapper,dao,targets,grants,tasks);
    final ChatDispatchOutboxEntity row=new ChatDispatchOutboxEntity().setTenantId("0").setClientId("client")
            .setOwnerJiacn("A").setEventId("event").setDispatchId("dispatch").setTurnId("turn")
            .setEventType("DISPATCH").setTaskGrantId("grant-original").setTaskGrantVersion(3L);
    final ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setClientId("client").setOwnerJiacn("A")
            .setTurnId("turn").setDispatchId("dispatch").setConversationId("42").setConversationGeneration(2L)
            .setTargetAgentId("agent-B").setRoute("CHAT").setRequestId("request");

    @BeforeEach void fixture() {
        when(tasks.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("A"),eq("task-A"),any()))
                .thenAnswer(call -> call.<AgentTaskMutationTransaction.LockedTaskMutation<Object>>getArgument(4)
                        .apply(new cn.jia.agent.entity.AgentTaskMetaEntity().setTaskVersion(12L).setRewardStatus("reviewing")));
        row.setPayloadJson(CanonicalContextJson.write(Map.of("targetAgentId","agent-B","conversationId","42",
                "conversationScopeType","bounty","taskId","task-A","factsManifest",Map.of("taskAuthority",
                        Map.of("grantId","grant-original","grantVersion","3","assignmentRevision","7","requirementRevision","2")))));
        when(mapper.findDispatch("0","client","dispatch")).thenReturn(List.of(row));
        when(mapper.findTurnDispatch("0","client","turn")).thenReturn(List.of(row));
        when(dao.findTurn("0","A","client","turn")).thenReturn(turn);
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",3))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0","client","B","agent-B",19L,4L));
    }
    @Test void outboundRoutesToBButRetainsAData() {
        var result=service.outgoing(row);
        assertEquals("B",result.executorOwner()); assertEquals("A",result.turn().getOwnerJiacn());
        assertEquals("grant-original",result.dispatch().getTaskGrantId());
        verify(grants).admitFollowupBaseline(new AgentTaskExecutionGrantService.Scope("0","client","A"),
                "task-A","grant-original",3,12,7,2,"agent-B");
        verifyNoMoreInteractions(grants);
    }
    @Test void ackReceiptDeltaFinalAndInputResolveOnlyAuthenticatedBFromOriginalReference() {
        assertEquals("A",service.incoming("0","client","B","agent-B","dispatch").turn().getOwnerJiacn());
        assertEquals("A",service.incomingTurn("0","client","B","agent-B","turn").turn().getOwnerJiacn());
        verify(targets,times(2)).requireGrantTarget("0","client","A","task-A","grant-original",3);
        verifyNoMoreInteractions(targets); // Never asks whether the provider still accepts NEW orders.
    }
    @Test void ownerAAndThirdPartyCannotImpersonateExecutorB() {
        assertThrows(RuntimeException.class,()->service.incoming("0","client","A","agent-B","dispatch"));
        assertThrows(RuntimeException.class,()->service.incoming("0","client","C","agent-B","dispatch"));
        assertThrows(RuntimeException.class,()->service.incoming("0","client","B","agent-C","dispatch"));
    }
    @Test void crossTenantClientAndMissingReferenceAreRejected() {
        assertThrows(RuntimeException.class,()->service.incoming("other","client","B","agent-B","dispatch"));
        assertThrows(RuntimeException.class,()->service.incoming("0","other","B","agent-B","dispatch"));
        row.setTaskGrantId(null);
        assertThrows(RuntimeException.class,()->service.outgoing(row));
        verifyNoInteractions(targets,grants);
    }
    @Test void revokedGrantOrAssignmentNeverFallsBackToCurrentGrant() {
        doThrow(new IllegalStateException("REVOKED_OR_ASSIGNMENT_CHANGED")).when(grants).admitFollowupBaseline(
                new AgentTaskExecutionGrantService.Scope("0","client","A"),"task-A","grant-original",3,12,7,2,"agent-B");
        assertThrows(RuntimeException.class,()->service.outgoing(row));
        verifyNoInteractions(targets);
    }
    @Test void bindingReplacementOrWrongPlatformIsRejected() {
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",3))
                .thenThrow(new IllegalStateException("BINDING_CHANGED"));
        assertThrows(RuntimeException.class,()->service.outgoing(row));
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",3))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0","other","B","agent-B",19L,null));
        assertThrows(RuntimeException.class,()->service.outgoing(row));
    }
    @Test void restartAndStopUsePersistedOriginalDispatchNotCurrentAssignment() {
        var restarted=new ChatDispatchAuthorityService(mapper,dao,targets,grants,tasks);
        var stop=new ChatDispatchOutboxEntity().setTenantId("0").setClientId("client").setOwnerJiacn("A")
                .setEventType("CANCEL_REQUESTED").setDispatchId("dispatch").setTurnId("turn");
        when(mapper.findOriginalGrantForStop("0","client","A","task-A","grant-original"))
                .thenReturn(new ChatDispatchAuthorityMapper.StopGrant(3L,"ACTIVE","agent-B"));
        assertEquals("B",restarted.outgoing(stop).executorOwner());
        verifyNoInteractions(grants); // Revocation may block work, never an original-binding stop.
        verify(targets).requireGrantTarget("0","client","A","task-A","grant-original",3);
        stop.setOwnerJiacn("C");
        assertThrows(RuntimeException.class,()->restarted.outgoing(stop));
    }
    @Test void duplicateLocatorAndForgedPersistedTupleFailClosed() {
        when(mapper.findDispatch("0","client","dispatch")).thenReturn(List.of(row,row));
        assertThrows(RuntimeException.class,()->service.incoming("0","client","B","agent-B","dispatch"));
        row.setTaskGrantVersion(4L);
        assertThrows(RuntimeException.class,()->service.outgoing(row));
        verifyNoInteractions(targets,grants);
    }
    @Test void ordinaryPrivateChatKeepsOwnerOnlyAndNeverAcquiresExternalReadRights() {
        row.setTaskGrantId(null).setTaskGrantVersion(null).setPayloadJson(CanonicalContextJson.write(Map.of(
                "targetAgentId","agent-B","conversationId","42","conversationScopeType","private")));
        assertEquals("A",service.incoming("0","client","A","agent-B","dispatch").executorOwner());
        assertThrows(RuntimeException.class,()->service.incoming("0","client","B","agent-B","dispatch"));
        verifyNoInteractions(targets,grants);
    }
    @Test void inspectionKeepsItsExplicitInputOperationPermission() {
        turn.setRoute("INSPECT");
        service.outgoing(row);
        verify(grants).admit(new AgentTaskExecutionGrantService.Scope("0","client","A"),
                "task-A","grant-original",3,7,"agent-B","INSPECT_INPUTS",false);
        verifyNoMoreInteractions(grants);
        verifyNoInteractions(tasks);
    }
    @Test void unrelatedMultiMemberTaskKeepsOwnerOnlyTransport() {
        row.setTaskGrantId(null).setTaskGrantVersion(null).setPayloadJson(CanonicalContextJson.write(Map.of(
                "targetAgentId","agent-B","conversationId","42","conversationScopeType","bounty",
                "factsManifest",Map.of("participantAgentIds",List.of("agent-B","agent-C")))));
        assertEquals("A",service.incoming("0","client","A","agent-B","dispatch").executorOwner());
        assertThrows(ChatDeliberationException.class,()->service.incoming("0","client","B","agent-B","dispatch"));
        verifyNoInteractions(targets,grants,tasks);
    }

    @Test void revokedStopUsesOnlyOriginalGrantTerminalVersionWhileIncomingRemainsStrict() {
        var stop=new ChatDispatchOutboxEntity().setTenantId("0").setClientId("client").setOwnerJiacn("A")
                .setEventType("CANCEL_REQUESTED").setDispatchId("dispatch").setTurnId("turn");
        when(mapper.findOriginalGrantForStop("0","client","A","task-A","grant-original"))
                .thenReturn(new ChatDispatchAuthorityMapper.StopGrant(4L,"REVOKED","agent-B"));
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",3))
                .thenThrow(new IllegalStateException("STRICT_VERSION_MISMATCH"));
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",4))
                .thenReturn(new TaskAssigneeAuthorityService.Target("0","client","B","agent-B",19L,null));
        assertEquals("B",service.outgoing(stop).executorOwner());
        verifyNoInteractions(grants,tasks);
        assertThrows(RuntimeException.class,()->service.incoming("0","client","B","agent-B","dispatch"));
        when(targets.requireGrantTarget("0","client","A","task-A","grant-original",4))
                .thenThrow(new IllegalStateException("BINDING_REPLACED"));
        assertThrows(RuntimeException.class,()->service.outgoing(stop));
        when(mapper.findOriginalGrantForStop("0","client","A","task-A","grant-original"))
                .thenReturn(new ChatDispatchAuthorityMapper.StopGrant(4L,"ACTIVE","agent-B"));
        assertThrows(ChatDeliberationException.class,()->service.outgoing(stop));
        when(mapper.findOriginalGrantForStop("0","client","A","task-A","grant-original"))
                .thenReturn(new ChatDispatchAuthorityMapper.StopGrant(5L,"REVOKED","agent-B"));
        assertThrows(ChatDeliberationException.class,()->service.outgoing(stop));
    }

}
