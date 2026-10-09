package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentCommandRedriveRabbitSettings;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentCommandDlqRedriverV2AdmissionTest {
    private static final String ARTIFACT = "a".repeat(64);
    private static final String CREDENTIAL = "c".repeat(64);
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);
    private static final AgentRabbitV2TopologyManifest TOPOLOGY =
            AgentRabbitV2TopologyManifest.forScope(SCOPE);
    private static final AgentCommandRedriveRabbitSettings SETTINGS =
            new AgentCommandRedriveRabbitSettings(
                    2_000, 2_000, 2_000, 1_000,
                    15_000, 10_000, 500, 12);

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AgentCommandRedriveRawProvider provider;
    private Connection sourceConnection;
    private Channel sourceChannel;
    private Connection publishConnection;
    private Channel publishChannel;
    private AgentCommandOperationsDao dao;
    private AgentCommandDlqRedriverImpl redriver;
    private AgentCommandDeliveryEntity delivery;
    private AgentOutboxEventEntity outbox;
    private final AtomicReference<AgentCommandRedrivePublishAttempt> lastAttempt =
            new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:redrive_" + UUID.randomUUID()
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        createAuthoritySchema();
        seedAuthority();
        provider = mock(AgentCommandRedriveRawProvider.class);
        sourceConnection = mock(Connection.class);
        sourceChannel = mock(Channel.class);
        publishConnection = mock(Connection.class);
        publishChannel = mock(Channel.class);
        dao = mock(AgentCommandOperationsDao.class);
        when(provider.openSource()).thenAnswer(invocation -> new AgentCommandRedriveSourceSession(
                sourceConnection, sourceChannel, 100));
        doAnswer(invocation -> {
            AgentConfirmedPublishRequest request = invocation.getArgument(0);
            AgentCommandRedrivePublishAttempt attempt = new AgentCommandRedrivePublishAttempt(
                    publishConnection, publishChannel, request, properties(request), 100);
            lastAttempt.set(attempt);
            return attempt;
        }).when(provider).preparePublish(any(), any());
        when(publishChannel.waitForConfirms(anyLong())).thenReturn(true);
        seedPersistentSource();
        redriver = newRedriver(transactions, SETTINGS);
    }

    @AfterEach
    void tearDown() {
        if (redriver != null) redriver.close();
    }

    @Test
    void exactAuthorityAndProtocolAreRequiredBeforeAnyConnection() {
        AgentConfirmedPublishRequest expected = request();
        WorkerScope wrongPool = new WorkerScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-b", "deployment-a",
                7, 11, 13, "agent-command", 17);
        TenantModuleAdmissionGuard guard = guard();
        AgentCommandDlqRedriverImpl wrong = new AgentCommandDlqRedriverImpl(
                provider, dao, wrongPool, guard, transactions, SETTINGS);
        try {
            assertEquals("DLQ_REDRIVE_SCOPE_MISMATCH",
                    wrong.redrive(expected, 5_000, 1).errorCode());
        } finally {
            wrong.close();
        }
        AgentConfirmedPublishRequest legacy = new AgentConfirmedPublishRequest(
                expected.destination(), expected.routingKey(), expected.wirePayload(),
                expected.wirePayloadHash(), expected.messageId(), expected.eventId(),
                expected.deliveryId(), expected.commandId(), "1", expected.clientId(),
                expected.taskId(), expected.targetAgentId(), expected.commandType(),
                expected.activeAttempt(), expected.expiresAt(), expected.topologySha256(), 0);
        assertEquals("LEGACY_DLQ_REDRIVE_UNSUPPORTED",
                redriver.redrive(legacy, 5_000, 1).errorCode());
        verifyNoInteractions(provider);
    }

    @Test
    void publishPreparationBlocksOutsideDatabaseLocksAndCloseThenPreventsSend()
            throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(1, expected));
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            preparing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return newAttempt(invocation.getArgument(0));
        }).when(provider).preparePublish(any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 5_000, 1));
            assertTrue(preparing.await(5, TimeUnit.SECONDS));
            assertEquals(1, closeTenant(executor).get(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("WORKER_ADMISSION_REJECTED",
                    operation.get(5, TimeUnit.SECONDS).errorCode());
            verify(publishChannel, never()).basicPublish(any(), any(), eq(true), any(), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void connectionPreparedBeforeTransactionButTenantCloseCommitsMeansZeroPublish()
            throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(2, expected));
        doAnswer(invocation -> {
            jdbc.update("UPDATE core_tenant_control SET state='WRITE_CLOSED_CLAIM_CLOSED' "
                    + "WHERE tenant_id='1'");
            return newAttempt(invocation.getArgument(0));
        }).when(provider).preparePublish(any(), any());
        assertEquals("WORKER_ADMISSION_REJECTED",
                redriver.redrive(expected, 5_000, 1).errorCode());
        verify(publishChannel, never()).basicPublish(any(), any(), eq(true), any(), any());
    }

    @Test
    void databaseValidationIsTransactionalButNetworkPublishRunsOnDedicatedThread()
            throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(3, expected));
        when(dao.lockDelivery("1", "client-a", 1L)).thenAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return delivery;
        });
        when(dao.lockActiveOutboxes("1", "client-a", 1L, "msg-1"))
                .thenAnswer(invocation -> {
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    return List.of(outbox);
                });
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(publishChannel).basicPublish(any(), any(), eq(true), any(), any());
        assertEquals(AgentRabbitPublishResult.Type.ACK,
                redriver.redrive(expected, 5_000, 1).type());
        verify(sourceChannel).basicAck(3, false);
    }

    @Test
    void tenantCloseWaitsOnlyForBoundedAdmittedAttemptAndBlocksLaterWork()
            throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(4, expected));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(publishChannel).basicPublish(any(), any(), eq(true), any(), any());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 5_000, 1));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            var close = closeTenant(executor);
            Thread.sleep(100);
            assertFalse(close.isDone());
            release.countDown();
            assertEquals(AgentRabbitPublishResult.Type.ACK,
                    operation.get(5, TimeUnit.SECONDS).type());
            assertEquals(1, close.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void permanentlyBlockedBasicPublishIsAbortedPoisonsServiceAndNeverReconnects()
            throws Exception {
        AgentCommandRedriveRabbitSettings fast = new AgentCommandRedriveRabbitSettings(
                100, 100, 100, 100, 2_500, 100, 100, 1);
        redriver.close();
        redriver = newRedriver(transactions, fast);
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(5, expected));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch recoverBackground = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            for (;;) {
                try {
                    if (recoverBackground.await(50, TimeUnit.MILLISECONDS)) return null;
                } catch (InterruptedException ignored) {
                    // Deliberately model a client call that ignores interruption.
                }
            }
        }).when(publishChannel).basicPublish(any(), any(), eq(true), any(), any());

        long started = System.nanoTime();
        AgentRabbitPublishResult result = redriver.redrive(expected, 100, 1);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(sending.getCount() == 0);
        assertEquals("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN", result.errorCode());
        assertTrue(elapsed < 1_500, "elapsed=" + elapsed);
        verify(publishConnection).abort(eq(541), any(), eq(100));
        assertEquals("DLQ_REDRIVE_SERVICE_POISONED",
                redriver.redrive(expected, 100, 1).errorCode());
        verify(provider, times(1)).openSource();
        recoverBackground.countDown();
        Thread.sleep(100);
        verify(publishChannel, times(1)).basicPublish(any(), any(), eq(true), any(), any());
    }

    @Test
    void deadlineExpiryAfterSendingCasPoisonsAbortsAndNeverReconnects()
            throws Exception {
        AgentCommandRedriveRabbitSettings fast = new AgentCommandRedriveRabbitSettings(
                100, 100, 100, 100, 2_500, 100, 100, 1);
        redriver.close();
        redriver = newRedriver(transactions, fast);
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(50, expected));
        CountDownLatch enteredSending = new CountDownLatch(1);
        CountDownLatch physicalAbort = new CountDownLatch(1);
        doAnswer(invocation -> new AgentCommandRedrivePublishAttempt(
                publishConnection, publishChannel, invocation.getArgument(0),
                properties(invocation.getArgument(0)), 100, System::nanoTime, () -> {
                    enteredSending.countDown();
                    boolean aborted = false;
                    while (!aborted) {
                        try {
                            aborted = physicalAbort.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // The physical abort, not interruption, releases this seam.
                        }
                    }
                })).when(provider).preparePublish(any(), any());
        doAnswer(invocation -> {
            physicalAbort.countDown();
            return null;
        }).when(publishConnection).abort(eq(541), any(), eq(100));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            var operation = caller.submit(() -> redriver.redrive(expected, 100, 1));
            assertTrue(enteredSending.await(5, TimeUnit.SECONDS));
            AgentRabbitPublishResult result = operation.get(5, TimeUnit.SECONDS);
            assertEquals("DLQ_REDRIVE_PUBLISH_TIMEOUT_UNCERTAIN", result.errorCode());
            verify(publishConnection).abort(eq(541), any(), eq(100));
            verify(publishChannel, never()).basicPublish(
                    any(), any(), eq(true), any(), any());
            assertEquals("DLQ_REDRIVE_SERVICE_POISONED",
                    redriver.redrive(expected, 100, 1).errorCode());
            verify(provider, times(1)).openSource();
        } finally {
            physicalAbort.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void closeIsBoundedDuringSourceScanAndLateSourceCanOnlyBeAborted() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AgentCommandRedriveSourceSession late = new AgentCommandRedriveSourceSession(
                sourceConnection, sourceChannel, 100);
        doAnswer(invocation -> {
            opening.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return late;
        }).when(provider).openSource();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 5_000, 1));
            assertTrue(opening.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            redriver.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000);
            release.countDown();
            assertEquals("DLQ_REDRIVE_SERVICE_CLOSED",
                    operation.get(5, TimeUnit.SECONDS).errorCode());
            verify(sourceConnection).abort(eq(541), any(), eq(100));
            verify(sourceChannel, never()).basicGet(any(), eq(false));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeDuringSendingAbortsPhysicalConnectionAndReturnsWithinBudget()
            throws Exception {
        AgentCommandRedriveRabbitSettings fast = new AgentCommandRedriveRabbitSettings(
                100, 100, 100, 100, 2_500, 100, 100, 1);
        redriver.close();
        redriver = newRedriver(transactions, fast);
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(6, expected));
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            while (!release.await(50, TimeUnit.MILLISECONDS)) {
                // Wait for the physical abort callback below.
            }
            return null;
        }).when(publishChannel).basicPublish(any(), any(), eq(true), any(), any());
        doAnswer(invocation -> {
            release.countDown();
            return null;
        }).when(publishConnection).abort(eq(541), any(), eq(100));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 100, 1));
            assertTrue(sending.await(5, TimeUnit.SECONDS));
            long started = System.nanoTime();
            redriver.close();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed < 1_500, "elapsed=" + elapsed);
            assertEquals("DLQ_REDRIVE_PUBLISH_ABORT_UNCONFIRMED",
                    operation.get(5, TimeUnit.SECONDS).errorCode());
            verify(publishConnection).abort(eq(541), any(), eq(100));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void tenantCloseCanCommitBetweenScannedMessagesAndPreventsTargetSend()
            throws Exception {
        AgentConfirmedPublishRequest expected = request();
        AgentConfirmedPublishRequest other = request("msg-other", "event-other");
        CountDownLatch between = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenAnswer(invocation -> {
                    if (reads.getAndIncrement() == 0) return response(7, other);
                    between.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return response(8, expected);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 5_000, 2));
            assertTrue(between.await(5, TimeUnit.SECONDS));
            assertEquals(1, closeTenant(executor).get(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("WORKER_ADMISSION_REJECTED",
                    operation.get(5, TimeUnit.SECONDS).errorCode());
            verify(publishChannel, never()).basicPublish(any(), any(), eq(true), any(), any());
            verify(sourceChannel).basicNack(7, false, true);
            verify(sourceChannel).basicNack(8, false, true);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void persistedV2ScopeAndEpochDriftAlwaysProducesZeroPublish() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        String[] fields = {
                "protocolVersion", "tenantId", "clientId", "dataDomainId",
                "executionPoolId", "admissionEpoch", "producerDeploymentId",
                "moduleId", "migrationEpoch"
        };
        Object[] original = {2, "1", "client-a", "cyf", "pool-a", 13L,
                "producer-a", "agent-command", 17L};
        Object[] drift = {1, "2", "client-b", "other", "pool-b", 14L,
                "producer-b", "other-module", 18L};
        for (int index = 0; index < fields.length; index++) {
            mutateOutbox(fields[index], drift[index]);
            when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                    .thenReturn(response(20 + index, expected));
            assertEquals("DLQ_REDRIVE_SOURCE_DRIFT",
                    redriver.redrive(expected, 5_000, 1).errorCode(), fields[index]);
            mutateOutbox(fields[index], original[index]);
        }
        verify(publishChannel, never()).basicPublish(any(), any(), eq(true), any(), any());
    }

    @Test
    void confirmAckFollowedByUnknownDatabaseCommitNeverAcksSource() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(30, expected));
        DataSourceTransactionManager failingCommit = new DataSourceTransactionManager(
                transactions.getDataSource()) {
            @Override
            protected void doCommit(DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new IllegalStateException("commit outcome unavailable");
            }
        };
        redriver.close();
        redriver = newRedriver(failingCommit, SETTINGS);
        AgentRabbitPublishResult result = redriver.redrive(expected, 5_000, 1);
        assertEquals("DLQ_REDRIVE_PUBLISH_TRANSACTION_UNCERTAIN", result.errorCode());
        verify(sourceChannel, never()).basicAck(30, false);
        verify(sourceChannel).basicNack(30, false, true);
    }

    @Test
    void ackBarrierAndSourceCloseFailuresAreSettlementUncertain() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(31, expected));
        doThrow(new IOException("ack unavailable")).when(sourceChannel).basicAck(31, false);
        assertEquals("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH",
                redriver.redrive(expected, 5_000, 1).errorCode());
    }



    @Test
    void closeDuringPreparedPublishAbortsLateConnectionAndSendsNothing() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(40, expected));
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            AgentCommandRedrivePublishAttempt attempt = newAttempt(invocation.getArgument(0));
            preparing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return attempt;
        }).when(provider).preparePublish(any(), any());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var operation = executor.submit(() -> redriver.redrive(expected, 5_000, 1));
            assertTrue(preparing.await(5, TimeUnit.SECONDS));
            redriver.close();
            release.countDown();
            assertEquals("DLQ_REDRIVE_SERVICE_CLOSED",
                    operation.get(5, TimeUnit.SECONDS).errorCode());
            verify(publishConnection).abort(eq(541), any(), eq(100));
            verify(publishChannel, never()).basicPublish(any(), any(), eq(true), any(), any());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }



    @Test
    void sourceBarrierFailureAfterAckIsSettlementUncertain() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(41, expected));
        AtomicInteger declares = new AtomicInteger();
        when(sourceChannel.queueDeclarePassive(TOPOLOGY.deadLetterQueue()))
                .thenAnswer(invocation -> {
                    if (declares.getAndIncrement() > 0) {
                        throw new IOException("barrier unavailable");
                    }
                    return mock(AMQP.Queue.DeclareOk.class);
                });
        assertEquals("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH",
                redriver.redrive(expected, 5_000, 1).errorCode());
    }



    @Test
    void sourceCloseFailureAfterAckIsSettlementUncertain() throws Exception {
        AgentConfirmedPublishRequest expected = request();
        when(sourceChannel.basicGet(TOPOLOGY.deadLetterQueue(), false))
                .thenReturn(response(42, expected));
        doThrow(new IOException("close unavailable")).when(sourceChannel)
                .close(eq(200), any());
        assertEquals("DLQ_SOURCE_SETTLEMENT_UNCERTAIN_AFTER_PUBLISH",
                redriver.redrive(expected, 5_000, 1).errorCode());
    }

    private AgentCommandDlqRedriverImpl newRedriver(
            DataSourceTransactionManager manager,
            AgentCommandRedriveRabbitSettings settings) {
        return new AgentCommandDlqRedriverImpl(
                provider, dao, SCOPE, guard(), manager, settings);
    }

    private TenantModuleAdmissionGuard guard() {
        return new TenantModuleAdmissionGuard(
                jdbc, "deployment-a", ARTIFACT, "pool-a", CREDENTIAL, 7, 11);
    }

    private AgentCommandRedrivePublishAttempt newAttempt(
            AgentConfirmedPublishRequest request) {
        AgentCommandRedrivePublishAttempt attempt = new AgentCommandRedrivePublishAttempt(
                publishConnection, publishChannel, request, properties(request), 100);
        lastAttempt.set(attempt);
        return attempt;
    }

    private java.util.concurrent.Future<Integer> closeTenant(ExecutorService executor) {
        return executor.submit(() -> new TransactionTemplate(transactions).execute(status ->
                jdbc.update("UPDATE core_tenant_control "
                        + "SET state='WRITE_CLOSED_CLAIM_CLOSED' WHERE tenant_id='1'")));
    }

    private void mutateOutbox(String field, Object value) {
        switch (field) {
            case "protocolVersion" -> outbox.setProtocolVersion((Integer) value);
            case "tenantId" -> outbox.setTenantId((String) value);
            case "clientId" -> outbox.setClientId((String) value);
            case "dataDomainId" -> outbox.setDataDomainId((String) value);
            case "executionPoolId" -> outbox.setExecutionPoolId((String) value);
            case "admissionEpoch" -> outbox.setAdmissionEpoch((Long) value);
            case "producerDeploymentId" -> outbox.setProducerDeploymentId((String) value);
            case "moduleId" -> outbox.setModuleId((String) value);
            case "migrationEpoch" -> outbox.setMigrationEpoch((Long) value);
            default -> throw new IllegalArgumentException(field);
        }
    }

    private void seedPersistentSource() {
        AgentConfirmedPublishRequest expected = request();
        delivery = new AgentCommandDeliveryEntity();
        delivery.setId(1L);
        delivery.setProtocolVersion(2);
        delivery.setTenantId("1");
        delivery.setClientId("client-a");
        delivery.setDataDomainId("cyf");
        delivery.setExecutionPoolId("pool-a");
        delivery.setAdmissionEpoch(13L);
        delivery.setProducerDeploymentId("producer-a");
        delivery.setModuleId("agent-command");
        delivery.setMigrationEpoch(17L);
        delivery.setCommandId("cmd-1");
        delivery.setTaskId("task-1");
        delivery.setTargetAgentId("agent-a");
        delivery.setCommandType("TASK_INVITE");
        delivery.setStatus("PUBLISHED");
        delivery.setActiveMessageId("msg-1");
        delivery.setActiveAttempt(1);
        delivery.setExpiresAt(1_700_000_060_000L);
        outbox = new AgentOutboxEventEntity();
        outbox.setId(2L);
        outbox.setProtocolVersion(2);
        outbox.setTenantId("1");
        outbox.setClientId("client-a");
        outbox.setDataDomainId("cyf");
        outbox.setExecutionPoolId("pool-a");
        outbox.setAdmissionEpoch(13L);
        outbox.setProducerDeploymentId("producer-a");
        outbox.setModuleId("agent-command");
        outbox.setMigrationEpoch(17L);
        outbox.setEventId("event-1");
        outbox.setMessageId("msg-1");
        outbox.setCommandId("cmd-1");
        outbox.setDeliveryId(1L);
        outbox.setAggregateType("task");
        outbox.setAggregateId("task-1");
        outbox.setDestination(expected.destination());
        outbox.setRoutingKey(expected.routingKey());
        outbox.setWirePayload(expected.wirePayload());
        outbox.setWirePayloadHash(expected.wirePayloadHash());
        outbox.setStatus("PUBLISHED");
        outbox.setAttemptCount(1);
        outbox.setActiveAttempt(1);
        outbox.setExpiresAt(1_700_000_060_000L);
        outbox.setPublisherConfirmStatus("ACK");
        outbox.setConfirmedAt(1_700_000_001_000L);
        outbox.setMandatoryReturnStatus("NOT_RETURNED");
        outbox.setPublishedAt(1_700_000_001_000L);
        when(dao.lockDelivery("1", "client-a", 1L)).thenReturn(delivery);
        when(dao.lockActiveOutboxes("1", "client-a", 1L, "msg-1"))
                .thenReturn(List.of(outbox));
    }

    private AgentConfirmedPublishRequest request() {
        return request("msg-1", "event-1");
    }

    private AgentConfirmedPublishRequest request(String messageId, String eventId) {
        byte[] wire = ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"" + messageId + "\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"1\",\"dataDomainId\":\"cyf\"," +
                "\"executionPoolId\":\"pool-a\",\"producerDeploymentId\":\"producer-a\"," +
                "\"admissionEpoch\":13,\"moduleId\":\"agent-command\",\"migrationEpoch\":17," +
                "\"deliveryId\":\"1\"," +
                "\"clientId\":\"client-a\",\"taskId\":\"task-1\"," +
                "\"targetAgentId\":\"agent-a\",\"commandType\":\"TASK_INVITE\"," +
                "\"attempt\":1,\"expiresAt\":1700000060000,\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
        return new AgentConfirmedPublishRequest(
                TOPOLOGY.mainExchange(),
                TOPOLOGY.generalRoutingKey(),
                wire, AgentCommandAmqpContract.sha256(wire), messageId, eventId, 1,
                "cmd-1", "1", "cyf", "pool-a", 13, "producer-a", "agent-command", 17,
                2, "client-a", "task-1", "agent-a", "TASK_INVITE", 1,
                1_700_000_060_000L, TOPOLOGY.sha256(), 0);
    }

    private GetResponse response(long tag, AgentConfirmedPublishRequest expected) {
        Map<String, Object> headers = new LinkedHashMap<>(AgentCommandAmqpContract.headers(expected));
        headers.put("spring_listener_return_correlation",
                "11111111-1111-1111-1111-111111111111");
        headers.put("spring_returned_message_correlation",
                "22222222-2222-2222-2222-222222222222");
        headers.put("x-death", List.of(Map.of(
                "reason", "rejected", "queue", TOPOLOGY.dispatchQueue(),
                "exchange", TOPOLOGY.mainExchange(), "count", 1L,
                "time", new Date(1_700_000_000_000L),
                "routing-keys", List.of(TOPOLOGY.generalRoutingKey()))));
        headers.put("x-first-death-queue", TOPOLOGY.dispatchQueue());
        headers.put("x-first-death-exchange", TOPOLOGY.mainExchange());
        headers.put("x-first-death-reason", "rejected");
        AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2).messageId(expected.messageId())
                .type(AgentCommandAmqpContract.MESSAGE_TYPE).headers(headers).build();
        return new GetResponse(new Envelope(tag, false,
                TOPOLOGY.deadLetterExchange(),
                TOPOLOGY.deadRoutingKey()),
                properties, expected.wirePayload(), 0);
    }

    private AMQP.BasicProperties properties(AgentConfirmedPublishRequest request) {
        return new AMQP.BasicProperties.Builder()
                .contentType(AgentCommandAmqpContract.CONTENT_TYPE)
                .contentEncoding(AgentCommandAmqpContract.CONTENT_ENCODING)
                .deliveryMode(2).messageId(request.messageId())
                .type(AgentCommandAmqpContract.MESSAGE_TYPE)
                .headers(AgentCommandAmqpContract.headers(request)).build();
    }

    private void createAuthoritySchema() {
        jdbc.execute("CREATE TABLE core_tenant_control (tenant_id VARCHAR(50) PRIMARY KEY, "
                + "state VARCHAR(40), tenant_session_epoch BIGINT, admission_epoch BIGINT)");
        jdbc.execute("CREATE TABLE core_tenant_module_control (tenant_id VARCHAR(50), "
                + "module_id VARCHAR(64), mode_state VARCHAR(20), mode_revision BIGINT, "
                + "migration_epoch BIGINT, drain_sealed BOOLEAN, schema_version BIGINT, "
                + "mapping_digest VARCHAR(64), PRIMARY KEY(tenant_id,module_id))");
        jdbc.execute("CREATE TABLE core_deployment_registration (deployment_id VARCHAR(100) PRIMARY KEY, "
                + "stage VARCHAR(20), artifact_digest VARCHAR(64), credential_digest VARCHAR(64), "
                + "status VARCHAR(20), worker_generation BIGINT, registration_revision BIGINT)");
        jdbc.execute("CREATE TABLE core_deployment_tenant_grant (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), execution_pool_id VARCHAR(100), "
                + "status VARCHAR(20), grant_revision BIGINT, allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id))");
        jdbc.execute("CREATE TABLE core_deployment_module_admission (deployment_id VARCHAR(100), "
                + "tenant_id VARCHAR(50), data_domain_id VARCHAR(64), module_id VARCHAR(64), "
                + "mode_state VARCHAR(20), mode_revision BIGINT, migration_epoch BIGINT, "
                + "admission_epoch BIGINT, schema_version BIGINT, registration_revision BIGINT, "
                + "grant_revision BIGINT, status VARCHAR(20), allow_read BOOLEAN, allow_write BOOLEAN, "
                + "PRIMARY KEY(deployment_id,tenant_id,data_domain_id,module_id))");
    }

    private void seedAuthority() throws Exception {
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',2,13)");
        jdbc.update("INSERT INTO core_tenant_module_control VALUES "
                + "('1','agent-command','DOMAIN',5,17,TRUE,2,?)", "b".repeat(64));
        jdbc.update("INSERT INTO core_deployment_registration VALUES "
                + "('deployment-a','SHARED',?,?,'ACTIVE',7,11)", ARTIFACT,
                credentialDigest("deployment-a", 7, CREDENTIAL));
        jdbc.update("INSERT INTO core_deployment_tenant_grant VALUES "
                + "('deployment-a','1','cyf','pool-a','ACTIVE',19,TRUE,TRUE)");
        jdbc.update("INSERT INTO core_deployment_module_admission VALUES "
                + "('deployment-a','1','cyf','agent-command','DOMAIN',5,17,13,2,11,19,"
                + "'ACTIVE',TRUE,TRUE)");
    }

    private static String credentialDigest(
            String deploymentId, long generation, String credential) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("cyf-deployment-v1\u0000".getBytes(StandardCharsets.US_ASCII));
        digest.update(deploymentId.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(generation).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) 0);
        digest.update(credential.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }
}
