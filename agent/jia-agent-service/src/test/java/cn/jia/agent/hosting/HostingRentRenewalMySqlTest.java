package cn.jia.agent.hosting;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.common.*;
import cn.jia.economy.config.*;
import cn.jia.economy.entity.*;
import cn.jia.economy.hosting.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.service.*;
import cn.jia.economy.service.impl.*;
import cn.jia.task.config.TaskTenantConfiguration;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.*;
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
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.*;

/** Actual wallet + lease + schedule SQL on owned MySQL. No production DB, balance or provider. */
@EnabledIfEnvironmentVariable(named = "CYF_REVIEW_MYSQLD", matches = ".+")
class HostingRentRenewalMySqlTest {
    private static Process mysql;
    private static String baseUrl;
    private static JdbcTemplate admin;
    private String database;
    private JdbcTemplate jdbc;
    private EconomyHostingRentMapper leases;
    private EconomyHostingRenewalMapper renewals;
    private EconomyLedgerMapper accounts;
    private HostingRentRenewalServiceImpl service;
    private EconomyPostingServiceImpl posting;
    private ScheduledWorkStore schedules;
    private HostingRentRenewalHandler handler;
    private HostingRentLeaseWakeup leaseWakeup;
    private DataSourceTransactionManager manager;
    private final EconomyScope scope = new EconomyScope("tenant", "client");
    private final FixedTenantAdmissionPolicy policy = new FixedTenantAdmissionPolicy("tenant", "client");
    private final ScheduleKey key = new ScheduleKey("client", HostingRentBalanceWakeup.HANDLER, "user", "");
    private static final long PERIOD = HostingRentRenewalServiceImpl.PERIOD_MILLIS;
    private static final long RETENTION = HostingRentRenewalServiceImpl.LOCK_RETENTION_MILLIS;

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


    @BeforeEach void setup() throws Exception {
        database = "renewal_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var source = new DriverManagerDataSource(baseUrl.replace("/mysql?", "/" + database + "?"), "root", "");
        jdbc = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-v0-foundation.sql"),
                new ClassPathResource("db/economy-v0-hosting-rent.sql"),
                new ClassPathResource("db/task-scheduled-work-v1.sql")).execute(source);
        manager = new DataSourceTransactionManager(source);
        var config = new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        config.addMapper(EconomyLedgerMapper.class); config.addMapper(EconomyHostingRentMapper.class);
        config.addMapper(EconomyHostingRenewalMapper.class); config.addMapper(ScheduledWorkMapper.class);
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(config);
        factory.setPlugins(new TaskTenantConfiguration().taskPlanTenantInterceptor(policy));
        var template = new SqlSessionTemplate(factory.getObject());
        accounts = template.getMapper(EconomyLedgerMapper.class); leases = template.getMapper(EconomyHostingRentMapper.class);
        renewals = spy(template.getMapper(EconomyHostingRenewalMapper.class));
        schedules = new ScheduledWorkStore(template.getMapper(ScheduledWorkMapper.class), policy, manager);
        var gate = new EconomyPreviewGate(new EconomyPreviewProperties(true,
                List.of(new EconomyPreviewProperties.AllowedScope("tenant", "client"))));
        posting = new EconomyPostingServiceImpl(accounts, manager, gate);
        ReflectionTestUtils.invokeMethod(posting, "setAvailableBalanceListeners",
                List.of(new HostingRentBalanceWakeup(schedules, policy)));
        service = new HostingRentRenewalServiceImpl(leases, renewals, accounts, posting, gate, manager);
        leaseWakeup = new HostingRentLeaseWakeup(schedules, policy);
        service.setLeaseChangeListeners(List.of(leaseWakeup));
        handler = new HostingRentRenewalHandler(service, policy);
        wallet("tenant", "client", "user", 1000);
    }
    @AfterEach void dropSchema() { if (database != null) admin.execute("DROP DATABASE `" + database + "`"); }
    @Test void handoverSnapshotRequiresCallerTransactionAndRetainsMissingIntentAsUnknown() {
        seed("finish", "FINISHING", 500, now(), null);
        assertThrows(IllegalStateException.class, () -> service.lockHandover(scope, "missing", "finish"));
        var snapshot = new org.springframework.transaction.support.TransactionTemplate(manager)
                .execute(tx -> service.lockHandover(scope, "missing", "finish"));
        assertNull(snapshot.intent()); assertEquals("finish", snapshot.lease().getLeaseId());
        assertEquals("FINISHING", snapshot.lease().getStatus()); assertEquals("user", snapshot.lease().getPrincipalId());
        assertEquals(1000, balance()); assertEquals(0, charges());
    }

    private HostingRentRenewalService.Result complete(String id, String user, String binding, String agent) {
        return new org.springframework.transaction.support.TransactionTemplate(manager).execute(tx ->
                service.completeHandover(scope, new EconomyPrincipal(EconomyPrincipalType.USER, user), id, binding, agent));
    }
    @Test void finalHandoverReleasesOnlyOriginalLiveSlotAndNeverChangesPaidPeriodOrMoney() {
        long through = now() - RETENTION;
        seed("finish", "FINISHING", 500, through, through);
        var original = lease("finish");
        var first = complete("finish", "user", "binding-finish", "agent-finish");
        assertEquals("CLOSED", first.status());
        assertEquals(first, complete("finish", "user", "binding-finish", "agent-finish"));
        var closed = lease("finish");
        assertEquals(original.getVersion() + 1, closed.getVersion());
        assertEquals(original.getPaidFrom(), closed.getPaidFrom());
        assertEquals(original.getPaidThrough(), closed.getPaidThrough());
        assertEquals(original.getLatestIntentId(), closed.getLatestIntentId());
        assertNull(jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease WHERE lease_id='finish'", Integer.class));
        assertEquals(1000, balance()); assertEquals(0, charges());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work", Integer.class));
        // Financial slot can be reused for the same Agent; historical ownership stays on the old row.
        var next = leases.selectLeaseForUpdate("tenant", "client", "finish");
        next.setId(null).setLeaseId("new-lease").setPrincipalId("next-user").setBindingId("new-binding")
                .setLatestIntentId("next-intent").setStatus("PROVISIONING").setVersion(1L);
        assertEquals(1, leases.insertLease(next));
        assertEquals(first, complete("finish", "user", "binding-finish", "agent-finish"));
        assertEquals("PROVISIONING", leases.selectLeaseForUpdate("tenant", "client", "new-lease").getStatus());
        assertEquals("user", leases.selectLeaseForUpdate("tenant", "client", "finish").getPrincipalId());
    }
    @Test void finalHandoverRequiresCallerTransactionAndRollsBackWithRelationshipRelease() {
        seed("finish", "FINISHING", 500, now(), null);
        var owner = new EconomyPrincipal(EconomyPrincipalType.USER, "user");
        assertThrows(IllegalStateException.class, () -> service.completeHandover(scope, owner, "finish", "binding-finish", "agent-finish"));
        assertThrows(IllegalStateException.class, () -> new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(tx -> {
                    service.completeHandover(scope, owner, "finish", "binding-finish", "agent-finish");
                    throw new IllegalStateException("binding release failed");
                }));
        assertEquals("FINISHING", lease("finish").getStatus());
        assertEquals(1L, lease("finish").getVersion());
        assertEquals(1, jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease WHERE lease_id='finish'", Integer.class));
    }
    @Test void finalHandoverRejectsChangedOwnerBindingAgentAndNonFinishingLease() {
        seed("finish", "FINISHING", 500, now(), null);
        for (String[] changed : List.of(new String[]{"other", "binding-finish", "agent-finish"},
                new String[]{"user", "other", "agent-finish"}, new String[]{"user", "binding-finish", "other"}))
            assertThrows(HostingRentException.class, () -> complete("finish", changed[0], changed[1], changed[2]));
        seed("active", "ACTIVE", 500, now() + PERIOD, null);
        assertThrows(HostingRentException.class, () -> complete("active", "user", "binding-active", "agent-active"));
        assertEquals("FINISHING", lease("finish").getStatus()); assertEquals("ACTIVE", lease("active").getStatus());
        complete("finish", "user", "binding-finish", "agent-finish");
        assertThrows(HostingRentException.class, () -> complete("finish", "other", "binding-finish", "agent-finish"));
        assertEquals(1000, balance()); assertEquals(0, charges());
    }

    private void wallet(String tenant, String client, String user, long balance) {
        accounts.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId("wallet-" + user)
                .setOwnerType("USER").setOwnerId(user).setPurpose("AVAILABLE").setCurrency("WEN")
                .setBalanceWen(balance).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(tenant).setClientId(client).setCreateTime(1L).setUpdateTime(1L));
    }
    private long now() { return renewals.nowMillis(); }
    private void seed(String id, String state, long price, long through, Long locked) {
        seed("tenant", "client", "user", id, state, price, through, locked);
    }
    private void seed(String tenant, String client, String user, String id, String state, long price, long through, Long locked) {
        leases.insertLease(new EconomyHostingLeaseEntity().setLeaseId(id).setPrincipalType("USER").setPrincipalId(user)
                .setPersonaCode("persona-" + id).setAgentId("agent-" + id).setBindingId("binding-" + id)
                .setPlanId("hosted").setPlanVersion(1L).setAmountWen(price).setPeriodSeconds(PERIOD / 1000)
                .setStatus(state).setPaidFrom(through - PERIOD).setPaidThrough(through)
                .setLatestIntentId("initial-" + id).setVersion(1L).setTenantId(tenant).setClientId(client)
                .setCreateTime(1L).setUpdateTime(1L));
        if (locked != null) jdbc.update("UPDATE economy_hosting_lease SET locked_at=? WHERE tenant_id=? AND client_id=? AND lease_id=?",
                locked, tenant, client, id);
    }
    private EconomyHostingLeaseEntity lease(String id) { return leases.selectLatestLease("tenant", "client", "agent-" + id); }
    private long balance() { return jdbc.queryForObject("SELECT balance_wen FROM economy_account WHERE tenant_id='tenant' AND client_id='client' AND owner_type='USER' AND owner_id='user'", Long.class); }
    private int charges() { return jdbc.queryForObject("SELECT COUNT(*) FROM economy_transaction WHERE business_type='RENEW_HOSTING_RENT'", Integer.class); }
    private void trigger() throws Exception { handler.executeScheduled(new ScheduledInvocation("tenant", key, 1)); }
    private void credit(long amount, String key) {
        accounts.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId("issuance").setOwnerType("SYSTEM")
                .setOwnerId("treasury").setPurpose("WEN_ISSUANCE").setCurrency("WEN").setBalanceWen(0L)
                .setAllowNegative(1).setStatus("ACTIVE").setVersion(0L).setTenantId("tenant").setClientId("client")
                .setCreateTime(1L).setUpdateTime(1L));
        ReflectionTestUtils.invokeMethod(posting, "postTreasuryIssue", new EconomyPostingCommand(scope,
                new EconomyPrincipal(EconomyPrincipalType.SYSTEM, "treasury"), key, new byte[32], EconomyJournalType.ISSUE_WEN,
                "test-credit", List.of(new EconomyPostingLine(new EconomyAccountKey("WEN", EconomyAccountOwnerType.SYSTEM,
                        "treasury", EconomyAccountPurpose.WEN_ISSUANCE), -amount),
                        new EconomyPostingLine(new EconomyAccountKey("WEN", EconomyAccountOwnerType.USER,
                                "user", EconomyAccountPurpose.AVAILABLE), amount)), null));
    }

    @Test void duePaymentAndDuplicateCallbackChargeOnlyOnce() throws Exception {
        long due = now() - 1000; seed("a", "ACTIVE", 500, due, null);
        trigger(); var paid = lease("a");
        assertEquals(500, balance()); assertEquals(1, charges());
        assertEquals("ACTIVE", paid.getStatus()); assertNull(paid.getLockedAt());
        assertTrue(paid.getPaidFrom() > due); assertEquals(PERIOD, paid.getPaidThrough() - paid.getPaidFrom());
        assertNull(service.reconcile(scope, "a", due).transactionId());
        trigger(); assertEquals(1, charges()); assertEquals(500, balance());
        assertEquals(0L, jdbc.queryForObject("SELECT SUM(signed_amount_wen) FROM economy_entry", Long.class));
        assertEquals(paid.getPaidThrough(), schedules.nextWake(""));
    }
    @Test void nearestOtherLeaseIsDurableEvenIfWorkerDiesImmediatelyAfterBusinessCommit() {
        long future = now() + 60000;
        seed("due", "ACTIVE", 50, now() - 1000, null);
        seed("tomorrow", "ACTIVE", 50, future, null);
        schedules.signal(key, 0);
        var work = schedules.claimDue("", 1, 60000).getFirst();
        // Only call the business transaction; no handler post-processing or success acknowledgment.
        service.reconcileUser(scope, "user");
        assertEquals(future, jdbc.queryForObject("SELECT due_at FROM task_scheduled_work WHERE handler=? AND business_key='user'",
                Long.class, HostingRentBalanceWakeup.HANDLER));
        assertEquals(1, charges());
        assertTrue(schedules.succeed(work)); assertEquals(future, schedules.nextWake(""));
    }
    @Test void deadlineRegistrationFailureAfterLeaseChangeStillRollsBackPayment() {
        long due = now() - 1000; seed("a", "ACTIVE", 500, due, null);
        service.setLeaseChangeListeners(List.of(new HostingLeaseChangeListener() {
            @Override public void changed(Change change) { leaseWakeup.changed(change); }
            @Override public void reconciled(EconomyScope partition, String userId, Long next) {
                leaseWakeup.reconciled(partition, userId, next);
                throw new IllegalStateException("nearest-deadline persistence failed");
            }
        }));
        assertThrows(IllegalStateException.class, () -> service.reconcileUser(scope, "user"));
        assertEquals(1000, balance()); assertEquals(0, charges());
        assertEquals(due, lease("a").getPaidThrough()); assertNull(schedules.nextWake(""));
    }

    @Test void preExpiryWorkCannotChargeAndRetainsNearestOtherLeaseDeadline() throws Exception {
        long future = now() + 60000; seed("future", "ACTIVE", 500, future, null);
        seed("due", "ACTIVE", 50, now() - 1000, null);
        trigger(); assertEquals(1, charges()); assertEquals(future, schedules.nextWake(""));
        assertEquals(1L, lease("future").getVersion());
    }
    @Test void insufficientAvailableLocksWithoutUsingHeldMoneyOrResettingLockTime() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=49 WHERE owner_type='USER'");
        seed("a", "ACTIVE", 50, now() - 1000, null); trigger();
        long locked = lease("a").getLockedAt();
        assertEquals("LOCKED", lease("a").getStatus()); assertEquals(49, balance()); assertEquals(0, charges());
        trigger(); assertEquals(locked, lease("a").getLockedAt());
        assertEquals(locked + RETENTION, schedules.nextWake(""));
    }
    @Test void realCreditRegistersWakeAndUnlockStartsFreshPeriodWithoutDuplicateIssuance() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=0 WHERE owner_type='USER'");
        seed("a", "LOCKED", 50, now() - PERIOD, now() - 86400000);
        String id = UUID.randomUUID().toString(); credit(50, id); credit(50, id);
        assertEquals(50, balance()); assertEquals("LOCKED", lease("a").getStatus());
        var work = schedules.claimDue("", 1, 60000).getFirst();
        handler.executeScheduled(new ScheduledInvocation("tenant", work.key(), work.claim().revision()));
        assertTrue(schedules.succeed(work));
        assertEquals(0, balance()); assertEquals(1, charges());
        assertEquals("ACTIVE", lease("a").getStatus()); assertNull(lease("a").getLockedAt());
        assertEquals(PERIOD, lease("a").getPaidThrough() - lease("a").getPaidFrom());
        assertEquals(lease("a").getPaidThrough(), schedules.nextWake(""));
    }
    @Test void unaffordableOldLeaseDoesNotBlockAffordableLaterLease() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=50 WHERE owner_type='USER'");
        long due = now() - 10000;
        seed("a", "ACTIVE", 500, due, null); seed("b", "ACTIVE", 50, due + 1, null);
        trigger(); assertEquals("LOCKED", lease("a").getStatus());
        assertEquals("ACTIVE", lease("b").getStatus()); assertEquals(1, charges()); assertEquals(0, balance());
    }
    @Test void equalExpiryUsesStableLeaseKeyOrderNotInsertionOrder() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=50 WHERE owner_type='USER'");
        long due = now() - 1000;
        seed("b", "ACTIVE", 50, due, null); seed("a", "ACTIVE", 50, due, null);
        trigger(); assertEquals("ACTIVE", lease("a").getStatus()); assertEquals("LOCKED", lease("b").getStatus());
    }
    @Test void overFifteenDaysRemovalWinsEvenWithMoneyAndKeepsInstanceSlot() throws Exception {
        seed("old", "LOCKED", 500, now() - PERIOD, now() - RETENTION - 1000);
        trigger(); assertEquals("FINISHING", lease("old").getStatus());
        assertEquals(1000, balance()); assertEquals(0, charges());
        assertEquals(1, jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease", Integer.class));
        assertEquals("binding-old", lease("old").getBindingId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work WHERE handler=? AND business_key='old'",
                Integer.class, HostingRentLeaseWakeup.FINISH));
    }
    @Test void exactlyFifteenDaysFinalRemovalWinsAndRepeatedWakeCannotCharge() throws Exception {
        long fixed = now(); doReturn(fixed).when(renewals).nowMillis();
        seed("a", "LOCKED", 500, fixed - PERIOD, fixed - RETENTION);
        trigger(); trigger();
        assertEquals("FINISHING", lease("a").getStatus()); assertEquals(0, charges());
        assertEquals(1000, balance());
        assertEquals(1, jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease", Integer.class));
    }
    @Test void oneMillisecondBeforeFinalDeadlineCanRecover() throws Exception {
        long fixed = now(); doReturn(fixed).when(renewals).nowMillis();
        seed("a", "LOCKED", 500, fixed - PERIOD, fixed - RETENTION + 1);
        trigger(); assertEquals("ACTIVE", lease("a").getStatus()); assertEquals(1, charges());
        assertEquals(PERIOD, lease("a").getPaidThrough() - lease("a").getPaidFrom());
    }
    @Test void delayedExpiryWakeAnchorsArrearsToExpiryNotObservation() throws Exception {
        long fixed = now(); doReturn(fixed).when(renewals).nowMillis();
        jdbc.update("UPDATE economy_account SET balance_wen=0 WHERE owner_type='USER'");
        long expiry = fixed - 86400000;
        seed("a", "ACTIVE", 500, expiry, null);
        trigger(); assertEquals("LOCKED", lease("a").getStatus());
        assertEquals(expiry, lease("a").getLockedAt());
        assertEquals(expiry + RETENTION, schedules.nextWake(""));
    }
    @Test void missedExpiryBeyondGraceCannotRenewEvenIfBalanceRecovered() throws Exception {
        long fixed = now(); doReturn(fixed).when(renewals).nowMillis();
        seed("a", "ACTIVE", 500, fixed - RETENTION, null);
        trigger(); assertEquals("FINISHING", lease("a").getStatus());
        assertEquals(fixed - RETENTION, lease("a").getLockedAt());
        assertEquals(0, charges()); assertEquals(1000, balance());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work WHERE handler=? AND business_key='a'",
                Integer.class, HostingRentLeaseWakeup.FINISH));
    }
    @Test void registrationFailureRollsBackMoneyLeaseAndPendingWorkTogether() {
        long due = now() - 1000; seed("a", "ACTIVE", 500, due, null);
        service.setLeaseChangeListeners(List.of(change -> {
            leaseWakeup.changed(change); throw new IllegalStateException("schedule persistence failed");
        }));
        assertThrows(IllegalStateException.class, this::trigger);
        assertEquals(1000, balance()); assertEquals(0, charges());
        assertEquals(due, lease("a").getPaidThrough()); assertEquals(1L, lease("a").getVersion());
        assertNull(schedules.nextWake(""));
    }
    @Test void concurrentCallbacksUseBusinessPeriodNotScheduleRevision() throws Exception {
        seed("a", "ACTIVE", 500, now() - 1000, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> run = () -> { start.await(); trigger(); return null; };
            var a = pool.submit(run); var b = pool.submit(run); start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, charges()); assertEquals(500, balance());
    }
    @Test void partitionsApplyBeforeDiscoveryAndCannotWakeOtherTenant() throws Exception {
        long due = now() - 1000; seed("a", "ACTIVE", 50, due, null);
        seed("foreign", "client", "user", "foreign", "ACTIVE", 50, due - 10, null);
        seed("tenant", "other-client", "user", "other-client", "ACTIVE", 50, due - 10, null);
        seed("tenant", "client", "other-user", "other-user", "ACTIVE", 50, due - 10, null);
        trigger(); assertEquals(1, charges());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM economy_hosting_lease WHERE version=1", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> handler.executeScheduled(new ScheduledInvocation("foreign", key, 1)));
    }
    @Test void keysetPageDoesNotStarveAffordableTailBehindHundredLockedLeases() throws Exception {
        jdbc.update("UPDATE economy_account SET balance_wen=50 WHERE owner_type='USER'");
        long due = now() - 1000, lock = now() - 100;
        for (int i=0; i<101; i++) seed(String.format("l%03d", i), "LOCKED", 500, due, lock);
        seed("z", "LOCKED", 50, due, lock);
        trigger(); assertEquals(1, charges()); assertEquals("ACTIVE", lease("z").getStatus());
        assertEquals(0, balance());
    }
    @Test void finishingAndRefundedNeverCharge() throws Exception {
        seed("f", "FINISHING", 500, now() - 1000, null);
        seed("r", "REFUNDED", 500, now() - 1000, null);
        trigger(); assertEquals(0, charges()); assertEquals(1000, balance()); assertNull(schedules.nextWake(""));
    }
    @Test void concurrentCreditAndFinishingBeforePayableLeaseDoNotInvertMoneyScheduleLocks() throws Exception {
        seed("a", "LOCKED", 500, now() - PERIOD, now() - RETENTION - 1000);
        seed("b", "ACTIVE", 500, now() - 1000, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var rent = pool.submit(() -> { start.await(); trigger(); return 0; });
            var funds = pool.submit(() -> { start.await(); credit(50, UUID.randomUUID().toString()); return 0; });
            start.countDown(); rent.get(10, TimeUnit.SECONDS); funds.get(10, TimeUnit.SECONDS);
        }
        assertEquals("FINISHING", lease("a").getStatus()); assertEquals(1, charges()); assertEquals(550, balance());
    }
    private static final String REMOVAL_AGENT = "agt_0123456789abcdef0123456789abcdef";
    private static final HostingRentHttp.Actor REMOVAL_ACTOR = new HostingRentHttp.Actor("user", "tenant", "client", "owner");
    private cn.jia.agent.service.AgentIdentityService removalIdentities;
    private cn.jia.agent.dao.AgentRuntimeDao removalRuntime;
    private ManagedHostingProvisioner removalProvider;
    private HostingRentApplicationService removalApplication() {
        var owners = mock(HostingRentOwnerResolver.class);
        when(owners.requireOwner(REMOVAL_ACTOR)).thenReturn("owner");
        removalIdentities = mock(cn.jia.agent.service.AgentIdentityService.class);
        var identity = new cn.jia.agent.entity.AgentIdentityRegistryEntity().setCanonicalAgentId(REMOVAL_AGENT).setBindingId(7L);
        var binding = new cn.jia.agent.entity.AgentPersonaBindingEntity().setId(7L).setAgentId(REMOVAL_AGENT)
                .setPersonaCode("persona-remove").setJiacn("owner").setStatus(1);
        when(removalIdentities.lockRegistrationIdentityInScope("tenant", "client", "owner", REMOVAL_AGENT)).thenReturn(identity);
        when(removalIdentities.requireActiveBinding(identity, null)).thenReturn(binding);
        removalRuntime = mock(cn.jia.agent.dao.AgentRuntimeDao.class);
        removalProvider = mock(ManagedHostingProvisioner.class);
        @SuppressWarnings("unchecked") var providers = (org.springframework.beans.factory.ObjectProvider<ManagedHostingProvisioner>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(removalProvider);
        // Removing an existing lease must not depend on a current plan price or provisioner availability.
        return new HostingRentApplicationService(new cn.jia.agent.config.AgentHostingRentProperties(false, null, null, null),
                new EconomyPreviewGate(new EconomyPreviewProperties(true, List.of(new EconomyPreviewProperties.AllowedScope("tenant", "client")))),
                owners, leases, mock(HostingRentLedgerService.class), service,
                mock(cn.jia.agent.mapper.AgentHostingRentBindingMapper.class), mock(cn.jia.agent.dao.AgentPersonaBindingDao.class),
                removalRuntime, removalIdentities, providers, mock(HostingRentReconciler.class), manager, true);
    }
    private void removalLease() {
        seed("remove", "ACTIVE", 500, now() + PERIOD, null);
        jdbc.update("UPDATE economy_hosting_lease SET agent_id=?, binding_id='7' WHERE lease_id='remove'", REMOVAL_AGENT);
    }
    @Test void removalApplicationStopsExactLeaseWithoutDisablingIdentityOrExistingWork() {
        removalLease();var app = removalApplication();
        long through = leases.selectLatestLease("tenant", "client", REMOVAL_AGENT).getPaidThrough();
        var first = app.remove(REMOVAL_ACTOR, "persona-remove", REMOVAL_AGENT, "remove");
        long revision = jdbc.queryForObject("SELECT requested_revision FROM task_scheduled_work WHERE handler=? AND business_key='remove'",
                Long.class, HostingRentLeaseWakeup.FINISH);
        assertEquals(first, app.remove(REMOVAL_ACTOR, "persona-remove", REMOVAL_AGENT, "remove"));
        assertEquals("FINISHING", first.status());assertEquals(1000, balance());assertEquals(0, charges());
        var lease = leases.selectLatestLease("tenant", "client", REMOVAL_AGENT);
        assertEquals(through, lease.getPaidThrough());assertEquals("7", lease.getBindingId());
        assertEquals(revision, jdbc.queryForObject("SELECT requested_revision FROM task_scheduled_work WHERE handler=? AND business_key='remove'",
                Long.class, HostingRentLeaseWakeup.FINISH));
        verify(removalIdentities, never()).suspendForBinding(anyString(), anyString(), anyString(), anyLong());
        verifyNoInteractions(removalRuntime, removalProvider);
    }
    @Test void removalRejectsStaleBindingLeaseOrWrongPersonaWithoutTouchingMoney() {
        removalLease();var app = removalApplication();
        assertThrows(HostingRentApplicationException.class, () -> app.remove(REMOVAL_ACTOR, "other", REMOVAL_AGENT, "remove"));
        assertThrows(HostingRentApplicationException.class, () -> app.remove(REMOVAL_ACTOR, "persona-remove", REMOVAL_AGENT, "missing"));
        jdbc.update("UPDATE economy_hosting_lease SET binding_id='8' WHERE lease_id='remove'");
        assertThrows(HostingRentApplicationException.class, () -> app.remove(REMOVAL_ACTOR, "persona-remove", REMOVAL_AGENT, "remove"));
        assertEquals("ACTIVE", leases.selectLatestLease("tenant", "client", REMOVAL_AGENT).getStatus());
        assertEquals(1000, balance());assertEquals(0, charges());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work", Integer.class));
    }
    @Test void removalApplicationRollsBackRentalAndFinishingWorkWithCallingTransaction() {
        removalLease();var app = removalApplication();
        assertThrows(IllegalStateException.class, () -> new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(tx -> {app.remove(REMOVAL_ACTOR, "persona-remove", REMOVAL_AGENT, "remove");throw new IllegalStateException("caller rollback");}));
        assertEquals("ACTIVE", leases.selectLatestLease("tenant", "client", REMOVAL_AGENT).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work", Integer.class));
        assertEquals(1000, balance());assertEquals(0, charges());
    }

    private HostingRentRenewalService.Result stop(String leaseId) {
        return service.stopRenewal(scope, new EconomyPrincipal(EconomyPrincipalType.USER, "user"), leaseId);
    }
    @Test void voluntaryStopRetainsPaidPeriodIdentityAndLiveSlotWithoutRefund() throws Exception {
        long future = now() + PERIOD;
        seed("a", "ACTIVE", 500, future, null);
        var before = lease("a");
        var result = stop("a");
        var stopped = lease("a");
        assertEquals("FINISHING", result.status());
        assertNull(result.transactionId());
        assertEquals(before.getPaidFrom(), stopped.getPaidFrom());
        assertEquals(before.getPaidThrough(), stopped.getPaidThrough());
        assertEquals(before.getAgentId(), stopped.getAgentId());
        assertEquals(before.getBindingId(), stopped.getBindingId());
        assertEquals(1, jdbc.queryForObject("SELECT live_slot FROM economy_hosting_lease WHERE lease_id='a'", Integer.class));
        assertEquals(1000, balance()); assertEquals(0, charges());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work WHERE handler=? AND business_key='a'",
                Integer.class, HostingRentLeaseWakeup.FINISH));
        long version = stopped.getVersion();
        long revision = jdbc.queryForObject("SELECT requested_revision FROM task_scheduled_work WHERE handler=? AND business_key='a'",
                Long.class, HostingRentLeaseWakeup.FINISH);
        stop("a"); trigger();
        assertEquals(version, lease("a").getVersion());
        assertEquals(revision, jdbc.queryForObject("SELECT requested_revision FROM task_scheduled_work WHERE handler=? AND business_key='a'",
                Long.class, HostingRentLeaseWakeup.FINISH));
        assertEquals(1000, balance()); assertEquals(0, charges());
    }
    @Test void voluntaryStopOfLockedLeaseCannotBeReactivatedByLaterCredit() throws Exception {
        long locked = now() - 1000;
        seed("a", "LOCKED", 500, now() - PERIOD, locked);
        stop("a"); credit(50, UUID.randomUUID().toString()); trigger();
        assertEquals("FINISHING", lease("a").getStatus());
        assertEquals(locked, lease("a").getLockedAt());
        assertEquals(1050, balance()); assertEquals(0, charges());
    }
    @Test void voluntaryStopRejectsWrongPayerDisabledPartitionAndUnpaidProvisioning() {
        seed("a", "ACTIVE", 500, now() + PERIOD, null);
        var other = new EconomyPrincipal(EconomyPrincipalType.USER, "other");
        assertEquals(HostingRentException.Reason.NOT_FOUND_OR_FORBIDDEN,
                assertThrows(HostingRentException.class, () -> service.stopRenewal(scope, other, "a")).reason());
        var owner = new EconomyPrincipal(EconomyPrincipalType.USER, "user");
        assertThrows(cn.jia.economy.exception.EconomyPostingException.class, () -> service.stopRenewal(new EconomyScope("other", "client"), owner, "a"));
        assertThrows(cn.jia.economy.exception.EconomyPostingException.class, () -> service.stopRenewal(new EconomyScope("tenant", "other"), owner, "a"));
        assertThrows(HostingRentException.class, () -> service.stopRenewal(scope,
                new EconomyPrincipal(EconomyPrincipalType.SYSTEM, "user"), "a"));
        jdbc.update("UPDATE economy_hosting_lease SET status='PROVISIONING' WHERE lease_id='a'");
        assertEquals(HostingRentException.Reason.LEASE_CONFLICT, assertThrows(HostingRentException.class, () -> stop("a")).reason());
        assertEquals(1L, lease("a").getVersion()); assertEquals(1000, balance()); assertNull(schedules.nextWake(""));
    }
    @Test void voluntaryStopAndReliableFinishingRegistrationRollBackWithOuterTransaction() {
        seed("a", "ACTIVE", 500, now() + PERIOD, null);
        var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            stop("a"); throw new IllegalStateException("Agent removal failed");
        }));
        assertEquals("ACTIVE", lease("a").getStatus()); assertEquals(1L, lease("a").getVersion());
        assertNull(schedules.nextWake("")); assertEquals(1000, balance());
    }
    @Test void concurrentVoluntaryStopAndRenewalCannotChargeAfterFinishing() throws Exception {
        seed("a", "ACTIVE", 50, now() - 1000, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var removal = pool.submit(() -> { start.await(); stop("a"); return 0; });
            var renewal = pool.submit(() -> { start.await(); trigger(); return 0; });
            start.countDown(); removal.get(10, TimeUnit.SECONDS); renewal.get(10, TimeUnit.SECONDS);
        }
        assertEquals("FINISHING", lease("a").getStatus());
        int paid = charges(); assertTrue(paid == 0 || paid == 1);
        trigger(); stop("a"); assertEquals(paid, charges()); assertEquals(1000 - 50L * paid, balance());
    }
    @Test void catalogMatchesNewDdlAndDoesNotAddChecks() {
        new EconomyHostingRentSchemaInitializer(jdbc).afterPropertiesSet();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'", Integer.class));
    }
}
