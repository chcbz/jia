package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationReceiptEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRuntimeV2RegistrationReceiptMapper {
    @Insert("""
            INSERT INTO agent_runtime_v2_registration_receipt(
              registration_receipt_id,receipt_kind,registration_request_id,previous_registration_receipt_id,
              previous_registration_receipt_sha256,runtime_binding_id,binding_generation,binding_generation_fact_id,
              runtime_instance_id,process_public_key_sha256,verifier_version,verifier_sha256,verifier_cipher_suite,
              server_ephemeral_public_key_spki,nonce,sealed_verifier,data_domain_id,execution_pool_id,
              runtime_credential_client_id,owner_jiacn,agent_id,registration_authority_id,
              registration_authority_revision,claimant_deployment_id,claimant_worker_generation,
              receiver_registration_revision,admission_epoch,module_id,migration_epoch,connected_at,
              receipt_wire_bytes,receipt_wire_sha256,contract_version,tenant_id,client_id,create_time,update_time)
            VALUES(#{e.registrationReceiptId},#{e.receiptKind},#{e.registrationRequestId},#{e.previousRegistrationReceiptId},
              #{e.previousRegistrationReceiptSha256},#{e.runtimeBindingId},#{e.bindingGeneration},#{e.bindingGenerationFactId},
              #{e.runtimeInstanceId},#{e.processPublicKeySha256},#{e.verifierVersion},#{e.verifierSha256},#{e.verifierCipherSuite},
              #{e.serverEphemeralPublicKeySpki},#{e.nonce},#{e.sealedVerifier},#{e.dataDomainId},#{e.executionPoolId},
              #{e.runtimeCredentialClientId},#{e.ownerJiacn},#{e.agentId},#{e.registrationAuthorityId},
              #{e.registrationAuthorityRevision},#{e.claimantDeploymentId},#{e.claimantWorkerGeneration},
              #{e.receiverRegistrationRevision},#{e.admissionEpoch},#{e.moduleId},#{e.migrationEpoch},#{e.connectedAt},
              #{e.receiptWireBytes},#{e.receiptWireSha256},#{e.contractVersion},#{e.tenantId},#{e.clientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("e") AgentRuntimeV2RegistrationReceiptEntity entity);
    @Select("SELECT * FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=#{id} AND CAST(registration_receipt_id AS BINARY)=CAST(#{id} AS BINARY) AND OCTET_LENGTH(registration_receipt_id)=OCTET_LENGTH(#{id}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2RegistrationReceiptEntity lockById(@Param("id") String receiptId);
    @Select("SELECT * FROM agent_runtime_v2_registration_receipt WHERE runtime_binding_id=#{bindingId} AND binding_generation=#{generation} LIMIT 1 FOR UPDATE")
    AgentRuntimeV2RegistrationReceiptEntity lockByBindingGeneration(@Param("bindingId") String bindingId,@Param("generation") long generation);
}
