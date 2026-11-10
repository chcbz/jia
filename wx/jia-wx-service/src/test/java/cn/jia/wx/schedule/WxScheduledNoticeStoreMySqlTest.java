package cn.jia.wx.schedule;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.wx.schedule.WxScheduledNotice.Key;
import cn.jia.wx.schedule.WxScheduledNotice.Kind;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Private owned MySQL only. No actual WeChat/API calls or production subscriptions. */
class WxScheduledNoticeStoreMySqlTest extends SkillManagementMySqlFixture {
    final Key key = new Key("client-a", LocalDate.of(2026,11,11), Kind.PHRASE, 7);
    JdbcTemplate jdbc; JdbcWxScheduledNoticeStore store; TransactionTemplate transactions;
    @BeforeEach void setup() throws Exception {
        admin.execute("CREATE DATABASE IF NOT EXISTS cyf_wx_notice CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var ds = source("cyf_wx_notice"); jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS wx_scheduled_notice");
        try (var input = getClass().getResourceAsStream("/db/wx-scheduled-notice-v1.sql")) {
            assertNotNull(input); jdbc.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        store = new JdbcWxScheduledNoticeStore(jdbc, new FixedTenantAdmissionPolicy("tenant-a", null));
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @Test void repeatedAndRestartedPlanningKeepFirstRecipientAndRandomDeadline() {
        var first = store.plan(key, "owner-a", 1000);
        assertEquals(first, store.plan(key, "replacement-owner", 9000));
        var restarted = new JdbcWxScheduledNoticeStore(new JdbcTemplate(source("cyf_wx_notice")),
                new FixedTenantAdmissionPolicy("tenant-a", null));
        assertEquals(first, restarted.plan(key, "owner-a", 2000));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wx_scheduled_notice", Integer.class));
    }
    @Test void concurrentPlanningHasOnlyOneImmutableWinner() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> store.plan(key, "owner-a", 1000));
            var two = pool.submit(() -> store.plan(key, "owner-a", 2000));
            assertEquals(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdown(); assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS)); }
    }
    @Test void failedPlanningTransactionLeavesNoOrphanBusinessIntent() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            store.plan(key, "owner-a", 1000);
            throw new IllegalStateException("schedule registration failed");
        }));
        assertNull(store.find(key));
    }
    @Test void committedStartedFactSurvivesResultRollbackAndCannotBeReplayed() {
        store.plan(key, "owner-a", 1000);
        assertFalse(store.start(key, "early", 999));
        assertTrue(Boolean.TRUE.equals(transactions.execute(status -> store.start(key, "original", 1000))));
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            store.finish(key, "original", State.SUCCEEDED, 1001);
            throw new IllegalStateException("completion commit failed");
        }));
        var independent = new JdbcWxScheduledNoticeStore(new JdbcTemplate(source("cyf_wx_notice")),
                new FixedTenantAdmissionPolicy("tenant-a", null));
        assertEquals(State.STARTED, independent.find(key).state());
        assertFalse(independent.start(key, "new-owner", 2000));
        assertFalse(independent.skip(key, State.EXPIRED, 3000));
        assertThrows(IllegalStateException.class, () -> independent.finish(key, "wrong", State.SUCCEEDED, 3000));
        independent.finish(key, "original", State.UNKNOWN, 3000);
        assertFalse(independent.start(key, "retry-unknown", 4000));
        assertEquals(State.UNKNOWN, independent.find(key).state());
    }
    @Test void scopeDateKindAndSubscriptionAreIndependentAndSkippedWorkNeverStarts() {
        store.plan(key, "owner-a", 1000);
        var otherClient = new Key("Client-a", key.day(), Kind.PHRASE, 7);
        var nextDay = new Key(key.clientId(), key.day().plusDays(1), Kind.PHRASE, 7);
        var vote = new Key(key.clientId(), key.day(), Kind.VOTE, 7);
        var anotherSubscription = new Key(key.clientId(), key.day(), Kind.PHRASE, 8);
        for (var distinct : java.util.List.of(otherClient,nextDay,vote,anotherSubscription)) {
            assertNull(store.find(distinct)); store.plan(distinct, "owner-a", 1000);
        }
        var foreign = new JdbcWxScheduledNoticeStore(jdbc, new FixedTenantAdmissionPolicy("Tenant-a", null));
        assertNull(foreign.find(key)); foreign.plan(key, "foreign", 5000);
        var restricted = new JdbcWxScheduledNoticeStore(jdbc, new FixedTenantAdmissionPolicy("tenant-a", "client-a"));
        assertThrows(IllegalArgumentException.class, () -> restricted.find(otherClient));
        assertTrue(store.skip(key, State.SKIPPED, 1000)); assertFalse(store.start(key, "token", 2000));
        assertEquals(State.PLANNED, foreign.find(key).state());
        assertEquals(State.PLANNED, store.find(otherClient).state());
        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM wx_scheduled_notice", Integer.class));
    }
}
