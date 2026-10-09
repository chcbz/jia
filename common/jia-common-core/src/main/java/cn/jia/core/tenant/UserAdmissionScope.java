package cn.jia.core.tenant;

import java.util.Objects;

/**
 * Signed user identity and authorization versions parsed at the authentication
 * boundary; never construct this scope from request fields or a worker credential.
 */
public record UserAdmissionScope(BusinessScope businessScope, long userId, long authEpoch,
                                 long tenantSessionEpoch, long membershipRevision,
                                 long clientGrantRevision) {
    public UserAdmissionScope {
        Objects.requireNonNull(businessScope, "businessScope");
        if (userId <= 0 || authEpoch < 0 || tenantSessionEpoch < 0 || membershipRevision < 0
                || clientGrantRevision < 0) {
            throw new IllegalArgumentException("A signed user and authorization versions are required");
        }
    }
}
