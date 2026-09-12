package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentCommandOperationsProperties;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.dao.impl.AgentCommandOperationsDaoImpl;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationsException;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production mapper plus real read-only transaction evidence on H2 MySQL mode. */
class AgentCommandOperationsRealTransactionTest {
    private static final long NOW = 1_700_000_000_000L;
    private static final long ISSUED = NOW - 1_000L;
    private static final long EXPIRES = ISSUED + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private AgentCommandOperationsDao dao;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:d09_ops_tx_" + System.nanoTime()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        jdbc = new JdbcTemplate(source);
        createSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentCommandOperationsMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        dao = new AgentCommandOperationsDaoImpl(new SqlSessionTemplate(factory)
                .getMapper(AgentCommandOperationsMapper.class));
        manager = new DataSourceTransactionManager(source);
        insertSource(1L, "11111111-1111-1111-1111-111111111111", "event-1", true);
        insertSource(2L, "22222222-2222-2222-2222-222222222222", "event-2", false);
        insertSource(3L, "33333333-3333-3333-3333-333333333333", "event-3", true);
    }

    @Test
    void broadRowsArePolicyFilteredWithStableCursorLimitAndNoApproximation() {
        AgentCommandOperationsServiceImpl service = service();

        var first = service.listDlq("tenant-a", "client-a", 0, 1);
        assertEquals(List.of(1L), first.items().stream().map(row -> row.deliveryId()).toList());
        assertEquals(1L, first.nextAfterDeliveryId());
        assertTrue(first.hasMore());

        var second = service.listDlq("tenant-a", "client-a", 1, 10);
        assertEquals(List.of(3L), second.items().stream().map(row -> row.deliveryId()).toList());
        assertEquals(3L, second.nextAfterDeliveryId());
        assertFalse(second.hasMore());
    }

    @Test
    void redriveV1AcceptanceAndAuditCommitTogetherReplayWithoutDuplicateAndRollbackTogether() {
        AgentCommandOperationsServiceImpl service = service(true);

        var accepted = service.acceptBrokerRedriveV1(
                request(1L, "11111111-1111-1111-1111-111111111111",
                        "incident recovery"),
                "redrive-key-0001", NOW);

        assertEquals("ACCEPTED", accepted.status());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_redrive_operation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_operation_audit", Integer.class));
        assertEquals(accepted.operationId(), jdbc.queryForObject(
                "SELECT operation_id FROM agent_command_redrive_operation", String.class));
        var pending = new TransactionTemplate(manager).execute(status ->
                dao.lockPendingRedriveOperations(
                        "tenant-a", "client-a", NOW, 0, 10));
        assertEquals(List.of(accepted.operationId()), pending.stream()
                .map(row -> row.getOperationId()).toList());

        var replay = service.acceptBrokerRedriveV1(
                request(1L, "11111111-1111-1111-1111-111111111111",
                        "incident recovery"),
                "redrive-key-0001", NOW);
        assertEquals(accepted.operationId(), replay.operationId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_redrive_operation", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_operation_audit", Integer.class));

        AgentCommandOperationsException conflict = assertThrows(
                AgentCommandOperationsException.class,
                () -> service.acceptBrokerRedriveV1(
                        request(1L, "11111111-1111-1111-1111-111111111111",
                                "different recovery"),
                        "redrive-key-0001", NOW));
        assertEquals(AgentCommandOperationsException.Reason.OPERATION_CONFLICT,
                conflict.reason());

        jdbc.execute("DROP TABLE agent_command_operation_audit");
        AgentCommandOperationsException unavailable = assertThrows(
                AgentCommandOperationsException.class,
                () -> service.acceptBrokerRedriveV1(
                        request(3L, "33333333-3333-3333-3333-333333333333",
                                "second recovery"),
                        "redrive-key-0002", NOW));
        assertEquals(AgentCommandOperationsException.Reason.AUDIT_UNAVAILABLE,
                unavailable.reason());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_redrive_operation", Integer.class));
    }

    private AgentCommandOperationsServiceImpl service() {
        return service(false);
    }

    private AgentCommandOperationsServiceImpl service(boolean asyncRedriveEnabled) {
        return new AgentCommandOperationsServiceImpl(
                dao, gate(), AgentRabbitTopologyManifest.canonical(), null, null,
                new AgentCommandOperationsProperties(
                        true, false, asyncRedriveEnabled, false, 20, 20, null, null),
                manager, java.util.UUID::randomUUID, () -> NOW);
    }

    private AgentCommandOperationRequest request(
            long deliveryId, String messageId, String reason) {
        return new AgentCommandOperationRequest(
                "tenant-a", "client-a", deliveryId, "task-" + deliveryId,
                "agent-a", messageId, "operator-a", null, reason, "INC-42");
    }

    private void insertSource(long id, String messageId, String eventId, boolean legalRoute) {
        AgentCommandDraft draft = draft(id);
        byte[] business = AgentCommandCanonicalCodec.businessBytes(draft);
        byte[] wire = AgentCommandCanonicalCodec.wireBytes(draft, messageId, 1);
        var route = AgentRabbitTopologyManifest.canonical().defaultCommandPublishRoute();
        jdbc.update("""
                INSERT INTO agent_command_delivery(
                    id,command_id,task_id,work_item_id,target_agent_id,command_type,
                    command_payload,command_payload_hash,status,attempt_count,next_retry_at,
                    lease_owner,lease_until,active_message_id,active_attempt,expires_at,last_error,
                    version,replay_parent_message_id,replay_requester_id,replay_approver_id,
                    replay_reason,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, draft.commandId(), draft.taskId(), draft.workItemId(), draft.targetAgentId(),
                draft.commandType(), business, AgentCommandCanonicalCodec.sha256(business),
                "PUBLISHED", 1, null, null, null, messageId, 1, EXPIRES, null, 1,
                null, null, null, null, "tenant-a", "client-a", NOW - 20, NOW - 5);
        jdbc.update("""
                INSERT INTO agent_outbox_event(
                    id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                    destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                    next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                    publisher_confirm_status,confirmed_at,confirm_error,mandatory_return_status,
                    returned_at,return_reply_code,return_reply_text,published_at,last_error,version,
                    replay_parent_message_id,replay_requester_id,replay_approver_id,replay_reason,
                    tenant_id,client_id,create_time,update_time)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, eventId, messageId, draft.commandId(), id, "task", draft.taskId(),
                route.destination(), legalRoute ? route.routingKey() : "agent.command.poison",
                wire, AgentCommandCanonicalCodec.sha256(wire), "PUBLISHED", 1,
                null, null, null, 1, EXPIRES, "ACK", NOW - 10, null, "NOT_RETURNED",
                null, null, null, NOW - 9, null, 1, null, null, null, null,
                "tenant-a", "client-a", NOW - 20, NOW - 4);
    }

    private AgentCommandDraft draft(long id) {
        String taskId = "task-" + id;
        String intentId = "intent-" + id;
        String commandId = AgentCommandCanonicalCodec.hallCommandId(
                "tenant-a", "client-a", taskId, "agent-a", intentId,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        return new AgentCommandDraft(
                1, commandId, taskId, intentId, "tenant-a", "client-a", taskId,
                "work-" + id, "agent-a", AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                ISSUED, EXPIRES, intentId, new AgentHallCommandPayload(
                        "execute", "Execute bounded work", "juyiting",
                        null, null, null, null, false, null));
    }

    private void createSchema() {
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT PRIMARY KEY, command_id VARCHAR(100), task_id VARCHAR(100),
                  work_item_id VARCHAR(100), target_agent_id VARCHAR(100), command_type VARCHAR(64),
                  command_payload BLOB, command_payload_hash BINARY(32), status VARCHAR(32),
                  attempt_count INT, next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT,
                  active_message_id VARCHAR(100), active_attempt INT, expires_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event(
                  id BIGINT PRIMARY KEY, event_id VARCHAR(100), message_id VARCHAR(100), command_id VARCHAR(100),
                  delivery_id BIGINT, aggregate_type VARCHAR(30), aggregate_id VARCHAR(100),
                  destination VARCHAR(100), routing_key VARCHAR(100), wire_payload BLOB,
                  wire_payload_hash BINARY(32), status VARCHAR(32), attempt_count INT,
                  next_retry_at BIGINT, lease_owner VARCHAR(100), lease_until BIGINT, active_attempt INT,
                  expires_at BIGINT, publisher_confirm_status VARCHAR(20), confirmed_at BIGINT,
                  confirm_error VARCHAR(2000), mandatory_return_status VARCHAR(20), returned_at BIGINT,
                  return_reply_code INT, return_reply_text VARCHAR(2000), published_at BIGINT,
                  last_error VARCHAR(2000), version BIGINT, replay_parent_message_id VARCHAR(100),
                  replay_requester_id VARCHAR(100), replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000),
                  tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_consumer_inbox(
                  id BIGINT PRIMARY KEY, consumer_name VARCHAR(100), message_id VARCHAR(100), event_id VARCHAR(100),
                  command_id VARCHAR(100), delivery_id BIGINT, wire_payload BLOB, wire_payload_hash BINARY(32),
                  status VARCHAR(32), result_status VARCHAR(32), attempt_count INT, next_retry_at BIGINT,
                  lease_owner VARCHAR(100), lease_until BIGINT, active_attempt INT, expires_at BIGINT,
                  processed_at BIGINT, last_error VARCHAR(2000), version BIGINT,
                  replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                  replay_approver_id VARCHAR(100), replay_reason VARCHAR(1000), tenant_id VARCHAR(50),
                  client_id VARCHAR(50), create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_operation_audit(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id VARCHAR(100) NOT NULL,
                  phase VARCHAR(20) NOT NULL, operation_type VARCHAR(40) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, target_agent_id VARCHAR(100) NOT NULL,
                  command_id VARCHAR(100), source_message_id VARCHAR(100) NOT NULL,
                  new_message_id VARCHAR(100), delivery_id BIGINT NOT NULL, source_attempt INT,
                  new_attempt INT, wire_hash BINARY(32), requester_id VARCHAR(100) NOT NULL,
                  approver_id VARCHAR(100), reason VARCHAR(1000) NOT NULL,
                  ticket_reference VARCHAR(200) NOT NULL, requested_at BIGINT NOT NULL,
                  completed_at BIGINT, outcome VARCHAR(32) NOT NULL, error_code VARCHAR(200),
                  created_by VARCHAR(100) NOT NULL, created_at BIGINT NOT NULL,
                  UNIQUE(operation_id, phase))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_redrive_operation(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id VARCHAR(100) NOT NULL,
                  delivery_id BIGINT NOT NULL, task_id VARCHAR(100) NOT NULL,
                  target_agent_id VARCHAR(100) NOT NULL, command_id VARCHAR(100) NOT NULL,
                  source_event_id VARCHAR(100) NOT NULL, source_message_id VARCHAR(100) NOT NULL,
                  source_attempt INT NOT NULL, wire_hash BINARY(32) NOT NULL,
                  requester_id VARCHAR(100) NOT NULL, reason VARCHAR(1000) NOT NULL,
                  ticket_reference VARCHAR(200) NOT NULL, outcome_state VARCHAR(32) NOT NULL,
                  settlement_state VARCHAR(32) NOT NULL, error_code VARCHAR(200),
                  requested_at BIGINT NOT NULL, completed_at BIGINT, version BIGINT NOT NULL,
                  disposition_guard INT GENERATED ALWAYS AS
                    (CASE WHEN outcome_state='PENDING' THEN 1 ELSE NULL END),
                  redrive_guard INT GENERATED ALWAYS AS
                    (CASE WHEN settlement_state IN ('SOURCE_REQUEUED','NOT_ACQUIRED')
                          THEN NULL ELSE 1 END),
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                  UNIQUE(tenant_id, client_id, operation_id),
                  UNIQUE(tenant_id, client_id, delivery_id, source_message_id,
                         source_attempt, redrive_guard))
                """);
    }

    private AgentRabbitSafetyGate gate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "secret", "/isolated")),
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-a", "client-a"))));
    }
}
