package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.config.ArchiveReaderDataSchemaInitializer;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceSchemaInitializer;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveConfirmedPolicyRef;
import cn.jia.chat.archive.maintenance.model.ArchiveRequestContext;
import cn.jia.chat.archive.maintenance.model.ArchiveExecutionGrantRecord;
import cn.jia.chat.archive.maintenance.model.ArchiveRuntimeScope;
import cn.jia.chat.archive.maintenance.store.ArchiveMaintenanceStore;
import cn.jia.chat.archive.maintenance.store.JdbcArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveMySqlTestGuard;
import cn.jia.chat.archive.service.ArchiveReaderServiceImpl;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.service.SpringArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Opt-in real MySQL lock-order evidence only. It uses the existing destructive H02 guard:
 * CYF_H02_MYSQL_ISOLATED=true, an IP-literal loopback CYF_H02_MYSQL_URL on a non-3306/33060
 * port, and an exactly matching cyf_h02_* CYF_H02_MYSQL_DATABASE_CONFIRM. It does not exercise
 * a real Agent runtime, websocket transport, billing, or end-to-end HTTP.
 */
class ArchiveMaintenanceConcurrencyMySqlTest {
    private static final ArchiveActorScope ACTOR = new ArchiveActorScope("0", "client-a", "owner-a");
    private static final String COLLECTION = "platform-classics";
    private static final String AGENT = "agent-a";
    private static final String JOB = "job-a";
    private static final String RUN = "run-a";
    private static final String APPOINTMENT = "appointment-a";
    private static final String SHA = "a".repeat(64);
    private static final byte[] SOURCE = "第一回甲第二回乙".getBytes(StandardCharsets.UTF_8);

    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private ArchiveTransactions transactions;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_H02_MYSQL_ISOLATED")),
                "requires explicit isolated H02 MySQL acknowledgement");
        ArchiveMySqlTestGuard.Target target = ArchiveMySqlTestGuard.requireDisposable(System.getenv());
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(target.url());
        dataSource.setUsername(System.getenv().getOrDefault("CYF_H02_MYSQL_USER", "root"));
        dataSource.setPassword(System.getenv().getOrDefault("CYF_H02_MYSQL_PASSWORD", ""));
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transactions = new SpringArchiveTransactions(transactionManager);
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();
        jdbc.execute("CREATE TABLE aam_test_agent_root (agent_id VARCHAR(100) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_bin NOT NULL, PRIMARY KEY(agent_id)) ENGINE=InnoDB");
        assertEquals("REPEATABLE-READ", jdbc.queryForObject(
                "SELECT @@transaction_isolation", String.class));
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) clean();
    }

    @Test
    void platformAgentRootThenManagerAndEnsureExecutionCompleteWithoutReverseLockDeadlock() throws Exception {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        CountDownLatch platformHasRoot = new CountDownLatch(1);
        CountDownLatch allowPlatformManager = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> platform = pool.submit(() -> transactions.required(() -> {
                lockAgentRoot();
                platformHasRoot.countDown();
                await(allowPlatformManager);
                store.findManagerGrant(ACTOR, COLLECTION, true);
                return null;
            }));
            assertTrue(platformHasRoot.await(5, TimeUnit.SECONDS), "platform root lock was not acquired");
            Future<?> ensure = pool.submit(() -> service.ensureExecution(ACTOR, JOB, "ensure-key", 1));
            assertTrue(port.firstRootAttempted.await(5, TimeUnit.SECONDS),
                    "ensureExecution did not reach the Agent root boundary");
            allowPlatformManager.countDown();
            platform.get(10, TimeUnit.SECONDS);
            ensure.get(10, TimeUnit.SECONDS);
        } finally {
            allowPlatformManager.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("AUTHORIZED", jdbc.queryForObject(
                "SELECT state FROM archive_job_run WHERE run_id=?", String.class, RUN));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM archive_execution_grant WHERE run_id=?", String.class, RUN));
    }

    @Test
    void revokeWaitingBehindEnsureSeesAndFencesNewlyCommittedGrantUnderRepeatableRead() throws Exception {
        seedExecutionCandidate();
        CountDownLatch grantInserted = new CountDownLatch(1);
        CountDownLatch allowEnsureCommit = new CountDownLatch(1);
        BlockingGrantStore store = new BlockingGrantStore(jdbc, grantInserted, allowEnsureCommit);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ensure = pool.submit(() -> service.ensureExecution(ACTOR, JOB, "ensure-key", 1));
            assertTrue(grantInserted.await(5, TimeUnit.SECONDS), "ensure did not insert its grant");
            Future<?> revoke = pool.submit(() -> service.revokeManagerAuthorization(ACTOR, COLLECTION,
                    "revoke-key", 3, new ArchiveManagerRevokeRequest("configuration removed")));
            assertTrue(port.secondRootAttempted.await(5, TimeUnit.SECONDS),
                    "revoke did not queue behind the persisted Agent root");
            allowEnsureCommit.countDown();
            ensure.get(10, TimeUnit.SECONDS);
            revoke.get(10, TimeUnit.SECONDS);
        } finally {
            allowEnsureCommit.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("FENCED", jdbc.queryForObject(
                "SELECT state FROM archive_job_run WHERE run_id=?", String.class, RUN));
        assertEquals("FENCED", jdbc.queryForObject(
                "SELECT state FROM archive_execution_grant WHERE run_id=?", String.class, RUN));
        assertEquals("REVOKED:4", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', revision) FROM archive_collection_manager "
                        + "WHERE collection_id=? AND tenant_id='0' AND client_id='client-a' "
                        + "AND owner_jiacn='owner-a'",
                String.class, COLLECTION));
    }

    @Test
    void nativeLifecyclePersistsStartFailureReplayAndLateTerminalReceipt() {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        service.ensureExecution(ACTOR, JOB, "ensure-lifecycle", 1);

        ArchiveRuntimeResultDTO started = service.runtimeStart(runtime(), JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));
        ArchiveRuntimeResultDTO replay = service.runtimeStart(runtime(), JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));
        assertEquals("RUNNING", started.runState());
        assertEquals(started.runRevision(), replay.runRevision());
        assertEquals("message-a:RUNNING", jdbc.queryForObject(
                "SELECT CONCAT(started_message_id, ':', state) FROM archive_job_run WHERE run_id=?",
                String.class, RUN));
        assertEquals(1, eventCount("EXECUTION_STARTED"));

        port.expired = true;
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeUpdateDraft(runtime(), JOB, RUN, "expired-write", 0,
                        new ArchiveDraftUpdateRequest(List.of(), List.of()))).code());
        port.expired = false;

        ArchiveRuntimeFailureRequest failure = new ArchiveRuntimeFailureRequest(
                "RUNNER", "RUNNER_CRASH", true);
        ArchiveRuntimeResultDTO failed = service.runtimeFailure(runtime(), JOB, RUN, failure);
        assertEquals("FAILED", failed.runState());
        assertEquals("FAILED:READ_ONLY:FAILED", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertEquals(1, eventCount("JOB_FAILED"));

        port.expired = true;
        assertEquals("FAILED", service.runtimeFailure(runtime(), JOB, RUN, failure).runState());
        assertEquals("FAILED", service.runtimeResult(runtime(), JOB, RUN).runState());
        assertEquals(1, eventCount("JOB_FAILED"));

        service.revokeManagerAuthorization(ACTOR, COLLECTION, "revoke-read-only", 3,
                new ArchiveManagerRevokeRequest("configuration removed"));
        assertEquals("FENCED:FENCED", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
    }

    @Test
    void lateStartCasFailureRollsBackRunJobAndEventTogether() {
        seedExecutionCandidate();
        RootLockingPort port = new RootLockingPort(jdbc);
        JdbcArchiveMaintenanceStore normal = new JdbcArchiveMaintenanceStore(jdbc);
        service(normal, port).ensureExecution(ACTOR, JOB, "ensure-cas", 1);
        ArchiveMaintenanceServiceImpl failing = service(new LateFailingStore(jdbc), port);

        assertEquals("ARCHIVE_EXECUTION_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> failing.runtimeStart(runtime(), JOB, RUN,
                        new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"))).code());

        assertEquals("AUTHORIZED:EXECUTION_REQUESTED", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertEquals(0, eventCount("EXECUTION_STARTED"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_job_run WHERE run_id=? AND started_message_id IS NOT NULL",
                Integer.class, RUN));
    }

    @Test
    void lateFailureCasFailureRollsBackRunGrantJobAndEventTogether() {
        seedExecutionCandidate();
        RootLockingPort port = new RootLockingPort(jdbc);
        JdbcArchiveMaintenanceStore normal = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(normal, port);
        service.ensureExecution(ACTOR, JOB, "ensure-failure-cas", 1);
        ArchiveRuntimeScope originalRuntime = runtime();
        service.runtimeStart(originalRuntime, JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));
        ArchiveMaintenanceServiceImpl failing = service(new LateFailingFailureStore(jdbc), port);

        assertEquals("ARCHIVE_EXECUTION_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> failing.runtimeFailure(originalRuntime, JOB, RUN,
                        new ArchiveRuntimeFailureRequest("RUNNER", "RUNNER_CRASH", true))).code());

        assertEquals("RUNNING:ACTIVE:RUNNING", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertEquals(0, eventCount("JOB_FAILED"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_job_run WHERE run_id=? "
                        + "AND (failure_phase IS NOT NULL OR failure_code IS NOT NULL OR failure_retryable IS NOT NULL)",
                Integer.class, RUN));
    }

    @Test
    void draftOnlyValidationCompletesAndReadOnlyRevocationFencesWithoutPublication() throws Exception {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        service.ensureExecution(ACTOR, JOB, "ensure-validation", 1);
        ArchiveRuntimeScope originalRuntime = runtime();
        service.runtimeStart(originalRuntime, JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));
        String draftJson = new ObjectMapper().writeValueAsString(validDraft());
        jdbc.update("UPDATE archive_draft SET revision=1,content_json=?,content_sha256=? WHERE draft_id='draft-a'",
                draftJson, "c".repeat(64));

        ArchiveValidationDTO validation = service.runtimeValidate(originalRuntime, JOB, RUN,
                "validate-complete", 1);
        assertEquals("PASSED", validation.outcome());
        assertEquals("COMPLETED:READ_ONLY:AWAITING_PUBLISH", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication", Integer.class));
        long draftRevision = jdbc.queryForObject(
                "SELECT revision FROM archive_draft WHERE draft_id='draft-a'", Long.class);
        int eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_event WHERE job_id=?", Integer.class, JOB);

        port.expired = true;
        assertEquals("COMPLETED", service.runtimeResult(originalRuntime, JOB, RUN).runState());
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeUpdateDraft(runtime(), JOB, RUN, "write-after-complete", 1,
                        validDraft())).code());
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeValidate(runtime(), JOB, RUN, "validate-after-complete", 1)).code());
        assertEquals(draftRevision, jdbc.queryForObject(
                "SELECT revision FROM archive_draft WHERE draft_id='draft-a'", Long.class));
        assertEquals(eventCount, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_event WHERE job_id=?", Integer.class, JOB));

        service.revokeAppointment(ACTOR, APPOINTMENT, "revoke-completed", 1,
                new ArchiveAppointmentRevokeRequest("editor removed"));
        assertEquals("FENCED:FENCED", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertFalse(jdbc.queryForObject(
                "SELECT status='ACTIVE' FROM archive_appointment WHERE appointment_id=?",
                Boolean.class, APPOINTMENT));
        assertEquals("ARCHIVE_ASSIGNMENT_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(originalRuntime, JOB, RUN)).code());
        assertEquals("ARCHIVE_ASSIGNMENT_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeContext(originalRuntime, JOB, RUN)).code());
    }

    @Test
    void autoValidatedNativePublishIsAtomicAndLostResponseReplaysOnePublication() throws Exception {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "appoint,job.manage,draft.write,validate,publish", COLLECTION);
        jdbc.update("UPDATE archive_appointment SET permission_profile='PUBLISH_VALIDATED' "
                + "WHERE appointment_id=?", APPOINTMENT);
        jdbc.update("UPDATE archive_maintenance_job SET permission_profile='PUBLISH_VALIDATED',"
                + "publication_mode='AUTO' WHERE job_id=?", JOB);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port, new JdbcArchiveContentStore(jdbc));
        service.ensureExecution(ACTOR, JOB, "ensure-auto", 1);
        ArchiveRuntimeScope scope = runtime();
        service.runtimeStart(scope, JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));
        ArchiveRuntimeContextDTO context = service.runtimeContext(scope, JOB, RUN);
        assertEquals("work-a", context.workId());
        assertEquals("ADD_WORK", context.operation());
        assertEquals("0", context.expectedWorkRevision());
        assertEquals(null, context.expectedActiveEditionId());
        String draftJson = new ObjectMapper().writeValueAsString(validDraft());
        jdbc.update("UPDATE archive_draft SET revision=1,content_json=?,content_sha256=? WHERE draft_id='draft-a'",
                draftJson, "c".repeat(64));
        ArchiveValidationDTO validation = service.runtimeValidate(scope, JOB, RUN, "validate-auto", 1);
        assertEquals("PASSED", validation.outcome());
        assertEquals("RUNNING:ACTIVE:AWAITING_PUBLISH", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));

        ArchivePublishRequest publish = new ArchivePublishRequest(validation.validationId(),
                context.expectedActiveEditionId(), context.expectedWorkRevision());
        ArchivePublicationDTO first = service.runtimePublish(scope, JOB, RUN, "native-publish", 1, publish);
        port.expired = true;
        ArchivePublicationDTO replay = service.runtimePublish(scope, JOB, RUN, "native-publish", 1, publish);
        assertEquals(first.publicationId(), replay.publicationId());
        assertEquals("COMPLETED:READ_ONLY:PUBLISHED", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication WHERE job_id=?",
                Integer.class, JOB));
        assertEquals("PASSED:2:1:1", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision,':',verification_digest IS NOT NULL,':',checked_at IS NOT NULL) "
                        + "FROM archive_publication_readback WHERE publication_id=?",
                String.class, first.publicationId()));
        assertEquals("PASSED", first.verification().state());
        assertEquals(first.verification(), replay.verification());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication_readback",
                Integer.class));
        assertEquals(1, eventCount("PUBLICATION_COMMITTED"));
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeUpdateDraft(scope, JOB, RUN, "write-after-publish", 1,
                        validDraft())).code());
    }

    @Test
    void exactAdminPublishKeepsImmutablePendingSnapshotAndExposesCurrentPassedReadback() throws Exception {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "job.manage,draft.write,validate,publish", COLLECTION);
        seedValidatedDraft("validation-human");
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc),
                new JdbcArchiveContentStore(jdbc));
        ArchivePublishRequest request = new ArchivePublishRequest("validation-human", null, "0");

        ArchiveOperationAcceptedDTO accepted = service.publishDraft(ACTOR, "draft-a",
                "exact-human-publish", 1, request);
        String frozenResult = jdbc.queryForObject("SELECT result_json FROM "
                + "archive_admin_operation_receipt WHERE operation_id=?", String.class,
                accepted.operationId());
        ArchiveAdminOperationDTO status = service.operation(ACTOR, accepted.operationId());
        ArchiveOperationAcceptedDTO replay = service.publishDraft(ACTOR, "draft-a",
                "exact-human-publish", 1, request);

        assertEquals(accepted.operationId(), replay.operationId());
        assertEquals("COMMITTED", status.state());
        assertEquals("PENDING", status.result().get("readbackState"));
        assertEquals("PENDING", ((java.util.Map<?, ?>) status.result().get("verification")).get("state"));
        assertEquals("PASSED", status.verification().state());
        assertEquals(frozenResult, jdbc.queryForObject("SELECT result_json FROM "
                + "archive_admin_operation_receipt WHERE operation_id=?", String.class,
                accepted.operationId()));
        String publicationId = status.result().get("publicationId").toString();
        assertEquals("PASSED:2", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision) FROM archive_publication_readback "
                        + "WHERE publication_id=?", String.class, publicationId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication_readback",
                Integer.class));
        ArchiveEditionHistoryDTO history = service.editionHistory(ACTOR, "work-a");
        assertEquals("PASSED", history.editions().getFirst().verification().state());
    }

    @Test
    void legacyManualPublishRecordsFailureWithoutUndoAndAuthorizedEditionReadRetriesPassed()
            throws Exception {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "job.manage,draft.write,validate,publish", COLLECTION);
        seedValidatedDraft("validation-corrupt");
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ToggleFailingReadbackContentStore contentStore =
                new ToggleFailingReadbackContentStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc),
                contentStore);
        ArchivePublishRequest request = new ArchivePublishRequest("validation-corrupt", null, "0");

        ArchivePublicationDTO first = service.publish(ACTOR, JOB, "manual-corrupt-publish", 1, request);
        ArchivePublicationDTO replay = service.publish(ACTOR, JOB, "manual-corrupt-publish", 1, request);

        assertEquals(first.publicationId(), replay.publicationId());
        assertEquals("PUBLISHED", first.state());
        assertEquals("FAILED", first.verification().state());
        assertTrue(first.verification().findings().contains("READER_READ_FAILED"));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT state FROM archive_publication WHERE publication_id=?",
                String.class, first.publicationId()));
        assertEquals(first.editionId(), jdbc.queryForObject(
                "SELECT active_edition_id FROM archive_work WHERE work_id='work-a'", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication_readback",
                Integer.class));
        assertEquals(1, eventCount("PUBLICATION_COMMITTED"));

        contentStore.fail = false;
        ArchiveEditionVersionDTO recovered = service.edition(ACTOR, "work-a", first.editionId());
        assertEquals("PASSED", recovered.verification().state());
        assertEquals(first.editionId(), jdbc.queryForObject(
                "SELECT active_edition_id FROM archive_work WHERE work_id='work-a'", String.class));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT state FROM archive_publication WHERE publication_id=?",
                String.class, first.publicationId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM archive_publication",
                Integer.class));
    }

    @Test
    void legacyBootstrapPersistsPendingThenPassesAndCategoryCountDriftFails() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a','publish','ACTIVE',3)",
                COLLECTION);
        String workId = "legacy-work";
        String editionId = "legacy-edition";
        String manifestSha = "d".repeat(64);
        String sourceSha = "e".repeat(64);
        String prefaceSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                "序".getBytes(StandardCharsets.UTF_8));
        String chapterOneSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                "甲".getBytes(StandardCharsets.UTF_8));
        String chapterTwoSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                "乙".getBytes(StandardCharsets.UTF_8));
        String prefaceBlock = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                (manifestSha + ":" + editionId + "-preface:[" + prefaceSha + "]")
                        .getBytes(StandardCharsets.UTF_8));
        String chapterBlock = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                (manifestSha + ":" + editionId + "-c001:[" + chapterOneSha + ", "
                        + chapterTwoSha + "]").getBytes(StandardCharsets.UTF_8));
        JdbcArchiveContentStore contentStore = new JdbcArchiveContentStore(jdbc);
        contentStore.insertWork(new cn.jia.chat.archive.model.ArchiveWorkRecord(workId, "legacy", null));
        contentStore.insertEdition(new cn.jia.chat.archive.model.ArchiveEditionRecord(editionId, workId,
                "READY", sourceSha, manifestSha, manifestSha, 9, 1, 1, 2, 3, 3, 6, 9));
        contentStore.insertBlock(new cn.jia.chat.archive.model.ArchiveBlockRecord(editionId,
                editionId + "-preface", "PREFACE", 0, null, "序", 1, 3, prefaceBlock));
        contentStore.insertParagraph(new cn.jia.chat.archive.model.ArchiveParagraphRecord(editionId,
                editionId + "-preface", editionId + "-preface-p0001", 1, "序", 3, prefaceSha));
        contentStore.insertBlock(new cn.jia.chat.archive.model.ArchiveBlockRecord(editionId,
                editionId + "-c001", "CHAPTER", 1, 1, "章", 2, 6, chapterBlock));
        contentStore.insertParagraph(new cn.jia.chat.archive.model.ArchiveParagraphRecord(editionId,
                editionId + "-c001", editionId + "-c001-p0001", 1, "甲", 3, chapterOneSha));
        contentStore.insertParagraph(new cn.jia.chat.archive.model.ArchiveParagraphRecord(editionId,
                editionId + "-c001", editionId + "-c001-p0002", 2, "乙", 3, chapterTwoSha));
        transactions.required(() -> {
            cn.jia.chat.archive.model.ArchiveWorkRecord work = contentStore.lockWork(workId);
            cn.jia.chat.archive.model.ArchiveEditionRecord edition = contentStore.lockEdition(editionId);
            contentStore.ensureLegacyPublication(COLLECTION, "legacy-key", work, edition);
            assertEquals(1, contentStore.switchActiveEdition(workId, editionId));
            assertEquals(1, contentStore.markActivated(editionId));
            return null;
        });
        assertEquals("PENDING:1", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision) FROM archive_publication_readback "
                        + "WHERE publication_id='legacy-legacy-edition'", String.class));

        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), contentStore);
        ArchiveEditionVersionDTO passed = service.edition(ACTOR, workId, editionId);
        assertEquals("PASSED", passed.verification().state());

        jdbc.update("UPDATE archive_publication_readback SET state='PENDING',revision=revision+1,"
                + "verification_digest=NULL,findings_json='[]',checked_at=NULL "
                + "WHERE publication_id='legacy-legacy-edition'");
        assertEquals(1, jdbc.update("UPDATE archive_edition SET preface_paragraph_count=2,"
                + "chapter_paragraph_count=1 WHERE edition_id=?", editionId));
        ArchiveEditionVersionDTO failed = service.edition(ACTOR, workId, editionId);
        assertEquals("FAILED", failed.verification().state());
        assertTrue(failed.verification().findings().contains("PREFACE_PARAGRAPH_COUNT_MISMATCH"));
        assertTrue(failed.verification().findings().contains("CHAPTER_PARAGRAPH_COUNT_MISMATCH"));
        assertEquals(editionId, jdbc.queryForObject(
                "SELECT active_edition_id FROM archive_work WHERE work_id=?", String.class, workId));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT state FROM archive_publication WHERE publication_id='legacy-legacy-edition'",
                String.class));
    }

    @Test
    void sourcePrepareReturnsDurable202IdentityAndOperationLookupReauthorizesWithoutFakeJob() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a',"
                + "'source.prepare','ACTIVE',3)", COLLECTION);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        String sourceSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(SOURCE);
        ArchiveSourcePrepareRequest request = new ArchiveSourcePrepareRequest("source", "v1",
                "authorized", sourceSha, java.util.Base64.getEncoder().encodeToString(SOURCE));

        ArchiveOperationAcceptedDTO first = service.prepareSource(ACTOR, COLLECTION,
                "source-202", request);
        ArchiveOperationAcceptedDTO replay = service.prepareSource(ACTOR, COLLECTION,
                "source-202", request);
        ArchiveAdminOperationDTO committed = service.operation(ACTOR, first.operationId());
        ArchiveOperationDTO byKey = service.operationByKey(ACTOR, "source-202");

        assertEquals(first, replay);
        assertEquals(first.operationId(), byKey.targetId());
        assertEquals("SOURCE", byKey.targetType());
        assertEquals("COMMITTED", committed.state());
        assertEquals("SOURCE_PREPARE", committed.action());
        assertEquals(first.operationId(), committed.result().get("sourceId"));
        assertFalse(committed.result().containsKey("storageUri"));
        assertEquals("1:1:0:0:0", jdbc.queryForObject(
                "SELECT CONCAT((SELECT COUNT(*) FROM archive_source_snapshot),':',"
                        + "(SELECT COUNT(*) FROM archive_operation),':',"
                        + "(SELECT COUNT(*) FROM archive_admin_operation_receipt),':',"
                        + "(SELECT COUNT(*) FROM archive_maintenance_job),':',"
                        + "(SELECT COUNT(*) FROM archive_draft))", String.class));

        jdbc.update("INSERT INTO archive_operation(tenant_id,client_id,owner_jiacn,operation_key,"
                + "http_method,canonical_path,request_sha256,target_type,target_id,state) VALUES "
                + "('0','client-a','owner-a','source-pending','POST',?,?,'SOURCE','src_pending','PENDING')",
                "/archive/admin/v1/collections/" + COLLECTION + "/source-snapshots", "c".repeat(64));
        ArchiveAdminOperationDTO pending = service.operation(ACTOR, "src_pending");
        assertEquals("PENDING", pending.state());
        assertNull(pending.result());

        jdbc.update("UPDATE archive_collection_manager SET state='REVOKED',revision=4 "
                + "WHERE collection_id=?", COLLECTION);
        assertEquals(403, assertThrows(ArchiveMaintenanceException.class,
                () -> service.operation(ACTOR, first.operationId())).status());
        assertEquals(403, assertThrows(ArchiveMaintenanceException.class,
                () -> service.operationByKey(ACTOR, "source-202")).status());
        ArchiveActorScope foreign = new ArchiveActorScope("0", "client-a", "owner-b");
        assertEquals(404, assertThrows(ArchiveMaintenanceException.class,
                () -> service.operation(foreign, first.operationId())).status());
    }

    @Test
    void reviseContextReturnsExactWorkSnapshotAndConcurrentRevisionChangeBlocksAutoPublish() throws Exception {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "appoint,job.manage,draft.write,validate,publish", COLLECTION);
        jdbc.update("UPDATE archive_appointment SET permission_profile='PUBLISH_VALIDATED' "
                + "WHERE appointment_id=?", APPOINTMENT);
        jdbc.update("UPDATE archive_maintenance_job SET permission_profile='PUBLISH_VALIDATED',"
                + "publication_mode='AUTO',operation_code='REVISE_WORK' WHERE job_id=?", JOB);
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) VALUES ('work-a','existing',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) "
                + "VALUES (?,'work-a','key-a',4)", COLLECTION);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port, new JdbcArchiveContentStore(jdbc));
        service.ensureExecution(ACTOR, JOB, "ensure-revise-context", 1);
        ArchiveRuntimeScope scope = runtime();
        service.runtimeStart(scope, JOB, RUN,
                new ArchiveRuntimeStartRequest("command-a", "message-a", "1", "1"));

        ArchiveRuntimeContextDTO context = service.runtimeContext(scope, JOB, RUN);
        assertEquals("work-a", context.workId());
        assertEquals("REVISE_WORK", context.operation());
        assertEquals("4", context.expectedWorkRevision());
        assertEquals(null, context.expectedActiveEditionId());

        String draftJson = new ObjectMapper().writeValueAsString(validDraft());
        jdbc.update("UPDATE archive_draft SET revision=1,content_json=?,content_sha256=? WHERE draft_id='draft-a'",
                draftJson, "c".repeat(64));
        ArchiveValidationDTO validation = service.runtimeValidate(scope, JOB, RUN, "validate-revise", 1);
        assertEquals("PASSED", validation.outcome());
        jdbc.update("UPDATE archive_collection_work SET revision=revision+1 "
                + "WHERE collection_id=? AND work_id='work-a'", COLLECTION);

        ArchivePublishRequest publish = new ArchivePublishRequest(validation.validationId(),
                context.expectedActiveEditionId(), context.expectedWorkRevision());
        assertEquals("ACTIVE_EDITION_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimePublish(scope, JOB, RUN, "publish-revise-stale", 1, publish)).code());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_publication WHERE job_id=?", Integer.class, JOB));
        assertEquals("RUNNING:ACTIVE:AWAITING_PUBLISH", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', g.state, ':', j.state) FROM archive_job_run r "
                        + "JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "JOIN archive_maintenance_job j ON j.run_id=r.run_id WHERE r.run_id=?",
                String.class, RUN));
    }

    @Test
    void explicitResumeCreatesOneHigherEpochPreservesHistoryAndFencesOldRun() {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc));
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveExecutionRecoveryDTO first = service.resume(ACTOR, JOB, "resume-key", 1,
                new ArchiveResumeRequest("retry after explicit review", APPOINTMENT, "1", skill));
        ArchiveExecutionRecoveryDTO replay = service.resume(ACTOR, JOB, "resume-key", 1,
                new ArchiveResumeRequest("retry after explicit review", APPOINTMENT, "1", skill));
        assertEquals(first.runId(), replay.runId());
        assertEquals("2", first.executionEpoch());
        assertEquals("FENCED:2", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', execution_epoch) FROM archive_job_run WHERE run_id=?",
                String.class, RUN));
        assertEquals("WAITING:2", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', execution_epoch) FROM archive_job_run WHERE run_id=?",
                String.class, first.runId()));
        assertEquals(first.runId(), jdbc.queryForObject(
                "SELECT run_id FROM archive_maintenance_job WHERE job_id=?", String.class, JOB));
        ArchiveRuntimeScope old = new ArchiveRuntimeScope("0", "client-a", "owner-a", AGENT,
                "runtime-a", "grant-a", "execution-a", "command-a", 1, 1);
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(old, JOB, RUN)).code());
    }

    @Test
    void expiredProductionFenceResumesAtNextEpochAndExactReplayKeepsOneReplacement() {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        service.ensureExecution(ACTOR, JOB, "ensure-expiry-resume", 1);
        ArchiveRuntimeScope oldScope = runtime();
        jdbc.update("UPDATE archive_execution_grant SET expires_at=1 WHERE run_id=?", RUN);

        ArchiveMaintenanceException expired = assertThrows(ArchiveMaintenanceException.class,
                () -> service.ensureExecution(ACTOR, JOB, "ensure-expiry-resume", 1));
        assertEquals("ARCHIVE_EXECUTION_EXPIRED", expired.code());
        assertEquals("FENCED:2:FENCED:2", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', r.execution_epoch, ':', g.state, ':', g.execution_epoch) "
                        + "FROM archive_job_run r JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "WHERE r.run_id=?", String.class, RUN));
        assertEquals("WAITING_SKILL:3", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', revision) FROM archive_maintenance_job WHERE job_id=?",
                String.class, JOB));

        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveResumeRequest request = new ArchiveResumeRequest(
                "resume after persisted expiry fence", APPOINTMENT, "1", skill);
        ArchiveExecutionRecoveryDTO first = service.resume(ACTOR, JOB, "resume-expired", 3, request);
        ArchiveExecutionRecoveryDTO replay = service.resume(ACTOR, JOB, "resume-expired", 3, request);

        assertEquals(first.runId(), replay.runId());
        assertEquals("3", first.executionEpoch());
        assertEquals("WAITING:3", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', execution_epoch) FROM archive_job_run WHERE run_id=?",
                String.class, first.runId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_job_run WHERE job_id=?", Integer.class, JOB));
        assertEquals(1, eventCount("EXECUTION_RESUMED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(oldScope, JOB, RUN)).code());
    }

    @Test
    void productionRevocationReplacementAndReassignAdvanceEpochOnceAndFenceOldScope() {
        seedExecutionCandidate();
        String agentB = "agent-b";
        jdbc.update("INSERT INTO aam_test_agent_root(agent_id) VALUES (?)", agentB);
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, port);
        service.ensureExecution(ACTOR, JOB, "ensure-revoke-reassign", 1);
        ArchiveRuntimeScope oldScope = runtime();

        service.revokeAppointment(ACTOR, APPOINTMENT, "revoke-for-reassign", 1,
                new ArchiveAppointmentRevokeRequest("replace assigned editor"));
        assertEquals("FENCED:2:FENCED:2", jdbc.queryForObject(
                "SELECT CONCAT(r.state, ':', r.execution_epoch, ':', g.state, ':', g.execution_epoch) "
                        + "FROM archive_job_run r JOIN archive_execution_grant g ON g.run_id=r.run_id "
                        + "WHERE r.run_id=?", String.class, RUN));
        assertEquals("REVOKED:2", jdbc.queryForObject(
                "SELECT CONCAT(status, ':', revision) FROM archive_appointment WHERE appointment_id=?",
                String.class, APPOINTMENT));

        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveAppointmentDTO replacement = service.createAppointment(ACTOR, COLLECTION,
                "create-replacement", 2, new ArchiveAppointmentCreateRequest(agentB, "8",
                        "COLLECTION", List.of(), "DRAFT_ONLY", skill));
        ArchiveReassignRequest request = new ArchiveReassignRequest("explicit replacement",
                APPOINTMENT, "1", skill, replacement.appointmentId(), replacement.revision(), skill);
        ArchiveExecutionRecoveryDTO first = service.reassign(ACTOR, JOB, "reassign-revoked", 2, request);
        ArchiveExecutionRecoveryDTO replay = service.reassign(ACTOR, JOB, "reassign-revoked", 2, request);

        assertEquals(first.runId(), replay.runId());
        assertEquals("3", first.executionEpoch());
        assertEquals(agentB + ":8:" + replacement.appointmentId() + ":" + first.runId(),
                jdbc.queryForObject(
                        "SELECT CONCAT(agent_id, ':', binding_version, ':', appointment_id, ':', run_id) "
                                + "FROM archive_maintenance_job WHERE job_id=?", String.class, JOB));
        assertEquals("WAITING:3", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', execution_epoch) FROM archive_job_run WHERE run_id=?",
                String.class, first.runId()));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_job_run WHERE job_id=?", Integer.class, JOB));
        assertEquals(1, eventCount("EXECUTION_REASSIGNED"));
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeResult(oldScope, JOB, RUN)).code());
    }

    @Test
    void persistedConfirmationBindsCanonicalTurnAndAllRetriesReuseOneDurableJob() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,?,?,?,?,'ACTIVE',3)", COLLECTION,
                "0", "client-a", "owner-a", "job.create");
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',?,1)", COLLECTION, APPOINTMENT);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES (?,?,'ARCHIVE_EDITOR','0','client-a','owner-a',?,'7','COLLECTION','',"
                + "'DRAFT_ONLY','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                APPOINTMENT, COLLECTION, AGENT, SHA);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('source-m7',?,'0','client-a','owner-a',"
                + "'cyf-artifact://m7',?,?,'source','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, SHA, SOURCE.length);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc));
        service.setArchiveMaintenanceProperties(new ArchiveMaintenanceProperties());
        ArchiveNewWorkRequest newWork = new ArchiveNewWorkRequest("m7-book", "M7 Book", "en");
        ArchiveMaintenanceRequest business = new ArchiveMaintenanceRequest(COLLECTION,
                "ADD_WORK", newWork, null, "source-m7", "MANUAL");

        ArchiveMaintenanceRequestResultDTO manager = service.confirmRequest(
                ACTOR, COLLECTION, "manager-confirmation", business);
        assertTrue(manager.confirmationRef().startsWith("acf_"));
        AtomicInteger canonicalWrites = new AtomicInteger();
        java.util.function.Supplier<String> canonicalWriter = () -> {
            canonicalWrites.incrementAndGet();
            return "9901";
        };
        String turnSha = "c".repeat(64);
        ArchiveRequestContext direct = service.bindChatConfirmation(ACTOR,
                manager.confirmationRef(), "conversation-direct", 7, turnSha,
                "DIRECT_PRIVATE", AGENT, canonicalWriter);
        ArchiveRequestContext retry = service.bindChatConfirmation(ACTOR,
                manager.confirmationRef(), "conversation-direct", 7, turnSha,
                "DIRECT_PRIVATE", AGENT, canonicalWriter);
        ArchiveMaintenanceRequestResultDTO routed = service.request(direct, business);
        ArchiveMaintenanceRequestResultDTO routedRetry = service.request(retry, business);

        assertEquals(manager.job().jobId(), routed.job().jobId());
        assertEquals(manager.job().jobId(), routedRetry.job().jobId());
        assertEquals(1, canonicalWrites.get());
        assertEquals("conversation-direct:9901", direct.conversationRef());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_confirmed_request WHERE confirmation_ref=? "
                        + "AND conversation_id='conversation-direct' AND canonical_message_id='9901' "
                        + "AND conversation_generation=7 AND turn_sha256=? "
                        + "AND entry_point='DIRECT_PRIVATE' AND target_agent_id=?",
                Integer.class, manager.confirmationRef(), turnSha, AGENT));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_maintenance_job WHERE tenant_id='0' AND client_id='client-a' "
                        + "AND owner_jiacn='owner-a' AND request_intent_id=?",
                Integer.class, direct.requestIntentId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_job_run WHERE job_id=?", Integer.class,
                manager.job().jobId()));

        ArchiveMaintenanceException entryMismatch = assertThrows(ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(ACTOR, manager.confirmationRef(),
                        "conversation-direct", 7, turnSha, "SONGJIANG", null,
                        canonicalWriter));
        assertEquals("ARCHIVE_CONFIRMATION_BINDING_MISMATCH", entryMismatch.code());
        ArchiveMaintenanceException foreign = assertThrows(ArchiveMaintenanceException.class,
                () -> service.bindChatConfirmation(new ArchiveActorScope(
                                "0", "client-a", "owner-b"), manager.confirmationRef(),
                        "conversation-direct", 7, turnSha, "DIRECT_PRIVATE", AGENT,
                        canonicalWriter));
        assertEquals("ARCHIVE_CONFIRMATION_NOT_AVAILABLE", foreign.code());
        assertEquals(1, canonicalWrites.get());
    }

    @Test
    void explicitReassignUsesOnlySuppliedCurrentAppointmentAndNewEpoch() {
        seedExecutionCandidate();
        String agentB = "agent-b";
        String appointmentB = "appointment-b";
        jdbc.update("INSERT INTO aam_test_agent_root(agent_id) VALUES (?)", agentB);
        jdbc.update("UPDATE archive_appointment SET status='REVOKED',revision=revision+1 WHERE appointment_id=?",
                APPOINTMENT);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES (?,?,'ARCHIVE_EDITOR','0','client-a','owner-a',?,'8','COLLECTION','',"
                + "'DRAFT_ONLY','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                appointmentB, COLLECTION, agentB, SHA);
        jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=?,revision=revision+1 "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'", appointmentB, COLLECTION);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc));
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveExecutionRecoveryDTO recovered = service.reassign(ACTOR, JOB, "reassign-key", 1,
                new ArchiveReassignRequest("explicit handoff", APPOINTMENT, "1", skill,
                        appointmentB, "1", skill));
        assertEquals("2", recovered.executionEpoch());
        assertEquals(agentB + ":8:" + appointmentB + ":" + recovered.runId(), jdbc.queryForObject(
                "SELECT CONCAT(agent_id, ':', binding_version, ':', appointment_id, ':', run_id) "
                        + "FROM archive_maintenance_job WHERE job_id=?", String.class, JOB));
        assertEquals("FENCED", jdbc.queryForObject(
                "SELECT state FROM archive_job_run WHERE run_id=?", String.class, RUN));
        assertEquals("WAITING", jdbc.queryForObject(
                "SELECT state FROM archive_job_run WHERE run_id=?", String.class, recovered.runId()));
    }

    private int eventCount(String type) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_event WHERE job_id=? AND event_type=?",
                Integer.class, JOB, type);
    }

    @Test
    void waitingJobPersistsWithoutExecutionAndResolvesThroughExactCurrentAppointment() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a',"
                + "'job.create,job.manage','ACTIVE',3)", COLLECTION);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('source-wait',?,'0','client-a','owner-a',"
                + "'cyf-artifact://wait',?,?,'source','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, SHA, SOURCE.length);
        RootLockingPort port = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc), port);
        ArchiveJobDTO initial = service.createJob(ACTOR, COLLECTION, "waiting-create",
                new ArchiveJobCreateRequest("ADD_WORK",
                        new ArchiveNewWorkRequest("waiting-key", "Waiting Work", null),
                        null, null, "MANUAL", "waiting-intent"));
        assertEquals("WAITING_INPUT", initial.state());
        assertEquals("SOURCE_AND_ASSIGNEE_REQUIRED", initial.waitReason());
        assertNull(initial.runId());
        assertNull(initial.draftId());
        assertEquals("0:0:0", jdbc.queryForObject(
                "SELECT CONCAT((SELECT COUNT(*) FROM archive_job_run WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_draft WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_execution_grant g JOIN archive_job_run r "
                        + "ON r.run_id=g.run_id WHERE r.job_id=?))", String.class,
                initial.jobId(), initial.jobId(), initial.jobId()));
        assertEquals(0, port.rootAttempts.get());

        ArchiveJobDTO cancellable = service.createJob(ACTOR, COLLECTION, "waiting-cancel-create",
                new ArchiveJobCreateRequest("ADD_WORK", null, null, null,
                        "MANUAL", "waiting-cancel-intent"));
        ArchiveJobDTO cancelled = service.cancel(ACTOR, cancellable.jobId(),
                "waiting-cancel", 1, new ArchiveCancelRequest("input unavailable"));
        assertEquals("CANCELLED", cancelled.state());
        assertNull(cancelled.runId());
        assertEquals(0, port.rootAttempts.get(),
                "cancelling a waiting job must not invent an execution target");

        ArchiveJobDTO awaitingAssignee = service.resolveInput(ACTOR, initial.jobId(),
                "waiting-source", 1, new ArchiveResolveInputRequest("source-wait", null,
                        null, null, null, null));
        assertEquals("WAITING_ASSIGNEE", awaitingAssignee.state());
        assertEquals("ASSIGNEE_REQUIRED", awaitingAssignee.waitReason());
        assertEquals("source-wait", awaitingAssignee.sourceId());
        assertEquals("0:0", jdbc.queryForObject(
                "SELECT CONCAT((SELECT COUNT(*) FROM archive_job_run WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_draft WHERE job_id=?))", String.class,
                initial.jobId(), initial.jobId()));

        jdbc.update("INSERT INTO aam_test_agent_root(agent_id) VALUES (?)", AGENT);
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',NULL,0)", COLLECTION);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES (?,?,'ARCHIVE_EDITOR','0','client-a','owner-a',?,'7','COLLECTION','',"
                + "'DRAFT_ONLY','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                APPOINTMENT, COLLECTION, AGENT, SHA);
        assertEquals(1, jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=?,revision=1 "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR' "
                + "AND current_appointment_id IS NULL AND revision=0", APPOINTMENT, COLLECTION));
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ArchiveResolveInputRequest bind = new ArchiveResolveInputRequest(null, null, null,
                APPOINTMENT, "1", skill);
        ArchiveJobDTO ready = service.resolveInput(ACTOR, initial.jobId(), "waiting-bind", 2, bind);
        assertEquals("WAITING_SKILL", ready.state());
        assertEquals("CLIENT_UPDATE_REQUIRED", ready.waitReason());
        assertEquals(APPOINTMENT, ready.appointmentId());
        assertEquals("1:1:0", jdbc.queryForObject(
                "SELECT CONCAT((SELECT COUNT(*) FROM archive_job_run WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_draft WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_execution_grant g JOIN archive_job_run r "
                        + "ON r.run_id=g.run_id WHERE r.job_id=?))", String.class,
                initial.jobId(), initial.jobId(), initial.jobId()));
        assertEquals(0, port.rootAttempts.get(), "input resolution must not dispatch or claim a lease");

        ArchiveJobDTO replay = service.resolveInput(ACTOR, initial.jobId(), "waiting-bind", 2, bind);
        assertEquals(ready, replay);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(ACTOR, initial.jobId(), "waiting-bind", 2,
                        new ArchiveResolveInputRequest(null, null, null,
                                APPOINTMENT, "1", new ArchiveSkillRef(
                                        "archive-maintainer", "1.0.0", "b".repeat(64))))).code());

        ArchiveActorScope foreign = new ArchiveActorScope("0", "client-a", "owner-b");
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.resolveInput(foreign, initial.jobId(), "foreign", 3, bind)).code());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_maintenance_job WHERE job_id=? AND revision=3 "
                        + "AND state='WAITING_SKILL'", Integer.class, initial.jobId()));
    }

    @Test
    void managerRevocationCommittedBeforeResolvePreventsCandidateCreation() throws Exception {
        seedWaitingResolutionCandidate();
        CountDownLatch resolvePreflightComplete = new CountDownLatch(1);
        CountDownLatch allowResolveTransaction = new CountDownLatch(1);
        ArchiveTransactions delayedResolveTransactions = new ArchiveTransactions() {
            @Override
            public <T> T required(java.util.function.Supplier<T> action) {
                resolvePreflightComplete.countDown();
                await(allowResolveTransaction);
                return transactions.required(action);
            }
        };
        ArchiveMaintenanceServiceImpl resolveService = service(
                new JdbcArchiveMaintenanceStore(jdbc), new RootLockingPort(jdbc),
                mock(ArchiveContentStore.class), delayedResolveTransactions);
        ArchiveMaintenanceServiceImpl revokeService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc));
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> resolve = pool.submit(() -> {
                try {
                    resolveService.resolveInput(ACTOR, JOB, "resolve-after-manager-revoke", 1,
                            new ArchiveResolveInputRequest(null, null, null,
                                    APPOINTMENT, "1", skill));
                    return "unexpected";
                } catch (ArchiveMaintenanceException failure) {
                    return failure.code();
                }
            });
            assertTrue(resolvePreflightComplete.await(5, TimeUnit.SECONDS));
            revokeService.revokeManagerAuthorization(ACTOR, COLLECTION, "revoke-before-resolve", 3,
                    new ArchiveManagerRevokeRequest("authorization removed"));
            allowResolveTransaction.countDown();
            assertEquals("ARCHIVE_FORBIDDEN", resolve.get(10, TimeUnit.SECONDS));
        } finally {
            allowResolveTransaction.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("WAITING_ASSIGNEE:1", jdbc.queryForObject(
                "SELECT CONCAT(state,':',revision) FROM archive_maintenance_job WHERE job_id=?",
                String.class, JOB));
        assertEquals("0:0:0", candidateRowCounts(JOB));
    }

    @Test
    void resolveCommittedBeforeAppointmentRevocationCreatesWholeCandidateThenFencesIt() throws Exception {
        seedWaitingResolutionCandidate();
        CountDownLatch candidateUpdated = new CountDownLatch(1);
        CountDownLatch allowResolveCommit = new CountDownLatch(1);
        BlockingResolveStore resolveStore = new BlockingResolveStore(jdbc, candidateUpdated,
                allowResolveCommit);
        RootLockingPort revokePort = new RootLockingPort(jdbc);
        ArchiveMaintenanceServiceImpl resolveService = service(resolveStore,
                new RootLockingPort(jdbc));
        ArchiveMaintenanceServiceImpl revokeService = service(new JdbcArchiveMaintenanceStore(jdbc),
                revokePort);
        ArchiveSkillRef skill = new ArchiveSkillRef("archive-maintainer", "1.0.0", SHA);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchiveJobDTO> resolve = pool.submit(() -> resolveService.resolveInput(
                    ACTOR, JOB, "resolve-before-appointment-revoke", 1,
                    new ArchiveResolveInputRequest(null, null, null,
                            APPOINTMENT, "1", skill)));
            assertTrue(candidateUpdated.await(5, TimeUnit.SECONDS));
            Future<ArchiveAppointmentDTO> revoke = pool.submit(() -> revokeService.revokeAppointment(
                    ACTOR, APPOINTMENT, "revoke-after-resolve", 1,
                    new ArchiveAppointmentRevokeRequest("assignment removed")));
            assertTrue(revokePort.firstRootAttempted.await(5, TimeUnit.SECONDS));
            allowResolveCommit.countDown();
            ArchiveJobDTO ready = resolve.get(10, TimeUnit.SECONDS);
            assertEquals("WAITING_SKILL", ready.state());
            assertEquals("REVOKED", revoke.get(10, TimeUnit.SECONDS).status());
        } finally {
            allowResolveCommit.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("WAITING_SKILL:2:1:1:1", jdbc.queryForObject(
                "SELECT CONCAT(j.state,':',j.revision,':',j.run_id IS NOT NULL,':',"
                        + "j.draft_id IS NOT NULL,':',j.appointment_id IS NOT NULL) "
                        + "FROM archive_maintenance_job j WHERE j.job_id=?", String.class, JOB));
        assertEquals("FENCED", jdbc.queryForObject(
                "SELECT state FROM archive_job_run WHERE job_id=?", String.class, JOB));
        assertEquals("REVOKED", jdbc.queryForObject(
                "SELECT status FROM archive_appointment WHERE appointment_id=?",
                String.class, APPOINTMENT));
        assertNull(jdbc.queryForObject(
                "SELECT current_appointment_id FROM archive_appointment_slot "
                        + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'",
                String.class, COLLECTION));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_execution_grant g JOIN archive_job_run r "
                        + "ON r.run_id=g.run_id WHERE r.job_id=? AND g.state='ACTIVE'",
                Integer.class, JOB));
    }

    @Test
    void businessOutboxRejectsNullJobEventSequenceByItsNamedSourceConstraint() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a','job.manage','ACTIVE',1)",
                COLLECTION);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                + "request_intent_id,request_sha256) VALUES ('shape-job',?,'0','client-a','owner-a',1,"
                + "'MANUAL','ADD_WORK','WAITING_INPUT','SOURCE_AND_WORK',1,'shape-intent',?)",
                COLLECTION, SHA);
        jdbc.update("INSERT INTO archive_event(job_id,sequence,schema_version,event_type,job_revision,data_json,"
                + "outbox_state) VALUES ('shape-job',1,1,'JOB_CREATED',1,'{}','PENDING')");

        DataAccessException rejected = assertThrows(DataAccessException.class, () -> jdbc.update("""
                INSERT INTO archive_business_outbox(
                    projection_key,source_type,job_id,event_sequence,withdrawal_id,state,
                    attempt_count,fencing_token,available_at,lease_until,last_error_code,projected_message_id)
                VALUES ('EVENT:shape-job:null','JOB_EVENT','shape-job',NULL,NULL,'READY',0,0,
                        CURRENT_TIMESTAMP(6),NULL,NULL,NULL)
                """));
        assertTrue(exceptionMessages(rejected).contains("chk_archive_business_source"),
                "the negative row must reach the named source CHECK, not fail through a fixture FK");
    }

    @Test
    void businessOutboxIndexAndCheckDriftRemainFailClosed() {
        jdbc.execute("ALTER TABLE archive_business_outbox DROP INDEX idx_archive_business_lease");
        IllegalStateException indexDrift = assertThrows(IllegalStateException.class,
                () -> new ArchiveMaintenanceSchemaInitializer(jdbc,
                        new JdbcArchiveMaintenanceStore(jdbc),
                        new ArchiveMaintenanceProperties()).initialize());
        assertTrue(indexDrift.getMessage().contains("archive_business_outbox.indexes"));

        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();
        jdbc.execute("ALTER TABLE archive_business_outbox DROP CHECK chk_archive_business_source, "
                + "ADD CONSTRAINT chk_archive_business_source CHECK "
                + "(((source_type='JOB_EVENT') AND (event_sequence >= 1) "
                + "AND (withdrawal_id IS NULL)) OR ((source_type='WITHDRAWAL') "
                + "AND (event_sequence IS NULL) AND (withdrawal_id IS NOT NULL)))");
        IllegalStateException checkDrift = assertThrows(IllegalStateException.class,
                () -> new ArchiveMaintenanceSchemaInitializer(jdbc,
                        new JdbcArchiveMaintenanceStore(jdbc),
                        new ArchiveMaintenanceProperties()).initialize());
        assertTrue(checkDrift.getMessage().contains("archive_business_outbox.checks"));
    }

    @Test
    void businessOutboxSourceAcknowledgementDriftRemainsFailClosed() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a','job.manage','ACTIVE',1)",
                COLLECTION);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                + "request_intent_id,request_sha256) VALUES ('state-job',?,'0','client-a','owner-a',1,"
                + "'MANUAL','ADD_WORK','WAITING_INPUT','SOURCE_AND_WORK',1,'state-intent',?)",
                COLLECTION, SHA);
        jdbc.update("INSERT INTO archive_event(job_id,sequence,schema_version,event_type,job_revision,data_json,"
                + "outbox_state) VALUES ('state-job',1,1,'JOB_CREATED',1,'{}','PENDING')");
        jdbc.update("INSERT INTO archive_business_outbox(projection_key,source_type,job_id,event_sequence,"
                + "withdrawal_id,state,attempt_count,fencing_token,available_at,lease_until,last_error_code,"
                + "projected_message_id) VALUES ('EVENT:state-job:1','JOB_EVENT','state-job',1,NULL,'READY',"
                + "0,0,CURRENT_TIMESTAMP(6),NULL,NULL,NULL)");
        jdbc.update("UPDATE archive_event SET outbox_state='DELIVERED' "
                + "WHERE job_id='state-job' AND sequence=1");

        IllegalStateException drift = assertThrows(IllegalStateException.class,
                () -> new ArchiveMaintenanceSchemaInitializer(jdbc,
                        new JdbcArchiveMaintenanceStore(jdbc),
                        new ArchiveMaintenanceProperties()).initialize());
        assertTrue(drift.getMessage().contains("source acknowledgement is inconsistent"));
    }

    @Test
    void exactEighteenTablePredecessorBackfillsPendingBusinessFactsWithoutChangingPrivateBytes()
            throws Exception {
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        String ddl;
        try (var input = new ClassPathResource("db/archive-maintenance-schema.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        new ResourceDatabasePopulator(new ByteArrayResource(
                businessOutboxPredecessorSchema(ddl).getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a','job.manage','ACTIVE',1)",
                COLLECTION);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                + "request_intent_id,request_sha256) VALUES ('upgrade-outbox-job',?,'0','client-a','owner-a',1,"
                + "'MANUAL','ADD_WORK','WAITING_INPUT','SOURCE_AND_WORK',1,'upgrade-outbox-intent',?)",
                COLLECTION, SHA);
        jdbc.update("INSERT INTO archive_event(job_id,sequence,schema_version,event_type,job_revision,data_json,"
                + "outbox_state) VALUES ('upgrade-outbox-job',1,1,'JOB_CREATED',1,?,'PENDING')",
                "{\"private\":\"preserve exactly\"}");
        String eventDigest = jdbc.queryForObject(
                "SELECT SHA2(CONCAT(event_type,':',data_json,':',outbox_state),256) FROM archive_event "
                        + "WHERE job_id='upgrade-outbox-job' AND sequence=1", String.class);
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) VALUES "
                + "('upgrade-outbox-work','title',NULL)");
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,"
                + "manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,"
                + "chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,"
                + "chapter_utf8_byte_length,reader_utf8_byte_length) VALUES ('upgrade-outbox-edition',"
                + "'upgrade-outbox-work','READY',?,?,?,1,1,0,1,1,0,1,1)", SHA, SHA, SHA);
        jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,chapter_number,"
                + "title,paragraph_count,utf8_byte_length,block_content_sha256) VALUES "
                + "('upgrade-outbox-edition','upgrade-outbox-edition-c001','CHAPTER',1,1,'chapter',1,1,?)", SHA);
        jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                + "utf8_byte_length,sha256) VALUES ('upgrade-outbox-edition','upgrade-outbox-edition-c001',"
                + "'upgrade-outbox-edition-c001-p0001',1,'x',1,?)", SHA);
        jdbc.update("INSERT INTO archive_note(tenant_id,client_id,owner_jiacn,note_id,edition_id,state,"
                + "text,block_id,anchor_json,version) VALUES ('0','private-client','private-owner',"
                + "'423e4567-e89b-82d3-a456-426614174000','upgrade-outbox-edition','ACTIVE',"
                + "'private outbox upgrade fact',NULL,NULL,1)");
        String privateDigest = jdbc.queryForObject("SELECT SHA2(CONCAT(owner_jiacn,':',text,':',version),256) "
                + "FROM archive_note WHERE note_id='423e4567-e89b-82d3-a456-426614174000'", String.class);
        jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,"
                + "draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision) "
                + "VALUES ('upgrade-outbox-publication','upgrade-outbox-job',?,'upgrade-outbox-work',"
                + "'upgrade-outbox-edition',1,?,?,'WITHDRAWN','HUMAN','owner-a',1)", COLLECTION, SHA, SHA);
        jdbc.update("INSERT INTO archive_edition_withdrawal(withdrawal_id,publication_id,collection_id,work_id,"
                + "edition_id,reason,tenant_id,client_id,owner_jiacn,actor_type,actor_id,authorization_revision,"
                + "requested_replacement_active_edition_id,resulting_active_edition_id,resulting_work_revision,"
                + "operation_key,outbox_state) VALUES ('upgrade-outbox-withdrawal','upgrade-outbox-publication',?,"
                + "'upgrade-outbox-work','upgrade-outbox-edition','reason','0','client-a','owner-a','HUMAN',"
                + "'owner-a',1,NULL,NULL,2,'upgrade-outbox-key','PENDING')", COLLECTION);
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,"
                + "manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,"
                + "chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,"
                + "chapter_utf8_byte_length,reader_utf8_byte_length) VALUES ('upgrade-bootstrap-edition',"
                + "'upgrade-outbox-work','READY',?,?,?,1,1,0,1,1,0,1,1)", SHA, SHA, SHA);
        jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,chapter_number,"
                + "title,paragraph_count,utf8_byte_length,block_content_sha256) VALUES "
                + "('upgrade-bootstrap-edition','upgrade-bootstrap-edition-c001','CHAPTER',1,1,"
                + "'chapter',1,1,?)", SHA);
        jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                + "utf8_byte_length,sha256) VALUES ('upgrade-bootstrap-edition',"
                + "'upgrade-bootstrap-edition-c001','upgrade-bootstrap-edition-c001-p0001',"
                + "1,'x',1,?)", SHA);
        jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,"
                + "draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision) "
                + "VALUES ('upgrade-bootstrap-publication',NULL,?,'upgrade-outbox-work',"
                + "'upgrade-bootstrap-edition',1,?,?,'WITHDRAWN','HUMAN','owner-a',1)",
                COLLECTION, SHA, SHA);
        jdbc.update("INSERT INTO archive_edition_withdrawal(withdrawal_id,publication_id,collection_id,work_id,"
                + "edition_id,reason,tenant_id,client_id,owner_jiacn,actor_type,actor_id,authorization_revision,"
                + "requested_replacement_active_edition_id,resulting_active_edition_id,resulting_work_revision,"
                + "operation_key,outbox_state) VALUES ('upgrade-bootstrap-withdrawal',"
                + "'upgrade-bootstrap-publication',?,'upgrade-outbox-work','upgrade-bootstrap-edition',"
                + "'bootstrap reason','0','client-a','owner-a','HUMAN','owner-a',1,NULL,NULL,3,"
                + "'upgrade-bootstrap-key','PENDING')", COLLECTION);
        String withdrawalDigest = jdbc.queryForObject(
                "SELECT SHA2(CONCAT(reason,':',actor_id,':',operation_key,':',outbox_state),256) "
                        + "FROM archive_edition_withdrawal WHERE withdrawal_id='upgrade-outbox-withdrawal'",
                String.class);
        // Simulate an interrupted ordered DDL bridge: event CHECK upgraded, withdrawal/table pending.
        jdbc.execute("ALTER TABLE archive_event DROP CHECK chk_archive_event_outbox, "
                + "ADD CONSTRAINT chk_archive_event_outbox CHECK "
                + "(outbox_state IN ('PENDING','DELIVERED','NO_TARGET'))");

        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM archive_business_outbox", Integer.class));
        assertEquals("READY:PENDING", jdbc.queryForObject("SELECT CONCAT(o.state,':',e.outbox_state) "
                + "FROM archive_business_outbox o JOIN archive_event e ON e.job_id=o.job_id "
                + "AND e.sequence=o.event_sequence WHERE o.projection_key='EVENT:upgrade-outbox-job:1'",
                String.class));
        assertEquals("READY:PENDING", jdbc.queryForObject("SELECT CONCAT(o.state,':',w.outbox_state) "
                + "FROM archive_business_outbox o JOIN archive_edition_withdrawal w "
                + "ON w.withdrawal_id=o.withdrawal_id WHERE o.projection_key="
                + "'WITHDRAWAL:upgrade-outbox-withdrawal'", String.class));
        assertEquals("NO_TARGET:0", jdbc.queryForObject("SELECT CONCAT(w.outbox_state,':',"
                + "(SELECT COUNT(*) FROM archive_business_outbox o "
                + "WHERE o.withdrawal_id=w.withdrawal_id)) FROM archive_edition_withdrawal w "
                + "WHERE w.withdrawal_id='upgrade-bootstrap-withdrawal'", String.class),
                "an upgraded bootstrap withdrawal must terminate without a fabricated job");
        assertEquals(eventDigest, jdbc.queryForObject(
                "SELECT SHA2(CONCAT(event_type,':',data_json,':',outbox_state),256) FROM archive_event "
                        + "WHERE job_id='upgrade-outbox-job' AND sequence=1", String.class));
        assertEquals(withdrawalDigest, jdbc.queryForObject(
                "SELECT SHA2(CONCAT(reason,':',actor_id,':',operation_key,':',outbox_state),256) "
                        + "FROM archive_edition_withdrawal WHERE withdrawal_id='upgrade-outbox-withdrawal'",
                String.class));
        assertEquals(privateDigest, jdbc.queryForObject(
                "SELECT SHA2(CONCAT(owner_jiacn,':',text,':',version),256) FROM archive_note "
                        + "WHERE note_id='423e4567-e89b-82d3-a456-426614174000'", String.class));
    }

    @Test
    void completePreviousMaintenanceSchemaUpgradesAdditivelyAndMalformedBreakpointFailsClosed() throws Exception {
        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        String ddl;
        try (var input = new ClassPathResource("db/archive-maintenance-schema.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        new ResourceDatabasePopulator(new ByteArrayResource(
                legacyWaitingSchema(ddl, false).getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_maintenance_job' AND column_name='target_agent_id'",
                Integer.class));
        assertEquals("NO", jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_maintenance_job' AND column_name='run_id'",
                String.class));
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) VALUES ('upgrade-work','title',NULL)");
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,"
                + "manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,"
                + "chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,"
                + "chapter_utf8_byte_length,reader_utf8_byte_length) VALUES ('upgrade-edition',"
                + "'upgrade-work','READY',?,?,?,1,1,0,1,1,0,1,1)", SHA, SHA, SHA);
        jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,"
                + "chapter_number,title,paragraph_count,utf8_byte_length,block_content_sha256) "
                + "VALUES ('upgrade-edition','upgrade-edition-c001','CHAPTER',1,1,'chapter',1,1,?)", SHA);
        jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                + "utf8_byte_length,sha256) VALUES ('upgrade-edition','upgrade-edition-c001',"
                + "'upgrade-edition-c001-p0001',1,'x',1,?)", SHA);
        jdbc.update("INSERT INTO archive_note(tenant_id,client_id,owner_jiacn,note_id,edition_id,state,"
                + "text,block_id,anchor_json,version) VALUES ('0','private-client','private-owner',"
                + "'323e4567-e89b-82d3-a456-426614174000','upgrade-edition','ACTIVE',"
                + "'private upgrade fact',NULL,NULL,1)");
        String privateDigest = jdbc.queryForObject(
                "SELECT SHA2(CONCAT(owner_jiacn,':',text,':',version),256) FROM archive_note "
                        + "WHERE note_id='323e4567-e89b-82d3-a456-426614174000'", String.class);

        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_edition_withdrawal'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_publication_readback'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_admin_operation_receipt'", Integer.class));
        assertEquals("YES", jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_maintenance_job' AND column_name='run_id'",
                String.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_maintenance_job' AND column_name='target_agent_id'",
                Integer.class));
        assertEquals(privateDigest, jdbc.queryForObject(
                "SELECT SHA2(CONCAT(owner_jiacn,':',text,':',version),256) FROM archive_note "
                        + "WHERE note_id='323e4567-e89b-82d3-a456-426614174000'", String.class));

        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ResourceDatabasePopulator(new ByteArrayResource(
                legacyWaitingSchema(ddl, true).getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_edition_withdrawal'", Integer.class));
        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_maintenance_job' AND column_name='target_agent_id'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_publication_readback'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                        + "AND table_name='archive_admin_operation_receipt'", Integer.class));

        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        new ResourceDatabasePopulator(new ByteArrayResource(
                legacyWaitingSchema(ddl, false).getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        jdbc.execute("ALTER TABLE archive_maintenance_job ADD COLUMN target_agent_id "
                + "VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL AFTER owner_jiacn");
        assertThrows(IllegalStateException.class, () -> new ArchiveMaintenanceSchemaInitializer(jdbc,
                new JdbcArchiveMaintenanceStore(jdbc), new ArchiveMaintenanceProperties()).initialize());
    }

    @Test
    void strictWaitingAndCancelledShapesRejectImpossibleRowsOnFreshAndUpgradedSchema() throws Exception {
        assertStrictWaitingShapesRejectImpossibleRows();

        clean();
        new ArchiveSchemaInitializer(jdbc).initialize();
        String ddl;
        try (var input = new ClassPathResource("db/archive-maintenance-schema.sql").getInputStream()) {
            ddl = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        new ResourceDatabasePopulator(new ByteArrayResource(
                previousWaitingShapeSchema(ddl).getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();

        assertStrictWaitingShapesRejectImpossibleRows();
    }

    @Test
    void withdrawalCasReplacementReplayAndPrivateFactsRemainImmutable() {
        seedPublishedVersions();
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        jdbc.update("INSERT INTO archive_reader_progress(tenant_id,client_id,owner_jiacn,edition_id,state,edition_manifest_sha256,block_type,block_id,paragraph_id,byte_offset,paragraph_sha256,version) VALUES ('0','reader-client','reader-owner','edition-a','IN_PROGRESS',?,'CHAPTER','edition-a-c001','edition-a-c001-p0001',0,?,1)", SHA, "b".repeat(64));
        jdbc.update("INSERT INTO archive_bookmark(tenant_id,client_id,owner_jiacn,bookmark_id,edition_id,state,edition_manifest_sha256,block_type,block_id,paragraph_id,byte_offset,paragraph_sha256,version) VALUES ('0','reader-client','reader-owner','123e4567-e89b-42d3-a456-426614174000','edition-a','ACTIVE',?,'CHAPTER','edition-a-c001','edition-a-c001-p0001',0,?,1)", SHA, "b".repeat(64));
        jdbc.update("INSERT INTO archive_note(tenant_id,client_id,owner_jiacn,note_id,edition_id,state,text,block_id,anchor_json,version) VALUES ('0','reader-client','reader-owner','223e4567-e89b-82d3-a456-426614174000','edition-a','ACTIVE','private note','edition-a-c001','{}',1)");
        String before = jdbc.queryForObject("SELECT SHA2(CONCAT((SELECT text FROM archive_paragraph WHERE paragraph_id='edition-a-c001-p0001'),':',(SELECT text FROM archive_note WHERE note_id='223e4567-e89b-82d3-a456-426614174000'),':',(SELECT COUNT(*) FROM archive_reader_progress),':',(SELECT COUNT(*) FROM archive_bookmark)),256)", String.class);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));

        ArchiveWithdrawalDTO first = service.withdraw(ACTOR, "work-withdraw", "edition-a", "withdraw-a", 1,
                new ArchiveWithdrawRequest("rights correction", "edition-b"));
        ArchiveWithdrawalDTO replay = service.withdraw(ACTOR, "work-withdraw", "edition-a", "withdraw-a", 1,
                new ArchiveWithdrawRequest("rights correction", "edition-b"));
        assertEquals(first, replay);
        assertEquals("NO_TARGET", first.outboxState());
        assertEquals("NO_TARGET", replay.outboxState());
        assertEquals("edition-b", first.resultingActiveEditionId());
        assertEquals("2", first.resultingWorkRevision());
        assertEquals("edition-b:2:WITHDRAWN", jdbc.queryForObject(
                "SELECT CONCAT(w.active_edition_id,':',cw.revision,':',p.state) FROM archive_work w JOIN archive_collection_work cw ON cw.work_id=w.work_id JOIN archive_publication p ON p.edition_id='edition-a' WHERE w.work_id='work-withdraw'", String.class));
        assertEquals("rights correction:owner-a:5:withdraw-a:NO_TARGET", jdbc.queryForObject(
                "SELECT CONCAT(reason,':',actor_id,':',authorization_revision,':',operation_key,':',outbox_state) FROM archive_edition_withdrawal WHERE publication_id='pub-edition-a'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_business_outbox "
                + "WHERE withdrawal_id=?", Integer.class, first.withdrawalId()),
                "a bootstrap publication without a maintenance job must terminate explicitly "
                        + "without inventing a chat target");
        ArchiveEditionHistoryDTO history = service.editionHistory(ACTOR, "work-withdraw");
        ArchiveEditionVersionDTO withdrawn = history.editions().stream()
                .filter(value -> "edition-a".equals(value.editionId())).findFirst().orElseThrow();
        assertEquals(SHA, withdrawn.manifestSha256());
        assertEquals("a".repeat(64), withdrawn.sourceSha256());
        assertEquals("HUMAN", withdrawn.actorType());
        assertEquals("WITHDRAWN", withdrawn.state());
        assertEquals("rights correction", withdrawn.withdrawal().reason());
        assertEquals("edition-b", withdrawn.withdrawal().resultingActiveEditionId());

        ArchiveWithdrawalDTO nonActive = service.withdraw(ACTOR, "work-withdraw", "edition-c", "withdraw-c", 2,
                new ArchiveWithdrawRequest("obsolete", null));
        assertEquals("edition-b", nonActive.resultingActiveEditionId());
        ArchiveWithdrawalDTO cleared = service.withdraw(ACTOR, "work-withdraw", "edition-b", "withdraw-b", 3,
                new ArchiveWithdrawRequest("withdraw current", null));
        assertNull(cleared.resultingActiveEditionId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_work WHERE work_id='work-withdraw' AND active_edition_id IS NOT NULL", Integer.class));
        String after = jdbc.queryForObject("SELECT SHA2(CONCAT((SELECT text FROM archive_paragraph WHERE paragraph_id='edition-a-c001-p0001'),':',(SELECT text FROM archive_note WHERE note_id='223e4567-e89b-82d3-a456-426614174000'),':',(SELECT COUNT(*) FROM archive_reader_progress),':',(SELECT COUNT(*) FROM archive_bookmark)),256)", String.class);
        assertEquals(before, after);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ArchiveMaintenanceException.class,
                () -> service.withdraw(ACTOR, "work-withdraw", "edition-a", "withdraw-a", 1,
                        new ArchiveWithdrawRequest("changed", "edition-b"))).code());
        ArchiveReaderServiceImpl reader = new ArchiveReaderServiceImpl(new JdbcArchiveContentStore(jdbc));
        assertThrows(cn.jia.chat.archive.service.ArchiveResourceGoneException.class,
                () -> reader.editionCatalog("edition-a"));
        assertThrows(cn.jia.chat.archive.service.ArchiveResourceNotFoundException.class,
                () -> reader.workCatalog("work-withdraw"));
    }

    @Test
    void editionHistoryReauthorizesAndReturnsOnlyAtomicPostWithdrawalSnapshot() throws Exception {
        seedPublishedVersions();
        CountDownLatch preflightRead = new CountDownLatch(1);
        CountDownLatch withdrawalCommitted = new CountDownLatch(1);
        AtomicInteger unlockedReads = new AtomicInteger();
        JdbcArchiveMaintenanceStore historyStore = new JdbcArchiveMaintenanceStore(jdbc) {
            @Override
            public List<cn.jia.chat.archive.maintenance.model.ArchiveEditionVersionRecord> listPublications(
                    String workId, boolean lock) {
                List<cn.jia.chat.archive.maintenance.model.ArchiveEditionVersionRecord> result =
                        super.listPublications(workId, lock);
                if (!lock && "work-withdraw".equals(workId)
                        && unlockedReads.incrementAndGet() == 1) {
                    preflightRead.countDown();
                    await(withdrawalCommitted);
                }
                return result;
            }
        };
        ArchiveMaintenanceServiceImpl historyService = service(historyStore,
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ArchiveMaintenanceServiceImpl withdrawalService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ArchiveEditionHistoryDTO> history = pool.submit(
                    () -> historyService.editionHistory(ACTOR, "work-withdraw"));
            assertTrue(preflightRead.await(5, TimeUnit.SECONDS));
            ArchiveWithdrawalDTO withdrawal = withdrawalService.withdraw(ACTOR, "work-withdraw",
                    "edition-a", "withdraw-during-history", 1,
                    new ArchiveWithdrawRequest("atomic history", "edition-b"));
            assertEquals("2", withdrawal.resultingWorkRevision());
            withdrawalCommitted.countDown();

            ArchiveEditionHistoryDTO result = history.get(10, TimeUnit.SECONDS);
            assertEquals("2", result.workRevision());
            assertEquals("edition-b", result.activeEditionId());
            assertEquals("WITHDRAWN", result.editions().stream()
                    .filter(value -> "edition-a".equals(value.editionId()))
                    .findFirst().orElseThrow().state());
            assertEquals("PUBLISHED", result.editions().stream()
                    .filter(value -> "edition-b".equals(value.editionId()))
                    .findFirst().orElseThrow().state());
        } finally {
            withdrawalCommitted.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void editionFinalReauthorizationRejectsPermissionRevokedDuringReaderIo() throws Exception {
        seedPublishedVersions();
        LatchingReadbackContentStore contentStore = new LatchingReadbackContentStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), contentStore);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> edition = pool.submit(() -> {
                try {
                    service.edition(ACTOR, "work-withdraw", "edition-a");
                    return "unexpected";
                } catch (ArchiveMaintenanceException failure) {
                    return failure.status() + ":" + failure.code();
                }
            });
            assertTrue(contentStore.readerEntered.await(5, TimeUnit.SECONDS),
                    "edition readback did not reach the Reader paragraph boundary");
            assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                    + "SET permissions='job.manage',revision=revision+1 "
                    + "WHERE collection_id=? AND tenant_id='0' AND client_id='client-a' "
                    + "AND owner_jiacn='owner-a' AND state='ACTIVE'", COLLECTION));
            contentStore.allowReaderReturn.countDown();

            assertEquals("403:ARCHIVE_FORBIDDEN", edition.get(10, TimeUnit.SECONDS));
        } finally {
            contentStore.allowReaderReturn.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void editionHistoryFinalSnapshotMatchesWithdrawalCommittedDuringReaderIo() throws Exception {
        seedPublishedVersions();
        LatchingReadbackContentStore contentStore = new LatchingReadbackContentStore(jdbc);
        ArchiveMaintenanceServiceImpl historyService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), contentStore);
        ArchiveMaintenanceServiceImpl withdrawalService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ArchiveEditionHistoryDTO> history = pool.submit(
                    () -> historyService.editionHistory(ACTOR, "work-withdraw"));
            assertTrue(contentStore.readerEntered.await(5, TimeUnit.SECONDS),
                    "history readback did not reach the Reader paragraph boundary");
            ArchiveWithdrawalDTO withdrawal = withdrawalService.withdraw(ACTOR, "work-withdraw",
                    "edition-a", "withdraw-during-reader", 1,
                    new ArchiveWithdrawRequest("atomic final history", "edition-b"));
            assertEquals("2", withdrawal.resultingWorkRevision());
            contentStore.allowReaderReturn.countDown();

            ArchiveEditionHistoryDTO result = history.get(10, TimeUnit.SECONDS);
            assertEquals("2", result.workRevision());
            assertEquals("edition-b", result.activeEditionId());
            ArchiveEditionVersionDTO withdrawn = result.editions().stream()
                    .filter(value -> "edition-a".equals(value.editionId()))
                    .findFirst().orElseThrow();
            assertEquals("WITHDRAWN", withdrawn.state());
            assertEquals("edition-b", withdrawn.withdrawal().resultingActiveEditionId());
            assertEquals("2:edition-b:WITHDRAWN", jdbc.queryForObject(
                    "SELECT CONCAT(cw.revision,':',w.active_edition_id,':',p.state) "
                            + "FROM archive_collection_work cw JOIN archive_work w ON w.work_id=cw.work_id "
                            + "JOIN archive_publication p ON p.work_id=w.work_id "
                            + "WHERE cw.collection_id=? AND cw.work_id='work-withdraw' "
                            + "AND p.edition_id='edition-a'", String.class, COLLECTION));
            assertEquals(jdbc.queryForObject("SELECT state FROM archive_publication_readback "
                            + "WHERE publication_id='pub-edition-a'", String.class),
                    withdrawn.verification().state());
            assertEquals(Long.toString(jdbc.queryForObject(
                            "SELECT revision FROM archive_publication_readback "
                                    + "WHERE publication_id='pub-edition-a'", Long.class)),
                    withdrawn.verification().revision());
            assertEquals(jdbc.queryForObject("SELECT verification_digest "
                            + "FROM archive_publication_readback "
                            + "WHERE publication_id='pub-edition-a'", String.class),
                    withdrawn.verification().verificationDigest());
            assertEquals(jdbc.queryForObject("SELECT findings_json "
                            + "FROM archive_publication_readback "
                            + "WHERE publication_id='pub-edition-a'", String.class),
                    new ObjectMapper().writeValueAsString(withdrawn.verification().findings()));
            assertEquals(jdbc.queryForObject("SELECT checked_at IS NOT NULL "
                            + "FROM archive_publication_readback "
                            + "WHERE publication_id='pub-edition-a'", Boolean.class),
                    withdrawn.verification().checkedAt() != null);
        } finally {
            contentStore.allowReaderReturn.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void adminOperationFinalReauthorizationRejectsPublishPermissionRevokedDuringReaderIo()
            throws Exception {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "job.manage,draft.write,validate,publish", COLLECTION);
        seedValidatedDraft("validation-operation-readback");
        ArchiveMaintenanceServiceImpl publishingService = service(
                new JdbcArchiveMaintenanceStore(jdbc), new RootLockingPort(jdbc),
                new JdbcArchiveContentStore(jdbc));
        ArchiveOperationAcceptedDTO accepted = publishingService.publishDraft(ACTOR, "draft-a",
                "operation-readback", 1,
                new ArchivePublishRequest("validation-operation-readback", null, "0"));
        assertEquals(1, jdbc.update("UPDATE archive_publication_readback SET state='PENDING',"
                + "revision=revision+1,verification_digest=NULL,findings_json='[]',checked_at=NULL "
                + "WHERE publication_id=(SELECT target_id FROM archive_operation "
                + "WHERE operation_key='operation-readback' AND tenant_id='0' "
                + "AND client_id='client-a' AND owner_jiacn='owner-a')"));
        LatchingReadbackContentStore contentStore = new LatchingReadbackContentStore(jdbc);
        ArchiveMaintenanceServiceImpl operationService = service(
                new JdbcArchiveMaintenanceStore(jdbc), new RootLockingPort(jdbc), contentStore);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> operation = pool.submit(() -> {
                try {
                    operationService.operation(ACTOR, accepted.operationId());
                    return "unexpected";
                } catch (ArchiveMaintenanceException failure) {
                    return failure.status() + ":" + failure.code();
                }
            });
            assertTrue(contentStore.readerEntered.await(5, TimeUnit.SECONDS),
                    "operation readback did not reach the Reader paragraph boundary");
            assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                    + "SET permissions='job.manage',revision=revision+1 "
                    + "WHERE collection_id=? AND tenant_id='0' AND client_id='client-a' "
                    + "AND owner_jiacn='owner-a' AND state='ACTIVE'", COLLECTION));
            contentStore.allowReaderReturn.countDown();

            assertEquals("403:ARCHIVE_FORBIDDEN", operation.get(10, TimeUnit.SECONDS));
        } finally {
            contentStore.allowReaderReturn.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void validationRecoveryReadsOnlyCurrentRevisionAndExactActorScope() {
        seedExecutionCandidate();
        jdbc.update("UPDATE archive_draft SET revision=2,state='CHANGES_REQUIRED' WHERE draft_id='draft-a'");
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,validation_digest,findings_json) VALUES ('validation-old','draft-a',1,'PASSED',?, '[]')", "b".repeat(64));
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,validation_digest,findings_json) VALUES ('validation-current','draft-a',2,'FAILED',?, '[\"current finding\"]')", "c".repeat(64));
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));

        ArchiveValidationDTO current = service.validation(ACTOR, "draft-a");

        assertEquals("validation-current", current.validationId());
        assertEquals("2", current.draftRevision());
        assertEquals("FAILED", current.outcome());
        assertEquals(List.of("current finding"), current.findings());
        ArchiveActorScope foreign = new ArchiveActorScope("0", "client-a", "owner-b");
        assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", assertThrows(ArchiveMaintenanceException.class,
                () -> service.validation(foreign, "draft-a")).code());
    }

    @Test
    void exactEb31260SchemaAddsReadbackWithoutChangingPrivateOrOperationFactsAndDriftFailsClosed() {
        seedExecutionCandidate();
        seedPublishedContent();
        new ArchiveReaderDataSchemaInitializer(jdbc).initialize();
        jdbc.update("INSERT INTO archive_note(tenant_id,client_id,owner_jiacn,note_id,edition_id,state,"
                + "text,block_id,anchor_json,version) VALUES ('0','private-client','private-owner',"
                + "'423e4567-e89b-82d3-a456-426614174000','edition-a','ACTIVE',"
                + "'private readback upgrade fact',NULL,NULL,1)");
        jdbc.update("INSERT INTO archive_operation(tenant_id,client_id,owner_jiacn,operation_key,"
                + "http_method,canonical_path,request_sha256,target_type,target_id,state) VALUES "
                + "('0','client-a','owner-a','upgrade-snapshot','PATCH',"
                + "'/archive/admin/v1/drafts/draft-a',?,'DRAFT','draft-a','COMMITTED')",
                "f".repeat(64));
        jdbc.update("INSERT INTO archive_admin_operation_receipt(operation_id,tenant_id,client_id,"
                + "owner_jiacn,operation_key,collection_id,job_id,draft_id,action,"
                + "authorization_revision,state,result_json,committed_at) VALUES "
                + "('admin-upgrade','0','client-a','owner-a','upgrade-snapshot',?,?,'draft-a',"
                + "'DRAFT_PATCH',3,'COMMITTED','{\"draftId\":\"draft-a\","
                + "\"validatedRevision\":null}',CURRENT_TIMESTAMP(6))", COLLECTION, JOB);
        String before = jdbc.queryForObject(
                "SELECT SHA2(CONCAT((SELECT content_sha256 FROM archive_draft WHERE draft_id='draft-a'),':',"
                        + "(SELECT text FROM archive_note WHERE note_id='423e4567-e89b-82d3-a456-426614174000'),':',"
                        + "(SELECT result_json FROM archive_admin_operation_receipt WHERE operation_id='admin-upgrade')),256)",
                String.class);
        jdbc.execute("DROP TABLE archive_business_outbox");
        jdbc.execute("DROP TABLE archive_publication_readback");
        jdbc.execute("ALTER TABLE archive_event DROP CHECK chk_archive_event_outbox, "
                + "ADD CONSTRAINT chk_archive_event_outbox CHECK "
                + "(outbox_state IN ('PENDING','DELIVERED'))");
        jdbc.execute("ALTER TABLE archive_edition_withdrawal "
                + "DROP CHECK chk_archive_withdrawal_outbox, "
                + "ADD CONSTRAINT chk_archive_withdrawal_outbox CHECK "
                + "(outbox_state IN ('PENDING','DELIVERED'))");

        new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                new ArchiveMaintenanceProperties()).initialize();

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='archive_publication_readback'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='archive_business_outbox'",
                Integer.class));
        assertEquals("3:3", jdbc.queryForObject(
                "SELECT CONCAT(COUNT(*),':',SUM(state='PENDING')) FROM archive_publication_readback",
                String.class));
        assertEquals(before, jdbc.queryForObject(
                "SELECT SHA2(CONCAT((SELECT content_sha256 FROM archive_draft WHERE draft_id='draft-a'),':',"
                        + "(SELECT text FROM archive_note WHERE note_id='423e4567-e89b-82d3-a456-426614174000'),':',"
                        + "(SELECT result_json FROM archive_admin_operation_receipt WHERE operation_id='admin-upgrade')),256)",
                String.class));

        jdbc.execute("ALTER TABLE archive_admin_operation_receipt MODIFY COLUMN result_json "
                + "TEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NULL");
        IllegalStateException drift = assertThrows(IllegalStateException.class, () ->
                new ArchiveMaintenanceSchemaInitializer(jdbc, new JdbcArchiveMaintenanceStore(jdbc),
                        new ArchiveMaintenanceProperties()).initialize());
        assertTrue(drift.getMessage().contains("archive_admin_operation_receipt.columns"),
                drift.getMessage());
    }

    @Test
    void exactAdminBlockPatchSnapshotsSurviveRevokedAppointmentAndManagedWorksKeepNullActiveHistory() throws Exception {
        seedExecutionCandidate();
        seedPublishedContent();
        jdbc.update("UPDATE archive_appointment SET status='REVOKED',revision=2,revoked_at=CURRENT_TIMESTAMP(6) "
                + "WHERE appointment_id=?", APPOINTMENT);
        jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=NULL,revision=2 "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'", COLLECTION);
        jdbc.update("UPDATE archive_publication SET state='WITHDRAWN' WHERE work_id='work-withdraw'");
        jdbc.update("UPDATE archive_work SET active_edition_id=NULL WHERE work_id='work-withdraw'");
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc),
                new JdbcArchiveContentStore(jdbc));
        ArchiveDraftBlockInput first = validDraft().blocks().getFirst();

        ArchiveDraftBlockDTO block = service.putDraftBlock(ACTOR, "draft-a", "one",
                "exact-block", 0, first);
        ArchiveDraftDTO patched = service.patchDraft(ACTOR, "draft-a", "exact-patch", 1,
                new ArchiveDraftPatchRequest(List.of(new ArchiveDraftBlockMetadataPatch(
                        "one", 1, "第一回", first.titleSourceRanges())), null));

        assertEquals("1", block.revision());
        assertEquals("2", patched.revision());
        assertNull(patched.validatedRevision());
        assertNull(patched.validationId());
        String operationId = jdbc.queryForObject("SELECT operation_id FROM archive_admin_operation_receipt "
                + "WHERE operation_key='exact-patch'", String.class);
        ArchiveAdminOperationDTO status = service.operation(ACTOR, operationId);
        assertEquals("COMMITTED", status.state());
        assertNull(status.result().get("validatedRevision"));
        assertNull(status.result().get("validationId"));
        assertEquals("COMMITTED:COMMITTED", jdbc.queryForObject(
                "SELECT CONCAT(o.state,':',r.state) FROM archive_operation o "
                        + "JOIN archive_admin_operation_receipt r ON r.tenant_id=o.tenant_id "
                        + "AND r.client_id=o.client_id AND r.owner_jiacn=o.owner_jiacn "
                        + "AND r.operation_key=o.operation_key WHERE o.operation_key='exact-patch'",
                String.class));
        assertTrue(jdbc.queryForObject("SELECT result_json IS NOT NULL FROM "
                + "archive_admin_operation_receipt WHERE operation_id=?", Boolean.class, operationId));
        ArchiveWorksDTO works = service.listWorks(ACTOR, COLLECTION, 20);
        assertTrue(works.items().stream().anyMatch(work -> "work-withdraw".equals(work.workId())
                && work.activeEditionId() == null && work.hasEditionHistory()));
        assertTrue(works.items().stream().anyMatch(work -> "work-a".equals(work.workId())
                && JOB.equals(work.pendingJobId())));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM archive_job_run WHERE job_id=?",
                Long.class, JOB));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM archive_execution_grant g "
                + "JOIN archive_job_run r ON r.run_id=g.run_id WHERE r.job_id=?", Long.class, JOB));
        ArchiveRuntimeScope oldRuntime = new ArchiveRuntimeScope("0", "client-a", "owner-a",
                AGENT, "runtime-old", "grant-old", "execution-old", "command-old", 1, 1);
        assertEquals("ARCHIVE_ASSIGNMENT_CHANGED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeDraft(oldRuntime, JOB, RUN)).code());
    }

    @Test
    void exactValidateReplaySurvivesLaterEditButRejectsChangedRequestAndCurrentRevocation()
            throws Exception {
        seedExecutionCandidate();
        String draftJson = new ObjectMapper().writeValueAsString(validDraft());
        jdbc.update("UPDATE archive_draft SET content_json=?,content_sha256=? WHERE draft_id='draft-a'",
                draftJson, "c".repeat(64));
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc));

        ArchiveOperationAcceptedDTO first = service.validateDraft(ACTOR, "draft-a",
                "exact-validate", 0);
        ArchiveAdminOperationDTO firstStatus = service.operation(ACTOR, first.operationId());
        String firstResult = jdbc.queryForObject("SELECT result_json FROM "
                + "archive_admin_operation_receipt WHERE operation_id=?",
                String.class, first.operationId());
        String validationEvent = jdbc.queryForObject("SELECT data_json FROM archive_event "
                + "WHERE job_id=? AND event_type='VALIDATION_FINISHED'", String.class, JOB);
        var event = new ObjectMapper().readTree(validationEvent);
        assertEquals("HUMAN", event.path("actorType").asText());
        assertEquals("owner-a", event.path("actorId").asText());
        assertEquals("3", event.path("authorizationRevision").asText());
        assertEquals("PASSED", event.path("outcome").asText());

        ArchiveDraftBlockInput firstBlock = validDraft().blocks().getFirst();
        ArchiveDraftDTO edited = service.patchDraft(ACTOR, "draft-a", "edit-after-validate", 0,
                new ArchiveDraftPatchRequest(List.of(new ArchiveDraftBlockMetadataPatch(
                        "one", 1, "第一回", firstBlock.titleSourceRanges())), null));
        assertEquals("1", edited.revision());

        ArchiveOperationAcceptedDTO replay = service.validateDraft(ACTOR, "draft-a",
                "exact-validate", 0);
        ArchiveAdminOperationDTO replayStatus = service.operation(ACTOR, replay.operationId());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(firstStatus.result(), replayStatus.result());
        assertEquals(firstResult, jdbc.queryForObject("SELECT result_json FROM "
                + "archive_admin_operation_receipt WHERE operation_id=?",
                String.class, replay.operationId()));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM archive_validation "
                + "WHERE draft_id='draft-a'", Long.class));

        ArchiveMaintenanceException changedRevision = assertThrows(ArchiveMaintenanceException.class,
                () -> service.validateDraft(ACTOR, "draft-a", "exact-validate", 1));
        assertEquals("IDEMPOTENCY_CONFLICT", changedRevision.code());
        ArchiveMaintenanceException changedPathAndBody = assertThrows(ArchiveMaintenanceException.class,
                () -> service.patchDraft(ACTOR, "draft-a", "exact-validate", 1,
                        new ArchiveDraftPatchRequest(List.of(new ArchiveDraftBlockMetadataPatch(
                                "one", 1, "changed", firstBlock.titleSourceRanges())), null)));
        assertEquals("IDEMPOTENCY_CONFLICT", changedPathAndBody.code());
        ArchiveMaintenanceException differentKeyCannotReplayStaleRevision =
                assertThrows(ArchiveMaintenanceException.class,
                        () -> service.validateDraft(ACTOR, "draft-a", "validate-new-key", 0));
        assertEquals("ARCHIVE_REVISION_CONFLICT", differentKeyCannotReplayStaleRevision.code());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM archive_operation "
                + "WHERE operation_key='validate-new-key'", Long.class),
                "the stale new-key validation reservation must roll back");

        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET state='REVOKED',revision=revision+1 WHERE collection_id=? "
                + "AND tenant_id='0' AND client_id='client-a' AND owner_jiacn='owner-a'",
                COLLECTION));
        ArchiveMaintenanceException revoked = assertThrows(ArchiveMaintenanceException.class,
                () -> service.validateDraft(ACTOR, "draft-a", "exact-validate", 0));
        assertEquals(403, revoked.status());
        assertEquals("ARCHIVE_FORBIDDEN", revoked.code());
    }

    @Test
    void managedWorksExcludeCancelledJobsButKeepFailedJobsAsExplicitRecoveryCandidates() {
        seedExecutionCandidate();
        JdbcArchiveMaintenanceStore store = new JdbcArchiveMaintenanceStore(jdbc);
        ArchiveMaintenanceServiceImpl service = service(store, new RootLockingPort(jdbc));

        assertEquals(1, jdbc.update("UPDATE archive_maintenance_job "
                + "SET state='CANCELLED',wait_reason='USER_CANCELLED' WHERE job_id=?", JOB));
        ArchiveWorksDTO cancelled = service.listWorks(ACTOR, COLLECTION, 20);
        assertFalse(cancelled.items().stream().anyMatch(work -> "work-a".equals(work.workId())),
                "a cancelled-only job without catalog history must not be enumerated");

        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) "
                + "VALUES ('work-a','title',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) "
                + "VALUES (?,'work-a','key-a',1)", COLLECTION);
        ArchiveWorksDTO cancelledCatalogWork = service.listWorks(ACTOR, COLLECTION, 20);
        ArchiveWorkSummaryDTO cancelledWork = cancelledCatalogWork.items().stream()
                .filter(work -> "work-a".equals(work.workId())).findFirst().orElseThrow();
        assertNull(cancelledWork.pendingJobId(),
                "a cancelled job must not populate pendingJobId for an existing catalog work");

        assertEquals(1, jdbc.update("UPDATE archive_maintenance_job "
                + "SET state='FAILED',wait_reason='EXECUTION_FAILED' WHERE job_id=?", JOB));
        ArchiveWorksDTO failed = service.listWorks(ACTOR, COLLECTION, 20);
        assertTrue(failed.items().stream().anyMatch(work -> "work-a".equals(work.workId())
                && JOB.equals(work.pendingJobId())),
                "FAILED remains visible because explicit resume/reassign can recover it");
    }

    @Test
    void repeatableReadRealPublishWinsBeforeWithdrawalAndStaleWorkCasLeavesEditionPublished() throws Exception {
        seedExecutionCandidate();
        seedPublishedContent();
        jdbc.update("UPDATE archive_collection_manager SET permissions=? WHERE collection_id=?",
                "appoint,job.manage,draft.write,validate,publish,edition.withdraw", COLLECTION);
        jdbc.update("UPDATE archive_maintenance_job SET state='AWAITING_PUBLISH',wait_reason=NULL,"
                + "operation_code='REVISE_WORK',work_id='work-withdraw',canonical_key='withdraw-key' "
                + "WHERE job_id=?", JOB);
        String draftJson = new ObjectMapper().writeValueAsString(validDraft());
        jdbc.update("UPDATE archive_draft SET revision=1,state='VALIDATED',content_json=?,content_sha256=?,"
                + "validated_revision=1,validation_id='validation-race' WHERE draft_id='draft-a'",
                draftJson, "c".repeat(64));
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,"
                + "validation_digest,findings_json) VALUES ('validation-race','draft-a',1,'PASSED',?,'[]')",
                "e".repeat(64));
        CountDownLatch publishLocked = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicInteger workLocks = new AtomicInteger();
        JdbcArchiveMaintenanceStore publishingStore = new JdbcArchiveMaintenanceStore(jdbc) {
            @Override
            public ArchiveMaintenanceStore.CollectionWork lockCollectionWork(String collectionId, String workId) {
                ArchiveMaintenanceStore.CollectionWork value = super.lockCollectionWork(collectionId, workId);
                if ("work-withdraw".equals(workId) && workLocks.incrementAndGet() == 1) {
                    publishLocked.countDown();
                    await(releasePublish);
                }
                return value;
            }
        };
        ArchiveMaintenanceServiceImpl publishingService = service(publishingStore,
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ArchiveMaintenanceServiceImpl withdrawalService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchivePublicationDTO> publish = pool.submit(() -> publishingService.publish(
                    ACTOR, JOB, "publish-race", 1,
                    new ArchivePublishRequest("validation-race", "edition-a", "1")));
            assertTrue(publishLocked.await(5, TimeUnit.SECONDS));
            Future<String> withdrawal = pool.submit(() -> {
                try {
                    withdrawalService.withdraw(ACTOR, "work-withdraw", "edition-a",
                            "withdraw-race", 1, new ArchiveWithdrawRequest("race", null));
                    return "unexpected";
                } catch (ArchiveMaintenanceException failure) {
                    return failure.code();
                }
            });
            releasePublish.countDown();
            ArchivePublicationDTO publication = publish.get(10, TimeUnit.SECONDS);
            assertEquals("ARCHIVE_REVISION_CONFLICT", withdrawal.get(10, TimeUnit.SECONDS));
            assertEquals(publication.editionId(), jdbc.queryForObject(
                    "SELECT active_edition_id FROM archive_work WHERE work_id='work-withdraw'", String.class));
        } finally {
            releasePublish.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "SELECT state FROM archive_publication WHERE edition_id='edition-a'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM archive_edition_withdrawal", Integer.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT revision FROM archive_collection_work WHERE collection_id=? AND work_id='work-withdraw'",
                Long.class, COLLECTION));
    }

    @Test
    void jobHandlingFactsUseCurrentJobReadAuthorityAndOnlyPersistedTrustedProgress() throws Exception {
        seedExecutionCandidate();
        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET permissions='job.manage' WHERE collection_id=?", COLLECTION));
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        enableReader(service);

        ArchiveJobDTO initial = service.getJob(ACTOR, JOB);
        assertEquals("title", initial.handling().title());
        assertEquals(COLLECTION, initial.handling().collectionId());
        assertEquals("source", initial.handling().source().sourceName());
        assertEquals("v1", initial.handling().source().sourceVersion());
        assertEquals("ACTIVE", initial.handling().assignmentStatus());
        assertEquals(new ArchiveJobHandlingFactsDTO.AssignmentSnapshot(
                APPOINTMENT, "1", AGENT, "DRAFT_ONLY"),
                initial.handling().assignmentSnapshot());
        assertEquals(AGENT, initial.handling().assignedAgentId());
        assertEquals("DRAFT_ONLY", initial.handling().permissionProfile());
        assertEquals("MANUAL", initial.handling().publicationMode());
        assertEquals("WAITING_SKILL", initial.handling().stage());
        assertEquals("CLIENT_UPDATE_REQUIRED", initial.handling().blocker());
        assertEquals("0", initial.handling().progress().completedChapters());
        assertFalse(initial.handling().progress().totalKnown());
        assertNull(initial.handling().progress().totalChapters());
        assertNull(initial.handling().currentPublication());
        assertEquals("job.manage", jdbc.queryForObject(
                "SELECT permissions FROM archive_collection_manager WHERE collection_id=?",
                String.class, COLLECTION));

        ArchiveDraftUpdateRequest partial = new ArchiveDraftUpdateRequest(
                List.of(validDraft().blocks().getFirst()), List.of());
        String partialJson = new ObjectMapper().writeValueAsString(partial);
        assertEquals(1, jdbc.update("UPDATE archive_draft SET revision=1,state='EDITABLE',"
                + "content_json=?,content_sha256=?,validated_revision=NULL,validation_id=NULL "
                + "WHERE draft_id='draft-a'", partialJson,
                cn.jia.chat.archive.content.ArchiveEtags.sha256(
                        partialJson.getBytes(StandardCharsets.UTF_8))));
        ArchiveJobDTO partialResult = service.getJob(ACTOR, JOB);
        assertEquals("1", partialResult.handling().progress().completedChapters());
        assertFalse(partialResult.handling().progress().totalKnown(),
                "a partial persisted draft is not a trusted total");
        assertNull(partialResult.handling().progress().totalChapters());

        ArchiveDraftUpdateRequest complete = validDraft();
        String completeJson = new ObjectMapper().writeValueAsString(complete);
        assertEquals(1, jdbc.update("UPDATE archive_draft SET revision=2,state='VALIDATED',"
                + "content_json=?,content_sha256=?,validated_revision=2,validation_id='validation-facts' "
                + "WHERE draft_id='draft-a'", completeJson,
                cn.jia.chat.archive.content.ArchiveEtags.sha256(
                        completeJson.getBytes(StandardCharsets.UTF_8))));
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,"
                + "validation_digest,findings_json) VALUES "
                + "('validation-facts','draft-a',2,'PASSED',?,'[]')", "e".repeat(64));
        assertEquals(1, jdbc.update("UPDATE archive_maintenance_job SET state='AWAITING_PUBLISH',"
                + "wait_reason=NULL,revision=2 WHERE job_id=?", JOB));
        ArchiveJobDTO validated = service.getJob(ACTOR, JOB);
        assertEquals("2", validated.handling().progress().completedChapters());
        assertTrue(validated.handling().progress().totalKnown());
        assertEquals("2", validated.handling().progress().totalChapters());

        seedJobPublicationFacts();
        ArchiveJobDTO pending = service.getJob(ACTOR, JOB);
        assertEquals("pub-job-a", pending.handling().currentPublication().receipt().publicationId());
        assertEquals(JOB, pending.handling().currentPublication().receipt().jobId());
        assertEquals("PENDING", pending.handling().currentPublication().verification().state());
        assertNull(pending.handling().currentPublication().readerTarget());

        assertEquals(1, jdbc.update("UPDATE archive_publication_readback SET state='FAILED',"
                + "revision=2,verification_digest=?,findings_json='[\"reader mismatch\"]',"
                + "checked_at=CURRENT_TIMESTAMP(6) WHERE publication_id='pub-job-a'", "f".repeat(64)));
        ArchiveJobDTO failed = service.getJob(ACTOR, JOB);
        assertEquals("FAILED", failed.handling().currentPublication().verification().state());
        assertNull(failed.handling().currentPublication().readerTarget());

        assertEquals(1, jdbc.update("UPDATE archive_publication_readback SET state='PASSED',"
                + "revision=3,verification_digest=?,findings_json='[]',"
                + "checked_at=CURRENT_TIMESTAMP(6) WHERE publication_id='pub-job-a'", "1".repeat(64)));
        ArchiveJobDTO passed = service.getJob(ACTOR, JOB);
        assertEquals("edition-job-a",
                passed.handling().currentPublication().readerTarget().editionId());
        assertEquals("work-a", passed.handling().currentPublication().readerTarget().workId());
        ArchiveMaintenanceServiceImpl readerDisabled = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        assertNull(readerDisabled.getJob(ACTOR, JOB).handling().currentPublication().readerTarget(),
                "a PASSED receipt does not itself grant current Reader access");

        assertEquals(1, jdbc.update("UPDATE archive_publication SET state='WITHDRAWN' "
                + "WHERE publication_id='pub-job-a'"));
        ArchiveJobDTO withdrawn = service.getJob(ACTOR, JOB);
        assertEquals("WITHDRAWN", withdrawn.handling().currentPublication().state());
        assertEquals("PASSED", withdrawn.handling().currentPublication().verification().state(),
                "immutable receipt and current verification remain distinct facts");
        assertNull(withdrawn.handling().currentPublication().readerTarget());

        seedPublicationJobMismatch();
        ArchiveMaintenanceException mismatched = assertThrows(ArchiveMaintenanceException.class,
                () -> service.getJob(ACTOR, JOB));
        assertEquals("ARCHIVE_PUBLICATION_CHANGED", mismatched.code());
    }

    @Test
    void waitingAndLeastPrivilegeJobFactsFailClosedForForeignOrRevokedScopes() {
        seedWaitingResolutionCandidate();
        ArchiveMaintenanceServiceImpl service = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));

        ArchiveJobDTO waiting = service.getJob(ACTOR, JOB);
        assertEquals("WAITING_ASSIGNEE", waiting.handling().stage());
        assertEquals("ASSIGNEE_REQUIRED", waiting.handling().blocker());
        assertNull(waiting.handling().assignedAgentId());
        assertEquals("0", waiting.handling().progress().completedChapters());
        assertFalse(waiting.handling().progress().totalKnown());
        assertNull(waiting.handling().progress().totalChapters());

        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET permissions='job.create',revision=revision+1 WHERE collection_id=?", COLLECTION));
        assertEquals(JOB, service.getJob(ACTOR, JOB).jobId(),
                "job.create is the other intentionally supported least-privilege read grant");
        for (ArchiveActorScope foreign : List.of(
                new ArchiveActorScope("1", "client-a", "owner-a"),
                new ArchiveActorScope("0", "client-b", "owner-a"),
                new ArchiveActorScope("0", "client-a", "owner-b"))) {
            ArchiveMaintenanceException hidden = assertThrows(ArchiveMaintenanceException.class,
                    () -> service.getJob(foreign, JOB));
            assertEquals(404, hidden.status());
            assertEquals("ARCHIVE_RESOURCE_NOT_FOUND", hidden.code());
        }
        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET state='REVOKED',revision=revision+1 WHERE collection_id=?", COLLECTION));
        ArchiveMaintenanceException revoked = assertThrows(ArchiveMaintenanceException.class,
                () -> service.getJob(ACTOR, JOB));
        assertEquals(403, revoked.status());
        assertEquals("ARCHIVE_FORBIDDEN", revoked.code());
    }

    @Test
    void jobFactsReauthorizeAfterConcurrentManagerRevocationBeforeLockedSnapshot() throws Exception {
        seedExecutionCandidate();
        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET permissions='job.manage' WHERE collection_id=?", COLLECTION));
        CountDownLatch lockingAuthorizationReached = new CountDownLatch(1);
        CountDownLatch revocationCommitted = new CountDownLatch(1);
        AtomicBoolean firstLock = new AtomicBoolean(true);
        JdbcArchiveMaintenanceStore latchingStore = new JdbcArchiveMaintenanceStore(jdbc) {
            @Override
            public cn.jia.chat.archive.maintenance.model.ArchiveManagerGrantRecord findManagerGrant(
                    ArchiveActorScope actor, String collectionId, boolean lock) {
                if (lock && COLLECTION.equals(collectionId) && firstLock.compareAndSet(true, false)) {
                    lockingAuthorizationReached.countDown();
                    await(revocationCommitted);
                }
                return super.findManagerGrant(actor, collectionId, lock);
            }
        };
        ArchiveMaintenanceServiceImpl service = service(latchingStore,
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> read = pool.submit(() -> {
                try {
                    service.getJob(ACTOR, JOB);
                    return "unexpected";
                } catch (ArchiveMaintenanceException failure) {
                    return failure.status() + ":" + failure.code();
                }
            });
            assertTrue(lockingAuthorizationReached.await(5, TimeUnit.SECONDS),
                    "job read did not reach its final locking authorization");
            assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                    + "SET state='REVOKED',revision=revision+1 WHERE collection_id=? "
                    + "AND state='ACTIVE'", COLLECTION));
            revocationCommitted.countDown();
            assertEquals("403:ARCHIVE_FORBIDDEN", read.get(10, TimeUnit.SECONDS));
        } finally {
            revocationCommitted.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void appointmentRevocationLinearizesBeforeJobFactsAndClearsOnlyCurrentAssignmentAuthority()
            throws Exception {
        seedExecutionCandidate();
        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET permissions='appoint,job.manage' WHERE collection_id=?", COLLECTION));
        seedJobPublicationFacts();
        assertEquals(1, jdbc.update("UPDATE archive_publication_readback SET state='PASSED',"
                + "revision=2,verification_digest=?,findings_json='[]',checked_at=CURRENT_TIMESTAMP(6) "
                + "WHERE publication_id='pub-job-a'", "1".repeat(64)));

        CountDownLatch lockingAuthorizationReached = new CountDownLatch(1);
        CountDownLatch revocationCommitted = new CountDownLatch(1);
        AtomicBoolean firstLock = new AtomicBoolean(true);
        JdbcArchiveMaintenanceStore readStore = new JdbcArchiveMaintenanceStore(jdbc) {
            @Override
            public cn.jia.chat.archive.maintenance.model.ArchiveManagerGrantRecord findManagerGrant(
                    ArchiveActorScope actor, String collectionId, boolean lock) {
                if (lock && COLLECTION.equals(collectionId) && firstLock.compareAndSet(true, false)) {
                    lockingAuthorizationReached.countDown();
                    await(revocationCommitted);
                }
                return super.findManagerGrant(actor, collectionId, lock);
            }
        };
        ArchiveMaintenanceServiceImpl readService = service(readStore,
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        enableReader(readService);
        ArchiveMaintenanceServiceImpl revokeService = service(new JdbcArchiveMaintenanceStore(jdbc),
                new RootLockingPort(jdbc), new JdbcArchiveContentStore(jdbc));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ArchiveJobDTO> read = pool.submit(() -> readService.getJob(ACTOR, JOB));
            assertTrue(lockingAuthorizationReached.await(5, TimeUnit.SECONDS),
                    "job facts did not reach their final locking authorization");
            ArchiveAppointmentDTO revoked = revokeService.revokeAppointment(ACTOR, APPOINTMENT,
                    "revoke-before-job-facts", 1,
                    new ArchiveAppointmentRevokeRequest("editor rotation"));
            assertEquals("REVOKED", revoked.status());
            revocationCommitted.countDown();

            ArchiveJobDTO result = read.get(10, TimeUnit.SECONDS);
            assertEquals("9", result.revision(), "appointment revocation must not rewrite job history");
            assertEquals("PUBLISHED", result.state());
            assertEquals("PUBLISHED", result.handling().stage());
            assertEquals("REVOKED", result.handling().assignmentStatus());
            assertNull(result.handling().assignedAgentId());
            assertNull(result.handling().permissionProfile());
            assertEquals("REASSIGNMENT_REQUIRED", result.handling().blocker());
            assertEquals(new ArchiveJobHandlingFactsDTO.AssignmentSnapshot(
                    APPOINTMENT, "1", AGENT, "DRAFT_ONLY"),
                    result.handling().assignmentSnapshot());
            assertEquals("edition-job-a",
                    result.handling().currentPublication().readerTarget().editionId(),
                    "revoking the producer appointment does not revoke an authorized published Reader target");
        } finally {
            revocationCommitted.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals("PUBLISHED:9", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', revision) FROM archive_maintenance_job WHERE job_id=?",
                String.class, JOB));
        assertEquals("REVOKED:2", jdbc.queryForObject(
                "SELECT CONCAT(status, ':', revision) FROM archive_appointment WHERE appointment_id=?",
                String.class, APPOINTMENT));
        assertNull(jdbc.queryForObject("SELECT current_appointment_id FROM archive_appointment_slot "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'", String.class, COLLECTION));
    }

    @Test
    void hostedBindingSuspensionLinearizesWithJobFactsAndClearsCurrentIdentityAuthority()
            throws Exception {
        seedExecutionCandidate();
        assertEquals(1, jdbc.update("UPDATE archive_collection_manager "
                + "SET permissions='job.manage' WHERE collection_id=?", COLLECTION));
        seedJobPublicationFacts();
        assertEquals(1, jdbc.update("UPDATE archive_publication_readback SET state='PASSED',"
                + "revision=2,verification_digest=?,findings_json='[]',checked_at=CURRENT_TIMESTAMP(6) "
                + "WHERE publication_id='pub-job-a'", "1".repeat(64)));
        RealIdentityFixture identity = seedRealIdentityFixture();

        CountDownLatch identityRootLocked = new CountDownLatch(1);
        CountDownLatch allowJobReadToContinue = new CountDownLatch(1);
        CountDownLatch suspensionLockAttempted = new CountDownLatch(1);
        AgentIdentityService latchingIdentity = mock(AgentIdentityService.class);
        when(latchingIdentity.lockBindingAuthority(
                "0", "client-a", "owner-a", 7L, AGENT)).thenAnswer(call -> {
                    AgentIdentityService.BindingAuthority authority =
                            identity.service.lockBindingAuthority(
                                    "0", "client-a", "owner-a", 7L, AGENT);
                    identityRootLocked.countDown();
                    await(allowJobReadToContinue);
                    return authority;
                });
        ArchiveMaintenanceServiceImpl readService = service(
                new JdbcArchiveMaintenanceStore(jdbc), new RootLockingPort(jdbc),
                new JdbcArchiveContentStore(jdbc), transactions, latchingIdentity);
        enableReader(readService);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ArchiveJobDTO> read = pool.submit(() -> readService.getJob(ACTOR, JOB));
            assertTrue(identityRootLocked.await(5, TimeUnit.SECONDS),
                    "job facts did not lock the persisted identity/binding root first");
            Future<?> suspension = pool.submit(() -> transactions.required(() -> {
                identity.suspendBinding(suspensionLockAttempted);
                return null;
            }));
            assertTrue(suspensionLockAttempted.await(5, TimeUnit.SECONDS),
                    "production identity suspension did not attempt the locked binding root");
            allowJobReadToContinue.countDown();

            ArchiveJobDTO beforeSuspensionCommit = read.get(10, TimeUnit.SECONDS);
            assertEquals("ACTIVE", beforeSuspensionCommit.handling().assignmentStatus(),
                    "a GET that owns the identity root linearizes before suspension");
            assertEquals(AGENT, beforeSuspensionCommit.handling().assignedAgentId());
            suspension.get(10, TimeUnit.SECONDS);
        } finally {
            allowJobReadToContinue.countDown();
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        ArchiveMaintenanceServiceImpl afterService = service(
                new JdbcArchiveMaintenanceStore(jdbc), new RootLockingPort(jdbc),
                new JdbcArchiveContentStore(jdbc), transactions, identity.service);
        enableReader(afterService);
        ArchiveJobDTO afterSuspension = afterService.getJob(ACTOR, JOB);
        assertEquals("BINDING_CHANGED", afterSuspension.handling().assignmentStatus());
        assertNull(afterSuspension.handling().assignedAgentId());
        assertNull(afterSuspension.handling().permissionProfile());
        assertEquals("REASSIGNMENT_REQUIRED", afterSuspension.handling().blocker());
        assertEquals(new ArchiveJobHandlingFactsDTO.AssignmentSnapshot(
                APPOINTMENT, "1", AGENT, "DRAFT_ONLY"),
                afterSuspension.handling().assignmentSnapshot());
        assertEquals("edition-job-a",
                afterSuspension.handling().currentPublication().readerTarget().editionId(),
                "identity suspension does not rewrite an independently authorized published edition");
        assertEquals("ACTIVE:1", jdbc.queryForObject(
                "SELECT CONCAT(status, ':', revision) FROM archive_appointment WHERE appointment_id=?",
                String.class, APPOINTMENT));
        assertEquals("PUBLISHED:9", jdbc.queryForObject(
                "SELECT CONCAT(state, ':', revision) FROM archive_maintenance_job WHERE job_id=?",
                String.class, JOB));
        assertEquals("0:SUSPENDED", jdbc.queryForObject(
                "SELECT CONCAT(b.status, ':', i.lifecycle_status) "
                        + "FROM agent_persona_binding b JOIN agent_identity_registry i "
                        + "ON i.binding_id=b.id WHERE b.id=7", String.class));
    }

    private void enableReader(ArchiveMaintenanceServiceImpl service) {
        cn.jia.chat.archive.config.ArchiveReaderProperties properties =
                new cn.jia.chat.archive.config.ArchiveReaderProperties();
        properties.setEnabled(true);
        service.setArchiveReaderAccessPolicy(
                cn.jia.chat.archive.config.ArchiveReaderAccessPolicy.from(properties));
    }

    private void seedJobPublicationFacts() {
        String manifest = "2".repeat(64);
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) "
                + "VALUES ('work-a','title',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) "
                + "VALUES (?,'work-a','key-a',1)", COLLECTION);
        jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,"
                + "manifest_sha256,manifest_file_sha256,source_utf8_byte_length,chapter_count,"
                + "preface_paragraph_count,chapter_paragraph_count,reader_paragraph_count,"
                + "preface_utf8_byte_length,chapter_utf8_byte_length,reader_utf8_byte_length) "
                + "VALUES ('edition-job-a','work-a','READY',?,?,?,6,2,0,2,2,0,6,6)",
                SHA, manifest, "3".repeat(64));
        for (int chapter = 1; chapter <= 2; chapter++) {
            String blockId = "edition-job-a-c00" + chapter;
            jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,"
                    + "chapter_number,title,paragraph_count,utf8_byte_length,block_content_sha256) "
                    + "VALUES ('edition-job-a',?,'CHAPTER',?,?,?,1,3,?)", blockId,
                    chapter, chapter, "chapter-" + chapter, "4".repeat(64));
            jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,"
                    + "utf8_byte_length,sha256) VALUES ('edition-job-a',?,?,1,?,3,?)", blockId,
                    blockId + "-p0001", chapter == 1 ? "甲" : "乙", "5".repeat(64));
        }
        assertEquals(1, jdbc.update("UPDATE archive_work SET active_edition_id='edition-job-a' "
                + "WHERE work_id='work-a' AND active_edition_id IS NULL"));
        jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,"
                + "edition_id,draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,"
                + "authorization_revision) VALUES ('pub-job-a',?,?, 'work-a','edition-job-a',2,?,?,"
                + "'PUBLISHED','HUMAN','owner-a',3)", JOB, COLLECTION, manifest, SHA);
        jdbc.update("INSERT INTO archive_publication_readback(publication_id,state,revision,"
                + "verification_digest,findings_json,checked_at) "
                + "VALUES ('pub-job-a','PENDING',1,NULL,'[]',NULL)");
        assertEquals(1, jdbc.update("UPDATE archive_maintenance_job SET publication_id='pub-job-a',"
                + "state='PUBLISHED',wait_reason=NULL,revision=9 WHERE job_id=?", JOB));
    }

    private void seedPublicationJobMismatch() {
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,run_id,collection_id,tenant_id,client_id,"
                + "owner_jiacn,appointment_id,appointment_revision,agent_id,binding_version,"
                + "permission_profile,manager_authorization_revision,publication_mode,operation_code,"
                + "work_id,canonical_key,title,source_id,source_sha256,source_summary,rights_basis,state,"
                + "wait_reason,revision,draft_id,request_intent_id,request_sha256) "
                + "VALUES ('job-b','run-b',?,'0','client-a','owner-a',?,1,?,'7','DRAFT_ONLY',3,"
                + "'MANUAL','ADD_WORK','work-a','key-b','title-b','source-a',?,'source / v1',"
                + "'authorized','WAITING_SKILL','CLIENT_UPDATE_REQUIRED',1,'draft-b','intent-b',?)",
                COLLECTION, APPOINTMENT, AGENT, SHA, "6".repeat(64));
        jdbc.update("INSERT INTO archive_job_run(run_id,job_id,execution_epoch,grant_revision,state,revision) "
                + "VALUES ('run-b','job-b',1,1,'WAITING',1)");
        jdbc.update("INSERT INTO archive_draft(draft_id,job_id,revision,state,content_json,content_sha256) "
                + "VALUES ('draft-b','job-b',0,'EDITABLE',"
                + "'{\"blocks\":[],\"excludedSourceRanges\":[]}',?)", "7".repeat(64));
        assertEquals(1, jdbc.update("UPDATE archive_publication SET job_id='job-b' "
                + "WHERE publication_id='pub-job-a'"));
    }

    private void seedWaitingResolutionCandidate() {
        jdbc.update("INSERT INTO aam_test_agent_root(agent_id) VALUES (?)", AGENT);
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,"
                + "permissions,state,revision) VALUES (?,'0','client-a','owner-a',"
                + "'appoint,job.manage','ACTIVE',3)", COLLECTION);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('source-wait',?,'0','client-a','owner-a',"
                + "'cyf-artifact://wait',?,?,'source','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, SHA, SOURCE.length);
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',NULL,0)", COLLECTION);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES (?,?,'ARCHIVE_EDITOR','0','client-a','owner-a',?,'7','COLLECTION','',"
                + "'DRAFT_ONLY','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                APPOINTMENT, COLLECTION, AGENT, SHA);
        assertEquals(1, jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=?,revision=1 "
                + "WHERE collection_id=? AND role_code='ARCHIVE_EDITOR' "
                + "AND current_appointment_id IS NULL AND revision=0", APPOINTMENT, COLLECTION));
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "target_agent_id,manager_authorization_revision,publication_mode,operation_code,"
                + "work_id,canonical_key,title,source_id,source_sha256,source_summary,rights_basis,"
                + "state,wait_reason,revision,request_intent_id,request_sha256) "
                + "VALUES (?,?,'0','client-a','owner-a',?,3,'MANUAL','ADD_WORK','work-wait',"
                + "'waiting-key','Waiting Work','source-wait',?,'source / v1','authorized',"
                + "'WAITING_ASSIGNEE','ASSIGNEE_REQUIRED',1,'waiting-race',?)",
                JOB, COLLECTION, AGENT, SHA, "b".repeat(64));
    }

    private String candidateRowCounts(String jobId) {
        return jdbc.queryForObject(
                "SELECT CONCAT((SELECT COUNT(*) FROM archive_job_run WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_draft WHERE job_id=?),':',"
                        + "(SELECT COUNT(*) FROM archive_execution_grant g JOIN archive_job_run r "
                        + "ON r.run_id=g.run_id WHERE r.job_id=?))",
                String.class, jobId, jobId, jobId);
    }

    private void assertStrictWaitingShapesRejectImpossibleRows() {
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,"
                + "normalization_rule,state) VALUES ('shape-source',?,'0','client-a','owner-a',"
                + "'cyf-artifact://shape',?,1,'shape','v1','authorized','UTF8_EXACT_V1','READY')",
                COLLECTION, SHA);
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?,'ARCHIVE_EDITOR',NULL,0)", COLLECTION);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,"
                + "owner_jiacn,agent_id,binding_version,work_scope_mode,work_ids,permission_profile,"
                + "required_skill_key,required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES ('shape-appointment',?,'ARCHIVE_EDITOR','0','client-a','owner-a','shape-agent',"
                + "'1','COLLECTION','','DRAFT_ONLY','archive-maintainer','1.0.0',?,'REVOKED',1)",
                COLLECTION, SHA);
        DataAccessException waitingShape = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                        + "manager_authorization_revision,publication_mode,operation_code,work_id,canonical_key,title,"
                        + "source_id,source_sha256,source_summary,rights_basis,state,wait_reason,revision,"
                        + "request_intent_id,request_sha256) VALUES ('shape-waiting',?,'0','client-a','owner-a',"
                        + "1,'MANUAL','ADD_WORK','shape-work','shape-key','shape-title','shape-source',?,"
                        + "'shape / v1','authorized','WAITING_INPUT','SOURCE_REQUIRED',1,'shape-waiting',?)",
                COLLECTION, SHA, "b".repeat(64)));
        assertTrue(waitingShape.getMostSpecificCause().getMessage()
                .contains("chk_archive_job_waiting_shape"), waitingShape.getMessage());
        DataAccessException cancelledShape = assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO archive_maintenance_job(job_id,collection_id,tenant_id,client_id,owner_jiacn,"
                        + "appointment_id,appointment_revision,agent_id,binding_version,permission_profile,"
                        + "manager_authorization_revision,publication_mode,operation_code,state,wait_reason,revision,"
                        + "request_intent_id,request_sha256) VALUES ('shape-cancelled',?,'0','client-a','owner-a',"
                        + "'shape-appointment',1,'shape-agent','1','DRAFT_ONLY',1,'MANUAL','ADD_WORK',"
                        + "'CANCELLED','USER_CANCELLED',1,'shape-cancelled',?)",
                COLLECTION, "c".repeat(64)));
        assertTrue(cancelledShape.getMostSpecificCause().getMessage()
                .contains("chk_archive_job_waiting_shape"), cancelledShape.getMessage());
    }

    private static String previousWaitingShapeSchema(String ddl) {
        String current = "    CONSTRAINT chk_archive_job_waiting_shape CHECK (((state='WAITING_INPUT') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (publication_id IS NULL) AND ((source_id IS NULL) OR (work_id IS NULL))) OR ((state='WAITING_ASSIGNEE') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL) AND (publication_id IS NULL)) OR ((state='CANCELLED') AND (publication_id IS NULL) AND (((run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL)) OR ((run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))) OR ((state NOT IN ('WAITING_INPUT','WAITING_ASSIGNEE','CANCELLED')) AND (run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))";
        String previous = "    CONSTRAINT chk_archive_job_waiting_shape CHECK (((state='WAITING_INPUT') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (publication_id IS NULL)) OR ((state='WAITING_ASSIGNEE') AND (run_id IS NULL) AND (draft_id IS NULL) AND (appointment_id IS NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL) AND (publication_id IS NULL)) OR (state='CANCELLED') OR ((state NOT IN ('WAITING_INPUT','WAITING_ASSIGNEE','CANCELLED')) AND (run_id IS NOT NULL) AND (draft_id IS NOT NULL) AND (appointment_id IS NOT NULL) AND (source_id IS NOT NULL) AND (work_id IS NOT NULL)))";
        if (!ddl.contains(current)) throw new IllegalStateException("Current waiting CHECK unavailable");
        return withoutPublicationReadback(ddl).replace(current, previous);
    }

    private static String legacyWaitingSchema(String ddl, boolean includeWithdrawal) {
        String result = withoutPublicationReadback(ddl).replaceFirst(
                "(?s)CREATE TABLE IF NOT EXISTS archive_maintenance_job \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;",
                java.util.regex.Matcher.quoteReplacement(legacyWaitingJobDdl()));
        result = result.replaceFirst(
                "(?s)CREATE TABLE IF NOT EXISTS archive_admin_operation_receipt \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\\s*", "");
        if (!includeWithdrawal) {
            result = result.replaceFirst(
                    "(?s)CREATE TABLE IF NOT EXISTS archive_edition_withdrawal \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\\s*", "");
        }
        return result;
    }

    private static String businessOutboxPredecessorSchema(String ddl) {
        String predecessor = ddl.replace(
                "outbox_state IN ('PENDING','DELIVERED','NO_TARGET')",
                "outbox_state IN ('PENDING','DELIVERED')")
                .replaceFirst("(?s)CREATE TABLE IF NOT EXISTS archive_business_outbox \\(.*?\\) "
                        + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\\s*", "");
        assertEquals("5f358f3cd71f5cd8c7ecc0c633ae3aa0770305e913fc1f0d711c5245847213c2",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(
                        predecessor.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)),
                "the outbox predecessor must remain byte-bound to 18d66419 after LF normalization");
        return predecessor;
    }

    private static String withoutPublicationReadback(String ddl) {
        String predecessor = businessOutboxPredecessorSchema(ddl).replaceFirst(
                "(?s)CREATE TABLE IF NOT EXISTS archive_publication_readback \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\\s*", "");
        assertEquals("c398de55270b8a7dd0467e796ae7a8dd0444458c51f0b437e7ac90f11a9c4118",
                cn.jia.chat.archive.content.ArchiveEtags.sha256(
                        predecessor.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)),
                "the additive predecessor must remain byte-bound to eb31260f after LF normalization");
        return predecessor;
    }

    private static String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) messages.append(current.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static String legacyWaitingJobDdl() {
        return """
                CREATE TABLE IF NOT EXISTS archive_maintenance_job (
                    job_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    collection_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    tenant_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    client_id VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    owner_jiacn VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    appointment_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    appointment_revision BIGINT NOT NULL,
                    agent_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    binding_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    permission_profile VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    manager_authorization_revision BIGINT NOT NULL,
                    publication_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    operation_code VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    work_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    canonical_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    title VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    source_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    source_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    source_summary VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    rights_basis VARCHAR(1000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    state VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    wait_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    revision BIGINT NOT NULL,
                    draft_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    publication_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
                    request_intent_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
                    request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (job_id),
                    UNIQUE KEY uk_archive_job_run (run_id),
                    UNIQUE KEY uk_archive_job_intent (tenant_id, client_id, owner_jiacn, request_intent_id),
                    KEY idx_archive_job_collection (collection_id, updated_at, job_id),
                    KEY fk_archive_job_appointment (appointment_id),
                    KEY fk_archive_job_source (source_id),
                    CONSTRAINT fk_archive_job_collection FOREIGN KEY (collection_id) REFERENCES archive_collection(collection_id),
                    CONSTRAINT fk_archive_job_appointment FOREIGN KEY (appointment_id) REFERENCES archive_appointment(appointment_id),
                    CONSTRAINT fk_archive_job_source FOREIGN KEY (source_id) REFERENCES archive_source_snapshot(source_id),
                    CONSTRAINT chk_archive_job_mode CHECK (publication_mode IN ('MANUAL','AUTO')),
                    CONSTRAINT chk_archive_job_revision CHECK (revision >= 1)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
                """;
    }

    private void seedPublishedVersions() {
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,permissions,state,revision) VALUES (?,'0','client-a','owner-a','edition.withdraw,publish','ACTIVE',5)", COLLECTION);
        seedPublishedContent();
    }

    private void seedPublishedContent() {
        jdbc.update("INSERT INTO archive_work(work_id,title,active_edition_id) VALUES ('work-withdraw','title',NULL)");
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) VALUES (?,'work-withdraw','withdraw-key',1)", COLLECTION);
        for (String edition : List.of("edition-a", "edition-b", "edition-c")) {
            jdbc.update("INSERT INTO archive_edition(edition_id,work_id,import_state,source_sha256,manifest_sha256,manifest_file_sha256,source_utf8_byte_length,chapter_count,preface_paragraph_count,chapter_paragraph_count,reader_paragraph_count,preface_utf8_byte_length,chapter_utf8_byte_length,reader_utf8_byte_length) VALUES (?,'work-withdraw','READY',?,?,?,1,1,0,1,1,0,1,1)", edition, "a".repeat(64), SHA, "c".repeat(64));
            jdbc.update("INSERT INTO archive_chapter(edition_id,block_id,block_type,reader_ordinal,chapter_number,title,paragraph_count,utf8_byte_length,block_content_sha256) VALUES (?,?,'CHAPTER',1,1,'chapter',1,1,?)", edition, edition + "-c001", "d".repeat(64));
            jdbc.update("INSERT INTO archive_paragraph(edition_id,block_id,paragraph_id,ordinal,text,utf8_byte_length,sha256) VALUES (?,?,?,1,'x',1,?)", edition, edition + "-c001", edition + "-c001-p0001", "b".repeat(64));
            jdbc.update("INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,draft_revision,manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision) VALUES (?,NULL,?,'work-withdraw',?,1,?,?,'PUBLISHED','HUMAN','owner-a',5)", "pub-" + edition, COLLECTION, edition, SHA, "a".repeat(64));
            jdbc.update("INSERT INTO archive_publication_readback(publication_id,state,revision,"
                    + "verification_digest,findings_json,checked_at) VALUES (?,'PENDING',1,NULL,'[]',NULL)",
                    "pub-" + edition);
        }
        assertEquals(1, jdbc.update("UPDATE archive_work SET active_edition_id='edition-a' "
                + "WHERE work_id='work-withdraw' AND active_edition_id IS NULL"));
    }

    private ArchiveRuntimeScope runtime() {
        ArchiveExecutionGrantRecord grant = new JdbcArchiveMaintenanceStore(jdbc)
                .findExecutionGrant(RUN, false);
        return new ArchiveRuntimeScope(grant.tenantId(), grant.clientId(), grant.ownerJiacn(),
                grant.agentId(), grant.runtimeInstanceId(), grant.grantRef(), grant.executionRef(),
                grant.commandId(), grant.activeAttempt(), grant.executionEpoch());
    }

    private void seedValidatedDraft(String validationId) throws Exception {
        String contentJson = new ObjectMapper().writeValueAsString(validDraft());
        String contentSha = cn.jia.chat.archive.content.ArchiveEtags.sha256(
                contentJson.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, jdbc.update("UPDATE archive_draft SET revision=1,state='VALIDATED',"
                + "content_json=?,content_sha256=?,validated_revision=1,validation_id=? "
                + "WHERE draft_id='draft-a'", contentJson, contentSha, validationId));
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,"
                + "validation_digest,findings_json) VALUES (?,'draft-a',1,'PASSED',?,'[]')",
                validationId, "e".repeat(64));
    }

    private ArchiveDraftUpdateRequest validDraft() {
        return new ArchiveDraftUpdateRequest(List.of(
                new ArchiveDraftBlockInput("CHAPTER", "one", 1, "第一回",
                        List.of(new ArchiveSourceRangeInput(0, 9)),
                        List.of(new ArchiveDraftParagraphInput(1, "甲",
                                List.of(new ArchiveSourceRangeInput(9, 12))))),
                new ArchiveDraftBlockInput("CHAPTER", "two", 2, "第二回",
                        List.of(new ArchiveSourceRangeInput(12, 21)),
                        List.of(new ArchiveDraftParagraphInput(1, "乙",
                                List.of(new ArchiveSourceRangeInput(21, 24)))))), List.of());
    }

    private ArchiveMaintenanceServiceImpl service(JdbcArchiveMaintenanceStore store,
            ArchiveAgentExecutionPort port) {
        return service(store, port, mock(ArchiveContentStore.class));
    }

    private ArchiveMaintenanceServiceImpl service(JdbcArchiveMaintenanceStore store,
            ArchiveAgentExecutionPort port, ArchiveContentStore contentStore) {
        return service(store, port, contentStore, transactions);
    }

    private ArchiveMaintenanceServiceImpl service(JdbcArchiveMaintenanceStore store,
            ArchiveAgentExecutionPort port, ArchiveContentStore contentStore,
            ArchiveTransactions archiveTransactions) {
        AgentIdentityService identities = mock(AgentIdentityService.class);
        when(identities.lockBindingAuthority(eq("0"), eq("client-a"), eq("owner-a"),
                anyLong(), anyString())).thenReturn(
                        AgentIdentityService.BindingAuthority.CURRENT);
        when(identities.requireActiveIdentityForBinding(eq("0"), eq("client-a"), eq("owner-a"),
                anyLong(), anyString())).thenAnswer(call -> new AgentIdentityRegistryEntity()
                        .setCanonicalAgentId(call.getArgument(4, String.class)));
        return service(store, port, contentStore, archiveTransactions, identities);
    }

    private ArchiveMaintenanceServiceImpl service(JdbcArchiveMaintenanceStore store,
            ArchiveAgentExecutionPort port, ArchiveContentStore contentStore,
            ArchiveTransactions archiveTransactions, AgentIdentityService identities) {
        AgentTaskArtifactStorage sourceStorage = mock(AgentTaskArtifactStorage.class);
        when(sourceStorage.store(any(), any(byte[].class), eq("text/plain")))
                .thenAnswer(call -> {
                    AgentTaskArtifactStorage.Scope scope = call.getArgument(0);
                    byte[] bytes = call.getArgument(1);
                    return new AgentTaskArtifactStorage.StoredObject(
                            "cyf-artifact://" + scope.taskId(),
                            cn.jia.chat.archive.content.ArchiveEtags.sha256(bytes),
                            bytes.length, "text/plain", true);
                });
        when(sourceStorage.matches(any(), anyString(), anyString())).thenReturn(true);
        when(sourceStorage.read(any(), anyString(), anyString(), anyLong(), anyString()))
                .thenAnswer(call -> {
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                            "source storage must be read outside the database transaction");
                    return new AgentTaskArtifactStorage.StoredContent(
                            SOURCE, SHA, SOURCE.length, "text/plain");
                });
        ArchiveMaintenanceServiceImpl service = new ArchiveMaintenanceServiceImpl(store,
                contentStore, archiveTransactions, identities, new ObjectMapper(),
                sourceStorage, Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));
        ArchiveMaintenanceProperties properties = new ArchiveMaintenanceProperties();
        properties.setExecutionEnabled(true);
        service.setArchiveMaintenanceProperties(properties);
        service.setArchiveAgentExecutionPort(port);
        return service;
    }

    private RealIdentityFixture seedRealIdentityFixture() {
        jdbc.execute("CREATE TABLE agent_persona_binding ("
                + "id BIGINT NOT NULL,jiacn VARCHAR(50) NOT NULL,persona_code VARCHAR(50) NOT NULL,"
                + "agent_id VARCHAR(100) NOT NULL,bound_at BIGINT NOT NULL,status INT NOT NULL,"
                + "tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,"
                + "PRIMARY KEY(id),CONSTRAINT chk_test_binding_status CHECK(status IN (0,1,2,3))) "
                + "ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE agent_identity_registry ("
                + "id BIGINT NOT NULL,canonical_agent_id VARCHAR(100) CHARACTER SET utf8mb4 "
                + "COLLATE utf8mb4_0900_bin NOT NULL,canonical_type VARCHAR(32) NOT NULL,"
                + "lifecycle_status VARCHAR(20) NOT NULL,tenant_id VARCHAR(50) NOT NULL,"
                + "client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,"
                + "binding_id BIGINT NOT NULL,provisioned_at BIGINT NOT NULL,activated_at BIGINT NOT NULL,"
                + "suspended_at BIGINT NULL,retired_at BIGINT NULL,audit_reason VARCHAR(1000) NOT NULL,"
                + "PRIMARY KEY(id),UNIQUE KEY uk_test_identity_binding(binding_id)) ENGINE=InnoDB");
        jdbc.update("INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,"
                + "tenant_id,client_id) VALUES (7,'owner-a','archive-editor',?,1,1,'0','client-a')",
                AGENT);
        jdbc.update("INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,"
                + "lifecycle_status,tenant_id,client_id,owner_jiacn,binding_id,provisioned_at,"
                + "activated_at,audit_reason) VALUES (11,?,'LEGACY_CANONICAL','ACTIVE','0',"
                + "'client-a','owner-a',7,1,2,'archive identity fixture')", AGENT);

        AgentPersonaBindingDao bindings = mock(AgentPersonaBindingDao.class);
        when(bindings.findByIdForUpdate(anyLong())).thenAnswer(call ->
                findIdentityBinding(call.getArgument(0), true));
        when(bindings.updateById(any(AgentPersonaBindingEntity.class))).thenAnswer(call -> {
            AgentPersonaBindingEntity binding = call.getArgument(0);
            return jdbc.update("UPDATE agent_persona_binding SET status=? WHERE id=?",
                    binding.getStatus(), binding.getId());
        });

        AgentIdentityRegistryDao registry = mock(AgentIdentityRegistryDao.class);
        when(registry.findExactByBindingInScope(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(call -> findIdentity(call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), false));
        when(registry.findExactByBindingInScopeForUpdate(
                anyString(), anyString(), anyString(), anyLong())).thenAnswer(call ->
                        findIdentity(call.getArgument(0), call.getArgument(1),
                                call.getArgument(2), call.getArgument(3), true));
        when(registry.suspendUsable(anyLong(), anyLong())).thenAnswer(call -> {
            long identityId = call.getArgument(0, Long.class);
            long suspendedAt = call.getArgument(1, Long.class);
            Object[] parameters = {suspendedAt, identityId};
            return jdbc.update(
                    "UPDATE agent_identity_registry SET lifecycle_status='SUSPENDED',suspended_at=? "
                            + "WHERE id=? AND lifecycle_status IN ('PROVISIONED','ACTIVE')",
                    parameters);
        });
        AgentIdentityAliasDao aliases = mock(AgentIdentityAliasDao.class);
        AgentIdentityServiceImpl target = new AgentIdentityServiceImpl(registry, aliases, bindings);
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(target);
        proxyFactory.setInterfaces(AgentIdentityService.class);
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource()));
        return new RealIdentityFixture(bindings, (AgentIdentityService) proxyFactory.getProxy());
    }

    private AgentPersonaBindingEntity findIdentityBinding(long bindingId, boolean lock) {
        List<AgentPersonaBindingEntity> rows = jdbc.query(
                "SELECT id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id "
                        + "FROM agent_persona_binding WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, rowNum) -> {
                    AgentPersonaBindingEntity binding = new AgentPersonaBindingEntity();
                    binding.setId(rs.getLong("id"));
                    binding.setJiacn(rs.getString("jiacn"));
                    binding.setPersonaCode(rs.getString("persona_code"));
                    binding.setAgentId(rs.getString("agent_id"));
                    binding.setBoundAt(rs.getLong("bound_at"));
                    binding.setStatus(rs.getInt("status"));
                    binding.setTenantId(rs.getString("tenant_id"));
                    binding.setClientId(rs.getString("client_id"));
                    return binding;
                }, bindingId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private AgentIdentityRegistryEntity findIdentity(String tenantId, String clientId,
            String ownerJiacn, long bindingId, boolean lock) {
        List<AgentIdentityRegistryEntity> rows = jdbc.query(
                "SELECT id,canonical_agent_id,canonical_type,lifecycle_status,tenant_id,client_id,"
                        + "owner_jiacn,binding_id,provisioned_at,activated_at,suspended_at,retired_at,"
                        + "audit_reason FROM agent_identity_registry WHERE tenant_id=? AND client_id=? "
                        + "AND owner_jiacn=? AND binding_id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, rowNum) -> {
                    AgentIdentityRegistryEntity identity = new AgentIdentityRegistryEntity();
                    identity.setId(rs.getLong("id"));
                    identity.setCanonicalAgentId(rs.getString("canonical_agent_id"));
                    identity.setCanonicalType(rs.getString("canonical_type"));
                    identity.setLifecycleStatus(rs.getString("lifecycle_status"));
                    identity.setTenantId(rs.getString("tenant_id"));
                    identity.setClientId(rs.getString("client_id"));
                    identity.setOwnerJiacn(rs.getString("owner_jiacn"));
                    identity.setBindingId(rs.getLong("binding_id"));
                    identity.setProvisionedAt(rs.getLong("provisioned_at"));
                    identity.setActivatedAt(rs.getLong("activated_at"));
                    identity.setSuspendedAt((Long) rs.getObject("suspended_at"));
                    identity.setRetiredAt((Long) rs.getObject("retired_at"));
                    identity.setAuditReason(rs.getString("audit_reason"));
                    return identity;
                }, tenantId, clientId, ownerJiacn, bindingId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private final class RealIdentityFixture {
        private final AgentPersonaBindingDao bindings;
        private final AgentIdentityService service;

        private RealIdentityFixture(AgentPersonaBindingDao bindings, AgentIdentityService service) {
            this.bindings = bindings;
            this.service = service;
        }

        private void suspendBinding(CountDownLatch lockAttempted) {
            lockAttempted.countDown();
            AgentPersonaBindingEntity binding = bindings.findByIdForUpdate(7L);
            assertEquals(AgentConstants.BINDING_STATUS_ACTIVE, binding.getStatus());
            binding.setStatus(AgentConstants.BINDING_STATUS_SUSPENDED);
            assertEquals(1, bindings.updateById(binding));
            service.suspendForBinding("0", "client-a", "owner-a", 7L);
        }
    }

    private void seedExecutionCandidate() {
        jdbc.update("INSERT INTO aam_test_agent_root(agent_id) VALUES (?)", AGENT);
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_collection WHERE collection_id=? AND code=? AND revision=1",
                Integer.class, COLLECTION, COLLECTION));
        jdbc.update("INSERT INTO archive_collection_manager(collection_id,tenant_id,client_id,owner_jiacn,permissions,state,revision) "
                + "VALUES (?,?,?,?,?,'ACTIVE',3)", COLLECTION, "0", "client-a", "owner-a",
                "appoint,job.manage,draft.write,validate");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM archive_appointment_slot WHERE collection_id=? AND role_code='ARCHIVE_EDITOR'",
                Integer.class, COLLECTION));
        jdbc.update("INSERT INTO archive_appointment_slot(collection_id,role_code,current_appointment_id,revision) "
                + "VALUES (?, 'ARCHIVE_EDITOR', ?, 1)", COLLECTION, APPOINTMENT);
        jdbc.update("INSERT INTO archive_appointment(appointment_id,collection_id,role_code,tenant_id,client_id,owner_jiacn,"
                + "agent_id,binding_version,work_scope_mode,work_ids,permission_profile,required_skill_key,"
                + "required_skill_version,required_skill_sha256,status,revision) "
                + "VALUES (?,?,'ARCHIVE_EDITOR','0','client-a','owner-a',?,'7','COLLECTION','',"
                + "'DRAFT_ONLY','archive-maintainer','1.0.0',?,'ACTIVE',1)",
                APPOINTMENT, COLLECTION, AGENT, SHA);
        jdbc.update("INSERT INTO archive_source_snapshot(source_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "storage_uri,raw_sha256,raw_byte_length,source_name,source_version,rights_basis,normalization_rule,state) "
                + "VALUES ('source-a',?,'0','client-a','owner-a','cyf-artifact://source',?,?,'source','v1',"
                + "'authorized','UTF8_EXACT_V1','READY')", COLLECTION, SHA, SOURCE.length);
        jdbc.update("INSERT INTO archive_maintenance_job(job_id,run_id,collection_id,tenant_id,client_id,owner_jiacn,"
                + "appointment_id,appointment_revision,agent_id,binding_version,permission_profile,"
                + "manager_authorization_revision,publication_mode,operation_code,work_id,canonical_key,title,"
                + "source_id,source_sha256,source_summary,rights_basis,state,wait_reason,revision,draft_id,"
                + "request_intent_id,request_sha256) VALUES (?,?,?,'0','client-a','owner-a',?,1,?,'7',"
                + "'DRAFT_ONLY',3,'MANUAL','ADD_WORK','work-a','key-a','title','source-a',?,"
                + "'source / v1','authorized','WAITING_SKILL','CLIENT_UPDATE_REQUIRED',1,'draft-a','intent-a',?)",
                JOB, RUN, COLLECTION, APPOINTMENT, AGENT, SHA, "b".repeat(64));
        jdbc.update("INSERT INTO archive_job_run(run_id,job_id,execution_epoch,grant_revision,state,revision) "
                + "VALUES (?,?,1,1,'WAITING',1)", RUN, JOB);
        jdbc.update("INSERT INTO archive_draft(draft_id,job_id,revision,state,content_json,content_sha256) "
                + "VALUES ('draft-a',?,0,'EDITABLE','{\"blocks\":[],\"excludedSourceRanges\":[]}',?)",
                JOB, "d".repeat(64));
    }

    private void lockAgentRoot() {
        assertEquals(AGENT, jdbc.queryForObject(
                "SELECT agent_id FROM aam_test_agent_root WHERE agent_id=? FOR UPDATE",
                String.class, AGENT));
    }

    private void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    statement.execute("DROP TABLE IF EXISTS aam_test_agent_root");
                    for (String table : new String[]{"agent_identity_registry", "agent_persona_binding",
                            "archive_idempotency", "archive_note", "archive_bookmark",
                            "archive_reader_progress", "archive_admin_operation_receipt", "archive_operation", "archive_business_outbox", "archive_event",
                            "archive_edition_withdrawal", "archive_publication_readback", "archive_publication",
                            "archive_validation", "archive_draft", "archive_execution_grant", "archive_job_run",
                            "archive_maintenance_job", "archive_confirmed_request", "archive_source_snapshot", "archive_appointment",
                            "archive_appointment_slot", "archive_collection_work", "archive_collection_manager",
                            "archive_collection", "archive_paragraph", "archive_chapter", "archive_edition",
                            "archive_work"}) {
                        statement.execute("DROP TABLE IF EXISTS " + table);
                    }
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency latch interrupted", interrupted);
        }
    }

    private static final class LatchingReadbackContentStore extends JdbcArchiveContentStore {
        final CountDownLatch readerEntered = new CountDownLatch(1);
        final CountDownLatch allowReaderReturn = new CountDownLatch(1);
        private final AtomicBoolean firstParagraphRead = new AtomicBoolean();

        LatchingReadbackContentStore(JdbcTemplate jdbc) { super(jdbc); }

        @Override
        public List<cn.jia.chat.archive.model.ArchiveParagraphRecord> listParagraphs(
                String editionId, String blockId) {
            if (firstParagraphRead.compareAndSet(false, true)) {
                readerEntered.countDown();
                await(allowReaderReturn);
            }
            return super.listParagraphs(editionId, blockId);
        }
    }

    private static final class ToggleFailingReadbackContentStore extends JdbcArchiveContentStore {
        private boolean fail = true;

        ToggleFailingReadbackContentStore(JdbcTemplate jdbc) { super(jdbc); }

        @Override
        public List<cn.jia.chat.archive.model.ArchiveParagraphRecord> listParagraphs(
                String editionId, String blockId) {
            if (fail) {
                throw new IllegalStateException("injected Reader dependency failure");
            }
            return super.listParagraphs(editionId, blockId);
        }
    }

    private static class RootLockingPort implements ArchiveAgentExecutionPort {
        private final JdbcTemplate jdbc;
        private final AtomicInteger rootAttempts = new AtomicInteger();
        final CountDownLatch firstRootAttempted = new CountDownLatch(1);
        final CountDownLatch secondRootAttempted = new CountDownLatch(1);
        volatile boolean expired;

        RootLockingPort(JdbcTemplate jdbc) { this.jdbc = jdbc; }

        @Override
        public LockedIdentityRoot lockIdentityRoot(TargetRequest request) {
            int attempt = rootAttempts.incrementAndGet();
            if (attempt == 1) firstRootAttempted.countDown();
            if (attempt == 2) secondRootAttempted.countDown();
            String locked = jdbc.queryForObject(
                    "SELECT agent_id FROM aam_test_agent_root WHERE agent_id=? FOR UPDATE",
                    String.class, request.canonicalAgent());
            if (!request.canonicalAgent().equals(locked)) throw new Denied("TARGET_FENCED");
            return new LockedIdentityRoot(request.tenant(), request.client(), request.owner(),
                    request.canonicalAgent(), request.binding());
        }

        @Override
        public LockedTarget requireControlledTarget(TargetRequest request, LockedIdentityRoot root) {
            return new LockedTarget(request.tenant(), request.client(), request.owner(),
                    request.canonicalAgent(), request.binding(), "runtime-a", new byte[32]);
        }

        @Override
        public Grant ensureExecution(Request request, LockedTarget lockedTarget) {
            return new Grant(request.grantRef(), request.executionRef(), "command-a", 1,
                    request.executionEpoch(), 2_000_000_000_000L, "runtime-a", new byte[32],
                    new InstalledSkillResolver.Proof("installation-a", 1, "archive-maintainer",
                            "1.0.0", ArchiveMaintenanceConcurrencyMySqlTest.SHA), false);
        }

        @Override public Inspection inspectExecution(Expected expected, LockedTarget lockedTarget) {
            if (expired) throw new Denied("ARCHIVE_EXECUTION_EXPIRED");
            return new Inspection(true, "STARTED", "message-a");
        }
        @Override public Inspection inspectResult(Expected expected, LockedTarget lockedTarget) {
            return new Inspection(true, "SUCCEEDED", "message-a");
        }
        @Override public AgentRawCommandDispatchResult dispatch(Expected expected, byte[] exactWire) {
            return AgentRawCommandDispatchResult.rejected();
        }
    }

    private static final class LateFailingStore extends JdbcArchiveMaintenanceStore {
        LateFailingStore(JdbcTemplate jdbc) { super(jdbc); }

        @Override
        public int updateJobState(String id, long expected, String state, String wait,
                String publicationId) {
            if ("RUNNING".equals(state)) return 0;
            return super.updateJobState(id, expected, state, wait, publicationId);
        }
    }

    private static final class LateFailingFailureStore extends JdbcArchiveMaintenanceStore {
        LateFailingFailureStore(JdbcTemplate jdbc) { super(jdbc); }

        @Override
        public int updateJobState(String id, long expected, String state, String wait,
                String publicationId) {
            if ("FAILED".equals(state)) return 0;
            return super.updateJobState(id, expected, state, wait, publicationId);
        }
    }

    private static final class BlockingResolveStore extends JdbcArchiveMaintenanceStore {
        private final CountDownLatch updated;
        private final CountDownLatch release;

        BlockingResolveStore(JdbcTemplate jdbc, CountDownLatch updated, CountDownLatch release) {
            super(jdbc);
            this.updated = updated;
            this.release = release;
        }

        @Override
        public int resolveWaitingJob(cn.jia.chat.archive.maintenance.model.ArchiveMaintenanceJobRecord job,
                long expectedRevision) {
            int rows = super.resolveWaitingJob(job, expectedRevision);
            if (rows == 1) {
                updated.countDown();
                await(release);
            }
            return rows;
        }
    }

    private static final class BlockingGrantStore extends JdbcArchiveMaintenanceStore {
        private final CountDownLatch inserted;
        private final CountDownLatch release;

        BlockingGrantStore(JdbcTemplate jdbc, CountDownLatch inserted, CountDownLatch release) {
            super(jdbc);
            this.inserted = inserted;
            this.release = release;
        }

        @Override
        public void insertExecutionGrant(ArchiveExecutionGrantRecord grant) {
            super.insertExecutionGrant(grant);
            inserted.countDown();
            await(release);
        }
    }
}
