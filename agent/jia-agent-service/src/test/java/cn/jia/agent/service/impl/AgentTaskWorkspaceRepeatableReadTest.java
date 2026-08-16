package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.dao.impl.AgentTaskWorkspaceDaoImpl;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real H2/MyBatis/Spring transaction proof for the C04 REPEATABLE READ snapshot boundary. */
class AgentTaskWorkspaceRepeatableReadTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskWorkspaceDao realDao;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:c04_repeatable;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskWorkspaceMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = bean.getObject();
        AgentTaskWorkspaceMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(AgentTaskWorkspaceMapper.class);
        realDao = new AgentTaskWorkspaceDaoImpl(mapper);
        transactionManager = new DataSourceTransactionManager(source);
        insertBeforeState();
    }

    @Test
    void concurrentMutationAppearsEntirelyBeforeOrAfterSnapshot() throws Exception {
        CountDownLatch taskRead = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        AgentTaskWorkspaceDao pausingDao = new PausingDao(realDao, taskRead, mutationCommitted);
        AgentService identity = mock(AgentService.class);
        when(identity.requireApiKeyOwnedAgent(CLIENT, TENANT, ACTOR))
                .thenReturn(new AgentRuntimeDTO());
        AgentTaskWorkspaceService snapshotService = transactional(
                new AgentTaskWorkspaceServiceImpl(identity, pausingDao));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> during = executor.submit(
                    () -> snapshotService.snapshot(TENANT, CLIENT, TASK, ACTOR));
            assertTrue(taskRead.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);
            AgentTaskWorkspaceDTO before = during.get(10, TimeUnit.SECONDS);

            assertEquals("0", before.getCurrentVersion());
            assertEquals("accepted", before.getMembers().get(0).getStatus());
            assertEquals("0", before.getMembers().get(0).getVersion());
            assertTrue(before.getWorkItems().isEmpty());
            assertTrue(before.getRecentArtifacts().isEmpty());
            assertTrue(before.getRecentEvents().isEmpty());
            assertNull(before.getConversationId());

            AgentTaskWorkspaceDTO after = transactional(
                    new AgentTaskWorkspaceServiceImpl(identity, realDao))
                    .snapshot(TENANT, CLIENT, TASK, ACTOR);
            assertEquals("1", after.getCurrentVersion());
            assertEquals("working", after.getMembers().get(0).getStatus());
            assertEquals("1", after.getMembers().get(0).getVersion());
            assertEquals(1, after.getWorkItems().size());
            assertEquals("1", after.getWorkItems().get(0).getVersion());
            assertEquals(1, after.getRecentArtifacts().size());
            assertEquals("1", after.getRecentEvents().get(0).getVersion());
            assertFalse(after.getRecentEvents().get(0).getRedacted());
            assertNull(after.getConversationId());
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTaskWorkspaceService transactional(AgentTaskWorkspaceServiceImpl target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (AgentTaskWorkspaceService) proxy.getProxy();
    }

    private void mutate() {
        jdbc.update("UPDATE agent_task_member SET member_status='working', version=1 "
                + "WHERE tenant_id=? AND client_id=? AND task_id=? AND agent_id=?",
                TENANT, CLIENT, TASK, ACTOR);
        jdbc.update("INSERT INTO agent_task_work_item "
                        + "(work_item_id,task_id,title,work_type,status,priority,required_item,"
                        + "attempt_count,max_attempts,version,create_time,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "work-1", TASK, "work", "analysis", "running", 5, true,
                1, 3, 1L, 1000L, TENANT, CLIENT);
        jdbc.update("INSERT INTO agent_task_artifact "
                        + "(artifact_id,task_id,producer_agent_id,artifact_type,title,content_hash,"
                        + "artifact_version,visibility,created_at,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "artifact-1", TASK, ACTOR, "document", "artifact", "a".repeat(64),
                1, "task_members", 1001L, TENANT, CLIENT);
        jdbc.update("INSERT INTO agent_task_event "
                        + "(task_id,event_version,event_type,actor_type,actor_id,aggregate_type,"
                        + "aggregate_id,event_json,occurred_at,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                TASK, 1L, "ARTIFACT_PUBLISHED", "agent", ACTOR, "artifact", "artifact-1",
                "{\"artifactId\":\"artifact-1\",\"artifactVersion\":1}",
                1001L, TENANT, CLIENT);
        jdbc.update("UPDATE agent_task_meta SET task_version=1,current_event_version=1 "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", TENANT, CLIENT, TASK);
    }

    private void insertBeforeState() {
        jdbc.update("INSERT INTO agent_task_meta "
                        + "(task_id,reward_status,collaboration_mode,risk_level,max_agents,"
                        + "review_required,task_version,current_event_version,tenant_id,client_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                TASK, "running", "team", "low", 5, false, 0L, 0L, TENANT, CLIENT);
        jdbc.update("INSERT INTO agent_task_member "
                        + "(task_id,agent_id,member_role,member_status,assignment_source,version,"
                        + "tenant_id,client_id) VALUES (?,?,?,?,?,?,?,?)",
                TASK, ACTOR, "worker", "accepted", "manual", 0L, TENANT, CLIENT);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE agent_task_meta (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR(100),reward_status VARCHAR(20),assigned_agent_id VARCHAR(100),"
                + "required_abilities CLOB,reward INT,assigned_at BIGINT,started_at BIGINT,"
                + "completed_at BIGINT,collaboration_mode VARCHAR(20),risk_level VARCHAR(20),"
                + "max_agents INT,coordinator_agent_id VARCHAR(100),review_required BOOLEAN,"
                + "task_version BIGINT,current_event_version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_member (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR(100),agent_id VARCHAR(100),member_role VARCHAR(20),"
                + "member_status VARCHAR(20),assignment_source VARCHAR(20),joined_at BIGINT,"
                + "accepted_at BIGINT,started_at BIGINT,completed_at BIGINT,last_heartbeat_at BIGINT,"
                + "version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_work_item (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "work_item_id VARCHAR(100),task_id VARCHAR(100),title VARCHAR(255),description CLOB,"
                + "work_type VARCHAR(30),required_abilities CLOB,assignee_agent_id VARCHAR(100),"
                + "status VARCHAR(20),priority INT,required_item BOOLEAN,dependency_json CLOB,"
                + "lease_until BIGINT,attempt_count INT,max_attempts INT,result_artifact_id VARCHAR(100),"
                + "submitted_at BIGINT,completed_at BIGINT,version BIGINT,create_time BIGINT,"
                + "tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_request (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "request_id VARCHAR(100),task_id VARCHAR(100),work_item_id VARCHAR(100),"
                + "requester_agent_id VARCHAR(100),target_type VARCHAR(20),target_id VARCHAR(100),"
                + "request_type VARCHAR(30),status VARCHAR(20),priority INT,title VARCHAR(255),"
                + "description CLOB,due_at BIGINT,acknowledged_at BIGINT,version BIGINT,create_time BIGINT,"
                + "tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_artifact (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "artifact_id VARCHAR(100),task_id VARCHAR(100),work_item_id VARCHAR(100),"
                + "producer_agent_id VARCHAR(100),artifact_type VARCHAR(30),title VARCHAR(255),"
                + "content_hash VARCHAR(128),artifact_version INT,visibility VARCHAR(20),created_at BIGINT,"
                + "tenant_id VARCHAR(50),client_id VARCHAR(50))");
        jdbc.execute("CREATE TABLE agent_task_event (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "task_id VARCHAR(100),event_version BIGINT,event_type VARCHAR(64),actor_type VARCHAR(20),"
                + "actor_id VARCHAR(100),aggregate_type VARCHAR(30),aggregate_id VARCHAR(100),"
                + "event_json CLOB,occurred_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50))");
    }

    private static final class PausingDao implements AgentTaskWorkspaceDao {
        private final AgentTaskWorkspaceDao delegate;
        private final CountDownLatch taskRead;
        private final CountDownLatch mutationCommitted;
        private final AtomicBoolean pause = new AtomicBoolean(true);

        private PausingDao(AgentTaskWorkspaceDao delegate, CountDownLatch taskRead,
                CountDownLatch mutationCommitted) {
            this.delegate = delegate;
            this.taskRead = taskRead;
            this.mutationCommitted = mutationCommitted;
        }

        @Override
        public TaskRow findTask(String tenantId, String clientId, String taskId) {
            return delegate.findTask(tenantId, clientId, taskId);
        }

        @Override public MemberRow findActorMember(String t, String c, String task, String actor) { return delegate.findActorMember(t, c, task, actor); }
        @Override public List<MemberRow> findMembers(String t, String c, String task) { return delegate.findMembers(t, c, task); }
        @Override public List<WorkItemRow> findWorkItems(String t, String c, String task) { return delegate.findWorkItems(t, c, task); }
        @Override public List<RequestRow> findOpenRequests(String t, String c, String task) { return delegate.findOpenRequests(t, c, task); }
        @Override public List<ArtifactRow> findVisibleArtifacts(String t, String c, String task, String actor, boolean reviewer, boolean coordinator) { return delegate.findVisibleArtifacts(t, c, task, actor, reviewer, coordinator); }
        @Override public ArtifactRow findArtifactVersion(String t, String c, String task, String artifact, int version) { return delegate.findArtifactVersion(t, c, task, artifact, version); }
        @Override
        public List<EventRow> findLatestEvents(String t, String c, String task) {
            List<EventRow> rows = delegate.findLatestEvents(t, c, task);
            if (pause.compareAndSet(true, false)) {
                taskRead.countDown();
                try {
                    if (!mutationCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("mutation did not commit");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return rows;
        }
    }
}
