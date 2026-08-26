package cn.jia.user.security;

import java.util.Optional;

/**
 * Security boundary for authoritative account state and epoch operations.
 */
public interface AccountSecurityService {
    Optional<AccountSecuritySnapshot> findByUserId(long userId);

    /**
     * Returns a snapshot only when the byte-exact jiacn resolves to exactly one row.
     */
    Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn);

    /**
     * Performs the single-statement account epoch CAS and returns the affected row count.
     */
    int revokeAllSessions(long userId, long expectedEpoch);
}
