package cn.jia.agent.output;

public final class OutputDeliveryException extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean retryable;

    public OutputDeliveryException(String code, String message, int status) {
        this(code, message, status, status >= 500);
    }

    public OutputDeliveryException(String code, String message, int status, boolean retryable) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public int status() { return status; }
    public boolean retryable() { return retryable; }
}
