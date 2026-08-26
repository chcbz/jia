package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentOutboxRelaySettings;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded polling lifecycle. Every claim/publish/settle unit is independent and batch-free. */
public final class AgentOutboxRelayScheduler implements SmartLifecycle, AutoCloseable {
    private final AgentRabbitSafetyGate gate;
    private final AgentRabbitTopologyReadiness readiness;
    private final AgentOutboxRelaySettings settings;
    private final AgentOutboxRelayService relayService;
    private final AgentOutboxPublisher publisher;
    private final String leaseOwner = "d03-relay-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger inflight = new AtomicInteger();

    private volatile ScheduledExecutorService poller;
    private volatile ExecutorService workers;

    public AgentOutboxRelayScheduler(
            AgentRabbitSafetyGate gate,
            AgentRabbitTopologyReadiness readiness,
            AgentOutboxRelaySettings settings,
            AgentOutboxRelayService relayService,
            AgentOutboxPublisher publisher) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.relayService = Objects.requireNonNull(relayService, "relayService");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-outbox-relay-poller");
            thread.setDaemon(true);
            return thread;
        });
        workers = Executors.newFixedThreadPool(settings.maxInflight(), runnable -> {
            Thread thread = new Thread(runnable, "agent-outbox-relay-publisher");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(
                this::safePoll, 0L, settings.pollDelayMillis(), TimeUnit.MILLISECONDS);
    }

    void pollOnce() {
        if (!running.get() || !dispatchReady()) return;
        int available = Math.min(
                settings.batchSize(), settings.maxInflight() - inflight.get());
        if (available <= 0) return;
        long now = System.currentTimeMillis();
        List<AgentOutboxCandidate> candidates = relayService.discover(now, available);
        int submitted = 0;
        for (AgentOutboxCandidate candidate : candidates) {
            if (submitted >= available || !running.get()) break;
            AgentOutboxClaim claim = relayService.claim(candidate, leaseOwner, now);
            if (claim.status() != AgentOutboxClaim.Status.ACQUIRED) continue;
            if (inflight.incrementAndGet() > settings.maxInflight()) {
                inflight.decrementAndGet();
                break;
            }
            submitted++;
            AgentOutboxClaimToken token = claim.token();
            try {
                workers.execute(() -> publishAndSettle(token));
            } catch (RuntimeException rejected) {
                inflight.decrementAndGet();
                break;
            }
        }
    }

    private void safePoll() {
        try {
            pollOnce();
        } catch (RuntimeException ignored) {
            // Fixed-delay retry; no row attempt is consumed unless its short claim committed.
        }
    }

    private void publishAndSettle(AgentOutboxClaimToken token) {
        try {
            AgentRabbitPublishResult result;
            try {
                result = publisher.publish(token, settings.confirmTimeoutMillis());
            } catch (RuntimeException ignored) {
                result = new AgentRabbitPublishResult(
                        AgentRabbitPublishResult.Type.EXCEPTION,
                        "NONE", "NOT_RETURNED", null, null,
                        "RABBIT_PUBLISH_EXCEPTION");
            }
            relayService.settle(token, result, System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // A committed claim intentionally remains for stale-lease recovery.
        } finally {
            inflight.decrementAndGet();
        }
    }

    private boolean dispatchReady() {
        AgentRabbitActivationState state = gate.state();
        return (state == AgentRabbitActivationState.DISPATCH_CANARY
                || state == AgentRabbitActivationState.DISPATCH_SCOPED)
                && readiness.snapshot().canonicalTopologyReady();
    }

    @Override
    public synchronized void stop() {
        stop(() -> { });
    }

    @Override
    public synchronized void stop(Runnable callback) {
        Objects.requireNonNull(callback, "callback");
        if (!running.compareAndSet(true, false)) {
            callback.run();
            return;
        }
        ScheduledExecutorService localPoller = poller;
        ExecutorService localWorkers = workers;
        if (localPoller != null) localPoller.shutdownNow();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(settings.shutdownDrainMillis());
        boolean interrupted = false;
        while (inflight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                interrupted = true;
                break;
            }
        }
        if (localWorkers != null) localWorkers.shutdownNow();
        if (interrupted) Thread.currentThread().interrupt();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    int inflightCount() {
        return inflight.get();
    }

    @Override
    public void close() {
        stop();
    }
}
