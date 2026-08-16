package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.entity.AgentTaskEventEntity;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventBroker.TaskEventWakeup;
import cn.jia.agent.service.AgentTaskEventReplayService.DurableEvent;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayBackpressureException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplayCapacityException;
import cn.jia.agent.service.AgentTaskEventReplayService.ReplaySignal;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncReason;
import cn.jia.agent.service.AgentTaskEventReplayService.ResyncRequired;
import cn.jia.agent.service.AgentTaskEventReplayService.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.reactivestreams.Subscription;
import reactor.core.Disposable;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskEventReplayServiceImplTest {
    private static final TaskScope SCOPE =
            new TaskScope("Tenant-A", "client-a", "caf\u00e9");
    private static final AgentTaskEventBroker.TaskScope BROKER_SCOPE =
            new AgentTaskEventBroker.TaskScope(
                    SCOPE.tenantId(), SCOPE.clientId(), SCOPE.taskId());
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private TrackingBroker broker;
    private AgentTaskEventDao eventDao;
    private MutableDurableStore store;
    private ThreadPoolExecutor worker;
    private ThreadPoolExecutor delivery;
    private ScheduledThreadPoolExecutor timer;
    private AgentTaskEventReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        broker = new TrackingBroker();
        eventDao = mock(AgentTaskEventDao.class);
        store = new MutableDurableStore(SCOPE);
        bindStore();
        worker = workerExecutor(2, 8);
        delivery = deliveryExecutor(12);
        timer = timerExecutor();
        service = new AgentTaskEventReplayServiceImpl(
                eventDao, broker, worker, delivery, timer,
                policy(Duration.ofHours(1)), resourceLimits(16, 8, 12));
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
        if (broker != null) {
            broker.close();
        }
        shutdown(timer);
        shutdown(worker);
        shutdown(delivery);
    }

    @Test
    void rejectsNegativeCursorAndPostCloseSubscriptionsWithoutAllocatingLiveState() {
        StepVerifier.create(service.replay(SCOPE, -1L))
                .expectErrorMatches(error -> error instanceof IllegalArgumentException
                        && error.getMessage().contains("negative"))
                .verify(VERIFY_TIMEOUT);
        assertEquals(0, service.activeSubscriptionCount());
        assertEquals(0, broker.trackedLeaseCount());

        Flux<ReplaySignal> createdBeforeClose = service.replay(SCOPE, 0L);
        service.close();
        StepVerifier.create(createdBeforeClose)
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("closed"))
                .verify(VERIFY_TIMEOUT);
        StepVerifier.create(service.replay(SCOPE, 0L))
                .expectErrorMatches(error -> error instanceof IllegalStateException
                        && error.getMessage().contains("closed"))
                .verify(VERIFY_TIMEOUT);
        assertEquals(0, service.activeSubscriptionCount());
        assertEquals(0, broker.trackedLeaseCount());
    }

    @Test
    void emptyInitialHistoryStaysLiveAndAllDurableReadsRunOnlyOnReplayWorker() {
        store.setCurrent(0L);

        StepVerifier.create(service.replay(SCOPE, 0L))
                .expectSubscription()
                .then(() -> awaitCondition(() -> store.currentCalls.get() >= 1))
                .expectNoEvent(Duration.ofMillis(80))
                .thenCancel()
                .verify(VERIFY_TIMEOUT);

        awaitCleaned();
        assertEquals(0, store.earliestCalls.get());
        assertTrue(store.daoThreads.stream().allMatch(
                name -> name.startsWith("c03-replay-worker-")), store.daoThreads.toString());
        verify(eventDao).findCurrentVersion("Tenant-A", "client-a", "caf\u00e9");
    }

    @Test
    void replaysExactEventsAcrossPagesAndSupportsProcessRestartCursor() {
        store.appendRange(1L, 5L);

        StepVerifier.create(service.replay(SCOPE, 0L).take(5))
                .assertNext(signal -> assertDurable(signal, 1L))
                .assertNext(signal -> assertDurable(signal, 2L))
                .assertNext(signal -> assertDurable(signal, 3L))
                .assertNext(signal -> assertDurable(signal, 4L))
                .assertNext(signal -> assertDurable(signal, 5L))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
        awaitCleaned();

        StepVerifier.create(service.replay(SCOPE, 3L).take(2))
                .assertNext(signal -> assertDurable(signal, 4L))
                .assertNext(signal -> assertDurable(signal, 5L))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
        awaitCleaned();

        assertTrue(store.pageLimits.stream().allMatch(limit -> limit == 2));
        assertTrue(store.pageCursors.containsAll(List.of(0L, 2L, 4L, 3L)));
        verify(eventDao, org.mockito.Mockito.atLeastOnce()).findEarliestVersion(
                "Tenant-A", "client-a", "café");
        verify(eventDao, org.mockito.Mockito.atLeastOnce()).findAfterVersion(
                org.mockito.ArgumentMatchers.eq("Tenant-A"),
                org.mockito.ArgumentMatchers.eq("client-a"),
                org.mockito.ArgumentMatchers.eq("café"), anyLong(),
                org.mockito.ArgumentMatchers.eq(2));
    }

    @Test
    void liveFirstClosesWriteWindowBetweenSubscriptionAndFirstDurableRead() {
        CountDownLatch currentReadEntered = new CountDownLatch(1);
        CountDownLatch releaseCurrentRead = new CountDownLatch(1);
        store.currentSupplier = () -> {
            currentReadEntered.countDown();
            await(releaseCurrentRead);
            return store.currentValue();
        };

        StepVerifier.create(service.replay(SCOPE, 0L).take(1))
                .then(() -> {
                    await(broker.firstSubscribed);
                    await(currentReadEntered);
                    store.append(1L);
                    broker.publish(BROKER_SCOPE, 1L);
                    releaseCurrentRead.countDown();
                })
                .assertNext(signal -> assertDurable(signal, 1L))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        awaitCleaned();
        assertEquals(0, broker.trackedLeaseCount());
    }

    @Test
    void advancingHighWaterDuringPagedCatchUpIsReadAgainAndFullyDelivered() {
        store.appendRange(1L, 2L);
        CountDownLatch firstPageEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstPage = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        store.pageFunction = cursor -> {
            if (cursor == 0L && first.compareAndSet(true, false)) {
                firstPageEntered.countDown();
                await(releaseFirstPage);
            }
            return store.defaultPage(cursor, 2);
        };

        StepVerifier.create(service.replay(SCOPE, 0L).take(3))
                .then(() -> {
                    await(firstPageEntered);
                    store.append(3L);
                    releaseFirstPage.countDown();
                })
                .assertNext(signal -> assertDurable(signal, 1L))
                .assertNext(signal -> assertDurable(signal, 2L))
                .assertNext(signal -> assertDurable(signal, 3L))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertTrue(store.currentCalls.get() >= 2);
        awaitCleaned();
    }

    @Test
    void duplicateAndOutOfOrderWakeupsCoalesceIntoOneSerializedDurableCatchUp() {
        store.setCurrent(0L);
        List<Long> received = new CopyOnWriteArrayList<>();
        Disposable replay = service.replay(SCOPE, 0L)
                .ofType(DurableEvent.class)
                .map(DurableEvent::eventVersion)
                .take(3)
                .subscribe(received::add);
        awaitCondition(() -> broker.trackedLeaseCount() == 1
                && store.currentCalls.get() >= 1
                && service.scheduledWorkerTaskCount() == 0);
        store.appendRange(1L, 3L);

        broker.publish(BROKER_SCOPE, 3L);
        broker.publish(BROKER_SCOPE, 1L);
        broker.publish(BROKER_SCOPE, 2L);
        broker.publish(BROKER_SCOPE, 3L);

        awaitCondition(() -> received.equals(List.of(1L, 2L, 3L)));
        replay.dispose();
        awaitCleaned();
        assertEquals(1, store.maxConcurrentDaoCalls.get());
    }

    @Test
    void periodicDurableCheckRecoversDroppedWakeupAndBrokerClosedCrossInstanceWrite() {
        replaceService(policy(Duration.ofMillis(40)));
        store.setCurrent(0L);
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        service.replay(SCOPE, 0L)
                .ofType(DurableEvent.class)
                .map(DurableEvent::eventVersion)
                .take(2)
                .subscribe(received::add, ignored -> { }, completed::countDown);

        awaitCondition(() -> store.currentCalls.get() >= 1
                && service.scheduledWorkerTaskCount() == 0);
        broker.dropNextWakeup();
        store.append(1L);
        broker.publish(BROKER_SCOPE, 1L);
        awaitCondition(() -> received.equals(List.of(1L))
                && service.scheduledWorkerTaskCount() == 0);

        int readsBeforeClosedBrokerWrite = store.currentCalls.get();
        broker.close();
        store.append(2L); // Simulates another process: no local wakeup can exist.
        await(completed);

        assertEquals(List.of(1L, 2L), received);
        assertEquals(1, broker.testDroppedWakeups.get());
        assertTrue(store.currentCalls.get() > readsBeforeClosedBrokerWrite);
        assertTrue(store.daoThreads.stream().allMatch(
                name -> name.startsWith("c03-replay-worker-")), store.daoThreads.toString());
        awaitCleaned();
    }

    @Test
    void cursorAheadHistoryGapAndRetentionGapHaveStableTerminalReasons() {
        store.appendRange(1L, 3L);
        assertResync(service.replay(SCOPE, 5L), 3L, ResyncReason.CURSOR_AHEAD);
        awaitCleaned();

        store.clearRowsAndSetCurrent(2L);
        assertResync(service.replay(SCOPE, 0L), 2L, ResyncReason.HISTORY_GAP);
        awaitCleaned();

        store.clearRowsAndSetCurrent(5L);
        store.put(entity(SCOPE, 3L));
        store.put(entity(SCOPE, 4L));
        store.put(entity(SCOPE, 5L));
        assertResync(service.replay(SCOPE, 1L), 5L, ResyncReason.RETENTION_GAP);
        awaitCleaned();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pageGapCases")
    void everyMalformedOrNonContiguousPageFailsClosed(
            String name,
            long currentVersion,
            Map<Long, List<AgentTaskEventEntity>> pages,
            List<Long> emittedPrefix) {
        store.clearRowsAndSetCurrent(currentVersion);
        store.earliestSupplier = () -> 1L;
        store.pageFunction = cursor -> pages.getOrDefault(cursor, List.of());

        StepVerifier.Step<ReplaySignal> verification =
                StepVerifier.create(service.replay(SCOPE, 0L));
        for (long version : emittedPrefix) {
            verification = verification.assertNext(signal -> assertDurable(signal, version));
        }
        verification
                .assertNext(signal -> assertResyncSignal(
                        signal, currentVersion, ResyncReason.PAGE_GAP))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
        awaitCleaned();
    }

    static Stream<Arguments> pageGapCases() {
        AgentTaskEventEntity one = entity(SCOPE, 1L);
        AgentTaskEventEntity two = entity(SCOPE, 2L);
        AgentTaskEventEntity three = entity(SCOPE, 3L);
        AgentTaskEventEntity four = entity(SCOPE, 4L);
        AgentTaskEventEntity wrongScopeTwo = entity(
                new TaskScope("tenant-other", SCOPE.clientId(), SCOPE.taskId()), 2L);
        return Stream.of(
                Arguments.of("missing first event", 3L,
                        Map.of(0L, List.of(two, three)), List.of()),
                Arguments.of("middle gap", 3L,
                        Map.of(0L, List.of(one, three)), List.of(1L)),
                Arguments.of("missing final event", 3L,
                        Map.of(0L, List.of(one, two)), List.of(1L, 2L)),
                Arguments.of("page boundary gap", 4L,
                        Map.of(0L, List.of(one, two), 2L, List.of(four)),
                        List.of(1L, 2L)),
                Arguments.of("duplicate version", 2L,
                        Map.of(0L, List.of(one, one)), List.of(1L)),
                Arguments.of("non ascending version", 3L,
                        Map.of(0L, List.of(one, three)), List.of(1L)),
                Arguments.of("wrong byte exact scope", 2L,
                        Map.of(0L, List.of(one, wrongScopeTwo)), List.of(1L)),
                Arguments.of("null page item", 2L,
                        Map.of(0L, Arrays.asList(one, null)), List.of(1L)));
    }

    @Test
    void replayBudgetExhaustionIsTerminalAfterBoundedPrefix() {
        replaceService(new AgentTaskEventReplayServiceImpl.ReplayPolicy(
                2, 2, 4, 4, Duration.ofHours(1)));
        store.appendRange(1L, 3L);

        StepVerifier.create(service.replay(SCOPE, 0L))
                .assertNext(signal -> assertDurable(signal, 1L))
                .assertNext(signal -> assertDurable(signal, 2L))
                .assertNext(signal -> assertResyncSignal(
                        signal, 3L, ResyncReason.REPLAY_BUDGET_EXHAUSTED))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        assertTrue(store.pageCalls.get() <= 1);
        awaitCleaned();
    }

    @Test
    void daoFailureAndUnprovableDurableValuesProduceOneTerminalResync() {
        store.currentSupplier = () -> {
            throw new IllegalStateException("database unavailable");
        };
        assertResync(service.replay(SCOPE, 7L),
                0L, ResyncReason.DURABLE_STATE_UNPROVABLE);
        awaitCleaned();

        store.currentSupplier = () -> -1L;
        assertResync(service.replay(SCOPE, 0L),
                0L, ResyncReason.DURABLE_STATE_UNPROVABLE);
        awaitCleaned();
    }

    @Test
    void concurrentHintsWhileDaoIsBlockedUseOneWorkerAndNoPerHintQueueing() {
        replaceService(policy(Duration.ofMillis(10)));
        CountDownLatch currentEntered = new CountDownLatch(1);
        CountDownLatch releaseCurrent = new CountDownLatch(1);
        store.append(1L);
        store.currentSupplier = () -> {
            currentEntered.countDown();
            await(releaseCurrent);
            return store.currentValue();
        };
        List<Long> received = new CopyOnWriteArrayList<>();
        Disposable replay = service.replay(SCOPE, 0L)
                .ofType(DurableEvent.class)
                .map(DurableEvent::eventVersion)
                .take(1)
                .subscribe(received::add);
        await(currentEntered);

        for (int i = 0; i < 200; i++) {
            broker.publish(BROKER_SCOPE, (i % 5) + 1L);
        }
        awaitCondition(() -> service.scheduledWorkerTaskCount() == 1);
        assertEquals(1, service.scheduledWorkerTaskCount());
        assertEquals(1, service.inFlightCatchUpCount());
        assertEquals(0, worker.getQueue().size());
        assertEquals(1, store.maxConcurrentDaoCalls.get());

        releaseCurrent.countDown();
        awaitCondition(() -> received.equals(List.of(1L)));
        replay.dispose();
        awaitCleaned();
    }

    @Test
    void saturatedWorkerDropsSchedulingTriggerAndPeriodicRetryRecoversOffCallerThreads() {
        replaceService(policy(Duration.ofMillis(20)));
        CountDownLatch blockersStarted = new CountDownLatch(2);
        CountDownLatch releaseBlockers = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            worker.execute(() -> {
                blockersStarted.countDown();
                await(releaseBlockers);
            });
        }
        await(blockersStarted);
        assertEquals(8, worker.getQueue().size());
        store.append(1L);
        List<Long> received = new CopyOnWriteArrayList<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        service.replay(SCOPE, 0L)
                .ofType(DurableEvent.class)
                .take(1)
                .subscribe(event -> {
                    callbackThread.set(Thread.currentThread().getName());
                    received.add(event.eventVersion());
                }, ignored -> { }, completed::countDown);

        awaitCondition(() -> service.scheduledWorkerTaskCount() == 0);
        assertEquals(0, store.currentCalls.get());
        assertTrue(received.isEmpty());
        assertEquals(1, broker.trackedLeaseCount());

        releaseBlockers.countDown();
        await(completed);
        assertEquals(List.of(1L), received);
        assertTrue(callbackThread.get().startsWith("c03-replay-delivery-"), callbackThread.get());
        awaitCleaned();
    }

    @Test
    void insufficientDemandFailsClosedWithoutBufferingWholeHistory() {
        store.appendRange(1L, 10L);
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Long> received = new CopyOnWriteArrayList<>();
        BaseSubscriber<ReplaySignal> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }

            @Override
            protected void hookOnNext(ReplaySignal value) {
                received.add(((DurableEvent) value).eventVersion());
                first.countDown();
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                failure.set(throwable);
                terminal.countDown();
            }
        };

        service.replay(SCOPE, 0L).subscribe(subscriber);
        await(first);
        await(terminal);

        assertEquals(List.of(1L), received);
        assertInstanceOf(ReplayBackpressureException.class, failure.get());
        assertTrue(store.pageCalls.get() <= 1);
        awaitCleaned();
    }

    @Test
    void blockingDownstreamCallbackCannotQueueHintsAndCancelReturnsBeforeCallbackExits()
            throws Exception {
        store.appendRange(1L, 2L);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        BaseSubscriber<ReplaySignal> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(Long.MAX_VALUE);
            }

            @Override
            protected void hookOnNext(ReplaySignal value) {
                callbackEntered.countDown();
                while (releaseCallback.getCount() > 0) {
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException expected) {
                        callbackInterrupted.countDown();
                        // Deliberately remain blocked until the test proves cancel returned.
                    }
                }
            }
        };
        service.replay(SCOPE, 0L).subscribe(subscriber);
        await(callbackEntered);

        for (int i = 0; i < 100; i++) {
            broker.publish(BROKER_SCOPE, i + 1L);
        }
        awaitCondition(() -> service.scheduledWorkerTaskCount() == 0
                && service.inFlightCatchUpCount() == 0);
        assertEquals(1, service.inFlightDeliveryCount());
        assertEquals(0, worker.getQueue().size());

        Thread cancel = new Thread(subscriber::cancel, "c03-blocking-downstream-cancel");
        cancel.start();
        cancel.join(TimeUnit.SECONDS.toMillis(2));
        assertTrue(!cancel.isAlive(), "cancel must not wait for a blocking callback");
        await(callbackInterrupted);
        assertEquals(0, service.activeSubscriptionCount());
        assertEquals(0, service.activeTimerCount());
        assertEquals(0, broker.trackedLeaseCount());

        assertEquals(1, service.deliveryPermitCount());
        releaseCallback.countDown();
        awaitCleaned();
        awaitCondition(() -> service.deliveryPermitCount() == 0
                && service.inFlightDeliveryCount() == 0);
    }

    @Test
    void blockedCancelledDeliveriesNeverOccupyCatchUpWorkersOrStarveFifthReplay() {
        replaceResources(policy(Duration.ofHours(1)), resourceLimits(4, 4, 6), 4, 8, 6);
        store.append(1L);
        CountDownLatch callbacksEntered = new CountDownLatch(4);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);
        List<BaseSubscriber<ReplaySignal>> blockedSubscribers = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            BaseSubscriber<ReplaySignal> subscriber = blockingSubscriber(
                    callbacksEntered, releaseCallbacks);
            blockedSubscribers.add(subscriber);
            service.replay(SCOPE, 0L).subscribe(subscriber);
        }
        await(callbacksEntered);
        awaitCondition(() -> service.inFlightCatchUpCount() == 0
                && service.scheduledWorkerTaskCount() == 0);
        assertEquals(4, service.inFlightDeliveryCount());

        blockedSubscribers.forEach(BaseSubscriber::cancel);
        awaitCondition(() -> service.activeSubscriptionCount() == 0
                && service.activeTimerCount() == 0
                && broker.trackedLeaseCount() == 0
                && service.pendingTimerTaskCount() == 0);
        assertEquals(4, service.deliveryPermitCount());
        assertEquals(0, service.inFlightCatchUpCount());

        StepVerifier.create(service.replay(SCOPE, 0L).take(1))
                .assertNext(signal -> assertDurable(signal, 1L))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
        awaitCondition(() -> service.activeSubscriptionCount() == 0
                && service.deliveryPermitCount() == 4
                && service.inFlightCatchUpCount() == 0);

        releaseCallbacks.countDown();
        awaitCondition(() -> service.deliveryPermitCount() == 0
                && service.inFlightDeliveryCount() == 0);
    }

    @Test
    void repeatedBlockedCancelIsBoundedAndFurtherSubscriptionFailsBeforeAllocation() {
        replaceResources(policy(Duration.ofHours(1)), resourceLimits(2, 1, 3), 4, 8, 3);
        store.append(1L);
        CountDownLatch callbacksEntered = new CountDownLatch(3);
        CountDownLatch releaseCallbacks = new CountDownLatch(1);

        for (int index = 0; index < 3; index++) {
            int expectedDeliveries = index + 1;
            BaseSubscriber<ReplaySignal> subscriber = blockingSubscriber(
                    callbacksEntered, releaseCallbacks);
            service.replay(SCOPE, 0L).subscribe(subscriber);
            long expectedRemainingCallbacks = 3L - expectedDeliveries;
            awaitCondition(() -> callbacksEntered.getCount() == expectedRemainingCallbacks
                    && service.inFlightDeliveryCount() == expectedDeliveries);
            subscriber.cancel();
            awaitCondition(() -> service.activeSubscriptionCount() == 0
                    && service.activeTimerCount() == 0
                    && broker.trackedLeaseCount() == 0
                    && service.pendingTimerTaskCount() == 0);
        }
        await(callbacksEntered);
        assertEquals(3, service.deliveryPermitCount());
        int poolSizeAtCapacity = delivery.getPoolSize();
        int pendingDeliveriesAtCapacity = service.pendingDeliveryTaskCount();
        int durableReadsAtCapacity = store.currentCalls.get();

        StepVerifier.create(service.replay(SCOPE, 0L))
                .expectErrorMatches(error -> error instanceof ReplayCapacityException
                        && error.getMessage().equals(
                                "Task event replay capacity is exhausted"))
                .verify(VERIFY_TIMEOUT);

        assertEquals(0, service.activeSubscriptionCount());
        assertEquals(0, service.activeTimerCount());
        assertEquals(0, broker.trackedLeaseCount());
        assertEquals(0, service.pendingTimerTaskCount());
        assertEquals(3, service.deliveryPermitCount());
        assertEquals(poolSizeAtCapacity, delivery.getPoolSize());
        assertEquals(pendingDeliveriesAtCapacity, service.pendingDeliveryTaskCount());
        assertEquals(durableReadsAtCapacity, store.currentCalls.get());
        assertEquals(0, worker.getQueue().size());

        releaseCallbacks.countDown();
        awaitCondition(() -> service.deliveryPermitCount() == 0
                && service.inFlightDeliveryCount() == 0);
    }

    @Test
    void activeSubscriptionCapFailsBeforeBrokerTimerOrWorkerAndTimerQueueConverges() {
        replaceResources(policy(Duration.ofHours(1)), resourceLimits(2, 2, 4), 2, 4, 4);
        store.setCurrent(0L);
        Disposable first = service.replay(SCOPE, 0L).subscribe();
        Disposable second = service.replay(SCOPE, 0L).subscribe();
        awaitCondition(() -> service.activeSubscriptionCount() == 2
                && service.activeTimerCount() == 2
                && broker.trackedLeaseCount() == 2
                && service.pendingTimerTaskCount() == 2
                && service.scheduledWorkerTaskCount() == 0);
        int durableReadsAtCapacity = store.currentCalls.get();

        StepVerifier.create(service.replay(SCOPE, 0L))
                .expectError(ReplayCapacityException.class)
                .verify(VERIFY_TIMEOUT);

        assertEquals(2, service.activeSubscriptionCount());
        assertEquals(2, service.activeTimerCount());
        assertEquals(2, broker.trackedLeaseCount());
        assertEquals(2, service.pendingTimerTaskCount());
        assertEquals(durableReadsAtCapacity, store.currentCalls.get());
        first.dispose();
        second.dispose();
        awaitCondition(() -> service.activeSubscriptionCount() == 0
                && service.activeTimerCount() == 0
                && broker.trackedLeaseCount() == 0
                && service.pendingTimerTaskCount() == 0
                && service.deliveryPermitCount() == 0);
    }

    @Test
    void fullSignalQueueStopsDurableReadsAndCleansLogicalResourcesBeforeCallbackReturns() {
        replaceResources(
                new AgentTaskEventReplayServiceImpl.ReplayPolicy(
                        1, 20, 20, 10, Duration.ofMillis(20)),
                resourceLimits(1, 1, 2), 2, 4, 2);
        store.appendRange(1L, 5L);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        List<Long> received = new CopyOnWriteArrayList<>();
        store.pageFunction = cursor -> {
            if (cursor == 1L) {
                await(callbackEntered);
            }
            return store.defaultPage(cursor, 1);
        };
        BaseSubscriber<ReplaySignal> subscriber = new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(Long.MAX_VALUE);
            }

            @Override
            protected void hookOnNext(ReplaySignal value) {
                received.add(((DurableEvent) value).eventVersion());
                callbackEntered.countDown();
                while (releaseCallback.getCount() > 0) {
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately non-cooperative until the queue-full state is observed.
                    }
                }
            }

            @Override
            protected void hookOnError(Throwable throwable) {
                assertInstanceOf(ReplayBackpressureException.class, throwable);
                errors.incrementAndGet();
                terminal.countDown();
            }

            @Override
            protected void hookOnComplete() {
                completions.incrementAndGet();
                terminal.countDown();
            }
        };

        service.replay(SCOPE, 0L).subscribe(subscriber);
        await(callbackEntered);
        awaitCondition(() -> service.activeSubscriptionCount() == 0
                && service.activeTimerCount() == 0
                && broker.trackedLeaseCount() == 0
                && service.inFlightCatchUpCount() == 0
                && service.scheduledWorkerTaskCount() == 0
                && service.pendingTimerTaskCount() == 0);
        int readsAfterOverflow = store.pageCalls.get();
        try {
            Thread.sleep(100L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
        assertEquals(readsAfterOverflow, store.pageCalls.get());
        assertEquals(1, service.deliveryPermitCount());

        releaseCallback.countDown();
        await(terminal);
        assertEquals(List.of(1L), received);
        assertEquals(1, errors.get());
        assertEquals(0, completions.get());
        awaitCondition(() -> service.deliveryPermitCount() == 0
                && service.inFlightDeliveryCount() == 0);
    }

    @Test
    void cancellationInterruptsInFlightDaoAndReleasesBrokerTimerAndWorker() {
        CountDownLatch currentEntered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        store.currentSupplier = () -> {
            currentEntered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                return 0L;
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("cancelled durable read", expected);
            }
        };

        Disposable replay = service.replay(SCOPE, 0L).subscribe();
        await(currentEntered);
        assertEquals(1, broker.trackedLeaseCount());
        assertEquals(1, service.activeTimerCount());
        assertEquals(1, service.inFlightCatchUpCount());

        replay.dispose();
        await(interrupted);
        awaitCleaned();
        assertEquals(1, broker.finalSignals.get());
    }

    @Test
    void closeCancelsActiveSubscriptionAndIsIdempotentWithCancelRace() throws Exception {
        store.setCurrent(0L);
        Disposable replay = service.replay(SCOPE, 0L).subscribe();
        awaitCondition(() -> broker.trackedLeaseCount() == 1);

        Thread cancel = new Thread(replay::dispose, "c03-cancel-racer");
        cancel.start();
        service.close();
        cancel.join(TimeUnit.SECONDS.toMillis(2));
        assertTrue(!cancel.isAlive());

        awaitCleaned();
        service.close();
        assertEquals(1, broker.finalSignals.get());
    }

    @Test
    void productionDefaultsUseOwnedSharedWorkersAndCloseReleasesActiveState() {
        service.close();
        store.daoThreads.clear();
        service = new AgentTaskEventReplayServiceImpl(eventDao, broker);
        Disposable replay = service.replay(SCOPE, 0L).subscribe();
        awaitCondition(() -> store.currentCalls.get() >= 1
                && service.scheduledWorkerTaskCount() == 0);

        assertTrue(store.daoThreads.stream().allMatch(
                name -> name.startsWith("agent-task-replay-worker-")),
                store.daoThreads.toString());
        service.close();
        awaitCleaned();
        replay.dispose();
    }

    @Test
    void publicSignalsKeepLongVersionsAndImmutableByteExactScope() throws Exception {
        assertEquals(long.class, Arrays.stream(DurableEvent.class.getRecordComponents())
                .filter(component -> component.getName().equals("eventVersion"))
                .findFirst().orElseThrow().getType());
        assertEquals(long.class, Arrays.stream(ResyncRequired.class.getRecordComponents())
                .filter(component -> component.getName().equals("currentVersion"))
                .findFirst().orElseThrow().getType());
        assertEquals(SCOPE, new DurableEvent(
                SCOPE, 1L, "event-1", "progress_reported", "system", null,
                "task", SCOPE.taskId(), "{}", 1L).scope());
        assertThrows(IllegalArgumentException.class, () -> new ResyncRequired(
                SCOPE, -1L, ResyncReason.PAGE_GAP));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScope(" padded", "client", "task"));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskScope("tenant", "client", "task\n"));
    }

    private void bindStore() {
        when(eventDao.findCurrentVersion(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> store.observe(store.currentCalls, store.currentSupplier));
        when(eventDao.findEarliestVersion(anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> store.observe(store.earliestCalls, store.earliestSupplier));
        when(eventDao.findAfterVersion(
                anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenAnswer(invocation -> {
                    long cursor = invocation.getArgument(3);
                    int limit = invocation.getArgument(4);
                    store.pageCursors.add(cursor);
                    store.pageLimits.add(limit);
                    return store.observe(store.pageCalls,
                            () -> store.pageFunction.apply(cursor));
                });
    }

    private void replaceResources(
            AgentTaskEventReplayServiceImpl.ReplayPolicy replacementPolicy,
            AgentTaskEventReplayServiceImpl.ReplayResourceLimits replacementLimits,
            int catchUpThreads,
            int catchUpQueueCapacity,
            int deliverySlots) {
        service.close();
        shutdown(timer);
        shutdown(worker);
        shutdown(delivery);
        worker = workerExecutor(catchUpThreads, catchUpQueueCapacity);
        delivery = deliveryExecutor(deliverySlots);
        timer = timerExecutor();
        service = new AgentTaskEventReplayServiceImpl(
                eventDao, broker, worker, delivery, timer,
                replacementPolicy, replacementLimits);
    }

    private static BaseSubscriber<ReplaySignal> blockingSubscriber(
            CountDownLatch entered,
            CountDownLatch release) {
        return new BaseSubscriber<>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(Long.MAX_VALUE);
            }

            @Override
            protected void hookOnNext(ReplaySignal value) {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Intentionally ignore cancellation interrupts until explicitly released.
                    }
                }
            }
        };
    }

    private void replaceService(AgentTaskEventReplayServiceImpl.ReplayPolicy replacementPolicy) {
        service.close();
        service = new AgentTaskEventReplayServiceImpl(
                eventDao, broker, worker, delivery, timer,
                replacementPolicy, resourceLimits(16, 8, 12));
    }

    private static AgentTaskEventReplayServiceImpl.ReplayPolicy policy(Duration interval) {
        return new AgentTaskEventReplayServiceImpl.ReplayPolicy(2, 20, 20, 10, interval);
    }

    private static AgentTaskEventReplayServiceImpl.ReplayResourceLimits resourceLimits(
            int queueCapacity,
            int activeSubscriptions,
            int deliverySlots) {
        return new AgentTaskEventReplayServiceImpl.ReplayResourceLimits(
                queueCapacity, activeSubscriptions, deliverySlots);
    }

    private void assertResync(
            Flux<ReplaySignal> replay,
            long currentVersion,
            ResyncReason reason) {
        StepVerifier.create(replay)
                .assertNext(signal -> assertResyncSignal(signal, currentVersion, reason))
                .expectComplete()
                .verify(VERIFY_TIMEOUT);
    }

    private static void assertDurable(ReplaySignal signal, long version) {
        DurableEvent event = assertInstanceOf(DurableEvent.class, signal);
        assertEquals(SCOPE, event.scope());
        assertEquals(version, event.eventVersion());
        assertEquals("event-" + version, event.eventId());
        assertEquals("{\"version\":" + version + "}", event.eventJson());
    }

    private static void assertResyncSignal(
            ReplaySignal signal,
            long currentVersion,
            ResyncReason reason) {
        ResyncRequired resync = assertInstanceOf(ResyncRequired.class, signal);
        assertEquals(SCOPE, resync.scope());
        assertEquals(currentVersion, resync.currentVersion());
        assertEquals(reason, resync.reason());
    }

    private void awaitCleaned() {
        awaitCondition(() -> service.activeSubscriptionCount() == 0
                && service.activeTimerCount() == 0
                && service.scheduledWorkerTaskCount() == 0
                && service.inFlightCatchUpCount() == 0
                && service.inFlightDeliveryCount() == 0
                && service.deliveryPermitCount() == 0
                && broker.trackedLeaseCount() == 0);
    }

    private static AgentTaskEventEntity entity(TaskScope scope, long version) {
        AgentTaskEventEntity entity = new AgentTaskEventEntity();
        entity.setTenantId(scope.tenantId());
        entity.setClientId(scope.clientId());
        entity.setTaskId(scope.taskId());
        entity.setEventVersion(version);
        entity.setEventId("event-" + version);
        entity.setEventType("progress_reported");
        entity.setActorType("system");
        entity.setActorId(null);
        entity.setAggregateType("task");
        entity.setAggregateId(scope.taskId());
        entity.setEventJson("{\"version\":" + version + "}");
        entity.setOccurredAt(1_000L + version);
        return entity;
    }

    private static ThreadPoolExecutor workerExecutor(int threads, int queueCapacity) {
        return new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), namedFactory("c03-replay-worker"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadPoolExecutor deliveryExecutor(int slots) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                slots, slots, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(slots),
                namedFactory("c03-replay-delivery"),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static ScheduledThreadPoolExecutor timerExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1, namedFactory("c03-replay-timer"));
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicLong sequence = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void shutdown(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        try {
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(4, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static final class TrackingBroker extends AgentTaskEventBroker {
        private final AtomicInteger trackedLeases = new AtomicInteger();
        private final AtomicInteger finalSignals = new AtomicInteger();
        private final AtomicInteger testDroppedWakeups = new AtomicInteger();
        private final AtomicBoolean dropNextWakeup = new AtomicBoolean();
        private final CountDownLatch firstSubscribed = new CountDownLatch(1);

        @Override
        public void publish(
                AgentTaskEventBroker.TaskScope scope, long eventVersion) {
            if (dropNextWakeup.compareAndSet(true, false)) {
                testDroppedWakeups.incrementAndGet();
                return;
            }
            super.publish(scope, eventVersion);
        }

        @Override
        public Flux<TaskEventWakeup> stream(AgentTaskEventBroker.TaskScope scope) {
            return super.stream(scope)
                    .doOnSubscribe(ignored -> {
                        trackedLeases.incrementAndGet();
                        firstSubscribed.countDown();
                    })
                    .doFinally(ignored -> {
                        trackedLeases.decrementAndGet();
                        finalSignals.incrementAndGet();
                    });
        }

        int trackedLeaseCount() {
            return trackedLeases.get();
        }

        void dropNextWakeup() {
            dropNextWakeup.set(true);
        }
    }

    private static final class MutableDurableStore {
        private final TaskScope scope;
        private final NavigableMap<Long, AgentTaskEventEntity> rows =
                new java.util.concurrent.ConcurrentSkipListMap<>();
        private final AtomicReference<Long> current = new AtomicReference<>(0L);
        private final AtomicInteger activeDaoCalls = new AtomicInteger();
        private final AtomicInteger maxConcurrentDaoCalls = new AtomicInteger();
        private final AtomicInteger currentCalls = new AtomicInteger();
        private final AtomicInteger earliestCalls = new AtomicInteger();
        private final AtomicInteger pageCalls = new AtomicInteger();
        private final List<String> daoThreads = new CopyOnWriteArrayList<>();
        private final List<Long> pageCursors = new CopyOnWriteArrayList<>();
        private final List<Integer> pageLimits = new CopyOnWriteArrayList<>();
        private volatile Supplier<Long> currentSupplier;
        private volatile Supplier<Long> earliestSupplier;
        private volatile LongFunction<List<AgentTaskEventEntity>> pageFunction;

        private MutableDurableStore(TaskScope scope) {
            this.scope = scope;
            currentSupplier = this::currentValue;
            earliestSupplier = this::earliestValue;
            pageFunction = cursor -> defaultPage(cursor, 2);
        }

        private <T> T observe(AtomicInteger counter, Supplier<T> supplier) {
            counter.incrementAndGet();
            daoThreads.add(Thread.currentThread().getName());
            int active = activeDaoCalls.incrementAndGet();
            maxConcurrentDaoCalls.accumulateAndGet(active, Math::max);
            try {
                return supplier.get();
            } finally {
                activeDaoCalls.decrementAndGet();
            }
        }

        private void appendRange(long first, long last) {
            for (long version = first; version <= last; version++) {
                append(version);
            }
        }

        private void append(long version) {
            put(entity(scope, version));
            current.accumulateAndGet(version, Math::max);
        }

        private void put(AgentTaskEventEntity entity) {
            rows.put(entity.getEventVersion(), entity);
        }

        private void setCurrent(long version) {
            current.set(version);
        }

        private void clearRowsAndSetCurrent(long version) {
            rows.clear();
            current.set(version);
            currentSupplier = this::currentValue;
            earliestSupplier = this::earliestValue;
            pageFunction = cursor -> defaultPage(cursor, 2);
        }

        private Long currentValue() {
            return current.get();
        }

        private Long earliestValue() {
            return rows.isEmpty() ? null : rows.firstKey();
        }

        private List<AgentTaskEventEntity> defaultPage(long cursor, int limit) {
            List<AgentTaskEventEntity> page = new ArrayList<>(limit);
            for (AgentTaskEventEntity entity : rows.tailMap(cursor, false).values()) {
                page.add(entity);
                if (page.size() == limit) {
                    break;
                }
            }
            return page;
        }
    }
}
