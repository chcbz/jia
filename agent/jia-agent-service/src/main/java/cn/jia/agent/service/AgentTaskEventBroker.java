package cn.jia.agent.service;

import jakarta.inject.Named;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-local, lossy wakeup fan-out for durable task events.
 *
 * <p>The broker never carries event payloads and never acts as a fact source. A subscriber
 * receiving a wakeup must read the durable event sequence from MySQL. Channels exist only while
 * their exact byte-preserving scope has subscribers; blind publication is intentionally dropped.
 */
@Named
public class AgentTaskEventBroker {
    private final ConcurrentHashMap<TaskScope, ScopedChannel> channels =
            new ConcurrentHashMap<>();

    /** Subscribe to process-local wakeups for one exact task scope. */
    public Flux<TaskEventWakeup> stream(TaskScope scope) {
        TaskScope requiredScope = Objects.requireNonNull(scope, "task scope is required");
        return Flux.defer(() -> {
            SubscriptionLease lease = acquire(requiredScope);
            return lease.channel.sink.asFlux()
                    .doFinally(ignored -> lease.release());
        });
    }

    /** Publish one immutable high-water hint; publication without subscribers is dropped. */
    public void publish(TaskScope scope, long eventVersion) {
        TaskEventWakeup wakeup = new TaskEventWakeup(scope, eventVersion);
        ScopedChannel channel = channels.get(wakeup.scope());
        if (channel == null) {
            return;
        }

        synchronized (channel) {
            if (channels.get(wakeup.scope()) != channel || channel.subscribers == 0) {
                return;
            }
            Sinks.EmitResult result = channel.sink.tryEmitNext(wakeup);
            if (result == Sinks.EmitResult.OK
                    || result == Sinks.EmitResult.FAIL_ZERO_SUBSCRIBER
                    || result == Sinks.EmitResult.FAIL_OVERFLOW
                    || result == Sinks.EmitResult.FAIL_CANCELLED
                    || result == Sinks.EmitResult.FAIL_TERMINATED) {
                return;
            }
            throw new IllegalStateException("Unable to publish task event wakeup: " + result);
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
