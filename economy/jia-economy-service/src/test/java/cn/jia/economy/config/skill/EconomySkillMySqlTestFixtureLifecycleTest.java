package cn.jia.economy.config.skill;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** No DB, driver, environment gate, or real process control: adversarial fixture lifecycle proof. */
class EconomySkillMySqlTestFixtureLifecycleTest {
    private static final String DATABASE = "w07_lifecycle_owned";
    private static final Duration CLEANUP_TIMEOUT = Duration.ofSeconds(1);
    private final JdbcTemplate admin = mock(JdbcTemplate.class);
    private final EconomySkillMySqlTestFixture fixture = new EconomySkillMySqlTestFixture();
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);

    @Test
    void stalledJdbcWorkerCannotDropDatabaseEvenWhenFutureIsCancelledAndPrimaryIsPreserved() throws Exception {
        prepareFixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(statement.execute("SELECT 1")).thenAnswer(invocation -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return false;
        });
        EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers(CLEANUP_TIMEOUT);
        // Always release the deliberately broken fake, even if an assertion fails; TWR preserves that failure.
        try (AutoCloseable cleanup = () -> { release.countDown(); workers.close(); }) {
            Future<Void> future = execute(workers);
            TimeoutException primary = new TimeoutException("original fixture deadline");
            TimeoutException actual = assertThrows(TimeoutException.class, () -> {
                try (workers) {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertTrue(future.cancel(true));
                    throw primary;
                }
            });
            assertSame(primary, actual);
            assertTrue(future.isDone(), "cancelled Future is not worker-exit evidence");
            assertFalse(workers.cleanupComplete());
            assertEquals(1, primary.getSuppressed().length);
            assertTrue(primary.getSuppressed()[0].getMessage().contains("cleanup incomplete"));
            assertRetained();
            fixture.closePreserving(primary);
            assertEquals(2, primary.getSuppressed().length);
            assertTrue(primary.getSuppressed()[1].getMessage().contains(DATABASE));
            verify(admin, never()).execute(anyString());
        }
        assertTrue(workers.cleanupComplete());
        fixture.close();
        verify(admin).execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
    }

    @Test
    void jdbcAbortCanReleaseAnInterruptIgnoringWorkerBeforeDrop() throws Exception {
        prepareFixture();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(statement.execute("SELECT 1")).thenAnswer(invocation -> {
            entered.countDown();
            awaitIgnoringInterrupt(release);
            return false;
        });
        doAnswer(invocation -> { release.countDown(); return null; }).when(connection).abort(any());
        EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers(CLEANUP_TIMEOUT);
        try (AutoCloseable cleanup = () -> { release.countDown(); workers.close(); }) {
            Future<Void> future = execute(workers);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            workers.close();
            future.get(5, TimeUnit.SECONDS);
            assertTrue(workers.cleanupComplete());
            verify(connection).abort(any());
            fixture.close();
            verify(admin).execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
        }
    }

    @Test
    void stalledAbortStillInterlocksDropAfterInitializerWorkerHasExited() throws Exception {
        prepareFixture();
        CountDownLatch releaseAbort = new CountDownLatch(1);
        doAnswer(invocation -> { awaitIgnoringInterrupt(releaseAbort); return null; })
                .when(connection).abort(any());
        EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers(CLEANUP_TIMEOUT);
        try (AutoCloseable cleanup = () -> { releaseAbort.countDown(); workers.close(); }) {
            execute(workers).get(5, TimeUnit.SECONDS);
            IllegalStateException failure = assertThrows(IllegalStateException.class, workers::close);
            assertTrue(failure.getMessage().contains("cleanup incomplete"));
            assertRetained();
        }
        fixture.close();
        verify(admin).execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
    }

    @Test
    void connectionReturningAfterCancellationIsAbortedAndNeverUsedForSql() throws Exception {
        prepareFixture();
        CountDownLatch connecting = new CountDownLatch(1);
        CountDownLatch releaseConnect = new CountDownLatch(1);
        when(source.getConnection()).thenAnswer(invocation -> {
            connecting.countDown();
            awaitIgnoringInterrupt(releaseConnect);
            return connection;
        });
        EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers(CLEANUP_TIMEOUT);
        try (AutoCloseable cleanup = () -> { releaseConnect.countDown(); workers.close(); }) {
            Future<Void> future = execute(workers);
            assertTrue(connecting.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, workers::close);
            assertRetained();
            releaseConnect.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            assertEquals("W07 connection arrived after initializer cancellation", failure.getCause().getMessage());
        }
        verify(connection).abort(any());
        verify(connection, never()).createStatement();
        fixture.close();
        verify(admin).execute("DROP DATABASE IF EXISTS `" + DATABASE + "`");
    }

    @Test
    void abortFailureIsReportedAndRetainsDatabaseEvenAfterAllThreadsExit() throws Exception {
        prepareFixture();
        SQLException abortFailure = new SQLException("fake driver cannot abort");
        doThrow(abortFailure).when(connection).abort(any());
        EconomySkillMySqlTestFixture.InitializerWorkers workers = fixture.initializerWorkers(CLEANUP_TIMEOUT);
        try (AutoCloseable cleanup = () -> {
            IllegalStateException failure = assertThrows(IllegalStateException.class, workers::close);
            assertEquals(1, failure.getSuppressed().length);
            assertSame(abortFailure, failure.getSuppressed()[0]);
        }) {
            execute(workers).get(5, TimeUnit.SECONDS);
        }
        assertRetained();
    }

    @Test
    void driverWaitsAreFiniteEvenWhenInputUrlRequestsInfiniteWaitsOrReconnect() {
        assertEquals("jdbc:mysql://localhost/test?useSSL=false&connectTimeout=10000&socketTimeout=2400000"
                        + "&autoReconnect=false&autoReconnectForPools=false",
                EconomySkillMySqlTestFixture.boundedJdbcUrl("jdbc:mysql://localhost/test?connectTimeout=0"
                        + "&socket%54imeout=0&autoReconnect=true&autoReconnectForPools=true&useSSL=false"));
    }

    private void prepareFixture() throws SQLException {
        // Inject only mock admin state, so exercising the real close() path cannot touch a DB.
        ReflectionTestUtils.setField(fixture, "admin", admin);
        ReflectionTestUtils.setField(fixture, "databasePrefix", "w07_lifecycle");
        ReflectionTestUtils.setField(fixture, "databases", new ArrayList<>(List.of(DATABASE)));
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
    }

    private Future<Void> execute(EconomySkillMySqlTestFixture.InitializerWorkers workers) {
        return workers.submit(() -> {
            workers.jdbc(source).execute("SELECT 1");
            return null;
        });
    }

    private void assertRetained() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, fixture::close);
        assertTrue(failure.getMessage().contains("cleanup incomplete"));
        assertTrue(failure.getMessage().contains(DATABASE));
        verify(admin, never()).execute(anyString());
    }

    private static void awaitIgnoringInterrupt(CountDownLatch release) {
        // Even the intentionally broken fake has an absolute deadline, including repeated interrupts.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try {
                if (release.await(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) return;
            } catch (InterruptedException ignored) {
                // Model a JDBC driver that ignores Future.cancel(true)/shutdownNow().
            }
        }
        throw new AssertionError("test did not release stalled fake within 15 seconds");
    }
}
