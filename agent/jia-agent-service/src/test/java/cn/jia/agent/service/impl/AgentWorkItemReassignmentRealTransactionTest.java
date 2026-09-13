package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.AgentWorkItemReassignmentDao;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.dao.impl.AgentWorkItemReassignmentDaoImpl;
import cn.jia.agent.entity.AgentCommandDraft;
import cn.jia.agent.entity.AgentCommandTransportWriteResult;
import cn.jia.agent.entity.AgentHallCommandPayload;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentWorkItemReassignmentRequestDTO;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.mapper.AgentWorkItemReassignmentMapper;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
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

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentWorkItemReassignmentRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_e05_reassignment;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String WORK = "work-1";
    private static final String PREVIOUS = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String COORDINATOR = "agt_cccccccccccccccccccccccccccccccc";
    private static final long NOW = 1_800_000_000_000L;

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentWorkItemReassignmentDao reassignmentDao;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private AgentIdentityService identityService;
    private AgentService agentService;
    private AgentWorkItemLeaseService leaseService;
    private String sourceCommandId;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(JDBC_URL);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        taskDao = new AgentTaskMetaDaoImpl();
        setField(taskDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        workItemDao = new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class));
        reassignmentDao = new AgentWorkItemReassignmentDaoImpl(
                template.getMapper(AgentWorkItemReassignmentMapper.class));
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
        eventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(
                        new AgentTaskEventBroker(), transactionManager));
        mutationTransaction = new AgentTaskMutationTransactionImpl(taskDao, transactionManager);
        identityService = mock(AgentIdentityService.class);
        agentService = mock(AgentService.class);
        leaseService = mock(AgentWorkItemLeaseService.class);
        when(identityService.lockActiveCanonicalAgentIdsInScope(
                anyString(), anyString(), anyString(), any())).thenAnswer(i -> i.getArgument(3));
        when(agentService.requireApiKeyOwnedAgentForUpdate(
                anyString(), anyString(), anyString())).thenAnswer(i -> {
            AgentRuntimeDTO runtime = new AgentRuntimeDTO();
            runtime.setAgentId(i.getArgument(2));
            runtime.setStatus("offline");
            return runtime;
        });
        doNothing().when(agentService).requireHostingNewWork(TENANT, CLIENT, TARGET);
        insertFixture();
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void workItemEventNewCommandOutboxAndReceiptCommitAtomically() {
        AgentWorkItemReassignmentServiceImpl service = service(new JdbcCommandWriter(false));
        var result = service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request());

        assertEquals(TARGET, jdbc.queryForObject(
                "SELECT assignee_agent_id FROM agent_task_work_item", String.class));
        assertEquals("claimed", jdbc.queryForObject(
                "SELECT status FROM agent_task_work_item", String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT attempt_count FROM agent_task_work_item", Integer.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version FROM agent_task_work_item", Long.class));
        assertEquals(1, count("agent_task_event"));
        assertEquals(2, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));
        assertEquals(1, count("agent_work_item_reassignment"));
        assertEquals(result.getCommandId(), jdbc.queryForObject(
                "SELECT command_id FROM agent_work_item_reassignment", String.class));
        assertFalse(jdbc.queryForObject(
                "SELECT lease_fence_sha256 FROM agent_work_item_reassignment", String.class)
                .contains("new-secret-token"));

        var replay = service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request());
        assertEquals(result.getCommandId(), replay.getCommandId());
        assertEquals(1, count("agent_task_event"));
        assertEquals(2, count("agent_command_delivery"));
        assertEquals(1, count("agent_outbox_event"));
        assertEquals(1, count("agent_work_item_reassignment"));
    }

    @Test
    void heartbeatAndReassignmentRaceHasOneExactLeaseCasWinner() throws Exception {
        AgentWorkItemReassignmentServiceImpl service = service(new JdbcCommandWriter(false));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> reassigned = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    service.reassign(TENANT, CLIENT, "operator-1", COORDINATOR,
                            TASK, WORK, "reassign-key-race", request());
                    return true;
                } catch (RuntimeException lost) {
                    return false;
                }
            });
            Future<Integer> heartbeat = pool.submit(() -> {
                ready.countDown();
                start.await();
                AgentTaskWorkItemDTO update = originalWorkItemUpdate();
                update.setLeaseUntil(NOW + 300_000);
                return workItemDao.updateActiveLeaseByVersion(
                        TENANT, CLIENT, TASK, WORK, PREVIOUS, "old-secret-token",
                        "claimed", NOW - 1, 0, NOW - 2, update);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            boolean reassignmentWon = reassigned.get(10, TimeUnit.SECONDS);
            int heartbeatRows = heartbeat.get(10, TimeUnit.SECONDS);
            assertEquals(1, (reassignmentWon ? 1 : 0) + heartbeatRows);
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT version FROM agent_task_work_item", Long.class));
            assertEquals(reassignmentWon ? TARGET : PREVIOUS, jdbc.queryForObject(
                    "SELECT assignee_agent_id FROM agent_task_work_item", String.class));
            assertEquals(reassignmentWon ? 1 : 0, count("agent_work_item_reassignment"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outboxFailureRollsBackLeaseEventDeliveryAndReceipt() {
        AgentWorkItemReassignmentServiceImpl service = service(new JdbcCommandWriter(true));
        assertThrows(IllegalStateException.class, () -> service.reassign(
                TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request()));

        assertRollbackBaseline();
    }

    @Test
    void eventFailureRollsBackLeaseBeforeAnyCommandOrReceipt() {
        AgentTaskEventWriter failingEvent = command -> {
            throw new IllegalStateException("synthetic event failure");
        };
        AgentWorkItemReassignmentServiceImpl service = service(
                new JdbcCommandWriter(false), failingEvent, reassignmentDao);
        assertThrows(IllegalStateException.class, () -> service.reassign(
                TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request()));
        assertRollbackBaseline();
    }

    @Test
    void receiptFailureRollsBackLeaseEventNewCommandAndOutbox() {
        AgentWorkItemReassignmentDao failingReceipt = new AgentWorkItemReassignmentDao() {
            @Override
            public cn.jia.agent.entity.AgentWorkItemReassignmentEntity
                    findByReassignmentIdForUpdate(String tenantId, String clientId,
                    String taskId, String workItemId, String reassignmentId) {
                return reassignmentDao.findByReassignmentIdForUpdate(
                        tenantId, clientId, taskId, workItemId, reassignmentId);
            }
            @Override
            public cn.jia.agent.entity.AgentWorkItemReassignmentEntity
                    findLatestByWorkItemForUpdate(String tenantId, String clientId,
                    String taskId, String workItemId) {
                return reassignmentDao.findLatestByWorkItemForUpdate(
                        tenantId, clientId, taskId, workItemId);
            }
            @Override
            public cn.jia.agent.entity.AgentCommandDeliveryEntity findSourceCommand(
                    String tenantId, String clientId, String commandId) {
                return reassignmentDao.findSourceCommand(tenantId, clientId, commandId);
            }
            @Override
            public int insert(cn.jia.agent.entity.AgentWorkItemReassignmentEntity receipt) {
                throw new IllegalStateException("synthetic receipt failure");
            }
        };
        AgentWorkItemReassignmentServiceImpl service = service(
                new JdbcCommandWriter(false), eventWriter, failingReceipt);
        assertThrows(IllegalStateException.class, () -> service.reassign(
                TENANT, CLIENT, "operator-1", COORDINATOR,
                TASK, WORK, "reassign-key-0001", request()));
        assertRollbackBaseline();
    }

    private void assertRollbackBaseline() {
        assertEquals(PREVIOUS, jdbc.queryForObject(
                "SELECT assignee_agent_id FROM agent_task_work_item", String.class));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT version FROM agent_task_work_item", Long.class));
        assertEquals(0, count("agent_task_event"));
        assertEquals(1, count("agent_command_delivery"));
        assertEquals(0, count("agent_outbox_event"));
        assertEquals(0, count("agent_work_item_reassignment"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta", Long.class));
    }

    private AgentWorkItemReassignmentServiceImpl service(AgentCommandTransportWriter writer) {
        return service(writer, eventWriter, reassignmentDao);
    }

    private AgentWorkItemReassignmentServiceImpl service(
            AgentCommandTransportWriter writer, AgentTaskEventWriter events,
            AgentWorkItemReassignmentDao receipts) {
        return new AgentWorkItemReassignmentServiceImpl(
                receipts, memberDao, workItemDao, mutationTransaction,
                identityService, agentService, events, writer, leaseService,
                () -> NOW, () -> "new-secret-token", 300_000);
    }

    private AgentTaskWorkItemDTO originalWorkItemUpdate() {
        AgentTaskWorkItemDTO value = new AgentTaskWorkItemDTO();
        value.setWorkItemId(WORK);
        value.setTaskId(TASK);
        value.setTitle("Implement E05");
        value.setDescription("One CAS");
        value.setWorkType("implementation");
        value.setAssigneeAgentId(PREVIOUS);
        value.setStatus("claimed");
        value.setPriority(0);
        value.setRequiredItem(true);
        value.setLeaseToken("old-secret-token");
        value.setLeaseUntil(NOW - 1);
        value.setAttemptCount(0);
        value.setMaxAttempts(3);
        value.setVersion(0L);
        return value;
    }

    private AgentWorkItemReassignmentRequestDTO request() {
        AgentWorkItemReassignmentRequestDTO value = new AgentWorkItemReassignmentRequestDTO();
        value.setExpectedTaskVersion(7L);
        value.setExpectedWorkItemVersion(0L);
        value.setExpectedPreviousAgentId(PREVIOUS);
        value.setTargetAgentId(TARGET);
        value.setSourceCommandId(sourceCommandId);
        value.setReason("authoritative_lease_expired");
        return value;
    }

    private void insertFixture() {
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id,reward_status,coordinator_agent_id,task_version,current_event_version,
                 tenant_id,client_id,create_time,update_time)
                VALUES (?, 'running', ?, 7, 0, ?, ?, 1, 1)
                """, TASK, COORDINATOR, TENANT, CLIENT);
        for (String[] member : new String[][] {
                {PREVIOUS, "worker"}, {TARGET, "worker"}, {COORDINATOR, "coordinator"}}) {
            jdbc.update("""
                    INSERT INTO agent_task_member
                    (task_id,agent_id,member_role,member_status,assignment_source,version,
                     tenant_id,client_id,create_time,update_time)
                    VALUES (?, ?, ?, 'working', 'manual', 0, ?, ?, 1, 1)
                    """, TASK, member[0], member[1], TENANT, CLIENT);
        }
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id,task_id,title,description,work_type,assignee_agent_id,status,
                 priority,required_item,lease_token,lease_until,attempt_count,max_attempts,version,
                 tenant_id,client_id,create_time,update_time)
                VALUES (?, ?, 'Implement E05', 'One CAS', 'implementation', ?, 'claimed',
                        0, TRUE, 'old-secret-token', ?, 0, 3, 0, ?, ?, 1, 1)
                """, WORK, TASK, PREVIOUS, NOW - 1, TENANT, CLIENT);
        long issued = NOW - 600_000;
        String intent = "source-intent";
        sourceCommandId = AgentCommandCanonicalCodec.hallCommandId(
                TENANT, CLIENT, TASK, PREVIOUS, intent,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE);
        AgentCommandDraft source = new AgentCommandDraft(
                1, sourceCommandId, TASK, intent, TENANT, CLIENT, TASK, WORK, PREVIOUS,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE, issued,
                issued + AgentCommandCanonicalCodec.HALL_COMMAND_TTL_MILLIS, intent,
                new AgentHallCommandPayload("work_item_execute", "Execute original work item",
                        "juyiting", null, null, null, "autonomous", false, null));
        byte[] bytes = AgentCommandCanonicalCodec.businessBytes(source);
        jdbc.update("""
                INSERT INTO agent_command_delivery
                (command_id,task_id,work_item_id,target_agent_id,command_type,
                 command_payload,command_payload_hash,status,attempt_count,version,
                 tenant_id,client_id,create_time,update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DEAD', 2147483647, 0, ?, ?, ?, ?)
                """, sourceCommandId, TASK, WORK, PREVIOUS,
                AgentProtocolConstants.COMMAND_WORK_ITEM_EXECUTE,
                bytes, AgentCommandCanonicalCodec.sha256(bytes), TENANT, CLIENT, issued, issued);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        configuration.addMapper(AgentWorkItemReassignmentMapper.class);
        configuration.addMapper(AgentTaskEventMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        return bean.getObject();
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL, coordinator_agent_id VARCHAR(100),
                    task_version BIGINT NOT NULL, current_event_version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id,client_id,task_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL, member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL, assignment_source VARCHAR(20),
                    version BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, work_item_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL, title VARCHAR(500) NOT NULL,
                    description VARCHAR(2000), work_type VARCHAR(100) NOT NULL,
                    required_abilities VARCHAR(2000), assignee_agent_id VARCHAR(100),
                    status VARCHAR(20) NOT NULL, priority INT NOT NULL, required_item BOOLEAN NOT NULL,
                    dependency_json VARCHAR(4000), lease_token VARCHAR(100), lease_until BIGINT,
                    attempt_count INT NOT NULL, max_attempts INT NOT NULL,
                    result_artifact_id VARCHAR(100), submitted_at BIGINT, completed_at BIGINT,
                    version BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id,client_id,work_item_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(100) NOT NULL,
                    event_version BIGINT NOT NULL, event_id VARCHAR(100) NOT NULL,
                    event_type VARCHAR(64) NOT NULL, actor_type VARCHAR(20) NOT NULL,
                    actor_id VARCHAR(100), aggregate_type VARCHAR(30) NOT NULL,
                    aggregate_id VARCHAR(100) NOT NULL, event_json CLOB NOT NULL,
                    occurred_at BIGINT NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL, create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id,client_id,task_id,event_version),
                    UNIQUE (tenant_id,client_id,event_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_command_delivery (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, command_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100), work_item_id VARCHAR(100), target_agent_id VARCHAR(100),
                    command_type VARCHAR(64), command_payload BLOB, command_payload_hash BINARY(32),
                    status VARCHAR(30), attempt_count INT, next_retry_at BIGINT,
                    lease_owner VARCHAR(100), lease_until BIGINT, active_message_id VARCHAR(100),
                    active_attempt INT, expires_at BIGINT, last_error VARCHAR(255), version BIGINT,
                    replay_parent_message_id VARCHAR(100), replay_requester_id VARCHAR(100),
                    replay_approver_id VARCHAR(100), replay_reason VARCHAR(2000),
                    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id,client_id,command_id))
                """);
        jdbc.execute("""
                CREATE TABLE agent_outbox_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(100),
                    message_id VARCHAR(100), command_id VARCHAR(100), delivery_id BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50), create_time BIGINT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_work_item_reassignment (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY, reassignment_id VARCHAR(100) NOT NULL,
                    request_sha256 CHAR(64) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100) NOT NULL, operator_subject VARCHAR(100) NOT NULL,
                    coordinator_agent_id VARCHAR(100) NOT NULL, previous_agent_id VARCHAR(100) NOT NULL,
                    target_agent_id VARCHAR(100) NOT NULL, source_command_id VARCHAR(100) NOT NULL,
                    command_id VARCHAR(100) NOT NULL, message_id VARCHAR(100) NOT NULL,
                    outbox_event_id VARCHAR(100) NOT NULL, expected_work_item_version BIGINT NOT NULL,
                    result_work_item_version BIGINT NOT NULL, task_version BIGINT NOT NULL,
                    lease_fence_sha256 CHAR(64) NOT NULL, previous_lease_until BIGINT NOT NULL,
                    lease_until BIGINT NOT NULL, attempt_count INT NOT NULL, max_attempts INT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
                    UNIQUE (tenant_id,client_id,reassignment_id),
                    UNIQUE (tenant_id,client_id,command_id))
                """);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private final class JdbcCommandWriter implements AgentCommandTransportWriter {
        private final AtomicBoolean failOutbox;
        private JdbcCommandWriter(boolean failOutbox) {
            this.failOutbox = new AtomicBoolean(failOutbox);
        }
        @Override
        public AgentCommandTransportWriteResult write(AgentCommandDraft draft) {
            throw new UnsupportedOperationException();
        }
        @Override
        public AgentCommandTransportWriteResult writeAuthorizedHall(
                AgentCommandDraft draft, String callerAgentId) {
            byte[] bytes = AgentCommandCanonicalCodec.businessBytes(draft);
            jdbc.update("""
                    INSERT INTO agent_command_delivery
                    (command_id,task_id,work_item_id,target_agent_id,command_type,
                     command_payload,command_payload_hash,status,attempt_count,version,
                     tenant_id,client_id,create_time,update_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 1, 0, ?, ?, ?, ?)
                    """, draft.commandId(), draft.taskId(), draft.workItemId(),
                    draft.targetAgentId(), draft.commandType(), bytes,
                    AgentCommandCanonicalCodec.sha256(bytes), draft.tenantId(), draft.clientId(),
                    draft.issuedAt(), draft.issuedAt());
            if (failOutbox.get()) throw new IllegalStateException("synthetic outbox failure");
            Long delivery = jdbc.queryForObject(
                    "SELECT id FROM agent_command_delivery WHERE command_id=?", Long.class,
                    draft.commandId());
            jdbc.update("INSERT INTO agent_outbox_event "
                            + "(event_id,message_id,command_id,delivery_id,tenant_id,client_id,create_time) "
                            + "VALUES ('outbox-new','msg-new',?,?,?,?,?)",
                    draft.commandId(), delivery, draft.tenantId(), draft.clientId(), draft.issuedAt());
            return new AgentCommandTransportWriteResult(
                    delivery, draft.commandId(), "msg-new", "outbox-new", false);
        }
    }
}
