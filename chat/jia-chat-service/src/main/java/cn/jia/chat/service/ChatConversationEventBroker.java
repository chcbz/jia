package cn.jia.chat.service;

import cn.jia.core.util.JsonUtil;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** In-process live optimization layered over the durable conversation event journal. */
@Component
public class ChatConversationEventBroker {
    private static final int STRIPE_COUNT = 64;
    private static final int LIVE_BUFFER_SIZE = 1024;

    private final Map<String, EventSink> sinks = new ConcurrentHashMap<>();
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];
    private final AtomicLong subscriberIds = new AtomicLong();

    public ChatConversationEventBroker() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock();
    }

    public Flux<String> stream(String conversationId) { return stream(conversationId, 1L, () -> true); }
    public Flux<String> stream(String conversationId, long generation, BooleanSupplier persistentLiveCheck) {
        return stream(conversationId, generation, persistentLiveCheck, null);
    }

    public Flux<String> stream(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck, String initialFrame) {
        return Flux.defer(() -> {
            LiveSubscription subscription = subscribeBuffered(conversationId, generation, persistentLiveCheck);
            if (subscription == null) return Flux.empty();
            Flux<String> live = subscription.flux();
            Flux<String> result = initialFrame == null ? live : Flux.just(initialFrame).concatWith(live);
            return ChatStreamPolicy.bounded(result, ignored -> subscription.close())
                    .doFinally(ignored -> subscription.close());
        });
    }

    /** Registers a bounded per-client live buffer immediately, before callers read a DB watermark/replay. */
    public LiveSubscription subscribeBuffered(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck) {
        if (!validKey(conversationId, generation) || persistentLiveCheck == null) return null;
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        try {
            try { if (!persistentLiveCheck.getAsBoolean()) return null; }
            catch (RuntimeException unavailable) { return null; }
            EventSink eventSink = sinks.computeIfAbsent(conversationId, ignored -> new EventSink(generation));
            if (eventSink.invalidated || eventSink.generation != generation) return null;
            long id = subscriberIds.incrementAndGet();
            Sinks.Many<String> buffer = Sinks.many().replay().limit(LIVE_BUFFER_SIZE);
            eventSink.subscribers.put(id, buffer);
            return new LiveSubscription(conversationId, eventSink, id, buffer);
        } finally { lock.unlock(); }
    }

    public Flux<Boolean> deletionSignal(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck) {
        return Flux.defer(() -> {
            EventSink eventSink = retainWatcher(conversationId, generation, persistentLiveCheck);
            if (eventSink == null) return Flux.just(Boolean.TRUE);
            return eventSink.deleted.asMono().flux()
                    .doFinally(ignored -> releaseWatcher(conversationId, eventSink));
        });
    }

    public void publish(String conversationId, Map<String, ?> event) {
        publishIfLive(conversationId, 1L, () -> true, event);
    }

    public boolean publishIfLive(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck, Map<String, ?> event) {
        return runIfLive(conversationId, generation, persistentLiveCheck, () -> publishLocked(conversationId, event));
    }

    public boolean publishIfSubscribed(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck, Map<String, ?> event) {
        AtomicBoolean delivered = new AtomicBoolean();
        boolean live = runIfLive(conversationId, generation, persistentLiveCheck, () -> {
            EventSink eventSink = sinks.get(conversationId);
            if (eventSink == null || eventSink.generation != generation || eventSink.invalidated) return;
            String json = JsonUtil.toSafeJson(event);
            for (Sinks.Many<String> subscriber : eventSink.subscribers.values()) {
                delivered.compareAndSet(false, subscriber.tryEmitNext(json).isSuccess());
            }
        });
        return live && delivered.get();
    }

    private void publishLocked(String conversationId, Map<String, ?> event) {
        EventSink eventSink = sinks.get(conversationId);
        if (eventSink == null || eventSink.invalidated) return;
        String json = JsonUtil.toSafeJson(event);
        for (Sinks.Many<String> subscriber : eventSink.subscribers.values()) subscriber.tryEmitNext(json);
    }

    public boolean runIfLive(String conversationId, long generation,
            BooleanSupplier persistentLiveCheck, Runnable publication) {
        if (!validKey(conversationId, generation) || persistentLiveCheck == null || publication == null) return false;
        ReentrantLock lock = stripe(conversationId);
        lock.lock();
        try {
            if (!persistentLiveCheck.getAsBoolean()) return false;
            publication.run();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        } finally { lock.unlock(); }
    }

    public DeletionFence beginDeletion(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) throw new IllegalArgumentException("conversationId is required");
        ReentrantLock lock = stripe(conversationId); lock.lock();
        return new DeletionFence(conversationId, lock);
    }

    int subscriberCount(String conversationId) {
        EventSink sink = sinks.get(conversationId); return sink == null ? 0 : sink.subscribers.size();
    }
    int watcherCount(String conversationId) {
        EventSink sink = sinks.get(conversationId); return sink == null ? 0 : sink.watchers.get();
    }

    private EventSink retainWatcher(String conversationId, long generation, BooleanSupplier liveCheck) {
        if (!validKey(conversationId, generation) || liveCheck == null) return null;
        ReentrantLock lock = stripe(conversationId); lock.lock();
        try {
            try { if (!liveCheck.getAsBoolean()) return null; } catch (RuntimeException unavailable) { return null; }
            EventSink sink = sinks.computeIfAbsent(conversationId, ignored -> new EventSink(generation));
            if (sink.invalidated || sink.generation != generation) return null;
            sink.watchers.incrementAndGet(); return sink;
        } finally { lock.unlock(); }
    }

    private void releaseWatcher(String conversationId, EventSink sink) {
        ReentrantLock lock = stripe(conversationId); lock.lock();
        try {
            sink.watchers.updateAndGet(value -> Math.max(0, value - 1));
            removeUnused(conversationId, sink);
        } finally { lock.unlock(); }
    }

    private void releaseSubscriber(String conversationId, EventSink sink, long id) {
        ReentrantLock lock = stripe(conversationId); lock.lock();
        try {
            Sinks.Many<String> removed = sink.subscribers.remove(id);
            if (removed != null) removed.tryEmitComplete();
            removeUnused(conversationId, sink);
        } finally { lock.unlock(); }
    }

    private void removeUnused(String conversationId, EventSink sink) {
        if (sink.subscribers.isEmpty() && sink.watchers.get() == 0) sinks.remove(conversationId, sink);
    }

    private void invalidateLocked(String conversationId, long generation) {
        EventSink eventSink = sinks.remove(conversationId);
        if (eventSink != null) {
            eventSink.invalidated = true;
            eventSink.deleted.tryEmitValue(Boolean.TRUE);
            java.util.List<Sinks.Many<String>> subscribers = java.util.List.copyOf(eventSink.subscribers.values());
            eventSink.subscribers.clear();
            subscribers.forEach(Sinks.Many::tryEmitComplete);
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
        private final Map<Long,Sinks.Many<String>> subscribers = new ConcurrentHashMap<>();
        private final Sinks.One<Boolean> deleted = Sinks.one();
        private final AtomicInteger watchers = new AtomicInteger();
        private volatile boolean invalidated;
        private EventSink(long generation) { this.generation = generation; }
    }

    public final class LiveSubscription implements AutoCloseable {
        private final String conversationId;
        private final EventSink sink;
        private final long id;
        private final Sinks.Many<String> buffer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private LiveSubscription(String conversationId, EventSink sink, long id, Sinks.Many<String> buffer) {
            this.conversationId=conversationId; this.sink=sink; this.id=id; this.buffer=buffer;
        }
        public Flux<String> flux() { return buffer.asFlux().doFinally(ignored -> close()); }
        @Override public void close() { if (closed.compareAndSet(false,true)) releaseSubscriber(conversationId,sink,id); }
    }

    public final class DeletionFence implements AutoCloseable {
        private final String conversationId; private final ReentrantLock lock; private boolean closed;
        private DeletionFence(String conversationId, ReentrantLock lock) { this.conversationId=conversationId;this.lock=lock; }
        public void commitDeleted(long deletedGeneration) {
            if (closed || deletedGeneration < 1) throw new IllegalStateException("Deletion fence is not active");
            invalidateLocked(conversationId, deletedGeneration);
        }
        @Override public void close(){if(!closed){closed=true;lock.unlock();}}
    }
}
