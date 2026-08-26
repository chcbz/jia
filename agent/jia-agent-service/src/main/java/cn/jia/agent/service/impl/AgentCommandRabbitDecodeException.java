package cn.jia.agent.service.impl;

/** Fail-closed D05 wire/provenance rejection without payload or broker-secret disclosure. */
final class AgentCommandRabbitDecodeException extends IllegalArgumentException {
    private final String reasonCode;

    AgentCommandRabbitDecodeException(String reasonCode) {
        super("AGENT_COMMAND_RABBIT_MESSAGE_INVALID: " + reasonCode);
        this.reasonCode = reasonCode;
    }

    String reasonCode() {
        return reasonCode;
    }
}
