package cn.jia.agent.mapper;

import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ControlledImageBridgeOperationMapper extends BaseMapper<ControlledImageBridgeOperationEntity> {
    String COLUMNS="id,owner_jiacn,task_id,assignment_idempotency_key,wrapper_digest,consent_id,"
            +"expected_consent_version,grant_id,grant_version,assignment_revision,authority_locator,execution_protocol_version,operation_grant_id,created_at,"
            +"tenant_id,client_id,create_time,update_time";
    String EXACT_SCOPE=" AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)"
            +" AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)"
            +" AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)"
            +" AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)";
    String EXACT=EXACT_SCOPE+" AND CAST(assignment_idempotency_key AS BINARY)=CAST(#{key} AS BINARY)";
    @Select("SELECT "+COLUMNS+" FROM agent_controlled_image_bridge_operation WHERE tenant_id=#{tenantId}"
            +" AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}"
            +" AND assignment_idempotency_key=#{key}"+EXACT+" LIMIT 1")
    ControlledImageBridgeOperationEntity select(@Param("tenantId")String tenantId,@Param("clientId")String clientId,
            @Param("ownerJiacn")String ownerJiacn,@Param("taskId")String taskId,@Param("key")String key);
    @Select("SELECT "+COLUMNS+" FROM agent_controlled_image_bridge_operation WHERE tenant_id=#{tenantId}"
            +" AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}"
            +" AND assignment_idempotency_key=#{key}"+EXACT+" LIMIT 1 FOR UPDATE")
    ControlledImageBridgeOperationEntity selectForUpdate(@Param("tenantId")String tenantId,@Param("clientId")String clientId,
            @Param("ownerJiacn")String ownerJiacn,@Param("taskId")String taskId,@Param("key")String key);
    String AUTHORITY_EXACT=" AND CAST(consent_id AS BINARY)=CAST(#{consentId} AS BINARY)"
            +" AND CAST(grant_id AS BINARY)=CAST(#{grantId} AS BINARY)";
    @Select("SELECT "+COLUMNS+" FROM agent_controlled_image_bridge_operation WHERE tenant_id=#{tenantId}"
            +" AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}"
            +" AND consent_id=#{consentId} AND grant_id=#{grantId}"+EXACT_SCOPE+AUTHORITY_EXACT+" LIMIT 1 FOR UPDATE")
    ControlledImageBridgeOperationEntity selectByAuthorityForUpdate(@Param("tenantId")String tenantId,
            @Param("clientId")String clientId,@Param("ownerJiacn")String ownerJiacn,
            @Param("taskId")String taskId,@Param("consentId")String consentId,@Param("grantId")String grantId);
    @Select("SELECT "+COLUMNS+" FROM agent_controlled_image_bridge_operation WHERE tenant_id=#{tenantId}"
            +" AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}"
            +" AND operation_grant_id=#{operationGrantId}"+EXACT_SCOPE
            +" AND CAST(operation_grant_id AS BINARY)=CAST(#{operationGrantId} AS BINARY) LIMIT 1")
    ControlledImageBridgeOperationEntity selectByOperationGrant(@Param("tenantId")String tenantId,
            @Param("clientId")String clientId,@Param("ownerJiacn")String ownerJiacn,
            @Param("taskId")String taskId,@Param("operationGrantId")String operationGrantId);
    @Select("SELECT "+COLUMNS+" FROM agent_controlled_image_bridge_operation WHERE tenant_id=#{tenantId}"
            +" AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}"
            +" AND operation_grant_id=#{operationGrantId}"+EXACT_SCOPE
            +" AND CAST(operation_grant_id AS BINARY)=CAST(#{operationGrantId} AS BINARY) LIMIT 1 FOR UPDATE")
    ControlledImageBridgeOperationEntity selectByOperationGrantForUpdate(@Param("tenantId")String tenantId,
            @Param("clientId")String clientId,@Param("ownerJiacn")String ownerJiacn,
            @Param("taskId")String taskId,@Param("operationGrantId")String operationGrantId);

}
