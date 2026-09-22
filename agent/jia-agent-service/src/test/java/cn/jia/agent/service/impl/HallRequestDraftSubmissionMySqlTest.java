package cn.jia.agent.service.impl;

import cn.jia.agent.config.HallPrivateCaseSchemaInitializer;
import cn.jia.agent.config.HallRequestDraftSchemaInitializer;
import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallRequestDraftDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.impl.HallPrivateCaseDaoImpl;
import cn.jia.agent.dao.impl.HallRequestDraftDaoImpl;
import cn.jia.agent.entity.AgentRuntimeDTO;
import cn.jia.agent.mapper.HallCaseExecutionMapper;
import cn.jia.agent.mapper.HallPrivateCaseMapper;
import cn.jia.agent.mapper.HallRequestDraftMapper;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Real isolated production-parity MySQL 8.0.21 evidence for Hall CHECK catalog, unique-key races,
 * CAS and rollback. The exact version is an evidence target for the deployed engine, not an
 * application runtime rejection of otherwise supported MySQL versions.
 * The fixture creates and drops only a task-prefixed temporary database acknowledged by the caller.
 */
@EnabledIfEnvironmentVariable(named = "JYT_UX_B01B_MYSQL_URL", matches = ".+")
class HallRequestDraftSubmissionMySqlTest {
    private static final HallRequestDraftService.OwnerScope OWNER =
            new HallRequestDraftService.OwnerScope("0", "client-a", "owner-a");
    private static final HallRequestDraftService.OwnerScope OTHER_OWNER =
            new HallRequestDraftService.OwnerScope("0", "client-a", "owner-b");
    private static final HallRequestDraftService.OwnerScope OTHER_CLIENT =
            new HallRequestDraftService.OwnerScope("0", "client-b", "owner-a");
    private static final String MIME = PersonalWorkspaceExecutionProperties.DOCX;

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String namespace;
    private HallRequestDraftService service;
    private HallRequestDraftDao drafts;
    private AtomicBoolean failExecution;
    private AtomicInteger executionSequence;
    private CountDownLatch raceGate;

    @BeforeEach
    void setUp() throws Exception {
        String baseUrl = required("JYT_UX_B01B_MYSQL_URL");
        String user = required("JYT_UX_B01B_MYSQL_USER");
        String password = System.getenv("JYT_UX_B01B_MYSQL_PASSWORD");
        String prefix = required("JYT_UX_B01B_MYSQL_DATABASE_PREFIX");
        if (password == null || !baseUrl.startsWith("jdbc:mysql://")
                || !prefix.matches("[A-Za-z0-9_]{1,20}")
                || !"true".equals(required("JYT_UX_B01B_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("B01B isolated MySQL acknowledgement/credentials required");
        }
        namespace = prefix + "_jyt_b01b_";
        database = namespace + UUID.randomUUID().toString().substring(0, 8);
        admin = new JdbcTemplate(dataSource(baseUrl, user, password));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"),
                "B01B fixture requires MySQL 8.0.21, got " + version);
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");

        DataSource source = dataSource(databaseUrl(baseUrl, database), user, password);
        jdbc = new JdbcTemplate(source);
        new HallRequestDraftSchemaInitializer(jdbc).afterPropertiesSet();
        new HallPrivateCaseSchemaInitializer(jdbc).afterPropertiesSet();
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution (
                  execution_id VARCHAR(100) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL, execution_mode VARCHAR(16) NOT NULL,
                  execution_state VARCHAR(32) NOT NULL, task_id VARCHAR(100) NOT NULL,
                  run_id VARCHAR(100) NOT NULL,
                  PRIMARY KEY (execution_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_execution_output (
                  output_id VARCHAR(100) NOT NULL, execution_id VARCHAR(100) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL, workspace_file_id VARCHAR(100) NOT NULL,
                  workspace_file_version INT NOT NULL, output_state VARCHAR(32) NOT NULL,
                  publication_state VARCHAR(32) NOT NULL, original_filename VARCHAR(255) NOT NULL,
                  content_mime_type VARCHAR(160) NOT NULL, byte_length BIGINT NOT NULL,
                  content_hash CHAR(64) NOT NULL,
                  PRIMARY KEY (output_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_file_version (
                  file_id VARCHAR(100) NOT NULL, version INT NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL, original_filename VARCHAR(255) NOT NULL,
                  content_mime_type VARCHAR(160) NOT NULL, byte_length BIGINT NOT NULL,
                  content_hash CHAR(64) NOT NULL,
                  PRIMARY KEY (file_id,version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_personal_workspace_file (
                  file_id VARCHAR(100) NOT NULL, tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL, owner_jiacn VARCHAR(50) NOT NULL,
                  state VARCHAR(16) NOT NULL,
                  PRIMARY KEY (file_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE fixture_execution (
                  execution_id VARCHAR(100) NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,
                  request_key VARCHAR(100) NOT NULL,
                  PRIMARY KEY (execution_id),
                  UNIQUE KEY uk_fixture_execution_key
                    (tenant_id,client_id,owner_jiacn,request_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(HallRequestDraftMapper.class);
        configuration.addMapper(HallPrivateCaseMapper.class);
        configuration.addMapper(HallCaseExecutionMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(global);
        SqlSessionFactory factory = factoryBean.getObject();
        assertNotNull(factory);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        drafts = spy(new HallRequestDraftDaoImpl(
                template.getMapper(HallRequestDraftMapper.class)));
        HallPrivateCaseDao cases = new HallPrivateCaseDaoImpl(
                template.getMapper(HallPrivateCaseMapper.class),
                template.getMapper(HallCaseExecutionMapper.class));

        AgentService agents = mock(AgentService.class);
        when(agents.requireApiKeyOwnedAgent(any(), any(), any())).thenAnswer(invocation -> {
            AgentRuntimeDTO agent = new AgentRuntimeDTO();
            agent.setAgentId(invocation.getArgument(2));
            agent.setOwnerJiacn(invocation.getArgument(1));
            return agent;
        });
        PersonalWorkspaceExecutionService executions = mock(PersonalWorkspaceExecutionService.class);
        failExecution = new AtomicBoolean(false);
        executionSequence = new AtomicInteger();
        when(executions.create(any(), any(), any())).thenAnswer(invocation -> {
            PersonalWorkspaceExecutionService.OwnerScope scope = invocation.getArgument(0);
            String key = invocation.getArgument(2);
            PersonalWorkspaceExecutionService.CreateCommand command = invocation.getArgument(1);
            if (key.startsWith("legacy-submit")) {
                assertEquals(List.of(new PersonalWorkspaceExecutionService.InputSelection(
                        "legacy-file", 1)), command.inputs());
                assertEquals(null, command.sourceOutputRef(),
                        "private revision must not forge formal-delivery rework authority");
            }
            String executionId = "pwe_fixture_" + executionSequence.incrementAndGet();
            jdbc.update("INSERT INTO fixture_execution "
                            + "(execution_id,tenant_id,client_id,owner_jiacn,request_key) "
                            + "VALUES (?,?,?,?,?)",
                    executionId, scope.tenantId(), scope.clientId(), scope.ownerJiacn(), key);
            if (failExecution.get()) throw new IllegalStateException("forced execution failure");
            return execution(executionId);
        });
        when(executions.get(any(), any())).thenAnswer(invocation -> {
            PersonalWorkspaceExecutionService.OwnerScope scope = invocation.getArgument(0);
            String executionId = invocation.getArgument(1);
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fixture_execution "
                            + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND execution_id=?",
                    Integer.class, scope.tenantId(), scope.clientId(), scope.ownerJiacn(), executionId);
            if (count == null || count == 0) {
                count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_personal_workspace_execution "
                                + "WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND execution_id=? "
                                + "AND execution_mode='PRIVATE'",
                        Integer.class, scope.tenantId(), scope.clientId(), scope.ownerJiacn(), executionId);
            }
            if (count == null || count != 1) {
                throw new PersonalWorkspaceExecutionService.Failure(
                        PersonalWorkspaceExecutionService.Reason.NOT_FOUND);
            }
            return execution(executionId);
        });

        HallRequestDraftServiceImpl raw = new HallRequestDraftServiceImpl(drafts, cases, executions,
                mock(PersonalWorkspaceDao.class), mock(AgentTaskMetaDao.class), agents,
                mock(WorkspaceConversationAccessService.class),
                new PersonalWorkspaceExecutionProperties(List.of(MIME)),
                () -> 1_790_000_000_000L + executionSequence.get());
        service = transactionalProxy(raw, new DataSourceTransactionManager(source));
    }

    @AfterEach
    void tearDown() {
        if (admin == null || database == null) return;
        if (!database.startsWith(namespace)) throw new IllegalStateException("Unowned fixture database");
        admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
    }

    @Test
    void draftListEmptyFirstPageExecutesRealMapperWithoutWritingOrExecuting() {
        HallRequestDraftService.DraftPage page = service.list(OWNER, null);
        assertEquals(List.of(), page.items());
        assertNull(page.nextCursor());
        assertEquals(0, count("hall_request_draft"));
        assertEquals(0, count("fixture_execution"));
        assertEquals(0, executionSequence.get());
    }

    @Test
    void draftListFirstPageReturnsOnlyEditingRowsInStableBinaryOrder() {
        String left = createDraft(OWNER, "list-first-left");
        String right = createDraft(OWNER, "list-first-right");
        String discarded = createDraft(OWNER, "list-first-discarded");
        service.discard(OWNER, discarded, 1, "list-discard");

        HallRequestDraftService.DraftPage page = service.list(OWNER, null);
        assertEquals(List.of(left, right).stream().sorted(Comparator.reverseOrder()).toList(),
                draftIds(page));
        assertTrue(page.items().stream().allMatch(row -> row.state().equals("EDITING")));
        assertNull(page.nextCursor());
        assertEquals(3, count("hall_request_draft"), "listing must not rewrite recovery rows");
        assertEquals(0, executionSequence.get());
    }

    @Test
    void draftListCursorTraversesTimestampTiesAndOlderRowsWithoutDuplicates() {
        List<String> tied = new ArrayList<>();
        // Existing public draft page size is20; 21 tied rows cross that boundary.
        for (int index = 0; index < 21; index++) {
            tied.add(createDraft(OWNER, "list-cursor-" + index));
        }
        // All writes are confined to this fixture's task-prefixed temporary database.
        assertEquals(21, jdbc.update("UPDATE hall_request_draft SET updated_at=?",
                1_790_000_000_001L));
        String older = createDraft(OWNER, "list-cursor-older");
        List<String> expected = new ArrayList<>(tied.stream()
                .sorted(Comparator.reverseOrder()).toList());
        expected.add(older);

        HallRequestDraftService.DraftPage first = service.list(OWNER, null);
        assertEquals(expected.subList(0, 20), draftIds(first));
        assertNotNull(first.nextCursor());
        HallRequestDraftService.DraftPage second = service.list(OWNER, first.nextCursor());
        assertEquals(expected.subList(20, 22), draftIds(second),
                "both same-timestamp binary ID seek and older timestamp seek must execute");
        assertNull(second.nextCursor());
        List<String> all = new ArrayList<>(draftIds(first));
        all.addAll(draftIds(second));
        assertEquals(22L, all.stream().distinct().count());
        assertEquals(expected, all);
        assertEquals(22, count("hall_request_draft"));
        assertEquals(0, executionSequence.get());
    }

    @Test
    void draftListScopesOwnerClientAndCaseDistinctIdentityWithoutCrossScopeRows() {
        HallRequestDraftService.OwnerScope caseDistinct =
                new HallRequestDraftService.OwnerScope("0", "client-a", "Owner-a");
        List<HallRequestDraftService.OwnerScope> scopes =
                List.of(OWNER, OTHER_OWNER, OTHER_CLIENT, caseDistinct);
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < scopes.size(); index++) {
            ids.add(createDraft(scopes.get(index), "list-scope-" + index));
        }
        for (int index = 0; index < scopes.size(); index++) {
            HallRequestDraftService.DraftPage page = service.list(scopes.get(index), null);
            assertEquals(List.of(ids.get(index)), draftIds(page));
            assertNull(page.nextCursor());
        }
        assertTrue(service.list(new HallRequestDraftService.OwnerScope(
                "0", "client-a", "unrelated-owner"), null).items().isEmpty());
        assertEquals(4, count("hall_request_draft"));
        assertEquals(0, executionSequence.get());
    }

    private static List<String> draftIds(HallRequestDraftService.DraftPage page) {
        return page.items().stream().map(HallRequestDraftService.DraftSummary::draftId).toList();
    }

    @Test
    void initializerIsRepeatableNormalizesChecksAndRejectsInvalidRows() {
        List<String> before = definitions();
        new HallPrivateCaseSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(before, definitions());
        assertTrue(checks("hall_case_execution").stream()
                .anyMatch(check -> check.contains("revision_no") && check.contains("parent_execution_id")));

        DataAccessException revision = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO hall_private_case
                  (case_id,tenant_id,client_id,owner_jiacn,title,origin_ref,revision,created_at,updated_at)
                VALUES ('bad','0','client-a','owner-a','x','map',0,1,1)
                """));
        assertTrue(revision.getMostSpecificCause().getMessage().contains("chk_hall_case_revision"));
        DataAccessException lineage = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO hall_case_execution
                  (tenant_id,client_id,owner_jiacn,case_id,execution_id,revision_no,
                   parent_execution_id,source_output_ref_json,created_at)
                VALUES ('0','client-a','owner-a','case','execution',2,NULL,NULL,1)
                """));
        assertTrue(lineage.getMostSpecificCause().getMessage().contains(
                "chk_hall_case_execution_lineage"));
    }

    @Test
    void partialCatalogFailsClosedWithoutRepair() {
        jdbc.execute("DROP TABLE hall_case_execution");
        List<String> before = jdbc.queryForList("SHOW TABLES", String.class);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new HallPrivateCaseSchemaInitializer(jdbc).afterPropertiesSet());
        assertTrue(failure.getMessage().contains("partial"));
        assertEquals(before, jdbc.queryForList("SHOW TABLES", String.class));
    }

    @Test
    void concurrentDifferentDraftsWithOneSubmitKeyCreateExactlyOneExecution() throws Exception {
        String left = createDraft(OWNER, "create-left");
        String right = createDraft(OWNER, "create-right");
        // Force both transactions past the absent-key lookup, retaining the real mapper SQL.
        // The loser must fail before invoking execution creation, not merely roll it back later.
        CountDownLatch reserving = new CountDownLatch(2);
        raceGate = reserving;
        doAnswer(invocation -> {
            reserving.countDown();
            reserving.await();
            return invocation.callRealMethod();
        }).when(drafts).reserveSubmitIntent(eq("0"), eq("client-a"), eq("owner-a"),
                any(), eq(1L), eq("shared-submit"), any(), anyLong());
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = concurrently(start,
                () -> submitResult(left, "shared-submit"),
                () -> submitResult(right, "shared-submit"));
        assertEquals(1, results.stream().filter(
                HallRequestDraftService.SubmissionReceipt.class::isInstance).count());
        assertEquals(1, results.stream().filter(HallRequestDraftService.Failure.class::isInstance)
                .map(HallRequestDraftService.Failure.class::cast)
                .filter(failure -> failure.reason()
                        == HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT).count(),
                () -> "submit result types/reasons: " + resultKinds(results));
        assertEquals(1, executionSequence.get(), "loser must not call execution creation");
        assertEquals(1, count("fixture_execution"));
        assertEquals(1, count("hall_private_case"));
        assertEquals(1, count("hall_case_execution"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hall_request_draft "
                + "WHERE state='EDITING' AND revision=1 AND submit_key IS NULL "
                + "AND submit_hash IS NULL AND submission_ref IS NULL", Integer.class));
    }

    @Test
    void sameDraftConcurrentCasCreatesOneExecutionAndSameKeyReplaysReceipt() throws Exception {
        String draft = createDraft(OWNER, "create-same");
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = concurrently(start,
                () -> submitResult(draft, "same-submit"),
                () -> submitResult(draft, "same-submit"));
        assertEquals(2, results.stream().filter(
                HallRequestDraftService.SubmissionReceipt.class::isInstance).count());
        HallRequestDraftService.SubmissionReceipt first =
                (HallRequestDraftService.SubmissionReceipt) results.get(0);
        HallRequestDraftService.SubmissionReceipt second =
                (HallRequestDraftService.SubmissionReceipt) results.get(1);
        assertEquals(first, second);
        assertEquals(1, count("fixture_execution"));
        assertEquals(1, count("hall_private_case"));
        assertEquals(1, count("hall_case_execution"));
        assertEquals(2L, jdbc.queryForObject("SELECT revision FROM hall_request_draft "
                + "WHERE draft_id=?", Long.class, draft));
    }

    @Test
    void differentKeyCasLosesWithoutSecondExecution() throws Exception {
        String draft = createDraft(OWNER, "create-cas");
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = concurrently(start,
                () -> submitResult(draft, "submit-left"),
                () -> submitResult(draft, "submit-right"));
        assertEquals(1, results.stream().filter(
                HallRequestDraftService.SubmissionReceipt.class::isInstance).count());
        assertEquals(1, results.stream().filter(HallRequestDraftService.Failure.class::isInstance)
                .map(HallRequestDraftService.Failure.class::cast)
                .filter(failure -> failure.reason()
                        == HallRequestDraftService.Reason.IDEMPOTENCY_CONFLICT).count());
        assertEquals(1, count("fixture_execution"));
    }

    @Test
    void concurrentLegacyRevisionCreatesOneLazyCaseAndOneImmutableParentBinding() throws Exception {
        seedLegacyOutput();
        String left = createRevisionDraft("legacy-left");
        String right = createRevisionDraft("legacy-right");
        // Both sessions must have read an absent binding before either locks the source.
        // In particular, do not globally change the fixture's default MyBatis SESSION cache.
        CountDownLatch lockingLegacyOutput = new CountDownLatch(2);
        raceGate = lockingLegacyOutput;
        doAnswer(invocation -> {
            lockingLegacyOutput.countDown();
            lockingLegacyOutput.await();
            return invocation.callRealMethod();
        }).when(drafts).lockPrivateCommittedOutputExists("0", "client-a", "owner-a",
                "legacy-execution", "legacy-output", "legacy-file", 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Object> results = concurrently(start,
                () -> submitResult(left, "legacy-submit-left"),
                () -> submitResult(right, "legacy-submit-right"));
        long receipts = results.stream().filter(
                HallRequestDraftService.SubmissionReceipt.class::isInstance).count();
        long conflicts = results.stream().filter(HallRequestDraftService.Failure.class::isInstance)
                .map(HallRequestDraftService.Failure.class::cast)
                .filter(failure -> failure.reason()
                        == HallRequestDraftService.Reason.EXECUTION_CONFLICT).count();
        assertEquals(1, receipts, "both contenders observed the missing legacy binding");
        assertEquals(1, conflicts, () -> "legacy result types/reasons: " + resultKinds(results));
        assertEquals(receipts, executionSequence.get(), "loser must not create an execution");
        assertEquals(1, count("hall_private_case"));
        assertEquals(1 + receipts, count("hall_case_execution"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hall_case_execution "
                + "WHERE execution_id='legacy-execution' AND revision_no=1 "
                + "AND parent_execution_id IS NULL", Integer.class));
        assertEquals(receipts, count("fixture_execution"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM hall_request_draft "
                + "WHERE state='EDITING' AND revision=1 AND submit_key IS NULL "
                + "AND submit_hash IS NULL", Integer.class));

        // A user retry of the rolled-back contender reuses the committed parent/case, not
        // the old Provider execution. The failed submit key was not durably consumed.
        String retryDraft = results.get(0) instanceof HallRequestDraftService.Failure ? left : right;
        String retryKey = retryDraft.equals(left) ? "legacy-submit-left" : "legacy-submit-right";
        HallRequestDraftService.SubmissionReceipt retried =
                service.submit(OWNER, retryDraft, 1, true, retryKey);
        HallRequestDraftService.SubmissionReceipt winner = results.stream()
                .filter(HallRequestDraftService.SubmissionReceipt.class::isInstance)
                .map(HallRequestDraftService.SubmissionReceipt.class::cast).findFirst().orElseThrow();
        assertEquals(winner.ref(), retried.ref());
        assertEquals(2, executionSequence.get());
        assertEquals(2, count("fixture_execution"));
        assertEquals(1, count("hall_private_case"));
        assertEquals(3, count("hall_case_execution"));
        assertEquals(3L, jdbc.queryForObject("SELECT revision FROM hall_private_case", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM hall_case_execution "
                + "WHERE parent_execution_id='legacy-execution' AND revision_no IN (2,3)", Integer.class));
        assertEquals(retried, service.submit(OWNER, retryDraft, 1, true, retryKey));
        assertEquals(2, executionSequence.get(), "receipt replay must not create another execution");
    }

    @Test
    void executionFailureRollsBackCaseLineageExecutionAndSubmitReservation() {
        String draft = createDraft(OWNER, "create-rollback");
        failExecution.set(true);
        HallRequestDraftService.Failure failure = assertThrows(
                HallRequestDraftService.Failure.class,
                () -> service.submit(OWNER, draft, 1, true, "rollback-key"));
        assertEquals(HallRequestDraftService.Reason.STORAGE_UNAVAILABLE, failure.reason());
        assertEquals(0, count("fixture_execution"));
        assertEquals(0, count("hall_private_case"));
        assertEquals(0, count("hall_case_execution"));
        assertEquals("EDITING", jdbc.queryForObject("SELECT state FROM hall_request_draft "
                + "WHERE draft_id=?", String.class, draft));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM hall_request_draft "
                + "WHERE draft_id=? AND submit_key IS NOT NULL", Integer.class, draft));
    }

    @Test
    void ownerAndClientScopeHideDraftSubmissionAndCase() {
        String draft = createDraft(OWNER, "create-owner");
        HallRequestDraftService.SubmissionReceipt receipt =
                service.submit(OWNER, draft, 1, true, "owner-submit");
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.get(OTHER_OWNER, draft));
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getSubmissionByIdempotencyKey(OTHER_OWNER, "owner-submit"));
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getCase(OTHER_OWNER, receipt.ref().sourceId()));
    }

    @Test
    void legacyCommittedResultIsReadableBeforeCaseAndRemainsPinnedAfterLazyRevision() {
        seedLegacyOutput();
        seedTaskOutput();
        HallRequestDraftService.ExecutionResultsView before =
                service.getExecutionResults(OWNER, "legacy-execution");
        assertEquals("OUTPUT_COMMITTED", before.state());
        assertEquals("legacy-file", before.items().getFirst().fileId());
        assertEquals(1, before.items().getFirst().fileVersion());
        assertEquals("AVAILABLE", before.items().getFirst().availability());
        assertTrue(before.manifestId().startsWith("pwe_m_"));
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getExecutionResults(OTHER_OWNER, "legacy-execution"));
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getExecutionResults(OTHER_CLIENT, "legacy-execution"));
        assertFailure(HallRequestDraftService.Reason.NOT_FOUND,
                () -> service.getExecutionResults(OWNER, "task-execution"));

        String draft = createRevisionDraft("legacy-results-revision");
        HallRequestDraftService.SubmissionReceipt receipt =
                service.submit(OWNER, draft, 1, true, "legacy-results-submit");
        HallRequestDraftService.CaseView privateCase = service.getCase(OWNER, receipt.ref().sourceId());
        assertEquals(2, privateCase.executions().size());
        assertEquals("legacy-execution", privateCase.executions().get(1).parentExecutionId());
        assertEquals("legacy-file",
                privateCase.executions().get(1).sourceOutputRef().fileId());
        assertEquals(before, service.getExecutionResults(OWNER, "legacy-execution"),
                "lazy association and a new run must not rewrite the old fixed result");
    }

    private String createRevisionDraft(String key) {
        HallRequestDraftService.EditableFields fields = new HallRequestDraftService.EditableFields(
                "revision", "revise this result", "agent-a", MIME, List.of());
        HallRequestDraftService.SourceOutputRef source =
                new HallRequestDraftService.SourceOutputRef(
                        "legacy-execution", "legacy-output", "legacy-file", 1);
        return service.create(OWNER, new HallRequestDraftService.CreateCommand(
                "REVISION", "legacy-result",
                new HallRequestDraftService.SourceRef(
                        "EXECUTION_OUTPUT", "legacy-execution", null),
                null, null, null, fields, source), key).draftId();
    }

    private void seedLegacyOutput() {
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES (?,?,?,?,?,?,?,?)",
                "legacy-execution", "0", "client-a", "owner-a",
                "PRIVATE", "OUTPUT_COMMITTED", "legacy-task", "legacy-run");
        jdbc.update("INSERT INTO agent_personal_workspace_file VALUES (?,?,?,?,?)",
                "legacy-file", "0", "client-a", "owner-a", "ACTIVE");
        jdbc.update("INSERT INTO agent_personal_workspace_file_version VALUES (?,?,?,?,?,?,?,?,?)",
                "legacy-file", 1, "0", "client-a", "owner-a", "legacy.docx", MIME,
                123L, "a".repeat(64));
        jdbc.update("INSERT INTO agent_personal_workspace_execution_output VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "legacy-output", "legacy-execution", "0", "client-a", "owner-a",
                "legacy-file", 1, "COMMITTED", "PENDING", "legacy.docx", MIME,
                123L, "a".repeat(64));
    }

    private void seedTaskOutput() {
        jdbc.update("INSERT INTO agent_personal_workspace_execution VALUES (?,?,?,?,?,?,?,?)",
                "task-execution", "0", "client-a", "owner-a",
                "TASK", "OUTPUT_COMMITTED", "formal-task", "formal-run");
        jdbc.update("INSERT INTO agent_personal_workspace_file VALUES (?,?,?,?,?)",
                "task-file", "0", "client-a", "owner-a", "ACTIVE");
        jdbc.update("INSERT INTO agent_personal_workspace_file_version VALUES (?,?,?,?,?,?,?,?,?)",
                "task-file", 1, "0", "client-a", "owner-a", "formal.docx", MIME,
                321L, "b".repeat(64));
        jdbc.update("INSERT INTO agent_personal_workspace_execution_output VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                "task-output", "task-execution", "0", "client-a", "owner-a",
                "task-file", 1, "COMMITTED", "PUBLISHED", "formal.docx", MIME,
                321L, "b".repeat(64));
    }

    private String createDraft(HallRequestDraftService.OwnerScope scope, String key) {
        HallRequestDraftService.EditableFields fields = new HallRequestDraftService.EditableFields(
                "title", "instruction", "agent-a", MIME, List.of());
        return service.create(scope, new HallRequestDraftService.CreateCommand(
                "CREATE", "map", null, null, null, null, fields, null), key).draftId();
    }

    private Object submitResult(String draft, String key) {
        try {
            return service.submit(OWNER, draft, 1, true, key);
        } catch (HallRequestDraftService.Failure failure) {
            return failure;
        }
    }

    private List<Object> concurrently(CountDownLatch start,
            ThrowingSupplier left, ThrowingSupplier right) throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Object> first = pool.submit(() -> concurrentCall(start, left));
            Future<Object> second = pool.submit(() -> concurrentCall(start, right));
            start.countDown();
            List<Object> values = new ArrayList<>();
            values.add(first.get());
            values.add(second.get());
            return values;
        }
    }

    private Object concurrentCall(CountDownLatch start, ThrowingSupplier call) throws Exception {
        try {
            start.await();
            return call.get();
        } finally {
            // A contender failing before the rendezvous must not strand its peer in the fixture.
            if (raceGate != null) raceGate.countDown();
        }
    }

    private static List<String> resultKinds(List<Object> results) {
        return results.stream().map(result -> result instanceof HallRequestDraftService.Failure failure
                ? failure.reason().name() : result.getClass().getSimpleName()).toList();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<String> definitions() {
        return List.of("hall_private_case", "hall_case_execution").stream()
                .map(table -> jdbc.queryForObject("SHOW CREATE TABLE " + table,
                        (rs, row) -> rs.getString(2))).toList();
    }

    private List<String> checks(String table) {
        return jdbc.queryForList("""
                SELECT cc.check_clause
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.check_constraints cc
                    ON cc.constraint_catalog=tc.constraint_catalog
                   AND cc.constraint_schema=tc.constraint_schema
                   AND cc.constraint_name=tc.constraint_name
                 WHERE tc.table_schema=DATABASE() AND tc.table_name=?
                   AND tc.constraint_type='CHECK'
                """, String.class, table);
    }

    private static PersonalWorkspaceExecutionService.ExecutionView execution(String id) {
        return new PersonalWorkspaceExecutionService.ExecutionView(id, "private-task", "run",
                null, "agent-a", "QUEUED", null, null, 1, MIME, List.of(), null,
                "PRIVATE", null, null, null);
    }

    private static HallRequestDraftService transactionalProxy(HallRequestDraftServiceImpl target,
            DataSourceTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(HallRequestDraftService.class);
        factory.addAdvice(interceptor);
        return (HallRequestDraftService) factory.getProxy();
    }

    private static DriverManagerDataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(user);
        source.setPassword(password);
        return source;
    }

    private static String databaseUrl(String baseUrl, String database) {
        int query = baseUrl.indexOf('?');
        String suffix = query < 0 ? "" : baseUrl.substring(query);
        String root = query < 0 ? baseUrl : baseUrl.substring(0, query);
        int slash = root.indexOf('/', "jdbc:mysql://".length());
        return (slash < 0 ? root + "/" : root.substring(0, slash + 1)) + database + suffix;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static void assertFailure(HallRequestDraftService.Reason reason, Runnable action) {
        HallRequestDraftService.Failure failure = assertThrows(
                HallRequestDraftService.Failure.class, action::run);
        assertEquals(reason, failure.reason());
    }

    @FunctionalInterface
    private interface ThrowingSupplier { Object get() throws Exception; }
}
