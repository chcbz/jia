package cn.jia.core.entity;

/** The only request envelope for CYF-owned HTTP JSON operations. */
public record JsonRequest<T>(int protocolVersion, String requestId, T data) {
    public JsonRequest {
        if (protocolVersion != 1) throw new IllegalArgumentException("PROTOCOL_UNSUPPORTED_VERSION");
        if (requestId == null || requestId.isBlank() || requestId.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("PROTOCOL_INVALID_ENVELOPE");
        }
    }
}
