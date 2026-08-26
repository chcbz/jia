package cn.jia.user.security;

/**
 * Narrow, security-only projection of a user row.
 */
public record AccountSecuritySnapshot(long userId, String jiacn, AccountState accountState, long authEpoch) {
    public boolean isAuthenticatable() {
        return userId > 0
                && jiacn != null
                && !jiacn.isBlank()
                && accountState == AccountState.ACTIVE
                && authEpoch >= 0;
    }

    public boolean matches(long expectedUserId, String expectedJiacn, long expectedEpoch) {
        return isAuthenticatable()
                && userId == expectedUserId
                && jiacn.equals(expectedJiacn)
                && authEpoch == expectedEpoch;
    }
}
