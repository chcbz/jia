package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * One owner thread acquires/checks/releases the watchdog lock. It runs only short discovery ticks;
 * business handlers belong on workers. Notifications coalesce, with a centralized recovery wake.
 * Wiring must honor the existing grey/non-executing deployment switch; this class does not auto-start.
 */
public final class RedisScheduleCoordinator implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RedisScheduleCoordinator.class);
    private final RLock lock;
    private final RTopic topic;
    private final String client;
    private final LongSupplier step;
    private final long recoveryMillis;
    private final long ownerRetryMillis;
    private final ExecutorService owner;
    private final Object monitor = new Object();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private long wakeRevision;
    private boolean started;
    private volatile boolean closing;

    /** step returns the delay until the nearest deadline, or Long.MAX_VALUE when idle. */
    public RedisScheduleCoordinator(RedissonClient redis, FixedTenantAdmissionPolicy scope, String node,
            LongSupplier step, long recoveryMillis, long ownerRetryMillis) {
        if (recoveryMillis <= 0 || ownerRetryMillis <= 0)
            throw new IllegalArgumentException("Positive recovery and ownership retry intervals required");
        this.lock = redis.getLock(RedisScheduleWakeup.lock(scope.businessTenantId(), scope.clientId(), node));
        this.topic = redis.getTopic(RedisScheduleWakeup.topic(scope.businessTenantId(), node), StringCodec.INSTANCE);
        this.client = scope.clientId(); this.step = step;
        this.recoveryMillis = recoveryMillis; this.ownerRetryMillis = ownerRetryMillis;
        this.owner = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "task-schedule-owner"); thread.setDaemon(true); return thread;
        });
    }
    public synchronized void start() {
        if (closing) throw new IllegalStateException("Coordinator is closed");
        if (!started) { started = true; owner.execute(this::run); }
    }
    public void wake() {
        synchronized (monitor) { wakeRevision++; monitor.notifyAll(); }
    }
    public CompletableFuture<Void> stopped() { return stopped; }

    private void run() {
        Integer subscription = null;
        try {
            while (!closing) {
                long observed;
                synchronized (monitor) { observed = wakeRevision; }
                long delay = ownerRetryMillis;
                try {
                    if (subscription == null) subscription = topic.addListener(String.class, (channel, notifiedClient) -> {
                        if (client == null || client.equals(notifiedClient)) wake();
                    });
                    // Do not reacquire an already-owned reentrant lock on every tick.
                    if (lock.isHeldByCurrentThread() || lock.tryLock(0, TimeUnit.MILLISECONDS)) {
                        if (!closing) delay = Math.min(recoveryMillis, Math.max(0, step.getAsLong()));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); break;
                } catch (RuntimeException unavailable) {
                    // In particular: never call step after a failed ownership check.
                    log.warn("Schedule coordination/discovery unavailable; no unowned fallback", unavailable);
                }
                synchronized (monitor) {
                    if (!closing && wakeRevision == observed && delay > 0) monitor.wait(delay);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            } catch (RuntimeException unavailable) { log.warn("Schedule ownership release unavailable", unavailable); }
            try {
                if (subscription != null) topic.removeListener(subscription);
            } catch (RuntimeException unavailable) { log.warn("Schedule subscription removal unavailable", unavailable); }
            stopped.complete(null);
        }
    }
    @Override public synchronized void close() {
        closing = true; wake(); owner.shutdown();
        if (!started) stopped.complete(null);
    }
}
