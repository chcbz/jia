package cn.jia.chat.archive.service;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import cn.jia.chat.archive.store.ArchiveQuestionStore;
import cn.jia.chat.archive.store.ArchiveQuestionStore.EventRecord;
import cn.jia.chat.archive.store.ArchiveQuestionStore.OutboxRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@ConditionalOnProperty(prefix = "archive.question", name = "enabled", havingValue = "true")
@Component
public class ArchiveQuestionEventDelivery {
    private static final int RECOVERY_BATCH = 100;
    private final ArchiveQuestionStore store;
    private final ArchiveQuestionEventBroker broker;

    public ArchiveQuestionEventDelivery(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
    }

    void afterCommit(ArchiveOwnerScope owner, EventRecord event) {
        deliverExact(owner, event);
    }

    public int recoverOnce() {
        int delivered = 0;
        for (ArchiveQuestionStore.PublishCandidate candidate : store.findPublishCandidates(RECOVERY_BATCH)) {
            long cursor = candidate.publishedSequence();
            List<EventRecord> events = store.listEvents(candidate.owner(), candidate.questionId(), cursor,
                    candidate.currentSequence(), RECOVERY_BATCH);
            for (EventRecord event : events) {
                if (event.sequence() != cursor + 1) break;
                if (!deliverExact(candidate.owner(), event)) break;
                cursor = event.sequence();
                delivered++;
            }
        }
        return delivered;
    }

    private boolean deliverExact(ArchiveOwnerScope owner, EventRecord event) {
        OutboxRecord outbox = store.findOutbox(owner, event.questionId(), false);
        if (outbox == null || outbox.publishedSequence() >= event.sequence()) return true;
        if (outbox.publishedSequence() != event.sequence() - 1) return false;
        broker.publish(owner, event);
        return store.advancePublishedSequence(owner, event.questionId(),
                event.sequence() - 1, event.sequence()) == 1;
    }
}
