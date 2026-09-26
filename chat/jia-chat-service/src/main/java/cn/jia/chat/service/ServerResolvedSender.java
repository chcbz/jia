package cn.jia.chat.service;

/** Immutable sender identity derived only from the authenticated server context. */
public record ServerResolvedSender(
        String type,
        String displayName,
        String jiacn,
        String clientId,
        DisplayNameSource source) implements ServerTrustedSender {

    public static final String USER_TYPE = "user";
}
