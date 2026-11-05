package cn.jia.economy.task;

import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.pricing.TaskQuoteCalculator;
import cn.jia.economy.service.EconomyScope;
import java.util.Map;
import java.util.Objects;

/** Internal economy boundary. Callers authorize the task and bind current requirement/material facts.
 * All calls require the caller's transaction and task root lock (including quote creation);
 * the business layer owns this lock so requirement/assignment changes and money commit together.
 * Mutations join that transaction; no Provider I/O, task-table reads or actual-cost input.
 */
public interface TaskBillingService {
    record Task(EconomyScope scope, String ownerJiacn, String taskId) {
        public Task { Objects.requireNonNull(scope); Objects.requireNonNull(ownerJiacn); Objects.requireNonNull(taskId); }
    }
    /** A new recommendation gets a new identity. Prices changing alone never invalidate a quote. */
    record Binding(long requirementRevision, String requirementSha256, String inputSnapshotDigest,
            String agentId, String recommendationId) {
        public Binding {
            if (requirementRevision < 1) throw new IllegalArgumentException("requirement revision must be positive");
            Objects.requireNonNull(requirementSha256); Objects.requireNonNull(inputSnapshotDigest);
            Objects.requireNonNull(agentId); Objects.requireNonNull(recommendationId);
        }
    }
    record Quote(String quoteId, long amountWen, String snapshotJson) { }
    enum State { HELD, CAPTURED, RELEASED, LEGACY_FREE }
    record Charge(String quoteId, long amountWen, State state, String reserveTransactionId,
            String terminalTransactionId) { }

    /** Immutable per recommendation+candidate binding. Replay returns stored prices without recalculating. */
    Quote quote(Task task, Binding binding, String payerUserId, TaskQuoteCalculator.Estimate estimate,
            ModelTokenPrice modelPrice, Map<TaskQuoteCalculator.ToolKey, TaskQuoteCalculator.ToolPrice> toolPrices);
    /** Reserve exactly the selected quote, or replay the same reservation; never top up/reprice. */
    Charge reserve(Task task, String quoteId, Binding currentBinding);
    /** Missing reservation is not free. Legacy-free requires an explicit cutover record. */
    Charge capture(Task task);
    /** Cancelling an unassigned task can have no reservation; an assigned task must have one. */
    Charge release(Task task, boolean reservationRequired);
}
