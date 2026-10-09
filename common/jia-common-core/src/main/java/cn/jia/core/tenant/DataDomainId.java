package cn.jia.core.tenant;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable server-owned business product boundary; it is not an OAuth client id. */
public record DataDomainId(String value) {
    private static final Pattern CANONICAL = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public DataDomainId {
        Objects.requireNonNull(value, "dataDomainId");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("dataDomainId must be a lowercase canonical identifier");
        }
    }

    public static DataDomainId of(String value) {
        return new DataDomainId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
