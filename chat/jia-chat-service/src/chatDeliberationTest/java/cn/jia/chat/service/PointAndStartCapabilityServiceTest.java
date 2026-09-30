package cn.jia.chat.service;

import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskPointAndStartPolicyService;
import cn.jia.agent.service.NativeBountyExecutionSessionLookup;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointAndStartCapabilityServiceTest {
    private final NativeBountyExecutionSessionLookup sessions=mock(NativeBountyExecutionSessionLookup.class);
    private final AgentTaskPointAndStartPolicyService policy=mock(AgentTaskPointAndStartPolicyService.class);
    private final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    private final ChatBountyBootstrapRelay bootstrap=mock(ChatBountyBootstrapRelay.class);
    private final ChatBountyExecutionRelay execution=mock(ChatBountyExecutionRelay.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ChatBountyBootstrapRelay> bootstraps=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ChatBountyExecutionRelay> executions=mock(ObjectProvider.class);
    private final AgentTaskExecutionGrantService.Scope scope=new AgentTaskExecutionGrantService.Scope("0","client-a","owner-a");

    @Test
    void positiveNativeTransportStillReportsTruthfulUnavailableCostAuthorization() {
        readySources();
        var result=service(allEnabled()).read(scope,"task-a","agent-a");
        assertEquals("READY",result.serverLane().state());
        assertEquals("READY",result.nativeExecution().state());
        assertEquals(List.of("GENERATE_IMAGE"),result.requestedOperations());
        assertEquals("GENERATE_IMAGE",result.initialOperation());
        assertEquals("UNAVAILABLE",result.authorization().state());
        assertFalse(result.authorization().paidExecutionAuthorized());
        assertFalse(result.newStart().eligible());
        assertEquals(List.of("COST_AUTHORIZATION_UNAVAILABLE"),result.newStart().blockingReasons());
        assertEquals("EMPTY_ONLY",result.inputRefsPolicy());
        assertFalse(result.originalIntentRecovery().legacyFallbackAllowed());
    }

    @Test
    void disabledServerOfflineNativeAndClosedTaskRemainSeparateBlockers() {
        when(policy.read(scope,"task-a","agent-a")).thenReturn(new AgentTaskPointAndStartPolicyService.Snapshot("task-a","agent-a","completed",false,"TASK_NOT_OPEN"));
        when(sessions.current(any())).thenReturn(new NativeBountyExecutionSessionLookup.Snapshot(NativeBountyExecutionSessionLookup.State.OFFLINE,null,null,List.of()));
        var flags=new PointAndStartCapabilityService.Flags(false,false,false,false,false,false,false);
        var result=service(flags).read(scope,"task-a","agent-a");
        assertEquals("DISABLED",result.serverLane().state());
        assertEquals("OFFLINE",result.nativeExecution().state());
        assertTrue(result.requestedOperations().isEmpty());assertNull(result.initialOperation());
        assertTrue(result.newStart().blockingReasons().contains("TASK_NOT_OPEN"));
        assertTrue(result.newStart().blockingReasons().contains("NATIVE_EXECUTION_OFFLINE"));
        assertTrue(result.newStart().blockingReasons().contains("COST_AUTHORIZATION_UNAVAILABLE"));
    }

    @Test
    void malformedNativeSourceCannotAdvertiseUnknownOrUndeclaredOperations() {
        when(policy.read(scope,"task-a","agent-a")).thenReturn(
                new AgentTaskPointAndStartPolicyService.Snapshot(
                        "task-a","agent-a","open",true,null));
        when(sessions.current(any())).thenReturn(new NativeBountyExecutionSessionLookup.Snapshot(
                NativeBountyExecutionSessionLookup.State.READY,1,
                "PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1",List.of("EDIT_IMAGE")));
        assertThrows(PointAndStartCapabilityService.SourceUnavailable.class,
                ()->service(allEnabled()).read(scope,"task-a","agent-a"));
    }

    @Test
    void enabledFlagsRequireActualStorageAndBothRunningRelays() {
        when(policy.read(scope,"task-a","agent-a")).thenReturn(new AgentTaskPointAndStartPolicyService.Snapshot("task-a","agent-a","open",true,null));
        when(sessions.current(any())).thenReturn(new NativeBountyExecutionSessionLookup.Snapshot(NativeBountyExecutionSessionLookup.State.READY,1,"PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1",List.of("GENERATE_IMAGE")));
        when(storage.maxContentBytes()).thenReturn(-1L);when(bootstraps.getIfAvailable()).thenReturn(bootstrap);when(executions.getIfAvailable()).thenReturn(execution);
        var result=service(allEnabled()).read(scope,"task-a","agent-a");
        assertEquals("NOT_RUNNING",result.serverLane().state());
        assertEquals(List.of("PERSONAL_WORKSPACE_STORAGE_NOT_READY","BOUNTY_BOOTSTRAP_NOT_RUNNING","BOUNTY_EXECUTION_NOT_RUNNING"),result.serverLane().blockingReasons());
        assertTrue(result.requestedOperations().isEmpty());
    }

    private void readySources(){
        when(policy.read(scope,"task-a","agent-a")).thenReturn(new AgentTaskPointAndStartPolicyService.Snapshot("task-a","agent-a","open",true,null));
        when(sessions.current(any())).thenReturn(new NativeBountyExecutionSessionLookup.Snapshot(NativeBountyExecutionSessionLookup.State.READY,1,"PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1",List.of("GENERATE_IMAGE")));
        when(storage.maxContentBytes()).thenReturn(10L);when(bootstraps.getIfAvailable()).thenReturn(bootstrap);when(executions.getIfAvailable()).thenReturn(execution);when(bootstrap.isRunning()).thenReturn(true);when(execution.isRunning()).thenReturn(true);
    }
    private PointAndStartCapabilityService service(PointAndStartCapabilityService.Flags flags){return new PointAndStartCapabilityService(sessions,policy,storage,bootstraps,executions,flags);}
    private PointAndStartCapabilityService.Flags allEnabled(){return new PointAndStartCapabilityService.Flags(true,true,true,true,true,true,true);}
}
