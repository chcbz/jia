package cn.jia.agent.entity;

import java.util.Objects;

/** Sanitized offline publisher outcome; raw broker failures and payloads never cross this boundary. */
public record AgentRabbitPublishResult(
        Type type,
        String confirmStatus,
        String returnStatus,
        Integer returnReplyCode,
        String returnReplyText,
        String errorCode) {

    public AgentRabbitPublishResult {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(confirmStatus, "confirmStatus");
        Objects.requireNonNull(returnStatus, "returnStatus");
        if (returnReplyText != null && (returnReplyText.length() > 1000
                || returnReplyText.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("return reply text must be sanitized and bounded");
        }
        boolean errorShape = errorCode != null && errorCode.matches("[A-Z0-9_]{1,2000}");
        boolean exactShape = switch (type) {
            case ACK -> "ACK".equals(confirmStatus)
                    && "NOT_RETURNED".equals(returnStatus)
                    && returnReplyCode == null && returnReplyText == null && errorCode == null;
            case RETURNED -> ("ACK".equals(confirmStatus) || "NACK".equals(confirmStatus)
                    || "TIMEOUT".equals(confirmStatus) || "NONE".equals(confirmStatus))
                    && "RETURNED".equals(returnStatus)
                    && returnReplyCode != null && returnReplyText != null && errorShape;
            case NACK -> "NACK".equals(confirmStatus)
                    && "NOT_RETURNED".equals(returnStatus)
                    && returnReplyCode == null && returnReplyText == null && errorShape;
            case TIMEOUT -> "TIMEOUT".equals(confirmStatus)
                    && "NOT_RETURNED".equals(returnStatus)
                    && returnReplyCode == null && returnReplyText == null && errorShape;
            case EXCEPTION -> "NONE".equals(confirmStatus)
                    && "NOT_RETURNED".equals(returnStatus)
                    && returnReplyCode == null && returnReplyText == null && errorShape;
        };
        if (!exactShape) {
            throw new IllegalArgumentException("publish outcome shape is invalid");
        }
    }

    public static AgentRabbitPublishResult ack() {
        return new AgentRabbitPublishResult(
                Type.ACK, "ACK", "NOT_RETURNED", null, null, null);
    }

    public enum Type {
        ACK,
        RETURNED,
        NACK,
        TIMEOUT,
        EXCEPTION
    }
}
