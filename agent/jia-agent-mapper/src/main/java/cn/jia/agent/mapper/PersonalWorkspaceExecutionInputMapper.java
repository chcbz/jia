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
             WHERE i.tenant_id=#{tenantId} AND i.client_id=#{clientId} AND i.owner_jiacn=#{ownerJiacn}
               AND i.file_id=#{fileId} AND i.grant_state='ACTIVE' AND e.execution_state='QUEUED'
             ORDER BY e.execution_id ASC LIMIT #{limit}
            """)
    List<String> listActiveExecutionIdsByFile(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("ownerJiacn") String ownerJiacn,
            @Param("fileId") String fileId, @Param("limit") int limit);
}
