package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PersonalWorkspaceExecutionMapper extends BaseMapper<PersonalWorkspaceExecutionEntity> {

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND target_agent_id=#{targetAgentId} AND execution_state='QUEUED'
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(target_agent_id AS BINARY)=CAST(#{targetAgentId} AS BINARY)
               AND OCTET_LENGTH(target_agent_id)=OCTET_LENGTH(#{targetAgentId})
             ORDER BY created_at ASC, execution_id ASC LIMIT #{limit}
            """)
    java.util.List<PersonalWorkspaceExecutionEntity> listQueuedByTarget(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("targetAgentId") String targetAgentId,
            @Param("limit") int limit);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{executionId})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND idempotency_key=#{idempotencyKey}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByIdempotency(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND revoke_idempotency_key=#{idempotencyKey}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(revoke_idempotency_key AS BINARY)=CAST(#{idempotencyKey} AS BINARY)
               AND OCTET_LENGTH(revoke_idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByRevokeIdempotency(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND run_id=#{runId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
               AND CAST(run_id AS BINARY)=CAST(#{runId} AS BINARY)
               AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{runId})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionEntity findByTaskRun(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND task_id=#{taskId} AND run_id=#{runId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(task_id)=OCTET_LENGTH(#{taskId})
               AND CAST(run_id AS BINARY)=CAST(#{runId} AS BINARY)
               AND OCTET_LENGTH(run_id)=OCTET_LENGTH(#{runId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionEntity lockByTaskRun(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("taskId") String taskId, @Param("runId") String runId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
               AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(execution_id AS BINARY)=CAST(#{executionId} AS BINARY)
               AND OCTET_LENGTH(execution_id)=OCTET_LENGTH(#{executionId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionEntity lockExact(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);
}
