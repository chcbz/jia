package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalWorkspaceExecutionInputMapper extends BaseMapper<PersonalWorkspaceExecutionInputEntity> {
    @Select("""
            SELECT * FROM agent_personal_workspace_execution_input
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId}
             ORDER BY input_ref ASC
            """)
    List<PersonalWorkspaceExecutionInputEntity> listByExecution(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution_input
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId} AND input_ref=#{inputRef}
               AND CAST(input_ref AS BINARY)=CAST(#{inputRef} AS BINARY)
               AND OCTET_LENGTH(input_ref)=OCTET_LENGTH(#{inputRef})
             LIMIT 1
            """)
    PersonalWorkspaceExecutionInputEntity findByReference(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId, @Param("inputRef") String inputRef);

    @Select("""
            SELECT * FROM agent_personal_workspace_execution_input
             WHERE tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn}
               AND execution_id=#{executionId} AND input_ref=#{inputRef}
               AND CAST(input_ref AS BINARY)=CAST(#{inputRef} AS BINARY)
               AND OCTET_LENGTH(input_ref)=OCTET_LENGTH(#{inputRef})
             LIMIT 1 FOR UPDATE
            """)
    PersonalWorkspaceExecutionInputEntity lockByReference(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("executionId") String executionId, @Param("inputRef") String inputRef);

    @Select("""
            SELECT DISTINCT e.execution_id
              FROM agent_personal_workspace_execution e
              INNER JOIN agent_personal_workspace_execution_input i ON i.execution_id=e.execution_id
               AND i.tenant_id=e.tenant_id AND i.client_id=e.client_id AND i.owner_jiacn=e.owner_jiacn
               AND CAST(i.execution_id AS BINARY)=CAST(e.execution_id AS BINARY)
               AND OCTET_LENGTH(i.execution_id)=OCTET_LENGTH(e.execution_id)
               AND CAST(i.tenant_id AS BINARY)=CAST(e.tenant_id AS BINARY)
               AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(e.tenant_id)
               AND CAST(i.client_id AS BINARY)=CAST(e.client_id AS BINARY)
               AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(e.client_id)
               AND CAST(i.owner_jiacn AS BINARY)=CAST(e.owner_jiacn AS BINARY)
               AND OCTET_LENGTH(i.owner_jiacn)=OCTET_LENGTH(e.owner_jiacn)
             WHERE i.tenant_id=#{tenantId} AND i.client_id=#{clientId} AND i.owner_jiacn=#{ownerJiacn}
               AND i.file_id=#{fileId} AND i.grant_state='ACTIVE' AND e.execution_state='QUEUED'
               AND CAST(i.tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
               AND OCTET_LENGTH(i.tenant_id)=OCTET_LENGTH(#{tenantId})
               AND CAST(i.client_id AS BINARY)=CAST(#{clientId} AS BINARY)
               AND OCTET_LENGTH(i.client_id)=OCTET_LENGTH(#{clientId})
               AND CAST(i.owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
               AND OCTET_LENGTH(i.owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
               AND CAST(i.file_id AS BINARY)=CAST(#{fileId} AS BINARY)
               AND OCTET_LENGTH(i.file_id)=OCTET_LENGTH(#{fileId})
               AND CAST(i.grant_state AS BINARY)=CAST('ACTIVE' AS BINARY)
               AND OCTET_LENGTH(i.grant_state)=OCTET_LENGTH('ACTIVE')
               AND CAST(e.execution_state AS BINARY)=CAST('QUEUED' AS BINARY)
               AND OCTET_LENGTH(e.execution_state)=OCTET_LENGTH('QUEUED')
             ORDER BY e.execution_id ASC LIMIT #{limit}
            """)
    List<String> listActiveExecutionIdsByFile(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId, @Param("limit") int limit);
}
