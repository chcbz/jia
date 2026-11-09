package cn.jia.task.scheduling;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.job.JobRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded workers; no business callback on the owner/timer thread or inside claim transactions. */
public final class ScheduledJobRunner implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ScheduledJobRunner.class);
    private final ScheduledWorkStore store;
    private final JobRegistry registry;
    private final String tenant;
    private final String node;
    private final int batch;
    private final long leaseMillis;
    private final long retryMillis;
    private final Runnable wake;
    private final ExecutorService workers;
    private final ScheduledExecutorService heartbeats;
    private final Semaphore capacity;
    private final AtomicInteger active = new AtomicInteger();
    private boolean closing;

    public ScheduledJobRunner(ScheduledWorkStore store, JobRegistry registry, FixedTenantAdmissionPolicy scope,
            String node, int concurrency, int batch, long leaseMillis, long retryMillis, Runnable wake) {
        if (concurrency <= 0 || batch <= 0 || leaseMillis <= 0 || retryMillis <= 0)
            throw new IllegalArgumentException("Positive worker, batch, lease and retry settings required");
        this.store = store; this.registry = registry; this.tenant = scope.businessTenantId(); this.node = node;
        this.batch = batch; this.leaseMillis = leaseMillis; this.retryMillis = retryMillis; this.wake = wake;
        this.capacity = new Semaphore(concurrency);
        this.workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), runnable -> daemon(runnable, "task-schedule-worker"));
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "task-schedule-lease"));
    }
    private static Thread daemon(Runnable runnable, String name) {
        var thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
    }

    /** Called only by the coordinator after ownership confirmation. Does not wait for business completion. */
    public synchronized long tick() {
        if (closing || capacity.availablePermits() == 0) return Long.MAX_VALUE;
        Long delay = store.nextDelay(node);
        if (delay == null) return Long.MAX_VALUE;
        if (delay > 0) return delay;
        int slots = Math.min(batch, capacity.availablePermits());
        capacity.acquireUninterruptibly(slots);
        java.util.List<ScheduledWorkStore.LeasedWork> claims;
        try { claims = store.claimDue(node, slots, leaseMillis); }
        catch (RuntimeException failure) { capacity.release(slots); throw failure; }
        capacity.release(slots - claims.size());
        // Due rows may be locked by another transaction. Do not spin on an unchanged overdue index.
        if (claims.isEmpty()) return retryMillis;
        for (var claim : claims) {
            active.incrementAndGet();
            var pulse = heartbeats.scheduleWithFixedDelay(() -> renew(claim),
                    Math.max(1, leaseMillis / 3), Math.max(1, leaseMillis / 3), TimeUnit.MILLISECONDS);
            workers.execute(() -> execute(claim, pulse));
        }
        if (capacity.availablePermits() == 0) return Long.MAX_VALUE;
        delay = store.nextDelay(node);
        return delay == null ? Long.MAX_VALUE : delay;
    }
    private void renew(ScheduledWorkStore.LeasedWork claim) {
        try {
            if (!store.renewFor(claim, leaseMillis))
                throw new IllegalStateException("Scheduled claim no longer owned");
        } catch (RuntimeException failure) {
            // Stop this heartbeat on failure; do not interrupt business or assume its effects rolled back.
            log.warn("Scheduled claim renewal failed; result remains fenced", failure);
            throw failure;
        }
    }
    private void execute(ScheduledWorkStore.LeasedWork claim, ScheduledFuture<?> pulse) {
        try {
            var registered = registry.getHandler(claim.key().handler());
            if (!(registered instanceof ScheduledJobHandler handler))
                throw new IllegalStateException("Durable handler unavailable: " + claim.key().handler());
            handler.executeScheduled(new ScheduledInvocation(tenant, claim.key(), claim.claim().revision()));
            if (!store.succeed(claim)) log.warn("Ignoring stale scheduled completion for handler {}", claim.key().handler());
        } catch (Exception failure) {
            log.warn("Scheduled handler failed; durable work retained for reconciliation: {}", claim.key().handler(), failure);
            try { store.retryAfter(claim, retryMillis); }
            catch (RuntimeException unavailable) { log.warn("Cannot persist retry; lease recovery retains work", unavailable); }
        } finally {
            pulse.cancel(false);
            capacity.release();
            synchronized (this) {
                if (active.decrementAndGet() == 0 && closing) heartbeats.shutdown();
            }
            wake.run();
        }
    }
    @Override public synchronized void close() {
        closing = true; workers.shutdown();
        // Already accepted work/heartbeats finish; no shutdownNow or business deadline.
        if (active.get() == 0) heartbeats.shutdown();
    }
}
