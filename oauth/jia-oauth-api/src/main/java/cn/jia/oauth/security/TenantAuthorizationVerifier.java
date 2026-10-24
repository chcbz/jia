package cn.jia.oauth.security;

/**
 * Verifies a frozen, single-area token binding against current authoritative
 * tenant, membership, client and data-domain grants. No positive cache is allowed.
 */
public interface TenantAuthorizationVerifier {
    boolean isCurrent(TenantAuthorizationAttributes selected, long userId, String registeredClientId);
}
