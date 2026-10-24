package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRuntimeV2RegistrationRequestMapper {
    @Insert("""
            INSERT INTO agent_runtime_v2_registration_request(
              registration_request_id,request_kind,request_wire_sha256,semantic_sha256,
              registration_subject_sha256,registration_authority_sha256,runtime_binding_id,binding_generation,
              runtime_instance_id,process_public_key_sha256,registration_authority_id,
              registration_authority_revision,data_domain_id,execution_pool_id,runtime_credential_client_id,
              owner_jiacn,agent_id,claimant_deployment_id,claimant_worker_generation,
              receiver_registration_revision,admission_epoch,module_id,migration_epoch,decision,
              registration_receipt_id,registration_receipt_sha256,denial_code,decided_at,contract_version,
              tenant_id,client_id,create_time,update_time)
            VALUES(#{e.registrationRequestId},#{e.requestKind},#{e.requestWireSha256},#{e.semanticSha256},
              #{e.registrationSubjectSha256},#{e.registrationAuthoritySha256},#{e.runtimeBindingId},#{e.bindingGeneration},
              #{e.runtimeInstanceId},#{e.processPublicKeySha256},#{e.registrationAuthorityId},
              #{e.registrationAuthorityRevision},#{e.dataDomainId},#{e.executionPoolId},#{e.runtimeCredentialClientId},
              #{e.ownerJiacn},#{e.agentId},#{e.claimantDeploymentId},#{e.claimantWorkerGeneration},
              #{e.receiverRegistrationRevision},#{e.admissionEpoch},#{e.moduleId},#{e.migrationEpoch},#{e.decision},
              #{e.registrationReceiptId},#{e.registrationReceiptSha256},#{e.denialCode},#{e.decidedAt},#{e.contractVersion},
              #{e.tenantId},#{e.clientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("e") AgentRuntimeV2RegistrationRequestEntity entity);
    @Select("SELECT * FROM agent_runtime_v2_registration_request WHERE registration_request_id=#{id} AND CAST(registration_request_id AS BINARY)=CAST(#{id} AS BINARY) AND OCTET_LENGTH(registration_request_id)=OCTET_LENGTH(#{id}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2RegistrationRequestEntity lockById(@Param("id") String requestId);
}
