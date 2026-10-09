package cn.jia.agent.entity;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;

import java.util.Objects;

/** Trusted receiver registration snapshot used by stable runtime binding persistence. */
public record RuntimeReceiverScope(
        TenantId tenantId,
        DataDomainId dataDomainId,
        String executionPoolId,
        String runtimeCredentialClientId,
        String ownerJiacn,
        String agentId,
        String claimantDeploymentId,
        long claimantWorkerGeneration,
        long receiverRegistrationRevision,
        long admissionEpoch,
        String moduleId,
        long migrationEpoch) {

    public RuntimeReceiverScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("executionPoolId is not canonical");
        }
        RuntimeV2ExactIdentity.require(runtimeCredentialClientId, 50, "runtimeCredentialClientId");
        RuntimeV2ExactIdentity.require(ownerJiacn, 100, "ownerJiacn");
        RuntimeV2ExactIdentity.require(agentId, 100, "agentId");
        RuntimeV2ExactIdentity.require(claimantDeploymentId, 100, "claimantDeploymentId");
        if (claimantWorkerGeneration < 0 || receiverRegistrationRevision < 0
                || admissionEpoch < 0 || migrationEpoch < 0) {
            throw new IllegalArgumentException("receiver revisions must be non-negative");
        }
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("moduleId is not canonical");
        }
    }

    /** Physical binding-column alias; this remains credential identity, never caller authority. */
    public String clientId() {
        return runtimeCredentialClientId;
    }

}
