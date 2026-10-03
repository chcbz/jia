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

    @Test void confirmedFailureAndRevocationBecomeOneDurableTerminalEventWithoutFakeOutput() {
        for(String executionState:List.of("FAILED","INPUTS_REVOKED")) {
            reset(jdbc, requests, events, conversations, messages, executions, broker);
            ready();
            when(executions.get(scope,"exec")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                    "exec","task","run","42","agent",executionState,"INTERNAL_CODE","private-details",1,
                    "image/png",List.of(),null,"CONVERSATION",null,null,null));
            var row=events.findRequest("0","owner","client","req");
            row.setRequestRevision(1L).setConversationGeneration(1L);
            String expected="INPUTS_REVOKED".equals(executionState)?"CANCELLED":"FAILED";
            when(events.updateRequestState(eq(row),eq(expected),anyLong())).thenReturn(1);
            clearInvocations(messages,events,executions,jdbc);
            assertEquals(1,projector.project(candidate));
            verify(events).insertEvent(argThat(e->"execution_terminal".equals(e.getEventType())
                    && e.getEventId().length()<=64 && e.getPayloadJson().contains(expected)
                    && !e.getPayloadJson().contains("private-details")));
            verify(executions,never()).listConversationOutputs(any(),anyString(),anyString());
            verifyNoInteractions(messages);
            row.setAggregateState(expected).setStateVersion(2L);
            assertEquals(0,projector.project(candidate));
            verify(events,times(1)).insertEvent(any());
        }
    }

    @Test void failureRequestStepEventAndVersionRollbackTogetherAndPublishOnlyAfterCommit() {
        for (String failAt:List.of("step","request","event","version","none")) {
            var f=new ChatBountyAssetProjectorTest(); f.ready();
            var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:terminal_"+java.util.UUID.randomUUID(),"sa","");
            var evidence=new JdbcTemplate(source);
            // Keep connection alive for the transaction and assertions; no production database is used.
            source.setUrl(source.getUrl()+";DB_CLOSE_DELAY=-1");
            evidence.execute("CREATE TABLE writes(label VARCHAR(20) PRIMARY KEY)");
            java.util.function.Function<String,Integer> write=label->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                evidence.update("INSERT INTO writes(label) VALUES(?)",label);
                if(label.equals(failAt))throw new IllegalStateException("injected-"+label);return 1;
            };
            when(f.executions.get(f.scope,"exec")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                    "exec","task","run","42","agent","FAILED",null,null,1,"image/png",List.of(),null,"CONVERSATION",null,null,null));
            f.events.findRequest("0","owner","client","req").setRequestRevision(1L).setConversationGeneration(1L);
            doAnswer(i->write.apply("step")).when(f.jdbc).update(contains("UPDATE chat_interaction_step"),any(Object[].class));
            doAnswer(i->write.apply("request")).when(f.events).updateRequestState(any(),eq("FAILED"),anyLong());
            doAnswer(i->{cn.jia.chat.deliberation.ChatConversationEventEntity event=i.getArgument(0);
                event.setEventSequence(9L);return write.apply("event");}).when(f.events).insertEvent(any());
            doAnswer(i->write.apply("version")).when(f.events).assignEventVersion(9L);
            try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                        "terminal-test", java.util.Map.of("chat.bounty-asset.enabled", "true")));
                context.register(ChatActionExecutionTransactionTest.TxConfig.class);
                context.registerBean("transactionManager",org.springframework.jdbc.datasource.DataSourceTransactionManager.class,
                        ()->new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
                context.registerBean(ChatBountyAssetProjector.class,()->f.projector); context.refresh();
                var actual=context.getBean(ChatBountyAssetProjector.class);
                assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(actual));
                if("none".equals(failAt)) {
                    assertEquals(1,actual.project(f.candidate));
                    assertEquals(4,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                    verify(f.broker).publishIfSubscribed(eq("42"),eq(1L),any(),argThat(frame->"FAILED".equals(frame.get("state"))));
                } else {
                    assertEquals("injected-"+failAt,assertThrows(RuntimeException.class,()->actual.project(f.candidate)).getMessage());
                    assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                    verifyNoInteractions(f.broker);
                }
                verifyNoInteractions(f.messages);
            }
        }
    }

    @Test void unknownProviderOutcomeCannotBecomeAFalseTerminalFailure() {
        ready(); when(executions.get(scope,"exec")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView(
                "exec","task","run","42","agent","OUTPUT_STAGED",null,null,1,
                "image/png",List.of(),null,"CONVERSATION",null,null,null));
        assertEquals(0,projector.project(candidate)); verifyNoInteractions(messages,events,broker);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
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
