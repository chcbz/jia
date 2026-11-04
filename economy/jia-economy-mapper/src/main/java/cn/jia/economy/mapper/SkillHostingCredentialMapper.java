package cn.jia.economy.mapper;
import cn.jia.economy.entity.EconomyHostingProvisioningIntentEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
public interface SkillHostingCredentialMapper {
    String S=SkillTradeMapper.EXACT_SCOPE;
    @Select("SELECT * FROM economy_hosting_provisioning_intent WHERE "+S+" AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId}) AND quote_purpose='INITIAL' AND status='ACTIVE' AND managed_api_key_id IS NOT NULL")
    List<EconomyHostingProvisioningIntentEntity> hostingCandidates(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    // OAuth key IDs are global PKs. This internal uniqueness proof deliberately checks all references,
    // not a filtered subset that could hide another Agent/scope. No reference identity is exposed.
    @Select("SELECT COUNT(*) FROM economy_hosting_provisioning_intent WHERE managed_api_key_id=#{keyId} AND CAST(managed_api_key_id AS BINARY)=CAST(#{keyId} AS BINARY) AND OCTET_LENGTH(managed_api_key_id)=OCTET_LENGTH(#{keyId})")
    int hostingReferenceCount(@Param("keyId") String key);
}
