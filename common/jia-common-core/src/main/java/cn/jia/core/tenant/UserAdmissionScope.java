package cn.jia.core.tenant;

import java.util.Objects;
import java.util.Optional;

/**
 * Signed user identity and authorization versions parsed at the authentication
 * boundary; never construct this scope from request fields or a worker credential.
 */
public record UserAdmissionScope(BusinessScope businessScope, long userId, long authEpoch,
                                 Optional<PlatformVersions> platformVersions) {
    public UserAdmissionScope {
        Objects.requireNonNull(businessScope, "businessScope");
        Objects.requireNonNull(platformVersions, "platformVersions");
        if (userId <= 0 || authEpoch < 0) {
            throw new IllegalArgumentException("A signed user identity is required");
        }
    }

    public UserAdmissionScope(BusinessScope businessScope, long userId, long authEpoch,
            long tenantSessionEpoch, long membershipRevision, long clientGrantRevision) {
        this(businessScope, userId, authEpoch, Optional.of(new PlatformVersions(
                tenantSessionEpoch, membershipRevision, clientGrantRevision)));
    }

    public static UserAdmissionScope basic(BusinessScope businessScope, long userId, long authEpoch) {
        return new UserAdmissionScope(businessScope, userId, authEpoch, Optional.empty());
    }

    public long tenantSessionEpoch() {
        return requirePlatformVersions().tenantSessionEpoch();
    }

    public long membershipRevision() {
        return requirePlatformVersions().membershipRevision();
    }

    public long clientGrantRevision() {
        return requirePlatformVersions().clientGrantRevision();
    }

    private PlatformVersions requirePlatformVersions() {
        return platformVersions.orElseThrow(() ->
                new IllegalStateException("Platform authorization versions are required"));
    }

    public record PlatformVersions(long tenantSessionEpoch, long membershipRevision,
                                   long clientGrantRevision) {
        public PlatformVersions {
            if (tenantSessionEpoch < 0 || membershipRevision < 0 || clientGrantRevision < 0) {
                throw new IllegalArgumentException("Platform authorization versions must be non-negative");
            }
        }
    }
}
