package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentTaskArtifactEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentTaskArtifactMapper extends BaseMapper<AgentTaskArtifactEntity> {
    @Select("""
            SELECT *
            FROM agent_task_artifact
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND task_id = #{taskId}
              AND artifact_id = #{artifactId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(task_id AS BINARY) = CAST(#{taskId} AS BINARY)
              AND OCTET_LENGTH(task_id) = OCTET_LENGTH(#{taskId})
              AND CAST(artifact_id AS BINARY) = CAST(#{artifactId} AS BINARY)
              AND OCTET_LENGTH(artifact_id) = OCTET_LENGTH(#{artifactId})
            ORDER BY artifact_version DESC, id DESC
            LIMIT 1
            FOR UPDATE
            """)
    AgentTaskArtifactEntity selectLatestVersionForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("taskId") String taskId,
            @Param("artifactId") String artifactId);
}
