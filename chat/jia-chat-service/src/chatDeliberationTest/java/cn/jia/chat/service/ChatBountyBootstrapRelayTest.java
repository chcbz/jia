package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer;
import cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer.Readiness;
import cn.jia.chat.handler.TypedDeliberationSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatBountyBootstrapRelayTest {
    private final AgentTaskBountyBootstrapOutboxService outbox = mock(AgentTaskBountyBootstrapOutboxService.class);
    private final ChatBountyBootstrapAdmissionService admission = mock(ChatBountyBootstrapAdmissionService.class);
    private final ChatTypedDeliberationSchemaInitializer schema = mock(ChatTypedDeliberationSchemaInitializer.class);
    private final TypedDeliberationSessionRegistry sessions = new TypedDeliberationSessionRegistry();
    private final ChatTypedDeliberationContextService contexts = new ChatTypedDeliberationContextService(
            mock(JdbcTemplate.class), sessions, schema,
            mock(ChatActionCapabilityService.class), true);
    private final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
    private final ChatBountyBootstrapRelay relay = new ChatBountyBootstrapRelay(outbox, admission, contexts,
            () -> scheduler);

    @BeforeEach void ready() { when(schema.readiness()).thenReturn(Readiness.READY); }
    private final AgentTaskBountyBootstrapClaimDTO claim = new AgentTaskBountyBootstrapClaimDTO(
            "bootstrap-1", "0", "client", "owner", "task-1", "action-1", 1L,
            "TASK_REQUIREMENT_REVISION_V1", 3L, "agent-1", "grant-1", 1L,
            "GENERATE_IMAGE", List.of(), "a".repeat(64), "worker", Long.MAX_VALUE, 1, 2);

    @Test void committedRequestIsAcknowledgedExactlyOnceWithoutDispatchOrProviderCall() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenReturn(new ChatBountyBootstrapAdmissionService.Admission(
                "10", 1L, "request-1", "42", "step-1", false));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "ADMITTED", 3,
                        "10", "request-1"));
        relay.pollOnce();
        var receipt = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        var scope = org.mockito.ArgumentCaptor.forClass(AgentTaskExecutionGrantService.Scope.class);
        verify(outbox).reconcile(scope.capture(), receipt.capture(), anyLong());
        assertEquals(new AgentTaskExecutionGrantService.Scope("0", "client", "owner"), scope.getValue());
        assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED, receipt.getValue().outcome());
        assertEquals("request-1", receipt.getValue().initialRequestId());
        assertEquals(2L, receipt.getValue().expectedOutboxVersion());
        assertEquals(claim.leaseOwner(), receipt.getValue().leaseOwner());
        assertEquals(claim.claimAttempt(), receipt.getValue().claimAttempt());
        var order = inOrder(admission, outbox);
        order.verify(admission).admit(same(claim));
        order.verify(outbox).reconcile(any(), any(), anyLong());
    }

    @Test void failedAdmissionIsNeverReconciledAsDeliveredOrGenerated() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenThrow(new IllegalStateException("Missing exact revision"));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "RETRY", 3,
                        null, null));
        assertThrows(IllegalStateException.class, relay::pollOnce);
        var receipt = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox).reconcile(any(), receipt.capture(), anyLong());
        assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                receipt.getValue().outcome());
        assertNull(receipt.getValue().conversationId());
        assertNull(receipt.getValue().initialRequestId());
    }

    @Test void staleOrUnconfirmedAckDoesNotClaimSuccess() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenReturn(new ChatBountyBootstrapAdmissionService.Admission(
                "10", 1, "request-1", "42", "step-1", true));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "RETRY", 3,
                        null, null));
        assertThrows(IllegalStateException.class, relay::pollOnce);
    }

    @Test void smartLifecycleBeforeApplicationRunnerWaitsAndThenResumesOriginalPendingIntent() throws Exception {
        var state = new AtomicReference<>(Readiness.INITIALIZING);
        when(schema.readiness()).thenAnswer(ignored -> state.get());
        var tick = new AtomicReference<Runnable>();
        doAnswer(invocation -> { tick.set(invocation.getArgument(0)); return null; })
                .when(scheduler).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(500L), eq(TimeUnit.MILLISECONDS));
        var pending = new AgentTaskBountyBootstrapClaimDTO(
                "bootstrap-original", "0", "client", "owner", "task-original", "point-original", 1L,
                "TASK_REQUIREMENT_REVISION_V1", 1L, "agent-1", "grant-original", 1L,
                "DELIBERATE", List.of(), "a".repeat(64), "original-lease", Long.MAX_VALUE, 1, 2);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(pending, null);
        when(admission.admit(same(pending))).thenReturn(new ChatBountyBootstrapAdmissionService.Admission(
                "10", 1L, "request-original", "42", null, false));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-original", "ADMITTED", 3,
                        "10", "request-original"));
        doAnswer(ignored -> {
            assertTrue(relay.isRunning());
            assertNotNull(tick.get(), "SmartLifecycle must already have scheduled its poll");
            for (int i = 0; i < 3; i++) tick.get().run();
            assertTrue(relay.isRunning(), "initial readiness is not a repeated permanent failure");
            verifyNoInteractions(outbox, admission);
            state.set(Readiness.READY);
            return null;
        }).when(schema).run(any());
        var application = new SpringApplication(BootstrapTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        application.addInitializers(context -> {
            var beans = (GenericApplicationContext) context;
            beans.registerBean("typedSchema", ChatTypedDeliberationSchemaInitializer.class, () -> schema);
            beans.registerBean("bootstrapRelay", ChatBountyBootstrapRelay.class, () -> relay);
        });
        try (var ignored = application.run("--spring.main.banner-mode=off",
                "--chat.bounty-bootstrap.enabled=true", "--chat.typed-deliberation.enabled=true")) {
            verify(schema).run(any());
            for (int i = 0; i < 3; i++) tick.get().run();
            verify(admission, times(1)).admit(same(pending));
            var receipt = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
            verify(outbox, times(1)).reconcile(eq(new AgentTaskExecutionGrantService.Scope("0", "client", "owner")),
                    receipt.capture(), anyLong());
            assertEquals("bootstrap-original", receipt.getValue().bootstrapId());
            assertEquals("request-original", receipt.getValue().initialRequestId());
            assertEquals("original-lease", receipt.getValue().leaseOwner());
            assertEquals(2L, receipt.getValue().expectedOutboxVersion());
            assertEquals(1, receipt.getValue().claimAttempt());
            assertTrue(relay.isRunning());
        }
        assertFalse(relay.isRunning());
        verify(scheduler).shutdown();
    }

    @Test void schemaReadyBeforeSessionWaitsBeyondTwoPollsThenAdmitsOriginalIntentOnce() {
        when(schema.readiness()).thenReturn(Readiness.INITIALIZING);
        Runnable tick = startTick();
        for (int i = 0; i < 3; i++) tick.run();
        verifyNoInteractions(outbox, admission);
        when(schema.readiness()).thenReturn(Readiness.READY);
        when(schema.ready()).thenReturn(true);
        stubRuntimeAdmission();
        stubReconcile();
        var committed = new AtomicBoolean();
        var attempt = new AtomicInteger();
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenAnswer(ignored -> committed.get() ? null
                : pending("original", "agent-original", attempt.incrementAndGet(), attempt.get() * 2L));
        doAnswer(invocation -> {
            var command = invocation.<AgentTaskBountyBootstrapReconcileDTO>getArgument(1);
            if (command.outcome() == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED) committed.set(true);
            return reconciliation(command);
        }).when(outbox).reconcile(any(), any(), anyLong());
        for (int i = 0; i < 4; i++) { tick.run(); assertTrue(relay.isRunning()); }
        registerRuntime("original-session", "agent-original", "READY");
        for (int i = 0; i < 3; i++) tick.run();
        assertTrue(relay.isRunning());
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(5)).reconcile(eq(new AgentTaskExecutionGrantService.Scope("0", "client", "owner")),
                receipts.capture(), anyLong());
        for (int i = 0; i < 5; i++) {
            var receipt = receipts.getAllValues().get(i);
            assertEquals("original", receipt.bootstrapId());
            assertEquals((i + 1) * 2L, receipt.expectedOutboxVersion());
            assertEquals(i + 1, receipt.claimAttempt());
            assertEquals("lease-" + (i + 1), receipt.leaseOwner());
            if (i < 4) {
                assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE, receipt.outcome());
                assertEquals("BOUNTY_RUNTIME_AWAITING", receipt.errorCode());
                assertNull(receipt.conversationId()); assertNull(receipt.initialRequestId());
            } else {
                assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED, receipt.outcome());
                assertEquals("request-original", receipt.initialRequestId());
            }
        }
        var claims = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapClaimDTO.class);
        verify(admission, times(5)).admit(claims.capture());
        for (var original : claims.getAllValues()) {
            assertEquals("task-original", original.taskId()); assertEquals("action-original", original.sourceBusinessActionId());
            assertEquals(1L, original.requirementRevision()); assertEquals("grant-original", original.grantId());
        }
        verify(scheduler, never()).shutdown();
    }

    @Test void realTransactionalBootstrapRollsBackWaitingWritesAndRetainsOriginalTypedKey() throws Exception {
        when(schema.ready()).thenReturn(true);
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:bootstrap_runtime_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var probe = new JdbcTemplate(dataSource);
        probe.execute("CREATE TABLE admission_probe (id VARCHAR(100) PRIMARY KEY)");
        try {
            var requirements = mock(cn.jia.agent.service.AgentTaskRequirementSnapshotService.class);
            var discussions = mock(ChatBountyConversationService.class);
            var typed = mock(ChatTypedDiscussionAdmissionService.class);
            when(requirements.read(eq(new AgentTaskExecutionGrantService.Scope("0", "client", "owner")),
                    eq("task-original"), eq(1L))).thenReturn(new cn.jia.agent.service.AgentTaskRequirementSnapshotService.Snapshot(
                            "0", "client", "owner", "task-original", 1, "original demand", "original demand", "a".repeat(64), "CREATE"));
            when(discussions.ensure(any(), anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
                    .thenAnswer(ignored -> {
                        assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                        probe.update("INSERT INTO admission_probe SELECT 'discussion' WHERE NOT EXISTS (SELECT 1 FROM admission_probe WHERE id='discussion')");
                        return new ChatBountyConversationService.Discussion("10", 1, true);
                    });
            var keys = new java.util.ArrayList<String>();
            when(typed.admit(eq("0"), any(), eq("10"), anyString(), any())).thenAnswer(invocation -> {
                String key = invocation.getArgument(3); keys.add(key);
                var command = invocation.<cn.jia.chat.api.ChatTypedDeliberationWire.DiscussionCommand>getArgument(4);
                assertEquals("original demand", command.content());
                assertEquals("task-original", command.taskId()); assertEquals(3L, command.expectedAssignmentRevision());
                boolean replay = probe.queryForObject("SELECT COUNT(*) FROM admission_probe WHERE id=?", Integer.class, key) == 1;
                if (!replay) {
                    contexts.resolve(new ChatTypedDeliberationContextService.Scope("0", "owner", "client", "10", 1),
                            command.taskId(), "agent-original", command.sourceSelectors());
                    probe.update("INSERT INTO admission_probe VALUES (?)", key);
                }
                return new cn.jia.chat.api.ChatTypedDeliberationWire.Accepted(1, "DISCUSSION", "request-original", "42",
                        List.of("turn-original"), "ADMITTED", "0", "7", "/chat/requests/request-original",
                        "/chat/conversations/10/requests/request-original/typed-outcome", replay, null);
            });
            var target = new ChatBountyBootstrapAdmissionService(requirements, discussions,
                    mock(cn.jia.chat.dao.ChatConversationDao.class), mock(cn.jia.chat.dao.ChatMessageDao.class),
                    mock(cn.jia.chat.deliberation.ChatDeliberationDao.class), mock(cn.jia.chat.deliberation.ChatInteractionStepStore.class),
                    mock(ChatConversationEventBroker.class), cn.jia.core.util.JsonUtil.getMapper(), typed);
            var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
            interceptor.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
            interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
            interceptor.afterPropertiesSet();
            var factory = new org.springframework.aop.framework.ProxyFactory(target);
            factory.setProxyTargetClass(true); factory.addAdvice(interceptor);
            var transactional = (ChatBountyBootstrapAdmissionService) factory.getProxy();
            String summaryHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var original = pending("original", "agent-original", 1, 2);
            var reclaimed = pending("original", "agent-original", 2, 4);
            var resumed = pending("original", "agent-original", 3, 6);
            assertEquals(summaryHash, original.referenceSummarySha256());
            when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(original, reclaimed, resumed, null);
            when(outbox.reconcile(any(), any(), anyLong())).thenAnswer(invocation -> {
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                var command = invocation.<AgentTaskBountyBootstrapReconcileDTO>getArgument(1);
                assertEquals(command.outcome() == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED ? 2 : 0,
                        probe.queryForObject("SELECT COUNT(*) FROM admission_probe", Integer.class));
                return reconciliation(command);
            });
            var transactionalRelay = new ChatBountyBootstrapRelay(outbox, transactional, contexts, () -> scheduler);
            transactionalRelay.pollOnce(); transactionalRelay.pollOnce();
            assertEquals(0, probe.queryForObject("SELECT COUNT(*) FROM admission_probe", Integer.class));
            registerRuntime("ready", "agent-original", "READY");
            transactionalRelay.pollOnce(); transactionalRelay.pollOnce();
            sessions.remove("ready"); // durable replay must not require a newly connected runtime
            assertTrue(transactional.admit(resumed).replay());
            assertEquals(2, probe.queryForObject("SELECT COUNT(*) FROM admission_probe", Integer.class));
            assertEquals(4, keys.size()); assertEquals(1L, keys.stream().distinct().count());
            var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
            verify(outbox, times(3)).reconcile(any(), receipts.capture(), anyLong());
            assertEquals(1L, receipts.getAllValues().stream()
                    .filter(value -> value.outcome() == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED).count());
        } finally { probe.execute("SHUTDOWN"); }
    }

    @Test void unreadyTargetUsesDurableRetryWithoutStarvingReadyTarget() {
        when(schema.ready()).thenReturn(true);
        registerRuntime("session-a", "agent-a", "UNAVAILABLE");
        registerRuntime("session-b", "agent-b", "READY");
        var first = pending("original-a", "agent-a", 1, 2);
        var other = pending("original-b", "agent-b", 1, 2);
        var resumed = pending("original-a", "agent-a", 2, 4);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(first, other, resumed, null);
        stubRuntimeAdmission(); stubReconcile();
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertTrue(relay.isRunning());
        registerRuntime("session-a", "agent-a", "READY");
        tick.run(); tick.run();
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(3)).reconcile(any(), receipts.capture(), anyLong());
        assertEquals(List.of(AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED, AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED),
                receipts.getAllValues().stream().map(AgentTaskBountyBootstrapReconcileDTO::outcome).toList());
        assertEquals("request-original-b", receipts.getAllValues().get(1).initialRequestId());
        assertEquals("request-original-a", receipts.getAllValues().get(2).initialRequestId());
        verify(admission).admit(same(first)); verify(admission).admit(same(other)); verify(admission).admit(same(resumed));
        assertTrue(relay.isRunning());
    }

    @Test void disconnectWaitsAndExactReconnectionResumesOriginalIntent() {
        when(schema.ready()).thenReturn(true);
        var current = new AtomicBoolean(true);
        sessions.register("old", "0", "owner", "client", "agent-original", runtimeDeclaration("READY"), current::get);
        current.set(false);
        var original = pending("original", "agent-original", 1, 2);
        var resumed = pending("original", "agent-original", 2, 4);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(original, resumed, null);
        stubRuntimeAdmission(); stubReconcile();
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        registerRuntime("new", "agent-original", "READY");
        tick.run(); tick.run();
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(2)).reconcile(any(), receipts.capture(), anyLong());
        assertEquals("BOUNTY_RUNTIME_AWAITING", receipts.getAllValues().getFirst().errorCode());
        assertEquals("request-original", receipts.getAllValues().getLast().initialRequestId());
        assertTrue(relay.isRunning());
    }

    @Test void runtimeWaitDoesNotEraseAPreviouslyObservedPermanentFailure() {
        when(schema.ready()).thenReturn(true);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        var calls = new AtomicInteger();
        when(admission.admit(claim)).thenAnswer(ignored -> {
            if (calls.incrementAndGet() == 2) contexts.resolve(new ChatTypedDeliberationContextService.Scope(
                    "0", "owner", "client", "10", 1), "task-1", "agent-1", List.of());
            throw new IllegalStateException("Missing exact revision");
        });
        stubReconcile();
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        tick.run(); verify(admission, times(3)).admit(same(claim));
    }

    @Test void runtimeWaitCannotMaskUnconfirmedRetryOrReconciliationFailure() {
        when(schema.ready()).thenReturn(true);
        var original = pending("original", "agent-original", 1, 2);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(original);
        stubRuntimeAdmission();
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("original", "ADMITTED", 3, "10", "wrong-request"));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        verify(outbox, times(2)).reconcile(any(), any(), anyLong());
    }

    @Test void staleRetryFenceDuringRuntimeWaitStillStopsAfterTwoFailures() {
        when(schema.ready()).thenReturn(true);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(pending("original", "agent-original", 1, 2));
        stubRuntimeAdmission();
        when(outbox.reconcile(any(), any(), anyLong())).thenThrow(new IllegalStateException("Bootstrap claim fence is stale"));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
    }

    @Test void invalidLiveDeclarationStillStopsAfterTwoRealFailures() {
        sessions.register("invalid", "0", "owner", "client", "agent-original", Map.of("schemaVersion", 1));
        permanentRuntimeFailure();
    }

    @Test void ambiguousLiveDeclarationsStillStopAfterTwoRealFailures() {
        registerRuntime("s1", "agent-original", "READY");
        registerRuntime("s2", "agent-original", "UNAVAILABLE");
        permanentRuntimeFailure();
    }

    @Test void genericPersistenceErrorWithSameRuntimeMessageIsNotClassifiedByText() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenThrow(new ChatDeliberationException(
                ChatDeliberationException.Reason.PERSISTENCE_ERROR, "Typed deliberation runtime is unavailable"));
        stubReconcile();
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(2)).reconcile(any(), receipts.capture(), anyLong());
        assertTrue(receipts.getAllValues().stream().allMatch(value -> "BOUNTY_ADMISSION_UNAVAILABLE".equals(value.errorCode())));
    }

    private void permanentRuntimeFailure() {
        when(schema.ready()).thenReturn(true);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(pending("original", "agent-original", 1, 2));
        stubRuntimeAdmission(); stubReconcile();
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(2)).reconcile(any(), receipts.capture(), anyLong());
        assertTrue(receipts.getAllValues().stream().allMatch(value -> "BOUNTY_ADMISSION_UNAVAILABLE".equals(value.errorCode())));
    }

    private void stubRuntimeAdmission() {
        when(admission.admit(any())).thenAnswer(invocation -> {
            var intent = invocation.<AgentTaskBountyBootstrapClaimDTO>getArgument(0);
            contexts.resolve(new ChatTypedDeliberationContextService.Scope(intent.tenantId(), intent.ownerJiacn(),
                    intent.clientId(), "10", 1), intent.taskId(), intent.targetAgentId(), List.of());
            return new ChatBountyBootstrapAdmissionService.Admission("10", 1, "request-" + intent.bootstrapId(), "42", null, false);
        });
    }

    private void stubReconcile() {
        when(outbox.reconcile(any(), any(), anyLong())).thenAnswer(invocation -> reconciliation(invocation.getArgument(1)));
    }

    private static AgentTaskBountyBootstrapReconcileResultDTO reconciliation(AgentTaskBountyBootstrapReconcileDTO command) {
        return new AgentTaskBountyBootstrapReconcileResultDTO(command.bootstrapId(),
                command.outcome() == AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED ? "ADMITTED" : "RETRY",
                command.expectedOutboxVersion() + 1, command.conversationId(), command.initialRequestId());
    }

    private static AgentTaskBountyBootstrapClaimDTO pending(String bootstrap, String target, int attempt, long version) {
        return new AgentTaskBountyBootstrapClaimDTO(bootstrap, "0", "client", "owner", "task-" + bootstrap,
                "action-" + bootstrap, 1, "TASK_REQUIREMENT_REVISION_V1", 3, target, "grant-" + bootstrap, 1,
                "DELIBERATE", List.of(), "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
                "lease-" + attempt, Long.MAX_VALUE, attempt, version);
    }

    private void registerRuntime(String session, String agent, String state) {
        sessions.register(session, "0", "owner", "client", agent, runtimeDeclaration(state));
    }

    private static Map<String,Object> runtimeDeclaration(String state) {
        return Map.of("schemaVersion", 3, "state", state, "carrier", "CHAT_MESSAGE_FINAL_SIDECAR_V3",
                "referenceModes", List.of("NONE", "AVAILABLE"), "outcomeKinds", List.of("ANSWER", "CLARIFY", "ACTION_REQUEST"),
                "engine", "CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA", "strictNoToolsVerified", false, "toolPolicy", "read-only-constrained");
    }

    @Test void disabledTypedModeNeverStartsASchedulerOrClaimsAnIntent() {
        var disabled = new ChatTypedDeliberationContextService(mock(JdbcTemplate.class),
                new TypedDeliberationSessionRegistry(), schema, mock(ChatActionCapabilityService.class), false);
        @SuppressWarnings("unchecked")
        Supplier<ScheduledExecutorService> factory = mock(Supplier.class);
        var inactive = new ChatBountyBootstrapRelay(outbox, admission, disabled, factory);
        inactive.start();
        inactive.pollOnce();
        inactive.start();
        assertFalse(inactive.isRunning());
        verifyNoInteractions(factory, outbox, admission);
    }

    @Test void terminalUnavailabilityStopsAfterTwoPollsWithoutClaiming() {
        terminalSchemaFailure(Readiness.UNAVAILABLE);
    }

    @Test void failedInitializationIsNotTreatedAsEndlessStartupReadiness() {
        terminalSchemaFailure(Readiness.FAILED);
    }

    private void terminalSchemaFailure(Readiness readiness) {
        when(schema.readiness()).thenReturn(readiness);
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        when(schema.readiness()).thenReturn(Readiness.READY);
        tick.run(); // a real failure never auto-restarts when schema state changes
        verify(scheduler).shutdown();
        verifyNoInteractions(outbox, admission);
    }

    @Test void realUnchangedAdmissionFailureStillStopsAfterTwoAndRetainsOriginalFence() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim);
        when(admission.admit(claim)).thenThrow(new IllegalStateException("Missing exact revision"));
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "RETRY", 3, null, null));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
        tick.run();
        verify(admission, times(2)).admit(same(claim));
        var receipts = org.mockito.ArgumentCaptor.forClass(AgentTaskBountyBootstrapReconcileDTO.class);
        verify(outbox, times(2)).reconcile(eq(new AgentTaskExecutionGrantService.Scope("0", "client", "owner")),
                receipts.capture(), anyLong());
        for (var receipt : receipts.getAllValues()) {
            assertEquals(AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE, receipt.outcome());
            assertEquals(claim.bootstrapId(), receipt.bootstrapId());
            assertEquals(claim.outboxVersion(), receipt.expectedOutboxVersion());
            assertEquals(claim.leaseOwner(), receipt.leaseOwner());
            assertEquals(claim.claimAttempt(), receipt.claimAttempt());
            assertNull(receipt.conversationId()); assertNull(receipt.initialRequestId());
        }
    }

    @Test void startupWaitingDoesNotEraseARealFailureAlreadyObserved() {
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(new IllegalStateException("DB fence"));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        when(schema.readiness()).thenReturn(Readiness.INITIALIZING);
        for (int i = 0; i < 3; i++) tick.run();
        assertTrue(relay.isRunning());
        when(schema.readiness()).thenReturn(Readiness.READY);
        tick.run(); assertFalse(relay.isRunning());
        verify(outbox, times(2)).claimNextAvailable(anyString(), anyLong());
        verifyNoInteractions(admission);
    }

    @Test void successfulEmptyPollResetsConsecutiveRealFailures() {
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("DB fence")).thenReturn(null)
                .thenThrow(new IllegalStateException("DB fence"));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
    }

    @Test void changedErrorSignatureBeginsANewTwoFailureSequence() {
        when(outbox.claimNextAvailable(anyString(), anyLong()))
                .thenThrow(new IllegalStateException("fence A"))
                .thenThrow(new IllegalStateException("fence B"));
        Runnable tick = startTick();
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertTrue(relay.isRunning());
        tick.run(); assertFalse(relay.isRunning());
    }

    @Test void duplicateStartAndConcurrentPollCannotOverlapAnAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenReturn(claim, null);
        when(admission.admit(same(claim))).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new ChatBountyBootstrapAdmissionService.Admission("10", 1L, "request-1", "42", "step-1", false);
        });
        when(outbox.reconcile(any(), any(), anyLong())).thenReturn(
                new AgentTaskBountyBootstrapReconcileResultDTO("bootstrap-1", "ADMITTED", 3, "10", "request-1"));
        Runnable tick = startTick();
        relay.start();
        verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        var worker = Executors.newSingleThreadExecutor();
        try {
            var attempt = worker.submit(relay::pollOnce);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tick.run();
            relay.pollOnce();
            verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
            release.countDown();
            attempt.get(5, TimeUnit.SECONDS);
            tick.run();
            verify(admission, times(1)).admit(same(claim));
            verify(outbox, times(1)).reconcile(any(), any(), anyLong());
        } finally {
            release.countDown();
            worker.shutdownNow();
            relay.close();
        }
    }

    @Test void stoppedSchedulerCallbackCannotConsumeAfterExplicitRestartAndFailureCountResets() {
        var replacement = mock(ScheduledExecutorService.class);
        var newTick = new AtomicReference<Runnable>();
        doAnswer(invocation -> { newTick.set(invocation.getArgument(0)); return null; })
                .when(replacement).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        var factory = new AtomicReference<>(scheduler);
        var restarted = new ChatBountyBootstrapRelay(outbox, admission, contexts, factory::get);
        restarted.start();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());
        Runnable oldTick = callback.getValue();
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenThrow(new IllegalStateException("DB fence"));
        oldTick.run(); assertTrue(restarted.isRunning()); // one real failure in the old lifecycle
        restarted.stop();
        factory.set(replacement);
        restarted.start();
        clearInvocations(outbox);
        oldTick.run();
        verifyNoInteractions(outbox, admission);
        newTick.get().run(); assertTrue(restarted.isRunning()); // restart resets the old failure
        newTick.get().run(); assertFalse(restarted.isRunning());
        verify(scheduler).shutdown();
        verify(replacement).shutdown();
    }

    @Test void inFlightOldLifecycleFailureCannotStopItsReplacement() throws Exception {
        var replacement = mock(ScheduledExecutorService.class);
        var newTick = new AtomicReference<Runnable>();
        doAnswer(invocation -> { newTick.set(invocation.getArgument(0)); return null; })
                .when(replacement).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        var factory = new AtomicReference<>(scheduler);
        var restarted = new ChatBountyBootstrapRelay(outbox, admission, contexts, factory::get);
        restarted.start();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(callback.capture(), anyLong(), anyLong(), any());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(outbox.claimNextAvailable(anyString(), anyLong())).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException("DB fence");
        });
        var worker = Executors.newSingleThreadExecutor();
        try {
            var oldAttempt = worker.submit(callback.getValue());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            restarted.stop();
            factory.set(replacement);
            restarted.start();
            newTick.get().run(); // does not overlap the old claim or count as a successful poll
            verify(outbox, times(1)).claimNextAvailable(anyString(), anyLong());
            release.countDown();
            oldAttempt.get(5, TimeUnit.SECONDS);
            assertTrue(restarted.isRunning());
            newTick.get().run(); assertTrue(restarted.isRunning());
            newTick.get().run(); assertFalse(restarted.isRunning());
        } finally {
            release.countDown();
            worker.shutdownNow();
            restarted.close();
        }
    }

    @Test void schedulerRegistrationFailureDoesNotLeaveARunningRelay() {
        when(scheduler.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("scheduler unavailable"));
        assertThrows(java.util.concurrent.RejectedExecutionException.class, relay::start);
        assertFalse(relay.isRunning());
        verify(scheduler).shutdown();
        verifyNoInteractions(outbox, admission);
    }

    private Runnable startTick() {
        relay.start();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(callback.capture(), eq(0L), eq(500L), eq(TimeUnit.MILLISECONDS));
        return callback.getValue();
    }

    @Configuration(proxyBeanMethods = false)
    static class BootstrapTestConfiguration { }
}
