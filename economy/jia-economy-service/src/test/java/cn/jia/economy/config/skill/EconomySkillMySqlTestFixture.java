package cn.jia.economy.config.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Externally supplied and explicitly acknowledged isolated MySQL 8.0.21 fixture for W07 only. */
final class EconomySkillMySqlTestFixture implements AutoCloseable {
    static final String URL_ENV = "ECO_V0_W07_MYSQL_URL";
    private static final Pattern PREFIX = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern SUFFIX = Pattern.compile("[a-z][a-z0-9_]{0,15}");

    private JdbcTemplate admin;
    private String baseUrl;
    private String username;
    private String password;
    private String databasePrefix;
    private final List<String> databases = new ArrayList<>();
    private final List<InitializerWorkers> workerScopes = new ArrayList<>();

    InitializerWorkers initializerWorkers() {
        return initializerWorkers(Duration.ofSeconds(30));
    }

    // Short deadlines are injected only by the noDB lifecycle regression tests.
    InitializerWorkers initializerWorkers(Duration cleanupTimeout) {
        InitializerWorkers scope = new InitializerWorkers(cleanupTimeout);
        workerScopes.add(scope);
        return scope;
    }

    void start() {
        baseUrl = requiredNonBlank(URL_ENV);
        if (!baseUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException(URL_ENV + " must be a jdbc:mysql:// URL");
        }
        username = requiredNonBlank("ECO_V0_W07_MYSQL_USER");
        password = requiredPresent("ECO_V0_W07_MYSQL_PASSWORD");
        if (!"true".equals(requiredNonBlank("ECO_V0_W07_MYSQL_ISOLATED_FIXTURE"))) {
            throw new IllegalStateException("ECO_V0_W07_MYSQL_ISOLATED_FIXTURE must equal true");
        }
        databasePrefix = requiredNonBlank("ECO_V0_W07_MYSQL_DATABASE_PREFIX");
        if (!PREFIX.matcher(databasePrefix).matches()) {
            throw new IllegalStateException("ECO_V0_W07_MYSQL_DATABASE_PREFIX is unsafe");
        }
        admin = new JdbcTemplate(dataSource(baseUrl));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(version != null && version.startsWith("8.0.21"), version);
        String port = System.getenv("ECO_V0_W07_MYSQL_EXPECTED_PORT");
        if (port != null && !port.isBlank()) {
            assertEquals(Integer.valueOf(port), admin.queryForObject("SELECT @@port", Integer.class));
        }
        String datadir = System.getenv("ECO_V0_W07_MYSQL_EXPECTED_DATADIR");
        if (datadir != null && !datadir.isBlank()) {
            String actual = admin.queryForObject("SELECT @@datadir", String.class);
            assertTrue(actual != null && actual.startsWith(datadir), actual);
        }
    }

    Database newDatabase(String suffix) {
        if (admin == null) throw new IllegalStateException("MySQL fixture is not started");
        if (suffix == null || !SUFFIX.matcher(suffix).matches()) {
            throw new IllegalArgumentException("unsafe MySQL fixture database suffix");
        }
        String database = databasePrefix + "_" + suffix + "_" + Long.toUnsignedString(System.nanoTime(), 36);
        if (database.length() > 64 || !database.startsWith(databasePrefix + "_")) {
            throw new IllegalStateException("unsafe MySQL fixture database name");
        }
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        databases.add(database);
        DriverManagerDataSource source = dataSource(databaseUrl(baseUrl, database));
        return new Database(database, source, new JdbcTemplate(source));
    }

    void closePreserving(Throwable primaryFailure) {
        try {
            close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (primaryFailure == null) throw cleanupFailure;
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    @Override
    public void close() {
        // Future cancellation/isDone is NOT evidence that a JDBC worker has exited.
        // Keep this check before any DROP (and before the no-admin early return).
        for (InitializerWorkers scope : workerScopes) {
            if (!scope.cleanupComplete()) {
                throw new IllegalStateException("W07 cleanup incomplete: refusing DROP while initializer workers "
                        + "or JDBC cancellation remain incomplete; retained databases=" + databases);
            }
        }
        if (admin == null) return;
        for (int index = databases.size() - 1; index >= 0; index--) {
            String database = databases.get(index);
            if (!database.startsWith(databasePrefix + "_")) {
                throw new IllegalStateException("refusing to drop an unowned W07 MySQL database");
            }
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
        databases.clear();
        admin = null;
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(boundedJdbcUrl(url));
        source.setUsername(username);
        source.setPassword(password);
        return source;
    }

    static String boundedJdbcUrl(String url) {
        // Observed slow-host schema execution was 1876s. Bound driver connect/read waits,
        // not just Future.get; URL options must not silently override these fixture limits.
        int query = url.indexOf('?');
        String prefix = query < 0 ? url : url.substring(0, query);
        List<String> options = new ArrayList<>();
        if (query >= 0) {
            for (String option : url.substring(query + 1).split("&")) {
                String key = URLDecoder.decode(option.split("=", 2)[0], StandardCharsets.UTF_8);
                if (!option.isBlank() && !key.equalsIgnoreCase("connectTimeout")
                        && !key.equalsIgnoreCase("socketTimeout") && !key.equalsIgnoreCase("autoReconnect")
                        && !key.equalsIgnoreCase("autoReconnectForPools")) {
                    options.add(option);
                }
            }
        }
        options.add("connectTimeout=10000");
        options.add("socketTimeout=2400000");
        options.add("autoReconnect=false");
        options.add("autoReconnectForPools=false");
        return prefix + "?" + String.join("&", options);
    }

    /** Two real callers, each with one dedicated session; never expose these sessions outside the scope. */
    static final class InitializerWorkers implements AutoCloseable {
        private final ExecutorService workers = daemonPool(2, "w07-initializer");
        private final ExecutorService cancellations = daemonPool(2, "w07-jdbc-abort");
        private final List<Connection> connections = new ArrayList<>();
        private final ConcurrentLinkedQueue<Throwable> abortFailures = new ConcurrentLinkedQueue<>();
        private final Duration cleanupTimeout;
        private boolean closing;

        private InitializerWorkers(Duration cleanupTimeout) {
            if (cleanupTimeout.isNegative() || cleanupTimeout.isZero()
                    || cleanupTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
                throw new IllegalArgumentException("cleanup timeout must be positive and at most 30 seconds");
            }
            this.cleanupTimeout = cleanupTimeout;
        }

        synchronized <T> Future<T> submit(Callable<T> caller) {
            if (closing) throw new IllegalStateException("W07 initializer scope is closing");
            return workers.submit(caller);
        }

        JdbcTemplate jdbc(DataSource source) throws SQLException {
            synchronized (this) {
                if (closing) throw new SQLException("W07 initializer scope is closing");
            }
            // Outside the monitor: even a broken driver must not block the teardown interlock.
            Connection connection = source.getConnection();
            synchronized (this) {
                if (!closing) {
                    connections.add(connection);
                    // suppressClose lets production use normal try-with-resources without losing
                    // this caller's session; scope cleanup aborts the actual physical connection.
                    return new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                }
            }
            // A connect that returns after cancellation is still owned by a live worker.
            abort(connection);
            throw new SQLException("W07 connection arrived after initializer cancellation");
        }

        @Override
        public void close() {
            long deadline = System.nanoTime() + cleanupTimeout.toNanos();
            synchronized (this) {
                if (!closing) {
                    closing = true;
                    workers.shutdownNow();
                    // abort may itself block in a non-conforming driver. Run it off the test thread,
                    // track actual executor termination, and retain the DB if it misses the deadline.
                    for (Connection connection : connections) {
                        cancellations.submit(() -> abort(connection));
                    }
                    cancellations.shutdown();
                }
            }
            InterruptedException interrupted = null;
            try {
                awaitBeforeDeadline(workers, deadline);
                awaitBeforeDeadline(cancellations, deadline);
            } catch (InterruptedException failure) {
                interrupted = failure;
                Thread.currentThread().interrupt();
            }
            if (interrupted != null || !cleanupComplete()) {
                IllegalStateException failure = new IllegalStateException(
                        "W07 cleanup incomplete: worker/JDBC cancellation deadline exceeded or abort failed; "
                                + "workersTerminated=" + workers.isTerminated()
                                + ", cancellationsTerminated=" + cancellations.isTerminated(), interrupted);
                abortFailures.forEach(failure::addSuppressed);
                throw failure;
            }
        }

        boolean cleanupComplete() {
            return workers.isTerminated() && cancellations.isTerminated() && abortFailures.isEmpty();
        }

        private void abort(Connection connection) {
            try {
                // Direct execution stays on an owned worker/cancellation thread, never the test thread.
                connection.abort(Runnable::run);
            } catch (Throwable failure) {
                abortFailures.add(failure);
            }
        }

        private static void awaitBeforeDeadline(ExecutorService executor, long deadline)
                throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        }

        private static ExecutorService daemonPool(int size, String name) {
            return Executors.newFixedThreadPool(size, runnable -> {
                Thread thread = new Thread(runnable, name);
                // A broken JDBC driver must fail this test, not hang the entire test JVM indefinitely.
                thread.setDaemon(true);
                return thread;
            });
        }
    }

    private String databaseUrl(String url, String database) {
        int query = url.indexOf('?');
        String suffix = query < 0 ? "" : url.substring(query);
        String prefix = query < 0 ? url : url.substring(0, query);
        int slash = prefix.indexOf('/', "jdbc:mysql://".length());
        return slash < 0 ? prefix + "/" + database + suffix : prefix.substring(0, slash + 1) + database + suffix;
    }

    private String requiredNonBlank(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private String requiredPresent(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " must be explicitly supplied");
        return value;
    }

    record Database(String name, DriverManagerDataSource dataSource, JdbcTemplate jdbc) {
    }
}
