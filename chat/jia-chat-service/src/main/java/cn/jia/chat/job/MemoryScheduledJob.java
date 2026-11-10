package cn.jia.chat.job;

import cn.jia.chat.memory.MemoryJobRunStore;
import cn.jia.chat.memory.MemoryJobRunStore.Outcome;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobContext;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/** Explicit cron adapter. Task workers execute outside claim transactions, never on the timer.
 * The separate business receipt distinguishes failed/unresolved model work from dispatch completion.
 * A later scheduled occurrence can retry pending conversations as before; this occurrence cannot.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cyf.task.scheduling.enabled", havingValue = "true")
public final class MemoryScheduledJob implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER = "chat.memory-sync";
    private final MemoryJobHandler business;
    private final MemoryJobRunStore runs;
    private final CronScheduleService schedules;
    private final CronSchedule definition;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public MemoryScheduledJob(MemoryJobHandler business, MemoryJobRunStore runs, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager,
            @Value("${chat.memory.job.zone:Asia/Shanghai}") String zone) {
        this(business, runs, schedules, scope, manager, zone, Clock.systemUTC());
    }

    MemoryScheduledJob(MemoryJobHandler business, MemoryJobRunStore runs, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, PlatformTransactionManager manager, String zone, Clock clock) {
        this.business = business; this.runs = runs; this.schedules = schedules; this.clock = clock;
        this.definition = new CronSchedule(scope.clientId() == null ? "" : scope.clientId(), HANDLER,
                "", business.getCron(), zone, CronSchedule.Misfire.COALESCE);
        this.transactions = new TransactionTemplate(manager);
        // Acceptance must commit BEFORE provider I/O; completion is another short transaction.
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override public String getName() { return HANDLER; }
    @Override public void onApplicationEvent(ApplicationReadyEvent event) { schedules.register(definition); }

    @Override public void executeScheduled(ScheduledInvocation invocation) {
        String key = invocation.key().businessKey();
        if (!key.startsWith("cron:")) throw new IllegalArgumentException("Memory job requires a cron occurrence");
        long scheduledAt = Long.parseLong(key.substring(5));
        if (scheduledAt < 0 || !key.equals("cron:" + scheduledAt))
            throw new IllegalArgumentException("Memory occurrence must be a canonical timestamp");
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(transactions.execute(status -> runs.start(scheduledAt, token, clock.millis())))) {
            var previous = runs.find(scheduledAt);
            if (previous == null) throw new IllegalStateException("Memory acceptance receipt is unavailable");
            // STARTED may still run on an old owner, or may have lost its completion evidence.
            // Neither condition authorizes replay, interruption or a fabricated successful result.
            if (previous.outcome() == Outcome.STARTED)
                log.warn("Memory occurrence {} remains unresolved; provider work was not repeated", scheduledAt);
            return;
        }
        Outcome outcome;
        try {
            String result = business.execute(JobContext.builder().jobId("memory-sync-" + scheduledAt)
                    .jobName(business.getName()).group(business.getGroup()).triggerTime(scheduledAt).build());
            outcome = MemoryJobHandler.ALREADY_RUNNING.equals(result) ? Outcome.SKIPPED : Outcome.SUCCEEDED;
        } catch (Exception failure) {
            outcome = Outcome.FAILED;
            log.warn("Memory occurrence {} failed; pending snapshots retained for a later occurrence", scheduledAt, failure);
        }
        Outcome terminal = outcome;
        transactions.executeWithoutResult(status -> runs.finish(scheduledAt, token, terminal, clock.millis()));
    }
}
