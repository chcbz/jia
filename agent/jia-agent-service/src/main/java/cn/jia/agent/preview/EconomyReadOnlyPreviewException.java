package cn.jia.agent.preview;

import org.springframework.http.HttpStatus;

/** Stable HTTP/code failure used only by the independent read-only preview edge. */
public final class EconomyReadOnlyPreviewException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public EconomyReadOnlyPreviewException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
