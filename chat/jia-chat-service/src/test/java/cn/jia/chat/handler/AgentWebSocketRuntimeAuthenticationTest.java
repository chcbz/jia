package cn.jia.chat.handler;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentWebSocketRuntimeAuthenticationTest {
    static final String AGENT = "agt_" + "a".repeat(32), TOKEN = "1".repeat(32);

    @Test void realRegistrationWireCarriesOnlyServerBoundScopeAndCloseRevokes() throws Exception {
        Fixture f = new Fixture(false);
        when(f.auth.bind(eq("socket-a"), eq("tenant-a"), eq("client-a"), eq(AGENT), eq("runtime-a"),
                eq("key-a"), eq(TOKEN), any())).thenReturn(new AgentRuntimeAuthenticationService.Receipt(
                "native-runtime-v1", "tenant-a", "client-a", AGENT, "runtime-a", true));
        f.register();
        var receipt = f.frames.stream().map(text -> JsonUtil.getMapper().readTree(text))
                .filter(node -> "agent_registered".equals(node.path("type").textValue())).findFirst().orElseThrow();
        assertEquals(TOKEN, receipt.path("token").textValue());
        assertEquals("reg-a", receipt.path("messageId").textValue());
        assertEquals("runtime-a", receipt.path("runtimeInstanceId").textValue());
        assertEquals("tenant-a", receipt.path("runtimeAuth").path("tenantId").textValue());
        assertEquals("client-a", receipt.path("runtimeAuth").path("clientId").textValue());
        assertEquals(AGENT, receipt.path("runtimeAuth").path("agentId").textValue());
        assertFalse(receipt.toString().contains("key-a"));
        verify(f.auth).bind(eq("socket-a"), eq("tenant-a"), eq("client-a"), eq(AGENT), eq("runtime-a"),
                eq("key-a"), eq(TOKEN), any());
        f.handler.afterConnectionClosed(f.session, CloseStatus.NORMAL);
        verify(f.auth, atLeastOnce()).disconnect("socket-a");
    }

    @Test void failedBindingCannotEmitCredentialsOrSuccessfulReconnect() throws Exception {
        Fixture f = new Fixture(false);
        when(f.auth.bind(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any()))
                .thenThrow(new IllegalArgumentException("secret-raw-auth-detail"));
        f.register();
        assertFalse(String.join("", f.frames).contains(TOKEN));
        assertFalse(String.join("", f.frames).contains("secret-raw-auth-detail"));
        assertFalse(String.join("", f.frames).contains("agent_registered"));
        assertTrue(String.join("", f.frames).contains("AGENT_REGISTRATION_UNAVAILABLE"));
        verify(f.auth, atLeastOnce()).disconnect("socket-a");
    }

    @Test void failedReceiptDeliveryRevokesTheNewBinding() throws Exception {
        Fixture f = new Fixture(false);
        when(f.auth.bind(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any()))
                .thenReturn(new AgentRuntimeAuthenticationService.Receipt(
                        "native-runtime-v1", "tenant-a", "client-a", AGENT, "runtime-a", true));
        doThrow(new java.io.IOException("untrusted transport detail")).when(f.session).sendMessage(any());
        f.register();
        verify(f.auth, times(2)).disconnect("socket-a"); // prior-generation invalidation and failed delivery
        assertTrue(f.frames.isEmpty());
    }

    @Test void browserRegistrationDoesNotIssueOrReturnRuntimeCredentials() throws Exception {
        Fixture f = new Fixture(true); f.register();
        verify(f.service, never()).register(any());
        verify(f.auth, never()).bind(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
        assertFalse(String.join("", f.frames).contains(TOKEN));
        assertFalse(String.join("", f.frames).contains("runtimeAuth"));
    }

    @Test void confirmedOfflinePresenceRevokesWithoutWaitingForSocketClose() throws Exception {
        Fixture f = new Fixture(false);
        var runtime = new cn.jia.agent.entity.AgentRuntimeDTO(); runtime.setAgentId(AGENT); runtime.setStatus("offline");
        when(f.service.updateStatus(eq(AGENT), any())).thenReturn(runtime);
        f.handler.handleTextMessage(f.session, new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.presence\","
                + "\"messageId\":\"offline-a\",\"agentId\":\"" + AGENT
                + "\",\"runtimeInstanceId\":\"runtime-a\",\"status\":\"offline\"}"));
        verify(f.auth).disconnect("socket-a");
        assertTrue(String.join("", f.frames).contains("agent_status_updated"));
    }

    @Test void wrongRuntimeIdentityInvalidatesSessionAndNeverRegisters() throws Exception {
        Fixture f = new Fixture(false);
        f.handler.handleTextMessage(f.session,new TextMessage(f.command().replace("runtime-a","runtime-foreign")));
        verify(f.auth).disconnect("socket-a");
        verify(f.service, never()).register(any());
        assertFalse(String.join("", f.frames).contains(TOKEN));
    }

    static class Fixture {
        final AgentService service = mock(AgentService.class);
        final AgentRuntimeAuthenticationService auth = mock(AgentRuntimeAuthenticationService.class);
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<String> frames = new ArrayList<>();
        final AgentWebSocketHandler handler;
        @SuppressWarnings("unchecked") Fixture(boolean browser) throws Exception {
            ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(service);
            when(service.register(any(AgentRegisterDTO.class))).thenReturn(new AgentRegisterResultDTO(AGENT,TOKEN,AgentConstants.STATUS_ONLINE));
            var attributes = new HashMap<String,Object>(); attributes.put("agentId",AGENT);
            attributes.put("jiacn","tenant-a"); attributes.put("clientId","client-a");
            attributes.put("runtimeInstanceId","runtime-a"); attributes.put("managedApiKeyId","key-a");
            when(session.getAttributes()).thenReturn(attributes); when(session.getId()).thenReturn("socket-a");
            when(session.isOpen()).thenReturn(true);
            var headers = new HttpHeaders(); if (browser) headers.setOrigin("https://browser.invalid");
            when(session.getHandshakeHeaders()).thenReturn(headers);
            doAnswer(inv -> { frames.add(((TextMessage)inv.getArgument(0)).getPayload()); return null; }).when(session).sendMessage(any());
            handler = new AgentWebSocketHandler(mock(ChatClient.class),provider,mock(ChatMessageDao.class),mock(ChatConversationEventBroker.class));
            handler.setRuntimeAuthentication(auth);
        }
        String command() { return "{\"schemaVersion\":1,\"messageType\":\"agent.register\",\"messageId\":\"reg-a\",\"agentId\":\""
                + AGENT + "\",\"runtimeInstanceId\":\"runtime-a\",\"name\":\"Native\"}"; }
        void register() throws Exception { handler.handleTextMessage(session,new TextMessage(command())); }
    }
}
