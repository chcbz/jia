package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2ExecutionLeaseFactEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandV2ExecutionLeaseFactMapper {
    @Insert("""
            INSERT INTO agent_command_v2_execution_lease_fact(
              lease_fact_id,runtime_dispatch_id,inbox_id,delivery_id,fact_sequence,lease_revision,
              fact_type,previous_lease_fact_id,effective_at,lease_until,causing_receipt_id,
              runtime_binding_id,dispatch_binding_generation,holder_binding_generation,
              runtime_instance_id,runtime_credential_client_id,data_domain_id,execution_pool_id,
              owner_jiacn,agent_id,producer_deployment_id,claimant_deployment_id,
              claimant_worker_generation,receiver_registration_revision,admission_epoch,module_id,migration_epoch,
              tenant_control_operation_id,tenant_control_revision,tenant_control_cutoff_at,
              module_control_operation_id,module_mode_revision,module_control_cutoff_at,
              deployment_control_operation_id,deployment_registration_revision,
              deployment_control_cutoff_at,reason,created_at,execution_origin_snapshot_sha256,
              holder_generation_fact_id,holder_generation_snapshot_sha256,tenant_id,client_id,create_time,update_time)
            VALUES(#{e.leaseFactId},#{e.runtimeDispatchId},#{e.inboxId},#{e.deliveryId},#{e.factSequence},
              #{e.leaseRevision},#{e.factType},#{e.previousLeaseFactId},#{e.effectiveAt},#{e.leaseUntil},
              #{e.causingReceiptId},#{e.runtimeBindingId},#{e.dispatchBindingGeneration},
              #{e.holderBindingGeneration},#{e.runtimeInstanceId},#{runtimeCredentialClientId},
              #{dataDomainId},#{executionPoolId},#{ownerJiacn},#{agentId},#{producerDeploymentId},
              #{claimantDeploymentId},#{claimantWorkerGeneration},#{receiverRegistrationRevision},
              #{admissionEpoch},#{moduleId},#{migrationEpoch},#{e.tenantControlOperationId},
              #{e.tenantControlRevision},#{e.tenantControlCutoffAt},#{e.moduleControlOperationId},
              #{e.moduleModeRevision},#{e.moduleControlCutoffAt},#{e.deploymentControlOperationId},
              #{e.deploymentRegistrationRevision},#{e.deploymentControlCutoffAt},#{e.reason},
              #{e.createdAt},#{e.executionOriginSnapshotSha256},#{e.holderGenerationFactId},
              #{e.holderGenerationSnapshotSha256},#{tenantId},#{commandCallerClientId},#{e.createTime},#{e.updateTime})
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
            @Param("e") AgentCommandV2ExecutionLeaseFactEntity entity);

    @Select("SELECT * FROM agent_command_v2_execution_lease_fact WHERE "
            + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND runtime_dispatch_id=#{runtimeDispatchId} AND runtime_binding_id=#{runtimeBindingId}"
            + " AND CAST(runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})"
            + " ORDER BY fact_sequence DESC LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionLeaseFactEntity lockLatest(@Param("tenantId") String tenantId,
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
            @Param("runtimeBindingId") String runtimeBindingId,
            @Param("runtimeDispatchId") String runtimeDispatchId);

    @Select("SELECT * FROM agent_command_v2_execution_lease_fact WHERE "
            + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND lease_fact_id=#{leaseFactId}"
            + " AND CAST(lease_fact_id AS BINARY)=CAST(#{leaseFactId} AS BINARY)"
            + " AND OCTET_LENGTH(lease_fact_id)=OCTET_LENGTH(#{leaseFactId}) LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionLeaseFactEntity lockById(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("leaseFactId") String leaseFactId);
}
