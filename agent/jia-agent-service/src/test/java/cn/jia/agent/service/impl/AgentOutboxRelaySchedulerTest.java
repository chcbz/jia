package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import org.junit.jupiter.api.Test;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.core.security.FixedTenantAdmissionPolicy;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOutboxRelaySchedulerTest {
    private static final AgentRabbitTopologyManifest MANIFEST = AgentRabbitTopologyManifest.canonical();

    @Test
    void mqShadowHasDedicatedInfrastructureButZeroBusinessClaimOrPublish() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                mqShadowGate(), ready(), settings(), relay, publisher, mock(ScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a");
        try {
            scheduler.start();
            Thread.sleep(30);
            scheduler.pollOnce();
            verify(relay, never()).discover(anyLong(), anyInt());
            verify(relay, never()).claim(any(), anyString(), anyLong());
            verify(publisher, never()).publish(any(), anyLong());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void maxMinusOneCandidateIsQuarantinedByClaimWithoutPublisherInvocation() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitTopologyReadiness readiness = notReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), readiness, settings(), relay, publisher, mock(ScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a");
        AgentOutboxCandidate fenced = new AgentOutboxCandidate(
                1, "tenant-a", "client-a", 41, 1, Long.MAX_VALUE - 1, "PENDING");
        try {
            scheduler.start();
            Thread.sleep(30);
            when(relay.discover(anyLong(), anyInt())).thenReturn(List.of(fenced));
            when(relay.claim(any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenReturn(AgentOutboxClaim.skipped());
            markReady(readiness);

            scheduler.pollOnce();

            verify(relay).claim(org.mockito.ArgumentMatchers.eq(fenced),
                    anyString(), anyLong());
            verify(publisher, never()).publish(any(), anyLong());
            verify(relay, never()).settle(any(), any(), anyLong());
            assertEquals(0, scheduler.inflightCount());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void competingMaxMinusTwoHintsPublishOnlyTheWinningClaim() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitTopologyReadiness readiness = notReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), readiness, settings(), relay, publisher, mock(ScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a");
        AgentOutboxCandidate oldHint = new AgentOutboxCandidate(
                1, "tenant-a", "client-a", 41, 1, Long.MAX_VALUE - 2, "PENDING");
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        when(publisher.publish(any(), anyLong())).thenAnswer(invocation -> {
            published.countDown();
            return AgentRabbitPublishResult.ack();
        });
        when(relay.settle(any(), any(), anyLong())).thenAnswer(invocation -> {
            settled.countDown();
            return AgentOutboxSettleResult.PUBLISHED;
        });
        try {
            scheduler.start();
            Thread.sleep(30);
            when(relay.discover(anyLong(), anyInt())).thenReturn(List.of(oldHint, oldHint));
            when(relay.claim(any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenReturn(AgentOutboxClaim.acquired(token(1)), AgentOutboxClaim.skipped());
            markReady(readiness);

            scheduler.pollOnce();

            assertTrue(published.await(1, TimeUnit.SECONDS));
            assertTrue(settled.await(1, TimeUnit.SECONDS));
            verify(relay, times(2)).claim(org.mockito.ArgumentMatchers.eq(oldHint),
                    anyString(), anyLong());
            verify(publisher, times(1)).publish(any(), anyLong());
            verify(relay, times(1)).settle(any(), any(), anyLong());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void pollingIsMaxInflightBoundedAndGracefulStopDrainsCommittedWork() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitTopologyReadiness readiness = notReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), readiness, settings(), relay, publisher, mock(ScheduleService.class),
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a");
        CountDownLatch publisherStarted = new CountDownLatch(2);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(2);
        doAnswer(invocation -> {
            publisherStarted.countDown();
            releasePublisher.await(2, TimeUnit.SECONDS);
            return AgentRabbitPublishResult.ack();
        }).when(publisher).publish(any(), anyLong());
        when(relay.settle(any(), any(), anyLong())).thenAnswer(invocation -> {
            settled.countDown();
            return AgentOutboxSettleResult.PUBLISHED;
        });
        try {
            scheduler.start();
            Thread.sleep(30);
            reset(relay);
            when(relay.settle(any(), any(), anyLong())).thenAnswer(invocation -> {
                settled.countDown();
                return AgentOutboxSettleResult.PUBLISHED;
            });
            markReady(readiness);
            List<AgentOutboxCandidate> candidates = List.of(
                    candidate(1), candidate(2), candidate(3));
            when(relay.discover(anyLong(), anyInt())).thenReturn(candidates);
            when(relay.claim(any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenAnswer(invocation -> AgentOutboxClaim.acquired(
                            token(invocation.<AgentOutboxCandidate>getArgument(0).outboxId())));

            scheduler.pollOnce();
            assertTrue(publisherStarted.await(1, TimeUnit.SECONDS));
            assertEquals(2, scheduler.inflightCount());
            verify(relay, never()).claim(org.mockito.ArgumentMatchers.eq(candidates.get(2)),
                    anyString(), anyLong());

            AtomicBoolean stopped = new AtomicBoolean();
            Thread stopper = new Thread(() -> scheduler.stop(() -> stopped.set(true)));
            stopper.start();
            Thread.sleep(50);
            assertFalse(stopped.get());
            releasePublisher.countDown();
            assertTrue(settled.await(1, TimeUnit.SECONDS));
            stopper.join(1_000);
            assertTrue(stopped.get());
            assertEquals(0, scheduler.inflightCount());
        } finally {
            releasePublisher.countDown();
            scheduler.stop();
        }
    }

    @Test
    void unifiedDeadlineWaitsForReadyAndStoppedCallbackDoesNotDiscover() {
        var relay = mock(AgentOutboxRelayService.class);
        var publisher = mock(AgentOutboxPublisher.class);
        var schedules = mock(ScheduleService.class);
        var scheduler = new AgentOutboxRelayScheduler(dispatchGate(), ready(), settings(), relay, publisher,
                schedules, new FixedTenantAdmissionPolicy("tenant-a", "client-a"), "node-a");
        var key = new ScheduleKey("client-a", AgentOutboxRelayScheduler.HANDLER, "recovery", "node-a");
        try {
            scheduler.start();
            scheduler.executeScheduled(null);
            org.mockito.Mockito.verifyNoInteractions(relay, publisher, schedules);
            scheduler.onApplicationEvent(null);
            verify(schedules).signal(org.mockito.ArgumentMatchers.eq(key), anyLong());
            org.mockito.Mockito.verifyNoInteractions(relay, publisher);
            scheduler.executeScheduled(null);
            verify(relay).discover(anyLong(), anyInt());
            verify(schedules, times(2)).signal(org.mockito.ArgumentMatchers.eq(key), anyLong());
            scheduler.stop();
            reset(relay, publisher, schedules);
            scheduler.executeScheduled(null);
            scheduler.onApplicationEvent(null);
            org.mockito.Mockito.verifyNoInteractions(relay, publisher, schedules);
        } finally { scheduler.stop(); }
    }

    @Test
    void discoveryFailureKeepsNextDurableDeadlineAndPropagatesForTaskRecovery() {
        var relay = mock(AgentOutboxRelayService.class);
        var schedules = mock(ScheduleService.class);
        var scheduler = new AgentOutboxRelayScheduler(dispatchGate(), ready(), settings(), relay,
                mock(AgentOutboxPublisher.class), schedules,
                new FixedTenantAdmissionPolicy("tenant-a", null), "node-a");
        try {
            scheduler.start();scheduler.onApplicationEvent(null);
            when(relay.discover(anyLong(), anyInt())).thenThrow(new IllegalStateException("SQL unavailable"));
            long before = System.currentTimeMillis();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> scheduler.executeScheduled(null));
            var deadline = org.mockito.ArgumentCaptor.forClass(Long.class);
            verify(schedules, times(2)).signal(org.mockito.ArgumentMatchers.eq(new ScheduleKey("",
                    AgentOutboxRelayScheduler.HANDLER, "recovery", "node-a")), deadline.capture());
            assertTrue(deadline.getAllValues().get(1) >= before + settings().pollDelayMillis());
        } finally { scheduler.stop(); }
    }

    private static AgentOutboxRelaySettings settings() {
        return new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(
                true, 2, 4, 60_000L, 15_000L, 5_000L, 2, 20,
                1_000L, 2.0D, 300_000L, 20, 1_000L, 131_072));
    }

    private static AgentOutboxCandidate candidate(long id) {
        return new AgentOutboxCandidate(id, "tenant-a", "client-a", 41, id, 0, "PENDING");
    }

    private static AgentOutboxClaimToken token(long id) {
        byte[] bytes = new byte[] {(byte) id};
        byte[] hash = new byte[32];
        return new AgentOutboxClaimToken(id, 41, "tenant-a", "client-a", "evt-" + id,
                "msg-" + id, "cmd-1", "task-1", "agent-1", "task.invite",
                MANIFEST.defaultCommandPublishRoute().destination(),
                MANIFEST.defaultCommandPublishRoute().routingKey(), bytes, hash,
                Long.MAX_VALUE, "lease", Long.MAX_VALUE, 2, 1,
                "PENDING", "msg-" + id, 1, 1);
    }

    private static AgentRabbitTopologyReadiness ready() throws Exception {
        AgentRabbitTopologyReadiness readiness = notReady();
        markReady(readiness);
        return readiness;
    }

    private static void markReady(AgentRabbitTopologyReadiness readiness) throws Exception {
        Method method = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
        method.setAccessible(true); method.invoke(readiness);
    }

    private static AgentRabbitTopologyReadiness notReady() {
        return new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(MANIFEST);
    }

    private static AgentRabbitSafetyGate dispatchGate() {
        AgentRabbitSafetyProperties properties = properties(true, true);
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope("tenant-a", "client-a"))));
    }

    private static AgentRabbitSafetyGate mqShadowGate() {
        return new AgentRabbitSafetyGate(properties(false, false));
    }

    private static AgentRabbitSafetyProperties properties(boolean consume, boolean dispatch) {
        return new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(consume),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated"));
    }
}
