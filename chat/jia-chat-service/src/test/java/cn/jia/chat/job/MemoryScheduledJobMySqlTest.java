package cn.jia.chat.job;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.chat.memory.JdbcMemoryJobRunStore;
import cn.jia.chat.memory.MemoryJobRunStore;
import cn.jia.chat.memory.MemoryJobRunStore.Outcome;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable private MySQL; real unique keys, commit visibility and rollback, no provider calls. */
class MemoryScheduledJobMySqlTest extends SkillManagementMySqlFixture {
    final long at = 1_700_000_000_000L;
    final FixedTenantAdmissionPolicy scope = new FixedTenantAdmissionPolicy("tenant", "client");
    JdbcTemplate jdbc;
    DataSourceTransactionManager manager;
    JdbcMemoryJobRunStore store;
    @BeforeEach void setup() throws Exception {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_memory_scheduling CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var dataSource = source("cyf_memory_scheduling");
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS chat_memory_job_run");
        try (var input = getClass().getResourceAsStream("/db/chat-memory-job-run-v1.sql")) {
            assertNotNull(input);
            jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        store = new JdbcMemoryJobRunStore(jdbc, scope);
    }
    MemoryJobHandler business() {
        var business = mock(MemoryJobHandler.class);
        when(business.getCron()).thenReturn("0 0 * * * ?");
        when(business.getName()).thenReturn("memory_sync"); when(business.getGroup()).thenReturn("chat");
        return business;
    }
    MemoryScheduledJob job(MemoryJobHandler business, MemoryJobRunStore runs) {
        return new MemoryScheduledJob(business, runs, mock(CronScheduleService.class), scope, manager, "UTC",
                Clock.fixed(Instant.ofEpochMilli(at + 10), ZoneOffset.UTC));
    }
    ScheduledInvocation occurrence(long timestamp) {
        return new ScheduledInvocation("tenant", new ScheduleKey("client", MemoryScheduledJob.HANDLER, "cron:" + timestamp, ""), 7);
    }
    @Test void twoOwnersCannotRepeatStartedWorkAndAcceptanceIsCommittedBeforeProvider() throws Exception {
        var business = business(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger(); var pool = Executors.newFixedThreadPool(2);
        when(business.execute(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(Outcome.STARTED, new JdbcMemoryJobRunStore(new JdbcTemplate(source("cyf_memory_scheduling")), scope).find(at).outcome());
            calls.incrementAndGet(); entered.countDown(); release.await(); return "done";
        });
        try {
            var first = pool.submit(() -> job(business, store).executeScheduled(occurrence(at)));
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            pool.submit(() -> job(business, new JdbcMemoryJobRunStore(jdbc, scope)).executeScheduled(occurrence(at))).get(15, TimeUnit.SECONDS);
            assertEquals(1, calls.get()); assertEquals(Outcome.STARTED, store.find(at).outcome());
            release.countDown(); first.get(15, TimeUnit.SECONDS);
            assertEquals(Outcome.SUCCEEDED, store.find(at).outcome());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_job_run", Integer.class));
        } finally { release.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }
    @Test void rolledBackCompletionRetainsUnknownReceiptAndCannotReplayProvider() throws Exception {
        var business = business(); when(business.execute(any())).thenReturn("done");
        MemoryJobRunStore failing = new MemoryJobRunStore() {
            public boolean start(long at, String token, long now) { return store.start(at, token, now); }
            public Run find(long at) { return store.find(at); }
            public void finish(long at, String token, Outcome outcome, long now) {
                store.finish(at, token, outcome, now); throw new IllegalStateException("injected completion rollback");
            }
        };
        assertThrows(IllegalStateException.class, () -> job(business, failing).executeScheduled(occurrence(at)));
        assertEquals(Outcome.STARTED, store.find(at).outcome());
        job(business, store).executeScheduled(occurrence(at));
        verify(business).execute(any()); assertEquals(Outcome.STARTED, store.find(at).outcome());
    }
    @Test void failedOccurrenceStaysFailedWhileLaterPeriodCanRetryPendingBusiness() throws Exception {
        var business = business(); when(business.execute(any())).thenThrow(new IllegalStateException("provider unavailable")).thenReturn("done");
        var job = job(business, store);
        job.executeScheduled(occurrence(at)); job.executeScheduled(occurrence(at));
        job.executeScheduled(occurrence(at + 3_600_000));
        assertEquals(Outcome.FAILED, store.find(at).outcome());
        assertEquals(Outcome.SUCCEEDED, store.find(at + 3_600_000).outcome());
        verify(business, times(2)).execute(any());
    }
    @Test void exactPartitionsAndAttemptTokenFenceResultWithoutCrossScopeMutation() {
        for (String[] foreign : new String[][]{{"Tenant","client"},{"tenant ","client"},{"tenant","Client"},{"tenant","client "}}) {
            jdbc.update("INSERT INTO chat_memory_job_run VALUES (?,?,?,?,'STARTED',?,NULL)", foreign[0], foreign[1], at, "foreign", at);
        }
        assertTrue(store.start(at, "original", at)); assertFalse(store.start(at, "other", at));
        assertEquals("original", store.find(at).startToken());
        assertThrows(IllegalStateException.class, () -> store.finish(at, "other", Outcome.SUCCEEDED, at + 1));
        store.finish(at, "original", Outcome.SUCCEEDED, at + 1);
        assertThrows(IllegalStateException.class, () -> store.finish(at, "original", Outcome.FAILED, at + 2));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory_job_run WHERE outcome='STARTED'", Integer.class));
    }
}
