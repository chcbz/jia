package cn.jia.wx.schedule;

import cn.jia.core.redis.RedisService;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.kefu.service.KefuService;
import cn.jia.mat.service.MatPhraseService;
import cn.jia.mat.service.MatVoteService;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import cn.jia.wx.schedule.WxScheduledNotice.Kind;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Separate committed attempt before provider I/O. Unknown sends are never automatically replayed. */
@Component
@Slf4j
@ConditionalOnProperty(name="cyf.task.scheduling.enabled", havingValue="true")
public final class WxNoticeDispatch implements ScheduledJobHandler {
    public static final String HANDLER="wx.daily-delivery";
    private final WxScheduledNoticeStore notices;
    private final ScheduleService schedules;
    private final KefuMsgSubscribeService subscriptions;
    private final MatVoteService votes;
    private final MatPhraseService phrases;
    private final KefuService sender;
    private final RedisService redis;
    private final TransactionTemplate transactions;
    private final Clock clock;
    @Autowired
    public WxNoticeDispatch(WxScheduledNoticeStore notices,ScheduleService schedules,KefuMsgSubscribeService subscriptions,
            MatVoteService votes,MatPhraseService phrases,KefuService sender,RedisService redis,PlatformTransactionManager manager) {
        this(notices,schedules,subscriptions,votes,phrases,sender,redis,manager,Clock.systemUTC());
    }
    WxNoticeDispatch(WxScheduledNoticeStore notices,ScheduleService schedules,KefuMsgSubscribeService subscriptions,
            MatVoteService votes,MatPhraseService phrases,KefuService sender,RedisService redis,PlatformTransactionManager manager,Clock clock) {
        this.notices=notices;this.schedules=schedules;this.subscriptions=subscriptions;this.votes=votes;this.phrases=phrases;
        this.sender=sender;this.redis=redis;this.clock=clock;transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public String getName() { return HANDLER; }
    @Override public void executeScheduled(ScheduledInvocation invocation) {
        var key=WxScheduledNotice.Key.parse(invocation.key().clientId(),invocation.key().businessKey());
        var notice=notices.find(key);
        if(notice==null) throw new IllegalStateException("Scheduled notice receipt is missing");
        if(notice.state()!=State.PLANNED) {
            if(notice.state()==State.STARTED) log.warn("Unresolved WeChat notice {}; not sending again",key.businessKey());
            return;
        }
        if(clock.instant().atZone(WxSchedule.ZONE).toLocalDate().isAfter(key.day())) {
            skip(key,State.EXPIRED);return;
        }
        if(clock.millis()<notice.dueAt()) { schedules.signal(invocation.key(),notice.dueAt());return; }
        String type=WxSchedule.typeCode(key.kind());
        if(subscriptions.findScheduledRecipient(key.clientId(),key.subscriptionId(),type,notice.jiacn())==null) {
            skip(key,State.SKIPPED);return;
        }
        String[] content;Long questionId=null;
        if(key.kind()==Kind.VOTE) {
            var question=votes.findScheduledQuestion(key.clientId(),notice.jiacn());
            if(question==null) { skip(key,State.SKIPPED);return; }
            questionId=question.getId();
            var options=new StringBuilder();
            for(var item:question.getItems()) {
                if(!options.isEmpty()) options.append("\\n");
                options.append(item.getOpt()).append(". ").append(item.getContent());
            }
            content=new String[]{question.getTitle(),options.toString()};
        } else {
            var phrase=phrases.findScheduledRandom(key.clientId());
            if(phrase==null) { skip(key,State.SKIPPED);return; }
            content=new String[]{phrase.getContent()};
        }
        String token=UUID.randomUUID().toString();
        if(!Boolean.TRUE.equals(transactions.execute(status->notices.start(key,token,clock.millis())))) return;
        State outcome;
        try {
            var result=sender.sendScheduledWxTemplate(key.clientId(),type,notice.jiacn(),content);
            outcome=result.sent()?State.SUCCEEDED:State.FAILED;
            if(result.sent() && result.active() && questionId!=null) {
                try { redis.set(cn.jia.wx.dailyvote.WxDailyVoteKeys.answerPointer(invocation.tenantId(),result.appid(),notice.jiacn()),Long.toString(questionId),2L,TimeUnit.HOURS); }
                catch(RuntimeException cacheFailure) { log.warn("WeChat accepted notice but answer pointer write failed",cacheFailure); }
            }
        } catch(Exception failure) {
            outcome=State.UNKNOWN;
            log.warn("WeChat notice outcome is unresolved; no automatic resend",failure);
        }
        State terminal=outcome;
        transactions.executeWithoutResult(status->notices.finish(key,token,terminal,clock.millis()));
    }
    private void skip(WxScheduledNotice.Key key,State state) {
        transactions.executeWithoutResult(status->notices.skip(key,state,clock.millis()));
    }
}
