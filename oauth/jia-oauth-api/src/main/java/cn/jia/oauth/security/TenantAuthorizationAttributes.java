package cn.jia.oauth.security;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable tenant selection persisted with an OAuth authorization before code
 * exchange. It is intentionally not populated from the token request.
 */
public record TenantAuthorizationAttributes(TenantId tenantId, DataDomainId dataDomainId, String tenantName, TenantKind tenantKind,
                                            String tenantSessionEpoch, String membershipRevision,
                                            String clientTenantGrantRevision) {
    private static final Pattern REVISION = Pattern.compile("0|[1-9][0-9]{0,18}");

    public enum TenantKind { NORMAL, TEST }

    public TenantAuthorizationAttributes {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        requireNonBlank(tenantName, "tenantName");
        Objects.requireNonNull(tenantKind, "tenantKind");
        requireRevision(tenantSessionEpoch, "tenantSessionEpoch");
        requireRevision(membershipRevision, "membershipRevision");
        requireRevision(clientTenantGrantRevision, "clientTenantGrantRevision");
    }

    public static String attributeName() {
        return TenantAuthorizationAttributes.class.getName();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void requireRevision(String value, String name) {
        if (value == null || !REVISION.matcher(value).matches() || !fitsSignedLong(value)) {
            throw new IllegalArgumentException(name + " must be a canonical non-negative decimal string");
        }
    }

    private static boolean fitsSignedLong(String value) {
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }
}
