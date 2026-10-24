package cn.jia.core.tenant;

import java.util.Objects;

/**
 * Immutable protocol provenance for one durable producer write.
 *
 * <p>The scope is returned by a transaction-bound admission check or reconstructed
 * from a complete persisted protocol-v2 row. Request JSON and browser parameters
 * must never construct it.</p>
 */
public record ProducerScope(TenantId tenantId, DataDomainId dataDomainId,
                            String executionPoolId, String deploymentId,
                            long admissionEpoch, String moduleId, long migrationEpoch) {
    public ProducerScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("executionPoolId is not canonical");
        }
        requireExact(deploymentId, "deploymentId", 100);
        if (admissionEpoch < 0 || migrationEpoch < 0) {
            throw new IllegalArgumentException("Producer fence revisions must be non-negative");
        }
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("moduleId is not canonical");
        }
    }

    private static void requireExact(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be an exact identifier");
        }
    }
}
