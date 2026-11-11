package cn.jia.agent.service;

/** Trusted Agent ownership scope shared by binding and Runtime boundaries. */
public record AgentOwnerScope(String tenantId, String clientId, String ownerJiacn) {
    public AgentOwnerScope {
        if (!validExact(tenantId) || !validExact(clientId) || !validExact(ownerJiacn)
                || "0".equals(ownerJiacn)) {
            throw new IllegalArgumentException("exact Agent owner scope is required");
        }
    }
    private static boolean validExact(String value) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > 50
                || isPadding(value.codePointAt(0)) || isPadding(value.codePointBefore(value.length()))
                || value.codePoints().anyMatch(Character::isISOControl)) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }
    private static boolean isPadding(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
