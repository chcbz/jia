package cn.jia.chat.service;

/** Marker contract for sender values constructed only from authenticated server state. */
public sealed interface ServerTrustedSender permits ServerResolvedSender, ServerResolvedAgentSender {
    String type();
    String displayName();
    String jiacn();
    String clientId();
}
