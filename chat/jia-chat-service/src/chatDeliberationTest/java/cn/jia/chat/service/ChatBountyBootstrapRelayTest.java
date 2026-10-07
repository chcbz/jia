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
    private final ChatTypedDeliberationContextService contexts = new ChatTypedDeliberationContextService(
            mock(JdbcTemplate.class), new TypedDeliberationSessionRegistry(), schema,
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
