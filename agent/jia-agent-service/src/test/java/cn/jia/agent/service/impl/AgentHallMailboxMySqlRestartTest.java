package cn.jia.agent.service.impl;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentCommandTransportSchemaInitializer;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.impl.AgentCommandRecoveryDaoImpl;
import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandMailboxPage;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandContext;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.mapper.AgentCommandRecoveryMapper;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** D08 durable Hall producer/query/restart/reissue evidence on an isolated MySQL 8.0.21 instance. */
@EnabledIfEnvironmentVariable(named = "D08_MYSQL_URL", matches = ".+")
class AgentHallMailboxMySqlRestartTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String CALLER = "agent-caller";
    private static final String TARGET = "agent-target";
    private static final String INTENT = "intent-restart-1";
    private static final long ISSUED = 1_700_000_000_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;
    private static final String M1 = "11111111-1111-1111-1111-111111111111";
    private static final String E1 = "22222222-2222-2222-2222-222222222222";
    private static final String M2 = "33333333-3333-3333-3333-333333333333";
    private static final String E2 = "44444444-4444-4444-4444-444444444444";

    private String baseUrl;
    private String username;
    private String password;
    private String databaseName;
    private JdbcTemplate admin;
    private RuntimeContext firstContext;

    @BeforeEach
    void setUp() throws Exception {
        baseUrl = requiredEnvironment("D08_MYSQL_URL");
        username = requiredEnvironment("D08_MYSQL_USER");
        password = requiredEnvironment("D08_MYSQL_PASSWORD");
        int expectedPort = Integer.parseInt(requiredEnvironment("D08_MYSQL_EXPECTED_PORT"));
        String expectedDatadir = requiredEnvironment("D08_MYSQL_EXPECTED_DATADIR");
        assertTrue(expectedPort != 3306, "D08 runner must never use the production MySQL port");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:" + expectedPort + "/"), baseUrl);

        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        Integer port = admin.queryForObject("SELECT @@port", Integer.class);
        String datadir = admin.queryForObject("SELECT @@datadir", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        assertEquals(expectedPort, port);
        assertTrue(datadir != null && datadir.startsWith(expectedDatadir + "/"), datadir);
        System.out.println("D08_MYSQL_VERSION=" + version);
        System.out.println("D08_MYSQL_PORT=" + port);
        System.out.println("D08_MYSQL_DATADIR=" + datadir);

        databaseName = "d08_hall_mailbox_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        firstContext = newContext();
        createTaskAclSchema(firstContext.jdbc());
        new AgentCommandTransportSchemaInitializer(firstContext.jdbc()).afterPropertiesSet();
        insertTaskAcl(firstContext.jdbc());
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseName != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void pendingHallDeliverySurvivesContextRestartWithExactBoundedQueryAndIdempotency() throws Exception {
        AgentCommandDraft original = hallDraft(ISSUED, "请回报当前进展、风险和下一步计划");
        AgentCommandTransportWriteResult written = writer(firstContext, ids(M1, E1))
                .writeAuthorizedHall(original, CALLER);

        assertFalse(written.duplicate());
        assertEquals(1, count(firstContext.jdbc(), "agent_command_delivery"));
        assertEquals(1, count(firstContext.jdbc(), "agent_outbox_event"));
        assertEquals("PENDING", string(firstContext.jdbc(),
                "SELECT status FROM agent_command_delivery"));

        RuntimeContext restarted = newContext();
        AgentCommandMailboxServiceImpl mailbox = new AgentCommandMailboxServiceImpl(
                restarted.transportMapper());
        AgentCommandMailboxPage page = mailbox.query(
                TENANT, CLIENT, CALLER, TARGET, TASK, null, null, 10, false);

        assertEquals(1, page.entries().size());
        assertEquals(original.commandId(), page.entries().getFirst().commandId());
        assertEquals(AgentProtocolConstants.COMMAND_CONTEXT_REFRESH,
                page.entries().getFirst().commandType());
        assertEquals("PENDING", page.entries().getFirst().status());
        assertTrue(mailbox.query("tenant-other", CLIENT, CALLER, TARGET, TASK,
                null, null, 10, false).entries().isEmpty());
        assertTrue(mailbox.query(TENANT, CLIENT, "agent-other", TARGET, TASK,
                null, null, 10, false).entries().isEmpty());
        assertTrue(mailbox.query(TENANT, CLIENT, CALLER, TARGET, "task-other",
                null, null, 10, false).entries().isEmpty());

        AgentCommandTransportWriteResult duplicate = writer(restarted, ids(M2, E2)).writeAuthorizedHall(
                hallDraft(ISSUED + 50_000L, "请回报当前进展、风险和下一步计划"), CALLER);
        assertTrue(duplicate.duplicate());
        assertEquals(written.deliveryId(), duplicate.deliveryId());
        assertThrows(IllegalStateException.class, () -> writer(restarted, ids(M2, E2)).writeAuthorizedHall(
                hallDraft(ISSUED + 60_000L, "请回报不同的进展内容"), CALLER));
        assertEquals(1, count(restarted.jdbc(), "agent_command_delivery"));
        assertEquals(1, count(restarted.jdbc(), "agent_outbox_event"));
    }

    @Test
    void waitingHallDeliveryRestartsAndReissuesWithFreshMessageIdAndOriginalCommandId() throws Exception {
        AgentCommandDraft draft = hallDraft(ISSUED, "请回报当前进展、风险和下一步计划");
        AgentCommandTransportWriteResult written = writer(firstContext, ids(M1, E1)).writeAuthorizedHall(draft, CALLER);
        long waitingAt = ISSUED + 1_000L;
        long retryAt = ISSUED + 5_000L;
        makeWaitingSource(firstContext.jdbc(), written, waitingAt, retryAt);

        RuntimeContext restarted = newContext();
        AgentCommandReissueServiceImpl reissue = new AgentCommandReissueServiceImpl(
                new AgentCommandRecoveryDaoImpl(restarted.recoveryMapper()), gate(), connected(),
                AgentRabbitTopologyManifest.canonical(), restarted.transactionManager(), ids(M2, E2));

        assertEquals(1, reissue.reissueForReconnect(new AgentCommandReconnectScope(
                TENANT, CLIENT, TARGET, TARGET,
                AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT), 10,
                ISSUED + 10_000L).reissued());

        assertEquals("PENDING", string(restarted.jdbc(),
                "SELECT status FROM agent_command_delivery"));
        assertEquals(M2, string(restarted.jdbc(),
                "SELECT active_message_id FROM agent_command_delivery"));
        assertNotEquals(M1, string(restarted.jdbc(),
                "SELECT active_message_id FROM agent_command_delivery"));
        assertEquals(2, number(restarted.jdbc(),
                "SELECT active_attempt FROM agent_command_delivery"));
        assertEquals(draft.commandId(), string(restarted.jdbc(),
                "SELECT command_id FROM agent_command_delivery"));
        assertEquals(2, count(restarted.jdbc(), "agent_outbox_event"));
        assertEquals(draft.commandId(), string(restarted.jdbc(),
                "SELECT command_id FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals(M1, string(restarted.jdbc(),
                "SELECT replay_parent_message_id FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        String newWire = new String(restarted.jdbc().queryForObject(
                "SELECT wire_payload FROM agent_outbox_event WHERE message_id=?",
                byte[].class, M2), StandardCharsets.UTF_8);
        assertTrue(newWire.contains("\"messageId\":\"" + M2 + "\""));
        assertTrue(newWire.contains("\"commandId\":\"" + draft.commandId() + "\""));

        AgentCommandMailboxPage page = new AgentCommandMailboxServiceImpl(
                restarted.transportMapper()).query(
                TENANT, CLIENT, CALLER, TARGET, TASK, null, null, 10, false);
        assertEquals(1, page.entries().size());
        assertEquals("PENDING", page.entries().getFirst().status());
        assertEquals(draft.commandId(), page.entries().getFirst().commandId());
    }

    @Test
    void concurrentTargetMembershipRevokeCommitsBeforeAuthorizationAndProducesZeroWrites()
            throws Exception {
        RuntimeContext revokeContext = newContext();
        RuntimeContext writerContext = newContext();
        CountDownLatch revokedWhileLocked = new CountDownLatch(1);
        CountDownLatch allowRevokeCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = workers.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(
                        revokeContext.transactionManager());
                transaction.executeWithoutResult(status -> {
                    assertEquals(1, revokeContext.jdbc().update("""
                            UPDATE agent_task_member SET member_status='left',update_time=?
                            WHERE tenant_id=? AND client_id=? AND task_id=? AND agent_id=?
                            """, ISSUED + 1, TENANT, CLIENT, TASK, TARGET));
                    revokedWhileLocked.countDown();
                    try {
                        if (!allowRevokeCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("revoke commit was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("revoke interrupted", interrupted);
                    }
                });
            });
            assertTrue(revokedWhileLocked.await(5, TimeUnit.SECONDS));

            Future<AgentCommandTransportWriteResult> attempted = workers.submit(() ->
                    writer(writerContext, ids(M1, E1)).writeAuthorizedHall(
                            hallDraft(ISSUED, "请回报当前进展、风险和下一步计划"), CALLER));
            Thread.sleep(250);
            assertFalse(attempted.isDone(),
                    "writer must wait on the target membership row lock");

            allowRevokeCommit.countDown();
            revoke.get(5, TimeUnit.SECONDS);
            ExecutionException denied = assertThrows(ExecutionException.class,
                    () -> attempted.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, denied.getCause());
            assertEquals(0, count(writerContext.jdbc(), "agent_command_delivery"));
            assertEquals(0, count(writerContext.jdbc(), "agent_outbox_event"));
            assertEquals(0, count(writerContext.jdbc(), "agent_consumer_inbox"));
        } finally {
            allowRevokeCommit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentTargetOwnershipRevokeCommitsBeforeAuthorizationAndProducesZeroWrites()
            throws Exception {
        RuntimeContext revokeContext = newContext();
        RuntimeContext writerContext = newContext();
        CountDownLatch revokedWhileLocked = new CountDownLatch(1);
        CountDownLatch allowRevokeCommit = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> revoke = workers.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(
                        revokeContext.transactionManager());
                transaction.executeWithoutResult(status -> {
                    assertEquals(1, revokeContext.jdbc().update("""
                            UPDATE d08_agent_identity SET identity_status='revoked'
                            WHERE tenant_id=? AND client_id=? AND agent_id=?
                            """, TENANT, CLIENT, TARGET));
                    revokedWhileLocked.countDown();
                    try {
                        if (!allowRevokeCommit.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("revoke commit was not released");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("revoke interrupted", interrupted);
                    }
                });
            });
            assertTrue(revokedWhileLocked.await(5, TimeUnit.SECONDS));

            Future<AgentCommandTransportWriteResult> attempted = workers.submit(() ->
                    writer(writerContext, ids(M1, E1)).writeAuthorizedHall(
                            hallDraft(ISSUED, "请回报当前进展、风险和下一步计划"), CALLER));
            Thread.sleep(250);
            assertFalse(attempted.isDone(),
                    "writer must wait on the target ownership row lock");

            allowRevokeCommit.countDown();
            revoke.get(5, TimeUnit.SECONDS);
            ExecutionException denied = assertThrows(ExecutionException.class,
                    () -> attempted.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, denied.getCause());
            assertEquals(0, count(writerContext.jdbc(), "agent_command_delivery"));
            assertEquals(0, count(writerContext.jdbc(), "agent_outbox_event"));
            assertEquals(0, count(writerContext.jdbc(), "agent_consumer_inbox"));
        } finally {
            allowRevokeCommit.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void shadowDeadCapturePromotesAfterCanaryRestartWithSameProvenanceAndIdempotency()
            throws Exception {
        AgentCommandDraft shadowDraft = hallDraft(
                ISSUED, "请回报当前进展、风险和下一步计划");
        AgentCommandTransportWriteResult captured = writer(
                firstContext, ids(M1, E1), gate(AgentRabbitActivationState.DB_SHADOW))
                .writeAuthorizedHall(shadowDraft, CALLER);
        byte[] commandBytes = firstContext.jdbc().queryForObject(
                "SELECT command_payload FROM agent_command_delivery WHERE id=?",
                byte[].class, captured.deliveryId());
        byte[] wireBytes = firstContext.jdbc().queryForObject(
                "SELECT wire_payload FROM agent_outbox_event WHERE event_id=?",
                byte[].class, captured.outboxEventId());
        assertEquals("DEAD", string(firstContext.jdbc(),
                "SELECT status FROM agent_command_delivery"));
        assertEquals(AgentCommandTransportWriterImpl.DB_SHADOW_MARKER,
                string(firstContext.jdbc(),
                        "SELECT last_error FROM agent_command_delivery"));

        RuntimeContext restarted = newContext();
        AgentCommandTransportWriteResult promoted = writer(
                restarted, ids(M2, E2), gate(AgentRabbitActivationState.DISPATCH_CANARY))
                .writeAuthorizedHall(hallDraft(
                        ISSUED + 1_000L, "请回报当前进展、风险和下一步计划"), CALLER);

        assertFalse(promoted.duplicate());
        assertEquals(captured.deliveryId(), promoted.deliveryId());
        assertEquals(captured.messageId(), promoted.messageId());
        assertEquals(captured.outboxEventId(), promoted.outboxEventId());
        assertEquals("PENDING", string(restarted.jdbc(),
                "SELECT status FROM agent_command_delivery"));
        assertEquals("PENDING", string(restarted.jdbc(),
                "SELECT status FROM agent_outbox_event"));
        assertEquals(AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER,
                string(restarted.jdbc(),
                        "SELECT last_error FROM agent_command_delivery"));
        assertTrue(Arrays.equals(commandBytes, restarted.jdbc().queryForObject(
                "SELECT command_payload FROM agent_command_delivery WHERE id=?",
                byte[].class, captured.deliveryId())));
        assertTrue(Arrays.equals(wireBytes, restarted.jdbc().queryForObject(
                "SELECT wire_payload FROM agent_outbox_event WHERE event_id=?",
                byte[].class, captured.outboxEventId())));

        AgentCommandTransportWriteResult duplicate = writer(
                restarted, ids(M2, E2), gate(AgentRabbitActivationState.DISPATCH_CANARY))
                .writeAuthorizedHall(hallDraft(
                        ISSUED + 2_000L, "请回报当前进展、风险和下一步计划"), CALLER);
        assertTrue(duplicate.duplicate());
        assertEquals(captured.deliveryId(), duplicate.deliveryId());
        assertEquals(captured.messageId(), duplicate.messageId());
        assertEquals(1, count(restarted.jdbc(), "agent_command_delivery"));
        assertEquals(1, count(restarted.jdbc(), "agent_outbox_event"));
    }

    @Test
    void taskBriefingTaskInvitePersistsAndRemainsQueryableAfterRestart()
            throws Exception {
        AgentCommandDraft briefing = hallTaskBriefingDraft(ISSUED);
        AgentCommandTransportWriteResult written = writer(firstContext, ids(M1, E1))
                .writeAuthorizedHall(briefing, CALLER);

        assertFalse(written.duplicate());
        assertEquals(AgentProtocolConstants.COMMAND_TASK_INVITE,
                string(firstContext.jdbc(),
                        "SELECT command_type FROM agent_command_delivery"));
        RuntimeContext restarted = newContext();
        AgentCommandMailboxPage page = new AgentCommandMailboxServiceImpl(
                restarted.transportMapper()).query(
                TENANT, CLIENT, CALLER, TARGET, TASK, null, null, 10, false);

        assertEquals(1, page.entries().size());
        assertEquals(briefing.commandId(), page.entries().getFirst().commandId());
        assertEquals(AgentProtocolConstants.COMMAND_TASK_INVITE,
                page.entries().getFirst().commandType());
        assertEquals("PENDING", page.entries().getFirst().status());
    }

    @Test
    void hallOutboxFailureRollsBackDeliveryInTheSameRequiredTransaction() {
        firstContext.jdbc().execute("""
                CREATE TRIGGER d08_fail_outbox BEFORE INSERT ON agent_outbox_event
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='D08_OUTBOX_FAILURE'
                """);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> writer(firstContext, ids(M1, E1)).writeAuthorizedHall(
                        hallDraft(ISSUED, "请回报当前进展、风险和下一步计划"), CALLER));
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("D08_OUTBOX_FAILURE"), messages.toString());
        assertEquals(0, count(firstContext.jdbc(), "agent_command_delivery"));
        assertEquals(0, count(firstContext.jdbc(), "agent_outbox_event"));
        assertEquals(0, count(firstContext.jdbc(), "agent_consumer_inbox"));
    }

    private AgentCommandTransportWriterImpl writer(
            RuntimeContext context, Supplier<UUID> ids) {
        return writer(context, ids, gate(AgentRabbitActivationState.DISPATCH_CANARY));
    }

    private AgentCommandTransportWriterImpl writer(
            RuntimeContext context, Supplier<UUID> ids, AgentRabbitSafetyGate safetyGate) {
        return new AgentCommandTransportWriterImpl(
                new AgentCommandTransportDaoImpl(context.transportMapper()), safetyGate,
                lockedAgentService(context), lockedAccessService(context),
                context.transactionManager(), ids);
    }

    private AgentTaskCollaborationAccessService lockedAccessService(
            RuntimeContext context) {
        AgentTaskCollaborationAccessService access =
                mock(AgentTaskCollaborationAccessService.class);
        when(access.resolveMemberAccessForUpdate(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String tenantId = invocation.getArgument(0);
                    String clientId = invocation.getArgument(1);
                    String taskId = invocation.getArgument(2);
                    String agentId = invocation.getArgument(3);
                    List<String> taskStatus = context.jdbc().query(
                            """
                            SELECT reward_status FROM agent_task_meta
                            WHERE tenant_id=? AND client_id=? AND task_id=?
                              AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(task_id AS BINARY)=CAST(? AS BINARY)
                            LIMIT 2 FOR UPDATE
                            """, (row, index) -> row.getString(1),
                            tenantId, clientId, taskId, tenantId, clientId, taskId);
                    if (taskStatus.size() != 1) return AgentTaskAccessLevel.NONE;
                    List<String> memberStatus = context.jdbc().query(
                            """
                            SELECT member_status FROM agent_task_member
                            WHERE tenant_id=? AND client_id=? AND task_id=? AND agent_id=?
                              AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(task_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(agent_id AS BINARY)=CAST(? AS BINARY)
                            LIMIT 2 FOR UPDATE
                            """, (row, index) -> row.getString(1),
                            tenantId, clientId, taskId, agentId,
                            tenantId, clientId, taskId, agentId);
                    if (memberStatus.size() != 1) return AgentTaskAccessLevel.NONE;
                    return switch (memberStatus.getFirst()) {
                        case "accepted", "working", "blocked" ->
                                AgentTaskAccessLevel.READ_WRITE;
                        case "done", "failed" -> AgentTaskAccessLevel.READ_ONLY;
                        default -> AgentTaskAccessLevel.NONE;
                    };
                });
        return access;
    }

    private AgentService lockedAgentService(RuntimeContext context) {
        AgentService service = mock(AgentService.class);
        when(service.requireApiKeyOwnedAgentForUpdate(
                anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String clientId = invocation.getArgument(0);
                    String tenantId = invocation.getArgument(1);
                    String agentId = invocation.getArgument(2);
                    List<String> identities = context.jdbc().query(
                            """
                            SELECT agent_id FROM d08_agent_identity
                            WHERE tenant_id=? AND client_id=? AND agent_id=? AND identity_status='active'
                              AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(agent_id AS BINARY)=CAST(? AS BINARY)
                            LIMIT 2 FOR UPDATE
                            """, (row, index) -> row.getString(1),
                            tenantId, clientId, agentId, tenantId, clientId, agentId);
                    if (!List.of(agentId).equals(identities)) {
                        throw new IllegalArgumentException("Agent identity is not owned");
                    }
                    List<String> statuses = context.jdbc().query(
                            """
                            SELECT runtime_status FROM d08_agent_runtime
                            WHERE tenant_id=? AND client_id=? AND agent_id=?
                              AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                              AND CAST(agent_id AS BINARY)=CAST(? AS BINARY)
                            LIMIT 2 FOR UPDATE
                            """, (row, index) -> row.getString(1),
                            tenantId, clientId, agentId, tenantId, clientId, agentId);
                    if (statuses.size() != 1) {
                        throw new IllegalArgumentException("Agent runtime is not owned");
                    }
                    AgentRuntimeDTO runtime = new AgentRuntimeDTO();
                    runtime.setAgentId(agentId);
                    runtime.setStatus(statuses.getFirst());
                    return runtime;
                });
        return service;
    }

    private RuntimeContext newContext() throws Exception {
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, databaseName));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandTransportMapper.class);
        configuration.addMapper(AgentCommandRecoveryMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        assertNotNull(factory);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        return new RuntimeContext(
                new JdbcTemplate(source), new DataSourceTransactionManager(source),
                template.getMapper(AgentCommandTransportMapper.class),
                template.getMapper(AgentCommandRecoveryMapper.class));
    }

    private void createTaskAclSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  task_id VARCHAR(100) NOT NULL,
                  reward_status VARCHAR(20) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_agent_task_meta_scope (tenant_id,client_id,task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  task_id VARCHAR(100) NOT NULL,
                  agent_id VARCHAR(100) NOT NULL,
                  member_role VARCHAR(20) NOT NULL,
                  member_status VARCHAR(20) NOT NULL,
                  assignment_source VARCHAR(20) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_task_member_scope (tenant_id,client_id,task_id,agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE d08_agent_identity (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  agent_id VARCHAR(100) NOT NULL,
                  identity_status VARCHAR(20) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_d08_identity_scope (tenant_id,client_id,agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE d08_agent_runtime (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  agent_id VARCHAR(100) NOT NULL,
                  runtime_status VARCHAR(20) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_d08_runtime_scope (tenant_id,client_id,agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
    }

    private void insertTaskAcl(JdbcTemplate jdbc) {
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_task_meta(
                    task_id,reward_status,tenant_id,client_id,create_time,update_time)
                VALUES (?,'running',?,?,?,?)
                """, TASK, TENANT, CLIENT, ISSUED - 1, ISSUED - 1));
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_task_member(
                    task_id,agent_id,member_role,member_status,assignment_source,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,'working','manual',?,?,?,?)
                """, TASK, CALLER, "coordinator", TENANT, CLIENT, ISSUED - 1, ISSUED - 1));
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_task_member(
                    task_id,agent_id,member_role,member_status,assignment_source,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,'accepted','auto',?,?,?,?)
                """, TASK, TARGET, "worker", TENANT, CLIENT, ISSUED - 1, ISSUED - 1));
        for (String agentId : List.of(CALLER, TARGET)) {
            assertEquals(1, jdbc.update("""
                    INSERT INTO d08_agent_identity(
                        agent_id,identity_status,tenant_id,client_id)
                    VALUES (?,'active',?,?)
                    """, agentId, TENANT, CLIENT));
            assertEquals(1, jdbc.update("""
                    INSERT INTO d08_agent_runtime(
                        agent_id,runtime_status,tenant_id,client_id)
                    VALUES (?,?,?,?)
                    """, agentId, TARGET.equals(agentId) ? "offline" : "online",
                    TENANT, CLIENT));
        }
    }

    private void makeWaitingSource(
            JdbcTemplate jdbc, AgentCommandTransportWriteResult written,
            long processedAt, long retryAt) {
        assertEquals(1, jdbc.update("""
                UPDATE agent_outbox_event
                SET status='PUBLISHED',attempt_count=1,next_retry_at=NULL,
                    lease_owner=NULL,lease_until=NULL,publisher_confirm_status='ACK',
                    confirmed_at=?,confirm_error=NULL,mandatory_return_status='NOT_RETURNED',
                    returned_at=NULL,return_reply_code=NULL,return_reply_text=NULL,
                    published_at=?,last_error=NULL,version=1,update_time=?
                WHERE event_id=?
                """, processedAt - 2, processedAt - 1, processedAt, written.outboxEventId()));
        assertEquals(1, jdbc.update("""
                UPDATE agent_command_delivery
                SET status='WAITING_AGENT',next_retry_at=?,lease_owner=NULL,lease_until=NULL,
                    last_error=?,version=2,update_time=?
                WHERE id=?
                """, retryAt, AgentCommandReissueServiceImpl.AGENT_OFFLINE,
                processedAt, written.deliveryId()));
        byte[] wire = jdbc.queryForObject(
                "SELECT wire_payload FROM agent_outbox_event WHERE event_id=?",
                byte[].class, written.outboxEventId());
        byte[] hash = jdbc.queryForObject(
                "SELECT wire_payload_hash FROM agent_outbox_event WHERE event_id=?",
                byte[].class, written.outboxEventId());
        assertNotNull(wire);
        assertNotNull(hash);
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                    consumer_name,message_id,event_id,command_id,delivery_id,
                    wire_payload,wire_payload_hash,status,result_status,attempt_count,
                    next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                    processed_at,last_error,version,replay_parent_message_id,
                    replay_requester_id,replay_approver_id,replay_reason,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,'WAITING_AGENT','WAITING_AGENT',1,
                    ?,NULL,NULL,1,?,?,?,0,NULL,NULL,NULL,NULL,?,?,?,?)
                """, AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                written.messageId(), written.outboxEventId(),
                string(jdbc, "SELECT command_id FROM agent_command_delivery WHERE id="
                        + written.deliveryId()), written.deliveryId(), wire, hash, retryAt,
                EXPIRES, processedAt, AgentCommandReissueServiceImpl.AGENT_OFFLINE,
                TENANT, CLIENT, processedAt - 1, processedAt));
    }

    private AgentCommandDraft hallTaskBriefingDraft(long issuedAt) {
        String commandType = AgentProtocolConstants.COMMAND_TASK_INVITE;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, TASK, TARGET, INTENT, commandType);
        return new AgentCommandDraft(
                AgentCommandCanonicalCodec.SCHEMA_VERSION, commandId, TASK, INTENT,
                TENANT, CLIENT, TASK, null, TARGET, commandType, issuedAt,
                issuedAt + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, INTENT,
                new AgentHallCommandPayload(
                        "task_briefing", "请阅读任务简报并确认职责", "juyiting",
                        "协作任务已分派", "conversation-1", null,
                        "supervised", false,
                        new AgentHallCommandContext(
                                "接口联调", null, null, null, "v1",
                                List.of("ref-1"), List.of("briefing"))));
    }

    private AgentCommandDraft hallDraft(long issuedAt, String instruction) {
        String commandType = AgentProtocolConstants.COMMAND_CONTEXT_REFRESH;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, TASK, TARGET, INTENT, commandType);
        return new AgentCommandDraft(
                AgentCommandCanonicalCodec.SCHEMA_VERSION, commandId, TASK, INTENT,
                TENANT, CLIENT, TASK, null, TARGET, commandType, issuedAt,
                issuedAt + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, INTENT,
                new AgentHallCommandPayload(
                        "request_report", instruction, "juyiting", "例行进展同步",
                        "conversation-1", null, "supervised", false,
                        new AgentHallCommandContext(
                                "接口联调", null, null, null, "v1",
                                List.of("ref-1"), List.of("hall", "restart"))));
    }

    private AgentRabbitSafetyGate gate() {
        return gate(AgentRabbitActivationState.DISPATCH_CANARY);
    }

    private AgentRabbitSafetyGate gate(AgentRabbitActivationState state) {
        boolean topology = state == AgentRabbitActivationState.MQ_SHADOW
                || state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        boolean dispatch = state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED;
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(
                        state != AgentRabbitActivationState.OFF),
                new AgentRabbitSafetyProperties.RabbitTopology(topology),
                new AgentRabbitSafetyProperties.RabbitPublish(dispatch),
                new AgentRabbitSafetyProperties.RabbitConsume(dispatch),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch),
                topology ? new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated") : null);
        AgentRabbitDispatchScopeProperties scopes = dispatch
                ? new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(TENANT, CLIENT)))
                : null;
        return new AgentRabbitSafetyGate(properties, scopes);
    }

    private AgentRawCommandDispatcher connected() {
        return new AgentRawCommandDispatcher() {
            @Override
            public boolean isExactAgentConnected(
                    String tenantId, String clientId, String targetAgentId) {
                return TENANT.equals(tenantId) && CLIENT.equals(clientId)
                        && TARGET.equals(targetAgentId);
            }

            @Override
            public AgentRawCommandDispatchResult dispatchExactRawCommand(
                    String tenantId, String clientId, String taskId,
                    String targetAgentId, byte[] rawWireBytes) {
                throw new AssertionError("reissue recovery must not perform WebSocket I/O");
            }
        };
    }

    private Supplier<UUID> ids(String... values) {
        ArrayDeque<UUID> ids = new ArrayDeque<>();
        for (String value : values) ids.add(UUID.fromString(value));
        return () -> {
            UUID value = ids.pollFirst();
            if (value == null) throw new AssertionError("UUID sequence exhausted");
            return value;
        };
    }

    private int count(JdbcTemplate jdbc, String table) {
        return number(jdbc, "SELECT COUNT(*) FROM " + table);
    }

    private int number(JdbcTemplate jdbc, String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        assertNotNull(value);
        return value;
    }

    private String string(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, String.class);
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
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record RuntimeContext(
            JdbcTemplate jdbc,
            DataSourceTransactionManager transactionManager,
            AgentCommandTransportMapper transportMapper,
            AgentCommandRecoveryMapper recoveryMapper) {
    }
}
