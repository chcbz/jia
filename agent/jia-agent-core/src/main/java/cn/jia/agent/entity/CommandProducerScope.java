package cn.jia.agent.entity;

import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;

import java.util.Objects;

/** Immutable producer-side command authority; never grants runtime receiver authority. */
public record CommandProducerScope(
        TenantId tenantId,
        DataDomainId dataDomainId,
        String executionPoolId,
        String commandCallerClientId,
        String ownerJiacn,
        String targetAgentId,
        String producerDeploymentId,
        long admissionEpoch,
        String moduleId,
        long migrationEpoch) {

    public CommandProducerScope {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataDomainId, "dataDomainId");
        if (executionPoolId == null || !executionPoolId.matches("[a-z0-9-]{1,100}")) {
            throw new IllegalArgumentException("executionPoolId is not canonical");
        }
        RuntimeV2ExactIdentity.require(commandCallerClientId, 50, "commandCallerClientId");
        RuntimeV2ExactIdentity.require(ownerJiacn, 100, "ownerJiacn");
        RuntimeV2ExactIdentity.require(targetAgentId, 100, "targetAgentId");
        RuntimeV2ExactIdentity.require(producerDeploymentId, 100, "producerDeploymentId");
        if (admissionEpoch < 0 || migrationEpoch < 0) {
            throw new IllegalArgumentException("producer revisions must be non-negative");
        }
        if (moduleId == null || !moduleId.matches("[a-z][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("moduleId is not canonical");
        }
    }

}
