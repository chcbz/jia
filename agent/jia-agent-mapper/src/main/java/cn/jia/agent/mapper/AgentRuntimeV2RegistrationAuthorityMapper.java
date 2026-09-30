package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2RegistrationAuthorityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRuntimeV2RegistrationAuthorityMapper {
    @Insert("""
            INSERT INTO agent_runtime_v2_registration_authority(
              registration_authority_id,credential_kind,credential_reference,credential_digest,
              credential_key_id,owner_jiacn,agent_id,data_domain_id,execution_pool_id,module_id,
              claimant_deployment_id,authority_revision,status,revoked_at,revoke_reason,version,tenant_id,client_id,create_time,update_time)
            VALUES(#{e.registrationAuthorityId},#{e.credentialKind},#{e.credentialReference},#{e.credentialDigest},
              #{e.credentialKeyId},#{e.ownerJiacn},#{e.agentId},#{e.dataDomainId},#{e.executionPoolId},#{e.moduleId},
              #{e.claimantDeploymentId},#{e.authorityRevision},#{e.status},#{e.revokedAt},#{e.revokeReason},#{e.version},#{e.tenantId},#{e.clientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("e") AgentRuntimeV2RegistrationAuthorityEntity entity);

    @Select("SELECT * FROM agent_runtime_v2_registration_authority WHERE registration_authority_id=#{id} AND CAST(registration_authority_id AS BINARY)=CAST(#{id} AS BINARY) AND OCTET_LENGTH(registration_authority_id)=OCTET_LENGTH(#{id}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2RegistrationAuthorityEntity lockById(@Param("id") String authorityId);

    @Select("SELECT * FROM agent_runtime_v2_registration_authority WHERE credential_kind=#{kind} AND credential_reference=#{reference} AND tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{owner} AND agent_id=#{agentId} AND CAST(credential_reference AS BINARY)=CAST(#{reference} AS BINARY) AND OCTET_LENGTH(credential_reference)=OCTET_LENGTH(#{reference}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2RegistrationAuthorityEntity lockExactSubject(@Param("kind") String kind,
            @Param("reference") String reference,@Param("tenantId") String tenantId,
            @Param("clientId") String clientId,@Param("owner") String owner,@Param("agentId") String agentId);
}
