package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskNoteDao;
import cn.jia.agent.dao.AgentTaskWorkspaceDao;
import cn.jia.agent.dao.DialogueTemplateDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkspaceDaoImpl;
import cn.jia.agent.entity.AgentTaskWorkspaceDTO;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.ArtifactRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.EventRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.MemberRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.RequestRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.TaskRow;
import cn.jia.agent.entity.AgentTaskWorkspaceRows.WorkItemRow;
import cn.jia.agent.exception.AgentTaskWorkspaceException;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentTaskWorkspaceMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentSceneService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskWorkspaceService;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.core.config.db.DataSourceConfig;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Isolated MySQL 8.0.21 gate for C04 exact SQL and cross-query snapshot isolation. */
@EnabledIfEnvironmentVariable(named = "C04_MYSQL_URL", matches = ".+")
class AgentTaskWorkspaceMySqlTest {
    private static final String TENANT = "owner-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String ACTOR = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private String databaseName;
    private JdbcTemplate adminJdbc;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskWorkspaceMapper workspaceMapper;
    private AgentTaskWorkspaceDao workspaceDao;
    private AgentService realAgentService;
    private SqlConnectionEvidence connectionEvidence;
    private ObjectProvider<AgentEventPublisher> eventPublisherProvider;
    private ObjectProvider<TaskService> taskServiceProvider;
    private ObjectProvider<ApiKeyService> apiKeyServiceProvider;
    private ObjectProvider<AgentSceneService> sceneServiceProvider;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = requiredEnvironment("C04_MYSQL_URL");
        assertTrue(baseUrl.startsWith("jdbc:mysql://127.0.0.1:33307/"),
                "C04 gate refuses every endpoint except isolated 127.0.0.1:33307");
        assertEquals("33307", requiredEnvironment("C04_MYSQL_EXPECTED_PORT"));
        String expectedDatadir = requiredEnvironment("C04_MYSQL_EXPECTED_DATADIR");
        assertTrue(Path.of(expectedDatadir).normalize().startsWith(Path.of("/tmp")),
                "isolated datadir must be below /tmp");
        assertEquals("false", requiredEnvironment("C04_DYNAMIC_DATASOURCE_ENABLE"),
                "isolated gate must explicitly disable dynamic datasource routing");

        String username = requiredEnvironment("C04_MYSQL_USER");
        String password = requiredEnvironment("C04_MYSQL_PASSWORD");
        DriverManagerDataSource admin = dataSource(baseUrl, username, password);
        adminJdbc = new JdbcTemplate(admin);
        String version = adminJdbc.queryForObject("SELECT VERSION()", String.class);
        Integer port = adminJdbc.queryForObject("SELECT @@port", Integer.class);
        String datadir = adminJdbc.queryForObject("SELECT @@datadir", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "C04 evidence requires MySQL 8.0.21, got " + version);
        assertEquals(33307, port);
        assertTrue(datadir != null && Path.of(datadir).normalize()
                        .startsWith(Path.of(expectedDatadir).normalize()),
                "unexpected datadir " + datadir);
        System.out.printf("C04_MYSQL_IDENTITY version=%s port=%s datadir=%s "
                        + "dynamic.datasource.enable=false%n", version, port, datadir);

        databaseName = "c04_workspace_" + Long.toUnsignedString(System.nanoTime());
        adminJdbc.execute("CREATE DATABASE `" + databaseName
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        dataSource = dataSource(databaseUrl(baseUrl, databaseName), username, password);
        jdbc = new JdbcTemplate(dataSource);
        createTables();

        connectionEvidence = new SqlConnectionEvidence();
        SqlSessionFactory factory = sqlSessionFactory(dataSource, connectionEvidence);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        workspaceMapper = template.getMapper(AgentTaskWorkspaceMapper.class);
        workspaceDao = new AgentTaskWorkspaceDaoImpl(workspaceMapper);

        AgentIdentityRegistryDao registryDao = wire(new AgentIdentityRegistryDaoImpl(),
                template.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(new AgentIdentityAliasDaoImpl(),
                template.getMapper(AgentIdentityAliasMapper.class));
        AgentPersonaBindingDao bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        AgentRuntimeDao runtimeDao = wire(new AgentRuntimeDaoImpl(),
                template.getMapper(AgentRuntimeMapper.class));
        AgentIdentityService identityService = new AgentIdentityServiceImpl(
                registryDao, aliasDao, bindingDao);

        eventPublisherProvider = provider();
        taskServiceProvider = provider();
        apiKeyServiceProvider = provider();
        sceneServiceProvider = provider();
        realAgentService = new AgentServiceImpl(
                runtimeDao, identityService, mock(cn.jia.agent.dao.AgentPersonaDao.class),
                bindingDao, mock(AgentTaskMetaDao.class), mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class),
                mock(AgentTaskNoteDao.class), mock(DialogueTemplateDao.class),
                eventPublisherProvider, taskServiceProvider, apiKeyServiceProvider,
                sceneServiceProvider, new AgentSceneFeatureFlags(false, false),
                mock(AgentTaskMutationTransaction.class), mock(AgentTaskEventWriter.class));

        transactionManager = new DataSourceTransactionManager(dataSource);
        PlatformTransactionManager configured = new DataSourceConfig()
                .transactionManager(dataSource);
        assertInstanceOf(DataSourceTransactionManager.class, configured);
        assertSame(dataSource, ((DataSourceTransactionManager) configured).getDataSource(),
                "DataSourceConfig must not construct a second routing DataSource");
        seedIdentity();
    }

    @AfterEach
    void tearDown() {
        if (adminJdbc != null && databaseName != null
                && databaseName.matches("c04_workspace_[0-9]+")) {
            adminJdbc.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
        }
    }

    @Test
    void realWorkspaceMapperSqlIsByteExactForScopeIdsAclAndUnicodeForms() {
        insertTask(TENANT, CLIENT, TASK, 0L);
        insertTask("OWNER-A", CLIENT, TASK, 0L);
        insertTask(TENANT, "CLIENT-A", TASK, 0L);
        insertTask(TENANT, CLIENT, "TASK-1", 0L);
        insertMember(TENANT, CLIENT, TASK, ACTOR, "worker", "accepted", 0L);
        insertMember(TENANT, CLIENT, TASK, ACTOR.toUpperCase(Locale.ROOT),
                "worker", "accepted", 0L);
        insertMember("OWNER-A", CLIENT, TASK, "foreign-scope", "worker", "accepted", 0L);
        insertWorkItem(TENANT, CLIENT, TASK, "work-exact", 1L);
        insertWorkItem(TENANT, CLIENT, "TASK-1", "work-foreign", 1L);
        insertRequest(TENANT, CLIENT, TASK, "request-exact", 1L);
        insertRequest(TENANT, "CLIENT-A", TASK, "request-foreign", 1L);
        insertArtifact(TENANT, CLIENT, TASK, "artifact-a", ACTOR,
                "private", 2000L, 1);
        insertArtifact(TENANT, CLIENT, TASK, "Artifact-a", "other",
                "task_members", 1900L, 1);
        insertArtifact(TENANT, CLIENT, TASK, "private-case-collision",
                ACTOR.toUpperCase(Locale.ROOT), "private", 3000L, 1);
        insertArtifact("OWNER-A", CLIENT, TASK, "foreign-artifact", ACTOR,
                "task_members", 4000L, 1);
        insertEvent(TENANT, CLIENT, TASK, 1L);
        insertEvent(TENANT, CLIENT, "TASK-1", 2L);

        String supplementary = new String(Character.toChars(0x1f642));
        String supplementaryTask = "t" + supplementary.repeat(99);
        insertTask(TENANT, CLIENT, supplementaryTask, 0L);
        String composed = "task-\u00e9";
        String decomposed = "task-e\u0301";
        insertTask(TENANT, CLIENT, composed, 0L);

        assertEquals(TASK, workspaceMapper.findTask(TENANT, CLIENT, TASK).getTaskId());
        assertNull(workspaceMapper.findTask("Owner-A", CLIENT, TASK));
        assertNull(workspaceMapper.findTask(TENANT, "Client-A", TASK));
        assertNull(workspaceMapper.findTask(TENANT, CLIENT, "Task-1"));
        assertEquals(supplementaryTask,
                workspaceDao.findTask(TENANT, CLIENT, supplementaryTask).getTaskId());
        assertEquals(composed, workspaceMapper.findTask(TENANT, CLIENT, composed).getTaskId());
        assertNull(workspaceMapper.findTask(TENANT, CLIENT, decomposed),
                "composed and decomposed identifiers must remain byte-distinct");
        assertThrows(IllegalArgumentException.class, () -> workspaceDao.findTask(
                TENANT, CLIENT, "t" + supplementary.repeat(100)));
        assertThrows(IllegalArgumentException.class,
                () -> workspaceDao.findTask(TENANT, CLIENT, "task-\ud800"));

        assertEquals(ACTOR, workspaceMapper.findActorMember(
                TENANT, CLIENT, TASK, ACTOR).getAgentId());
        assertNull(workspaceMapper.findActorMember(TENANT, CLIENT, TASK,
                "Agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(2, workspaceMapper.findMembers(TENANT, CLIENT, TASK).size());
        assertEquals(List.of("work-exact"), workspaceMapper.findWorkItems(
                TENANT, CLIENT, TASK).stream().map(WorkItemRow::getWorkItemId).toList());
        assertEquals(List.of("request-exact"), workspaceMapper.findOpenRequests(
                TENANT, CLIENT, TASK).stream().map(RequestRow::getRequestId).toList());

        List<ArtifactRow> visible = workspaceMapper.findVisibleArtifacts(
                TENANT, CLIENT, TASK, ACTOR, false, false);
        assertEquals(List.of("artifact-a", "Artifact-a"),
                visible.stream().map(ArtifactRow::getArtifactId).toList());
        assertTrue(visible.stream().noneMatch(row ->
                "private-case-collision".equals(row.getArtifactId())));
        assertEquals("artifact-a", workspaceMapper.findArtifactVersion(
                TENANT, CLIENT, TASK, "artifact-a", 1).getArtifactId());
        assertEquals("Artifact-a", workspaceMapper.findArtifactVersion(
                TENANT, CLIENT, TASK, "Artifact-a", 1).getArtifactId());
        assertNull(workspaceMapper.findArtifactVersion(
                TENANT, CLIENT, TASK, "ARTIFACT-A", 1));
        assertEquals(List.of(1L), workspaceMapper.findLatestEvents(
                TENANT, CLIENT, TASK).stream().map(EventRow::getEventVersion).toList());
    }

    @Test
    void realIdentityPathAcceptsUnicodeCodePointBoundariesAndRejectsInvalidScalars() {
        String supplementary = new String(Character.toChars(0x1f642));
        String tenant = "t" + supplementary.repeat(49);
        String client = "c" + supplementary.repeat(49);
        String actor = "a" + supplementary.repeat(99);
        String task = "task-unicode-identity";
        seedIdentity(tenant, client, actor, 2L, "LEGACY_CANONICAL");
        insertTask(tenant, client, task, 0L);
        insertMember(tenant, client, task, actor, "worker", "accepted", 0L);
        connectionEvidence.beginCapture();

        AgentTaskWorkspaceDTO snapshot = transactional(workspaceDao)
                .snapshot(tenant, client, task, actor);

        assertEquals(task, snapshot.getTask().getTaskId());
        assertEquals(actor, snapshot.getMembers().get(0).getAgentId());
        connectionEvidence.assertSingleTransactionalConnection(
                "REPEATABLE-READ", true);
        assertReadOnlyMapperCoverage();
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> realAgentService.requireApiKeyOwnedAgent(
                        client, "t" + supplementary.repeat(50), actor));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> realAgentService.requireApiKeyOwnedAgent(
                        client, tenant, "a" + supplementary.repeat(100)));
        assertThrows(AgentServiceImpl.AgentBizException.class,
                () -> realAgentService.requireApiKeyOwnedAgent(
                        client, tenant, "agent-\ud800"));
    }

    @Test
    void repeatableReadCommitBeforeReturnsEntireNewSnapshotOnOnePhysicalConnection() {
        insertBeforeState();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> mutate());
        connectionEvidence.beginCapture();

        AgentTaskWorkspaceDTO snapshot = transactional(workspaceDao)
                .snapshot(TENANT, CLIENT, TASK, ACTOR);

        assertAfter(snapshot);
        connectionEvidence.assertSingleTransactionalConnection(
                "REPEATABLE-READ", true);
        assertReadOnlyMapperCoverage();
    }

    @Test
    void repeatableReadCommitDuringReturnsEntireOldThenEntireNewSnapshot() throws Exception {
        insertBeforeState();
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        AgentTaskWorkspaceDao pausing = new PausingDao(
                workspaceDao, firstReadCompleted, mutationCommitted);
        AgentTaskWorkspaceService service = transactional(pausing);
        connectionEvidence.beginCapture();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> during = executor.submit(
                    () -> service.snapshot(TENANT, CLIENT, TASK, ACTOR));
            assertTrue(firstReadCompleted.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);

            assertBefore(during.get(10, TimeUnit.SECONDS));
            connectionEvidence.assertSingleTransactionalConnection(
                    "REPEATABLE-READ", true);
            assertReadOnlyMapperCoverage();

            connectionEvidence.beginCapture();
            AgentTaskWorkspaceDTO after = transactional(workspaceDao)
                    .snapshot(TENANT, CLIENT, TASK, ACTOR);
            assertAfter(after);
            connectionEvidence.assertSingleTransactionalConnection(
                    "REPEATABLE-READ", true);
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void readCommittedNegativeControlDeterministicallyRejectsMixedSnapshot() throws Exception {
        insertBeforeState();
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        AgentTaskWorkspaceServiceImpl target = target(new PausingDao(
                workspaceDao, firstReadCompleted, mutationCommitted));
        TransactionTemplate readCommitted = new TransactionTemplate(transactionManager);
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        readCommitted.setReadOnly(true);
        connectionEvidence.beginCapture();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> mixed = executor.submit(() ->
                    readCommitted.execute(status ->
                            target.snapshot(TENANT, CLIENT, TASK, ACTOR)));
            assertTrue(firstReadCompleted.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> mixed.get(10, TimeUnit.SECONDS));
            AgentTaskWorkspaceException unavailable = assertInstanceOf(
                    AgentTaskWorkspaceException.class, failure.getCause());
            assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                    unavailable.getReason());
            connectionEvidence.assertSingleTransactionalConnection("READ-COMMITTED", true);
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void noTransactionNegativeControlUsesMultipleConnectionsAndRejectsMixedSnapshot()
            throws Exception {
        insertBeforeState();
        CountDownLatch firstReadCompleted = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        AgentTaskWorkspaceServiceImpl target = target(new PausingDao(
                workspaceDao, firstReadCompleted, mutationCommitted));
        connectionEvidence.beginCapture();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentTaskWorkspaceDTO> mixed = executor.submit(
                    () -> target.snapshot(TENANT, CLIENT, TASK, ACTOR));
            assertTrue(firstReadCompleted.await(10, TimeUnit.SECONDS));
            Future<?> writer = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> mutate());
                mutationCommitted.countDown();
            });
            writer.get(10, TimeUnit.SECONDS);

            ExecutionException failure = assertThrows(
                    ExecutionException.class, () -> mixed.get(10, TimeUnit.SECONDS));
            AgentTaskWorkspaceException unavailable = assertInstanceOf(
                    AgentTaskWorkspaceException.class, failure.getCause());
            assertEquals(AgentTaskWorkspaceException.Reason.SNAPSHOT_UNAVAILABLE,
                    unavailable.getReason());
            connectionEvidence.assertNoTransactionUsesMultipleConnections();
        } finally {
            mutationCommitted.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTaskWorkspaceService transactional(AgentTaskWorkspaceDao dao) {
        AgentTaskWorkspaceServiceImpl target = target(dao);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(AgentTaskWorkspaceService.class);
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (AgentTaskWorkspaceService) proxy.getProxy();
    }

    private AgentTaskWorkspaceServiceImpl target(AgentTaskWorkspaceDao dao) {
        return new AgentTaskWorkspaceServiceImpl(realAgentService, dao);
    }

    private void assertReadOnlyMapperCoverage() {
        connectionEvidence.assertStatementSeen("AgentIdentityRegistryMapper.selectList");
        connectionEvidence.assertStatementSeen("AgentPersonaBindingMapper.selectById");
        connectionEvidence.assertStatementSeen("AgentRuntimeMapper.findExactByAgentId");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findTask");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findActorMember");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findMembers");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findWorkItems");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findOpenRequests");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findVisibleArtifacts");
        connectionEvidence.assertStatementSeen("AgentTaskWorkspaceMapper.findLatestEvents");
        assertTrue(connectionEvidence.onlySelectsWithoutLocks(),
                "snapshot must issue only non-locking SELECT statements");
        verifyNoInteractions(eventPublisherProvider, taskServiceProvider,
                apiKeyServiceProvider, sceneServiceProvider);
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
        insertWorkItem(TENANT, CLIENT, TASK, "work-1", 1L);
        insertArtifact(TENANT, CLIENT, TASK, "artifact-1", ACTOR,
                "task_members", 1001L, 1);
        jdbc.update("""
                INSERT INTO agent_task_event
                    (task_id,event_version,event_type,actor_type,actor_id,aggregate_type,
                     aggregate_id,event_json,occurred_at,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, TASK, 1L, "ARTIFACT_PUBLISHED", "agent", ACTOR,
                "artifact", "artifact-1",
                "{\"artifactId\":\"artifact-1\",\"artifactType\":\"document\","
                        + "\"artifactVersion\":1,\"contentSha256\":\""
                        + "a".repeat(64) + "\",\"visibility\":\"task_members\"}",
                1001L, TENANT, CLIENT);
        jdbc.update("UPDATE agent_task_meta SET task_version=1,current_event_version=1 "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", TENANT, CLIENT, TASK);
    }

    private void insertBeforeState() {
        insertTask(TENANT, CLIENT, TASK, 0L);
        insertMember(TENANT, CLIENT, TASK, ACTOR, "worker", "accepted", 0L);
    }

    private void seedIdentity() {
        seedIdentity(TENANT, CLIENT, ACTOR, 1L, "OPAQUE");
    }

    private void seedIdentity(
            String tenant, String client, String actor, long id, String canonicalType) {
        jdbc.update("""
                INSERT INTO agent_persona_binding
                    (id,jiacn,persona_code,agent_id,bound_at,status,
                     tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, tenant, "wuyong", actor, 1L, 1, tenant, client, 1L, 1L);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                    (id,canonical_agent_id,canonical_type,lifecycle_status,owner_jiacn,
                     binding_id,provisioned_at,activated_at,audit_reason,
                     tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,'ACTIVE',?,?,1,1,'c04-r3',?,?,1,1)
                """, id, actor, canonicalType, tenant, id, tenant, client);
        jdbc.update("""
                INSERT INTO agent_runtime
                    (id,agent_id,name,owner_jiacn,persona_code,persona_name,binding_id,
                     abilities,status,last_seen_at,tenant_id,client_id,create_time,update_time)
                VALUES (?,?,'Agent',?,'wuyong','Wu Yong',?,'[]','online',1,?,?,1,1)
                """, id, actor, tenant, id, tenant, client);
    }

    private void insertTask(String tenant, String client, String taskId, long currentVersion) {
        jdbc.update("""
                INSERT INTO agent_task_meta
                    (task_id,reward_status,collaboration_mode,risk_level,max_agents,
                     review_required,task_version,current_event_version,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, taskId, "running", "team", "low", 5, false,
                currentVersion, currentVersion, tenant, client);
    }

    private void insertMember(String tenant, String client, String taskId, String agentId,
            String role, String status, long version) {
        jdbc.update("""
                INSERT INTO agent_task_member
                    (task_id,agent_id,member_role,member_status,assignment_source,version,
                     tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, taskId, agentId, role, status, "manual", version, tenant, client);
    }

    private void insertWorkItem(
            String tenant, String client, String taskId, String id, long version) {
        jdbc.update("""
                INSERT INTO agent_task_work_item
                    (work_item_id,task_id,title,work_type,status,priority,required_item,
                     attempt_count,max_attempts,version,create_time,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, taskId, "work", "analysis", "running", 5, true,
                1, 3, version, 1000L, tenant, client);
    }

    private void insertRequest(
            String tenant, String client, String taskId, String id, long version) {
        jdbc.update("""
                INSERT INTO agent_task_request
                    (request_id,task_id,requester_agent_id,target_type,target_id,request_type,
                     status,priority,title,description,version,create_time,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, taskId, ACTOR, "agent", ACTOR, "help", "open", 1,
                "request", "safe", version, 1000L, tenant, client);
    }

    private void insertArtifact(String tenant, String client, String taskId, String id,
            String producer, String visibility, long createdAt, int version) {
        jdbc.update("""
                INSERT INTO agent_task_artifact
                    (artifact_id,task_id,producer_agent_id,artifact_type,title,content_hash,
                     artifact_version,visibility,created_at,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, taskId, producer, "document", "artifact", "a".repeat(64),
                version, visibility, createdAt, tenant, client);
    }

    private void insertEvent(String tenant, String client, String taskId, long version) {
        jdbc.update("""
                INSERT INTO agent_task_event
                    (task_id,event_version,event_type,actor_type,actor_id,aggregate_type,
                     aggregate_id,event_json,occurred_at,tenant_id,client_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, taskId, version, "TASK_STARTED", "system", null, "task", taskId,
                "{\"fromStatus\":\"assigned\",\"toStatus\":\"running\","
                        + "\"expectedVersion\":0,\"resultVersion\":1}",
                1000L + version, tenant, client);
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    jiacn VARCHAR(50) NOT NULL, persona_code VARCHAR(50) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL, bound_at BIGINT NOT NULL,
                    status INT NOT NULL, create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    canonical_agent_id VARCHAR(100) NOT NULL,
                    canonical_type VARCHAR(32) NOT NULL, lifecycle_status VARCHAR(20) NOT NULL,
                    owner_jiacn VARCHAR(50), binding_id BIGINT,
                    provisioned_at BIGINT, activated_at BIGINT, suspended_at BIGINT,
                    retired_at BIGINT, audit_reason VARCHAR(1000) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50),
                    KEY idx_c04_registry_scope
                        (tenant_id,client_id,owner_jiacn,canonical_agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    registry_id BIGINT NOT NULL, canonical_agent_id VARCHAR(100) NOT NULL,
                    alias_type VARCHAR(32) NOT NULL, alias_value VARCHAR(100) NOT NULL,
                    alias_status VARCHAR(20) NOT NULL, valid_from BIGINT NOT NULL,
                    valid_to BIGINT, owner_jiacn VARCHAR(50), audit_reason VARCHAR(1000),
                    create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    agent_id VARCHAR(100) NOT NULL, name VARCHAR(100), avatar VARCHAR(500),
                    owner_jiacn VARCHAR(50), persona_code VARCHAR(50), persona_name VARCHAR(100),
                    binding_id BIGINT, abilities TEXT, endpoint VARCHAR(500), token_hash VARCHAR(255),
                    status VARCHAR(20), current_task_id VARCHAR(100), current_task_title VARCHAR(255),
                    last_seen_at BIGINT, error_message TEXT, create_time BIGINT, update_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50),
                    KEY idx_c04_runtime_agent (agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100), reward_status VARCHAR(20),
                    assigned_agent_id VARCHAR(100), required_abilities TEXT, reward INT,
                    assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    collaboration_mode VARCHAR(20), risk_level VARCHAR(20), max_agents INT,
                    coordinator_agent_id VARCHAR(100), review_required BOOLEAN,
                    task_version BIGINT, current_event_version BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100), agent_id VARCHAR(100), member_role VARCHAR(20),
                    member_status VARCHAR(20), assignment_source VARCHAR(20), joined_at BIGINT,
                    accepted_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    last_heartbeat_at BIGINT, version BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    work_item_id VARCHAR(100), task_id VARCHAR(100), title VARCHAR(255),
                    description TEXT, work_type VARCHAR(30), required_abilities TEXT,
                    assignee_agent_id VARCHAR(100), status VARCHAR(20), priority INT,
                    required_item BOOLEAN, dependency_json TEXT, lease_until BIGINT,
                    attempt_count INT, max_attempts INT, result_artifact_id VARCHAR(100),
                    submitted_at BIGINT, completed_at BIGINT, version BIGINT, create_time BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_request (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    request_id VARCHAR(100), task_id VARCHAR(100), work_item_id VARCHAR(100),
                    requester_agent_id VARCHAR(100), target_type VARCHAR(20), target_id VARCHAR(100),
                    request_type VARCHAR(30), status VARCHAR(20), priority INT, title VARCHAR(255),
                    description TEXT, due_at BIGINT, acknowledged_at BIGINT, version BIGINT,
                    create_time BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    artifact_id VARCHAR(100), task_id VARCHAR(100), work_item_id VARCHAR(100),
                    producer_agent_id VARCHAR(100), artifact_type VARCHAR(30), title VARCHAR(255),
                    content_hash VARCHAR(128), artifact_version INT, visibility VARCHAR(20),
                    created_at BIGINT, tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100), event_version BIGINT, event_type VARCHAR(64),
                    actor_type VARCHAR(20), actor_id VARCHAR(100), aggregate_type VARCHAR(30),
                    aggregate_id VARCHAR(100), event_json MEDIUMTEXT, occurred_at BIGINT,
                    tenant_id VARCHAR(50), client_id VARCHAR(50)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
    }

    private SqlSessionFactory sqlSessionFactory(
            DataSource source, SqlConnectionEvidence evidence) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        configuration.addMapper(AgentTaskWorkspaceMapper.class);
        configuration.addInterceptor(evidence);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setBanner(false);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        bean.setGlobalConfig(globalConfig);
        return bean.getObject();
    }

    private static DriverManagerDataSource dataSource(
            String url, String username, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String head = query < 0 ? baseUrl : baseUrl.substring(0, query);
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        int path = head.indexOf('/', "jdbc:mysql://".length());
        return path < 0 ? head + "/" + database + suffix
                : head.substring(0, path + 1) + database + suffix;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " is required by the C04 MySQL gate");
        }
        return value;
    }

    private static <T> T wire(T dao, Object mapper) throws Exception {
        Class<?> type = dao.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("baseMapper");
                field.setAccessible(true);
                field.set(dao, mapper);
                return dao;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException("baseMapper");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }

    /** Releases the writer after the first task row has established the snapshot. */
    private static final class PausingDao implements AgentTaskWorkspaceDao {
        private final AgentTaskWorkspaceDao delegate;
        private final CountDownLatch firstReadCompleted;
        private final CountDownLatch mutationCommitted;
        private final AtomicBoolean pause = new AtomicBoolean(true);

        private PausingDao(AgentTaskWorkspaceDao delegate,
                CountDownLatch firstReadCompleted, CountDownLatch mutationCommitted) {
            this.delegate = delegate;
            this.firstReadCompleted = firstReadCompleted;
            this.mutationCommitted = mutationCommitted;
        }

        @Override
        public TaskRow findTask(String tenantId, String clientId, String taskId) {
            TaskRow row = delegate.findTask(tenantId, clientId, taskId);
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

        @Override public MemberRow findActorMember(String t, String c, String task, String actor) { return delegate.findActorMember(t, c, task, actor); }
        @Override public List<MemberRow> findMembers(String t, String c, String task) { return delegate.findMembers(t, c, task); }
        @Override public List<WorkItemRow> findWorkItems(String t, String c, String task) { return delegate.findWorkItems(t, c, task); }
        @Override public List<RequestRow> findOpenRequests(String t, String c, String task) { return delegate.findOpenRequests(t, c, task); }
        @Override public List<ArtifactRow> findVisibleArtifacts(String t, String c, String task, String actor, boolean reviewer, boolean coordinator) { return delegate.findVisibleArtifacts(t, c, task, actor, reviewer, coordinator); }
        @Override public ArtifactRow findArtifactVersion(String t, String c, String task, String artifact, int version) { return delegate.findArtifactVersion(t, c, task, artifact, version); }
        @Override public List<EventRow> findLatestEvents(String t, String c, String task) { return delegate.findLatestEvents(t, c, task); }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class,
                            ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class,
                            ResultHandler.class, org.apache.ibatis.cache.CacheKey.class,
                            org.apache.ibatis.mapping.BoundSql.class})
    })
    private static final class SqlConnectionEvidence implements Interceptor {
        private final List<SqlObservation> observations = new ArrayList<>();
        private volatile boolean capturing;

        private synchronized void beginCapture() {
            observations.clear();
            capturing = true;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (capturing) {
                Executor executor = (Executor) invocation.getTarget();
                MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
                Object parameter = invocation.getArgs()[1];
                Connection connection = executor.getTransaction().getConnection();
                long connectionId;
                String isolation;
                boolean readOnly;
                try (Statement probe = connection.createStatement();
                        ResultSet result = probe.executeQuery(
                                "SELECT CONNECTION_ID(), @@transaction_isolation, "
                                        + "@@transaction_read_only")) {
                    assertTrue(result.next());
                    connectionId = result.getLong(1);
                    isolation = result.getString(2);
                    readOnly = result.getInt(3) == 1;
                }
                String sql = statement.getBoundSql(parameter).getSql();
                synchronized (this) {
                    observations.add(new SqlObservation(statement.getId(),
                            statement.getSqlCommandType(), sql, connectionId, isolation,
                            readOnly, TransactionSynchronizationManager
                                    .isActualTransactionActive()));
                }
            }
            return invocation.proceed();
        }

        private synchronized void assertSingleTransactionalConnection(
                String isolation, boolean readOnly) {
            assertFalse(observations.isEmpty());
            assertEquals(1, observations.stream()
                    .map(SqlObservation::connectionId).distinct().count(), observations.toString());
            assertTrue(observations.stream().allMatch(SqlObservation::transactionActive));
            assertTrue(observations.stream().allMatch(row -> isolation.equals(row.isolation())));
            assertTrue(observations.stream().allMatch(row -> readOnly == row.readOnly()));
            System.out.printf("C04_CONNECTION_PROOF physicalConnectionId=%d isolation=%s "
                            + "readOnly=%s mapperQueries=%d%n",
                    observations.get(0).connectionId(), isolation, readOnly, observations.size());
        }

        private synchronized void assertNoTransactionUsesMultipleConnections() {
            assertFalse(observations.isEmpty());
            assertTrue(observations.stream()
                    .map(SqlObservation::connectionId).distinct().count() > 1,
                    observations.toString());
            assertTrue(observations.stream().noneMatch(SqlObservation::transactionActive));
            assertTrue(observations.stream().noneMatch(SqlObservation::readOnly));
            System.out.printf("C04_CONNECTION_NEGATIVE noTransaction=true "
                            + "physicalConnections=%d mapperQueries=%d%n",
                    observations.stream().map(SqlObservation::connectionId).distinct().count(),
                    observations.size());
        }

        private synchronized void assertStatementSeen(String suffix) {
            assertTrue(observations.stream().anyMatch(row -> row.statementId().endsWith(suffix)),
                    "missing mapper statement " + suffix + " from " + observations);
        }

        private synchronized boolean onlySelectsWithoutLocks() {
            return observations.stream().allMatch(row ->
                    row.commandType() == SqlCommandType.SELECT
                            && !row.sql().toLowerCase(Locale.ROOT).contains("for update")
                            && !row.statementId().toLowerCase(Locale.ROOT).contains("chat"));
        }
    }

    private record SqlObservation(String statementId, SqlCommandType commandType,
            String sql, long connectionId, String isolation, boolean readOnly,
            boolean transactionActive) {
    }
}
