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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disabled until the Chat/Agent schema and private media endpoints are deployed together. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "chat.bounty-asset", name = "enabled", havingValue = "true")
public class ChatBountyAssetRelay implements SmartLifecycle, AutoCloseable {
    private final ChatBountyAssetProjector projector;
    private final Supplier<ScheduledExecutorService> schedulerFactory;
    private final Set<WorkspaceExecutionProjectionHint> pending = new LinkedHashSet<>();
    private boolean drainScheduled;
    private long generation;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile String previousFailure;
    private volatile int failureCount;

    @Autowired
    public ChatBountyAssetRelay(ChatBountyAssetProjector projector) {
        this(projector, () -> Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-bounty-assets");
            thread.setDaemon(true);
            return thread;
        }));
    }

    ChatBountyAssetRelay(ChatBountyAssetProjector projector, Supplier<ScheduledExecutorService> schedulerFactory) {
        this.projector = Objects.requireNonNull(projector);
        this.schedulerFactory = Objects.requireNonNull(schedulerFactory);
    }

    @Override public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        generation++;
        previousFailure = null; failureCount = 0;
        scheduler = schedulerFactory.get();
        long epoch = generation;
        // Same 30s missed-hint scale as native work discovery, not a business deadline.
        scheduler.scheduleWithFixedDelay(() -> { if (current(epoch)) safePoll(); }, 0, 30, TimeUnit.SECONDS);
    }

    private synchronized boolean current(long epoch) { return running.get() && generation == epoch; }

    @EventListener
    public synchronized void onProjectionHint(WorkspaceExecutionProjectionHint hint) {
        if (!running.get()) return; // Startup/restart scan recovers the persisted fact.
        pending.add(hint);
        if (drainScheduled) return;
        drainScheduled = true;
        long epoch = generation;
        try { scheduler.execute(() -> drain(epoch)); }
        catch (java.util.concurrent.RejectedExecutionException stopping) {
            drainScheduled = false; pending.clear(); // Durable scan remains the recovery path.
        }
    }

    private void drain(long epoch) {
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
        var active = scheduler;
        scheduler = null;
        if (active != null) active.shutdown();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public void close() { stop(); }
}
