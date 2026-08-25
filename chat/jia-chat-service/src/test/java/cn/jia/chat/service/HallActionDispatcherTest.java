package cn.jia.chat.service;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandMailboxEntry;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.service.AgentCommandMailboxAccessDeniedException;
import cn.jia.agent.service.AgentCommandMailboxService;
import cn.jia.agent.service.AgentCommandShadowIntentException;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
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
import org.springframework.dao.DataAccessResourceFailureException;

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
        verify(writer, never()).writeAuthorizedHall(any(), any());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
        assertTrue(dispatcher.mailbox("agent-target").isEmpty());
    }

    @Test
    void canaryOnlineAndOfflineTargetsBothUseOneDurableFactSourceOnly() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.writeAuthorizedHall(any(), any())).thenAnswer(invocation -> {
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
        verify(writer, org.mockito.Mockito.times(2)).writeAuthorizedHall(
                any(), eq("caller-agent"));
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
        assertTrue(dispatcher.mailbox("agent-online").isEmpty());
        assertTrue(dispatcher.mailbox("agent-offline").isEmpty());
    }

    @Test
    void taskBriefingEndpointShapeDurablyDispatchesIntentScopedTaskInvite() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.writeAuthorizedHall(any(), eq("caller-agent"))).thenAnswer(invocation -> {
            AgentCommandDraft draft = invocation.getArgument(0);
            return new AgentCommandTransportWriteResult(
                    61L, draft.commandId(), "message-briefing", "event-briefing", false);
        });
        HallActionIntent intent = durableIntent(
                "intent-briefing", "agent-target", "task-1");
        intent.setActionType("task_briefing");
        intent.setInstruction("请阅读任务简报并确认职责");

        HallActionDispatchResult result = dispatcher.dispatch(
                intent, new HallTrustedCaller("tenant-a", "client-a", "caller-agent"));

        assertEquals(HallActionDispatcher.STATUS_ACCEPTED, result.getStatus());
        ArgumentCaptor<AgentCommandDraft> draft =
                ArgumentCaptor.forClass(AgentCommandDraft.class);
        verify(writer).writeAuthorizedHall(draft.capture(), eq("caller-agent"));
        assertEquals(AgentProtocolConstants.COMMAND_TASK_INVITE,
                draft.getValue().commandType());
        assertEquals("intent-briefing", draft.getValue().intentId());
        assertTrue(draft.getValue().commandId().startsWith("cmd_hall_action_"));
        AgentHallCommandPayload payload = assertInstanceOf(
                AgentHallCommandPayload.class, draft.getValue().payload());
        assertEquals("task_briefing", payload.actionType());
        assertEquals("assist", payload.autonomyLevel());
        assertEquals(Boolean.FALSE, payload.requiresApproval());
        String wire = new String(AgentCommandCanonicalCodec.wireBytes(
                draft.getValue(), "message-briefing"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"type\":\"agent_direct_message\""));
        assertTrue(wire.contains("\"targetAgentId\":\"agent-target\""));
        assertTrue(wire.contains("\"agentId\":\"agent-target\""));
        assertTrue(wire.contains("\"actionType\":\"task_briefing\""));
        assertTrue(wire.contains("\"content\":\"请阅读任务简报并确认职责\""));
        assertTrue(wire.contains("\"autonomyLevel\":\"assist\""));
        assertTrue(wire.contains("\"metadata\":{"));
        verify(agentWebSocketHandler, never()).sendDirectMessageToAgent(any(), any(Map.class));
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
    void transactionalWriterDenialProducesNoAcceptedClaimOrLegacyIo() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.writeAuthorizedHall(any(), eq("caller-agent")))
                .thenThrow(new IllegalArgumentException("authorization revoked"));

        HallActionDispatchResult denied = dispatcher.dispatch(
                durableIntent("intent-denied", "agent-target", "task-1"), caller);

        assertEquals(HallActionDispatcher.STATUS_FAILED, denied.getStatus());
        verify(writer).writeAuthorizedHall(any(), eq("caller-agent"));
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(agentWebSocketHandler, never()).isAgentConnected(any(), any(), any());
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
        verify(writer, never()).writeAuthorizedHall(any(), any());
    }

    @Test
    void dbShadowCapturesTerminalDurableRowThenUsesLegacyWithoutDurableDeliveryClaim() {
        HallActionDispatcher dispatcher = dispatcher(AgentRabbitActivationState.DB_SHADOW, false);
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.writeAuthorizedHall(any(), any())).thenAnswer(invocation -> {
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
        verify(writer).writeAuthorizedHall(any(), eq("caller-agent"));
        verify(agentService, never()).requireApiKeyOwnedAgent(any(), any(), any());
        verify(accessService, never()).resolveMemberAccess(any(), any(), any(), any());
        verify(agentWebSocketHandler).sendDirectMessageToAgent(
                eq("agent-target"), any(Map.class));
    }

    @Test
    void dbShadowLegacySideEffectIsNotRepeatedOrClaimedAfterCanaryCutover() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(writer.writeAuthorizedHall(any(), eq("caller-agent")))
                .thenReturn(new AgentCommandTransportWriteResult(
                        71L, "cmd-shadow", "message-shadow", "event-shadow", false))
                .thenThrow(new AgentCommandShadowIntentException(
                        "DB_SHADOW intent is capture-only; submit a new intent for canary dispatch"));
        when(agentWebSocketHandler.isAgentConnected(
                "tenant-a", "client-a", "agent-target")).thenReturn(true);
        when(agentWebSocketHandler.sendDirectMessageToAgent(
                eq("agent-target"), any(Map.class))).thenReturn(true);
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");
        HallActionIntent shadow = durableIntent("intent-shadow-cutover", "agent-target", "task-1");

        HallActionDispatchResult legacy = dispatcher(
                AgentRabbitActivationState.DB_SHADOW, false).dispatch(shadow, caller);
        HallActionIntent retry = durableIntent(
                "intent-shadow-cutover", "agent-target", "task-1");
        HallActionDispatchResult canary = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true).dispatch(retry, caller);

        assertEquals(HallActionDispatcher.STATUS_DISPATCHED, legacy.getStatus());
        assertEquals(HallActionDispatcher.STATUS_FAILED, canary.getStatus());
        assertTrue(canary.getMessage().contains("new intent"));
        verify(writer, org.mockito.Mockito.times(2)).writeAuthorizedHall(
                any(), eq("caller-agent"));
        verify(agentWebSocketHandler, org.mockito.Mockito.times(1))
                .sendDirectMessageToAgent(eq("agent-target"), any(Map.class));
    }

    @Test
    void durableMailboxUsesTrustedProjectionAndOpaqueStableCursor() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
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

    @Test
    void durableMailboxMapsExplicitForbiddenIdentityToEmpty() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(mailboxProvider.getIfAvailable()).thenReturn(durableMailbox);
        when(durableMailbox.query(any(), any(), any(), any(), any(),
                any(), any(), eq(50), eq(false)))
                .thenThrow(new AgentCommandMailboxAccessDeniedException());

        HallDurableMailboxPage page = assertInstanceOf(HallDurableMailboxPage.class,
                dispatcher.mailbox("agent-target", "task-1", null, 50, false,
                        new HallTrustedCaller(
                                "tenant-a", "client-a", "caller-agent")));

        assertTrue(page.items().isEmpty());
        verify(durableMailbox).query(any(), any(), any(), any(), any(),
                any(), any(), eq(50), eq(false));
    }

    @Test
    void durableMailboxAclInfrastructureFailureIsObservable() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(mailboxProvider.getIfAvailable()).thenReturn(durableMailbox);
        when(durableMailbox.query(any(), any(), any(), any(), any(),
                any(), any(), eq(50), eq(false)))
                .thenThrow(new DataAccessResourceFailureException(
                        "membership database unavailable"));

        DataAccessResourceFailureException failure = assertThrows(
                DataAccessResourceFailureException.class, () ->
                        dispatcher.mailbox("agent-target", "task-1", null, 50, false,
                                new HallTrustedCaller(
                                        "tenant-a", "client-a", "caller-agent")));

        assertEquals("membership database unavailable", failure.getMessage());
        verify(durableMailbox).query(any(), any(), any(), any(), any(),
                any(), any(), eq(50), eq(false));
    }

    @Test
    void malformedMailboxTargetTaskCursorAndLimitRemainExplicitErrors() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        HallTrustedCaller caller = new HallTrustedCaller(
                "tenant-a", "client-a", "caller-agent");

        assertThrows(IllegalArgumentException.class, () -> dispatcher.mailbox(
                " agent-target", "task-1", null, 50, false, caller));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.mailbox(
                "agent-target", "task-1 ", null, 50, false, caller));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.mailbox(
                "agent-target", "task-1", "not-a-cursor", 50, false, caller));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.mailbox(
                "agent-target", "task-1", null, 0, false, caller));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.mailbox(
                "agent-target", "task-1", null, 101, false, caller));
        verify(mailboxProvider, never()).getIfAvailable();
    }

    @Test
    void durableMailboxProviderFailureIsObservable() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(mailboxProvider.getIfAvailable())
                .thenThrow(new IllegalStateException("provider infrastructure unavailable"));

        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                dispatcher.mailbox("agent-target", "task-1", null, 50, false,
                        new HallTrustedCaller(
                                "tenant-a", "client-a", "caller-agent")));

        assertEquals("provider infrastructure unavailable", failure.getMessage());
    }

    @Test
    void durableMailboxSqlFailureIsObservable() {
        HallActionDispatcher dispatcher = dispatcher(
                AgentRabbitActivationState.DISPATCH_CANARY, true);
        when(mailboxProvider.getIfAvailable()).thenReturn(durableMailbox);
        when(durableMailbox.query(any(), any(), any(), any(), any(),
                any(), any(), eq(50), eq(false)))
                .thenThrow(new DataAccessResourceFailureException("mysql unavailable"));

        DataAccessResourceFailureException failure = assertThrows(
                DataAccessResourceFailureException.class, () ->
                        dispatcher.mailbox("agent-target", "task-1", null, 50, false,
                                new HallTrustedCaller(
                                        "tenant-a", "client-a", "caller-agent")));

        assertEquals("mysql unavailable", failure.getMessage());
    }

    private HallActionDispatcher dispatcher(
            AgentRabbitActivationState state, boolean scopeAllowed) {
        return new HallActionDispatcher(
                agentWebSocketHandler, gate(state, scopeAllowed), writerProvider,
                mailboxProvider, agentService, accessService, () -> 1_000L);
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
