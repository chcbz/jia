package cn.jia.user.security;

/**
 * Persisted account lifecycle states. UNKNOWN is never written and exists only to fail closed on unexpected data.
 */
public enum AccountState {
    ACTIVE,
    SUSPENDED,
    DISABLED,
    UNKNOWN;

    public static AccountState fromDatabaseValue(String value) {
        if ("ACTIVE".equals(value)) {
            return ACTIVE;
        }
        if ("SUSPENDED".equals(value)) {
            return SUSPENDED;
        }
        if ("DISABLED".equals(value)) {
            return DISABLED;
        }
        return UNKNOWN;
    }
}
