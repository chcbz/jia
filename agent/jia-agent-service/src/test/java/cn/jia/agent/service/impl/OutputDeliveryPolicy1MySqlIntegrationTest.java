package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitDispatchScopeProperties;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitSafetyProperties;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.config.AgentSceneFeatureFlags;
import cn.jia.agent.config.OutputDeliveryLeaseSchemaInitializer;
import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.config.OutputDeliverySchemaInitializer;
import cn.jia.agent.config.OutputDeliverySubmissionSchemaInitializer;
import cn.jia.agent.config.OutputObjectSchemaInitializer;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskArtifactOutcomeDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentCommandTransportDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskArtifactOutcomeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskWorkItemDaoImpl;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskArtifactAcceptDTO;
import cn.jia.agent.entity.AgentTaskArtifactRefDTO;
import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDeliveryRequirementsDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskStateTransitionDTO;
import cn.jia.agent.entity.AgentTaskWorkItemEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.exception.AgentTaskStateException;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentCommandTransportMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskArtifactOutcomeMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskOutputMapper;
import cn.jia.agent.mapper.AgentTaskWorkItemMapper;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputLeaseHttpResult;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.TaskDeliveryHttpResult;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import cn.jia.agent.output.dao.impl.OutputAccessTicketDaoImpl;
import cn.jia.agent.output.dao.impl.OutputRunBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputSourceBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.dao.impl.TaskDeliveryDaoImpl;
import cn.jia.agent.output.dto.OutputAuthReceiptDTO;
import cn.jia.agent.output.dto.OutputLeaseRequestDTO;
import cn.jia.agent.output.dto.OutputPublishDTO;
import cn.jia.agent.output.dto.TaskDeliveryItemDTO;
import cn.jia.agent.output.dto.TaskDeliverySubmitDTO;
import cn.jia.agent.output.mapper.OutputAccessTicketMapper;
import cn.jia.agent.output.mapper.OutputRunBindingMapper;
import cn.jia.agent.output.mapper.OutputSourceBindingMapper;
import cn.jia.agent.output.service.OutputLeaseServiceImpl;
import cn.jia.agent.output.service.OutputDeliveryServiceImpl;
import cn.jia.agent.output.service.OutputRunAuthorizationServiceImpl;
import cn.jia.agent.output.service.OutputSourceAuthorizerRegistry;
import cn.jia.agent.output.service.TaskDeliverySubmissionServiceImpl;
import cn.jia.agent.output.service.TaskOutputSourceAuthorizer;
import cn.jia.agent.output.service.TaskOutputVersionProvider;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskAggregationService;
import cn.jia.agent.service.AgentTaskArtifactOutcomeService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.AgentTaskEventBroker;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskStateService;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.util.JsonUtil;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.task.service.TaskService;
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
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Actual MySQL 8 proof for the authenticated policy-1 create/dispatch/ticket/lease chain. */
@EnabledIfEnvironmentVariable(named = "OD01_MYSQL_URL", matches = ".+")
class OutputDeliveryPolicy1MySqlIntegrationTest {
    private static final String TENANT = "owner";
    private static final String CLIENT = "client";
    private static final String AGENT_A = "agt_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AGENT_B = "agt_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transaction;
    private String baseUrl;
    private String username;
    private String password;
    private String database;

    private AgentTaskMetaDao taskDao;
    private AgentTaskMemberDao memberDao;
    private AgentTaskWorkItemDao workItemDao;
    private AgentRuntimeDao runtimeDao;
    private AgentIdentityService identityService;
    private AgentTaskMutationTransaction taskTransactions;
    private AgentTaskEventWriter eventWriter;
    private AgentLegacyTaskCompatibilityService legacy;
    private AgentTaskStateService stateService;
    private AgentTaskArtifactOutcomeService artifactOutcomeService;
    private OutputRunAuthorizationServiceImpl authorization;
    private OutputRunBindingDao runDao;
    private OutputUploadDao receipts;
    private AgentTaskArtifactDao artifactDao;
    private TaskDeliveryDao deliveryDao;
    private OutputDeliveryService outputDeliveryService;
    private TaskDeliverySubmissionServiceImpl submissionService;
    private AgentServiceImpl agentService;
    private AgentCommandTransportWriter commandWriter;
    private AgentCommandTransportCapture commandCapture;
    private AtomicInteger leaseTokens;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        EsContextHolder.setContext(new EsContext());
        baseUrl = requiredEnvironment("OD01_MYSQL_URL");
        username = environment("OD01_MYSQL_USER", "root");
        password = environment("OD01_MYSQL_PASSWORD", "");
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0."), version);
        database = "cyf_od08_policy1_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        dataSource = dataSource(urlForDatabase(baseUrl, database));
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        new ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/schema.sql"))
                .execute(dataSource);
        new ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                        "db/agent-task-artifact-outcome-f06.sql"))
                .execute(dataSource);
        new OutputDeliveryLeaseSchemaInitializer(jdbc).afterPropertiesSet();
        new OutputDeliverySubmissionSchemaInitializer(jdbc).afterPropertiesSet();
        new OutputDeliverySchemaInitializer(jdbc).afterPropertiesSet();
        new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet();
        wireServices();
        authenticate();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        EsContextHolder.clearContext();
        if (admin != null && database != null) {
            assertTrue(database.matches("cyf_od08_policy1_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void authenticatedCreateDispatchTicketAndLeaseLifecycleUseOneTrustedRun() throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A);

        assertEquals(1, count("agent_task_member", assigned.taskId()));
        assertEquals(1, count("agent_task_work_item", assigned.taskId()));
        assertEquals(1, count("output_run_binding", assigned.taskId()));
        assertEquals(assigned.runId(), jdbc.queryForObject("""
                SELECT dispatched_run_id FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));
        assertRunCommandIdentity(
                assigned.runId(), assigned.taskId(), assigned.workItemId(), "TASK_ASSIGNED");

        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        OutputLeaseHttpResult recoveredReady = leaseService(receipts).recover(
                ticket.projected(), ticket.bearer(), assigned.taskId(), assigned.workItemId(),
                "req-recover-ready");
        assertEquals(200, recoveredReady.status());
        assertEquals("ready", field(recoveredReady, "status"));
        assertEquals("0", field(recoveredReady, "version"));

        OutputLeaseHttpResult claimed = mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-claim-0001", "req-claim");
        assertEquals(200, claimed.status());
        String leaseToken = field(claimed, "leaseToken");
        assertNotNull(leaseToken);
        assertEquals("1", field(claimed, "version"));
        assertEquals(assigned.runId(), jdbc.queryForObject("""
                SELECT execution_run_id FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));

        OutputLeaseHttpResult sameKeyReplay = mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-claim-0001", "req-replay");
        assertEquals(json(claimed), json(sameKeyReplay));
        OutputLeaseHttpResult sameKeyDifferentBody = mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "119999"),
                "idempotency-claim-0001", "req-conflict");
        assertEquals(409, sameKeyDifferentBody.status());
        assertEquals("OUTPUT_IDEMPOTENCY_CONFLICT", code(sameKeyDifferentBody));
        assertEquals(1L, workVersion(assigned.workItemId()));

        OutputLeaseHttpResult started = mutate(ticket, assigned, "start",
                request(assigned.runId(), "1", leaseToken, null),
                "idempotency-start-0001", "req-start");
        assertEquals("running", field(started, "status"));
        assertEquals("2", field(started, "version"));

        OutputLeaseHttpResult heartbeat = mutate(ticket, assigned, "heartbeat",
                request(assigned.runId(), "2", leaseToken, "120000"),
                "idempotency-heartbeat-0001", "req-heartbeat");
        assertEquals("3", field(heartbeat, "version"));
        OutputLeaseHttpResult recoveredRunning = leaseService(receipts).recover(
                ticket.projected(), ticket.bearer(), assigned.taskId(), assigned.workItemId(),
                "req-recover-running");
        assertEquals(heartbeat.responseJson(), recoveredRunning.responseJson());

        OutputLeaseHttpResult released = mutate(ticket, assigned, "release",
                request(assigned.runId(), "3", leaseToken, null),
                "idempotency-release-0001", "req-release");
        assertEquals("ready", field(released, "status"));
        assertEquals("4", field(released, "version"));
        assertNull(json(released).path("data").get("leaseToken"));
        assertNull(jdbc.queryForObject("""
                SELECT execution_run_id FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",
                Integer.class));

        String secondRun = dispatchedRun(assigned.workItemId());
        assertNotNull(secondRun);
        assertTrue(!assigned.runId().equals(secondRun));
        assertEquals(AGENT_A, jdbc.queryForObject("""
                SELECT assignee_agent_id FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));
        assertEquals(2, count("output_run_binding", assigned.taskId()));
        assertRunCommandIdentity(
                secondRun, assigned.taskId(), assigned.workItemId(),
                "WORK_ITEM_LEASE_RELEASED");

        OutputLeaseHttpResult replayedRelease = mutate(ticket, assigned, "release",
                request(assigned.runId(), "3", leaseToken, null),
                "idempotency-release-0001", "req-release-replay-after-redispatch");
        assertEquals(json(released), json(replayedRelease));

        OutputLeaseHttpResult staleReleasedRun = mutate(ticket, assigned, "claim",
                request(assigned.runId(), "4", null, "120000"),
                "idempotency-old-run-claim-1", "req-old-run-claim");
        assertEquals(409, staleReleasedRun.status());
        Ticket secondTicket = ticket(
                new AssignedTask(assigned.taskId(), assigned.workItemId(), secondRun),
                AGENT_A, "runtime-a");
        OutputLeaseHttpResult secondClaim = leaseService(receipts).mutate(
                secondTicket.projected(), secondTicket.bearer(),
                "idempotency-second-run-claim", assigned.taskId(), assigned.workItemId(),
                "claim", request(secondRun, "4", null, "120000"), "req-second-run-claim");
        assertEquals(200, secondClaim.status());
        assertEquals("5", field(secondClaim, "version"));

        jdbc.update("""
                UPDATE agent_task_work_item SET lease_until=?
                WHERE tenant_id=? AND client_id=? AND work_item_id=? AND version=5
                """, System.currentTimeMillis() - 1, TENANT, CLIENT, assigned.workItemId());
        assertEquals(1, leaseCore().expireLeases(TENANT, CLIENT, 10).getRequeuedCount());
        String thirdRun = dispatchedRun(assigned.workItemId());
        assertNotNull(thirdRun);
        assertTrue(!secondRun.equals(thirdRun));
        assertEquals(3, count("output_run_binding", assigned.taskId()));
        assertRunCommandIdentity(
                thirdRun, assigned.taskId(), assigned.workItemId(), "WORK_ITEM_REQUEUED");

        OutputLeaseHttpResult staleExpiredRun = leaseService(receipts).mutate(
                secondTicket.projected(), secondTicket.bearer(),
                "idempotency-expired-run-claim", assigned.taskId(), assigned.workItemId(),
                "claim", request(secondRun, "6", null, "120000"), "req-expired-run-claim");
        assertEquals(409, staleExpiredRun.status());
        Ticket thirdTicket = ticket(
                new AssignedTask(assigned.taskId(), assigned.workItemId(), thirdRun),
                AGENT_A, "runtime-a");
        OutputLeaseHttpResult thirdClaim = leaseService(receipts).mutate(
                thirdTicket.projected(), thirdTicket.bearer(),
                "idempotency-third-run-claim", assigned.taskId(), assigned.workItemId(),
                "claim", request(thirdRun, "6", null, "120000"), "req-third-run-claim");
        assertEquals(200, thirdClaim.status());
        assertEquals("7", field(thirdClaim, "version"));

        OutputLeaseHttpResult exhausted = leaseService(receipts).mutate(
                thirdTicket.projected(), thirdTicket.bearer(),
                "idempotency-third-run-release", assigned.taskId(), assigned.workItemId(),
                "release", request(thirdRun, "7", field(thirdClaim, "leaseToken"), null),
                "req-third-run-release");
        assertEquals(200, exhausted.status());
        assertEquals("failed", field(exhausted, "status"));
        assertEquals("8", field(exhausted, "version"));
        assertEquals(3, count("output_run_binding", assigned.taskId()));
        Map<String, Object> exhaustedRow = jdbc.queryForMap("""
                SELECT attempt_count,assignee_agent_id,execution_run_id,dispatched_run_id
                FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, TENANT, CLIENT, assigned.workItemId());
        assertEquals(3, ((Number) exhaustedRow.get("attempt_count")).intValue());
        assertNull(exhaustedRow.get("assignee_agent_id"));
        assertNull(exhaustedRow.get("execution_run_id"));
        assertNull(exhaustedRow.get("dispatched_run_id"));
    }

    @Test
    void formalSubmissionCommitsOneBatchAndTerminalTicketReplaysExactReceipt() throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "text");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        OutputLeaseHttpResult claim = mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-submit-claim-1", "req-submit-claim");
        String leaseToken = field(claim, "leaseToken");
        OutputLeaseHttpResult started = mutate(ticket, assigned, "start",
                request(assigned.runId(), "1", leaseToken, null),
                "idempotency-submit-start-1", "req-submit-start");
        assertEquals("2", field(started, "version"));

        outputDeliveryService.publish(ticket.bearer(), "idempotency-publish-summary-1",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Final conclusion", "summary",
                        "The requested work is complete with verified results.", null,
                        "final-summary", "1", assigned.workItemId(), "private", false));
        long taskVersion = jdbc.queryForObject("""
                SELECT task_version FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, assigned.taskId());
        TaskDeliverySubmitDTO request = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Ready for owner review", List.of(
                        new TaskDeliveryItemDTO("final-summary", "1", "conclusion")));

        TaskDeliveryHttpResult submitted = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-submit-1",
                assigned.taskId(), request, "req-formal-submit");
        assertEquals(200, submitted.status());
        JsonNode response = JsonUtil.getMapper().readTree(submitted.responseJson());
        String deliveryId = response.path("data").path("deliveryId").asText();
        assertTrue(deliveryId.matches("[0-9a-f]{32}"));
        assertEquals("SUBMITTED", response.path("data").path("state").asText());
        assertEquals("1", response.path("data").path("revision").asText());
        assertEquals(1, count("task_delivery", assigned.taskId()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_delivery_item
                WHERE tenant_id=? AND client_id=? AND delivery_id=?
                """, Integer.class, TENANT, CLIENT, deliveryId));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_artifact
                WHERE tenant_id=? AND client_id=? AND task_id=?
                  AND artifact_id=? AND artifact_version=1
                """, Integer.class, TENANT, CLIENT, assigned.taskId(),
                "delivery-manifest-" + deliveryId));
        Map<String, Object> work = jdbc.queryForMap("""
                SELECT status,version,lease_token,lease_until,result_delivery_id,
                       result_artifact_id,execution_run_id,dispatched_run_id
                FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, TENANT, CLIENT, assigned.workItemId());
        assertEquals("submitted", work.get("status"));
        assertEquals(3L, ((Number) work.get("version")).longValue());
        assertNull(work.get("lease_token"));
        assertNull(work.get("lease_until"));
        assertEquals(deliveryId, dbString(work.get("result_delivery_id")));
        assertEquals("delivery-manifest-" + deliveryId,
                dbString(work.get("result_artifact_id")));
        assertEquals(assigned.runId(), dbString(work.get("execution_run_id")));
        assertEquals(assigned.runId(), dbString(work.get("dispatched_run_id")));
        Map<String, Object> task = jdbc.queryForMap("""
                SELECT reward_status,task_version,current_delivery_id,delivery_revision
                FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, TENANT, CLIENT, assigned.taskId());
        assertEquals("reviewing", task.get("reward_status"));
        assertEquals(taskVersion + 1, ((Number) task.get("task_version")).longValue());
        assertEquals(deliveryId, dbString(task.get("current_delivery_id")));
        assertEquals(1L, ((Number) task.get("delivery_revision")).longValue());
        assertEquals(OutputConstants.RUN_RESULT_SUBMITTED, jdbc.queryForObject("""
                SELECT state FROM output_run_binding
                WHERE tenant_id=? AND client_id=? AND run_id=?
                """, String.class, TENANT, CLIENT, assigned.runId()));

        TaskDeliveryHttpResult replay = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-submit-1",
                assigned.taskId(), request, "req-formal-replay");
        assertEquals(submitted.responseJson(), replay.responseJson());
        TaskDeliverySubmitDTO changed = new TaskDeliverySubmitDTO(
                request.runId(), request.workItemId(), request.expectedTaskVersion(),
                request.expectedWorkItemVersion(), request.leaseToken(),
                "Changed after commit", request.items());
        TaskDeliveryHttpResult conflict = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-submit-1",
                assigned.taskId(), changed, "req-formal-conflict");
        assertEquals(409, conflict.status());
        assertEquals("OUTPUT_IDEMPOTENCY_CONFLICT",
                JsonUtil.getMapper().readTree(conflict.responseJson()).path("code").asText());
        assertEquals(1, count("task_delivery", assigned.taskId()));
        assertThrows(OutputAuthorizationException.class, () -> submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-submit-2",
                assigned.taskId(), request, "req-formal-second-key"));
    }

    @Test
    void objectBackedSubmissionCreatesDeliveryPinAndSerializesWithGc() throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "files");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        String leaseToken = field(mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-file-claim-1", "req-file-claim"), "leaseToken");
        mutate(ticket, assigned, "start", request(assigned.runId(), "1", leaseToken, null),
                "idempotency-file-start-1", "req-file-start");

        String objectId = seedReadyObject(assigned, "zip-result".getBytes(StandardCharsets.UTF_8),
                "result.zip", "application/zip");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-publish-file-1",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Result archive", "document", null,
                        objectId, "result-file", "1", assigned.workItemId(), "private", false));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM output_object_reference
                WHERE tenant_id=? AND client_id=? AND object_id=?
                  AND reference_kind='ROLE_CANDIDATE' AND state='ACTIVE' AND hold=FALSE
                """, Integer.class, TENANT, CLIENT, objectId));
        jdbc.update("""
                UPDATE output_object_reference
                SET state='RELEASED',retain_until=0,released_at=?,updated_at=?,row_version=row_version+1
                WHERE tenant_id=? AND client_id=? AND object_id=?
                  AND reference_kind='ROLE_CANDIDATE' AND state='ACTIVE'
                """, System.currentTimeMillis(), System.currentTimeMillis(),
                TENANT, CLIENT, objectId);

        long taskVersion = jdbc.queryForObject("SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", Long.class,
                TENANT, CLIENT, assigned.taskId());
        TaskDeliverySubmitDTO request = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "The exact result archive is ready", List.of(
                        new TaskDeliveryItemDTO("result-file", "1", "archive")));

        CountDownLatch pinInserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch gcStarted = new CountDownLatch(1);
        OutputUploadDao blockingPins = blockingDeliveryPinDao(pinInserted, allowCommit);
        TaskDeliverySubmissionServiceImpl pinningService = new TaskDeliverySubmissionServiceImpl(
                authorization, taskTransactions, taskDao, workItemDao, artifactDao,
                runDao, blockingPins, deliveryDao, eventWriter,
                new OutputDeliveryProperties(true));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<TaskDeliveryHttpResult> submit = pool.submit(() -> pinningService.submit(
                    ticket.projected(), ticket.bearer(), "idempotency-file-submit-1",
                    assigned.taskId(), request, "req-file-submit"));
            assertTrue(pinInserted.await(10, TimeUnit.SECONDS));

            Future<Boolean> gcClaimed = pool.submit(() -> {
                gcStarted.countDown();
                OutputUploadDao gcDao = new OutputUploadDaoImpl(new JdbcTemplate(dataSource));
                TransactionTemplate gcTransaction = new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource));
                Boolean claimed = gcTransaction.execute(status -> {
                    long now = System.currentTimeMillis();
                    OutputUploadDao.ObjectRow locked = gcDao.findObject(
                            TENANT, CLIENT, objectId, true);
                    if (locked == null) return false;
                    if ("READY".equals(locked.lifecycleStatus())
                            && gcDao.activeObjectReferences(TENANT, CLIENT, objectId, now) > 0) {
                        return false;
                    }
                    return gcDao.claimObjectDelete(TENANT, CLIENT, objectId,
                            "od08-gc", now + 60_000, now) == 1;
                });
                return Boolean.TRUE.equals(claimed);
            });
            assertTrue(gcStarted.await(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> gcClaimed.get(200, TimeUnit.MILLISECONDS));
            allowCommit.countDown();

            TaskDeliveryHttpResult submitted = submit.get(10, TimeUnit.SECONDS);
            assertEquals(200, submitted.status());
            assertTrue(!gcClaimed.get(10, TimeUnit.SECONDS));
            String deliveryId = JsonUtil.getMapper().readTree(submitted.responseJson())
                    .path("data").path("deliveryId").asText();
            Map<String, Object> pin = jdbc.queryForMap("""
                    SELECT reference_kind,delivery_id,state,hold,hold_reason,retain_until
                    FROM output_object_reference
                    WHERE tenant_id=? AND client_id=? AND object_id=?
                      AND reference_kind='DELIVERY_PIN'
                    """, TENANT, CLIENT, objectId);
            assertEquals("DELIVERY_PIN", pin.get("reference_kind"));
            assertEquals(deliveryId, dbString(pin.get("delivery_id")));
            assertEquals("ACTIVE", pin.get("state"));
            assertTrue(Boolean.TRUE.equals(pin.get("hold"))
                    || pin.get("hold") instanceof Number value && value.intValue() == 1);
            assertEquals("DELIVERY_SUBMITTED", pin.get("hold_reason"));
            assertTrue(((Number) pin.get("retain_until")).longValue()
                    > System.currentTimeMillis());
            assertEquals("READY", jdbc.queryForObject("""
                    SELECT lifecycle_status FROM output_object
                    WHERE tenant_id=? AND client_id=? AND object_id=?
                    """, String.class, TENANT, CLIENT, objectId));
        } finally {
            allowCommit.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void mixedRequirementsEnforceInlineConclusionFileCountAndRequiredNames()
            throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "mixed");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        String leaseToken = field(mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-mixed-claim-1", "req-mixed-claim"), "leaseToken");
        mutate(ticket, assigned, "start", request(assigned.runId(), "1", leaseToken, null),
                "idempotency-mixed-start-1", "req-mixed-start");

        String wrongNameObject = seedReadyObject(
                assigned, "wrong-name".getBytes(StandardCharsets.UTF_8),
                "other.zip", "application/zip");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-mixed-wrong-file",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Wrong named archive", "document", null,
                        wrongNameObject, "wrong-file", "1", assigned.workItemId(),
                        "private", false));
        long taskVersion = jdbc.queryForObject("SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", Long.class,
                TENANT, CLIENT, assigned.taskId());
        TaskDeliverySubmitDTO fileOnly = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "File without conclusion", List.of(
                        new TaskDeliveryItemDTO("wrong-file", "1", "archive")));
        TaskDeliveryHttpResult missingText = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-mixed-no-text",
                assigned.taskId(), fileOnly, "req-mixed-no-text");
        assertEquals(422, missingText.status());
        assertEquals("OUTPUT_DELIVERY_TEXT_REQUIRED", JsonUtil.getMapper()
                .readTree(missingText.responseJson()).path("code").asText());

        outputDeliveryService.publish(ticket.bearer(), "idempotency-mixed-summary",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Mixed conclusion", "summary",
                        "The mixed delivery includes a real inline conclusion.", null,
                        "mixed-summary", "1", assigned.workItemId(), "private", false));
        TaskDeliverySubmitDTO wrongName = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Wrong required name", List.of(
                        new TaskDeliveryItemDTO("mixed-summary", "1", "conclusion"),
                        new TaskDeliveryItemDTO("wrong-file", "1", "archive")));
        TaskDeliveryHttpResult missingName = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-mixed-wrong-name",
                assigned.taskId(), wrongName, "req-mixed-wrong-name");
        assertEquals(422, missingName.status());
        assertEquals("OUTPUT_DELIVERY_REQUIRED_NAME_MISSING", JsonUtil.getMapper()
                .readTree(missingName.responseJson()).path("code").asText());

        String correctObject = seedReadyObject(
                assigned, "correct-name".getBytes(StandardCharsets.UTF_8),
                "result.zip", "application/zip");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-mixed-correct-file",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Required result archive", "document", null,
                        correctObject, "correct-file", "1", assigned.workItemId(),
                        "private", false));
        TaskDeliverySubmitDTO complete = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Complete mixed delivery", List.of(
                        new TaskDeliveryItemDTO("mixed-summary", "1", "conclusion"),
                        new TaskDeliveryItemDTO("correct-file", "1", "archive")));
        TaskDeliveryHttpResult submitted = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-mixed-complete",
                assigned.taskId(), complete, "req-mixed-complete");
        assertEquals(200, submitted.status());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM task_delivery_item",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM output_object_reference
                WHERE tenant_id=? AND client_id=? AND object_id=?
                  AND reference_kind='DELIVERY_PIN' AND state='ACTIVE' AND hold=TRUE
                """, Integer.class, TENANT, CLIENT, correctObject));
    }

    @Test
    void failureAfterDeliveryItemInsertRollsBackManifestBatchStateRunEventsAndReceipt()
            throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "text");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        String leaseToken = field(mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-rollback-claim", "req-rollback-claim"), "leaseToken");
        mutate(ticket, assigned, "start", request(assigned.runId(), "1", leaseToken, null),
                "idempotency-rollback-start", "req-rollback-start");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-rollback-publish",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Rollback conclusion", "summary",
                        "This real published artifact must survive a failed submit.", null,
                        "rollback-summary", "1", assigned.workItemId(), "private", false));
        outputDeliveryService.publish(ticket.bearer(), "idempotency-rollback-publish-details",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Rollback details", "analysis",
                        "The second immutable item makes the partial-write boundary observable.",
                        null, "rollback-details", "1", assigned.workItemId(), "private", false));
        long taskVersion = jdbc.queryForObject("SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", Long.class,
                TENANT, CLIENT, assigned.taskId());
        long beforeEvents = tableCount("agent_task_event");
        int beforeReceipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class);
        TaskDeliveryDao failing = failAfterInvocationProxy(
                TaskDeliveryDao.class, deliveryDao, "insertItem", 2);
        TaskDeliverySubmissionServiceImpl failingService = new TaskDeliverySubmissionServiceImpl(
                authorization, taskTransactions, taskDao, workItemDao, artifactDao,
                runDao, receipts, failing, eventWriter, new OutputDeliveryProperties(true));
        TaskDeliverySubmitDTO request = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Rollback formal delivery", List.of(
                        new TaskDeliveryItemDTO("rollback-summary", "1", null),
                        new TaskDeliveryItemDTO("rollback-details", "1", "evidence")));

        assertThrows(IllegalStateException.class, () -> failingService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-rollback",
                assigned.taskId(), request, "req-formal-rollback"));
        assertEquals(0, count("task_delivery", assigned.taskId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_delivery_item",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_artifact
                WHERE tenant_id=? AND client_id=? AND task_id=?
                  AND artifact_id LIKE 'delivery-manifest-%'
                """, Integer.class, TENANT, CLIENT, assigned.taskId()));
        assertEquals("running", jdbc.queryForObject("""
                SELECT status FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));
        assertEquals(OutputConstants.RUN_ACTIVE, jdbc.queryForObject("""
                SELECT state FROM output_run_binding
                WHERE tenant_id=? AND client_id=? AND run_id=?
                """, String.class, TENANT, CLIENT, assigned.runId()));
        assertEquals(beforeEvents, tableCount("agent_task_event"));
        assertEquals(beforeReceipts, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class));
    }

    @Test
    void failureAfterReceiptInsertRollsBackEntireFormalSubmission() throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "text");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        String leaseToken = field(mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-receipt-claim", "req-receipt-claim"), "leaseToken");
        mutate(ticket, assigned, "start", request(assigned.runId(), "1", leaseToken, null),
                "idempotency-receipt-start", "req-receipt-start");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-receipt-publish",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Receipt rollback conclusion", "summary",
                        "A failed receipt write must roll back every earlier submission mutation.",
                        null, "receipt-summary", "1", assigned.workItemId(), "private", false));
        long taskVersion = jdbc.queryForObject("SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", Long.class,
                TENANT, CLIENT, assigned.taskId());
        long beforeEvents = tableCount("agent_task_event");
        int beforeReceipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class);
        OutputUploadDao failingReceipts = failAfterSubmitReceiptInsertDao();
        TaskDeliverySubmissionServiceImpl failingService = new TaskDeliverySubmissionServiceImpl(
                authorization, taskTransactions, taskDao, workItemDao, artifactDao,
                runDao, failingReceipts, deliveryDao, eventWriter,
                new OutputDeliveryProperties(true));
        TaskDeliverySubmitDTO request = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Receipt rollback formal delivery", List.of(
                        new TaskDeliveryItemDTO("receipt-summary", "1", "conclusion")));

        assertThrows(IllegalStateException.class, () -> failingService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-formal-receipt-rollback",
                assigned.taskId(), request, "req-formal-receipt-rollback"));
        assertEquals(0, count("task_delivery", assigned.taskId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_delivery_item",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_artifact
                WHERE tenant_id=? AND client_id=? AND task_id=?
                  AND artifact_id LIKE 'delivery-manifest-%'
                """, Integer.class, TENANT, CLIENT, assigned.taskId()));
        assertEquals("running", jdbc.queryForObject("""
                SELECT status FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, assigned.workItemId()));
        assertEquals("assigned", jdbc.queryForObject("""
                SELECT reward_status FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, String.class, TENANT, CLIENT, assigned.taskId()));
        assertEquals(OutputConstants.RUN_ACTIVE, jdbc.queryForObject("""
                SELECT state FROM output_run_binding
                WHERE tenant_id=? AND client_id=? AND run_id=?
                """, String.class, TENANT, CLIENT, assigned.runId()));
        assertEquals(beforeEvents, tableCount("agent_task_event"));
        assertEquals(beforeReceipts, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class));
    }

    @Test
    void submissionRejectsExpiredOrOldRunLeaseAndNonExactArtifactAuthority()
            throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask assigned = createAndAssignPolicy1(AGENT_A, "text");
        Ticket ticket = ticket(assigned, AGENT_A, "runtime-a");
        String leaseToken = field(mutate(ticket, assigned, "claim",
                request(assigned.runId(), "0", null, "120000"),
                "idempotency-negative-claim", "req-negative-claim"), "leaseToken");
        mutate(ticket, assigned, "start", request(assigned.runId(), "1", leaseToken, null),
                "idempotency-negative-start", "req-negative-start");
        outputDeliveryService.publish(ticket.bearer(), "idempotency-negative-publish",
                OutputConstants.SOURCE_TASK, assigned.taskId(), new OutputPublishDTO(
                        assigned.runId(), "0", "Negative-path conclusion", "summary",
                        "Only this exact producer, run and immutable version may be submitted.",
                        null, "negative-summary", "1", assigned.workItemId(), "private", false));
        long taskVersion = jdbc.queryForObject("SELECT task_version FROM agent_task_meta "
                + "WHERE tenant_id=? AND client_id=? AND task_id=?", Long.class,
                TENANT, CLIENT, assigned.taskId());

        TaskDeliverySubmitDTO wrongVersion = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Wrong immutable version", List.of(
                        new TaskDeliveryItemDTO("negative-summary", "2", null)));
        TaskDeliveryHttpResult missing = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-negative-version",
                assigned.taskId(), wrongVersion, "req-negative-version");
        assertEquals(404, missing.status());
        assertEquals("OUTPUT_ARTIFACT_NOT_FOUND",
                JsonUtil.getMapper().readTree(missing.responseJson()).path("code").asText());

        TaskDeliverySubmitDTO exact = new TaskDeliverySubmitDTO(
                assigned.runId(), assigned.workItemId(), Long.toString(taskVersion), "2",
                leaseToken, "Exact authority", List.of(
                        new TaskDeliveryItemDTO("negative-summary", "1", null)));
        jdbc.update("""
                UPDATE agent_task_artifact SET producer_agent_id=?
                WHERE tenant_id=? AND client_id=? AND task_id=? AND artifact_id=?
                """, AGENT_B, TENANT, CLIENT, assigned.taskId(), "negative-summary");
        TaskDeliveryHttpResult wrongProducer = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-negative-producer",
                assigned.taskId(), exact, "req-negative-producer");
        assertEquals(409, wrongProducer.status());
        assertEquals("OUTPUT_ARTIFACT_SCOPE_CONFLICT", JsonUtil.getMapper()
                .readTree(wrongProducer.responseJson()).path("code").asText());
        jdbc.update("""
                UPDATE agent_task_artifact SET producer_agent_id=?
                WHERE tenant_id=? AND client_id=? AND task_id=? AND artifact_id=?
                """, AGENT_A, TENANT, CLIENT, assigned.taskId(), "negative-summary");

        long leaseUntil = jdbc.queryForObject("""
                SELECT lease_until FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, Long.class, TENANT, CLIENT, assigned.workItemId());
        jdbc.update("""
                UPDATE agent_task_work_item SET lease_until=0
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, TENANT, CLIENT, assigned.workItemId());
        TaskDeliveryHttpResult expired = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-negative-expired",
                assigned.taskId(), exact, "req-negative-expired");
        assertEquals(409, expired.status());
        assertEquals("OUTPUT_DELIVERY_LEASE_CONFLICT", JsonUtil.getMapper()
                .readTree(expired.responseJson()).path("code").asText());
        jdbc.update("""
                UPDATE agent_task_work_item SET lease_until=?
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, leaseUntil, TENANT, CLIENT, assigned.workItemId());

        String oldRun = "f".repeat(32);
        jdbc.update("""
                UPDATE agent_task_work_item SET execution_run_id=?
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, oldRun, TENANT, CLIENT, assigned.workItemId());
        TaskDeliveryHttpResult staleRun = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-negative-old-run",
                assigned.taskId(), exact, "req-negative-old-run");
        assertEquals(409, staleRun.status());
        assertEquals("OUTPUT_DELIVERY_LEASE_CONFLICT", JsonUtil.getMapper()
                .readTree(staleRun.responseJson()).path("code").asText());
        jdbc.update("""
                UPDATE agent_task_work_item SET execution_run_id=?
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, assigned.runId(), TENANT, CLIENT, assigned.workItemId());

        TaskDeliveryHttpResult submitted = submissionService.submit(
                ticket.projected(), ticket.bearer(), "idempotency-negative-final",
                assigned.taskId(), exact, "req-negative-final");
        assertEquals(200, submitted.status());
        assertEquals(1, count("task_delivery", assigned.taskId()));
    }

    @Test
    void missingDeliveryCapabilityFailsClosedWhilePolicy0StillAssigns() {
        seedAgent(7L, AGENT_A, "runtime-a", r1Capabilities());
        AgentTaskDTO policy1 = createTask(true);
        AgentTaskAssignDTO assign = new AgentTaskAssignDTO();
        assign.setAgentId(AGENT_A);

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> agentService.assignTask(policy1.getId(), assign));
        assertEquals("Assigned Agent lacks a fresh delivery runtime capability",
                missing.getMessage());
        assertEquals(0, count("agent_task_member", policy1.getId()));
        assertEquals(0, count("agent_task_work_item", policy1.getId()));
        assertEquals(0, count("output_run_binding", policy1.getId()));

        jdbc.update("UPDATE agent_runtime SET output_capabilities_updated_at=? WHERE agent_id=?",
                System.currentTimeMillis() - OutputConstants.CAPABILITY_FRESHNESS_MILLIS - 1,
                AGENT_A);
        assertThrows(IllegalArgumentException.class,
                () -> agentService.assignTask(policy1.getId(), assign));
        assertEquals(0, count("agent_task_member", policy1.getId()));

        AgentTaskDTO policy0 = createTask(false);
        AgentTaskDTO assignedPolicy0 = agentService.assignTask(policy0.getId(), assign);
        assertEquals("0", assignedPolicy0.getDeliveryPolicyVersion());
        assertEquals(1, count("agent_task_member", policy0.getId()));
        assertEquals(1, count("agent_task_work_item", policy0.getId()));
    }

    @Test
    void crossRunAndOldRuntimeTicketsCannotMutateAndLegacyDirectPathsStayReadOnly()
            throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask first = createAndAssignPolicy1(AGENT_A);
        AssignedTask second = createAndAssignPolicy1(AGENT_A);
        Ticket ticketA = ticket(first, AGENT_A, "runtime-a");
        Ticket ticketB = ticket(second, AGENT_A, "runtime-a");

        assertThrows(OutputAuthorizationException.class, () -> leaseService(receipts).mutate(
                ticketA.projected(), ticketA.bearer(), "idempotency-cross-body-01",
                first.taskId(), first.workItemId(), "claim",
                request(second.runId(), "0", null, "120000"), "req-cross-body"));
        assertThrows(OutputAuthorizationException.class, () -> leaseService(receipts).mutate(
                ticketA.projected(), ticketB.bearer(), "idempotency-cross-ticket-1",
                first.taskId(), first.workItemId(), "claim",
                request(first.runId(), "0", null, "120000"), "req-cross-ticket"));
        assertEquals(0L, workVersion(first.workItemId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",
                Integer.class));

        long eventCount = tableCount("agent_task_event");
        long eventVersion = jdbc.queryForObject("""
                SELECT current_event_version FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, first.taskId());
        AgentTaskCollaborationException legacyDenied = assertThrows(
                AgentTaskCollaborationException.class,
                () -> legacy.reportResolved(TENANT, CLIENT, first.taskId(), AGENT_A,
                        "running", null));
        assertEquals(AgentTaskCollaborationException.Reason.RESERVED_FOR_LEASE_PROTOCOL,
                legacyDenied.getReason());
        AgentTaskStateTransitionDTO direct = new AgentTaskStateTransitionDTO();
        direct.setTargetStatus("claimed");
        direct.setExpectedVersion(0L);
        AgentTaskStateException directDenied = assertThrows(AgentTaskStateException.class,
                () -> stateService.transitionWorkItem(
                        TENANT, CLIENT, first.workItemId(), direct));
        assertEquals(AgentTaskStateException.Reason.RESERVED_FOR_CLAIM_PROTOCOL,
                directDenied.getReason());
        assertEquals(eventCount, tableCount("agent_task_event"));
        assertEquals(eventVersion, jdbc.queryForObject("""
                SELECT current_event_version FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, first.taskId()));
        assertEquals(0L, workVersion(first.workItemId()));

        jdbc.update("""
                UPDATE agent_runtime SET output_capabilities_runtime_id='runtime-new',
                    output_capabilities_json=CAST(? AS JSON),output_capabilities_updated_at=?
                WHERE agent_id=?
                """, JsonUtil.toJson(deliveryCapabilities()), System.currentTimeMillis(), AGENT_A);
        assertThrows(OutputAuthorizationException.class, () -> leaseService(receipts).mutate(
                ticketA.projected(), ticketA.bearer(), "idempotency-old-runtime-1",
                first.taskId(), first.workItemId(), "claim",
                request(first.runId(), "0", null, "120000"), "req-old-runtime"));
        assertEquals(0L, workVersion(first.workItemId()));
    }

    @Test
    void policy1AndRunBackedArtifactsCannotUseLegacyOutcomeTransaction() {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        AssignedTask policy1 = createAndAssignPolicy1(AGENT_A);
        insertOutcomeArtifact(policy1.taskId(), policy1.workItemId(),
                "artifact-policy1", policy1.runId());
        long eventsBeforePolicy1 = taskEventCount(policy1.taskId());

        AgentTaskCollaborationException policy1Denied = assertThrows(
                AgentTaskCollaborationException.class,
                () -> artifactOutcomeService.accept(
                        TENANT, CLIENT, policy1.taskId(), AGENT_A,
                        outcomeCommand("decision-policy1", "artifact-policy1")));

        assertEquals(AgentTaskCollaborationException.Reason.RESERVED_FOR_LEASE_PROTOCOL,
                policy1Denied.getReason());
        assertEquals(0, outcomeCount(policy1.taskId()));
        assertEquals(eventsBeforePolicy1, taskEventCount(policy1.taskId()));

        AgentTaskDTO policy0 = createTask(false);
        AgentTaskAssignDTO assign = new AgentTaskAssignDTO();
        assign.setAgentId(AGENT_A);
        AgentTaskDTO assignedPolicy0 = agentService.assignTask(policy0.getId(), assign);
        AgentTaskWorkItemEntity policy0Item = workItemDao.listByTask(
                TENANT, CLIENT, policy0.getId(), null, 2).getFirst();
        assertEquals("0", assignedPolicy0.getDeliveryPolicyVersion());
        insertOutcomeArtifact(policy0.getId(), policy0Item.getWorkItemId(),
                "artifact-run-backed", "fedcba9876543210fedcba9876543210");
        long eventsBeforeRunBacked = taskEventCount(policy0.getId());

        AgentTaskCollaborationException runBackedDenied = assertThrows(
                AgentTaskCollaborationException.class,
                () -> artifactOutcomeService.accept(
                        TENANT, CLIENT, policy0.getId(), AGENT_A,
                        outcomeCommand("decision-run-backed", "artifact-run-backed")));

        assertEquals(AgentTaskCollaborationException.Reason.RESERVED_FOR_LEASE_PROTOCOL,
                runBackedDenied.getReason());
        assertEquals(0, outcomeCount(policy0.getId()));
        assertEquals(eventsBeforeRunBacked, taskEventCount(policy0.getId()));
    }

    @Test
    void competingAssignmentAndClaimHaveOneWinnerAndInjectedFailuresRollbackEverything()
            throws Exception {
        seedAgent(7L, AGENT_A, "runtime-a", deliveryCapabilities());
        seedAgent(8L, AGENT_B, "runtime-b", deliveryCapabilities());
        AgentTaskDTO task = createTask(true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Object>> assignments = new ArrayList<>();
        for (String agentId : List.of(AGENT_A, AGENT_B)) {
            assignments.add(pool.submit(capture(() -> {
                authenticate();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                AgentTaskAssignDTO request = new AgentTaskAssignDTO();
                request.setAgentId(agentId);
                return agentService.assignTask(task.getId(), request);
            })));
        }
        start.countDown();
        List<Object> assignmentResults = List.of(
                assignments.get(0).get(30, TimeUnit.SECONDS),
                assignments.get(1).get(30, TimeUnit.SECONDS));
        assertEquals(1, assignmentResults.stream().filter(AgentTaskDTO.class::isInstance).count());
        assertEquals(1, count("agent_task_member", task.getId()));
        assertEquals(1, count("agent_task_work_item", task.getId()));
        assertEquals(1, count("output_run_binding", task.getId()));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT w.work_item_id,w.assignee_agent_id,w.dispatched_run_id,
                       r.original_runtime_id
                FROM agent_task_work_item w JOIN output_run_binding r
                  ON r.tenant_id=w.tenant_id AND r.client_id=w.client_id
                 AND r.run_id=w.dispatched_run_id
                WHERE w.tenant_id=? AND w.client_id=? AND w.task_id=?
                """, TENANT, CLIENT, task.getId());
        AssignedTask assigned = new AssignedTask(task.getId(),
                dbString(row.get("work_item_id")), dbString(row.get("dispatched_run_id")));
        String winnerAgent = dbString(row.get("assignee_agent_id"));
        String runtimeId = dbString(row.get("original_runtime_id"));
        Ticket ticket = ticket(assigned, winnerAgent, runtimeId);

        CountDownLatch claimStart = new CountDownLatch(1);
        List<Future<OutputLeaseHttpResult>> claims = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            int attempt = index;
            claims.add(pool.submit(() -> {
                assertTrue(claimStart.await(10, TimeUnit.SECONDS));
                return leaseService(receipts).mutate(ticket.projected(), ticket.bearer(),
                        "idempotency-race-claim-" + attempt,
                        assigned.taskId(), assigned.workItemId(), "claim",
                        request(assigned.runId(), "0", null, "120000"),
                        "req-race-claim-" + attempt);
            }));
        }
        claimStart.countDown();
        List<OutputLeaseHttpResult> claimResults = List.of(
                claims.get(0).get(30, TimeUnit.SECONDS),
                claims.get(1).get(30, TimeUnit.SECONDS));
        assertEquals(1, claimResults.stream().filter(result -> result.status() == 200).count());
        assertEquals(1, claimResults.stream().filter(result -> result.status() == 409).count());
        assertEquals(1L, workVersion(assigned.workItemId()));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",
                Integer.class));
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        AssignedTask rollbackTask = createAndAssignPolicy1(winnerAgent);
        Ticket rollbackTicket = ticket(rollbackTask, winnerAgent, runtimeId);
        long baselineEvents = tableCount("agent_task_event");
        OutputUploadDao failingReceipt = delegatingProxy(OutputUploadDao.class, receipts,
                "insertReceipt", true);
        assertThrows(IllegalStateException.class, () -> leaseService(failingReceipt).mutate(
                rollbackTicket.projected(), rollbackTicket.bearer(),
                "idempotency-fail-receipt-1", rollbackTask.taskId(),
                rollbackTask.workItemId(), "claim",
                request(rollbackTask.runId(), "0", null, "120000"),
                "req-fail-receipt"));
        assertEquals(0L, workVersion(rollbackTask.workItemId()));
        assertEquals(baselineEvents, tableCount("agent_task_event"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",
                Integer.class));

        AgentTaskWorkItemDao failingMutation = delegatingProxy(
                AgentTaskWorkItemDao.class, workItemDao, "claimReadyByVersion", true);
        AgentWorkItemLeaseService failingLeaseCore = new AgentWorkItemLeaseServiceImpl(
                memberDao, failingMutation, taskTransactions, eventWriter,
                System::currentTimeMillis, () -> "lease-failing-mutation", 900_000L);
        OutputLeaseServiceImpl failingLease = new OutputLeaseServiceImpl(
                authorization, taskTransactions, failingLeaseCore, receipts);
        assertThrows(IllegalStateException.class, () -> failingLease.mutate(
                rollbackTicket.projected(), rollbackTicket.bearer(),
                "idempotency-fail-mutation-1", rollbackTask.taskId(),
                rollbackTask.workItemId(), "claim",
                request(rollbackTask.runId(), "0", null, "120000"),
                "req-fail-mutation"));
        assertEquals(0L, workVersion(rollbackTask.workItemId()));
        assertEquals(baselineEvents, tableCount("agent_task_event"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM output_mutation_receipt",
                Integer.class));

        AssignedTask releaseRollbackTask = createAndAssignPolicy1(winnerAgent);
        Ticket releaseRollbackTicket = ticket(releaseRollbackTask, winnerAgent, runtimeId);
        OutputLeaseHttpResult rollbackClaim = leaseService(receipts).mutate(
                releaseRollbackTicket.projected(), releaseRollbackTicket.bearer(),
                "idempotency-release-rollback-claim", releaseRollbackTask.taskId(),
                releaseRollbackTask.workItemId(), "claim",
                request(releaseRollbackTask.runId(), "0", null, "120000"),
                "req-release-rollback-claim");
        String rollbackLeaseToken = field(rollbackClaim, "leaseToken");
        long beforeReleaseVersion = workVersion(releaseRollbackTask.workItemId());
        long beforeReleaseEvents = tableCount("agent_task_event");
        int beforeReleaseRuns = count("output_run_binding", releaseRollbackTask.taskId());
        int beforeReleaseDeliveries = scopedTransportCount(
                "agent_command_delivery", releaseRollbackTask.taskId());
        int beforeReleaseOutbox = scopedTransportCount(
                "agent_outbox_event", releaseRollbackTask.taskId());
        int beforeReleaseReceipts = jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class);
        OutputUploadDao failingReleaseReceipt = delegatingProxy(
                OutputUploadDao.class, receipts, "insertReceipt", true);
        assertThrows(IllegalStateException.class, () -> leaseService(failingReleaseReceipt).mutate(
                releaseRollbackTicket.projected(), releaseRollbackTicket.bearer(),
                "idempotency-release-rollback", releaseRollbackTask.taskId(),
                releaseRollbackTask.workItemId(), "release",
                request(releaseRollbackTask.runId(), Long.toString(beforeReleaseVersion),
                        rollbackLeaseToken, null),
                "req-release-rollback"));
        assertEquals(beforeReleaseVersion, workVersion(releaseRollbackTask.workItemId()));
        assertEquals(beforeReleaseEvents, tableCount("agent_task_event"));
        assertEquals(beforeReleaseRuns,
                count("output_run_binding", releaseRollbackTask.taskId()));
        assertEquals(beforeReleaseDeliveries, scopedTransportCount(
                "agent_command_delivery", releaseRollbackTask.taskId()));
        assertEquals(beforeReleaseOutbox, scopedTransportCount(
                "agent_outbox_event", releaseRollbackTask.taskId()));
        assertEquals(beforeReleaseReceipts, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_mutation_receipt", Integer.class));
    }

    private void wireServices() throws Exception {
        SqlSessionTemplate template = new SqlSessionTemplate(sqlSessionFactory(dataSource));
        taskDao = wire(new AgentTaskMetaDaoImpl(), template.getMapper(AgentTaskMetaMapper.class));
        memberDao = new AgentTaskMemberDaoImpl(template.getMapper(AgentTaskMemberMapper.class));
        workItemDao = new AgentTaskWorkItemDaoImpl(template.getMapper(AgentTaskWorkItemMapper.class));
        runtimeDao = wire(new AgentRuntimeDaoImpl(), template.getMapper(AgentRuntimeMapper.class));
        AgentTaskEventDao eventDao = wire(
                new AgentTaskEventDaoImpl(), template.getMapper(AgentTaskEventMapper.class));
        AgentIdentityRegistryDao registryDao = wire(
                new AgentIdentityRegistryDaoImpl(),
                template.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(
                new AgentIdentityAliasDaoImpl(), template.getMapper(AgentIdentityAliasMapper.class));
        AgentPersonaBindingDao bindingDao = wire(
                new AgentPersonaBindingDaoImpl(),
                template.getMapper(AgentPersonaBindingMapper.class));
        identityService = transactionalInterfaceProxy(
                new AgentIdentityServiceImpl(registryDao, aliasDao, bindingDao),
                AgentIdentityService.class);
        taskTransactions = new AgentTaskMutationTransactionImpl(taskDao, transactionManager);
        eventWriter = new AgentTaskEventWriterImpl(eventDao, transactionManager,
                new AgentTaskEventAfterCommitPublisher(
                        new AgentTaskEventBroker(), transactionManager));
        AgentTaskAggregationService aggregation = transactionalInterfaceProxy(
                new AgentTaskAggregationServiceImpl(
                        taskDao, identityService, taskTransactions, eventWriter,
                        new AgentTaskAggregationCalculator(), System::currentTimeMillis),
                AgentTaskAggregationService.class);
        legacy = transactionalClassProxy(new AgentLegacyTaskCompatibilityService(
                taskDao, memberDao, workItemDao, aggregation, identityService,
                taskTransactions, eventWriter, System::currentTimeMillis));
        stateService = transactionalInterfaceProxy(new AgentTaskStateServiceImpl(
                taskDao, memberDao, workItemDao, taskTransactions, eventWriter,
                System::currentTimeMillis), AgentTaskStateService.class);
        artifactDao = new AgentTaskArtifactDaoImpl(
                template.getMapper(AgentTaskArtifactMapper.class));
        AgentTaskArtifactOutcomeDao artifactOutcomeDao =
                new AgentTaskArtifactOutcomeDaoImpl(
                        template.getMapper(AgentTaskArtifactOutcomeMapper.class));
        artifactOutcomeService = transactionalInterfaceProxy(
                new AgentTaskArtifactOutcomeServiceImpl(
                        artifactDao, artifactOutcomeDao, memberDao, taskDao,
                        taskTransactions, eventWriter, System::currentTimeMillis),
                AgentTaskArtifactOutcomeService.class);

        OutputSourceBindingDao sourceDao = wire(new OutputSourceBindingDaoImpl(),
                template.getMapper(OutputSourceBindingMapper.class));
        runDao = wire(new OutputRunBindingDaoImpl(),
                template.getMapper(OutputRunBindingMapper.class));
        OutputAccessTicketDao ticketDao = wire(new OutputAccessTicketDaoImpl(),
                template.getMapper(OutputAccessTicketMapper.class));
        var access = new AgentTaskCollaborationAccessServiceImpl(taskDao, memberDao);
        authorization = new OutputRunAuthorizationServiceImpl(
                new OutputSourceAuthorizerRegistry(List.of(new TaskOutputSourceAuthorizer(access))),
                sourceDao, runDao, ticketDao, runtimeDao, identityService, true);
        receipts = new OutputUploadDaoImpl(jdbc);
        deliveryDao = new TaskDeliveryDaoImpl(jdbc);
        OutputDeliveryProperties outputProperties = new OutputDeliveryProperties(true);
        TaskOutputVersionProvider taskOutputProvider = new TaskOutputVersionProvider(
                taskDao, workItemDao, artifactDao,
                template.getMapper(AgentTaskOutputMapper.class), eventWriter);
        outputDeliveryService = new OutputDeliveryServiceImpl(
                authorization, receipts, mock(OutputObjectStorage.class), transactionManager,
                outputProperties, List.of(taskOutputProvider));
        submissionService = new TaskDeliverySubmissionServiceImpl(
                authorization, taskTransactions, taskDao, workItemDao, artifactDao,
                runDao, receipts, deliveryDao, eventWriter, outputProperties);

        AtomicLong commandIds = new AtomicLong();
        commandWriter = new AgentCommandTransportWriterImpl(
                new AgentCommandTransportDaoImpl(
                        template.getMapper(AgentCommandTransportMapper.class)),
                dispatchGate(), transactionManager,
                () -> new UUID(0L, commandIds.incrementAndGet()));
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentCommandTransportWriter> writerProvider = mock(ObjectProvider.class);
        when(writerProvider.getIfAvailable()).thenReturn(commandWriter);
        @SuppressWarnings("unchecked")
        ObjectProvider<OutputRunAuthorizationService> outputProvider = mock(ObjectProvider.class);
        when(outputProvider.getIfAvailable()).thenReturn(authorization);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentTaskWorkItemDao> workItemProvider = mock(ObjectProvider.class);
        when(workItemProvider.getIfAvailable()).thenReturn(workItemDao);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentRabbitTopologyReadiness> topologyProvider = mock(ObjectProvider.class);
        AgentRabbitTopologyReadiness topologyReadiness = mock(AgentRabbitTopologyReadiness.class);
        when(topologyReadiness.snapshot()).thenReturn(new AgentRabbitTopologyReadiness.Snapshot(
                AgentRabbitTopologyReadiness.Status.READY,
                AgentRabbitTopologyReadiness.Source.PROVISION,
                AgentRabbitTopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                AgentRabbitTopologyManifest.CANONICAL_SHA256, 1L, null));
        when(topologyProvider.getIfAvailable()).thenReturn(topologyReadiness);
        commandCapture = new AgentCommandTransportCapture(
                dispatchGate(), writerProvider, outputProvider);
        commandCapture.configureWorkItemDao(workItemProvider);
        commandCapture.configureTopologyReadiness(topologyProvider);

        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        AgentServiceImpl rawAgentService = new AgentServiceImpl(
                runtimeDao, identityService,
                mock(cn.jia.agent.dao.AgentPersonaDao.class), bindingDao,
                taskDao, memberDao, legacy,
                mock(cn.jia.agent.dao.AgentTaskNoteDao.class),
                mock(cn.jia.agent.dao.DialogueTemplateDao.class),
                empty.getBeanProvider(cn.jia.agent.event.AgentEventPublisher.class),
                empty.getBeanProvider(TaskService.class),
                empty.getBeanProvider(ApiKeyService.class),
                empty.getBeanProvider(cn.jia.agent.service.AgentSceneService.class),
                new cn.jia.agent.service.AgentScopePublicationCoordinator(),
                new AgentSceneFeatureFlags(false, false), taskTransactions, eventWriter,
                commandCapture);
        ReflectionTestUtils.setField(rawAgentService, "outputDeliveryEnabled", true);
        ReflectionTestUtils.setField(rawAgentService, "deliveryPolicy1Enabled", true);
        ReflectionTestUtils.setField(rawAgentService, "deliveryPolicy1OwnerAllowlist", TENANT);
        ReflectionTestUtils.setField(rawAgentService, "deliveryPolicy1ClientAllowlist", CLIENT);
        agentService = transactionalClassProxy(rawAgentService);
        leaseTokens = new AtomicInteger();
    }

    private AssignedTask createAndAssignPolicy1(String agentId) {
        return createAndAssignPolicy1(agentId, "files");
    }

    private AssignedTask createAndAssignPolicy1(String agentId, String mode) {
        AgentTaskDTO created = createTask(true, mode);
        AgentTaskAssignDTO request = new AgentTaskAssignDTO();
        request.setAgentId(agentId);
        AgentTaskDTO assigned = agentService.assignTask(created.getId(), request);
        assertEquals("1", assigned.getDeliveryPolicyVersion());
        AgentTaskWorkItemEntity item = workItemDao.listByTask(
                TENANT, CLIENT, created.getId(), null, 2).getFirst();
        assertEquals("ready", item.getStatus());
        assertEquals(0L, item.getVersion());
        assertNotNull(item.getDispatchedRunId());
        assertNull(item.getExecutionRunId());
        return new AssignedTask(created.getId(), item.getWorkItemId(), item.getDispatchedRunId());
    }

    private AgentTaskDTO createTask(boolean policy1) {
        return createTask(policy1, "files");
    }

    private AgentTaskDTO createTask(boolean policy1, String mode) {
        authenticate();
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle(policy1 ? "Policy 1 output" : "Policy 0 legacy");
        request.setRequiredAbilities(List.of("planning"));
        if (policy1) {
            AgentTaskDeliveryRequirementsDTO delivery = new AgentTaskDeliveryRequirementsDTO();
            delivery.setMode(mode);
            delivery.setMinFiles("text".equals(mode) ? 0 : 1);
            delivery.setRequiredNames("text".equals(mode) ? List.of() : List.of("result.zip"));
            request.setDeliveryRequirements(delivery);
        }
        return agentService.createTask(request);
    }

    private Ticket ticket(AssignedTask assigned, String agentId, String runtimeId) {
        OutputAuthReceiptDTO issued = transaction.execute(status -> authorization.issueTicket(
                TENANT, CLIENT, agentId, runtimeId,
                "ticket-" + UUID.randomUUID(), assigned.runId()));
        assertNotNull(issued);
        assertTrue(issued.operations().contains(OutputConstants.OP_LEASE));
        OutputTicketAuthorization projected = transaction.execute(status ->
                authorization.authorizeTicket(
                        issued.token(), OutputConstants.OP_LEASE, false));
        return new Ticket("Bearer " + issued.token(), projected);
    }

    private OutputLeaseHttpResult mutate(
            Ticket ticket, AssignedTask assigned, String action, OutputLeaseRequestDTO request,
            String key, String requestId) {
        return leaseService(receipts).mutate(ticket.projected(), ticket.bearer(), key,
                assigned.taskId(), assigned.workItemId(), action, request, requestId);
    }

    private OutputLeaseServiceImpl leaseService(OutputUploadDao receiptDao) {
        AgentWorkItemLeaseService core = leaseCore();
        return new OutputLeaseServiceImpl(authorization, taskTransactions, core, receiptDao);
    }

    private AgentWorkItemLeaseServiceImpl leaseCore() {
        AgentWorkItemLeaseServiceImpl core = new AgentWorkItemLeaseServiceImpl(
                memberDao, workItemDao, taskTransactions, eventWriter,
                System::currentTimeMillis,
                () -> "lease-token-" + leaseTokens.incrementAndGet(), 900_000L);
        core.setPolicy1Dispatcher(commandCapture);
        return core;
    }

    private OutputLeaseRequestDTO request(
            String runId, String version, String token, String duration) {
        return new OutputLeaseRequestDTO(runId, version, token, duration);
    }

    private String field(OutputLeaseHttpResult result, String name) throws Exception {
        JsonNode node = json(result).path("data").get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    private String code(OutputLeaseHttpResult result) throws Exception {
        return json(result).path("code").asText();
    }

    private JsonNode json(OutputLeaseHttpResult result) throws Exception {
        return JsonUtil.getMapper().readTree(result.responseJson());
    }

    private void seedAgent(long bindingId, String agentId, String runtimeId,
            List<String> capabilities) {
        long now = System.currentTimeMillis();
        String personaCode = "test" + bindingId;
        jdbc.update("""
                INSERT INTO agent_persona_binding(
                    id,jiacn,persona_code,agent_id,bound_at,status,
                    create_time,update_time,tenant_id,client_id)
                VALUES (?,?,?,?,?,1,?,?,?,?)
                """, bindingId, TENANT, personaCode, agentId,
                now, now, now, TENANT, CLIENT);
        jdbc.update("""
                INSERT INTO agent_identity_registry(
                    id,canonical_agent_id,canonical_type,lifecycle_status,
                    client_id,owner_jiacn,tenant_id,binding_id,
                    provisioned_at,activated_at,audit_reason,create_time,update_time)
                VALUES (?,?,'OPAQUE','ACTIVE',?,?,?,?,?,?,?, ?,?)
                """, bindingId, agentId, CLIENT, TENANT, TENANT, bindingId,
                now, now, "OD07 MySQL integration", now, now);
        jdbc.update("""
                INSERT INTO agent_runtime(
                    agent_id,name,owner_jiacn,persona_code,persona_name,binding_id,
                    abilities,endpoint,token_hash,status,last_seen_at,
                    create_time,update_time,tenant_id,client_id,
                    output_capabilities_json,output_capabilities_runtime_id,
                    output_capabilities_updated_at)
                VALUES (?,?,?,?,'Test',?,?,'wss://agent',?,'online',?,?,?,?,?,
                        CAST(? AS JSON),?,?)
                """, agentId, agentId, TENANT, personaCode, bindingId, "[\"planning\"]",
                "registration-" + bindingId, now, now, now, TENANT, CLIENT,
                JsonUtil.toJson(capabilities), runtimeId, now);
    }

    private List<String> deliveryCapabilities() {
        return List.of(OutputConstants.CAPABILITY_HTTP_V1,
                OutputConstants.CAPABILITY_OWNER_SHARE_V1,
                OutputConstants.CAPABILITY_DELIVERY_HTTP_V1);
    }

    private List<String> r1Capabilities() {
        return List.of(OutputConstants.CAPABILITY_HTTP_V1,
                OutputConstants.CAPABILITY_OWNER_SHARE_V1);
    }

    private long workVersion(String workItemId) {
        return jdbc.queryForObject("""
                SELECT version FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, Long.class, TENANT, CLIENT, workItemId);
    }

    private String dbString(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return (String) value;
    }

    private int count(String table, String taskId) {
        if (!List.of("agent_task_member", "agent_task_work_item", "output_run_binding",
                "task_delivery")
                .contains(table)) throw new IllegalArgumentException("unsupported table");
        String key = "output_run_binding".equals(table) ? "source_id" : "task_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE tenant_id=? AND client_id=? AND " + key + "=?",
                Integer.class, TENANT, CLIENT, taskId);
    }

    private long tableCount(String table) {
        if (!"agent_task_event".equals(table)) {
            throw new IllegalArgumentException("unsupported table");
        }
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private void insertOutcomeArtifact(
            String taskId, String workItemId, String artifactId, String runId) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO agent_task_artifact(
                    artifact_id,task_id,work_item_id,producer_agent_id,artifact_type,title,
                    object_id,run_id,file_name,content_byte_length,mime_type,retain_until,
                    content_hash,artifact_version,visibility,created_at,
                    tenant_id,client_id,create_time,update_time)
                VALUES (?,?,?,?,'document',?, ?,?,'result.txt',4,'text/plain',?,
                        ?,1,'private',?,?,?,?,?)
                """, artifactId, taskId, workItemId, AGENT_A, artifactId,
                "object-" + artifactId, runId, now + 60_000L, "a".repeat(64), now,
                TENANT, CLIENT, now, now);
    }

    private String seedReadyObject(
            AssignedTask assigned, byte[] content, String fileName, String mime) throws Exception {
        long now = System.currentTimeMillis();
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(content);
        String objectId = "object-" + UUID.randomUUID().toString().replace("-", "");
        String uploadId = "upload-" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(1, receipts.insertObject(new OutputUploadDao.ObjectRow(
                TENANT, CLIENT, objectId, assigned.runId(), "od08-bucket",
                "task/" + assigned.taskId() + "/" + objectId, "version-1", hash,
                (long) content.length, mime, "PASSED", "READY", "od08-test-scanner",
                now, now - 1, null, 0, now - 1, null, null, null), now));
        assertEquals(1, receipts.insertUpload(new OutputUploadDao.UploadRow(
                TENANT, CLIENT, uploadId, assigned.runId(), "7", objectId, fileName,
                content.length, hash, mime, "READY", 1L, now, null, null,
                now + 60_000, 0L, true, 0, null, null, null, null), now));
        return objectId;
    }

    private OutputUploadDao blockingDeliveryPinDao(
            CountDownLatch pinInserted, CountDownLatch allowCommit) {
        return (OutputUploadDao) Proxy.newProxyInstance(
                OutputUploadDao.class.getClassLoader(), new Class<?>[]{OutputUploadDao.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(receipts, arguments);
                        if ("insertReference".equals(method.getName())
                                && arguments != null
                                && arguments[0] instanceof OutputUploadDao.ReferenceRow reference
                                && "DELIVERY_PIN".equals(reference.referenceKind())) {
                            pinInserted.countDown();
                            try {
                                if (!allowCommit.await(10, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("delivery pin test timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "delivery pin test interrupted", interrupted);
                            }
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private OutputUploadDao failAfterSubmitReceiptInsertDao() {
        return (OutputUploadDao) Proxy.newProxyInstance(
                OutputUploadDao.class.getClassLoader(), new Class<?>[]{OutputUploadDao.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(receipts, arguments);
                        if ("insertReceipt".equals(method.getName())
                                && arguments != null && arguments.length > 4
                                && "submitDelivery".equals(arguments[4])) {
                            throw new IllegalStateException(
                                    "forced failure after formal submission receipt insert");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private AgentTaskArtifactAcceptDTO outcomeCommand(
            String decisionId, String artifactId) {
        AgentTaskArtifactRefDTO ref = new AgentTaskArtifactRefDTO();
        ref.setArtifactId(artifactId);
        ref.setArtifactVersion(1);
        ref.setExpectedOutcomeVersion(0L);
        AgentTaskArtifactAcceptDTO command = new AgentTaskArtifactAcceptDTO();
        command.setDecisionId(decisionId);
        command.setAcceptedArtifact(ref);
        command.setSupersededArtifacts(List.of());
        return command;
    }

    private int outcomeCount(String taskId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_artifact_outcome
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Integer.class, TENANT, CLIENT, taskId);
    }

    private long taskEventCount(String taskId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_event
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, Long.class, TENANT, CLIENT, taskId);
    }

    private AgentRabbitSafetyGate dispatchGate() {
        AgentRabbitSafetyGate gate = new AgentRabbitSafetyGate(
                new AgentRabbitSafetyProperties(
                        new AgentRabbitSafetyProperties.CommandOutbox(true),
                        new AgentRabbitSafetyProperties.RabbitTopology(true),
                        new AgentRabbitSafetyProperties.RabbitPublish(true),
                        new AgentRabbitSafetyProperties.RabbitConsume(true),
                        new AgentRabbitSafetyProperties.RabbitDispatch(true),
                        new AgentRabbitSafetyProperties.RabbitBroker(
                                "isolated.invalid", 5673, "user", "secret", "/isolated")),
                new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(TENANT, CLIENT))));
        assertEquals(AgentRabbitActivationState.DISPATCH_CANARY, gate.state());
        return gate;
    }

    private String dispatchedRun(String workItemId) {
        return jdbc.queryForObject("""
                SELECT dispatched_run_id FROM agent_task_work_item
                WHERE tenant_id=? AND client_id=? AND work_item_id=?
                """, String.class, TENANT, CLIENT, workItemId);
    }

    private void assertRunCommandIdentity(
            String runId, String taskId, String workItemId, String expectedEventType) {
        String originId = dbString(jdbc.queryForObject("""
                SELECT origin_id FROM output_run_binding
                WHERE tenant_id=? AND client_id=? AND run_id=?
                """, Object.class, TENANT, CLIENT, runId));
        Map<String, Object> delivery = jdbc.queryForMap("""
                SELECT command_id,command_payload FROM agent_command_delivery
                WHERE tenant_id=? AND client_id=? AND task_id=? AND work_item_id=?
                  AND command_id=?
                """, TENANT, CLIENT, taskId, workItemId, originId);
        assertEquals(originId, delivery.get("command_id"));
        var draft = AgentCommandCanonicalCodec.decodeBusinessBytes(
                (byte[]) delivery.get("command_payload"));
        assertEquals(originId, draft.commandId());
        assertEquals(workItemId, draft.workItemId());
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_task_event
                WHERE tenant_id=? AND client_id=? AND task_id=?
                  AND event_id=? AND event_type=?
                """, Integer.class, TENANT, CLIENT, taskId,
                draft.causationId(), expectedEventType));
    }

    private int scopedTransportCount(String table, String taskId) {
        if ("agent_command_delivery".equals(table)) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM agent_command_delivery
                    WHERE tenant_id=? AND client_id=? AND task_id=?
                    """, Integer.class, TENANT, CLIENT, taskId);
        }
        if ("agent_outbox_event".equals(table)) {
            return jdbc.queryForObject("""
                    SELECT COUNT(*) FROM agent_outbox_event
                    WHERE tenant_id=? AND client_id=? AND aggregate_id=?
                    """, Integer.class, TENANT, CLIENT, taskId);
        }
        throw new IllegalArgumentException("unsupported transport table");
    }

    private SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMetaMapper.class);
        configuration.addMapper(AgentTaskMemberMapper.class);
        configuration.addMapper(AgentTaskWorkItemMapper.class);
        configuration.addMapper(AgentTaskEventMapper.class);
        configuration.addMapper(AgentTaskArtifactMapper.class);
        configuration.addMapper(AgentTaskArtifactOutcomeMapper.class);
        configuration.addMapper(AgentTaskOutputMapper.class);
        configuration.addMapper(AgentCommandTransportMapper.class);
        configuration.addMapper(AgentRuntimeMapper.class);
        configuration.addMapper(AgentIdentityRegistryMapper.class);
        configuration.addMapper(AgentIdentityAliasMapper.class);
        configuration.addMapper(AgentPersonaBindingMapper.class);
        configuration.addMapper(OutputSourceBindingMapper.class);
        configuration.addMapper(OutputRunBindingMapper.class);
        configuration.addMapper(OutputAccessTicketMapper.class);
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        return factory.getObject();
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalInterfaceProxy(Object target, Class<T> interfaceType) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(interfaceType);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalClassProxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private <T> T wire(T target, Object mapper) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : List.of("mapper", "baseMapper")) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, mapper);
                    return target;
                } catch (NoSuchFieldException ignored) {
                    // Continue through the DAO hierarchy.
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException("mapper/baseMapper");
    }

    @SuppressWarnings("unchecked")
    private <T> T delegatingProxy(
            Class<T> type, T delegate, String failMethod, boolean failAfterDelegate) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (failAfterDelegate && failMethod.equals(method.getName())) {
                            throw new IllegalStateException(
                                    "forced failure after " + failMethod);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private <T> T failAfterInvocationProxy(
            Class<T> type, T delegate, String failMethod, int failingInvocation) {
        AtomicInteger invocations = new AtomicInteger();
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (failMethod.equals(method.getName())
                                && invocations.incrementAndGet() == failingInvocation) {
                            throw new IllegalStateException(
                                    "forced failure after " + failMethod + " #" + failingInvocation);
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private Callable<Object> capture(Callable<?> operation) {
        return () -> {
            try {
                return operation.call();
            } catch (Throwable failure) {
                return failure;
            } finally {
                SecurityContextHolder.clearContext();
            }
        };
    }

    private void authenticate() {
        Jwt jwt = Jwt.withTokenValue("od07-policy1-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("jiacn", TENANT)
                .claim("client_id", CLIENT)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        EsContext context = new EsContext();
        context.setJiacn(TENANT);
        context.setClientId(CLIENT);
        EsContextHolder.setContext(context);
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(url);
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    private String urlForDatabase(String url, String selectedDatabase) {
        int query = url.indexOf('?');
        String head = query >= 0 ? url.substring(0, query) : url;
        String tail = query >= 0 ? url.substring(query) : "";
        int slash = head.indexOf('/', "jdbc:mysql://".length());
        if (slash < 0) return head + "/" + selectedDatabase + tail;
        return head.substring(0, slash + 1) + selectedDatabase + tail;
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private record AssignedTask(String taskId, String workItemId, String runId) {
    }

    private record Ticket(String bearer, OutputTicketAuthorization projected) {
    }
}
