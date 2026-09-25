package cn.jia.chat.deliberation;

/** Internal authorization-safe route. Only authoritative command paths may produce EXECUTE. */
public enum InteractionRoute {
    CHAT,
    CHAT_STATUS,
    INSPECT,
    EXECUTE
}
