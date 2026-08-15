package cn.jia.chat.service;

import cn.jia.agent.common.TaskEventType;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.entity.AgentTaskEventWriteCommand;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.impl.AgentTaskCollaborationAccessServiceImpl;
import cn.jia.agent.service.impl.AgentTaskMutationTransactionImpl;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.dao.impl.AgentTaskThreadDaoImpl;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatMessageDaoImpl;
import cn.jia.chat.entity.AgentTaskThreadDTO;
import cn.jia.chat.entity.AgentTaskThreadEntity;
import cn.jia.chat.entity.AgentTaskThreadMessageCreateDTO;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.mapper.AgentTaskThreadMapper;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.service.impl.AgentTaskThreadCreationTransaction;
import cn.jia.chat.service.impl.AgentTaskThreadServiceImpl;
import cn.jia.common.dao.BaseDaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B07 real H2/MyBatis transaction test. It forces two callers past the initial
 * read together and verifies the duplicate loser rolls back its conversation.
 */
class AgentTaskThreadRealDatabaseTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b07_thread;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                    + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String OUTSIDER = "agt_cccccccccccccccccccccccccccccccc";

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private AgentTaskThreadService service;
    private AgentTaskThreadDao realThreadDao;
    private ChatConversationDao conversationDao;
    private ChatMessageDao messageDao;
    private AgentTaskCollaborationAccessService accessService;
    private AgentService agentService;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        SqlSessionFactory sqlSessionFactory = createSqlSessionFactory();
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory);

        AgentTaskMetaDaoImpl metaDao = new AgentTaskMetaDaoImpl();
        setBaseMapper(metaDao, template.getMapper(AgentTaskMetaMapper.class));
        AgentTaskMetaDao taskMetaDao = metaDao;
        AgentTaskMemberDao memberDao = new AgentTaskMemberDaoImpl(
                template.getMapper(AgentTaskMemberMapper.class));

        ChatConversationDaoImpl conversationDaoImpl = new ChatConversationDaoImpl();
        setBaseMapper(conversationDaoImpl, template.getMapper(ChatConversationMapper.class));
        conversationDao = conversationDaoImpl;
        ChatMessageDaoImpl messageDaoImpl = new ChatMessageDaoImpl();
        setBaseMapper(messageDaoImpl, template.getMapper(ChatMessageMapper.class));
        messageDao = messageDaoImpl;
        realThreadDao = new AgentTaskThreadDaoImpl(
                template.getMapper(AgentTaskThreadMapper.class));

        transactionManager = new DataSourceTransactionManager(dataSource);
        mutationTransaction = new AgentTaskMutationTransactionImpl(taskMetaDao, transactionManager);
        eventWriter = mock(AgentTaskEventWriter.class);
        accessService = new AgentTaskCollaborationAccessServiceImpl(taskMetaDao, memberDao);
        agentService = mock(AgentService.class);
        when(agentService.requireApiKeyOwnedAgent(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> runtime(invocation.getArgument(2)));
        when(agentService.requireApiKeyOwnedAgentForUpdate(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> runtime(invocation.getArgument(2)));
        AgentTaskThreadCreationTransaction creation = transactionalCreation(
                new AgentTaskThreadCreationTransaction(
                        realThreadDao, conversationDao, messageDao, agentService, accessService,
                        mutationTransaction, eventWriter));
        service = new AgentTaskThreadServiceImpl(
                realThreadDao, conversationDao, messageDao, agentService, accessService, creation);

        insertTask(TENANT, CLIENT, TASK);
        insertMember(TENANT, CLIENT, TASK, AGENT_A, "working");
        insertMember(TENANT, CLIENT, TASK, AGENT_B, "accepted");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void concurrentCreateIsIdempotentRollsBackOrphanAndSharesMessages() throws Exception {
        AgentTaskThreadDao barrierThreadDao = new FirstReadBarrierThreadDao(realThreadDao);
        AgentTaskThreadCreationTransaction creation = transactionalCreation(
                new AgentTaskThreadCreationTransaction(
                        barrierThreadDao, conversationDao, messageDao, agentService, accessService,
                        mutationTransaction, eventWriter));
        AgentTaskThreadService concurrentService = new AgentTaskThreadServiceImpl(
                barrierThreadDao, conversationDao, messageDao,
                agentService, accessService, creation);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskThreadDTO> first = executor.submit(() ->
                    concurrentService.getOrCreateTeamThread(
                            TENANT, CLIENT, TASK, AGENT_A, "Team A"));
            Future<AgentTaskThreadDTO> second = executor.submit(() ->
                    concurrentService.getOrCreateTeamThread(
                            TENANT, CLIENT, TASK, AGENT_B, "Team B"));

            AgentTaskThreadDTO a = first.get(20, TimeUnit.SECONDS);
            AgentTaskThreadDTO b = second.get(20, TimeUnit.SECONDS);
            assertEquals(a.getConversationId(), b.getConversationId());
            assertEquals(1, count("agent_task_thread"));
            assertEquals(1, count("chat_conversation"),
                    "duplicate loser conversation must roll back with its binding insert");

            AgentTaskThreadMessageCreateDTO message = new AgentTaskThreadMessageCreateDTO();
            message.setActorAgentId(AGENT_A);
            message.setContent("implementation is 50% complete");
            concurrentService.appendTeamMessage(TENANT, CLIENT, TASK, message);

            var shared = concurrentService.listTeamMessages(
                    TENANT, CLIENT, TASK, AGENT_B, 20);
            assertEquals(1, shared.size());
            assertEquals("implementation is 50% complete", shared.getFirst().getContent());
            assertEquals("agent", shared.getFirst().getSenderType());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void historicalReadOnlyAndAllCrossScopePathsFailClosed() {
        AgentTaskThreadDTO thread = service.getOrCreateTeamThread(
                TENANT, CLIENT, TASK, AGENT_A, null);
        AgentTaskThreadMessageCreateDTO initial = new AgentTaskThreadMessageCreateDTO();
        initial.setActorAgentId(AGENT_A);
        initial.setContent("finished");
        service.appendTeamMessage(TENANT, CLIENT, TASK, initial);

        jdbc.update("""
                UPDATE agent_task_member SET member_status = 'done'
                WHERE tenant_id = ? AND client_id = ? AND task_id = ? AND agent_id = ?
                """, TENANT, CLIENT, TASK, AGENT_A);
        assertEquals(1, service.listTeamMessages(
                TENANT, CLIENT, TASK, AGENT_A, 20).size());
        AgentTaskThreadMessageCreateDTO late = new AgentTaskThreadMessageCreateDTO();
        late.setActorAgentId(AGENT_A);
        late.setContent("late mutation");
        assertUnavailable(() -> service.appendTeamMessage(TENANT, CLIENT, TASK, late));

        assertUnavailable(() -> service.getTeamThread(TENANT, CLIENT, TASK, OUTSIDER));
        assertUnavailable(() -> service.getTeamThread("tenant-b", CLIENT, TASK, AGENT_B));
        assertUnavailable(() -> service.getTeamThread(TENANT, "client-b", TASK, AGENT_B));
        assertUnavailable(() -> service.getTeamThread(TENANT, CLIENT, "task-2", AGENT_B));

        assertEquals(thread.getConversationId(), jdbc.queryForObject(
                "SELECT conversation_id FROM agent_task_thread", String.class));
        assertEquals(1, count("chat_message"));
    }

    @Test
    void taskAndMemberLocksHoldConcurrentRevocationUntilMessageCommit() throws Exception {
        service.getOrCreateTeamThread(TENANT, CLIENT, TASK, AGENT_A, null);
        CountDownLatch ownershipLocked = new CountDownLatch(1);
        CountDownLatch continueWrite = new CountDownLatch(1);
        when(agentService.requireApiKeyOwnedAgentForUpdate(CLIENT, TENANT, AGENT_A))
                .thenAnswer(invocation -> {
                    ownershipLocked.countDown();
                    if (!continueWrite.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting for revocation");
                    }
                    return runtime(AGENT_A);
                });

        AgentTaskThreadMessageCreateDTO message = new AgentTaskThreadMessageCreateDTO();
        message.setActorAgentId(AGENT_A);
        message.setContent("commits before the waiting revocation");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> append = executor.submit(() ->
                    service.appendTeamMessage(TENANT, CLIENT, TASK, message));
            assertTrue(ownershipLocked.await(10, TimeUnit.SECONDS));
            Future<Integer> revoke = executor.submit(() -> jdbc.update("""
                    UPDATE agent_task_member SET member_status = 'left'
                    WHERE tenant_id = ? AND client_id = ? AND task_id = ? AND agent_id = ?
                    """, TENANT, CLIENT, TASK, AGENT_A));
            Thread.sleep(150L);
            assertTrue(!revoke.isDone(), "member revocation must wait for the root/member locks");
            continueWrite.countDown();
            append.get(20, TimeUnit.SECONDS);
            assertEquals(1, revoke.get(20, TimeUnit.SECONDS));
            assertEquals(1, count("chat_message"));
            assertEquals("left", jdbc.queryForObject(
                    "SELECT member_status FROM agent_task_member WHERE agent_id=?",
                    String.class, AGENT_A));
        } finally {
            continueWrite.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void threadEventAppendFailureRollsBackConversationAndBinding() {
        doAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            if (TaskEventType.THREAD_CREATED.equals(command.getEventType())) {
                throw new IllegalStateException("forced thread event append failure");
            }
            return null;
        }).when(eventWriter).append(org.mockito.ArgumentMatchers.any());

        assertThrows(AgentTaskThreadException.class,
                () -> service.getOrCreateTeamThread(TENANT, CLIENT, TASK, AGENT_A, null));

        assertEquals(0, count("agent_task_thread"));
        assertEquals(0, count("chat_conversation"));
    }

    @Test
    void messageEventAppendFailureRollsBackImplicitThreadConversationAndMessage() {
        doAnswer(invocation -> {
            AgentTaskEventWriteCommand command = invocation.getArgument(0);
            if (TaskEventType.MESSAGE_POSTED.equals(command.getEventType())) {
                throw new IllegalStateException("forced event append failure");
            }
            return null;
        }).when(eventWriter).append(org.mockito.ArgumentMatchers.any());

        AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
        request.setActorAgentId(AGENT_A);
        request.setContent("must roll back with its event");
        assertThrows(AgentTaskThreadException.class,
                () -> service.appendTeamMessage(TENANT, CLIENT, TASK, request));

        assertEquals(0, count("agent_task_thread"));
        assertEquals(0, count("chat_conversation"));
        assertEquals(0, count("chat_message"));
    }

    @Test
    void outerRollbackRemovesImplicitThreadConversationAndMessage() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            AgentTaskThreadMessageCreateDTO request = new AgentTaskThreadMessageCreateDTO();
            request.setActorAgentId(AGENT_A);
            request.setContent("outer rollback");
            service.appendTeamMessage(TENANT, CLIENT, TASK, request);
            status.setRollbackOnly();
        });

        assertEquals(0, count("agent_task_thread"));
        assertEquals(0, count("chat_conversation"));
        assertEquals(0, count("chat_message"));
    }

    @Test
    void scopedLatestMessageReadHasStableOrderingForEqualTimestamps() {
        AgentTaskThreadDTO thread = service.getOrCreateTeamThread(
                TENANT, CLIENT, TASK, AGENT_A, null);
        for (String content : List.of("one", "two", "three")) {
            jdbc.update("""
                    INSERT INTO chat_message
                    (conversation_id, message_type, content, metadata, create_time, update_time,
                     client_id, tenant_id, jiacn, sync_status, conversation_type, sender_type, sender_name)
                    VALUES (?, 'ASSISTANT', ?, '{}', 100, 100, ?, ?, ?, 'PENDING', 'juyiting', 'agent', 'test')
                    """, thread.getConversationId(), content, CLIENT, TENANT, TENANT);
        }

        var latest = service.listTeamMessages(TENANT, CLIENT, TASK, AGENT_B, 2);
        assertEquals(List.of("two", "three"),
                latest.stream().map(item -> item.getContent()).toList());
        assertTrue(latest.get(0).getMessageId() < latest.get(1).getMessageId());
    }

    private AgentRuntimeDTO runtime(String agentId) {
        AgentRuntimeDTO runtime = new AgentRuntimeDTO();
        runtime.setAgentId(agentId);
        runtime.setName("Agent " + agentId.substring(Math.max(0, agentId.length() - 4)));
        return runtime;
    }

    private void assertUnavailable(Runnable operation) {
        AgentTaskThreadException exception = assertThrows(
                AgentTaskThreadException.class, operation::run);
        assertEquals(AgentTaskThreadException.Reason.NOT_FOUND_OR_FORBIDDEN,
                exception.getReason());
        assertEquals("Task thread is not available in the requested scope",
                exception.getMessage());
    }

    private AgentTaskThreadCreationTransaction transactionalCreation(
            AgentTaskThreadCreationTransaction target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (AgentTaskThreadCreationTransaction) factory.getProxy();
    }

    private SqlSessionFactory createSqlSessionFactory() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(ChatConversationMapper.class);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(AgentTaskThreadMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        return factoryBean.getObject();
    }

    private void setBaseMapper(Object dao, Object mapper) throws Exception {
        Field field = BaseDaoImpl.class.getDeclaredField("baseMapper");
        field.setAccessible(true);
        field.set(dao, mapper);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void insertTask(String tenant, String client, String task) {
        jdbc.update("""
                INSERT INTO agent_task_meta
                (task_id, reward_status, collaboration_mode, risk_level, max_agents,
                 review_required, task_version, current_event_version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, 'running', 'team', 'low', 3, 0, 0, 0, ?, ?, 1, 1)
                """, task, tenant, client);
    }

    private void insertMember(
            String tenant, String client, String task, String agent, String status) {
        jdbc.update("""
                INSERT INTO agent_task_member
                (task_id, agent_id, member_role, member_status, assignment_source,
                 version, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'worker', ?, 'manual', 0, ?, ?, 1, 1)
                """, task, agent, status, tenant, client);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL,
                    assigned_agent_id VARCHAR(100),
                    required_abilities TEXT,
                    reward INT,
                    assigned_at BIGINT,
                    started_at BIGINT,
                    completed_at BIGINT,
                    failure_reason VARCHAR(1000),
                    collaboration_mode VARCHAR(20) NOT NULL,
                    risk_level VARCHAR(20) NOT NULL,
                    max_agents INT NOT NULL,
                    coordinator_agent_id VARCHAR(100),
                    review_required TINYINT NOT NULL,
                    task_version BIGINT NOT NULL,
                    current_event_version BIGINT NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR(50),
                    client_id VARCHAR(50),
                    PRIMARY KEY (id),
                    UNIQUE (task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL,
                    assignment_source VARCHAR(20) NOT NULL,
                    joined_at BIGINT,
                    accepted_at BIGINT,
                    started_at BIGINT,
                    completed_at BIGINT,
                    last_heartbeat_at BIGINT,
                    failure_reason VARCHAR(1000),
                    version BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    PRIMARY KEY (id),
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )""");
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    title VARCHAR(500),
                    jiacn VARCHAR(50),
                    conversation_type VARCHAR(20),
                    conversation_scope_type VARCHAR(20),
                    conversation_scope_key VARCHAR(120),
                    task_id VARCHAR(64),
                    target_agent_id VARCHAR(100),
                    status INT,
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR(50),
                    client_id VARCHAR(50),
                    PRIMARY KEY (id)
                )""");
        jdbc.execute("""
                CREATE TABLE chat_message (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    conversation_id VARCHAR(100) NOT NULL,
                    message_type VARCHAR(20),
                    content TEXT,
                    metadata TEXT,
                    jiacn VARCHAR(50),
                    sync_status VARCHAR(20),
                    conversation_type VARCHAR(20),
                    sender_type VARCHAR(20),
                    sender_name VARCHAR(100),
                    create_time BIGINT,
                    update_time BIGINT,
                    tenant_id VARCHAR(50),
                    client_id VARCHAR(50),
                    PRIMARY KEY (id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_thread (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    task_id VARCHAR(100) NOT NULL,
                    thread_type VARCHAR(20) NOT NULL,
                    thread_key VARCHAR(100) NOT NULL,
                    conversation_id VARCHAR(100) NOT NULL,
                    created_by_agent_id VARCHAR(100) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    PRIMARY KEY (id),
                    CONSTRAINT uk_task_thread_scope UNIQUE
                        (tenant_id, client_id, task_id, thread_type, thread_key),
                    CONSTRAINT uk_task_thread_conversation UNIQUE
                        (tenant_id, client_id, conversation_id)
                )""");
    }

    private static final class FirstReadBarrierThreadDao implements AgentTaskThreadDao {
        private final AgentTaskThreadDao delegate;
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        private final AtomicInteger taskReads = new AtomicInteger();

        private FirstReadBarrierThreadDao(AgentTaskThreadDao delegate) {
            this.delegate = delegate;
        }

        @Override
        public int insert(String tenantId, String clientId, AgentTaskThreadEntity thread) {
            return delegate.insert(tenantId, clientId, thread);
        }

        @Override
        public AgentTaskThreadEntity findByTaskThread(
                String tenantId, String clientId, String taskId,
                String threadType, String threadKey) {
            if (taskReads.incrementAndGet() <= 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException("Unable to synchronize concurrent create", exception);
                }
            }
            return delegate.findByTaskThread(
                    tenantId, clientId, taskId, threadType, threadKey);
        }

        @Override
        public AgentTaskThreadEntity findByTaskThreadForUpdate(
                String tenantId, String clientId, String taskId,
                String threadType, String threadKey) {
            return delegate.findByTaskThreadForUpdate(
                    tenantId, clientId, taskId, threadType, threadKey);
        }

        @Override
        public AgentTaskThreadEntity findByConversationId(
                String tenantId, String clientId, String conversationId) {
            return delegate.findByConversationId(tenantId, clientId, conversationId);
        }

        @Override
        public AgentTaskThreadEntity findAnyByConversationId(String conversationId) {
            return delegate.findAnyByConversationId(conversationId);
        }
    }
}
