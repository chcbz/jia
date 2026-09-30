package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentRuntimeV2BindingDao;
import cn.jia.agent.dao.AgentRuntimeV2BindingGenerationFactDao;
import cn.jia.agent.dao.impl.AgentRuntimeV2BindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeV2BindingGenerationFactDaoImpl;
import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationEvent;
import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationFactEntity;
import cn.jia.agent.entity.AuthenticatedRuntimeSession;
import cn.jia.agent.entity.RuntimeReceiverScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adversarial R2a1 tests for receiver-only stable binding generation facts. */
class AgentRuntimeV2BindingGenerationFactTest {
    private static final long NOW = 1_900_000_000_000L;
    private static final String BINDING = "00000000-0000-4000-8000-0000000000a1";
    private static final String OTHER_BINDING = "00000000-0000-4000-8000-0000000000a2";
    private static final byte[] VERIFIER = verifier(7);

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private Connection schemaConnection;
    private Persistence persistence;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:runtime_v2_r2a1_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=5000;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        schemaConnection = source.getConnection();
        createTables(schemaConnection);
        transactions = new DataSourceTransactionManager(source);
        persistence = persistence();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (executor != null) executor.shutdownNow();
        if (schemaConnection != null) schemaConnection.close();
    }

    @Test
    void creationRequiresWritableTransactionAndRollsBackBindingWhenInitialFactConflicts() {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession session = session(BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        AgentRuntimeV2BindingEntity draft = draft(NOW + 100);

        assertThrows(IllegalStateException.class,
                () -> persistence.generations.createVersion2(scope, draft, session, VERIFIER, NOW));

        TransactionTemplate readOnly = transaction();
        readOnly.setReadOnly(true);
        assertThrows(IllegalStateException.class, () -> readOnly.execute(status ->
                persistence.generations.createVersion2(scope, draft(NOW + 100), session,
                        VERIFIER, NOW)));

        AgentRuntimeV2BindingGenerationFactEntity rogue = directFact(scope, session, 0, null,
                "CONNECTED", NOW + 100, NOW, null);
        tx(() -> persistence.factMapper.insert(scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId(), scope.clientId(), scope.ownerJiacn(), scope.agentId(),
                scope.claimantDeploymentId(), scope.claimantWorkerGeneration(),
                scope.receiverRegistrationRevision(), scope.admissionEpoch(), scope.moduleId(),
                scope.migrationEpoch(), rogue));

        assertThrows(DataIntegrityViolationException.class, () -> tx(() ->
                persistence.generations.createVersion2(scope, draft(NOW + 100), session,
                        VERIFIER, NOW)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_binding", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime_v2_binding_generation_fact", Integer.class));

        assertThrows(IllegalArgumentException.class, () -> tx(() ->
                persistence.generations.createVersion2(scope, draft(NOW), session, VERIFIER, NOW)));
    }

    @Test
    void lifecyclePreservesImmutableLeaseSnapshotsAcrossRestartAndRevocation() throws Exception {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession generation0 = session(
                BINDING, 0, "运行实例-😀", "fence-0", "replica-a", 3);
        AgentRuntimeV2BindingGenerationFactEntity connected = tx(() ->
                persistence.generations.createVersion2(scope, draft(NOW + 100), generation0,
                        VERIFIER, NOW));
        assertEquals("CONNECTED", connected.getEventType());
        assertEquals(0L, connected.getFactSequence());
        assertEquals(NOW + 100, connected.getSocketOwnerLeaseUntil());
        assertArrayEquals(VERIFIER, tx(() -> persistence.bindings.findExact(scope, BINDING))
                .getReconnectVerifier());
        assertNull(tx(() -> persistence.bindings.findExact(scope, BINDING)).getProducerDeploymentId());

        AgentRuntimeV2BindingGenerationFactEntity disconnected = tx(() ->
                persistence.generations.append(scope, generation0,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED, null, null, NOW, null));
        assertEquals("DISCONNECTED", disconnected.getEventType());
        assertEquals(NOW + 100, disconnected.getSocketOwnerLeaseUntil());
        AgentRuntimeV2BindingEntity disconnectedProjection = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals("DISCONNECTED", disconnectedProjection.getStatus());
        assertNull(disconnectedProjection.getSocketOwnerInstanceId());
        assertNull(disconnectedProjection.getSocketOwnerLeaseUntil());

        Persistence restarted = persistence();
        AgentRuntimeV2BindingGenerationFactEntity restartRead = tx(() ->
                restarted.generations.lockLatest(scope, BINDING));
        assertEquals(disconnected.getBindingGenerationFactId(), restartRead.getBindingGenerationFactId());
        assertEquals(NOW + 100, restartRead.getSocketOwnerLeaseUntil());
        assertEquals("运行实例-😀", restartRead.getRuntimeInstanceId());

        AuthenticatedRuntimeSession differentProcess = session(
                BINDING, 1, "runtime-b", "fence-1", "replica-b", 4);
        assertThrows(IllegalArgumentException.class, () -> tx(() ->
                restarted.generations.append(scope, generation0,
                        AgentRuntimeV2BindingGenerationEvent.CONNECTED, differentProcess,
                        NOW + 200, NOW + 1, null)));
        AuthenticatedRuntimeSession reusedFence = session(
                BINDING, 1, generation0.runtimeInstanceId(), generation0.sessionFence(), "replica-b", 4);
        assertThrows(IllegalArgumentException.class, () -> tx(() ->
                restarted.generations.append(scope, generation0,
                        AgentRuntimeV2BindingGenerationEvent.CONNECTED, reusedFence,
                        NOW + 200, NOW + 1, null)));

        AuthenticatedRuntimeSession generation1 = session(
                BINDING, 1, generation0.runtimeInstanceId(), "fence-1", "replica-b", 4);
        AgentRuntimeV2BindingGenerationFactEntity reconnected = tx(() ->
                restarted.generations.append(scope, generation0,
                        AgentRuntimeV2BindingGenerationEvent.CONNECTED, generation1,
                        NOW + 200, NOW + 1, null));
        assertEquals(1L, reconnected.getBindingGeneration());
        assertEquals(NOW + 200, reconnected.getSocketOwnerLeaseUntil());

        AgentRuntimeV2BindingEntity reconnectedProjection = tx(() ->
                restarted.bindings.findExact(scope, BINDING));
        assertEquals("ACTIVE", reconnectedProjection.getStatus());
        assertEquals(NOW + 1, reconnectedProjection.getConnectedAt());
        assertNull(reconnectedProjection.getDisconnectedAt());
        assertNull(reconnectedProjection.getRevokedAt());
        assertNull(reconnectedProjection.getRevokeReason());
        assertEquals(NOW, jdbc.queryForObject("""
                SELECT effective_at FROM agent_runtime_v2_binding_generation_fact
                 WHERE binding_generation_fact_id=?
                """, Long.class, disconnected.getBindingGenerationFactId()));

        AgentRuntimeV2BindingGenerationFactEntity revoked = tx(() ->
                restarted.generations.append(scope, generation1,
                        AgentRuntimeV2BindingGenerationEvent.REVOKED, null, null,
                        NOW + 1, "hard-stop"));
        assertEquals(NOW + 200, revoked.getSocketOwnerLeaseUntil());
        assertThrows(IllegalArgumentException.class, () -> tx(() ->
                restarted.generations.append(scope, generation1,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED, null, null,
                        NOW + 2, null)));

        Persistence afterSecondRestart = persistence();
        AgentRuntimeV2BindingGenerationFactEntity finalRead = tx(() ->
                afterSecondRestart.generations.lockLatest(scope, BINDING));
        assertEquals("REVOKED", finalRead.getEventType());
        assertEquals(NOW + 200, finalRead.getSocketOwnerLeaseUntil());
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime_v2_binding_generation_fact", Integer.class));
    }

    @Test
    void reconnectClearsProjectionThenSecondDisconnectAndRevokePreserveHistory() {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession generation0 = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), generation0, VERIFIER, NOW));
        AgentRuntimeV2BindingGenerationFactEntity firstDisconnect = tx(() ->
                persistence.generations.append(scope, generation0,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, null, NOW + 1, null));

        AuthenticatedRuntimeSession generation1 = session(
                BINDING, 1, "runtime-a", "fence-1", "replica-b", 4);
        tx(() -> persistence.generations.append(scope, generation0,
                AgentRuntimeV2BindingGenerationEvent.CONNECTED,
                generation1, NOW + 200, NOW + 2, null));
        AgentRuntimeV2BindingEntity active = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals("ACTIVE", active.getStatus());
        assertEquals(NOW + 2, active.getConnectedAt());
        assertNull(active.getDisconnectedAt());
        assertNull(active.getRevokedAt());
        assertNull(active.getRevokeReason());

        AgentRuntimeV2BindingGenerationFactEntity secondDisconnect = tx(() ->
                persistence.generations.append(scope, generation1,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, null, NOW + 3, null));
        AgentRuntimeV2BindingEntity disconnected = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals("DISCONNECTED", disconnected.getStatus());
        assertEquals(NOW + 2, disconnected.getConnectedAt());
        assertEquals(NOW + 3, disconnected.getDisconnectedAt());
        assertNull(disconnected.getRevokedAt());
        assertNull(disconnected.getRevokeReason());

        AgentRuntimeV2BindingGenerationFactEntity revoked = tx(() ->
                persistence.generations.append(scope, generation1,
                        AgentRuntimeV2BindingGenerationEvent.REVOKED,
                        null, null, NOW + 4, "after-second-disconnect"));
        AgentRuntimeV2BindingEntity finalProjection = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals("REVOKED", finalProjection.getStatus());
        assertEquals(NOW + 2, finalProjection.getConnectedAt());
        assertEquals(NOW + 3, finalProjection.getDisconnectedAt());
        assertEquals(NOW + 4, finalProjection.getRevokedAt());
        assertEquals("after-second-disconnect", finalProjection.getRevokeReason());
        assertEquals(NOW + 1, jdbc.queryForObject("""
                SELECT effective_at FROM agent_runtime_v2_binding_generation_fact
                 WHERE binding_generation_fact_id=?
                """, Long.class, firstDisconnect.getBindingGenerationFactId()));
        assertEquals(NOW + 100, firstDisconnect.getSocketOwnerLeaseUntil());
        assertEquals(NOW + 3, secondDisconnect.getEffectiveAt());
        assertEquals(NOW + 200, secondDisconnect.getSocketOwnerLeaseUntil());
        assertEquals(NOW + 200, revoked.getSocketOwnerLeaseUntil());
        assertLinearFactChain(BINDING, 5);
    }

    @Test
    void productionBindingStateCheckRejectsInvalidUpdateAndRollsBackTransaction() {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession current = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), current, VERIFIER, NOW));

        assertThrows(DataIntegrityViolationException.class, () -> tx(() -> {
            assertEquals(1, jdbc.update("""
                    UPDATE agent_runtime_v2_binding SET credential_reference=?
                     WHERE runtime_binding_id=?
                    """, "mutated-before-invalid-state", BINDING));
            jdbc.update("""
                    UPDATE agent_runtime_v2_binding SET disconnected_at=?
                     WHERE runtime_binding_id=?
                    """, NOW + 1, BINDING);
            return true;
        }));

        AgentRuntimeV2BindingEntity unchanged = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals("ACTIVE", unchanged.getStatus());
        assertEquals("receiver-key", unchanged.getCredentialReference());
        assertNull(unchanged.getDisconnectedAt());
        assertEquals(1, factCount());
    }

    @Test
    void staleSessionTimeAndProjectionDriftFailClosedWithoutAppending() {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession current = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), current, VERIFIER, NOW));

        for (AuthenticatedRuntimeSession stale : new AuthenticatedRuntimeSession[]{
                session(BINDING, 0, "runtime-a", "stale-fence", "replica-a", 3),
                session(BINDING, 0, "runtime-a", "fence-0", "replica-x", 3),
                session(BINDING, 0, "runtime-a", "fence-0", "replica-a", 2)}) {
            assertThrows(IllegalStateException.class, () -> tx(() ->
                    persistence.generations.append(scope, stale,
                            AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                            null, null, NOW + 1, null)));
        }
        assertThrows(IllegalStateException.class, () -> tx(() ->
                persistence.generations.append(scope, current,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, null, NOW - 1, null)));
        assertThrows(IllegalArgumentException.class, () -> tx(() ->
                persistence.generations.append(scope, current,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, NOW + 200, NOW + 1, null)));
        assertEquals(1, factCount());

        jdbc.update("UPDATE agent_runtime_v2_binding SET socket_owner_lease_until=? WHERE runtime_binding_id=?",
                NOW + 101, BINDING);
        assertThrows(IllegalStateException.class, () -> tx(() ->
                persistence.generations.append(scope, current,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, null, NOW + 1, null)));
        assertEquals(1, factCount());
    }

    @Test
    void legacyBindingCannotBeUpgradedOrMutatedByGenerationDao() {
        RuntimeReceiverScope scope = scope();
        AgentRuntimeV2BindingEntity legacy = new AgentRuntimeV2BindingEntity()
                .setCredentialKind("API_KEY").setCredentialReference("legacy-key")
                .setRuntimeInstanceId("legacy-runtime").setBindingGeneration(0L)
                .setStatus("ACTIVE").setConnectedAt(NOW).setVersion(0L);
        legacy.setCreateTime(NOW);
        legacy.setUpdateTime(NOW);
        assertEquals(1, tx(() -> persistence.bindings.insertTrusted(scope, legacy)));
        AgentRuntimeV2BindingEntity stored = tx(() ->
                persistence.bindings.findExact(scope, legacy.getRuntimeBindingId()));
        assertEquals(1, stored.getContractVersion());

        AuthenticatedRuntimeSession claimed = session(stored.getRuntimeBindingId(), 0,
                "legacy-runtime", "legacy-fence", "legacy-replica", 0);
        assertThrows(IllegalStateException.class, () -> tx(() ->
                persistence.generations.append(scope, claimed,
                        AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                        null, null, NOW + 1, null)));
        AgentRuntimeV2BindingEntity unchanged = tx(() ->
                persistence.bindings.findExact(scope, legacy.getRuntimeBindingId()));
        assertEquals(1, unchanged.getContractVersion());
        assertEquals("ACTIVE", unchanged.getStatus());
        assertEquals(0, factCount());
    }

    @Test
    void directMapperUsesBinaryReceiverScopeAndDatabaseUniquenessBlocksForks() {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession generation0 = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        AgentRuntimeV2BindingGenerationFactEntity initial = tx(() ->
                persistence.generations.createVersion2(scope, draft(NOW + 100), generation0,
                        VERIFIER, NOW));

        assertNull(lockLatestDirect(persistence, scope, "Owner-a", BINDING));
        assertNull(lockLatestDirect(persistence, scope, "owner-a ", BINDING));
        assertNull(lockLatestDirect(persistence, receiverClient(scope, "Client-r"),
                scope.ownerJiacn(), BINDING));
        assertNull(findBindingDirect(persistence, scope, "Agent-a", BINDING));
        assertNull(findBindingDirect(persistence, scope, "agent-a ", BINDING));

        AgentRuntimeV2BindingGenerationFactEntity disconnected = directFact(
                scope, generation0, 1, initial.getBindingGenerationFactId(),
                "DISCONNECTED", NOW + 100, NOW + 1, null);
        assertEquals(1, tx(() -> insertDirect(persistence, scope, disconnected)));
        AgentRuntimeV2BindingGenerationFactEntity predecessorFork = directFact(
                scope, session(BINDING, 1, "runtime-a", "fence-1", "replica-b", 4),
                2, initial.getBindingGenerationFactId(), "CONNECTED",
                NOW + 200, NOW + 2, null);
        assertThrows(DataIntegrityViolationException.class,
                () -> tx(() -> insertDirect(persistence, scope, predecessorFork)));

        AgentRuntimeV2BindingGenerationFactEntity firstRevoke = directFact(
                scope, generation0, 2, disconnected.getBindingGenerationFactId(),
                "REVOKED", NOW + 100, NOW + 2, "first");
        assertEquals(1, tx(() -> insertDirect(persistence, scope, firstRevoke)));
        AgentRuntimeV2BindingGenerationFactEntity secondRevoke = directFact(
                scope, session(BINDING, 1, "runtime-a", "fence-1", "replica-b", 4),
                3, "00000000-0000-4000-8000-0000000000f1", "REVOKED",
                NOW + 200, NOW + 3, "second");
        assertThrows(DataIntegrityViolationException.class,
                () -> tx(() -> insertDirect(persistence, scope, secondRevoke)));
    }

    @Test
    void concurrentDisconnectHasOneWinnerAndNoFactFork() throws Exception {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession current = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), current, VERIFIER, NOW));

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> first = executor.submit(() -> disconnectAfterBarrier(scope, current, ready, start));
        Future<Boolean> second = executor.submit(() -> disconnectAfterBarrier(scope, current, ready, start));
        ready.await();
        start.countDown();

        int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        assertEquals(1, winners);
        assertEquals(2, factCount());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT fact_sequence) FROM agent_runtime_v2_binding_generation_fact",
                Integer.class));
        assertEquals("DISCONNECTED", tx(() ->
                persistence.bindings.findExact(scope, BINDING)).getStatus());
    }

    @Test
    void concurrentReconnectsHaveOneWinnerAndProjectionMatchesTheLinearFactChain() throws Exception {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession disconnectedSession = session(
                BINDING, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), disconnectedSession, VERIFIER, NOW));
        tx(() -> persistence.generations.append(scope, disconnectedSession,
                AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                null, null, NOW + 1, null));

        AuthenticatedRuntimeSession firstNext = session(
                BINDING, 1, "runtime-a", "fence-1-a", "replica-b", 4);
        AuthenticatedRuntimeSession secondNext = session(
                BINDING, 1, "runtime-a", "fence-1-b", "replica-c", 5);
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<AgentRuntimeV2BindingGenerationFactEntity> first = executor.submit(() ->
                reconnectAfterBarrier(scope, disconnectedSession, firstNext,
                        NOW + 200, ready, start));
        Future<AgentRuntimeV2BindingGenerationFactEntity> second = executor.submit(() ->
                reconnectAfterBarrier(scope, disconnectedSession, secondNext,
                        NOW + 300, ready, start));
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();

        AgentRuntimeV2BindingGenerationFactEntity firstResult = first.get(10, TimeUnit.SECONDS);
        AgentRuntimeV2BindingGenerationFactEntity secondResult = second.get(10, TimeUnit.SECONDS);
        assertTrue((firstResult == null) ^ (secondResult == null));
        AgentRuntimeV2BindingGenerationFactEntity winner = firstResult != null
                ? firstResult : secondResult;
        assertEquals("CONNECTED", winner.getEventType());
        assertEquals(1L, winner.getBindingGeneration());
        assertLinearFactChain(BINDING, 3);

        AgentRuntimeV2BindingGenerationFactEntity latest = tx(() ->
                persistence.generations.lockLatest(scope, BINDING));
        AgentRuntimeV2BindingEntity projection = tx(() ->
                persistence.bindings.findExact(scope, BINDING));
        assertEquals(winner.getBindingGenerationFactId(), latest.getBindingGenerationFactId());
        assertEquals("ACTIVE", projection.getStatus());
        assertEquals(latest.getBindingGeneration(), projection.getBindingGeneration());
        assertEquals(latest.getRuntimeInstanceId(), projection.getRuntimeInstanceId());
        assertEquals(latest.getSessionFence(), projection.getCurrentSessionFence());
        assertEquals(latest.getSocketOwnerInstanceId(), projection.getSocketOwnerInstanceId());
        assertEquals(latest.getSocketOwnerEpoch(), projection.getSocketOwnerEpoch());
        assertEquals(latest.getSocketOwnerLeaseUntil(), projection.getSocketOwnerLeaseUntil());
    }

    @Test
    void reconnectVsRevokeRaceAllowsEitherLegalWinnerWithoutForking() throws Exception {
        executor = Executors.newFixedThreadPool(2);
        assertReconnectVsRevokeOutcome(BINDING, true);
        assertReconnectVsRevokeOutcome(OTHER_BINDING, false);
    }

    private boolean disconnectAfterBarrier(RuntimeReceiverScope scope,
            AuthenticatedRuntimeSession current, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            tx(() -> persistence.generations.append(scope, current,
                    AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                    null, null, NOW + 1, null));
            return true;
        } catch (RuntimeException expectedLoser) {
            return false;
        }
    }

    private AgentRuntimeV2BindingGenerationFactEntity reconnectAfterBarrier(
            RuntimeReceiverScope scope, AuthenticatedRuntimeSession expected,
            AuthenticatedRuntimeSession next, long leaseUntil,
            CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return tx(() -> persistence.generations.append(scope, expected,
                    AgentRuntimeV2BindingGenerationEvent.CONNECTED,
                    next, leaseUntil, NOW + 2, null));
        } catch (RuntimeException expectedLoser) {
            return null;
        }
    }

    private void assertReconnectVsRevokeOutcome(String bindingId,
            boolean reconnectMustWin) throws Exception {
        RuntimeReceiverScope scope = scope();
        AuthenticatedRuntimeSession disconnectedSession = session(
                bindingId, 0, "runtime-a", "fence-0", "replica-a", 3);
        tx(() -> persistence.generations.createVersion2(
                scope, draft(NOW + 100), disconnectedSession, VERIFIER, NOW));
        tx(() -> persistence.generations.append(scope, disconnectedSession,
                AgentRuntimeV2BindingGenerationEvent.DISCONNECTED,
                null, null, NOW + 1, null));
        AuthenticatedRuntimeSession nextSession = session(
                bindingId, 1, "runtime-a", "fence-1", "replica-b", 4);

        CountDownLatch preferredLocked = new CountDownLatch(1);
        CountDownLatch contenderEntered = new CountDownLatch(1);
        Future<AgentRuntimeV2BindingGenerationFactEntity> preferred = executor.submit(() -> tx(() -> {
            assertNotNull(persistence.bindings.lockExact(scope, bindingId));
            preferredLocked.countDown();
            await(contenderEntered);
            return appendReconnectOrRevoke(scope, disconnectedSession, nextSession,
                    reconnectMustWin, "preferred");
        }));
        assertTrue(preferredLocked.await(5, TimeUnit.SECONDS));
        Future<AgentRuntimeV2BindingGenerationFactEntity> contender = executor.submit(() -> {
            try {
                return tx(() -> {
                    contenderEntered.countDown();
                    return appendReconnectOrRevoke(scope, disconnectedSession, nextSession,
                            !reconnectMustWin, "contender");
                });
            } catch (RuntimeException expectedLoser) {
                return null;
            }
        });

        AgentRuntimeV2BindingGenerationFactEntity winner = preferred.get(10, TimeUnit.SECONDS);
        AgentRuntimeV2BindingGenerationFactEntity loser = contender.get(10, TimeUnit.SECONDS);
        assertNotNull(winner);
        assertNull(loser);
        assertEquals(reconnectMustWin ? "CONNECTED" : "REVOKED", winner.getEventType());
        assertLinearFactChain(bindingId, 3);

        AgentRuntimeV2BindingGenerationFactEntity latest = tx(() ->
                persistence.generations.lockLatest(scope, bindingId));
        AgentRuntimeV2BindingEntity projection = tx(() ->
                persistence.bindings.findExact(scope, bindingId));
        assertEquals(winner.getBindingGenerationFactId(), latest.getBindingGenerationFactId());
        assertEquals(latest.getBindingGeneration(), projection.getBindingGeneration());
        assertEquals(latest.getRuntimeInstanceId(), projection.getRuntimeInstanceId());
        if (reconnectMustWin) {
            assertEquals("ACTIVE", projection.getStatus());
            assertEquals(1L, latest.getBindingGeneration());
            assertEquals(latest.getSessionFence(), projection.getCurrentSessionFence());
            assertEquals(latest.getSocketOwnerInstanceId(), projection.getSocketOwnerInstanceId());
            assertEquals(latest.getSocketOwnerEpoch(), projection.getSocketOwnerEpoch());
            assertEquals(latest.getSocketOwnerLeaseUntil(), projection.getSocketOwnerLeaseUntil());
        } else {
            assertEquals("REVOKED", projection.getStatus());
            assertEquals(0L, latest.getBindingGeneration());
            assertEquals(latest.getEffectiveAt(), projection.getRevokedAt());
            assertEquals(latest.getReason(), projection.getRevokeReason());
            assertNull(projection.getCurrentSessionFence());
            assertNull(projection.getSocketOwnerInstanceId());
            assertNull(projection.getSocketOwnerEpoch());
            assertNull(projection.getSocketOwnerLeaseUntil());
        }
    }

    private AgentRuntimeV2BindingGenerationFactEntity appendReconnectOrRevoke(
            RuntimeReceiverScope scope, AuthenticatedRuntimeSession expected,
            AuthenticatedRuntimeSession next, boolean reconnect, String reasonSuffix) {
        if (reconnect) {
            return persistence.generations.append(scope, expected,
                    AgentRuntimeV2BindingGenerationEvent.CONNECTED,
                    next, NOW + 200, NOW + 2, null);
        }
        return persistence.generations.append(scope, expected,
                AgentRuntimeV2BindingGenerationEvent.REVOKED,
                null, null, NOW + 2, "race-" + reasonSuffix);
    }

    private void assertLinearFactChain(String bindingId, int expectedCount) {
        assertEquals(expectedCount, jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_runtime_v2_binding_generation_fact
                 WHERE runtime_binding_id=?
                """, Integer.class, bindingId));
        assertEquals(expectedCount, jdbc.queryForObject("""
                SELECT COUNT(DISTINCT fact_sequence)
                  FROM agent_runtime_v2_binding_generation_fact
                 WHERE runtime_binding_id=?
                """, Integer.class, bindingId));
        assertEquals(expectedCount - 1, jdbc.queryForObject("""
                SELECT COUNT(DISTINCT previous_fact_id)
                  FROM agent_runtime_v2_binding_generation_fact
                 WHERE runtime_binding_id=? AND previous_fact_id IS NOT NULL
                """, Integer.class, bindingId));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM agent_runtime_v2_binding_generation_fact successor
                  LEFT JOIN agent_runtime_v2_binding_generation_fact predecessor
                    ON predecessor.binding_generation_fact_id=successor.previous_fact_id
                 WHERE successor.runtime_binding_id=? AND successor.fact_sequence>0
                   AND predecessor.binding_generation_fact_id IS NULL
                """, Integer.class, bindingId));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent lifecycle contender did not enter");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrent lifecycle test interrupted", interrupted);
        }
    }

    private Persistence persistence() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeV2BindingMapper.class);
        configuration.addMapper(AgentRuntimeV2BindingGenerationFactMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        AgentRuntimeV2BindingMapper bindingMapper = sessions.getMapper(AgentRuntimeV2BindingMapper.class);
        AgentRuntimeV2BindingGenerationFactMapper factMapper = sessions.getMapper(
                AgentRuntimeV2BindingGenerationFactMapper.class);
        AgentRuntimeV2BindingDao bindings = new AgentRuntimeV2BindingDaoImpl(bindingMapper);
        AgentRuntimeV2BindingGenerationFactDao generations =
                new AgentRuntimeV2BindingGenerationFactDaoImpl(bindingMapper, bindings, factMapper);
        return new Persistence(bindingMapper, factMapper, bindings, generations);
    }

    private <T> T tx(Supplier<T> work) {
        return transaction().execute(status -> work.get());
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    private int factCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime_v2_binding_generation_fact", Integer.class);
    }

    private static AgentRuntimeV2BindingEntity draft(long leaseUntil) {
        AgentRuntimeV2BindingEntity draft = new AgentRuntimeV2BindingEntity()
                .setCredentialKind("API_KEY").setCredentialReference("receiver-key")
                .setSocketOwnerLeaseUntil(leaseUntil);
        return draft;
    }

    private static RuntimeReceiverScope scope() {
        return new RuntimeReceiverScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "pool-a", "client-r", "owner-a", "agent-a", "claimant-b",
                7, 17, 11, "agent-command", 13);
    }

    private static RuntimeReceiverScope receiverClient(RuntimeReceiverScope scope, String client) {
        return new RuntimeReceiverScope(scope.tenantId(), scope.dataDomainId(), scope.executionPoolId(),
                client, scope.ownerJiacn(), scope.agentId(), scope.claimantDeploymentId(),
                scope.claimantWorkerGeneration(), scope.receiverRegistrationRevision(),
                scope.admissionEpoch(), scope.moduleId(), scope.migrationEpoch());
    }

    private static AuthenticatedRuntimeSession session(String binding, long generation,
            String runtime, String fence, String owner, long epoch) {
        return new AuthenticatedRuntimeSession(binding, generation, runtime, fence, owner, epoch);
    }

    private static AgentRuntimeV2BindingGenerationFactEntity directFact(RuntimeReceiverScope scope,
            AuthenticatedRuntimeSession session, long sequence, String previous, String event,
            long leaseUntil, long effectiveAt, String reason) {
        AgentRuntimeV2BindingGenerationFactEntity entity =
                new AgentRuntimeV2BindingGenerationFactEntity()
                        .setBindingGenerationFactId(UUID.randomUUID().toString())
                        .setFactSequence(sequence).setPreviousFactId(previous)
                        .setRuntimeBindingId(session.runtimeBindingId())
                        .setBindingGeneration(session.bindingGeneration()).setEventType(event)
                        .setRuntimeInstanceId(session.runtimeInstanceId())
                        .setDataDomainId(scope.dataDomainId().value())
                        .setExecutionPoolId(scope.executionPoolId()).setOwnerJiacn(scope.ownerJiacn())
                        .setAgentId(scope.agentId()).setClaimantDeploymentId(scope.claimantDeploymentId())
                        .setClaimantWorkerGeneration(scope.claimantWorkerGeneration())
                        .setReceiverRegistrationRevision(scope.receiverRegistrationRevision())
                        .setAdmissionEpoch(scope.admissionEpoch()).setModuleId(scope.moduleId())
                        .setMigrationEpoch(scope.migrationEpoch()).setSessionFence(session.sessionFence())
                        .setSocketOwnerInstanceId(session.socketOwnerInstanceId())
                        .setSocketOwnerEpoch(session.socketOwnerEpoch()).setSocketOwnerLeaseUntil(leaseUntil)
                        .setEffectiveAt(effectiveAt).setReason(reason);
        entity.setTenantId(scope.tenantId().value());
        entity.setClientId(scope.clientId());
        entity.setCreateTime(effectiveAt);
        entity.setUpdateTime(effectiveAt);
        return entity;
    }

    private static int insertDirect(Persistence p, RuntimeReceiverScope scope,
            AgentRuntimeV2BindingGenerationFactEntity fact) {
        return p.factMapper.insert(scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId(), scope.clientId(), scope.ownerJiacn(), scope.agentId(),
                scope.claimantDeploymentId(), scope.claimantWorkerGeneration(),
                scope.receiverRegistrationRevision(), scope.admissionEpoch(), scope.moduleId(),
                scope.migrationEpoch(), fact);
    }

    private static AgentRuntimeV2BindingGenerationFactEntity lockLatestDirect(Persistence p,
            RuntimeReceiverScope scope, String owner, String binding) {
        return p.factMapper.lockLatest(scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId(), scope.clientId(), owner, scope.agentId(),
                scope.claimantDeploymentId(), scope.claimantWorkerGeneration(),
                scope.receiverRegistrationRevision(), scope.admissionEpoch(), scope.moduleId(),
                scope.migrationEpoch(), binding);
    }

    private static AgentRuntimeV2BindingGenerationFactEntity lockLatestDirect(Persistence p,
            RuntimeReceiverScope scope, String owner, String binding, boolean ignored) {
        return lockLatestDirect(p, scope, owner, binding);
    }

    private static AgentRuntimeV2BindingEntity findBindingDirect(Persistence p,
            RuntimeReceiverScope scope, String agent, String binding) {
        return p.bindingMapper.findExact(scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId(), scope.clientId(), scope.ownerJiacn(), agent,
                scope.claimantDeploymentId(), scope.claimantWorkerGeneration(),
                scope.receiverRegistrationRevision(), scope.admissionEpoch(), scope.moduleId(),
                scope.migrationEpoch(), binding);
    }

    private static byte[] verifier(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return value;
    }

    private static void createTables(Connection connection) throws Exception {
        String sql = Files.readString(sqlPath());
        try (var statement = connection.createStatement()) {
            statement.execute(h2CreateTable(sql, "agent_runtime_v2_binding"));
            statement.execute(h2CreateTable(sql, "agent_runtime_v2_binding_generation_fact"));
            for (String definition : new String[]{
                    "registration_subject_sha256 BINARY(32)",
                    "registration_authority_id VARCHAR(36)",
                    "registration_authority_revision BIGINT",
                    "process_public_key_spki BINARY(44)",
                    "process_public_key_sha256 BINARY(32)",
                    "verifier_version BIGINT",
                    "verifier_key_id VARCHAR(100)"}) {
                statement.execute("ALTER TABLE agent_runtime_v2_binding ADD COLUMN " + definition);
            }
            for (String definition : new String[]{
                    "registration_subject_sha256 BINARY(32)",
                    "registration_authority_id VARCHAR(36)",
                    "registration_authority_revision BIGINT",
                    "runtime_credential_client_id VARCHAR(50)",
                    "process_public_key_sha256 BINARY(32)",
                    "verifier_version BIGINT",
                    "registration_receipt_id VARCHAR(36)",
                    "registration_receipt_sha256 BINARY(32)",
                    "connection_admission_kind VARCHAR(20)",
                    "drain_control_operation_id VARCHAR(100)",
                    "generation_snapshot_sha256 BINARY(32)"}) {
                statement.execute("ALTER TABLE agent_runtime_v2_binding_generation_fact ADD COLUMN "
                        + definition);
            }
        }
    }

    private static Path sqlPath() {
        return Path.of(System.getProperty("user.dir"), "..", "jia-agent-mapper",
                "src", "main", "resources", "db",
                "agent-runtime-v2-persistence-manual-20260930.sql").normalize();
    }

    private static String h2CreateTable(String sql, String table) {
        String marker = "CREATE TABLE " + table + " (";
        int start = sql.indexOf(marker);
        if (start < 0) throw new IllegalArgumentException("missing table " + table);
        int end = sql.indexOf(';', start);
        if (end < 0) throw new IllegalArgumentException("unterminated table " + table);
        return sql.substring(start, end + 1)
                .replaceAll("(?i) COLLATE utf8mb4_0900_bin", "")
                .replace(" STORED", "")
                .replaceAll("(?i)\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;$", ");");
    }

    private record Persistence(AgentRuntimeV2BindingMapper bindingMapper,
            AgentRuntimeV2BindingGenerationFactMapper factMapper,
            AgentRuntimeV2BindingDao bindings,
            AgentRuntimeV2BindingGenerationFactDao generations) {}
}
