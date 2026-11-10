package cn.jia.wx.schedule;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import cn.jia.core.redis.RedisService;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.WxNoticeSendResult;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuService;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import cn.jia.task.config.TaskTenantConfiguration;
import cn.jia.task.mapper.ScheduledWorkMapper;
import cn.jia.task.scheduling.*;
import cn.jia.wx.schedule.WxScheduledNotice.Key;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual task/notice SQL and transaction managers; isolated owned MySQL, fake provider only. */
class WxSchedulingTransactionMySqlTest extends SkillManagementMySqlFixture {
    static final String DATABASE="cyf_wx_scheduling";
    final FixedTenantAdmissionPolicy scope=new FixedTenantAdmissionPolicy("tenant","client");
    JdbcTemplate jdbc;DataSourceTransactionManager manager;JdbcWxScheduledNoticeStore notices;ScheduledWorkStore work;
    KefuMsgSubscribeService subscriptions;Clock clock;long at;Key key;
    final AtomicInteger wakeups=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        admin.execute("DROP DATABASE IF EXISTS "+DATABASE);
        admin.execute("CREATE DATABASE "+DATABASE+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var ds=source(DATABASE);jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        new ResourceDatabasePopulator(new ClassPathResource("db/task-scheduled-work-v1.sql"),
                new ClassPathResource("db/wx-scheduled-notice-v1.sql")).execute(ds);
        var mapper=mapper(ds);at=mapper.nowMillis();clock=Clock.fixed(Instant.ofEpochMilli(at),ZoneOffset.UTC);
        key=new Key("client",clock.instant().atZone(WxSchedule.ZONE).toLocalDate(),WxScheduledNotice.Kind.PHRASE,7);
        notices=new JdbcWxScheduledNoticeStore(jdbc,scope);
        work=new ScheduledWorkStore(mapper,scope,manager,k->wakeups.incrementAndGet());
        subscriptions=mock(KefuMsgSubscribeService.class);
        var recipient=new KefuMsgSubscribeEntity().setId(7L).setJiacn("owner");recipient.setClientId("client");
        when(subscriptions.findScheduledRecipients(WxSchedule.typeCode(key.kind()),0,100)).thenReturn(List.of(recipient));
        when(subscriptions.findScheduledRecipient("client",7,WxSchedule.typeCode(key.kind()),"owner")).thenReturn(recipient);
    }
    ScheduledWorkMapper mapper(javax.sql.DataSource ds) throws Exception {
        var config=new MybatisConfiguration();config.addMapper(ScheduledWorkMapper.class);
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        factory.setPlugins(new TaskTenantConfiguration().taskPlanTenantInterceptor(scope));
        return new SqlSessionTemplate(factory.getObject()).getMapper(ScheduledWorkMapper.class);
    }
    WxSchedule planner(WxScheduledNoticeStore receipts,ScheduleService schedules,long randomOffset) {
        return new WxSchedule(subscriptions,receipts,schedules,mock(CronScheduleService.class),scope,manager,clock,
                (start,end)->Math.min(start+randomOffset,end-1));
    }
    ScheduledInvocation cron() {
        return new ScheduledInvocation("tenant",new ScheduleKey("client",WxSchedule.HANDLER,"cron:"+at,""),1);
    }
    ScheduledInvocation invocation(ScheduledWorkStore.LeasedWork claim) {
        return new ScheduledInvocation("tenant",claim.key(),claim.claim().revision());
    }
    WxNoticeDispatch dispatch(WxScheduledNoticeStore receipts,KefuService sender) {
        var phrases=mock(MatPhraseService.class);when(phrases.findScheduledRandom("client")).thenReturn(new MatPhraseEntity().setContent("synthetic"));
        return new WxNoticeDispatch(receipts,work,subscriptions,mock(MatVoteService.class),phrases,sender,
                mock(RedisService.class),manager,clock);
    }
    @Test void planAndTaskCommitTogetherAndNewProcessKeepsOriginalRandomDeadline() throws Exception {
        planner(notices,work,10000).executeScheduled(cron());
        long due=notices.find(key).dueAt();assertTrue(due>=at);assertEquals(1,wakeups.get());
        var freshSource=source(DATABASE);var freshManager=new DataSourceTransactionManager(freshSource);
        var restartedWork=new ScheduledWorkStore(mapper(freshSource),scope,freshManager);
        var restartedNotices=new JdbcWxScheduledNoticeStore(new JdbcTemplate(freshSource),scope);
        new WxSchedule(subscriptions,restartedNotices,restartedWork,mock(CronScheduleService.class),scope,freshManager,
                clock,(start,end)->{throw new AssertionError("Restart must not redraw random deadline");}).executeScheduled(cron());
        assertEquals(due,restartedNotices.find(key).dueAt());assertEquals(due,restartedWork.nextWake(""));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wx_scheduled_notice",Integer.class));
    }
    @Test void scheduleFailureRollsBackBothRowsAndDoesNotPublishWakeup() {
        ScheduleService failed=new ScheduleService() {
            public void signal(ScheduleKey key,long at) {work.signal(key,at);throw new IllegalStateException("after SQL before commit");}
            public void reschedule(ScheduleKey key,long at) {throw new AssertionError();}
            public void cancel(ScheduleKey key) {throw new AssertionError();}
        };
        assertThrows(IllegalStateException.class,()->planner(notices,failed,0).executeScheduled(cron()));
        assertNull(notices.find(key));assertNull(work.nextWake(""));assertEquals(0,wakeups.get());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work",Integer.class));
    }
    @Test void concurrentSnapshotsReturnOneWinnerRatherThanLosingCommittedReceipt() throws Exception {
        var barrier=new CyclicBarrier(2);
        var simultaneous=new ForwardingStore(notices) {
            @Override public WxScheduledNotice find(Key key) {
                var result=super.find(key);
                try {barrier.await(20,TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}
                return result;
            }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->planner(simultaneous,work,1000).executeScheduled(cron()));
            var two=pool.submit(()->planner(simultaneous,work,2000).executeScheduled(cron()));
            one.get(30,TimeUnit.SECONDS);two.get(30,TimeUnit.SECONDS);
        } finally {pool.shutdown();assertTrue(pool.awaitTermination(30,TimeUnit.SECONDS));}
        long due=notices.find(key).dueAt();assertEquals(due,work.nextWake(""));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM task_scheduled_work",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wx_scheduled_notice",Integer.class));
    }
    @Test void providerSeesCommittedStartAndLostResultNeverAuthorizesReplay() throws Exception {
        planner(notices,work,0).executeScheduled(cron());
        var claim=work.claimDue("",1,60000).getFirst();
        var sender=mock(KefuService.class);
        when(sender.sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class)))
                .thenAnswer(call->{
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals("STARTED",new JdbcTemplate(source(DATABASE)).queryForObject("SELECT state FROM wx_scheduled_notice",String.class));
                    return new WxNoticeSendResult(true,false,"app");
                });
        var failedResult=new ForwardingStore(notices) {
            @Override public void finish(Key key,String token,State outcome,long now) {
                super.finish(key,token,outcome,now);throw new IllegalStateException("completion transaction lost");
            }
        };
        assertThrows(IllegalStateException.class,()->dispatch(failedResult,sender).executeScheduled(invocation(claim)));
        assertEquals(State.STARTED,notices.find(key).state());
        // Retry deadlines use the same database clock as settlement, not the earlier planning clock.
        assertTrue(work.retryAfter(claim,1));
        var reclaimed=assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),()->{
            for (;;) {
                var due=work.claimDue("",1,60000);
                if (!due.isEmpty()) return due.getFirst();
                Thread.sleep(5);
            }
        });
        dispatch(new JdbcWxScheduledNoticeStore(new JdbcTemplate(source(DATABASE)),scope),sender).executeScheduled(invocation(reclaimed));
        assertTrue(work.succeed(reclaimed));assertNull(work.nextWake(""));assertEquals(State.STARTED,notices.find(key).state());
        verify(sender,times(1)).sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class));
    }
    static class ForwardingStore implements WxScheduledNoticeStore {
        final WxScheduledNoticeStore delegate;
        ForwardingStore(WxScheduledNoticeStore delegate) {this.delegate=delegate;}
        public WxScheduledNotice plan(Key key,String owner,long due) {return delegate.plan(key,owner,due);}
        public WxScheduledNotice find(Key key) {return delegate.find(key);}
        public boolean start(Key key,String token,long now) {return delegate.start(key,token,now);}
        public void finish(Key key,String token,State state,long now) {delegate.finish(key,token,state,now);}
        public boolean skip(Key key,State reason,long now) {return delegate.skip(key,reason,now);}
    }
}
