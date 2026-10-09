package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.dao.impl.AgentOutboxRelayDaoImpl;
import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyConfiguration;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import cn.jia.agent.config.AgentRabbitV2TopologyReadiness;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.service.impl.AgentCommandCanonicalCodec;
import cn.jia.agent.service.impl.AgentOutboxRelayServiceImpl;
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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Executes the production MyBatis relay SQL against a case-insensitive isolated database. */
class AgentOutboxRelayV2PersistenceTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final WorkerScope SCOPE = new WorkerScope(
            TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
            7, 11, 13, "agent-command", 17);

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private AgentOutboxRelayDao dao;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:relay_v2_" + UUID.randomUUID()
                + ";MODE=MYSQL;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        createTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentOutboxRelayMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(global);
        SqlSessionFactory factory = bean.getObject();
        dao = new AgentOutboxRelayDaoImpl(
                new SqlSessionTemplate(factory).getMapper(AgentOutboxRelayMapper.class));
    }

    @Test
    void discoveryExposesOnlyTheExactV2LaneAndCurrentClaimantGeneration() {
        insertPair(1, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(2, null, "1", null, null, null, null, null,
                "PENDING", null, null);
        insertPair(3, 2, "1 ", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(4, 2, "1", "CYF", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(5, 2, "1", "cyf", "Pool-A", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(6, 2, "1", "cyf", "pool-a", 14L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(7, 2, "1", "cyf", "pool-a", 13L, "Agent-Command", 17L,
                "PENDING", null, null);
        insertPair(8, 2, "1", "cyf", "pool-a", 13L, "agent-command", 18L,
                "PENDING", null, null);
        insertPair(9, 1, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(20, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "CLAIMED", "deployment-a", 7L);
        insertPair(21, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "CLAIMED", "another-node", 7L);
        insertPair(22, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "CLAIMED", "deployment-a", 8L);

        assertEquals(List.of(1L), ids(dao.selectDueCandidates(SCOPE, NOW, 100)));
        assertEquals(List.of(20L), ids(dao.selectStaleCandidates(SCOPE, NOW, 100)));
        assertEquals(List.of(), ids(dao.selectCorruptCandidates(SCOPE, NOW, 100)));
    }

    @Test
    void locksAndMutationCasCannotCrossScopeOrClaimantAuthority() {
        insertPair(1, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(2, 2, "1", "cyf", "pool-b", 13L, "agent-command", 17L,
                "PENDING", null, null);

        assertNull(dao.lockOutbox(new WorkerScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-b", "deployment-a",
                7, 11, 13, "agent-command", 17), 1));
        AgentCommandDeliveryEntity delivery = dao.lockDelivery(SCOPE, 1);
        AgentOutboxEventEntity outbox = dao.lockOutbox(SCOPE, 1);
        assertEquals(1, dao.claimDelivery(SCOPE, delivery, "lease-a", NOW + 100, null, NOW));
        assertEquals(1, dao.claimOutbox(SCOPE, outbox, "lease-a", NOW + 100, null, NOW));
        assertEquals("PENDING", value("agent_command_delivery", 2, "status"));
        assertEquals("PENDING", value("agent_outbox_event", 2, "status"));

        WorkerScope wrongClaimant = new WorkerScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "pool-a", "Deployment-A",
                7, 11, 13, "agent-command", 17);
        delivery = dao.lockDelivery(SCOPE, 1);
        outbox = dao.lockOutbox(SCOPE, 1);
        assertEquals(0, dao.disposeDelivery(
                wrongClaimant, delivery, "PUBLISHED", null, null, NOW + 1));
        assertEquals(0, dao.disposeOutbox(
                wrongClaimant, outbox, "PUBLISHED", null, "ACK", NOW + 1,
                null, "NOT_RETURNED", null, null, null, NOW + 1, null, NOW + 1));
        assertEquals("CLAIMED", value("agent_outbox_event", 1, "status"));

        assertEquals(1, dao.disposeDelivery(
                SCOPE, delivery, "PUBLISHED", null, null, NOW + 2));
        assertEquals(1, dao.disposeOutbox(
                SCOPE, outbox, "PUBLISHED", null, "ACK", NOW + 2,
                null, "NOT_RETURNED", null, null, null, NOW + 2, null, NOW + 2));
        assertEquals("PUBLISHED", value("agent_command_delivery", 1, "status"));
        assertEquals("PUBLISHED", value("agent_outbox_event", 1, "status"));
    }

    @Test
    void claimStateMachineAtomicallyDisposesGateDeniedUnclaimedRowAndWrongScopeWritesNothing()
            throws Exception {
        insertPair(1, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        AgentOutboxCandidate denied = dao.selectDueCandidates(SCOPE, NOW, 10).getFirst();
        AgentOutboxRelayServiceImpl service = serviceWithDeniedDispatchScope();

        AgentOutboxClaim result = service.claim(SCOPE, denied,
                leaseOwner(SCOPE, new UUID(0, 1)), NOW);

        assertEquals(AgentOutboxClaim.Status.SKIPPED, result.status());
        assertEquals("DEAD", value("agent_command_delivery", 1, "status"));
        assertEquals("DEAD", value("agent_outbox_event", 1, "status"));
        assertEquals("DISPATCH_SCOPE_CAPTURE_ONLY",
                value("agent_command_delivery", 1, "last_error"));
        assertEquals("DISPATCH_SCOPE_CAPTURE_ONLY",
                value("agent_outbox_event", 1, "last_error"));

        insertPair(2, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        AgentOutboxCandidate exactRow = dao.selectDueCandidates(SCOPE, NOW, 10).getFirst();
        WorkerScope wrongScope = new WorkerScope(
                TenantId.of("2"), DataDomainId.of("cyf"), "pool-a", "deployment-a",
                7, 11, 13, "agent-command", 17);

        assertEquals(AgentOutboxClaim.Status.DISABLED,
                service.claim(wrongScope, exactRow,
                        leaseOwner(wrongScope, new UUID(0, 2)), NOW).status());
        assertEquals("PENDING", value("agent_command_delivery", 2, "status"));
        assertEquals("PENDING", value("agent_outbox_event", 2, "status"));
    }

    @Test
    void terminalDispositionAcceptsUnclaimedRowsButRejectsForeignClaimants() {
        insertPair(1, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(2, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "CLAIMED", "another-node", 7L);

        AgentCommandDeliveryEntity unclaimedDelivery = dao.lockDelivery(SCOPE, 1);
        AgentOutboxEventEntity unclaimedOutbox = dao.lockOutbox(SCOPE, 1);
        assertEquals(1, dao.disposeDelivery(
                SCOPE, unclaimedDelivery, "EXPIRED", null, "MESSAGE_EXPIRED", NOW));
        assertEquals(1, dao.disposeOutbox(
                SCOPE, unclaimedOutbox, "EXPIRED", null, "NONE", null,
                null, "NONE", null, null, null, null, "MESSAGE_EXPIRED", NOW));
        assertEquals("EXPIRED", value("agent_command_delivery", 1, "status"));
        assertEquals("EXPIRED", value("agent_outbox_event", 1, "status"));

        AgentCommandDeliveryEntity foreignDelivery = dao.lockDelivery(SCOPE, 2);
        AgentOutboxEventEntity foreignOutbox = dao.lockOutbox(SCOPE, 2);
        assertEquals(0, dao.disposeDelivery(
                SCOPE, foreignDelivery, "DEAD", null, "FOREIGN", NOW));
        assertEquals(0, dao.disposeOutbox(
                SCOPE, foreignOutbox, "DEAD", null, "NONE", null,
                null, "NONE", null, null, null, null, "FOREIGN", NOW));
        assertEquals("PENDING", value("agent_command_delivery", 2, "status"));
        assertEquals("CLAIMED", value("agent_outbox_event", 2, "status"));
    }

    @Test
    void quarantineCasExecutesForUnclaimedRowsAndRejectsForeignClaimants() {
        insertPair(1, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "PENDING", null, null);
        insertPair(2, 2, "1", "cyf", "pool-a", 13L, "agent-command", 17L,
                "CLAIMED", "another-node", 7L);

        AgentCommandDeliveryEntity unclaimedDelivery = dao.lockDelivery(SCOPE, 1);
        AgentOutboxEventEntity unclaimedOutbox = dao.lockOutboxForQuarantine(
                SCOPE, dao.selectDueCandidates(SCOPE, NOW, 10).stream()
                        .filter(candidate -> candidate.outboxId() == 1).findFirst().orElseThrow());
        assertEquals(1, dao.quarantineDelivery(SCOPE, unclaimedDelivery, "POISON", NOW));
        assertEquals(1, dao.quarantineOutbox(SCOPE, unclaimedOutbox, "POISON", NOW));
        assertEquals("DEAD", value("agent_command_delivery", 1, "status"));
        assertEquals("DEAD", value("agent_outbox_event", 1, "status"));

        AgentCommandDeliveryEntity foreignDelivery = dao.lockDelivery(SCOPE, 2);
        AgentOutboxEventEntity foreignOutbox = dao.lockOutbox(SCOPE, 2);
        assertEquals(0, dao.quarantineDelivery(SCOPE, foreignDelivery, "FOREIGN", NOW));
        assertEquals(0, dao.quarantineOutbox(SCOPE, foreignOutbox, "FOREIGN", NOW));
        assertEquals("PENDING", value("agent_command_delivery", 2, "status"));
        assertEquals("CLAIMED", value("agent_outbox_event", 2, "status"));
    }

    private static String leaseOwner(WorkerScope scope, UUID instanceId) {
        byte[] deployment = AgentCommandCanonicalCodec.sha256(
                scope.deploymentId().getBytes(StandardCharsets.UTF_8));
        return "d03/" + HexFormat.of().formatHex(deployment, 0, 16) + "/"
                + scope.workerGeneration() + "/" + instanceId;
    }

    private AgentOutboxRelayServiceImpl serviceWithDeniedDispatchScope() throws Exception {
        TenantModuleAdmissionGuard guard = mock(TenantModuleAdmissionGuard.class);
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            java.util.function.Function<TenantModuleAdmissionGuard.Mode, Object> work =
                    invocation.getArgument(2);
            return work.apply(TenantModuleAdmissionGuard.Mode.DOMAIN);
        }).when(guard).withWorkerAdmission(any(), any(), any());

        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 5673, "user", "secret", "/isolated"));
        AgentRabbitSafetyGate gate = new AgentRabbitSafetyGate(properties,
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "another-tenant", "another-client"))));
        AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
        AgentRabbitTopologyReadiness readiness = new AgentRabbitTopologyConfiguration()
                .agentRabbitTopologyReadiness(manifest);
        Method markProvisioned = AgentRabbitTopologyReadiness.class
                .getDeclaredMethod("markProvisioned");
        markProvisioned.setAccessible(true);
        markProvisioned.invoke(readiness);
        AgentRabbitV2TopologyManifest scoped =
                AgentRabbitV2TopologyManifest.forScope(SCOPE);
        AgentRabbitV2TopologyReadiness v2Readiness =
                new AgentRabbitV2TopologyReadiness();
        Method markV2Provisioned = AgentRabbitV2TopologyReadiness.class
                .getDeclaredMethod("markProvisioned", AgentRabbitV2TopologyManifest.class);
        markV2Provisioned.setAccessible(true);
        markV2Provisioned.invoke(v2Readiness, scoped);
        return new AgentOutboxRelayServiceImpl(
                dao, gate, manifest, readiness, v2Readiness,
                new AgentOutboxRelaySettings(properties.rabbitPublish()), guard,
                new DataSourceTransactionManager(source));
    }

    private static List<Long> ids(List<AgentOutboxCandidate> candidates) {
        return candidates.stream().map(AgentOutboxCandidate::outboxId).toList();
    }

    private String value(String table, long id, String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM " + table + " WHERE id=?", String.class, id);
    }

    private void insertPair(long id, Integer protocol, String tenant, String domain,
            String pool, Long admission, String module, Long migration, String status,
            String claimant, Long generation) {
        String producer = protocol != null && protocol == 2 ? "producer-a" : null;
        boolean claimed = "CLAIMED".equals(status);
        String leaseOwner = claimed ? "lease-old" : null;
        Long leaseUntil = claimed ? NOW - 1 : null;
        int attempt = claimed ? 1 : 0;
        jdbc.update("""
                INSERT INTO agent_command_delivery
                  (id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                   producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                   claimed_worker_generation,command_id,owner_jiacn,task_id,target_agent_id,
                   command_type,command_payload,command_payload_hash,status,attempt_count,
                   lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
                   version,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, protocol, domain, pool, admission, producer, module, migration,
                claimant, generation, "command-" + id, "owner-a", "task-" + id, "agent-a",
                "task.invite", new byte[] {1}, new byte[32], "PENDING", 1,
                leaseOwner, leaseUntil, "message-" + id, 1, NOW + 10_000,
                "DISPATCH_ELIGIBLE_V1", 0L, tenant, "client-a", NOW - 100, NOW - 100);
        jdbc.update("""
                INSERT INTO agent_outbox_event
                  (id,protocol_version,data_domain_id,execution_pool_id,admission_epoch,
                   producer_deployment_id,module_id,migration_epoch,claimed_deployment_id,
                   claimed_worker_generation,event_id,message_id,command_id,delivery_id,
                   aggregate_type,aggregate_id,destination,routing_key,wire_payload,
                   wire_payload_hash,status,attempt_count,next_retry_at,lease_owner,lease_until,
                   active_attempt,expires_at,publisher_confirm_status,mandatory_return_status,
                   last_error,version,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, protocol, domain, pool, admission, producer, module, migration,
                claimant, generation, "event-" + id, "message-" + id, "command-" + id, id,
                "task", "task-" + id, "exchange", "routing", new byte[] {1}, new byte[32],
                status, attempt, null, leaseOwner, leaseUntil, 1, NOW + 10_000,
                claimed ? "PENDING" : "NONE", claimed ? "PENDING" : "NONE",
                claimed ? null : "DISPATCH_ELIGIBLE_V1", 0L, tenant, "client-a",
                NOW - 100, NOW - 100);
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
    }
}
