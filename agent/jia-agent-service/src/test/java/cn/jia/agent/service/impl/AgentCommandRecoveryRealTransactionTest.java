package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentCommandReissueSettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.dao.impl.AgentCommandInboxDaoImpl;
import cn.jia.agent.dao.impl.AgentCommandRecoveryDaoImpl;
import cn.jia.agent.dao.impl.AgentOutboxRelayDaoImpl;
import cn.jia.agent.entity.AgentCommandAck;
import cn.jia.agent.entity.AgentCommandAckRejectedException;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import cn.jia.agent.entity.AgentWaitingCommandCandidate;
import cn.jia.agent.mapper.AgentCommandInboxMapper;
import cn.jia.agent.mapper.AgentCommandRecoveryMapper;
import cn.jia.agent.mapper.AgentOutboxRelayMapper;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D06 production mapper + REQUIRED rollback evidence on H2 MySQL mode. */
class AgentCommandRecoveryRealTransactionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS;
    private static final String M1 = "11111111-1111-1111-1111-111111111111";
    private static final String M2 = "22222222-2222-2222-2222-222222222222";
    private static final String E2 = "33333333-3333-3333-3333-333333333333";

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private AgentCommandRecoveryDao dao;
    private AgentOutboxRelayDao relayDao;
    private AgentCommandInboxDao inboxDao;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:d06_tx_" + System.nanoTime()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        createSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandRecoveryMapper.class);
        configuration.addMapper(AgentOutboxRelayMapper.class);
        configuration.addMapper(AgentCommandInboxMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        dao = new AgentCommandRecoveryDaoImpl(
                template.getMapper(AgentCommandRecoveryMapper.class));
        relayDao = new AgentOutboxRelayDaoImpl(
                template.getMapper(AgentOutboxRelayMapper.class));
        inboxDao = new AgentCommandInboxDaoImpl(
                template.getMapper(AgentCommandInboxMapper.class));
        manager = new DataSourceTransactionManager(source);
        insertWaitingSource();
    }

    @Test
    void reissueCommitsDeliveryAndOneRelayDiscoverablePendingOutbox() {
        AgentCommandReissueServiceImpl service = reissueService(dao);
        assertEquals(1, service.reissueForReconnect(scope(), 10, NOW).reissued());

        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(M2, string("SELECT active_message_id FROM agent_command_delivery WHERE id=1"));
        assertEquals(2, number("SELECT active_attempt FROM agent_command_delivery WHERE id=1"));
        assertEquals(2, number("SELECT attempt_count FROM agent_command_delivery WHERE id=1"));
        assertEquals(M1, string("SELECT replay_parent_message_id FROM agent_command_delivery WHERE id=1"));
        assertEquals(2, number("SELECT COUNT(*) FROM agent_outbox_event"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event WHERE message_id='" + M1 + "'"));
        assertEquals("PENDING", string("SELECT status FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals(0, number("SELECT attempt_count FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals(2, number("SELECT active_attempt FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals("DISPATCH_ELIGIBLE_V1", string(
                "SELECT last_error FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals(M1, string("SELECT replay_parent_message_id FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertFalse(Arrays.equals(
                blob("SELECT wire_payload FROM agent_outbox_event WHERE message_id='" + M1 + "'"),
                blob("SELECT wire_payload FROM agent_outbox_event WHERE message_id='" + M2 + "'")));
    }


    @Test
    void fastReceivedAckBeforeSentCompletionIsDurableAndNeverRegresses() {
        jdbc.update("DELETE FROM agent_consumer_inbox");
        jdbc.update("UPDATE agent_command_delivery SET status='PUBLISHED',next_retry_at=NULL,"
                + "last_error=NULL,version=7 WHERE id=1");
        byte[] wire = blob("SELECT wire_payload FROM agent_outbox_event WHERE message_id='" + M1 + "'");
        AgentCommandInboxServiceImpl inbox = new AgentCommandInboxServiceImpl(inboxDao, gate(), manager);
        var claim = inbox.claim(new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", M1, "event-1",
                "cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624",
                1L, wire), "fast-ack-worker", NOW, 10_000L);
        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox WHERE message_id='" + M1 + "'"));

        AgentCommandAckServiceImpl ack = new AgentCommandAckServiceImpl(dao, gate(), manager);
        assertEquals("RECEIVED", ack.acknowledge(
                ack("fast-received", "RECEIVED", M1), NOW + 1).status());
        assertEquals("RECEIVED", string("SELECT status FROM agent_command_delivery WHERE id=1"));

        inbox.complete(claim.token(), AgentInboxDisposition.sent(), NOW + 2);
        assertEquals("RECEIVED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals("PROCESSED", string("SELECT status FROM agent_consumer_inbox WHERE message_id='" + M1 + "'"));
        assertEquals("SENT", string("SELECT result_status FROM agent_consumer_inbox WHERE message_id='" + M1 + "'"));
    }

    @Test
    void restartSchedulerRecoversStaleSentWithNewTransportIdentity() {
        jdbc.update("UPDATE agent_command_delivery SET status='SENT',next_retry_at=NULL,"
                + "last_error=NULL,update_time=? WHERE id=1", NOW - 30_001L);
        jdbc.update("UPDATE agent_consumer_inbox SET status='PROCESSED',result_status='SENT',"
                + "next_retry_at=NULL,last_error=NULL WHERE id=20");
        AgentCommandReissueServiceImpl service = reissueService(dao);
        try (AgentCommandReissueCoordinator coordinator = new AgentCommandReissueCoordinator(
                service, new AgentCommandReissueSettings(2, 1, 10, 100, 30_000L),
                () -> NOW, false)) {
            coordinator.runOnce();
        }
        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(M2, string("SELECT active_message_id FROM agent_command_delivery WHERE id=1"));
        assertEquals(2, number("SELECT active_attempt FROM agent_command_delivery WHERE id=1"));
        assertEquals(2, number("SELECT COUNT(*) FROM agent_outbox_event"));
        assertEquals(M1, string("SELECT replay_parent_message_id FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
    }

    @Test
    void expiredWaitingTerminalizesDeliveryAndInboxAtomically() {
        assertEquals(0, reissueService(dao).reissueDue(10, 0, EXPIRES).reissued());
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals("EXPIRED", string("SELECT status FROM agent_consumer_inbox WHERE id=20"));
        assertEquals("EXPIRED", string("SELECT result_status FROM agent_consumer_inbox WHERE id=20"));

        insertWaitingSourceAfterDelete();
        AgentCommandRecoveryDao failing = new DelegatingDao(dao) {
            @Override public int expireWaitingInbox(
                    AgentConsumerInboxEntity inbox, String lastError, long now) { return 0; }
        };
        assertThrows(IllegalStateException.class,
                () -> reissueService(failing).reissueDue(10, 0, EXPIRES));
        assertEquals("WAITING_AGENT", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals("WAITING_AGENT", string("SELECT status FROM agent_consumer_inbox WHERE id=20"));
    }

    @Test
    void expiredSentLateReceivedCannotEscapeExpiryRecovery() {
        jdbc.update("UPDATE agent_command_delivery SET status='SENT',next_retry_at=NULL,"
                + "last_error=NULL,update_time=? WHERE id=1", EXPIRES - 1);
        jdbc.update("UPDATE agent_consumer_inbox SET status='PROCESSED',result_status='SENT',"
                + "next_retry_at=NULL,last_error=NULL WHERE id=20");
        long version = jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=1", Long.class);
        AgentCommandAckServiceImpl ack = new AgentCommandAckServiceImpl(dao, gate(), manager);

        assertThrows(AgentCommandAckRejectedException.class,
                () -> ack.acknowledge(ack("late-received", "RECEIVED", M1), EXPIRES));

        assertEquals("SENT", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(version, jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=1", Long.class));
        assertEquals(1, number("SELECT COUNT(*) FROM agent_outbox_event"));

        assertEquals(0, reissueService(dao).reissueDue(10, 0, EXPIRES).reissued());
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(version + 1, jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=1", Long.class));
        assertEquals("PROCESSED", string("SELECT status FROM agent_consumer_inbox WHERE id=20"));
        assertEquals("SENT", string("SELECT result_status FROM agent_consumer_inbox WHERE id=20"));
        assertEquals(1, number("SELECT COUNT(*) FROM agent_outbox_event"));
    }

    @Test
    void maxMinusEightCompletesAllEightMutationsAtRejectedTerminalMaxVersion() {
        jdbc.update("UPDATE agent_command_delivery SET version=? WHERE id=1", Long.MAX_VALUE - 8);
        assertEquals(1, reissueService(dao).reissueForReconnect(scope(), 10, NOW).reissued());

        AgentOutboxRelayServiceImpl relay = relayService();
        var candidates = relay.discover(NOW + 1, 10);
        assertEquals(1, candidates.size());
        AgentOutboxClaim claim = relay.claim(candidates.getFirst(), "relay-d06", NOW + 1);
        assertEquals(AgentOutboxClaim.Status.ACQUIRED, claim.status());
        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                relay.settle(claim.token(), AgentRabbitPublishResult.ack(), NOW + 2));

        byte[] wire = blob("SELECT wire_payload FROM agent_outbox_event WHERE message_id='" + M2 + "'");
        String eventId = string("SELECT event_id FROM agent_outbox_event WHERE message_id='" + M2 + "'");
        AgentCommandInboxServiceImpl inbox = new AgentCommandInboxServiceImpl(inboxDao, gate(), manager);
        AgentInboxClaim inboxClaim = inbox.claim(new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", M2, eventId,
                "cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624",
                1L, wire), "inbox-d06", NOW + 3, 10_000L);
        assertEquals(AgentInboxClaim.Kind.ACQUIRED, inboxClaim.kind());
        inbox.complete(inboxClaim.token(), AgentInboxDisposition.sent(), NOW + 4);

        AgentCommandAckServiceImpl ack = new AgentCommandAckServiceImpl(dao, gate(), manager);
        ack.acknowledge(ack("ack-received", "RECEIVED", M2), NOW + 5);
        ack.acknowledge(ack("ack-started", "STARTED", M2), NOW + 6);
        ack.acknowledge(ack("ack-rejected", "REJECTED", M2), NOW + 7);

        assertEquals("REJECTED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(Long.MAX_VALUE,
                jdbc.queryForObject("SELECT version FROM agent_command_delivery WHERE id=1", Long.class));
        assertEquals("PUBLISHED", string(
                "SELECT status FROM agent_outbox_event WHERE message_id='" + M2 + "'"));
        assertEquals("PUBLISHED", string(
                "SELECT status FROM agent_outbox_event WHERE message_id='" + M1 + "'"));
        assertEquals("WAITING_AGENT", string(
                "SELECT status FROM agent_consumer_inbox WHERE message_id='" + M1 + "'"));
        assertEquals("PROCESSED", string(
                "SELECT status FROM agent_consumer_inbox WHERE message_id='" + M2 + "'"));
    }

    @Test
    void maxMinusSevenRejectsReissueWithoutChangingDeliveryOrOutbox() {
        jdbc.update("UPDATE agent_command_delivery SET version=? WHERE id=1", Long.MAX_VALUE - 7);

        assertEquals(0, reissueService(dao).reissueForReconnect(scope(), 10, NOW).reissued());

        assertEquals("WAITING_AGENT", string(
                "SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(M1, string(
                "SELECT active_message_id FROM agent_command_delivery WHERE id=1"));
        assertEquals(Long.MAX_VALUE - 7,
                jdbc.queryForObject("SELECT version FROM agent_command_delivery WHERE id=1", Long.class));
        assertEquals(1, number("SELECT COUNT(*) FROM agent_outbox_event"));
    }

    @Test
    void failureAfterDeliveryCasBeforeOutboxInsertRollsBackBoth() {
        AgentCommandRecoveryDao failing = new DelegatingDao(dao) {
            @Override public int insertOutbox(AgentOutboxEventEntity outbox) { return 0; }
        };
        assertThrows(IllegalStateException.class,
                () -> reissueService(failing).reissueForReconnect(scope(), 10, NOW));
        assertEquals("WAITING_AGENT", string("SELECT status FROM agent_command_delivery WHERE id=1"));
        assertEquals(M1, string("SELECT active_message_id FROM agent_command_delivery WHERE id=1"));
        assertEquals(1, number("SELECT COUNT(*) FROM agent_outbox_event"));
    }

    @Test
    void reconnectAndSchedulerRaceHasOneCasWinnerAndOneNewOutbox() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> reconnect = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return reissueService(dao,
                        "22222222-2222-2222-2222-222222222222",
                        "33333333-3333-3333-3333-333333333333")
                        .reissueForReconnect(scope(), 10, NOW).reissued();
            });
            Future<Integer> scheduler = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return reissueService(dao,
                        "44444444-4444-4444-4444-444444444444",
                        "55555555-5555-5555-5555-555555555555")
                        .reissueDue(10, 0, NOW).reissued();
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, reconnect.get(15, TimeUnit.SECONDS)
                    + scheduler.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, number("SELECT COUNT(*) FROM agent_outbox_event"));
        assertEquals(1, number("SELECT COUNT(*) FROM agent_outbox_event WHERE status='PENDING'"));
    }

    @Test
    void ackTransitionsCommitAndZeroRowCasRollsBackWithoutMutation() {
        jdbc.update("UPDATE agent_command_delivery SET status='SENT',next_retry_at=NULL,last_error=NULL WHERE id=1");
        jdbc.update("UPDATE agent_consumer_inbox SET status='PROCESSED',result_status='SENT',next_retry_at=NULL,last_error=NULL WHERE id=20");
        AgentCommandAckServiceImpl service = new AgentCommandAckServiceImpl(dao, gate(), manager);
        service.acknowledge(ack("ack-r", "RECEIVED"), NOW);
        service.acknowledge(ack("ack-s", "STARTED"), NOW);
        assertEquals("STARTED", string("SELECT status FROM agent_command_delivery WHERE id=1"));

        AgentCommandRecoveryDao zero = new DelegatingDao(dao) {
            @Override public int advanceAck(AgentCommandDeliveryEntity delivery, String newStatus,
                    String lastError, long now) { return 0; }
        };
        assertThrows(RuntimeException.class,
                () -> new AgentCommandAckServiceImpl(zero, gate(), manager)
                        .acknowledge(ack("ack-terminal", "SUCCEEDED"), NOW));
        assertEquals("STARTED", string("SELECT status FROM agent_command_delivery WHERE id=1"));
    }

    private AgentCommandReissueServiceImpl reissueService(AgentCommandRecoveryDao selectedDao) {
        return reissueService(selectedDao, M2, E2);
    }

    private AgentCommandReissueServiceImpl reissueService(
            AgentCommandRecoveryDao selectedDao, String messageId, String eventId) {
        List<UUID> ids = new java.util.ArrayList<>(List.of(
                UUID.fromString(messageId), UUID.fromString(eventId)));
        return new AgentCommandReissueServiceImpl(
                selectedDao, gate(), connected(), AgentRabbitTopologyManifest.canonical(),
                manager, () -> ids.removeFirst());
    }


    private AgentOutboxRelayServiceImpl relayService() {
        return new AgentOutboxRelayServiceImpl(
                relayDao, gate(), AgentRabbitTopologyManifest.canonical(), ready(),
                new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(true)),
                manager);
    }

    private AgentRabbitTopologyReadiness ready() {
        try {
            AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
            AgentRabbitTopologyReadiness readiness =
                    new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(manifest);
            Method method = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
            method.setAccessible(true);
            method.invoke(readiness);
            return readiness;
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private AgentCommandReconnectScope scope() {
        return new AgentCommandReconnectScope(
                "tenant-a", "client-a", "agent-a", "agent-a", "AGENT_RECONNECT");
    }

    private AgentCommandAck ack(String messageId, String status) {
        return ack(messageId, status, M1);
    }

    private AgentCommandAck ack(String messageId, String status, String correlationId) {
        return new AgentCommandAck("tenant-a", "client-a", "agent-a", messageId, correlationId,
                "cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624",
                "task-1", null, status, NOW);
    }

    private void insertWaitingSourceAfterDelete() {
        jdbc.update("DELETE FROM agent_consumer_inbox");
        jdbc.update("DELETE FROM agent_outbox_event");
        jdbc.update("DELETE FROM agent_command_delivery");
        insertWaitingSource();
    }

    private void insertWaitingSource() {
        AgentCommandDraft draft = new AgentCommandDraft(1, "cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624", "task-1", "cause-1",
                "tenant-a", "client-a", "task-1", null, "agent-a",
                AgentProtocolConstants.COMMAND_TASK_INVITE, ISSUED, EXPIRES,
                new AgentTaskInvitePayload(
                        "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                        "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                        "title", List.of("review"), "agent-a", List.of("agent-a", "agent-b"),
                        "coordinator",
                        "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                        "juyiting"));
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, M1, 1);
        AgentRabbitTopologyManifest.PublishRoute route =
                AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,command_id,task_id,target_agent_id,command_type,command_payload,
                  command_payload_hash,status,attempt_count,next_retry_at,active_message_id,
                  active_attempt,expires_at,last_error,version,tenant_id,client_id,create_time,update_time)
                VALUES (1,'cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624','task-1','agent-a','TASK_INVITE',?,?,'WAITING_AGENT',1,?, ?,1,?,'AGENT_OFFLINE',7,'tenant-a','client-a',?,?)
                """, business, AgentCommandCanonicalCodec.sha256(business), NOW - 1, M1, EXPIRES, NOW, NOW);
        jdbc.update("""
                INSERT INTO agent_outbox_event(
                  id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                  destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                  active_attempt,expires_at,publisher_confirm_status,confirmed_at,
                  mandatory_return_status,published_at,version,tenant_id,client_id,create_time,update_time)
                VALUES (10,'event-1',?,'cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624',1,'task','task-1',?,?,?,?,'PUBLISHED',1,1,?,
                        'ACK',?,'NOT_RETURNED',?,2,'tenant-a','client-a',?,?)
                """, M1, route.destination(), route.routingKey(), wire,
                AgentCommandCanonicalCodec.sha256(wire), EXPIRES, NOW - 10, NOW - 9, NOW, NOW);
        jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                  id,consumer_name,message_id,event_id,command_id,delivery_id,wire_payload,
                  wire_payload_hash,status,result_status,attempt_count,next_retry_at,active_attempt,
                  expires_at,processed_at,last_error,version,tenant_id,client_id,create_time,update_time)
                VALUES (20,'agent-command-dispatch-v1',?,'event-1','cmd_task_invite_a40585d9a8f94e453a79de08e8c9723874e0b915c6e4975a8668b0ba1fc40624',1,?,?,'WAITING_AGENT',
                        'WAITING_AGENT',1,?,1,?,?,'AGENT_OFFLINE',1,'tenant-a','client-a',?,?)
                """, M1, wire, AgentCommandCanonicalCodec.sha256(wire), NOW - 1,
                EXPIRES, NOW - 2, NOW, NOW);
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, command_id VARCHAR(100) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100), target_agent_id VARCHAR(100) NOT NULL,
                  command_type VARCHAR(64) NOT NULL, command_payload BLOB NOT NULL,
                  command_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
                  attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_message_id VARCHAR(100), active_attempt INT NOT NULL, expires_at BIGINT NOT NULL,
                  last_error VARCHAR(2000), version BIGINT NOT NULL, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,command_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
                  command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL, aggregate_type VARCHAR(30) NOT NULL,
                  aggregate_id VARCHAR(100) NOT NULL, destination VARCHAR(100) NOT NULL, routing_key VARCHAR(100) NOT NULL,
                  wire_payload BLOB NOT NULL, wire_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
                  attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, publisher_confirm_status VARCHAR(20) NOT NULL,
                  confirmed_at BIGINT, confirm_error VARCHAR(2000), mandatory_return_status VARCHAR(20) NOT NULL,
                  returned_at BIGINT, return_reply_code INT, return_reply_text VARCHAR(1000), published_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT NOT NULL, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,event_id))
                """);
        jdbc.execute("CREATE INDEX idx_outbox_message ON agent_outbox_event(tenant_id,client_id,message_id)");
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, consumer_name VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
                  event_id VARCHAR(100) NOT NULL, command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL,
                  wire_payload BLOB NOT NULL, wire_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
                  result_status VARCHAR(32), attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100),
                  lease_until BIGINT, active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, processed_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT NOT NULL, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,consumer_name,message_id))
                """);
    }

    private AgentRabbitSafetyGate gate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true), new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true), new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker("isolated.invalid", 35672, "user", "pass", "/d06"));
        return new AgentRabbitSafetyGate(properties, new AgentRabbitDispatchScopeProperties(
                List.of(new AgentRabbitDispatchScopeProperties.AllowedScope("tenant-a", "client-a"))));
    }

    private AgentRawCommandDispatcher connected() {
        return new AgentRawCommandDispatcher() {
            @Override public boolean isExactAgentConnected(String tenantId, String clientId, String targetAgentId) {
                return true;
            }
            @Override public AgentRawCommandDispatchResult dispatchExactRawCommand(
                    String tenantId, String clientId, String taskId, String targetAgentId, byte[] rawWireBytes) {
                throw new AssertionError("network dispatch is forbidden in D06 recovery transactions");
            }
        };
    }

    private String string(String sql) { return jdbc.queryForObject(sql, String.class); }
    private int number(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private byte[] blob(String sql) { return jdbc.queryForObject(sql, byte[].class); }

    private static class DelegatingDao implements AgentCommandRecoveryDao {
        private final AgentCommandRecoveryDao delegate;
        private DelegatingDao(AgentCommandRecoveryDao delegate) { this.delegate = delegate; }
        @Override public List<AgentWaitingCommandCandidate> findReconnectCandidates(String tenantId, String clientId,
                String targetAgentId, long now, long afterDeliveryId, int limit) {
            return delegate.findReconnectCandidates(tenantId, clientId, targetAgentId, now, afterDeliveryId, limit);
        }
        @Override public List<AgentWaitingCommandCandidate> findDueCandidates(
                long now, long sentBefore, long afterDeliveryId, int limit) {
            return delegate.findDueCandidates(now, sentBefore, afterDeliveryId, limit);
        }
        @Override public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) {
            return delegate.lockDelivery(tenantId, clientId, deliveryId);
        }
        @Override public AgentCommandDeliveryEntity lockDeliveryByCommand(String tenantId, String clientId, String commandId) {
            return delegate.lockDeliveryByCommand(tenantId, clientId, commandId);
        }
        @Override public List<AgentOutboxEventEntity> lockActiveOutboxes(String tenantId, String clientId,
                long deliveryId, String messageId) {
            return delegate.lockActiveOutboxes(tenantId, clientId, deliveryId, messageId);
        }
        @Override public List<AgentOutboxEventEntity> lockPreviousAttemptOutboxes(
                String tenantId, String clientId, long deliveryId, int previousAttempt) {
            return delegate.lockPreviousAttemptOutboxes(
                    tenantId, clientId, deliveryId, previousAttempt);
        }
        @Override public AgentConsumerInboxEntity lockInbox(String tenantId, String clientId,
                String consumerName, String messageId) {
            return delegate.lockInbox(tenantId, clientId, consumerName, messageId);
        }
        @Override public int reissueDelivery(AgentCommandDeliveryEntity delivery, String newMessageId,
                String requestedBy, String reason, String lastError, long now) {
            return delegate.reissueDelivery(delivery, newMessageId, requestedBy, reason, lastError, now);
        }
        @Override public int expireDelivery(
                AgentCommandDeliveryEntity delivery, String lastError, long now) {
            return delegate.expireDelivery(delivery, lastError, now);
        }
        @Override public int expireWaitingInbox(
                AgentConsumerInboxEntity inbox, String lastError, long now) {
            return delegate.expireWaitingInbox(inbox, lastError, now);
        }
        @Override public int insertOutbox(AgentOutboxEventEntity outbox) { return delegate.insertOutbox(outbox); }
        @Override public int advanceAck(AgentCommandDeliveryEntity delivery, String newStatus, String lastError, long now) {
            return delegate.advanceAck(delivery, newStatus, lastError, now);
        }
    }
}
