package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2ExecutionContinuationAnchorEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandV2ExecutionContinuationAnchorMapper {
    @Insert("""
            INSERT INTO agent_command_v2_execution_continuation_anchor(
              runtime_dispatch_id,started_receipt_id,holder_binding_generation,execution_grant_id,
              created_at,contract_version,data_domain_id,execution_pool_id,runtime_credential_client_id,
              owner_jiacn,agent_id,producer_deployment_id,claimant_deployment_id,
              claimant_worker_generation,receiver_registration_revision,admission_epoch,module_id,
              migration_epoch,execution_origin_snapshot_sha256,holder_generation_fact_id,
              holder_generation_snapshot_sha256,tenant_id,client_id,create_time,update_time)
            VALUES(#{e.runtimeDispatchId},#{e.startedReceiptId},#{e.holderBindingGeneration},
              #{e.executionGrantId},#{e.createdAt},#{e.contractVersion},#{dataDomainId},#{executionPoolId},
              #{runtimeCredentialClientId},#{ownerJiacn},#{agentId},#{producerDeploymentId},
              #{claimantDeploymentId},#{claimantWorkerGeneration},#{receiverRegistrationRevision},
              #{admissionEpoch},#{moduleId},#{migrationEpoch},#{e.executionOriginSnapshotSha256},
              #{e.holderGenerationFactId},#{e.holderGenerationSnapshotSha256},#{tenantId},#{commandCallerClientId},
              #{e.createTime},#{e.updateTime})
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
            @Param("e") AgentCommandV2ExecutionContinuationAnchorEntity entity);

    @Select("SELECT * FROM agent_command_v2_execution_continuation_anchor WHERE "
            + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND runtime_dispatch_id=#{runtimeDispatchId} AND started_receipt_id=#{startedReceiptId}"
            + " AND CAST(runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND CAST(started_receipt_id AS BINARY)=CAST(#{startedReceiptId} AS BINARY)"
            + " AND OCTET_LENGTH(started_receipt_id)=OCTET_LENGTH(#{startedReceiptId})"
            + " AND holder_binding_generation=#{holderBindingGeneration} LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionContinuationAnchorEntity lockAnchor(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeDispatchId") String runtimeDispatchId,
            @Param("startedReceiptId") String startedReceiptId,
            @Param("holderBindingGeneration") long holderBindingGeneration);
}
