package cn.jia.agent.service;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final long CLOSE_GRACE_MILLIS = 250L;
    private static final long CLOSE_FORCE_MILLIS = 250L;
    private static final AtomicLong DISPATCHER_SEQUENCE = new AtomicLong();

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
            if (channel == null) {
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
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closingChannels = List.copyOf(channels.values());
            channels.clear();
            List<Runnable> queuedWakeups = new ArrayList<>();
            dispatcher.getQueue().drainTo(queuedWakeups);
            countDiscarded(queuedWakeups);
            if (!closingChannels.isEmpty()) {
                try {
                    dispatcher.execute(() -> closingChannels.forEach(
                            channel -> channel.sink.tryEmitComplete()));
                } catch (RejectedExecutionException ignored) {
                    // The executor can only reject here during an external concurrent shutdown.
                }
            }
            dispatcher.shutdown();
        }

        if (!awaitTermination(CLOSE_GRACE_MILLIS)) {
            countDiscarded(dispatcher.shutdownNow());
            if (!Thread.currentThread().isInterrupted()) {
                awaitTermination(CLOSE_FORCE_MILLIS);
            }
        }
    }

    int activeScopeCount() {
        return channels.size();
    }

    int subscriberCount(TaskScope scope) {
        ScopedChannel channel = channels.get(scope);
        if (channel == null) {
            return 0;
        }
        synchronized (channel) {
            return channels.get(scope) == channel ? channel.subscribers : 0;
        }
    }

    long droppedWakeupCount() {
        return droppedWakeups.sum();
    }

    boolean isClosed() {
        return closed.get();
    }

    private void emitIfCurrent(TaskEventWakeup wakeup, ScopedChannel channel) {
        synchronized (channel) {
            if (closed.get()
                    || channels.get(wakeup.scope()) != channel
                    || channel.subscribers == 0) {
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
        ScopedChannel channel = channels.compute(scope, (ignored, current) -> {
            ScopedChannel selected = current == null ? new ScopedChannel() : current;
            synchronized (selected) {
                selected.subscribers++;
            }
            return selected;
        });
        return new SubscriptionLease(scope, channel);
    }

    private void release(TaskScope scope, ScopedChannel channel) {
        channels.compute(scope, (ignored, current) -> {
            if (current != channel) {
                return current;
            }
            synchronized (channel) {
                if (channel.subscribers <= 0) {
                    throw new IllegalStateException(
                            "Task event broker subscriber count underflow");
                }
                channel.subscribers--;
                if (channel.subscribers == 0) {
                    channel.sink.tryEmitComplete();
                    return null;
                }
                return channel;
            }
        });
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

    private static final class ScopedChannel {
        private final Sinks.Many<TaskEventWakeup> sink =
                Sinks.many().multicast().directBestEffort();
        private int subscribers;
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
        private final AtomicBoolean released = new AtomicBoolean();

        private SubscriptionLease(TaskScope scope, ScopedChannel channel) {
            this.scope = scope;
            this.channel = channel;
        }

        private void release() {
            if (released.compareAndSet(false, true)) {
                AgentTaskEventBroker.this.release(scope, channel);
            }
        }
    }
}
