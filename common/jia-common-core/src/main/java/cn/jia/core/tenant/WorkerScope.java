package cn.jia.core.tenant;

import java.util.Objects;

/**
 * Immutable control-plane authority for one worker lane.
 *
 * <p>It is created from trusted deployment configuration/registration, never from
 * a message, browser parameter, or a row discovered by the worker.</p>
 */
public record WorkerScope(TenantId tenantId, DataDomainId dataDomainId,
                          String executionPoolId, String deploymentId,
                          long workerGeneration, long registrationRevision,
                          long admissionEpoch, String moduleId, long migrationEpoch) {
    public WorkerScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("executionPoolId is not canonical");
        }
        requireExact(deploymentId, "deploymentId", 100);
        if (workerGeneration < 0 || registrationRevision < 0 || admissionEpoch < 0
                || migrationEpoch < 0) {
            throw new IllegalArgumentException("Worker fence revisions must be non-negative");
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
