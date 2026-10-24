package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyReadiness;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;

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
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);
    private static final AgentRabbitV2TopologyManifest SCOPED_MANIFEST =
            AgentRabbitV2TopologyManifest.forScope(SCOPE);

    @Test
    void mqShadowHasDedicatedInfrastructureButZeroBusinessClaimOrPublish() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                mqShadowGate(), ready(), v2Ready(), settings(), relay, publisher, SCOPE);
        try {
            scheduler.start();
            Thread.sleep(30);
            scheduler.pollOnce();
            verify(relay, never()).discover(any(WorkerScope.class), anyLong(), anyInt());
            verify(relay, never()).claim(any(WorkerScope.class), any(), anyString(), anyLong());
            verify(publisher, never()).publish(any(WorkerScope.class), any(AgentOutboxClaimToken.class), anyLong());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void maxMinusOneCandidateIsQuarantinedByClaimWithoutPublisherInvocation() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitV2TopologyReadiness readiness = v2NotReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), ready(), readiness, settings(), relay, publisher, SCOPE);
        AgentOutboxCandidate fenced = new AgentOutboxCandidate(
                1, "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17, 2,
                "client-a", 41, 1, Long.MAX_VALUE - 1, "PENDING");
        try {
            scheduler.start();
            Thread.sleep(30);
            when(relay.discover(any(WorkerScope.class), anyLong(), anyInt())).thenReturn(List.of(fenced));
            when(relay.claim(any(WorkerScope.class), any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenReturn(AgentOutboxClaim.skipped());
            markV2Ready(readiness);

            scheduler.pollOnce();

            verify(relay).claim(org.mockito.ArgumentMatchers.eq(SCOPE), org.mockito.ArgumentMatchers.eq(fenced),
                    anyString(), anyLong());
            verify(publisher, never()).publish(any(WorkerScope.class), any(AgentOutboxClaimToken.class), anyLong());
            verify(relay, never()).settle(any(WorkerScope.class), any(), any(), anyLong());
            assertEquals(0, scheduler.inflightCount());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void competingMaxMinusTwoHintsPublishOnlyTheWinningClaim() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitV2TopologyReadiness readiness = v2NotReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), ready(), readiness, settings(), relay, publisher, SCOPE);
        AgentOutboxCandidate oldHint = new AgentOutboxCandidate(
                1, "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17, 2,
                "client-a", 41, 1, Long.MAX_VALUE - 2, "PENDING");
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        when(publisher.publish(any(WorkerScope.class), any(AgentOutboxClaimToken.class), anyLong())).thenAnswer(invocation -> {
            published.countDown();
            return AgentRabbitPublishResult.ack();
        });
        when(relay.settle(any(WorkerScope.class), any(), any(), anyLong())).thenAnswer(invocation -> {
            settled.countDown();
            return AgentOutboxSettleResult.PUBLISHED;
        });
        try {
            scheduler.start();
            Thread.sleep(30);
            when(relay.discover(any(WorkerScope.class), anyLong(), anyInt())).thenReturn(List.of(oldHint, oldHint));
            when(relay.claim(any(WorkerScope.class), any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenReturn(AgentOutboxClaim.acquired(token(1)), AgentOutboxClaim.skipped());
            markV2Ready(readiness);

            scheduler.pollOnce();

            assertTrue(published.await(1, TimeUnit.SECONDS));
            assertTrue(settled.await(1, TimeUnit.SECONDS));
            verify(relay, times(2)).claim(org.mockito.ArgumentMatchers.eq(SCOPE), org.mockito.ArgumentMatchers.eq(oldHint),
                    anyString(), anyLong());
            verify(publisher, times(1)).publish(any(WorkerScope.class), any(AgentOutboxClaimToken.class), anyLong());
            verify(relay, times(1)).settle(any(WorkerScope.class), any(), any(), anyLong());
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void pollingIsMaxInflightBoundedAndGracefulStopDrainsCommittedWork() throws Exception {
        AgentOutboxRelayService relay = mock(AgentOutboxRelayService.class);
        AgentOutboxPublisher publisher = mock(AgentOutboxPublisher.class);
        AgentRabbitV2TopologyReadiness readiness = v2NotReady();
        AgentOutboxRelayScheduler scheduler = new AgentOutboxRelayScheduler(
                dispatchGate(), ready(), readiness, settings(), relay, publisher, SCOPE);
        CountDownLatch publisherStarted = new CountDownLatch(2);
        CountDownLatch releasePublisher = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(2);
        doAnswer(invocation -> {
            publisherStarted.countDown();
            releasePublisher.await(2, TimeUnit.SECONDS);
            return AgentRabbitPublishResult.ack();
        }).when(publisher).publish(any(WorkerScope.class), any(AgentOutboxClaimToken.class), anyLong());
        when(relay.settle(any(WorkerScope.class), any(), any(), anyLong())).thenAnswer(invocation -> {
            settled.countDown();
            return AgentOutboxSettleResult.PUBLISHED;
        });
        try {
            scheduler.start();
            Thread.sleep(30);
            reset(relay);
            when(relay.settle(any(WorkerScope.class), any(), any(), anyLong())).thenAnswer(invocation -> {
                settled.countDown();
                return AgentOutboxSettleResult.PUBLISHED;
            });
            markV2Ready(readiness);
            List<AgentOutboxCandidate> candidates = List.of(
                    candidate(1), candidate(2), candidate(3));
            when(relay.discover(any(WorkerScope.class), anyLong(), anyInt())).thenReturn(candidates);
            when(relay.claim(any(WorkerScope.class), any(AgentOutboxCandidate.class), anyString(), anyLong()))
                    .thenAnswer(invocation -> AgentOutboxClaim.acquired(
                            token(invocation.<AgentOutboxCandidate>getArgument(1).outboxId())));

            scheduler.pollOnce();
            assertTrue(publisherStarted.await(1, TimeUnit.SECONDS));
            assertEquals(2, scheduler.inflightCount());
            verify(relay, never()).claim(org.mockito.ArgumentMatchers.eq(SCOPE),
                    org.mockito.ArgumentMatchers.eq(candidates.get(2)),
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

    private static AgentOutboxRelaySettings settings() {
        return new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(
                true, 2, 4, 60_000L, 15_000L, 5_000L, 2, 20,
                1_000L, 2.0D, 300_000L, 20, 1_000L, 131_072));
    }

    private static AgentOutboxCandidate candidate(long id) {
        return new AgentOutboxCandidate(id, "1", "cyf", "pool-a", 13, "producer-a",
                "agent-command", 17, 2, "client-a", 41, id, 0, "PENDING");
    }

    private static AgentOutboxClaimToken token(long id) {
        byte[] bytes = new byte[] {(byte) id};
        byte[] hash = new byte[32];
        return new AgentOutboxClaimToken(id, 41, "1", "cyf", "pool-a", 13,
                "producer-a", "agent-command", 17, 2, "deployment-a", 7,
                "client-a", "evt-" + id,
                "msg-" + id, "cmd-1", "task-1", "agent-1", "task.invite",
                SCOPED_MANIFEST.defaultCommandPublishRoute().destination(),
                SCOPED_MANIFEST.defaultCommandPublishRoute().routingKey(), bytes, hash,
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

    private static AgentRabbitV2TopologyReadiness v2Ready() throws Exception {
        AgentRabbitV2TopologyReadiness readiness = v2NotReady();
        markV2Ready(readiness);
        return readiness;
    }

    private static void markV2Ready(AgentRabbitV2TopologyReadiness readiness) throws Exception {
        Method method = AgentRabbitV2TopologyReadiness.class.getDeclaredMethod(
                "markProvisioned", AgentRabbitV2TopologyManifest.class);
        method.setAccessible(true);
        method.invoke(readiness, SCOPED_MANIFEST);
    }

    private static AgentRabbitV2TopologyReadiness v2NotReady() {
        return new AgentRabbitV2TopologyReadiness();
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
