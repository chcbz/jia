package cn.jia.agent.mapper;


import cn.jia.agent.config.AgentRuntimeV2PersistenceSchemaGate;
import cn.jia.agent.dao.impl.AgentRuntimeV2ExecutionCommitDaoImpl;
import cn.jia.agent.entity.*;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.Connection;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2ExecutionCommitTransactionTest {
    private static final String BINDING = "00000000-0000-4000-8000-000000000001";
    private static final String DISPATCH = "00000000-0000-4000-8000-000000000002";
    private static final String RECEIVED_RECEIPT = "00000000-0000-4000-8000-000000000003";
    private static final String SOURCE_MESSAGE = "source-message";
    private static final String RECEIVED_ACK = "received-ack";
    private static final String STARTED_ACK = "started-ack";
    private static final String RUNTIME = "runtime-process-a";
    private static final String SESSION_FENCE = "session-fence-a";
    private static final String SOCKET_OWNER = "replica-a";

    private final CommandProducerScope producer = new CommandProducerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "pool-a", "caller-client", "owner-a", "agent-a",
            "producer-a", 11, "agent-command", 13);
    private final RuntimeReceiverScope receiver = new RuntimeReceiverScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "pool-a", "runtime-client", "owner-a", "agent-a",
            "claimant-b", 7, 17, 11, "agent-command", 13);

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private TransactionTemplate readOnly;
    private AgentRuntimeV2ExecutionCommitDaoImpl commits;
    private AgentCommandV2AckReceiptMapper receiptMapper;
    private AgentRuntimeV2DispatchMapper dispatchMapper;
    private AgentCommandV2ExecutionLeaseFactMapper factMapper;
    private Connection schemaConnection;
    private long now;

    @BeforeEach
    void setUp() throws Exception {
        now = System.currentTimeMillis();
        String url = "jdbc:h2:mem:runtime_v2_commit_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        DriverManagerDataSource source = new DriverManagerDataSource(url, "sa", "");
        schemaConnection = source.getConnection();
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(schemaConnection, true));
        createTables();
        jdbc = new JdbcTemplate(source);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentRuntimeV2BindingMapper.class);
        configuration.addMapper(AgentRuntimeV2BindingGenerationFactMapper.class);
        configuration.addMapper(AgentRuntimeV2DispatchMapper.class);
        configuration.addMapper(AgentCommandV2AckReceiptMapper.class);
        configuration.addMapper(AgentCommandV2ExecutionLeaseFactMapper.class);
        configuration.addMapper(AgentCommandV2ExecutionGrantMapper.class);
        configuration.addMapper(AgentCommandV2ExecutionContinuationAnchorMapper.class);
        configuration.addMapper(AgentCommandV2ExecutionRequestMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        SqlSessionTemplate sessions = new SqlSessionTemplate(factory);
        receiptMapper = sessions.getMapper(AgentCommandV2AckReceiptMapper.class);
        dispatchMapper = sessions.getMapper(AgentRuntimeV2DispatchMapper.class);

        factMapper = sessions.getMapper(AgentCommandV2ExecutionLeaseFactMapper.class);
        commits = new AgentRuntimeV2ExecutionCommitDaoImpl(
                sessions.getMapper(AgentRuntimeV2BindingMapper.class),
                sessions.getMapper(AgentRuntimeV2BindingGenerationFactMapper.class),
                sessions.getMapper(AgentRuntimeV2DispatchMapper.class), receiptMapper,
                sessions.getMapper(AgentCommandV2ExecutionLeaseFactMapper.class),
                sessions.getMapper(AgentCommandV2ExecutionGrantMapper.class),
                sessions.getMapper(AgentCommandV2ExecutionContinuationAnchorMapper.class),
                sessions.getMapper(AgentCommandV2ExecutionRequestMapper.class));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        readOnly = new TransactionTemplate(manager);
        readOnly.setReadOnly(true);
        seedBindingHistory(3, SESSION_FENCE);
        seedDispatch();
    }


    @AfterEach
    void tearDown() throws Exception {
        if (schemaConnection != null) schemaConnection.close();
    }
    @Test
    void dispatchMapperUsesSplitClientsDeploymentsAndByteExactScope() {
        assertNotEquals(producer.commandCallerClientId(), receiver.runtimeCredentialClientId());
        assertNotEquals(producer.producerDeploymentId(), receiver.claimantDeploymentId());
        seedUnpreparedDispatch();
        String preparedDispatch = "00000000-0000-4000-8000-000000000077";
        assertEquals(0, prepareDispatch("owner-b", "agent-a", preparedDispatch));
        assertEquals(0, prepareDispatch("owner-a", "agent-b", preparedDispatch));
        assertNull(jdbc.queryForObject(
                "SELECT runtime_dispatch_id FROM agent_consumer_inbox WHERE id=201", String.class));
        assertEquals(1, prepareDispatch("owner-a", "agent-a", preparedDispatch));
        assertEquals(preparedDispatch, jdbc.queryForObject(
                "SELECT runtime_dispatch_id FROM agent_consumer_inbox WHERE id=201", String.class));

        AgentConsumerInboxEntity exact = lockDispatch(
                "caller-client", "runtime-client", "owner-a", "agent-a");
        assertNotNull(exact);
        assertNull(lockDispatch("Caller-client", "runtime-client", "owner-a", "agent-a"));
        assertNull(lockDispatch("caller-client", "Runtime-client", "owner-a", "agent-a"));
        assertNull(lockDispatch("caller-client", "runtime-client", "Owner-a", "agent-a"));
        assertNull(lockDispatch("caller-client", "runtime-client", "owner-a", "agent-a "));

        jdbc.update("UPDATE agent_runtime_v2_binding SET owner_jiacn='owner-a ' WHERE runtime_binding_id=?", BINDING);
        assertNull(lockDispatch("caller-client", "runtime-client", "owner-a", "agent-a"));
        assertEquals(0, projectLease(exact));
        jdbc.update("UPDATE agent_runtime_v2_binding SET owner_jiacn='owner-a' WHERE runtime_binding_id=?", BINDING);
        jdbc.update("UPDATE agent_command_delivery SET owner_jiacn='owner-a ' WHERE id=100");
        assertNull(lockDispatch("caller-client", "runtime-client", "owner-a", "agent-a"));
        assertEquals(0, projectLease(exact));
        jdbc.update("UPDATE agent_command_delivery SET owner_jiacn='owner-a',target_agent_id='Agent-a' WHERE id=100");
        assertNull(lockDispatch("caller-client", "runtime-client", "owner-a", "agent-a"));
        assertEquals(0, projectLease(exact));
        jdbc.update("UPDATE agent_command_delivery SET target_agent_id='agent-a' WHERE id=100");

        jdbc.update("UPDATE agent_runtime_v2_binding SET status='DISCONNECTED',disconnected_at=?,"
                + " socket_owner_instance_id=NULL,socket_owner_epoch=NULL,socket_owner_lease_until=NULL"
                + " WHERE runtime_binding_id=?", now, BINDING);
        AgentConsumerInboxEntity immutable = lockDispatch(
                "caller-client", "runtime-client", "owner-a", "agent-a");
        assertNotNull(immutable, "immutable dispatch remains readable after disconnect");
        assertEquals(0, projectLease(immutable));
    }

    @Test
    void receiptAndLeaseMappersEnforceExactScopeAndAppendOnlyUniqueness() {
        AgentCommandV2AckReceiptEntity received = findReceipt(
                "caller-client", "runtime-client", "owner-a", RECEIVED_ACK);
        assertNotNull(received);
        assertNull(findReceipt("Caller-client", "runtime-client", "owner-a", RECEIVED_ACK));
        assertNull(findReceipt("caller-client", "Runtime-client", "owner-a", RECEIVED_ACK));
        assertNull(findReceipt("caller-client", "runtime-client", "Owner-a", RECEIVED_ACK));
        assertNull(receiptMapper.findByAckMessageId("2", "cyf", "pool-a", "caller-client",
                "runtime-client", "owner-a", "agent-a", "producer-a", "claimant-b", 7, 17,
                11, "agent-command", 13, RECEIVED_ACK));
        received.setId(null);
        received.setReceiptId(uuid()).setAckMessageId("received-ack-other");
        assertThrows(RuntimeException.class, () -> insertReceipt(received),
                "same dispatch/status must remain unique even with a new ACK ID");

        transaction.execute(status -> appendStart(session(3), STARTED_ACK));
        AgentCommandV2ExecutionLeaseFactEntity start = factMapper.lockLatest("1", "cyf", "pool-a",
                "caller-client", "runtime-client", "owner-a", "agent-a", "producer-a",
                "claimant-b", 7, 17, 11, "agent-command", 13, BINDING, DISPATCH);
        assertNotNull(start);
        assertEquals("START_GRANTED", start.getFactType());
        assertNull(factMapper.lockLatest("1", "cyf", "pool-a", "Caller-client",
                "runtime-client", "owner-a", "agent-a", "producer-a", "claimant-b", 7, 17,
                11, "agent-command", 13, BINDING, DISPATCH));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        AgentCommandV2ExecutionGrantEntity renewal = transaction.execute(status -> commits.renewExecution(
                producer, receiver, session(3), renewalRequest(startedReceipt),
                fact(1, 1, "RENEWED", start.getLeaseFactId(), start.getLeaseUntil() + 60_000),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("RENEWAL")));
        assertNotNull(renewal);
        AgentCommandV2ExecutionLeaseFactEntity latest = factMapper.lockLatest("1", "cyf", "pool-a",
                "caller-client", "runtime-client", "owner-a", "agent-a", "producer-a",
                "claimant-b", 7, 17, 11, "agent-command", 13, BINDING, DISPATCH);
        assertEquals("RENEWED", latest.getFactType());
        assertEquals(1L, latest.getFactSequence());
        assertEquals(start.getLeaseFactId(), latest.getPreviousLeaseFactId());
        latest.setId(null);
        latest.setLeaseFactId(uuid()).setLeaseRevision(2L);
        assertThrows(RuntimeException.class, () -> factMapper.insert("1", "cyf", "pool-a",
                "caller-client", "runtime-client", "owner-a", "agent-a", "producer-a",
                "claimant-b", 7, 17, 11, "agent-command", 13, latest),
                "a full valid-shape row may not reuse the dispatch fact sequence");
    }

    @Test
    void staleSessionFenceOwnerEpochRoutingLeaseAndLifecycleFailBeforeAnyWrite() {
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                appendStart(session(3, "stale-session-fence"), STARTED_ACK)));
        AuthenticatedRuntimeSession staleOwner = new AuthenticatedRuntimeSession(
                BINDING, 3, RUNTIME, SESSION_FENCE, SOCKET_OWNER, 4);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                appendStart(staleOwner, STARTED_ACK)));
        jdbc.update("UPDATE agent_runtime_v2_binding SET socket_owner_lease_until=? WHERE runtime_binding_id=?",
                now - 1, BINDING);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                appendStart(session(3), STARTED_ACK)));
        jdbc.update("UPDATE agent_runtime_v2_binding SET socket_owner_lease_until=? WHERE runtime_binding_id=?",
                now + 600_000, BINDING);
        appendDisconnectedLifecycleFact(3, SESSION_FENCE, 3);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                appendStart(session(3), STARTED_ACK)));
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=100", String.class));
        assertEquals(1, count("agent_command_v2_ack_receipt"));
        assertEquals(0, count("agent_command_v2_execution_lease_fact"));
        assertEquals(0, count("agent_command_v2_execution_grant"));
        assertEquals(0, count("agent_command_v2_execution_request"));
    }

    @Test
    void requiresWritableTransactionAndRollsBackEveryStartWriteAfterUniqueConflict() {
        assertThrows(IllegalStateException.class, () -> appendStart(session(3), STARTED_ACK));
        assertThrows(IllegalStateException.class,
                () -> readOnly.execute(status -> appendStart(session(3), STARTED_ACK)));
        insertValidAckConflict(STARTED_ACK);
        DataIntegrityViolationException uniqueConflict = assertThrows(DataIntegrityViolationException.class,
                () -> transaction.execute(status -> appendStart(session(3), STARTED_ACK)));
        assertSqlState(uniqueConflict, "23505");
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=100", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=100", Long.class));
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT ack_status FROM agent_consumer_inbox WHERE id=200", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT execution_current_lease_fact_id FROM agent_consumer_inbox WHERE id=200", String.class));
        assertEquals(0, count("agent_command_v2_execution_lease_fact"));
        assertEquals(0, count("agent_command_v2_execution_grant"));
        assertEquals(0, count("agent_command_v2_execution_continuation_anchor"));
    }

    @Test
    void expiredStartFailsBeforeEveryAppendAndVersionAdvance() {
        long expiredAt = System.currentTimeMillis() - 1;
        long effectiveAt = expiredAt - 1_000;
        long ackAt = now - 500;
        AgentCommandV2AckReceiptEntity receipt = new AgentCommandV2AckReceiptEntity()
                .setAckMessageId(STARTED_ACK).setSourceMessageId(SOURCE_MESSAGE)
                .setStatus("STARTED").setAckAt(ackAt)
                .setRequestFingerprint(AgentRuntimeV2WireCodec.ackRequestFingerprint(
                        STARTED_ACK, DISPATCH, SOURCE_MESSAGE, "STARTED", ackAt));
        AgentCommandV2ExecutionLeaseFactEntity expired = fact(0, 0, "START_GRANTED", null, expiredAt)
                .setEffectiveAt(effectiveAt);

        assertThrows(IllegalStateException.class, () -> transaction.execute(status ->
                commits.appendStarted(producer, receiver, session(3), DISPATCH, receipt, expired,
                        new AgentCommandV2ExecutionGrantEntity().setGrantKind("START"),
                        new AgentCommandV2ExecutionContinuationAnchorEntity())));

        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=100", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=100", Long.class));
        assertEquals("RECEIVED", jdbc.queryForObject(
                "SELECT ack_status FROM agent_consumer_inbox WHERE id=200", String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT execution_current_lease_fact_id FROM agent_consumer_inbox WHERE id=200", String.class));
        assertEquals(1, count("agent_command_v2_ack_receipt"));
        assertEquals(0, count("agent_command_v2_execution_lease_fact"));
        assertEquals(0, count("agent_command_v2_execution_grant"));
        assertEquals(0, count("agent_command_v2_execution_continuation_anchor"));
        assertEquals(0, count("agent_command_v2_execution_request"));
    }

    @Test
    void committedReceivedStartsTransfersAndRenewsWithoutRebindingHistoricalDispatch() {
        AgentCommandV2ExecutionGrantEntity start = transaction.execute(
                status -> appendStart(session(3), STARTED_ACK));
        assertNotNull(start);
        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT status FROM agent_command_delivery WHERE id=100", String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM agent_command_delivery WHERE id=100", Long.class));
        assertEquals("STARTED", jdbc.queryForObject(
                "SELECT ack_status FROM agent_consumer_inbox WHERE id=200", String.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT version FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT postcommit_delivery_version FROM agent_command_v2_ack_receipt WHERE status='STARTED'", Long.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT postcommit_inbox_version FROM agent_command_v2_ack_receipt WHERE status='STARTED'", Long.class));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        String startFact = jdbc.queryForObject(
                "SELECT lease_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", String.class);
        long firstExpiry = jdbc.queryForObject(
                "SELECT lease_until FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", Long.class);
        reconnect(4, "session-fence-b");
        AgentCommandV2ExecutionGrantEntity continuation = transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, firstExpiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        assertNotNull(continuation);
        AgentCommandV2ExecutionGrantEntity sameAnchor = transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"),
                continuationRequest(startedReceipt, "00000000-0000-4000-8000-000000000012"),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, firstExpiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        assertArrayEquals(continuation.getGrantWireBytes(), sameAnchor.getGrantWireBytes());
        assertEquals(2, count("agent_command_v2_execution_lease_fact"));
        assertEquals(2, count("agent_command_v2_execution_grant"));
        assertEquals(2, count("agent_command_v2_execution_continuation_anchor"));
        String transferFact = continuation.getCurrentLeaseFactId();
        AgentCommandV2ExecutionGrantEntity renewal = transaction.execute(status -> commits.renewExecution(
                producer, receiver, session(4, "session-fence-b"), renewalRequest(startedReceipt),
                fact(2, 1, "RENEWED", transferFact, firstExpiry + 60_000),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("RENEWAL")));
        assertNotNull(renewal);
        assertEquals(3, count("agent_command_v2_execution_lease_fact"));
        assertEquals(3, count("agent_command_v2_execution_grant"));
        assertEquals(2, count("agent_command_v2_execution_continuation_anchor"));
        assertEquals(3, count("agent_command_v2_execution_request"));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT dispatch_binding_generation FROM agent_command_v2_ack_receipt WHERE status='STARTED'", Long.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT runtime_binding_generation FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(4L, jdbc.queryForObject(
                "SELECT execution_holder_binding_generation FROM agent_consumer_inbox WHERE id=200", Long.class));
        AgentCommandV2ExecutionGrantEntity replay = transaction.execute(status -> commits.renewExecution(
                producer, receiver, session(4, "session-fence-b"), renewalRequest(startedReceipt),
                fact(2, 1, "RENEWED", transferFact, firstExpiry + 60_000),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("RENEWAL")));
        assertArrayEquals(renewal.getGrantWireBytes(), replay.getGrantWireBytes());
        assertEquals(3, count("agent_command_v2_execution_grant"));
        AgentCommandV2ExecutionRequestEntity conflict = renewalRequest(startedReceipt);
        byte[] conflictingFingerprint = new byte[32];
        conflictingFingerprint[0] = 8;
        conflict.setRequestFingerprint(conflictingFingerprint);
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.renewExecution(
                producer, receiver, session(4, "session-fence-b"), conflict,
                fact(2, 1, "RENEWED", transferFact, firstExpiry + 60_000),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("RENEWAL"))));
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"),
                continuationRequest(startedReceipt, "00000000-0000-4000-8000-000000000013"),
                fact(3, 1, "HOLDER_TRANSFERRED", renewal.getCurrentLeaseFactId(), renewal.getLeaseUntil()),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity())));
        assertEquals(3, count("agent_command_v2_execution_lease_fact"));
        assertEquals(3, count("agent_command_v2_execution_grant"));
        assertEquals(3, count("agent_command_v2_execution_request"));
    }

    @Test
    void consecutiveReconnectsTransferHolderTwiceWithoutRenewalOrHistoricalRebinding() {
        transaction.execute(status -> appendStart(session(3), STARTED_ACK));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        String startFact = jdbc.queryForObject(
                "SELECT lease_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", String.class);
        long originalExpiry = jdbc.queryForObject(
                "SELECT lease_until FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", Long.class);

        reconnect(4, "session-fence-b");
        AgentCommandV2ExecutionGrantEntity firstTransfer = transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"),
                continuationRequest(startedReceipt, "00000000-0000-4000-8000-000000000020"),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, originalExpiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        assertNotNull(firstTransfer);

        reconnect(5, "session-fence-c");
        AgentCommandV2ExecutionGrantEntity secondTransfer = transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(5, "session-fence-c"),
                continuationRequest(startedReceipt, "00000000-0000-4000-8000-000000000021"),
                fact(2, 0, "HOLDER_TRANSFERRED", firstTransfer.getCurrentLeaseFactId(), originalExpiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        assertNotNull(secondTransfer);

        assertEquals(3, count("agent_command_v2_execution_lease_fact"));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_v2_execution_lease_fact WHERE fact_type='HOLDER_TRANSFERRED'",
                Integer.class));
        assertEquals(3, count("agent_command_v2_execution_grant"));
        assertEquals(3, count("agent_command_v2_execution_continuation_anchor"));
        assertEquals(2, count("agent_command_v2_execution_request"));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT runtime_binding_generation FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(3L, jdbc.queryForObject(
                "SELECT dispatch_binding_generation FROM agent_command_v2_ack_receipt WHERE status='STARTED'", Long.class));
        assertEquals(5L, jdbc.queryForObject(
                "SELECT execution_holder_binding_generation FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT execution_lease_revision FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(originalExpiry, jdbc.queryForObject(
                "SELECT execution_lease_until FROM agent_consumer_inbox WHERE id=200", Long.class));
        assertEquals(0L, secondTransfer.getLeaseRevision());
        assertEquals(originalExpiry, secondTransfer.getLeaseUntil());
    }

    @Test
    void tamperedAnchorAndRequestMappingsFailClosedWithoutAppendingAuthority() {
        transaction.execute(status -> appendStart(session(3), STARTED_ACK));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        String startFact = jdbc.queryForObject(
                "SELECT lease_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", String.class);
        long expiry = jdbc.queryForObject(
                "SELECT lease_until FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", Long.class);
        String startGrant = jdbc.queryForObject(
                "SELECT execution_grant_id FROM agent_command_v2_execution_grant WHERE grant_kind='START'", String.class);
        jdbc.update("UPDATE agent_command_v2_execution_continuation_anchor SET execution_grant_id=?",
                "00000000-0000-4000-8000-000000000088");
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(3), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, expiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity())));
        jdbc.update("UPDATE agent_command_v2_execution_continuation_anchor SET execution_grant_id=?", startGrant);
        reconnect(4, "session-fence-b");
        transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, expiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        jdbc.update("UPDATE agent_command_v2_execution_request SET runtime_binding_id=?",
                "00000000-0000-4000-8000-000000000089");
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, expiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity())));
        assertEquals(2, count("agent_command_v2_execution_lease_fact"));
        assertEquals(2, count("agent_command_v2_execution_grant"));
        assertEquals(2, count("agent_command_v2_execution_continuation_anchor"));
        assertEquals(1, count("agent_command_v2_execution_request"));
    }

    @Test
    void tamperedHolderBindingProjectionFailsClosedBeforeAnyContinuationAppend() {
        transaction.execute(status -> appendStart(session(3), STARTED_ACK));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        String startFact = jdbc.queryForObject(
                "SELECT lease_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", String.class);
        long expiry = jdbc.queryForObject(
                "SELECT lease_until FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", Long.class);
        jdbc.update("UPDATE agent_consumer_inbox SET execution_holder_binding_id=? WHERE id=200",
                "00000000-0000-4000-8000-000000000088");
        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(3), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, expiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity())));
        assertEquals(1, count("agent_command_v2_execution_lease_fact"));
        assertEquals(1, count("agent_command_v2_execution_grant"));
        assertEquals(1, count("agent_command_v2_execution_continuation_anchor"));
        assertEquals(0, count("agent_command_v2_execution_request"));
    }

    @Test
    void transferredCurrentFactImmutableDispatchLinksFenceReplayAndRenewal() {
        transaction.execute(status -> appendStart(session(3), STARTED_ACK));
        String startedReceipt = jdbc.queryForObject(
                "SELECT receipt_id FROM agent_command_v2_ack_receipt WHERE status='STARTED'", String.class);
        String startFact = jdbc.queryForObject(
                "SELECT lease_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", String.class);
        long expiry = jdbc.queryForObject(
                "SELECT lease_until FROM agent_command_v2_execution_lease_fact WHERE fact_type='START_GRANTED'", Long.class);
        reconnect(4, "session-fence-b");
        AgentCommandV2ExecutionGrantEntity transfer = transaction.execute(status -> commits.continueExecution(
                producer, receiver, session(4, "session-fence-b"), continuationRequest(startedReceipt),
                fact(1, 0, "HOLDER_TRANSFERRED", startFact, expiry),
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                new AgentCommandV2ExecutionContinuationAnchorEntity()));
        assertNotNull(transfer);

        Map<String, long[]> attacks = Map.of(
                "inbox_id", new long[]{201, 200},
                "delivery_id", new long[]{101, 100},
                "dispatch_binding_generation", new long[]{2, 3});
        int requestSuffix = 0x40;
        for (Map.Entry<String, long[]> attack : attacks.entrySet()) {
            assertEquals(1, jdbc.update("UPDATE agent_command_v2_execution_lease_fact SET "
                    + attack.getKey() + "=? WHERE lease_fact_id=?",
                    attack.getValue()[0], transfer.getCurrentLeaseFactId()));
            String requestId = String.format("00000000-0000-4000-8000-%012x", requestSuffix++);
            assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.continueExecution(
                    producer, receiver, session(4, "session-fence-b"),
                    continuationRequest(startedReceipt, requestId),
                    fact(2, 0, "HOLDER_TRANSFERRED", transfer.getCurrentLeaseFactId(), expiry),
                    new AgentCommandV2ExecutionGrantEntity().setGrantKind("CONTINUATION"),
                    new AgentCommandV2ExecutionContinuationAnchorEntity())));
            assertThrows(IllegalStateException.class, () -> transaction.execute(status -> commits.renewExecution(
                    producer, receiver, session(4, "session-fence-b"), renewalRequest(startedReceipt),
                    fact(2, 1, "RENEWED", transfer.getCurrentLeaseFactId(), expiry + 60_000),
                    new AgentCommandV2ExecutionGrantEntity().setGrantKind("RENEWAL"))));
            assertEquals(2, count("agent_command_v2_execution_lease_fact"));
            assertEquals(2, count("agent_command_v2_execution_grant"));
            assertEquals(2, count("agent_command_v2_execution_continuation_anchor"));
            assertEquals(1, count("agent_command_v2_execution_request"));
            assertEquals(2, count("agent_command_v2_ack_receipt"));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT version FROM agent_command_delivery WHERE id=100", Long.class));
            assertEquals(4L, jdbc.queryForObject(
                    "SELECT version FROM agent_consumer_inbox WHERE id=200", Long.class));
            assertEquals(transfer.getCurrentLeaseFactId(), jdbc.queryForObject(
                    "SELECT execution_current_lease_fact_id FROM agent_consumer_inbox WHERE id=200", String.class));
            assertEquals(4L, jdbc.queryForObject(
                    "SELECT execution_holder_binding_generation FROM agent_consumer_inbox WHERE id=200", Long.class));
            assertEquals(0L, jdbc.queryForObject(
                    "SELECT execution_lease_revision FROM agent_consumer_inbox WHERE id=200", Long.class));
            assertEquals(1, jdbc.update("UPDATE agent_command_v2_execution_lease_fact SET "
                    + attack.getKey() + "=? WHERE lease_fact_id=?",
                    attack.getValue()[1], transfer.getCurrentLeaseFactId()));
        }
    }

    private int prepareDispatch(String owner, String agent, String dispatchId) {
        return dispatchMapper.prepare("1", "cyf", "pool-a", "caller-client", "runtime-client",
                owner, agent, "producer-a", "claimant-b", 7, 17, 11, "agent-command", 13,
                201, 0, dispatchId, BINDING, 3, RUNTIME, now);
    }

    private void seedUnpreparedDispatch() {
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                  claimed_worker_generation,command_id,owner_jiacn,target_agent_id,status,
                  active_message_id,active_attempt,attempt_count,version,tenant_id,client_id,update_time)
                VALUES(101,2,'cyf','pool-a',11,'producer-a','agent-command',13,
                  'claimant-b',7,'command-b','owner-a','agent-a','CLAIMED','source-b',1,1,0,
                  '1','caller-client',?)
                """, now);
        jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimant_deployment_id,
                  claimant_worker_generation,consumer_name,message_id,event_id,command_id,delivery_id,
                  status,result_status,attempt_count,active_attempt,version,tenant_id,client_id,update_time)
                VALUES(201,2,'cyf','pool-a',11,'producer-a','agent-command',13,'claimant-b',7,
                  'agent-command-dispatch-v2','source-b','event-b','command-b',101,
                  'PROCESSING','NONE',1,1,0,'1','caller-client',?)
                """, now);
    }

    private int projectLease(AgentConsumerInboxEntity inbox) {
        return dispatchMapper.projectLease("1", "cyf", "pool-a", "caller-client",
                "runtime-client", "owner-a", "agent-a", "producer-a", "claimant-b", 7, 17,
                11, "agent-command", 13, inbox.getId(), inbox.getVersion(), DISPATCH,
                BINDING, 3, BINDING, 3, RUNTIME, uuid(), inbox.getExecutionLeaseRevision(),
                inbox.getExecutionLeaseRevision() == null ? 0 : inbox.getExecutionLeaseRevision() + 1,
                now, now + 60_000, now);
    }

    private AgentConsumerInboxEntity lockDispatch(String callerClient, String credentialClient,
            String owner, String agent) {
        return dispatchMapper.lock("1", "cyf", "pool-a", callerClient, credentialClient,
                owner, agent, "producer-a", "claimant-b", 7, 17, 11,
                "agent-command", 13, BINDING, DISPATCH);
    }

    private AgentCommandV2AckReceiptEntity findReceipt(String callerClient, String credentialClient,
            String owner, String ackMessageId) {
        return receiptMapper.findByAckMessageId("1", "cyf", "pool-a", callerClient,
                credentialClient, owner, "agent-a", "producer-a", "claimant-b", 7, 17,
                11, "agent-command", 13, ackMessageId);
    }

    private AgentCommandV2ExecutionGrantEntity appendStart(AuthenticatedRuntimeSession session,
            String ackMessageId) {
        long ackAt = now - 500;
        AgentCommandV2AckReceiptEntity receipt = new AgentCommandV2AckReceiptEntity()
                .setAckMessageId(ackMessageId).setSourceMessageId(SOURCE_MESSAGE)
                .setStatus("STARTED").setAckAt(ackAt)
                .setRequestFingerprint(AgentRuntimeV2WireCodec.ackRequestFingerprint(
                        ackMessageId, DISPATCH, SOURCE_MESSAGE, "STARTED", ackAt));
        AgentCommandV2ExecutionLeaseFactEntity fact = fact(0, 0, "START_GRANTED", null,
                now + 120_000);
        return commits.appendStarted(producer, receiver, session, DISPATCH, receipt, fact,
                new AgentCommandV2ExecutionGrantEntity().setGrantKind("START"),
                new AgentCommandV2ExecutionContinuationAnchorEntity());
    }

    private AgentCommandV2ExecutionLeaseFactEntity fact(long sequence, long revision,
            String type, String previous, long leaseUntil) {
        return new AgentCommandV2ExecutionLeaseFactEntity().setFactSequence(sequence)
                .setLeaseRevision(revision).setFactType(type).setPreviousLeaseFactId(previous)
                .setEffectiveAt(now - 100).setLeaseUntil(leaseUntil)
                .setTenantControlRevision(0L).setModuleModeRevision(0L)
                .setDeploymentRegistrationRevision(17L).setReason(type.toLowerCase());
    }

    private AgentCommandV2ExecutionRequestEntity continuationRequest(String startedReceipt) {
        return continuationRequest(startedReceipt, "00000000-0000-4000-8000-000000000010");
    }

    private AgentCommandV2ExecutionRequestEntity continuationRequest(
            String startedReceipt, String requestId) {
        return new AgentCommandV2ExecutionRequestEntity().setExecutionRequestId(requestId)
                .setRequestKind("CONTINUATION").setRuntimeDispatchId(DISPATCH)
                .setStartedReceiptId(startedReceipt)
                .setRequestFingerprint(AgentRuntimeV2WireCodec.continuationRequestFingerprint(
                        requestId, DISPATCH, startedReceipt, receiptSha(startedReceipt)));
    }

    private AgentCommandV2ExecutionRequestEntity renewalRequest(String startedReceipt) {
        byte[] fingerprint = new byte[32];
        fingerprint[0] = 9;
        return new AgentCommandV2ExecutionRequestEntity()
                .setExecutionRequestId("00000000-0000-4000-8000-000000000011")
                .setRequestKind("RENEWAL").setRuntimeDispatchId(DISPATCH)
                .setStartedReceiptId(startedReceipt).setRequestFingerprint(fingerprint);
    }

    private byte[] receiptSha(String receiptId) {
        return jdbc.queryForObject("SELECT receipt_wire_sha256 FROM agent_command_v2_ack_receipt WHERE receipt_id=?",
                (rs, row) -> rs.getBytes(1), receiptId);
    }

    private AuthenticatedRuntimeSession session(long generation) {
        return session(generation, SESSION_FENCE);
    }

    private AuthenticatedRuntimeSession session(long generation, String fence) {
        return new AuthenticatedRuntimeSession(BINDING, generation, RUNTIME, fence, SOCKET_OWNER, generation);
    }

    private void reconnect(long generation, String fence) {
        long currentGeneration = jdbc.queryForObject(
                "SELECT binding_generation FROM agent_runtime_v2_binding WHERE runtime_binding_id=?",
                Long.class, BINDING);
        assertTrue(generation > currentGeneration);
        String previousFact = jdbc.queryForObject("""
                SELECT binding_generation_fact_id FROM agent_runtime_v2_binding_generation_fact
                WHERE runtime_binding_id=? ORDER BY fact_sequence DESC LIMIT 1
                """, String.class, BINDING);
        long nextSequence = jdbc.queryForObject("""
                SELECT MAX(fact_sequence)+1 FROM agent_runtime_v2_binding_generation_fact
                WHERE runtime_binding_id=?
                """, Long.class, BINDING);
        previousFact = insertGenerationFact(nextSequence, currentGeneration, "DISCONNECTED",
                previousFact, jdbc.queryForObject("""
                        SELECT current_session_fence FROM agent_runtime_v2_binding
                        WHERE runtime_binding_id=?
                        """, String.class, BINDING), currentGeneration);
        insertGenerationFact(nextSequence + 1, generation, "CONNECTED", previousFact, fence, generation);
        jdbc.update("""
                UPDATE agent_runtime_v2_binding SET binding_generation=?,current_session_fence=?,
                  connected_at=?,disconnected_at=NULL,socket_owner_instance_id=?,socket_owner_epoch=?,
                  socket_owner_lease_until=?,version=version+1,update_time=?
                WHERE runtime_binding_id=?
                """, generation, fence, now - 100, SOCKET_OWNER, generation,
                now + 600_000, now, BINDING);
    }

    private void seedBindingHistory(long generation, String fence) {
        jdbc.update("""
                INSERT INTO agent_runtime_v2_binding(
                  runtime_binding_id,credential_kind,credential_reference,runtime_installation_id,
                  contract_version,reconnect_verifier,data_domain_id,execution_pool_id,owner_jiacn,agent_id,
                  claimant_deployment_id,claimant_worker_generation,receiver_registration_revision,
                  admission_epoch,module_id,migration_epoch,runtime_instance_id,binding_generation,
                  status,connected_at,socket_owner_instance_id,socket_owner_epoch,
                  socket_owner_lease_until,current_session_fence,version,tenant_id,client_id,
                  create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, BINDING, "API_KEY", "credential-a", null, 2, new byte[32], "cyf", "pool-a",
                "owner-a", "agent-a", "claimant-b", 7, 17, 11, "agent-command", 13,
                RUNTIME, generation, "ACTIVE", now - 10_000, SOCKET_OWNER, generation,
                now + 600_000, fence, 0, "1", "runtime-client", now - 10_000, now - 10_000);
        String previousFact = insertGenerationFact(0, 0, "CONNECTED", null,
                generation == 0 ? fence : "historical-session-0", 0);
        long sequence = 1;
        for (long nextGeneration = 1; nextGeneration <= generation; nextGeneration++) {
            previousFact = insertGenerationFact(sequence++, nextGeneration - 1, "DISCONNECTED",
                    previousFact, "historical-session-" + (nextGeneration - 1), nextGeneration - 1);
            previousFact = insertGenerationFact(sequence++, nextGeneration, "CONNECTED", previousFact,
                    nextGeneration == generation ? fence : "historical-session-" + nextGeneration,
                    nextGeneration);
        }
    }

    private void appendDisconnectedLifecycleFact(long generation, String fence, long ownerEpoch) {
        String previousFact = jdbc.queryForObject("""
                SELECT binding_generation_fact_id FROM agent_runtime_v2_binding_generation_fact
                WHERE runtime_binding_id=? ORDER BY fact_sequence DESC LIMIT 1
                """, String.class, BINDING);
        long nextSequence = jdbc.queryForObject("""
                SELECT MAX(fact_sequence)+1 FROM agent_runtime_v2_binding_generation_fact
                WHERE runtime_binding_id=?
                """, Long.class, BINDING);
        insertGenerationFact(nextSequence, generation, "DISCONNECTED", previousFact, fence, ownerEpoch);
    }

    private String insertGenerationFact(long sequence, long generation, String eventType,
            String previousFactId, String fence, long ownerEpoch) {
        String factId = uuid();
        long effectiveAt = now - 10_000 + sequence;
        jdbc.update("""
                INSERT INTO agent_runtime_v2_binding_generation_fact(
                  binding_generation_fact_id,fact_sequence,previous_fact_id,runtime_binding_id,
                  binding_generation,event_type,runtime_instance_id,data_domain_id,execution_pool_id,
                  owner_jiacn,agent_id,claimant_deployment_id,claimant_worker_generation,
                  receiver_registration_revision,admission_epoch,module_id,migration_epoch,
                  session_fence,socket_owner_instance_id,socket_owner_epoch,socket_owner_lease_until,
                  effective_at,reason,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, factId, sequence, previousFactId, BINDING, generation, eventType, RUNTIME,
                "cyf", "pool-a", "owner-a", "agent-a", "claimant-b", 7, 17, 11,
                "agent-command", 13, fence, SOCKET_OWNER, ownerEpoch, now + 600_000,
                effectiveAt, null, "1", "runtime-client", effectiveAt, effectiveAt);
        return factId;
    }

    private void seedDispatch() {
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                  claimed_worker_generation,command_id,owner_jiacn,target_agent_id,status,
                  active_message_id,active_attempt,attempt_count,version,tenant_id,client_id,update_time)
                VALUES(100,2,'cyf','pool-a',11,'producer-a','agent-command',13,
                  'claimant-b',7,'command-a','owner-a','agent-a','RECEIVED',?,1,1,1,'1',
                  'caller-client',?)
                """, SOURCE_MESSAGE, now - 1_000);
        jdbc.update("""
                INSERT INTO agent_consumer_inbox(
                  id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                  producer_deployment_id,module_id,migration_epoch,claimant_deployment_id,
                  claimant_worker_generation,consumer_name,message_id,event_id,command_id,delivery_id,
                  status,result_status,attempt_count,active_attempt,version,claim_delivery_version,
                  claim_inbox_version,claim_started_at,ack_delivery_version,ack_message_id,ack_status,
                  ack_at,ack_agent_id,ack_runtime_installation_id,ack_runtime_instance_id,
                  runtime_dispatch_id,runtime_binding_id,runtime_binding_generation,
                  dispatch_runtime_instance_id,dispatch_runtime_credential_client_id,
                  dispatch_receiver_registration_revision,dispatch_prepared_at,
                  ack_runtime_binding_id,ack_runtime_binding_generation,ack_committed_at,
                  tenant_id,client_id,update_time)
                VALUES(200,2,'cyf','pool-a',11,'producer-a','agent-command',13,'claimant-b',7,
                  'agent-command-dispatch-v2',?,'event-a','command-a',100,'PROCESSED','SENT',1,1,2,
                  0,0,?,1,?,'RECEIVED',?,'agent-a',NULL,?, ?,?,3,?,
                  'runtime-client',17,?, ?,3,?,'1','caller-client',?)
                """, SOURCE_MESSAGE, now - 5_000, RECEIVED_ACK, now - 1_000, RUNTIME,
                DISPATCH, BINDING, RUNTIME, now - 2_000, BINDING, now - 900, now - 900);
        AgentCommandV2AckReceiptEntity received = new AgentCommandV2AckReceiptEntity()
                .setReceiptId(RECEIVED_RECEIPT).setRuntimeDispatchId(DISPATCH).setInboxId(200L)
                .setDeliveryId(100L).setAckMessageId(RECEIVED_ACK).setSourceMessageId(SOURCE_MESSAGE)
                .setStatus("RECEIVED").setAckAt(now - 1_000)
                .setRequestFingerprint(AgentRuntimeV2WireCodec.ackRequestFingerprint(
                        RECEIVED_ACK, DISPATCH, SOURCE_MESSAGE, "RECEIVED", now - 1_000))
                .setRuntimeBindingId(BINDING).setDispatchBindingGeneration(3L)
                .setAckHolderBindingGeneration(3L).setRuntimeInstanceId(RUNTIME)
                .setRuntimeCredentialClientId("runtime-client").setReceiverRegistrationRevision(17L)
                .setPostcommitDeliveryVersion(1L).setPostcommitInboxVersion(2L)
                .setContractVersion(2).setCommittedAt(now - 900).setReceiptWireContractVersion(2);
        received.setTenantId("1");
        received.setClientId("caller-client");
        received.setDataDomainId("cyf").setExecutionPoolId("pool-a").setOwnerJiacn("owner-a")
                .setAgentId("agent-a").setProducerDeploymentId("producer-a")
                .setClaimantDeploymentId("claimant-b").setClaimantWorkerGeneration(7L)
                .setAdmissionEpoch(11L).setModuleId("agent-command").setMigrationEpoch(13L);
        received.setCreateTime(now - 900);
        received.setUpdateTime(now - 900);
        byte[] wire = AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(
                RECEIVED_RECEIPT, DISPATCH, RECEIVED_ACK, SOURCE_MESSAGE, "RECEIVED",
                now - 1_000, now - 900, 1, 2, null));
        received.setReceiptWireBytes(wire).setReceiptWireSha256(AgentRuntimeV2WireCodec.sha256(wire));
        assertEquals(1, insertReceipt(received));
    }

    private void insertValidAckConflict(String ackMessageId) {
        String receiptId = uuid();
        String dispatchId = uuid();
        String sourceMessageId = "conflict-source-message";
        long ackAt = now - 700;
        long committedAt = now - 600;
        AgentCommandV2AckReceiptEntity conflict = new AgentCommandV2AckReceiptEntity()
                .setReceiptId(receiptId).setRuntimeDispatchId(dispatchId).setInboxId(200L)
                .setDeliveryId(100L).setAckMessageId(ackMessageId).setSourceMessageId(sourceMessageId)
                .setStatus("REJECTED").setAckAt(ackAt)
                .setRequestFingerprint(AgentRuntimeV2WireCodec.ackRequestFingerprint(
                        ackMessageId, dispatchId, sourceMessageId, "REJECTED", ackAt))
                .setRuntimeBindingId(BINDING).setDispatchBindingGeneration(3L)
                .setAckHolderBindingGeneration(3L).setRuntimeInstanceId(RUNTIME)
                .setRuntimeCredentialClientId("runtime-client").setReceiverRegistrationRevision(17L)
                .setPostcommitDeliveryVersion(1L).setPostcommitInboxVersion(2L)
                .setContractVersion(2).setCommittedAt(committedAt).setReceiptWireContractVersion(2);
        conflict.setTenantId("1");
        conflict.setClientId("caller-client");
        conflict.setDataDomainId("cyf").setExecutionPoolId("pool-a").setOwnerJiacn("owner-a")
                .setAgentId("agent-a").setProducerDeploymentId("producer-a")
                .setClaimantDeploymentId("claimant-b").setClaimantWorkerGeneration(7L)
                .setAdmissionEpoch(11L).setModuleId("agent-command").setMigrationEpoch(13L);
        conflict.setCreateTime(committedAt);
        conflict.setUpdateTime(committedAt);
        byte[] wire = AgentRuntimeV2WireCodec.encodeReceipt(new AgentRuntimeV2WireCodec.Receipt(
                receiptId, dispatchId, ackMessageId, sourceMessageId, "REJECTED", ackAt,
                committedAt, 1, 2, null));
        conflict.setReceiptWireBytes(wire).setReceiptWireSha256(AgentRuntimeV2WireCodec.sha256(wire));
        assertEquals(1, insertReceipt(conflict));
    }

    private int insertReceipt(AgentCommandV2AckReceiptEntity receipt) {
        return receiptMapper.insert("1", "cyf", "pool-a", "caller-client", "runtime-client",
                "owner-a", "agent-a", "producer-a", "claimant-b", 7, 17, 11,
                "agent-command", 13, receipt);
    }

    private static void assertSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) return;
        }
        fail("missing SQLState " + expected, failure);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private void installProductionChecks() {
        Map<String, String> checks = productionChecks();
        Map<String, String> tables = Map.of(
                "chk_inbox_runtime_v2_projection", "agent_consumer_inbox",
                "chk_runtime_v2_binding_canonical", "agent_runtime_v2_binding",
                "chk_runtime_v2_binding_state", "agent_runtime_v2_binding",
                "chk_runtime_v2_binding_route", "agent_runtime_v2_binding",
                "chk_binding_generation_fact_canonical", "agent_runtime_v2_binding_generation_fact",
                "chk_ack_receipt_canonical", "agent_command_v2_ack_receipt",
                "chk_execution_lease_fact_canonical", "agent_command_v2_execution_lease_fact",
                "chk_execution_grant_canonical", "agent_command_v2_execution_grant",
                "chk_execution_continuation_anchor", "agent_command_v2_execution_continuation_anchor",
                "chk_execution_request_canonical", "agent_command_v2_execution_request");
        tables.forEach((name, table) -> jdbc.execute(
                "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " CHECK (" + checks.get(name) + ")"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> productionChecks() {
        try {
            Method method = AgentRuntimeV2PersistenceSchemaGate.class
                    .getDeclaredMethod("expectedCheckClauses");
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("unable to load production runtime-v2 CHECK clauses", failure);
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_binding(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,runtime_binding_id VARCHAR_IGNORECASE(36) UNIQUE,
                  credential_kind VARCHAR(32),credential_reference VARCHAR(200),runtime_installation_id VARCHAR(100),
                  contract_version INT,reconnect_verifier BINARY(32),data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT,runtime_instance_id VARCHAR_IGNORECASE(100),binding_generation BIGINT,
                  status VARCHAR(20),connected_at BIGINT,disconnected_at BIGINT,revoked_at BIGINT,
                  revoke_reason VARCHAR(500),socket_owner_instance_id VARCHAR_IGNORECASE(100),
                  socket_owner_epoch BIGINT,socket_owner_lease_until BIGINT,current_session_fence VARCHAR_IGNORECASE(100),
                  version BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  create_time BIGINT,update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime_v2_binding_generation_fact(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,binding_generation_fact_id VARCHAR_IGNORECASE(36) UNIQUE,
                  fact_sequence BIGINT,previous_fact_id VARCHAR_IGNORECASE(36),runtime_binding_id VARCHAR_IGNORECASE(36),
                  binding_generation BIGINT,event_type VARCHAR(20),revoked_runtime_binding_id VARCHAR_IGNORECASE(36),
                  runtime_instance_id VARCHAR_IGNORECASE(100),data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),claimant_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_worker_generation BIGINT,receiver_registration_revision BIGINT,admission_epoch BIGINT,
                  module_id VARCHAR_IGNORECASE(64),migration_epoch BIGINT,session_fence VARCHAR_IGNORECASE(100),
                  socket_owner_instance_id VARCHAR_IGNORECASE(100),socket_owner_epoch BIGINT,
                  socket_owner_lease_until BIGINT,effective_at BIGINT,reason VARCHAR(500),
                  tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),create_time BIGINT,update_time BIGINT,
                  UNIQUE(runtime_binding_id,fact_sequence))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT PRIMARY KEY,protocol_version INT,data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100),admission_epoch BIGINT,
                  producer_deployment_id VARCHAR_IGNORECASE(100),module_id VARCHAR_IGNORECASE(64),migration_epoch BIGINT,
                  claimed_deployment_id VARCHAR_IGNORECASE(100),claimed_worker_generation BIGINT,
                  command_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100),task_id VARCHAR(100),
                  work_item_id VARCHAR(100),target_agent_id VARCHAR_IGNORECASE(100),command_type VARCHAR(100),
                  command_payload BLOB,command_payload_hash BINARY(32),status VARCHAR(20),attempt_count INT,
                  next_retry_at BIGINT,lease_owner VARCHAR(100),lease_until BIGINT,active_message_id VARCHAR_IGNORECASE(100),
                  active_attempt INT,expires_at BIGINT,last_error VARCHAR(1000),version BIGINT,
                  replay_parent_message_id VARCHAR(100),replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100),replay_reason VARCHAR(1000),tenant_id VARCHAR_IGNORECASE(50),
                  client_id VARCHAR_IGNORECASE(50),create_time BIGINT,update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox(
                  id BIGINT PRIMARY KEY,protocol_version INT,data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100),admission_epoch BIGINT,
                  producer_deployment_id VARCHAR_IGNORECASE(100),module_id VARCHAR_IGNORECASE(64),migration_epoch BIGINT,
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  consumer_name VARCHAR(100),message_id VARCHAR_IGNORECASE(100),event_id VARCHAR(100),
                  command_id VARCHAR_IGNORECASE(100),delivery_id BIGINT,wire_payload BLOB,wire_payload_hash BINARY(32),
                  status VARCHAR(20),result_status VARCHAR(20),attempt_count INT,next_retry_at BIGINT,
                  lease_owner VARCHAR(100),lease_until BIGINT,active_attempt INT,expires_at BIGINT,processed_at BIGINT,
                  last_error VARCHAR(1000),version BIGINT,claim_delivery_version BIGINT,claim_inbox_version BIGINT,
                  claim_started_at BIGINT,ack_delivery_version BIGINT,ack_message_id VARCHAR_IGNORECASE(100),
                  ack_status VARCHAR_IGNORECASE(20),ack_at BIGINT,ack_agent_id VARCHAR_IGNORECASE(100),
                  ack_runtime_installation_id VARCHAR_IGNORECASE(100),ack_runtime_instance_id VARCHAR_IGNORECASE(100),
                  runtime_dispatch_id VARCHAR_IGNORECASE(36) UNIQUE,runtime_binding_id VARCHAR_IGNORECASE(36),
                  runtime_binding_generation BIGINT,dispatch_runtime_instance_id VARCHAR_IGNORECASE(100),
                  dispatch_runtime_credential_client_id VARCHAR_IGNORECASE(50),
                  dispatch_receiver_registration_revision BIGINT,dispatch_prepared_at BIGINT,
                  execution_started_at BIGINT,execution_lease_until BIGINT,execution_lease_revision BIGINT,
                  execution_holder_binding_id VARCHAR_IGNORECASE(36),execution_holder_binding_generation BIGINT,
                  execution_holder_runtime_instance_id VARCHAR_IGNORECASE(100),
                  execution_current_lease_fact_id VARCHAR_IGNORECASE(36),ack_runtime_binding_id VARCHAR_IGNORECASE(36),
                  ack_runtime_binding_generation BIGINT,ack_committed_at BIGINT,replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100),replay_approver_id VARCHAR(100),replay_reason VARCHAR(1000),
                  tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),create_time BIGINT,update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_ack_receipt(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,receipt_id VARCHAR_IGNORECASE(36) UNIQUE,
                  runtime_dispatch_id VARCHAR_IGNORECASE(36),inbox_id BIGINT,delivery_id BIGINT,
                  ack_message_id VARCHAR_IGNORECASE(100) UNIQUE,source_message_id VARCHAR_IGNORECASE(100),
                  status VARCHAR_IGNORECASE(20),ack_at BIGINT,request_fingerprint BINARY(32),
                  runtime_binding_id VARCHAR_IGNORECASE(36),dispatch_binding_generation BIGINT,
                  ack_holder_binding_generation BIGINT,runtime_instance_id VARCHAR_IGNORECASE(100),
                  runtime_credential_client_id VARCHAR_IGNORECASE(50),data_domain_id VARCHAR_IGNORECASE(64),
                  execution_pool_id VARCHAR_IGNORECASE(100),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT,postcommit_delivery_version BIGINT,postcommit_inbox_version BIGINT,
                  lease_fact_id VARCHAR_IGNORECASE(36),contract_version INT,receipt_wire_bytes BLOB,
                  receipt_wire_sha256 BINARY(32),receipt_wire_contract_version INT,committed_at BIGINT,
                  tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),create_time BIGINT,update_time BIGINT,
                  UNIQUE(runtime_dispatch_id,status))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_lease_fact(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,lease_fact_id VARCHAR_IGNORECASE(36) UNIQUE,
                  runtime_dispatch_id VARCHAR_IGNORECASE(36),inbox_id BIGINT,delivery_id BIGINT,
                  fact_sequence BIGINT,lease_revision BIGINT,fact_type VARCHAR_IGNORECASE(20),
                  previous_lease_fact_id VARCHAR_IGNORECASE(36),effective_at BIGINT,lease_until BIGINT,
                  causing_receipt_id VARCHAR_IGNORECASE(36),runtime_binding_id VARCHAR_IGNORECASE(36),
                  dispatch_binding_generation BIGINT,holder_binding_generation BIGINT,
                  runtime_instance_id VARCHAR_IGNORECASE(100),runtime_credential_client_id VARCHAR_IGNORECASE(50),
                  data_domain_id VARCHAR_IGNORECASE(64),execution_pool_id VARCHAR_IGNORECASE(100),
                  owner_jiacn VARCHAR_IGNORECASE(100),agent_id VARCHAR_IGNORECASE(100),
                  producer_deployment_id VARCHAR_IGNORECASE(100),claimant_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_worker_generation BIGINT,receiver_registration_revision BIGINT,admission_epoch BIGINT,
                  module_id VARCHAR_IGNORECASE(64),migration_epoch BIGINT,tenant_control_operation_id VARCHAR(100),
                  tenant_control_revision BIGINT,tenant_control_cutoff_at BIGINT,module_control_operation_id VARCHAR(100),
                  module_mode_revision BIGINT,module_control_cutoff_at BIGINT,deployment_control_operation_id VARCHAR(100),
                  deployment_registration_revision BIGINT,deployment_control_cutoff_at BIGINT,reason VARCHAR(500),
                  created_at BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  create_time BIGINT,update_time BIGINT,UNIQUE(runtime_dispatch_id,fact_sequence),
                  UNIQUE(runtime_dispatch_id,holder_binding_generation,lease_revision,fact_type))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_grant(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,execution_grant_id VARCHAR_IGNORECASE(36) UNIQUE,
                  grant_kind VARCHAR_IGNORECASE(20),runtime_dispatch_id VARCHAR_IGNORECASE(36),
                  started_receipt_id VARCHAR_IGNORECASE(36),started_receipt_sha256 BINARY(32),
                  runtime_binding_id VARCHAR_IGNORECASE(36),runtime_instance_id VARCHAR_IGNORECASE(100),
                  dispatch_binding_generation BIGINT,holder_binding_generation BIGINT,
                  original_start_lease_fact_id VARCHAR_IGNORECASE(36),current_lease_fact_id VARCHAR_IGNORECASE(36),
                  lease_revision BIGINT,lease_until BIGINT,previous_execution_grant_id VARCHAR_IGNORECASE(36),
                  issued_at BIGINT,grant_wire_bytes BLOB,grant_wire_sha256 BINARY(32),grant_wire_contract_version INT,
                  data_domain_id VARCHAR_IGNORECASE(64),execution_pool_id VARCHAR_IGNORECASE(100),
                  runtime_credential_client_id VARCHAR_IGNORECASE(50),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  create_time BIGINT,update_time BIGINT,
                  UNIQUE(runtime_dispatch_id,holder_binding_generation,lease_revision,grant_kind))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_continuation_anchor(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,runtime_dispatch_id VARCHAR_IGNORECASE(36),
                  started_receipt_id VARCHAR_IGNORECASE(36),holder_binding_generation BIGINT,
                  execution_grant_id VARCHAR_IGNORECASE(36) UNIQUE,created_at BIGINT,contract_version INT,
                  data_domain_id VARCHAR_IGNORECASE(64),execution_pool_id VARCHAR_IGNORECASE(100),
                  runtime_credential_client_id VARCHAR_IGNORECASE(50),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  create_time BIGINT,update_time BIGINT,
                  UNIQUE(runtime_dispatch_id,started_receipt_id,holder_binding_generation))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_v2_execution_request(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,execution_request_id VARCHAR_IGNORECASE(36) UNIQUE,
                  request_kind VARCHAR_IGNORECASE(20),request_fingerprint BINARY(32),
                  runtime_dispatch_id VARCHAR_IGNORECASE(36),started_receipt_id VARCHAR_IGNORECASE(36),
                  runtime_binding_id VARCHAR_IGNORECASE(36),runtime_instance_id VARCHAR_IGNORECASE(100),
                  requested_holder_binding_generation BIGINT,decision VARCHAR(20),
                  execution_grant_id VARCHAR_IGNORECASE(36),denial_code VARCHAR(100),decided_at BIGINT,
                  contract_version INT,data_domain_id VARCHAR_IGNORECASE(64),execution_pool_id VARCHAR_IGNORECASE(100),
                  runtime_credential_client_id VARCHAR_IGNORECASE(50),owner_jiacn VARCHAR_IGNORECASE(100),
                  agent_id VARCHAR_IGNORECASE(100),producer_deployment_id VARCHAR_IGNORECASE(100),
                  claimant_deployment_id VARCHAR_IGNORECASE(100),claimant_worker_generation BIGINT,
                  receiver_registration_revision BIGINT,admission_epoch BIGINT,module_id VARCHAR_IGNORECASE(64),
                  migration_epoch BIGINT,tenant_id VARCHAR_IGNORECASE(50),client_id VARCHAR_IGNORECASE(50),
                  create_time BIGINT,update_time BIGINT)
                """);
        installProductionChecks();
    }
}
