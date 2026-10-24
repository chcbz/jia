package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2ExecutionGrantEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandV2ExecutionGrantMapper {
    @Insert("""
            INSERT INTO agent_command_v2_execution_grant(
              execution_grant_id,grant_kind,runtime_dispatch_id,started_receipt_id,
              started_receipt_sha256,runtime_binding_id,runtime_instance_id,
              dispatch_binding_generation,holder_binding_generation,original_start_lease_fact_id,
              current_lease_fact_id,lease_revision,lease_until,previous_execution_grant_id,issued_at,
              grant_wire_bytes,grant_wire_sha256,grant_wire_contract_version,data_domain_id,
              execution_pool_id,runtime_credential_client_id,owner_jiacn,agent_id,
              producer_deployment_id,claimant_deployment_id,claimant_worker_generation,
              receiver_registration_revision,admission_epoch,module_id,migration_epoch,execution_origin_snapshot_sha256,
              holder_generation_fact_id,holder_generation_snapshot_sha256,
              tenant_id,client_id,create_time,update_time)
            VALUES(#{e.executionGrantId},#{e.grantKind},#{e.runtimeDispatchId},#{e.startedReceiptId},
              #{e.startedReceiptSha256},#{e.runtimeBindingId},#{e.runtimeInstanceId},
              #{e.dispatchBindingGeneration},#{e.holderBindingGeneration},#{e.originalStartLeaseFactId},
              #{e.currentLeaseFactId},#{e.leaseRevision},#{e.leaseUntil},#{e.previousExecutionGrantId},
              #{e.issuedAt},#{e.grantWireBytes},#{e.grantWireSha256},#{e.grantWireContractVersion},
              #{dataDomainId},#{executionPoolId},#{runtimeCredentialClientId},#{ownerJiacn},#{agentId},
              #{producerDeploymentId},#{claimantDeploymentId},#{claimantWorkerGeneration},
              #{receiverRegistrationRevision},#{admissionEpoch},#{moduleId},#{migrationEpoch},
              #{e.executionOriginSnapshotSha256},#{e.holderGenerationFactId},#{e.holderGenerationSnapshotSha256},
              #{tenantId},#{commandCallerClientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch,
            @Param("e") AgentCommandV2ExecutionGrantEntity entity);

    @Select("SELECT * FROM agent_command_v2_execution_grant WHERE " + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND evidence_provenance='R2B1_ENFORCED'"
            + " AND execution_grant_id=#{executionGrantId}"
            + " AND CAST(execution_grant_id AS BINARY)=CAST(#{executionGrantId} AS BINARY)"
            + " AND OCTET_LENGTH(execution_grant_id)=OCTET_LENGTH(#{executionGrantId}) LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionGrantEntity lockById(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("executionGrantId") String executionGrantId);

    @Select("SELECT * FROM agent_command_v2_execution_grant WHERE " + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND evidence_provenance='R2B1_ENFORCED'"
            + " AND runtime_dispatch_id=#{runtimeDispatchId} AND current_lease_fact_id=#{currentLeaseFactId}"
            + " AND CAST(runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND CAST(current_lease_fact_id AS BINARY)=CAST(#{currentLeaseFactId} AS BINARY)"
            + " AND OCTET_LENGTH(current_lease_fact_id)=OCTET_LENGTH(#{currentLeaseFactId})"
            + " ORDER BY issued_at DESC LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionGrantEntity lockCurrentByFact(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch,
            @Param("runtimeDispatchId") String runtimeDispatchId,
            @Param("currentLeaseFactId") String currentLeaseFactId);

    @Select("SELECT * FROM agent_command_v2_execution_grant WHERE "
            + AgentRuntimeV2MapperScope.HISTORICAL_EXECUTION_IDENTITY
            + " AND execution_grant_id=#{executionGrantId}"
            + " AND CAST(execution_grant_id AS BINARY)=CAST(#{executionGrantId} AS BINARY)"
            + " AND OCTET_LENGTH(execution_grant_id)=OCTET_LENGTH(#{executionGrantId}) LIMIT 1")
    AgentCommandV2ExecutionGrantEntity findHistoricalById(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("runtimeBindingId") String runtimeBindingId,
            @Param("executionGrantId") String executionGrantId);
}
