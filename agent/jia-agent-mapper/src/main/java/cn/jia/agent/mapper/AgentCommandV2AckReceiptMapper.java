package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentCommandV2AckReceiptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentCommandV2AckReceiptMapper {
    @Insert("""
            INSERT INTO agent_command_v2_ack_receipt(
              receipt_id,runtime_dispatch_id,inbox_id,delivery_id,ack_message_id,source_message_id,
              status,ack_at,request_fingerprint,runtime_binding_id,dispatch_binding_generation,
              ack_holder_binding_generation,runtime_instance_id,runtime_credential_client_id,
              data_domain_id,execution_pool_id,owner_jiacn,agent_id,producer_deployment_id,
              claimant_deployment_id,claimant_worker_generation,receiver_registration_revision,
              admission_epoch,module_id,migration_epoch,postcommit_delivery_version,
              postcommit_inbox_version,lease_fact_id,contract_version,receipt_wire_bytes,
              receipt_wire_sha256,receipt_wire_contract_version,committed_at,execution_origin_snapshot_sha256,
              ack_holder_generation_fact_id,ack_holder_generation_snapshot_sha256,
              tenant_id,client_id,create_time,update_time)
            VALUES(#{e.receiptId},#{e.runtimeDispatchId},#{e.inboxId},#{e.deliveryId},
              #{e.ackMessageId},#{e.sourceMessageId},#{e.status},#{e.ackAt},#{e.requestFingerprint},
              #{e.runtimeBindingId},#{e.dispatchBindingGeneration},#{e.ackHolderBindingGeneration},
              #{e.runtimeInstanceId},#{runtimeCredentialClientId},#{dataDomainId},#{executionPoolId},
              #{ownerJiacn},#{agentId},#{producerDeploymentId},#{claimantDeploymentId},
              #{claimantWorkerGeneration},#{receiverRegistrationRevision},#{admissionEpoch},#{moduleId},
              #{migrationEpoch},#{e.postcommitDeliveryVersion},#{e.postcommitInboxVersion},
              #{e.leaseFactId},#{e.contractVersion},#{e.receiptWireBytes},#{e.receiptWireSha256},
              #{e.receiptWireContractVersion},#{e.committedAt},#{e.executionOriginSnapshotSha256},
              #{e.ackHolderGenerationFactId},#{e.ackHolderGenerationSnapshotSha256},#{tenantId},#{commandCallerClientId},
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
            @Param("migrationEpoch") long migrationEpoch, @Param("e") AgentCommandV2AckReceiptEntity entity);

    @Select("SELECT * FROM agent_command_v2_ack_receipt WHERE " + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND ack_message_id=#{ackMessageId}"
            + " AND CAST(ack_message_id AS BINARY)=CAST(#{ackMessageId} AS BINARY)"
            + " AND OCTET_LENGTH(ack_message_id)=OCTET_LENGTH(#{ackMessageId}) LIMIT 1")
    AgentCommandV2AckReceiptEntity findByAckMessageId(@Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId, @Param("executionPoolId") String executionPoolId,
            @Param("commandCallerClientId") String commandCallerClientId,
            @Param("runtimeCredentialClientId") String runtimeCredentialClientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("agentId") String agentId,
            @Param("producerDeploymentId") String producerDeploymentId,
            @Param("claimantDeploymentId") String claimantDeploymentId,
            @Param("claimantWorkerGeneration") long claimantWorkerGeneration,
            @Param("receiverRegistrationRevision") long receiverRegistrationRevision,
            @Param("admissionEpoch") long admissionEpoch, @Param("moduleId") String moduleId,
            @Param("migrationEpoch") long migrationEpoch, @Param("ackMessageId") String ackMessageId);

    @Select("SELECT * FROM agent_command_v2_ack_receipt WHERE " + AgentRuntimeV2MapperScope.SPLIT_EXACT
            + " AND runtime_dispatch_id=#{runtimeDispatchId} AND status=#{status}"
            + " AND runtime_binding_id=#{runtimeBindingId}"
            + " AND CAST(runtime_dispatch_id AS BINARY)=CAST(#{runtimeDispatchId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_dispatch_id)=OCTET_LENGTH(#{runtimeDispatchId})"
            + " AND CAST(runtime_binding_id AS BINARY)=CAST(#{runtimeBindingId} AS BINARY)"
            + " AND OCTET_LENGTH(runtime_binding_id)=OCTET_LENGTH(#{runtimeBindingId})"
            + " AND CAST(status AS BINARY)=CAST(#{status} AS BINARY)"
            + " AND OCTET_LENGTH(status)=OCTET_LENGTH(#{status}) LIMIT 1 FOR UPDATE")
    AgentCommandV2AckReceiptEntity lockCommitted(@Param("tenantId") String tenantId,
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
            @Param("runtimeDispatchId") String runtimeDispatchId, @Param("status") String status);
}
