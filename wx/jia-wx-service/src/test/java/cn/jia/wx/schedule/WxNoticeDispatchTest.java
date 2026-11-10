package cn.jia.wx.schedule;

import cn.jia.core.redis.RedisService;
import cn.jia.kefu.entity.KefuMsgSubscribeEntity;
import cn.jia.kefu.entity.WxNoticeSendResult;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuService;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import cn.jia.task.scheduling.*;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WxNoticeDispatchTest {
    final LocalDate day=LocalDate.of(2026,11,11);
    final long at=day.atTime(12,0).atZone(WxSchedule.ZONE).toInstant().toEpochMilli();
    final WxScheduledNotice.Key key=new WxScheduledNotice.Key("client",day,WxScheduledNotice.Kind.PHRASE,7);
    WxScheduledNoticeStore notices;ScheduleService schedules;KefuMsgSubscribeService subscriptions;
    MatVoteService votes;MatPhraseService phrases;KefuService sender;RedisService redis;
    PlatformTransactionManager manager;TransactionStatus transaction;
    @BeforeEach void setup() throws Exception {
        notices=mock(WxScheduledNoticeStore.class);schedules=mock(ScheduleService.class);subscriptions=mock(KefuMsgSubscribeService.class);
        votes=mock(MatVoteService.class);phrases=mock(MatPhraseService.class);sender=mock(KefuService.class);redis=mock(RedisService.class);
        manager=mock(PlatformTransactionManager.class);transaction=mock(TransactionStatus.class);when(manager.getTransaction(any())).thenReturn(transaction);
        when(notices.find(key)).thenReturn(new WxScheduledNotice(key,"owner",at,State.PLANNED,null));
        when(notices.start(eq(key),anyString(),eq(at))).thenReturn(true);
        when(subscriptions.findScheduledRecipient("client",7,WxSchedule.typeCode(key.kind()),"owner")).thenReturn(new KefuMsgSubscribeEntity());
        when(phrases.findScheduledRandom("client")).thenReturn(new MatPhraseEntity().setContent("phrase"));
        when(sender.sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class)))
                .thenReturn(new WxNoticeSendResult(true,false,"app"));
    }
    WxNoticeDispatch dispatch(long now) {
        return new WxNoticeDispatch(notices,schedules,subscriptions,votes,phrases,sender,redis,manager,
                Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC));
    }
    ScheduledInvocation invocation() {
        return new ScheduledInvocation("tenant",new ScheduleKey("client",WxNoticeDispatch.HANDLER,key.businessKey(),""),1);
    }
    @Test void committedAcceptancePrecedesSendAndResultUsesOriginalToken() throws Exception {
        dispatch(at).executeScheduled(invocation());
        var order=inOrder(manager,notices,sender);
        var token=org.mockito.ArgumentCaptor.forClass(String.class);
        order.verify(notices).find(key);order.verify(manager).getTransaction(any());
        order.verify(notices).start(eq(key),token.capture(),eq(at));order.verify(manager).commit(transaction);
        order.verify(sender).sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class));
        order.verify(manager).getTransaction(any());order.verify(notices).finish(key,token.getValue(),State.SUCCEEDED,at);
        order.verify(manager).commit(transaction);verifyNoInteractions(redis);
    }
    @ParameterizedTest @EnumSource(value=State.class,names="PLANNED",mode=EnumSource.Mode.EXCLUDE)
    void startedAndAllTerminalFactsPreventAnotherSend(State state) {
        when(notices.find(key)).thenReturn(new WxScheduledNotice(key,"owner",at,state,"original"));
        dispatch(at).executeScheduled(invocation());verifyNoInteractions(subscriptions,phrases,sender,manager);
    }
    @Test void unknownNetworkOutcomeIsRecordedWithoutResending() throws Exception {
        when(sender.sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class)))
                .thenThrow(new java.io.IOException("response lost"));
        dispatch(at).executeScheduled(invocation());
        verify(notices).finish(eq(key),anyString(),eq(State.UNKNOWN),eq(at));
        verify(sender).sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(key.kind())),eq("owner"),any(String[].class));
        verifyNoInteractions(schedules);
    }
    @Test void completionFailurePropagatesWithoutRepeatingProvider() {
        doThrow(new IllegalStateException("result commit failed")).when(notices).finish(eq(key),anyString(),any(),anyLong());
        assertThrows(IllegalStateException.class,()->dispatch(at).executeScheduled(invocation()));
        verify(manager).rollback(transaction);
    }
    @Test void expiredNoticeNeverConsultsRecipientOrProvider() {
        dispatch(at+Duration.ofDays(1).toMillis()).executeScheduled(invocation());
        verify(notices).skip(eq(key),eq(State.EXPIRED),anyLong());verifyNoInteractions(subscriptions,phrases,sender);
    }
    @Test void earlyWakeupRestoresOriginalDeadlineWithoutSending() {
        dispatch(at-1).executeScheduled(invocation());verify(schedules).signal(invocation().key(),at);
        verifyNoInteractions(subscriptions,phrases,sender);
    }
    @Test void revokedSubscriptionSkipsWithoutProvider() {
        when(subscriptions.findScheduledRecipient(anyString(),anyLong(),anyString(),anyString())).thenReturn(null);
        dispatch(at).executeScheduled(invocation());verify(notices).skip(key,State.SKIPPED,at);
        verifyNoInteractions(phrases,sender);
    }
    @Test void competingAttemptLoserDoesNotSend() {
        when(notices.start(eq(key),anyString(),eq(at))).thenReturn(false);
        dispatch(at).executeScheduled(invocation());verifyNoInteractions(sender);
        verify(notices,never()).finish(any(),anyString(),any(),anyLong());
    }
    @Test void acceptedActiveVotePreservesTwoHourPointerAndCacheFailureDoesNotChangeDeliveryFact() throws Exception {
        var voteKey=new WxScheduledNotice.Key("client",day,WxScheduledNotice.Kind.VOTE,8);
        var invoke=new ScheduledInvocation("tenant",new ScheduleKey("client",WxNoticeDispatch.HANDLER,voteKey.businessKey(),""),1);
        when(notices.find(voteKey)).thenReturn(new WxScheduledNotice(voteKey,"owner",at,State.PLANNED,null));
        when(notices.start(eq(voteKey),anyString(),eq(at))).thenReturn(true);
        when(subscriptions.findScheduledRecipient("client",8,WxSchedule.typeCode(voteKey.kind()),"owner")).thenReturn(new KefuMsgSubscribeEntity());
        var question=new cn.jia.mat.entity.MatVoteQuestionVO();question.setId(337L);question.setTitle("question");question.setItems(java.util.List.of());
        when(votes.findScheduledQuestion("client","owner")).thenReturn(question);
        when(sender.sendScheduledWxTemplate(eq("client"),eq(WxSchedule.typeCode(voteKey.kind())),eq("owner"),any(String[].class)))
                .thenReturn(new WxNoticeSendResult(true,true,"app"));
        String pointer=cn.jia.wx.dailyvote.WxDailyVoteKeys.answerPointer("app","owner");
        doThrow(new IllegalStateException("Redis unavailable")).when(redis).set(pointer,"337",2L,java.util.concurrent.TimeUnit.HOURS);
        dispatch(at).executeScheduled(invoke);
        verify(redis).set(pointer,"337",2L,java.util.concurrent.TimeUnit.HOURS);
        verify(notices).finish(eq(voteKey),anyString(),eq(State.SUCCEEDED),eq(at));
    }

}
