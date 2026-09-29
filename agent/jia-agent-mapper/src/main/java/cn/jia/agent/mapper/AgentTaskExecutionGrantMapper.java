package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AgentTaskExecutionGrantMapper extends BaseMapper<AgentTaskExecutionGrantEntity> {
    String COLUMNS = """
            id,grant_id,owner_jiacn,task_id,requirement_revision,assignment_revision,
            target_agent_id,permitted_operations_json,permitted_tool_policy_ref,input_scope_json,
            allow_own_task_derived_assets,cost_authorization_ref,source_business_action_id,
            idempotency_key,request_hash,policy_revision,grant_version,state,issued_by,
            created_at,revoked_at,revoke_idempotency_key,revoke_request_hash,
            tenant_id,client_id,create_time,update_time
            """;
    String EXACT_SCOPE = """
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
            """;
    String EXACT_TASK = """
              AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
            """;

    @Select("SELECT " + COLUMNS + " FROM agent_task_execution_grant"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND source_business_action_id=#{sourceBusinessActionId}" + EXACT_SCOPE + """
              AND CAST(source_business_action_id AS BINARY)=CAST(#{sourceBusinessActionId} AS BINARY)
              AND OCTET_LENGTH(source_business_action_id)=OCTET_LENGTH(#{sourceBusinessActionId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskExecutionGrantEntity selectByActionForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("sourceBusinessActionId") String sourceBusinessActionId);

    @Select("SELECT " + COLUMNS + " FROM agent_task_execution_grant"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND grant_id=#{grantId}" + EXACT_SCOPE + EXACT_TASK + """
              AND CAST(grant_id AS BINARY)=CAST(#{grantId} AS BINARY)
              AND OCTET_LENGTH(grant_id)=OCTET_LENGTH(#{grantId})
             LIMIT 1 FOR UPDATE
            """)
    AgentTaskExecutionGrantEntity selectByGrantForUpdate(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("grantId") String grantId);

    @Select("SELECT " + COLUMNS + " FROM agent_task_execution_grant"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND state='ACTIVE'" + EXACT_SCOPE + EXACT_TASK + """
              AND CAST(state AS BINARY)=CAST('ACTIVE' AS BINARY)
              AND OCTET_LENGTH(state)=OCTET_LENGTH('ACTIVE')
             LIMIT 1
            """)
    AgentTaskExecutionGrantEntity selectActiveByTask(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId);

    @Select("SELECT " + COLUMNS + " FROM agent_task_execution_grant"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND grant_id=#{grantId}" + EXACT_SCOPE + EXACT_TASK + """
              AND CAST(grant_id AS BINARY)=CAST(#{grantId} AS BINARY)
              AND OCTET_LENGTH(grant_id)=OCTET_LENGTH(#{grantId})
             LIMIT 1
            """)
    AgentTaskExecutionGrantEntity selectByGrant(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("grantId") String grantId);

    @Select("SELECT event_json FROM agent_task_event"
            + " WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}"
            + " AND task_id=#{taskId} AND event_type='TASK_ASSIGNED'" + EXACT_SCOPE + EXACT_TASK + """
              AND CAST(event_type AS BINARY)=CAST('TASK_ASSIGNED' AS BINARY)
              AND OCTET_LENGTH(event_type)=OCTET_LENGTH('TASK_ASSIGNED')
              ORDER BY event_version DESC LIMIT 1
            """)
    String selectLatestAssignmentEventJson(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId);

    @Update("""
            UPDATE agent_task_execution_grant
               SET state='SUPERSEDED', grant_version=grant_version+1,
                   revoked_at=#{supersededAt}, update_time=#{supersededAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND state='ACTIVE'
            """ + EXACT_SCOPE + EXACT_TASK + """
               AND CAST(state AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('ACTIVE')
            """)
    int supersedeActiveForTask(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("supersededAt") long supersededAt);

    @Update("""
            UPDATE agent_task_execution_grant
               SET state='REVOKED', grant_version=grant_version+1, revoked_at=#{revokedAt},
                   revoke_idempotency_key=#{idempotencyKey}, revoke_request_hash=#{requestHash},
                   update_time=#{revokedAt}
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND grant_id=#{grantId}
               AND state='ACTIVE' AND grant_version=#{expectedVersion}
            """ + EXACT_SCOPE + EXACT_TASK + """
               AND CAST(grant_id AS BINARY)=CAST(#{grantId} AS BINARY)
               AND OCTET_LENGTH(grant_id)=OCTET_LENGTH(#{grantId})
               AND CAST(state AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(state)=OCTET_LENGTH('ACTIVE')
            """)
    int revoke(@Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("grantId") String grantId, @Param("expectedVersion") long expectedVersion,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestHash") String requestHash, @Param("revokedAt") long revokedAt);
}
