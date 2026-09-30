package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyAssetProjectorTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ChatDeliberationService requests = mock(ChatDeliberationService.class);
    private final ChatDeliberationDao events = mock(ChatDeliberationDao.class);
    private final ChatConversationDao conversations = mock(ChatConversationDao.class);
    private final ChatMessageDao messages = mock(ChatMessageDao.class);
    private final PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
    private final ChatConversationEventBroker broker = mock(ChatConversationEventBroker.class);
    private final ChatBountyAssetProjector projector = new ChatBountyAssetProjector(jdbc, requests,
            events, conversations, messages, executions, broker);
    private final ChatBountyAssetProjector.Candidate candidate =
            new ChatBountyAssetProjector.Candidate("0", "owner", "client", "req", "step", "exec");
    private final PersonalWorkspaceExecutionService.OwnerScope scope =
            new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "owner");

    private void ready() {
        when(requests.getRequest("0", "owner", "client", "req"))
                .thenReturn(new ChatDeliberationService.RequestView("req", "1", "42", "1", "7",
                        "RUNNING", "1", List.of(), List.of(new ChatDeliberationService.StepView(
                        "step", "1", "task", "3", "agent", "EXECUTE", "RUNNING", "2", "intent", "exec", "RUNNING"))));
        when(executions.get(scope, "exec")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                "exec", "task", "run", "42", "agent", "OUTPUT_COMMITTED", null, null, 1,
                "image/png", List.of(), null, "CONVERSATION", null, null, null));
        when(executions.listConversationOutputs(scope, "task", "run")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("exec", "output_1",
                        "image/png", "a".repeat(64), 20)));
        var live = new ChatConversationEntity().setId(42L).setJiacn("owner")
                .setLifecycleGeneration(1L).setTaskId("task")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task");
        live.setTenantId("0"); live.setClientId("client");
        when(conversations.lockScopedById("owner", "client", "42")).thenReturn(live);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(messages.insertScoped(eq("0"), eq("client"), any())).thenAnswer(invocation -> {
            ChatMessageEntity message = invocation.getArgument(2); message.setId(100L); return 1;
        });
        when(events.insertEvent(any())).thenAnswer(invocation -> {
            cn.jia.chat.deliberation.ChatConversationEventEntity event = invocation.getArgument(0);
            event.setEventSequence(9L); return 1;
        });
        when(events.assignEventVersion(9L)).thenReturn(1);
        when(events.findRequest("0", "owner", "client", "req"))
                .thenReturn(new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                        .setClientId("client").setRequestId("req").setConversationId("42")
                        .setAggregateState("RUNNING").setStateVersion(1L));
        when(events.updateRequestState(any(), eq("OUTPUT_COMMITTED"), anyLong())).thenReturn(1);
    }

    @Test void onlyCommittedOwnerLinkedOutputsProduceDurableMessageAssetAndEvent() {
        ready();
        assertEquals(1, projector.project(candidate));
        var ordered = inOrder(executions, conversations);
        ordered.verify(executions).get(scope, "exec");
        ordered.verify(executions).listConversationOutputs(scope, "task", "run");
        ordered.verify(conversations).lockScopedById("owner", "client", "42");
        verify(messages).insertScoped(eq("0"), eq("client"), argThat(message ->
                "42".equals(message.getConversationId()) && "agent".equals(message.getSenderType())));
        verify(events).insertEvent(argThat(event -> event.getPayloadJson().contains("ast_")
                && event.getPayloadJson().contains("image/png") && event.getPayloadJson().contains("ready")));
        verify(jdbc).update(contains("INSERT INTO chat_conversation_asset"), any(Object[].class));
        verify(jdbc).update(contains("state_version=?"), any(Object[].class));
        verifyNoInteractions(broker); // No signal before a real transaction commits.
    }

    @Test void repeatProjectionReadsCurrentAssetAndDoesNotCreateAnotherMessageOrEvent() throws Exception {
        ready();
        String source = "0\nowner\nclient\nstep\noutput_1";
        String suffix = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 32);
        var existing = new ChatBountyAssetProjector.Asset("ast_" + suffix, "0", "owner", "client",
                "42", 1, "req", "step", "exec", "run", "output_1", "100", "part_" + suffix,
                "image", "image/png", "a".repeat(64), 20, 1);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenReturn(List.of(existing));
        assertEquals(0, projector.project(candidate));
        verify(jdbc).query(contains("FOR UPDATE"), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class));
        verifyNoInteractions(messages, events, broker);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void publicationIsRegisteredOnlyForAfterCommit() {
        ready(); TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(1, projector.project(candidate));
            verifyNoInteractions(broker);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().getFirst().afterCommit();
            verify(broker).publishIfSubscribed(eq("42"), eq(1L), any(), argThat(frame ->
                    "9".equals(frame.get("eventSequence")) && "agent_message".equals(frame.get("type"))));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void staleConversationAndUncommittedExecutionNeverInsertAnAsset() {
        ready();
        when(conversations.lockScopedById("owner", "client", "42"))
                .thenReturn(new ChatConversationEntity().setLifecycleGeneration(2L));
        assertEquals(0, projector.project(candidate));
        verifyNoInteractions(messages, events, broker);
        reset(executions);
        assertEquals(0, projector.project(candidate));
        verifyNoInteractions(messages, events, broker);
    }

    @Test void malformedCommittedCatalogueIsRejectedBeforeAnyVisibleOutput() {
        ready();
        when(executions.listConversationOutputs(scope, "task", "run")).thenReturn(List.of(
                new PersonalWorkspaceExecutionService.ConversationOutputInfo("other-exec", "out",
                        "image/png", "a".repeat(64), 20)));
        assertThrows(IllegalStateException.class, () -> projector.project(candidate));
        verifyNoInteractions(messages, events, broker);
    }

    @Test void readsAndInvalidScanDoNotDispatchGenerateOrCreateMessages() {
        assertThrows(IllegalArgumentException.class, () -> projector.pending(null, 0));
        assertThrows(IllegalArgumentException.class, () -> projector.pending(null, 129));
        assertNull(projector.find(scope, "../other", "asset"));
        assertTrue(projector.partsFor(scope, "../other").isEmpty());
        verifyNoInteractions(requests, executions, messages, events, broker, jdbc);
    }
    @Test void outputLookupRequiresExactSourceRelationAndCannotPublishOrGenerate() {
        var asset = new ChatBountyAssetProjector.Asset("ast_1", "0", "owner", "client", "42", 1,
                "req", "step", "exec", "run", "output_1", "100", "part_1", "image", "image/png",
                "a".repeat(64), 20, 1);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object[].class)))
                .thenReturn(List.of(asset));
        assertEquals(asset, projector.findOutput(scope, "42", "req", "step", "exec", "run", "output_1"));
        assertNull(projector.findOutput(scope, "42", "other-request", "step", "exec", "run", "output_1"));
        assertNull(projector.findOutput(scope, "42", "req", "step", "exec", "other-run", "output_1"));
        assertNull(projector.findOutput(new PersonalWorkspaceExecutionService.OwnerScope("0", "client", "foreign"),
                "42", "req", "step", "exec", "run", "output_1"));
        assertNull(projector.findOutput(scope, "42", "req", "../step", "exec", "run", "output_1"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verifyNoInteractions(requests, executions, messages, events, broker, conversations);
    }

}
