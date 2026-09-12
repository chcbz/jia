package cn.jia.agent.hosting;

public final class HostingRentApplicationException extends RuntimeException {
    private final int status;
    private final String code;
    public HostingRentApplicationException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
