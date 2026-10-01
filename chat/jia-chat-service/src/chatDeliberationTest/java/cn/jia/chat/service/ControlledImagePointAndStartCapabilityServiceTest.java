package cn.jia.chat.service;

import cn.jia.agent.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledImagePointAndStartCapabilityServiceTest {
    private final ControlledImageExecutionSessionLookup sessions=mock(ControlledImageExecutionSessionLookup.class);
    private final ControlledImageProviderAuthorityLookup operator=mock(ControlledImageProviderAuthorityLookup.class);
    private final AgentTaskPointAndStartPolicyService policy=mock(AgentTaskPointAndStartPolicyService.class);
    private final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ChatBountyBootstrapRelay> boot=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ChatBountyExecutionRelay> execution=mock(ObjectProvider.class);
    private final AgentTaskExecutionGrantService.Scope scope=new AgentTaskExecutionGrantService.Scope("0","client","owner");

    @Test void readySourcesStillRequireExactOwnerConsent() {
        when(policy.read(scope,"task","agent")).thenReturn(new AgentTaskPointAndStartPolicyService.Snapshot(
                "task","agent","confirmed",true,null));
        when(sessions.current(any())).thenReturn(new ControlledImageExecutionSessionLookup.Snapshot(
                ControlledImageExecutionSessionLookup.State.READY,"runtime",1,
                "PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2",List.of("GENERATE_IMAGE"),
                "CONTROLLED_IMAGE_HTTP_V1","binding",1L,"model",16,1,1));
        when(operator.current(any(),eq("agent"),eq("binding"),eq(1L))).thenReturn(
                new ControlledImageProviderAuthorityLookup.Snapshot(
                        ControlledImageProviderAuthorityLookup.State.READY,"CONTROLLED_IMAGE_HTTP_V1",
                        "binding","1","model",1));
        when(storage.maxContentBytes()).thenReturn(1024L);
        var b=mock(ChatBountyBootstrapRelay.class);when(b.isRunning()).thenReturn(true);when(boot.getIfAvailable()).thenReturn(b);
        var e=mock(ChatBountyExecutionRelay.class);when(e.isRunning()).thenReturn(true);when(execution.getIfAvailable()).thenReturn(e);
        var result=service(allEnabled()).read(scope,"task","agent");
        assertEquals(2,result.schemaVersion());assertEquals("READY",result.serverLane().state());
        assertEquals("READY",result.controlledExecution().state());
        assertEquals("CONSENT_REQUIRED",result.authorization().state());
        assertFalse(result.newStart().eligible());
        assertEquals(List.of("OWNER_EXACT_CONSENT_REQUIRED"),result.newStart().blockingReasons());
        assertEquals(List.of("GENERATE_IMAGE"),result.requestedOperations());
        assertEquals("TASK_LINKED_REFERENCE",result.inputRefsPolicy());assertEquals(16,result.maxInputItems());
    }

    @Test void missingOperatorDelegationNeverAdvertisesProviderBindingOrOperation() {
        when(policy.read(scope,"task","agent")).thenReturn(new AgentTaskPointAndStartPolicyService.Snapshot(
                "task","agent","confirmed",true,null));
        when(sessions.current(any())).thenReturn(new ControlledImageExecutionSessionLookup.Snapshot(
                ControlledImageExecutionSessionLookup.State.READY,"runtime",1,
                "PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2",List.of("GENERATE_IMAGE"),
                "CONTROLLED_IMAGE_HTTP_V1","binding",1L,"model",16,1,1));
        when(operator.current(any(),anyString(),anyString(),anyLong())).thenReturn(
                new ControlledImageProviderAuthorityLookup.Snapshot(
                        ControlledImageProviderAuthorityLookup.State.UNAVAILABLE,null,null,null,null,null));
        when(storage.maxContentBytes()).thenReturn(1L);
        var b=mock(ChatBountyBootstrapRelay.class);when(b.isRunning()).thenReturn(true);when(boot.getIfAvailable()).thenReturn(b);
        var e=mock(ChatBountyExecutionRelay.class);when(e.isRunning()).thenReturn(true);when(execution.getIfAvailable()).thenReturn(e);
        var result=service(allEnabled()).read(scope,"task","agent");
        assertEquals("UNAVAILABLE",result.controlledExecution().state());assertNull(result.providerBinding());
        assertEquals("UNAVAILABLE",result.authorization().state());assertTrue(result.requestedOperations().isEmpty());
        assertTrue(result.newStart().blockingReasons().contains("OPERATOR_POLICY_UNAVAILABLE"));
    }
    private ControlledImagePointAndStartCapabilityService service(
            ControlledImagePointAndStartCapabilityService.Flags flags) {
        return new ControlledImagePointAndStartCapabilityService(sessions,operator,policy,storage,boot,execution,flags);
    }
    private static ControlledImagePointAndStartCapabilityService.Flags allEnabled() {
        return new ControlledImagePointAndStartCapabilityService.Flags(true,true,true,true,true,true,true,true,true,true);
    }
}
