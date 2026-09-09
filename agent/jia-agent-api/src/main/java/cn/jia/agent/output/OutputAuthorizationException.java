package cn.jia.agent.output;

public class OutputAuthorizationException extends RuntimeException {
    private final String code;

    public OutputAuthorizationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
