package cn.jia.core.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/** A transport message, not an HTTP response nested inside another envelope. */
public record ProtocolMessage<T>(int protocolVersion, String requestId, String messageType, String messageId,
        @JsonInclude(JsonInclude.Include.ALWAYS) T data,
        @JsonInclude(JsonInclude.Include.NON_NULL) String correlationId,
        @JsonInclude(JsonInclude.Include.NON_NULL) String code,
        @JsonInclude(JsonInclude.Include.NON_NULL) String msg) {
    public ProtocolMessage {
        if (protocolVersion != 1 || !id(requestId) || !id(messageId)
                || messageType == null || !messageType.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+"))
            throw new IllegalArgumentException("Invalid protocol message");
        boolean reply = correlationId != null || code != null || msg != null;
        if (reply && (!id(correlationId) || !id(code) || msg == null))
            throw new IllegalArgumentException("Incomplete protocol reply");
    }
    private static boolean id(String value) {
        return value != null && !value.isBlank() && value.codePoints().noneMatch(Character::isISOControl);
    }
    public static <T> ProtocolMessage<T> event(String requestId, String type, String messageId, T data) {
        return new ProtocolMessage<>(1, requestId, type, messageId, data, null, null, null);
    }
    public static <T> ProtocolMessage<T> reply(ProtocolMessage<?> original, String type, String messageId,
            String code, String msg, T data) {
        Objects.requireNonNull(original);
        return new ProtocolMessage<>(1, original.requestId(), type, messageId, data, original.messageId(), code, msg);
    }
}
