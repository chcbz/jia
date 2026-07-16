package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentSceneEventDao;
import cn.jia.agent.dao.AgentSceneStateDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentSceneEventDTO;
import cn.jia.agent.entity.AgentSceneSnapshotDTO;
import cn.jia.agent.entity.AgentSceneStateDTO;
import cn.jia.agent.entity.AgentSceneStateEntity;
import cn.jia.agent.service.AgentSceneEventBroker;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
    AgentRuntimeDao runtimeDao;

    AgentSceneServiceImpl service;
    AgentSceneEventBroker eventBroker;

    @BeforeEach
    void setUp() {
        setScope("tenant-a", "client-a");
        eventBroker = new AgentSceneEventBroker();
        service = new AgentSceneServiceImpl(stateDao, eventDao, runtimeDao, eventBroker);
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
    void reportPhaseRemainsExplicitlyUnavailableUntilTaskFive() {
        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> service.reportPhase(SCENE_ID, null));
        assertTrue(error.getMessage().contains("Task 5"));
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
}
