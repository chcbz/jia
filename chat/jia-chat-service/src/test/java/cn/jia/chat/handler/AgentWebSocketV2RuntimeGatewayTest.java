package cn.jia.chat.handler;

import cn.jia.agent.entity.AgentRegisterDTO;
import cn.jia.agent.entity.AgentRegisterResultDTO;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AgentWebSocketV2RuntimeGatewayTest {
    private final AgentService agentService = mock(AgentService.class);
    private final ObjectProvider<AgentService> agentServices = mock(ObjectProvider.class);

    @Test
    void sendsByteExactPayloadOnlyToTheAuthoritativeSessionId() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession oldSession = session("old-session", "runtime-old");
        WebSocketSession currentSession = session("current-session", "runtime-current");
        register(handler, oldSession, "agent-1");
        register(handler, currentSession, "agent-1");
        clearInvocations(oldSession, currentSession);
        byte[] raw = "{\"messageId\":\"msg-1\",\"opaque\":\"执行🔥\"}"
                .getBytes(StandardCharsets.UTF_8);

        AgentCommandV2RuntimeGateway.DispatchResult result = handler.dispatch(
                target("current-session", "runtime-current"), raw,
                AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS);

        assertEquals(AgentCommandV2RuntimeGateway.Kind.DISPATCHED, result.kind());
        org.mockito.ArgumentCaptor<TextMessage> sent =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(currentSession).sendMessage(sent.capture());
        assertArrayEquals(raw, sent.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
        verify(oldSession, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void successfulV2SendDoesNotLeaveTimeoutOnSubsequentLegacySend() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        WsSession nativeSession = (WsSession) ((NativeWebSocketSession) current)
                .getNativeSession();
        Map<String, Object> properties = nativeSession.getUserProperties();
        clearInvocations(current);
        AtomicInteger sends = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (sends.getAndIncrement() == 0) {
                long deadline = (Long) properties.get(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY);
                assertTrue(deadline > 0L && deadline <= 100L);
            } else {
                assertTrue(!properties.containsKey(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
            }
            return null;
        }).when(current).sendMessage(any(TextMessage.class));

        assertEquals(AgentCommandV2RuntimeGateway.Kind.DISPATCHED,
                handler.dispatch(target("current-session", "runtime-current"),
                        "{}".getBytes(StandardCharsets.UTF_8), 100L).kind());
        assertTrue(!properties.containsKey(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
        handler.handleTextMessage(current, new TextMessage("{\"type\":\"ping\"}"));
        assertEquals(2, sends.get());
    }

    @Test
    void restoresPreexistingTimeoutAfterSuccessfulV2SendAndLegacySend() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        WsSession nativeSession = (WsSession) ((NativeWebSocketSession) current)
                .getNativeSession();
        Map<String, Object> properties = nativeSession.getUserProperties();
        properties.put(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, 12000L);
        clearInvocations(current);
        AtomicInteger sends = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (sends.getAndIncrement() == 0) {
                long deadline = (Long) properties.get(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY);
                assertTrue(deadline > 0L && deadline <= 100L);
            } else {
                assertEquals(12000L, properties.get(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
            }
            return null;
        }).when(current).sendMessage(any(TextMessage.class));

        assertEquals(AgentCommandV2RuntimeGateway.Kind.DISPATCHED,
                handler.dispatch(target("current-session", "runtime-current"),
                        "{}".getBytes(StandardCharsets.UTF_8), 100L).kind());
        assertEquals(12000L, properties.get(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
        handler.handleTextMessage(current, new TextMessage("{\"type\":\"ping\"}"));
        assertEquals(2, sends.get());
    }

    @Test
    void rejectsOldRuntimeOrCrossScopeTargetWithoutSending() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        clearInvocations(current);
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);

        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                handler.dispatch(target("current-session", "runtime-old"), raw,
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());
        AgentCommandV2RuntimeGateway.CurrentSession foreign =
                new AgentCommandV2RuntimeGateway.CurrentSession(
                        "current-session", "2", "cyf", "local-test", "client-a",
                        "owner-a", "agent-1", "runtime-current");
        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                handler.dispatch(foreign, raw,
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());
        verify(current, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void distinguishesOfflineAndSendFailure() throws Exception {
        AgentWebSocketHandler handler = handler();
        byte[] raw = "{}".getBytes(StandardCharsets.UTF_8);
        assertEquals(AgentCommandV2RuntimeGateway.Kind.OFFLINE,
                handler.dispatch(target("missing", "runtime-current"), raw,
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());

        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        clearInvocations(current);
        doThrow(new IllegalStateException("send failed"))
                .when(current).sendMessage(any(TextMessage.class));
        assertEquals(AgentCommandV2RuntimeGateway.Kind.SEND_FAILED,
                handler.dispatch(target("current-session", "runtime-current"), raw,
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());
    }

    @Test
    void workerScopeAloneNeverAdvertisesV2OrItsContract() throws Exception {
        AgentWebSocketHandler handler = handler();
        handler.setAgentCommandWorkerScope(new WorkerScope(TenantId.of("1"),
                DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
                7, "agent-command", 9));
        WebSocketSession current = session("current-session", "runtime-current");
        handler.afterConnectionEstablished(current);
        clearInvocations(current);

        handler.handleTextMessage(current, new TextMessage(
                "{\"schemaVersion\":1,\"messageType\":\"protocol.hello\","
                        + "\"messageId\":\"hello-1\"}"));

        org.mockito.ArgumentCaptor<TextMessage> sent =
                org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(current).sendMessage(sent.capture());
        assertTrue(sent.getValue().getPayload().contains(
                "\"supportedProtocolVersions\":[1]"));
        assertTrue(!sent.getValue().getPayload().contains(
                AgentCommandV2RuntimeGateway.DEDUPLICATION_CONTRACT));
    }

    @Test
    void v1RegistrationIsNotUpgradedByWorkerScopeOrCapabilityClaims() throws Exception {
        AgentWebSocketHandler handler = handler();
        handler.setAgentCommandWorkerScope(new WorkerScope(TenantId.of("1"),
                DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
                7, "agent-command", 9));
        WebSocketSession missing = session("missing-contract", "runtime-missing");
        missing.getAttributes().remove("commandV2DeduplicationContract");
        register(handler, missing, "agent-1");
        clearInvocations(missing);
        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                handler.dispatch(target("missing-contract", "runtime-missing"),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());

        WebSocketSession capable = session("capable", "runtime-capable");
        capable.getAttributes().remove("commandV2DeduplicationContract");
        handler.afterConnectionEstablished(capable);
        handler.handleTextMessage(capable, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"agent-1\"," +
                        "\"name\":\"Agent\",\"byteExactCommandV2\":true," +
                        "\"commandV2DeduplicationContract\":\"" +
                        AgentCommandV2RuntimeGateway.DEDUPLICATION_CONTRACT + "\"}"));
        clearInvocations(capable);
        assertEquals(AgentCommandV2RuntimeGateway.Kind.REJECTED,
                handler.dispatch(target("capable", "runtime-capable"),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        AgentCommandV2ExecutionPort.SEND_TIMEOUT_MILLIS).kind());
    }

    @Test
    void sessionLockContentionIsBoundedAndDoesNotInvalidateHealthySession() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        clearInvocations(current);
        CountDownLatch legacySendEntered = new CountDownLatch(1);
        CountDownLatch releaseLegacySend = new CountDownLatch(1);
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger concurrentSends = new AtomicInteger();
        AtomicInteger maximumConcurrentSends = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            int active = concurrentSends.incrementAndGet();
            maximumConcurrentSends.accumulateAndGet(active, Math::max);
            try {
                if (sends.incrementAndGet() == 1) {
                    legacySendEntered.countDown();
                    assertTrue(releaseLegacySend.await(5, TimeUnit.SECONDS));
                }
                return null;
            } finally {
                concurrentSends.decrementAndGet();
            }
        }).when(current).sendMessage(any(TextMessage.class));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> legacySend = executor.submit(() -> handler.handleTextMessage(
                    current, new TextMessage("{\"type\":\"ping\"}")));
            assertTrue(legacySendEntered.await(5, TimeUnit.SECONDS));

            AgentCommandV2RuntimeGateway.DispatchResult contended = assertTimeout(
                    Duration.ofMillis(500), () -> handler.dispatch(
                            target("current-session", "runtime-current"),
                            "{}".getBytes(StandardCharsets.UTF_8), 100L));

            assertEquals(AgentCommandV2RuntimeGateway.Kind.SEND_FAILED, contended.kind());
            assertEquals(1, sends.get());
            releaseLegacySend.countDown();
            legacySend.get(5, TimeUnit.SECONDS);

            assertEquals(AgentCommandV2RuntimeGateway.Kind.DISPATCHED,
                    handler.dispatch(target("current-session", "runtime-current"),
                            "{}".getBytes(StandardCharsets.UTF_8), 100L).kind());
            assertEquals(2, sends.get());
            assertEquals(1, maximumConcurrentSends.get());
        } finally {
            releaseLegacySend.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void appliesTomcatBlockingSendDeadlineAndInvalidatesTimedOutSessionWithoutLateTask()
            throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        clearInvocations(current);
        WsSession nativeSession = (WsSession) ((NativeWebSocketSession) current)
                .getNativeSession();
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger appliedTimeout = new AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            sends.incrementAndGet();
            long timeout = (Long) nativeSession.getUserProperties().get(
                    Constants.BLOCKING_SEND_TIMEOUT_PROPERTY);
            appliedTimeout.set((int) timeout);
            Thread.sleep(timeout + 20L);
            throw new IOException("simulated Tomcat blocking send timeout");
        }).when(current).sendMessage(any(TextMessage.class));

        AgentCommandV2RuntimeGateway.DispatchResult result = assertTimeout(
                Duration.ofMillis(500), () -> handler.dispatch(
                        target("current-session", "runtime-current"),
                        "{}".getBytes(StandardCharsets.UTF_8), 100L));

        assertEquals(AgentCommandV2RuntimeGateway.Kind.SEND_FAILED, result.kind());
        assertEquals(1, sends.get());
        assertTrue(appliedTimeout.get() > 0 && appliedTimeout.get() <= 100);
        assertTrue(!nativeSession.getUserProperties().containsKey(
                Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
        assertEquals(AgentCommandV2RuntimeGateway.Kind.OFFLINE,
                handler.dispatch(target("current-session", "runtime-current"),
                        "{}".getBytes(StandardCharsets.UTF_8), 100L).kind());
        assertEquals(1, sends.get());
    }

    @Test
    void restoresPreexistingTimeoutAfterFailedV2Send() throws Exception {
        AgentWebSocketHandler handler = handler();
        WebSocketSession current = session("current-session", "runtime-current");
        register(handler, current, "agent-1");
        WsSession nativeSession = (WsSession) ((NativeWebSocketSession) current)
                .getNativeSession();
        Map<String, Object> properties = nativeSession.getUserProperties();
        properties.put(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, 12000L);
        clearInvocations(current);
        doThrow(new IOException("send failed"))
                .when(current).sendMessage(any(TextMessage.class));

        assertEquals(AgentCommandV2RuntimeGateway.Kind.SEND_FAILED,
                handler.dispatch(target("current-session", "runtime-current"),
                        "{}".getBytes(StandardCharsets.UTF_8), 100L).kind());
        assertEquals(12000L, properties.get(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY));
    }

    private AgentWebSocketHandler handler() {
        when(agentServices.getIfAvailable()).thenReturn(agentService);
        when(agentService.register(any(AgentRegisterDTO.class))).thenAnswer(invocation -> {
            AgentRegisterDTO request = invocation.getArgument(0);
            return new AgentRegisterResultDTO(request.getAgentId(), "0123456789abcdef0123456789abcdef", "online");
        });
        return new AgentWebSocketHandler(mock(ChatClient.class), agentServices,
                mock(ChatMessageDao.class), mock(ChatConversationEventBroker.class));
    }

    private void register(AgentWebSocketHandler handler, WebSocketSession session,
            String agentId) throws Exception {
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage(
                "{\"type\":\"agent.register\",\"agentId\":\"" + agentId
                        + "\",\"name\":\"Agent\"}"));
    }

    private WebSocketSession session(String id, String runtimeInstanceId) {
        WebSocketSession session = mock(WebSocketSession.class,
                withSettings().extraInterfaces(NativeWebSocketSession.class));
        WsSession nativeSession = mock(WsSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(((NativeWebSocketSession) session).getNativeSession()).thenReturn(nativeSession);
        when(nativeSession.isOpen()).thenReturn(true);
        when(nativeSession.getUserProperties()).thenReturn(new ConcurrentHashMap<>());
        when(session.getAttributes()).thenReturn(new HashMap<>(Map.of(
                "agentId", "agent-1", "runtimeInstanceId", runtimeInstanceId,
                "tenantId", "1", "dataDomainId", "cyf",
                "executionPoolId", "local-test", "clientId", "client-a",
                "jiacn", "owner-a", "ownerJiacn", "owner-a",
                "commandV2DeduplicationContract",
                AgentCommandV2RuntimeGateway.DEDUPLICATION_CONTRACT)));
        return session;
    }

    private AgentCommandV2RuntimeGateway.CurrentSession target(
            String sessionId, String runtimeInstanceId) {
        return new AgentCommandV2RuntimeGateway.CurrentSession(
                sessionId, "1", "cyf", "local-test", "client-a", "owner-a",
                "agent-1", runtimeInstanceId);
    }
}
