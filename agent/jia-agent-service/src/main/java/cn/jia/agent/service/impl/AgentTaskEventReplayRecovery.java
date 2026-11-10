package cn.jia.agent.service.impl;

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

/** Node recovery only submits a coalesced scan; authorized subscriptions and replay workers own delivery. */
@Component
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public final class AgentTaskEventReplayRecovery implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER = "agent.task-event-recovery";
    private final AgentTaskEventReplayServiceImpl replay;
    private final CronScheduleService schedules;
    private final CronSchedule definition;

    public AgentTaskEventReplayRecovery(AgentTaskEventReplayServiceImpl replay, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, @Value("${cyf.task.scheduling.node-id}") String node,
            @Value("${jia.agent.task-event.recovery-cron:0/5 * * * * *}") String cron) {
        if (node == null || node.isBlank()) throw new IllegalArgumentException("Task event recovery requires a scheduler node identity");
        this.replay = replay;
        this.schedules = schedules;
        this.definition = new CronSchedule(scope.clientId() == null ? "" : scope.clientId(), HANDLER,
                node, cron, "UTC", CronSchedule.Misfire.COALESCE);
    }

    @Override public String getName() { return HANDLER; }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) { schedules.register(definition); }
    @Override public void executeScheduled(ScheduledInvocation invocation) { replay.scheduleRecovery(); }
}
