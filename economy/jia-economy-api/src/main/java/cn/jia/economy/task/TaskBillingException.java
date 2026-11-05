package cn.jia.economy.task;

/** Candidate-local/transaction-local business failures, translated by the existing API boundary. */
public final class TaskBillingException extends RuntimeException {
    public enum Reason { QUOTE_NOT_FOUND, QUOTE_BINDING_CHANGED, RESERVATION_REQUIRED, CHARGE_CONFLICT }
    private final Reason reason;
    public TaskBillingException(Reason reason) { super(reason.name()); this.reason = reason; }
    public Reason reason() { return reason; }
}
