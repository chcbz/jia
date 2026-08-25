package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentCommandTransportSchemaInitializer;
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
import cn.jia.agent.entity.AgentInboxSourceNotSettledException;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.mapper.AgentCommandInboxMapper;
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

import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D07 exact-schema transaction, identity, binary-scope and concurrency matrix on isolated MySQL 8.0.21. */
@EnabledIfEnvironmentVariable(named = "D07_MYSQL_URL", matches = ".+")
class AgentCommandInboxMySqlTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final byte[] WIRE = wire(
            "tenant-a", "client-a", "msg-1", "cmd-1", NOW + 60_000);

    private String databaseName;
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private AgentCommandInboxDao productionDao;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("D07_MYSQL_URL");
        String username = requiredEnvironment("D07_MYSQL_USER");
        String password = requiredEnvironment("D07_MYSQL_PASSWORD");
        int expectedPort = Integer.parseInt(requiredEnvironment("D07_MYSQL_EXPECTED_PORT"));
        String expectedDatadir = requiredEnvironment("D07_MYSQL_EXPECTED_DATADIR");
        assertTrue(expectedPort != 3306, "D07 runner must never use production MySQL port 3306");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:" + expectedPort + "/"), baseUrl);

        DriverManagerDataSource adminSource = dataSource(baseUrl, username, password);
        admin = new JdbcTemplate(adminSource);
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        Integer port = admin.queryForObject("SELECT @@port", Integer.class);
        String datadir = admin.queryForObject("SELECT @@datadir", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        assertEquals(expectedPort, port);
        assertTrue(datadir != null && datadir.startsWith(expectedDatadir + "/"), datadir);
        System.out.println("D07_MYSQL_VERSION=" + version);
        System.out.println("D07_MYSQL_PORT=" + port);
        System.out.println("D07_MYSQL_DATADIR=" + datadir);

        databaseName = "d07_inbox_" + Long.toUnsignedString(System.nanoTime());
        admin.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(source);
        new AgentCommandTransportSchemaInitializer(jdbc).afterPropertiesSet();

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
        insertPublishedSource("tenant-a", "client-a", 1L,
                "msg-1", "evt-1", "cmd-1", NOW + 60_000);
    }

    @AfterEach
    void tearDown() {
        if (admin != null && databaseName != null) {
            admin.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void successClaimUsesExactSchemaAndCommitsBothFacts() {
        jdbc.update("UPDATE agent_outbox_event SET active_attempt=7 WHERE event_id='evt-1'");
        AgentInboxClaim claim = service(productionDao).claim(message(), "worker-a", NOW, 10_000);

        assertEquals(AgentInboxClaim.Kind.ACQUIRED, claim.kind());
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
        assertArrayEquals(WIRE, jdbc.queryForObject(
                "SELECT wire_payload FROM agent_consumer_inbox", byte[].class));
    }

    @Test
    void completeCommitsFencedDispositionToBothTables() {
        AgentInboxClaimToken token = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000).token();
        jdbc.update("UPDATE agent_outbox_event SET active_attempt=9 WHERE event_id='evt-1'");

        service(productionDao).complete(token, AgentInboxDisposition.sent(), NOW + 1);

        assertEquals("PROCESSED", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("SENT", string("SELECT result_status FROM agent_consumer_inbox"));
        assertEquals("SENT", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void inboxInsertFailureRollsBackWithoutDeliveryMutation() {
        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.INBOX_INSERT))
                        .claim(message(), "worker-a", NOW, 10_000));

        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void deliveryClaimFailureRollsBackInsertedInbox() {
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(new FailingDao(productionDao, Failure.DELIVERY_CLAIM))
                        .claim(message(), "worker-a", NOW, 10_000));

        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void completionFailureOnEitherSideRollsBackTheOtherSide() {
        AgentInboxClaimToken token = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000).token();
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(new FailingDao(productionDao, Failure.DELIVERY_COMPLETE))
                        .complete(token, AgentInboxDisposition.sent(), NOW + 1));
        assertProcessingConsumed();

        assertThrows(AgentInboxFenceException.class,
                () -> service(new FailingDao(productionDao, Failure.INBOX_COMPLETE))
                        .complete(token, AgentInboxDisposition.sent(), NOW + 1));
        assertProcessingConsumed();
    }

    @Test
    void exactDuplicateReturnsPriorResultWithoutSecondSideEffect() {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken token = service.claim(message(), "worker-a", NOW, 10_000).token();
        service.complete(token, AgentInboxDisposition.sent(), NOW + 1);
        jdbc.update("UPDATE agent_command_delivery SET status='FAILED' WHERE id=1");

        AgentInboxClaim duplicate = service.claim(message(), "worker-b", NOW + 2, 10_000);

        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, duplicate.kind());
        assertEquals("SENT", duplicate.priorResult().resultStatus());
        assertEquals(1, count("agent_consumer_inbox"));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT attempt_count FROM agent_command_delivery", Long.class));
        assertEquals("FAILED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void conflictAndStoredCorruptionPreserveOriginalRows() {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxMessage changed = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1,
                "changed".getBytes());
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(changed, "worker-a", NOW, 10_000));
        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
        assertArrayEquals(WIRE, jdbc.queryForObject(
                "SELECT wire_payload FROM agent_outbox_event", byte[].class));

        jdbc.update("UPDATE agent_outbox_event SET wire_payload_hash=? WHERE event_id='evt-1'", new byte[32]);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-a", NOW, 10_000));
        assertEquals(0, count("agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void productionMapperMaterializesEveryClaimedPoisonColumnBeforeTransientGate() {
        resetPublishedSource();
        makeCanonicalClaimedSource();
        assertThrows(AgentInboxSourceNotSettledException.class,
                () -> service(productionDao).claim(message(), "worker-a", NOW, 10_000));

        List<SourcePoison> poisons = List.of(
                new SourcePoison("agent_outbox_event", "confirmed_at=1700000000000"),
                new SourcePoison("agent_outbox_event", "confirm_error='RABBIT_NACK'"),
                new SourcePoison("agent_outbox_event", "returned_at=1700000000000"),
                new SourcePoison("agent_outbox_event", "return_reply_code=312"),
                new SourcePoison("agent_outbox_event", "return_reply_text='NO_ROUTE'"),
                new SourcePoison("agent_outbox_event", "published_at=1700000000000"),
                new SourcePoison("agent_outbox_event", "replay_parent_message_id='msg-parent'"),
                new SourcePoison("agent_outbox_event", "replay_requester_id='requester-1'"),
                new SourcePoison("agent_outbox_event", "replay_approver_id='approver-1'"),
                new SourcePoison("agent_outbox_event", "replay_reason='manual replay'"),
                new SourcePoison("agent_command_delivery", "replay_parent_message_id='msg-parent'"),
                new SourcePoison("agent_command_delivery", "replay_requester_id='requester-1'"),
                new SourcePoison("agent_command_delivery", "replay_approver_id='approver-1'"),
                new SourcePoison("agent_command_delivery", "replay_reason='manual replay'"));
        for (SourcePoison poison : poisons) {
            resetPublishedSource();
            makeCanonicalClaimedSource();
            assertEquals(1, jdbc.update(
                    "UPDATE " + poison.table() + " SET " + poison.assignment()));

            assertThrows(AgentInboxIdentityConflictException.class,
                    () -> service(productionDao)
                            .claim(message(), "worker-a", NOW, 10_000),
                    poison.table() + ": " + poison.assignment());
            assertEquals(0, count("agent_consumer_inbox"), poison.assignment());
        }
    }

    @Test
    void concurrentFirstClaimHasSingleWinner() throws Exception {
        List<AgentInboxClaim.Kind> results = concurrentClaims(NOW, "worker-a", "worker-b");

        assertEquals(1, results.stream().filter(
                kind -> kind == AgentInboxClaim.Kind.ACQUIRED).count());
        assertEquals(1, results.stream().filter(
                kind -> kind == AgentInboxClaim.Kind.IN_FLIGHT).count());
        assertEquals(1, count("agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void leaseReclaimHasSingleWinnerAndStaleTokenFailsClosed() throws Exception {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken oldToken = service.claim(message(), "worker-a", NOW, 1_000).token();

        List<AgentInboxClaim.Kind> results = concurrentClaims(
                NOW + 1_000, "worker-b", "worker-c");

        assertEquals(1, results.stream().filter(
                kind -> kind == AgentInboxClaim.Kind.ACQUIRED).count());
        assertEquals(1, results.stream().filter(
                kind -> kind == AgentInboxClaim.Kind.IN_FLIGHT).count());
        assertThrows(AgentInboxFenceException.class,
                () -> service.complete(oldToken, AgentInboxDisposition.sent(), NOW + 1_001));
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM agent_consumer_inbox", Integer.class));
    }

    @Test
    void expiredBoundaryAndStaleMessageFenceDoNotAcquireOrRegressDelivery() {
        byte[] expiredWire = wire("tenant-a", "client-a", "msg-1", "cmd-1", NOW);
        jdbc.update("UPDATE agent_command_delivery SET expires_at=? WHERE id=1", NOW);
        jdbc.update("UPDATE agent_outbox_event SET expires_at=?, wire_payload=?, "
                        + "wire_payload_hash=? WHERE event_id='evt-1'",
                NOW, expiredWire, sha256(expiredWire));
        AgentInboxClaim expired = service(productionDao)
                .claim(message(expiredWire), "worker-a", NOW, 10_000);
        assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT, expired.kind());
        assertEquals("EXPIRED", expired.priorResult().status());
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery"));

        resetPublishedSource();
        jdbc.update("UPDATE agent_command_delivery SET active_message_id='msg-new' WHERE id=1");
        AgentInboxClaim stale = service(productionDao)
                .claim(message(), "worker-a", NOW, 10_000);
        assertEquals("DEAD", stale.priorResult().status());
        assertEquals(AgentCommandInboxServiceImpl.STALE_MESSAGE_FENCE,
                stale.priorResult().lastError());
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
        assertEquals("msg-new", string("SELECT active_message_id FROM agent_command_delivery"));

        jdbc.update("UPDATE agent_command_delivery SET active_message_id='msg-1' WHERE id=1");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(productionDao).claim(message(), "worker-b", NOW + 1, 10_000));
        assertEquals(1, count("agent_consumer_inbox"));
        assertEquals("DEAD", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void expiredCompletionBoundaryAndTerminalShapeCorruptionFailClosed() {
        AgentCommandInboxServiceImpl service = service(productionDao);
        AgentInboxClaimToken token = service.claim(message(), "worker-a", NOW, 60_000).token();
        AgentInboxDisposition expired = new AgentInboxDisposition(
                AgentInboxDisposition.Type.EXPIRED, null, "MESSAGE_EXPIRED");

        assertThrows(IllegalArgumentException.class,
                () -> service.complete(token, expired, NOW + 1));
        assertProcessingConsumed();
        service.complete(token, expired, NOW + 60_000);
        assertEquals("EXPIRED", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery"));
        jdbc.update("UPDATE agent_consumer_inbox SET processed_at=? WHERE message_id='msg-1'",
                NOW + 59_999);
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 60_001, 10_000));
        assertEquals("EXPIRED", string("SELECT status FROM agent_command_delivery"));

        resetPublishedSource();
        AgentInboxClaimToken sentToken = service.claim(
                message(), "worker-a", NOW, 10_000).token();
        service.complete(sentToken, AgentInboxDisposition.sent(), NOW + 1);
        jdbc.update("UPDATE agent_consumer_inbox SET last_error='CORRUPT_SENT' "
                + "WHERE message_id='msg-1'");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 2, 10_000));
        assertEquals("SENT", string("SELECT status FROM agent_command_delivery"));

        resetPublishedSource();
        sentToken = service.claim(message(), "worker-a", NOW, 10_000).token();
        service.complete(sentToken, AgentInboxDisposition.sent(), NOW + 1);
        jdbc.update("UPDATE agent_consumer_inbox SET processed_at=NULL WHERE message_id='msg-1'");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 2, 10_000));
        assertEquals("SENT", string("SELECT status FROM agent_command_delivery"));

        resetPublishedSource();
        AgentInboxClaimToken failedToken = service.claim(
                message(), "worker-a", NOW, 10_000).token();
        service.complete(failedToken, new AgentInboxDisposition(
                AgentInboxDisposition.Type.FAILED, null, "WS_FAILED"), NOW + 1);
        jdbc.update("UPDATE agent_consumer_inbox SET last_error=NULL WHERE message_id='msg-1'");
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service.claim(message(), "worker-b", NOW + 2, 10_000));
        assertEquals("FAILED", string("SELECT status FROM agent_command_delivery"));
    }

    @Test
    void binaryScopeRejectsCaseAndUnicodeNormalizationDrift() {
        resetAll();
        String composed = "tenant-\u00e9";
        String decomposed = "tenant-e\u0301";
        insertPublishedSource(composed, "Client-A", 1L,
                "msg-1", "evt-1", "cmd-1", NOW + 60_000);
        insertPublishedSource(decomposed, "Client-A", 2L,
                "msg-2", "evt-2", "cmd-2", NOW + 60_000);
        AgentInboxMessage exact = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                composed, "Client-A", "msg-1", "evt-1", "cmd-1", 1,
                wire(composed, "Client-A", "msg-1", "cmd-1", NOW + 60_000));
        service(productionDao).claim(exact, "worker-a", NOW, 10_000);
        assertEquals("CONSUMED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=1", String.class));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=2", String.class));

        AgentInboxMessage wrongCase = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                composed, "client-a", "msg-1", "evt-1", "cmd-1", 1,
                wire(composed, "Client-A", "msg-1", "cmd-1", NOW + 60_000));
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(productionDao).claim(wrongCase, "worker-b", NOW + 1, 10_000));
        AgentInboxMessage wrongNormalization = new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                decomposed, "Client-A", "msg-1", "evt-1", "cmd-1", 1,
                wire(composed, "Client-A", "msg-1", "cmd-1", NOW + 60_000));
        assertThrows(AgentInboxIdentityConflictException.class,
                () -> service(productionDao).claim(
                        wrongNormalization, "worker-b", NOW + 1, 10_000));
        assertEquals(1, count("agent_consumer_inbox"));
    }

    private List<AgentInboxClaim.Kind> concurrentClaims(
            long now, String firstOwner, String secondOwner) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentInboxClaim.Kind> first = executor.submit(
                    () -> concurrentClaim(ready, start, firstOwner, now));
            Future<AgentInboxClaim.Kind> second = executor.submit(
                    () -> concurrentClaim(ready, start, secondOwner, now));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
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
        return message(WIRE);
    }

    private AgentInboxMessage message(byte[] wire) {
        return new AgentInboxMessage(
                AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1,
                "tenant-a", "client-a", "msg-1", "evt-1", "cmd-1", 1, wire);
    }

    private void assertProcessingConsumed() {
        assertEquals("PROCESSING", string("SELECT status FROM agent_consumer_inbox"));
        assertEquals("CONSUMED", string("SELECT status FROM agent_command_delivery"));
    }

    private void resetPublishedSource() {
        resetAll();
        insertPublishedSource("tenant-a", "client-a", 1L,
                "msg-1", "evt-1", "cmd-1", NOW + 60_000);
    }

    private void resetAll() {
        jdbc.update("DELETE FROM agent_consumer_inbox");
        jdbc.update("DELETE FROM agent_outbox_event");
        jdbc.update("DELETE FROM agent_command_delivery");
    }

    private void insertPublishedSource(
            String tenant, String client, long deliveryId,
            String messageId, String eventId, String commandId, long expiresAt) {
        byte[] command = ("business-" + commandId).getBytes();
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
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, deliveryId + 1_000, eventId, messageId, commandId, deliveryId,
                "task", "task-1", "jia.agent.command", "agent.command.general",
                sourceWire, sha256(sourceWire), "PUBLISHED", 1, 1, expiresAt,
                "ACK", NOW, "NOT_RETURNED", NOW, 0, tenant, client, NOW, NOW));
    }

    private void makeCanonicalClaimedSource() {
        assertEquals(1, jdbc.update("""
                UPDATE agent_command_delivery
                SET status='PENDING', attempt_count=1, next_retry_at=NULL,
                    lease_owner='d03-relay', lease_until=?, active_attempt=1,
                    last_error=NULL, version=1,
                    replay_parent_message_id=NULL, replay_requester_id=NULL,
                    replay_approver_id=NULL, replay_reason=NULL
                """, NOW + 30_000));
        assertEquals(1, jdbc.update("""
                UPDATE agent_outbox_event
                SET status='CLAIMED', attempt_count=1, next_retry_at=NULL,
                    lease_owner='d03-relay', lease_until=?, active_attempt=1,
                    publisher_confirm_status='PENDING', confirmed_at=NULL, confirm_error=NULL,
                    mandatory_return_status='PENDING', returned_at=NULL,
                    return_reply_code=NULL, return_reply_text=NULL, published_at=NULL,
                    last_error=NULL, version=1,
                    replay_parent_message_id=NULL, replay_requester_id=NULL,
                    replay_approver_id=NULL, replay_reason=NULL
                """, NOW + 30_000));
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

    private DriverManagerDataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String prefix = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
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

    private record SourcePoison(String table, String assignment) {
    }

    private enum Failure { INBOX_INSERT, DELIVERY_CLAIM, DELIVERY_COMPLETE, INBOX_COMPLETE }

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
