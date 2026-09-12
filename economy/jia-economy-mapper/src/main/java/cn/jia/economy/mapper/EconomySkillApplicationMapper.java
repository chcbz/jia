package cn.jia.economy.mapper;
import cn.jia.economy.entity.skill.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** W09-only concurrency roots and durable incoming result receipts. No funding-path writes. */
public interface EconomySkillApplicationMapper {
    String S = EconomySkillMarketplaceMapper.EXACT_SCOPE;
    @Insert("INSERT INTO economy_skill_actor_root(tenant_id,client_id,actor_id) VALUES(#{tenantId},#{clientId},#{actorId}) ON DUPLICATE KEY UPDATE id=id")
    int ensureActor(@Param("tenantId") String t,@Param("clientId") String c,@Param("actorId") String a);
    @Select("SELECT id FROM economy_skill_actor_root WHERE " + S + " AND actor_id=#{actorId} AND OCTET_LENGTH(actor_id)=OCTET_LENGTH(#{actorId}) FOR UPDATE")
    Long lockActor(@Param("tenantId") String t,@Param("clientId") String c,@Param("actorId") String a);
    @Insert("INSERT INTO economy_skill_agent_version(tenant_id,client_id,agent_id,source_hash,version) VALUES(#{tenantId},#{clientId},#{agentId},#{hash},1) ON DUPLICATE KEY UPDATE id=id")
    int ensureVersion(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a,@Param("hash") byte[] hash);
    @Select("SELECT source_hash,version FROM economy_skill_agent_version WHERE " + S + " AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId}) FOR UPDATE")
    SkillAgentVersionEntity lockVersion(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    @Update("UPDATE economy_skill_agent_version SET version=version+1,source_hash=#{hash} WHERE " + S + " AND agent_id=#{agentId} AND OCTET_LENGTH(agent_id)=OCTET_LENGTH(#{agentId}) AND version=#{version} AND version<9223372036854775807")
    int advanceVersion(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a,@Param("hash") byte[] hash,@Param("version") long v);
    @Select("SELECT installation_id,request_hash,outcome FROM economy_skill_result_receipt WHERE " + S + " AND message_id=#{messageId} AND OCTET_LENGTH(message_id)=OCTET_LENGTH(#{messageId}) FOR UPDATE")
    SkillResultReceiptEntity lockResult(@Param("tenantId") String t,@Param("clientId") String c,@Param("messageId") String m);
    @Insert("INSERT INTO economy_skill_result_receipt(tenant_id,client_id,message_id,installation_id,request_hash,outcome,create_time) VALUES(#{tenantId},#{clientId},#{messageId},#{installationId},#{hash},#{outcome},#{now})")
    int insertResult(@Param("tenantId") String t,@Param("clientId") String c,@Param("messageId") String m,@Param("installationId") String i,@Param("hash") byte[] h,@Param("outcome") String o,@Param("now") long n);
    @Select("SELECT * FROM economy_skill_installation WHERE " + S + " AND installation_id=#{installationId} AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})")
    SkillInstallationEntity installation(@Param("tenantId") String t,@Param("clientId") String c,@Param("installationId") String i);
    @Select("SELECT * FROM economy_skill_installation WHERE " + S + " AND command_id=#{commandId} AND OCTET_LENGTH(command_id)=OCTET_LENGTH(#{commandId})")
    SkillInstallationEntity byCommand(@Param("tenantId") String t,@Param("clientId") String c,@Param("commandId") String i);
    @Select("SELECT * FROM economy_skill_product WHERE " + S + " AND product_id=#{productId} AND OCTET_LENGTH(product_id)=OCTET_LENGTH(#{productId}) AND status='PUBLISHED'")
    SkillProductEntity product(@Param("tenantId") String t,@Param("clientId") String c,@Param("productId") String p);
    // Historical FAILED rows retain their original association. V0 forbids a second skill-key activation;
    // a safe retry must be separately admitted rather than reusing an old install's identity.
    @Select("SELECT * FROM economy_skill_entitlement WHERE " + S + " AND target_agent_id=#{agentId} AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId}) AND skill_key=#{skillKey} AND OCTET_LENGTH(skill_key)=OCTET_LENGTH(#{skillKey}) FOR UPDATE")
    List<SkillEntitlementEntity> lockSkillKey(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a,@Param("skillKey") String k);
    @Select("SELECT api_key_id,registration_hash,escrow_version FROM economy_skill_delivery_binding WHERE " + S + " AND installation_id=#{installationId} AND OCTET_LENGTH(installation_id)=OCTET_LENGTH(#{installationId})")
    SkillDeliveryBindingEntity deliveryBinding(@Param("tenantId") String t,@Param("clientId") String c,@Param("installationId") String i);
    @Insert("INSERT INTO economy_skill_delivery_binding(tenant_id,client_id,installation_id,api_key_id,registration_hash,escrow_version) VALUES(#{tenantId},#{clientId},#{installationId},#{keyId},#{hash},#{escrowVersion})")
    int insertDeliveryBinding(@Param("tenantId") String t,@Param("clientId") String c,@Param("installationId") String i,@Param("keyId") String k,@Param("hash") byte[] h,@Param("escrowVersion") Long v);
    @Select("SELECT * FROM economy_skill_order WHERE " + S + " AND order_id=#{orderId} AND OCTET_LENGTH(order_id)=OCTET_LENGTH(#{orderId})")
    SkillOrderEntity order(@Param("tenantId") String t,@Param("clientId") String c,@Param("orderId") String o);
    @Select("SELECT order_id FROM economy_skill_order WHERE " + S + " AND quote_id=#{quoteId} AND OCTET_LENGTH(quote_id)=OCTET_LENGTH(#{quoteId})")
    String orderForQuote(@Param("tenantId") String t,@Param("clientId") String c,@Param("quoteId") String q);
    @Select("SELECT COUNT(*) FROM economy_skill_order WHERE "+S+" AND target_agent_id=#{agentId} AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{agentId}) AND status IN ('FUNDS_HELD','INSTALLING')")
    int pendingInstalls(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
}
