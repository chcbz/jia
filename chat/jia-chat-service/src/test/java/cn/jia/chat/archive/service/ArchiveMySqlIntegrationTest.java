package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveSchemaInitializer;
import cn.jia.chat.archive.content.ArchiveManifestBundle;
import cn.jia.chat.archive.content.ArchiveManifestLoader;
import cn.jia.chat.archive.store.JdbcArchiveContentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Isolated MySQL evidence. It performs destructive DDL/DML only when all of these are supplied:
 * CYF_H02_MYSQL_ISOLATED=true, an IP-literal loopback CYF_H02_MYSQL_URL with an explicit
 * non-3306/non-33060 port and a cyf_h02_ database, plus CYF_H02_MYSQL_DATABASE_CONFIRM exactly
 * equal to that database name. URL query parameters, embedded credentials and remote hosts are rejected
 * before JdbcTemplate is created and before any DROP statement can run.
 */
class ArchiveMySqlIntegrationTest {
    private JdbcTemplate jdbc;
    private TrackingDataSource dataSource;
    private ArchiveSchemaInitializer schemaInitializer;
    private ArchiveContentImporter importer;
    private ArchiveManifestBundle bundle;

    @BeforeEach
    void setUp() {
        assumeTrue("true".equals(System.getenv("CYF_H02_MYSQL_ISOLATED")),
                "requires explicit isolated-MySQL acknowledgement");
        ArchiveMySqlTestGuard.Target target = ArchiveMySqlTestGuard.requireDisposable(System.getenv());
        DriverManagerDataSource delegate = new DriverManagerDataSource();
        delegate.setDriverClassName("com.mysql.cj.jdbc.Driver");
        delegate.setUrl(target.url());
        delegate.setUsername(System.getenv().getOrDefault("CYF_H02_MYSQL_USER", "root"));
        delegate.setPassword(System.getenv().getOrDefault("CYF_H02_MYSQL_PASSWORD", ""));
        dataSource = new TrackingDataSource(delegate);
        jdbc = new JdbcTemplate(dataSource);
        clean();
        schemaInitializer = new ArchiveSchemaInitializer(jdbc);
        schemaInitializer.initialize();
        JdbcArchiveContentStore store = new JdbcArchiveContentStore(jdbc);
        importer = new ArchiveContentImporter(store,
                new SpringArchiveTransactions(new DataSourceTransactionManager(dataSource)), 100);
        bundle = new ArchiveManifestLoader().load();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) clean();
    }

    @Test
    void realMysqlSchemaImportRestartConcurrencyMismatchAndDriftFailClosed() throws Exception {
        importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
        assertEquals(120, count("archive_chapter", "block_type='CHAPTER'"));
        assertEquals(1, count("archive_chapter", "block_type='PREFACE'"));
        assertEquals(3677, count("archive_paragraph", "1=1"));
        assertEquals(bundle.manifest().editionId(), jdbc.queryForObject(
                "SELECT active_edition_id FROM archive_work WHERE work_id='shuihuzhuan'", String.class));

        var timestampsBeforeRestart = jdbc.queryForMap("""
                SELECT w.updated_at, e.ready_at, e.activated_at
                FROM archive_work w JOIN archive_edition e
                  ON e.work_id = w.work_id AND e.edition_id = w.active_edition_id
                WHERE w.work_id = 'shuihuzhuan'
                """);
        List<Future<Void>> successful = runConcurrentImports();
        for (Future<Void> future : successful) {
            assertTrue(future.isDone());
            assertTrue(!future.isCancelled());
            future.get();
        }
        assertEquals(timestampsBeforeRestart, jdbc.queryForMap("""
                SELECT w.updated_at, e.ready_at, e.activated_at
                FROM archive_work w JOIN archive_edition e
                  ON e.work_id = w.work_id AND e.edition_id = w.active_edition_id
                WHERE w.work_id = 'shuihuzhuan'
                """), "validated READY restarts must be mutation-free");
        assertImportResourcesReleased();

        String paragraphId = bundle.manifest().chapters().getFirst().paragraphs().getFirst().paragraphId();
        jdbc.update("UPDATE archive_paragraph SET text='tampered' WHERE paragraph_id=?", paragraphId);
        assertThrows(ArchiveImportException.class,
                () -> importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256()));
        List<Future<Void>> mismatches = runConcurrentImports();
        for (Future<Void> future : mismatches) {
            assertTrue(future.isDone());
            assertTrue(!future.isCancelled());
            ExecutionException failure = assertThrows(ExecutionException.class, future::get);
            assertTrue(failure.getCause() instanceof ArchiveImportException, failure.toString());
        }
        assertImportResourcesReleased();
        assertEquals("tampered", jdbc.queryForObject(
                "SELECT text FROM archive_paragraph WHERE paragraph_id=?", String.class, paragraphId));

        jdbc.execute("ALTER TABLE archive_paragraph MODIFY text LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        assertThrows(IllegalStateException.class, schemaInitializer::initialize);
    }

    private List<Future<Void>> runConcurrentImports() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(pool.submit(() -> {
                start.await();
                importer.importAndActivate(bundle.manifest(), bundle.manifestFileSha256());
                return null;
            }));
        }
        start.countDown();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(60, TimeUnit.SECONDS);
        if (!terminated) {
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertTrue(terminated, () -> "archive import futures did not terminate: " + futureStates(futures));
        return List.copyOf(futures);
    }

    private List<String> futureStates(List<? extends Future<?>> futures) {
        return futures.stream().map(future -> "done=" + future.isDone()
                + ",cancelled=" + future.isCancelled()).toList();
    }

    private void assertImportResourcesReleased() throws Exception {
        assertEquals(0, dataSource.activeConnections(), "archive importer leaked a JDBC connection");
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET SESSION innodb_lock_wait_timeout=3");
            statement.executeQuery("SELECT work_id FROM archive_work WHERE work_id='shuihuzhuan' FOR UPDATE").close();
            statement.executeQuery("SELECT edition_id FROM archive_edition "
                    + "WHERE edition_id='shuihuzhuan-zh-120-v1' FOR UPDATE").close();
            connection.rollback();
        }
        assertEquals(0, dataSource.activeConnections(), "archive lock probe connection was not released");
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + predicate, Integer.class);
    }

    private void clean() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                try {
                    statement.execute("DROP TABLE IF EXISTS archive_paragraph");
                    statement.execute("DROP TABLE IF EXISTS archive_chapter");
                    statement.execute("DROP TABLE IF EXISTS archive_edition");
                    statement.execute("DROP TABLE IF EXISTS archive_work");
                } finally {
                    statement.execute("SET FOREIGN_KEY_CHECKS=1");
                }
            }
            return null;
        });
    }

    private static final class TrackingDataSource extends AbstractDataSource {
        private final DriverManagerDataSource delegate;
        private final AtomicInteger activeConnections = new AtomicInteger();

        private TrackingDataSource(DriverManagerDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return track(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return track(delegate.getConnection(username, password));
        }

        int activeConnections() {
            return activeConnections.get();
        }

        private Connection track(Connection connection) {
            activeConnections.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if ("close".equals(method.getName()) && closed.compareAndSet(false, true)) {
                            try {
                                return method.invoke(connection, args);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            } finally {
                                activeConnections.decrementAndGet();
                            }
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
        }
    }
}
