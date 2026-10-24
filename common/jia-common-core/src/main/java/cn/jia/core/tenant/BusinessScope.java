package cn.jia.core.tenant;

import java.util.Objects;

/**
 * Immutable, authenticated business data scope. Controllers construct this from
 * trusted authentication context; request JSON must not supply it.
 */
public record BusinessScope(TenantId tenantId, DataDomainId dataDomainId,
                            String ownerJiacn, String callerClientId) {
    public BusinessScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        requireExactIdentity(ownerJiacn, "ownerJiacn");
        requireExactIdentity(callerClientId, "callerClientId");
    }

    private static void requireExactIdentity(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be an exact identity");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(name + " has an incomplete Unicode character");
                }
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(name + " has an incomplete Unicode character");
            }
        }
    }
}
