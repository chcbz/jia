package cn.jia.economy.usage;

import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import java.math.BigDecimal;
import java.util.Objects;

/** Internal cost boundary. Source adapters own trusted invocation/task identity and usage semantics.
 * No task lifecycle, wallet, provider call or current-price lookup is performed here.
 */
public interface UsageCostService {
    /** Null taskId is a real non-task invocation, not a synthetic task or a wallet charge. */
    record Binding(EconomyScope scope, String ownerJiacn, String taskId, String source, String sourceAttemptId) {
        public Binding {
            Objects.requireNonNull(scope, "scope");
            for (String value : new String[]{ownerJiacn, source, sourceAttemptId})
                if (value == null || value.isBlank()) throw new IllegalArgumentException("Cost attempt binding is required");
            if (taskId != null && taskId.isBlank()) throw new IllegalArgumentException("Task identity cannot be blank");
        }
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
    Summary summary(EconomyScope scope, String ownerJiacn, String taskId);
}
