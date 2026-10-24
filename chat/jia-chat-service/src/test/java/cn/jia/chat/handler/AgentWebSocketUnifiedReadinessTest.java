package cn.jia.chat.handler;

import cn.jia.agent.entity.*;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.service.ChatConversationEventBroker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Delivery/readiness slice; persistent fence authority is mocked, not claimed as cloud evidence. */
class AgentWebSocketUnifiedReadinessTest {
    @Test void installationSessionDoesNotAdvertiseOrEnterUnfinishedV2Registration() throws Exception {
        var f = new Fixture();
        var adapter = mock(AgentRuntimeV2WebSocketAdapter.class);
        when(adapter.supportedProtocolVersions()).thenReturn(new int[] {1, 2});
        f.handler.setRegistrationV2(adapter);
        var session = f.session("installation", "agent-a");

        f.register(session, true, true);

        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        verify(adapter, never()).intercept(any(), any(), any());
        verify(adapter, never()).supportedProtocolVersions();
        var frames = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(frames.capture());
        var connected = frames.getAllValues().stream()
                .map(TextMessage::getPayload).map(wire -> {
                    try { return new ObjectMapper().readTree(wire); }
                    catch (Exception invalid) { throw new AssertionError(invalid); }
                }).filter(wire -> "connected".equals(wire.path("type").asText()))
                .findFirst().orElseThrow();
        assertEquals(1, connected.path("supportedProtocolVersions").size());
        assertEquals(1, connected.path("supportedProtocolVersions").get(0).asInt());
        assertFalse(connected.has("commandV2DeduplicationContract"));
    }

    @Test void socketPresenceAndHeartbeatDoNotAdmitExecution() throws Exception {
        var f = new Fixture(); var session = f.session("socket", "agent-a");
        f.handler.afterConnectionEstablished(session);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
    }
    @Test void durableStateUnhealthyDoesNotStarveReadySibling() throws Exception {
        var f = new Fixture(); var a = f.session("socket-a", "agent-a"); var b = f.session("socket-b", "agent-b");
        f.register(a, false, true); f.register(b, true, true);
        clearInvocations(a, b);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        assertEquals(AgentRawCommandDispatchResult.Status.SENT, f.dispatch("agent-b").status());
        verify(a, never()).sendMessage(any()); verify(b).sendMessage(any(TextMessage.class));
    }
    @Test void disabledExecutionAdapterIsNotMadeReadyByHealthyQueue() throws Exception {
        var f = new Fixture(); var a = f.session("socket-a", "agent-a"); f.register(a, true, false);
        clearInvocations(a);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        verify(a, never()).sendMessage(any());
    }
    @Test void staleChannelDoesNotReceiveOriginalCommandButCurrentChannelReceivesExactBytesOnce() throws Exception {
        var f = new Fixture(); var old = f.session("old", "agent-a"); var current = f.session("new", "agent-a");
        f.register(old, true, true); f.register(current, true, true); f.current.remove("old");
        clearInvocations(old, current);
        var result = f.dispatch("agent-a"); assertEquals(AgentRawCommandDispatchResult.Status.SENT, result.status());
        assertEquals(1, result.sentSessionCount()); verify(old, never()).sendMessage(any());
        var message = org.mockito.ArgumentCaptor.forClass(TextMessage.class); verify(current).sendMessage(message.capture());
        assertArrayEquals(f.wire("agent-a"), message.getValue().getPayload().getBytes(StandardCharsets.UTF_8));
    }
    @Test void duplicateCurrentExecutionChannelsRejectInsteadOfBroadcast() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-a");
        f.register(a, true, true); f.register(b, true, true); clearInvocations(a, b);
        assertEquals(AgentRawCommandDispatchResult.Status.REJECTED, f.dispatch("agent-a").status());
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        verify(a, never()).sendMessage(any()); verify(b, never()).sendMessage(any());
    }
    @Test void receiptDeliveryFailureCannotActivateReadiness() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a");
        doThrow(new java.io.IOException("fixture transport failure")).when(a).sendMessage(any());
        f.register(a, true, true);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
    }
    @Test void reRegistrationWithBrokenDurableStateRetiresPreviousReadiness() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        f.register(a, false, true);
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
    }
    @Test void currentBindingIsRecheckedImmediatelyBeforeTransportSend() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenReturn(true, false);
        clearInvocations(a);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, f.dispatch("agent-a").status());
        verify(a, never()).sendMessage(any());
    }
    @Test @Timeout(20)
    void rawCommandRechecksRevokedAuthorityAfterWaitingForOrdinaryEventGate() throws Exception {
        assertQueuedCommandRejected(false);
    }
    @Test @Timeout(20)
    void skillCommandRechecksRotatedFenceAfterWaitingForOrdinaryEventGate() throws Exception {
        assertQueuedCommandRejected(true);
    }
    private static void assertQueuedCommandRejected(boolean skill) throws Exception {
        var f = new Fixture(); var a = f.session("gate-session", "agent-a");
        f.register(a, true, List.of(skill ? "SKILL_INSTALL" : "TASK_INVITE"));
        var proof = UnifiedRuntimeTestSupport.install(a);
        var fence = new cn.jia.agent.service.AgentManagedSessionLookup.SessionFence(
                proof.installationId(), proof.hostId(), proof.scope().runtimeInstanceId(), proof.sessionGeneration());
        var authorityCurrent = new AtomicBoolean(true);
        when(f.auth.isCurrentBinding(any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> authorityCurrent.get());
        byte[] raw = skill ? new String(f.wire("agent-a"), StandardCharsets.UTF_8)
                .replace("TASK_INVITE", "SKILL_INSTALL")
                .replace("\"taskId\":\"task\"", "\"taskId\":\"task\",\"orderId\":\"task\"")
                .getBytes(StandardCharsets.UTF_8) : f.wire("agent-a");
        clearInvocations(a);
        var eventSending = new CountDownLatch(1); var releaseEvent = new CountDownLatch(1);
        doAnswer(inv -> {
            TextMessage frame = inv.getArgument(0);
            if ("pong".equals(new ObjectMapper().readTree(frame.getPayload()).path("type").asText())) {
                eventSending.countDown();
                assertTrue(releaseEvent.await(5, TimeUnit.SECONDS), "Ordinary event gate was not released");
            }
            return null;
        }).when(a).sendMessage(any(TextMessage.class));
        var executor = Executors.newFixedThreadPool(2);
        var dispatchThread = new AtomicReference<Thread>();
        try {
            var event = executor.submit(() -> {
                f.handler.handleTextMessage(a, new TextMessage("{\"type\":\"ping\",\"agentId\":\"agent-a\","
                        + "\"runtimeInstanceId\":\"boot\"}"));
                return null;
            });
            assertTrue(eventSending.await(5, TimeUnit.SECONDS), "Ordinary event never acquired the gate");
            var gate = sessionSendLock(f.handler, a.getId());
            var dispatch = executor.submit(() -> {
                dispatchThread.set(Thread.currentThread());
                return skill ? f.handler.dispatchManagedSkill("0", "client", "agent-a", fence, raw)
                        : f.handler.dispatchExactRawCommand("0", "client", "task", "agent-a", raw);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while ((dispatchThread.get() == null || !gate.hasQueuedThread(dispatchThread.get()))
                    && System.nanoTime() < deadline && !dispatch.isDone()) Thread.sleep(1);
            assertTrue(dispatchThread.get() != null && gate.hasQueuedThread(dispatchThread.get()),
                    "Command never waited at the shared gate after passing its pre-send checks");
            if (skill) {
                a.getAttributes().put(cn.jia.chat.config.AgentRuntimeHandshakeInterceptor.PROOF_ATTRIBUTE,
                        new AgentRuntimeAuthenticationService.Proof(proof.scope(), proof.installationId(),
                                proof.hostId(), proof.sessionGeneration() + 1, proof.tokenDigest(),
                                proof.userId(), proof.authEpoch()));
            } else authorityCurrent.set(false);
            releaseEvent.countDown();
            event.get(5, TimeUnit.SECONDS);
            var result = dispatch.get(5, TimeUnit.SECONDS);
            assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE, result.status());
            assertEquals(0, result.matchingSessionCount()); assertEquals(0, result.sentSessionCount());
            var frames = ArgumentCaptor.forClass(TextMessage.class);
            verify(a).sendMessage(frames.capture()); // Only the already-authorized ordinary event was sent.
            assertEquals("pong", new ObjectMapper().readTree(frames.getValue().getPayload()).path("type").asText());
        } finally {
            releaseEvent.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Gate regression workers did not terminate");
        }
    }
    // Observe real contention without acquiring, replacing or otherwise changing the production gate.
    private static ReentrantLock sessionSendLock(AgentWebSocketHandler handler, String sessionId) throws Exception {
        var field = AgentWebSocketHandler.class.getDeclaredField("sessionSendGates"); field.setAccessible(true);
        var gates = (Map<?, ?>) field.get(handler);
        synchronized (gates) {
            var gate = gates.get(sessionId);
            var lock = gate.getClass().getDeclaredField("lock"); lock.setAccessible(true);
            return (ReentrantLock) lock.get(gate);
        }
    }
    @Test void revokedAgentDoesNotDisableSibling() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-b");
        f.register(a, true, true); f.register(b, true, true); f.current.remove("a");
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-b"));
    }
    @Test void undeclaredMediaAdapterDoesNotHideReadyCommandAgentFromActiveProjection() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-a");
        when(f.service.get("agent-a")).thenReturn(runtime);
        assertEquals(List.of(runtime), f.handler.getExecutionReadyAgents());
    }
    @Test void activeProjectionNeverFetchesUnreadyAgentsOrChangesCallerAcl() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, false);
        assertTrue(f.handler.getExecutionReadyAgents().isEmpty());
        verify(f.service, never()).get(any());
    }
    @Test void unknownOrDuplicateCommandTypesRejectRegistrationBeforeRuntimeMutation() throws Exception {
        for (var types : List.of(List.of("NEW_BUSINESS_COMMAND"), List.of("TASK_INVITE", "TASK_INVITE"))) {
            var f = new Fixture(); var a = f.session("a", "agent-a");
            f.handler.afterConnectionEstablished(a);
            f.handler.handleTextMessage(a, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                    "type", "agent.register", "agentId", "agent-a", "runtimeInstanceId", "boot",
                    "durableStateHealthy", true, "readyCommandTypes", types))));
            verify(f.service, never()).register(any());
            assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        }
    }
    @Test void unlistedCommandTypeCannotUseAnotherReadyAdapter() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        clearInvocations(a);
        var raw = new String(f.wire("agent-a"), StandardCharsets.UTF_8).replace("TASK_INVITE", "WORK_ITEM_EXECUTE").getBytes(StandardCharsets.UTF_8);
        assertEquals(AgentRawCommandDispatchResult.Status.OFFLINE,
                f.handler.dispatchExactRawCommand("0", "client", "task", "agent-a", raw).status());
        verify(a, never()).sendMessage(any());
    }
    @Test void unhealthyPresenceRetiresReadinessWithoutClosingSiblingChannel() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); var b = f.session("b", "agent-b");
        f.register(a, true, true); f.register(b, true, true);
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-a"); runtime.setStatus("online");
        when(f.service.updateStatus(eq("agent-a"), any())).thenReturn(runtime);
        f.handler.handleTextMessage(a, new TextMessage("{\"schemaVersion\":1,\"messageType\":\"agent.presence\",\"messageId\":\"health\","
                + "\"agentId\":\"agent-a\",\"runtimeInstanceId\":\"boot\",\"status\":\"online\",\"durableStateHealthy\":false}"));
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-b"));
    }
    @Test void replacedSocketCannotMutatePresenceButCurrentSocketStillCan() throws Exception {
        var f = new Fixture(); var old = f.session("old", "agent-a"); var current = f.session("new", "agent-a");
        f.register(old, true, true); f.register(current, true, true); f.current.remove("old");
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-a"); runtime.setStatus("online");
        when(f.service.updateStatus(eq("agent-a"), any())).thenReturn(runtime);
        var presence = new TextMessage("{\"type\":\"agent.presence\",\"agentId\":\"agent-a\","
                + "\"runtimeInstanceId\":\"boot\",\"status\":\"online\"}");

        f.handler.handleTextMessage(old, presence);
        verify(f.service, never()).updateStatus(any(), any());
        f.handler.handleTextMessage(current, presence);
        verify(f.service).updateStatus(eq("agent-a"), any());
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-a"));
    }
    @Test void queueHealthLossDoesNotRejectExistingTerminalTaskReport() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, false, true);
        var task = new AgentTaskDTO(); task.setId("original-task"); task.setStatus("completed");
        when(f.service.reportTask(eq("original-task"), any())).thenReturn(task);

        f.handler.handleTextMessage(a, new TextMessage("{\"type\":\"task.report\",\"agentId\":\"agent-a\","
                + "\"runtimeInstanceId\":\"boot\",\"taskId\":\"original-task\",\"status\":\"completed\"}"));

        verify(f.service).reportTask(eq("original-task"), any());
        assertFalse(f.handler.isExactAgentConnected("0", "client", "agent-a"));
    }
    @Test void staleFencedBusinessCallbackNeverMutatesEvenIfChannelWasSelected() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        var proof = UnifiedRuntimeTestSupport.install(a);
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-a"));
        doThrow(new IllegalArgumentException("stale generation"))
                .when(f.auth).withFence(eq(proof), eq(false), any());

        f.handler.handleTextMessage(a, new TextMessage("{\"type\":\"agent.presence\",\"agentId\":\"agent-a\","
                + "\"runtimeInstanceId\":\"boot\",\"status\":\"online\"}"));

        verify(f.auth).withFence(eq(proof), eq(false), any());
        verify(f.service, never()).updateStatus(any(), any());
        ArgumentCaptor<TextMessage> rejected = ArgumentCaptor.forClass(TextMessage.class);
        verify(a, atLeastOnce()).sendMessage(rejected.capture());
        assertTrue(rejected.getAllValues().stream().map(TextMessage::getPayload)
                .anyMatch(wire -> wire.contains("stale generation")));

        // The targeted stale generation must not disable a current sibling's callbacks.
        var b = f.session("b", "agent-b"); f.register(b, true, true);
        var runtime = new AgentRuntimeDTO(); runtime.setAgentId("agent-b"); runtime.setStatus("online");
        when(f.service.updateStatus(eq("agent-b"), any())).thenReturn(runtime);
        f.handler.handleTextMessage(b, new TextMessage("{\"type\":\"agent.presence\",\"agentId\":\"agent-b\","
                + "\"runtimeInstanceId\":\"boot\",\"status\":\"online\"}"));
        verify(f.service).updateStatus(eq("agent-b"), any());
        verify(f.service, never()).updateStatus(eq("agent-a"), any());
        assertTrue(f.handler.isExactAgentConnected("0", "client", "agent-b"));
    }
    @Test void cancellationMustKeepOriginalConversationAndRetainHandleUntilExactMatch() throws Exception {
        var f = new Fixture(); var a = f.session("a", "agent-a"); f.register(a, true, true);
        var disposable = trackStream(f.handler, "a", "original-request", "original-conversation");
        f.stop(a, "original-request", "wrong-conversation");
        verify(disposable, never()).dispose();
        f.stop(a, "original-request", "original-conversation");
        verify(disposable).dispose();
        f.stop(a, "original-request", "original-conversation");
        verify(disposable, times(1)).dispose();
    }
    @Test void replacementSocketAndUnboundCancellationCannotDisposeOriginalHandle() throws Exception {
        var f = new Fixture(); var old = f.session("old", "agent-a"); var current = f.session("new", "agent-a");
        f.register(old, true, true); f.register(current, true, true); f.current.remove("old");
        var disposable = trackStream(f.handler, "old", "original-request", "original-conversation");
        f.stop(old, "original-request", "original-conversation");
        f.stop(current, "original-request", "original-conversation");
        f.handler.handleTextMessage(old, new TextMessage("{\"type\":\"chat.stop\",\"requestId\":\"original-request\"}"));
        verify(disposable, never()).dispose();
    }
    // Seed only the existing private stream recovery handle; no network/provider execution.
    @SuppressWarnings("unchecked")
    private static reactor.core.Disposable trackStream(AgentWebSocketHandler handler, String sessionId,
            String requestId, String conversationId) throws Exception {
        var disposable = mock(reactor.core.Disposable.class);
        var type = Class.forName(AgentWebSocketHandler.class.getName() + "$StreamState");
        var constructor = type.getDeclaredConstructor(String.class, String.class, String.class, reactor.core.Disposable.class);
        constructor.setAccessible(true);
        var field = AgentWebSocketHandler.class.getDeclaredField("runningStreams"); field.setAccessible(true);
        var streams = (Map<String, Object>) field.get(handler);
        streams.put(sessionId + ":" + requestId, constructor.newInstance(sessionId, requestId, conversationId, disposable));
        return disposable;
    }

    private static class Fixture {
        final AgentService service = mock(AgentService.class);
        final AgentRuntimeAuthenticationService auth = mock(AgentRuntimeAuthenticationService.class);
        final Set<String> current = new HashSet<>();
        final AgentWebSocketHandler handler;
        @SuppressWarnings("unchecked") Fixture() {
            ObjectProvider<AgentService> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(service);
            when(service.register(any())).thenAnswer(inv -> new AgentRegisterResultDTO(
                    ((AgentRegisterDTO) inv.getArgument(0)).getAgentId(), "fixture-token", "online"));
            when(auth.isCurrentBinding(any(), any(), any(), any(), any(), any())).thenAnswer(inv -> current.contains(inv.getArgument(0)));
            handler = new AgentWebSocketHandler(mock(ChatClient.class), provider, mock(ChatMessageDao.class), mock(ChatConversationEventBroker.class));
            UnifiedRuntimeTestSupport.stub(auth);
            handler.setRuntimeAuthentication(auth);
        }
        WebSocketSession session(String id, String agent) {
            var s = mock(WebSocketSession.class);
            when(s.getId()).thenReturn(id); when(s.isOpen()).thenReturn(true);
            when(s.getAttributes()).thenReturn(new HashMap<>(Map.of("tenantId", "0", "clientId", "client", "jiacn", "owner", "agentId", agent, "runtimeInstanceId", "boot")));
            UnifiedRuntimeTestSupport.install(s);
            return s;
        }
        void register(WebSocketSession s, boolean durable, boolean execute) throws Exception {
            register(s, durable, execute ? List.of("TASK_INVITE") : List.of());
        }
        void register(WebSocketSession s, boolean durable, List<String> commandTypes) throws Exception {
            current.add(s.getId()); handler.afterConnectionEstablished(s);
            var payload = new HashMap<String, Object>(); payload.put("type", "agent.register"); payload.put("agentId", s.getAttributes().get("agentId"));
            UnifiedRuntimeTestSupport.registrationProof(s, payload);
            payload.put("runtimeInstanceId", "boot"); payload.put("durableStateHealthy", durable); payload.put("readyCommandTypes", commandTypes);
            handler.handleTextMessage(s, new TextMessage(new ObjectMapper().writeValueAsString(payload)));
        }
        void stop(WebSocketSession session, String requestId, String conversationId) throws Exception {
            handler.handleTextMessage(session, new TextMessage(new ObjectMapper().writeValueAsString(Map.of(
                    "type", "chat.stop", "requestId", requestId, "conversationId", conversationId))));
        }
        AgentRawCommandDispatchResult dispatch(String agent) { return handler.dispatchExactRawCommand("0", "client", "task", agent, wire(agent)); }
        byte[] wire(String agent) {
            return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\",\"messageId\":\"original-message\",\"commandId\":\"original-command\","
                    + "\"tenantId\":\"0\",\"clientId\":\"client\",\"taskId\":\"task\",\"targetAgentId\":\"" + agent + "\","
                    + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,\"expiresAt\":2000000,\"payload\":{\"instruction\":\"fixture\"}}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
