package cn.jia.core.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Canonical identifier of one business area.
 *
 * <p>This is deliberately a string. Tenant identifiers are database and protocol
 * identifiers rather than JavaScript numbers: {@code 0} and {@code 00} must never
 * become indistinguishable while crossing an API boundary.</p>
 */
public record TenantId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("0|[1-9][0-9]{0,49}");

    public TenantId {
        Objects.requireNonNull(value, "tenantId");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantId must be a canonical decimal string");
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
