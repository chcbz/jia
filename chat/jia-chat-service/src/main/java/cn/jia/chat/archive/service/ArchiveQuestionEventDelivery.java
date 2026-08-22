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
    private final ArchiveTransactions transactions;

    public ArchiveQuestionEventDelivery(ArchiveQuestionStore store, ArchiveQuestionEventBroker broker,
                                        ArchiveTransactions transactions) {
        this.store = Objects.requireNonNull(store, "store");
        this.broker = Objects.requireNonNull(broker, "broker");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    /** Best-effort commit wake-up. Durable recovery owns correctness; this method never throws. */
    void afterCommit(ArchiveOwnerScope owner, EventRecord event) {
        deliverBestEffort(owner, event);
    }

    public int recoverOnce() {
        int delivered = 0;
        List<ArchiveQuestionStore.PublishCandidate> candidates;
        try {
            candidates = store.findPublishCandidates(RECOVERY_BATCH);
        } catch (Throwable unavailable) {
            return 0;
        }
        for (ArchiveQuestionStore.PublishCandidate candidate : candidates) {
            try {
                long cursor = candidate.publishedSequence();
                List<EventRecord> events = store.listEvents(candidate.owner(), candidate.questionId(), cursor,
                        candidate.currentSequence(), RECOVERY_BATCH);
                for (EventRecord event : events) {
                    if (event.sequence() != cursor + 1 || !deliverBestEffort(candidate.owner(), event)) break;
                    cursor = event.sequence();
                    delivered++;
                }
            } catch (Throwable ignored) {
                // One corrupt/unavailable candidate must not starve recovery for the rest of the batch.
            }
        }
        return delivered;
    }

    private boolean deliverBestEffort(ArchiveOwnerScope owner, EventRecord event) {
        try {
            return Boolean.TRUE.equals(transactions.requiresNew(() -> deliverExact(owner, event)));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean deliverExact(ArchiveOwnerScope owner, EventRecord event) {
        OutboxRecord outbox = store.findOutbox(owner, event.questionId(), true);
        if (outbox == null || outbox.publishedSequence() >= event.sequence()) return true;
        if (outbox.publishedSequence() != event.sequence() - 1) return false;
        broker.publish(owner, event);
        return store.advancePublishedSequence(owner, event.questionId(),
                event.sequence() - 1, event.sequence()) == 1;
    }
}
