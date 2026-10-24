package cn.jia.tenantcard;

import cn.jia.chat.service.ConversationAccountAdmissionGuard;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCardA06bTest {
    private static final long UID = 101L;
    private static final long AUTH_EPOCH = 7L;
    private static final long WAIT_SECONDS = 10L;
    private static final String EPOCH_UPDATE_MARKER = "A06B_EPOCH_CHANGE";

    @Test
    void h2RowLockSurvivesGuardUntilCallerCommitAndBusinessWriteCommits() throws Exception {
        verifyH2LockLifetimeAndCallerTransaction(false);
    }

    @Test
    void h2RowLockSurvivesGuardUntilCallerRollbackAndBusinessWriteRollsBack() throws Exception {
        verifyH2LockLifetimeAndCallerTransaction(true);
    }

    @Test
    void invalidH2AccountSkipsCallback() {
        Fixture fixture = fixture(AUTH_EPOCH + 1);
        AtomicInteger callbacks = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> fixture.transaction.executeWithoutResult(status -> {
            fixture.guard.requireCurrent(scope());
            callbacks.incrementAndGet();
            fixture.jdbc.update("INSERT INTO business_marker(id) VALUES (1)");
        }));

        assertEquals(0, callbacks.get());
        assertEquals(0, countBusinessMarkers(fixture.jdbc));
        assertEquals(AUTH_EPOCH + 1, currentEpoch(fixture.jdbc));
    }

    /**
     * This is deliberately H2-only offline evidence. It proves the caller-transaction
     * lifetime of H2 FOR UPDATE; it does not claim MySQL FOR SHARE behavior.
     */
    private static void verifyH2LockLifetimeAndCallerTransaction(boolean rollback) throws Exception {
        Fixture fixture = fixture(AUTH_EPOCH);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallerTransaction = new CountDownLatch(1);
        CountDownLatch updaterAttempting = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        AtomicInteger holderSessionId = new AtomicInteger();
        AtomicInteger updaterSessionId = new AtomicInteger();
        Future<?> holder = null;
        Future<Integer> updater = null;

        try {
            holder = workers.submit(() -> fixture.transaction.executeWithoutResult(status -> {
                holderSessionId.set(sessionId(fixture.jdbc));
                fixture.guard.requireCurrent(scope());
                callbacks.incrementAndGet();
                fixture.jdbc.update("INSERT INTO business_marker(id) VALUES (1)");
                callbackEntered.countDown();
                await(releaseCallerTransaction, "caller transaction was not released");
                if (rollback) {
                    status.setRollbackOnly();
                }
            }));

            await(callbackEntered, "guard-protected callback did not run");
            assertEquals(1, callbacks.get());
            assertEquals(0, countBusinessMarkers(fixture.jdbc),
                    "the guard probe must not commit the caller business transaction early");

            updater = workers.submit(() -> fixture.transaction.execute(status -> {
                updaterSessionId.set(sessionId(fixture.jdbc));
                updaterAttempting.countDown();
                return fixture.jdbc.update("UPDATE user_info /* " + EPOCH_UPDATE_MARKER
                        + " */ SET auth_epoch=? WHERE id=?", AUTH_EPOCH + 1, UID);
            }));

            await(updaterAttempting, "epoch update transaction did not start");
            awaitDatabaseBlockedByHolder(fixture.jdbc, updaterSessionId.get(), holderSessionId.get());
            assertFalse(updater.isDone(),
                    "the epoch update completed while the guard-protected caller transaction was open");
            assertEquals(AUTH_EPOCH, currentEpoch(fixture.jdbc));

            releaseCallerTransaction.countDown();
            get(holder, "caller transaction did not finish");
            assertEquals(1, get(updater, "epoch update did not finish after caller transaction ended"));

            assertEquals(AUTH_EPOCH + 1, currentEpoch(fixture.jdbc));
            assertEquals(rollback ? 0 : 1, countBusinessMarkers(fixture.jdbc),
                    "guard probing must preserve the caller transaction commit/rollback outcome");
        } finally {
            releaseCallerTransaction.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(updater);
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS),
                    "bounded transaction workers did not terminate");
        }
    }

    private static void awaitDatabaseBlockedByHolder(JdbcTemplate jdbc, int waiterId, int holderId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer observed = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM INFORMATION_SCHEMA.SESSIONS waiter
                    JOIN INFORMATION_SCHEMA.SESSIONS blocker
                      ON blocker.SESSION_ID = waiter.BLOCKER_ID
                    WHERE waiter.SESSION_ID = ?
                      AND waiter.BLOCKER_ID = ?
                      AND waiter.SESSION_STATE = 'BLOCKED'
                      AND waiter.EXECUTING_STATEMENT LIKE ?
                      AND blocker.CONTAINS_UNCOMMITTED = TRUE
                    """, Integer.class, waiterId, holderId, "%" + EPOCH_UPDATE_MARKER + "%");
            if (observed != null && observed == 1) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("H2 did not expose the epoch update as blocked by the guard lock");
    }

    private static Fixture fixture(long accountEpoch) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:a06b_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE user_info (
                    id BIGINT PRIMARY KEY,
                    jiacn VARCHAR(100) NOT NULL,
                    account_state VARCHAR(32) NOT NULL,
                    auth_epoch BIGINT NOT NULL
                )
                """);
        jdbc.execute("CREATE TABLE business_marker(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO user_info(id,jiacn,account_state,auth_epoch) VALUES (?,?,?,?)",
                UID, "owner-a", "ACTIVE", accountEpoch);

        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        return new Fixture(jdbc, new ConversationAccountAdmissionGuard(jdbc), transaction);
    }

    private static UserAdmissionScope scope() {
        return UserAdmissionScope.basic(new BusinessScope(
                TenantId.of("1"), DataDomainId.of("cyf"), "owner-a", "client-a"),
                UID, AUTH_EPOCH);
    }

    private static int sessionId(JdbcTemplate jdbc) {
        Integer value = jdbc.queryForObject("SELECT SESSION_ID()", Integer.class);
        if (value == null) {
            throw new AssertionError("H2 session id was unavailable");
        }
        return value;
    }

    private static long currentEpoch(JdbcTemplate jdbc) {
        Long value = jdbc.queryForObject(
                "SELECT auth_epoch FROM user_info WHERE id=?", Long.class, UID);
        if (value == null) {
            throw new AssertionError("account epoch was unavailable");
        }
        return value;
    }

    private static int countBusinessMarkers(JdbcTemplate jdbc) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM business_marker", Integer.class);
        return value == null ? -1 : value;
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static <T> T get(Future<T> future, String message) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError(message, failed.getCause());
        }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    private record Fixture(JdbcTemplate jdbc, ConversationAccountAdmissionGuard guard,
                           TransactionTemplate transaction) {
    }
}
