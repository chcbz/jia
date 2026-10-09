package cn.jia.chat.service;

import cn.jia.agent.service.PersonalWorkspaceExecutionService;
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
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;
    private volatile String previousFailure;
    private volatile int failureCount;

    public ChatBountyAssetRelay(ChatBountyAssetProjector projector) {
        this.projector = Objects.requireNonNull(projector);
    }

    @Override public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-bounty-assets");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::safePoll, 0, 5, TimeUnit.SECONDS);
    }

    void pollOnce() {
        String after = null;
        while (true) {
            var pending = projector.pending(after, 64);
            if (pending.isEmpty()) return;
            for (var candidate : pending) {
                // Revocation after output commit must not allow publication, but it also must
                // not prevent an unrelated owner's verified output from being reconciled.
                try { projector.project(candidate); }
                catch (PersonalWorkspaceExecutionService.Failure denied) {
                    if (denied.getReason() != PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED
                            && denied.getReason() != PersonalWorkspaceExecutionService.Reason.NOT_FOUND) throw denied;
                    log.warn("Bounty asset projection deferred by revoked/unavailable source: {}", denied.getReason());
                }
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
        var active = scheduler;
        scheduler = null;
        if (active != null) active.shutdown();
    }
    @Override public boolean isRunning() { return running.get(); }
    @Override public void close() { stop(); }
}
