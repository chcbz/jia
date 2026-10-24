package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.dao.impl.AgentCommandInboxV2DaoImpl;
import cn.jia.agent.entity.AgentCommandAckResult;
import cn.jia.agent.entity.AgentCommandAckV2;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.mapper.AgentCommandInboxV2Mapper;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/** Real transaction and MyBatis proof for the durable consumer boundary. */
class AgentCommandInboxV2TransactionTest {
    @Test void combinedDurableHandoffLeavesSettledSentAckFormulaReachable() throws Exception {
        cn.jia.agent.config.AgentRuntimeV2DispatchTransactionTest.verifyCombinedHandoffAckFormula();
    }
    @Test void productionDurableHandoffUsesIndependentCompositeTransactionAndTransportAfterCleanup() throws Exception {
        cn.jia.agent.config.AgentRuntimeV2DispatchTransactionTest.verifyPostTransactionTransport();
        cn.jia.agent.config.AgentRuntimeV2DispatchTransactionTest.verifyCommitFailureZeroSend();
    }
    private static final long NOW = 1_000L;
    private static final long EXPIRES = 10_000L;
    private static final byte[] WIRE = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "local-test", "consumer-b",
            5, 4, 7, "agent-command", 9);

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private AgentCommandInboxV2Dao dao;
    private RecordingTransactionManager manager;
    private TenantModuleAdmissionGuard guard;
    private AgentCommandInboxV2ServiceImpl service;
    private AgentCommandAckV2ServiceImpl ackService;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:inbox_v2_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandInboxV2Mapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        dao = new AgentCommandInboxV2DaoImpl(
                new SqlSessionTemplate(factory).getMapper(AgentCommandInboxV2Mapper.class));

        manager = new RecordingTransactionManager(source);
        guard = mock(TenantModuleAdmissionGuard.class);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Function<TenantModuleAdmissionGuard.Mode, Object> work = invocation.getArgument(2);
            return work.apply(TenantModuleAdmissionGuard.Mode.DOMAIN);
        }).when(guard).withWorkerAdmission(eq(SCOPE), any(), any());
        service = new AgentCommandInboxV2ServiceImpl(dao, SCOPE, guard, manager);
        ackService = new AgentCommandAckV2ServiceImpl(dao, SCOPE, guard, manager);
    }

    @Test
    void sourceUsesClearedRelayClaimantWhileInboxPersistsDifferentConsumerAuthority() {
        insertPublishedSource(41, 51, "msg-1", "evt-1", null, null);

        AgentInboxClaim claim = service.claim(message(41, "msg-1", "evt-1"),
                "lease-a", NOW, 1_000);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals(1, manager.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("CONSUMED", value("agent_command_delivery", 41, "status"));
        assertEquals("producer-a", value("agent_consumer_inbox", claim.token().inboxId(),
                "producer_deployment_id"));
        assertEquals("consumer-b", value("agent_consumer_inbox", claim.token().inboxId(),
                "claimant_deployment_id"));
        assertEquals(null, value("agent_command_delivery", 41, "claimed_deployment_id"));
        assertEquals(null, value("agent_outbox_event", 51, "claimed_deployment_id"));

        execute(service, claim.token(), NOW + 100);
        assertEquals(2, manager.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

        service.complete(claim.token(), AgentInboxDisposition.sent(), NOW + 200);
        assertEquals(3, manager.commits);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals("SENT", value("agent_command_delivery", 41, "status"));
        assertEquals("PROCESSED", value(
                "agent_consumer_inbox", claim.token().inboxId(), "status"));

        insertPublishedSource(42, 52, "msg-2", "evt-2", "relay-a", 5L);
        assertThrows(IllegalStateException.class, () -> service.claim(
                message(42, "msg-2", "evt-2"), "lease-b", NOW, 1_000));
        assertEquals(0L, countInbox("msg-2"));
        assertEquals("PUBLISHED", value("agent_command_delivery", 42, "status"));
    }

    @Test
    void eachBusinessIdentityMismatchRollsBackBeforeInboxInsertOrDeliveryCas() {
        String[] fields = {"task", "target", "type"};
        for (int index = 0; index < fields.length; index++) {
            long deliveryId = 60 + index;
            long outboxId = 160 + index;
            String messageId = "msg-source-" + fields[index];
            String eventId = "evt-source-" + fields[index];
            insertPublishedSource(deliveryId, outboxId, messageId, eventId, null, null);
            AgentInboxMessage mismatched = switch (fields[index]) {
                case "task" -> message(deliveryId, messageId, eventId, "other-task", "agent-1", "TASK_INVITE");
                case "target" -> message(deliveryId, messageId, eventId, "task-1", "other-agent", "TASK_INVITE");
                default -> message(deliveryId, messageId, eventId, "task-1", "agent-1", "WORK_ITEM_EXECUTE");
            };

            assertThrows(IllegalStateException.class,
                    () -> service.claim(mismatched, "lease-source", NOW, 1_000), fields[index]);
            assertEquals(0L, countInbox(messageId));
            assertEquals("PUBLISHED", value("agent_command_delivery", deliveryId, "status"));
            assertEquals(5L, longValue("agent_command_delivery", deliveryId, "version"));
        }
    }

    @Test
    void everyPublicBoundaryRejectsAmbientTransactionBeforeExternalSideEffects() {
        insertPublishedSource(41, 51, "msg-1", "evt-1", null, null);
        AgentInboxClaimToken token = service.claim(message(41, "msg-1", "evt-1"),
                "lease-a", NOW, 1_000).token();
        int committedBeforeAmbientCalls = manager.commits;
        AtomicBoolean externalSideEffect = new AtomicBoolean();

        assertAmbientRejected(() -> {
            service.claim(message(41, "msg-1", "evt-1"), "lease-b", NOW + 10, 500);
            externalSideEffect.set(true);
        });
        assertAmbientRejected(() -> {
            execute(service, token, NOW + 100);
            externalSideEffect.set(true);
        });
        assertAmbientRejected(() -> {
            service.complete(token, AgentInboxDisposition.sent(), NOW + 200);
            externalSideEffect.set(true);
        });
        assertAmbientRejected(() -> {
            ackService.acknowledge(ack(token, "RECEIVED", 1, NOW + 100), NOW + 100);
            externalSideEffect.set(true);
        });

        assertFalse(externalSideEffect.get());
        assertEquals(committedBeforeAmbientCalls, manager.commits);
        assertEquals("CONSUMED", value("agent_command_delivery", 41, "status"));
        assertEquals("PROCESSING", value("agent_consumer_inbox", token.inboxId(), "status"));
    }

    @Test
    void internalCasFailureRollsBackTheWholeRequiresNewTransaction() {
        insertPublishedSource(41, 51, "msg-1", "evt-1", null, null);
        AgentCommandInboxV2Dao failing = mock(AgentCommandInboxV2Dao.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(dao)));
        doReturn(0).when(failing).transitionDelivery(any(), anyInt(), anyLong(),
                anyString(), anyString(), nullable(Long.class), nullable(String.class), anyLong());
        AgentCommandInboxV2ServiceImpl failingService =
                new AgentCommandInboxV2ServiceImpl(failing, SCOPE, guard, manager);

        assertThrows(IllegalStateException.class, () -> failingService.claim(
                message(41, "msg-1", "evt-1"), "lease-a", NOW, 1_000));

        assertEquals(0L, countInbox("msg-1"));
        assertEquals("PUBLISHED", value("agent_command_delivery", 41, "status"));
        assertEquals(0, manager.commits);
        assertEquals(1, manager.rollbacks);
    }

    @Test
    void retryReclaimUsesTransitionedDeliveryVersionAndRejectsTheOldToken() {
        insertPublishedSource(90, 190, "msg-retry", "evt-retry", null, null);
        AgentInboxMessage message = message(90, "msg-retry", "evt-retry");
        AgentInboxClaimToken original = service.claim(
                message, "lease-original", NOW, 1_000).token();

        service.complete(original, new AgentInboxDisposition(
                AgentInboxDisposition.Type.RETRY, NOW + 200, "TRANSIENT"), NOW + 100);
        assertEquals("RETRY", value("agent_command_delivery", 90, "status"));
        assertEquals(7L, longValue("agent_command_delivery", 90, "version"));
        assertEquals(1L, longValue("agent_consumer_inbox", original.inboxId(), "version"));

        AgentInboxClaimToken reclaimed = service.claim(
                message, "lease-reclaimed", NOW + 200, 1_000).token();

        assertEquals(8L, reclaimed.deliveryVersion());
        assertEquals(2L, reclaimed.inboxVersion());
        assertEquals(8L, longValue("agent_command_delivery", 90, "version"));
        assertEquals(8L, longValue("agent_consumer_inbox", reclaimed.inboxId(),
                "claim_delivery_version"));
        assertEquals(2L, longValue("agent_consumer_inbox", reclaimed.inboxId(),
                "claim_inbox_version"));

        assertThrows(IllegalStateException.class,
                () -> execute(service, original, NOW + 250));
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(original, "RECEIVED", 90, NOW + 250), NOW + 250));
        assertEquals("CONSUMED", value("agent_command_delivery", 90, "status"));
        assertEquals(8L, longValue("agent_command_delivery", 90, "version"));
        assertEquals(2L, longValue("agent_consumer_inbox", reclaimed.inboxId(), "version"));
        assertEquals(null, value("agent_consumer_inbox", reclaimed.inboxId(), "ack_status"));

        execute(service, reclaimed, NOW + 250);
        ackService.acknowledge(
                ack(reclaimed, "RECEIVED", 91, NOW + 300), NOW + 300);
        service.complete(reclaimed, AgentInboxDisposition.sent(), NOW + 400);

        assertEquals("RECEIVED", value("agent_command_delivery", 90, "status"));
        assertEquals(9L, longValue("agent_command_delivery", 90, "version"));
        assertEquals("PROCESSED", value(
                "agent_consumer_inbox", reclaimed.inboxId(), "status"));
        assertEquals("SENT", value(
                "agent_consumer_inbox", reclaimed.inboxId(), "result_status"));
        assertEquals(4L, longValue("agent_consumer_inbox", reclaimed.inboxId(), "version"));
    }


    @Test
    void tamperedBusinessIdentityTokenIsZeroWriteAtEveryDurableBoundary() {
        insertPublishedSource(91, 191, "msg-token-fence", "evt-token-fence", null, null);
        AgentInboxClaimToken token = service.claim(
                message(91, "msg-token-fence", "evt-token-fence"), "lease-token", NOW, 2_000).token();
        for (String field : new String[]{"task", "target", "type"}) {
            AgentInboxClaimToken altered = alteredToken(token, field);
            assertThrows(IllegalStateException.class,
                    () -> execute(service, altered, NOW + 100), field);
            assertThrows(IllegalStateException.class,
                    () -> service.complete(altered, AgentInboxDisposition.sent(), NOW + 100), field);
            assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                    ack(altered, "RECEIVED", field.hashCode(), NOW + 100), NOW + 100), field);
            assertEquals("CONSUMED", value("agent_command_delivery", 91, "status"));
            assertEquals(6L, longValue("agent_command_delivery", 91, "version"));
            assertEquals(0L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
            assertEquals(null, value("agent_consumer_inbox", token.inboxId(), "ack_status"));
        }
    }
    @Test
    void ordinaryFirstAcknowledgementOnlyAllowsReceivedOrRejected() {
        String[] statuses = {"RECEIVED", "REJECTED"};
        for (int index = 0; index < statuses.length; index++) {
            long deliveryId = 100 + index;
            long outboxId = 200 + index;
            String messageId = "msg-ack-" + index;
            String eventId = "evt-ack-" + index;
            insertPublishedSource(deliveryId, outboxId, messageId, eventId, null, null);
            AgentInboxClaimToken token = service.claim(
                    message(deliveryId, messageId, eventId), "lease-" + index, NOW, 2_000).token();

            AgentCommandAckResult result = ackService.acknowledge(
                    ack(token, statuses[index], index + 1, NOW + 100), NOW + 100);

            assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind());
            assertEquals(statuses[index], value("agent_command_delivery", deliveryId, "status"));
            assertEquals(7L, longValue("agent_command_delivery", deliveryId, "version"));
            assertEquals(statuses[index], value("agent_consumer_inbox", token.inboxId(), "ack_status"));
            assertEquals(1L, longValue("agent_consumer_inbox", token.inboxId(), "version"));

            service.complete(token, AgentInboxDisposition.sent(), NOW + 200);

            assertEquals(statuses[index], value("agent_command_delivery", deliveryId, "status"));
            assertEquals(7L, longValue("agent_command_delivery", deliveryId, "version"));
            assertEquals("PROCESSED", value("agent_consumer_inbox", token.inboxId(), "status"));
            assertEquals("SENT", value("agent_consumer_inbox", token.inboxId(), "result_status"));
            assertEquals(2L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
        }
    }

    @Test
    void ordinaryFirstStartedTerminalSuccessFailureAndAgentExpiryDeathAreZeroWrite() {
        String[] statuses = {"STARTED", "SUCCEEDED", "FAILED", "EXPIRED", "DEAD"};
        for (int index = 0; index < statuses.length; index++) {
            String status = statuses[index];
            int ackSequence = index + 1;
            long deliveryId = 110 + index;
            long outboxId = 210 + index;
            String messageId = "msg-denied-" + index;
            String eventId = "evt-denied-" + index;
            insertPublishedSource(deliveryId, outboxId, messageId, eventId, null, null);
            AgentInboxClaimToken token = service.claim(
                    message(deliveryId, messageId, eventId),
                    "lease-denied-" + index, NOW, 2_000).token();

            assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                    ack(token, status, ackSequence, NOW + 100), NOW + 100),
                    status);

            assertEquals("CONSUMED", value("agent_command_delivery", deliveryId, "status"));
            assertEquals(6L, longValue("agent_command_delivery", deliveryId, "version"));
            assertEquals(null, value("agent_command_delivery", deliveryId, "last_error"));
            assertEquals(0L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
            assertEquals(null, value("agent_consumer_inbox", token.inboxId(), "ack_status"));
        }
    }

    @Test
    void automaticReplayWithVerifiedImmediateParentAllowsDirectTerminalAcknowledgement() {
        String[] statuses = {"SUCCEEDED", "FAILED", "REJECTED"};
        for (int index = 0; index < statuses.length; index++) {
            long deliveryId = 130 + index;
            long outboxId = 230 + index;
            String messageId = "msg-replay-" + index;
            String eventId = "evt-replay-" + index;
            insertAutomaticReplaySource(deliveryId, outboxId, messageId, eventId);
            AgentInboxClaimToken token = service.claim(
                    message(deliveryId, messageId, eventId),
                    "lease-replay-" + index, NOW, 2_000).token();

            AgentCommandAckResult result = ackService.acknowledge(
                    ack(token, statuses[index], index + 30, NOW + 100), NOW + 100);

            assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind());
            assertEquals(statuses[index], value("agent_command_delivery", deliveryId, "status"));
            assertEquals(7L, longValue("agent_command_delivery", deliveryId, "version"));
            assertEquals(statuses[index], value(
                    "agent_consumer_inbox", token.inboxId(), "ack_status"));
            service.complete(token, AgentInboxDisposition.sent(), NOW + 200);
            assertEquals("PROCESSED", value(
                    "agent_consumer_inbox", token.inboxId(), "status"));
            assertEquals("SENT", value(
                    "agent_consumer_inbox", token.inboxId(), "result_status"));
        }
    }

    @Test
    void acknowledgementChainUsesExactVersionDriftAndDuplicateDeliveryRecoversPriorResult() {
        insertPublishedSource(120, 220, "msg-chain", "evt-chain", null, null);
        AgentInboxMessage message = message(120, "msg-chain", "evt-chain");
        AgentInboxClaimToken token = service.claim(message, "lease-chain", NOW, 2_000).token();

        ackService.acknowledge(ack(token, "RECEIVED", 1, NOW + 100), NOW + 100);
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ackWithRuntime(token, "STARTED", 20, NOW + 200, "runtime-2"), NOW + 200));
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "STARTED", 21, NOW + 50), NOW + 200));
        assertEquals(7L, longValue("agent_command_delivery", 120, "version"));
        assertEquals(1L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
        ackService.acknowledge(ack(token, "STARTED", 2, NOW + 200), NOW + 200);
        ackService.acknowledge(ack(token, "SUCCEEDED", 3, NOW + 300), NOW + 300);
        AgentCommandAckResult duplicateAck = ackService.acknowledge(
                ack(token, "SUCCEEDED", 3, NOW + 300), NOW + 400);
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "SUCCEEDED", 3, NOW + 301), NOW + 400));
        for (String field : new String[]{"task", "target", "type"}) {
            AgentInboxClaimToken altered = alteredToken(token, field);
            assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                    ack(altered, "SUCCEEDED", 3, NOW + 300), NOW + 400), field);
        }

        assertEquals(AgentCommandAckResult.Kind.PRIOR, duplicateAck.kind());
        assertEquals(9L, longValue("agent_command_delivery", 120, "version"));
        assertEquals(3L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
        assertEquals("SUCCEEDED", value("agent_consumer_inbox", token.inboxId(), "ack_status"));

        AgentInboxClaim duplicateClaim = service.claim(message, "other-owner", NOW + 400, 500);

        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, duplicateClaim.kind());
        assertEquals("SENT", duplicateClaim.priorResult().resultStatus());
        assertEquals("SUCCEEDED", value("agent_command_delivery", 120, "status"));
        assertEquals(9L, longValue("agent_command_delivery", 120, "version"));
        assertEquals("PROCESSED", value("agent_consumer_inbox", token.inboxId(), "status"));
        assertEquals(4L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
    }

    @Test
    void durableAcknowledgementContinuesAfterConsumerSettlesSent() {
        insertPublishedSource(125, 225, "msg-settled-ack", "evt-settled-ack", null, null);
        AgentInboxClaimToken token = service.claim(
                message(125, "msg-settled-ack", "evt-settled-ack"),
                "lease-settled-ack", NOW, 2_000).token();

        ackService.acknowledge(ack(token, "RECEIVED", 1, NOW + 100), NOW + 100);
        service.complete(token, AgentInboxDisposition.sent(), NOW + 150);

        assertEquals("RECEIVED", value("agent_command_delivery", 125, "status"));
        assertEquals("PROCESSED", value("agent_consumer_inbox", token.inboxId(), "status"));
        assertEquals(2L, longValue("agent_consumer_inbox", token.inboxId(), "version"));

        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ackWithRuntime(token, "STARTED", 2, NOW + 200, "runtime-2"), NOW + 200));
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "STARTED", 1, NOW + 200), NOW + 200));
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "STARTED", 2, NOW + 50), NOW + 200));

        ackService.acknowledge(ack(token, "STARTED", 2, NOW + 200), NOW + 200);
        ackService.acknowledge(ack(token, "SUCCEEDED", 3, NOW + 300), NOW + 300);
        AgentCommandAckResult duplicate = ackService.acknowledge(
                ack(token, "SUCCEEDED", 3, NOW + 300), NOW + 400);

        assertEquals(AgentCommandAckResult.Kind.PRIOR, duplicate.kind());
        assertEquals("SUCCEEDED", value("agent_command_delivery", 125, "status"));
        assertEquals(9L, longValue("agent_command_delivery", 125, "version"));
        assertEquals("PROCESSED", value("agent_consumer_inbox", token.inboxId(), "status"));
        assertEquals("SENT", value("agent_consumer_inbox", token.inboxId(), "result_status"));
        assertEquals("SUCCEEDED", value("agent_consumer_inbox", token.inboxId(), "ack_status"));
        assertEquals(4L, longValue("agent_consumer_inbox", token.inboxId(), "version"));

        AgentInboxClaim duplicateClaim = service.claim(
                message(125, "msg-settled-ack", "evt-settled-ack"),
                "other-owner", NOW + 400, 500);
        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, duplicateClaim.kind());
        assertEquals("SENT", duplicateClaim.priorResult().resultStatus());
    }

    @Test
    void completionCanWinFirstAndLateAcknowledgementOnlyAdvancesAckEvidence() {
        insertPublishedSource(121, 221, "msg-complete-first", "evt-complete-first", null, null);
        AgentInboxClaimToken token = service.claim(
                message(121, "msg-complete-first", "evt-complete-first"),
                "lease-complete", NOW, 2_000).token();
        service.complete(token, AgentInboxDisposition.sent(), NOW + 100);

        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(alteredToken(token, "version"), "RECEIVED", 20, NOW + 200), NOW + 200));
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "RECEIVED", 21, NOW - 1), NOW + 200));

        AgentCommandAckResult result = ackService.acknowledge(
                ack(token, "RECEIVED", 1, NOW + 200), NOW + 200);
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ackWithRuntime(token, "STARTED", 2, NOW + 300, "runtime-2"), NOW + 300));
        AgentCommandAckResult duplicate = ackService.acknowledge(
                ack(token, "RECEIVED", 1, NOW + 200), NOW + 300);
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "RECEIVED", 1, NOW + 201), NOW + 300));

        assertEquals(AgentCommandAckResult.Kind.ADVANCED, result.kind());
        assertEquals(AgentCommandAckResult.Kind.PRIOR, duplicate.kind());
        assertEquals("RECEIVED", value("agent_command_delivery", 121, "status"));
        assertEquals(8L, longValue("agent_command_delivery", 121, "version"));
        assertEquals("PROCESSED", value("agent_consumer_inbox", token.inboxId(), "status"));
        assertEquals("SENT", value("agent_consumer_inbox", token.inboxId(), "result_status"));
        assertEquals("RECEIVED", value("agent_consumer_inbox", token.inboxId(), "ack_status"));
        assertEquals(2L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
    }

    @Test
    void wrongTokenIdentityExpiredLeaseAndReclaimedTokenProduceZeroAcknowledgementWrites() {
        insertPublishedSource(122, 222, "msg-fence", "evt-fence", null, null);
        AgentInboxClaimToken token = service.claim(
                message(122, "msg-fence", "evt-fence"), "lease-fence", NOW, 1_000).token();

        for (String field : new String[]{"tenant", "claimant", "message", "event",
                "command", "task", "target", "type", "delivery", "attempt"}) {
            AgentInboxClaimToken altered = alteredToken(token, field);
            assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                    ack(altered, "RECEIVED", field.hashCode(), NOW + 100), NOW + 100), field);
        }
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "RECEIVED", 99, token.leaseUntil()), token.leaseUntil()));

        assertEquals("CONSUMED", value("agent_command_delivery", 122, "status"));
        assertEquals(6L, longValue("agent_command_delivery", 122, "version"));
        assertEquals(0L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
        assertEquals(null, value("agent_consumer_inbox", token.inboxId(), "ack_status"));

        jdbc.update("""
                UPDATE agent_consumer_inbox
                   SET lease_owner='lease-reclaimed', attempt_count=2, active_attempt=2,
                       version=1, claim_inbox_version=1
                 WHERE id=?
                """, token.inboxId());
        assertThrows(IllegalStateException.class, () -> ackService.acknowledge(
                ack(token, "RECEIVED", 100, NOW + 200), NOW + 200));
        assertEquals(1L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
        assertEquals(null, value("agent_consumer_inbox", token.inboxId(), "ack_status"));
    }

    @Test
    void eitherAcknowledgementCasFailureRollsBackTheWholeTransaction() {
        insertPublishedSource(123, 223, "msg-ack-delivery-cas", "evt-ack-delivery-cas", null, null);
        AgentInboxClaimToken deliveryToken = service.claim(
                message(123, "msg-ack-delivery-cas", "evt-ack-delivery-cas"),
                "lease-delivery-cas", NOW, 2_000).token();
        AgentCommandInboxV2Dao deliveryFailing = mock(AgentCommandInboxV2Dao.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(dao)));
        doReturn(0).when(deliveryFailing).advanceAcknowledgement(any(), any(), anyLong(),
                anyString(), anyString(), nullable(String.class), anyLong());
        AgentCommandAckV2ServiceImpl deliveryFailingService =
                new AgentCommandAckV2ServiceImpl(deliveryFailing, SCOPE, guard, manager);

        assertThrows(IllegalStateException.class, () -> deliveryFailingService.acknowledge(
                ack(deliveryToken, "RECEIVED", 1, NOW + 100), NOW + 100));
        assertEquals("CONSUMED", value("agent_command_delivery", 123, "status"));
        assertEquals(6L, longValue("agent_command_delivery", 123, "version"));
        assertEquals(0L, longValue("agent_consumer_inbox", deliveryToken.inboxId(), "version"));

        insertPublishedSource(124, 224, "msg-ack-inbox-cas", "evt-ack-inbox-cas", null, null);
        AgentInboxClaimToken inboxToken = service.claim(
                message(124, "msg-ack-inbox-cas", "evt-ack-inbox-cas"),
                "lease-inbox-cas", NOW, 2_000).token();
        AgentCommandInboxV2Dao inboxFailing = mock(AgentCommandInboxV2Dao.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(dao)));
        doReturn(0).when(inboxFailing).recordAcknowledgement(any(), any(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyLong());
        AgentCommandAckV2ServiceImpl inboxFailingService =
                new AgentCommandAckV2ServiceImpl(inboxFailing, SCOPE, guard, manager);

        assertThrows(IllegalStateException.class, () -> inboxFailingService.acknowledge(
                ack(inboxToken, "RECEIVED", 2, NOW + 100), NOW + 100));
        assertEquals("CONSUMED", value("agent_command_delivery", 124, "status"));
        assertEquals(6L, longValue("agent_command_delivery", 124, "version"));
        assertEquals(0L, longValue("agent_consumer_inbox", inboxToken.inboxId(), "version"));
        assertEquals(null, value("agent_consumer_inbox", inboxToken.inboxId(), "ack_status"));
    }

    @Test
    void eitherSettledAcknowledgementCasFailureRollsBackTheWholeTransaction() {
        insertPublishedSource(126, 226, "msg-settled-cas", "evt-settled-cas", null, null);
        AgentInboxClaimToken token = service.claim(
                message(126, "msg-settled-cas", "evt-settled-cas"),
                "lease-settled-cas", NOW, 2_000).token();
        ackService.acknowledge(ack(token, "RECEIVED", 1, NOW + 100), NOW + 100);
        service.complete(token, AgentInboxDisposition.sent(), NOW + 150);

        AgentCommandInboxV2Dao deliveryFailing = mock(AgentCommandInboxV2Dao.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(dao)));
        doReturn(0).when(deliveryFailing).advanceSettledAcknowledgement(any(), any(), anyLong(),
                anyString(), anyString(), nullable(String.class), anyLong());
        AgentCommandAckV2ServiceImpl deliveryFailingService =
                new AgentCommandAckV2ServiceImpl(deliveryFailing, SCOPE, guard, manager);
        assertThrows(IllegalStateException.class, () -> deliveryFailingService.acknowledge(
                ack(token, "STARTED", 2, NOW + 200), NOW + 200));

        assertEquals("RECEIVED", value("agent_command_delivery", 126, "status"));
        assertEquals(7L, longValue("agent_command_delivery", 126, "version"));
        assertEquals(2L, longValue("agent_consumer_inbox", token.inboxId(), "version"));

        AgentCommandInboxV2Dao inboxFailing = mock(AgentCommandInboxV2Dao.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(dao)));
        doReturn(0).when(inboxFailing).recordSettledAcknowledgement(any(), any(), anyString(),
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyLong());
        AgentCommandAckV2ServiceImpl inboxFailingService =
                new AgentCommandAckV2ServiceImpl(inboxFailing, SCOPE, guard, manager);
        assertThrows(IllegalStateException.class, () -> inboxFailingService.acknowledge(
                ack(token, "STARTED", 2, NOW + 200), NOW + 200));

        assertEquals("RECEIVED", value("agent_command_delivery", 126, "status"));
        assertEquals(7L, longValue("agent_command_delivery", 126, "version"));
        assertEquals("RECEIVED", value("agent_consumer_inbox", token.inboxId(), "ack_status"));
        assertEquals(2L, longValue("agent_consumer_inbox", token.inboxId(), "version"));
    }

    private void assertAmbientRejected(Runnable operation) {
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        assertThrows(IllegalStateException.class,
                () -> outer.executeWithoutResult(status -> operation.run()));
    }

    private AgentCommandV2ExecutionPort.Result execute(
            AgentCommandInboxV2ServiceImpl target, AgentInboxClaimToken token, long now) {
        return target.executeAdmitted(token, "owner-a", WIRE, now,
                command -> AgentCommandV2ExecutionPort.Result.dispatched());
    }

    private AgentInboxMessage message(long deliveryId, String messageId, String eventId) {
        return message(deliveryId, messageId, eventId,
                "task-1", "agent-1", "TASK_INVITE");
    }

    private AgentInboxMessage message(long deliveryId, String messageId, String eventId,
            String taskId, String targetAgentId, String commandType) {
        return new AgentInboxMessage(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                "1", "cyf", "local-test", 7, "producer-a", "agent-command", 9, 2,
                "consumer-b", 5, "client-a", messageId, eventId, "cmd-" + deliveryId,
                taskId, targetAgentId, commandType, deliveryId, WIRE);
    }

    private AgentCommandAckV2 ack(AgentInboxClaimToken token, String status,
            int sequence, long ackAt) {
        return ackWithRuntime(token, status, sequence, ackAt, "runtime-1");
    }

    private AgentCommandAckV2 ackWithRuntime(AgentInboxClaimToken token, String status,
            int sequence, long ackAt, String runtimeInstanceId) {
        return new AgentCommandAckV2(token, "ack-" + Math.abs(sequence), token.messageId(),
                "agent-1", "installation-1", runtimeInstanceId, status, ackAt);
    }

    private AgentInboxClaimToken alteredToken(AgentInboxClaimToken token, String field) {
        return new AgentInboxClaimToken(token.inboxId(), token.consumerName(),
                field.equals("tenant") ? "0" : token.tenantId(), token.dataDomainId(),
                token.executionPoolId(), token.admissionEpoch(), token.producerDeploymentId(),
                token.moduleId(), token.migrationEpoch(), token.protocolVersion(),
                field.equals("claimant") ? "other-consumer" : token.claimantDeploymentId(),
                token.claimantWorkerGeneration(), token.clientId(),
                field.equals("message") ? "other-message" : token.messageId(),
                field.equals("event") ? "other-event" : token.eventId(),
                field.equals("command") ? "other-command" : token.commandId(),
                field.equals("task") ? "other-task" : token.taskId(),
                field.equals("target") ? "other-agent" : token.targetAgentId(),
                field.equals("type") ? "WORK_ITEM_EXECUTE" : token.commandType(),
                field.equals("delivery") ? token.deliveryId() + 1 : token.deliveryId(),
                token.leaseOwner(), token.leaseUntil(),
                field.equals("attempt") ? token.activeAttempt() + 1 : token.activeAttempt(),
                field.equals("version") ? token.inboxVersion() + 1 : token.inboxVersion(),
                token.deliveryActiveAttempt(), token.deliveryVersion(),
                token.expiresAt());
    }

    private void insertAutomaticReplaySource(long deliveryId, long outboxId,
            String messageId, String eventId) {
        String parentMessageId = "parent-" + messageId;
        String parentEventId = "parent-" + eventId;
        insertPublishedSource(deliveryId, outboxId, messageId, eventId, null, null);
        jdbc.update("""
                UPDATE agent_command_delivery
                   SET attempt_count=2, active_attempt=2, replay_parent_message_id=?,
                       replay_requester_id='agent-1', replay_approver_id=NULL,
                       replay_reason='AGENT_RECONNECT'
                 WHERE id=?
                """, parentMessageId, deliveryId);
        jdbc.update("""
                UPDATE agent_outbox_event
                   SET active_attempt=2, replay_parent_message_id=?,
                       replay_requester_id='agent-1', replay_approver_id=NULL,
                       replay_reason='AGENT_RECONNECT'
                 WHERE id=?
                """, parentMessageId, outboxId);
        byte[] parentWire = replayParentWire(parentMessageId, deliveryId);
        jdbc.update("""
                INSERT INTO agent_outbox_event
                  (id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                   producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                   claimed_worker_generation,event_id,message_id,command_id,delivery_id,
                   aggregate_type,aggregate_id,destination,routing_key,wire_payload,
                   wire_payload_hash,status,attempt_count,active_attempt,expires_at,
                   publisher_confirm_status,confirmed_at,mandatory_return_status,published_at,
                   version,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, outboxId + 1_000, 2, "cyf", "local-test", 7L, "producer-a",
                "agent-command", 9L, null, null, parentEventId, parentMessageId,
                "cmd-" + deliveryId, deliveryId, "task", "task-1", "jia.agent.command",
                "agent.command.general", parentWire, AgentCommandAmqpContract.sha256(parentWire),
                "PUBLISHED", 1, 1, EXPIRES, "ACK", NOW - 50, "NOT_RETURNED", NOW - 50,
                1L, "1", "client-a", NOW - 100, NOW - 50);
    }

    private byte[] replayParentWire(String messageId, long deliveryId) {
        return ("""
                {"schemaVersion":2,"messageType":"command.dispatch","messageId":"%s",
                "commandId":"cmd-%d","tenantId":"1","dataDomainId":"cyf",
                "executionPoolId":"local-test","producerDeploymentId":"producer-a",
                "admissionEpoch":7,"moduleId":"agent-command","migrationEpoch":9,
                "deliveryId":"%d",
                "clientId":"client-a","taskId":"task-1","targetAgentId":"agent-1",
                "commandType":"TASK_INVITE","attempt":1,"expiresAt":10000}
                """).formatted(messageId, deliveryId, deliveryId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private void insertPublishedSource(long deliveryId, long outboxId,
            String messageId, String eventId, String claimedDeployment, Long claimedGeneration) {
        byte[] commandPayload = new byte[]{7};
        jdbc.update("""
                INSERT INTO agent_command_delivery
                  (id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                   producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                   claimed_worker_generation,command_id,owner_jiacn,task_id,target_agent_id,
                   command_type,command_payload,command_payload_hash,status,attempt_count,
                   active_message_id,active_attempt,expires_at,version,tenant_id,client_id,
                   create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, deliveryId, 2, "cyf", "local-test", 7L, "producer-a",
                "agent-command", 9L, claimedDeployment, claimedGeneration,
                "cmd-" + deliveryId, "owner-a", "task-1", "agent-1", "TASK_INVITE",
                commandPayload, AgentCommandAmqpContract.sha256(commandPayload), "PUBLISHED", 1,
                messageId, 1, EXPIRES, 5L, "1", "client-a", NOW - 100, NOW - 100);
        jdbc.update("""
                INSERT INTO agent_outbox_event
                  (id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                   producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                   claimed_worker_generation,event_id,message_id,command_id,delivery_id,
                   aggregate_type,aggregate_id,destination,routing_key,wire_payload,
                   wire_payload_hash,status,attempt_count,active_attempt,expires_at,
                   publisher_confirm_status,confirmed_at,mandatory_return_status,published_at,
                   version,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, outboxId, 2, "cyf", "local-test", 7L, "producer-a",
                "agent-command", 9L, claimedDeployment, claimedGeneration, eventId, messageId,
                "cmd-" + deliveryId, deliveryId, "task", "task-1", "jia.agent.command",
                "agent.command.general", WIRE, AgentCommandAmqpContract.sha256(WIRE),
                "PUBLISHED", 1, 1, EXPIRES, "ACK", NOW - 50, "NOT_RETURNED", NOW - 50,
                7L, "1", "client-a", NOW - 100, NOW - 50);
    }

    private String value(String table, long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id=?", String.class, id);
    }

    private long longValue(String table, long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id=?", Long.class, id);
    }

    private long countInbox(String messageId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_consumer_inbox WHERE message_id=?",
                Long.class, messageId);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery (
                  id BIGINT PRIMARY KEY, protocol_version SMALLINT, data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100), admission_epoch BIGINT,
                  producer_deployment_id VARCHAR_IGNORECASE(100), module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT, claimed_deployment_id VARCHAR_IGNORECASE(100),
                  claimed_worker_generation BIGINT, command_id VARCHAR(100), owner_jiacn VARCHAR(50),
                  task_id VARCHAR(100), work_item_id VARCHAR(100), target_agent_id VARCHAR(100),
                  command_type VARCHAR(64), command_payload VARBINARY, command_payload_hash VARBINARY,
                  status VARCHAR(32), attempt_count INT, next_retry_at BIGINT, lease_owner VARCHAR(100),
                  lease_until BIGINT, active_message_id VARCHAR(100), active_attempt INT, expires_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event (
                  id BIGINT PRIMARY KEY, protocol_version SMALLINT, data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100), admission_epoch BIGINT,
                  producer_deployment_id VARCHAR_IGNORECASE(100), module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT, claimed_deployment_id VARCHAR_IGNORECASE(100),
                  claimed_worker_generation BIGINT, event_id VARCHAR(100), message_id VARCHAR(100),
                  command_id VARCHAR(100), delivery_id BIGINT, aggregate_type VARCHAR(30), aggregate_id VARCHAR(100),
                  destination VARCHAR(100), routing_key VARCHAR(100), wire_payload VARBINARY,
                  wire_payload_hash VARBINARY, status VARCHAR(32), attempt_count INT, next_retry_at BIGINT,
                  lease_owner VARCHAR(100), lease_until BIGINT, active_attempt INT, expires_at BIGINT,
                  publisher_confirm_status VARCHAR(20), confirmed_at BIGINT, confirm_error VARCHAR(2000),
                  mandatory_return_status VARCHAR(20), returned_at BIGINT, return_reply_code INT,
                  return_reply_text VARCHAR(1000), published_at BIGINT, last_error VARCHAR(2000), version BIGINT,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, protocol_version SMALLINT,
                  data_domain_id VARCHAR_IGNORECASE(64), execution_pool_id VARCHAR_IGNORECASE(100),
                  admission_epoch BIGINT, producer_deployment_id VARCHAR_IGNORECASE(100),
                  module_id VARCHAR_IGNORECASE(64), migration_epoch BIGINT,
                  claimant_deployment_id VARCHAR_IGNORECASE(100), claimant_worker_generation BIGINT,
                  consumer_name VARCHAR(100), message_id VARCHAR(100), event_id VARCHAR(100),
                  command_id VARCHAR(100), delivery_id BIGINT, wire_payload VARBINARY,
                  wire_payload_hash VARBINARY, status VARCHAR(32), result_status VARCHAR(32),
                  attempt_count INT, next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_attempt INT, expires_at BIGINT, processed_at BIGINT, last_error VARCHAR(2000),
                  version BIGINT, claim_delivery_version BIGINT, claim_inbox_version BIGINT,
                  claim_started_at BIGINT, ack_delivery_version BIGINT,
                  ack_message_id VARCHAR(100), ack_status VARCHAR(20), ack_at BIGINT,
                  ack_agent_id VARCHAR(100), ack_runtime_installation_id VARCHAR(100),
                  ack_runtime_instance_id VARCHAR(100), replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR_IGNORECASE(50), client_id VARCHAR(50), create_time BIGINT,
                  update_time BIGINT, UNIQUE(consumer_name,message_id))
                """);
    }

    private static final class RecordingTransactionManager extends DataSourceTransactionManager {
        int commits;
        int rollbacks;

        RecordingTransactionManager(DriverManagerDataSource source) {
            super(source);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            super.doCommit(status);
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            super.doRollback(status);
            rollbacks++;
        }
    }
}
