package cn.jia.economy.exception;

public class EconomyPostingException extends RuntimeException {
    private final Reason reason;

    public EconomyPostingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EconomyPostingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        FEATURE_DISABLED,
        INVALID_COMMAND,
        IMBALANCED_TRANSACTION,
        AMOUNT_RANGE_EXCEEDED,
        INSUFFICIENT_FUNDS,
        ACCOUNT_NOT_FOUND,
        ACCOUNT_NOT_ACTIVE,
        IDEMPOTENCY_CONFLICT,
        JOURNAL_ID_CONFLICT,
        JOURNAL_CORRUPT,
        CONCURRENCY_CONFLICT,
        ESCROW_CONFLICT
    }
}
