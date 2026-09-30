package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2BindingGenerationFactEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRuntimeV2BindingGenerationFactMapper {
    @Insert("""
            INSERT INTO agent_runtime_v2_binding_generation_fact(
              binding_generation_fact_id,fact_sequence,previous_fact_id,runtime_binding_id,
              binding_generation,event_type,runtime_instance_id,registration_subject_sha256,
              registration_authority_id,registration_authority_revision,runtime_credential_client_id,
              data_domain_id,execution_pool_id,
              owner_jiacn,agent_id,claimant_deployment_id,claimant_worker_generation,
              receiver_registration_revision,admission_epoch,module_id,migration_epoch,process_public_key_sha256,
              verifier_version,registration_receipt_id,registration_receipt_sha256,
              connection_admission_kind,drain_control_operation_id,generation_snapshot_sha256,
              session_fence,socket_owner_instance_id,socket_owner_epoch,socket_owner_lease_until,effective_at,reason,
              tenant_id,client_id,create_time,update_time)
            VALUES(#{e.bindingGenerationFactId},#{e.factSequence},#{e.previousFactId},#{e.runtimeBindingId},
              #{e.bindingGeneration},#{e.eventType},#{e.runtimeInstanceId},#{e.registrationSubjectSha256},
              #{e.registrationAuthorityId},#{e.registrationAuthorityRevision},#{e.runtimeCredentialClientId},
              #{dataDomainId},#{executionPoolId},
              #{ownerJiacn},#{agentId},#{claimantDeploymentId},#{claimantWorkerGeneration},
              #{receiverRegistrationRevision},#{admissionEpoch},#{moduleId},#{migrationEpoch},
              #{e.processPublicKeySha256},#{e.verifierVersion},#{e.registrationReceiptId},
              #{e.registrationReceiptSha256},#{e.connectionAdmissionKind},#{e.drainControlOperationId},
              #{e.generationSnapshotSha256},
              #{e.sessionFence},#{e.socketOwnerInstanceId},#{e.socketOwnerEpoch},#{e.socketOwnerLeaseUntil},#{e.effectiveAt},#{e.reason},
              #{tenantId},#{clientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("tenantId") String tenantId, @Param("dataDomainId") String dataDomainId,
            @Param("executionPoolId") String executionPoolId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch,
            @Param("e") AgentRuntimeV2BindingGenerationFactEntity entity);

    @Select("SELECT * FROM agent_runtime_v2_binding_generation_fact WHERE "
            + AgentRuntimeV2MapperScope.RECEIVER_EXACT
            + " AND runtime_binding_id=#{runtimeBindingId}"
            + " AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})"
            + " ORDER BY fact_sequence DESC LIMIT 1 FOR UPDATE")
    AgentRuntimeV2BindingGenerationFactEntity lockLatest(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("agentId") String agentId, @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("runtimeBindingId") String runtimeBindingId);

}
