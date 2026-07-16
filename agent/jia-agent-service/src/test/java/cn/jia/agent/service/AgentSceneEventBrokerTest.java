package cn.jia.agent.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.service.impl.AgentSceneServiceImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscription;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentSceneEventBrokerTest {
    private final AgentSceneEventBroker broker = new AgentSceneEventBroker();
    private final AgentSceneEventBroker.SceneScope scope =
            new AgentSceneEventBroker.SceneScope("tenant-a", "client-a", "juyiting-main");

    @Test
    void streamsOnlyNewerEventsAndDefensivelyCopies() {
        AgentSceneEventDTO source = event(129L, "agent-songjiang");

        StepVerifier.create(broker.stream(scope, 128L).take(1))
                .then(() -> {
                    broker.publish(scope, event(128L, "not-newer"));
                    broker.publish(scope, source);
                    AgentSceneStateDTO mutated = source.getState();
                    mutated.setPersonaCode("mutated-source");
                    source.setState(mutated);
                })
                .assertNext(received -> {
                    assertEquals(129L, received.getSceneVersion());
                    assertEquals("songjiang", received.getState().getPersonaCode());
                    assertNotSame(source, received);
                    assertNotSame(source.getState(), received.getState());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void isolatesTenantClientAndSceneScopes() {
        AgentSceneEventBroker.SceneScope anotherTenant =
                new AgentSceneEventBroker.SceneScope("tenant-b", "client-a", "juyiting-main");
        AgentSceneEventBroker.SceneScope anotherClient =
                new AgentSceneEventBroker.SceneScope("tenant-a", "client-b", "juyiting-main");
        AgentSceneEventBroker.SceneScope anotherScene =
                new AgentSceneEventBroker.SceneScope("tenant-a", "client-a", "another-scene");

        StepVerifier.create(broker.stream(scope, 0L).take(1))
                .then(() -> {
                    broker.publish(anotherTenant, event(1L, "wrong-tenant"));
                    broker.publish(anotherClient, event(2L, "wrong-client"));
                    broker.publish(anotherScene, event(3L, "wrong-scene"));
                    broker.publish(scope, event(4L, "agent-songjiang"));
                })
                .assertNext(received -> assertEquals(4L, received.getSceneVersion()))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void cleansUpScopeAfterLastSubscriberAndDoesNotCreateChannelsForBlindPublish() {
        var subscription = broker.stream(scope, 0L).subscribe();
        assertEquals(1, broker.activeScopeCount());

        subscription.dispose();

        assertEquals(0, broker.activeScopeCount());
        broker.publish(scope, event(1L, "no-subscriber"));
        assertEquals(0, broker.activeScopeCount());
    }

    @Test
    void directBestEffortKeepsFastSubscriberSafeWhenAnotherSubscriberIsSlow() throws Exception {
        List<Long> fastVersions = new CopyOnWriteArrayList<>();
        CountDownLatch fastLatch = new CountDownLatch(2);
        CountDownLatch slowLatch = new CountDownLatch(1);
        var fast = broker.stream(scope, 0L).subscribe(event -> {
            fastVersions.add(event.getSceneVersion());
            fastLatch.countDown();
        });
        ControlledSubscriber slow = new ControlledSubscriber(slowLatch);
        broker.stream(scope, 0L).subscribe(slow);

        broker.publish(scope, event(1L, "first"));
        slow.requestOne();
        broker.publish(scope, event(2L, "second"));

        assertTrue(fastLatch.await(2, TimeUnit.SECONDS));
        assertTrue(slowLatch.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L), fastVersions);
        assertEquals(List.of(2L), slow.versions);
        fast.dispose();
        slow.cancelNow();
        assertEquals(0, broker.activeScopeCount());
    }

    @Test
    void rejectsBlankScopeAndNegativeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSceneEventBroker.SceneScope(" ", "client", "scene"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSceneEventBroker.SceneScope("tenant", "", "scene"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentSceneEventBroker.SceneScope("tenant", "client", "\t"));
        StepVerifier.create(broker.stream(scope, -1L))
                .expectError(IllegalArgumentException.class)
                .verify(Duration.ofSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> broker.publish(scope, null));
    }

    @Test
    void serviceBridgesBacklogAndLiveWithoutDuplicateOrGapDuringConcurrentHandoff() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        CountDownLatch backlogEntered = new CountDownLatch(1);
        CountDownLatch releaseBacklog = new CountDownLatch(1);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(2L, 3L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(1L);
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 0L, 1000))
                .thenAnswer(invocation -> {
                    backlogEntered.countDown();
                    assertTrue(releaseBacklog.await(5, TimeUnit.SECONDS));
                    return List.of(entity(event(1L, "one")), entity(event(2L, "two")));
                });
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 2L, 1000))
                .thenReturn(List.of(entity(event(3L, "three"))));
        AgentSceneServiceImpl service = service(eventDao);
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch receivedLatch = new CountDownLatch(3);
        var subscription = service.events("juyiting-main", 0L)
                .subscribeOn(Schedulers.boundedElastic()).take(3)
                .subscribe(event -> {
                    received.add(event.getSceneVersion());
                    receivedLatch.countDown();
                });

        await(backlogEntered);
        broker.publish(scope, event(2L, "duplicate-live"));
        broker.publish(scope, event(3L, "new-live"));
        releaseBacklog.countDown();

        await(receivedLatch);
        assertEquals(List.of(1L, 2L, 3L), received);
        subscription.dispose();
    }

    @Test
    void serviceCatchesUpPersistedEventsWhenLiveCallbacksArriveOutOfVersionOrder() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        CountDownLatch streamReady = new CountDownLatch(1);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(1L, 3L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenAnswer(invocation -> {
                    streamReady.countDown();
                    return 1L;
                });
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 1L, 1000))
                .thenAnswer(invocation -> {
                    assertTrue(Thread.currentThread().getName().startsWith("boundedElastic-"));
                    return List.of(entity(event(2L, "two")), entity(event(3L, "three")));
                });

        StepVerifier.create(service(eventDao).events("juyiting-main", 1L))
                .then(() -> {
                    await(streamReady);
                    broker.publish(scope, event(3L, "three-live-first"));
                    broker.publish(scope, event(2L, "two-live-delayed"));
                })
                .assertNext(event -> assertEquals(2L, event.getSceneVersion()))
                .assertNext(event -> assertEquals(3L, event.getSceneVersion()))
                .expectNoEvent(Duration.ofMillis(200))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        verify(eventDao).findAfterVersion(
                "tenant-a", "client-a", "juyiting-main", 1L, 1000);
    }

    @Test
    void persistedBacklogGapRequiresOneSafeResyncWithoutPartialReplay() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(3L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(1L);
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 0L, 1000))
                .thenReturn(List.of(entity(event(1L, "one")), entity(event(3L, "three"))));

        assertSingleSafeResync(service(eventDao).events("juyiting-main", 0L), 3L);
    }

    @Test
    void liveJumpWithMissingPersistedIntermediateRequiresResync() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        CountDownLatch streamReady = new CountDownLatch(1);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(1L, 3L, 3L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenAnswer(invocation -> {
                    streamReady.countDown();
                    return 1L;
                });
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 1L, 1000))
                .thenReturn(List.of(entity(event(3L, "three"))));

        StepVerifier.create(service(eventDao).events("juyiting-main", 1L))
                .then(() -> {
                    await(streamReady);
                    broker.publish(scope, event(3L, "three-live"));
                })
                .assertNext(event -> assertSafeResync(event, 3L))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void retentionChangeDuringBacklogHandoffRequiresResync() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(3L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(1L);
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 0L, 1000))
                .thenReturn(List.of(entity(event(2L, "two")), entity(event(3L, "three"))));

        assertSingleSafeResync(service(eventDao).events("juyiting-main", 0L), 3L);
    }

    @Test
    void cursorAheadOfDurableVersionRequiresResync() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(3L);

        assertSingleSafeResync(service(eventDao).events("juyiting-main", 5L), 3L);
        verify(eventDao, never()).findEarliestSceneVersion(any(), any(), any());
    }

    @Test
    void serviceEmitsExactlyOneSafeResyncWhenRetentionHasARealGap() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(50L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(40L);

        StepVerifier.create(service(eventDao).events("juyiting-main", 10L))
                .assertNext(event -> {
                    assertEquals(50L, event.getSceneVersion());
                    assertEquals("resync-required", event.getEventType());
                    assertEquals(null, event.getState());
                    assertEquals(null, event.getOccurredAt());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
        assertEquals(0, broker.activeScopeCount());
        verify(eventDao, never()).findAfterVersion(any(), any(), any(), anyLong(), anyInt());
    }

    @Test
    void retentionBoundaryReplaysEarliestWhenClientHasImmediatelyPrecedingVersion() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(40L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(40L);
        when(eventDao.findAfterVersion("tenant-a", "client-a", "juyiting-main", 39L, 1000))
                .thenReturn(List.of(entity(event(40L, "boundary"))));

        StepVerifier.create(service(eventDao).events("juyiting-main", 39L).take(1))
                .assertNext(event -> assertEquals(40L, event.getSceneVersion()))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void noRetainedRowsWithNewerCurrentVersionRequiresResync() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(8L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", "juyiting-main"))
                .thenReturn(null);

        StepVerifier.create(service(eventDao).events("juyiting-main", 7L))
                .assertNext(event -> {
                    assertEquals(8L, event.getSceneVersion());
                    assertEquals("resync-required", event.getEventType());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    void serviceEventReadsUseCurrentTenantClientAndRequestedSceneOnly() {
        setScope("tenant-b", "client-b");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        when(eventDao.findCurrentSceneVersion("tenant-b", "client-b", "scene-b")).thenReturn(0L);
        when(eventDao.findEarliestSceneVersion("tenant-b", "client-b", "scene-b")).thenReturn(null);

        StepVerifier.create(service(eventDao).events("scene-b", 0L))
                .then(() -> {
                    verify(eventDao, timeout(2_000))
                            .findCurrentSceneVersion("tenant-b", "client-b", "scene-b");
                    verify(eventDao, timeout(2_000))
                            .findEarliestSceneVersion("tenant-b", "client-b", "scene-b");
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertEquals(0, broker.activeScopeCount());
        verify(eventDao).findCurrentSceneVersion("tenant-b", "client-b", "scene-b");
        verify(eventDao).findEarliestSceneVersion("tenant-b", "client-b", "scene-b");
        verify(eventDao, never()).findCurrentSceneVersion(eq("tenant-a"), any(), any());
    }

    @Test
    void upsertPublishesOnlyAfterCommitAndNeverAfterRollback() {
        setScope("tenant-a", "client-a");
        AgentSceneEventDao eventDao = mock(AgentSceneEventDao.class);
        AgentSceneStateDao stateDao = mock(AgentSceneStateDao.class);
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        when(eventDao.nextSceneVersion("tenant-a", "client-a", "juyiting-main")).thenReturn(9L, 10L);
        when(eventDao.insert(eq("tenant-a"), eq("client-a"), eq("juyiting-main"), any())).thenReturn(1);
        when(stateDao.upsert(eq("tenant-a"), eq("client-a"), eq("juyiting-main"), any())).thenReturn(1);
        when(stateDao.findActiveByScene(eq("tenant-a"), eq("client-a"), eq("juyiting-main"), anyLong()))
                .thenReturn(List.of());
        when(runtimeDao.findRosterByOwner("client-a", "tenant-a", null, null))
                .thenReturn(List.of(runtime("agent-songjiang", "songjiang")));
        AgentSceneServiceImpl service = new AgentSceneServiceImpl(stateDao, eventDao, runtimeDao, broker);
        List<Long> liveVersions = new CopyOnWriteArrayList<>();
        var live = broker.stream(scope, 0L).subscribe(event -> liveVersions.add(event.getSceneVersion()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upsertState("juyiting-main", stateRequest());
            assertTrue(liveVersions.isEmpty());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.get(0).afterCommit();
            assertEquals(List.of(9L), liveVersions);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.upsertState("juyiting-main", stateRequest());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            synchronizations.get(0).afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            assertEquals(List.of(9L), liveVersions);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            live.dispose();
        }
    }

    private AgentSceneEventDTO event(long version, String agentId) {
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId(agentId);
        state.setPersonaCode("songjiang");
        state.setBehavior("moving");
        state.setTargetRegionId("council-table");
        state.setPhase("moving");
        state.setStateVersion(version);
        state.setStartedAt(1_000L);
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(version);
        event.setEventType("agent-scene-state-updated");
        event.setState(state);
        event.setOccurredAt(2_000L);
        return event;
    }

    private AgentSceneEventEntity entity(AgentSceneEventDTO event) {
        AgentSceneEventEntity entity = new AgentSceneEventEntity();
        entity.setSceneVersion(event.getSceneVersion());
        entity.setEventType(event.getEventType());
        entity.setOccurredAt(event.getOccurredAt());
        entity.setEventJson(JsonUtil.toJson(event));
        return entity;
    }

    private void assertSingleSafeResync(Flux<AgentSceneEventDTO> events, long currentVersion) {
        StepVerifier.create(events)
                .assertNext(event -> assertSafeResync(event, currentVersion))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    private void assertSafeResync(AgentSceneEventDTO event, long currentVersion) {
        assertEquals(currentVersion, event.getSceneVersion());
        assertEquals("resync-required", event.getEventType());
        assertEquals(null, event.getState());
        assertEquals(null, event.getOccurredAt());
    }

    private AgentSceneServiceImpl service(AgentSceneEventDao eventDao) {
        return new AgentSceneServiceImpl(
                mock(AgentSceneStateDao.class), eventDao, mock(AgentRuntimeDao.class), broker);
    }

    private void setScope(String tenantId, String clientId) {
        EsContext context = new EsContext();
        context.setJiacn(tenantId);
        context.setClientId(clientId);
        EsContextHolder.setContext(context);
    }

    private AgentRuntimeEntity runtime(String agentId, String personaCode) {
        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId(agentId);
        runtime.setPersonaCode(personaCode);
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        runtime.setOwnerJiacn("tenant-a");
        runtime.setClientId("client-a");
        return runtime;
    }

    private AgentSceneStateDTO stateRequest() {
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId("agent-songjiang");
        state.setPersonaCode("songjiang");
        state.setBehavior("moving");
        state.setOriginRegionId("main-seat");
        state.setTargetRegionId("council-table");
        state.setPhase("moving");
        state.setStartedAt(1_000L);
        state.setExpectedArrivalAt(2_000L);
        state.setExpiresAt(System.currentTimeMillis() + 60_000L);
        return state;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class ControlledSubscriber extends BaseSubscriber<AgentSceneEventDTO> {
        private final List<Long> versions = new CopyOnWriteArrayList<>();
        private final CountDownLatch latch;

        private ControlledSubscriber(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            // Intentionally do not request until the first event has been offered.
        }

        @Override
        protected void hookOnNext(AgentSceneEventDTO value) {
            versions.add(value.getSceneVersion());
            latch.countDown();
        }

        private void requestOne() {
            request(1);
        }

        private void cancelNow() {
            cancel();
        }
    }
}
