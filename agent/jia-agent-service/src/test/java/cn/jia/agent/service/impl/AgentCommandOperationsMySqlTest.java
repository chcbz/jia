package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentCommandOperationsProperties;
import cn.jia.agent.config.AgentCommandTransportSchemaInitializer;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.dao.impl.AgentCommandOperationsDaoImpl;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandOperationAuditEntity;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationsException;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;

/**
 * D09 production mapper/DAO/service-policy operations matrix on an isolated MySQL 8.0.21.
 * Raw command/wire bytes are fixture inputs only and are never emitted as test output.
 */
@EnabledIfEnvironmentVariable(named = "D09_MYSQL_URL", matches = ".+")
class AgentCommandOperationsMySqlTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TARGET = "agent-a";
    private static final long NOW = 1_700_000_000_000L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;

    private String baseUrl;
    private String username;
    private String password;
    private String databaseName;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentCommandOperationsDao productionDao;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = requiredEnvironment("D09_MYSQL_URL");
        username = requiredEnvironment("D09_MYSQL_USER");
        password = requiredEnvironment("D09_MYSQL_PASSWORD");
        int expectedPort = Integer.parseInt(requiredEnvironment("D09_MYSQL_EXPECTED_PORT"));
        String expectedDatadir = requiredEnvironment("D09_MYSQL_EXPECTED_DATADIR");
        String expectedVersion = requiredEnvironment("D09_MYSQL_EXPECTED_VERSION");
        assertTrue(expectedPort != 3306 && expectedPort != 33060,
                "D09 runner must use neither host MySQL port");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:" + expectedPort + "/"), baseUrl);

        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        Integer port = admin.queryForObject("SELECT @@port", Integer.class);
        String datadir = admin.queryForObject("SELECT @@datadir", String.class);
        assertTrue(version != null && version.startsWith(expectedVersion), version);
        assertEquals(expectedPort, port);
        assertTrue(datadir != null && datadir.equals(expectedDatadir + "/"), datadir);
        System.out.println("D09_MYSQL_IDENTITY=version:" + version + ",port:" + port
                + ",datadir-verified:true");

        databaseName = "d09_operations_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, databaseName));
        jdbc = new JdbcTemplate(source);
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandOperationsMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = Objects.requireNonNull(bean.getObject());
        AgentCommandOperationsMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(AgentCommandOperationsMapper.class);
        productionDao = new AgentCommandOperationsDaoImpl(mapper);
        transactionManager = new DataSourceTransactionManager(source);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseName != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void broadDiscoveryAndCountRemainDeliveryOnlyBeforeSolePolicyFiltersEveryRealRowGuard() {
        Source legal = insertCanonicalSource(10, 1);
        Source duplicate = insertCanonicalSource(20, 1);
        insertOutbox(duplicate, duplicate.eventId() + "-duplicate", duplicate.messageId(),
                duplicate.attempt(), duplicate.wire(), null, null, null, null);
        Source route = insertCanonicalSource(30, 1);
        jdbc.update("UPDATE agent_outbox_event SET routing_key='agent.command.poison' WHERE event_id=?",
                route.eventId());
        Source type = insertCanonicalSource(40, 1);
        jdbc.update("UPDATE agent_command_delivery SET command_type='UNSUPPORTED_COMMAND' WHERE id=?",
                type.deliveryId());
        Source bytes = insertCanonicalSource(50, 1);
        byte[] changedWire = Arrays.copyOf(bytes.wire(), bytes.wire().length + 1);
        changedWire[changedWire.length - 1] = '\n';
        jdbc.update("UPDATE agent_outbox_event SET wire_payload=?,wire_payload_hash=? WHERE event_id=?",
                changedWire, AgentCommandCanonicalCodec.sha256(changedWire), bytes.eventId());
        Source hash = insertCanonicalSource(60, 1);
        jdbc.update("UPDATE agent_outbox_event SET wire_payload_hash=? WHERE event_id=?",
                new byte[32], hash.eventId());
        Source lineage = insertCanonicalSource(70, 2);
        jdbc.update("DELETE FROM agent_outbox_event WHERE delivery_id=? AND active_attempt=1",
                lineage.deliveryId());
        Source disposition = insertCanonicalSource(80, 1);
        insertInbox(disposition);
        Source blocking = insertCanonicalSource(90, 1);
        insertBlockingOperation(blocking);

        assertEquals(9, productionDao.countDlqBroad(TENANT, CLIENT, NOW));
        assertEquals(List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L),
                productionDao.listDlqBroad(TENANT, CLIENT, 0, NOW, 20).stream()
                        .map(row -> row.getId()).toList());
        assertEquals(0, productionDao.countDlqBroad("Tenant-a", CLIENT, NOW));

        var page = service(productionDao, 20, 20, false)
                .listDlq(TENANT, CLIENT, 0, 20);
        assertEquals(List.of(legal.deliveryId()), page.items().stream()
                .map(row -> row.deliveryId()).toList());
        assertFalse(page.hasMore());
        assertEquals(legal.messageId(), page.items().getFirst().messageId());
        assertEquals(64, page.items().getFirst().wireSha256().length());
        assertEquals(1, service(productionDao, 20, 20, false)
                .metrics(TENANT, CLIENT, NOW).rabbitDlqCount());
    }

    @Test
    void nonlockingAndLockingBundlesUseProductionSqlAndFrozenServiceLockOrder() {
        Source source = insertCanonicalSource(100, 2);
        assertEquals(1, productionDao.selectActiveOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.messageId()).size());
        assertEquals(1, productionDao.selectCurrentAttemptOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.attempt()).size());
        assertEquals(1, productionDao.selectPreviousAttemptOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.attempt() - 1).size());
        assertEquals(null, productionDao.selectInbox(
                TENANT, CLIENT, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, source.messageId()));
        assertTrue(productionDao.selectActiveRedriveOperations(
                TENANT, CLIENT, source.deliveryId(), source.messageId(), source.attempt()).isEmpty());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(source.deliveryId(), productionDao.lockDelivery(
                    TENANT, CLIENT, source.deliveryId()).getId());
            assertEquals(source.eventId(), productionDao.lockActiveOutboxes(
                    TENANT, CLIENT, source.deliveryId(), source.messageId()).getFirst().getEventId());
            assertEquals(source.eventId(), productionDao.lockCurrentAttemptOutboxes(
                    TENANT, CLIENT, source.deliveryId(), source.attempt()).getFirst().getEventId());
            assertEquals(source.parentEventId(), productionDao.lockPreviousAttemptOutboxes(
                    TENANT, CLIENT, source.deliveryId(), source.attempt() - 1).getFirst().getEventId());
            assertEquals(null, productionDao.lockInbox(
                    TENANT, CLIENT, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                    source.messageId()));
            assertTrue(productionDao.lockActiveRedriveOperations(
                    TENANT, CLIENT, source.deliveryId(), source.messageId(), source.attempt()).isEmpty());
        });

        AgentCommandOperationsDao orderedDao = spy(productionDao);
        AtomicReference<byte[]> publishedBytes = new AtomicReference<>();
        var service = new AgentCommandOperationsServiceImpl(
                orderedDao, gate(), AgentRabbitTopologyManifest.canonical(),
                (expected, timeout, limit) -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "Rabbit redrive must remain outside the database transaction");
                    publishedBytes.set(expected.wirePayload());
                    return AgentRabbitPublishResult.ack();
                }, null, properties(20, 20, true), transactionManager,
                () -> UUID.fromString("90000000-0000-0000-0000-000000000001"), () -> NOW);
        var result = service.brokerRedrive(new AgentCommandOperationRequest(
                TENANT, CLIENT, source.deliveryId(), source.taskId(), TARGET,
                source.messageId(), "operator-a", null, "broker redrive", "INC-100"), NOW);

        assertEquals("SUCCEEDED", result.outcome());
        assertArrayEquals(source.wire(), publishedBytes.get());
        InOrder order = inOrder(orderedDao);
        order.verify(orderedDao).lockDelivery(TENANT, CLIENT, source.deliveryId());
        order.verify(orderedDao).lockActiveOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.messageId());
        order.verify(orderedDao).lockCurrentAttemptOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.attempt());
        order.verify(orderedDao).lockPreviousAttemptOutboxes(
                TENANT, CLIENT, source.deliveryId(), source.attempt() - 1);
        order.verify(orderedDao).lockInbox(
                TENANT, CLIENT, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, source.messageId());
        order.verify(orderedDao).lockActiveRedriveOperations(
                TENANT, CLIENT, source.deliveryId(), source.messageId(), source.attempt());
        ArgumentCaptor<AgentCommandOperationAuditEntity> auditCaptor =
                ArgumentCaptor.forClass(AgentCommandOperationAuditEntity.class);
        order.verify(orderedDao).insertAudit(auditCaptor.capture());
        order.verify(orderedDao).insertAudit(auditCaptor.capture());
        order.verifyNoMoreInteractions();

        List<AgentCommandOperationAuditEntity> audits = auditCaptor.getAllValues();
        assertEquals(2, audits.size());
        AgentCommandOperationAuditEntity requestAudit = audits.getFirst();
        AgentCommandOperationAuditEntity resultAudit = audits.getLast();
        assertEquals("REQUEST", requestAudit.getPhase());
        assertEquals("REQUESTED", requestAudit.getOutcome());
        assertEquals("RESULT", resultAudit.getPhase());
        assertEquals("SUCCEEDED", resultAudit.getOutcome());
        assertEquals("90000000-0000-0000-0000-000000000001", requestAudit.getOperationId());
        assertEquals(requestAudit.getOperationId(), resultAudit.getOperationId());
        for (AgentCommandOperationAuditEntity audit : audits) {
            assertEquals("BROKER_REDRIVE", audit.getOperationType());
            assertEquals(source.deliveryId(), audit.getDeliveryId());
            assertEquals(source.messageId(), audit.getSourceMessageId());
            assertArrayEquals(AgentCommandCanonicalCodec.sha256(source.wire()), audit.getWireHash());
        }
        List<String> sensitiveAuditFields = List.of(
                "payload", "header", "credential", "secret", "token", "lease");
        assertTrue(Arrays.stream(AgentCommandOperationAuditEntity.class.getDeclaredFields())
                .map(field -> field.getName().toLowerCase(Locale.ROOT))
                .noneMatch(name -> sensitiveAuditFields.stream().anyMatch(name::contains)));

        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_operation_audit", Integer.class));
        assertTrue(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='agent_command_operation_audit'
                  AND (column_name LIKE '%payload%' OR column_name LIKE '%header%'
                    OR column_name LIKE '%credential%' OR column_name LIKE '%secret%'
                    OR column_name LIKE '%token%' OR column_name LIKE '%lease%')
                """, String.class).isEmpty());
    }

    @Test
    void repeatableReadSnapshotKeepsCountAndPageConsistentAcrossConcurrentCommit() throws Exception {
        insertCanonicalSource(1, 1);
        AgentCommandOperationsDao snapshotDao = spy(productionDao);
        CountDownLatch counted = new CountDownLatch(1);
        CountDownLatch inserted = new CountDownLatch(1);
        doAnswer(invocation -> {
            long value = (long) invocation.callRealMethod();
            counted.countDown();
            assertTrue(inserted.await(5, TimeUnit.SECONDS));
            return value;
        }).when(snapshotDao).countDlqBroad(TENANT, CLIENT, NOW);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Long> observed = executor.submit(() -> service(snapshotDao, 10, 10, false)
                    .metrics(TENANT, CLIENT, NOW).rabbitDlqCount());
            assertTrue(counted.await(5, TimeUnit.SECONDS));
            insertCanonicalSource(2, 1);
            inserted.countDown();
            assertEquals(1L, observed.get(10, TimeUnit.SECONDS));
            assertEquals(2, productionDao.countDlqBroad(TENANT, CLIENT, NOW));
        } finally {
            inserted.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stableCursorLimitBudgetAndHasMoreAdvanceAcrossPolicyRejectedRows() {
        Source firstRejected = insertCanonicalSource(1, 1);
        Source secondRejected = insertCanonicalSource(2, 1);
        jdbc.update("UPDATE agent_outbox_event SET routing_key='agent.command.poison' WHERE event_id IN (?,?)",
                firstRejected.eventId(), secondRejected.eventId());
        insertCanonicalSource(3, 1);
        insertCanonicalSource(4, 1);
        var service = service(productionDao, 2, 2, false);

        var first = service.listDlq(TENANT, CLIENT, 0, 1);
        assertTrue(first.items().isEmpty());
        assertEquals(2L, first.nextAfterDeliveryId());
        assertTrue(first.hasMore());

        var second = service.listDlq(TENANT, CLIENT, first.nextAfterDeliveryId(), 1);
        assertEquals(List.of(3L), second.items().stream().map(row -> row.deliveryId()).toList());
        assertEquals(3L, second.nextAfterDeliveryId());
        assertTrue(second.hasMore());

        var third = service.listDlq(TENANT, CLIENT, second.nextAfterDeliveryId(), 1);
        assertEquals(List.of(4L), third.items().stream().map(row -> row.deliveryId()).toList());
        assertEquals(4L, third.nextAfterDeliveryId());
        assertFalse(third.hasMore());

        assertReason(AgentCommandOperationsException.Reason.INVALID_REQUEST,
                () -> service.listDlq(TENANT, CLIENT, 0, 3));
        assertReason(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                () -> service.metrics(TENANT, CLIENT, NOW));
    }

    @Test
    void countAndPageDriftFromRealMapperResultsFailClosedWithoutProjection() {
        insertCanonicalSource(1, 1);
        insertCanonicalSource(2, 1);

        AgentCommandOperationsDao countDrift = spy(productionDao);
        doAnswer(invocation -> (long) invocation.callRealMethod() + 1L)
                .when(countDrift).countDlqBroad(TENANT, CLIENT, NOW);
        assertReason(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                () -> service(countDrift, 10, 10, false).metrics(TENANT, CLIENT, NOW));

        AgentCommandOperationsDao pageDrift = spy(productionDao);
        doAnswer(invocation -> {
            List<?> actual = (List<?>) invocation.callRealMethod();
            ArrayList<Object> reversed = new ArrayList<>(actual);
            Collections.reverse(reversed);
            return reversed;
        }).when(pageDrift).listDlqBroad(TENANT, CLIENT, 0, NOW, 11);
        assertReason(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                () -> service(pageDrift, 10, 10, false).listDlq(TENANT, CLIENT, 0, 2));
    }

    private AgentCommandOperationsServiceImpl service(
            AgentCommandOperationsDao dao, int maxPage, int scanLimit, boolean redrive) {
        return new AgentCommandOperationsServiceImpl(
                dao, gate(), AgentRabbitTopologyManifest.canonical(), null, null,
                properties(maxPage, scanLimit, redrive), transactionManager,
                UUID::randomUUID, () -> NOW);
    }

    private AgentCommandOperationsProperties properties(
            int maxPage, int scanLimit, boolean redrive) {
        return new AgentCommandOperationsProperties(
                true, redrive, false, maxPage, scanLimit, 1_000L, 300_000L);
    }

    private Source insertCanonicalSource(long deliveryId, int attempt) {
        AgentCommandDraft draft = draft(deliveryId);
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        String messageId = messageId(deliveryId, attempt);
        String eventId = "event-" + deliveryId + "-" + attempt;
        String parentMessageId = attempt == 1 ? null : messageId(deliveryId, attempt - 1);
        String parentEventId = attempt == 1 ? null : "event-" + deliveryId + "-" + (attempt - 1);
        String requester = attempt == 1 ? null : TARGET;
        String reason = attempt == 1 ? null : AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT;
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_command_delivery(
                    id,command_id,task_id,work_item_id,target_agent_id,command_type,
                    command_payload,command_payload_hash,status,attempt_count,next_retry_at,
                    lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
                    version,replay_parent_message_id,replay_requester_id,replay_approver_id,
                    replay_reason,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, deliveryId, draft.commandId(), draft.taskId(), draft.workItemId(), TARGET,
                draft.commandType(), business, AgentCommandCanonicalCodec.sha256(business),
                "PUBLISHED", attempt, null, null, null, messageId, attempt, EXPIRES, null, 1,
                parentMessageId, requester, null, reason, TENANT, CLIENT, NOW - 20, NOW - 5));
        if (attempt > 1) {
            byte[] parentWire = AgentCommandCanonicalCodec.wireBytes(
                    draft, parentMessageId, attempt - 1);
            insertOutbox(new Source(deliveryId, draft.taskId(), draft.commandId(),
                            parentEventId, parentMessageId, attempt - 1, parentWire, null),
                    parentEventId, parentMessageId, attempt - 1, parentWire,
                    null, null, null, null);
        }
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, messageId, attempt);
        Source source = new Source(deliveryId, draft.taskId(), draft.commandId(), eventId,
                messageId, attempt, wire, parentEventId);
        insertOutbox(source, eventId, messageId, attempt, wire,
                parentMessageId, requester, null, reason);
        return source;
    }

    private void insertOutbox(
            Source source, String eventId, String messageId, int attempt, byte[] wire,
            String parentMessageId, String requester, String approver, String reason) {
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_outbox_event(
                    event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                    destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                    next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                    publisher_confirm_status,confirmed_at,confirm_error,mandatory_return_status,
                    returned_at,return_reply_code,return_reply_text,published_at,last_error,version,
                    replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, eventId, messageId, source.commandId(), source.deliveryId(), "task",
                source.taskId(), route.destination(), route.routingKey(), wire,
                AgentCommandCanonicalCodec.sha256(wire), "PUBLISHED", 1, null, null, null,
                attempt, EXPIRES, "ACK", NOW - 10, null, "NOT_RETURNED", null, null, null,
                NOW - 9, null, 1, parentMessageId, requester, approver, reason,
                TENANT, CLIENT, NOW - 20, NOW - 4));
    }

    private void insertInbox(Source source) {
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                    consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,
                    wire_payload_hash,status,result_status,attempt_count,next_retry_at,lease_owner,
                    lease_until,active_attempt,expires_at,processed_at,last_error,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1, source.messageId(),
                source.eventId(), source.commandId(), source.deliveryId(), source.wire(),
                AgentCommandCanonicalCodec.sha256(source.wire()), "RECEIVED", null, 1,
                null, null, null, source.attempt(), EXPIRES, null, null, 0,
                TENANT, CLIENT, NOW - 3, NOW - 2));
    }

    private void insertBlockingOperation(Source source) {
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_command_redrive_operation(
                    operation_id,delivery_id,task_id,target_agent_id,command_id,source_event_id,
                    source_message_id,source_attempt,wire_hash,requester_id,reason,ticket_reference,
                    outcome_state,settlement_state,error_code,requested_at,completed_at,version,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'PENDING','PENDING',NULL,?,NULL,0,?,?,?,?)
                """, operationId(source.deliveryId()), source.deliveryId(), source.taskId(), TARGET,
                source.commandId(), source.eventId(), source.messageId(), source.attempt(),
                AgentCommandCanonicalCodec.sha256(source.wire()), "operator-a", "existing redrive",
                "INC-" + source.deliveryId(), NOW - 1, TENANT, CLIENT, NOW - 1, NOW - 1));
    }

    private AgentCommandDraft draft(long id) {
        String taskId = "task-" + id;
        String intentId = "intent-" + id;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, taskId, TARGET, intentId,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        return new AgentCommandDraft(
                1, commandId, taskId, intentId, TENANT, CLIENT, taskId,
                "work-" + id, TARGET, AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                ISSUED, EXPIRES, intentId, new AgentHallCommandPayload(
                        "execute", "Execute bounded work", "juyiting",
                        null, null, null, null, false, null));
    }

    private AgentRabbitSafetyGate gate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "d09-user", "d09-pass", "/d09")),
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(TENANT, CLIENT))));
    }

    private void assertReason(
            AgentCommandOperationsException.Reason reason, Runnable action) {
        AgentCommandOperationsException failure = assertThrows(
                AgentCommandOperationsException.class, action::run);
        assertEquals(reason, failure.reason());
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String url, String database) {
        int query = url.indexOf('?');
        String prefix = query < 0 ? url : url.substring(0, query);
        String suffix = query < 0 ? "" : url.substring(query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static String messageId(long id, int attempt) {
        return String.format("10000000-0000-0000-%04x-%012x", attempt, id);
    }

    private static String operationId(long id) {
        return String.format("80000000-0000-0000-0000-%012x", id);
    }

    private record Source(
            long deliveryId,
            String taskId,
            String commandId,
            String eventId,
            String messageId,
            int attempt,
            byte[] wire,
            String parentEventId) {
        private Source {
            wire = Arrays.copyOf(wire, wire.length);
        }
        @Override public byte[] wire() {
            return Arrays.copyOf(wire, wire.length);
        }
    }
}
