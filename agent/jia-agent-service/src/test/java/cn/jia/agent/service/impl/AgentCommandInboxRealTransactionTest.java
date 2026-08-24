package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.AgentCommandInboxDao;
import cn.jia.agent.dao.impl.AgentCommandInboxDaoImpl;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentConsumerInboxEntity;
import cn.jia.agent.entity.AgentInboxClaim;
import cn.jia.agent.entity.AgentInboxClaimToken;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentInboxDisposition;
import cn.jia.agent.entity.AgentInboxFenceException;
import cn.jia.agent.entity.AgentInboxIdentityConflictException;
import cn.jia.agent.entity.AgentInboxMessage;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandInboxMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D07 REQUIRED transaction and concurrency evidence on an H2 MySQL-mode database. */
class AgentCommandInboxRealTransactionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final byte[] WIRE = wire(
            "tenant-a", "client-a", "msg-1", "cmd-1", NOW + 60_000);

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate transactions;
    private AgentCommandInboxDao productionDao;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:d07_tx_" + System.nanoTime()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        createSchema();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandInboxMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        productionDao = new AgentCommandInboxDaoImpl(
                new SqlSessionTemplate(factory).getMapper(AgentCommandInboxMapper.class));
        manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        insertPublishedSource("tenant-a", "client-a", 1L, "msg-1", "evt-1", "cmd-1", NOW + 60_000);
    }

    @Test
    void claimAndDeliveryCommitTogether() {
        jdbc.update("UPDATE agent_outbox_event SET active_attempt=7 WHERE event_id='evt-1'");
        AgentInboxClaim claim = service(productionDao).claim(message(), "worker-a", NOW, 10_000);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void outerRequiredCallbackFailureRollsBackClaimAndDelivery() {
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            service(productionDao).claim(message(), "worker-a", NOW, 10_000);
            throw new IllegalStateException("CALLBACK_FAILURE");
        }));

        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void inboxInsertFailureAndDeliveryClaimFailureEachRollBackBothSides() {
        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.INBOX_INSERT))
                        .claim(message(), "worker-a", NOW, 10_000));
        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));

        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(new FailingDao(productionDao, Failure.DELIVERY_CLAIM))
                        .claim(message(), "worker-a", NOW, 10_000));
        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void completeCommitsBothTablesAndOuterCallbackCanRollItBack() {
        AgentInboxClaimToken token = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000).token();
        service(productionDao).complete(token, AgentInboxDisposition.sent(), NOW + 1);
        assertEquals("PROCESSED", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("SENT", string("SELECT status FROM agent_command_delivery"));

        resetPublishedSource();
        AgentInboxClaimToken rollbackToken = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000).token();
        assertThrows(IllegalStateException.class, () -> transactions.execute(status -> {
            service(productionDao).complete(
                    rollbackToken, AgentInboxDisposition.sent(), NOW + 1);
            throw new IllegalStateException("COMPLETE_CALLBACK_FAILURE");
        }));
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void failureOnEitherCompletionSideRollsBackTheOtherSide() {
        AgentInboxClaimToken token = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000).token();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(new FailingDao(productionDao, Failure.DELIVERY_COMPLETE))
                        .complete(token, AgentInboxDisposition.sent(), NOW + 1));
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));

        assertThrows(AgentInboxFenceException.class,
                () -> service(new FailingDao(productionDao, Failure.INBOX_COMPLETE))
                        .complete(token, AgentInboxDisposition.sent(), NOW + 1));
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void duplicateReturnsPriorResultAndConflictPreservesDurableRows() {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken token = service.claim(message(), "worker-a", NOW, 10_000).token();
        service.complete(token, AgentInboxDisposition.sent(), NOW + 1);

        jdbc.update("UPDATE agent_command_delivery SET status='FAILED' WHERE id=1");
        AgentInboxClaim duplicate = service.claim(message(), "worker-b", NOW + 2, 10_000);
        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, duplicate.kind());
        assertEquals("SENT", duplicate.priorResult().resultStatus());

        AgentInboxMessage conflict = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                "changed".getBytes());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(conflict, "worker-c", NOW + 3, 10_000));
        assertEquals(1, count("agent_consumer_inbox"));
        assertEquals("FAILED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void expiredCompletionUsesAuthoritativeBoundaryAndRollsBackEarlyAttempt() {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken token = service.claim(message(), "worker-a", NOW, 60_000).token();
        AgentInboxDisposition expired = new AgentInboxDisposition(
                AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED");

        assertThrows(IllegalArgumentException.class,
                () -> service.complete(token, expired, NOW + 1));
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));

        service.complete(token, expired, NOW + 60_000);
        assertEquals("EXPIRED", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void staleMarkerAndTerminalShapeCorruptionPreserveRows() {
        jdbc.update("UPDATE agent_command_delivery SET active_message_id='msg-new' WHERE id=1");
        AgentCommandInboxServiceImpl service = service(productionDao);
        assertEquals("DEAD", service.claim(
                message(), "worker-a", NOW, 10_000).priorResult().status());
        jdbc.update("UPDATE agent_command_delivery SET active_message_id='msg-1' WHERE id=1");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 1, 10_000));
        assertEquals("DEAD", string("SELECT status FROM agent_consumer_inbox"));

        resetPublishedSource();
        AgentInboxClaimToken token = service.claim(message(), "worker-a", NOW, 10_000).token();
        service.complete(token, AgentInboxDisposition.sent(), NOW + 1);
        jdbc.update("UPDATE agent_consumer_inbox SET processed_at=NULL WHERE message_id='msg-1'");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 2, 10_000));
        assertEquals("SENT", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void concurrentFirstClaimHasSingleWinnerAndNoDuplicateSideEffect() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentInboxClaim.Kind> first = executor.submit(
                    () -> concurrentClaim(ready, start, "worker-a", NOW));
            Future<AgentInboxClaim.Kind> second = executor.submit(
                    () -> concurrentClaim(ready, start, "worker-b", NOW));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AgentInboxClaim.Kind> results = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(
                    kind -> kind == AgentInboxClaim.Kind.ACQUIRED).count());
            assertEquals(1, results.stream().filter(
                    kind -> kind == AgentInboxClaim.Kind.IN_FLIGHT).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void concurrentExpiredLeaseReclaimHasSingleWinnerAndOldTokenIsStale() throws Exception {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken firstToken = service.claim(message(), "worker-a", NOW, 1_000).token();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentInboxClaim.Kind> first = executor.submit(
                    () -> concurrentClaim(ready, start, "worker-b", NOW + 1_000));
            Future<AgentInboxClaim.Kind> second = executor.submit(
                    () -> concurrentClaim(ready, start, "worker-c", NOW + 1_000));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<AgentInboxClaim.Kind> results = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(
                    kind -> kind == AgentInboxClaim.Kind.ACQUIRED).count());
            assertEquals(1, results.stream().filter(
                    kind -> kind == AgentInboxClaim.Kind.IN_FLIGHT).count());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertThrows(AgentInboxFenceException.class,
                () -> service.complete(firstToken, AgentInboxDisposition.sent(), NOW + 1_001));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM agent_consumer_inbox", Integer.class));
    }

    private AgentInboxClaim.Kind concurrentClaim(
            CountDownLatch ready, CountDownLatch start, String owner, long now) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return service(productionDao).claim(message(), owner, now, 10_000).kind();
    }

    private AgentCommandInboxServiceImpl service(AgentCommandInboxDao dao) {
        return new AgentCommandInboxServiceImpl(dao, enabledGate(), manager);
    }

    private AgentInboxMessage message() {
        return new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1, WIRE);
    }

    private void resetPublishedSource() {
        jdbc.update("DELETE FROM agent_consumer_inbox");
        jdbc.update("DELETE FROM agent_outbox_event");
        jdbc.update("DELETE FROM agent_command_delivery");
        insertPublishedSource("tenant-a", "client-a", 1L,
                "msg-1", "evt-1", "cmd-1", NOW + 60_000);
    }

    private void insertPublishedSource(
            String tenant, String client, long deliveryId,
            String messageId, String eventId, String commandId, long expiresAt) {
        byte[] command = "business".getBytes();
        byte[] sourceWire = wire(tenant, client, messageId, commandId, expiresAt);
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,command_id,task_id,target_agent_id,command_type,
                  command_payload,command_payload_hash,status,attempt_count,
                  active_message_id,active_attempt,expires_at,version,
                  tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, deliveryId, commandId, "task-1", "agent-1", "TASK_INVITE",
                command, sha256(command), "PUBLISHED", 1, messageId, 1, expiresAt, 0,
                tenant, client, NOW, NOW));
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_outbox_event(
                  id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                  destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                  active_attempt,expires_at,publisher_confirm_status,confirmed_at,
                  mandatory_return_status,published_at,version,
                  tenant_id,client_id,create_time,update_time)
                VALUES (2,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, eventId, messageId, commandId, deliveryId, "task", "task-1",
                "jia.agent.command", "agent.command.general", sourceWire, sha256(sourceWire),
                "PUBLISHED", 1, 1, expiresAt, "ACK", NOW,
                "NOT_RETURNED", NOW, 0, tenant, client, NOW, NOW));
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, command_id VARCHAR(100) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100), target_agent_id VARCHAR(100) NOT NULL,
                  command_type VARCHAR(64) NOT NULL, command_payload BLOB NOT NULL,
                  command_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
                  attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100),
                  lease_until BIGINT, active_message_id VARCHAR(100), active_attempt INT NOT NULL,
                  expires_at BIGINT NOT NULL, last_error VARCHAR(2000), version BIGINT NOT NULL,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,command_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(100) NOT NULL,
                  message_id VARCHAR(100) NOT NULL, command_id VARCHAR(100) NOT NULL,
                  delivery_id BIGINT NOT NULL, aggregate_type VARCHAR(30) NOT NULL,
                  aggregate_id VARCHAR(100) NOT NULL, destination VARCHAR(100) NOT NULL,
                  routing_key VARCHAR(100) NOT NULL, wire_payload BLOB NOT NULL,
                  wire_payload_hash BINARY(32) NOT NULL, status VARCHAR(32) NOT NULL,
                  attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100),
                  lease_until BIGINT, active_attempt INT NOT NULL, expires_at BIGINT NOT NULL,
                  publisher_confirm_status VARCHAR(20) NOT NULL, confirmed_at BIGINT,
                  confirm_error VARCHAR(2000), mandatory_return_status VARCHAR(20) NOT NULL,
                  returned_at BIGINT, return_reply_code INT, return_reply_text VARCHAR(1000),
                  published_at BIGINT, last_error VARCHAR(2000), version BIGINT NOT NULL,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,event_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, consumer_name VARCHAR(100) NOT NULL,
                  message_id VARCHAR(100) NOT NULL, event_id VARCHAR(100) NOT NULL,
                  command_id VARCHAR(100) NOT NULL, delivery_id BIGINT NOT NULL,
                  wire_payload BLOB NOT NULL, wire_payload_hash BINARY(32) NOT NULL,
                  status VARCHAR(32) NOT NULL, result_status VARCHAR(32), attempt_count INT NOT NULL,
                  next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_attempt INT NOT NULL, expires_at BIGINT NOT NULL, processed_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT NOT NULL,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,consumer_name,message_id))
                """);
    }

    private AgentRabbitSafetyGate enabledGate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(false),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "d07-user", "d07-pass", "/d07")));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private String string(String sql) {
        return jdbc.queryForObject(sql, String.class);
    }

    private static byte[] wire(
            String tenantId, String clientId, String messageId, String commandId,
            long expiresAt) {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\","
                + "\"messageId\":\"" + messageId + "\",\"commandId\":\""
                + commandId + "\",\"tenantId\":\"" + tenantId
                + "\",\"clientId\":\"" + clientId + "\","
                + "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\","
                + "\"commandType\":\"TASK_INVITE\",\"attempt\":1,"
                + "\"expiresAt\":" + expiresAt + ",\"payload\":{}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private enum Failure { NONE, INBOX_INSERT, DELIVERY_CLAIM, DELIVERY_COMPLETE, INBOX_COMPLETE }

    private static final class FailingDao implements AgentCommandInboxDao {
        private final AgentCommandInboxDao delegate;
        private final Failure failure;

        private FailingDao(AgentCommandInboxDao delegate, Failure failure) {
            this.delegate = delegate;
            this.failure = failure;
        }

        @Override
        public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, long deliveryId) {
            return delegate.lockDelivery(tenantId, clientId, deliveryId);
        }

        @Override
        public AgentOutboxEventEntity lockOutbox(String tenantId, String clientId, String eventId) {
            return delegate.lockOutbox(tenantId, clientId, eventId);
        }

        @Override
        public AgentConsumerInboxEntity lockInbox(
                String tenantId, String clientId, String consumerName, String messageId) {
            return delegate.lockInbox(tenantId, clientId, consumerName, messageId);
        }

        @Override
        public int insertInbox(AgentConsumerInboxEntity inbox) {
            if (failure == Failure.INBOX_INSERT) throw new IllegalStateException("INBOX_INSERT_FAILURE");
            return delegate.insertInbox(inbox);
        }

        @Override
        public int updateDeliveryDisposition(
                String tenantId, String clientId, long deliveryId,
                String activeMessageId, int activeAttempt,
                String expectedStatus, long expectedVersion,
                String newStatus, Long nextRetryAt, String lastError, long now) {
            if (failure == Failure.DELIVERY_CLAIM
                    && "PUBLISHED".equals(expectedStatus) && "CONSUMED".equals(newStatus)) return 0;
            if (failure == Failure.DELIVERY_COMPLETE
                    && "CONSUMED".equals(expectedStatus)) return 0;
            return delegate.updateDeliveryDisposition(
                    tenantId, clientId, deliveryId, activeMessageId, activeAttempt,
                    expectedStatus, expectedVersion, newStatus, nextRetryAt, lastError, now);
        }

        @Override
        public int reclaimProcessingInbox(
                AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now) {
            return delegate.reclaimProcessingInbox(inbox, newLeaseOwner, newLeaseUntil, now);
        }

        @Override
        public int reclaimRetryInbox(
                AgentConsumerInboxEntity inbox, String newLeaseOwner, long newLeaseUntil, long now) {
            return delegate.reclaimRetryInbox(inbox, newLeaseOwner, newLeaseUntil, now);
        }

        @Override
        public int expireRetryInbox(
                AgentConsumerInboxEntity inbox, long processedAt, String lastError, long now) {
            return delegate.expireRetryInbox(inbox, processedAt, lastError, now);
        }

        @Override
        public int completeInbox(
                long inboxId, String tenantId, String clientId,
                String consumerName, String messageId,
                String leaseOwner, long leaseUntil, int activeAttempt, long expectedVersion,
                String newStatus, String resultStatus, Long nextRetryAt,
                long processedAt, String lastError, long now) {
            if (failure == Failure.INBOX_COMPLETE) return 0;
            return delegate.completeInbox(
                    inboxId, tenantId, clientId, consumerName, messageId,
                    leaseOwner, leaseUntil, activeAttempt, expectedVersion,
                    newStatus, resultStatus, nextRetryAt, processedAt, lastError, now);
        }
    }
}
