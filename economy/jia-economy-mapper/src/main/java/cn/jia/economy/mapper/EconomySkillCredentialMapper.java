package cn.jia.economy.mapper;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** New per-Agent managed key binding. Never reinterprets an owner-shared legacy key. */
public interface EconomySkillCredentialMapper {
    String S=EconomySkillMarketplaceMapper.EXACT_SCOPE;
    @Select("SELECT credential_id,api_key_id,agent_id,binding_id,version,tenant_id,client_id FROM economy_skill_managed_credential WHERE "+S+" AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId}) AND live_slot=1 FOR UPDATE")
    SkillManagedCredentialEntity active(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    @Insert("INSERT INTO economy_skill_managed_credential(credential_id,api_key_id,agent_id,binding_id,version,tenant_id,client_id,live_slot,create_time) VALUES(#{credentialId},#{apiKeyId},#{agentId},#{bindingId},1,#{tenantId},#{clientId},1,#{now})")
    int insert(@Param("credentialId") String id,@Param("apiKeyId") String key,@Param("agentId") String agent,@Param("bindingId") long binding,@Param("tenantId") String t,@Param("clientId") String c,@Param("now") long now);
    @Update("UPDATE economy_skill_managed_credential SET live_slot=NULL,retired_at=#{now},version=version+1 WHERE "+S+" AND credential_id=#{id} AND OCTET_LENGTH(credential_id)=OCTET_LENGTH(#{id}) AND live_slot=1 AND version=#{version}")
    int retire(@Param("tenantId") String t,@Param("clientId") String c,@Param("id") String id,@Param("version") long v,@Param("now") long now);
    @Select("SELECT request_hash,credential_id FROM economy_skill_credential_operation WHERE "+S+" AND actor_id=#{actorId} AND OCTET_LENGTH(actor_id)=OCTET_LENGTH(#{actorId}) AND idempotency_key=#{key} FOR UPDATE")
    SkillCredentialOperationEntity operation(@Param("tenantId") String t,@Param("clientId") String c,@Param("actorId") String a,@Param("key") byte[] key);
    @Insert("INSERT INTO economy_skill_credential_operation(tenant_id,client_id,actor_id,idempotency_key,request_hash,credential_id,create_time) VALUES(#{tenantId},#{clientId},#{actorId},#{key},#{hash},#{credentialId},#{now})")
    int operationInsert(@Param("tenantId") String t,@Param("clientId") String c,@Param("actorId") String a,@Param("key") byte[] key,@Param("hash") byte[] hash,@Param("credentialId") String id,@Param("now") long now);
    @Select("SELECT * FROM economy_hosting_provisioning_intent WHERE "+S+" AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId}) AND quote_purpose='INITIAL' AND status='ACTIVE' AND managed_api_key_id IS NOT NULL")
    List<EconomyHostingProvisioningIntentEntity> hostingCandidates(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    // OAuth key IDs are global PKs. This internal uniqueness proof deliberately checks all references,
    // not a filtered subset that could hide another Agent/scope. No reference identity is exposed.
    @Select("SELECT COUNT(*) FROM economy_hosting_provisioning_intent WHERE managed_api_key_id=#{keyId} AND CAST(managed_api_key_id AS BINARY)=CAST(#{keyId} AS BINARY) AND OCTET_LENGTH(managed_api_key_id)=OCTET_LENGTH(#{keyId})")
    int hostingReferenceCount(@Param("keyId") String key);
}
