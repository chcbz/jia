package cn.jia.chat.archive.config;

import cn.jia.chat.archive.service.ArchiveQuestionWorker;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Explicit node discovery. The question outbox continues to own claims, retries and paid execution. */
@Component
@ConditionalOnProperty(name = {"archive.question.enabled", "cyf.task.scheduling.enabled"}, havingValue = "true")
public final class ArchiveQuestionBootstrap implements ScheduledJobHandler, ApplicationListener<ApplicationReadyEvent> {
    public static final String HANDLER = "archive.question-recovery";
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveQuestionWorker worker;
    private final ScheduleService schedules;
    private final ScheduleKey key;
    private final Clock clock;
    private boolean ready;
    private boolean closed;

    @Autowired
    public ArchiveQuestionBootstrap(ArchiveQuestionAccessPolicy accessPolicy, ArchiveQuestionWorker worker,
            ScheduleService schedules, FixedTenantAdmissionPolicy scope,
            @Value("${cyf.task.scheduling.node-id}") String node) {
        this(accessPolicy, worker, schedules, scope, node, Clock.systemUTC());
    }

    ArchiveQuestionBootstrap(ArchiveQuestionAccessPolicy accessPolicy, ArchiveQuestionWorker worker,
            ScheduleService schedules, FixedTenantAdmissionPolicy scope, String node, Clock clock) {
        if (node == null || node.isBlank()) throw new IllegalArgumentException("Archive discovery requires a scheduler node identity");
        this.accessPolicy = accessPolicy;
        this.worker = worker;
        this.schedules = schedules;
        this.key = new ScheduleKey(scope.clientId() == null ? "" : scope.clientId(), HANDLER, "recovery", node);
        this.clock = clock;
    }

    @Override public String getName() { return HANDLER; }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (closed || ready || !accessPolicy.enabled()) return;
        worker.start();
        schedules.signal(key, clock.millis());
        ready = true;
    }

    @Override
    public synchronized void executeScheduled(ScheduledInvocation invocation) {
        if (closed || !ready || !accessPolicy.enabled()) return;
        schedules.signal(key, clock.millis() + 250L);
        worker.submit();
    }

    @PreDestroy
    public synchronized void stop() { closed = true; }
}
