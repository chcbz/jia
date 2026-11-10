package cn.jia.wx.schedule;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.task.scheduling.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.*;
import java.util.List;
import java.util.function.LongBinaryOperator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WxScheduleTest {
    final LocalDate day=LocalDate.of(2026,11,11);
    final long at=day.atTime(7,30).atZone(WxSchedule.ZONE).toInstant().toEpochMilli();
    KefuMsgSubscribeService subscriptions;WxScheduledNoticeStore notices;ScheduleService schedules;
    CronScheduleService crons;PlatformTransactionManager manager;TransactionStatus transaction;LongBinaryOperator random;
    @BeforeEach void setup() {
        subscriptions=mock(KefuMsgSubscribeService.class);notices=mock(WxScheduledNoticeStore.class);
        schedules=mock(ScheduleService.class);crons=mock(CronScheduleService.class);manager=mock(PlatformTransactionManager.class);
        transaction=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(transaction);
        random=mock(LongBinaryOperator.class);when(random.applyAsLong(anyLong(),anyLong())).thenReturn(at+10000);
    }
    WxSchedule planner(String client,long now) {
        return new WxSchedule(subscriptions,notices,schedules,crons,new FixedTenantAdmissionPolicy("tenant",client),manager,
                Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC),random);
    }
    ScheduledInvocation occurrence(long timestamp) {
        return new ScheduledInvocation("tenant",new ScheduleKey("client",WxSchedule.HANDLER,"cron:"+timestamp,""),1);
    }
    KefuMsgSubscribeEntity recipient() {
        var recipient=new KefuMsgSubscribeEntity().setId(7L).setJiacn("owner");recipient.setClientId("client");return recipient;
    }
    @Test void registersExplicitSkipCronAndNeverSendsAtBootstrap() {
        planner("client",at).onApplicationEvent(null);planner(null,at).onApplicationEvent(null);
        verify(crons).register(new CronSchedule("client",WxSchedule.HANDLER,"","0 30 7 * * ?","Asia/Shanghai",CronSchedule.Misfire.SKIP));
        verify(crons).register(new CronSchedule("",WxSchedule.HANDLER,"","0 30 7 * * ?","Asia/Shanghai",CronSchedule.Misfire.SKIP));
        verifyNoInteractions(subscriptions,notices,schedules);
    }
    @Test void missedPreviousDayIsNotBackfilled() {
        planner("client",at+Duration.ofDays(1).toMillis()).executeScheduled(occurrence(at));
        verifyNoInteractions(subscriptions,notices,schedules,random);
    }
    @Test void planningCommitsFrozenRandomDeadlineAndScheduleTogether() {
        var key=new WxScheduledNotice.Key("client",day,WxScheduledNotice.Kind.PHRASE,7);
        when(subscriptions.findScheduledRecipients(WxSchedule.typeCode(key.kind()),0,100)).thenReturn(List.of(recipient()));
        var notice=new WxScheduledNotice(key,"owner",at+10000,WxScheduledNotice.State.PLANNED,null);
        when(notices.plan(key,"owner",at+10000)).thenReturn(notice);
        planner("client",at).executeScheduled(occurrence(at));
        var order=inOrder(manager,notices,schedules);
        order.verify(manager).getTransaction(any());order.verify(notices).find(key);
        order.verify(notices).plan(key,"owner",at+10000);
        order.verify(schedules).signal(new ScheduleKey("client",WxNoticeDispatch.HANDLER,key.businessKey(),""),at+10000);
        order.verify(manager).commit(transaction);
    }
    @Test void repeatedPlanningNeverRedrawsDeadlineOrResendsTerminalNotice() {
        var key=new WxScheduledNotice.Key("client",day,WxScheduledNotice.Kind.PHRASE,7);
        when(subscriptions.findScheduledRecipients(WxSchedule.typeCode(key.kind()),0,100)).thenReturn(List.of(recipient()));
        when(notices.find(key)).thenReturn(new WxScheduledNotice(key,"original",at+10000,WxScheduledNotice.State.PLANNED,null));
        planner("client",at+2000).executeScheduled(occurrence(at));
        verify(schedules).signal(any(),eq(at+10000));verifyNoInteractions(random);
        reset(schedules);when(notices.find(key)).thenReturn(new WxScheduledNotice(key,"original",at+10000,WxScheduledNotice.State.SUCCEEDED,"token"));
        planner("client",at+3000).executeScheduled(occurrence(at));verifyNoInteractions(schedules,random);
        verify(notices,never()).plan(any(),anyString(),anyLong());
    }
    @Test void failedScheduleRegistrationRollsBackPlanningTransaction() {
        var key=new WxScheduledNotice.Key("client",day,WxScheduledNotice.Kind.VOTE,7);
        when(subscriptions.findScheduledRecipients(WxSchedule.typeCode(key.kind()),0,100)).thenReturn(List.of(recipient()));
        when(notices.plan(key,"owner",at)).thenReturn(new WxScheduledNotice(key,"owner",at,WxScheduledNotice.State.PLANNED,null));
        doThrow(new IllegalStateException("register failed")).when(schedules).signal(any(),anyLong());
        assertThrows(IllegalStateException.class,()->planner("client",at).executeScheduled(occurrence(at)));
        verify(manager).rollback(transaction);verify(manager,never()).commit(any());
    }
}
