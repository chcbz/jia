package cn.jia.chat.handler;

import cn.jia.agent.service.SkillCapabilityLookup.*;
import cn.jia.agent.service.AgentManagedSessionLookup.SessionFence;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.jia.chat.handler.AgentWebSocketRuntimeAuthenticationTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketSkillExecutionRegistrationTest {
    final Scope scope = new Scope(TENANT, CLIENT, OWNER, AGENT);
    final SessionFence fence = new SessionFence(UnifiedRuntimeTestSupport.INSTALLATION, "host-1", "runtime-a", 1);
    final SkillExecutionSessionRegistry registry = new SkillExecutionSessionRegistry();
    final ObjectMapper json = new ObjectMapper();

    Fixture fixture() throws Exception {
        var f = new Fixture(false); f.handler.setSkillExecutionSessions(registry);
        when(f.auth.bind(eq("socket-a"), any(AgentRuntimeAuthenticationService.Proof.class), any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt("native-runtime-v1", TENANT, CLIENT, OWNER,
                        AGENT, "runtime-a", UnifiedRuntimeTestSupport.INSTALLATION, "host-1", 1, true));
        return f;
    }
    @SuppressWarnings("unchecked") String wire(Fixture f, Object report) {
        Map<String,Object> payload = json.readValue(f.command(), Map.class);
        UnifiedRuntimeTestSupport.registrationProof(f.session, payload);
        payload.put("durableStateHealthy", true); payload.put("readyCommandTypes", List.of());
        if (report != null) payload.put("skillExecution", report);
        return json.writeValueAsString(payload);
    }
    void register(Fixture f, Object report) { f.handler.handleTextMessage(f.session, new TextMessage(wire(f, report))); }

    @Test void onlyDeliveredAuthenticatedRegistrationActivatesExactTrustedSubject() throws Exception {
        var f = fixture();
        doAnswer(inv -> {
            String frame = ((TextMessage)inv.getArgument(0)).getPayload();
            if (frame.contains("agent_registered")) assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
            f.frames.add(frame); return null;
        }).when(f.session).sendMessage(any());
        register(f, SkillExecutionDeclarationTest.catalog());
        assertEquals(State.AVAILABLE, registry.lookup(scope, fence).state());
        assertEquals(2, registry.lookup(scope, fence).catalog().skills().size());
        assertEquals(State.DISCONNECTED, registry.lookup(new Scope(TENANT, CLIENT, "foreign-owner", AGENT), fence).state());
        assertEquals(State.FENCED, registry.lookup(scope, new SessionFence(fence.runtimeInstallationId(), "foreign-host", "runtime-a", 1)).state());
    }
    @Test void missingOrMalformedReportReplacesPriorDirectoryWithoutDisruptingOtherRegistration() throws Exception {
        var f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        register(f, null); assertEquals(State.INVALID_DECLARATION, registry.lookup(scope, fence).state());
        register(f, SkillExecutionDeclarationTest.catalog());
        var report = SkillExecutionDeclarationTest.catalog(); report.put("ownerJiacn", "foreign-owner"); register(f, report);
        assertEquals(State.INVALID_DECLARATION, registry.lookup(scope, fence).state());
        assertTrue(f.frames.stream().anyMatch(frame -> frame.contains("agent_registered")));
    }
    @Test void duplicateJsonKeysRejectAndRemovePriorDirectoryWithoutNormalizingThemAway() throws Exception {
        var f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        String duplicated = wire(f, SkillExecutionDeclarationTest.catalog()).replace("\"catalogRevision\":\"7\"", "\"catalogRevision\":\"7\",\"catalogRevision\":\"8\"");
        assertTrue(duplicated.contains("\"catalogRevision\":\"8\""));
        f.handler.handleTextMessage(f.session, new TextMessage(duplicated));
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        assertTrue(f.frames.stream().anyMatch(frame -> frame.contains("AGENT_REGISTRATION_INVALID")));
    }
    @Test void failedAuthenticationOrReceiptDeliveryNeverRetainsPriorDirectory() throws Exception {
        var f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        when(f.auth.bind(anyString(), any(AgentRuntimeAuthenticationService.Proof.class), any()))
                .thenThrow(new IllegalArgumentException("private"));
        register(f, SkillExecutionDeclarationTest.catalog()); assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        doThrow(new java.io.IOException("private")).when(f.session).sendMessage(any());
        register(f, SkillExecutionDeclarationTest.catalog()); assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
    }
    @Test void disconnectUnhealthyStateAndRevokedBindingNeverReturnCachedFacts() throws Exception {
        var f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(false);
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(true);
        f.handler.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
        register(f, SkillExecutionDeclarationTest.catalog());
        String unhealthy = wire(f, SkillExecutionDeclarationTest.catalog()).replace("\"durableStateHealthy\":true", "\"durableStateHealthy\":false");
        f.handler.handleTextMessage(f.session, new TextMessage(unhealthy));
        assertEquals(State.DISCONNECTED, registry.lookup(scope, fence).state());
    }
    @Test void presenceCannotReplaceDirectoryOrGrantReadiness() throws Exception {
        var f = fixture(); register(f, SkillExecutionDeclarationTest.catalog());
        var runtime = new cn.jia.agent.entity.AgentRuntimeDTO(); runtime.setAgentId(AGENT); runtime.setStatus("online");
        when(f.service.updateStatus(eq(AGENT), any())).thenReturn(runtime);
        String wire = wire(f, Map.of("forged", true)).replace("agent.register", "agent.presence");
        wire = wire.substring(0, wire.length()-1)+",\"status\":\"online\"}";
        f.handler.handleTextMessage(f.session, new TextMessage(wire));
        assertEquals("7", registry.lookup(scope, fence).catalog().catalogRevision());
    }
}
