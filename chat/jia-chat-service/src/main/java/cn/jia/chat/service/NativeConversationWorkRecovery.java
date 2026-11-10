package cn.jia.chat.service;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** Recover missed hints for this node's live subscriptions. The business queue/claim stays authoritative. */
@Component
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public final class NativeConversationWorkRecovery implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER = "chat.native-work-notifications";
    private final NativeConversationWorkNotifier notifier;
    private final CronScheduleService schedules;
    private final CronSchedule definition;

    public NativeConversationWorkRecovery(NativeConversationWorkNotifier notifier, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope,
            @Value("${cyf.task.scheduling.node-id}") String node,
            @Value("${jia.chat.native-work.recovery-cron:0/30 * * * * *}") String cron) {
        if (node == null || node.isBlank())
            throw new IllegalArgumentException("Native subscription recovery requires a scheduler node identity");
        this.notifier = notifier;
        this.schedules = schedules;
        this.definition = new CronSchedule(scope.clientId() == null ? "" : scope.clientId(),
                HANDLER, node, cron, "UTC", CronSchedule.Misfire.COALESCE);
    }

    @Override public String getName() { return HANDLER; }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) { schedules.register(definition); }
    @Override public void executeScheduled(ScheduledInvocation invocation) { notifier.recover(); }
}
