package cn.jia.agent.service;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-local, lossy wakeup fan-out for durable task events.
 *
 * <p>The broker never carries event payloads and never acts as a fact source. A subscriber
 * receiving a wakeup must read the durable event sequence from MySQL. Channels exist only while
 * their exact byte-preserving scope has subscribers; blind publication is intentionally dropped.
 */
@Named
public class AgentTaskEventBroker implements AutoCloseable {
    private static final int DEFAULT_DISPATCH_QUEUE_CAPACITY = 1024;
    private static final long DISPATCHER_IDLE_SECONDS = 30L;
    private static final long CLOSE_WAIT_MILLIS = 250L;
    private static final AtomicLong DISPATCHER_SEQUENCE = new AtomicLong();
    private static final Subscription CANCELLED_SUBSCRIPTION = new Subscription() {
        @Override
        public void request(long requested) {
            // Terminal sentinel.
        }

        @Override
        public void cancel() {
            // Terminal sentinel.
        }
    };

    private final ConcurrentHashMap<TaskScope, ScopedChannel> channels =
            new ConcurrentHashMap<>();
    private final Object lifecycleMonitor = new Object();
    private final ThreadPoolExecutor dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder droppedWakeups = new LongAdder();

    public AgentTaskEventBroker() {
        this(DEFAULT_DISPATCH_QUEUE_CAPACITY);
    }

    AgentTaskEventBroker(int dispatchQueueCapacity) {
        if (dispatchQueueCapacity <= 0) {
            throw new IllegalArgumentException("dispatchQueueCapacity must be positive");
        }
        long dispatcherId = DISPATCHER_SEQUENCE.incrementAndGet();
        this.dispatcher = new ThreadPoolExecutor(
                1,
                1,
                DISPATCHER_IDLE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(dispatchQueueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable, "agent-task-event-broker-" + dispatcherId);
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.dispatcher.allowCoreThreadTimeOut(true);
    }

    /** Subscribe to process-local wakeups for one exact task scope. */
    public Flux<TaskEventWakeup> stream(TaskScope scope) {
        TaskScope requiredScope = Objects.requireNonNull(scope, "task scope is required");
        return Flux.defer(() -> {
            SubscriptionLease lease;
            synchronized (lifecycleMonitor) {
                if (closed.get()) {
                    return Flux.error(closedFailure());
                }
                lease = acquire(requiredScope);
            }
            return lease.channel.sink.asFlux()
                    .doOnSubscribe(lease::attach)
                    .doFinally(ignored -> lease.release());
        });
    }

    /**
     * Queue one immutable high-water hint for best-effort asynchronous delivery.
     *
     * <p>Publication without subscribers or without bounded dispatcher capacity is dropped. The
     * caller never executes subscriber code and therefore cannot be delayed by a blocking
     * callback.
     */
    public void publish(TaskScope scope, long eventVersion) {
        TaskEventWakeup wakeup = new TaskEventWakeup(scope, eventVersion);
        synchronized (lifecycleMonitor) {
            if (closed.get()) {
                droppedWakeups.increment();
                throw closedFailure();
            }
            ScopedChannel channel = channels.get(wakeup.scope());
            if (channel == null || channel.leases.isEmpty()) {
                droppedWakeups.increment();
                return;
            }

            try {
                dispatcher.execute(new EmissionTask(wakeup, channel));
            } catch (RejectedExecutionException ignored) {
                droppedWakeups.increment();
                // Process-local wakeups are deliberately lossy. Durable replay is the fact source.
            }
        }
    }

    @PreDestroy
    @Override
    public void close() {
        List<ScopedChannel> closingChannels;
        List<SubscriptionLease> closingLeases = new ArrayList<>();
        List<Runnable> queuedWakeups = new ArrayList<>();
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closingChannels = List.copyOf(channels.values());
            for (ScopedChannel channel : closingChannels) {
                closingLeases.addAll(channel.leases);
                channel.leases.clear();
            }
            channels.clear();
            dispatcher.getQueue().drainTo(queuedWakeups);
        }

        countDiscarded(queuedWakeups);
        closingLeases.forEach(SubscriptionLease::cancelAndReleaseDetached);
        closingChannels.forEach(channel -> channel.sink.tryEmitComplete());
        countDiscarded(dispatcher.shutdownNow());
        awaitTermination(CLOSE_WAIT_MILLIS);
    }

    int activeScopeCount() {
        synchronized (lifecycleMonitor) {
            return channels.size();
        }
    }

    int subscriberCount(TaskScope scope) {
        synchronized (lifecycleMonitor) {
            ScopedChannel channel = channels.get(scope);
            return channel == null ? 0 : channel.leases.size();
        }
    }

    int activeLeaseCount() {
        synchronized (lifecycleMonitor) {
            return channels.values().stream()
                    .mapToInt(channel -> channel.leases.size())
                    .sum();
        }
    }

    long droppedWakeupCount() {
        return droppedWakeups.sum();
    }

    boolean isClosed() {
        return closed.get();
    }

    private void emitIfCurrent(TaskEventWakeup wakeup, ScopedChannel channel) {
        synchronized (lifecycleMonitor) {
            if (closed.get()
                    || channels.get(wakeup.scope()) != channel
                    || channel.leases.isEmpty()) {
                droppedWakeups.increment();
                return;
            }
        }

        Sinks.EmitResult result = channel.sink.tryEmitNext(wakeup);
        if (result != Sinks.EmitResult.OK) {
            droppedWakeups.increment();
        }
    }

    private SubscriptionLease acquire(TaskScope scope) {
        ScopedChannel channel = channels.computeIfAbsent(scope, ignored -> new ScopedChannel());
        SubscriptionLease lease = new SubscriptionLease(scope, channel);
        channel.leases.add(lease);
        return lease;
    }

    private boolean detach(TaskScope scope, ScopedChannel channel, SubscriptionLease lease) {
        synchronized (lifecycleMonitor) {
            boolean removed = channel.leases.remove(lease);
            if (removed && channel.leases.isEmpty() && channels.get(scope) == channel) {
                channels.remove(scope, channel);
                return true;
            }
            return false;
        }
    }

    private boolean awaitTermination(long timeoutMillis) {
        try {
            return dispatcher.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void countDiscarded(Iterable<Runnable> tasks) {
        for (Runnable task : tasks) {
            if (task instanceof EmissionTask) {
                droppedWakeups.increment();
            }
        }
    }

    private static IllegalStateException closedFailure() {
        return new IllegalStateException("Task event broker is closed");
    }

    /** Exact task scope. Validation rejects malformed identities without rewriting valid bytes. */
    public record TaskScope(String tenantId, String clientId, String taskId) {
        public TaskScope {
            tenantId = requireIdentity(tenantId, "tenantId", 50);
            clientId = requireIdentity(clientId, "clientId", 50);
            taskId = requireIdentity(taskId, "taskId", 100);
        }
    }

    /** Immutable process-local hint. Durable event data is intentionally absent. */
    public record TaskEventWakeup(TaskScope scope, long eventVersion) {
        public TaskEventWakeup {
            scope = Objects.requireNonNull(scope, "task scope is required");
            if (eventVersion <= 0) {
                throw new IllegalArgumentException("eventVersion must be positive");
            }
        }
    }

    private static String requireIdentity(String value, String field, int maxLength) {
        if (value == null || value.length() > maxLength
                || value.codePoints().allMatch(AgentTaskEventBroker::isPadding)
                || isPadding(value.codePointAt(0))
                || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    field + " is invalid: must be non-blank, unpadded, byte-exact and at most "
                            + maxLength + " chars");
        }
        return value;
    }

    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private final class ScopedChannel {
        private final Sinks.Many<TaskEventWakeup> sink =
                Sinks.many().multicast().directBestEffort();
        private final Set<SubscriptionLease> leases =
                Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private final class EmissionTask implements Runnable {
        private final TaskEventWakeup wakeup;
        private final ScopedChannel channel;

        private EmissionTask(TaskEventWakeup wakeup, ScopedChannel channel) {
            this.wakeup = wakeup;
            this.channel = channel;
        }

        @Override
        public void run() {
            emitIfCurrent(wakeup, channel);
        }
    }

    private final class SubscriptionLease {
        private final TaskScope scope;
        private final ScopedChannel channel;
        private final AtomicReference<Subscription> upstream = new AtomicReference<>();
        private final AtomicBoolean released = new AtomicBoolean();

        private SubscriptionLease(TaskScope scope, ScopedChannel channel) {
            this.scope = scope;
            this.channel = channel;
        }

        private void attach(Subscription subscription) {
            Objects.requireNonNull(subscription, "subscription");
            if (!upstream.compareAndSet(null, subscription)) {
                subscription.cancel();
            }
        }

        private void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            boolean finalLease = AgentTaskEventBroker.this.detach(scope, channel, this);
            upstream.set(CANCELLED_SUBSCRIPTION);
            if (finalLease) {
                channel.sink.tryEmitComplete();
            }
        }

        private void cancelAndReleaseDetached() {
            released.set(true);
            Subscription subscription = upstream.getAndSet(CANCELLED_SUBSCRIPTION);
            if (subscription != null && subscription != CANCELLED_SUBSCRIPTION) {
                subscription.cancel();
            }
        }
    }
}
