package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalWorkspaceExecutionOutputMapper extends BaseMapper<PersonalWorkspaceExecutionOutputEntity> {
    @Select("""
            SELECT o.*
              FROM agent_personal_workspace_execution_output o
              JOIN agent_personal_workspace_execution e
                ON e.tenant_id=o.tenant_id AND e.client_id=o.client_id
               AND e.owner_jiacn=o.owner_jiacn AND e.execution_id=o.execution_id
             WHERE o.tenant_id=#{tenantId} AND o.client_id=#{clientId}
               AND o.owner_jiacn=#{ownerJiacn} AND e.task_id=#{taskId}
               AND e.execution_mode='TASK' AND e.execution_state='OUTPUT_COMMITTED'
               AND o.formal_delivery_id=#{formalDeliveryId} AND o.output_id=#{outputId}
               AND o.workspace_file_id=#{workspaceFileId}
               AND o.workspace_file_version=#{workspaceFileVersion}
               AND o.output_state='COMMITTED' AND o.publication_state='PUBLISHED'
               AND CAST(e.execution_mode AS BINARY)=CAST('TASK' AS BINARY)
               AND OCTET_LENGTH(e.execution_mode)=OCTET_LENGTH('TASK')
               AND CAST(e.execution_state AS BINARY)=CAST('OUTPUT_COMMITTED' AS BINARY)
               AND OCTET_LENGTH(e.execution_state)=OCTET_LENGTH('OUTPUT_COMMITTED')
               AND CAST(o.output_state AS BINARY)=CAST('COMMITTED' AS BINARY)
               AND OCTET_LENGTH(o.output_state)=OCTET_LENGTH('COMMITTED')
               AND CAST(o.publication_state AS BINARY)=CAST('PUBLISHED' AS BINARY)
               AND OCTET_LENGTH(o.publication_state)=OCTET_LENGTH('PUBLISHED')
               AND CAST(o.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(o.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(o.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(o.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(o.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(o.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(e.task_id AS BINARY)=CAST(#{taskId} AS BINARY)
               AND OCTET_LENGTH(e.task_id)=OCTET_LENGTH(#{taskId})
               AND CAST(o.formal_delivery_id AS BINARY)=CAST(#{formalDeliveryId} AS BINARY)
               AND OCTET_LENGTH(o.formal_delivery_id)=OCTET_LENGTH(#{formalDeliveryId})
               AND CAST(o.output_id AS BINARY)=CAST(#{outputId} AS BINARY)
               AND OCTET_LENGTH(o.output_id)=OCTET_LENGTH(#{outputId})
               AND CAST(o.workspace_file_id AS BINARY)=CAST(#{workspaceFileId} AS BINARY)
               AND OCTET_LENGTH(o.workspace_file_id)=OCTET_LENGTH(#{workspaceFileId})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionOutputEntity findPublishedReworkSource(
            @Param("tenantId") String tenantId, @Param("clientId") String clientId,
            @Param("ownerJiacn") String ownerJiacn, @Param("taskId") String taskId,
            @Param("formalDeliveryId") String formalDeliveryId, @Param("outputId") String outputId,
            @Param("workspaceFileId") String workspaceFileId,
            @Param("workspaceFileVersion") int workspaceFileVersion);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution_output
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId} AND output_id=#{outputId}
               AND CAST(output_id AS BINARY)=CAST(#{outputId} AS BINARY)
               AND OCTET_LENGTH(output_id)=OCTET_LENGTH(#{outputId})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionOutputEntity lockByOutputId(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId, @Param("outputId") String outputId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution_output
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
             ORDER BY output_id ASC FOR UPDATE
            """)
    List<PersonalWorkspaceExecutionOutputEntity> lockByExecution(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);
}
