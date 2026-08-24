package cn.jia.agent.service.impl;

import cn.jia.agent.config.AgentCommandReissueSettings;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentCommandReissueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Non-blocking bounded reconnect queue plus bounded restart/lost-signal fallback scanner. */
public final class AgentCommandReissueCoordinator implements AgentCommandReconnectSignal, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommandReissueCoordinator.class);
    private static final long CLOSE_AWAIT_MILLIS = 1_000L;

    private final AgentCommandReissueService service;
    private final AgentCommandReissueSettings settings;
    private final ArrayBlockingQueue<AgentCommandReconnectScope> signals;
    private final LongSupplier nowMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService worker;
    private volatile long schedulerCursor;

    public AgentCommandReissueCoordinator(
            AgentCommandReissueService service,
            AgentCommandReissueSettings settings) {
        this(service, settings, Clock.systemUTC()::millis, true);
    }

    AgentCommandReissueCoordinator(
            AgentCommandReissueService service,
            AgentCommandReissueSettings settings,
            LongSupplier nowMillis,
            boolean start) {
        this.service = Objects.requireNonNull(service, "service");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
        this.signals = new ArrayBlockingQueue<>(settings.queueCapacity());
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-command-reissue");
            thread.setDaemon(true);
            return thread;
        });
        if (start) {
            worker.scheduleWithFixedDelay(
                    this::runSafely, settings.pollDelayMillis(), settings.pollDelayMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public boolean signalReconnect(AgentCommandReconnectScope scope) {
        if (closed.get() || !validScope(scope)) return false;
        return signals.offer(scope);
    }

    void runOnce() {
        if (closed.get()) return;
        long now = nowMillis.getAsLong();
        if (now <= 0) throw new IllegalStateException("clock returned a non-positive epoch millis");
        for (int i = 0; i < settings.reconnectBatchSize(); i++) {
            AgentCommandReconnectScope scope = signals.poll();
            if (scope == null) break;
            service.reissueForReconnect(scope, settings.schedulerBatchSize(), now);
        }
        AgentCommandReissueScanResult due = service.reissueDue(
                settings.schedulerBatchSize(), schedulerCursor, now);
        if (due != null && due.examined() > 0) {
            schedulerCursor = due.lastVisitedDeliveryId();
        } else if (schedulerCursor > 0) {
            schedulerCursor = 0;
        }
    }

    int queuedSignalCount() {
        return signals.size();
    }

    long schedulerCursor() {
        return schedulerCursor;
    }

    private void runSafely() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            LOG.warn("Agent command reissue scheduler iteration failed, reason={}",
                    safeFailureCode(failure));
        }
    }

    private boolean validScope(AgentCommandReconnectScope scope) {
        return scope != null
                && exact(scope.tenantId(), 50)
                && exact(scope.clientId(), 50)
                && exact(scope.targetAgentId(), 100)
                && exact(scope.requestedBy(), 100)
                && AgentCommandReissueServiceImpl.REASON_AGENT_RECONNECT.equals(scope.reason());
    }

    private boolean exact(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private String safeFailureCode(RuntimeException failure) {
        String simple = failure.getClass().getSimpleName();
        return simple != null && simple.matches("[A-Za-z0-9_$]{1,100}")
                ? simple : "RUNTIME_FAILURE";
    }

    boolean workerTerminated() {
        return worker.isTerminated();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdownNow();
        signals.clear();
        try {
            if (!worker.awaitTermination(CLOSE_AWAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                // The worker is daemon-backed, so a bounded shutdown cannot hold JVM termination.
                LOG.warn("Agent command reissue worker did not terminate within bounded shutdown");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("Interrupted while awaiting Agent command reissue worker shutdown");
        }
    }
}
