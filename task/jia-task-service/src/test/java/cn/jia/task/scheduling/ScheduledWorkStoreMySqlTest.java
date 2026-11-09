package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.config.TaskTenantConfiguration;
import cn.jia.task.entity.ScheduledWorkRow;
import cn.jia.task.mapper.ScheduledWorkMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real production SQL + Spring transactions on an owned MySQL, never configured production DBs. */
@EnabledIfEnvironmentVariable(named = "CYF_REVIEW_MYSQLD", matches = ".+")
class ScheduledWorkStoreMySqlTest {
    private static Process mysql;
    private static String baseUrl;
    private static JdbcTemplate admin;
    private String database;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private ScheduledWorkMapper mapper;
    private ScheduledWorkStore store;
    private static final ScheduleKey KEY = new ScheduleKey("client", "rent", "user", "");

    @BeforeAll static void startOwnedMysql() throws Exception {
        Path executable = Path.of(System.getenv("CYF_REVIEW_MYSQLD")).toRealPath();
        Path scratch = Files.createDirectory(Path.of(System.getenv("CYF_SCHEDULE_MYSQL_DIR")));
        Path data = Files.createDirectory(scratch.resolve("data")), log = scratch.resolve("mysql.log");
        String user = System.getProperty("user.name");
        Process init = new ProcessBuilder(executable.toString(), "--no-defaults", "--initialize-insecure",
                "--user=" + user, "--datadir=" + data, "--basedir=" + executable.getParent().getParent())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertEquals(0, init.waitFor(), () -> read(log)); }
        finally { if (init.isAlive()) { init.destroy(); init.waitFor(); } }
        int port;
        do { try (var socket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        } } while (port == 3306 || port == 33060 || port == 10018);
        mysql = new ProcessBuilder(executable.toString(), "--no-defaults", "--user=" + user,
                "--basedir=" + executable.getParent().getParent(), "--datadir=" + data,
                "--socket=" + scratch.resolve("mysql.sock"), "--pid-file=" + scratch.resolve("mysql.pid"),
                "--port=" + port, "--bind-address=127.0.0.1", "--mysqlx=OFF", "--skip-log-bin",
                "--innodb-buffer-pool-size=67108864")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        baseUrl = "jdbc:mysql://127.0.0.1:" + port + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        Class.forName("com.mysql.cj.jdbc.Driver");
        try {
            while (true) {
                assertTrue(mysql.isAlive(), () -> read(log));
                try (var connection = DriverManager.getConnection(baseUrl, "root", "")) {
                    assertTrue(connection.isValid(1)); break;
                } catch (SQLException starting) { Thread.sleep(100); }
            }
            admin = new JdbcTemplate(new DriverManagerDataSource(baseUrl, "root", ""));
            System.out.println("SCHEDULE_MYSQL owned=true version=" + admin.queryForObject("SELECT VERSION()", String.class));
        } catch (Throwable failure) { stopOwnedMysql(); throw failure; }
    }
    @AfterAll static void stopOwnedMysql() throws Exception {
        if (mysql != null && mysql.isAlive()) { mysql.destroy(); mysql.waitFor(); }
    }
    private static String read(Path log) { try { return Files.readString(log); } catch (Exception e) { return e.toString(); } }

    @BeforeEach void createSchema() throws Exception {
        database = "schedule_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        source = new DriverManagerDataSource(baseUrl.replace("/mysql?", "/" + database + "?"), "root", "");
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/task-scheduled-work-v1.sql")).execute(source);
        manager = new DataSourceTransactionManager(source);
        mapper = mapper(new FixedTenantAdmissionPolicy("tenant", null));
        store = store("tenant", null);
    }
    private ScheduledWorkMapper mapper(FixedTenantAdmissionPolicy policy) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.addMapper(ScheduledWorkMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source); factory.setConfiguration(configuration);
        // Include the actual task interceptor: new SQL explicitly owns partitioning, not HTTP context.
        factory.setPlugins(new TaskTenantConfiguration().taskPlanTenantInterceptor(policy));
        return new SqlSessionTemplate(factory.getObject()).getMapper(ScheduledWorkMapper.class);
    }
    private ScheduledWorkStore store(String tenant, String client) throws Exception {
        var policy = new FixedTenantAdmissionPolicy(tenant, client);
        return new ScheduledWorkStore(mapper(policy), policy, manager);
    }
    private ScheduledWorkRow row() {
        return new TransactionTemplate(manager).execute(status -> mapper.lock("tenant", KEY));
    }
    @AfterEach void dropSchema() { if (database != null) admin.execute("DROP DATABASE `" + database + "`"); }

    @Test void notificationJoinsBusinessRollbackAndCommit() {
        jdbc.execute("CREATE TABLE business_fact (id INT PRIMARY KEY)");
        var tx = new TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_fact VALUES (1)"); store.signal(KEY, 0);
            throw new IllegalStateException("business rollback");
        }));
        assertNull(row());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM business_fact", Integer.class));
        tx.executeWithoutResult(status -> { jdbc.update("INSERT INTO business_fact VALUES (2)"); store.signal(KEY, 0); });
        assertEquals(1, row().getRequestedRevision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_fact", Integer.class));
    }
    @Test void scheduleWriteFailureRollsBackBusinessFact() {
        jdbc.execute("CREATE TABLE business_fact (id INT PRIMARY KEY)");
        assertThrows(RuntimeException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO business_fact VALUES (1)");
            store.signal(new ScheduleKey("client", "rent", null, ""), 0);
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM business_fact", Integer.class));
    }
    @Test void claimCommitsBeforeCallbackAndEventsDuringExecutionSurvive() throws Exception {
        store.signal(KEY, 0);
        var lease = store.claimDue("", 1, 60000).getFirst();
        // Separate connection/thread would block if claim retained its row lock.
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> store.signal(KEY, 0)).get(5, TimeUnit.SECONDS);
        }
        assertTrue(store.succeed(lease));
        assertNotNull(store.nextWake(""));
        var next = store.claimDue("", 1, 60000).getFirst();
        assertTrue(next.claim().revision() > lease.claim().revision());
        assertTrue(store.succeed(next)); assertNull(store.nextWake(""));
    }
    @Test void concurrentWorkersCannotBothClaimSameRevision() throws Exception {
        store.signal(KEY, 0);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> work = () -> { start.await(); return store.claimDue("", 1, 60000).size(); };
            var a = executor.submit(work); var b = executor.submit(work); start.countDown();
            assertEquals(1, a.get(5, TimeUnit.SECONDS) + b.get(5, TimeUnit.SECONDS));
        }
    }
    @Test void skipLockedDoesNotWaitOnAnotherWorker() throws Exception {
        store.signal(KEY, 0);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var locker = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                mapper.lock("tenant", KEY); held.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
            }));
            try {
                assertTrue(held.await(5, TimeUnit.SECONDS));
                assertTrue(executor.submit(() -> store.claimDue("", 1, 60000)).get(3, TimeUnit.SECONDS).isEmpty());
            } finally { release.countDown(); }
            locker.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void concurrentNotificationsCoalesceWithoutLostRevision() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Void> send = () -> { start.await(); store.signal(KEY, 0); return null; };
            var a = executor.submit(send); var b = executor.submit(send); start.countDown();
            a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, row().getRequestedRevision());
        assertEquals(1, store.claimDue("", 10, 60000).size());
    }
    @Test void discoveryPartitionsBeforeLimitAndKeepsNodeAffinity() throws Exception {
        store("other-tenant", null).signal(KEY, 0);
        store.signal(new ScheduleKey("aaa-other-client", "rent", "user", ""), 0);
        store.signal(new ScheduleKey("client", "rent", "user", "node-a"), 0);
        store.signal(KEY, 0);
        var scoped = store("tenant", "client");
        assertEquals(List.of(KEY), scoped.claimDue("", 1, 60000).stream().map(ScheduledWorkStore.LeasedWork::key).toList());
        assertEquals(1, scoped.claimDue("node-a", 1, 60000).size());
        assertTrue(scoped.claimDue("node-b", 1, 60000).isEmpty());
        assertEquals(1, store("other-tenant", null).claimDue("", 1, 60000).size());
        assertThrows(IllegalArgumentException.class, () -> scoped.signal(new ScheduleKey("other-client", "rent", "x", ""), 0));
    }
    @Test void expiredClaimCanRecoverAfterRecreatingStoreButOldTokenCannotSettle() throws Exception {
        store.signal(KEY, 0); var old = store.claimDue("", 1, 60000).getFirst();
        jdbc.update("UPDATE task_scheduled_work SET claim_until=0, next_wake_at=0");
        var recovered = store("tenant", null);
        var current = recovered.claimDue("", 1, 60000).getFirst();
        assertEquals(old.claim().revision(), current.claim().revision());
        assertNotEquals(old.claim().token(), current.claim().token());
        assertFalse(store.succeed(old)); assertTrue(recovered.succeed(current));
    }
    @Test void cancellationAndReplacementWinOverOldRetry() {
        store.signal(KEY, 0); var claimed = store.claimDue("", 1, 60000).getFirst();
        store.cancel(KEY);
        assertTrue(store.retry(claimed, Long.MAX_VALUE)); assertNull(store.nextWake(""));
        store.signal(KEY, 0); claimed = store.claimDue("", 1, 60000).getFirst();
        store.reschedule(KEY, Long.MAX_VALUE - 1);
        assertTrue(store.retry(claimed, Long.MAX_VALUE)); assertEquals(Long.MAX_VALUE - 1, store.nextWake(""));
    }
    @Test void cancelExpiredClaimDoesNotResurrectWork() {
        store.signal(KEY, 0); store.claimDue("", 1, 60000); store.cancel(KEY);
        jdbc.update("UPDATE task_scheduled_work SET claim_until=0, next_wake_at=0");
        assertTrue(store.claimDue("", 1, 60000).isEmpty()); assertNull(store.nextWake(""));
        store.cancel(KEY); // no duplicate revision from repeated cancellation
        assertEquals(2, row().getRequestedRevision());
    }
    @Test void versionCasRejectsStaleSnapshot() {
        store.signal(KEY, 0); var stale = row(); store.signal(KEY, 0);
        var update = ScheduledWorkRow.of("tenant", KEY, stale.state().cancel());
        assertEquals(0, mapper.replace(update, stale.getVersion()));
        assertEquals(2, row().getRequestedRevision());
    }
    @Test void renewalKeepsTokenButAdvancesRecoveryDeadline() {
        store.signal(KEY, 0); var claimed = store.claimDue("", 1, 60000).getFirst();
        long until = claimed.claim().expiresAt() + 60000;
        assertTrue(store.renew(claimed, until)); assertEquals(until, store.nextWake(""));
        assertTrue(store.succeed(claimed)); assertNull(store.nextWake(""));
    }
    @Test void workerOperationsRejectAmbientWalletTransaction() {
        store.signal(KEY, 0); var claimed = store.claimDue("", 1, 60000).getFirst();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThrows(IllegalStateException.class, () -> store.claimDue("", 1, 60000));
            assertThrows(IllegalStateException.class, () -> store.succeed(claimed));
        });
        assertTrue(store.succeed(claimed));
    }
    @Test void databaseUsesExactKeysAndDeadlineIndex() {
        store.signal(KEY, 0);
        store.signal(new ScheduleKey("client", "rent", "user ", ""), 0);
        store.signal(new ScheduleKey("client", "rent", "USER", ""), 0);
        assertEquals(3, store.claimDue("", 10, 60000).size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=? AND table_name='task_scheduled_work' AND index_name='idx_task_scheduled_due' AND column_name='next_wake_at'", Integer.class, database));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=? AND constraint_type='CHECK'", Integer.class, database));
    }
    @Test void noticesAreAfterCommitCoalescedAndAbsentOnRollback() {
        var notices = new java.util.ArrayList<ScheduleKey>();
        var notifying = new ScheduledWorkStore(mapper, new FixedTenantAdmissionPolicy("tenant", null), manager, notices::add);
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> {
            notifying.signal(KEY, 0); notifying.signal(KEY, 0);
            assertTrue(notices.isEmpty());
        });
        assertEquals(List.of(KEY), notices);
        notices.clear();
        tx.executeWithoutResult(status -> { notifying.signal(KEY, 0); status.setRollbackOnly(); });
        assertTrue(notices.isEmpty()); assertEquals(2, row().getRequestedRevision());
    }
    @Test void transportFailureAfterCommitDoesNotTurnCommittedFactIntoFailure() {
        var notifying = new ScheduledWorkStore(mapper, new FixedTenantAdmissionPolicy("tenant", null), manager,
                key -> { throw new IllegalStateException("notification transport down"); });
        assertDoesNotThrow(() -> notifying.signal(KEY, 0));
        assertEquals(1, store.claimDue("", 1, 60000).size());
    }
    @Test void nestedTransactionsKeepIndependentCommitNotices() {
        var notices = new java.util.ArrayList<ScheduleKey>();
        var notifying = new ScheduledWorkStore(mapper, new FixedTenantAdmissionPolicy("tenant", null), manager, notices::add);
        var innerKey = new ScheduleKey("client", "rent", "other-user", "");
        var inner = new TransactionTemplate(manager);
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            notifying.signal(KEY, 0);
            inner.executeWithoutResult(nested -> notifying.signal(innerKey, 0));
            assertEquals(List.of(innerKey), notices);
            status.setRollbackOnly();
        });
        assertNull(row()); assertEquals(List.of(innerKey), notices);
    }
    @Test void nearestDeadlineUsesDatabaseClockAndIdleDoesNotBecomeZero() {
        assertNull(store.nextDelay("")); store.signal(KEY, 0); assertEquals(0L, store.nextDelay(""));
        long future = mapper.nowMillis() + 60000;
        store.reschedule(KEY, future);
        assertTrue(store.nextDelay("") > 0 && store.nextDelay("") <= 60000);
    }
    @Test void runnerDoesNotHoldClaimTransactionOrInterruptAcceptedWorkOnClose() throws Exception {
        var registry = new cn.jia.task.job.JobRegistry();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var finished = new CountDownLatch(1);
        registry.register(new ScheduledJobHandler() {
            public String getName() { return "rent"; }
            public void executeScheduled(ScheduledInvocation invocation) throws Exception {
                assertEquals("tenant", invocation.tenantId()); assertEquals(KEY, invocation.key());
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            }
        });
        var runner = new ScheduledJobRunner(store, registry, new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 60000, 1000, finished::countDown);
        store.signal(KEY, 0);
        try {
            runner.tick(); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(Long.MAX_VALUE, runner.tick());
            store.signal(KEY, 0); runner.close(); release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(row().getClaimToken()); assertEquals(2, row().getRequestedRevision());
            assertEquals(1, store.claimDue("", 1, 60000).size());
        } finally { release.countDown(); runner.close(); }
    }
    @Test void unavailableHandlerIsRetriedNotDeletedOrInvokedThroughLegacyPath() throws Exception {
        var registry = new cn.jia.task.job.JobRegistry(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        registry.register(new cn.jia.task.job.JobHandler() {
            public String getName() { return "rent"; }
            public String execute(cn.jia.task.job.JobContext context) { calls.incrementAndGet(); return null; }
        });
        var finished = new CountDownLatch(1);
        try (var runner = new ScheduledJobRunner(store, registry, new FixedTenantAdmissionPolicy("tenant", null),
                "", 1, 1, 60000, 60000, finished::countDown)) {
            store.signal(KEY, 0); runner.tick(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(0, calls.get()); assertNull(row().getClaimToken());
            assertTrue(store.nextDelay("") > 0);
        }
    }

}
