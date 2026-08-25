package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.entity.AgentCommandDeliveryEntity;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentOutboxEventEntity;
import cn.jia.agent.entity.AgentTaskInvitePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentCommandTransportRealTransactionTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private JdbcTransportDao dao;
    private AgentCommandTransportWriterImpl writer;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:d02_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        source.setUsername("sa");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE task_domain(task_id VARCHAR(100) PRIMARY KEY, state VARCHAR(20))");
        jdbc.execute("CREATE TABLE task_event(event_id VARCHAR(100) PRIMARY KEY, task_id VARCHAR(100))");
        jdbc.execute("""
                CREATE TABLE agent_command_delivery(
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, command_id VARCHAR(100) NOT NULL,
                  task_id VARCHAR(100) NOT NULL, work_item_id VARCHAR(100), target_agent_id VARCHAR(100) NOT NULL,
                  command_type VARCHAR(64) NOT NULL, command_payload BLOB NOT NULL, command_payload_hash BINARY(32) NOT NULL,
                  status VARCHAR(32) NOT NULL, attempt_count INT NOT NULL, next_retry_at BIGINT, lease_owner VARCHAR(100),
                  lease_until BIGINT, active_message_id VARCHAR(100), active_attempt INT NOT NULL, expires_at BIGINT NOT NULL,
                  last_error VARCHAR(2000), version BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
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
                  mandatory_return_status VARCHAR(20) NOT NULL, last_error VARCHAR(2000), version BIGINT NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                  UNIQUE(tenant_id,client_id,event_id))
                """);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        dao = new JdbcTransportDao(jdbc);
        AtomicInteger ids = new AtomicInteger();
        writer = new AgentCommandTransportWriterImpl(dao, gate(), manager,
                () -> new UUID(0, ids.incrementAndGet()));
    }

    @Test
    void domainEventDeliveryAndOutboxCommitAsOneTransaction() {
        assign(Failure.NONE, draft("Task One"));
        assertFacts(1, 1, 1, 1);
    }

    @Test
    void eventDeliveryOrOutboxFailureRollsBackEveryFact() {
        assertThrows(IllegalStateException.class, () -> assign(Failure.EVENT, draft("Task One")));
        assertFacts(0, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> assign(Failure.DELIVERY, draft("Task One")));
        assertFacts(0, 0, 0, 0);
        assertThrows(IllegalStateException.class, () -> assign(Failure.OUTBOX, draft("Task One")));
        assertFacts(0, 0, 0, 0);
    }

    @Test
    void exactDuplicateAddsNoOutboxAndConflictingBytesFailClosed() {
        writer.write(draft("Task One"));
        writer.write(draft("Task One"));
        assertEquals(1, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));
        assertThrows(IllegalStateException.class, () -> writer.write(draft("Changed title")));
        assertEquals(1, count("agent_outbox_event"));
    }

    private void assign(Failure failure, AgentCommandDraft draft) {
        dao.failure = failure;
        try {
            transactions.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO task_domain(task_id,state) VALUES (?,?)", "task-1", "assigned");
                if (failure == Failure.EVENT) throw new IllegalStateException("forced event failure");
                jdbc.update("INSERT INTO task_event(event_id,task_id) VALUES (?,?)", "evt-real", "task-1");
                writer.write(draft);
            });
        } finally {
            dao.failure = Failure.NONE;
        }
    }

    private void assertFacts(int domain, int event, int delivery, int outbox) {
        assertEquals(domain, count("task_domain"));
        assertEquals(event, count("task_event"));
        assertEquals(delivery, count("agent_command_delivery"));
        assertEquals(outbox, count("agent_outbox_event"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private AgentCommandDraft draft(String title) {
        String commandId = AgentCommandCanonicalCodec.taskInviteCommandId(
                "tenant-a", "client-a", "task-1", "agent-1");
        return new AgentCommandDraft(1, commandId, "task-1", "evt-real", "tenant-a", "client-a",
                "task-1", null, "agent-1", AgentProtocolConstants.COMMAND_TASK_INVITE,
                1000L, 3601000L, new AgentTaskInvitePayload(
                "task_briefing", "宋江首领已完成悬赏分派，请按职责协作推进。",
                "阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。",
                title, List.of("planning"), "agent-1", List.of("agent-1"), "coordinator",
                "回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。",
                "juyiting"));
    }

    private AgentRabbitSafetyGate gate() {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(false),
                new AgentRabbitSafetyProperties.RabbitPublish(false),
                new AgentRabbitSafetyProperties.RabbitConsume(false),
                new AgentRabbitSafetyProperties.RabbitDispatch(false), null));
    }

    enum Failure { NONE, EVENT, DELIVERY, OUTBOX }

    static final class JdbcTransportDao implements AgentCommandTransportDao {
        private final JdbcTemplate jdbc;
        private Failure failure = Failure.NONE;
        JdbcTransportDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public AgentCommandDeliveryEntity lockDelivery(String tenantId, String clientId, String commandId) {
            List<AgentCommandDeliveryEntity> rows = jdbc.query("""
                    SELECT id,command_id,task_id,work_item_id,target_agent_id,command_type,
                           command_payload,command_payload_hash,expires_at,active_message_id,tenant_id,client_id
                    FROM agent_command_delivery WHERE tenant_id=? AND client_id=? AND command_id=? FOR UPDATE
                    """, (rs, row) -> {
                AgentCommandDeliveryEntity entity = new AgentCommandDeliveryEntity()
                        .setId(rs.getLong("id")).setCommandId(rs.getString("command_id"))
                        .setTaskId(rs.getString("task_id")).setWorkItemId(rs.getString("work_item_id"))
                        .setTargetAgentId(rs.getString("target_agent_id")).setCommandType(rs.getString("command_type"))
                        .setCommandPayload(rs.getBytes("command_payload"))
                        .setCommandPayloadHash(rs.getBytes("command_payload_hash"))
                        .setExpiresAt(rs.getLong("expires_at")).setActiveMessageId(rs.getString("active_message_id"));
                entity.setTenantId(rs.getString("tenant_id")); entity.setClientId(rs.getString("client_id"));
                return entity;
            }, tenantId, clientId, commandId);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public List<AgentOutboxEventEntity> lockActiveOutboxes(
                String tenantId, String clientId, long deliveryId, String messageId) {
            return jdbc.query("""
                    SELECT id,event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                           destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,
                           next_retry_at,lease_owner,lease_until,active_attempt,expires_at,
                           publisher_confirm_status,mandatory_return_status,last_error,version,
                           tenant_id,client_id,create_time,update_time
                    FROM agent_outbox_event
                    WHERE tenant_id=? AND client_id=? AND delivery_id=? AND message_id=?
                    ORDER BY id LIMIT 2 FOR UPDATE
                    """, (rs, row) -> {
                AgentOutboxEventEntity entity = new AgentOutboxEventEntity()
                        .setId(rs.getLong("id"))
                        .setEventId(rs.getString("event_id"))
                        .setMessageId(rs.getString("message_id"))
                        .setCommandId(rs.getString("command_id"))
                        .setDeliveryId(rs.getLong("delivery_id"))
                        .setAggregateType(rs.getString("aggregate_type"))
                        .setAggregateId(rs.getString("aggregate_id"))
                        .setDestination(rs.getString("destination"))
                        .setRoutingKey(rs.getString("routing_key"))
                        .setWirePayload(rs.getBytes("wire_payload"))
                        .setWirePayloadHash(rs.getBytes("wire_payload_hash"))
                        .setStatus(rs.getString("status"))
                        .setAttemptCount(rs.getInt("attempt_count"))
                        .setNextRetryAt(rs.getObject("next_retry_at", Long.class))
                        .setLeaseOwner(rs.getString("lease_owner"))
                        .setLeaseUntil(rs.getObject("lease_until", Long.class))
                        .setActiveAttempt(rs.getInt("active_attempt"))
                        .setExpiresAt(rs.getLong("expires_at"))
                        .setPublisherConfirmStatus(rs.getString("publisher_confirm_status"))
                        .setMandatoryReturnStatus(rs.getString("mandatory_return_status"))
                        .setLastError(rs.getString("last_error"))
                        .setVersion(rs.getLong("version"));
                entity.setTenantId(rs.getString("tenant_id"));
                entity.setClientId(rs.getString("client_id"));
                entity.setCreateTime(rs.getLong("create_time"));
                entity.setUpdateTime(rs.getLong("update_time"));
                return entity;
            }, tenantId, clientId, deliveryId, messageId);
        }

        @Override
        public int promoteShadowDelivery(
                AgentCommandDeliveryEntity delivery, String marker, long now) {
            return jdbc.update("""
                    UPDATE agent_command_delivery
                    SET status='PENDING',last_error=?,version=version+1,update_time=?
                    WHERE id=? AND tenant_id=? AND client_id=? AND command_id=?
                      AND active_message_id=? AND status='DEAD' AND last_error=?
                      AND active_attempt=? AND attempt_count=? AND version=?
                    """, marker, now, delivery.getId(), delivery.getTenantId(),
                    delivery.getClientId(), delivery.getCommandId(),
                    delivery.getActiveMessageId(), delivery.getLastError(),
                    delivery.getActiveAttempt(), delivery.getAttemptCount(),
                    delivery.getVersion());
        }

        @Override
        public int promoteShadowOutbox(
                AgentOutboxEventEntity outbox, String marker, long now) {
            return jdbc.update("""
                    UPDATE agent_outbox_event
                    SET status='PENDING',last_error=?,version=version+1,update_time=?
                    WHERE id=? AND tenant_id=? AND client_id=? AND event_id=?
                      AND message_id=? AND command_id=? AND delivery_id=?
                      AND status='DEAD' AND last_error=? AND active_attempt=?
                      AND attempt_count=? AND version=?
                    """, marker, now, outbox.getId(), outbox.getTenantId(),
                    outbox.getClientId(), outbox.getEventId(), outbox.getMessageId(),
                    outbox.getCommandId(), outbox.getDeliveryId(), outbox.getLastError(),
                    outbox.getActiveAttempt(), outbox.getAttemptCount(), outbox.getVersion());
        }

        @Override
        public int insertDelivery(AgentCommandDeliveryEntity d) {
            if (failure == Failure.DELIVERY) throw new IllegalStateException("forced delivery failure");
            KeyHolder keys = new GeneratedKeyHolder();
            int rows = jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO agent_command_delivery(command_id,task_id,work_item_id,target_agent_id,command_type,
                        command_payload,command_payload_hash,status,attempt_count,active_message_id,active_attempt,
                        expires_at,last_error,version,tenant_id,client_id,create_time,update_time)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                int i=1; ps.setString(i++,d.getCommandId()); ps.setString(i++,d.getTaskId()); ps.setString(i++,d.getWorkItemId());
                ps.setString(i++,d.getTargetAgentId()); ps.setString(i++,d.getCommandType()); ps.setBytes(i++,d.getCommandPayload());
                ps.setBytes(i++,d.getCommandPayloadHash()); ps.setString(i++,d.getStatus()); ps.setInt(i++,d.getAttemptCount());
                ps.setString(i++,d.getActiveMessageId()); ps.setInt(i++,d.getActiveAttempt()); ps.setLong(i++,d.getExpiresAt());
                ps.setString(i++,d.getLastError()); ps.setLong(i++,d.getVersion()); ps.setString(i++,d.getTenantId());
                ps.setString(i++,d.getClientId()); ps.setLong(i++,d.getCreateTime()); ps.setLong(i,d.getUpdateTime()); return ps;
            }, keys);
            d.setId(keys.getKey().longValue()); return rows;
        }

        @Override
        public int insertOutbox(AgentOutboxEventEntity o) {
            if (failure == Failure.OUTBOX) throw new IllegalStateException("forced outbox failure");
            return jdbc.update("""
                    INSERT INTO agent_outbox_event(event_id,message_id,command_id,delivery_id,aggregate_type,aggregate_id,
                    destination,routing_key,wire_payload,wire_payload_hash,status,attempt_count,active_attempt,expires_at,
                    publisher_confirm_status,mandatory_return_status,last_error,version,tenant_id,client_id,create_time,update_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, o.getEventId(),o.getMessageId(),o.getCommandId(),o.getDeliveryId(),o.getAggregateType(),o.getAggregateId(),
                    o.getDestination(),o.getRoutingKey(),o.getWirePayload(),o.getWirePayloadHash(),o.getStatus(),o.getAttemptCount(),
                    o.getActiveAttempt(),o.getExpiresAt(),o.getPublisherConfirmStatus(),o.getMandatoryReturnStatus(),o.getLastError(),
                    o.getVersion(),o.getTenantId(),o.getClientId(),o.getCreateTime(),o.getUpdateTime());
        }
    }
}
