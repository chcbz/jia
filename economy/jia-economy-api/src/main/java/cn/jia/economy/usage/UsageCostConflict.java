package cn.jia.economy.usage;

/** A local receipt/binding/revision conflict; never a reason to repeat the provider invocation. */
public final class UsageCostConflict extends IllegalStateException {
    public enum Reason { ATTEMPT_BINDING, RECEIPT_CONTENT, REVISION }
    private final Reason reason;
    public UsageCostConflict(Reason reason) { super("Usage cost conflict: " + reason); this.reason = reason; }
    public Reason reason() { return reason; }
}
