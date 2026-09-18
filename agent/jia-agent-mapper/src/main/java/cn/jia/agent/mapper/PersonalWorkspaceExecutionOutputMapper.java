package cn.jia.agent.mapper;

import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface PersonalWorkspaceExecutionOutputMapper extends BaseMapper<PersonalWorkspaceExecutionOutputEntity> {
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
