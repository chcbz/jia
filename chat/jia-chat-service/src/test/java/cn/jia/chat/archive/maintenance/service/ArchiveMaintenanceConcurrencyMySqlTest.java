package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
        assertEquals(1, eventCount("PUBLICATION_COMMITTED"));
        assertEquals("EXECUTION_FENCED", assertThrows(ArchiveMaintenanceException.class,
                () -> service.runtimeUpdateDraft(scope, JOB, RUN, "write-after-publish", 1,
                        validDraft())).code());
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
        assertEquals("edition-b", first.resultingActiveEditionId());
        assertEquals("2", first.resultingWorkRevision());
        assertEquals("edition-b:2:WITHDRAWN", jdbc.queryForObject(
                "SELECT CONCAT(w.active_edition_id,':',cw.revision,':',p.state) FROM archive_work w JOIN archive_collection_work cw ON cw.work_id=w.work_id JOIN archive_publication p ON p.edition_id='edition-a' WHERE w.work_id='work-withdraw'", String.class));
        assertEquals("rights correction:owner-a:5:withdraw-a:PENDING", jdbc.queryForObject(
                "SELECT CONCAT(reason,':',actor_id,':',authorization_revision,':',operation_key,':',outbox_state) FROM archive_edition_withdrawal WHERE publication_id='pub-edition-a'", String.class));
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
        return ddl.replace(current, previous);
    }

    private static String legacyWaitingSchema(String ddl, boolean includeWithdrawal) {
        String result = ddl.replaceFirst(
                "(?s)CREATE TABLE IF NOT EXISTS archive_maintenance_job \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;",
                java.util.regex.Matcher.quoteReplacement(legacyWaitingJobDdl()));
        if (!includeWithdrawal) {
            result = result.replaceFirst(
                    "(?s)CREATE TABLE IF NOT EXISTS archive_edition_withdrawal \\(.*?\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;\\s*", "");
        }
        return result;
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
        when(identities.requireActiveIdentityForBinding(eq("0"), eq("client-a"), eq("owner-a"),
                anyLong(), anyString())).thenAnswer(call -> new AgentIdentityRegistryEntity()
                        .setCanonicalAgentId(call.getArgument(4)));
        AgentTaskArtifactStorage sourceStorage = mock(AgentTaskArtifactStorage.class);
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
                    for (String table : new String[]{"archive_idempotency", "archive_note", "archive_bookmark",
                            "archive_reader_progress", "archive_operation", "archive_event",
                            "archive_edition_withdrawal", "archive_publication",
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
