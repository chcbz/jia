package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskRequestDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskRequestDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskArtifactPublishDTO;
import cn.jia.agent.entity.AgentTaskArtifactQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestCreateDTO;
import cn.jia.agent.entity.AgentTaskRequestQueryDTO;
import cn.jia.agent.entity.AgentTaskRequestTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemDTO;
import cn.jia.agent.entity.AgentWorkItemLeaseCommandDTO;
import cn.jia.agent.entity.AgentWorkItemResultCommitDTO;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskCollaborationException.Reason;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskRequestMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.service.AgentTaskArtifactService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequestService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.AgentWorkItemResultCommitService;
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
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskCollaborationServiceRealTransactionTest {
    private static final String JDBC_URL =
            "jdbc:h2:mem:cyf_b06_real_tx;MODE=MYSQL;DB_CLOSE_DELAY=-1;"
            + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000";
    private static final String TENANT = "tenant-a";
    private static final String CLIENT = "client-a";
    private static final String TASK = "task-1";
    private static final String REQUESTER = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TARGET = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final long LEASE_NOW = 1_000L;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private PlatformTransactionManager transactionManager;
    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskRequestDao requestDao;
    private AgentTaskArtifactDao artifactDao;
    private AgentTaskRequestService requestService;
    private AgentTaskArtifactService artifactService;
    private AgentTaskWorkItemDao workItemDao;
    private AgentWorkItemLeaseService leaseService;
    private AgentTaskMutationTransaction mutationTransaction;
    private AgentTaskEventWriter eventWriter;
    private AgentWorkItemResultCommitService resultService;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("sa");
        source.setPassword("");
        dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        createTables();

        SqlSessionFactory factory = sqlSessionFactory(dataSource);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        taskDao = new AgentTaskMetaDaoImpl();
        setField(taskDao, "baseMapper", template.getMapper(AgentTaskMetaMapper.class));
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        workItemDao = new AgentTaskWorkItemDaoImpl(
                template.getMapper(AgentTaskWorkItemMapper.class));
        requestDao = new AgentTaskRequestDaoImpl(template.getMapper(AgentTaskRequestMapper.class));
        artifactDao = new AgentTaskArtifactDaoImpl(template.getMapper(AgentTaskArtifactMapper.class));
        AgentTaskEventDaoImpl eventDao = new AgentTaskEventDaoImpl();
        setField(eventDao, "baseMapper", template.getMapper(AgentTaskEventMapper.class));
        eventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(
                        new AgentTaskEventBroker(), transactionManager));
        mutationTransaction = new AgentTaskMutationTransactionImpl(taskDao, transactionManager);

        AtomicLong collaborationClock = new AtomicLong(LEASE_NOW);
        AgentTaskCollaborationServiceImpl raw = new AgentTaskCollaborationServiceImpl(
                taskDao, memberDao, workItemDao, requestDao, artifactDao,
                mutationTransaction, eventWriter, collaborationClock::incrementAndGet);
        Object proxy = transactionalProxy(raw, dataSource,
                AgentTaskRequestService.class, AgentTaskArtifactService.class);
        requestService = (AgentTaskRequestService) proxy;
        artifactService = (AgentTaskArtifactService) proxy;
        AgentWorkItemLeaseServiceImpl rawLease = new AgentWorkItemLeaseServiceImpl(
                memberDao, workItemDao,
                mutationTransaction,
                command -> new cn.jia.agent.entity.AgentTaskEventWriteResult(),
                () -> LEASE_NOW, () -> "lease_generated", 1_000L);
        leaseService = (AgentWorkItemLeaseService) transactionalProxy(
                rawLease, dataSource, AgentWorkItemLeaseService.class);
        AgentWorkItemResultCommitServiceImpl rawResult = new AgentWorkItemResultCommitServiceImpl(
                leaseService, artifactService, workItemDao, mutationTransaction, eventWriter,
                () -> LEASE_NOW);
        resultService = (AgentWorkItemResultCommitService) transactionalProxy(
                rawResult, dataSource, AgentWorkItemResultCommitService.class);

        insertTask(TENANT, CLIENT, TASK, null);
        insertMember(TENANT, CLIENT, TASK, REQUESTER, "worker", "working");
        insertMember(TENANT, CLIENT, TASK, TARGET, "reviewer", "working");
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void aclCrossTenantAndIllegalReferencesFailClosedWithRealMappers() {
        AgentTaskCollaborationException crossTenant = assertThrows(AgentTaskCollaborationException.class,
                () -> requestService.get("tenant-b", CLIENT, TASK, REQUESTER, "req-secret"));
        assertEquals(Reason.NOT_FOUND, crossTenant.getReason());
        assertEquals("Resource was not found in the requested scope", crossTenant.getMessage());

        String outsider = "agt_cccccccccccccccccccccccccccccccc";
        AgentTaskCollaborationException nonMember = assertThrows(AgentTaskCollaborationException.class,
                () -> requestService.list(TENANT, CLIENT, TASK, outsider, null));
        assertEquals(Reason.FORBIDDEN, nonMember.getReason());

        insertWorkItem(TENANT, CLIENT, "other-task", "work-other");
        AgentTaskRequestCreateDTO command = createRequest("req-bad-work");
        command.setWorkItemId("work-other");
        AgentTaskCollaborationException badWork = assertThrows(AgentTaskCollaborationException.class,
                () -> requestService.create(TENANT, CLIENT, TASK, REQUESTER, command));
        assertEquals(Reason.NOT_FOUND, badWork.getReason());
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_request"));
    }

    @Test
    void requestStateMachineAndConcurrentCasAllowExactlyOneTerminalDecision() throws Exception {
        requestService.create(TENANT, CLIENT, TASK, REQUESTER, createRequest("req-race"));
        requestService.acknowledge(TENANT, CLIENT, TASK, TARGET, "req-race",
                transition(0L, Map.of("acknowledged", true)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> invokeAfterBarrier(ready, start, () -> requestService.resolve(
                        TENANT, CLIENT, TASK, TARGET, "req-race",
                        transition(1L, Map.of("decision", "resolved")))),
                () -> invokeAfterBarrier(ready, start, () -> requestService.reject(
                        TENANT, CLIENT, TASK, TARGET, "req-race",
                        transition(1L, Map.of("decision", "rejected"))))), ready, start);

        assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        assertEquals(1, outcomes.stream().filter(o -> o.reason() == Reason.VERSION_CONFLICT).count());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version, response_json FROM agent_task_request WHERE request_id = 'req-race'");
        assertTrue(List.of("resolved", "rejected").contains(row.get("STATUS")));
        assertEquals(2L, ((Number) row.get("VERSION")).longValue());
        assertTrue(row.get("RESPONSE_JSON").toString().contains("decision"));

        AgentTaskCollaborationException terminal = assertThrows(AgentTaskCollaborationException.class,
                () -> requestService.cancel(TENANT, CLIENT, TASK, REQUESTER, "req-race",
                        transition(2L, null)));
        assertEquals(Reason.INVALID_TRANSITION, terminal.getReason());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM agent_task_request WHERE request_id = 'req-race'", Long.class));
    }

    @Test
    void concurrentArtifactPublishUsesLockAndUniqueConstraintToKeepChainMonotonic() throws Exception {
        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER, artifact("artifact-race", 1, 0));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Outcome> outcomes = runConcurrently(List.of(
                () -> invokeAfterBarrier(ready, start, () -> artifactService.publish(
                        TENANT, CLIENT, TASK, REQUESTER, artifact("artifact-race", 2, 1))),
                () -> invokeAfterBarrier(ready, start, () -> artifactService.publish(
                        TENANT, CLIENT, TASK, REQUESTER, artifact("artifact-race", 2, 1)))), ready, start);

        assertEquals(1, outcomes.stream().filter(Outcome::success).count());
        assertEquals(1, outcomes.stream().filter(o -> o.reason() == Reason.VERSION_CONFLICT).count());
        assertEquals(List.of(1, 2), jdbc.queryForList(
                "SELECT artifact_version FROM agent_task_artifact"
                        + " WHERE tenant_id = ? AND client_id = ? AND task_id = ? AND artifact_id = ?"
                        + " ORDER BY artifact_version",
                Integer.class, TENANT, CLIENT, TASK, "artifact-race"));
    }

    @Test
    void artifactAclVisibilityAndStableOrderingAreEnforcedByRealDatabasePath() {
        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER, artifact("shared", 1, 0));
        AgentTaskArtifactPublishDTO privateArtifact = artifact("private", 1, 0);
        privateArtifact.setVisibility("private");
        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER, privateArtifact);
        AgentTaskArtifactPublishDTO reviewArtifact = artifact("review", 1, 0);
        reviewArtifact.setVisibility("reviewer");
        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER, reviewArtifact);

        List<String> reviewerView = artifactService.list(
                TENANT, CLIENT, TASK, TARGET, null).stream().map(a -> a.getArtifactId()).toList();
        assertEquals(List.of("review", "shared"), reviewerView);
        assertThrows(AgentTaskCollaborationException.class,
                () -> artifactService.getLatest(TENANT, CLIENT, TASK, TARGET, "private"));

        List<String> producerView = artifactService.list(
                TENANT, CLIENT, TASK, REQUESTER, null).stream().map(a -> a.getArtifactId()).toList();
        assertEquals(List.of("review", "private", "shared"), producerView);
    }

    @Test
    void requestFiltersBeforeLimitReturnRowsBeyondFormerFiveHundredWindow() {
        insertWorkItem(TENANT, CLIENT, TASK, "work-other");
        insertWorkItem(TENANT, CLIENT, TASK, "work-target");
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            rows.add(new Object[]{
                    "req-other-" + index, TASK, "work-other", REQUESTER, "agent", TARGET,
                    "review", "open", 10, "Other", "Other request", 0L,
                    TENANT, CLIENT, 1L, 1L});
        }
        rows.add(new Object[]{
                "req-target-b", TASK, "work-target", REQUESTER, "agent", TARGET,
                "review", "open", 1, "Target B", "Target request", 0L,
                TENANT, CLIENT, 1L, 1L});
        rows.add(new Object[]{
                "req-target-a", TASK, "work-target", REQUESTER, "agent", TARGET,
                "review", "open", 1, "Target A", "Target request", 0L,
                TENANT, CLIENT, 1L, 1L});
        jdbc.batchUpdate("""
                INSERT INTO agent_task_request
                (request_id, task_id, work_item_id, requester_agent_id, target_type, target_id,
                 request_type, status, priority, title, description, version,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);

        AgentTaskRequestQueryDTO query = new AgentTaskRequestQueryDTO();
        query.setStatus("open");
        query.setWorkItemId("work-target");
        query.setLimit(2);
        List<String> ids = requestService.list(
                TENANT, CLIENT, TASK, REQUESTER, query).stream()
                .map(item -> item.getRequestId()).toList();

        assertEquals(List.of("req-target-a", "req-target-b"), ids);
    }

    @Test
    void artifactAclFiltersBeforeLimitWithoutPermissionExpansion() {
        List<Object[]> rows = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            rows.add(new Object[]{
                    "hidden-" + index, TASK, REQUESTER, "analysis", "Hidden", "x",
                    sha256("x"), 1, "private", "{}", 10_000L, TENANT, CLIENT, 1L, 1L});
        }
        rows.add(new Object[]{
                "visible-b", TASK, REQUESTER, "analysis", "Visible B", "x",
                sha256("x"), 1, "task_members", "{}", 1L, TENANT, CLIENT, 1L, 1L});
        rows.add(new Object[]{
                "visible-a", TASK, REQUESTER, "analysis", "Visible A", "x",
                sha256("x"), 1, "reviewer", "{}", 1L, TENANT, CLIENT, 1L, 1L});
        jdbc.batchUpdate("""
                INSERT INTO agent_task_artifact
                (artifact_id, task_id, producer_agent_id, artifact_type, title, content,
                 content_hash, artifact_version, visibility, metadata_json, created_at,
                 tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);

        AgentTaskArtifactQueryDTO query = new AgentTaskArtifactQueryDTO();
        query.setLimit(2);
        List<String> ids = artifactService.list(
                TENANT, CLIENT, TASK, TARGET, query).stream()
                .map(item -> item.getArtifactId()).toList();

        assertEquals(List.of("visible-a", "visible-b"), ids);
        AgentTaskCollaborationException hidden = assertThrows(AgentTaskCollaborationException.class,
                () -> artifactService.getLatest(TENANT, CLIENT, TASK, TARGET, "hidden-0"));
        assertEquals(Reason.NOT_FOUND, hidden.getReason());
    }

    @Test
    void authoritativeResultCommitReusesB04AndAtomicallySubmitsWorkItem() {
        insertRunningWorkItem("work-result", 7L, "lease-current", 1_500L);

        var result = resultService.commitResult(
                TENANT, CLIENT, TASK, REQUESTER, resultCommand("work-result", "artifact-result",
                        "lease-current", 7L));

        assertEquals("submitted", result.getStatus());
        assertEquals(8L, result.getWorkItemVersion());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, result_artifact_id, lease_token, lease_until, version"
                        + " FROM agent_task_work_item WHERE work_item_id = 'work-result'");
        assertEquals("submitted", row.get("STATUS"));
        assertEquals("artifact-result", row.get("RESULT_ARTIFACT_ID"));
        assertEquals(8L, ((Number) row.get("VERSION")).longValue());
        assertEquals(null, row.get("LEASE_TOKEN"));
        assertEquals(null, row.get("LEASE_UNTIL"));
        assertEquals(1, count("SELECT COUNT(*) FROM agent_task_artifact WHERE artifact_id = 'artifact-result'"));
        assertEquals(List.of("ARTIFACT_PUBLISHED", "WORK_ITEM_SUBMITTED"), jdbc.queryForList(
                "SELECT event_type FROM agent_task_event ORDER BY event_version", String.class));
    }

    @Test
    void staleResultTokenIsRejectedByB04BeforeArtifactInsert() {
        insertRunningWorkItem("work-result", 7L, "lease-current", 1_500L);

        AgentTaskStateException error = assertThrows(AgentTaskStateException.class,
                () -> resultService.commitResult(TENANT, CLIENT, TASK, REQUESTER,
                        resultCommand("work-result", "artifact-stale", "lease-old", 7L)));

        assertEquals(AgentTaskStateException.Reason.LEASE_INVALID, error.getReason());
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_artifact"));
        assertEquals("running", jdbc.queryForObject(
                "SELECT status FROM agent_task_work_item WHERE work_item_id = 'work-result'", String.class));
    }

    @Test
    void resultRootLockMakesConcurrentHeartbeatWaitAndPreservesEventOrder() throws Exception {
        insertRunningWorkItem("work-result", 7L, "lease-current", 1_500L);
        CountDownLatch beforeResultCas = new CountDownLatch(1);
        CountDownLatch allowResultCas = new CountDownLatch(1);
        AgentTaskWorkItemDao pausingDao = (AgentTaskWorkItemDao) Proxy.newProxyInstance(
                AgentTaskWorkItemDao.class.getClassLoader(),
                new Class<?>[]{AgentTaskWorkItemDao.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("updateActiveLeaseByVersion")
                            && args[10] instanceof AgentTaskWorkItemDTO update
                            && "submitted".equals(update.getStatus())) {
                        beforeResultCas.countDown();
                        if (!allowResultCas.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("Timed out waiting to release result CAS");
                        }
                    }
                    try {
                        return method.invoke(workItemDao, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        AgentWorkItemResultCommitService pausingResult =
                (AgentWorkItemResultCommitService) transactionalProxy(
                        new AgentWorkItemResultCommitServiceImpl(
                                leaseService, artifactService, pausingDao,
                                mutationTransaction, eventWriter, () -> LEASE_NOW),
                        dataSource, AgentWorkItemResultCommitService.class);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> resultFuture = executor.submit(() -> pausingResult.commitResult(
                    TENANT, CLIENT, TASK, REQUESTER,
                    resultCommand("work-result", "artifact-result", "lease-current", 7L)));
            assertTrue(beforeResultCas.await(10, TimeUnit.SECONDS));

            Future<Object> heartbeatFuture = executor.submit(() -> {
                AgentWorkItemLeaseCommandDTO heartbeat = new AgentWorkItemLeaseCommandDTO();
                heartbeat.setAgentId(REQUESTER);
                heartbeat.setLeaseToken("lease-current");
                heartbeat.setExpectedVersion(7L);
                heartbeat.setLeaseDurationMillis(800L);
                try {
                    return leaseService.heartbeat(TENANT, CLIENT, TASK, "work-result", heartbeat);
                } catch (RuntimeException e) {
                    return e;
                }
            });
            Thread.sleep(250L);
            assertFalse(heartbeatFuture.isDone(),
                    "heartbeat must wait behind the result transaction's task-root lock");
            allowResultCas.countDown();

            Object result = resultFuture.get(20, TimeUnit.SECONDS);
            assertTrue(result instanceof cn.jia.agent.entity.AgentWorkItemResultCommitViewDTO);
            Object heartbeat = heartbeatFuture.get(20, TimeUnit.SECONDS);
            assertTrue(heartbeat instanceof AgentTaskStateException);
            assertEquals(List.of("ARTIFACT_PUBLISHED", "WORK_ITEM_SUBMITTED"),
                    jdbc.queryForList(
                            "SELECT event_type FROM agent_task_event ORDER BY event_version",
                            String.class));
            assertEquals("submitted", jdbc.queryForObject(
                    "SELECT status FROM agent_task_work_item WHERE work_item_id='work-result'",
                    String.class));
            assertEquals(1, count("SELECT COUNT(*) FROM agent_task_artifact"));
        } finally {
            allowResultCas.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void submittedEventAppendFailureRollsBackArtifactWorkItemAndEarlierArtifactEvent() {
        insertRunningWorkItem("work-result", 7L, "lease-current", 1_500L);
        AgentWorkItemResultCommitService failing =
                (AgentWorkItemResultCommitService) transactionalProxy(
                        new AgentWorkItemResultCommitServiceImpl(
                                leaseService, artifactService, workItemDao, mutationTransaction,
                                command -> {
                                    throw new IllegalStateException("submitted append failed");
                                }, () -> LEASE_NOW),
                        dataSource, AgentWorkItemResultCommitService.class);

        assertThrows(IllegalStateException.class, () -> failing.commitResult(
                TENANT, CLIENT, TASK, REQUESTER,
                resultCommand("work-result", "artifact-rollback", "lease-current", 7L)));

        Map<String, Object> work = jdbc.queryForMap(
                "SELECT status, version, result_artifact_id FROM agent_task_work_item"
                        + " WHERE work_item_id='work-result'");
        assertEquals("running", work.get("STATUS"));
        assertEquals(7L, ((Number) work.get("VERSION")).longValue());
        assertEquals(null, work.get("RESULT_ARTIFACT_ID"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_artifact"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_event"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?",
                Long.class, TASK));
    }

    @Test
    void legacyExternalArtifactWithoutLengthPersistsHashAndOmitsUnknownLengthAndUri() {
        AgentTaskArtifactPublishDTO external = artifact("artifact-external", 1, 0);
        external.setContent(null);
        external.setStorageUri("s3://bucket/private/path/artifact.bin");
        external.setContentByteLength(null);

        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER, external);

        String payload = jdbc.queryForObject(
                "SELECT event_json FROM agent_task_event WHERE event_type='ARTIFACT_PUBLISHED'",
                String.class);
        assertTrue(payload.contains(external.getContentHash()));
        assertFalse(payload.contains("contentByteLength"));
        assertFalse(payload.contains(external.getStorageUri()));
        assertEquals(external.getStorageUri(), jdbc.queryForObject(
                "SELECT storage_uri FROM agent_task_artifact WHERE artifact_id='artifact-external'",
                String.class));
    }

    @Test
    void requestAndArtifactEventsPersistWithCanonicalTypesAndRedactedPayloads() {
        requestService.create(TENANT, CLIENT, TASK, REQUESTER, createRequest("req-events"));
        requestService.acknowledge(TENANT, CLIENT, TASK, TARGET, "req-events",
                transition(0L, Map.of("ack", true)));
        artifactService.publish(TENANT, CLIENT, TASK, REQUESTER,
                artifact("artifact-events", 1, 0));

        assertEquals(List.of("REVIEW_REQUESTED", "REQUEST_ACKNOWLEDGED",
                        "ARTIFACT_PUBLISHED"),
                jdbc.queryForList("SELECT event_type FROM agent_task_event ORDER BY event_version",
                        String.class));
        String artifactJson = jdbc.queryForObject(
                "SELECT event_json FROM agent_task_event WHERE event_type='ARTIFACT_PUBLISHED'",
                String.class);
        assertTrue(artifactJson.contains(sha256("artifact-events-v1")));
        assertTrue(artifactJson.contains("contentByteLength"));
        assertTrue(!artifactJson.contains("artifact-events-v1"));
        assertTrue(!artifactJson.contains("metadata"));
    }

    @Test
    void requestAppendFailureRollsBackBusinessRowAndEventVersion() {
        AgentTaskRequestService failing = (AgentTaskRequestService) transactionalProxy(
                new AgentTaskCollaborationServiceImpl(
                        taskDao, memberDao, workItemDao, requestDao, artifactDao,
                        mutationTransaction, command -> {
                            throw new IllegalStateException("append failed");
                        }, () -> LEASE_NOW),
                dataSource, AgentTaskRequestService.class);

        assertThrows(IllegalStateException.class, () -> failing.create(
                TENANT, CLIENT, TASK, REQUESTER, createRequest("req-rollback")));

        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_request"));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_task_event"));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT current_event_version FROM agent_task_meta WHERE task_id=?",
                Long.class, TASK));
    }

    private List<Outcome> runConcurrently(List<Callable<Outcome>> calls,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> call : calls) {
                futures.add(executor.submit(call));
            }
            ready.await();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private Outcome invokeAfterBarrier(CountDownLatch ready, CountDownLatch start, Runnable action) {
        ready.countDown();
        try {
            start.await();
            action.run();
            return new Outcome(true, null);
        } catch (AgentTaskCollaborationException e) {
            return new Outcome(false, e.getReason());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Object transactionalProxy(Object raw, DataSource dataSource, Class<?>... interfaces) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(transactionManager);
        NameMatchTransactionAttributeSource source = new NameMatchTransactionAttributeSource();
        RuleBasedTransactionAttribute attribute = new RuleBasedTransactionAttribute();
        attribute.setRollbackRules(List.of(new RollbackRuleAttribute(Exception.class)));
        source.setNameMap(Map.of("*", attribute));
        interceptor.setTransactionAttributeSource(source);
        ProxyFactory proxyFactory = new ProxyFactory(raw);
        proxyFactory.setInterfaces(interfaces);
        proxyFactory.addAdvice(interceptor);
        return proxyFactory.getProxy();
    }

    private SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        configuration.addMapper(AgentTaskRequestMapper.class);
        configuration.addMapper(AgentTaskArtifactMapper.class);
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
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,
                    reward_status VARCHAR(20) NOT NULL DEFAULT 'open',
                    assigned_agent_id VARCHAR(100),
                    required_abilities TEXT,
                    reward INT, assigned_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    failure_reason VARCHAR(1000), collaboration_mode VARCHAR(20) DEFAULT 'single',
                    risk_level VARCHAR(20) DEFAULT 'low', max_agents INT DEFAULT 1,
                    coordinator_agent_id VARCHAR(100), review_required TINYINT DEFAULT 0,
                    task_version BIGINT NOT NULL DEFAULT 0,
                    current_event_version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL,
                    client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT,
                    update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL, event_version BIGINT NOT NULL,
                    event_id VARCHAR(100) NOT NULL, event_type VARCHAR(64) NOT NULL,
                    actor_type VARCHAR(20) NOT NULL, actor_id VARCHAR(100),
                    aggregate_type VARCHAR(30) NOT NULL, aggregate_id VARCHAR(100) NOT NULL,
                    event_json CLOB NOT NULL, occurred_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, event_version),
                    UNIQUE (tenant_id, client_id, event_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    task_id VARCHAR(100) NOT NULL,
                    agent_id VARCHAR(100) NOT NULL,
                    member_role VARCHAR(20) NOT NULL,
                    member_status VARCHAR(20) NOT NULL,
                    assignment_source VARCHAR(20) NOT NULL DEFAULT 'manual',
                    joined_at BIGINT, accepted_at BIGINT, started_at BIGINT, completed_at BIGINT,
                    last_heartbeat_at BIGINT, failure_reason VARCHAR(1000),
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, task_id, agent_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_work_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    work_item_id VARCHAR(100) NOT NULL,
                    task_id VARCHAR(100) NOT NULL,
                    title VARCHAR(255) NOT NULL DEFAULT '', description TEXT,
                    work_type VARCHAR(30) NOT NULL DEFAULT 'implementation', required_abilities TEXT,
                    assignee_agent_id VARCHAR(100), status VARCHAR(20) NOT NULL DEFAULT 'ready',
                    priority INT NOT NULL DEFAULT 0, required_item TINYINT NOT NULL DEFAULT 1,
                    dependency_json TEXT, lease_token VARCHAR(100), lease_until BIGINT,
                    attempt_count INT NOT NULL DEFAULT 0, max_attempts INT NOT NULL DEFAULT 3,
                    result_artifact_id VARCHAR(100), submitted_at BIGINT, completed_at BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, work_item_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_request (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    request_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100), requester_agent_id VARCHAR(100) NOT NULL,
                    target_type VARCHAR(20) NOT NULL, target_id VARCHAR(100) NOT NULL,
                    request_type VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'open',
                    priority INT NOT NULL DEFAULT 0, title VARCHAR(255) NOT NULL, description TEXT NOT NULL,
                    response_json CLOB, due_at BIGINT, acknowledged_at BIGINT, resolved_at BIGINT,
                    version BIGINT NOT NULL DEFAULT 0,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, request_id)
                )""");
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    artifact_id VARCHAR(100) NOT NULL, task_id VARCHAR(100) NOT NULL,
                    work_item_id VARCHAR(100), producer_agent_id VARCHAR(100) NOT NULL,
                    artifact_type VARCHAR(30) NOT NULL, title VARCHAR(255) NOT NULL,
                    content CLOB, storage_uri VARCHAR(1000), content_hash VARCHAR(128),
                    artifact_version INT NOT NULL, visibility VARCHAR(20) NOT NULL,
                    metadata_json CLOB, created_at BIGINT NOT NULL,
                    tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
                    create_time BIGINT, update_time BIGINT,
                    UNIQUE (tenant_id, client_id, artifact_id, artifact_version)
                )""");
    }

    private void insertTask(String tenant, String client, String task, String coordinator) {
        jdbc.update("INSERT INTO agent_task_meta"
                        + " (task_id, reward_status, coordinator_agent_id, tenant_id, client_id, create_time, update_time)"
                        + " VALUES (?, 'running', ?, ?, ?, 1, 1)",
                task, coordinator, tenant, client);
    }

    private void insertMember(String tenant, String client, String task,
            String agent, String role, String status) {
        jdbc.update("INSERT INTO agent_task_member"
                        + " (task_id, agent_id, member_role, member_status, assignment_source,"
                        + " tenant_id, client_id, create_time, update_time)"
                        + " VALUES (?, ?, ?, ?, 'manual', ?, ?, 1, 1)",
                task, agent, role, status, tenant, client);
    }

    private void insertWorkItem(String tenant, String client, String task, String workItem) {
        jdbc.update("INSERT INTO agent_task_work_item"
                        + " (work_item_id, task_id, title, work_type, status, tenant_id, client_id, create_time, update_time)"
                        + " VALUES (?, ?, 'work', 'implementation', 'ready', ?, ?, 1, 1)",
                workItem, task, tenant, client);
    }

    private AgentTaskRequestCreateDTO createRequest(String requestId) {
        AgentTaskRequestCreateDTO command = new AgentTaskRequestCreateDTO();
        command.setRequestId(requestId);
        command.setRequesterAgentId(REQUESTER);
        command.setTargetType("agent");
        command.setTargetId(TARGET);
        command.setRequestType("review");
        command.setPriority(5);
        command.setTitle("Review requested");
        command.setDescription("Please review the result");
        return command;
    }

    private AgentTaskRequestTransitionDTO transition(long version, Map<String, Object> response) {
        AgentTaskRequestTransitionDTO command = new AgentTaskRequestTransitionDTO();
        command.setExpectedVersion(version);
        command.setResponse(response);
        return command;
    }

    private void insertRunningWorkItem(
            String workItemId, long version, String leaseToken, long leaseUntil) {
        jdbc.update("""
                INSERT INTO agent_task_work_item
                (work_item_id, task_id, title, description, work_type, required_abilities,
                 assignee_agent_id, status, priority, required_item, dependency_json,
                 lease_token, lease_until, attempt_count, max_attempts, result_artifact_id,
                 submitted_at, completed_at, version, tenant_id, client_id, create_time, update_time)
                VALUES (?, ?, 'result work', 'preserve', 'implementation', '[]',
                        ?, 'running', 10, 1, '[]', ?, ?, 0, 3, NULL, NULL, NULL, ?, ?, ?, 1, 1)
                """, workItemId, TASK, REQUESTER, leaseToken, leaseUntil, version, TENANT, CLIENT);
    }

    private AgentWorkItemResultCommitDTO resultCommand(
            String workItemId, String artifactId, String leaseToken, long version) {
        AgentTaskArtifactPublishDTO artifact = artifact(artifactId, 1, 0);
        artifact.setWorkItemId(workItemId);
        AgentWorkItemResultCommitDTO command = new AgentWorkItemResultCommitDTO();
        command.setWorkItemId(workItemId);
        command.setProducerAgentId(REQUESTER);
        command.setLeaseToken(leaseToken);
        command.setExpectedWorkItemVersion(version);
        command.setArtifact(artifact);
        return command;
    }

    private AgentTaskArtifactPublishDTO artifact(String artifactId, int version, int previous) {
        String content = artifactId + "-v" + version;
        AgentTaskArtifactPublishDTO command = new AgentTaskArtifactPublishDTO();
        command.setArtifactId(artifactId);
        command.setProducerAgentId(REQUESTER);
        command.setArtifactType("analysis");
        command.setTitle(artifactId);
        command.setContent(content);
        command.setContentHash(sha256(content));
        command.setArtifactVersion(version);
        command.setExpectedPreviousVersion(previous);
        command.setVisibility("task_members");
        command.setMetadata(Map.of("version", version));
        return command;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record Outcome(boolean success, Reason reason) {
    }
}
