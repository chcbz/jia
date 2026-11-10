package cn.jia.chat.service.recommendation;

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

/** Local receipt recovery, not a model retry. Persist a recurring node-bound wake independently of
 * foreground receipt writes: a receipt can exist even when SQL was unavailable during its creation.
 */
@Component
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public final class RecommendationUsageRecovery implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER = "chat.recommendation-usage-recovery";
    private final RecommendationUsageJournal journal;
    private final CronScheduleService schedules;
    private final CronSchedule definition;

    public RecommendationUsageRecovery(RecommendationUsageJournal journal, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope,
            @Value("${cyf.task.scheduling.node-id}") String node,
            @Value("${jia.chat.recommendation.usage-recovery-cron:0 * * * * *}") String cron) {
        if (node == null || node.isBlank())
            throw new IllegalArgumentException("Local receipt recovery requires a scheduler node identity");
        this.journal = journal;
        this.schedules = schedules;
        this.definition = new CronSchedule(scope.clientId() == null ? "" : scope.clientId(),
                HANDLER, node, cron, "UTC", CronSchedule.Misfire.COALESCE);
    }

    @Override public String getName() { return HANDLER; }

    @Override public void onApplicationEvent(ApplicationReadyEvent event) {
        // Job registry is initialized by its CommandLineRunner before ApplicationReadyEvent.
        // Same definition preserves the old cursor; restart does not create another paid operation.
        schedules.register(definition);
    }

    @Override public void executeScheduled(ScheduledInvocation invocation) {
        journal.recover();
    }
}
