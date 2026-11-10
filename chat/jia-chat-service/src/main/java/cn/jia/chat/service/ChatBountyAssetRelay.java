package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.entity.WorkspaceExecutionProjectionHint;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.CronSchedule;
import cn.jia.task.scheduling.CronScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import cn.jia.task.scheduling.ScheduledJobHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disabled until the Chat/Agent schema and private media endpoints are deployed together. */
@Slf4j
@Component
@ConditionalOnProperty(name = {"chat.bounty-asset.enabled", "cyf.task.scheduling.enabled"}, havingValue = "true")
public class ChatBountyAssetRelay implements SmartLifecycle, AutoCloseable, ScheduledJobHandler {
    public static final String HANDLER = "chat.bounty-assets";
    private final CronScheduleService schedules;
    private final CronSchedule definition;
    private final ChatBountyAssetProjector projector;
    private final Supplier<ExecutorService> executorFactory;
    private final Set<WorkspaceExecutionProjectionHint> pending = new LinkedHashSet<>();
    private final Object projectionLock = new Object();
    private boolean drainScheduled;
    private long generation;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ExecutorService executor;
    private volatile String previousFailure;
    private volatile int failureCount;

    @Autowired
    public ChatBountyAssetRelay(ChatBountyAssetProjector projector, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, @Value("${cyf.task.scheduling.node-id}") String node,
            @Value("${chat.bounty-asset.recovery-cron:0/30 * * * * *}") String cron) {
        this(projector, schedules, scope, node, cron, () -> Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "chat-bounty-assets");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ChatBountyAssetRelay(ChatBountyAssetProjector projector, CronScheduleService schedules,
            FixedTenantAdmissionPolicy scope, String node, String cron, Supplier<ExecutorService> executorFactory) {
        if (node == null || node.isBlank()) throw new IllegalArgumentException("Asset recovery requires a scheduler node identity");
        this.projector = Objects.requireNonNull(projector);
        this.executorFactory = Objects.requireNonNull(executorFactory);
        this.schedules = Objects.requireNonNull(schedules);
        this.definition = new CronSchedule(scope.clientId() == null ? "" : scope.clientId(),
                HANDLER, node, cron, "UTC", CronSchedule.Misfire.COALESCE);
    }

    @Override public String getName() { return HANDLER; }
    @EventListener public void onApplicationReady(ApplicationReadyEvent event) { schedules.register(definition); }
    @Override public void executeScheduled(ScheduledInvocation invocation) { safePoll(); }

    @Override public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        generation++;
        previousFailure = null; failureCount = 0;
        executor = executorFactory.get();
        // Immediate hints use this executor; durable task scheduling owns missed-hint recovery.
    }

    private synchronized boolean current(long epoch) { return running.get() && generation == epoch; }

    @EventListener
    public synchronized void onProjectionHint(WorkspaceExecutionProjectionHint hint) {
        if (!running.get()) return; // Startup/restart scan recovers the persisted fact.
        pending.add(hint);
        if (drainScheduled) return;
        drainScheduled = true;
        long epoch = generation;
        try { executor.execute(() -> drain(epoch)); }
        catch (java.util.concurrent.RejectedExecutionException stopping) {
            drainScheduled = false; pending.clear(); // Durable scan remains the recovery path.
        }
    }

    private void drain(long epoch) {
        synchronized (projectionLock) { drainSerially(epoch); }
    }

    private void drainSerially(long epoch) {
        while (true) {
            WorkspaceExecutionProjectionHint hint;
            synchronized (this) {
                if (!current(epoch)) return;
                if (pending.isEmpty()) { drainScheduled = false; return; }
                var iterator = pending.iterator(); hint = iterator.next(); iterator.remove();
            }
            try {
                for (var candidate : projector.pendingExecution(hint)) {
                    if (!current(epoch)) return;
                    project(candidate);
                }
            } catch (RuntimeException unavailable) {
                // Do not busy-retry or turn a committed result into a failed execution.
                log.warn("Immediate result projection deferred to durable compensation ({})",
                        unavailable.getClass().getSimpleName());
            }
        }
    }

    private void project(ChatBountyAssetProjector.Candidate candidate) {
        try { projector.project(candidate); }
        catch (PersonalWorkspaceExecutionService.Failure denied) {
            if (denied.getReason() != PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED
                    && denied.getReason() != PersonalWorkspaceExecutionService.Reason.NOT_FOUND) throw denied;
            log.warn("Bounty asset projection deferred by revoked/unavailable source: {}", denied.getReason());
        }
    }

    void pollOnce() {
        String after = null;
        while (true) {
            var pending = projector.pending(after, 64);
            if (pending.isEmpty()) return;
            for (var candidate : pending) {
                // Revocation after output commit must not allow publication, but it also must
                // not prevent an unrelated owner's verified output from being reconciled.
                project(candidate);
            }
            after = pending.getLast().stepId();
            if (pending.size() < 64) return;
        }
    }

    private void safePoll() {
        synchronized (projectionLock) { recoverSerially(); }
    }

    private void recoverSerially() {
        if (!running.get()) return;
        try { pollOnce(); previousFailure = null; failureCount = 0; }
        catch (RuntimeException failure) {
            String signature = failure.getClass().getName() + ":" + failure.getMessage();
            failureCount = Objects.equals(signature, previousFailure) ? failureCount + 1 : 1;
            previousFailure = signature;
            log.warn("Committed bounty assets could not be projected", failure);
            if (failureCount >= 2) { log.error("Bounty asset relay halted on unchanged failure"); stop(); }
        }
    }

    @Override public synchronized void stop() {
        running.set(false);
        generation++; pending.clear(); drainScheduled = false;
        var active = executor;
        executor = null;
        if (active != null) active.shutdown();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public void close() { stop(); }
}
