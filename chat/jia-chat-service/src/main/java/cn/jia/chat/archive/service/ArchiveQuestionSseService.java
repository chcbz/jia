package cn.jia.chat.archive.service;

import cn.jia.chat.archive.config.ArchiveQuestionAccessPolicy;
import cn.jia.chat.archive.dto.ArchiveQuestionEventDTO;
import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.QuestionRecord;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionSseService {
    static final int BUFFER_LIMIT = 256;
    static final int REPLAY_BATCH = 100;
    static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    static final long HEARTBEAT_SECONDS = 15;

    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;
    private final ArchiveQuestionAccessPolicy accessPolicy;
    private final ArchiveWriteJson json = new ArchiveWriteJson();
    private final ExecutorService replayExecutor;
    private final ScheduledExecutorService heartbeatExecutor;
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();

    public ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                     ArchiveQuestionAccessPolicy accessPolicy) {
        this(store, broker, accessPolicy,
                Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "archive-question-replay")),
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> daemon(runnable, "archive-question-heartbeat")));
    }

    ArchiveQuestionSseService(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                              ArchiveQuestionAccessPolicy accessPolicy, ExecutorService replayExecutor,
                              ScheduledExecutorService heartbeatExecutor) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        this.replayExecutor = Objects.requireNonNull(replayExecutor, "replayExecutor");
        this.heartbeatExecutor = Objects.requireNonNull(heartbeatExecutor, "heartbeatExecutor");
    }

    public SseEmitter open(ArchiveOwnerScope owner, String questionId, long cursor) {
        ManagedSseEmitter emitter = new ManagedSseEmitter(SSE_TIMEOUT_MILLIS);
        subscribe(owner, questionId, cursor, new EmitterSink(emitter));
        return emitter;
    }

    StreamHandle subscribe(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink) {
        if (!ArchiveWire.lowercaseUuid(questionId)) notFound();
        if (cursor < 0) throw new ArchivePersonalDataException(400, "INVALID_EVENT_CURSOR",
                "Last-Event-ID must be a canonical nonnegative decimal");
        if (!accessPolicy.allows(owner.tenantId(), owner.clientId())) notFound();
        QuestionRecord visible = store.findQuestion(owner, questionId, false);
        if (visible == null) notFound();
        Session session = new Session(owner, questionId, cursor, sink);
        session.start();
        return session;
    }

    int activeSubscriptions() { return broker.activeSubscriptions(); }

    interface Sink {
        void onClose(Runnable cleanup);
        boolean persisted(ArchiveQuestionEventDTO event);
        boolean resync(String questionId);
        boolean heartbeat();
        void complete();
        boolean closed();
    }

    interface StreamHandle extends AutoCloseable {
        boolean closed();
        @Override void close();
    }

    private final class Session implements StreamHandle {
        private final ArchiveOwnerScope owner;
        private final String questionId;
        private final long cursor;
        private final Sink sink;
        private final Object lock = new Object();
        private final TreeMap<Long, EventRecord> buffer = new TreeMap<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private ArchiveQuestionEventBroker.Subscription live;
        private Future<?> replay;
        private ScheduledFuture<?> heartbeat;
        private long delivered;
        private boolean direct;

        private Session(ArchiveOwnerScope owner, String questionId, long cursor, Sink sink) {
            this.owner = owner;
            this.questionId = questionId;
            this.cursor = cursor;
            this.sink = Objects.requireNonNull(sink, "sink");
            this.delivered = cursor;
        }

        private void start() {
            sessions.add(this);
            try {
                sink.onClose(this::close);
                synchronized (lock) {
                    if (closed.get() || sink.closed()) { close(); return; }
                    live = broker.subscribe(owner, questionId, this::observe);
                    heartbeat = heartbeatExecutor.scheduleWithFixedDelay(
                            this::heartbeat, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
                    replay = replayExecutor.submit(this::initializeReplay);
                }
            } catch (Throwable failure) {
                close();
                throw failure;
            }
        }

        private void observe(EventRecord event) {
            synchronized (lock) {
                if (closed.get() || sink.closed() || event == null || !questionId.equals(event.questionId())) return;
                if (event.sequence() <= delivered) return;
                if (!direct) {
                    buffer.putIfAbsent(event.sequence(), event);
                    if (buffer.size() > BUFFER_LIMIT) resyncLocked();
                    return;
                }
                if (event.sequence() != delivered + 1) { resyncLocked(); return; }
                if (sendPersistedLocked(event)) delivered = event.sequence();
                else close();
            }
        }

        private void initializeReplay() {
            try {
                if (closed.get() || sink.closed()) return;
                QuestionRecord snapshot = store.findQuestion(owner, questionId, false);
                if (snapshot == null) { close(); return; }
                long watermark = snapshot.currentSequence();
                if (cursor > watermark || truncated(cursor, watermark)) { resync(); return; }
                long after = cursor;
                while (after < watermark && !closed.get()) {
                    List<EventRecord> events = store.listEvents(owner, questionId, after, watermark, REPLAY_BATCH);
                    if (events.isEmpty()) { resync(); return; }
                    for (EventRecord event : events) {
                        synchronized (lock) {
                            if (closed.get() || sink.closed()) return;
                            if (event.sequence() != delivered + 1 || event.sequence() > watermark) {
                                resyncLocked(); return;
                            }
                            if (!sendPersistedLocked(event)) { close(); return; }
                            delivered = event.sequence();
                            buffer.remove(event.sequence());
                            after = delivered;
                        }
                    }
                }
                synchronized (lock) {
                    if (closed.get() || sink.closed()) return;
                    if (delivered != watermark) { resyncLocked(); return; }
                    buffer.headMap(delivered, true).clear();
                    while (!buffer.isEmpty()) {
                        Map.Entry<Long, EventRecord> next = buffer.firstEntry();
                        if (next.getKey() != delivered + 1) { resyncLocked(); return; }
                        buffer.pollFirstEntry();
                        if (!sendPersistedLocked(next.getValue())) { close(); return; }
                        delivered = next.getKey();
                    }
                    direct = true;
                }
            } catch (Throwable failure) {
                if (!Thread.currentThread().isInterrupted()) resync();
            }
        }

        private boolean truncated(long after, long watermark) {
            if (after == watermark) return false;
            Long earliest = store.earliestEventSequence(owner, questionId);
            return earliest == null || after < earliest - 1;
        }

        @SuppressWarnings("unchecked")
        private boolean sendPersistedLocked(EventRecord event) {
            Map<String, Object> payload = json.readValue(event.payloadJson(), Map.class);
            ArchiveQuestionEventCatalog.validate(event.eventType(), payload);
            ArchiveQuestionEventDTO dto = new ArchiveQuestionEventDTO(1, questionId,
                    Long.toString(event.sequence()), event.eventType(), event.occurredAt().toString(), payload);
            return sink.persisted(dto);
        }

        private void heartbeat() {
            synchronized (lock) {
                if (closed.get() || sink.closed()) return;
                if (!accessPolicy.allows(owner.tenantId(), owner.clientId())) {
                    sink.complete();
                    return;
                }
                if (!sink.heartbeat()) close();
            }
        }

        private void resync() { synchronized (lock) { resyncLocked(); } }
        private void resyncLocked() {
            if (closed.get() || sink.closed()) return;
            sink.resync(questionId);
            sink.complete();
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (lock) {
                direct = false;
                buffer.clear();
                ArchiveQuestionEventBroker.Subscription currentLive = live;
                live = null;
                if (currentLive != null) currentLive.close();
                Future<?> currentReplay = replay;
                replay = null;
                if (currentReplay != null) currentReplay.cancel(true);
                ScheduledFuture<?> currentHeartbeat = heartbeat;
                heartbeat = null;
                if (currentHeartbeat != null) currentHeartbeat.cancel(true);
            }
            sessions.remove(this);
            sink.complete();
        }
        @Override public boolean closed() { return closed.get(); }
    }

    private static final class EmitterSink implements Sink {
        private final ManagedSseEmitter emitter;
        private EmitterSink(ManagedSseEmitter emitter) { this.emitter = emitter; }
        @Override public void onClose(Runnable cleanup) {
            emitter.setCleanup(cleanup);
            emitter.onCompletion(emitter::closeFromContainer);
            emitter.onTimeout(emitter::complete);
            emitter.onError(ignored -> emitter.closeFromContainer());
        }
        @Override public boolean persisted(ArchiveQuestionEventDTO dto) {
            return emitter.sendIfOpen(SseEmitter.event().id(dto.sequence()).name(dto.type())
                    .data(dto, MediaType.APPLICATION_JSON));
        }
        @Override public boolean resync(String questionId) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemaVersion", 1);
            data.put("questionId", questionId);
            data.put("type", "resync_required");
            return emitter.sendIfOpen(SseEmitter.event().name("resync_required")
                    .data(data, MediaType.APPLICATION_JSON));
        }
        @Override public boolean heartbeat() {
            return emitter.sendIfOpen(SseEmitter.event().comment("heartbeat"));
        }
        @Override public void complete() { emitter.complete(); }
        @Override public boolean closed() { return emitter.closed(); }
    }

    static class ManagedSseEmitter extends SseEmitter {
        private final AtomicBoolean finished = new AtomicBoolean();
        private Runnable cleanup = () -> { };
        ManagedSseEmitter(long timeout) { super(timeout); }
        private synchronized void setCleanup(Runnable cleanup) { this.cleanup = cleanup; }
        private boolean sendIfOpen(SseEventBuilder event) {
            Throwable failure = null;
            synchronized (this) {
                if (finished.get()) return false;
                try {
                    send(event);
                    return true;
                } catch (IOException | RuntimeException caught) {
                    if (finished.compareAndSet(false, true)) failure = caught;
                }
            }
            if (failure != null) {
                runCleanup();
                super.completeWithError(failure);
            }
            return false;
        }
        @Override public void complete() {
            if (!markFinished()) return;
            runCleanup();
            super.complete();
        }
        @Override public void completeWithError(Throwable error) {
            if (!markFinished()) return;
            runCleanup();
            super.completeWithError(error);
        }
        private void closeFromContainer() {
            if (!markFinished()) return;
            runCleanup();
        }
        private synchronized boolean markFinished() {
            return finished.compareAndSet(false, true);
        }
        private void runCleanup() {
            Runnable action;
            synchronized (this) { action = cleanup; }
            action.run();
        }
        boolean closed() { return finished.get(); }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
    private void notFound() {
        throw new ArchivePersonalDataException(404, "ARCHIVE_RESOURCE_NOT_FOUND",
                "Archive resource is not available");
    }
    @PreDestroy public void stop() {
        for (Session session : List.copyOf(sessions)) session.close();
        replayExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
    }
}
