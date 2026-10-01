package cn.jia.chat.archive.maintenance.service;

import cn.jia.agent.entity.AgentIdentityRegistryEntity;
import cn.jia.agent.entity.AgentRawCommandDispatchResult;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.ArchiveAgentExecutionPort;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceProperties;
import cn.jia.chat.archive.maintenance.config.ArchiveMaintenanceSchemaInitializer;
import cn.jia.chat.archive.maintenance.dto.*;
import cn.jia.chat.archive.maintenance.model.ArchiveActorScope;
import cn.jia.chat.archive.maintenance.model.ArchiveConfirmedPolicyRef;
import cn.jia.chat.archive.maintenance.model.ArchiveRequestContext;
import cn.jia.chat.archive.maintenance.model.ArchiveExecutionGrantRecord;
import cn.jia.chat.archive.maintenance.model.ArchiveRuntimeScope;
import cn.jia.chat.archive.maintenance.store.JdbcArchiveMaintenanceStore;
import cn.jia.chat.archive.service.ArchiveMySqlTestGuard;
import cn.jia.chat.archive.service.ArchiveTransactions;
import cn.jia.chat.archive.service.SpringArchiveTransactions;
import cn.jia.chat.archive.store.ArchiveContentStore;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
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
                contentStore, transactions, identities, new ObjectMapper(),
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
                    for (String table : new String[]{"archive_operation", "archive_event", "archive_publication",
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
