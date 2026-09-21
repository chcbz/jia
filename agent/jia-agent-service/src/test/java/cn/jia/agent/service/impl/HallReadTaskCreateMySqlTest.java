package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.config.HallPrivateCaseSchemaInitializer;
import cn.jia.agent.config.HallRequestDraftSchemaInitializer;
import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.*;
import cn.jia.agent.entity.HallItemRow;
import cn.jia.agent.event.AgentEventPublisher;
import cn.jia.agent.mapper.*;
import cn.jia.agent.service.*;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.service.BaseServiceImpl;
import cn.jia.task.mapper.TaskItemMapper;
import cn.jia.task.mapper.TaskPlanMapper;
import cn.jia.task.service.TaskService;
import cn.jia.task.service.impl.TaskItemDaoImpl;
import cn.jia.task.service.impl.TaskPlanDaoImpl;
import cn.jia.task.service.impl.TaskServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in isolated MySQL only. Actual Hall/Task services, MyBatis DAOs, task plan/items, event writer,
 * and one physical Spring transaction. No Provider, real broker, production DB or version hard gate.
 * Reuses the B01B isolated fixture credential/acknowledgement variables, with a distinct DB namespace.
 */
@EnabledIfEnvironmentVariable(named = "JYT_UX_B01B_MYSQL_URL", matches = ".+")
class HallReadTaskCreateMySqlTest {
    private static final HallRequestDraftService.OwnerScope OWNER = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String namespace;
    private DataSourceTransactionManager transactionManager;
    private HallRequestDraftService hall;
    private HallReadService reads;
    private HallReadMapper readMapper;
    private PersonalWorkspaceExecutionService executions;
    private AgentTaskEventBroker broker;
    private AgentEventPublisher events;
    private AgentTaskEventAfterCommitPublisher wakeups;
    private AtomicBoolean failReceipt;

    @BeforeEach void setUp() throws Exception {
        String url = required("JYT_UX_B01B_MYSQL_URL");
        String user = required("JYT_UX_B01B_MYSQL_USER");
        String password = System.getenv("JYT_UX_B01B_MYSQL_PASSWORD");
        String prefix = required("JYT_UX_B01B_MYSQL_DATABASE_PREFIX");
        if (password == null || !url.startsWith("jdbc:mysql://") || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !"true".equals(required("JYT_UX_B01B_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("Isolated MySQL acknowledgement and task prefix required");
        }
        namespace = prefix + "_jyt_b02_"; database = namespace + UUID.randomUUID().toString().substring(0, 8);
        admin = new JdbcTemplate(dataSource(url, user, password));
        // Compatibility is demonstrated by executing real SQL, not a newly invented engine-version rejection.
        assertNotNull(admin.queryForObject("SELECT VERSION()", String.class));
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DataSource source = dataSource(databaseUrl(url, database), user, password);
        jdbc = new JdbcTemplate(source);
        new HallRequestDraftSchemaInitializer(jdbc).afterPropertiesSet();
        new HallPrivateCaseSchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/hall-b02-task-read-fixture.sql")).execute(source);
        identity(OWNER);
        MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(HallRequestDraftMapper.class, HallPrivateCaseMapper.class,
                HallCaseExecutionMapper.class, HallReadMapper.class, AgentTaskMetaMapper.class,
                AgentTaskEventMapper.class, TaskPlanMapper.class, TaskItemMapper.class)) configuration.addMapper(mapper);
        GlobalConfig global = new GlobalConfig(); global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean(); bean.setDataSource(source);
        bean.setConfiguration(configuration); bean.setGlobalConfig(global);
        SqlSessionTemplate template = new SqlSessionTemplate(java.util.Objects.requireNonNull(bean.getObject()));
        transactionManager = new DataSourceTransactionManager(source);
        AgentTaskMetaDaoImpl tasks = new AgentTaskMetaDaoImpl(); mapper(tasks, template.getMapper(AgentTaskMetaMapper.class));
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl(); mapper(eventDao, template.getMapper(AgentTaskEventMapper.class));
        TaskPlanDaoImpl plans = new TaskPlanDaoImpl(); mapper(plans, template.getMapper(TaskPlanMapper.class));
        TaskItemDaoImpl items = new TaskItemDaoImpl(); mapper(items, template.getMapper(TaskItemMapper.class));
        TaskServiceImpl taskService = new TaskServiceImpl();
        field(BaseServiceImpl.class, "baseDao", taskService, plans);
        field(TaskServiceImpl.class, "taskItemDao", taskService, items);
        broker = mock(AgentTaskEventBroker.class); events = mock(AgentEventPublisher.class);
        wakeups = new AgentTaskEventAfterCommitPublisher(broker, transactionManager);
        AgentTaskEventWriter writer = new AgentTaskEventWriterImpl(eventDao, transactionManager, wakeups);
        AgentServiceImpl rawAgents = new AgentServiceImpl(mock(AgentRuntimeDao.class), mock(AgentIdentityService.class),
                mock(AgentPersonaDao.class), mock(AgentPersonaBindingDao.class), tasks, mock(AgentTaskMemberDao.class),
                mock(AgentLegacyTaskCompatibilityService.class), mock(AgentTaskNoteDao.class), mock(DialogueTemplateDao.class),
                provider(events), provider((TaskService) taskService), provider(), provider(),
                new AgentScopePublicationCoordinator(), new AgentSceneFeatureFlags(false, false),
                new AgentTaskMutationTransactionImpl(tasks, transactionManager), writer);
        AgentService agents = proxy(rawAgents, AgentService.class);
        HallTaskCreationService creation = proxy(new HallTaskCreationServiceImpl(agents, tasks, provider((TaskService) taskService)), HallTaskCreationService.class);
        HallRequestDraftDao drafts = spy(new HallRequestDraftDaoImpl(template.getMapper(HallRequestDraftMapper.class)));
        failReceipt = new AtomicBoolean();
        doAnswer(invocation -> failReceipt.get() ? 0 : invocation.callRealMethod()).when(drafts)
                .markSubmitted(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(),
                        nullable(String.class), anyString(), nullable(String.class), anyLong());
        HallPrivateCaseDao cases = new HallPrivateCaseDaoImpl(template.getMapper(HallPrivateCaseMapper.class), template.getMapper(HallCaseExecutionMapper.class));
        executions = mock(PersonalWorkspaceExecutionService.class);
        hall = proxy(new HallRequestDraftServiceImpl(drafts, cases, executions, mock(PersonalWorkspaceDao.class), tasks,
                agents, null, new PersonalWorkspaceExecutionProperties(List.of(PersonalWorkspaceExecutionProperties.DOCX)),
                System::currentTimeMillis, creation), HallRequestDraftService.class);
        readMapper = template.getMapper(HallReadMapper.class);
        reads = new HallReadServiceImpl(new HallReadDaoImpl(readMapper));
    }

    @AfterEach void tearDown() {
        clearIdentity();
        if (wakeups != null) wakeups.close();
        if (admin != null && database != null && namespace != null && database.startsWith(namespace)
                && database.matches("[A-Za-z0-9_]+")) admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test void realTaskPlanItemsEventAndDraftCommitOnceAndLostResponseReconciles() {
        String draft = draft("one");
        var receipt = hall.submit(OWNER, draft, 1, true, "submit");
        assertNull(receipt.execution()); assertNotNull(receipt.task()); assertEquals("TASK", receipt.ref().sourceType());
        assertEquals(receipt, hall.getSubmissionByIdempotencyKey(OWNER, "submit"));
        assertEquals(receipt, hall.submit(OWNER, draft, 1, true, "submit"));
        for (String table : List.of("task_plan", "task_item", "agent_task_meta", "agent_task_event")) assertEquals(1, count(table));
        assertEquals(0, count("hall_private_case")); assertEquals(0, count("hall_case_execution"));
        assertEquals("SUBMITTED", jdbc.queryForObject("SELECT state FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertEquals("body", jdbc.queryForObject("SELECT description FROM task_plan", String.class));
        assertNull(jdbc.queryForObject("SELECT amount FROM task_plan", java.math.BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Integer.class));
        verifyNoInteractions(executions);
        var page = reads.items(OWNER, "task", "recent", "title", null);
        assertEquals("complete", page.section().status()); assertEquals(receipt.task().taskId(), page.section().partitions().get("task").items().getFirst().ref().sourceId());
    }

    @Test void finalReceiptFailureRollsBackEveryTaskSideEffectAndSubmitKey() {
        String draft = draft("rollback"); failReceipt.set(true);
        assertThrows(HallRequestDraftService.Failure.class, () -> hall.submit(OWNER, draft, 1, true, "submit"));
        assertNoTaskWrites();
        assertEquals("EDITING", jdbc.queryForObject("SELECT state FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertNull(jdbc.queryForObject("SELECT submit_key FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        verifyNoInteractions(broker, events, executions);
        failReceipt.set(false);
        assertNotNull(hall.submit(OWNER, draft, 1, true, "submit").task());
        assertEquals(1, count("task_plan"));
    }

    @Test void outerRollbackAndTaskItemFailureCannotLeaveAnAcceptedTask() {
        String draft = draft("outer");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            hall.submit(OWNER, draft, 1, true, "submit"); status.setRollbackOnly();
        });
        assertNoTaskWrites(); verifyNoInteractions(broker, events);
        // Real write failure after task_plan INSERT (not a mocked application result).
        jdbc.execute("ALTER TABLE task_item ADD CONSTRAINT fixture_reject_item CHECK (plan_id < 0)");
        assertThrows(RuntimeException.class, () -> hall.submit(OWNER, draft, 1, true, "submit"));
        assertNoTaskWrites();
        assertNull(jdbc.queryForObject("SELECT submit_key FROM hall_request_draft WHERE draft_id=?", String.class, draft));
    }

    @Test void duplicateConcurrentSubmitCreatesOneTaskAndDifferentDraftSameKeyConflicts() throws Exception {
        String draft = draft("race"); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HallRequestDraftService.SubmissionReceipt> call = () -> {
                identity(OWNER); try { start.await(); return hall.submit(OWNER, draft, 1, true, "submit"); }
                finally { clearIdentity(); }
            };
            var left = pool.submit(call); var right = pool.submit(call); start.countDown();
            assertEquals(left.get(), right.get());
        }
        assertEquals(1, count("task_plan")); assertEquals(1, count("agent_task_event"));
        String other = draft("other");
        assertEquals(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT, assertThrows(HallRequestDraftService.Failure.class,
                () -> hall.submit(OWNER, other, 1, true, "submit")).reason());
        verifyNoInteractions(executions);
    }

    @Test void cursorPagingRealSqlIsExactAcrossOwnerClientCaseAndTimestampTies() {
        for (int i=0; i<23; i++) { String id = draft("page-" + i); jdbc.update("UPDATE hall_request_draft SET updated_at=100 WHERE draft_id=?", id); }
        var first = reads.items(OWNER, "draft", "recent", "title", null).section().partitions().get("draft");
        assertEquals(20, first.items().size()); assertNotNull(first.nextCursor());
        var second = reads.items(OWNER, "draft", "recent", "title", first.nextCursor()).section().partitions().get("draft");
        assertEquals(3, second.items().size()); assertNull(second.nextCursor());
        Set<String> ids = first.items().stream().map(i -> i.ref().sourceId()).collect(Collectors.toSet());
        second.items().forEach(item -> assertTrue(ids.add(item.ref().sourceId()))); assertEquals(23, ids.size());
        for (var other : List.of(new HallRequestDraftService.OwnerScope("0", "client-a", "owner-b"),
                new HallRequestDraftService.OwnerScope("0", "client-b", "owner-a"),
                new HallRequestDraftService.OwnerScope("0", "client-a", "Owner-a"))) {
            assertTrue(reads.items(other, "draft", "recent", null, null).section().partitions().get("draft").items().isEmpty());
            assertThrows(HallReadService.Failure.class, () -> reads.items(other, "draft", "recent", "title", first.nextCursor()));
        }
        assertTrue(readMapper.page("1", "client-a", "owner-a", "draft", "recent", "", null, null, null, 21).isEmpty());
        assertTrue(readMapper.page("0", "client-a ", "owner-a", "draft", "recent", "", null, null, null, 21).isEmpty());
    }

    @Test void privateLatestRevisionAndUnlinkedLegacyAreReadOnlyAndTaskRunsStayOut() {
        seedExecution("old", "PRIVATE", "OUTPUT_COMMITTED", 100L);
        seedExecution("latest", "PRIVATE", "QUEUED", 400L);
        seedExecution("legacy", "PRIVATE", "FAILED", 300L);
        seedExecution("formal", "TASK", "FAILED", 900L);
        jdbc.update("INSERT INTO hall_private_case (case_id,tenant_id,client_id,owner_jiacn,title,origin_ref,revision,created_at,updated_at) VALUES ('case','0','client-a','owner-a','case title','test',2,1,2)");
        jdbc.update("INSERT INTO hall_case_execution (tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,created_at) VALUES ('0','client-a','owner-a','case','old',1,1)");
        jdbc.update("INSERT INTO hall_case_execution (tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,parent_execution_id,source_output_ref_json,created_at) VALUES ('0','client-a','owner-a','case','latest',2,'old',?,2)",
                "{\"executionId\":\"old\",\"outputId\":\"out\",\"fileId\":\"file\",\"fileVersion\":1}");
        var recent = reads.items(OWNER, "private", "recent", null, null).section().partitions().get("private");
        assertEquals("complete", recent.status()); assertEquals(2, recent.items().size());
        assertEquals("PRIVATE_CASE", recent.items().getFirst().ref().sourceType()); assertEquals("QUEUED", recent.items().getFirst().status().code());
        assertEquals(400L, recent.items().getFirst().updatedAt());
        var actions = reads.items(OWNER, "private", "needsAction", null, null).section().partitions().get("private");
        assertEquals("partial", actions.status()); assertEquals("legacy", actions.items().getFirst().ref().sourceId());
        assertEquals(1, actions.items().size()); assertEquals(1, count("hall_private_case")); assertEquals(2, count("hall_case_execution"));
        verifyNoInteractions(executions);
        jdbc.execute("RENAME TABLE agent_task_meta TO fixture_hidden_task_meta");
        assertEquals("error", reads.items(OWNER, "task", "recent", null, null).section().status());
    }

    @Test void jwtContextMismatchAndForeignDraftCannotReserveOrCreate() {
        String draft = draft("scope");
        var other = new HallRequestDraftService.OwnerScope("0", "client-a", "owner-b"); identity(other);
        assertEquals(HallRequestDraftService.Reason.NOT_FOUND, assertThrows(HallRequestDraftService.Failure.class,
                () -> hall.submit(other, draft, 1, true, "key")).reason());
        identity(OWNER); EsContext context = new EsContext(); context.setClientId("client-a"); context.setJiacn("owner-b"); EsContextHolder.setContext(context);
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE, assertThrows(HallRequestDraftService.Failure.class,
                () -> hall.submit(OWNER, draft, 1, true, "key")).reason());
        assertNoTaskWrites();
    }

    private String draft(String key) {
        return hall.create(OWNER, new HallRequestDraftService.CreateCommand("TASK_CREATE", "hall", null, null, null, null,
                new HallRequestDraftService.EditableFields("title", "body", null, null, List.of()), null), key).draftId();
    }
    private void seedExecution(String id, String mode, String state, long time) {
        jdbc.update("INSERT INTO agent_personal_workspace_execution (execution_id,tenant_id,client_id,owner_jiacn,execution_mode,execution_state,target_agent_id,created_at,update_time) VALUES (?,'0','client-a','owner-a',?,?,'agent',1,?)", id, mode, state, time);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertNoTaskWrites() { for (String table : List.of("agent_task_meta", "task_plan", "task_item", "agent_task_event")) assertEquals(0, count(table), table); }
    private <T> T proxy(Object target, Class<T> type) {
        TransactionInterceptor interceptor = new TransactionInterceptor(); interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target); factory.setInterfaces(type); factory.addAdvice(interceptor);
        return type.cast(factory.getProxy());
    }
    private static void mapper(Object dao, Object mapper) throws Exception { field(BaseDaoImpl.class, "baseMapper", dao, mapper); }
    private static void field(Class<?> type, String name, Object target, Object value) throws Exception { Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
    @SuppressWarnings("unchecked") private static <T> ObjectProvider<T> provider(T... values) {
        ObjectProvider<T> provider = mock(ObjectProvider.class); when(provider.getIfAvailable()).thenReturn(values.length == 0 ? null : values[0]); return provider;
    }
    private static void identity(HallRequestDraftService.OwnerScope scope) {
        EsContext context = new EsContext(); context.setJiacn(scope.ownerJiacn()); context.setClientId(scope.clientId()); EsContextHolder.setContext(context);
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .subject(scope.ownerJiacn()).claim("jiacn", scope.ownerJiacn()).claim("client_id", scope.clientId())
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build()));
    }
    private static void clearIdentity() { SecurityContextHolder.clearContext(); EsContextHolder.setContext(new EsContext()); }
    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource(); source.setDriverClassName("com.mysql.cj.jdbc.Driver"); source.setUrl(url); source.setUsername(user); source.setPassword(password); return source;
    }
    private static String databaseUrl(String url, String database) {
        int query = url.indexOf('?'); String suffix = query < 0 ? "" : url.substring(query); String root = query < 0 ? url : url.substring(0, query);
        int slash = root.indexOf('/', "jdbc:mysql://".length()); return (slash < 0 ? root + "/" : root.substring(0, slash+1)) + database + suffix;
    }
    private static String required(String name) {
        String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required"); return value;
    }
}
