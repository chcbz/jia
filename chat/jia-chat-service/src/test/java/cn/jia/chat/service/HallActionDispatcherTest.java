package cn.jia.chat.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandMailboxEntry;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.handler.AgentProtocolMessageNormalizer;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HallActionDispatcherTest extends BaseMockTest {
    @Mock
    AgentWebSocketHandler agentWebSocketHandler;
    @Mock
    ObjectProvider<AgentCommandTransportWriter> writerProvider;
    @Mock
    ObjectProvider<AgentCommandMailboxService> mailboxProvider;
    @Mock
    AgentCommandTransportWriter writer;
    @Mock
    AgentCommandMailboxService durableMailbox;
    @Mock
    AgentService agentService;
    @Mock
    AgentTaskCollaborationAccessService accessService;

    @AfterEach
    void tearDown() {
        EsContextHolder.setContext(new EsContext());
    }

    @Test
    void dispatchesOnlineIntentToAgentDirectMessageWhenM3IsNotConstructed() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-linchong"))
                .thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-linchong"), any(Map.class)))
                .thenReturn(true);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);
        HallActionIntent intent = legacyIntent("intent-1", "agent-linchong", "task-1");
        intent.setTargetAgentIds(List.of("agent-wuyong"));

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("agent-linchong"), payloadCaptor.capture());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("dispatched", result.getStatus());
        assertEquals(AgentProtocolConstants.LEGACY_AGENT_ACTION, payload.get("type"));
        assertEquals(AgentProtocolConstants.VERSION_1, payload.get("schemaVersion"));
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, payload.get("messageType"));
        assertEquals("intent-1", payload.get("commandId"));
        assertEquals(AgentProtocolConstants.COMMAND_REQUEST_RESPOND, payload.get("commandType"));
        assertEquals("agent-linchong", payload.get("targetAgentId"));
        assertEquals("tenant-a", payload.get("tenantId"));
        assertEquals("client-a", payload.get("clientId"));
        assertEquals("task-1", payload.get("taskId"));
        assertEquals(payload.get("messageId"), payload.get("requestId"));
        AgentProtocolMessageNormalizer.NormalizedMessage normalized =
                new AgentProtocolMessageNormalizer().normalizeInbound(payload);
        assertEquals(AgentProtocolConstants.TYPE_COMMAND_DISPATCH, normalized.canonicalType());
        assertTrue(((Map<?, ?>) payload.get("metadata")).containsKey("reason"));
    }

    @Test
    void queuesOfflineIntentInLegacyMailboxWhenM3IsNotConstructed() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-linchong"))
                .thenReturn(false);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);
        HallActionIntent intent = legacyIntent("intent-2", "agent-linchong", "task-2");
        intent.setActionType("request_report");

        HallActionDispatchResult result = dispatcher.dispatch(intent);

        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(
                eq("agent-linchong"), any(Map.class));
        assertEquals("queued", result.getStatus());
        assertEquals(1, dispatcher.mailbox("agent-linchong").size());
        assertEquals("intent-2", dispatcher.mailbox("agent-linchong").getFirst().getIntentId());
    }

    @Test
    void legacyMailboxReturnsImmutablePointInTimeSnapshots() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected(
                "tenant-a", "client-a", "agent-linchong")).thenReturn(false);
        HallActionDispatcher dispatcher = new HallActionDispatcher(agentWebSocketHandler);

        dispatcher.dispatch(legacyIntent("intent-snapshot-1", "agent-linchong", "task-1"));
        List<HallAgentMailboxItem> first = dispatcher.mailbox("agent-linchong");
        dispatcher.dispatch(legacyIntent("intent-snapshot-2", "agent-linchong", "task-1"));

        assertEquals(1, first.size());
        assertEquals(2, dispatcher.mailbox("agent-linchong").size());
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void flagsOffPreservesM1AndNeverResolvesM3ProvidersOrAcl() {
        setScope("tenant-a", "client-a");
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-target"))
                .thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-target"), any(Map.class)))
                .thenReturn(true);
        HallActionDispatcher dispatcher = dispatcher(AgentRabbitActivationState.OFF, true);

        HallActionDispatchResult result = dispatcher.dispatch(
                legacyIntent("intent-off", "agent-target", "task-1"), null);

        assertEquals(HallActionDispatcher.STATUS_DISPATCHED, result.getStatus());
        verify(writerProvider, never()).getIfAvailable();
        verify(mailboxProvider, never()).getIfAvailable();
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
    }

    @Test
    void trustedOutsideCanaryScopeUsesOnlyApprovedM1CompatibilityScope() {
        setScope("tenant-stale", "client-stale");
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, false);
        when(agentWebSocketHandler.isAgentConnected(
                "tenant-a", "client-a", "agent-target")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("agent-target"), any(Map.class))).thenReturn(true);

        HallActionDispatchResult result = dispatcher.dispatch(
                legacyIntent("intent-outside", "agent-target", "task-1"),
                new HallTrustedCaller("tenant-a", "client-a", null));

        assertEquals(HallActionDispatcher.STATUS_DISPATCHED, result.getStatus());
        verify(agentWebSocketHandler).isAgentConnected(
                "tenant-a", "client-a", "agent-target");
        verify(writerProvider, never()).getIfAvailable();
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
    }

    @Test
    void canaryWithoutTrustedCallerFailsClosedEvenWhenEsContextLooksAllowlisted() {
        setScope("tenant-a", "client-a");
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallActionIntent intent = durableIntent("intent-no-caller", "agent-target", "task-1");

        HallActionDispatchResult result = dispatcher.dispatch(intent, null);

        assertEquals(HallActionDispatcher.STATUS_FAILED, result.getStatus());
        verify(writerProvider, never()).getIfAvailable();
        verify(writer, never()).write(any());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
        assertTrue(dispatcher.mailbox("agent-target").isEmpty());
    }

    @Test
    void canaryOnlineAndOfflineTargetsBothUseOneDurableFactSourceOnly() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        allowOwned("caller-agent", AgentConstants.STATUS_ONLINE);
        allowOwned("agent-online", AgentConstants.STATUS_ONLINE);
        allowOwned("agent-offline", AgentConstants.STATUS_OFFLINE);
        when(accessService.resolveMemberAccess(any(), any(), any(), any()))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.write(any())).thenAnswer(invocation -> {
            AgentCommandDraft draft = invocation.getArgument(0);
            return new AgentCommandTransportWriteResult(
                    41L, draft.commandId(), "message-1", "event-1", false);
        });
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");

        HallActionDispatchResult online = dispatcher.dispatch(
                durableIntent("intent-online", "agent-online", "task-1"), caller);
        HallActionDispatchResult offline = dispatcher.dispatch(
                durableIntent("intent-offline", "agent-offline", "task-1"), caller);

        assertEquals(HallActionDispatcher.STATUS_ACCEPTED, online.getStatus());
        assertEquals(HallActionDispatcher.STATUS_ACCEPTED, offline.getStatus());
        verify(writer, org.mockito.Mockito.times(2)).write(any());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
        assertTrue(dispatcher.mailbox("agent-online").isEmpty());
        assertTrue(dispatcher.mailbox("agent-offline").isEmpty());
    }

    @Test
    void forgedBodyActorCannotReplaceMissingTrustedCaller() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallActionIntent intent = durableIntent("intent-forged", "owned-target", "task-1");

        HallActionDispatchResult result = dispatcher.dispatch(intent, null);

        assertEquals(HallActionDispatcher.STATUS_FAILED, result.getStatus());
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(writerProvider, never()).getIfAvailable();
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    @Test
    void callerTaskDenialAndTargetIdentityFailureProduceZeroWritesAndZeroLegacyIo() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");
        allowOwned("caller-agent", AgentConstants.STATUS_ONLINE);
        when(accessService.resolveMemberAccess(
                "tenant-a", "client-a", "task-1", "caller-agent"))
                .thenReturn(AgentTaskAccessLevel.READ_ONLY);

        HallActionDispatchResult callerDenied = dispatcher.dispatch(
                durableIntent("intent-denied", "agent-target", "task-1"), caller);

        assertEquals(HallActionDispatcher.STATUS_FAILED, callerDenied.getStatus());
        verify(agentService, never()).requireApiKeyOwnedAgent(
                "client-a", "tenant-a", "agent-target");
        verify(writerProvider, never()).getIfAvailable();
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());

        HallActionDispatcher targetDispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        allowOwned("caller-agent", AgentConstants.STATUS_ONLINE);
        when(accessService.resolveMemberAccess(
                "tenant-a", "client-a", "task-1", "caller-agent"))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(agentService.requireApiKeyOwnedAgent(
                "client-a", "tenant-a", "agent-target"))
                .thenThrow(new IllegalArgumentException("cross-scope target"));

        HallActionDispatchResult targetDenied = targetDispatcher.dispatch(
                durableIntent("intent-target-denied", "agent-target", "task-1"), caller);

        assertEquals(HallActionDispatcher.STATUS_FAILED, targetDenied.getStatus());
        verify(writer, never()).write(any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
    }

    @Test
    void untrustedIdentityListAndNestedContextAreRejectedBeforeAclAndWrite() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");
        HallActionIntent identities = durableIntent("intent-identities", "agent-target", "task-1");
        identities.setTargetAgentIds(List.of("agent-other"));
        HallActionIntent nested = durableIntent("intent-nested", "agent-target", "task-1");
        nested.setContext(Map.of("taskTitle", Map.of("nested", "forbidden")));

        assertEquals(HallActionDispatcher.STATUS_FAILED,
                dispatcher.dispatch(identities, caller).getStatus());
        assertEquals(HallActionDispatcher.STATUS_FAILED,
                dispatcher.dispatch(nested, caller).getStatus());
        verify(writerProvider, never()).getIfAvailable();
        verify(writer, never()).write(any());
    }

    @Test
    void dbShadowCapturesTerminalDurableRowThenUsesLegacyWithoutDurableDeliveryClaim() {
        HallActionDispatcher dispatcher = dispatcher(AgentRabbitActivationState.DB_SHADOW, false);
        allowAcl("caller-agent", "agent-target", "task-1");
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.write(any())).thenAnswer(invocation -> {
            AgentCommandDraft draft = invocation.getArgument(0);
            return new AgentCommandTransportWriteResult(
                    51L, draft.commandId(), "message-shadow", "event-shadow", false);
        });
        when(agentWebSocketHandler.isAgentConnected("tenant-a", "client-a", "agent-target"))
                .thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(eq("agent-target"), any(Map.class)))
                .thenReturn(true);

        HallActionDispatchResult result = dispatcher.dispatch(
                durableIntent("intent-shadow", "agent-target", "task-1"),
                new HallTrustedCaller("tenant-a", "client-a", "caller-agent"));

        assertEquals(HallActionDispatcher.STATUS_DISPATCHED, result.getStatus());
        assertEquals("dispatched", result.getMessage());
        assertFalse(result.getMessage().contains("durable"));
        verify(writer).write(any());
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("agent-target"), any(Map.class));
    }

    @Test
    void durableMailboxUsesTrustedProjectionAndOpaqueStableCursor() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        allowAcl("caller-agent", "agent-target", "task-1");
        when(mailboxProvider.getIfAvailable()).thenReturn(durableMailbox);
        AgentCommandMailboxEntry entry = new AgentCommandMailboxEntry(
                "cmd-1", "task-1", "work-1", "agent-target",
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                "WAITING_AGENT", 9_999L, 900L, 950L);
        when(durableMailbox.query(
                "tenant-a", "client-a", "caller-agent", "agent-target", "task-1",
                null, null, 25, false))
                .thenReturn(new AgentCommandMailboxPage(List.of(entry), 900L, 9L));

        Object raw = dispatcher.mailbox(
                "agent-target", "task-1", null, 25, false,
                new HallTrustedCaller("tenant-a", "client-a", "caller-agent"));

        HallDurableMailboxPage page = assertInstanceOf(HallDurableMailboxPage.class, raw);
        assertEquals(1, page.items().size());
        assertEquals("cmd-1", page.items().getFirst().commandId());
        assertEquals("WAITING_AGENT", page.items().getFirst().status());
        assertNotNull(page.nextCursor());
        assertFalse(page.terminalIncluded());
        verify(durableMailbox).query(
                "tenant-a", "client-a", "caller-agent", "agent-target", "task-1",
                null, null, 25, false);
    }

    @Test
    void durableMailboxHidesForbiddenAndMissingScopesWithoutLegacyFallback() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);

        HallDurableMailboxPage missing = assertInstanceOf(HallDurableMailboxPage.class,
                dispatcher.mailbox("agent-target", null, null, 50, false, null));
        HallDurableMailboxPage forged = assertInstanceOf(HallDurableMailboxPage.class,
                dispatcher.mailbox("agent-target", "task-1", null, 50, false,
                        new HallTrustedCaller("tenant-a", "client-a", null)));

        assertTrue(missing.items().isEmpty());
        assertTrue(forged.items().isEmpty());
        verify(mailboxProvider, never()).getIfAvailable();
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
    }

    private HallActionDispatcher dispatcher(
            AgentRabbitActivationState state, boolean scopeAllowed) {
        return new HallActionDispatcher(
                agentWebSocketHandler, gate(state, scopeAllowed), writerProvider,
                mailboxProvider, agentService, accessService, () -> 1_000L);
    }

    private void allowAcl(String callerAgentId, String targetAgentId, String taskId) {
        allowOwned(callerAgentId, AgentConstants.STATUS_ONLINE);
        allowOwned(targetAgentId, AgentConstants.STATUS_OFFLINE);
        when(accessService.resolveMemberAccess(
                "tenant-a", "client-a", taskId, callerAgentId))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
        when(accessService.resolveMemberAccess(
                "tenant-a", "client-a", taskId, targetAgentId))
                .thenReturn(AgentTaskAccessLevel.READ_WRITE);
    }

    private void allowOwned(String agentId, String status) {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(agentId);
        runtime.setStatus(status);
        when(agentService.requireApiKeyOwnedAgent("client-a", "tenant-a", agentId))
                .thenReturn(runtime);
    }

    private HallActionIntent durableIntent(String intentId, String targetAgentId, String taskId) {
        HallActionIntent intent = new HallActionIntent();
        intent.setIntentId(intentId);
        intent.setActionType("request_report");
        intent.setActorAgentId(targetAgentId);
        intent.setConversationId("conversation-1");
        intent.setTaskId(taskId);
        intent.setInstruction("请回报当前进展、风险和下一步计划");
        intent.setContext(Map.of("taskTitle", "接口联调"));
        return intent;
    }

    private HallActionIntent legacyIntent(String intentId, String targetAgentId, String taskId) {
        HallActionIntent intent = durableIntent(intentId, targetAgentId, taskId);
        intent.setActionType("ask_help");
        intent.setInstruction("请向吴用说明阻塞并请求替代方案");
        intent.setReason("接口依赖阻塞");
        return intent;
    }

    private AgentRabbitSafetyGate gate(
            AgentRabbitActivationState state, boolean scopeAllowed) {
        boolean outbox = state != AgentRabbitActivationState.OFF;
        boolean topology = state == AgentRabbitActivationState.MQ_SHADOW
                || state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        boolean dispatch = state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        AgentRabbitSafetyProperties.RabbitBroker broker = topology
                ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated")
                : null;
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(dispatch),
                new AgentRabbitSafetyProperties.RabbitConsume(dispatch),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch), broker);
        AgentRabbitDispatchScopeProperties scopes = dispatch
                ? new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                scopeAllowed ? "tenant-a" : "tenant-other", "client-a")))
                : null;
        return new AgentRabbitSafetyGate(properties, scopes);
    }

    private void setScope(String tenantId, String clientId) {
        EsContext context = new EsContext();
        context.setJiacn(tenantId);
        context.setClientId(clientId);
        EsContextHolder.setContext(context);
    }
}
