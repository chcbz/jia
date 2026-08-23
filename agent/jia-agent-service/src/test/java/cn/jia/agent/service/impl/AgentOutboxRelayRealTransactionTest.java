package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.dao.impl.AgentOutboxRelayDaoImpl;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.mapper.AgentOutboxRelayMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** D03 independent short-transaction, rollback, concurrency and duplicate-window evidence. */
class AgentOutboxRelayRealTransactionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long EXPIRES = NOW + 600_000L;
    private static final AgentRabbitTopologyManifest MANIFEST = AgentRabbitTopologyManifest.canonical();

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate outer;
    private AgentOutboxRelayDao productionDao;
    private JdbcTemplate mysqlAdmin;
    private String mysqlSchema;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = dataSource();
        jdbc = new JdbcTemplate(source);
        createSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentOutboxRelayMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(global);
        SqlSessionFactory factory = factoryBean.getObject();
        productionDao = new AgentOutboxRelayDaoImpl(
                new SqlSessionTemplate(factory).getMapper(AgentOutboxRelayMapper.class));
        manager = new DataSourceTransactionManager(source);
        outer = new TransactionTemplate(manager);
        insertPending();
    }


    @AfterEach
    void tearDownMySqlSchema() {
        if (mysqlAdmin != null && mysqlSchema != null) {
            mysqlAdmin.execute("DROP DATABASE IF EXISTS `" + mysqlSchema + "`");
        }
    }

    @Test
    void historicalUnadmittedPendingIsAtomicallyDeadWithoutConsumingPublishAttempt() {
        jdbc.update("UPDATE agent_outbox_event SET last_error=NULL");

        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service(productionDao).claim(candidate(), "worker-a", NOW).status());

        assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
        assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
        assertEquals(AgentOutboxRelayServiceImpl.UNADMITTED_PENDING,
                string("SELECT last_error FROM agent_outbox_event"));
        assertEquals(0, integer("SELECT attempt_count FROM agent_outbox_event"));
    }

    @Test
    void corruptRetryAckNotReturnedShapeIsAtomicallyDeadInsteadOfRepublished() {
        jdbc.update("""
                UPDATE agent_command_delivery
                SET status='RETRY', next_retry_at=?, last_error='RABBIT_NACK'
                """, NOW);
        jdbc.update("""
                UPDATE agent_outbox_event
                SET status='RETRY', attempt_count=1, active_attempt=2, next_retry_at=?,
                    publisher_confirm_status='ACK', confirmed_at=?,
                    confirm_error='RABBIT_NACK', mandatory_return_status='NOT_RETURNED',
                    last_error='RABBIT_NACK'
                """, NOW, NOW - 1);

        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service(productionDao).claim(candidate(), "worker-a", NOW).status());

        assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
        assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
        assertEquals("RETRY_SHAPE_CORRUPT",
                string("SELECT last_error FROM agent_outbox_event"));
        assertEquals(1, integer("SELECT attempt_count FROM agent_outbox_event"));
    }

    @Test
    void claimRequiresNewCommitsEvenWhenOuterCallbackRollsBack() {
        assertThrows(IllegalStateException.class, () -> outer.execute(status -> {
            assertEquals(AgentOutboxClaim.Status.ACQUIRED,
                    service(productionDao).claim(candidate(), "worker-a", NOW).status());
            throw new IllegalStateException("OUTER_CALLBACK_FAILURE");
        }));

        assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
        assertEquals(1, integer("SELECT attempt_count FROM agent_outbox_event"));
        assertEquals(2, integer("SELECT active_attempt FROM agent_outbox_event"));
        assertEquals(1, integer("SELECT attempt_count FROM agent_command_delivery"));
        assertEquals("worker-a", string("SELECT lease_owner FROM agent_command_delivery"));
    }

    @Test
    void partialClaimFailureRollsBackDeliveryAndOutboxTogether() {
        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.CLAIM_OUTBOX))
                        .claim(candidate(), "worker-a", NOW));

        assertEquals("PENDING", string("SELECT status FROM agent_outbox_event"));
        assertEquals(0, integer("SELECT attempt_count FROM agent_outbox_event"));
        assertEquals(1, integer("SELECT active_attempt FROM agent_outbox_event"));
        assertEquals(null, stringOrNull("SELECT lease_owner FROM agent_command_delivery"));
        assertEquals(0L, longValue("SELECT version FROM agent_command_delivery"));
    }

    @Test
    void confirmSettlementUpdatesBothRowsAtomicallyAndPartialFailureRollsBackBoth() {
        AgentOutboxClaimToken token = service(productionDao)
                .claim(candidate(), "worker-a", NOW).token();
        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.SETTLE_OUTBOX))
                        .settle(token, AgentRabbitPublishResult.ack(), NOW + 1));
        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
        assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
        assertEquals("worker-a", string("SELECT lease_owner FROM agent_command_delivery"));

        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                service(productionDao).settle(token, AgentRabbitPublishResult.ack(), NOW + 2));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
        assertEquals("ACK", string("SELECT publisher_confirm_status FROM agent_outbox_event"));
        assertEquals("NOT_RETURNED", string("SELECT mandatory_return_status FROM agent_outbox_event"));
    }

    @Test
    void concurrentClaimHasExactlyOneWinnerAndDoesNotDeadLetterFreshLease() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentOutboxClaim.Status> first = executor.submit(() -> {
                start.await(); return service(productionDao).claim(candidate(), "worker-a", NOW).status();
            });
            Future<AgentOutboxClaim.Status> second = executor.submit(() -> {
                start.await(); return service(productionDao).claim(candidate(), "worker-b", NOW).status();
            });
            start.countDown();
            List<AgentOutboxClaim.Status> statuses = List.of(
                    first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, statuses.stream().filter(s -> s == AgentOutboxClaim.Status.ACQUIRED).count());
            assertEquals(1, statuses.stream().filter(s -> s == AgentOutboxClaim.Status.SKIPPED).count());
        }
        assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
        assertEquals(1, integer("SELECT attempt_count FROM agent_outbox_event"));
    }

    @Test
    void staleLeaseRepublishesByteExactIdentityAndOldCallbackIsStale() {
        AgentOutboxClaimToken first = service(productionDao)
                .claim(candidate(), "worker-a", NOW).token();
        jdbc.update("UPDATE agent_command_delivery SET lease_until=?", NOW - 1);
        jdbc.update("UPDATE agent_outbox_event SET lease_until=?", NOW - 1);

        AgentOutboxClaimToken second = service(productionDao)
                .claim(candidate(), "worker-b", NOW + 1).token();

        assertEquals(first.messageId(), second.messageId());
        assertEquals(first.commandId(), second.commandId());
        assertEquals(first.eventId(), second.eventId());
        assertEquals(first.deliveryActiveAttempt(), second.deliveryActiveAttempt());
        assertArrayEquals(first.wirePayload(), second.wirePayload());
        assertArrayEquals(first.wirePayloadHash(), second.wirePayloadHash());
        assertEquals(first.publishAttempt() + 1, second.publishAttempt());
        assertEquals(AgentOutboxSettleResult.STALE,
                service(productionDao).settle(first, AgentRabbitPublishResult.ack(), NOW + 2));
        assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
        assertEquals("worker-b", string("SELECT lease_owner FROM agent_outbox_event"));
    }

    private DriverManagerDataSource dataSource() {
        String mysqlUrl = System.getenv("D03_MYSQL_URL");
        DriverManagerDataSource source = new DriverManagerDataSource();
        if (mysqlUrl == null || mysqlUrl.isBlank()) {
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:d03_tx_" + System.nanoTime()
                    + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }
        String user = requiredEnv("D03_MYSQL_USER");
        String password = requiredEnv("D03_MYSQL_PASSWORD");
        DriverManagerDataSource admin = new DriverManagerDataSource();
        admin.setDriverClassName("com.mysql.cj.jdbc.Driver");
        admin.setUrl(mysqlUrl);
        admin.setUsername(user);
        admin.setPassword(password);
        mysqlAdmin = new JdbcTemplate(admin);
        String version = mysqlAdmin.queryForObject("SELECT VERSION()", String.class);
        assertEquals(true, version != null && version.startsWith("8.0.21"), version);
        assertEquals(Integer.parseInt(requiredEnv("D03_MYSQL_EXPECTED_PORT")),
                mysqlAdmin.queryForObject("SELECT @@port", Integer.class));
        String datadir = mysqlAdmin.queryForObject("SELECT @@datadir", String.class);
        assertEquals(true, datadir != null
                && datadir.startsWith(requiredEnv("D03_MYSQL_EXPECTED_DATADIR")), datadir);
        mysqlSchema = "d03_relay_" + UUID.randomUUID().toString().replace("-", "");
        mysqlAdmin.execute("CREATE DATABASE `" + mysqlSchema
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(withSchema(mysqlUrl, mysqlSchema));
        source.setUsername(user);
        source.setPassword(password);
        return source;
    }

    private static String withSchema(String url, String schema) {
        int query = url.indexOf('?');
        String prefix = query < 0 ? url : url.substring(0, query);
        String suffix = query < 0 ? "" : url.substring(query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0) throw new IllegalStateException("D03 MySQL URL must include a path");
        return prefix.substring(0, slash + 1) + schema + suffix;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private AgentOutboxRelayServiceImpl service(AgentOutboxRelayDao dao) {
        return new AgentOutboxRelayServiceImpl(
                dao, gate(), MANIFEST, ready(),
                new AgentOutboxRelaySettings(new AgentRabbitSafetyProperties.RabbitPublish(true)),
                manager);
    }

    private void insertPending() {
        byte[] business = "business".getBytes(StandardCharsets.UTF_8);
        byte[] wire = wire();
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,command_id,task_id,target_agent_id,command_type,
                  command_payload,command_payload_hash,status,attempt_count,next_retry_at,
                  lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
                  version,tenant_id,client_id,create_time,update_time)
                VALUES (41,'cmd-1','task-1','agent-1','task.invite',?,?,?,?,NULL,
                        NULL,NULL,'msg-1',1,?,?,0,'tenant-a','client-a',?,?)
                """, business, AgentCommandAmqpContract.sha256(business), "PENDING", 1,
                EXPIRES, AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER, NOW, NOW));
        var route = MANIFEST.defaultCommandPublishRoute();
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_outbox_event(
                  id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                  destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                  next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                  publisher_confirm_status,mandatory_return_status,last_error,version,
                  tenant_id,client_id,create_time,update_time)
                VALUES (1,'evt-1','msg-1','cmd-1',41,'task','task-1',?,?,?,?,
                        'PENDING',0,NULL,NULL,NULL,1,?,'NONE','NONE',?,0,
                        'tenant-a','client-a',?,?)
                """, route.destination(), route.routingKey(), wire,
                AgentCommandAmqpContract.sha256(wire), EXPIRES,
                AgentCommandTransportWriterImpl.DISPATCH_ELIGIBLE_MARKER, NOW, NOW));
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, command_id VARCHAR(100) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100),
                  target_agent_id VARCHAR(100) NOT NULL, command_type VARCHAR(64) NOT NULL,
                  command_payload BLOB NOT NULL, command_payload_hash BINARY(32) NOT NULL,
                  status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL,
                  next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_message_id VARCHAR(100), active_attempt INT NOT NULL,
                  expires_at BIGINT NOT NULL, last_error VARCHAR(2000), version BIGINT NOT NULL,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT)
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
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT, update_time BIGINT)
                """);
    }

    private static byte[] wire() {
        return ("{\"schemaVersion\":1,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-1\",\"commandId\":\"cmd-1\"," +
                "\"tenantId\":\"tenant-a\",\"clientId\":\"client-a\"," +
                "\"taskId\":\"task-1\",\"targetAgentId\":\"agent-1\"," +
                "\"commandType\":\"task.invite\",\"attempt\":1," +
                "\"expiresAt\":" + EXPIRES + ",\"payload\":{}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static AgentOutboxCandidate candidate() {
        return new AgentOutboxCandidate(1, "tenant-a", "client-a", 41, NOW);
    }

    private static AgentRabbitSafetyGate gate() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated"));
        return new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope("tenant-a", "client-a"))));
    }

    private static AgentRabbitTopologyReadiness ready() {
        try {
            AgentRabbitTopologyReadiness readiness =
                    new AgentRabbitTopologyConfiguration().agentRabbitTopologyReadiness(MANIFEST);
            Method method = AgentRabbitTopologyReadiness.class.getDeclaredMethod("markProvisioned");
            method.setAccessible(true); method.invoke(readiness);
            return readiness;
        } catch (ReflectiveOperationException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private String string(String sql) { return jdbc.queryForObject(sql, String.class); }
    private String stringOrNull(String sql) { return jdbc.queryForObject(sql, String.class); }
    private int integer(String sql) { return jdbc.queryForObject(sql, Integer.class); }
    private long longValue(String sql) { return jdbc.queryForObject(sql, Long.class); }

    private enum Failure { CLAIM_OUTBOX, SETTLE_OUTBOX }

    private static final class FailingDao implements AgentOutboxRelayDao {
        private final AgentOutboxRelayDao delegate;
        private final Failure failure;
        private FailingDao(AgentOutboxRelayDao delegate, Failure failure) {
            this.delegate = delegate; this.failure = failure;
        }
        @Override public List<AgentOutboxCandidate> selectDueCandidates(long now, int limit) {
            return delegate.selectDueCandidates(now, limit);
        }
        @Override public List<AgentOutboxCandidate> selectStaleCandidates(long now, int limit) {
            return delegate.selectStaleCandidates(now, limit);
        }
        @Override public AgentCommandDeliveryEntity lockDelivery(String tenant, String client, long id) {
            return delegate.lockDelivery(tenant, client, id);
        }
        @Override public AgentOutboxEventEntity lockOutbox(String tenant, String client, long id) {
            return delegate.lockOutbox(tenant, client, id);
        }
        @Override public int claimDelivery(AgentCommandDeliveryEntity row, String owner,
                long leaseUntil, String error, long now) {
            return delegate.claimDelivery(row, owner, leaseUntil, error, now);
        }
        @Override public int claimOutbox(AgentOutboxEventEntity row, String owner,
                long leaseUntil, String error, long now) {
            return failure == Failure.CLAIM_OUTBOX ? 0
                    : delegate.claimOutbox(row, owner, leaseUntil, error, now);
        }
        @Override public int disposeDelivery(AgentCommandDeliveryEntity row, String status,
                Long retry, String error, long now) {
            return delegate.disposeDelivery(row, status, retry, error, now);
        }
        @Override public int disposeOutbox(AgentOutboxEventEntity row, String status,
                Long retry, String confirm, Long confirmedAt, String confirmError,
                String returned, Long returnedAt, Integer replyCode, String replyText,
                Long publishedAt, String error, long now) {
            return failure == Failure.SETTLE_OUTBOX ? 0 : delegate.disposeOutbox(row, status,
                    retry, confirm, confirmedAt, confirmError, returned, returnedAt,
                    replyCode, replyText, publishedAt, error, now);
        }
    }
}
