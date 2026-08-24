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
    void maxMinusTwoDoubleClaimHasOneWinnerAndLoserCannotQuarantineFreshLease()
            throws Exception {
        for (VersionMax max : VersionMax.values()) {
            resetPendingRows();
            long initialDeliveryVersion = 0L;
            long initialOutboxVersion = 0L;
            if (max != VersionMax.OUTBOX_ONLY) {
                initialDeliveryVersion = Long.MAX_VALUE - 2;
                jdbc.update("UPDATE agent_command_delivery SET version=?",
                        initialDeliveryVersion);
            }
            if (max != VersionMax.DELIVERY_ONLY) {
                initialOutboxVersion = Long.MAX_VALUE - 2;
                jdbc.update("UPDATE agent_outbox_event SET version=?", initialOutboxVersion);
            }
            AgentOutboxCandidate oldHint = new AgentOutboxCandidate(
                    1, "tenant-a", "client-a", 41, NOW,
                    initialOutboxVersion, "PENDING");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            List<AgentOutboxClaim> claims;
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<AgentOutboxClaim> first = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service(productionDao).claim(oldHint, "worker-a", NOW);
                });
                Future<AgentOutboxClaim> second = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return service(productionDao).claim(oldHint, "worker-b", NOW);
                });
                assertEquals(true, ready.await(2, TimeUnit.SECONDS), max.name());
                start.countDown();
                claims = List.of(first.get(5, TimeUnit.SECONDS),
                        second.get(5, TimeUnit.SECONDS));
            }

            assertEquals(1, claims.stream()
                    .filter(claim -> claim.status() == AgentOutboxClaim.Status.ACQUIRED)
                    .count(), max.name());
            assertEquals(1, claims.stream()
                    .filter(claim -> claim.status() == AgentOutboxClaim.Status.SKIPPED)
                    .count(), max.name());
            AgentOutboxClaim winner = claims.stream()
                    .filter(claim -> claim.status() == AgentOutboxClaim.Status.ACQUIRED)
                    .findFirst().orElseThrow();
            long claimedDeliveryVersion = max == VersionMax.OUTBOX_ONLY
                    ? 1L : Long.MAX_VALUE - 1;
            long claimedOutboxVersion = max == VersionMax.DELIVERY_ONLY
                    ? 1L : Long.MAX_VALUE - 1;
            assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
            assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
            assertEquals(claimedDeliveryVersion,
                    longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(claimedOutboxVersion,
                    longValue("SELECT version FROM agent_outbox_event"));
            assertEquals(null, stringOrNull("SELECT last_error FROM agent_command_delivery"));
            assertEquals(null, stringOrNull("SELECT last_error FROM agent_outbox_event"));
            assertEquals(1, integer("SELECT attempt_count FROM agent_outbox_event"));

            assertEquals(AgentOutboxSettleResult.PUBLISHED,
                    service(productionDao).settle(
                            winner.token(), AgentRabbitPublishResult.ack(), NOW + 1),
                    max.name());
            assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
            assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
            assertEquals(max == VersionMax.OUTBOX_ONLY ? 2L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(max == VersionMax.DELIVERY_ONLY ? 2L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_outbox_event"));
            assertEquals("ACK",
                    string("SELECT publisher_confirm_status FROM agent_outbox_event"));
            assertEquals("NOT_RETURNED",
                    string("SELECT mandatory_return_status FROM agent_outbox_event"));
        }
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


    @Test
    void ackAtAndAfterExpiryPublishesActiveDeliveryAndOutboxAtomically() {
        for (long settledAt : new long[] {EXPIRES, EXPIRES + 1}) {
            resetPendingRows();
            AgentOutboxClaimToken token = service(productionDao)
                    .claim(candidate(), "worker-a", NOW).token();

            assertEquals(AgentOutboxSettleResult.PUBLISHED,
                    service(productionDao).settle(
                            token, AgentRabbitPublishResult.ack(), settledAt));
            assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
            assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
            assertEquals("ACK", string("SELECT publisher_confirm_status FROM agent_outbox_event"));
            assertEquals("NOT_RETURNED",
                    string("SELECT mandatory_return_status FROM agent_outbox_event"));
            assertEquals(settledAt, longValue("SELECT published_at FROM agent_outbox_event"));
        }
    }

    @Test
    void ackAtAndAfterExpiryPublishesOldOutboxWithoutRegressingStaleDelivery() {
        for (long settledAt : new long[] {EXPIRES, EXPIRES + 1}) {
            resetPendingRows();
            AgentOutboxClaimToken token = service(productionDao)
                    .claim(candidate(), "worker-a", NOW).token();
            jdbc.update("""
                    UPDATE agent_command_delivery
                    SET active_message_id='msg-new', active_attempt=2, version=2,
                        lease_owner=NULL, lease_until=NULL
                    """);

            assertEquals(AgentOutboxSettleResult.PUBLISHED,
                    service(productionDao).settle(
                            token, AgentRabbitPublishResult.ack(), settledAt));
            assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
            assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
            assertEquals("msg-new", string("SELECT active_message_id FROM agent_command_delivery"));
            assertEquals(2L, longValue("SELECT version FROM agent_command_delivery"));
        }
    }

    @Test
    void poisonLaneQuarantinesMalformedHeadsAndStillReturnsLegalCandidate() {
        jdbc.update("UPDATE agent_outbox_event SET id=10");
        insertOutboxCopy(1, 0, "tenant-a", "client-a");
        insertOutboxCopy(2, 41, "", "client-a");
        insertOutboxCopy(3, 41, "tenant-" + Character.toString(1), "client-a");
        insertOutboxCopy(4, 404, "tenant-a", "client-a");
        insertOutboxCopy(5, -1, "tenant-a", "client-a");
        insertOutboxCopy(6, 405, "tenant-a", "client-a");

        List<AgentOutboxCandidate> found = service(productionDao).discover(NOW, 1);
        assertEquals(List.of(10L), found.stream()
                .map(AgentOutboxCandidate::outboxId).toList());
        assertEquals(AgentOutboxClaim.Status.ACQUIRED,
                service(productionDao).claim(found.get(0), "worker-a", NOW).status());

        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_ID_INVALID,
                string("SELECT last_error FROM agent_outbox_event WHERE id=1"));
        assertEquals(1, integer("SELECT COUNT(*) FROM agent_outbox_event "
                + "WHERE id<10 AND status='DEAD'"));
        assertEquals(5, integer("SELECT COUNT(*) FROM agent_outbox_event "
                + "WHERE id<10 AND status='PENDING'"));

        // The corruption lane is bounded to the requested row count, yet all remaining
        // poison rows are excluded from normal discovery and cannot starve legal id=10.
        for (int poll = 0; poll < 5; poll++) {
            assertEquals(List.of(), service(productionDao).discover(NOW, 1));
        }
        assertEquals(6, integer("SELECT COUNT(*) FROM agent_outbox_event "
                + "WHERE id<10 AND status='DEAD'"));
        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_SCOPE_INVALID,
                string("SELECT last_error FROM agent_outbox_event WHERE id=2"));
        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_SCOPE_INVALID,
                string("SELECT last_error FROM agent_outbox_event WHERE id=3"));
        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_NOT_FOUND,
                string("SELECT last_error FROM agent_outbox_event WHERE id=4"));
        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_ID_INVALID,
                string("SELECT last_error FROM agent_outbox_event WHERE id=5"));
        assertEquals(AgentOutboxRelayServiceImpl.DISCOVERY_DELIVERY_NOT_FOUND,
                string("SELECT last_error FROM agent_outbox_event WHERE id=6"));
        List<Long> versions = jdbc.queryForList(
                "SELECT version FROM agent_outbox_event WHERE id<10 ORDER BY id", Long.class);

        assertEquals(List.of(), service(productionDao).discover(NOW, 1));
        assertEquals(versions, jdbc.queryForList(
                "SELECT version FROM agent_outbox_event WHERE id<10 ORDER BY id", Long.class));
        assertEquals(0, productionDao.selectCorruptCandidates(NOW, 4).size());
    }

    @Test
    void claimCasRejectsMaxMinusOneWithoutMutation() {
        jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE - 1);
        jdbc.update("UPDATE agent_outbox_event SET version=?", Long.MAX_VALUE - 1);
        AgentCommandDeliveryEntity delivery = productionDao.lockDelivery(
                "tenant-a", "client-a", 41);
        AgentOutboxEventEntity outbox = productionDao.lockOutbox(
                "tenant-a", "client-a", 1);

        assertEquals(0, productionDao.claimDelivery(
                delivery, "worker-a", NOW + 30_000, null, NOW));
        assertEquals(0, productionDao.claimOutbox(
                outbox, "worker-a", NOW + 30_000, null, NOW));

        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
        assertEquals("PENDING", string("SELECT status FROM agent_outbox_event"));
        assertEquals(null, stringOrNull("SELECT lease_owner FROM agent_command_delivery"));
        assertEquals(null, stringOrNull("SELECT lease_owner FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE - 1,
                longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(Long.MAX_VALUE - 1,
                longValue("SELECT version FROM agent_outbox_event"));
        assertEquals(0, integer("SELECT attempt_count FROM agent_outbox_event"));
    }

    @Test
    void claimQuarantinesMaxMinusOneBeforePublishAndNeverWraps() {
        for (VersionMax max : VersionMax.values()) {
            resetPendingRows();
            if (max != VersionMax.OUTBOX_ONLY) {
                jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE - 1);
            }
            if (max != VersionMax.DELIVERY_ONLY) {
                jdbc.update("UPDATE agent_outbox_event SET version=?", Long.MAX_VALUE - 1);
            }

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service(productionDao).claim(candidate(), "worker-a", NOW).status(),
                    max.name());
            assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
            assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
            assertEquals(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED,
                    string("SELECT last_error FROM agent_outbox_event"));
            assertEquals(max == VersionMax.OUTBOX_ONLY ? 1L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(max == VersionMax.DELIVERY_ONLY ? 1L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_outbox_event"));
            assertEquals(List.of(), service(productionDao).discover(NOW, 1));
            assertEquals(0, productionDao.selectCorruptCandidates(NOW, 4).size());
        }
    }

    @Test
    void maxMinusTwoClaimAndAckConsumeReservedIncrementsExactlyToMax() {
        jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE - 2);
        jdbc.update("UPDATE agent_outbox_event SET version=?", Long.MAX_VALUE - 2);

        AgentOutboxClaimToken token = service(productionDao)
                .claim(candidate(), "worker-a", NOW).token();

        assertEquals(Long.MAX_VALUE - 1, token.deliveryVersion());
        assertEquals(Long.MAX_VALUE - 1, token.outboxVersion());
        assertEquals(Long.MAX_VALUE - 1,
                longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(Long.MAX_VALUE - 1,
                longValue("SELECT version FROM agent_outbox_event"));
        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                service(productionDao).settle(token, AgentRabbitPublishResult.ack(), NOW + 1));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_command_delivery"));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_outbox_event"));
    }

    @Test
    void claimQuarantineSaturatesDeliveryAndOutboxMaxVersions() {
        for (VersionMax max : VersionMax.values()) {
            resetPendingRows();
            if (max != VersionMax.OUTBOX_ONLY) {
                jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE);
            }
            if (max != VersionMax.DELIVERY_ONLY) {
                jdbc.update("""
                        UPDATE agent_outbox_event
                        SET version=?, next_retry_at=?, lease_owner='poison', lease_until=?,
                            publisher_confirm_status='ACK', confirmed_at=?,
                            mandatory_return_status='RETURNED', returned_at=?,
                            return_reply_code=312, return_reply_text='poison'
                        """, Long.MAX_VALUE, NOW, NOW, NOW, NOW);
            }

            assertEquals(AgentOutboxClaim.Status.SKIPPED,
                    service(productionDao).claim(candidate(), "worker-a", NOW).status(),
                    max.name());
            assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
            assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
            assertEquals(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED,
                    string("SELECT last_error FROM agent_outbox_event"));
            assertEquals(max == VersionMax.OUTBOX_ONLY ? 1L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(max == VersionMax.DELIVERY_ONLY ? 1L : Long.MAX_VALUE,
                    longValue("SELECT version FROM agent_outbox_event"));
            assertEquals(null, stringOrNull("SELECT lease_owner FROM agent_outbox_event"));
            assertEquals(null, stringOrNull("SELECT next_retry_at FROM agent_outbox_event"));
            assertEquals("NONE", string("SELECT publisher_confirm_status FROM agent_outbox_event"));
            assertEquals("NONE", string("SELECT mandatory_return_status FROM agent_outbox_event"));
            assertEquals(List.of(), service(productionDao).discover(NOW, 1));
        }
    }

    @Test
    void settleQuarantineSaturatesMaxVersionsAndStaleMaxDeliveryCannotOverwriteAck() {
        for (VersionMax max : VersionMax.values()) {
            resetPendingRows();
            AgentOutboxClaimToken claimed = service(productionDao)
                    .claim(candidate(), "worker-a", NOW).token();
            long deliveryVersion = max == VersionMax.OUTBOX_ONLY ? 1L : Long.MAX_VALUE;
            long outboxVersion = max == VersionMax.DELIVERY_ONLY ? 1L : Long.MAX_VALUE;
            jdbc.update("UPDATE agent_command_delivery SET version=?", deliveryVersion);
            jdbc.update("UPDATE agent_outbox_event SET version=?", outboxVersion);

            assertEquals(AgentOutboxSettleResult.DEAD,
                    service(productionDao).settle(
                            tokenWithVersions(claimed, outboxVersion, deliveryVersion),
                            AgentRabbitPublishResult.ack(), NOW + 1), max.name());
            assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
            assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
            assertEquals(deliveryVersion == Long.MAX_VALUE ? Long.MAX_VALUE : 2L,
                    longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(outboxVersion == Long.MAX_VALUE ? Long.MAX_VALUE : 2L,
                    longValue("SELECT version FROM agent_outbox_event"));
        }

        resetPendingRows();
        AgentOutboxClaimToken old = service(productionDao)
                .claim(candidate(), "worker-a", NOW).token();
        jdbc.update("""
                UPDATE agent_command_delivery
                SET active_message_id='msg-new', active_attempt=2, version=?,
                    lease_owner=NULL, lease_until=NULL
                """, Long.MAX_VALUE);
        assertEquals(AgentOutboxSettleResult.PUBLISHED,
                service(productionDao).settle(old, AgentRabbitPublishResult.ack(), NOW + 1));
        assertEquals("PUBLISHED", string("SELECT status FROM agent_outbox_event"));
        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
    }



    @Test
    void partialMaxMinusOneQuarantineFailureRollsBackBeforeRetryingSafely() {
        jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE - 1);

        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.QUARANTINE_OUTBOX))
                        .claim(candidate(), "worker-a", NOW));
        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
        assertEquals("PENDING", string("SELECT status FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE - 1,
                longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(0L, longValue("SELECT version FROM agent_outbox_event"));

        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service(productionDao).claim(candidate(), "worker-a", NOW).status());
        assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
        assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(1L, longValue("SELECT version FROM agent_outbox_event"));
    }

    @Test
    void partialVersionQuarantineFailureRollsBackPairedDeliveryMutation() {
        jdbc.update("UPDATE agent_command_delivery SET version=?", Long.MAX_VALUE);

        assertThrows(IllegalStateException.class,
                () -> service(new FailingDao(productionDao, Failure.QUARANTINE_OUTBOX))
                        .claim(candidate(), "worker-a", NOW));
        assertEquals("PENDING", string("SELECT status FROM agent_command_delivery"));
        assertEquals("PENDING", string("SELECT status FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(0L, longValue("SELECT version FROM agent_outbox_event"));

        assertEquals(AgentOutboxClaim.Status.SKIPPED,
                service(productionDao).claim(candidate(), "worker-a", NOW).status());
        assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
        assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
        assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
        assertEquals(1L, longValue("SELECT version FROM agent_outbox_event"));
    }

    @Test
    void staleCallbackCannotMutateMaxOrMaxMinusOneAndDiscoveryQuarantinesOnce() {
        for (long fencedVersion : new long[] {Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            resetPendingRows();
            AgentOutboxClaimToken old = service(productionDao)
                    .claim(candidate(), "worker-a", NOW).token();
            jdbc.update("""
                    UPDATE agent_command_delivery
                    SET version=?, lease_until=?
                    """, fencedVersion, NOW);
            jdbc.update("""
                    UPDATE agent_outbox_event
                    SET version=?, lease_until=?
                    """, fencedVersion, NOW);

            assertEquals(AgentOutboxSettleResult.STALE,
                    service(productionDao).settle(old, AgentRabbitPublishResult.ack(), NOW + 1));
            assertEquals("CLAIMED", string("SELECT status FROM agent_outbox_event"));
            assertEquals(fencedVersion, longValue("SELECT version FROM agent_outbox_event"));

            assertEquals(List.of(), service(productionDao).discover(NOW + 1, 1));
            assertEquals("DEAD", string("SELECT status FROM agent_command_delivery"));
            assertEquals("DEAD", string("SELECT status FROM agent_outbox_event"));
            assertEquals(AgentOutboxRelayServiceImpl.VERSION_FENCE_EXHAUSTED,
                    string("SELECT last_error FROM agent_outbox_event"));
            assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_command_delivery"));
            assertEquals(Long.MAX_VALUE, longValue("SELECT version FROM agent_outbox_event"));
            assertEquals(0, productionDao.selectCorruptCandidates(NOW + 1, 4).size());
        }
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

    private void resetPendingRows() {
        jdbc.update("DELETE FROM agent_outbox_event");
        jdbc.update("DELETE FROM agent_command_delivery");
        insertPending();
    }

    private void insertOutboxCopy(
            long id, long deliveryId, String tenantId, String clientId) {
        assertEquals(1, jdbc.update("""
                INSERT INTO agent_outbox_event(
                  id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                  destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                  next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                  publisher_confirm_status,mandatory_return_status,last_error,version,
                  tenant_id,client_id,create_time,update_time)
                SELECT ?,CONCAT('evt-',?),CONCAT('msg-',?),command_id,?,aggregate_type,aggregate_id,
                       destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                       next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                       publisher_confirm_status,mandatory_return_status,last_error,version,
                       ?,?,create_time,update_time
                FROM agent_outbox_event WHERE id=10
                """, id, id, id, deliveryId, tenantId, clientId));
    }

    private static AgentOutboxClaimToken tokenWithVersions(
            AgentOutboxClaimToken token, long outboxVersion, long deliveryVersion) {
        return new AgentOutboxClaimToken(
                token.outboxId(), token.deliveryId(), token.tenantId(), token.clientId(),
                token.eventId(), token.messageId(), token.commandId(), token.taskId(),
                token.targetAgentId(), token.commandType(), token.destination(),
                token.routingKey(), token.wirePayload(), token.wirePayloadHash(),
                token.expiresAt(), token.leaseOwner(), token.leaseUntil(),
                token.publishAttempt(), outboxVersion, token.deliveryStatus(),
                token.deliveryActiveMessageId(), token.deliveryActiveAttempt(), deliveryVersion);
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
        return new AgentOutboxCandidate(1, "tenant-a", "client-a", 41, NOW, 0, "PENDING");
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

    private enum VersionMax { DELIVERY_ONLY, OUTBOX_ONLY, BOTH }
    private enum Failure { CLAIM_OUTBOX, SETTLE_OUTBOX, QUARANTINE_OUTBOX }

    private static final class FailingDao implements AgentOutboxRelayDao {
        private final AgentOutboxRelayDao delegate;
        private final Failure failure;
        private FailingDao(AgentOutboxRelayDao delegate, Failure failure) {
            this.delegate = delegate; this.failure = failure;
        }
        @Override public List<AgentOutboxCandidate> selectCorruptCandidates(long now, int limit) {
            return delegate.selectCorruptCandidates(now, limit);
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
        @Override public AgentOutboxEventEntity lockOutboxForQuarantine(
                AgentOutboxCandidate candidate) {
            return delegate.lockOutboxForQuarantine(candidate);
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
        @Override public int quarantineDelivery(
                AgentCommandDeliveryEntity row, String error, long now) {
            return delegate.quarantineDelivery(row, error, now);
        }
        @Override public int quarantineOutbox(
                AgentOutboxEventEntity row, String error, long now) {
            return failure == Failure.QUARANTINE_OUTBOX ? 0
                    : delegate.quarantineOutbox(row, error, now);
        }
    }
}
