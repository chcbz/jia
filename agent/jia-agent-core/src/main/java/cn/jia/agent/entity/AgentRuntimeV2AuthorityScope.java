package cn.jia.agent.entity;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;

import java.util.Objects;

/**
 * Trusted persisted authority for one runtime-v2 lane.
 *
 * @deprecated R1 compatibility type combining producer and receiver authority. Retained only for
 * dispatch, receipt, and execution-lease persistence until R2a2. Binding persistence must use
 * {@link RuntimeReceiverScope}.
 */
@Deprecated(forRemoval = false)
public record AgentRuntimeV2AuthorityScope(
        TenantId tenantId,
        DataDomainId dataDomainId,
        String executionPoolId,
        String clientId,
        String ownerJiacn,
        String agentId,
        String producerDeploymentId,
        String claimantDeploymentId,
        long claimantWorkerGeneration,
        long admissionEpoch,
        String moduleId,
        long migrationEpoch) {

    public AgentRuntimeV2AuthorityScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("executionPoolId is not canonical");
        }
        requireExact(clientId, 50, "clientId");
        requireExact(ownerJiacn, 100, "ownerJiacn");
        requireExact(agentId, 100, "agentId");
        requireExact(producerDeploymentId, 100, "producerDeploymentId");
        requireExact(claimantDeploymentId, 100, "claimantDeploymentId");
        if (claimantWorkerGeneration < 0 || admissionEpoch < 0 || migrationEpoch < 0) {
            throw new IllegalArgumentException("runtime authority revisions must be non-negative");
        }
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("moduleId is not canonical");
        }
    }

    private static void requireExact(String value, int maximum, String name) {
        if (value == null || value.isEmpty() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is not exact");
        }
    }
}
