package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionEventBroker {
    private final ConcurrentHashMap<Key, Set<Subscription>> channels = new ConcurrentHashMap<>();

    Subscription subscribe(ArchiveOwnerScope owner, String questionId, Consumer<EventRecord> listener) {
        Key key = new Key(owner, questionId);
        Subscription subscription = new Subscription(key, listener);
        channels.computeIfAbsent(key, ignored -> ConcurrentHashMap.newKeySet()).add(subscription);
        return subscription;
    }

    void publish(ArchiveOwnerScope owner, EventRecord event) {
        Set<Subscription> listeners = channels.get(new Key(owner, event.questionId()));
        if (listeners == null) return;
        for (Subscription listener : listeners) listener.accept(event);
    }

    int activeSubscriptions() {
        return channels.values().stream().mapToInt(Set::size).sum();
    }

    final class Subscription implements AutoCloseable {
        private final Key key;
        private final Consumer<EventRecord> listener;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(Key key, Consumer<EventRecord> listener) {
            this.key = key;
            this.listener = listener;
        }

        private void accept(EventRecord event) {
            if (closed.get()) return;
            try {
                listener.accept(event);
            } catch (Throwable failure) {
                close();
            }
        }

        boolean closed() { return closed.get(); }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            channels.computeIfPresent(key, (ignored, subscriptions) -> {
                subscriptions.remove(this);
                return subscriptions.isEmpty() ? null : subscriptions;
            });
        }
    }

    private record Key(ArchiveOwnerScope owner, String questionId) { }
}
