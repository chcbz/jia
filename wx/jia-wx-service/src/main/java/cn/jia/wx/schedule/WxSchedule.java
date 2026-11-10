package cn.jia.wx.schedule;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.kefu.entity.KefuMsgTypeCode;
import cn.jia.kefu.service.KefuMsgSubscribeService;
import cn.jia.task.scheduling.*;
import cn.jia.wx.schedule.WxScheduledNotice.Kind;
import cn.jia.wx.schedule.WxScheduledNotice.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongBinaryOperator;

/** One durable daily planner; no local cron, Redis TTL lock or in-memory delay queue. */
@Component
@ConditionalOnProperty(name="cyf.task.scheduling.enabled", havingValue="true")
public final class WxSchedule implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER="wx.daily-plan";
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final KefuMsgSubscribeService subscriptions;
    private final WxScheduledNoticeStore notices;
    private final ScheduleService schedules;
    private final CronScheduleService crons;
    private final CronSchedule definition;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final LongBinaryOperator random;

    @Autowired
    public WxSchedule(KefuMsgSubscribeService subscriptions, WxScheduledNoticeStore notices, ScheduleService schedules,
            CronScheduleService crons, FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager) {
        this(subscriptions,notices,schedules,crons,scope,manager,Clock.systemUTC(),
                (start,end)->ThreadLocalRandom.current().nextLong(start,end));
    }
    WxSchedule(KefuMsgSubscribeService subscriptions, WxScheduledNoticeStore notices, ScheduleService schedules,
            CronScheduleService crons, FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager,
            Clock clock, LongBinaryOperator random) {
        this.subscriptions=subscriptions;this.notices=notices;this.schedules=schedules;this.crons=crons;
        this.clock=clock;this.random=random;
        definition=new CronSchedule(scope.clientId()==null?"":scope.clientId(),HANDLER,"","0 30 7 * * ?",
                ZONE.getId(),CronSchedule.Misfire.SKIP);
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public String getName() { return HANDLER; }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) { crons.register(definition); }
    @Override public void executeScheduled(ScheduledInvocation invocation) {
        String occurrence=invocation.key().businessKey();
        if(!occurrence.startsWith("cron:")) throw new IllegalArgumentException("Daily planner requires a cron occurrence");
        long scheduledAt=Long.parseLong(occurrence.substring(5));
        if(scheduledAt<0 || !occurrence.equals("cron:"+scheduledAt)) throw new IllegalArgumentException("Invalid occurrence timestamp");
        var day=Instant.ofEpochMilli(scheduledAt).atZone(ZONE).toLocalDate();
        // Daily content is not backfilled across calendar days after an outage.
        if(!day.equals(clock.instant().atZone(ZONE).toLocalDate())) return;
        long dayEnd=day.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
        for(Kind kind:Kind.values()) {
            long after=0;
            while(true) {
                var page=subscriptions.findScheduledRecipients(typeCode(kind),after,100);
                if(page.isEmpty()) break;
                for(var recipient:page) {
                    long now=clock.millis();
                    if(now>=dayEnd) return;
                    var key=new WxScheduledNotice.Key(recipient.getClientId(),day,kind,recipient.getId());
                    transactions.executeWithoutResult(status->{
                        var notice=notices.find(key);
                        if(notice==null) {
                            long due=kind==Kind.VOTE?scheduledAt:random.applyAsLong(Math.max(now,scheduledAt),dayEnd);
                            notice=notices.plan(key,recipient.getJiacn(),due);
                        }
                        if(notice.state()==State.PLANNED)
                            schedules.signal(new ScheduleKey(key.clientId(),WxNoticeDispatch.HANDLER,key.businessKey(),""),notice.dueAt());
                    });
                }
                after=page.getLast().getId();
                if(page.size()<100) break;
            }
        }
    }
    static String typeCode(Kind kind) {
        return (kind==Kind.VOTE?KefuMsgTypeCode.VOTE:KefuMsgTypeCode.PHRASE).getCode();
    }
}
