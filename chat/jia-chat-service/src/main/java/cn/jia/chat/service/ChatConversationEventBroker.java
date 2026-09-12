package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * In-process conversation event broker with a monotonic deletion fence.
 *
 * <p>The stripe lock is acquired by both publication and deletion. Production deletion keeps its
 * stripe locked until transaction completion, so a callback can publish entirely before deletion
 * starts or observe the committed tombstone; it cannot publish in the commit/invalidation gap.</p>
 */
@Component
public class ChatConversationEventBroker {
    private static final int STRIPE_COUNT = 64;

    private final Map<String, EventSink> sinks = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];

    public ChatConversationEventBroker() {
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** Legacy test/helper entrypoint. Production paths must pass a persisted generation and live check. */
    public Flux<String> stream(String conversationId) {
        return stream(conversationId, 1L, () -> true);
    }

    public Flux<String> stream(
            String conversationId, long generation, BooleanSupplier persistentLiveCheck) {
        return Flux.defer(() -> {
            EventSink eventSink = retain(
                    conversationId, generation, false, persistentLiveCheck);
            if (eventSink == null) {
                return Flux.empty();
            }
            return eventSink.events.asFlux()
                    .doFinally(ignored -> release(conversationId, eventSink, false));
        });
    }

    /** Emits once when deletion commits, allowing HTTP/relay pipelines to dispose their source. */
    public Flux<Boolean> deletionSignal(
            String conversationId, long generation, BooleanSupplier persistentLiveCheck) {
        return Flux.defer(() -> {
            EventSink eventSink = retain(
                    conversationId, generation, true, persistentLiveCheck);
            if (eventSink == null) {
                return Flux.just(Boolean.TRUE);
            }
            return eventSink.deleted.asMono().flux()
                    .doFinally(ignored -> release(conversationId, eventSink, true));
        });
    }

    /** Legacy helper retained for non-conversation tests. */
    public void publish(String conversationId, Map<String, ?> event) {
        publishIfLive(conversationId, 1L, () -> true, event);
    }

    public boolean publishIfLive(
            String conversationId,
            long generation,
            BooleanSupplier persistentLiveCheck,
            Map<String, ?> event) {
        return runIfLive(conversationId, generation, persistentLiveCheck, () -> {
            EventSink eventSink = sinks.get(conversationId);
            if (eventSink != null && eventSink.generation == generation && !eventSink.invalidated) {
                eventSink.events.tryEmitNext(JsonUtil.toSafeJson(event));
            }
        });
    }

    /** Atomically validates persistent state and performs one outbound publication action. */
    public boolean runIfLive(
            String conversationId,
            long generation,
            BooleanSupplier persistentLiveCheck,
            Runnable publication) {
        if (!validKey(conversationId, generation)
                || persistentLiveCheck == null || publication == null) {
            return false;
        }
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        try {
            if (!persistentLiveCheck.getAsBoolean()) {
                return false;
            }
            publication.run();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires the same fence used by publishers. The caller must hold this guard through DB
     * transaction completion and call {@link DeletionFence#commitDeleted(long)} only after commit.
     */
    public DeletionFence beginDeletion(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        return new DeletionFence(conversationId, lock);
    }

    int subscriberCount(String conversationId) {
        EventSink sink = sinks.get(conversationId);
        return sink == null ? 0 : sink.subscribers.get();
    }

    int watcherCount(String conversationId) {
        EventSink sink = sinks.get(conversationId);
        return sink == null ? 0 : sink.watchers.get();
    }

    private EventSink retain(
            String conversationId, long generation, boolean watcher,
            BooleanSupplier persistentLiveCheck) {
        if (!validKey(conversationId, generation) || persistentLiveCheck == null) {
            return null;
        }
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        try {
            try {
                if (!persistentLiveCheck.getAsBoolean()) {
                    return null;
                }
            } catch (RuntimeException unavailable) {
                return null;
            }
            EventSink existing = sinks.get(conversationId);
            if (existing == null) {
                existing = new EventSink(generation);
                sinks.put(conversationId, existing);
            }
            if (existing.invalidated || existing.generation != generation) {
                return null;
            }
            (watcher ? existing.watchers : existing.subscribers).incrementAndGet();
            return existing;
        } finally {
            lock.unlock();
        }
    }

    private void release(String conversationId, EventSink eventSink, boolean watcher) {
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        try {
            AtomicInteger counter = watcher ? eventSink.watchers : eventSink.subscribers;
            counter.updateAndGet(value -> Math.max(0, value - 1));
            if (eventSink.subscribers.get() == 0 && eventSink.watchers.get() == 0) {
                sinks.remove(conversationId, eventSink);
            }
        } finally {
            lock.unlock();
        }
    }

    private void invalidateLocked(String conversationId, long generation) {
        EventSink eventSink = sinks.remove(conversationId);
        if (eventSink != null) {
            eventSink.invalidated = true;
            eventSink.deleted.tryEmitValue(Boolean.TRUE);
            eventSink.events.tryEmitComplete();
        }
    }

    private ReentrantLock stripe(String conversationId) {
        return stripes[(conversationId.hashCode() & Integer.MAX_VALUE) % stripes.length];
    }

    private boolean validKey(String conversationId, long generation) {
        return conversationId != null && !conversationId.isBlank() && generation >= 1;
    }

    private static final class EventSink {
        private final long generation;
        private final Sinks.Many<String> events = Sinks.many().multicast().directBestEffort();
        private final Sinks.One<Boolean> deleted = Sinks.one();
        private final AtomicInteger subscribers = new AtomicInteger();
        private final AtomicInteger watchers = new AtomicInteger();
        private volatile boolean invalidated;

        private EventSink(long generation) {
            this.generation = generation;
        }
    }

    public final class DeletionFence implements AutoCloseable {
        private final String conversationId;
        private final ReentrantLock lock;
        private boolean closed;

        private DeletionFence(String conversationId, ReentrantLock lock) {
            this.conversationId = conversationId;
            this.lock = lock;
        }

        public void commitDeleted(long deletedGeneration) {
            if (closed || deletedGeneration < 1) {
                throw new IllegalStateException("Deletion fence is not active");
            }
            invalidateLocked(conversationId, deletedGeneration);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                lock.unlock();
            }
        }
    }
}
