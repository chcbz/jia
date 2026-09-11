package cn.jia.agent.output;

public final class OutputUploadException extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean terminal;

    public OutputUploadException(String code, String message, int status) {
        this(code, message, status, status != 503);
    }

    public OutputUploadException(String code, String message, int status, boolean terminal) {
        super(message);
        this.code = code;
        this.status = status;
        this.terminal = terminal;
    }

    public String code() { return code; }
    public int status() { return status; }
    public boolean terminal() { return terminal; }
}
