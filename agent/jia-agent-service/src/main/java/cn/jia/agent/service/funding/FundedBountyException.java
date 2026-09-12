package cn.jia.agent.service.funding;

import org.springframework.http.HttpStatus;

public final class FundedBountyException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final boolean retryable;

    public FundedBountyException(HttpStatus status, String code, String message) {
        this(status, code, message, false);
    }

    public FundedBountyException(HttpStatus status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
