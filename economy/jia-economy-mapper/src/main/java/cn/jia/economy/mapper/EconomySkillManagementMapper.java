package cn.jia.economy.mapper;
import cn.jia.economy.entity.skill.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** Scoped persistent SM-C1 state; orchestration and lock ordering stay in the skill service. */
public interface EconomySkillManagementMapper {
    String S = "tenant_id=#{tenantId} AND client_id=#{clientId}";
    @Insert("INSERT INTO economy_skill_managed_state(tenant_id,client_id,agent_id,skill_key,skill_revision,active_installation_id,active_policy_revision,desired_enabled,admission_enabled,pending_change_id) VALUES(#{tenantId},#{clientId},#{agentId},#{skillKey},#{skillRevision},#{activeInstallationId},#{activePolicyRevision},#{desiredEnabled},#{admissionEnabled},#{pendingChangeId})")
    int insertState(SkillManagedStateEntity row);
    @Select("SELECT * FROM economy_skill_managed_state WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND agent_id=#{agentId} AND skill_key=#{skillKey}")
    SkillManagedStateEntity findState(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("agentId") String agentId,@Param("skillKey") String skillKey);
    @Select("SELECT * FROM economy_skill_managed_state WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND agent_id=#{agentId} AND skill_key=#{skillKey} FOR UPDATE")
    SkillManagedStateEntity lockState(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("agentId") String agentId,@Param("skillKey") String skillKey);
    @Insert("INSERT INTO economy_skill_change(tenant_id,client_id,change_id,agent_id,skill_key,actor_id,owner_jiacn,version,dto_json,permissions_json,runtime_installation_id,runtime_host_id,entitlement_id,command_id,created_at,updated_at) VALUES(#{tenantId},#{clientId},#{changeId},#{agentId},#{skillKey},#{actorId},#{ownerJiacn},#{version},#{dtoJson},#{permissionsJson},#{runtimeInstallationId},#{runtimeHostId},#{entitlementId},#{commandId},#{createdAt},#{updatedAt})")
    int insertChange(SkillChangeEntity row);
    @Select("SELECT * FROM economy_skill_change WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND change_id=#{changeId}")
    SkillChangeEntity findChange(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("changeId") String changeId);
    @Select("SELECT * FROM economy_skill_change WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND change_id=#{changeId} FOR UPDATE")
    SkillChangeEntity lockChange(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("changeId") String changeId);
    @Insert("INSERT INTO economy_skill_management_quote(tenant_id,client_id,quote_id,actor_id,request_json,dto_json,target_json,details_json) VALUES(#{tenantId},#{clientId},#{quoteId},#{actorId},#{requestJson},#{dtoJson},#{targetJson},#{detailsJson})")
    int insertQuote(SkillManagementQuoteEntity row);
    @Select("SELECT * FROM economy_skill_management_quote WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND quote_id=#{quoteId}")
    SkillManagementQuoteEntity findQuote(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("quoteId") String quoteId);
    @Select("SELECT * FROM economy_skill_management_quote WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND quote_id=#{quoteId} FOR UPDATE")
    SkillManagementQuoteEntity lockQuote(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("quoteId") String quoteId);
    @Insert("INSERT INTO economy_skill_installation_authorization(tenant_id,client_id,installation_id,change_id,entitlement_id,package_json,details_json,permissions_json,permissions_sha256,runtime_installation_id,runtime_host_id) VALUES(#{tenantId},#{clientId},#{installationId},#{changeId},#{entitlementId},#{packageJson},#{detailsJson},#{permissionsJson},#{permissionsSha256},#{runtimeInstallationId},#{runtimeHostId})")
    int insertAuthorization(SkillInstallationAuthorizationEntity row);
    @Select("SELECT * FROM economy_skill_installation_authorization WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND installation_id=#{installationId}")
    SkillInstallationAuthorizationEntity findAuthorization(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("installationId") String installationId);
    @Select("SELECT * FROM economy_skill_installation_authorization WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND installation_id=#{installationId} FOR UPDATE")
    SkillInstallationAuthorizationEntity lockAuthorization(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("installationId") String installationId);
    @Insert("INSERT INTO economy_skill_change_command(tenant_id,client_id,command_id,change_id,request_id,message_id,data_json,state,prepared_sha256) VALUES(#{tenantId},#{clientId},#{commandId},#{changeId},#{requestId},#{messageId},#{dataJson},#{state},#{preparedSha256})")
    int insertCommand(SkillChangeCommandEntity row);
    @Select("SELECT * FROM economy_skill_change_command WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND command_id=#{commandId}")
    SkillChangeCommandEntity findCommand(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("commandId") String commandId);
    @Select("SELECT * FROM economy_skill_change_command WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND command_id=#{commandId} FOR UPDATE")
    SkillChangeCommandEntity lockCommand(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("commandId") String commandId);
    @Insert("INSERT INTO economy_skill_management_request(tenant_id,client_id,actor_id,method,path,key_hash,request_sha256,resource_id) VALUES(#{tenantId},#{clientId},#{actorId},#{method},#{path},#{keyHash},#{requestSha256},#{resourceId})")
    int insertRequest(SkillManagementRequestEntity row);
    @Select("SELECT * FROM economy_skill_management_request WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND actor_id=#{actorId} AND method=#{method} AND path=#{path} AND key_hash=#{keyHash}")
    SkillManagementRequestEntity findRequest(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("actorId") String actorId,@Param("method") String method,@Param("path") String path,@Param("keyHash") String keyHash);
    @Select("SELECT * FROM economy_skill_management_request WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND actor_id=#{actorId} AND method=#{method} AND path=#{path} AND key_hash=#{keyHash} FOR UPDATE")
    SkillManagementRequestEntity lockRequest(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("actorId") String actorId,@Param("method") String method,@Param("path") String path,@Param("keyHash") String keyHash);
    @Insert("INSERT INTO economy_skill_management_result(tenant_id,client_id,message_id,result_sha256,receipt_json) VALUES(#{tenantId},#{clientId},#{messageId},#{resultSha256},#{receiptJson})")
    int insertResult(SkillManagementResultEntity row);
    @Select("SELECT * FROM economy_skill_management_result WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND message_id=#{messageId}")
    SkillManagementResultEntity findResult(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("messageId") String messageId);
    @Select("SELECT * FROM economy_skill_management_result WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND message_id=#{messageId} FOR UPDATE")
    SkillManagementResultEntity lockResult(@Param("tenantId") String tenantId,@Param("clientId") String clientId,@Param("messageId") String messageId);

    @Select("SELECT * FROM economy_skill_managed_state WHERE "+S+" AND agent_id=#{agentId} ORDER BY skill_key")
    List<SkillManagedStateEntity> states(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    @Insert("INSERT INTO economy_skill_managed_state(tenant_id,client_id,agent_id,skill_key) VALUES(#{tenantId},#{clientId},#{agentId},#{skillKey}) ON DUPLICATE KEY UPDATE skill_revision=skill_revision")
    int ensureState(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a,@Param("skillKey") String k);
    @Update("UPDATE economy_skill_managed_state SET skill_revision=#{r.skillRevision},active_installation_id=#{r.activeInstallationId},active_policy_revision=#{r.activePolicyRevision},desired_enabled=#{r.desiredEnabled},admission_enabled=#{r.admissionEnabled},pending_change_id=#{r.pendingChangeId} WHERE tenant_id=#{r.tenantId} AND client_id=#{r.clientId} AND agent_id=#{r.agentId} AND skill_key=#{r.skillKey} AND skill_revision=#{expected}")
    int saveState(@Param("r") SkillManagedStateEntity r,@Param("expected") long expected);
    @Update("UPDATE economy_skill_change SET version=version+1,dto_json=#{r.dtoJson},command_id=#{r.commandId},entitlement_id=#{r.entitlementId},updated_at=#{r.updatedAt} WHERE tenant_id=#{r.tenantId} AND client_id=#{r.clientId} AND change_id=#{r.changeId} AND version=#{r.version} AND version<9223372036854775807")
    int saveChange(@Param("r") SkillChangeEntity r);
    @Select("SELECT * FROM economy_skill_change WHERE "+S+" AND agent_id=#{agentId} ORDER BY created_at DESC,change_id DESC")
    List<SkillChangeEntity> changes(@Param("tenantId") String t,@Param("clientId") String c,@Param("agentId") String a);
    @Select("SELECT * FROM economy_skill_change_command WHERE "+S+" AND change_id=#{changeId} ORDER BY command_id")
    List<SkillChangeCommandEntity> commands(@Param("tenantId") String t,@Param("clientId") String c,@Param("changeId") String a);
    @Update("UPDATE economy_skill_change_command SET state=#{state},prepared_sha256=#{preparedSha256},evidence_json=#{evidenceJson} WHERE "+S+" AND command_id=#{commandId}")
    int saveCommand(SkillChangeCommandEntity command);
    @Select("SELECT policy FROM economy_skill_upgrade_policy WHERE "+S+" AND source_product_version_id=#{source} AND target_product_version_id=#{target} FOR UPDATE")
    String upgradePolicy(@Param("tenantId") String t,@Param("clientId") String c,@Param("source") String source,@Param("target") String target);
    @Select("SELECT * FROM economy_skill_entitlement WHERE "+S+" AND entitlement_id=#{entitlementId}")
    SkillEntitlementEntity readEntitlement(@Param("tenantId") String t,@Param("clientId") String c,@Param("entitlementId") String id);
    @Select("SELECT * FROM economy_skill_entitlement WHERE "+S+" AND entitlement_id=#{entitlementId} FOR UPDATE")
    SkillEntitlementEntity entitlement(@Param("tenantId") String t,@Param("clientId") String c,@Param("entitlementId") String id);
    @Select("SELECT p.* FROM economy_skill_product p WHERE "+S+" AND status='PUBLISHED' ORDER BY product_id")
    List<SkillProductEntity> products(@Param("tenantId") String t,@Param("clientId") String c);
    @Select("SELECT * FROM economy_skill_installation_authorization WHERE "+S+" AND change_id=#{changeId} ORDER BY installation_id")
    List<SkillInstallationAuthorizationEntity> authorizations(@Param("tenantId") String t,@Param("clientId") String c,@Param("changeId") String id);
}
