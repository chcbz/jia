package cn.jia.agent.entity;

/** Fail-closed ACK rejection. Handlers must not expose its detailed reason across scope boundaries. */
public final class AgentCommandAckRejectedException extends RuntimeException {
    public static final String CODE = "COMMAND_ACK_REJECTED";
    private final String reasonCode;

    public AgentCommandAckRejectedException(String reasonCode) {
        super(CODE);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
