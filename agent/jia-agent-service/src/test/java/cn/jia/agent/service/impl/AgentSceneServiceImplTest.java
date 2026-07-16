package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.dao.AgentScenePhaseReportDao;
import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneEventEntity;
import cn.jia.agent.entity.AgentScenePhaseReportDTO;
import cn.jia.agent.entity.AgentScenePhaseReportEntity;
import cn.jia.agent.entity.AgentScenePhaseResultDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.service.AgentSceneEventBroker;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentSceneServiceImplTest extends BaseMockTest {
    private static final String SCENE_ID = "juyiting-main";

    @Mock
    AgentSceneStateDao stateDao;
    @Mock
    AgentSceneEventDao eventDao;
    @Mock
    AgentScenePhaseReportDao phaseReportDao;
    @Mock
    AgentRuntimeDao runtimeDao;

    AgentSceneServiceImpl service;
    AgentSceneEventBroker eventBroker;

    @BeforeEach
    void setUp() {
        setScope("tenant-a", "client-a");
        eventBroker = new AgentSceneEventBroker();
        service = new AgentSceneServiceImpl(stateDao, eventDao, phaseReportDao, runtimeDao, eventBroker);
        lenient().when(runtimeDao.findRosterByOwner("client-a", "tenant-a", null, null))
                .thenReturn(List.of(runtime("agent-songjiang", "songjiang", AgentConstants.STATUS_ONLINE)));
        lenient().when(stateDao.findActiveByScene(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), anyLong()))
                .thenReturn(List.of());
        lenient().when(eventDao.nextSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(1L);
        lenient().when(stateDao.upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
        lenient().when(eventDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
    }

    @Test
    void upsertIncrementsStateAndSceneVersionsWithinCurrentScope() {
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 16L, null));
        when(eventDao.nextSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(129L);
        AgentSceneStateDTO request = request("agent-songjiang", "songjiang");
        request.setStateVersion(999L);

        AgentSceneStateDTO result = service.upsertState(SCENE_ID, request);

        assertEquals(17L, result.getStateVersion());
        assertEquals(999L, request.getStateVersion());
        ArgumentCaptor<AgentSceneStateEntity> stateCaptor = ArgumentCaptor.forClass(AgentSceneStateEntity.class);
        verify(stateDao).upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), stateCaptor.capture());
        assertEquals(17L, stateCaptor.getValue().getStateVersion());
        ArgumentCaptor<AgentSceneEventDTO> eventCaptor = ArgumentCaptor.forClass(AgentSceneEventDTO.class);
        verify(eventDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), eventCaptor.capture());
        assertEquals(129L, eventCaptor.getValue().getSceneVersion());
        assertEquals("agent-scene-state-updated", eventCaptor.getValue().getEventType());
        assertEquals(17L, eventCaptor.getValue().getState().getStateVersion());
        assertNotSame(result, eventCaptor.getValue().getState());

        InOrder order = inOrder(eventDao, runtimeDao, stateDao);
        order.verify(eventDao).nextSceneVersion("tenant-a", "client-a", SCENE_ID);
        order.verify(runtimeDao).findRosterByOwner("client-a", "tenant-a", null, null);
        order.verify(stateDao).findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang");
        order.verify(stateDao).findActiveByScene(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), anyLong());
        order.verify(stateDao).upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
        order.verify(eventDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
    }

    @Test
    void allocatorLockPrecedesEveryRosterAndStateReadAndExpiryUsesPostLockTime() {
        AtomicLong lockAcquiredAt = new AtomicLong();
        when(eventDao.nextSceneVersion("tenant-a", "client-a", SCENE_ID)).thenAnswer(invocation -> {
            lockAcquiredAt.set(System.currentTimeMillis());
            return 8L;
        });

        service.upsertState(SCENE_ID, request("agent-songjiang", "songjiang"));

        InOrder order = inOrder(eventDao, runtimeDao, stateDao);
        order.verify(eventDao).nextSceneVersion("tenant-a", "client-a", SCENE_ID);
        order.verify(runtimeDao).findRosterByOwner("client-a", "tenant-a", null, null);
        order.verify(stateDao).findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang");
        ArgumentCaptor<Long> activeAt = ArgumentCaptor.forClass(Long.class);
        order.verify(stateDao).findActiveByScene(
                eq("tenant-a"), eq("client-a"), eq(SCENE_ID), activeAt.capture());
        assertTrue(activeAt.getValue() >= lockAcquiredAt.get());
    }

    @Test
    void eventsOpensScopedLiveStreamWhenThereIsNoBacklog() {
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(0L);
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(null);

        StepVerifier.create(service.events(SCENE_ID, 0L))
                .then(() -> {
                    verify(eventDao, timeout(2_000))
                            .findCurrentSceneVersion("tenant-a", "client-a", SCENE_ID);
                    verify(eventDao, timeout(2_000))
                            .findEarliestSceneVersion("tenant-a", "client-a", SCENE_ID);
                })
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        verify(eventDao).findCurrentSceneVersion("tenant-a", "client-a", SCENE_ID);
        verify(eventDao).findEarliestSceneVersion("tenant-a", "client-a", SCENE_ID);
    }

    @Test
    void coalescesLiveNotificationsWhilePersistedCatchupIsBlocked() {
        AtomicLong durableVersion = new AtomicLong(1L);
        CountDownLatch streamReady = new CountDownLatch(1);
        CountDownLatch catchupEntered = new CountDownLatch(1);
        CountDownLatch releaseCatchup = new CountDownLatch(1);
        when(eventDao.findCurrentSceneVersion("tenant-a", "client-a", SCENE_ID))
                .thenAnswer(invocation -> durableVersion.get());
        when(eventDao.findEarliestSceneVersion("tenant-a", "client-a", SCENE_ID))
                .thenAnswer(invocation -> {
                    streamReady.countDown();
                    return 1L;
                });
        when(eventDao.findAfterVersion("tenant-a", "client-a", SCENE_ID, 1L, 1000))
                .thenAnswer(invocation -> {
                    catchupEntered.countDown();
                    assertTrue(releaseCatchup.await(2, TimeUnit.SECONDS));
                    return List.of(eventEntity(2L), eventEntity(3L));
                });
        when(eventDao.findAfterVersion("tenant-a", "client-a", SCENE_ID, 3L, 1000))
                .thenReturn(LongStream.rangeClosed(4L, 100L)
                        .mapToObj(this::eventEntity)
                        .toList());
        AgentSceneEventBroker.SceneScope scope = new AgentSceneEventBroker.SceneScope(
                "tenant-a", "client-a", SCENE_ID);

        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(99);
        var subscription = service.events(SCENE_ID, 1L).subscribe(event -> {
            received.add(event.getSceneVersion());
            delivered.countDown();
        });
        await(streamReady);
        durableVersion.set(3L);
        eventBroker.publish(scope, sceneEvent(3L));
        await(catchupEntered);
        try {
            durableVersion.set(100L);
            LongStream.rangeClosed(4L, 100L)
                    .forEach(version -> eventBroker.publish(scope, sceneEvent(version)));
            assertEquals(1, service.scheduledEventBridgeTaskCount());
        } finally {
            releaseCatchup.countDown();
        }

        try {
            assertTrue(delivered.await(5, TimeUnit.SECONDS), received.toString());
            assertEquals(LongStream.rangeClosed(2L, 100L).boxed().toList(), received);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } finally {
            subscription.dispose();
        }
        assertEquals(0, service.scheduledEventBridgeTaskCount());
    }

    @Test
    void acceptsExactCurrentPhaseAndPersistsOneContiguousEvent() {
        AgentSceneStateEntity current = stateEntity("agent-songjiang", "songjiang", 17L, null);
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(current);
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
        when(stateDao.updatePhase(eq("tenant-a"), eq("client-a"), eq(SCENE_ID),
                eq("agent-songjiang"), eq(17L), eq("arrived"), anyLong())).thenReturn(1);
        when(eventDao.nextSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(129L);

        AgentScenePhaseResultDTO result = service.reportPhase(
                SCENE_ID, phaseReport("r1", "agent-songjiang", 17L, "arrived", "council-table", 2_500L));

        assertEquals("accepted", result.getResult());
        assertEquals(17L, result.getStateVersion());
        ArgumentCaptor<AgentScenePhaseReportEntity> report = ArgumentCaptor.forClass(AgentScenePhaseReportEntity.class);
        verify(phaseReportDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), report.capture());
        assertEquals("accepted", report.getValue().getResult());
        ArgumentCaptor<AgentSceneEventDTO> event = ArgumentCaptor.forClass(AgentSceneEventDTO.class);
        verify(eventDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), event.capture());
        assertEquals(129L, event.getValue().getSceneVersion());
        assertEquals("arrived", event.getValue().getState().getPhase());
        InOrder order = inOrder(phaseReportDao, eventDao, stateDao);
        order.verify(phaseReportDao).findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "r1");
        order.verify(eventDao).lockSceneVersionScope("tenant-a", "client-a", SCENE_ID);
        order.verify(stateDao).findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang");
        order.verify(phaseReportDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
        order.verify(eventDao).nextSceneVersion("tenant-a", "client-a", SCENE_ID);
        order.verify(stateDao).updatePhase(eq("tenant-a"), eq("client-a"), eq(SCENE_ID),
                eq("agent-songjiang"), eq(17L), eq("arrived"), anyLong());
        order.verify(eventDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
    }

    @Test
    void returnsStoredMetadataForExistingAndDuplicateKeyReportsWithoutUpdatingTwice() {
        AgentScenePhaseReportEntity stored = phaseEntity("r1", 17L, "accepted");
        when(phaseReportDao.findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "r1"))
                .thenReturn(stored);

        AgentScenePhaseResultDTO existing = service.reportPhase(
                SCENE_ID, phaseReport("r1", "agent-songjiang", 99L, "blocked", "council-table", 2_500L));
        assertEquals("ignored_duplicate", existing.getResult());
        assertEquals(17L, existing.getStateVersion());
        verify(eventDao, never()).lockSceneVersionScope(any(), any(), any());

        when(phaseReportDao.findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "r2"))
                .thenReturn(null, phaseEntity("r2", 18L, "ignored_stale"));
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 18L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any()))
                .thenThrow(new DuplicateKeyException("concurrent duplicate"));

        AgentScenePhaseResultDTO raced = service.reportPhase(
                SCENE_ID, phaseReport("r2", "agent-songjiang", 18L, "arrived", "council-table", 2_500L));
        assertEquals("ignored_duplicate", raced.getResult());
        assertEquals(18L, raced.getStateVersion());
        verify(phaseReportDao, org.mockito.Mockito.times(2))
                .findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "r2");
        verify(stateDao, never()).updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong());
        verify(eventDao, org.mockito.Mockito.times(1))
                .lockSceneVersionScope("tenant-a", "client-a", SCENE_ID);
        verify(eventDao, never()).nextSceneVersion(any(), any(), any());
    }

    @Test
    void treatsOlderFutureAndWrongRegionReportsAsStaleWithoutAllocatingEvents() {
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);

        for (AgentScenePhaseReportDTO report : List.of(
                phaseReport("old", "agent-songjiang", 16L, "blocked", "council-table", 2_500L),
                phaseReport("future", "agent-songjiang", 18L, "arrived", "council-table", 2_500L),
                phaseReport("region", "agent-songjiang", 17L, "arrived", "main-seat", 2_500L),
                phaseReport("early", "agent-songjiang", 17L, "arrived", "council-table", 999L))) {
            assertEquals("ignored_stale", service.reportPhase(SCENE_ID, report).getResult());
        }

        InOrder staleOrder = inOrder(phaseReportDao, eventDao, stateDao);
        staleOrder.verify(phaseReportDao)
                .findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "old");
        staleOrder.verify(eventDao).lockSceneVersionScope("tenant-a", "client-a", SCENE_ID);
        staleOrder.verify(stateDao)
                .findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang");
        verify(stateDao, never()).updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong());
        verify(eventDao, org.mockito.Mockito.times(4))
                .lockSceneVersionScope("tenant-a", "client-a", SCENE_ID);
        verify(eventDao, never()).nextSceneVersion(any(), any(), any());
        verify(eventDao, never()).insert(any(), any(), any(), any());
    }

    @Test
    void validatesPhaseReportBeforeAnyScopedPersistence() {
        List<AgentScenePhaseReportDTO> invalid = List.of(
                phaseReport(" ", "agent-songjiang", 17L, "arrived", "council-table", 2_500L),
                phaseReport("r", " ", 17L, "arrived", "council-table", 2_500L),
                phaseReport("r", "agent-songjiang", 0L, "arrived", "council-table", 2_500L),
                phaseReport("r", "agent-songjiang", 17L, "moving", "council-table", 2_500L),
                phaseReport("r", "agent-songjiang", 17L, "arrived", " ", 2_500L),
                phaseReport("r", "agent-songjiang", 17L, "arrived", "council-table", -1L));
        for (AgentScenePhaseReportDTO report : invalid) {
            assertThrows(IllegalArgumentException.class, () -> service.reportPhase(SCENE_ID, report));
        }
        assertThrows(IllegalArgumentException.class, () -> service.reportPhase(SCENE_ID, null));
        verifyNoInteractions(phaseReportDao);
        verify(stateDao, never()).updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong());
        verify(eventDao, never()).nextSceneVersion(any(), any(), any());
    }

    @Test
    void isolatesPhaseReportsByTenantAndClient() {
        setScope("tenant-b", "client-b");
        when(stateDao.findByAgent("tenant-b", "client-b", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-b"), eq("client-b"), eq(SCENE_ID), any())).thenReturn(1);

        service.reportPhase(SCENE_ID,
                phaseReport("r-b", "agent-songjiang", 16L, "blocked", "council-table", 2_500L));

        verify(phaseReportDao).findByReportIdForUpdate("tenant-b", "client-b", SCENE_ID, "r-b");
        verify(phaseReportDao).insert(eq("tenant-b"), eq("client-b"), eq(SCENE_ID), any());
        verify(phaseReportDao, never()).findByReportIdForUpdate(eq("tenant-a"), any(), any(), any());
    }

    @Test
    void concurrentDuplicateReportsProduceOneAcceptedUpdateAndOneDuplicate() throws Exception {
        AtomicBoolean firstLockOwner = new AtomicBoolean(true);
        CountDownLatch reportCommitted = new CountDownLatch(1);
        AtomicReference<AgentScenePhaseReportEntity> stored = new AtomicReference<>();
        when(phaseReportDao.findByReportIdForUpdate("tenant-a", "client-a", SCENE_ID, "race"))
                .thenAnswer(invocation -> {
                    if (firstLockOwner.compareAndSet(true, false)) return null;
                    assertTrue(reportCommitted.await(2, TimeUnit.SECONDS));
                    return stored.get();
                });
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any()))
                .thenAnswer(invocation -> {
                    AgentScenePhaseReportEntity candidate = invocation.getArgument(3);
                    stored.set(candidate);
                    reportCommitted.countDown();
                    return 1;
                });
        when(stateDao.updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong())).thenReturn(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = List.of(
                    executor.submit(() -> reportInScope("race")),
                    executor.submit(() -> reportInScope("race")));
            assertEquals(Set.of("accepted", "ignored_duplicate"), Set.of(
                    results.get(0).get(3, TimeUnit.SECONDS), results.get(1).get(3, TimeUnit.SECONDS)));
        } finally {
            executor.shutdownNow();
        }
        verify(stateDao, org.mockito.Mockito.times(1))
                .updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong());
        verify(eventDao, org.mockito.Mockito.times(1)).lockSceneVersionScope(any(), any(), any());
        verify(eventDao, org.mockito.Mockito.times(1)).nextSceneVersion(any(), any(), any());
        verify(eventDao, org.mockito.Mockito.times(1)).insert(any(), any(), any(), any());
    }

    @Test
    void conditionalStateFailureThrowsAfterAllocationSoTheTransactionRollsBack() {
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
        when(stateDao.updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong())).thenReturn(0);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        AgentSceneService transactionalService = (AgentSceneService) proxyFactory.getProxy();

        assertThrows(IllegalStateException.class, () -> transactionalService.reportPhase(SCENE_ID,
                phaseReport("race-state", "agent-songjiang", 17L,
                        "arrived", "council-table", 2_500L)));

        verify(eventDao).nextSceneVersion("tenant-a", "client-a", SCENE_ID);
        verify(eventDao, never()).insert(any(), any(), any(), any());
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void acceptedPhasePublishesOnlyAfterCommit() {
        AgentSceneEventBroker broker = mock(AgentSceneEventBroker.class);
        AgentSceneServiceImpl phaseService = new AgentSceneServiceImpl(
                stateDao, eventDao, phaseReportDao, runtimeDao, broker);
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
        when(stateDao.updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong())).thenReturn(1);

        TransactionSynchronizationManager.initSynchronization();
        try {
            phaseService.reportPhase(SCENE_ID,
                    phaseReport("after-commit", "agent-songjiang", 17L,
                            "arrived", "council-table", 2_500L));
            verify(broker, never()).publish(any(), any());
            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertEquals(1, synchronizations.size());
            synchronizations.getFirst().afterCommit();
            verify(broker).publish(eq(new AgentSceneEventBroker.SceneScope(
                    "tenant-a", "client-a", SCENE_ID)), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acceptedPhaseEventFailureRollsBackReportStateAndVersionWork() throws Exception {
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(stateEntity("agent-songjiang", "songjiang", 17L, null));
        when(phaseReportDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(1);
        when(stateDao.updatePhase(any(), any(), any(), any(), anyLong(), any(), anyLong())).thenReturn(1);
        when(eventDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any()))
                .thenThrow(new IllegalStateException("phase event insert failed"));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        AgentSceneService transactionalService = (AgentSceneService) proxyFactory.getProxy();

        assertThrows(IllegalStateException.class, () -> transactionalService.reportPhase(SCENE_ID,
                phaseReport("rollback", "agent-songjiang", 17L,
                        "arrived", "council-table", 2_500L)));

        verify(phaseReportDao).insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
        verify(stateDao).updatePhase(eq("tenant-a"), eq("client-a"), eq(SCENE_ID),
                eq("agent-songjiang"), eq(17L), eq("arrived"), anyLong());
        verify(eventDao).nextSceneVersion("tenant-a", "client-a", SCENE_ID);
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(any());
        Transactional annotation = AgentSceneServiceImpl.class
                .getMethod("reportPhase", String.class, AgentScenePhaseReportDTO.class)
                .getAnnotation(Transactional.class);
        assertTrue(Arrays.asList(annotation.rollbackFor()).contains(Exception.class));
    }

    @Test
    void rejectsInvalidTemporalOrderingBeforeAllocatingTheSceneLock() {
        AgentSceneStateDTO missingStart = request("agent-songjiang", "songjiang");
        missingStart.setStartedAt(null);
        AgentSceneStateDTO negativeStart = request("agent-songjiang", "songjiang");
        negativeStart.setStartedAt(-1L);
        AgentSceneStateDTO negativeExpected = request("agent-songjiang", "songjiang");
        negativeExpected.setExpectedArrivalAt(-1L);
        AgentSceneStateDTO negativeExpiry = request("agent-songjiang", "songjiang");
        negativeExpiry.setExpiresAt(-1L);
        AgentSceneStateDTO expectedBeforeStart = request("agent-songjiang", "songjiang");
        expectedBeforeStart.setStartedAt(2_000L);
        expectedBeforeStart.setExpectedArrivalAt(1_999L);
        AgentSceneStateDTO expiryBeforeStart = request("agent-songjiang", "songjiang");
        expiryBeforeStart.setStartedAt(2_000L);
        expiryBeforeStart.setExpectedArrivalAt(null);
        expiryBeforeStart.setExpiresAt(1_999L);
        AgentSceneStateDTO expiryBeforeArrival = request("agent-songjiang", "songjiang");
        expiryBeforeArrival.setStartedAt(1_000L);
        expiryBeforeArrival.setExpectedArrivalAt(3_000L);
        expiryBeforeArrival.setExpiresAt(2_999L);

        for (AgentSceneStateDTO invalid : List.of(
                missingStart, negativeStart, negativeExpected, negativeExpiry,
                expectedBeforeStart, expiryBeforeStart, expiryBeforeArrival)) {
            assertThrows(IllegalArgumentException.class, () -> service.upsertState(SCENE_ID, invalid));
        }
        verify(eventDao, never()).nextSceneVersion(any(), any(), any());
        verify(runtimeDao, never()).findRosterByOwner(any(), any(), any(), any());
        verify(stateDao, never()).findByAgent(any(), any(), any(), any());
    }

    @Test
    void rejectsBlankScopeWithoutDefaultingOrDaoAccess() {
        setScope(" ", "client-a");
        assertThrows(IllegalArgumentException.class, () -> service.upsertState(SCENE_ID, request("a", "songjiang")));
        setScope("tenant-a", "");
        assertThrows(IllegalArgumentException.class, () -> service.snapshot(SCENE_ID));

        verifyNoInteractions(stateDao, eventDao);
        verify(runtimeDao, never()).findRosterByOwner(any(), any(), any(), any());
    }

    @Test
    void isolatesEveryReadAndWriteByCurrentTenantAndClient() {
        setScope("tenant-b", "client-b");
        when(runtimeDao.findRosterByOwner("client-b", "tenant-b", null, null))
                .thenReturn(List.of(runtime("agent-b", "songjiang", AgentConstants.STATUS_ONLINE)));
        when(eventDao.nextSceneVersion("tenant-b", "client-b", SCENE_ID)).thenReturn(4L);
        when(stateDao.findActiveByScene(eq("tenant-b"), eq("client-b"), eq(SCENE_ID), anyLong()))
                .thenReturn(List.of());
        when(stateDao.upsert(eq("tenant-b"), eq("client-b"), eq(SCENE_ID), any())).thenReturn(1);
        when(eventDao.insert(eq("tenant-b"), eq("client-b"), eq(SCENE_ID), any())).thenReturn(1);

        service.upsertState(SCENE_ID, request("agent-b", "songjiang"));

        verify(runtimeDao).findRosterByOwner("client-b", "tenant-b", null, null);
        verify(eventDao).nextSceneVersion("tenant-b", "client-b", SCENE_ID);
        verify(stateDao).findByAgent("tenant-b", "client-b", SCENE_ID, "agent-b");
        verify(stateDao, never()).findByAgent(eq("tenant-a"), any(), any(), any());
    }

    @Test
    void rejectsPersonaConflictWithAnotherRealAgentInTheSameScopedScene() {
        AgentRuntimeEntity requester = runtime("agent-a", "songjiang", AgentConstants.STATUS_ONLINE);
        AgentRuntimeEntity conflicting = runtime("agent-b", "songjiang", AgentConstants.STATUS_BUSY);
        when(runtimeDao.findRosterByOwner("client-a", "tenant-a", null, null))
                .thenReturn(List.of(requester, conflicting));
        when(stateDao.findActiveByScene(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), anyLong()))
                .thenReturn(List.of(stateEntity("agent-b", "songjiang", 3L, null)));

        assertThrows(IllegalArgumentException.class,
                () -> service.upsertState(SCENE_ID, request("agent-a", "songjiang")));

        verify(stateDao, never()).upsert(any(), any(), any(), any());
        verify(eventDao, never()).insert(any(), any(), any(), any());
    }

    @Test
    void snapshotFiltersExpiryAndVisibilityOrdersByAgentIdAndDefensivelyCopies() {
        long now = System.currentTimeMillis();
        AgentRuntimeEntity zeta = runtime("agent-zeta", "wuyong", AgentConstants.STATUS_BUSY);
        AgentRuntimeEntity alpha = runtime("agent-alpha", "songjiang", AgentConstants.STATUS_ONLINE);
        AgentRuntimeEntity offline = runtime("agent-offline", "linchong", AgentConstants.STATUS_OFFLINE);
        AgentSceneStateEntity activeZeta = stateEntity("agent-zeta", "wuyong", 2L, now + 60_000);
        AgentSceneStateEntity activeAlpha = stateEntity("agent-alpha", "songjiang", 4L, null);
        AgentSceneStateEntity expired = stateEntity("agent-offline", "linchong", 9L, now - 1);
        when(runtimeDao.findRosterByOwner("client-a", "tenant-a", null, null))
                .thenReturn(List.of(zeta, offline, alpha));
        when(stateDao.findActiveByScene(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), anyLong()))
                .thenReturn(List.of(activeZeta, expired, activeAlpha));
        when(eventDao.findLatestSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(77L);

        AgentSceneSnapshotDTO snapshot = service.snapshot(SCENE_ID);

        assertEquals(77L, snapshot.getSceneVersion());
        assertEquals(List.of("agent-alpha", "agent-zeta"),
                snapshot.getAgents().stream().map(agent -> agent.getAgentId()).toList());
        assertEquals(List.of("agent-alpha", "agent-zeta"),
                snapshot.getStates().stream().map(AgentSceneStateDTO::getAgentId).toList());

        alpha.setPersonaCode("mutated-source");
        activeAlpha.setPersonaCode("mutated-state");
        snapshot.getAgents().get(0).setPersonaCode("mutated-publication");
        snapshot.getStates().get(0).setPersonaCode("mutated-publication");
        assertEquals("songjiang", snapshot.getAgents().get(0).getPersonaCode());
        assertEquals("songjiang", snapshot.getStates().get(0).getPersonaCode());
    }

    @Test
    void secondUpsertKeepsOneCurrentStateAndAdvancesFromDurableCurrentVersion() {
        AgentSceneStateEntity current = stateEntity("agent-songjiang", "songjiang", 1L, null);
        when(stateDao.findByAgent("tenant-a", "client-a", SCENE_ID, "agent-songjiang"))
                .thenReturn(current);
        when(eventDao.nextSceneVersion("tenant-a", "client-a", SCENE_ID)).thenReturn(2L);
        when(stateDao.upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any())).thenReturn(2);

        AgentSceneStateDTO result = service.upsertState(SCENE_ID, request("agent-songjiang", "songjiang"));

        assertEquals(2L, result.getStateVersion());
        verify(stateDao).upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
    }

    @Test
    void eventFailureRollsBackTheTransactionalUnitInsteadOfCommittingPartialState() throws Exception {
        when(eventDao.insert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any()))
                .thenThrow(new IllegalStateException("event insert failed"));
        PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(transactionManager.getTransaction(any())).thenReturn(status);
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        AgentSceneService transactionalService = (AgentSceneService) proxyFactory.getProxy();

        assertThrows(IllegalStateException.class,
                () -> transactionalService.upsertState(SCENE_ID, request("agent-songjiang", "songjiang")));

        verify(stateDao).upsert(eq("tenant-a"), eq("client-a"), eq(SCENE_ID), any());
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(any());
        Transactional annotation = AgentSceneServiceImpl.class
                .getMethod("upsertState", String.class, AgentSceneStateDTO.class)
                .getAnnotation(Transactional.class);
        assertEquals(Propagation.REQUIRED, annotation.propagation());
        assertTrue(Arrays.asList(annotation.rollbackFor()).contains(Exception.class));
    }

    private void setScope(String tenantId, String clientId) {
        EsContext context = new EsContext();
        context.setJiacn(tenantId);
        context.setClientId(clientId);
        EsContextHolder.setContext(context);
    }

    private AgentSceneStateDTO request(String agentId, String personaCode) {
        AgentSceneStateDTO state = new AgentSceneStateDTO();
        state.setAgentId(agentId);
        state.setPersonaCode(personaCode);
        state.setBehavior("moving_to_council");
        state.setOriginRegionId("main-seat");
        state.setTargetRegionId("council-table");
        state.setRelatedType("task");
        state.setRelatedId("task-1");
        state.setPhase("moving");
        state.setStartedAt(1_000L);
        state.setExpectedArrivalAt(2_000L);
        state.setExpiresAt(System.currentTimeMillis() + 60_000);
        return state;
    }

    private AgentScenePhaseReportDTO phaseReport(
            String reportId, String agentId, long stateVersion,
            String phase, String regionId, long occurredAt) {
        AgentScenePhaseReportDTO report = new AgentScenePhaseReportDTO();
        report.setReportId(reportId);
        report.setAgentId(agentId);
        report.setStateVersion(stateVersion);
        report.setPhase(phase);
        report.setRegionId(regionId);
        report.setOccurredAt(occurredAt);
        return report;
    }

    private AgentScenePhaseReportEntity phaseEntity(String reportId, long stateVersion, String result) {
        AgentScenePhaseReportEntity entity = new AgentScenePhaseReportEntity();
        entity.setReportId(reportId);
        entity.setAgentId("agent-songjiang");
        entity.setStateVersion(stateVersion);
        entity.setPhase("arrived");
        entity.setRegionId("council-table");
        entity.setResult(result);
        entity.setOccurredAt(2_500L);
        entity.setProcessedAt(2_600L);
        return entity;
    }

    private String reportInScope(String reportId) {
        setScope("tenant-a", "client-a");
        return service.reportPhase(SCENE_ID,
                phaseReport(reportId, "agent-songjiang", 17L, "arrived", "council-table", 2_500L))
                .getResult();
    }

    private AgentRuntimeEntity runtime(String agentId, String personaCode, String status) {
        AgentRuntimeEntity entity = new AgentRuntimeEntity();
        entity.setAgentId(agentId);
        entity.setPersonaCode(personaCode);
        entity.setStatus(status);
        entity.setOwnerJiacn(EsContextHolder.getContext().getJiacn());
        entity.setClientId(EsContextHolder.getContext().getClientId());
        return entity;
    }

    private AgentSceneStateEntity stateEntity(
            String agentId, String personaCode, long stateVersion, Long expiresAt) {
        AgentSceneStateEntity entity = new AgentSceneStateEntity();
        entity.setAgentId(agentId);
        entity.setPersonaCode(personaCode);
        entity.setBehavior("moving_to_council");
        entity.setOriginRegionId("main-seat");
        entity.setTargetRegionId("council-table");
        entity.setRelatedType("task");
        entity.setRelatedId("task-1");
        entity.setPhase("moving");
        entity.setStateVersion(stateVersion);
        entity.setStartedAt(1_000L);
        entity.setExpectedArrivalAt(2_000L);
        entity.setExpiresAt(expiresAt);
        return entity;
    }

    private AgentSceneEventDTO sceneEvent(long version) {
        AgentSceneEventDTO event = new AgentSceneEventDTO();
        event.setSceneVersion(version);
        event.setEventType("agent-scene-state-updated");
        event.setOccurredAt(2_000L + version);
        return event;
    }

    private AgentSceneEventEntity eventEntity(long version) {
        AgentSceneEventDTO event = sceneEvent(version);
        AgentSceneEventEntity entity = new AgentSceneEventEntity();
        entity.setSceneVersion(version);
        entity.setEventType(event.getEventType());
        entity.setOccurredAt(event.getOccurredAt());
        entity.setEventJson(JsonUtil.toJson(event));
        return entity;
    }

    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
