package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2ExecutionRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandV2ExecutionRequestMapper {
    @Insert("""
            INSERT INTO agent_command_v2_execution_request(
              execution_request_id,request_kind,request_fingerprint,runtime_dispatch_id,
              started_receipt_id,runtime_binding_id,runtime_instance_id,
              requested_holder_binding_generation,decision,execution_grant_id,denial_code,decided_at,
              contract_version,data_domain_id,execution_pool_id,runtime_credential_client_id,
              owner_jiacn,agent_id,producer_deployment_id,claimant_deployment_id,
              claimant_worker_generation,receiver_registration_revision,admission_epoch,module_id,
              migration_epoch,execution_origin_snapshot_sha256,requested_holder_generation_fact_id,
              requested_holder_generation_snapshot_sha256,tenant_id,client_id,create_time,update_time)
            VALUES(#{e.executionRequestId},#{e.requestKind},#{e.requestFingerprint},#{e.runtimeDispatchId},
              #{e.startedReceiptId},#{e.runtimeBindingId},#{e.runtimeInstanceId},
              #{e.requestedHolderBindingGeneration},#{e.decision},#{e.executionGrantId},#{e.denialCode},
              #{e.decidedAt},#{e.contractVersion},#{dataDomainId},#{executionPoolId},
              #{runtimeCredentialClientId},#{ownerJiacn},#{agentId},#{producerDeploymentId},
              #{claimantDeploymentId},#{claimantWorkerGeneration},#{receiverRegistrationRevision},
              #{admissionEpoch},#{moduleId},#{migrationEpoch},#{e.executionOriginSnapshotSha256},
              #{e.requestedHolderGenerationFactId},#{e.requestedHolderGenerationSnapshotSha256},#{tenantId},#{commandCallerClientId},
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
            @Param("e") AgentCommandV2ExecutionRequestEntity entity);

    @Select("SELECT * FROM agent_command_v2_execution_request WHERE " + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND evidence_provenance='R2B1_ENFORCED'"
            + " AND execution_request_id=#{executionRequestId}"
            + " AND CAST(execution_request_id AS BINARY)=CAST(#{executionRequestId} AS BINARY)"
            + " AND OCTET_LENGTH(execution_request_id)=OCTET_LENGTH(#{executionRequestId}) LIMIT 1 FOR UPDATE")
    AgentCommandV2ExecutionRequestEntity lockByRequestId(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("executionRequestId") String executionRequestId);

    @Select("SELECT * FROM agent_command_v2_execution_request WHERE "
            + AgentRuntimeV2MapperScope.HISTORICAL_EXECUTION_IDENTITY
            + " AND execution_request_id=#{executionRequestId}"
            + " AND CAST(execution_request_id AS BINARY)=CAST(#{executionRequestId} AS BINARY)"
            + " AND OCTET_LENGTH(execution_request_id)=OCTET_LENGTH(#{executionRequestId}) LIMIT 1")
    AgentCommandV2ExecutionRequestEntity findHistoricalById(
            @Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("runtimeBindingId") String runtimeBindingId,
            @Param("executionRequestId") String executionRequestId);
}
