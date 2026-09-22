package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.config.HallPrivateCaseSchemaInitializer;
import cn.jia.agent.config.HallPrivateMarkSchemaInitializer;
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
    private HallRequestDraftDao drafts;
    private HallReadService reads;
    private HallPrivateMarkService marks;
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
        new HallPrivateMarkSchemaInitializer(jdbc).afterPropertiesSet();
        new ResourceDatabasePopulator(new ClassPathResource("db/hall-b02-task-read-fixture.sql")).execute(source);
        identity(OWNER);
        MybatisConfiguration configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(HallRequestDraftMapper.class, HallPrivateCaseMapper.class,
                HallCaseExecutionMapper.class, HallReadMapper.class, HallPrivateMarkMapper.class,
                PersonalWorkspaceExecutionMapper.class, AgentTaskMetaMapper.class,
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
        drafts = spy(new HallRequestDraftDaoImpl(template.getMapper(HallRequestDraftMapper.class)));
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
        reads = new HallReadServiceImpl(new HallReadDaoImpl(readMapper, true), System::currentTimeMillis, true);
        PersonalWorkspaceExecutionDao executionDao = new PersonalWorkspaceExecutionDaoImpl(
                template.getMapper(PersonalWorkspaceExecutionMapper.class), mock(PersonalWorkspaceExecutionInputMapper.class),
                mock(PersonalWorkspaceExecutionOutputMapper.class));
        marks = proxy(new HallPrivateMarkServiceImpl(new HallPrivateMarkDaoImpl(template.getMapper(HallPrivateMarkMapper.class)),
                cases, executionDao, hall), HallPrivateMarkService.class);
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
        assertNull(jdbc.queryForObject("SELECT submitted_execution_id FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertNull(jdbc.queryForObject("SELECT case_id FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertEquals("body", jdbc.queryForObject("SELECT description FROM task_plan", String.class));
        assertNull(jdbc.queryForObject("SELECT amount FROM task_plan", java.math.BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("SELECT current_event_version FROM agent_task_meta", Integer.class));
        verifyNoInteractions(executions);
        var page = reads.items(OWNER, "task", "recent", "title", null);
        assertEquals("complete", page.section().status()); assertEquals(receipt.task().taskId(), page.section().partitions().get("task").items().getFirst().ref().sourceId());
    }

    @Test void unauthenticatedJwtRollsBackSubmitReservationWithoutAnyTaskSideEffect() {
        String draft = draft("unauthenticated");
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,
                assertThrows(HallRequestDraftService.Failure.class,
                        () -> hall.submit(OWNER, draft, 1, true, "unauthenticated-submit")).reason());
        assertNoTaskWrites();
        assertEquals("EDITING", jdbc.queryForObject("SELECT state FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertNull(jdbc.queryForObject("SELECT submit_key FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        verifyNoInteractions(broker, events, executions);
    }

    @Test void receiptSqlRejectsTaskExecutionAndNonTaskMissingExecutionBindings() {
        String draft = draft("receipt-shape");
        String hash = "a".repeat(64);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertEquals(1, drafts.reserveSubmitIntent("0", "client-a", "owner-a", draft,
                    1, "shape-key", hash, 1000));
            assertEquals(0, drafts.markSubmitted("0", "client-a", "owner-a", draft,
                    1, "shape-key", hash, null, "42", "unexpected-execution", 1000));
            assertEquals(1, jdbc.update("UPDATE hall_request_draft SET kind='CREATE' WHERE draft_id=?", draft));
            assertEquals(0, drafts.markSubmitted("0", "client-a", "owner-a", draft,
                    1, "shape-key", hash, null, "42", null, 1000));
            status.setRollbackOnly();
        });
        assertNoTaskWrites();
        assertEquals("EDITING", jdbc.queryForObject("SELECT state FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        assertNull(jdbc.queryForObject("SELECT submit_key FROM hall_request_draft WHERE draft_id=?", String.class, draft));
        verifyNoInteractions(broker, events, executions);
    }

    @Test void finalReceiptFailureRollsBackEveryTaskSideEffectAndSubmitKey() {
        String draft = draft("rollback"); failReceipt.set(true);
        assertEquals(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE,
                assertThrows(HallRequestDraftService.Failure.class,
                        () -> hall.submit(OWNER, draft, 1, true, "submit")).reason());
        verify(drafts).markSubmitted(eq("0"), eq("client-a"), eq("owner-a"), eq(draft),
                eq(1L), eq("submit"), anyString(), isNull(), anyString(), isNull(), anyLong());
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
        assertTrue(readMapper.page("1", "client-a", "owner-a", "draft", "recent", "", null, null, null, 21, true).isEmpty());
        assertTrue(readMapper.page("0", "client-a ", "owner-a", "draft", "recent", "", null, null, null, 21, true).isEmpty());
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
        assertEquals("complete", actions.status()); assertEquals("legacy", actions.items().getFirst().ref().sourceId());
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

    @Test void realPrivateMarkVerifiesFixedManifestArchivesUnarchivesAndNewResultsReappear() {
        seedExecution("marked", "PRIVATE", "QUEUED", 100L);
        assertEquals(0, marks.get(OWNER, "LEGACY_EXECUTION", "marked").revision());
        var archive = new HallPrivateMarkService.Command(0, true, null);
        var receipt = marks.mark(OWNER, "LEGACY_EXECUTION", "marked", archive, "archive-key");
        assertTrue(receipt.archived());
        assertEquals(receipt, marks.mark(OWNER, "LEGACY_EXECUTION", "marked", archive, "archive-key"));
        assertEquals(1, count("hall_private_mark"));
        assertEquals(1, reads.items(OWNER,"private","archive",null,null).section().partitions().get("private").items().size());
        assertTrue(reads.items(OWNER,"private","recent",null,null).section().partitions().get("private").items().isEmpty());
        jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='OUTPUT_COMMITTED',update_time=200 WHERE execution_id='marked'");
        seedResult("marked", "marked-file", "marked-out");
        assertFalse(marks.get(OWNER,"LEGACY_EXECUTION","marked").archived());
        var attention = reads.items(OWNER,"private","needsAction",null,null).section().partitions().get("private");
        assertEquals("complete", attention.status()); assertEquals(1, attention.items().size());
        var result = hall.getExecutionResults(OWNER,"marked");
        var viewed = new HallPrivateMarkService.ResultRef("marked",result.manifestId());
        assertThrows(HallRequestDraftService.Failure.class, () -> marks.mark(OWNER,"LEGACY_EXECUTION","marked",
                new HallPrivateMarkService.Command(1,false,new HallPrivateMarkService.ResultRef("marked","forged")),"bad"));
        assertEquals(1, count("hall_private_mark"));
        jdbc.update("UPDATE agent_personal_workspace_file SET state='DELETED' WHERE file_id='marked-file'");
        assertThrows(HallRequestDraftService.Failure.class, () -> marks.mark(OWNER,"LEGACY_EXECUTION","marked",
                new HallPrivateMarkService.Command(1,false,viewed),"hidden"));
        jdbc.update("UPDATE agent_personal_workspace_file SET state='ACTIVE' WHERE file_id='marked-file'");
        var seen = marks.mark(OWNER,"LEGACY_EXECUTION","marked",new HallPrivateMarkService.Command(1,false,viewed),"seen");
        assertEquals(2,seen.revision()); assertEquals(viewed,seen.viewedResultRef());
        assertTrue(reads.items(OWNER,"private","needsAction",null,null).section().partitions().get("private").items().isEmpty());
        marks.mark(OWNER,"LEGACY_EXECUTION","marked",new HallPrivateMarkService.Command(2,true,null),"archive-again");
        marks.mark(OWNER,"LEGACY_EXECUTION","marked",new HallPrivateMarkService.Command(3,false,null),"unarchive");
        assertEquals(viewed,marks.get(OWNER,"LEGACY_EXECUTION","marked").viewedResultRef());
        assertEquals("OUTPUT_COMMITTED",jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution WHERE execution_id='marked'",String.class));
        verifyNoInteractions(executions);
    }

    @Test void privateCaseNewExecutionInvalidatesArchiveWithoutRewritingOldSeenManifest() {
        seedExecution("v1", "PRIVATE", "OUTPUT_COMMITTED", 100L);
        seedResult("v1", "file-v1", "out-v1");
        jdbc.update("INSERT INTO hall_private_case (case_id,tenant_id,client_id,owner_jiacn,title,origin_ref,revision,created_at,updated_at) VALUES ('case-mark','0','client-a','owner-a','case title','test',1,1,100)");
        jdbc.update("INSERT INTO hall_case_execution (tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,created_at) VALUES ('0','client-a','owner-a','case-mark','v1',1,100)");
        var viewed = new HallPrivateMarkService.ResultRef("v1", hall.getExecutionResults(OWNER,"v1").manifestId());
        marks.mark(OWNER,"PRIVATE_CASE","case-mark",new HallPrivateMarkService.Command(0,true,viewed),"case-archive");
        assertEquals(1, reads.items(OWNER,"private","archive",null,null).section().partitions().get("private").items().size());
        // Fixture records an already authorized revision; no Provider or historical run is replayed.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            seedExecution("v2", "PRIVATE", "OUTPUT_COMMITTED", 200L);
            seedResult("v2", "file-v2", "out-v2");
            jdbc.update("INSERT INTO hall_case_execution (tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,parent_execution_id,source_output_ref_json,created_at) VALUES ('0','client-a','owner-a','case-mark','v2',2,'v1',?,200)",
                    "{\"executionId\":\"v1\",\"outputId\":\"out-v1\",\"fileId\":\"file-v1\",\"fileVersion\":1}");
            jdbc.update("UPDATE hall_private_case SET revision=2,updated_at=200 WHERE case_id='case-mark'");
        });
        var current = marks.get(OWNER,"PRIVATE_CASE","case-mark");
        assertFalse(current.archived()); assertEquals(viewed,current.viewedResultRef()); assertEquals(1,current.revision());
        var attention = reads.items(OWNER,"private","needsAction",null,null).section().partitions().get("private");
        assertEquals("complete",attention.status()); assertEquals(1,attention.items().size());
        assertEquals("case-mark",attention.items().getFirst().ref().sourceId());
        assertTrue(reads.items(OWNER,"private","archive",null,null).section().partitions().get("private").items().isEmpty());
        assertEquals(viewed.manifestId(),hall.getExecutionResults(OWNER,"v1").manifestId());
        var viewedV2 = new HallPrivateMarkService.ResultRef("v2",hall.getExecutionResults(OWNER,"v2").manifestId());
        marks.mark(OWNER,"PRIVATE_CASE","case-mark",new HallPrivateMarkService.Command(1,false,viewedV2),"case-seen-v2");
        assertTrue(reads.items(OWNER,"private","needsAction",null,null).section().partitions().get("private").items().isEmpty());
        assertEquals(2,count("hall_case_execution")); verifyNoInteractions(executions);
    }

    @Test void markConcurrentCasDuplicateKeyRollbackAndForeignScopeNeverChangeExecution() throws Exception {
        seedExecution("mark-race","PRIVATE","FAILED",100L);
        var command=new HallPrivateMarkService.Command(0,true,null);
        CountDownLatch start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<HallPrivateMarkService.View> call=()->{start.await();return marks.mark(OWNER,"LEGACY_EXECUTION","mark-race",command,"same");};
            var a=pool.submit(call);var b=pool.submit(call);start.countDown();assertEquals(a.get(),b.get());
        }
        assertEquals(1,count("hall_private_mark"));
        seedExecution("other-subject","PRIVATE","FAILED",100L);
        assertEquals(HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT,assertThrows(HallRequestDraftService.Failure.class,
                ()->marks.mark(OWNER,"LEGACY_EXECUTION","other-subject",command,"same")).reason());
        assertEquals(1,count("hall_private_mark"));
        assertEquals(HallRequestDraftService.Reason.REVISION_CHANGED,assertThrows(HallRequestDraftService.Failure.class,
                ()->marks.mark(OWNER,"LEGACY_EXECUTION","mark-race",command,"different")).reason());
        new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            marks.mark(OWNER,"LEGACY_EXECUTION","mark-race",new HallPrivateMarkService.Command(1,false,null),"rollback");status.setRollbackOnly();});
        assertEquals(1,count("hall_private_mark"));
        for(var other:List.of(new HallRequestDraftService.OwnerScope("0","client-b","owner-a"),new HallRequestDraftService.OwnerScope("0","client-a","owner-b"))) {
            assertEquals(HallRequestDraftService.Reason.NOT_FOUND,assertThrows(HallRequestDraftService.Failure.class,
                    ()->marks.mark(other,"LEGACY_EXECUTION","mark-race",command,"same")).reason());
        }
        assertEquals(HallRequestDraftService.Reason.SOURCE_UNAVAILABLE,assertThrows(HallRequestDraftService.Failure.class,
                ()->marks.mark(OWNER,"TASK","mark-race",command,"task")).reason());
        seedExecution("task-run","TASK","FAILED",100L);
        assertEquals(HallRequestDraftService.Reason.NOT_FOUND,assertThrows(HallRequestDraftService.Failure.class,
                ()->marks.mark(OWNER,"LEGACY_EXECUTION","task-run",command,"task-run")).reason());
        assertEquals("FAILED",jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution WHERE execution_id='mark-race'",String.class));
    }

    @Test void formalReviewProjectionRequiresOwnerWorkItemManifestAndReleasedLease() {
        String taskId=hall.submit(OWNER,draft("review"),1,true,"review-submit").task().taskId();
        jdbc.update("UPDATE agent_task_meta SET reward_status='reviewing' WHERE task_id=?",taskId);
        jdbc.update("INSERT INTO agent_task_work_item VALUES ('work',?,'0','client-a','owner-a','submitted','manifest',NULL,NULL,2)",taskId);
        jdbc.update("INSERT INTO agent_task_formal_delivery (task_id,work_item_id,tenant_id,client_id,delivery_id,revision,version,state,manifest_artifact_id,manifest_artifact_version,submitted_at) VALUES (?,'work','0','client-a','delivery',1,0,'submitted','manifest',1,1000)",taskId);
        jdbc.update("INSERT INTO agent_task_formal_delivery_item VALUES ('0','client-a','delivery','artifact',1)");
        var page=reads.items(OWNER,"task","needsAction",null,null).section().partitions().get("task");
        assertEquals("complete",page.status());assertEquals(1,page.items().size());
        assertEquals("reviewing",page.items().getFirst().status().code());
        assertEquals("FORMAL_DELIVERY_SUBMITTED",page.items().getFirst().review().code());
        assertEquals("OPEN_TASK",page.items().getFirst().nextAction());
        jdbc.update("UPDATE agent_task_work_item SET owner_jiacn='foreign' WHERE work_item_id='work'");
        assertEquals("error",reads.items(OWNER,"task","needsAction",null,null).section().status());
        jdbc.update("UPDATE agent_task_work_item SET owner_jiacn='owner-a',lease_token='active' WHERE work_item_id='work'");
        assertEquals("error",reads.items(OWNER,"task","needsAction",null,null).section().status());
        jdbc.update("UPDATE agent_task_work_item SET lease_token=NULL,result_artifact_id='wrong' WHERE work_item_id='work'");
        assertEquals("error",reads.items(OWNER,"task","needsAction",null,null).section().status());
        assertEquals("submitted",jdbc.queryForObject("SELECT state FROM agent_task_formal_delivery",String.class));
        assertEquals(0,count("hall_private_mark"));
    }

    @Test void realMarkSchemaRevalidationRejectsMissingReceiptUniquenessAndUnenforcedChecks() {
        new HallPrivateMarkSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("ALTER TABLE hall_private_mark ALTER CHECK chk_hall_private_mark_type NOT ENFORCED");
        assertThrows(IllegalStateException.class,()->new HallPrivateMarkSchemaInitializer(jdbc).afterPropertiesSet());
        jdbc.execute("ALTER TABLE hall_private_mark ALTER CHECK chk_hall_private_mark_type ENFORCED");
        jdbc.execute("ALTER TABLE hall_private_mark DROP INDEX uk_hall_private_mark_key");
        assertThrows(IllegalStateException.class,()->new HallPrivateMarkSchemaInitializer(jdbc).afterPropertiesSet());
        assertEquals(0,count("hall_private_mark"));
    }

    private void seedResult(String execution,String file,String output) {
        String mime=PersonalWorkspaceExecutionProperties.DOCX;
        jdbc.update("INSERT INTO agent_personal_workspace_file VALUES (?,'0','client-a','owner-a','ACTIVE')",file);
        jdbc.update("INSERT INTO agent_personal_workspace_file_version VALUES (?,1,'0','client-a','owner-a','result.docx',?,123,?)",file,mime,"a".repeat(64));
        jdbc.update("INSERT INTO agent_personal_workspace_execution_output VALUES (?,?,'0','client-a','owner-a',?,1,'COMMITTED','PENDING','result.docx',?,123,?)",output,execution,file,mime,"a".repeat(64));
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
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .subject(scope.ownerJiacn()).claim("jiacn", scope.ownerJiacn()).claim("client_id", scope.clientId())
                .issuedAt(Instant.ofEpochSecond(1)).expiresAt(Instant.ofEpochSecond(2)).build());
        authentication.setAuthenticated(true); // applies independently in each concurrent submit thread
        SecurityContextHolder.getContext().setAuthentication(authentication);
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
