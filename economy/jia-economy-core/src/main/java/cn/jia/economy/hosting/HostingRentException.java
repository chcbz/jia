package cn.jia.economy.hosting;

public final class HostingRentException extends RuntimeException {
    private final Reason reason;

    public HostingRentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_COMMAND,
        NOT_CONFIGURED,
        NOT_FOUND_OR_FORBIDDEN,
        QUOTE_EXPIRED,
        IDEMPOTENCY_CONFLICT,
        QUOTE_ALREADY_CONSUMED,
        LEASE_CONFLICT,
        INTENT_CONFLICT,
        PROVISIONING_OUTCOME_UNKNOWN,
        REFUND_NOT_ALLOWED,
        CONCURRENCY_CONFLICT,
        DATA_CORRUPT
    }
}
