package cn.jia.oauth.security;

import cn.jia.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantControlTransitionServiceTest {
    private final TenantId tenant = TenantId.of("1");
    private JdbcTemplate jdbc;
    private TenantControlTransitionService control;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:control_" + UUID.randomUUID()
                + ";MODE=MYSQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE core_tenant_control ("
                + "tenant_id VARCHAR(50) PRIMARY KEY, state VARCHAR(32), control_revision BIGINT,"
                + "tenant_session_epoch BIGINT, admission_epoch BIGINT, drain_epoch BIGINT,"
                + "drain_started_at TIMESTAMP, drain_deadline TIMESTAMP)");
        jdbc.execute("CREATE TABLE core_tenant_operation ("
                + "tenant_id VARCHAR(50), operator_id VARCHAR(128), idempotency_key VARCHAR(128),"
                + "request_hash VARCHAR(64), expected_revision BIGINT, from_state VARCHAR(32),"
                + "to_state VARCHAR(32), result_revision BIGINT, result_snapshot VARCHAR(200),"
                + "PRIMARY KEY (tenant_id, operator_id, idempotency_key))");
        jdbc.update("INSERT INTO core_tenant_control VALUES ('1','ENABLED',0,3,7,1,NULL,NULL)");
        control = new TenantControlTransitionService(jdbc);
    }

    @Test
    void callerRollbackRestoresControlAndRemovesOperationTogether() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(
                jdbc.getDataSource()));
        outer.executeWithoutResult(status -> {
            var result = control.transition(tenant, "ops", "outer-rollback",
                    TenantControlTransitionService.Action.CLOSE_AUTH, "audit failed", 0L, null);
            assertEquals(1, result.controlRevision());
            assertEquals(1, countOperations());
            status.setRollbackOnly();
        });
        assertEquals("ENABLED", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        assertEquals(0, value("SELECT control_revision FROM core_tenant_control"));
        assertEquals(0, countOperations());
        var committed = control.transition(tenant, "ops", "outer-rollback",
                TenantControlTransitionService.Action.CLOSE_AUTH, "audit failed", 0L, null);
        assertEquals(1, committed.controlRevision());
        assertFalse(committed.replay());
        assertEquals(1, countOperations());
    }

    @Test
    void fullClosePathAdvancesRevisionAndTracksEpochs() {
        var a = control.transition(tenant, "ops", "k1", TenantControlTransitionService.Action.CLOSE_AUTH,
                "nightly", 0L, null);
        assertEquals(TenantControlTransitionService.State.AUTH_CLOSED, a.state());
        assertEquals(1, a.controlRevision());
        assertEquals(3, a.tenantSessionEpoch());
        assertEquals(7, a.admissionEpoch());

        var w = control.transition(tenant, "ops", "k2", TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS,
                "shutdown", 1L, 3600L);
        assertEquals(TenantControlTransitionService.State.WRITE_CLOSED_CLAIM_CLOSED, w.state());
        assertEquals(2, w.controlRevision());
        assertEquals(3, w.tenantSessionEpoch());
        assertEquals(8, w.admissionEpoch());
        var started = jdbc.queryForObject("SELECT drain_started_at FROM core_tenant_control", java.sql.Timestamp.class);
        var deadline = jdbc.queryForObject("SELECT drain_deadline FROM core_tenant_control", java.sql.Timestamp.class);
        assertEquals(3600, deadline.toInstant().getEpochSecond() - started.toInstant().getEpochSecond());

        assertEquals(8, value("SELECT admission_epoch FROM core_tenant_control"));
        assertTrue(flag("SELECT drain_started_at IS NOT NULL FROM core_tenant_control"));
        assertTrue(flag("SELECT drain_deadline IS NOT NULL FROM core_tenant_control"));

        var d = control.transition(tenant, "ops", "k3", TenantControlTransitionService.Action.BEGIN_DRAIN,
                "drain", 2L, null);
        assertEquals(started, jdbc.queryForObject("SELECT drain_started_at FROM core_tenant_control", java.sql.Timestamp.class));
        assertEquals(deadline, jdbc.queryForObject("SELECT drain_deadline FROM core_tenant_control", java.sql.Timestamp.class));

        assertEquals(TenantControlTransitionService.State.DRAINING, d.state());
        assertEquals(3, d.controlRevision());

        assertThrows(TenantControlTransitionService.ConflictException.class,
                () -> control.transition(tenant, "ops", "k4", TenantControlTransitionService.Action.DISABLE, "done", 3L, null));
        assertEquals(deadline, jdbc.queryForObject("SELECT drain_deadline FROM core_tenant_control", java.sql.Timestamp.class));

        assertEquals("DRAINING", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        assertEquals(3, value("SELECT control_revision FROM core_tenant_control"));
        assertEquals(3, countOperations());
    }

    @Test
    void emergencySkipFromEnabledToWriteClosedIsLegalAndBumpsAdmissionEpoch() {
        var result = control.transition(tenant, "ops", "k1",
                TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS, "emergency", 0L, 60L);
        assertEquals(TenantControlTransitionService.State.WRITE_CLOSED_CLAIM_CLOSED, result.state());
        assertEquals(1, result.controlRevision());
        assertEquals(8, result.admissionEpoch());
    }

    @Test
    void enableRemainsClosedUntilDrainAndReopenPreflightAreInstalled() {
        jdbc.update("UPDATE core_tenant_control SET state='DISABLED', control_revision=4 WHERE tenant_id='1'");
        assertThrows(TenantControlTransitionService.ConflictException.class,
                () -> control.transition(tenant, "ops", "k5", TenantControlTransitionService.Action.ENABLE, "c", 4L, null));
        assertEquals("DISABLED", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        assertEquals(3, value("SELECT tenant_session_epoch FROM core_tenant_control"));
        assertEquals(0, countOperations());
    }

    @Test
    void illegalTransitionsAreRejectedWithoutChangingState() {
        assertThrows(TenantControlTransitionService.ConflictException.class,
                () -> control.transition(tenant, "ops", "k1", TenantControlTransitionService.Action.DISABLE, "bad", 0L, null));
        assertThrows(TenantControlTransitionService.ConflictException.class,
                () -> control.transition(tenant, "ops", "k2", TenantControlTransitionService.Action.ENABLE, "bad", 0L, null));
        assertEquals("ENABLED", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        assertEquals(0, value("SELECT control_revision FROM core_tenant_control"));
    }

    @Test
    void staleIfMatchFailsClosedAndIsNotMaskedByOtherReasons() {
        control.transition(tenant, "ops", "k1", TenantControlTransitionService.Action.CLOSE_AUTH, "first", 0L, null);
        assertThrows(TenantControlTransitionService.PreconditionFailedException.class,
                () -> control.transition(tenant, "ops", "k2", TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS,
                        "stale", 0L, 60L));
        assertEquals("AUTH_CLOSED", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        assertEquals(1, value("SELECT control_revision FROM core_tenant_control"));
        assertThrows(TenantControlTransitionService.PreconditionFailedException.class,
                () -> control.transition(tenant, "ops", "k3", TenantControlTransitionService.Action.CLOSE_AUTH,
                        "stale and no longer legal", 0L, null));
    }

    @Test
    void missingIfMatchIsRejected() {
        assertThrows(TenantControlTransitionService.PreconditionRequiredException.class,
                () -> control.transition(tenant, "ops", "k1", TenantControlTransitionService.Action.CLOSE_AUTH, "c", null, null));
    }

    @Test
    void idempotentReplayReturnsOriginalResultAndDoesNotChangeState() {
        var first = control.transition(tenant, "ops", "key", TenantControlTransitionService.Action.CLOSE_AUTH, "c", 0L, null);
        var replay = control.transition(tenant, "ops", "key", TenantControlTransitionService.Action.CLOSE_AUTH, "c", 0L, null);
        assertTrue(replay.replay());
        assertEquals(first.state(), replay.state());
        assertEquals(first.controlRevision(), replay.controlRevision());
        assertEquals(first.tenantSessionEpoch(), replay.tenantSessionEpoch());
        assertEquals(1, countOperations());
        assertEquals(1, value("SELECT control_revision FROM core_tenant_control"));
    }

    @Test
    void idempotencyKeyReuseWithDifferentRequestIsConflict() {
        control.transition(tenant, "ops", "key", TenantControlTransitionService.Action.CLOSE_AUTH, "one", 0L, null);
        assertThrows(TenantControlTransitionService.ConflictException.class,
                () -> control.transition(tenant, "ops", "key", TenantControlTransitionService.Action.CLOSE_AUTH, "two", 0L, null));
        assertEquals(1, countOperations());
    }

    @Test
    void competingTransitionsSerializeOnTheControlRow() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                return control.transition(tenant, "ops", "a", TenantControlTransitionService.Action.CLOSE_AUTH, "c", 0L, null);
            });
            var second = executor.submit(() -> {
                start.await();
                return control.transition(tenant, "ops", "b", TenantControlTransitionService.Action.CLOSE_AUTH, "c", 0L, null);
            });
            start.countDown();
            int success = 0;
            int precondition = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertEquals(1, future.get(10, TimeUnit.SECONDS).controlRevision());
                    success++;
                } catch (java.util.concurrent.ExecutionException failed) {
                    assertTrue(failed.getCause() instanceof TenantControlTransitionService.ControlException);
                    precondition++;
                }
            }
            assertEquals(1, success);
            assertEquals(1, precondition);
            assertEquals(1, value("SELECT control_revision FROM core_tenant_control"));
            assertEquals(1, countOperations());
        }
    }

    @Test
    void invalidTenantIdAndReasonAreRejectedBeforeAnyWrite() {
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(TenantId.of("01"), "ops", "k", TenantControlTransitionService.Action.CLOSE_AUTH, "c", 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "k", TenantControlTransitionService.Action.CLOSE_AUTH, "c\tbad", 0L, null));
        assertEquals(0, countOperations());
    }

    @Test
    void closeWritesRequiresAPositiveBoundedTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "k", TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS,
                        "c", 0L, null));
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "k", TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS,
                        "c", 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "k", TenantControlTransitionService.Action.CLOSE_WRITES_AND_CLAIMS,
                        "c", 0L, 31_536_001L));
        assertEquals(0, countOperations());
    }

    @Test
    void unrelatedActionCannotSilentlyIgnoreDrainTimeoutOrReuseItsIdempotencyKey() {
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "same", TenantControlTransitionService.Action.CLOSE_AUTH,
                        "close", 0L, 60L));
        assertEquals(0, countOperations());
        assertEquals("ENABLED", jdbc.queryForObject("SELECT state FROM core_tenant_control", String.class));
        var accepted = control.transition(tenant, "ops", "same", TenantControlTransitionService.Action.CLOSE_AUTH,
                "close", 0L, null);
        assertEquals(TenantControlTransitionService.State.AUTH_CLOSED, accepted.state());
        assertEquals(1, countOperations());
        assertThrows(IllegalArgumentException.class,
                () -> control.transition(tenant, "ops", "same", TenantControlTransitionService.Action.CLOSE_AUTH,
                        "close", 0L, 120L));
        assertEquals(1, countOperations());
    }

    private long countOperations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM core_tenant_operation", Long.class);
    }

    private long value(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private boolean flag(String sql) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class));
    }
}
