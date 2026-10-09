package cn.jia.chat.service;

/** Immutable Agent sender identity resolved from an authenticated WebSocket Agent id. */
public record ServerResolvedAgentSender(
        String type,
        String displayName,
        String jiacn,
        String clientId,
        String agentId) implements ServerTrustedSender {

    public static final String AGENT_TYPE = "agent";
}
