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
