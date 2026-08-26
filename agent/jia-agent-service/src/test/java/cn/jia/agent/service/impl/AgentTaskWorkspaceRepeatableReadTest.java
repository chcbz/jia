package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.dao.impl.AgentTaskWorkspaceDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import cn.jia.agent.service.AgentIdentityService;
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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** H2 SNAPSHOT equivalent of MySQL REPEATABLE READ plus a deterministic RC negative control. */
class AgentTaskWorkspaceRepeatableReadTest {
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager writerTransactionManager;
    private DataSourceTransactionManager snapshotTransactionManager;
    private AgentTaskWorkspaceDao realDao;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource();
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
        writerTransactionManager = new DataSourceTransactionManager(source);
        snapshotTransactionManager = new H2SnapshotTransactionManager(source);
        insertBeforeState();
    }

    @Test
    void commitBeforeSnapshotReturnsEntireNewStateOnOneBoundConnection() {
        new TransactionTemplate(writerTransactionManager).executeWithoutResult(status -> mutate());
        TransactionProof proof = new TransactionProof(source);
        AgentTaskWorkspaceDTO snapshot = transactional(service(new ProofDao(realDao, proof), proof),
                snapshotTransactionManager).snapshot(TENANT, CLIENT, TASK, ACTOR);

        assertAfter(snapshot);
        proof.assertSingleBoundConnectionUsed();
    }

    @Test
    void commitDuringSnapshotReturnsEntireOldStateAndNeverMixed() throws Exception {
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        TransactionProof proof = new TransactionProof(source);
        AgentTaskWorkspaceDao pausingDao = new PausingDao(
                realDao, proof, firstReadCompleted, mutationCommitted);
        AgentTaskWorkspaceService snapshotService = transactional(
                service(pausingDao, proof), snapshotTransactionManager);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> during = executor.submit(
                    () -> snapshotService.snapshot(TENANT, CLIENT, TASK, ACTOR));
            assertTrue(firstReadCompleted.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(writerTransactionManager)
                        .executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);

            assertBefore(during.get(10, TimeUnit.SECONDS));
            proof.assertSingleBoundConnectionUsed();

            TransactionProof afterProof = new TransactionProof(source);
            AgentTaskWorkspaceDTO after = transactional(
                    service(new ProofDao(realDao, afterProof), afterProof),
                    snapshotTransactionManager).snapshot(TENANT, CLIENT, TASK, ACTOR);
            assertAfter(after);
            afterProof.assertSingleBoundConnectionUsed();
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readCommittedNegativeControlDeterministicallyFailsAfterFirstRead() throws Exception {
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        TransactionProof proof = new TransactionProof(source);
        AgentTaskWorkspaceServiceImpl target = service(new PausingDao(
                realDao, proof, firstReadCompleted, mutationCommitted), proof);
        TransactionTemplate readCommitted = new TransactionTemplate(writerTransactionManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readCommitted.setReadOnly(true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> mixed = executor.submit(() ->
                    readCommitted.execute(status -> target.snapshot(TENANT, CLIENT, TASK, ACTOR)));
            assertTrue(firstReadCompleted.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(writerTransactionManager)
                        .executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> mixed.get(10, TimeUnit.SECONDS));
            AgentTaskWorkspaceException unavailable = assertInstanceOf(
                    AgentTaskWorkspaceException.class, failure.getCause());
            assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                    unavailable.getReason());
            proof.assertSingleBoundConnectionUsed();
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTaskWorkspaceServiceImpl service(
            AgentTaskWorkspaceDao dao, TransactionProof proof) {
        AgentIdentityService identity = mock(AgentIdentityService.class);
        when(identity.requireCanonicalAgentIdInScope(
                TENANT, CLIENT, TENANT, ACTOR)).thenAnswer(invocation -> {
            proof.recordBoundConnection();
            return ACTOR;
        });
        return new AgentTaskWorkspaceServiceImpl(identity, dao);
    }

    private AgentTaskWorkspaceService transactional(
            AgentTaskWorkspaceServiceImpl target, DataSourceTransactionManager manager) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(
                manager, new AnnotationTransactionAttributeSource()));
        return (AgentTaskWorkspaceService) proxy.getProxy();
    }

    private static void assertBefore(AgentTaskWorkspaceDTO snapshot) {
        assertEquals("0", snapshot.getCurrentVersion());
        assertEquals("accepted", snapshot.getMembers().get(0).getStatus());
        assertEquals("0", snapshot.getMembers().get(0).getVersion());
        assertTrue(snapshot.getWorkItems().isEmpty());
        assertTrue(snapshot.getRecentArtifacts().isEmpty());
        assertTrue(snapshot.getRecentEvents().isEmpty());
        assertNull(snapshot.getConversationId());
    }

    private static void assertAfter(AgentTaskWorkspaceDTO snapshot) {
        assertEquals("1", snapshot.getCurrentVersion());
        assertEquals("working", snapshot.getMembers().get(0).getStatus());
        assertEquals("1", snapshot.getMembers().get(0).getVersion());
        assertEquals(1, snapshot.getWorkItems().size());
        assertEquals("1", snapshot.getWorkItems().get(0).getVersion());
        assertEquals(1, snapshot.getRecentArtifacts().size());
        assertEquals("1", snapshot.getRecentEvents().get(0).getVersion());
        assertFalse(snapshot.getRecentEvents().get(0).getRedacted());
        assertNull(snapshot.getConversationId());
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
                "{\"artifactId\":\"artifact-1\",\"artifactType\":\"document\","
                        + "\"artifactVersion\":1,\"contentSha256\":\"" + "a".repeat(64)
                        + "\",\"visibility\":\"task_members\"}",
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

    /** Maps the service's MySQL REPEATABLE READ contract to H2's equivalent MVCC SNAPSHOT. */
    private static final class H2SnapshotTransactionManager extends DataSourceTransactionManager {
        private H2SnapshotTransactionManager(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            super.doBegin(transaction, definition);
            if (definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ) {
                ConnectionHolder holder = (ConnectionHolder)
                        TransactionSynchronizationManager.getResource(obtainDataSource());
                if (holder == null) {
                    throw new IllegalStateException("H2 snapshot transaction has no bound connection");
                }
                try (Statement statement = holder.getConnection().createStatement()) {
                    statement.execute("SET TRANSACTION ISOLATION LEVEL SNAPSHOT");
                } catch (SQLException exception) {
                    throw new IllegalStateException("Cannot enable H2 SNAPSHOT isolation", exception);
                }
            }
        }
    }

    private static class ProofDao implements AgentTaskWorkspaceDao {
        final AgentTaskWorkspaceDao delegate;
        final TransactionProof proof;

        private ProofDao(AgentTaskWorkspaceDao delegate, TransactionProof proof) {
            this.delegate = delegate;
            this.proof = proof;
        }

        @Override public TaskRow findTask(String t, String c, String task) { proof.recordBoundConnection(); return delegate.findTask(t, c, task); }
        @Override public MemberRow findActorMember(String t, String c, String task, String actor) { proof.recordBoundConnection(); return delegate.findActorMember(t, c, task, actor); }
        @Override public List<MemberRow> findMembers(String t, String c, String task) { proof.recordBoundConnection(); return delegate.findMembers(t, c, task); }
        @Override public List<WorkItemRow> findWorkItems(String t, String c, String task) { proof.recordBoundConnection(); return delegate.findWorkItems(t, c, task); }
        @Override public List<RequestRow> findOpenRequests(String t, String c, String task) { proof.recordBoundConnection(); return delegate.findOpenRequests(t, c, task); }
        @Override public List<ArtifactRow> findVisibleArtifacts(String t, String c, String task, String actor, boolean reviewer, boolean coordinator) { proof.recordBoundConnection(); return delegate.findVisibleArtifacts(t, c, task, actor, reviewer, coordinator); }
        @Override public ArtifactRow findArtifactVersion(String t, String c, String task, String artifact, int version) { proof.recordBoundConnection(); return delegate.findArtifactVersion(t, c, task, artifact, version); }
        @Override public List<EventRow> findLatestEvents(String t, String c, String task) { proof.recordBoundConnection(); return delegate.findLatestEvents(t, c, task); }
    }

    /** Releases the concurrent writer immediately after the first consistent task read. */
    private static final class PausingDao extends ProofDao {
        private final CountDownLatch firstReadCompleted;
        private final CountDownLatch mutationCommitted;
        private final AtomicBoolean pause = new AtomicBoolean(true);

        private PausingDao(AgentTaskWorkspaceDao delegate, TransactionProof proof,
                CountDownLatch firstReadCompleted, CountDownLatch mutationCommitted) {
            super(delegate, proof);
            this.firstReadCompleted = firstReadCompleted;
            this.mutationCommitted = mutationCommitted;
        }

        @Override
        public TaskRow findTask(String tenantId, String clientId, String taskId) {
            TaskRow row = super.findTask(tenantId, clientId, taskId);
            if (pause.compareAndSet(true, false)) {
                firstReadCompleted.countDown();
                try {
                    if (!mutationCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("mutation did not commit");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }
            return row;
        }
    }

    private static final class TransactionProof {
        private final DataSource dataSource;
        private final AtomicReference<Connection> first = new AtomicReference<>();
        private final Set<Integer> connectionIdentities = new HashSet<>();
        private final AtomicInteger calls = new AtomicInteger();

        private TransactionProof(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        private synchronized void recordBoundConnection() {
            if (!TransactionSynchronizationManager.isActualTransactionActive()
                    || !TransactionSynchronizationManager.hasResource(dataSource)) {
                throw new AssertionError("workspace read is not transaction-bound");
            }
            Connection connection = DataSourceUtils.getConnection(dataSource);
            first.compareAndSet(null, connection);
            if (first.get() != connection) {
                throw new AssertionError("workspace reads changed physical transaction connection");
            }
            connectionIdentities.add(System.identityHashCode(connection));
            calls.incrementAndGet();
        }

        private synchronized void assertSingleBoundConnectionUsed() {
            assertTrue(calls.get() >= 8, "identity and all workspace reads must be observed");
            assertEquals(Set.of(System.identityHashCode(first.get())), connectionIdentities);
        }
    }
}
