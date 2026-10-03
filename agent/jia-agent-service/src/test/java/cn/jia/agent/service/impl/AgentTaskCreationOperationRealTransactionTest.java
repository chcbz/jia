package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskCreationOperationDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskCreationOperationEntity;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskCreationOperationService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.agent.service.PersonalWorkspaceTaskLinkService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real Spring REQUIRED proxies and H2 rows prove all sidecar/nested writes share one rollback. */
class AgentTaskCreationOperationRealTransactionTest {
    private static final AgentTaskCreationOperationService.Scope SCOPE =
            new AgentTaskCreationOperationService.Scope("0", "client-a", "owner-a");

    private JdbcTemplate jdbc;
    private AgentTaskCreationOperationService service;
    private AtomicBoolean failSecondReference;
    private AtomicBoolean failCompletion;
    private AtomicInteger taskCalls;
    private AtomicInteger linkCalls;

    @BeforeEach
    void setUp() {
        authenticate();
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:atco_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();
        PlatformTransactionManager manager = new DataSourceTransactionManager(source);
        failSecondReference = new AtomicBoolean();
        failCompletion = new AtomicBoolean();
        taskCalls = new AtomicInteger();
        linkCalls = new AtomicInteger();

        JdbcOperationDao operationRows = new JdbcOperationDao(jdbc, failCompletion);
        AgentService agentTarget = mock(AgentService.class);
        when(agentTarget.createTask(any())).thenAnswer(invocation -> {
            taskCalls.incrementAndGet();
            cn.jia.agent.entity.AgentTaskCreateDTO request = invocation.getArgument(0);
            String taskId = "task-" + taskCalls.get();
            jdbc.update("INSERT INTO fixture_task(task_id,title,description) VALUES (?,?,?)",
                    taskId, request.getTitle(), request.getDescription());
            jdbc.update("INSERT INTO fixture_snapshot(task_id,revision,title,description,source) "
                            + "VALUES (?,1,?,?, 'CREATE')",
                    taskId, request.getTitle(), request.getDescription());
            jdbc.update("INSERT INTO fixture_event(task_id,event_type) VALUES (?,'TASK_CREATED')",
                    taskId);
            return task(taskId, request.getTitle(), request.getDescription());
        });
        when(agentTarget.getTask(anyString())).thenAnswer(invocation -> {
            String taskId = invocation.getArgument(0);
            return jdbc.queryForObject("SELECT task_id,title,description FROM fixture_task "
                            + "WHERE task_id=?", (rs, row) ->
                            task(rs.getString(1), rs.getString(2), rs.getString(3)), taskId);
        });
        AgentService agentProxy = requiredProxy(agentTarget, AgentService.class, manager);

        PersonalWorkspaceTaskLinkService linkTarget = mock(PersonalWorkspaceTaskLinkService.class);
        when(linkTarget.create(any(), anyString(), any())).thenAnswer(invocation -> {
            int call = linkCalls.incrementAndGet();
            String taskId = invocation.getArgument(1);
            PersonalWorkspaceTaskLinkService.CreateCommand command = invocation.getArgument(2);
            jdbc.update("INSERT INTO fixture_link_operation(operation_key,task_id,file_id) "
                            + "VALUES (?,?,?)", command.idempotencyKey(), taskId, command.fileId());
            if (failSecondReference.get() && command.fileId().equals("file-b")) {
                throw new PersonalWorkspaceTaskLinkService.Failure(
                        PersonalWorkspaceTaskLinkService.Reason.NOT_FOUND);
            }
            String relationId = "relation-" + call;
            jdbc.update("INSERT INTO fixture_link(relation_id,task_id,file_id,file_version,role) "
                            + "VALUES (?,?,?,?,?)", relationId, taskId, command.fileId(),
                    command.version(), command.role());
            return new PersonalWorkspaceTaskLinkService.LinkView(relationId, taskId,
                    command.fileId(), command.version(), command.role(), "ACTIVE", 1, 1);
        });
        PersonalWorkspaceTaskLinkService linkProxy = requiredProxy(
                linkTarget, PersonalWorkspaceTaskLinkService.class, manager);

        AgentTaskRequirementSnapshotService requirements =
                mock(AgentTaskRequirementSnapshotService.class);
        when(requirements.read(any(AgentTaskExecutionGrantService.Scope.class), anyString(),
                Mockito.eq(1L))).thenAnswer(invocation -> {
            AgentTaskExecutionGrantService.Scope scope = invocation.getArgument(0);
            String taskId = invocation.getArgument(1);
            return jdbc.queryForObject("SELECT title,description,source FROM fixture_snapshot "
                            + "WHERE task_id=? AND revision=1", (rs, row) ->
                            new AgentTaskRequirementSnapshotService.Snapshot(scope.tenantId(),
                                    scope.clientId(), scope.ownerJiacn(), taskId, 1,
                                    rs.getString(1), rs.getString(2), "a".repeat(64),
                                    rs.getString(3)), taskId);
        });
        PersonalWorkspaceTaskLinkDao linkRows = mock(PersonalWorkspaceTaskLinkDao.class);
        when(linkRows.lockTask(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> count("fixture_task") > 0);
        when(linkRows.taskExists(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String taskId = invocation.getArgument(3, String.class);
                    return jdbc.queryForObject(
                            "SELECT COUNT(*) FROM fixture_task WHERE task_id=?", Integer.class,
                            taskId) == 1;
                });
        PersonalWorkspaceDao workspace = mock(PersonalWorkspaceDao.class);
        when(workspace.lockFile(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> file(invocation.getArgument(3)));
        when(workspace.findVersion(anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> version(invocation.getArgument(3),
                        invocation.getArgument(4)));

        AgentTaskCreationOperationServiceImpl raw = new AgentTaskCreationOperationServiceImpl(
                operationRows, agentProxy, requirements, linkProxy, linkRows, workspace,
                JsonMapper.builder().build());
        service = annotatedProxy(raw, manager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void actualSpringContextConstructsClassProxyAndAppliesReadOnlyTransaction() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(ClassProxyConfiguration.class)) {
            AgentTaskCreationOperationServiceImpl proxied =
                    context.getBean(AgentTaskCreationOperationServiceImpl.class);
            assertTrue(AopUtils.isCglibProxy(proxied),
                    "production class-proxy mode must proxy the concrete transactional service");

            AgentTaskCreationOperationService.Failure missing = assertThrows(
                    AgentTaskCreationOperationService.Failure.class,
                    () -> proxied.getByIdempotencyKey(SCOPE, "class-proxy-key"));
            assertEquals(AgentTaskCreationOperationService.Reason.NOT_FOUND, missing.reason());
            assertTrue(context.getBean(ObservedTransaction.class).readOnlyActive.get(),
                    "Spring transaction advice must be active before the DAO read");
        }
    }

    @Test
    void secondReferenceAndCompletionFailuresRollbackEveryNestedWriteThenReplayIsReadOnly() {
        var successful = service.create(SCOPE, "success-key", command());
        assertEquals("COMMITTED", successful.receipt().state());
        assertCounts(1, 1, 1, 2, 2, 1);

        int tasksBeforeReplay = taskCalls.get();
        int linksBeforeReplay = linkCalls.get();
        var replay = service.create(SCOPE, "success-key", command());
        assertTrue(replay.replay());
        assertEquals(tasksBeforeReplay, taskCalls.get());
        assertEquals(linksBeforeReplay, linkCalls.get());
        assertCounts(1, 1, 1, 2, 2, 1);
        assertEquals(replay.receipt(), service.getByIdempotencyKey(SCOPE, "success-key"));
        assertCounts(1, 1, 1, 2, 2, 1);

        failSecondReference.set(true);
        AgentTaskCreationOperationService.Failure second = assertThrows(
                AgentTaskCreationOperationService.Failure.class,
                () -> service.create(SCOPE, "second-fails", command()));
        assertEquals(AgentTaskCreationOperationService.Reason.NOT_FOUND, second.reason());
        assertCounts(1, 1, 1, 2, 2, 1);

        failSecondReference.set(false);
        failCompletion.set(true);
        AgentTaskCreationOperationService.Failure completion = assertThrows(
                AgentTaskCreationOperationService.Failure.class,
                () -> service.create(SCOPE, "completion-fails", command()));
        assertEquals(AgentTaskCreationOperationService.Reason.UNAVAILABLE, completion.reason());
        assertCounts(1, 1, 1, 2, 2, 1);
    }

    @Test
    void genericMaterialsFailureCannotLeaveTaskOrPartialLinksAndRecoveryDoesNotWriteAgain() {
        var old = command();
        var generic = new AgentTaskCreationOperationService.CreateCommand(2, old.title(),
                old.descriptionPresent(), old.description(), old.requiredAbilitiesPresent(),
                old.requiredAbilities(), old.rewardPresent(), old.reward(), old.inputRefs().stream()
                    .map(ref -> new AgentTaskCreationOperationService.InputReference(ref.fileId(), ref.version(), "INPUT")).toList());
        failSecondReference.set(true);
        assertThrows(AgentTaskCreationOperationService.Failure.class, () -> service.create(SCOPE, "v2-rollback", generic));
        assertCounts(0, 0, 0, 0, 0, 0);
        failSecondReference.set(false); failCompletion.set(true);
        assertThrows(AgentTaskCreationOperationService.Failure.class, () -> service.create(SCOPE, "v2-cas", generic));
        assertCounts(0, 0, 0, 0, 0, 0);
        failCompletion.set(false);
        var first = service.create(SCOPE, "v2-success", generic);
        assertCounts(1, 1, 1, 2, 2, 1);
        assertEquals(List.of("INPUT", "INPUT"), jdbc.queryForList("SELECT role FROM fixture_link ORDER BY file_id", String.class));
        int calls = linkCalls.get();
        assertEquals(first.receipt(), service.getByIdempotencyKey(SCOPE, "v2-success", 2));
        assertTrue(service.create(SCOPE, "v2-success", generic).replay());
        assertEquals(calls, linkCalls.get());
        assertCounts(1, 1, 1, 2, 2, 1);
    }

    private void assertCounts(int tasks, int snapshots, int events, int links,
            int linkOperations, int operations) {
        assertEquals(tasks, count("fixture_task"));
        assertEquals(snapshots, count("fixture_snapshot"));
        assertEquals(events, count("fixture_event"));
        assertEquals(links, count("fixture_link"));
        assertEquals(linkOperations, count("fixture_link_operation"));
        assertEquals(operations, count("fixture_operation"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void createTables() {
        jdbc.execute("CREATE TABLE fixture_operation (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id VARCHAR(50),client_id VARCHAR(50),owner_jiacn VARCHAR(50),"
                + "idempotency_key VARCHAR(100),operation_id VARCHAR(100),request_hash CHAR(64),"
                + "operation_state VARCHAR(20),task_id VARCHAR(100),requirement_revision BIGINT,"
                + "input_refs_json VARCHAR(16000),created_at BIGINT,completed_at BIGINT,"
                + "UNIQUE(tenant_id,client_id,owner_jiacn,idempotency_key))");
        jdbc.execute("CREATE TABLE fixture_task(task_id VARCHAR(100) PRIMARY KEY,title CLOB,description CLOB)");
        jdbc.execute("CREATE TABLE fixture_snapshot(task_id VARCHAR(100),revision BIGINT,title CLOB,description CLOB,source VARCHAR(20))");
        jdbc.execute("CREATE TABLE fixture_event(task_id VARCHAR(100),event_type VARCHAR(40))");
        jdbc.execute("CREATE TABLE fixture_link(relation_id VARCHAR(100),task_id VARCHAR(100),file_id VARCHAR(100),file_version INT,role VARCHAR(20))");
        jdbc.execute("CREATE TABLE fixture_link_operation(operation_key VARCHAR(100),task_id VARCHAR(100),file_id VARCHAR(100))");
    }

    private static AgentTaskCreationOperationService.CreateCommand command() {
        return new AgentTaskCreationOperationService.CreateCommand("完整🚀标题", true, "",
                true, List.of(), true, null, List.of(
                        new AgentTaskCreationOperationService.InputReference(
                                "file-a", 1, "REFERENCE"),
                        new AgentTaskCreationOperationService.InputReference(
                                "file-b", 2, "REFERENCE")));
    }

    private static AgentTaskDTO task(String id, String title, String description) {
        AgentTaskDTO task = new AgentTaskDTO();
        task.setId(id);
        task.setTenantId("0");
        task.setClientId("client-a");
        task.setTitle(title);
        task.setDescription(description);
        task.setRequiredAbilities(List.of());
        return task;
    }

    private static PersonalWorkspaceFileEntity file(String id) {
        PersonalWorkspaceFileEntity file = new PersonalWorkspaceFileEntity()
                .setFileId(id).setOwnerJiacn("owner-a").setState("ACTIVE");
        file.setTenantId("0");
        file.setClientId("client-a");
        return file;
    }

    private static PersonalWorkspaceVersionEntity version(String id, int version) {
        PersonalWorkspaceVersionEntity row = new PersonalWorkspaceVersionEntity()
                .setFileId(id).setOwnerJiacn("owner-a").setVersion(version)
                .setContentMimeType(version == 1 ? "image/jpeg" : "image/png");
        row.setTenantId("0");
        row.setClientId("client-a");
        return row;
    }

    private static <T> T requiredProxy(T target, Class<T> contract,
            PlatformTransactionManager manager) {
        DefaultTransactionAttribute attribute = new DefaultTransactionAttribute(
                TransactionDefinition.PROPAGATION_REQUIRED);
        MatchAlwaysTransactionAttributeSource source = new MatchAlwaysTransactionAttributeSource();
        source.setTransactionAttribute(attribute);
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(contract);
        factory.addAdvice(new TransactionInterceptor(manager, source));
        return contract.cast(factory.getProxy());
    }

    private static AgentTaskCreationOperationService annotatedProxy(
            AgentTaskCreationOperationService target, PlatformTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(AgentTaskCreationOperationService.class);
        factory.addAdvice(new TransactionInterceptor(manager,
                new AnnotationTransactionAttributeSource()));
        return (AgentTaskCreationOperationService) factory.getProxy();
    }

    private static final class ObservedTransaction {
        private final AtomicBoolean readOnlyActive = new AtomicBoolean();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ClassProxyConfiguration {
        @Bean
        DriverManagerDataSource classProxyDataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:atco_proxy_" + UUID.randomUUID()
                    + ";MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DriverManagerDataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        ObservedTransaction observedTransaction() {
            return new ObservedTransaction();
        }

        @Bean
        AgentTaskCreationOperationDao operationDao(ObservedTransaction observed) {
            AgentTaskCreationOperationDao dao = mock(AgentTaskCreationOperationDao.class);
            when(dao.find(anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> {
                        observed.readOnlyActive.set(
                                TransactionSynchronizationManager.isActualTransactionActive()
                                        && TransactionSynchronizationManager
                                                .isCurrentTransactionReadOnly());
                        return null;
                    });
            return dao;
        }

        @Bean
        AgentService agentService() {
            return mock(AgentService.class);
        }

        @Bean
        AgentTaskRequirementSnapshotService requirementSnapshotService() {
            return mock(AgentTaskRequirementSnapshotService.class);
        }

        @Bean
        PersonalWorkspaceTaskLinkService taskLinkService() {
            return mock(PersonalWorkspaceTaskLinkService.class);
        }

        @Bean
        PersonalWorkspaceTaskLinkDao taskLinkDao() {
            return mock(PersonalWorkspaceTaskLinkDao.class);
        }

        @Bean
        PersonalWorkspaceDao workspaceDao() {
            return mock(PersonalWorkspaceDao.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        AgentTaskCreationOperationServiceImpl creationOperationService(
                AgentTaskCreationOperationDao operations, AgentService agents,
                AgentTaskRequirementSnapshotService requirements,
                PersonalWorkspaceTaskLinkService links,
                PersonalWorkspaceTaskLinkDao linkRows, PersonalWorkspaceDao workspace,
                ObjectMapper json) {
            return new AgentTaskCreationOperationServiceImpl(operations, agents, requirements,
                    links, linkRows, workspace, json);
        }
    }

    private static void authenticate() {
        Jwt jwt = Jwt.withTokenValue("fixture").header("alg", "none")
                .claim("tenant_id", "0").claim("jiacn", "owner-a")
                .claim("client_id", "client-a").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        EsContext context = new EsContext();
        context.setTenantId("0");
        context.setClientId("client-a");
        context.setJiacn("owner-a");
        EsContextHolder.setContext(context);
    }

    private static final class JdbcOperationDao implements AgentTaskCreationOperationDao {
        private final JdbcTemplate jdbc;
        private final AtomicBoolean failCompletion;

        private JdbcOperationDao(JdbcTemplate jdbc, AtomicBoolean failCompletion) {
            this.jdbc = jdbc;
            this.failCompletion = failCompletion;
        }

        @Override
        public AgentTaskCreationOperationEntity reserveAndLock(String tenantId, String clientId,
                String ownerJiacn, String key, String hash, String operationId, String refs,
                long createdAt) {
            try {
                jdbc.update("INSERT INTO fixture_operation "
                                + "(tenant_id,client_id,owner_jiacn,idempotency_key,operation_id,"
                                + "request_hash,operation_state,input_refs_json,created_at) "
                                + "VALUES (?,?,?,?,?,?,'PROCESSING',?,?)",
                        tenantId, clientId, ownerJiacn, key, operationId, hash, refs, createdAt);
            } catch (org.springframework.dao.DuplicateKeyException repeated) {
                // The original immutable reservation wins; lock/read below decides replay/conflict.
            }
            return findLocked(tenantId, clientId, ownerJiacn, key);
        }

        @Override
        public AgentTaskCreationOperationEntity find(String tenantId, String clientId,
                String ownerJiacn, String key) {
            return rows(tenantId, clientId, ownerJiacn, key, false).stream()
                    .findFirst().orElse(null);
        }

        @Override
        public boolean complete(String tenantId, String clientId, String ownerJiacn,
                String operationId, String requestHash, String refs, String taskId,
                long revision, long completedAt) {
            if (failCompletion.get()) return false;
            return jdbc.update("UPDATE fixture_operation SET operation_state='COMMITTED',"
                            + "task_id=?,requirement_revision=?,completed_at=? WHERE tenant_id=? "
                            + "AND client_id=? AND owner_jiacn=? AND operation_id=? "
                            + "AND request_hash=? AND input_refs_json=? AND operation_state='PROCESSING'",
                    taskId, revision, completedAt, tenantId, clientId, ownerJiacn,
                    operationId, requestHash, refs) == 1;
        }

        private AgentTaskCreationOperationEntity findLocked(String tenantId, String clientId,
                String ownerJiacn, String key) {
            return rows(tenantId, clientId, ownerJiacn, key, true).stream()
                    .findFirst().orElseThrow();
        }

        private List<AgentTaskCreationOperationEntity> rows(String tenantId, String clientId,
                String ownerJiacn, String key, boolean lock) {
            return jdbc.query("SELECT tenant_id,client_id,owner_jiacn,idempotency_key,operation_id,"
                            + "request_hash,operation_state,task_id,requirement_revision,"
                            + "input_refs_json,created_at,completed_at FROM fixture_operation "
                            + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? "
                            + "AND idempotency_key=?" + (lock ? " FOR UPDATE" : ""),
                    (rs, row) -> {
                        AgentTaskCreationOperationEntity entity =
                                new AgentTaskCreationOperationEntity()
                                        .setOwnerJiacn(rs.getString(3))
                                        .setIdempotencyKey(rs.getString(4))
                                        .setOperationId(rs.getString(5))
                                        .setRequestHash(rs.getString(6))
                                        .setOperationState(rs.getString(7))
                                        .setTaskId(rs.getString(8))
                                        .setRequirementRevision((Long) rs.getObject(9))
                                        .setInputRefsJson(rs.getString(10))
                                        .setCreatedAt(rs.getLong(11))
                                        .setCompletedAt((Long) rs.getObject(12));
                        entity.setTenantId(rs.getString(1));
                        entity.setClientId(rs.getString(2));
                        return entity;
                    }, tenantId, clientId, ownerJiacn, key);
        }
    }
}
