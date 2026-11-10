package cn.jia.economy.usage;

import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.Objects;

/** Internal cost boundary. Source adapters own trusted invocation/task identity and usage semantics.
 * No task lifecycle, wallet, provider call or current-price lookup is performed here.
 */
public interface UsageCostService {
    /** Null taskId is a real non-task invocation. Null ownerJiacn explicitly means a platform
     * cost, never an anonymous user: trusted source adapters choose this binding, not HTTP input.
     * Platform costs cannot belong to a user task. No synthetic platform account is required.
     */
    record Binding(EconomyScope scope, String ownerJiacn, String taskId, String source, String sourceAttemptId) {
        public Binding {
            Objects.requireNonNull(scope, "scope");
            for (String value : new String[]{source, sourceAttemptId})
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Cost attempt binding is required");
            if (ownerJiacn != null && ownerJiacn.isBlank()) throw new IllegalArgumentException("Owner cannot be blank");
            if (ownerJiacn == null && taskId != null) throw new IllegalArgumentException("Platform cost cannot belong to a user task");
            if (taskId != null && taskId.isBlank()) throw new IllegalArgumentException("Task identity cannot be blank");
        }
    }
    /** Explicit entry for platform maintenance; sourceAttemptId retains the source's Agent/batch identity. */
    static Binding platformBinding(EconomyScope scope, String source, String sourceAttemptId) {
        return new Binding(scope, null, null, source, sourceAttemptId);
    }
    /** expectedRevision=0 creates an attempt; later evidence/repricing is an explicit revision.
     * receiptId and rawUsageJson are stable source evidence, not a new id/timestamp on redelivery.
     * Null normalizedUsage means absent or unverified usage, never free. Price is a retained snapshot.
     */
    record Observation(Binding binding, String receiptId, long expectedRevision, String rawUsageJson,
            TokenUsageCost.Usage normalizedUsage, ModelTokenPrice price, long observedAt) {
        public Observation {
            Objects.requireNonNull(binding, "binding"); Objects.requireNonNull(rawUsageJson, "rawUsageJson");
            if (receiptId == null || receiptId.isBlank()) throw new IllegalArgumentException("Source receipt identity is required");
            if (expectedRevision < 0) throw new IllegalArgumentException("Revision cannot be negative");
        }
    }
    record Receipt(String attemptId, long revision, TokenUsageCost.Status status, BigDecimal costYuan, boolean replay) { }
    record Summary(long attempts, long pendingUsage, long pendingPrice, BigDecimal costYuan) { }

    /** Independent short transaction, intended for durable source receipts/recovery consumers. */
    Receipt record(Observation observation);
    /** Retained source observation for stable redelivery and pre-call price reuse. */
    java.util.Optional<Observation> observation(Binding binding, String receiptId);
    Summary summary(EconomyScope scope, String ownerJiacn, String taskId);
}
